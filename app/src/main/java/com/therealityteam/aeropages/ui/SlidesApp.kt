package com.therealityteam.aeropages.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.VerticalDivider
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.doc.CompoundFileReader
import com.therealityteam.aeropages.format.DeskFiles
import com.therealityteam.aeropages.format.ppt.PptReader
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pdf.SlidesPdfExporter
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.AnimClass
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.Presentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R

internal enum class STab(val title: Int, val contextual: Boolean = false) {
    HOME(R.string.tab_home), INSERT(R.string.tab_insert), DESIGN(R.string.tab_design), TRANSITION(R.string.tab_transitions), ANIM(R.string.tab_animations),
    SHOW(R.string.tab_slide_show), REVIEW(R.string.tab_review), VIEW(R.string.tab_view),
    SHAPE_FMT(R.string.tab_shape_format, true), PICTURE_FMT(R.string.tab_picture_format, true), TABLE_DESIGN(R.string.tab_table_design, true), TABLE_LAYOUT(R.string.tab_table_layout, true), CHART_DESIGN(R.string.tab_chart_design, true);
}

/** Dev hook (debug builds only): `adb shell setprop debug.aero.tab design|anim|...` names a ribbon tab to open on launch (any module). */
@SuppressLint("PrivateApi")
internal fun devTabName(): String = if (!com.therealityteam.aeropages.BuildConfig.DEBUG) "" else try {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "debug.aero.tab") as? String ?: ""
} catch (e: Throwable) { "" }

internal fun devInitialTab(): STab = STab.entries.firstOrNull { it.name.equals(devTabName(), true) } ?: STab.HOME
/**
 * Cross-window slideshow session: the Slides module hands its editor to the dedicated
 * "show" WindowContainer (Compose snapshot state propagates across compositions, so
 * navigation in either window stays in sync).
 */
object ShowSession {
    var editor by mutableStateOf<SlideEditor?>(null)
        private set
    var active by androidx.compose.runtime.mutableStateOf(false)
        private set
    var wallTrackingAllowed by mutableStateOf<Boolean?>(null)
        private set
    /** Mirrored by the show window: current slide, build step and elapsed seconds (read by the presenter console). */
    var index by androidx.compose.runtime.mutableIntStateOf(0)
    var step by androidx.compose.runtime.mutableIntStateOf(0)
    var elapsedSec by androidx.compose.runtime.mutableIntStateOf(0)
    /** Navigation requests from the presenter console; the show window acts on each change of [navSeq]. */
    var navSeq by androidx.compose.runtime.mutableIntStateOf(0)
        private set
    var navDir = 0
        private set
    var stopRequested by androidx.compose.runtime.mutableStateOf(false)
        private set
    fun requestNext() { navDir = 1; navSeq++ }
    fun requestPrev() { navDir = -1; navSeq++ }
    fun requestStop() { stopRequested = true }
    fun start(e: SlideEditor) {
        wallTrackingAllowed = null
        editor = e; index = e.current; step = 0; elapsedSec = 0; navSeq = 0; navDir = 0; stopRequested = false; active = true
    }
    fun reportWallPermission(owner: SlideEditor, granted: Boolean) {
        if (editor === owner) wallTrackingAllowed = granted
    }
    fun stop() {
        active = false
        editor = null
        wallTrackingAllowed = null
    }
}

/** Dev hook (debug builds only): `adb shell setprop debug.aero.dialog open|save|filemenu` to auto-open a dialog on launch. */
@SuppressLint("PrivateApi")
internal fun devDialogProp(): String = if (!com.therealityteam.aeropages.BuildConfig.DEBUG) "" else try {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, "debug.aero.dialog") as? String ?: ""
} catch (e: Throwable) { "" }

/** Dev hook (debug builds only): `adb shell setprop debug.aero.export pdf` to auto-export a PDF on launch (verification). */
@SuppressLint("PrivateApi")
internal fun devExportProp(): String = if (!com.therealityteam.aeropages.BuildConfig.DEBUG) "" else try {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, "debug.aero.export") as? String ?: ""
} catch (e: Throwable) { "" }
/** Dev hook (debug builds only): `adb shell setprop debug.aero.page N` scrolls the Pages canvas to page N on launch (verification). */
@SuppressLint("PrivateApi")
internal fun devPageProp(): String = if (!com.therealityteam.aeropages.BuildConfig.DEBUG) "" else try {
    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, "debug.aero.page") as? String ?: ""
} catch (e: Throwable) { "" }

/** Dev hook (debug builds only): `adb shell setprop debug.aero.step N` opens the show at click N of its slide, without motion (screenshots). */
@SuppressLint("PrivateApi")
internal fun devStepProp(): Int? = if (!com.therealityteam.aeropages.BuildConfig.DEBUG) null else try {
    (Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
        .invoke(null, "debug.aero.step") as? String)?.toIntOrNull()
} catch (e: Throwable) { null }

internal enum class SPicker { NONE, TEXTCOLOR, BACKGROUND, FONT, SIZE, HIGHLIGHT, CASE, CHARSPACING, LINESPACING, LAYOUT, NEWSLIDE, ARRANGE, ALIGNTEXT, SHAPEFILL, SHAPEBORDER, SHAPESHADOW, SYMBOL, SMARTART, SLIDESIZE, SHAPES, CUSTOMSHOW, THEMES, ZOOM, REPLACEMENU, SELECTMENU, TABLESHADING, TABLEBORDERS, PENSTYLE, PENWEIGHT, PENCOLOR }

/** Design theme presets: name, background, title colour, body colour, accent (for the swatch strip). */
internal data class SlideTheme(val name: String, val bg: Long, val title: Long, val body: Long, val accent: Long)
internal val SLIDE_THEMES get() = listOf(
    SlideTheme("office", 0xFFFFFFFF, 0xFF1A1A1A, 0xFF404040, 0xFF2B579A),
    SlideTheme(Strings.get(R.string.theme_gallery), 0xFFFAFAFA, 0xFF202020, 0xFF505050, 0xFFE8A33D),
    SlideTheme(Strings.get(R.string.theme_ribbon), 0xFF1B3A2B, 0xFFFFFFFF, 0xFFD8E8DD, 0xFF4BAA7A),
    SlideTheme(Strings.get(R.string.theme_dividend), 0xFFB91C1C, 0xFFFFFFFF, 0xFFFFE0E0, 0xFFFFC83D),
    SlideTheme(Strings.get(R.string.theme_flat), 0xFFFFFFFF, 0xFF2B2B2B, 0xFF555555, 0xFFD23F6F),
    SlideTheme(Strings.get(R.string.theme_slice), 0xFFEDEDED, 0xFF333333, 0xFF606060, 0xFFE8A33D),
    SlideTheme(Strings.get(R.string.theme_newspaper), 0xFFF2EFE6, 0xFF2A2A2A, 0xFF4A4A4A, 0xFF8A7A5A),
    SlideTheme(Strings.get(R.string.theme_purple), 0xFF3B1E5E, 0xFFFFFFFF, 0xFFE9D8F5, 0xFFD23F9F),
    SlideTheme(Strings.get(R.string.theme_cyan), 0xFF0E7C7B, 0xFFFFFFFF, 0xFFD0F0EF, 0xFF17A2A0),
    SlideTheme(Strings.get(R.string.theme_dark), 0xFF1F1F1F, 0xFFFFFFFF, 0xFFD0D0D0, 0xFF4A90D9),
    SlideTheme(Strings.get(R.string.theme_blue), 0xFF2B579A, 0xFFFFFFFF, 0xFFD6E2F5, 0xFF8AB4F8),
    SlideTheme(Strings.get(R.string.theme_beige), 0xFFFFF2CC, 0xFF5B4636, 0xFF6B5646, 0xFFC9A227),
)

/** An animation effect's name, as the Animations gallery and the Animation Pane show it. */
internal fun animName(e: AnimEffect): String = Strings.get(when (e) {
    AnimEffect.APPEAR -> R.string.anim_appear
    AnimEffect.FADE -> R.string.anim_fade
    AnimEffect.FLY_IN -> R.string.anim_fly_in
    AnimEffect.FLOAT_IN -> R.string.anim_float_in
    AnimEffect.SPLIT, AnimEffect.SPLIT_OUT -> R.string.anim_split
    AnimEffect.WIPE, AnimEffect.WIPE_OUT -> R.string.anim_wipe
    AnimEffect.ZOOM, AnimEffect.ZOOM_OUT -> R.string.anim_zoom
    AnimEffect.GROW -> R.string.anim_grow_turn
    AnimEffect.BOUNCE -> R.string.anim_bounce
    AnimEffect.SPIN -> R.string.anim_spinner
    AnimEffect.PULSE -> R.string.anim_pulse
    AnimEffect.TEETER -> R.string.anim_teeter
    AnimEffect.ROTATE -> R.string.anim_spin
    AnimEffect.GROW_SHRINK -> R.string.anim_grow_shrink
    AnimEffect.TRANSPARENCY -> R.string.anim_transparency
    AnimEffect.DISAPPEAR -> R.string.anim_disappear
    AnimEffect.FADE_OUT -> R.string.anim_fade_out
    AnimEffect.FLY_OUT -> R.string.anim_fly_out
    AnimEffect.FLOAT_OUT -> R.string.anim_float_out
    AnimEffect.SHRINK_TURN -> R.string.anim_shrink_turn
})

