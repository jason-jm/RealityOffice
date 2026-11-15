package com.therealityteam.aeropages.ui

import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.base.SemanticLabelType
import com.pico.spatial.sense.base.TrackingState
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneOrientation
import com.pico.spatial.sense.plane.PlaneTrackingManager
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.platform.Diagnostics
import com.therealityteam.aeropages.platform.WallMath

/** Id of the Stage that hosts the spatial slide show (declared in Main.kt, opened from the Slides ribbon). */
const val SPATIAL_SHOW_STAGE = "AeroShowStage"

private const val TAG = "AeroSpatial"
private const val PANEL_ID = "show-panel"
private const val SPATIAL_DATA = "com.picovr.permission.SPATIAL_DATA"
/** Panel pixels: 16:9; 720p keeps the panel's composition and upload cheap while staying legible on a wall. */
private const val PANEL_W = 1280
private const val PANEL_H = 720

/**
 * The spatial slide show: a Mixed (passthrough) Stage whose only content is the slide show as a
 * 2D panel entity. While the Stage is open, plane tracking looks for a real wall; the panel is
 * moved onto the first sizeable vertical WALL plane and scaled to it, and otherwise floats in
 * front of the user. The main window keeps being the presenter console through [ShowSession].
 */
@Composable
internal fun SpatialShowStage() {
    val navigator = LocalSpatialNavigator.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // The Stage's content is composed while its container is still hidden (at app start), so
    // everything that costs — the panel, plane tracking — waits until the Stage is actually shown.
    var shown by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            Log.i(TAG, "stage lifecycle $event")
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> shown = true
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> shown = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val active = ShowSession.active
    val editor = ShowSession.editor
    // Show ended from the console while the stage is up → close it (a hidden stage is left alone).
    LaunchedEffect(active, shown) { if (shown && !active) runCatching { navigator.closeStage() } }
    if (!active || editor == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val canTrackWalls = rememberWallTrackingPermission(shown)
    if (!shown) {
        Box(Modifier.fillMaxSize())
        return
    }
    LaunchedEffect(editor, canTrackWalls) { ShowSession.reportWallPermission(editor, canTrackWalls) }
    val converter = LocalPhysicalLengthConverter.current
    // The unscaled panel width in metres, so the placer can fit the panel to a wall.
    val panelWidthM = remember(converter) {
        runCatching { with(converter) { converter.dpToLength(PANEL_W.toDp(), LengthUnit.Meters) } }.getOrDefault(1f).takeIf { it > 0.05f } ?: 1f
    }
    val placer = remember { WallPlacer() }
    placer.panelWidthM = panelWidthM
    DisposableEffect(placer, canTrackWalls) {
        if (canTrackWalls) placer.start()
        onDispose { if (canTrackWalls) placer.stop() }
    }
    SpatialView(
        modifier = Modifier.fillMaxSize(),
        initial = { content, attachments ->
            val root = Entity()
            attachments.entity(PANEL_ID)?.let { panel ->
                root.addChild(panel)
                placer.attach(root, panel)
            }
            content.addEntity(root)
        },
        attachments = {
            AttachmentPanel(id = PANEL_ID, size = IntSize(PANEL_W, PANEL_H)) {
                // Ending the show (console or Esc in the panel) stops the session; the effect above closes the stage.
                SlideShow(editor, fromStart = false, mirror = true, onExit = { ShowSession.stop() })
            }
        },
    )
}

/** A hidden Stage is also composed. Ask once per presentation, after the Stage becomes visible. */
@Composable
private fun rememberWallTrackingPermission(shown: Boolean): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.checkSelfPermission(SPATIAL_DATA) == PackageManager.PERMISSION_GRANTED) }
    var requested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(shown) {
        if (shown) {
            granted = context.checkSelfPermission(SPATIAL_DATA) == PackageManager.PERMISSION_GRANTED
            if (!granted && !requested) {
                requested = true
                launcher.launch(SPATIAL_DATA)
            }
        }
    }
    return granted
}

/**
 * Keeps the show panel on a real wall. Plane anchors come from [PlaneTrackingManager] (Full Space
 * only); the best sizeable vertical WALL plane wins and the panel entity is moved onto it straight
 * from the callback — TransformComponent writes, no UI state in the loop — and scaled to about 85%
 * of the wall's width. Without plane data (capability missing, nothing detected) the panel keeps
 * its floating default in front of the user. [stop] releases the subscription and the tracker.
 */
internal class WallPlacer {
    private var root: Entity? = null
    private var panel: Entity? = null
    /** Cancels the anchor subscription; captured as a lambda so the SDK's subscription type is not named here. */
    private var cancelSubscription: (() -> Unit)? = null
    private var chosen: java.util.UUID? = null
    private var chosenScore = Float.NEGATIVE_INFINITY
    private val startedAt = System.nanoTime()
    private val main = Handler(Looper.getMainLooper())
    var panelWidthM = 1f

