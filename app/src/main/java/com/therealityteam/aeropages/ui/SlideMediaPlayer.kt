package com.therealityteam.aeropages.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.format.MediaTypes
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Where a slide's video or sound is to be played from. */
internal sealed interface MediaTarget {
    /** A file on the device: the copy the presentation holds (written out to play), or the one it links to. */
    data class Local(val file: File) : MediaTarget
    /** A link to the web: AeroDesk has no network access of its own, so the browser plays it. */
    data class Web(val url: String) : MediaTarget
    /** A linked file found neither where the link says nor next to the presentation. */
    data class Missing(val name: String) : MediaTarget
}

internal object MediaSources {

    /**
     * What [link] — a video's or sound's link as the presentation has it — points to: a web address, a
     * file (`file:` URL, absolute path, or a path relative to the presentation's folder [deckDir]), or,
     * as PowerPoint looks for it, a file of its name next to the presentation (a link made on another
     * computer, `C:\Users\…\talk.mp4`). [exists] says whether a file is there.
     */
    fun resolveLink(link: String, deckDir: File?, exists: (File) -> Boolean): MediaTarget {
        val t = link.trim()
        val scheme = SCHEME.find(t)?.groupValues?.get(1)?.lowercase()
        if (scheme == "http" || scheme == "https") return MediaTarget.Web(t)
        val name = fileName(t)
        val candidates = ArrayList<File>()
        when {
            scheme == "file" -> runCatching { java.net.URI(t).path }.getOrNull()?.let { candidates += File(it) }
            scheme != null && scheme.length > 1 -> Unit                 // another scheme: only its name next to the deck
            scheme != null || t.startsWith("\\\\") -> Unit              // a Windows drive or share: likewise
            t.startsWith("/") -> candidates += File(t)
            deckDir != null -> candidates += File(deckDir, t.replace('\\', '/')).normalize()
        }
        if (deckDir != null && name.isNotEmpty()) candidates += File(deckDir, name)
        return candidates.firstOrNull(exists)?.let { MediaTarget.Local(it) } ?: MediaTarget.Missing(name.ifEmpty { t })
    }

    /** The file name at the end of a path or URL ([link]'s last segment, `%20` and the like undone for a URL). */
    fun fileName(link: String): String {
        val last = link.trim().substringBefore('?').substringBefore('#').trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        return if ('%' in last) runCatching { java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") }.getOrDefault(last) else last
    }

    /** The name the copy of [media]'s [data] is played from: its bytes' digest (the same clip is written once) and an extension players know. */
    fun cacheName(media: SlideMedia, data: ByteArray): String {
        val ext = media.name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && MediaTypes.isMedia(media.name) } ?: MediaTypes.extOf(media.mime)
        val digest = MessageDigest.getInstance("SHA-1").digest(data)
        val hex = buildString { for (b in digest.take(12)) { append(HEX[(b.toInt() shr 4) and 15]); append(HEX[b.toInt() and 15]) } }
        return "$hex.$ext"
    }

    /** Where to play [media] from, its copy written to the cache first (once; off the main thread). */
    suspend fun locate(context: Context, media: SlideMedia, deckDir: File?): MediaTarget = withContext(Dispatchers.IO) {
        val data = media.data
        if (data != null) {
            val dir = File(context.cacheDir, "slide-media").apply { mkdirs() }
            val f = File(dir, cacheName(media, data))
            if (!f.isFile || f.length() != data.size.toLong()) {
                val tmp = File(dir, f.name + ".part")
                tmp.writeBytes(data)
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                prune(dir, keep = f)
            } else {
                f.setLastModified(System.currentTimeMillis())
            }
            MediaTarget.Local(f)
        } else {
            resolveLink(media.link.orEmpty(), deckDir) { it.isFile }
        }
    }