/** PowerPoint's colour for an effect class: green entrances, gold emphasis, red exits. */
internal fun animClassColor(c: AnimClass): Color = when (c) {
    AnimClass.ENTRANCE -> Color(0xFF3FA34D)
    AnimClass.EMPHASIS -> Color(0xFFD9A400)
    AnimClass.EXIT -> Color(0xFFD9412B)
}

/** Table style presets (header, band, plain and border colours), by styleIndex: shared with the show and the PDF. */
internal typealias TableStyle = com.therealityteam.aeropages.model.SlideTableStyle
internal val TABLE_STYLES: List<TableStyle> get() = com.therealityteam.aeropages.model.SlideTableLook.STYLES

/** Chart palette sets (a list of series colours each). */
internal val CHART_PALETTES = com.therealityteam.aeropages.model.ChartStyle.PALETTES

internal val SLIDE_BG_COLORS = listOf(0xFFFFFFFF, 0xFF1F1F1F, 0xFF2B579A, 0xFF217346, 0xFFC43E1C, 0xFFFFF2CC, 0xFFE3F2FD, 0xFFF3E5F5)

/** Format Background → Gradient fill's presets: each a background colour and the colour it shades to. */
internal val SLIDE_BG_GRADIENTS = listOf(
    0xFFDEEBF7 to 0xFFFFFFFF, 0xFFFFFFFF to 0xFFD9D9D9, 0xFF2E75B6 to 0xFF1F3864,
    0xFF404040 to 0xFF0D0D0D, 0xFFFFE699 to 0xFFF4B183, 0xFFE2F0D9 to 0xFFA9D18E,
)

