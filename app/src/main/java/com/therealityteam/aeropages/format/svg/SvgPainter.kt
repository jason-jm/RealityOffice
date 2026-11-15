package com.therealityteam.aeropages.format.svg

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.core.graphics.withSave
import com.therealityteam.aeropages.model.Freeform
import kotlin.math.roundToInt

/**
 * Draws an [SvgPicture] on an Android canvas — shapes with their fills, outlines, dashes and
 * gradients, text, embedded pictures, groups faded as one, clips — and renders SVG bytes to a
 * bitmap for the places that show pictures as bitmaps (the editors, the thumbnails, the PDF).
 */
internal object SvgPainter {

    /** The largest side a picture is rendered at. */
    private const val MAX_SIDE = 4096

    /**
     * [data] rendered at its own aspect, as large as fits [maxW] × [maxH] pixels — or, [cover], as
     * small as still covers them (for a frame of another aspect); null when it is no SVG or cannot be drawn.
     */
    fun rasterize(data: ByteArray, maxW: Float, maxH: Float, cover: Boolean = false): Bitmap? = runCatching {
        val pic = SvgReader.read(data) ?: return null
        val s = if (cover) maxOf(maxW / pic.width, maxH / pic.height) else minOf(maxW / pic.width, maxH / pic.height)
        val w = (pic.width * s).roundToInt().coerceIn(1, MAX_SIDE)
        val h = (pic.height * s).roundToInt().coerceIn(1, MAX_SIDE)
        createBitmap(w, h).also { draw(Canvas(it), pic, w.toFloat(), h.toFloat()) }
    }.getOrNull()

    /** [pic] in the box [w] × [h] at the canvas's origin, its view box fitted as it says, nothing drawn outside the box. */
    fun draw(c: Canvas, pic: SvgPicture, w: Float, h: Float) {
        val f = pic.aspect.fit(pic.viewBox[0], pic.viewBox[1], pic.viewBox[2], pic.viewBox[3], w, h)
        c.withClip(0f, 0f, w, h) {
            translate(f[2], f[3]); scale(f[0], f[1])
            pic.items.forEach { item(this, it, depth = 0) }
        }
    }