    /** The copies played longest ago go once there are more than [limit] bytes of them ([keep] stays). */
    internal fun prune(dir: File, keep: File, limit: Long = CACHE_LIMIT) {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= limit) break
            if (f == keep) continue
            val n = f.length()
            if (f.delete()) total -= n
        }
    }

    /** At most this much of the cache for clips written out to play. */
    private const val CACHE_LIMIT = 512L * 1024 * 1024

    private val SCHEME = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):""")
    private const val HEX = "0123456789abcdef"
}

/**
 * One slide picture's video or sound being played: an [MediaPlayer] drawing into the picture's frame
 * (a [TextureView], so it turns, flips and fades with the slide), its state for the controls drawn over it.
 */
internal class MediaPlayback {
    enum class State { IDLE, PREPARING, PLAYING, PAUSED, ENDED, ERROR }

    var state by mutableStateOf(State.IDLE); private set
    var positionMs by mutableIntStateOf(0); private set
    var durationMs by mutableIntStateOf(0); private set
    /** Whether the video has drawn its first frame: until then its poster shows. */
    var rendering by mutableStateOf(false); private set
    /** Why it does not play (shown under the picture), or null. */
    var problem by mutableStateOf<String?>(null); private set

    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    /** The surface a video draws on ([texture] null when its view goes). */
    fun attach(texture: SurfaceTexture?) {
        val old = surface
        surface = texture?.let { Surface(it) }
        runCatching { player?.setSurface(surface) }
        old?.release()
    }

    /** Plays [file] from its start. */
    fun start(file: File, video: Boolean) {
        player?.release()
        problem = null; rendering = false; positionMs = 0; durationMs = 0
        val p = MediaPlayer()
        player = p
        state = State.PREPARING
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC).build())
        p.setOnPreparedListener { mp -> if (player === mp) { durationMs = mp.duration.coerceAtLeast(0); mp.start(); state = State.PLAYING } }
        p.setOnInfoListener { mp, what, _ -> if (player === mp && what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) rendering = true; false }
        p.setOnCompletionListener { mp -> if (player === mp) { positionMs = durationMs; state = State.ENDED } }
        p.setOnErrorListener { mp, _, _ -> if (player === mp) state = State.ERROR; true }
        runCatching {
            surface?.let { p.setSurface(it) }
            p.setDataSource(file.path)
            p.prepareAsync()
        }.onFailure { state = State.ERROR }
    }

    /** Pauses what plays, plays on what is paused, and plays again what has ended. */
    fun toggle() {
        val p = player ?: return
        when (state) {
            State.PLAYING -> { p.pause(); positionMs = p.currentPosition; state = State.PAUSED }
            State.PAUSED -> { p.start(); state = State.PLAYING }
            State.ENDED -> { p.seekTo(0); positionMs = 0; p.start(); state = State.PLAYING }
            else -> Unit
        }
    }

    /** How far it has played, 0 … 1. */
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    /** "0:12 / 1:30". */
    val clock: String get() = "${clock(positionMs)} / ${clock(durationMs)}"

    /** Pauses it if it plays (the app going to the background). */
    fun pauseIfPlaying() { if (state == State.PLAYING) toggle() }

    /** Getting it ready to play (its copy written out, its file found): what plays it comes next, in [start]. */
    fun preparing() { problem = null; state = State.PREPARING }

    /** Cannot play: [why] shown under the picture. */
    fun fail(why: String) { player?.release(); player = null; problem = why; state = State.ERROR }

    fun tick() { player?.takeIf { state == State.PLAYING }?.let { positionMs = runCatching { it.currentPosition }.getOrDefault(positionMs) } }

    fun release() {
        player?.release(); player = null
        surface?.release(); surface = null
        state = State.IDLE; rendering = false
    }
}

/**
 * A slide picture's video or sound with what plays it — [play] plays it, pauses it, plays it on, or
 * plays it again — and [playback], what is drawn: shared by the show (a tap on the picture) and the
 * editor's play bar.
 */
internal class MediaControl(val media: SlideMedia, val playback: MediaPlayback, val play: () -> Unit) {
    val video: Boolean get() = media.kind == SlideMedia.Kind.VIDEO
    /** A link to the web: a tap opens it in the browser rather than playing it here. */
    val onWeb: Boolean get() = media.data == null && media.link?.let { MediaSources.resolveLink(it, null) { false } } is MediaTarget.Web
}

/**
 * The control for [img]'s video or sound (null for a picture without one): a copy the presentation
 * holds is played from the cache; a linked file from where the link says or next to the presentation
 * ([deckDir]); a web link opens in the browser. It stops when it leaves the screen (another slide) and
 * pauses when the app goes to the background.
 */
@Composable
internal fun rememberMediaControl(img: SlideImage, deckDir: File?): MediaControl? {
    val media = img.media ?: return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playback = remember(img) { MediaPlayback() }
    DisposableEffect(playback) { onDispose { playback.release() } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, playback) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) playback.pauseIfPlaying() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val video = media.kind == SlideMedia.Kind.VIDEO
    val cannotPlay = stringResource(R.string.media_cannot_play, media.name)
    val notFound = stringResource(R.string.media_not_found, media.name)
    fun play() {
        when (playback.state) {
            MediaPlayback.State.IDLE, MediaPlayback.State.ERROR -> scope.launch {
                playback.preparing()
                when (val target = MediaSources.locate(context, media, deckDir)) {
                    is MediaTarget.Local -> playback.start(target.file, video)
                    is MediaTarget.Missing -> playback.fail(notFound)
                    // The browser plays it: nothing plays here (back as it was, for another tap).
                    is MediaTarget.Web -> runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, target.url.toUri())
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onSuccess { playback.release() }.onFailure { playback.fail(cannotPlay) }
                }
            }
            MediaPlayback.State.PREPARING -> Unit
            else -> playback.toggle()
        }
    }
    LaunchedEffect(playback.state) {
        if (playback.state == MediaPlayback.State.ERROR && playback.problem == null) playback.fail(cannotPlay)
        while (playback.state == MediaPlayback.State.PLAYING) { playback.tick(); delay(100) }
    }
    return MediaControl(media, playback) { play() }
}

/** What a video or sound is called for accessibility ("Video: clip.mp4"), what state it is in, and what a tap on it does. */
internal class MediaLabels(val name: String, val status: String, val action: String)

@Composable
internal fun mediaLabels(control: MediaControl): MediaLabels {
    val state = control.playback.state
    val action = stringResource(
        when {
            control.onWeb -> R.string.a11y_media_open_web
            state == MediaPlayback.State.PLAYING -> R.string.a11y_media_pause
            else -> R.string.a11y_media_play
        })
    val name = stringResource(if (control.video) R.string.a11y_media_video else R.string.a11y_media_audio, control.media.name)
    val status = when (state) {
        MediaPlayback.State.PREPARING -> stringResource(R.string.media_state_loading)
        MediaPlayback.State.PLAYING -> stringResource(R.string.media_state_playing)
        MediaPlayback.State.PAUSED -> stringResource(R.string.media_state_paused)
        else -> control.playback.problem.orEmpty()
    }
    return MediaLabels(name, status, action)
}

/**
 * What a slide picture shows of its video or sound: the video's frames while it plays (its poster over
 * them until the first is drawn), the badge for what a tap does, why it cannot play, and — with
 * [progress] — a line along its foot and its clock for how far it has played. [badgeLabel] names the
 * badge for accessibility where the picture itself is not named.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun MediaFrame(img: SlideImage, control: MediaControl, modifier: Modifier, progress: Boolean = true, badgeLabel: String? = null) {
    val playback = control.playback
    val state = playback.state
    val video = control.video
    Box(modifier) {
        // The video's frames, under its poster until the first is drawn; cropped and mirrored as the picture is.
        val showsVideo = video && state != MediaPlayback.State.IDLE && state != MediaPlayback.State.ERROR
        if (showsVideo) AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) { playback.attach(st); crop(this@apply, img) }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = crop(this@apply, img)
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { playback.attach(null); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier.matchParentSize().graphicsLayer(scaleX = if (img.flipH) -1f else 1f, scaleY = if (img.flipV) -1f else 1f),
        )
        if (!showsVideo || !playback.rendering) SlideImageView(img, Modifier.matchParentSize(), badge = false)
        // The badge: what a tap does (play, or pause a sound; a playing video shows none), or that it is getting ready.
        val glyph = when (state) {
            MediaPlayback.State.PREPARING -> MediaGlyph.BUSY
            MediaPlayback.State.PLAYING -> if (video) null else MediaGlyph.PAUSE
            MediaPlayback.State.PAUSED, MediaPlayback.State.ENDED -> MediaGlyph.PLAY
            MediaPlayback.State.ERROR -> MediaGlyph.ERROR
            MediaPlayback.State.IDLE -> if (video) MediaGlyph.PLAY else MediaGlyph.SPEAKER
        }
        if (glyph != null) MediaBadge(glyph, badgeLabel, Modifier.matchParentSize())
        // How far it has played: a line along its foot, and its clock.
        if (progress && (state == MediaPlayback.State.PLAYING || state == MediaPlayback.State.PAUSED)) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66000000))) {
                Box(Modifier.fillMaxWidth(playback.fraction).height(4.dp).background(Color.White))
            }
            Text(
                playback.clock, color = Color.White, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        // Why it does not play, under the picture (a sound's icon is too small to hold it).
        playback.problem?.let { why ->
            Text(
                why, color = Color.White, fontSize = 12.sp, maxLines = 2,
                modifier = Modifier.align(Alignment.BottomCenter).offset(y = 30.dp).wrapContentSize(unbounded = true)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xCC202028)).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * A slide picture as the show presents it: a video or sound plays where it sits when it is tapped
 * (tapped again, it pauses; at its end, a tap plays it again). [autoPlay] starts it at once (the
 * show's dev hook). A picture without media is just the picture.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun PlayableSlideImage(img: SlideImage, deckDir: File?, modifier: Modifier, autoPlay: Boolean = false) {
    val control = rememberMediaControl(img, deckDir) ?: return SlideImageView(img, modifier)
    LaunchedEffect(autoPlay) { if (autoPlay) control.play() }
    val labels = mediaLabels(control)
    MediaFrame(img, control, modifier.semantics { contentDescription = labels.name; stateDescription = labels.status; role = Role.Button }
        .clickable(onClickLabel = labels.action) { control.play() })
}

/**
 * PowerPoint's play bar under a selected video or sound in the editor: play or pause, how far it has
 * played, its clock — its picture plays the video meanwhile.
 */
@Composable
internal fun MediaBar(control: MediaControl, modifier: Modifier) {
    val playback = control.playback
    val labels = mediaLabels(control)
    val state = playback.state
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(ShowColors.bar).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(ShowColors.on)
                .semantics { contentDescription = labels.action; stateDescription = labels.status; role = Role.Button }
                .clickable { control.play() },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                val w = size.width; val h = size.height
                if (state == MediaPlayback.State.PLAYING) {
                    drawRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.12f, 0f), androidx.compose.ui.geometry.Size(w * 0.26f, h))
                    drawRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.62f, 0f), androidx.compose.ui.geometry.Size(w * 0.26f, h))
                } else drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.15f, 0f); lineTo(w, h / 2f); lineTo(w * 0.15f, h); close() }, Color.White)
            }
        }
        Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
            Box(Modifier.fillMaxWidth(playback.fraction).fillMaxHeight().background(Color.White))
        }
        // Its clock once its length is known (it is only once it has been opened to play).
        if (playback.durationMs > 0) Text(playback.clock, color = Color.White, fontSize = 11.sp, maxLines = 1)
    }
}

/** The video's visible part — the picture's crop — stretched over the view, as the poster is. */
private fun crop(view: TextureView, img: SlideImage) {
    val w = view.width.toFloat(); val h = view.height.toFloat()
    val keepW = (1f - img.cropL - img.cropR).coerceAtLeast(0.01f); val keepH = (1f - img.cropT - img.cropB).coerceAtLeast(0.01f)
    if (w <= 0f || h <= 0f || (keepW == 1f && keepH == 1f)) { view.setTransform(null); return }
    view.setTransform(android.graphics.Matrix().apply {
        postScale(1f / keepW, 1f / keepH)
        postTranslate(-img.cropL / keepW * w, -img.cropT / keepH * h)
    })
}

/** m:ss (h:mm:ss past an hour). */
internal fun clock(ms: Int): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val l = java.util.Locale.getDefault()
    return if (s >= 3600) String.format(l, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(l, "%d:%02d", s / 60, s % 60)
}