/** Slides (PowerPoint) module: navigator + slide canvas + ribbon + slideshow. */
@Composable
fun SlidesApp(openPath: String? = null, template: DeskTemplate? = null, windowId: String = OpenRequests.MAIN, onHome: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = remember { SlideEditor().also { it.reviewer = loadReviewer(context) } }
    val workDir = remember { (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() } }
    val navigator = com.pico.spatial.ui.platform.containers.LocalSpatialNavigator.current
    val session = remember {
        DocumentSession(
            context, scope, workDir, com.therealityteam.aeropages.format.DeskKind.SLIDES, "pptx",
            listOf("pptx", "odp"), ".draft-slides.pptx", Strings.get(R.string.presentation_label),
            object : DocumentSession.Adapter<Presentation> {
                override val dirty: Boolean get() = editor.dirty
                override val editVersion: Long get() = editor.saveState.version
                override val sourcePath: String? get() = editor.presentation.sourcePath
                override fun snapshot(): Presentation = editor.presentation.copyOf()
                override fun live(): Presentation = editor.presentation
                override fun write(model: Presentation, out: java.io.OutputStream, ext: String) =
                    if (ext == "odp") com.therealityteam.aeropages.format.odf.OdpWriter.write(model, out) else PptxWriter.write(model, out)
                override fun read(path: String): Presentation = readDeck(path)
                override fun load(model: Presentation, recovered: Boolean) = editor.load(model, recovered)
                override fun detach(model: Presentation) { model.sourcePath = null }
                override fun markSaved(path: String, clean: Boolean) = editor.markSaved(path, clean)
                override fun markSaved() = editor.markSaved()
            },
            windowId = windowId,
        )
    }

    var tab by remember { mutableStateOf(devInitialTab()) }
    var picker by remember { mutableStateOf(SPicker.NONE) }
    var showImage by remember { mutableStateOf(false) }
    var showNotes by rememberLayoutFlag("slides.notes")
    var slideshow by remember { mutableStateOf(false) }
    var slideshowFromStart by remember { mutableStateOf(true) }
    // A custom show picked to play (Custom Slide Show ▾), and a show started in presenter view.
    var slideshowCustom by remember { mutableStateOf<String?>(null) }
    var slideshowPresenter by remember { mutableStateOf(false) }
    var showCustomShows by remember { mutableStateOf(false) }
    // Insert → Symbol → More Symbols… (`dialog symbols`).
    var showSymbols by remember { mutableStateOf(devDialogProp() == "symbols") }
    // File → Info (`dialog fileinfo`).
    var showInfo by remember { mutableStateOf(devDialogProp() == "fileinfo") }
    // Insert → Video (true) / Audio (false): the files to pick from.
    var insertMedia by remember { mutableStateOf<Boolean?>(null) }
    var mediaFiles by remember { mutableStateOf(emptyList<FileEntry>()) }
    var showAutoPlay by remember { mutableStateOf(false) }
    var rehearseMode by remember { mutableStateOf(false) }
    var chart3DPreview by remember { mutableStateOf(false) }
    var showShowSetup by remember { mutableStateOf(false) }
    var showLink by remember { mutableStateOf(false) }
    // The link dialog opens on "Place in this presentation" (dev hook `slidelinks`).
    var linkInShow by remember { mutableStateOf(false) }
    // Insert → Equation: the LaTeX it opens on, and the equation box it edits (null: a new one).
    var slideEquation by remember { mutableStateOf<Pair<String, com.therealityteam.aeropages.model.SlideBox?>?>(null) }
    fun openEquation() {
        val box = editor.focusedBox()
        val eq = editor.focusedEquation
        slideEquation = if (box != null && eq != null) com.therealityteam.aeropages.model.MathLatex.write(eq.math) to box else "" to null
    }
    var showFind by remember { mutableStateOf(false) }
    var findReplace by remember { mutableStateOf(devDialogProp() == "slidereplace") }
    var showProofing by remember { mutableStateOf(false) }
    var showOverview by remember { mutableStateOf(false) }
    var outlineOpen by remember { mutableStateOf(false) }
    var showOutlineImport by remember { mutableStateOf(false) }
    var showComments by rememberLayoutFlag("slides.comments")
    var showTableDialog by remember { mutableStateOf(false) }
    var showChartDialog by remember { mutableStateOf(false) }
    var showHeaderFooter by remember { mutableStateOf(false) }
    var showSectionName by remember { mutableStateOf(false) }
    var showSlideMaster by remember { mutableStateOf(false) }
    var showPdfExport by remember { mutableStateOf(false) }
    var showChartData by remember { mutableStateOf(false) }
    var showRuler by rememberLayoutFlag("slides.ruler")
    var showGridlines by rememberLayoutFlag("slides.gridlines")
    var showGuides by rememberLayoutFlag("slides.guides")
    var animPaneOpen by remember { mutableStateOf(false) }
    var designerOpen by remember { mutableStateOf(false) }
    // The task pane in front of the right-hand pane area (the one opened last).
    // The Office Clipboard pane (Home ▸ Clipboard's launcher; dev hook `clipboardpane`), for this session only.
    var clipboardOpen by remember { mutableStateOf(devDialogProp() == "clipboardpane") }
    // Check Accessibility from the status bar.
    var a11yOpen by remember { mutableStateOf(false) }
    if (a11yOpen) SlidesAccessibility(editor) { a11yOpen = false }
    // The Selection Pane (Home ▸ Arrange, Alt+F10; dev hook `selectionpane`), for this session only.
    var selectionOpen by remember { mutableStateOf(devDialogProp() == "selectionpane") }
    // Reuse Slides (Home ▸ New Slide ▾; dev hook `reuseslides` opens it on the deck's own file): the deck being reused,
    // read on IO, and the browser that picks it.
    var reuseOpen by remember { mutableStateOf(devDialogProp() in setOf("reuseslides", "reuseinsert")) }
    var reuseDeck by remember { mutableStateOf<Presentation?>(null) }
    var reusePath by remember { mutableStateOf<String?>(null) }
    var reuseBrowse by remember { mutableStateOf(false) }
    var frontSlidePane by remember { mutableStateOf(if (reuseOpen) SlideTaskPane.REUSE else if (selectionOpen) SlideTaskPane.SELECTION else if (clipboardOpen) SlideTaskPane.CLIPBOARD else SlideTaskPane.COMMENTS) }
    fun paneOpen(p: SlideTaskPane) = when (p) {
        SlideTaskPane.DESIGNER -> designerOpen; SlideTaskPane.ANIMATION -> animPaneOpen; SlideTaskPane.COMMENTS -> showComments; SlideTaskPane.CLIPBOARD -> clipboardOpen
        SlideTaskPane.SELECTION -> selectionOpen
        SlideTaskPane.REUSE -> reuseOpen
    }
    fun shownSlidePane(): SlideTaskPane? = frontSlidePane.takeIf(::paneOpen) ?: SlideTaskPane.entries.lastOrNull(::paneOpen)
    // A toggle: a pane open but behind another comes forward; otherwise it opens (in front) or closes.
    fun toggleSlidePane(p: SlideTaskPane, open: Boolean, set: (Boolean) -> Unit) {
        if (open && shownSlidePane() != p) frontSlidePane = p else { set(!open); if (!open) frontSlidePane = p }
    }
    // Reuse Slides: the chosen deck read on IO (a deck that cannot be read says so in the status bar).
    fun loadReuse(path: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { readDeck(path) } }
                .onSuccess { reuseDeck = it; reusePath = path }
                .onFailure { session.status = Strings.get(R.string.reuse_open_failed) }
        }
    }
    // Dev hooks `reuseslides` / `reuseinsert`: the pane on a `reuse-source.pptx` beside this deck (else this deck's own
    // file), once it has loaded; `reuseinsert` then inserts its 2nd and 5th slides keeping their source formatting.
    if (devDialogProp() in setOf("reuseslides", "reuseinsert")) LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000)
        val own = editor.presentation.sourcePath ?: return@LaunchedEffect
        loadReuse(java.io.File(java.io.File(own).parentFile, "reuse-source.pptx").takeIf { it.exists() }?.path ?: own)
        if (devDialogProp() == "reuseinsert") {
            val deck = snapshotFlow { reuseDeck }.filterNotNull().first()
            editor.reuseSlides(deck, listOf(1, 4), keepSource = true)
        }
    }
    var zoomPct by rememberLayoutInt("slides.zoom", 100)
    var showShortcuts by remember { mutableStateOf(false) }
    var images by remember { mutableStateOf(listImages(workDir)) }
    val tooltipHost = remember { TooltipHost() }

    // 投影到墙面: the show opens in a passthrough Stage that snaps onto a detected wall; when the
    // stage is refused (policy, capability) the detached show window stands in.
    var spatialShowStarted by remember { mutableStateOf(false) }
    LaunchedEffect(ShowSession.active, ShowSession.editor, ShowSession.wallTrackingAllowed) {
        if (!spatialShowStarted) return@LaunchedEffect
        if (!ShowSession.active || ShowSession.editor !== editor) {
            spatialShowStarted = false
            session.status = Strings.get(R.string.status_show_ended)
        } else {
            ShowSession.wallTrackingAllowed?.let { granted ->
                session.status = Strings.get(if (granted) R.string.status_space_show else R.string.status_space_show_floating)
            }
        }
    }
    val startSpatialShow: () -> Unit = {
        spatialShowStarted = true
        ShowSession.start(editor)
        scope.launch {
            val result = runCatching { navigator.openStage(SPATIAL_SHOW_STAGE, style = com.pico.spatial.ui.platform.containers.StageStyle.Mixed) }
                .getOrElse { com.pico.spatial.ui.platform.containers.OpenStageResult.Error(-1, it.message ?: it.javaClass.simpleName) }
            if (result !is com.pico.spatial.ui.platform.containers.OpenStageResult.Allowed && ShowSession.editor === editor) {
                com.therealityteam.aeropages.platform.Diagnostics.log("spatial-show", "openStage refused: $result")
                navigator.openWindowContainer("show")
                session.status = Strings.get(R.string.msg_space_show_unavailable)
            }
        }
    }

    session.Effects()

    // Dev hook: `setprop debug.aero.page N` selects slide N of the opened deck (emulator screenshots).
    LaunchedEffect(editor.presentation) {
        devPageProp().toIntOrNull()?.let { n -> if (n in 1..editor.presentation.slides.size) editor.select(n - 1) }
        // `setprop debug.aero.dialog crop`: the slide's first cropped picture (else its first) selected, Crop open on it
        // (see PictureFormatTab).
        if (devDialogProp() == "crop") editor.slide.images.let { it.firstOrNull { img -> img.cropped } ?: it.firstOrNull() }?.let { editor.selectImage(it) }
        // `setprop debug.aero.dialog shapeoutline`: the slide's first line (else shape) selected, Shape Outline open on it.
        if (devDialogProp() == "shapeoutline") (editor.slide.shapes.firstOrNull { it.type == com.therealityteam.aeropages.model.ShapeType.LINE } ?: editor.slide.shapes.firstOrNull())?.let {
            editor.selectShape(it); picker = SPicker.SHAPEBORDER
        }
        // `setprop debug.aero.dialog shapeshadow`: the slide's first shape selected, Shadow open on it.
        if (devDialogProp() == "shapeshadow") editor.slide.shapes.firstOrNull()?.let { editor.selectShape(it); picker = SPicker.SHAPESHADOW }
        // `setprop debug.aero.dialog picture`: the slide's first picture selected (Picture Format shows).
        if (devDialogProp() == "picture") editor.slide.images.firstOrNull()?.let { editor.selectImage(it) }
        // `setprop debug.aero.dialog background`: Format Background open (with `debug.aero.tab design`).
        if (devDialogProp() == "background") picker = SPicker.BACKGROUND
        // `setprop debug.aero.dialog headerfooter`: Insert → Header & Footer open.
        if (devDialogProp() == "headerfooter") showHeaderFooter = true
        // `setprop debug.aero.dialog shapefill`: the slide's first shape with a gradient (else its first) selected, Shape Fill open on it.
        if (devDialogProp() == "shapefill") (editor.slide.shapes.firstOrNull { it.fillGradient != null && it.gradientKind != com.therealityteam.aeropages.model.GradientKind.LINEAR } ?: editor.slide.shapes.firstOrNull())?.let {
            editor.selectShape(it); picker = SPicker.SHAPEFILL
        }
    }

    LaunchedEffect(Unit) {
        images = listImages(workDir)
        if (openPath != null) session.openNow(openPath)
        else if (template != null && !template.blank) { editor.load(Templates().presentation(template)); session.startedFrom(template) }
        else { editor.load(Presentation.blank()); session.status = Strings.get(R.string.new_presentation) }
        session.checkDraft()
        when (devDialogProp()) {
            "open" -> session.showOpen = true; "save" -> session.showSave = true; "filemenu" -> session.showFileMenu = true
            "keeporiginal" -> session.save()
            // A designed look put on the opened deck once it has loaded (`deckbusiness`, `deckfresh`, `deckmidnight`).
            "deckbusiness", "deckfresh", "deckmidnight" -> scope.launch {
                kotlinx.coroutines.delay(4_000)
                val look = DeckDesigns.ALL.first { "deck" + it.name.lowercase() == devDialogProp() }
                editor.applyDeckDesign(DeckDesigns.indexOf(look), DeckDesigns.cover(look), DeckDesigns.content(look), look.background, look.title, look.body,
                    DeckDesigns.COVER_TITLE, DeckDesigns.COVER_BODY, DeckDesigns.NAMES)
            }
            "auxwindow" -> OpenRequests.idleSideWindow()?.let { navigator.openWindowContainer(it) }
            "showwindow" -> { ShowSession.start(editor); navigator.openWindowContainer("show") }
            "spatialshow" -> scope.launch { kotlinx.coroutines.delay(6_000); startSpatialShow() }   // after an intent-opened deck has loaded
            // Start the spatial show, then end it from the console 25 s later (exercises the stage close path).
            "spatialshowcycle" -> scope.launch { kotlinx.coroutines.delay(6_000); startSpatialShow(); kotlinx.coroutines.delay(25_000); ShowSession.requestStop() }
            "slidefind", "slidereplace" -> showFind = true
            // Review ▸ Chinese Conversion: the whole deck to Traditional characters once it has loaded (`zhtraditional`).
            "zhtraditional" -> scope.launch { kotlinx.coroutines.delay(4_000); editor.convertChinese(toTraditional = true) }
            // Commands on what is already drawn — the first picture flipped, the first table's first cell filled, the first
            // shape filled red — 5 s after the deck opens: each must show (`redrawcheck`).
            "redrawcheck" -> scope.launch {
                kotlinx.coroutines.delay(5_000)
                val si = editor.presentation.slides.indexOfFirst { it.images.isNotEmpty() || it.shapes.isNotEmpty() }.coerceAtLeast(0)
                editor.select(si)
                if (editor.slide.tables.isEmpty()) editor.addTable(2, 2)
                kotlinx.coroutines.delay(2_500)
                editor.slide.images.firstOrNull()?.let { editor.selectImage(it); editor.toggleImageFlipH(); editor.selectImage(null) }
                editor.slide.tables.firstOrNull()?.let { t -> editor.replaceText(t.cells[0][0], t.cells[0][0].text, "REDRAWN") }
                editor.slide.shapes.firstOrNull()?.let { editor.selectShape(it); editor.setShapeFill(0xFFE53935); editor.selectShape(null) }
            }
            // Review → Proofing on the opened deck (once it has loaded).
            "proofing" -> scope.launch { kotlinx.coroutines.delay(4_000); showProofing = true }
            "chart3d" -> { editor.addChart(com.therealityteam.aeropages.model.ChartType.COLUMN); chart3DPreview = true }
            "rehearse" -> { rehearseMode = true; slideshowFromStart = true; slideshow = true }
            "outline" -> outlineOpen = true
            "slidesize" -> { tab = STab.DESIGN; picker = SPicker.SLIDESIZE }
            // The opened deck turned 4:3 by Ensure Fit, numbered from 5 (once it has loaded).
            "slidefit" -> scope.launch {
                kotlinx.coroutines.delay(4_000); tab = STab.DESIGN
                editor.setSlideSize(com.therealityteam.aeropages.model.SlideSize.STANDARD, com.therealityteam.aeropages.model.SlideScale.ENSURE_FIT, 5)
            }
            // Insert → Video / Audio: the pick dialog; "addvideo" / "addaudio" put the folder's first one on the current slide.
            "insertvideo", "insertaudio" -> { tab = STab.INSERT; val video = devDialogProp() == "insertvideo"; mediaFiles = listMedia(workDir, video); insertMedia = video }
            "addvideo", "addaudio" -> scope.launch {
                kotlinx.coroutines.delay(4_000)
                listMedia(workDir, devDialogProp() == "addvideo").firstOrNull()?.let { f ->
                    withContext(Dispatchers.IO) { loadMediaForInsert(f.path) }.onSuccess { m -> tab = STab.INSERT; editor.insertMedia(m.media, m.poster, "image/png", m.widthPt, m.heightPt) }
                }
            }
            // Home's galleries: New Slide's layouts, the current slide's Layout; Arrange and Align Text for the first shape.
            "newslide" -> picker = SPicker.NEWSLIDE
            // Home ▸ Replace ▾ / Select ▾ (`replacemenu`, `selectmenu`); Select All on the first slide with shapes (`selectall`).
            "replacemenu" -> picker = SPicker.REPLACEMENU
            "selectmenu" -> picker = SPicker.SELECTMENU
            "selectall" -> scope.launch {
                kotlinx.coroutines.delay(4_000)
                editor.presentation.slides.indexOfFirst { sl -> sl.shapes.count { !it.hidden } > 1 }.takeIf { it >= 0 }?.let { editor.select(it); editor.selectAllShapes() }
            }
            // The first shape picked, its handles showing (`setprop debug.aero.dialog selectshape`).
            "selectshape" -> scope.launch { kotlinx.coroutines.delay(4_000); editor.slide.shapes.firstOrNull()?.let { editor.selectShape(it) } }
            // The contextual tabs, each on its object 4 s after the deck opens (a table or chart added when the slide has
            // none): `ctxshape`, `ctxpicture`, `ctxtable`, `ctxtablelayout`, `ctxchart`.
            "ctxshape", "ctxpicture", "alttext", "objectmenu", "ctxtable", "ctxtablelayout", "ctxchart", "tableshading", "tableborders", "contenttable", "tableselect" -> scope.launch {
                kotlinx.coroutines.delay(4_000)
                when (devDialogProp()) {
                    // A new Title and Content slide, then a table: it takes the content placeholder (no prompt left behind).
                    "contenttable" -> { editor.addSlide(com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT); editor.slide.title.paragraph.runs.first().text = "Quarterly results"; editor.addTable(4, 3) }
                    "ctxshape" -> editor.slide.shapes.firstOrNull()?.let { editor.selectShape(it); kotlinx.coroutines.delay(300); tab = STab.SHAPE_FMT }
                    "ctxpicture", "alttext", "objectmenu" -> editor.slide.images.firstOrNull()?.let { editor.selectImage(it) }
                    "ctxchart" -> { if (editor.slide.charts.isEmpty()) editor.addChart(com.therealityteam.aeropages.model.ChartType.COLUMN); editor.selectChart(editor.slide.charts.first()) }
                    else -> {
                        if (editor.slide.tables.isEmpty()) editor.addTable(3, 4)
                        editor.selectTable(editor.slide.tables.first())
                        if (devDialogProp() == "ctxtablelayout") { kotlinx.coroutines.delay(1_500); tab = STab.TABLE_LAYOUT }
                        if (devDialogProp() == "tableselect") {   // the second row picked with Select ▾ ▸ Select Row, ready to merge
                            editor.slide.tables.first().cells.getOrNull(1)?.getOrNull(0)?.let { editor.focus(it) }
                            editor.selectTableCells(SlideEditor.TableSelect.ROW)
                            kotlinx.coroutines.delay(1_500); tab = STab.TABLE_LAYOUT
                        }
                        if (devDialogProp() == "tableborders") {   // a red dashed outside drawn with the pen; then the Borders menu open
                            editor.tablePen = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.DASHED, 2.25f, 0xFFC00000)
                            editor.applyTableBorders(SlideEditor.TableBorders.OUTSIDE)
                            kotlinx.coroutines.delay(1_500); picker = SPicker.TABLEBORDERS
                        }
                        if (devDialogProp() == "tableshading") {   // a cell typed in, shaded gold; then the menu open
                            val t = editor.slide.tables.first()
                            t.cells.getOrNull(1)?.getOrNull(1)?.let { editor.focus(it); editor.setTableShading(0xFFFFC000) }
                            kotlinx.coroutines.delay(1_500); picker = SPicker.TABLESHADING
                        }
                    }
                }
            }
            "layoutgallery" -> picker = SPicker.LAYOUT
            // Insert ▸ Shapes on the current slide from the Insert tab, 4 s after opening: the ribbon must move to Shape Format.
            "insertshape" -> scope.launch { tab = STab.INSERT; kotlinx.coroutines.delay(4_000); editor.addShape(com.therealityteam.aeropages.model.ShapeType.ROUNDED) }
            "arrange", "aligntext" -> scope.launch {
                kotlinx.coroutines.delay(4_000)
                editor.slide.shapes.firstOrNull()?.let { editor.selectShape(it) }
                picker = if (devDialogProp() == "arrange") SPicker.ARRANGE else SPicker.ALIGNTEXT
            }
            "shapes" -> { tab = STab.INSERT; picker = SPicker.SHAPES }
            "sorter" -> showOverview = true
            // An edit once the deck has loaded: the first slide's background, which its thumbnail must follow.
            "thumbedit" -> scope.launch { kotlinx.coroutines.delay(8_000); editor.select(0); editor.setBackground(0xFFFFE699) }
            "fromoutline" -> showOutlineImport = true
            // Design and Insert dialogs with no ribbon path a hook can take: Slide Master, Export PDF's layouts, Insert Chart,
            // the section name, and Edit Data on a chart (added on the first slide when it has none, 4 s after opening).
            "slidemaster" -> showSlideMaster = true
            "pdflayout" -> showPdfExport = true
            "chartinsert" -> showChartDialog = true
            "sectionname" -> showSectionName = true
            "chartdata" -> scope.launch {
                kotlinx.coroutines.delay(4_000)
                if (editor.slide.charts.isEmpty()) editor.addChart(com.therealityteam.aeropages.model.ChartType.COLUMN)
                editor.selectChart(editor.slide.charts.first()); showChartData = true
            }
            "animpane" -> { animPaneOpen = true; frontSlidePane = SlideTaskPane.ANIMATION }
            // The Comments pane with two comments on the slide, and the Designer (`slidecomments`, `designer`).
            "slidecomments" -> scope.launch {
                kotlinx.coroutines.delay(4_000)
                if (editor.slide.comments.isEmpty()) { editor.addComment("Can we show last year's figure beside this one?"); editor.addComment("Title reads well — keep it.") }
                showComments = true; frontSlidePane = SlideTaskPane.COMMENTS
            }
            "designer" -> { designerOpen = true; frontSlidePane = SlideTaskPane.DESIGNER }
            // Slide Show: Set Up, Custom Shows, the Custom Slide Show menu, and the custom show set up played — once the deck has loaded.
            "showsetup" -> scope.launch { kotlinx.coroutines.delay(4_000); showShowSetup = true }
            "customshows", "customshowedit" -> scope.launch { kotlinx.coroutines.delay(4_000); showCustomShows = true }
            "customshowmenu" -> scope.launch { kotlinx.coroutines.delay(4_000); tab = STab.SHOW; picker = SPicker.CUSTOMSHOW }
            "showsetupplay" -> scope.launch { kotlinx.coroutines.delay(6_000); slideshowFromStart = true; slideshow = true }
            // Insert → Link on the first slide's first shape (its click), on the places in the show, once the deck has loaded.
            "slidelinks" -> scope.launch {
                kotlinx.coroutines.delay(4_000)
                editor.select(0); editor.slide.shapes.firstOrNull()?.let { editor.selectShape(it) }
                linkInShow = true; showLink = true
            }
            // The show from the first slide, following its first link (`showlink`, see SlideShow).
            "showlink" -> scope.launch { kotlinx.coroutines.delay(6_000); slideshowFromStart = true; slideshow = true }
            // The show from the slide `debug.aero.page` picks, once an intent-opened deck has loaded (`debug.aero.step`: at which click).
            "show", "showend" -> scope.launch { kotlinx.coroutines.delay(6_000); slideshowFromStart = false; slideshow = true }
            // The show with its presenter panel open (`setprop debug.aero.dialog showpresenter`).
            "showpresenter" -> scope.launch { kotlinx.coroutines.delay(6_000); slideshowFromStart = false; slideshowPresenter = true; slideshow = true }
            // The same, its first video or sound playing at once.
            "showplay" -> scope.launch { kotlinx.coroutines.delay(6_000); slideshowFromStart = false; showAutoPlay = true; slideshow = true }
        }
        // Dev hook (debug builds): `adb shell setprop debug.aero.export pdf|handout|notes` → export the
        // deck on launch (a blank deck gets a sample chart + table first) as slides / 6-up handouts / notes pages.
        val devExport = devExportProp()
        if (devExport == "pdf" || devExport == "handout" || devExport == "notes") {
            val pres = editor.presentation
            if (openPath == null) pres.slides[0].let { s ->
                s.charts.add(com.therealityteam.aeropages.model.SlideChart.sample())
                s.tables.add(com.therealityteam.aeropages.model.SlideTable.create(3, 3))
                s.notes = "Open with the quarterly numbers.\nMention the two new regions, then hand over to sales.\n\nKeep it under five minutes."
            }
            val snap = pres.copyOf()
            val layout = when (devExport) {
                "handout" -> SlidesPdfExporter.Layout.HANDOUT_6
                "notes" -> SlidesPdfExporter.Layout.NOTES
                else -> SlidesPdfExporter.Layout.SLIDES
            }
            val outFile = File(workDir, "dev-slides-$devExport.pdf")
            runCatching {
                withContext(Dispatchers.IO) { outFile.outputStream().use { SlidesPdfExporter.export(snap, it, layout) } }
            }.onSuccess { android.util.Log.i("AeroExport", "slides $devExport ok: ${outFile.absolutePath} (${outFile.length()} bytes)") }
                .onFailure { android.util.Log.e("AeroExport", "slides $devExport fail", it) }
        }
        // `setprop debug.aero.export pptx|odp` → the opened deck saved in that format (the writers as the device runs them).
        if (devExport == "pptx" || devExport == "odp") {
            val snap = editor.presentation.copyOf()
            val outFile = File(workDir, "dev-slides.$devExport")
            runCatching {
                withContext(Dispatchers.IO) {
                    outFile.outputStream().use {
                        if (devExport == "pptx") com.therealityteam.aeropages.format.pptx.PptxWriter.write(snap, it)
                        else com.therealityteam.aeropages.format.odf.OdpWriter.write(snap, it)
                    }
                }
            }.onSuccess { android.util.Log.i("AeroExport", "slides $devExport ok: ${outFile.absolutePath} (${outFile.length()} bytes)") }
                .onFailure { android.util.Log.e("AeroExport", "slides $devExport fail", it) }
        }
    }

    // Export as PDF opens the layout choice; the export itself names handouts / notes pages after their layout.
    val doExportPdf: () -> Unit = { showPdfExport = true }
    val exportPdfAs: (SlidesPdfExporter.Layout) -> Unit = { layout ->
        val (ext, label) = when (layout) {
            SlidesPdfExporter.Layout.SLIDES -> "pdf" to "PDF"
            SlidesPdfExporter.Layout.NOTES -> "notes.pdf" to Strings.get(R.string.pdf_notes_pages)
            else -> "handouts.pdf" to Strings.get(R.string.pdf_handouts)
        }
        session.export(ext, label) { pres, out -> SlidesPdfExporter.export(pres, out, layout) }
    }

    val clipboard = rememberTextClipboard()
    // Copy / cut take the picked object when no text is selected (PowerPoint's copy of a shape), for
    // any module's paste; paste pastes whichever of an object and some text was copied last.
    val onCopyObject: () -> Unit = { if (editor.copySelectedObject() != null) clipboard.copyCurrentObject() }
    val onCopy: () -> Unit = {
        val t = editor.selectedText()
        if (t.isEmpty() && editor.hasObjectSelected) onCopyObject() else copySlideText(editor, clipboard)
    }
    val onCut: () -> Unit = {
        val t = editor.selectedText()
        if (t.isEmpty() && editor.hasObjectSelected) { if (editor.cutSelectedObject() != null) clipboard.copyCurrentObject() }
        else if (t.isNotEmpty()) { copySlideText(editor, clipboard); editor.deleteSelection() }
    }
    val onPaste: () -> Unit = { pasteSlideText(editor, clipboard) }

    // A shape just inserted opens Shape Format (PowerPoint's); a shape merely clicked leaves Home or Insert in place.
    LaunchedEffect(editor.shapesInserted) { if (editor.shapesInserted > 0 && editor.selectedShape != null) tab = STab.SHAPE_FMT }
    // Auto-activate the matching contextual tab when a table / chart / shape is selected,
    // and leave a now-hidden contextual tab when its element is deselected.
    LaunchedEffect(editor.selectedTable, editor.selectedChart, editor.selectedShape, editor.selectedImage) {
        when {
            editor.selectedChart != null -> tab = STab.CHART_DESIGN
            editor.selectedImage != null -> tab = STab.PICTURE_FMT
            editor.selectedTable != null -> tab = STab.TABLE_DESIGN
            editor.selectedShape != null && tab !in listOf(STab.HOME, STab.INSERT) -> tab = STab.SHAPE_FMT
            else -> if (tab.contextual) tab = STab.HOME
        }
    }

    com.pico.spatial.ui.design.text.TextSelectionAndToolbarProvider(
        colors = androidx.compose.foundation.text.selection.TextSelectionColors(
            handleColor = PicoTheme.colorScheme.interaction,
            backgroundColor = PicoTheme.colorScheme.interaction.copy(alpha = 0.35f),
        ),
    ) {
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Search's "Find in Presentation" opens Find and Replace with the words in it (a new panel each time).
    var findSeed by remember { mutableStateOf(if (devDialogProp() == "slidefind") "e" else "") }
    var findSeedRev by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val ribbonFold = rememberRibbonFold("slides")
    androidx.compose.runtime.CompositionLocalProvider(LocalTooltipHost provides tooltipHost, LocalAccent provides Aero.slides, LocalSearchFocus provides searchFocus,
        LocalRibbonFold provides ribbonFold) {
    ProvideSlideThumbs {
    Box(
        Modifier.fillMaxSize().ribbonPeekDismiss(ribbonFold)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (searchShortcut(e, searchFocus) || ribbonShortcut(e, ribbonFold)) return@onPreviewKeyEvent true
                val ctrl = e.isCtrlPressed || e.isMetaPressed
                // Ctrl+C / X on a picked object with no text selected, Ctrl+V after an object was copied:
                // the object; otherwise the text fields copy and paste their text themselves.
                val objectPicked = editor.hasObjectSelected && editor.selectedText().isEmpty()
                when {
                    ctrl && e.key == Key.Z -> { editor.undo(); true }
                    ctrl && e.key == Key.Y -> { editor.redo(); true }
                    ctrl && e.key == Key.C && objectPicked -> { onCopy(); true }
                    ctrl && e.key == Key.X && objectPicked -> { onCut(); true }
                    ctrl && e.key == Key.V && clipboard.objectCopiedLast -> { onPaste(); true }
                    // PowerPoint's keys: F5 / Shift+F5 the show from the start / this slide; Ctrl+M a new slide; Ctrl+D the
                    // picked object again (else the slide); Delete the picked object; Ctrl+G / Ctrl+Shift+G group and
                    // ungroup; Ctrl+K a link; arrows nudge a picked picture, table or chart (Ctrl: a little).
                    e.key == Key.F5 -> { slideshowFromStart = !e.isShiftPressed; slideshow = true; true }
                    // PowerPoint's Alt+F10: the Selection Pane.
                    e.key == Key.F10 && e.isAltPressed -> { toggleSlidePane(SlideTaskPane.SELECTION, selectionOpen) { selectionOpen = it }; true }
                    ctrl && e.key == Key.M -> { editor.addSlide(); true }
                    // Ctrl+A outside a text box: every shape on the slide (in a text box, its own select-all).
                    ctrl && e.key == Key.A && editor.focusedBox() == null -> editor.selectAllShapes()
                    // Ctrl+H: Replace.
                    ctrl && e.key == Key.H -> { findReplace = true; showFind = true; true }
                    ctrl && e.key == Key.D -> { if (objectPicked) editor.duplicateSelectedObject() else editor.duplicateSlide(); true }
                    (e.key == Key.Delete || e.key == Key.Backspace) && objectPicked && editor.focusedBox() == null -> { editor.deleteSelectedObject(); true }
                    ctrl && e.isShiftPressed && e.key == Key.G -> { editor.ungroupSelected(); true }
                    ctrl && e.key == Key.G -> { editor.groupSelected(); true }
                    ctrl && e.key == Key.K -> { showLink = true; true }
                    !ctrl && (editor.selectedImage != null || editor.selectedTable != null || editor.selectedChart != null) && editor.focusedBox() == null &&
                        (e.key == Key.DirectionUp || e.key == Key.DirectionDown || e.key == Key.DirectionLeft || e.key == Key.DirectionRight) -> {
                        val step = 0.01f
                        when (e.key) {
                            Key.DirectionUp -> editor.moveSelected(0f, -step); Key.DirectionDown -> editor.moveSelected(0f, step)
                            Key.DirectionLeft -> editor.moveSelected(-step, 0f); else -> editor.moveSelected(step, 0f)
                        }
                        true
                    }
                    // In a text box, Word's character and paragraph keys.
                    ctrl && editor.focusedBox() != null -> when {
                        e.isShiftPressed && e.key == Key.Period -> { editor.setFontSize(stepSize(editor.currentFormat.fontSizePt ?: editor.focusedBox()!!.defaultSizePt, +1)); true }
                        e.isShiftPressed && e.key == Key.Comma -> { editor.setFontSize(stepSize(editor.currentFormat.fontSizePt ?: editor.focusedBox()!!.defaultSizePt, -1)); true }
                        e.isShiftPressed -> false
                        e.key == Key.B -> { editor.toggleBold(); true }
                        e.key == Key.I -> { editor.toggleItalic(); true }
                        e.key == Key.U -> { editor.toggleUnderline(); true }
                        e.key == Key.E -> { editor.setAlign(com.therealityteam.aeropages.model.TextAlign.CENTER); true }
                        e.key == Key.L -> { editor.setAlign(com.therealityteam.aeropages.model.TextAlign.LEFT); true }
                        e.key == Key.R -> { editor.setAlign(com.therealityteam.aeropages.model.TextAlign.RIGHT); true }
                        e.key == Key.J -> { editor.setAlign(com.therealityteam.aeropages.model.TextAlign.JUSTIFY); true }
                        e.key == Key.RightBracket -> { editor.setFontSize((editor.currentFormat.fontSizePt ?: editor.focusedBox()!!.defaultSizePt) + 1f); true }
                        e.key == Key.LeftBracket -> { editor.setFontSize(((editor.currentFormat.fontSizePt ?: editor.focusedBox()!!.defaultSizePt) - 1f).coerceAtLeast(1f)); true }
                        else -> false
                    }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            SlidesRibbon(
                editor, tab, { tab = it }, picker, { picker = if (picker == it) SPicker.NONE else it },
                onHome = { session.guardDirty(onHome) }, onSave = session::save, dirty = editor.dirty,
                title = session.sourceName ?: session.untitledName, saveState = docState(session),
                onInsertImage = { images = listImages(workDir); showImage = true },
                onPlay = { slideshowFromStart = true; slideshow = true },
                onPlayCurrent = { slideshowFromStart = false; slideshow = true },
                onPresenter = { slideshowFromStart = false; slideshowPresenter = true; slideshow = true },
                onFindInFile = { q -> findSeed = q; findSeedRev++; findReplace = false; showFind = true },
                onPlayCustom = { name -> slideshowFromStart = true; slideshowCustom = name; slideshow = true },
                onCustomShows = { showCustomShows = true },
                onMoreSymbols = { showSymbols = true },
                onInsertMedia = { video -> mediaFiles = listMedia(workDir, video); insertMedia = video },
                notesOpen = showNotes, onToggleNotes = { showNotes = !showNotes },
                onCopy = onCopy, onCut = onCut, onPaste = onPaste, onCopyObject = onCopyObject,
                onInsertLink = { showLink = true },
                onInsertEquation = { openEquation() },
                onFind = { findReplace = false; showFind = true },
                onReplace = { findReplace = true; showFind = true },
                onProofing = { showProofing = true },
                commentsOpen = showComments, onToggleComments = { toggleSlidePane(SlideTaskPane.COMMENTS, showComments) { showComments = it } },
                onNewComment = { showComments = true; frontSlidePane = SlideTaskPane.COMMENTS },
                onShowOverview = { showOverview = true }, onShowOutline = { outlineOpen = !outlineOpen }, outlineOpen = outlineOpen, onSlidesFromOutline = { showOutlineImport = true },
                onStatus = { session.status = it },
                onInsertTable = { showTableDialog = true },
                onInsertChart = { showChartDialog = true },
                onHeaderFooter = { showHeaderFooter = true },
                onSectionName = { showSectionName = true },
                onSlideMaster = { showSlideMaster = true },
                onEditChartData = { showChartData = true },
                animPaneOpen = animPaneOpen, onToggleAnimPane = { toggleSlidePane(SlideTaskPane.ANIMATION, animPaneOpen) { animPaneOpen = it } },
                designerOpen = designerOpen, onToggleDesigner = { toggleSlidePane(SlideTaskPane.DESIGNER, designerOpen) { designerOpen = it } },
                onClipboardPane = { toggleSlidePane(SlideTaskPane.CLIPBOARD, clipboardOpen) { clipboardOpen = it } },
                onSelectionPane = { toggleSlidePane(SlideTaskPane.SELECTION, selectionOpen) { selectionOpen = it } },
                onReuseSlides = { reuseOpen = true; frontSlidePane = SlideTaskPane.REUSE },
                showRuler = showRuler, onToggleRuler = { showRuler = !showRuler },
                showGridlines = showGridlines, onToggleGridlines = { showGridlines = !showGridlines },
                showGuides = showGuides, onToggleGuides = { showGuides = !showGuides },
                zoomPct = zoomPct, onZoom = { zoomPct = it.coerceIn(40, 200) },
                onShortcuts = { showShortcuts = true },
                onDetachedShow = {
                    ShowSession.start(editor)
                    navigator.openWindowContainer("show")
                    session.status = Strings.get(R.string.status_show_new_window)
                },
                onSpatialShow = startSpatialShow,
                onRehearse = { rehearseMode = true; slideshowFromStart = true; slideshow = true },
                onChart3D = { chart3DPreview = !chart3DPreview }, chart3DOpen = chart3DPreview,
                onShowSetup = { showShowSetup = true },
                onExportPdf = doExportPdf,
                fileMenuOpen = session.showFileMenu,
                onToggleFileMenu = { session.showFileMenu = !session.showFileMenu },
            )
            if (!slideshow) SessionBanner(session)

            // While the show runs in this window the editor's body is not composed: its panes' spatial
            // materials are composited above ordinary content, so they would show through the show.
            // (The slide sorter covers the body too: its glass panes would show through the sorter.)
            // Slide Sorter: a view in the window's body; a click picks a slide, a second click on the picked one opens it.
            if (showOverview && !slideshow) SlideOverview(editor, onPick = { i -> if (i == editor.current) showOverview = false else editor.select(i) },
                Modifier.fillMaxWidth().weight(1f), onSectionName = { showSectionName = true })
            else if (slideshow) Box(Modifier.fillMaxWidth().weight(1f)) else Row(Modifier.fillMaxWidth().weight(1f)) {
                if (outlineOpen) OutlinePane(editor) else SlideNavigator(editor, onSectionName = { showSectionName = true })
                VerticalDivider(modifier = Modifier.fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // While the show runs in its own window this window is the presenter console (notes, timer, next slide).
                    if (ShowSession.active && ShowSession.editor === editor) PresenterConsole(editor, Modifier.weight(1f))
                    else SlideCanvas(editor, Modifier.weight(1f), showGridlines = showGridlines, showGuides = showGuides, zoomPct = zoomPct, animTags = tab == STab.ANIM,
                        onCommentMarker = { i -> editor.pickedComment = i; showComments = true; frontSlidePane = SlideTaskPane.COMMENTS })
                    if (showNotes) {
                        HorizontalDivider()
                        NotesEditor(editor)
                    }
                }
                // PowerPoint's task panes docked on the right: one at a time at full height, a switcher on top when
                // several are open, the one opened last in front.
                val panes = SlideTaskPane.entries.filter(::paneOpen)
                if (panes.isNotEmpty()) {
                    val front = shownSlidePane() ?: panes.last()
                    VerticalDivider(modifier = Modifier.fillMaxHeight())
                    Column(Modifier.width(300.dp).fillMaxHeight()) {
                        if (panes.size > 1) PaneSwitcher(panes, front, { frontSlidePane = it }, { stringResource(it.label) })
                        val fill = Modifier.fillMaxWidth().weight(1f)
                        when (front) {
                            SlideTaskPane.DESIGNER -> DesignerPanel(editor, onClose = { designerOpen = false }, modifier = fill)
                            SlideTaskPane.ANIMATION -> AnimPane(editor, onClose = { animPaneOpen = false }, modifier = fill)
                            SlideTaskPane.COMMENTS -> CommentsPanel(editor, onClose = { showComments = false }, modifier = fill)
                            SlideTaskPane.CLIPBOARD -> ClipboardPane(fill, onPasteText = { editor.insertAtCaret(it) }, onPasteObject = { editor.pasteItem(it) }) { clipboardOpen = false }
                            SlideTaskPane.SELECTION -> SelectionPane(editor, fill) { selectionOpen = false }
                            SlideTaskPane.REUSE -> ReuseSlidesPane(editor, reuseDeck, reusePath, onBrowse = { reuseBrowse = true }, onOpenRecent = ::loadReuse,
                                onStatus = { session.status = it }, modifier = fill) { reuseOpen = false }
                        }
                    }
                }
            }

            @Suppress("UNUSED_VARIABLE") val statusRev = editor.revision
            AeroStatusBar(
                start = {
                    StatusText(stringResource(R.string.label_slide_of, editor.current + 1, editor.slideCount))
                    AccessibilityStatus(statusFindings(com.therealityteam.aeropages.model.AccessibilityCheck.of(editor.presentation))) { a11yOpen = true }
                    StatusText(session.status, Modifier.weight(1f))
                },
                end = {
                    // PowerPoint's status bar: Notes and Comments, then the views — Normal, Slide Sorter, Reading View, Slide Show.
                    TitleIconButton("ic_notes", stringResource(R.string.notes), { showNotes = !showNotes }, selected = showNotes, size = 28.dp)
                    TitleIconButton("ic_comment", stringResource(R.string.comments), { toggleSlidePane(SlideTaskPane.COMMENTS, showComments) { showComments = it } }, selected = showComments, size = 28.dp)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 8.dp))
                    StatusView("ic_layout", stringResource(R.string.view_normal), !showOverview) { showOverview = false }
                    StatusView("ic_grid", stringResource(R.string.slide_sorter), showOverview) { showOverview = true }
                    StatusView("ic_book", stringResource(R.string.reading_view), false) { slideshowFromStart = false; slideshow = true }
                    StatusView("ic_play", stringResource(R.string.tab_slide_show), false) { slideshowFromStart = false; slideshow = true }
                },
            )
        }

        insertMedia?.let { video ->
            ImageDialog(
                images = mediaFiles,
                onDismiss = { insertMedia = null },
                title = stringResource(if (video) R.string.title_insert_video else R.string.title_insert_audio),
                hint = stringResource(if (video) R.string.label_choose_video else R.string.label_choose_audio),
                empty = stringResource(if (video) R.string.msg_no_video_in_folder else R.string.msg_no_audio_in_folder),
                icon = if (video) "🎬" else "🔊",
                onPick = { path ->
                    insertMedia = null
                    scope.launch {
                        val loaded = withContext(Dispatchers.IO) { loadMediaForInsert(path) }
                        loaded.onSuccess { m ->
                            editor.insertMedia(m.media, m.poster, "image/png", m.widthPt, m.heightPt)
                            session.status = Strings.get(if (m.media.kind == com.therealityteam.aeropages.model.SlideMedia.Kind.VIDEO) R.string.status_video_inserted else R.string.status_audio_inserted)
                        }.onFailure { e ->
                            val name = File(path).name
                            session.status = if ((e as? MediaException)?.problem == MediaProblem.TooLarge) Strings.get(R.string.err_media_too_large, name)
                                else Strings.get(R.string.err_cannot_read_media, name)
                        }
                    }
                },
            )
        }
        if (showImage) {
            ImageDialog(
                images = images,
                onDismiss = { showImage = false },
                onPick = { path ->
                    showImage = false
                    scope.launch {
                        val loaded = runCatching { withContext(Dispatchers.IO) { loadImageForInsert(path) } }.getOrNull()
                        if (loaded == null) session.status = Strings.get(R.string.err_cannot_read_image, File(path).name)
                        else { editor.setImage(loaded.data, loaded.mime, loaded.widthPt, loaded.heightPt); session.status = Strings.get(R.string.status_image_inserted) }
                    }
                },
            )
        }

        SessionFileMenu(
            session,
            newLabel = stringResource(R.string.new_presentation),
            onNew = { session.newFrom(DeskTemplate.BLANK_DECK, Strings.get(R.string.new_presentation)) { Presentation.blank() } },
            onExportPdf = doExportPdf,
            templates = DeskTemplate.of(com.therealityteam.aeropages.format.DeskKind.SLIDES),
            onNewFrom = { t -> session.newFrom(t, Strings.get(R.string.new_presentation)) { Templates().presentation(t) } },
            onInfo = { showInfo = true },
        )
        if (showInfo) {
            val deck = editor.presentation
            DocInfoDialog(deck.props, deck.sourcePath, listOf(
                stringResource(R.string.stat_slides) to deck.slides.size.toString(),
                stringResource(R.string.stat_hidden_slides) to deck.slides.count { it.hidden }.toString(),
                stringResource(R.string.stat_notes) to deck.slides.count { it.notes.isNotBlank() }.toString(),
                stringResource(R.string.stat_pictures) to deck.slides.sumOf { s -> s.images.count { it.media == null } }.toString(),
                stringResource(R.string.stat_media) to deck.slides.sumOf { s -> s.images.count { it.media != null } }.toString(),
            ), onApply = editor::setProps) { showInfo = false }
        }

        if (showLink) {
            SlideLinkDialog(editor, onDismiss = { showLink = false; linkInShow = false }, startInShow = linkInShow)
        }

        slideEquation?.let { (latex, box) ->
            EquationDialog(latex, editing = box != null, allowInline = false, initialDisplay = true, onDismiss = { slideEquation = null }, onApply = { math, _ ->
                slideEquation = null
                if (box != null) editor.setEquation(box, math) else editor.insertEquation(math)
                session.status = Strings.get(if (box != null) R.string.status_equation_updated else R.string.status_equation_inserted)
            })
        }

        if (showProofing) {
            // Every box and every slide's notes: the device's spell checker's typos, then AeroDesk's own checks.
            val targets = remember { editor.proofTargets() }
            val texts = remember(targets) { targets.map { editor.proofText(it) } }
            ProofingSession(
                texts = texts,
                ownIssues = { editor.proofread(targets) },
                onJump = { i -> targets.getOrNull(i)?.let { editor.showProofTarget(it) } },
                apply = { issue, s -> val t = targets.getOrNull(issue.index); val w = issue.word; t != null && w != null && editor.applySpelling(t, issue.start, w, s) },
                onDismiss = { showProofing = false },
                where = { issue ->
                    targets.getOrNull(issue.index)?.let { t ->
                        Strings.get(R.string.proof_where_slide, t.slide + 1, Strings.get(when (t.part) {
                            com.therealityteam.aeropages.editor.SlideEditor.ProofPart.TITLE -> R.string.label_title
                            com.therealityteam.aeropages.editor.SlideEditor.ProofPart.BODY -> R.string.anim_entry_content
                            com.therealityteam.aeropages.editor.SlideEditor.ProofPart.SHAPE -> R.string.shape
                            com.therealityteam.aeropages.editor.SlideEditor.ProofPart.TABLE -> R.string.table
                            com.therealityteam.aeropages.editor.SlideEditor.ProofPart.NOTES -> R.string.notes
                        }))
                    }.orEmpty()
                },
            )
        }

        // Find and Replace beside the window, modeless (`dialog slidefind` — with a word of the deck searched for —, `slidereplace`).
        if (showFind) {
            com.pico.spatial.ui.design.windows.Subwindow(placement = com.pico.spatial.ui.design.windows.SubwindowPlacement.Right) {
                androidx.compose.runtime.key(findSeedRev) { SlidesFindPanel(editor, findReplace, { findReplace = it }, initialQuery = findSeed) { showFind = false } }
            }
        }

        if (showShortcuts) {
            ShortcutsDialog("Slides", COMMON_SHORTCUTS + listOf(
                stringResource(R.string.shortcuts_show_section) to listOf(
                    ShortcutEntry(stringResource(R.string.shortcut_next_key), stringResource(R.string.shortcut_next_desc)),
                    ShortcutEntry("←", stringResource(R.string.shortcut_prev_desc)),
                    ShortcutEntry("Esc", stringResource(R.string.shortcut_end_show_desc)),
                    ShortcutEntry("F5 / Shift+F5", stringResource(R.string.show_from_beginning) + " / " + stringResource(R.string.show_from_current_slide)),
                ),
                stringResource(R.string.shortcut_category_editing) to listOf(
                    ShortcutEntry("Ctrl+M", stringResource(R.string.new_slide)),
                    ShortcutEntry("Ctrl+D", stringResource(R.string.shortcut_duplicate_object)),
                    ShortcutEntry("Ctrl+G / Ctrl+Shift+G", stringResource(R.string.group) + " / " + stringResource(R.string.ungroup)),
                    ShortcutEntry("←↑→↓", stringResource(R.string.shortcut_nudge)),
                    ShortcutEntry("Ctrl+B / I / U", stringResource(R.string.bold) + " / " + stringResource(R.string.italic) + " / " + stringResource(R.string.underline)),
                    ShortcutEntry("Ctrl+L / E / R / J", stringResource(R.string.align_left) + " / " + stringResource(R.string.center) + " / " +
                        stringResource(R.string.align_right) + " / " + stringResource(R.string.justify)),
                    ShortcutEntry("Ctrl+Shift+> / <", stringResource(R.string.increase_font_size) + " / " + stringResource(R.string.decrease_font_size)),
                    ShortcutEntry("Ctrl+K", stringResource(R.string.insert_link)),
                ),
            ), onDismiss = { showShortcuts = false })
        }


        if (showTableDialog) {
            TableInsertDialog(onDismiss = { showTableDialog = false }, onInsert = { r, c -> showTableDialog = false; editor.addTable(r, c); session.status = Strings.get(R.string.status_table_inserted, r, c) })
        }

        if (showChartDialog) {
            ChartTypeDialog(onDismiss = { showChartDialog = false }, onPick = { t -> showChartDialog = false; editor.addChart(t); session.status = Strings.get(R.string.status_chart_inserted) })
        }

        if (showChartData && editor.selectedChart != null) {
            ChartDataDialog(editor, onDismiss = { showChartData = false })
        }

        if (showShowSetup) {
            ShowSetupDialog(editor, onDismiss = { showShowSetup = false }, onCustomShows = { showShowSetup = false; showCustomShows = true })
        }
        if (showSymbols) SymbolDialog(onInsert = { editor.insertAtCaretOrTitle(it) }) { showSymbols = false }
        if (showCustomShows) {
            CustomShowsDialog(editor, onDismiss = { showCustomShows = false }, onPlay = { name ->
                showCustomShows = false; slideshowFromStart = true; slideshowCustom = name; slideshow = true
            }, editFirst = com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "customshowedit")
        }

        // XR 空间3D图表预览: the selected chart's data as orbit-able floating 3D columns.
        if (chart3DPreview) {
            editor.selectedChart?.let { chart ->
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth(0.5f).barSurface().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.label_3d_spatial_view, chart.title), style = AeroType.heading, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
                        TitleIconButton("ic_close", stringResource(R.string.close), { chart3DPreview = false }, size = 36.dp)
                    }
                    Spatial3DChart(chart, Modifier.fillMaxWidth(), plotHeight = 240)
                }
            } ?: run { chart3DPreview = false }
        }

        if (showSectionName) {
            SectionNameDialog(
                initial = editor.slide.sectionName ?: "",
                onDismiss = { showSectionName = false },
                onApply = { name -> showSectionName = false; editor.setSectionName(name) },
            )
        }
        if (showOutlineImport) {
            SlidesFromOutlineDialog(onDismiss = { showOutlineImport = false }) { text ->
                showOutlineImport = false
                val n = editor.insertSlidesFromOutline(text)
                session.status = Strings.get(R.string.status_slides_from_outline, n)
            }
        }
        if (showPdfExport) {
            PdfLayoutDialog(onDismiss = { showPdfExport = false }, onPick = exportPdfAs)
        }
        if (showSlideMaster) {
            SlideMasterDialog(editor, onDismiss = { showSlideMaster = false })
        }

        if (showHeaderFooter) {
            HeaderFooterDialog(
                editor.presentation,
                onDismiss = { showHeaderFooter = false },
                onApply = { footer, showNum, date, hideOnTitle ->
                    showHeaderFooter = false
                    editor.setFooter(footer, showNum, date, hideOnTitle)
                    session.status = Strings.get(R.string.status_footer_applied)
                },
            )
        }


        if (slideshow) SlideShow(editor, fromStart = slideshowFromStart, rehearse = rehearseMode, autoPlayMedia = showAutoPlay,
            custom = slideshowCustom, presenter = slideshowPresenter, onExit = {
            slideshow = false
            showAutoPlay = false
            slideshowCustom = null; slideshowPresenter = false
            if (rehearseMode) { rehearseMode = false; session.status = Strings.get(R.string.status_rehearsal_complete) }
        })

        SessionDialogs(session, saveTitle = stringResource(R.string.title_save_presentation), untitledLabel = stringResource(R.string.label_untitled_presentation))
        if (reuseBrowse) FileBrowser(
            mode = BrowserMode.OPEN, title = stringResource(R.string.reuse_slides),
            openExtensions = DeskFiles.SLIDES_EXT,
            startDir = session.browseStart(), shortcuts = session.shortcuts(), suggestedName = "",
            hasStorageAccess = session.storageAccess, onRequestStorage = session::requestStorage, onRefresh = session::refreshStorage,
            onDismiss = { reuseBrowse = false },
            onOpen = { path -> reuseBrowse = false; loadReuse(path) },
            onSave = {},
        )

        TooltipOverlay(tooltipHost)
    }
    }
    }
    }
}

