package com.therealityteam.aeropages.format.pdf

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.therealityteam.aeropages.model.ArrowHeads
import com.therealityteam.aeropages.model.Freeform
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideShape
import kotlin.math.cos
import kotlin.math.sin

/**
 * A shape drawn as it looks — on a PDF page, and on screen (the editors draw through it too, so the
 * two never differ): its preset outline — or its freeform one, part by part — filled (solid or a
 * linear gradient) and stroked, dashed as it says; a line corner to corner with its arrowheads; turned by its rotation and mirrored by its
 * flips; its text (not mirrored) laid out inside DrawingML's insets and placed top, middle or bottom.
 */
internal object ShapePainter {

    /**
     * [sh] in the frame ([x], [y], [w] × [h]) of [c]. [scale] is the page's size for a point of the
     * shape's own (its outline width, its text insets); [textScale] sets its text's sizes; [text]
     * false leaves the text to the caller (an equation). Text setting no colour is [textColor] —
     * by default one that reads over its fill (dark where it has none).
     */
    fun draw(c: Canvas, sh: SlideShape, x: Float, y: Float, w: Float, h: Float, scale: Float = 1f, textScale: Float = 1f, text: Boolean = true,
             textColor: Long = com.therealityteam.aeropages.model.TextColors.over(null, sh.textBackdrop(null))) {
        c.withRotation(sh.rotationDeg, x + w / 2f, y + h / 2f) {
            shape(this, sh, x, y, w, h, scale)
            if (text && sh.type != ShapeType.LINE && sh.box.text.isNotBlank()) {
                val insetX = 7.2f * scale
                val insetY = 3.6f * scale
                val boxW = ((w - 2 * insetX) / textScale).coerceAtLeast(1f)
                // Unwrapped text: its lines as long as they are, the block placed by its alignment in the box.
                val layout = if (!sh.noWrap) SlideTextLayout.of(sh.box, boxW, null, textColor) else {
                    val wide = SlideTextLayout.of(sh.box, 100_000f, null, textColor)
                    val longest = (0 until wide.lineCount).maxOfOrNull { wide.getLineWidth(it) } ?: 0f
                    SlideTextLayout.of(sh.box, kotlin.math.ceil(longest) + 1f, null, textColor)
                }
                val th = layout.height * textScale
                val tw = layout.width * textScale
                val top = when (sh.anchor) {
                    ShapeAnchor.TOP -> y + insetY
                    ShapeAnchor.BOTTOM -> y + h - insetY - th
                    ShapeAnchor.CENTER -> y + (h - th) / 2f
                }
                val left = if (!sh.noWrap) x + insetX else when (sh.box.align) {
                    com.therealityteam.aeropages.model.TextAlign.CENTER -> x + (w - tw) / 2f
                    com.therealityteam.aeropages.model.TextAlign.RIGHT -> x + w - insetX - tw
                    else -> x + insetX
                }
                withTranslation(left, top) { withScale(textScale, textScale) { layout.draw(this) } }
            }
        }
    }

    /**
     * [sh]'s geometry — fill, outline, a line and its arrowheads — mirrored by its flips but not
     * turned (the screen turns the whole shape, text and all), in ([x], [y], [w] × [h]) of [c];
     * [scale] is the canvas's size for a point.
     */
    fun shape(c: Canvas, sh: SlideShape, x: Float, y: Float, w: Float, h: Float, scale: Float = 1f) {
        sh.shadow?.let { shadow(c, sh, it, x, y, w, h, scale) }
        c.withScale(if (sh.flipH) -1f else 1f, if (sh.flipV) -1f else 1f, x + w / 2f, y + h / 2f) { geometry(this, sh, x, y, w, h, scale) }
    }