    fun attach(root: Entity, panel: Entity) {
        this.root = root; this.panel = panel
        floatInFront()
    }

    fun start() {
        val t0 = System.nanoTime()
        runCatching {
            val sub = PlaneTrackingManager.subscribeAnchorUpdate { update -> onMain { if (cancelSubscription != null) onUpdate(update) } }
            cancelSubscription = { sub.cancel() }
            PlaneTrackingManager.start()
            Log.i(TAG, "plane tracking started: ${PlaneTrackingManager.state} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }.onFailure { Diagnostics.log("spatial-show", "plane tracking unavailable", it) }
    }

    fun stop() {
        runCatching { cancelSubscription?.invoke() }
        cancelSubscription = null
        runCatching { if (PlaneTrackingManager.state == TrackingState.RUNNING) PlaneTrackingManager.stop() }
        chosen = null
        chosenScore = Float.NEGATIVE_INFINITY
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun onUpdate(update: AnchorUpdate<PlaneAnchor>) {
        val anchor = update.anchor
        if (update.event == AnchorUpdate.Event.REMOVED) {
            if (chosen == anchor.anchorUUID) { chosen = null; chosenScore = Float.NEGATIVE_INFINITY; floatInFront() }
            return
        }
        if (!isWall(anchor)) return
        val p = anchor.transform.position
        val score = WallMath.score(p.x, p.z)
        // Settle on the best wall seen in the first seconds; later a chosen wall only follows its own updates.
        val early = System.nanoTime() - startedAt < 8_000_000_000L
        if (chosen == null || chosen == anchor.anchorUUID || (early && score > chosenScore + 0.15f)) {
            chosen = anchor.anchorUUID; chosenScore = score
            place(anchor)
        }
    }

    private fun isWall(a: PlaneAnchor): Boolean =
        a.planeOrientation == PlaneOrientation.VERTICAL && a.semantics in WALL_LIKE &&
            WallMath.bigEnough(a.boundingBoxSize.x, a.boundingBoxSize.y)

    /** Wall normal in world space pointing into the room, from the plane's own vertices (convention-free). */
    private fun normalOf(a: PlaneAnchor): Vector3? {
        val axis = WallMath.normalAxis(a.vertices.map { floatArrayOf(it.x, it.y, it.z) }) ?: return null
        val local = when (axis) { 0 -> Vector3(1f, 0f, 0f); 1 -> Vector3(0f, 1f, 0f); else -> Vector3(0f, 0f, 1f) }
        val world = a.transform.quaternion.rotateVector(local)
        val n = WallMath.unit(floatArrayOf(world.x, world.y, world.z)) ?: return null
        if (!WallMath.horizontal(n)) return null
        val p = a.transform.position
        val toward = WallMath.toward(n, floatArrayOf(p.x, p.y, p.z), floatArrayOf(0f, p.y, 0f))
        return Vector3(toward[0], toward[1], toward[2])
    }

    private fun place(a: PlaneAnchor) {
        val root = root ?: return
        val t = panel?.components?.get<TransformComponent>() ?: return
        val n = normalOf(a) ?: return
        // Stand 3 cm off the wall so the panel never fights the passthrough geometry.
        val world = a.transform.position + n * 0.03f
        val pos = root.convertPositionFrom(world, null)
        val rot = root.convertRotationFrom(EulerAngles(yaw = WallMath.yawDegrees(floatArrayOf(n.x, n.y, n.z))).toQuat(), null)
        val s = WallMath.fitScale(a.boundingBoxSize.x, panelWidthM)
        t.setPosition(pos)
        t.setQuaternion(rot)
        t.scaleVector = Vector3(s, s, s)
        Log.i(TAG, "show panel on ${a.semantics} ${a.anchorUUID} (${a.boundingBoxSize.x} × ${a.boundingBoxSize.y} m), scale $s")
    }

    /** Default: 1.8 m wide, 1.4 m up, 2.2 m in front of where the user started. */
    private fun floatInFront() {
        val t = panel?.components?.get<TransformComponent>() ?: return
        val s = 1.8f / panelWidthM.coerceAtLeast(0.05f)
        t.setPosition(Vector3(0f, 1.4f, -2.2f))
        t.setQuaternion(EulerAngles().toQuat())
        t.scaleVector = Vector3(s, s, s)
    }

    private companion object {
        val WALL_LIKE = setOf(SemanticLabelType.WALL, SemanticLabelType.VIRTUAL_WALL, SemanticLabelType.SCREEN, SemanticLabelType.UNKNOWN)
    }
}
