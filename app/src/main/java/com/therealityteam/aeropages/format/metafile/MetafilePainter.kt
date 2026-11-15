package com.therealityteam.aeropages.format.metafile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import com.therealityteam.aeropages.format.pdf.PdfFaces
import com.therealityteam.aeropages.model.Freeform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a [MetafilePicture] on an Android canvas — its paths filled and outlined (hairlines a pixel
 * wide however large it shows, dashes as long as their pen is wide), its text in its faces, turned
 * and placed as GDI places it, its bitmaps — and renders an EMF's or a WMF's bytes to a bitmap for
 * the places that show pictures as bitmaps (the editors, the thumbnails, the PDF).
 */
internal object MetafilePainter {

    /** The largest side a picture is rendered at. */
    private const val MAX_SIDE = 4096

    /**
     * [data] rendered at its own aspect, as large as fits [maxW] × [maxH] pixels — or, [cover], as
     * small as still covers them; null when it is no metafile or draws nothing.
     */
    fun rasterize(data: ByteArray, maxW: Float, maxH: Float, cover: Boolean = false): Bitmap? = runCatching {
        val pic = Metafiles.read(data) ?: return null
        if (pic.items.isEmpty()) return null
        val pw = if (pic.widthPt > 0f) pic.widthPt else abs(pic.width)
        val ph = if (pic.heightPt > 0f) pic.heightPt else abs(pic.height)
        val s = if (cover) max(maxW / pw, maxH / ph) else min(maxW / pw, maxH / ph)
        val w = (pw * s).roundToInt().coerceIn(1, MAX_SIDE)
        val h = (ph * s).roundToInt().coerceIn(1, MAX_SIDE)
        createBitmap(w, h).also { draw(Canvas(it), pic, w.toFloat(), h.toFloat()) }
    }.getOrNull()

    /** [pic] in the box [w] × [h] at the canvas's origin, nothing drawn outside it. */
    fun draw(c: Canvas, pic: MetafilePicture, w: Float, h: Float) {
        if (pic.width == 0f || pic.height == 0f) return
        val sx = w / pic.width; val sy = h / pic.height
        // One output pixel in the picture's units: a hairline's width, a dash's least length.
        val pixel = 1f / min(abs(sx), abs(sy))
        c.withClip(0f, 0f, w, h) {
            scale(sx, sy)
            translate(-pic.left, -pic.top)
            for (item in pic.items) withSave {
                item.clip?.let { r -> clipRect(min(r[0], r[2]), min(r[1], r[3]), max(r[0], r[2]), max(r[1], r[3])) }
                when (item) {
                    is MetafilePath -> path(this, item, pixel)
                    is MetafileText -> text(this, item)
                    is MetafileImage -> image(this, item)
                }
            }
        }
    }

    private fun path(c: Canvas, p: MetafilePath, pixel: Float) {
        val path = Path().apply {
            fillType = if (p.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            for (op in p.ops) when (op) {
                is Freeform.Move -> moveTo(op.x, op.y)
                is Freeform.Line -> lineTo(op.x, op.y)
                is Freeform.Cubic -> cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
                Freeform.Close -> close()
            }
        }
        p.fill?.let { color -> c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }) }
        p.stroke?.let { color ->
            val width = if (p.strokeWidth < pixel) 0f else p.strokeWidth
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE; strokeWidth = width
                strokeCap = when (p.cap) { 1 -> Paint.Cap.SQUARE; 2 -> Paint.Cap.BUTT; else -> Paint.Cap.ROUND }
                strokeJoin = when (p.join) { 1 -> Paint.Join.BEVEL; 2 -> Paint.Join.MITER; else -> Paint.Join.ROUND }
                p.dash?.let { d -> val unit = max(width, pixel); pathEffect = DashPathEffect(FloatArray(d.size) { d[it] * unit }, 0f) }
            })
        }
    }

    private fun text(c: Canvas, t: MetafileText) {
        if (t.size <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = t.color; textSize = t.size
            typeface = PdfFaces.of(t.face, t.bold, t.italic)
            isUnderlineText = t.underline; isStrikeThruText = t.strike
        }
        val fm = paint.fontMetrics
        c.withSave {
            // Turned anticlockwise as it shows (the canvas turns clockwise).
            if (t.angleDeg != 0f) rotate(-t.angleDeg, t.x, t.y)
            val baseline = when (t.vAlign) { 0 -> t.y - fm.ascent; 2 -> t.y - fm.descent; else -> t.y }
            val adv = t.advances?.takeIf { it.size >= t.text.length }
            val width = adv?.let { a -> a.take(t.text.length).sum() } ?: paint.measureText(t.text)
            val left = when (t.align) { 1 -> t.x - width / 2f; 2 -> t.x - width; else -> t.x }
            t.background?.let { bg -> drawRect(left, baseline + fm.ascent, left + width, baseline + fm.descent, Paint().apply { color = bg }) }
            if (adv == null) { drawText(t.text, left, baseline, paint); return@withSave }
            // Each character where its advance puts it (a surrogate pair drawn as one).
            var x = left
            var i = 0
            while (i < t.text.length) {
                val n = if (Character.isHighSurrogate(t.text[i]) && i + 1 < t.text.length) 2 else 1
                drawText(t.text, i, i + n, x, baseline, paint)
                x += adv[i] + (if (n == 2) adv[i + 1] else 0f)
                i += n
            }
        }
    }

    private fun image(c: Canvas, m: MetafileImage) {
        val bmp = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(m.data, 0, m.data.size, bounds)
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            BitmapFactory.decodeByteArray(m.data, 0, m.data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull() ?: return
        val src = m.src?.let { s -> Rect(s[0], s[1], s[0] + s[2], s[1] + s[3]) }
        val dst = RectF(min(m.x0, m.x1), min(m.y0, m.y1), max(m.x0, m.x1), max(m.y0, m.y1))
        c.withSave {
            // Mirrored when its box runs the other way.
            if (m.x1 < m.x0) scale(-1f, 1f, dst.centerX(), dst.centerY())
            if (m.y1 < m.y0) scale(1f, -1f, dst.centerX(), dst.centerY())
            drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
    }
}