    /**
     * [sh]'s outer shadow: its silhouette — its fill's outline filled, its outline stroked — in the
     * shadow's colour, blurred, moved by the shadow's offset on the page (the canvas is turned with
     * the shape, so the offset is turned back). A shape with neither fill nor outline casts none.
     */
    private fun shadow(c: Canvas, sh: SlideShape, s: com.therealityteam.aeropages.model.ShapeShadow, x: Float, y: Float, w: Float, h: Float, scale: Float) {
        if (sh.fill == null && sh.border == null) return
        val a = Math.toRadians(-sh.rotationDeg.toDouble())
        val dx = ((s.dxPt * cos(a) - s.dyPt * sin(a)) * scale).toFloat()
        val dy = ((s.dxPt * sin(a) + s.dyPt * cos(a)) * scale).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color.toInt()
            if (s.blurPt > 0f) maskFilter = android.graphics.BlurMaskFilter((s.blurPt * scale).coerceAtLeast(0.5f), android.graphics.BlurMaskFilter.Blur.NORMAL)
            val outline = sh.border != null
            style = when {
                sh.fill != null && outline -> Paint.Style.FILL_AND_STROKE
                sh.fill != null -> Paint.Style.FILL
                else -> Paint.Style.STROKE
            }
            strokeWidth = (sh.borderWidthPt * scale).coerceAtLeast(0.5f)
            strokeJoin = Paint.Join.ROUND
        }
        c.withTranslation(dx, dy) {
            withScale(if (sh.flipH) -1f else 1f, if (sh.flipV) -1f else 1f, x + w / 2f, y + h / 2f) {
                val free = outlineOf(sh, w, h)
                when {
                    sh.type == ShapeType.LINE -> { paint.style = Paint.Style.STROKE; drawLine(x, y, x + w, y + h, paint) }
                    sh.type == ShapeType.FREEFORM && free != null -> free.parts.forEach { part ->
                        if (part.filled && sh.fill != null || part.stroked && sh.border != null) drawPath(freeformPath(part, x, y, w, h), paint)
                    }
                    else -> drawPath(path(sh.type, x, y, w, h), paint)
                }
            }
        }
    }

    private fun geometry(c: Canvas, sh: SlideShape, x: Float, y: Float, w: Float, h: Float, scale: Float) {
        val width = (sh.borderWidthPt * scale).coerceAtLeast(0.5f)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            strokeJoin = Paint.Join.ROUND
            val dashes = sh.dash.intervals(width)
            if (dashes.isNotEmpty()) pathEffect = DashPathEffect(dashes, 0f)
        }
        if (sh.type == ShapeType.LINE) {
            val color = (sh.border ?: return).toInt()
            stroke.color = color
            val begin = ArrowHeads.head(sh.beginArrow, x, y, x + w, y + h, width, ArrowHeads.MIN_STROKE_PT * scale)
            val end = ArrowHeads.head(sh.endArrow, x + w, y + h, x, y, width, ArrowHeads.MIN_STROKE_PT * scale)
            val (x1, y1, x2, y2) = ArrowHeads.trimmed(x, y, x + w, y + h, begin, end)
            c.drawLine(x1, y1, x2, y2, stroke)
            // Heads are solid whatever the line's dashes; an open arrow is stroked as wide as the line.
            val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = width; strokeJoin = Paint.Join.MITER }
            listOfNotNull(begin, end).forEach { head -> drawHead(c, head, solid) }
            return
        }
        val fillPaint = sh.fill?.let { fill ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fill.toInt(); style = Paint.Style.FILL
                // A gradient: linear along the shape's angle, axial, radial or rectangular from its centre — its
                // colours' own alpha only (the paint's would scale the shader's, and a first colour may be clear).
                sh.fillGradient?.let { g ->
                    color = 0xFF000000.toInt()
                    shader = GradientShaders.shader(sh.gradientKind, fill, g, sh.gradientAngle, sh.gradientCx, sh.gradientCy, x, y, w, h)
                }
            }
        }
        sh.border?.let { stroke.color = it.toInt() }
        val free = outlineOf(sh, w, h)
        if (sh.type == ShapeType.FREEFORM && free != null) {
            // Each part filled and outlined as it says: all the fills first, the outlines over them.
            val paths = free.parts.map { it to freeformPath(it, x, y, w, h) }
            fillPaint?.let { p ->
                paths.forEach { (part, path) ->
                    if (!part.filled) return@forEach
                    c.drawPath(path, p)
                    // A shaded part: its fill, then black or white over it as far as its shade says.
                    if (part.shade != 0f) c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL
                        color = if (part.shade < 0f) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                        alpha = (kotlin.math.abs(part.shade).coerceAtMost(1f) * 255).toInt()
                    })
                }
            }
            val color = sh.border ?: return
            paths.forEach { (part, path) -> if (part.stroked) c.drawPath(path, stroke) }
            // An open outline's arrowheads, as a line's: at its first point and at its last.
            if (!sh.beginArrow.isNone || !sh.endArrow.isNone) free.openEnds()?.let { e ->
                fun px(u: Float) = x + u * w
                fun py(v: Float) = y + v * h
                val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt(); strokeWidth = width; strokeJoin = Paint.Join.MITER }
                ArrowHeads.head(sh.beginArrow, px(e[0]), py(e[1]), px(e[2]), py(e[3]), width, ArrowHeads.MIN_STROKE_PT * scale)?.let { drawHead(c, it, solid) }
                ArrowHeads.head(sh.endArrow, px(e[4]), py(e[5]), px(e[6]), py(e[7]), width, ArrowHeads.MIN_STROKE_PT * scale)?.let { drawHead(c, it, solid) }
            }
            return
        }
        val path = path(sh.type, x, y, w, h)
        fillPaint?.let { c.drawPath(path, it) }
        if (sh.border != null) c.drawPath(path, stroke)
    }

    /** A freeform shape's outline at [w] × [h]: a preset's worked out at that size, else the one it keeps. */
    private fun outlineOf(sh: SlideShape, w: Float, h: Float): Freeform? =
        sh.preset?.let { com.therealityteam.aeropages.format.pptx.PresetGeometries.outline(it, sh.presetAdjust, w, h) } ?: sh.path

    /** A freeform part's figures in ([x], [y], [w] × [h]); a figure inside another is a hole. */
    private fun freeformPath(part: Freeform.Part, x: Float, y: Float, w: Float, h: Float): Path = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        for (op in part.ops) when (op) {
            is Freeform.Move -> moveTo(x + op.x * w, y + op.y * h)
            is Freeform.Line -> lineTo(x + op.x * w, y + op.y * h)
            is Freeform.Cubic -> cubicTo(x + op.x1 * w, y + op.y1 * h, x + op.x2 * w, y + op.y2 * h, x + op.x * w, y + op.y * h)
            Freeform.Close -> close()
        }
    }

    /** An arrowhead: its outline filled, or (an open arrow) its two strokes. */
    private fun drawHead(c: Canvas, head: ArrowHeads.Head, paint: Paint) {
        val pts = head.points
        val p = Path().apply {
            moveTo(pts[0], pts[1])
            for (i in 2 until pts.size step 2) lineTo(pts[i], pts[i + 1])
            if (head.filled) close()
        }
        paint.style = if (head.filled) Paint.Style.FILL else Paint.Style.STROKE
        c.drawPath(p, paint)
    }

    /** The outline of a shape of [type] filling ([x], [y], [w] × [h]), as Office's preset geometries draw it. */
    fun path(type: ShapeType, x: Float, y: Float, w: Float, h: Float): Path = Path().apply {
        when (type) {
            ShapeType.ELLIPSE -> addOval(RectF(x, y, x + w, y + h), Path.Direction.CW)
            ShapeType.ROUNDED -> { val r = minOf(w, h) * 0.16667f; addRoundRect(RectF(x, y, x + w, y + h), r, r, Path.Direction.CW) }
            ShapeType.TRIANGLE -> { moveTo(x + w / 2f, y); lineTo(x + w, y + h); lineTo(x, y + h); close() }
            ShapeType.STAR -> {
                val cx = x + w / 2f; val cy = y + h / 2f
                for (i in 0 until 10) {
                    val f = if (i % 2 == 0) 1f else 0.38f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val px = cx + (w / 2f * f * cos(a)).toFloat(); val py = cy + (h / 2f * f * sin(a)).toFloat()
                    if (i == 0) moveTo(px, py) else lineTo(px, py)
                }
                close()
            }
            ShapeType.ARROW -> {
                val head = minOf(w * 0.4f, h * 0.5f)
                moveTo(x, y + h * 0.25f); lineTo(x + w - head, y + h * 0.25f); lineTo(x + w - head, y); lineTo(x + w, y + h * 0.5f)
                lineTo(x + w - head, y + h); lineTo(x + w - head, y + h * 0.75f); lineTo(x, y + h * 0.75f); close()
            }
            else -> addRect(RectF(x, y, x + w, y + h), Path.Direction.CW) // RECT, TEXTBOX
        }
    }
}