/** Open any presentation format the module supports (runs on IO). */
internal fun readDeck(path: String): Presentation {
    val bytes = File(path).readBytes()
    // A password-protected file is refused, never read as a blank deck that a save would write over it.
    com.therealityteam.aeropages.format.Encryption.check(path, bytes)
    val ext = path.substringAfterLast('.', "").lowercase()
    val pres = when {
        CompoundFileReader.isCfb(bytes) -> PptReader.read(bytes)
        com.therealityteam.aeropages.format.odf.OdfPackage.typeOf(bytes)?.startsWith(com.therealityteam.aeropages.format.odf.OdfPackage.PRESENTATION) == true ||
            ext == "odp" || ext == "otp" -> com.therealityteam.aeropages.format.odf.OdpReader.read(bytes)
        else -> PptxReader.read(bytes)
    }
    pres.sourcePath = path
    return pres
}

// --- bullet / numbered list rendering (marker injection, no model change) -----

// --- Slideshow (放映) -------------------------------------------------------

// --- dialogs / panels ----------------------------------------------------------

/** Slides' right-hand task panes, in the order the switcher lists them. */
internal enum class SlideTaskPane(val label: Int) {
    DESIGNER(R.string.designer_panel_title), ANIMATION(R.string.animation_pane), COMMENTS(R.string.comments), CLIPBOARD(R.string.clipboard),
    SELECTION(R.string.selection_pane), REUSE(R.string.reuse_slides)
}