    private fun matrixOf(m: FloatArray): Matrix = Matrix().apply { setValues(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f)) }

    private fun pathOf(ops: List<Freeform.Op>, evenOdd: Boolean): Path = Path().apply {
        fillType = if (evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        for (op in ops) when (op) {
            is Freeform.Move -> moveTo(op.x, op.y)
            is Freeform.Line -> lineTo(op.x, op.y)
            is Freeform.Cubic -> cubicTo(op.x1, op.y1, op.x2, op.y2, op.x, op.y)
            Freeform.Close -> close()
        }
    }

    private fun item(c: Canvas, it: SvgItem, depth: Int) {
        if (it.opacity <= 0f) return
        c.withSave {
            it.matrix?.let { m -> concat(matrixOf(m)) }
            it.clip?.let { clip -> clip(this, clip, it) }
            when (it) {
                is SvgGroup -> {
                    // A layer the saved state's restore puts down again, faded.
                    if (it.opacity < 1f) saveLayerAlpha(null, (it.opacity * 255).roundToInt())
                    it.items.forEach { child -> item(this, child, depth) }
                }
                is SvgShape -> shape(this, it)
                is SvgText -> text(this, it)
                is SvgImage -> image(this, it, depth)
            }
        }
    }

    /** What [item] covers in its own units (after its transform, before what holds it), for clips in box units. */
    /** [path]'s bounds. API 35 deprecates this two-argument form for a one-argument one that arrives only in API 36. */
    @Suppress("DEPRECATION")
    private fun boundsOf(path: android.graphics.Path): RectF = RectF().also { path.computeBounds(it, true) }

    private fun boundsOf(item: SvgItem): RectF? {
        val r: RectF? = when (item) {
            is SvgShape -> boundsOf(pathOf(item.ops, false))
            is SvgImage -> RectF(item.x, item.y, item.x + item.w, item.y + item.h)
            is SvgGroup -> item.items.mapNotNull { child -> boundsOf(child)?.let { b -> child.matrix?.let { m -> RectF(b).also { matrixOf(m).mapRect(it) } } ?: b } }
                .reduceOrNull { a, b -> RectF(a).also { it.union(b) } }
            is SvgText -> null
        }
        return r
    }

    private fun clip(c: Canvas, clip: SvgClip, target: SvgItem) {
        val path = Path()
        for (part in clip.parts) {
            val p = pathOf(part.ops, part.evenOdd)
            part.matrix?.let { p.transform(matrixOf(it)) }
            path.op(p, Path.Op.UNION)
        }
        clip.matrix?.let { path.transform(matrixOf(it)) }
        if (clip.bboxUnits) {
            val b = boundsOf(target) ?: return
            path.transform(Matrix().apply { setScale(b.width(), b.height()); postTranslate(b.left, b.top) })
        }
        c.clipPath(path)
    }

    private fun shape(c: Canvas, s: SvgShape) {
        val path = pathOf(s.ops, s.evenOdd)
        val bounds = boundsOf(path)
        // Fill and outline fade together as one when the shape has both.
        val layered = s.opacity < 1f && s.fill != null && s.stroke != null
        if (layered) c.saveLayerAlpha(null, (s.opacity * 255).roundToInt())
        val fade = if (layered) 1f else s.opacity
        s.fill?.let { p -> paintOf(p, bounds, fade * s.fillOpacity)?.let { c.drawPath(path, it.apply { style = Paint.Style.FILL }) } }
        s.stroke?.let { p ->
            paintOf(p, bounds, fade * s.strokeOpacity)?.let { paint ->
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s.strokeWidth
                paint.strokeCap = when (s.cap) { 1 -> Paint.Cap.ROUND; 2 -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
                paint.strokeJoin = when (s.join) { 1 -> Paint.Join.ROUND; 2 -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
                paint.strokeMiter = s.miterLimit
                s.dashes?.let { d -> paint.pathEffect = DashPathEffect(d, s.dashOffset) }
                c.drawPath(path, paint)
            }
        }
    }

    /** A paint for [p] over a shape whose box is [bounds], faded to [opacity]; null when it paints nothing. */
    private fun paintOf(p: SvgPaint, bounds: RectF, opacity: Float): Paint? {
        if (opacity <= 0f) return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (p) {
            is SvgColor -> {
                val a = ((p.argb ushr 24) * opacity).roundToInt().coerceIn(0, 255)
                if (a == 0) return null
                paint.color = (a shl 24) or (p.argb and 0xFFFFFF)
            }
            is SvgGradient -> {
                // A gradient in the box of a shape with no width or height paints its last colour.
                if (p.bboxUnits && (bounds.width() <= 0f || bounds.height() <= 0f)) {
                    paint.color = p.stops.last().second
                    paint.alpha = ((paint.alpha) * opacity).roundToInt()
                    return paint
                }
                paint.shader = shaderOf(p, bounds)
                paint.alpha = (opacity * 255).roundToInt().coerceIn(0, 255)
            }
        }
        return paint
    }

    private fun shaderOf(g: SvgGradient, bounds: RectF): Shader {
        val colors = g.stops.map { it.second }.toIntArray()
        val positions = g.stops.map { it.first }.toFloatArray()
        val tile = when (g.spread) { 1 -> Shader.TileMode.MIRROR; 2 -> Shader.TileMode.REPEAT; else -> Shader.TileMode.CLAMP }
        val shader: Shader = if (g.radial) {
            val r = g.r.coerceAtLeast(1e-4f)
            if (g.x2 != g.x1 || g.y2 != g.y1) {
                // A focus off the centre: the two-point conical gradient SVG's radial one is.
                RadialGradient(g.x2, g.y2, 0f, g.x1, g.y1, r, colors.map { Color.pack(it) }.toLongArray(), positions, tile)
            } else RadialGradient(g.x1, g.y1, r, colors, positions, tile)
        } else LinearGradient(g.x1, g.y1, g.x2, g.y2, colors, positions, tile)
        val m = Matrix()
        if (g.bboxUnits) { m.setScale(bounds.width(), bounds.height()); m.postTranslate(bounds.left, bounds.top) }
        g.matrix?.let { m.preConcat(matrixOf(it)) }
        shader.setLocalMatrix(m)
        return shader
    }

    private fun typeface(family: String?, bold: Boolean, italic: Boolean): Typeface {
        val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        val base = when (family?.lowercase()) {
            null, "", "sans-serif", "sans", "arial", "helvetica" -> Typeface.SANS_SERIF
            "serif", "times", "times new roman" -> Typeface.SERIF
            "monospace", "courier", "courier new" -> Typeface.MONOSPACE
            else -> Typeface.create(family, Typeface.NORMAL)
        }
        return Typeface.create(base, style)
    }

    /** Text: each chunk (a run placed where it says, and the runs after it) anchored at its start, middle or end. */
    private fun text(c: Canvas, t: SvgText) {
        val paints = t.spans.map { s ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = s.size
                typeface = typeface(s.family, s.bold, s.italic)
            }
        }
        var penX = 0f; var penY = 0f
        var i = 0
        while (i < t.spans.size) {
            // The chunk: this run and those after it up to the next that is placed.
            var j = i + 1
            while (j < t.spans.size && t.spans[j].x == null) j++
            var width = 0f
            for (k in i until j) width += paints[k].measureText(t.spans[k].text) + (if (k > i) t.spans[k].dx else 0f)
            val first = t.spans[i]
            penX = (first.x ?: penX) + first.dx
            penY = (first.y ?: penY) + first.dy
            penX -= when (first.anchor) { 1 -> width / 2f; 2 -> width; else -> 0f }
            for (k in i until j) {
                val s = t.spans[k]
                if (k > i) { penX += s.dx; penY = (s.y ?: penY) + s.dy }
                val paint = paints[k]
                val fill = s.fill
                val w = paint.measureText(s.text)
                if (fill != null && s.fillOpacity > 0f) {
                    val look = paintOf(fill, RectF(penX, penY - s.size, penX + w, penY), s.fillOpacity) ?: Paint()
                    paint.color = look.color; paint.shader = look.shader; paint.alpha = look.alpha
                    c.drawText(s.text, penX, penY, paint)
                }
                penX += w
            }
            i = j
        }
    }

    private fun image(c: Canvas, im: SvgImage, depth: Int) {
        val bmp = when {
            SvgReader.isSvg(im.data) -> if (depth < 3) rasterize(im.data, im.w * 2f, im.h * 2f) else null
            else -> runCatching { BitmapFactory.decodeByteArray(im.data, 0, im.data.size) }.getOrNull()
        } ?: return
        val f = im.aspect.fit(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), im.w, im.h)
        c.withClip(im.x, im.y, im.x + im.w, im.y + im.h) {
            translate(im.x + f[2], im.y + f[3]); scale(f[0], f[1])
            drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
    }
}
