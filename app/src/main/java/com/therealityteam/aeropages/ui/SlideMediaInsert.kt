package com.therealityteam.aeropages.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import com.therealityteam.aeropages.format.MediaTypes
import com.therealityteam.aeropages.model.SlideMedia
import java.io.ByteArrayOutputStream
import java.io.File

/** A video or sound read to insert: the media, and the picture that stands for it on the slide (PNG, its size in points). */
internal class LoadedMedia(val media: SlideMedia, val poster: ByteArray, val widthPt: Float, val heightPt: Float)

/** Why a file could not be inserted. */
internal sealed class MediaProblem { object Unreadable : MediaProblem(); object TooLarge : MediaProblem() }

/**
 * Reads [path] to insert as video or sound: the file embedded (as PowerPoint embeds it), a video's poster a frame from its start
 * (a dark frame when the device cannot decode one), a sound's a speaker.
 */
internal fun loadMediaForInsert(path: String): Result<LoadedMedia> {
    val file = File(path)
    val (kind, mime) = MediaTypes.of(file.name) ?: return Result.failure(MediaException(MediaProblem.Unreadable))
    if (!file.isFile) return Result.failure(MediaException(MediaProblem.Unreadable))
    if (file.length() > MediaTypes.MAX_BYTES) return Result.failure(MediaException(MediaProblem.TooLarge))
    val data = runCatching { file.readBytes() }.getOrElse { return Result.failure(MediaException(MediaProblem.Unreadable)) }
    val poster = if (kind == SlideMedia.Kind.VIDEO) firstFrame(path) ?: playPoster() else speakerIcon()
    val png = ByteArrayOutputStream().also { poster.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    // A frame at 96 dpi, as a picture's pixels are taken; the slide fits it in.
    val loaded = LoadedMedia(SlideMedia(kind, mime, data, null, file.name), png, poster.width * 0.75f, poster.height * 0.75f)
    poster.recycle()
    return Result.success(loaded)
}

internal class MediaException(val problem: MediaProblem) : Exception()

/**
 * A frame from the video's start — a tenth of the way in, a second at most, past the black many videos open with —
 * no wider than 1280 pixels; null when it cannot be decoded.
 */
private fun firstFrame(path: String): Bitmap? {
    val r = android.media.MediaMetadataRetriever()
    return try {
        r.setDataSource(path)
        val durationMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        // Early, else halfway through: a frame that is not all black (a decoder that cannot read the video gives black).
        val frame = listOf((durationMs * 100).coerceAtMost(1_000_000L), durationMs * 500, 0L).distinct().firstNotNullOfOrNull { atUs ->
            r.getFrameAtTime(atUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.takeIf { f -> !allDark(f).also { dark -> if (dark) f.recycle() } }
        } ?: return null
        if (frame.width <= 1280) frame
        else frame.scale(1280, (frame.height * 1280f / frame.width).toInt().coerceAtLeast(1)).also { frame.recycle() }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}

/** Whether [f] is black all over (sampled on a grid): what a decoder that cannot read the video hands back. */
private fun allDark(f: Bitmap): Boolean {
    for (y in 0 until 12) for (x in 0 until 12) {
        val c = f[(f.width - 1) * x / 11, (f.height - 1) * y / 11]
        if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) > 48) return false
    }
    return true
}

/** A 16:9 dark frame: a video's picture when no frame could be read (the slide marks it as a video). */
private fun playPoster(): Bitmap = createBitmap(640, 360).also { Canvas(it).drawColor(0xFF202124.toInt()) }

/** A speaker on a light rounded square: a sound's picture (PowerPoint shows one). */
private fun speakerIcon(): Bitmap {
    val b = createBitmap(256, 256)
    val c = Canvas(b)
    c.drawRoundRect(RectF(8f, 8f, 248f, 248f), 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF1F3F4.toInt() })
    val ink = 0xFF3C4043.toInt()
    c.drawPath(Path().apply {
        moveTo(56f, 104f); lineTo(94f, 104f); lineTo(140f, 62f); lineTo(140f, 194f); lineTo(94f, 152f); lineTo(56f, 152f); close()
    }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink })
    val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND }
    c.drawArc(RectF(128f, 92f, 180f, 164f), -50f, 100f, false, wave)
    c.drawArc(RectF(120f, 66f, 210f, 190f), -50f, 100f, false, wave)
    return b
}
