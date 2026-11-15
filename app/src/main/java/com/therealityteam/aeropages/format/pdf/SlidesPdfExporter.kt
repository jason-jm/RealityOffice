package com.therealityteam.aeropages.format.pdf

import android.graphics.Canvas
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withClip
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import androidx.core.graphics.withScale
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.format.pdf.PdfFaces.drawPdfText
import java.io.OutputStream

/**
 * Renders a [Presentation] to PDF — one page per (non-hidden) slide, drawing the
 * background, title, body lines, free shapes, tables and charts with android.graphics.
 */
object SlidesPdfExporter {


    /** What a PDF page holds: one slide, a handout grid of slides, or a slide over its notes. */
    enum class Layout(val perPage: Int) { SLIDES(1), HANDOUT_2(2), HANDOUT_3(3), HANDOUT_4(4), HANDOUT_6(6), NOTES(1) }

    fun export(p: Presentation, out: OutputStream, layout: Layout = Layout.SLIDES) {
        // A page point per slide point: the slide's text, set in points, sits on it as it does in PowerPoint.
        val w = Math.round(p.slideSize.widthPt)
        val h = Math.round(p.slideSize.heightPt)
        val pdf = PdfDocument()
        val visible = p.slides.withIndex().filter { !it.value.hidden }
        var pageNo = 0
        when (layout) {
            Layout.SLIDES -> visible.forEach { (index, slide) ->
                pageNo++
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
                drawSlide(page.canvas, slide, p, w.toFloat(), h.toFloat())
                drawFooter(page.canvas, p, slide, index + 1, w.toFloat(), h.toFloat())
                pdf.finishPage(page)
            }
            Layout.NOTES -> visible.forEach { (index, slide) ->
                pageNo++
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(PORTRAIT_W, PORTRAIT_H, pageNo).create())
                drawNotesPage(page.canvas, p, slide, index + 1, w.toFloat(), h.toFloat())
                pdf.finishPage(page)
            }
            else -> visible.chunked(layout.perPage).forEach { group ->
                pageNo++
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(PORTRAIT_W, PORTRAIT_H, pageNo).create())
                drawHandoutPage(page.canvas, p, group, layout, pageNo, w.toFloat(), h.toFloat())
                pdf.finishPage(page)
            }
        }
        if (pageNo == 0) { // entirely hidden deck still produces one blank page
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
            pdf.finishPage(page)
        }
        pdf.writeTo(out)
        pdf.close()
    }

    private const val PORTRAIT_W = 595   // A4 in points
    private const val PORTRAIT_H = 842
    private const val MARGIN = 40f

    /** Draws [slide] scaled into the box at ([x], [y]) of width [boxW] (aspect kept), with a hairline frame. */
    private fun drawThumb(c: Canvas, p: Presentation, slide: Slide, number: Int, x: Float, y: Float, boxW: Float, w: Float, h: Float): Float {
        val scale = boxW / w
        val boxH = h * scale
        c.withSave {
            translate(x, y)
            scale(scale, scale)
            clipRect(0f, 0f, w, h)
            drawSlide(this, slide, p, w, h)
            drawFooter(this, p, slide, number, w, h)
        }
        c.drawRect(x, y, x + boxW, y + boxH, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f; color = 0xFF9E9E9E.toInt() })
        return boxH
    }

    /**
     * PowerPoint-style handouts: 2 / 4 / 6 slides in a grid; the 3-per-page layout puts note
     * lines beside each slide. Slide numbers sit under each thumbnail, the page number at the foot.
     */
    private fun drawHandoutPage(c: Canvas, p: Presentation, group: List<IndexedValue<Slide>>, layout: Layout, pageNo: Int, w: Float, h: Float) {
        val pw = PORTRAIT_W.toFloat(); val ph = PORTRAIT_H.toFloat()
        val cols = if (layout == Layout.HANDOUT_4 || layout == Layout.HANDOUT_6) 2 else 1
        val rows = layout.perPage / cols
        val gap = 18f
        val innerW = pw - MARGIN * 2
        val innerH = ph - MARGIN * 2 - 24f
        val numPaint = textPaint(9f, false, 0xFF666666)
        if (layout == Layout.HANDOUT_3) {
            // Slide on the left (45% width), ruled note lines on the right, three rows.
            val boxW = innerW * 0.45f
            val rowH = innerH / rows
            group.forEachIndexed { i, (index, slide) ->
                val y = MARGIN + i * rowH + gap / 2
                val boxH = drawThumb(c, p, slide, index + 1, MARGIN, y, boxW, w, h)
                numPaint.textAlign = Paint.Align.LEFT
                c.drawPdfText((index + 1).toString(), MARGIN, y + boxH + 12f, numPaint)
                val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFBDBDBD.toInt(); strokeWidth = 0.5f }
                val lx = MARGIN + boxW + gap
                var ly = y + 14f
                while (ly < y + boxH) { c.drawLine(lx, ly, pw - MARGIN, ly, lines); ly += 16f }
            }
        } else {
            val cellW = (innerW - gap * (cols - 1)) / cols
            val boxW = cellW - 4f
            val cellH = (innerH - gap * (rows - 1)) / rows
            group.forEachIndexed { i, (index, slide) ->
                val col = i % cols; val row = i / cols
                val x = MARGIN + col * (cellW + gap) + 2f
                val boxH = h * (boxW / w)
                val y = MARGIN + row * (cellH + gap) + ((cellH - boxH - 14f) / 2f).coerceAtLeast(0f)
                drawThumb(c, p, slide, index + 1, x, y, boxW, w, h)
                numPaint.textAlign = Paint.Align.CENTER
                c.drawPdfText((index + 1).toString(), x + boxW / 2f, y + boxH + 12f, numPaint)
            }
        }
        numPaint.textAlign = Paint.Align.RIGHT
        c.drawPdfText(pageNo.toString(), pw - MARGIN, ph - MARGIN / 2, numPaint)
    }

    /** A notes page: the slide at the top (60% width), its speaker notes wrapped beneath, the slide number at the foot. */
    private fun drawNotesPage(c: Canvas, p: Presentation, slide: Slide, number: Int, w: Float, h: Float) {
        val pw = PORTRAIT_W.toFloat(); val ph = PORTRAIT_H.toFloat()
        val boxW = (pw - MARGIN * 2) * 0.6f
        val x = (pw - boxW) / 2f
        val boxH = drawThumb(c, p, slide, number, x, MARGIN, boxW, w, h)
        val np = textPaint(11f, false, 0xFF1A1A1A)
        var y = MARGIN + boxH + 28f
        val textW = pw - MARGIN * 2 - 20f
        slide.notes.split('\n').forEach { line ->
            if (line.isBlank()) { y += np.textSize * 0.8f; return@forEach }
            y = drawWrapped(c, line, np, MARGIN + 10f, y, textW, centered = false, centerX = pw / 2f) + 4f
            if (y > ph - MARGIN) return@forEach
        }
        val numPaint = textPaint(9f, false, 0xFF666666).apply { textAlign = Paint.Align.RIGHT }
        c.drawPdfText(number.toString(), pw - MARGIN, ph - MARGIN / 2, numPaint)
    }

    /**
     * [slide] — number [number] of [p] — as the PDF draws it, filling [bitmap]: the editor's slide
     * thumbnails, so they show what prints. Pictures are decoded no larger than they show.
     */
    fun renderSlide(bitmap: android.graphics.Bitmap, p: Presentation, slide: Slide, number: Int) {
        val w = p.slideSize.widthPt; val h = p.slideSize.heightPt
        val c = Canvas(bitmap)
        c.scale(bitmap.width / w, bitmap.height / h)
        drawSlide(c, slide, p, w, h, pixelsPerPoint = bitmap.width / w)
        drawFooter(c, p, slide, number, w, h)
        // Which pictures are a video or a sound, as the canvas marks them (a PDF prints the picture alone).
        slide.images.forEach { img -> if (!img.hidden) img.media?.let { drawMediaMark(c, img, it, w, h) } }
    }

    /** A thumbnail's play or speaker mark over a picture that stands for a video or a sound, sized to read at thumbnail size. */
    private fun drawMediaMark(c: Canvas, img: com.therealityteam.aeropages.model.SlideImage, media: com.therealityteam.aeropages.model.SlideMedia, w: Float, h: Float) {
        val cx = (img.xPct + img.wPct / 2f) * w; val cy = (img.yPct + img.hPct / 2f) * h
        val d = (minOf(img.wPct * w, img.hPct * h) * 0.36f).coerceIn(w * 0.05f, w * 0.12f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8C000000.toInt() }
        c.drawCircle(cx, cy, d / 2f, paint)
        paint.color = 0xFFFFFFFF.toInt()
        val mark = android.graphics.Path()
        if (media.kind == com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO) {
            val u = d * 0.5f
            mark.moveTo(cx - u * 0.55f, cy - u * 0.18f); mark.lineTo(cx - u * 0.3f, cy - u * 0.18f); mark.lineTo(cx, cy - u * 0.45f)
            mark.lineTo(cx, cy + u * 0.45f); mark.lineTo(cx - u * 0.3f, cy + u * 0.18f); mark.lineTo(cx - u * 0.55f, cy + u * 0.18f)
        } else {
            mark.moveTo(cx - d * 0.13f, cy - d * 0.22f); mark.lineTo(cx + d * 0.24f, cy); mark.lineTo(cx - d * 0.13f, cy + d * 0.22f)
        }
        mark.close()
        c.drawPath(mark, paint)
    }

    /**
     * [img]'s picture for a frame [needW] × [needH] pixels — the whole of it when [needW] is 0 (print):
     * decoded at the smallest power-of-two reduction that still fills the frame, its crop allowed for,
     * so a thumbnail of a photo does not take a photo's memory.
     */
    private fun decode(img: com.therealityteam.aeropages.model.SlideImage, needW: Float, needH: Float): android.graphics.Bitmap? = runCatching {
        // An SVG, an EMF or a WMF: drawn at the size the frame needs (its crop allowed for); for print, large enough to stay sharp.
        if (com.therealityteam.aeropages.format.VectorPictures.isVector(img.data)) {
            if (needW <= 0f || needH <= 0f) return@runCatching com.therealityteam.aeropages.format.VectorPictures.rasterize(img.data, 2400f, 2400f)
            val (l, t, r, b) = img.visiblePixels(1000, 1000)
            return@runCatching com.therealityteam.aeropages.format.VectorPictures.rasterize(img.data,
                needW * 1000f / (r - l).coerceAtLeast(1), needH * 1000f / (b - t).coerceAtLeast(1), cover = true)
        }
        if (needW <= 0f || needH <= 0f) return@runCatching android.graphics.BitmapFactory.decodeByteArray(img.data, 0, img.data.size)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(img.data, 0, img.data.size, bounds)
        val (l, t, r, b) = img.visiblePixels(1000, 1000)
        val fullW = needW * 1000f / (r - l).coerceAtLeast(1); val fullH = needH * 1000f / (b - t).coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= fullW && bounds.outHeight / (sample * 2) >= fullH) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(img.data, 0, img.data.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** [s] on [c], [w] × [h] points; [pixelsPerPoint] > 0 (a bitmap's) decodes its pictures only as large as they show. */
    private fun drawSlide(c: Canvas, s: Slide, p: Presentation, w: Float, h: Float, pixelsPerPoint: Float = 0f) {
        val master = p.master
        // Background: the slide's own, else its design's, else the master's; then what its design draws.
        val color = p.backgroundOf(s) ?: 0xFFFFFFFF
        val bg = Paint().apply {
            this.color = color.toInt()
            // A gradient: as a shape's runs, over the whole page.
            p.backgroundShadeOf(s)?.let { sh -> this.color = 0xFF000000.toInt(); shader = GradientShaders.shader(sh.kind, color, sh.second, sh.angleDeg, sh.cx, sh.cy, 0f, 0f, w, h) }
        }
        c.drawRect(0f, 0f, w, h, bg)
        val backdrop = p.backdropOf(s)
        p.designOf(s)?.let { drawDesign(c, it, picture = s.background == null, objects = s.designObjects, w, h, pixelsPerPoint, backdrop) }

        // Title: vertically centred in its frame (the box's own, else the layout's), like the canvas;
        // text setting no colour in one that reads over the slide.
        val titleText = p.titleTextOn(s)
        val bodyText = p.bodyTextOn(s)
        val tf = s.title.frame ?: com.therealityteam.aeropages.model.BoxFrame.defaultFor(s.layout, true)
        if (drawEquation(c, s.title, tf.xPct * w, tf.yPct * h, tf.wPct * w, tf.hPct * h, com.therealityteam.aeropages.model.ShapeAnchor.CENTER, titleText.color)) Unit
        else drawBoxText(c, s.title, tf.xPct * w, tf.yPct * h, tf.wPct * w, tf.hPct * h, com.therealityteam.aeropages.model.ShapeAnchor.CENTER, titleText, com.therealityteam.aeropages.model.TextColors.DARK)
        // Body: from the top of its frame, its lines with their levels, bullets and numbers.
        val bf = s.body.frame ?: com.therealityteam.aeropages.model.BoxFrame.defaultFor(s.layout, false)
        // As the canvas shows it: a title slide's subtitle too, once it has text, wherever it sits.
        val bodyShown = s.body.frame != null || (s.layout != SlideLayout.TITLE && s.layout != SlideLayout.BLANK) || s.body.text.isNotBlank()
        if (bodyShown && drawEquation(c, s.body, bf.xPct * w, bf.yPct * h, bf.wPct * w, bf.hPct * h, com.therealityteam.aeropages.model.ShapeAnchor.TOP, bodyText.color)) Unit
        else if (bodyShown) drawBoxText(c, s.body, bf.xPct * w, bf.yPct * h, bf.wPct * w, bf.hPct * h, com.therealityteam.aeropages.model.ShapeAnchor.TOP, bodyText, com.therealityteam.aeropages.model.TextColors.DARK)
        // Pictures: the part their crop leaves, stretched to their slide frames like the canvas (and PowerPoint).
        // Hidden objects (the Selection Pane's closed eye) stay in the file but are not drawn.
        s.images.forEach { if (!it.hidden) drawImage(c, it, w, h, pixelsPerPoint) }
        s.shapes.forEach { if (!it.hidden) drawShape(c, it, w, h, backdrop) }
        s.tables.forEach { if (!it.hidden) drawTable(c, it, w, h) }
        s.charts.forEach { if (!it.hidden) drawChart(c, it, w, h, p.backgroundOf(s)) }
    }

    /** The device's fonts for typesetting formulas, one per exporting thread. */
    private val mathFont = ThreadLocal.withInitial { com.therealityteam.aeropages.format.PaintMathFont() }

    /**
     * A box that is an equation, typeset in its frame ([x], [y], [fw] × [fh]) — placed as the box
     * is aligned and anchored, smaller when it would not fit; false (nothing drawn) for any other box.
     */
    private fun drawEquation(c: Canvas, box: com.therealityteam.aeropages.model.SlideBox, x: Float, y: Float, fw: Float, fh: Float,
                             anchor: com.therealityteam.aeropages.model.ShapeAnchor, fallbackColor: Long?): Boolean {
        val eq = com.therealityteam.aeropages.model.MathText.soleEquation(box.paragraph) ?: return false
        val first = box.paragraph.runs.first { it.format.math != null }.format
        val font = mathFont.get()!!
        val m = com.therealityteam.aeropages.format.MathLayout.layoutFitting(eq.math, font, first.fontSizePt ?: box.defaultSizePt, fw - 12f)
        val left = when (box.align) { TextAlign.LEFT -> x + 6f; TextAlign.RIGHT -> x + fw - 6f - m.width; else -> x + (fw - m.width) / 2f }
        val top = when (anchor) {
            com.therealityteam.aeropages.model.ShapeAnchor.TOP -> y + 6f
            com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> y + fh - 6f - m.height
            else -> y + (fh - m.height) / 2f
        }
        com.therealityteam.aeropages.format.MathPainter.draw(c, m, left, top + m.ascent, (first.color ?: fallbackColor ?: 0xFF1A1A1A).toInt(), font)
        return true
    }

    /**
     * The deck's date, footer and number at the foot of [s] (number [number]), each in its frame
     * where [com.therealityteam.aeropages.model.SlideChrome] puts it, centred on it vertically.
     */
    private fun drawFooter(c: Canvas, p: Presentation, s: Slide, number: Int, w: Float, h: Float) {
        com.therealityteam.aeropages.model.SlideChrome.placed(p, s, number).forEach { part ->
            val paint = textPaint(part.sizePt, false, part.color)
            val fm = paint.fontMetrics
            val x = part.xPct * w; val fw = part.wPct * w
            val baseline = part.yPct * h + (part.hPct * h - (fm.descent - fm.ascent)) / 2f - fm.ascent
            paint.textAlign = when (part.align) { TextAlign.LEFT -> Paint.Align.LEFT; TextAlign.RIGHT -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            val shown = android.text.TextUtils.ellipsize(part.text, android.text.TextPaint(paint), fw, android.text.TextUtils.TruncateAt.END).toString()
            c.drawPdfText(shown, when (part.align) { TextAlign.LEFT -> x; TextAlign.RIGHT -> x + fw; else -> x + fw / 2f }, baseline, paint)
        }
    }

    /** A picture — the part its crop leaves — stretched to its frame, turned and mirrored as it is, decoded no larger than it shows. */
    private fun drawImage(c: Canvas, img: com.therealityteam.aeropages.model.SlideImage, w: Float, h: Float, pixelsPerPoint: Float) {
        val bmp = decode(img, img.wPct * w * pixelsPerPoint, img.hPct * h * pixelsPerPoint) ?: return
        val (l, t, r, b) = img.visiblePixels(bmp.width, bmp.height)
        val fx = img.xPct * w; val fy = img.yPct * h
        val src = android.graphics.Rect(l, t, r, b)
        val dst = android.graphics.RectF(fx, fy, fx + img.wPct * w, fy + img.hPct * h)
        c.withRotation(img.rotationDeg, dst.centerX(), dst.centerY()) {
            // The page is in points here: its shadow's lengths are too.
            img.shadow?.let { PictureShadows.draw(this, bmp, src, dst, it, 1f) }
            withScale(if (img.flipH) -1f else 1f, if (img.flipV) -1f else 1f, dst.centerX(), dst.centerY()) { drawBitmap(bmp, src, dst, null) }
        }
    }

    /**
     * What design [d] (a template's master page) draws under a slide: its picture fill ([picture]:
     * unless the slide has a background of its own) and — unless the slide hides them ([objects]) —
     * its pictures and shapes, in order.
     */
    private fun drawDesign(c: Canvas, d: com.therealityteam.aeropages.model.SlideDesign, picture: Boolean, objects: Boolean, w: Float, h: Float, pixelsPerPoint: Float,
                           backdrop: Long? = d.background.takeIf { d.picture == null }) {
        if (picture) d.picture?.let { drawImage(c, it, w, h, pixelsPerPoint) }
        if (!objects) return
        for (item in d.items) when (item) {
            is com.therealityteam.aeropages.model.DesignPicture -> drawImage(c, item.image, w, h, pixelsPerPoint)
            is com.therealityteam.aeropages.model.DesignShape -> drawShape(c, item.shape, w, h, backdrop)
        }
    }

    /**
     * Design [d] alone, filling [bitmap] (transparent where it draws nothing): the layer the editor
     * and the show lay under a slide's content, so they draw the design as the PDF does.
     */
    fun renderDesign(bitmap: android.graphics.Bitmap, d: com.therealityteam.aeropages.model.SlideDesign, size: com.therealityteam.aeropages.model.SlideSize, picture: Boolean, objects: Boolean) {
        val w = size.widthPt; val h = size.heightPt
        val c = Canvas(bitmap)
        c.scale(bitmap.width / w, bitmap.height / h)
        drawDesign(c, d, picture, objects, w, h, pixelsPerPoint = bitmap.width / w)
    }

    /**
     * A free shape at its slide frame: its geometry and text as [ShapePainter] draws them, text setting
     * no colour in one reading over its fill (else over [backdrop], the slide); an equation typeset in it.
     */
    private fun drawShape(c: Canvas, sh: SlideShape, w: Float, h: Float, backdrop: Long?) {
        val x = sh.xPct * w; val y = sh.yPct * h
        val sw = sh.wPct * w; val shh = sh.hPct * h
        val equation = com.therealityteam.aeropages.model.MathText.soleEquation(sh.box.paragraph) != null
        val color = com.therealityteam.aeropages.model.TextColors.over(null, sh.textBackdrop(backdrop))
        ShapePainter.draw(c, sh, x, y, sw, shh, text = !equation, textColor = color)
        if (equation) drawEquation(c, sh.box, x, y, sw, shh, sh.anchor, color)
    }

    /**
     * [box]'s text in its frame ([x], [y], [fw] × [fh]), placed as [anchor] says — laid out as the
     * canvas shows it: its runs' faces, sizes, colours and marks, wrapped, each line indented for
     * its level with its bullet or number hanging before it. [defaults] (the master's text style)
     * fill in what the runs leave unset; [fallbackColor] is the colour when neither says.
     */
    private fun drawBoxText(c: Canvas, box: com.therealityteam.aeropages.model.SlideBox, x: Float, y: Float, fw: Float, fh: Float,
                            anchor: com.therealityteam.aeropages.model.ShapeAnchor, defaults: com.therealityteam.aeropages.model.MasterText?, fallbackColor: Long) {
        if (box.text.isBlank()) return
        val pad = 6f
        val layout = SlideTextLayout.of(box, (fw - 2 * pad).coerceAtLeast(1f), defaults, fallbackColor)
        val top = when (anchor) {
            com.therealityteam.aeropages.model.ShapeAnchor.TOP -> y + pad
            com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> y + fh - pad - layout.height
            else -> y + ((fh - layout.height) / 2f).coerceAtLeast(0f)
        }
        c.withTranslation(x + pad, top) { layout.draw(this) }
    }

    /**
     * A table at its slide frame, as the canvas draws it: each cell filled by its table style and options, half-point
     * lines in the style's border colour inside a one-point frame, and its text laid out with its own formatting
     * (white and bold in a header or total row unless its runs say), wrapped, centred in the cell's height and clipped to it.
     */
    private fun drawTable(c: Canvas, t: SlideTable, w: Float, h: Float) {
        if (t.rows == 0 || t.cols == 0) return
        val look = com.therealityteam.aeropages.model.SlideTableLook
        val x0 = t.xPct * w; val y0 = t.yPct * h
        val tw = t.wPct * w; val th = t.hPct * h
        val fill = Paint()
        val padX = 6f; val padY = 3f
        // A merged cell covers the places it spans; it is drawn once over all of them.
        val cover = t.coverage()
        for (r in 0 until t.rows) {
            for (cc in 0 until t.cols) {
                if (t.covered(cover, r, cc)) continue
                val (rs, cs) = t.spanOf(r, cc)
                val cx = x0 + t.colStart(cc) * tw; val cy = y0 + t.rowStart(r) * th
                val cw = (t.colStart(cc + cs) - t.colStart(cc)) * tw; val ch = (t.rowStart(r + rs) - t.rowStart(r)) * th
                fill.color = look.fill(t, r, cc).toInt()
                c.drawRect(cx, cy, cx + cw, cy + ch, fill)
                val box = t.cells.getOrNull(r)?.getOrNull(cc) ?: continue
                if (box.text.isBlank()) continue
                val layout = SlideTextLayout.of(box, (cw - 2 * padX).coerceAtLeast(1f), look.textDefaults(t, r), look.TEXT)
                val top = when (box.cellAnchor) {
                    com.therealityteam.aeropages.model.ShapeAnchor.TOP -> cy + padY
                    com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> cy + (ch - padY - layout.height).coerceAtLeast(padY)
                    else -> cy + ((ch - layout.height) / 2f).coerceAtLeast(padY)
                }
                c.withClip(cx, cy, cx + cw, cy + ch) { withTranslation(cx + padX, top) { layout.draw(this) } }
            }
        }
        // Its lines over the fills, each edge once: the cells' own borders, else the style's grid — as on the canvas.
        val side = com.therealityteam.aeropages.model.SlideTableLook.Side.entries
        for (r in 0 until t.rows) for (cc in 0 until t.cols) {
            val cx = x0 + t.colStart(cc) * tw; val cy = y0 + t.rowStart(r) * th
            val cw = t.colShare(cc) * tw; val ch = t.rowShare(r) * th
            if (r == 0 || cover[r][cc] != cover[r - 1][cc]) look.line(t, r, cc, side[0], cover)?.let { PdfExporter.cellSide(c, it, cx, cy, cx + cw, cy, true) }
            if (cc == 0 || cover[r][cc] != cover[r][cc - 1]) look.line(t, r, cc, side[2], cover)?.let { PdfExporter.cellSide(c, it, cx, cy, cx, cy + ch, false) }
            if (r == t.rows - 1) look.line(t, r, cc, side[1], cover)?.let { PdfExporter.cellSide(c, it, cx, cy + ch, cx + cw, cy + ch, true) }
            if (cc == t.cols - 1) look.line(t, r, cc, side[3], cover)?.let { PdfExporter.cellSide(c, it, cx + cw, cy, cx + cw, cy + ch, false) }
        }
    }

    /**
     * A chart at its slide frame, drawn like the canvas: the title at the top, the legend at the
     * bottom (categories for a pie), and between them the plot with a round-numbered value axis,
     * gridlines, category labels and optional data labels — text in the ink that reads on
     * [background]. Bars run horizontally (first category at the bottom), as in Office.
     */
    /** [chart] drawn in the box ([x], [y], [w] × [h]) of another page — a document's chart. */
    internal fun drawChartIn(c: Canvas, chart: SlideChart, x: Float, y: Float, w: Float, h: Float) {
        val framed = chart.copyOf().apply { xPct = 0f; yPct = 0f; wPct = 1f; hPct = 1f }
        c.withTranslation(x, y) { drawChart(this, framed, w, h, null) }
    }

    private fun drawChart(c: Canvas, chart: SlideChart, w: Float, h: Float, background: Long?) {
        val cs = com.therealityteam.aeropages.model.ChartStyle
        val ink = cs.inkFor(background)
        val pal = cs.palette(chart.colorSet)
        val x0 = chart.xPct * w; val y0 = chart.yPct * h
        val cw = chart.wPct * w; val chh = chart.hPct * h
        fun fill(color: Long) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toInt() }
        val label = textPaint(8f, false, (ink and 0xFFFFFF) or 0xCC000000)
        val value = textPaint(8f, false, ink)
        val titlePaint = textPaint(8.5f, false, (ink and 0xFFFFFF) or 0xE6000000)
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ((ink and 0xFFFFFF) or 0x2E000000).toInt(); strokeWidth = 0.6f }
        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ((ink and 0xFFFFFF) or 0x73000000).toInt(); strokeWidth = 0.8f }
        fun text(t: String, p: Paint, x: Float, baselineY: Float, align: Paint.Align) { p.textAlign = align; c.drawPdfText(t, x, baselineY, p) }
        /** A title turned a quarter left, centred on ([cx], [cy]). */
        fun sideTitle(t: String, cx: Float, cy: Float) {
            if (t.isBlank()) return
            c.withRotation(-90f, cx, cy) { titlePaint.textAlign = Paint.Align.CENTER; drawPdfText(t, cx, cy + 3f, titlePaint) }
        }

        // The chart area: title on top, then the legend on its side, the plot in what is left.
        var top = y0
        var bottom = y0 + chh
        var left = x0
        var right = x0 + cw
        if (chart.title.isNotBlank()) {
            val tp = textPaint(12f, true, ink)
            text(chart.title, tp, x0 + cw / 2f, y0 + 12f, Paint.Align.CENTER)
            top += 18f
        }
        val pie = chart.type == ChartType.PIE || chart.type == ChartType.DOUGHNUT
        if (chart.showLegend) {
            val names = (if (pie) chart.categories else chart.series.map { it.name }).take(if (chart.legendPos == com.therealityteam.aeropages.model.LegendPos.LEFT || chart.legendPos == com.therealityteam.aeropages.model.LegendPos.RIGHT) 12 else 6)
            val lp = textPaint(8f, false, ink)
            fun entry(i: Int, n: String, x: Float, baseline: Float) {
                c.drawRect(x, baseline - 7f, x + 7f, baseline, fill(pal[i % pal.size]))
                text(n, lp, x + 10f, baseline, Paint.Align.LEFT)
            }
            when (chart.legendPos) {
                com.therealityteam.aeropages.model.LegendPos.BOTTOM, com.therealityteam.aeropages.model.LegendPos.TOP -> {
                    val widths = names.map { lp.measureText(it) + 16f }
                    var lx = x0 + (cw - widths.sum()) / 2f
                    val baseline = if (chart.legendPos == com.therealityteam.aeropages.model.LegendPos.TOP) top + 8f else bottom - 2f
                    names.forEachIndexed { i, n -> entry(i, n, lx, baseline); lx += widths[i] }
                    if (chart.legendPos == com.therealityteam.aeropages.model.LegendPos.TOP) top += 14f else bottom -= 14f
                }
                else -> {
                    val lw = (names.maxOfOrNull { lp.measureText(it) } ?: 0f) + 16f
                    var ly = (top + bottom) / 2f - names.size * 12f / 2f + 9f
                    val lx = if (chart.legendPos == com.therealityteam.aeropages.model.LegendPos.LEFT) left + 2f else right - lw + 2f
                    names.forEachIndexed { i, n -> entry(i, n, lx, ly); ly += 12f }
                    if (chart.legendPos == com.therealityteam.aeropages.model.LegendPos.LEFT) left += lw else right -= lw
                }
            }
        }
        if (chart.series.isEmpty() || chart.categories.isEmpty() || bottom - top < 10f || right - left < 10f) return
        val aw = right - left

        if (pie) {
            val vals = (chart.series.firstOrNull()?.values ?: emptyList()).map { it.coerceAtLeast(0f) }
            val total = vals.sum()
            if (total <= 0f) return
            val d = minOf(aw, bottom - top) * 0.9f
            val cx = left + aw / 2f; val cy = (top + bottom) / 2f
            val rect = android.graphics.RectF(cx - d / 2f, cy - d / 2f, cx + d / 2f, cy + d / 2f)
            var start = -90f
            vals.forEachIndexed { i, v -> val sweep = v / total * 360f; c.drawArc(rect, start, sweep, true, fill(pal[i % pal.size])); start += sweep }
            if (chart.type == ChartType.DOUGHNUT) c.drawCircle(cx, cy, d / 2f * 0.55f, fill(background ?: 0xFFFFFFFF))
            if (chart.showValues) {
                var a = -90.0
                vals.forEachIndexed { i, v ->
                    val sweep = v / total * 360.0
                    val mid = Math.toRadians(a + sweep / 2)
                    val r = d / 2f * (if (chart.type == ChartType.DOUGHNUT) 0.78f else 0.62f)
                    if (sweep >= 12.0) text(cs.valueLabel(chart, v.toDouble()), textPaint(8f, false, cs.inkFor(pal[i % pal.size])),
                        cx + (r * Math.cos(mid)).toFloat(), cy + (r * Math.sin(mid)).toFloat() + 3f, Paint.Align.CENTER)
                    a += sweep
                }
            }
            return
        }

        val nCat = chart.categories.size; val nSer = chart.series.size

        if (chart.type == ChartType.RADAR && nCat >= 3) {
            // Spider chart: spokes clockwise from the top, rings at the value ticks, one polygon per series.
            val axis = cs.valueAxis(chart)
            val labelW = (chart.categories.maxOfOrNull { label.measureText(it) } ?: 0f).coerceAtMost(aw / 4f)
            val cx = left + aw / 2f; val cy = (top + bottom) / 2f
            val r = minOf(aw / 2f - labelW - 4f, (bottom - top) / 2f - 10f).coerceAtLeast(6f)
            fun ang(i: Int) = -Math.PI / 2 + 2 * Math.PI * i / nCat
            fun px(i: Int, f: Float) = cx + (r * f * Math.cos(ang(i))).toFloat()
            fun py(i: Int, f: Float) = cy + (r * f * Math.sin(ang(i))).toFloat()
            fun ring(f: Float, p: Paint) {
                val path = android.graphics.Path().apply { moveTo(px(0, f), py(0, f)); for (i in 1 until nCat) lineTo(px(i, f), py(i, f)); close() }
                c.drawPath(path, Paint(p).apply { style = Paint.Style.STROKE })
            }
            if (chart.showGrid) axis.ticks.forEach { t -> val f = axis.fraction(t); if (f > 0f) ring(f, gridPaint) }
            ring(1f, basePaint)
            for (i in 0 until nCat) c.drawLine(cx, cy, px(i, 1f), py(i, 1f), gridPaint)
            // Value scale beside the top spoke, each label just under its ring (clear of the top category name).
            axis.ticks.forEach { t -> text(cs.valueLabel(chart, t, compact = true), label, cx + 2f, py(0, axis.fraction(t)) + 8f, Paint.Align.LEFT) }
            chart.categories.forEachIndexed { i, cat ->
                val cos = Math.cos(ang(i)); val sin = Math.sin(ang(i))
                val align = when { cos > 0.3 -> Paint.Align.LEFT; cos < -0.3 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                text(cat, label, px(i, 1f) + (4 * cos).toFloat(), py(i, 1f) + (4 * sin).toFloat() + (if (sin > 0.3) 7f else if (sin < -0.3) -1f else 3f), align)
            }
            chart.series.forEachIndexed { si, s ->
                val col = pal[si % pal.size]
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.8f }
                val f = (0 until nCat).map { i -> axis.fraction(axis.clamp((s.values.getOrNull(i) ?: 0f).toDouble())) }
                val path = android.graphics.Path().apply { moveTo(px(0, f[0]), py(0, f[0])); for (i in 1 until nCat) lineTo(px(i, f[i]), py(i, f[i])); close() }
                c.drawPath(path, stroke)
                for (i in 0 until nCat) c.drawCircle(px(i, f[i]), py(i, f[i]), 2f, fill(col))
                if (chart.showValues) s.values.take(nCat).forEachIndexed { i, v -> text(cs.valueLabel(chart, v.toDouble()), value, px(i, f[i]), py(i, f[i]) - 4f, Paint.Align.CENTER) }
            }
            return
        }

        // The chart's own value axis (fixed bounds / unit from Format Axis) in its number format.
        val axis = cs.valueAxis(chart)
        val ticks = axis.ticks
        val tickText = ticks.map { cs.valueLabel(chart, it, compact = true) }
        // Axis titles: the vertical axis's turned along the left edge, the horizontal axis's under the labels.
        val vTitle = if (chart.type == ChartType.BAR) chart.catAxisTitle else chart.valAxisTitle
        val hTitle = if (chart.type == ChartType.BAR) chart.valAxisTitle else chart.catAxisTitle
        val vRoom = if (vTitle.isBlank()) 0f else 12f
        val hRoom = if (hTitle.isBlank()) 0f else 11f
        if (chart.type == ChartType.BAR) {
            val padL = (chart.categories.maxOfOrNull { label.measureText(it) } ?: 0f) + 5f + vRoom
            val plotL = left + padL; val plotR = right - 12f; val plotT = top + 2f; val plotB = bottom - 11f - hRoom
            if (plotR - plotL < 4f || plotB - plotT < 4f) return
            fun xOf(v: Double) = plotL + (plotR - plotL) * axis.fraction(v)
            ticks.forEachIndexed { i, t ->
                val x = xOf(t)
                if (chart.showGrid) c.drawLine(x, plotT, x, plotB, gridPaint)
                text(tickText[i], label, x, plotB + 9f, Paint.Align.CENTER)
            }
            val groupH = (plotB - plotT) / nCat; val barH = groupH * 0.7f / nSer
            for (ci in 0 until nCat) {
                val row = nCat - 1 - ci
                text(chart.categories[ci], label, plotL - 3f, plotT + groupH * (row + 0.5f) + 3f, Paint.Align.RIGHT)
                for (si in 0 until nSer) {
                    val v = chart.series[si].values.getOrNull(ci) ?: continue
                    val x = xOf(axis.clamp(v.toDouble())); val zx = xOf(axis.baseline)
                    val by = plotT + groupH * row + groupH * 0.15f + barH * (nSer - 1 - si)
                    c.drawRect(minOf(x, zx), by, maxOf(x, zx), by + barH * 0.92f, fill(pal[si % pal.size]))
                    if (chart.showValues) text(cs.valueLabel(chart, v.toDouble()), value, if (v >= 0f) maxOf(x, zx) + 2f else minOf(x, zx) - 2f,
                        by + barH * 0.46f + 3f, if (v >= 0f) Paint.Align.LEFT else Paint.Align.RIGHT)
                }
            }
            val zx = xOf(axis.baseline)
            c.drawLine(zx, plotT, zx, plotB, basePaint)
            sideTitle(vTitle, left + 6f, (plotT + plotB) / 2f)
            if (hTitle.isNotBlank()) { titlePaint.textAlign = Paint.Align.CENTER; c.drawPdfText(hTitle, (plotL + plotR) / 2f, bottom - 2f, titlePaint) }
            return
        }
        val padL = (tickText.maxOfOrNull { label.measureText(it) } ?: 0f) + 5f + vRoom
        val plotL = left + padL; val plotR = right - 3f
        val plotT = top + (if (chart.showValues) 10f else 4f); val plotB = bottom - 11f - hRoom
        if (plotR - plotL < 4f || plotB - plotT < 4f) return
        fun yOf(v: Double) = plotB - (plotB - plotT) * axis.fraction(v)
        ticks.forEachIndexed { i, t ->
            val y = yOf(t)
            if (chart.showGrid) c.drawLine(plotL, y, plotR, y, gridPaint)
            text(tickText[i], label, plotL - 3f, y + 3f, Paint.Align.RIGHT)
        }
        val zeroY = yOf(axis.baseline)
        val slot = (plotR - plotL) / nCat
        val every = kotlin.math.ceil(((chart.categories.maxOfOrNull { label.measureText(it) } ?: 0f) + 3f) / slot.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        chart.categories.forEachIndexed { ci, cat -> if (ci % every == 0) text(cat, label, plotL + slot * (ci + 0.5f), plotB + 9f, Paint.Align.CENTER) }
        if (chart.type == ChartType.COLUMN) {
            val barW = slot * 0.7f / nSer
            for (ci in 0 until nCat) for (si in 0 until nSer) {
                val v = chart.series[si].values.getOrNull(ci) ?: continue
                val y = yOf(axis.clamp(v.toDouble()))
                val bx = plotL + slot * ci + slot * 0.15f + barW * si
                c.drawRect(bx, minOf(y, zeroY), bx + barW * 0.92f, maxOf(y, zeroY), fill(pal[si % pal.size]))
                if (chart.showValues) text(cs.valueLabel(chart, v.toDouble()), value, bx + barW * 0.46f, if (v >= 0f) y - 2f else y + 8f, Paint.Align.CENTER)
            }
        } else {
            chart.series.forEachIndexed { si, s ->
                val col = pal[si % pal.size]
                val pts = s.values.take(nCat).mapIndexed { ci, v -> (plotL + slot * (ci + 0.5f)) to yOf(axis.clamp(v.toDouble())) }
                if (pts.isEmpty()) return@forEachIndexed
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = col.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.8f }
                when (chart.type) {
                    ChartType.SCATTER -> pts.forEach { (px, py) -> c.drawCircle(px, py, 2.5f, fill(col)) }
                    ChartType.AREA -> {
                        val path = android.graphics.Path().apply {
                            moveTo(pts.first().first, zeroY); pts.forEach { (px, py) -> lineTo(px, py) }; lineTo(pts.last().first, zeroY); close()
                        }
                        c.drawPath(path, fill((col and 0xFFFFFF) or 0x59000000))
                        for (i in 0 until pts.size - 1) c.drawLine(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second, stroke)
                    }
                    else -> {
                        for (i in 0 until pts.size - 1) c.drawLine(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second, stroke)
                        pts.forEach { (px, py) -> c.drawCircle(px, py, 2f, fill(col)) }
                    }
                }
                if (chart.showValues) s.values.take(nCat).forEachIndexed { ci, v -> text(cs.valueLabel(chart, v.toDouble()), value, pts[ci].first, pts[ci].second - 4f, Paint.Align.CENTER) }
            }
        }
        c.drawLine(plotL, zeroY, plotR, zeroY, basePaint)
        sideTitle(vTitle, left + 6f, (plotT + plotB) / 2f)
        if (hTitle.isNotBlank()) { titlePaint.textAlign = Paint.Align.CENTER; c.drawPdfText(hTitle, (plotL + plotR) / 2f, bottom - 2f, titlePaint) }
    }

    /** Word-wrap [text] at [maxWidth]; returns the y after the last line. */
    /** Draws [text] wrapped to [maxWidth] (a null canvas only measures) and returns the y below the last line. */
    private fun drawWrapped(c: Canvas?, text: String, p: Paint, x: Float, yStart: Float, maxWidth: Float, centered: Boolean, centerX: Float): Float {
        var y = yStart + p.textSize
        var line = StringBuilder()
        fun flush() {
            if (line.isEmpty()) return
            if (c == null) { /* measuring */ }
            else if (centered) { p.textAlign = Paint.Align.CENTER; c.drawPdfText(line.toString(), centerX, y, p) }
            else { p.textAlign = Paint.Align.LEFT; c.drawPdfText(line.toString(), x, y, p) }
            y += p.textSize * 1.3f
            line = StringBuilder()
        }
        text.forEach { ch ->
            line.append(ch)
            if (p.measureText(line.toString()) > maxWidth) { line.deleteCharAt(line.length - 1); flush(); line.append(ch) }
        }
        flush()
        return y - p.textSize * 0.3f
    }

    private fun textPaint(sizePt: Float, bold: Boolean, color: Long): Paint = PdfFaces.paint().apply {
        textSize = sizePt
        this.color = color.toInt()
        typeface = PdfFaces.of(null, bold, false)   // a bold face: PDF pages draw no fake bold
    }
}
