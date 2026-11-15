package com.therealityteam.aeropages.format.pdf

import androidx.core.graphics.withClip
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import com.therealityteam.aeropages.editor.PageFlow
import com.therealityteam.aeropages.editor.TableSlice
import com.therealityteam.aeropages.model.BibliographyBlock
import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.HeaderFooters
import com.therealityteam.aeropages.model.FloatPlacement
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TabLayout
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextBoxBlock
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.Texts
import com.therealityteam.aeropages.model.TocBlock
import java.io.OutputStream

/**
 * Renders a [Document] to a real, paginated PDF using Android's [PdfDocument].
 *
 * Every block is laid out into lines / rows measured with [Paint] under the layout model the
 * editor canvas uses too ([PageMetrics]: line pitch, spacing, indents, list markers, cell and
 * picture geometry, generated text), and [PageFlow] breaks the pages by the same rules — so the
 * PDF's pages match the editor's. The header and footer sit in the page margins; footnotes are
 * printed at the foot of the page that references them. Text wrapping breaks Latin text between
 * words and CJK text between characters (with the usual no-break rules for punctuation).
 */
object PdfExporter {

    fun export(doc: Document, output: OutputStream) {
        defaultTab.set(doc.defaultTabPt)
        try {
            var sections = layoutDocument(doc, emptyMap())
            // A table of contents needs its headings' pages: lay out once to find them, then again with
            // the numbers (a contents line is one fixed line either way, so the pages do not move).
            if (doc.blocks.any { it is TocBlock || it is com.therealityteam.aeropages.model.IndexBlock }) sections = layoutDocument(doc, headingPages(sections))
            render(doc, sections, output)
        } finally { defaultTab.remove() }
    }

    /** The document being exported's default tab stops' distance (see TabLayout). */
    private val defaultTab = ThreadLocal<Float?>()
    /** The captions a table of figures lists: their pages are looked up as the headings' are. */
    private val tocCaptions = ThreadLocal.withInitial<Set<Long>> { emptySet() }
    private val tocTargets: Set<Long> get() = tocCaptions.get()

    /** One section laid out: its geometry, its blocks and the pages they flowed into. */
    private class SectionLayout(
        val pg: PageSetup,
        val bodyW: Float,
        val colW: Float,
        val gap: Float,
        val blocks: List<PdfBlock>,
        val pages: List<PageFlow.Page>,
        val notes: Map<Int, PdfPara>,
        /** The page (of this section) each floating object the text steps over or goes beside is on, by its mark's item. */
        val bandPages: Map<Int, Int> = emptyMap(),
        /** Where each such object's mark stood when its room was taken: its paragraph's top, for one placed from it. */
        val marks: Map<Int, PageFlow.At> = emptyMap(),
    )

    /** Where each bordered or shaded paragraph sharing a box stands in it (see ParagraphBoxes), as the canvas has it. */
    private val boxEdges = ThreadLocal.withInitial<Map<Paragraph, com.therealityteam.aeropages.model.BoxEdge>> { emptyMap() }

    private fun layoutDocument(doc: Document, tocPages: Map<Long, String>): List<SectionLayout> {
        boxEdges.set(com.therealityteam.aeropages.model.ParagraphBoxes.edges(doc))
        tocCaptions.set(doc.blocks.filterIsInstance<TocBlock>().filter { it.caption != null }.flatMap { doc.tocEntries(it) }.mapTo(HashSet()) { it.paragraph.id }.also { ids ->
            // An index's entries: the paragraphs they are marked in.
            if (doc.blocks.any { it is com.therealityteam.aeropages.model.IndexBlock }) com.therealityteam.aeropages.model.IndexEntries.refs(doc).forEach { ids.add(it.paragraph.id) }
        })
        val docSections = doc.sections()
        val ordinals = PageMetrics.listLabels(doc)
        val footnotes = doc.footnotes.size
        var pagesBefore = 0
        return docSections.mapIndexed { si, (blocks, pg) ->
            val bodyW = (pg.widthPt - pg.marginLeftPt - pg.marginRightPt).coerceAtLeast(1f)
            val bodyH = (pg.heightPt - pg.marginTopPt - pg.marginBottomPt).coerceAtLeast(1f)
            val cols = PageMetrics.columnsOf(pg)
            val gap = PageMetrics.columnGapOf(pg)
            val colW = (bodyW - gap * (cols - 1)) / cols
            val out = ArrayList<PdfBlock>()
            val relayouts = HashMap<Int, Pair<Paragraph, (((Int) -> Pair<Float, Float>?)?) -> PdfPara>>()
            // What is drawn over the page goes just before the paragraph (or table) it is anchored to: it lands on
            // the page that paragraph starts on (as the canvas lays it out).
            fun onPage(b: Block) = floatOf(b)?.onPage == true
            val done = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Block, Boolean>())
            blocks.forEachIndexed { i, block ->
                if (onPage(block)) { if (done.add(block)) layoutBlock(doc, block, colW, ordinals, footnotes, tocPages, out, pg); return@forEachIndexed }
                if (block is ParagraphBlock || block is TableBlock) {
                    var j = i + 1
                    while (j < blocks.size && floatOf(blocks[j]) != null) {
                        if (onPage(blocks[j]) && done.add(blocks[j])) layoutBlock(doc, blocks[j], colW, ordinals, footnotes, tocPages, out, pg)
                        j++
                    }
                }
                layoutBlock(doc, block, colW, ordinals, footnotes, tocPages, out, pg, relayouts)
            }
            // Endnotes close the document: a heading, then "[i] text" entries after the last section.
            if (si == docSections.lastIndex && doc.endnotes.isNotEmpty()) {
                out.add(layoutPara(PageMetrics.endnotesHeading(Texts.get(TextId.ENDNOTES_HEADING)), colW, null, 0, keepWithNext = true))
                doc.endnotes.forEachIndexed { i, e -> out.add(layoutPara(PageMetrics.endnote(i, e.text), colW, null, 0)) }
            }
            val notes = HashMap<Int, PdfPara>()
            doc.footnotes.forEachIndexed { i, f -> notes[i + 1] = layoutPara(PageMetrics.footnote(i + 1, f.text), bodyW, null, 0) }
            // What each page's header and footer take from its body (its number counts the sections' pages before).
            val firstPage = pagesBefore + 1
            val geometry = PageFlow.Geometry(
                height = bodyH, columns = cols,
                noteHeight = { n -> notes[n]?.height ?: 0f },
                noteArea = PageMetrics.NOTE_GAP_ABOVE + PageMetrics.NOTE_RULE + PageMetrics.NOTE_GAP_BELOW,
                room = { pi ->
                    HeaderFooters.room(doc, firstPage + pi, pg).takeUnless { it.none }?.let { r ->
                        PageFlow.PageRoom(r.top, r.foot, r.spans.map { PageFlow.Band(it.top, it.bottom, it.columns) })
                    }
                },
                reflow = { item, top, sides -> relayouts[item]?.let { (p, make) -> beside(p, make, colW, top, sides, pg.linePitchPt)?.item as? PageFlow.Lines } },
            )
            // What floats over the page takes from the text — the height it steps over, the room beside it its lines leave —,
            // placed from where its mark stands.
            val marks = out.indices.filter { out[it] is PdfFloat }.toSet()
            val flowed = PageFlow.paginateAround(out.map { it.item }, geometry, marks) { i, at ->
                roomFor(out[i] as PdfFloat, pg, cols, colW, gap, pg.marginLeftPt + at.frame * (colW + gap), pg.marginTopPt + at.y)
            }
            // Each paragraph as the flow placed it: beside floating objects, where it starts so.
            flowed.pages.forEach { page ->
                page.wraps.forEach { (item, w) -> relayouts[item]?.let { (p, make) -> beside(p, make, colW, w.top, w.sides, pg.linePitchPt)?.let { out[item] = it } } }
            }
            pagesBefore += flowed.pages.size
            SectionLayout(pg, bodyW, colW, gap, out, flowed.pages, notes, flowed.bandPages, flowed.marks)
        }
    }

    /** A floating picture's, text box's or table's position, if it has one. */
    private fun floatOf(b: Block) = (b as? ImageBlock)?.floating ?: (b as? TextBoxBlock)?.floating ?: (b as? TableBlock)?.table?.floating

    /**
     * The room [f] takes from the text on its page, its column at [colX] and its paragraph's top at [paraTop]: the band
     * of its height when the text keeps clear of it (FloatPlacement.textKeepsClear), the room its lines leave beside it
     * when they go beside it (FloatPlacement.textBeside) — down the body, in the columns it stands over.
     */
    private fun roomFor(f: PdfFloat, pg: PageSetup, cols: Int, colW: Float, gap: Float, colX: Float, paraTop: Float): PageFlow.Room? {
        val (x, y) = FloatPlacement.place(f.pos, f.w, f.h, pg, pg.widthPt, 1f, colX, colW, paraTop)
        val frames = HashSet<Int>()
        var widest = 0f
        for (c in 0 until cols) {
            val cx = pg.marginLeftPt + c * (colW + gap)
            val overlap = minOf(x + f.w, cx + colW) - maxOf(x, cx)
            if (overlap > 0f) { frames.add(c); widest = maxOf(widest, overlap) }
        }
        if (frames.isEmpty()) return null
        if (FloatPlacement.textKeepsClear(f.pos, widest, colW)) return PageFlow.Room(bands = listOf(PageFlow.Band(y - pg.marginTopPt, y + f.h - pg.marginTopPt, frames)))
        if (!FloatPlacement.textBeside(f.pos, widest, colW)) return null
        return PageFlow.Room(sides = frames.map { c ->
            val cx = pg.marginLeftPt + c * (colW + gap)
            val (l, r) = FloatPlacement.beside(x, f.w, cx, colW, FloatPlacement.WRAP_GAP_PT)
            PageFlow.Side(y - pg.marginTopPt, y + f.h - pg.marginTopPt, setOf(c), l, r)
        })
    }

    /**
     * Paragraph [p] laid out again by [make] with its first line's top at [top] beside floating objects [sides] (in a column
     * [colW] wide): each line an object stands beside starts past it or ends short of it; null when none does.
     */
    private fun beside(p: Paragraph, make: (((Int) -> Pair<Float, Float>?)?) -> PdfPara, colW: Float, top: Float, sides: List<PageFlow.Side>, grid: Float?): PdfPara? {
        val f = p.format
        val inset = PageMetrics.indents(f)
        val markerW = PageMetrics.markerOf(p, null)?.let { PageMetrics.markerWidth(f, it, PageMetrics.maxSizePt(p)) } ?: 0f
        val startX = inset[0] + markerW
        val endX = inset[3]
        val pitch = PageMetrics.pitchPt(p, grid).coerceAtLeast(1f)
        var any = false
        val room = { k: Int ->
            val a = top + k * pitch; val b = a + pitch
            val met = sides.filter { it.bottom > a + 0.01f && it.top < b - 0.01f }
            if (met.isEmpty()) null else {
                val l = (met.maxOf { it.left } - startX).coerceAtLeast(0f)
                val r = (met.maxOf { it.right } - endX).coerceAtLeast(0f)
                // Too little room for a word beside it: the line runs across (the text keeps clear of a wide object).
                if (l + r <= 0f || colW - startX - endX - l - r < pitch * 2) null else { any = true; l to r }
            }
        }
        val laid = make(room)
        return if (any) laid else null
    }

    /** The page each Heading 1–3 starts on (1-based, across all sections). */
    private fun headingPages(sections: List<SectionLayout>): Map<Long, String> {
        val out = HashMap<Long, String>()
        // Each page as its section numbers it (i, ii… before the body's 1, 2…), as Word's contents show them.
        val shown = HeaderFooters.pageNumbers(sections.flatMap { s -> List(s.pages.size) { s.pg } })
        var n = 0
        sections.forEach { s ->
            s.pages.forEach { page ->
                n++
                page.pieces.forEach { pc ->
                    if (pc.item == PageFlow.GAP) return@forEach
                    val b = s.blocks[pc.item]
                    if (b is PdfPara && b.headingId != null && pc.from == 0) out.putIfAbsent(b.headingId, shown.getOrElse(n - 1) { n.toString() })
                }
            }
        }
        return out
    }

    private fun layoutBlock(
        doc: Document, block: Block, colW: Float, ordinals: Map<Paragraph, String>, footnotes: Int,
        tocPages: Map<Long, String>, out: MutableList<PdfBlock>, pg: PageSetup,
        /** Body paragraphs by their block's index, each with what lays it out again around floating objects beside it. */
        relayouts: MutableMap<Int, Pair<Paragraph, (((Int) -> Pair<Float, Float>?)?) -> PdfPara>>? = null,
    ) {
        // Over its page (behind or in front of the text, or placed down the page): no room in the text, a mark
        // where it is anchored; drawn over its page.
        val floating = (block as? ImageBlock)?.floating ?: (block as? TextBoxBlock)?.floating ?: (block as? TableBlock)?.table?.floating
        if (floating?.onPage == true) {
            when (block) {
                is TableBlock -> {
                    val w = block.table.widthPt ?: colW
                    val t = layoutTable(block.table, w, footnotes, ordinals)
                    out.add(PdfFloat(block, floating, w, t.rows.sumOf { it.height.toDouble() }.toFloat(), null, t))
                }
                is ImageBlock -> out.add(PdfFloat(block, floating, block.widthPt, block.heightPt, null))
                is TextBoxBlock -> {
                    val boxW = block.widthPt ?: colW
                    val ords = PageMetrics.listLabels(block.paragraphs.asSequence(), doc.lists)
                    val paras = block.paragraphs.map { layoutPara(it, PageMetrics.boxTextWidth(block, boxW - 2 * PageMetrics.BOX_INSET), ords[it], footnotes) }
                    val frameH = PageMetrics.boxFrameHeight(block, paras.sumOf { it.height.toDouble() }.toFloat())
                    out.add(PdfFloat(block, floating, boxW, frameH, TextBoxDraw(paras, boxW, block.shape, frameH)))
                }
                else -> Unit
            }
            return
        }
        when (block) {
            is ParagraphBlock -> {
                val p = block.paragraph
                if (p.isPageBreak()) { out.add(PdfBreak); return }
                val heading = p.format.style == ParagraphStyle.HEADING1 || p.format.style == ParagraphStyle.HEADING2 || p.format.style == ParagraphStyle.HEADING3
                val made = { room: ((Int) -> Pair<Float, Float>?)? ->
                    layoutPara(p, colW, ordinals[p], footnotes, keepWithNext = PageMetrics.keepsWithNext(p), headingId = if (heading || p.id in tocTargets) p.id else null, grid = pg.linePitchPt, room = room)
                        .also { it.countsLines = !p.format.suppressLineNumbers }
                }
                relayouts?.put(out.size, p to made)
                out.add(made(null))
            }
            is TableBlock -> {
                // A floating table that moves with its paragraph: at its own width, at its place across the column.
                val t = block.table
                val w = (t.widthPt ?: colW).coerceAtMost(colW)
                out.add(layoutTable(t, w, footnotes, ordinals).also {
                    if (t.floating != null) { it.floating = t.floating; it.pg = pg; it.w = w } else it.offset = PageMetrics.tableOffset(t, w, colW)
                })
            }
            is ImageBlock -> {
                val (w, h) = PageMetrics.fitImage(block.widthPt, block.heightPt, colW)
                out.add(PdfSolid(ImageDraw(block, w, h, pg)))
            }
            is com.therealityteam.aeropages.model.ChartBlock -> {
                val (w, h) = PageMetrics.fitImage(block.widthPt, block.heightPt, colW)
                out.add(PdfSolid(ChartDraw(block, w, h)))
            }
            is com.therealityteam.aeropages.model.EquationBlock -> {
                val font = mathFont.get()!!
                out.add(PdfSolid(EquationDraw(block, com.therealityteam.aeropages.format.MathLayout.layoutFitting(block.math, font, block.sizePt, colW), font)))
            }
            is TextBoxBlock -> {
                val boxW = (block.widthPt ?: colW).coerceAtMost(colW)
                val ords = PageMetrics.listLabels(block.paragraphs.asSequence(), doc.lists)
                val paras = block.paragraphs.map { layoutPara(it, PageMetrics.boxTextWidth(block, boxW - 2 * PageMetrics.BOX_INSET), ords[it], footnotes) }
                val frameH = PageMetrics.boxFrameHeight(block, paras.sumOf { it.height.toDouble() }.toFloat())
                out.add(PdfSolid(TextBoxDraw(paras, boxW, block.shape, frameH, block.floating, pg), paras.flatMap { it.allNotes() }.toIntArray()))
            }
            is TocBlock -> {
                if (block.caption == null) out.add(layoutPara(PageMetrics.generatedHeading(Texts.get(TextId.TOC_HEADING)), colW, null, 0, keepWithNext = true))
                val entries = doc.tocEntries(block)
                if (entries.isEmpty()) out.add(layoutPara(PageMetrics.generatedHint(Texts.get(if (block.caption == null) TextId.TOC_EMPTY else TextId.TOF_EMPTY)), colW, null, 0))
                entries.forEach { e ->
                    val text = e.text.ifBlank { Texts.get(TextId.UNTITLED_HEADING) }
                    val paint = paintFor(CharacterFormat(fontSizePt = PageMetrics.ENTRY_SIZE, bold = e.level == 0 && !e.figure, color = 0xFF1F1F1F), PageMetrics.ENTRY_SIZE, text)
                    out.add(PdfSolid(TocLineDraw(text, tocPages[e.paragraph.id] ?: "", e.level * PageMetrics.TOC_INDENT, paint)))
                }
            }
            is com.therealityteam.aeropages.model.IndexBlock -> {
                val lines = com.therealityteam.aeropages.model.IndexEntries.lines(doc, block.headings)
                if (lines.isEmpty()) out.add(layoutPara(PageMetrics.generatedHint(Texts.get(TextId.INDEX_EMPTY)), colW, null, 0))
                lines.forEach { l ->
                    val size = if (l.heading) PageMetrics.ENTRY_SIZE + 1f else PageMetrics.ENTRY_SIZE
                    val paint = paintFor(CharacterFormat(fontSizePt = size, bold = l.heading, color = 0xFF1F1F1F), size, l.text)
                    val pages = l.pages { tocPages[it.id] }
                    out.add(PdfSolid(TocLineDraw(l.text, pages, l.level.coerceAtLeast(0) * PageMetrics.TOC_INDENT, paint, leader = block.rightAlign && pages.isNotEmpty()), keepWithNext = l.heading))
                }
            }
            is BibliographyBlock -> {
                out.add(layoutPara(PageMetrics.generatedHeading(Texts.get(TextId.BIBLIOGRAPHY_HEADING)), colW, null, 0, keepWithNext = true))
                if (doc.sources.isEmpty()) out.add(layoutPara(PageMetrics.generatedHint(Texts.get(TextId.BIBLIOGRAPHY_EMPTY)), colW, null, 0))
                doc.sources.forEach { s -> out.add(layoutPara(PageMetrics.generatedEntry("${s.author}. ${s.title}. ${s.year}."), colW, null, 0)) }
            }
        }
    }

    // --- rendering ---------------------------------------------------------------

    private fun render(doc: Document, sections: List<SectionLayout>, output: OutputStream) {
        val pageCount = sections.sumOf { it.pages.size }.coerceAtLeast(1)
        val pdf = PdfDocument()
        var pageIndex = 0
        // Each page's number as it shows (a section's own start and format).
        val shown = HeaderFooters.pageNumbers(sections.flatMap { s -> List(s.pages.size) { s.pg } })
        // Word's line numbers, counted down the pages as the canvas counts them.
        val lineCounter = com.therealityteam.aeropages.model.LineNumbers.Counter()
        sections.forEach { s ->
            val pg = s.pg
            s.pages.forEachIndexed { pi, page ->
                val i = pageIndex++
                val info = PdfDocument.PageInfo.Builder(pg.widthPt.toInt(), pg.heightPt.toInt(), i + 1).create()
                val pdfPage = pdf.startPage(info)
                val canvas = pdfPage.canvas
                // Word's page borders (the canvas draws them with the same painter).
                doc.pageBorders?.takeIf { it.shows(i) }?.let { PageBorderPainter.draw(canvas, it, pg) }
                // Watermark: rotated translucent text behind the content (matches the canvas).
                doc.watermark?.takeIf { it.isNotBlank() }?.let { wm ->
                    val wp = PdfFaces.paint(wm).apply {
                        textSize = 64f; color = 0x14000000
                        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                    }
                    canvas.withRotation(-30f, pg.widthPt / 2f, pg.heightPt / 2f) {
                        drawText(wm, pg.widthPt / 2f, pg.heightPt / 2f, wp)
                    }
                }
                // Floating pictures and text boxes: on the page their anchor's paragraph is on, placed from its top.
                val floats = ArrayList<Triple<PdfFloat, Float, Float>>()
                page.frames.forEachIndexed { fi, frame ->
                    val x = pg.marginLeftPt + fi * (s.colW + s.gap)
                    var y = pg.marginTopPt
                    frame.forEach { pc ->
                        // A mark stands just before its paragraph: its place is that paragraph's top. (What the text
                        // steps over is placed on its band's page, below.)
                        if (pc.item !in s.bandPages) (s.blocks.getOrNull(pc.item) as? PdfFloat)?.let { b ->
                            val (fx, fy) = FloatPlacement.place(b.pos, b.w, b.h, pg, pg.widthPt, 1f, x, s.colW, y)
                            floats.add(Triple(b, fx, fy))
                        }
                        y += pc.height
                    }
                }
                s.bandPages.forEach { (item, p) ->
                    if (p == pi) (s.blocks[item] as? PdfFloat)?.let { b ->
                        val at = s.marks[item]
                        val (fx, fy) = FloatPlacement.place(b.pos, b.w, b.h, pg, pg.widthPt, 1f, pg.marginLeftPt + (at?.frame ?: 0) * (s.colW + s.gap), s.colW, pg.marginTopPt + (at?.y ?: 0f))
                        floats.add(Triple(b, fx, fy))
                    }
                }
                // The header's and footer's floating pictures (a letterhead's logo), under everything else (those in
                // their line come with their line).
                listOf(true, false).forEach { header ->
                    val paraTop = HeaderFooters.paraTop(header, HeaderFooters.layout(doc, i + 1, pageCount, pg, header), pg)
                    doc.hfPicturesFor(i + 1, header).forEach { img ->
                        FloatPlacement.placeInHeader(img, header, pg, paraTop = paraTop)?.let { (x, y) -> drawPicture(canvas, img, android.graphics.RectF(x, y, x + img.widthPt, y + img.heightPt)) }
                    }
                }
                floats.filter { it.first.behind }.forEach { (b, fx, fy) -> b.drawAt(canvas, fx, fy) }
                drawHeaderFooter(canvas, doc, pg, i + 1, pageCount, shown.getOrElse(i) { (i + 1).toString() })
                var tallest = 0f
                lineCounter.page(pg)
                val numbering = pg.lineNumbers
                val numberPaint = numbering?.let {
                    PdfFaces.paint("0123456789").apply { textSize = doc.styles.defaultSizePt; color = 0xFF000000.toInt(); textAlign = Paint.Align.RIGHT }
                }
                page.frames.forEachIndexed { fi, frame ->
                    val x = pg.marginLeftPt + fi * (s.colW + s.gap)
                    var y = pg.marginTopPt
                    frame.forEach { pc ->
                        if (pc.item != PageFlow.GAP) {
                            val b = s.blocks[pc.item]
                            b.draw(canvas, pc, x, y, s.colW)
                            // Word's line numbers: each counted line's, right-aligned left of its column.
                            if (numbering != null && numberPaint != null && b is PdfPara && b.countsLines) {
                                val rightEdge = x - com.therealityteam.aeropages.model.LineNumbers.distanceOf(numbering)
                                b.baselines(pc.from, pc.to, y).forEach { base -> val n = lineCounter.line(); if (n > 0) canvas.drawText(n.toString(), rightEdge, base, numberPaint) }
                            }
                        }
                        y += pc.height
                    }
                    tallest = maxOf(tallest, y - pg.marginTopPt)
                }
                // Word's line between columns: in the middle of each gap, as tall as the tallest column on the page.
                val cols = PageMetrics.columnsOf(pg)
                if (pg.columnLine && cols > 1 && tallest > 0f) {
                    val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); strokeWidth = 0.5f }
                    for (k in 1 until cols) {
                        val lx = pg.marginLeftPt + k * (s.colW + s.gap) - s.gap / 2f
                        canvas.drawLine(lx, pg.marginTopPt, lx, pg.marginTopPt + tallest, lp)
                    }
                }
                // Footnotes referenced on this page, at the foot of the body (above a footer that reaches into it).
                if (page.notes.isNotEmpty()) {
                    val notes = page.notes.sorted().mapNotNull { s.notes[it] }
                    var y = pg.heightPt - pg.marginBottomPt - HeaderFooters.room(doc, i + 1, pg).foot - notes.sumOf { it.height.toDouble() }.toFloat() -
                        (PageMetrics.NOTE_GAP_ABOVE + PageMetrics.NOTE_RULE + PageMetrics.NOTE_GAP_BELOW)
                    y += PageMetrics.NOTE_GAP_ABOVE
                    canvas.drawRect(pg.marginLeftPt, y, pg.marginLeftPt + PageMetrics.NOTE_RULE_WIDTH, y + PageMetrics.NOTE_RULE,
                        Paint().apply { color = 0xFF9E9E9E.toInt() })
                    y += PageMetrics.NOTE_RULE + PageMetrics.NOTE_GAP_BELOW
                    notes.forEach { n -> n.drawWhole(canvas, pg.marginLeftPt, y, s.bodyW); y += n.height }
                }
                floats.filter { !it.first.behind }.forEach { (b, fx, fy) -> b.drawAt(canvas, fx, fy) }
                pdf.finishPage(pdfPage)
            }
        }
        pdf.writeTo(output)
        pdf.close()
    }

    // --- blocks ------------------------------------------------------------------

    /** A block as the page flow sees it, and how to draw a piece of it. */
    private sealed class PdfBlock {
        abstract val item: PageFlow.Item
        /** Draws [piece] of this block with its top-left corner at ([x], [y]) in a column [w] wide. */
        abstract fun draw(canvas: Canvas, piece: PageFlow.Piece, x: Float, y: Float, w: Float)
    }

    private data object PdfBreak : PdfBlock() {
        override val item: PageFlow.Item = PageFlow.Break
        override fun draw(canvas: Canvas, piece: PageFlow.Piece, x: Float, y: Float, w: Float) {}
    }

    /** Something that never splits: a picture, a text box, a contents line. */
    private class PdfSolid(val drawable: Drawable, notes: IntArray = IntArray(0), keepWithNext: Boolean = false) : PdfBlock() {
        override val item: PageFlow.Item = PageFlow.Solid(drawable.height, notes, keepWithNext)
        override fun draw(canvas: Canvas, piece: PageFlow.Piece, x: Float, y: Float, w: Float) = drawable.draw(canvas, x, y, w)
    }

    /**
     * A paragraph: wrapped lines of one pitch each, its spacing, its insets (left [indent] and [right],
     * the first line's own [firstOffset] and the others' [restOffset] beyond the left) and list marker.
     */
    private class PdfPara(
        val lines: List<TextLine>,
        val before: Float,
        val after: Float,
        val indent: Float,
        val marker: Seg?,
        keepWithNext: Boolean,
        val headingId: Long?,
        val notes: List<IntArray>?,
        keepTogether: Boolean = false,
        widowControl: Boolean = true,
        breakBefore: Boolean = false,
        val firstOffset: Float = 0f,
        val restOffset: Float = 0f,
        val right: Float = 0f,
        /** The list marker's column (see PageMetrics.markerWidth). */
        val markerW: Float = 0f,
        /** A bordered or shaded paragraph's format and where it stands in its box (see ParagraphBoxPainter); null: no box. */
        val box: com.therealityteam.aeropages.model.ParagraphFormat? = null,
        val edge: com.therealityteam.aeropages.model.BoxEdge? = null,
        /** Word's drop cap: its letters drawn on the baseline of line [DropSeg.line], [DropSeg.x] from the paragraph's indent. */
        val drop: DropSeg? = null,
    ) : PdfBlock() {
        private val bottoms = FloatArray(lines.size).also { b -> var y = 0f; lines.forEachIndexed { i, l -> y += l.height; b[i] = y } }

        /** A body paragraph whose lines Word's line numbers count (not suppressed). */
        var countsLines = false

        /** Each of lines [from, to)'s baseline, the piece drawn from [y]. */
        fun baselines(from: Int, to: Int, y: Float): List<Float> {
            val out = ArrayList<Float>()
            var cy = y + (if (from == 0) before else 0f)
            for (i in from until to.coerceAtMost(lines.size)) { out.add(cy + lines[i].ascent); cy += lines[i].height }
            return out
        }
        override val item: PageFlow.Item = PageFlow.Lines(before, bottoms, after, keepWithNext, notes, keepTogether, widowControl, breakBefore)
        val height: Float get() = before + (bottoms.lastOrNull() ?: 0f) + after

        fun allNotes(): List<Int> = notes?.flatMap { it.toList() } ?: emptyList()

        override fun draw(canvas: Canvas, piece: PageFlow.Piece, x: Float, y: Float, w: Float) =
            drawLines(canvas, piece.from, piece.to, x, y, w)

        fun drawWhole(canvas: Canvas, x: Float, y: Float, w: Float) = drawLines(canvas, 0, lines.size, x, y, w)

        private fun drawLines(canvas: Canvas, from: Int, to: Int, x: Float, y: Float, w: Float) {
            // The box behind: the whole paragraph's, clipped to the lines this page holds (a box going on keeps its sides).
            box?.let { f ->
                val end = to.coerceAtMost(lines.size)
                val top = if (from == 0) y else y - before - bottoms[from - 1]
                val fragTop = y
                val fragBottom = y + (if (from == 0) before else 0f) + (if (end > 0) bottoms[end - 1] else 0f) - (if (from > 0) bottoms[from - 1] else 0f) +
                    (if (end >= lines.size) after else 0f)
                val full = height
                canvas.withClip(x - 72f, fragTop, x + w + 72f, fragBottom) { ParagraphBoxPainter.draw(canvas, f, edge, x, top, w, full) }
            }
            var cy = y + (if (from == 0) before else 0f)
            if (from == 0 && drop != null && lines.isNotEmpty()) {
                val k = drop.line.coerceAtMost(lines.lastIndex)
                val base = cy + (if (k > 0) bottoms[k - 1] else 0f) + lines[k].ascent + (drop.line - k) * drop.pitch
                canvas.drawText(drop.seg.text, x + indent + drop.x, base, drop.seg.paint)
            }
            for (i in from until to.coerceAtMost(lines.size)) {
                val line = lines[i]
                if (i == 0 && marker != null) canvas.drawText(marker.text, x + indent, cy + line.ascent, marker.paint)
                val off = if (i == 0) firstOffset else restOffset
                line.draw(canvas, x + indent + markerW + off + line.lead, cy, w - indent - markerW - off - right - line.lead - line.cut)
                cy += line.height
            }
        }
    }

    /** A table: its rows, each cell's paragraphs and pictures, and the header rows to repeat. */
    private class PdfTable(val rows: List<TableRowDraw>, val headerCount: Int) : PdfBlock() {
        override val item: PageFlow.Item = PageFlow.Rows(
            FloatArray(rows.size) { rows[it].height }, headerCount, PageMetrics.TABLE_PAD, PageMetrics.TABLE_PAD,
            rows.map { it.notes },
        )

        /** A floating table's position on a page of [pg], [w] wide: it keeps its place across its column. */
        var floating: com.therealityteam.aeropages.model.FloatPosition? = null
        var pg: PageSetup? = null
        var w = 0f
        /** In the text: how far in from its column's left edge it stands (its alignment or indent). */
        var offset = 0f

        /** Every row, its top at [y]. */
        fun drawAll(canvas: Canvas, x: Float, y: Float) {
            var cy = y
            rows.forEach { r -> r.draw(canvas, x, cy); cy += r.height }
        }

        override fun draw(canvas: Canvas, piece: PageFlow.Piece, x0: Float, y: Float, w: Float) {
            val f = floating; val page = pg
            val x = if (f != null && page != null) x0 + FloatPlacement.acrossColumn(f, this.w, page, page.widthPt, 1f, x0, w) else x0 + offset
            val slice = piece.slice ?: TableSlice(rows.indices, false)
            var cy = y + PageMetrics.TABLE_PAD
            if (slice.repeatHeader) for (h in 0 until headerCount.coerceAtMost(rows.size)) { rows[h].draw(canvas, x, cy); cy += rows[h].height }
            for (r in slice.rows) { rows[r].draw(canvas, x, cy); cy += rows[r].height }
        }
    }

    // --- drawables ---------------------------------------------------------------

    private sealed class Drawable(val height: Float) {
        abstract fun draw(canvas: Canvas, x: Float, y: Float, width: Float)
    }

    /** A chart [w] × [h] centred in its column, with the room a picture has above and below it. */
    private class ChartDraw(val block: com.therealityteam.aeropages.model.ChartBlock, val w: Float, val h: Float) : Drawable(h + 2 * PageMetrics.IMAGE_PAD) {
        override fun draw(canvas: Canvas, x: Float, y: Float, width: Float) {
            SlidesPdfExporter.drawChartIn(canvas, block.chart, x + (width - w) / 2f, y + PageMetrics.IMAGE_PAD, w, h)
        }
    }

    /** The device's fonts for typesetting formulas, one per exporting thread. */
    private val mathFont = ThreadLocal.withInitial { com.therealityteam.aeropages.format.PaintMathFont() }

    /** A displayed equation, typeset (smaller when wider than the column) and placed as its block is aligned. */
    private class EquationDraw(val block: com.therealityteam.aeropages.model.EquationBlock, val box: com.therealityteam.aeropages.format.MathBox,
                               val font: com.therealityteam.aeropages.format.PaintMathFont) : Drawable(box.height + 2 * PageMetrics.EQUATION_PAD) {
        override fun draw(canvas: Canvas, x: Float, y: Float, width: Float) {
            val left = when (block.align) {
                com.therealityteam.aeropages.model.TextAlign.LEFT -> x
                com.therealityteam.aeropages.model.TextAlign.RIGHT -> x + width - box.width
                else -> x + (width - box.width) / 2f
            }
            com.therealityteam.aeropages.format.MathPainter.draw(canvas, box, left, y + PageMetrics.EQUATION_PAD + box.ascent, 0xFF1A1A1A.toInt(), font)
        }
    }

    /** A table-of-contents line: the heading, a dotted leader and its page number at the right margin. */
    /** A contents or index line: its text, a dotted leader (unless not [leader]: the page then follows the text) and its page. */
    private class TocLineDraw(val text: String, val page: String, val indent: Float, val paint: Paint, val leader: Boolean = true) : Drawable(PageMetrics.TOC_LINE) {
        override fun draw(canvas: Canvas, x: Float, y: Float, width: Float) {
            paint.textAlign = Paint.Align.LEFT
            val fm = paint.fontMetrics
            val baseline = y + (height - (fm.descent - fm.ascent)) / 2f - fm.ascent
            if (!leader) {
                val line = if (page.isEmpty()) text else "$text, $page"
                var t = line
                if (paint.measureText(t) > width - indent) {
                    while (t.isNotEmpty() && paint.measureText("$t…") > width - indent) t = t.dropLast(1)
                    t = "$t…"
                }
                canvas.drawText(t, x + indent, baseline, paint)
                return
            }
            val pageW = paint.measureText(page)
            val room = width - indent - pageW - 24f
            var t = text
            if (paint.measureText(t) > room) {
                while (t.isNotEmpty() && paint.measureText("$t…") > room) t = t.dropLast(1)
                t = "$t…"
            }
            canvas.drawText(t, x + indent, baseline, paint)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9E9E9E.toInt() }
            var dx = x + indent + paint.measureText(t) + 6f
            while (dx < x + width - pageW - 6f) { canvas.drawCircle(dx, baseline - 1.2f, 0.6f, dot); dx += 3.5f }
            canvas.drawText(page, x + width - pageW, baseline, paint)
        }
    }

    /** [shift] moves the baseline: negative raises a superscript, positive lowers a subscript. */
    /** A piece of a line: its text in its paint, [width] wide — or, [leader] set, a tab that wide filled with its leader. */
    /** A piece of a line in its run's [paint]; [spacing]: the run's character spacing, after each of its characters. */
    private class Seg(val text: String, val paint: Paint, var width: Float, val shift: Float = 0f, val leader: com.therealityteam.aeropages.model.TabLeader? = null,
                      val spacing: Float = 0f)

    /** One line: its segments, where its baseline sits below the line top ([ascent]) and its pitch. */
    /** A drop cap: its letters ([seg]), [x] from the paragraph's indent, on line [line]'s baseline (counting on [pitch] apart past the text's last line). */
    private class DropSeg(val seg: Seg, val x: Float, val line: Int, val pitch: Float)

    /** A line of text; beside a floating object its room starts [lead] in from the paragraph's and ends [cut] short of its right. */
    private class TextLine(val segs: List<Seg>, val align: TextAlign, val ascent: Float, height: Float, val last: Boolean,
                           val lead: Float = 0f, val cut: Float = 0f) : Drawable(height) {
        override fun draw(canvas: Canvas, x: Float, y: Float, width: Float) {
            // Trailing spaces hang past the edge and do not count for alignment.
            var end = segs.size
            while (end > 0 && segs[end - 1].text.isBlank()) end--
            val total = (0 until end).sumOf { segs[it].width.toDouble() }.toFloat()
            val gaps = if (align == TextAlign.JUSTIFY && !last) (0 until end).count { segs[it].text.isBlank() && segs[it].leader == null } else 0
            val stretch = if (gaps > 0) ((width - total) / gaps).coerceAtLeast(0f) else 0f
            var cx = x + when (align) {
                TextAlign.CENTER -> (width - total) / 2f
                TextAlign.RIGHT -> width - total
                else -> 0f
            }
            val baseline = y + ascent
            for (i in 0 until end) {
                val seg = segs[i]
                if (seg.leader != null) { leader(canvas, seg, cx, baseline); cx += seg.width; continue }
                if (seg.spacing != 0f) {
                    // Letter by letter, each followed by the run's spacing (the paint's own leaves it off a run's ends).
                    var lx = cx
                    seg.text.forEach { c -> val t = c.toString(); canvas.drawText(t, lx, baseline + seg.shift, seg.paint); lx += seg.paint.measureText(t) + seg.spacing }
                } else canvas.drawText(seg.text, cx, baseline + seg.shift, seg.paint)
                cx += seg.width + (if (seg.text.isBlank()) stretch else 0f)
            }
        }

        /** A tab's leader across its width: its characters repeated up to the text after it, or a rule. */
        private fun leader(canvas: Canvas, seg: Seg, x: Float, baseline: Float) {
            val ch = when (seg.leader) {
                com.therealityteam.aeropages.model.TabLeader.DOT -> "."
                com.therealityteam.aeropages.model.TabLeader.HYPHEN -> "-"
                com.therealityteam.aeropages.model.TabLeader.MIDDLE_DOT -> "·"
                com.therealityteam.aeropages.model.TabLeader.UNDERSCORE -> {
                    val y = baseline + seg.paint.textSize * 0.12f
                    canvas.drawLine(x, y, x + seg.width, y, Paint(seg.paint).apply { strokeWidth = (seg.paint.textSize / 16f).coerceAtLeast(0.5f) })
                    return
                }
                else -> return
            }
            // Each character on its own, evenly from the text after the tab back to the text before it
            // (a run of them would be shaped together: PICO Sans narrows dots that follow dots).
            val w = seg.paint.measureText(ch).takeIf { it > 0f } ?: return
            var at = x + seg.width - w * 1.25f
            while (at >= x + w * 0.25f) { canvas.drawText(ch, at, baseline, seg.paint); at -= w }
        }
    }

    /** A text box: its paragraphs inside a thin frame. */
    /**
     * A text box [w] wide, its frame [frameH] tall: a thin rule round its text, or — a drawn shape —
     * the shape as slides draw it (fill, outline, dashes, arrowheads, turn and flips) with its text
     * placed top, middle or bottom, turned with it.
     */
    private class TextBoxDraw(val paras: List<PdfPara>, val w: Float, val shape: com.therealityteam.aeropages.model.SlideShape?, val frameH: Float,
                              val floating: com.therealityteam.aeropages.model.FloatPosition? = null, val pg: PageSetup? = null) :
        Drawable(frameH + 2 * PageMetrics.BOX_MARGIN) {
        override fun draw(canvas: Canvas, x0: Float, y: Float, width: Float) {
            // A floating box the text goes around keeps its place across its column.
            val x = if (floating != null && pg != null) x0 + FloatPlacement.acrossColumn(floating, w, pg, pg.widthPt, 1f, x0, width) else x0
            val top = y + PageMetrics.BOX_MARGIN
            val textH = paras.sumOf { it.height.toDouble() }.toFloat()
            if (shape == null) {
                val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.75f; color = 0xFF666666.toInt() }
                canvas.drawRect(x, top, x + w, top + frameH, border)
                var ly = top + PageMetrics.BOX_INSET
                paras.forEach { p -> p.drawWhole(canvas, x + PageMetrics.BOX_INSET, ly, w - 2 * PageMetrics.BOX_INSET); ly += p.height }
                return
            }
            canvas.withRotation(shape.rotationDeg, x + w / 2f, top + frameH / 2f) {
                ShapePainter.shape(this, shape, x, top, w, frameH)
                var ly = when (shape.anchor) {
                    com.therealityteam.aeropages.model.ShapeAnchor.TOP -> top + PageMetrics.BOX_INSET
                    com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> top + frameH - PageMetrics.BOX_INSET - textH
                    com.therealityteam.aeropages.model.ShapeAnchor.CENTER -> top + (frameH - textH) / 2f
                }
                paras.forEach { p -> p.drawWhole(this, x + PageMetrics.BOX_INSET, ly, w - 2 * PageMetrics.BOX_INSET); ly += p.height }
            }
        }
    }

    /**
     * A picture [w] × [h] in its column (or cell): centred, or — floating, the text going around it — at
     * its place across the column on a page of [pg].
     */
    private class ImageDraw(val image: ImageBlock, val w: Float, val h: Float, val pg: PageSetup? = null) : Drawable(h + 2 * PageMetrics.IMAGE_PAD) {
        override fun draw(canvas: Canvas, x: Float, y: Float, width: Float) {
            val left = x + (pg?.let { FloatPlacement.acrossColumn(image.floating, w, it, it.widthPt, 1f, x, width) } ?: ((width - w) / 2f))
            drawPicture(canvas, image, android.graphics.RectF(left, y + PageMetrics.IMAGE_PAD, left + w, y + PageMetrics.IMAGE_PAD + h))
        }
    }

    /** [image] in [dst]: the part its crop leaves, its shadow under it, mirrored as it says. */
    private fun drawPicture(canvas: Canvas, image: ImageBlock, dst: android.graphics.RectF) {
        val bmp = runCatching {
            if (com.therealityteam.aeropages.format.VectorPictures.isVector(image.data)) com.therealityteam.aeropages.format.VectorPictures.rasterize(image.data, 2048f, 2048f)
            else BitmapFactory.decodeByteArray(image.data, 0, image.data.size)
        }.getOrNull() ?: return
        val (l, t, r, b) = image.visiblePixels(bmp.width, bmp.height)
        image.shadow?.let { PictureShadows.draw(canvas, bmp, android.graphics.Rect(l, t, r, b), dst, it, 1f) }
        canvas.withScale(if (image.flipH) -1f else 1f, if (image.flipV) -1f else 1f, dst.centerX(), dst.centerY()) {
            drawBitmap(bmp, android.graphics.Rect(l, t, r, b), dst, null)
        }
    }

    /**
     * Where a picture or text box behind or in front of the text is anchored: no room in the flow.
     * [render] places it on the page from [pos] and draws it there, [w] × [h] — a text box by [box].
     */
    private class PdfFloat(val block: Block, val pos: com.therealityteam.aeropages.model.FloatPosition, val w: Float, val h: Float, val box: TextBoxDraw?,
                           val table: PdfTable? = null) : PdfBlock() {
        override val item: PageFlow.Item = PageFlow.Solid(0f, keepWithNext = true)
        override fun draw(canvas: Canvas, piece: PageFlow.Piece, x: Float, y: Float, w: Float) {}
        val behind: Boolean get() = pos.wrap == com.therealityteam.aeropages.model.FloatWrap.BEHIND

        fun drawAt(canvas: Canvas, x: Float, y: Float) {
            when {
                table != null -> table.drawAll(canvas, x, y)
                box != null -> box.draw(canvas, x, y - PageMetrics.BOX_MARGIN, w)
                block is ImageBlock -> drawPicture(canvas, block, android.graphics.RectF(x, y, x + w, y + h))
            }
        }
    }

    /** One table row: each cell's x, width, content (paragraphs, then pictures) and shade; [borders]: thin black lines round the cells. */
    private class TableRowDraw(
        val cellX: FloatArray,
        val cellW: FloatArray,
        val content: List<List<Any>>,
        val shades: List<Long?>,
        height: Float,
        val notes: IntArray,
        /** The lines along each cell's sides (Table.drawnSides). */
        val sides: List<com.therealityteam.aeropages.model.CellBorders> = emptyList(),
    ) : Drawable(height) {
        override fun draw(canvas: Canvas, x: Float, y: Float, width: Float) = draw(canvas, x, y)

        fun draw(canvas: Canvas, x: Float, y: Float) {
            content.forEachIndexed { ci, _ ->
                val cx = x + cellX[ci]
                shades.getOrNull(ci)?.let { canvas.drawRect(cx, y, cx + cellW[ci], y + height, Paint().apply { color = it.toInt() }) }
            }
            // The lines over every shade (a neighbour's shade must not cover a side).
            sides.forEachIndexed { ci, b ->
                val cx = x + cellX[ci]; val r = cx + cellW.getOrElse(ci) { 0f }
                b.top?.let { cellSide(canvas, it, cx, y, r, y, true) }
                b.bottom?.let { cellSide(canvas, it, cx, y + height, r, y + height, true) }
                b.left?.let { cellSide(canvas, it, cx, y, cx, y + height, false) }
                b.right?.let { cellSide(canvas, it, r, y, r, y + height, false) }
            }
            content.forEachIndexed { ci, items ->
                val cx = x + cellX[ci]
                var ty = y + PageMetrics.CELL_PAD_V
                val innerX = cx + PageMetrics.CELL_PAD_H
                val innerW = cellW[ci] - 2 * PageMetrics.CELL_PAD_H
                items.forEach { item ->
                    when (item) {
                        is PdfPara -> { item.drawWhole(canvas, innerX, ty, innerW); ty += item.height }
                        is ImageDraw -> { item.draw(canvas, innerX, ty, innerW); ty += item.height }
                    }
                }
            }
        }
    }

    /**
     * A table cell's side from ([x1], [y1]) to ([x2], [y2]): its width in points, colour (automatic: black) and kind —
     * dashes scaled to its width, a double line as two [horizontal]ly or vertically apart.
     */
    internal fun cellSide(canvas: Canvas, line: com.therealityteam.aeropages.model.CellBorder, x1: Float, y1: Float, x2: Float, y2: Float, horizontal: Boolean) {
        val w = line.widthPt.coerceAtLeast(0.25f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = w; color = (line.color ?: 0xFF000000L).toInt()
            line.style.dash?.takeIf { line.style != com.therealityteam.aeropages.model.LineStyle.DOUBLE }?.let { d ->
                pathEffect = android.graphics.DashPathEffect(FloatArray(d.size) { d[it] * w * 1.5f }, 0f)
            }
        }
        if (line.style == com.therealityteam.aeropages.model.LineStyle.DOUBLE) {
            val dx = if (horizontal) 0f else w; val dy = if (horizontal) w else 0f
            canvas.drawLine(x1 - dx, y1 - dy, x2 - dx, y2 - dy, paint)
            canvas.drawLine(x1 + dx, y1 + dy, x2 + dx, y2 + dy, paint)
        } else canvas.drawLine(x1, y1, x2, y2, paint)
    }

    // --- layout ------------------------------------------------------------------

    private fun layoutTable(table: Table, width: Float, footnotes: Int, labels: Map<Paragraph, String>? = null): PdfTable {
        val rows = table.rows.mapIndexed { ri, row ->
            val weights = PageMetrics.cellWeights(table, row)
            val sum = weights.sum().coerceAtLeast(0.1f)
            val cellW = FloatArray(weights.size) { width * weights[it] / sum }
            val cellX = FloatArray(weights.size).also { xs -> for (i in 1 until xs.size) xs[i] = xs[i - 1] + cellW[i - 1] }
            var rowH = PageMetrics.ROW_MIN
            val notes = ArrayList<Int>()
            val content = row.cells.mapIndexed { ci, cell ->
                val inner = (cellW[ci] - 2 * PageMetrics.CELL_PAD_H).coerceAtLeast(1f)
                val items = ArrayList<Any>()
                var h = 2 * PageMetrics.CELL_PAD_V
                if (!cell.vMerged) {
                    val ords = labels ?: PageMetrics.listLabels(cell.content.asSequence())
                    cell.content.forEach { p ->
                        val para = layoutPara(p, inner, ords[p], footnotes)
                        items.add(para); h += para.height; notes.addAll(para.allNotes())
                    }
                    cell.images.forEach { img ->
                        val (w, ih) = PageMetrics.fitImage(img.widthPt, img.heightPt, inner)
                        val d = ImageDraw(img, w, ih)
                        items.add(d); h += d.height
                    }
                }
                rowH = maxOf(rowH, h)
                items
            }
            TableRowDraw(cellX, cellW, content, row.cells.map { it.shade }, PageMetrics.rowHeight(row, rowH), notes.toIntArray(), row.cells.indices.map { table.drawnSides(ri, it) })
        }
        return PdfTable(rows, table.headerRowCount())
    }

    /**
     * Lays out one paragraph [width] wide: wraps its runs into lines of one pitch each (the tallest
     * font × single spacing × the paragraph's line spacing, as the editor does), notes which
     * footnotes each line references, and prepares its list marker.
     */
    private fun layoutPara(
        p: Paragraph, width: Float, label: String?, footnotes: Int,
        keepWithNext: Boolean = false, headingId: Long? = null, grid: Float? = null,
        /** What floating objects beside line [index] take from its left and right (null: nothing): square wrapping. */
        room: ((index: Int) -> Pair<Float, Float>?)? = null,
    ): PdfPara {
        val f = p.format
        val markerText = PageMetrics.markerOf(p, label)
        val markerW = if (markerText != null) PageMetrics.markerWidth(f, markerText, PageMetrics.maxSizePt(p)) else 0f
        val inset = PageMetrics.indents(f)
        val textW = (width - inset[0] - inset[3] - markerW).coerceAtLeast(1f)
        val pitch = PageMetrics.pitchPt(p, grid)
        val fallback = f.style.sizePt
        // Word's drop cap: its letters set apart, large (see DropCaps); dropped, the first lines start past them.
        val dropLen = com.therealityteam.aeropages.model.DropCaps.length(p)
        val drop = if (dropLen == 0) null else f.dropCap!!.let { d ->
            val capText = com.therealityteam.aeropages.model.DropCaps.text(p)
            val size = com.therealityteam.aeropages.model.DropCaps.size(capText, d.lines, pitch, PageMetrics.maxSizePt(p))
            val fmt = com.therealityteam.aeropages.model.DropCaps.format(p).copy(fontSizePt = size, superscript = false, subscript = false, underline = false, strikethrough = false)
            val paint = paintFor(fmt, fallback, capText).also { pp -> com.therealityteam.aeropages.model.HanLocale.of(p)?.let { pp.textLocale = it } }
            val capW = paint.measureText(capText)
            DropSeg(Seg(capText, paint, capW), if (d.inMargin) inset[2] - capW - d.distancePt else inset[2], d.lines - 1, pitch) to (if (d.inMargin) 0f else capW + d.distancePt)
        }
        val dropLines = if (drop != null && drop.second > 0f) f.dropCap!!.lines else 0
        /** What line [index] gives up on its left and right: floating objects beside it, and a dropped cap's letters (past the first line's own indent). */
        fun roomOf(index: Int): Pair<Float, Float>? {
            val beside = room?.invoke(index)
            if (index >= dropLines) return beside
            val lead = drop!!.second + (if (index == 0) inset[2] - inset[1] else 0f)
            return ((beside?.first ?: 0f) + lead) to (beside?.second ?: 0f)
        }
        // The first line indented (or the others, hanging): each line as wide as its own start leaves.
        fun lineW(index: Int) = (textW - (if (index == 0) inset[1] else inset[2]) - (roomOf(index)?.let { it.first + it.second } ?: 0f)).coerceAtLeast(1f)
        // Chinese characters in the paragraph's language's forms, as the canvas draws them (see HanLocale).
        val han = com.therealityteam.aeropages.model.HanLocale.of(p)
        // Each tab as wide as TabLayout makes it with this page's measures (the canvas asks it too).
        val tabs = TabLayout.tabs(p, width, defaultTab.get() ?: 36f) { s, fmt -> paintFor(fmt, fallback, s).measureText(s) }
        var tabIndex = 0
        val lines = ArrayList<TextLine>()
        val lineStarts = ArrayList<Int>()
        var cur = ArrayList<Seg>()
        var curW = 0f
        var maxAscent = 0f
        var maxDescent = 0f
        var offset = 0
        var lineStart = 0

        fun flush() {
            val natural = maxAscent + maxDescent
            // The spare room is shared above and below the text in proportion (Compose's Proportional line height).
            val ascent = if (natural > 0f) maxAscent * pitch / natural else pitch * 0.8f
            val beside = roomOf(lines.size)
            lines.add(TextLine(cur, f.align, ascent, pitch, last = false, lead = beside?.first ?: 0f, cut = beside?.second ?: 0f))
            lineStarts.add(lineStart)
            cur = ArrayList(); curW = 0f; maxAscent = 0f; maxDescent = 0f
            lineStart = offset
        }

        var dropLeft = dropLen
        p.runs.forEach { whole ->
            // A change the review display leaves out (a deletion with the changes taken, an insertion in the original).
            if (!com.therealityteam.aeropages.model.Markups.shows(whole.format)) { offset += whole.text.length; return@forEach }
            // The drop cap's letters are drawn apart.
            val run = if (dropLeft == 0) whole else {
                val k = minOf(dropLeft, whole.text.length)
                dropLeft -= k; offset += k
                if (k == whole.text.length) return@forEach
                whole.copy(text = whole.text.substring(k))
            }
            val paint = paintFor(run.format, fallback, run.text).also { pp -> han?.let { pp.textLocale = it } }
            val fm = paint.fontMetrics
            val baseSize = run.format.fontSizePt ?: fallback
            val shift = when {
                run.format.superscript -> -0.35f * baseSize
                run.format.subscript -> 0.15f * baseSize
                else -> 0f
            }
            val asc = -fm.ascent - minOf(shift, 0f)
            val desc = fm.descent + maxOf(shift, 0f)
            val spacing = run.format.letterSpacingPt ?: 0f
            tokenize(run.text).forEach { token ->
                if (token == "\n") { offset++; flush(); return@forEach }
                val pieces = if (token.length > 1 && paint.measureText(token) > lineW(lines.size)) token.map { it.toString() } else listOf(token)
                pieces.forEach { piece ->
                    if (piece == "\t") {
                        val tab = tabs.getOrNull(tabIndex++)
                        cur.add(Seg(piece, paint, tab?.advance ?: paint.measureText(" "), shift, tab?.leader ?: com.therealityteam.aeropages.model.TabLeader.NONE))
                        curW += cur.last().width; offset++
                        maxAscent = maxOf(maxAscent, asc); maxDescent = maxOf(maxDescent, desc)
                        return@forEach
                    }
                    val w = paint.measureText(piece) + spacing * piece.length
                    if (curW + w > lineW(lines.size) && cur.isNotEmpty() && piece.isNotBlank()) {
                        // No line starts with closing punctuation, none ends with an opening one: the
                        // character before moves down with it (as Android's line breaker does).
                        val carry = ArrayList<Seg>()
                        val lastSeg = cur.last()
                        if ((piece.length == 1 && piece[0] in NO_LINE_START && lastSeg.text.length == 1 && !lastSeg.text[0].isWhitespace() && cur.size > 1) ||
                            (lastSeg.text.length == 1 && lastSeg.text[0] in NO_LINE_END && cur.size > 1)) {
                            carry.add(cur.removeAt(cur.lastIndex))
                            offset -= carry[0].text.length
                        }
                        flush()
                        carry.forEach { c -> cur.add(c); curW += c.width; offset += c.text.length }
                        if (carry.isNotEmpty()) {
                            maxAscent = maxOf(maxAscent, -carry[0].paint.fontMetrics.ascent)
                            maxDescent = maxOf(maxDescent, carry[0].paint.fontMetrics.descent)
                        }
                    }
                    cur.add(Seg(piece, paint, w, shift, spacing = spacing)); curW += w
                    offset += piece.length
                    maxAscent = maxOf(maxAscent, asc); maxDescent = maxOf(maxDescent, desc)
                }
            }
        }
        if (cur.isNotEmpty() || lines.isEmpty()) {
            if (maxAscent == 0f) {
                // An empty line takes the metrics of the paragraph's first run.
                val fm = paintFor(p.runs.firstOrNull()?.format ?: CharacterFormat(), fallback).fontMetrics
                maxAscent = -fm.ascent; maxDescent = fm.descent
            }
            flush()
        }
        val lastIndex = lines.lastIndex
        val finalLines = lines.mapIndexed { i, l -> if (i == lastIndex) TextLine(l.segs, l.align, l.ascent, l.height, last = true, lead = l.lead, cut = l.cut) else l }
        // Which footnotes each line references.
        val anchors = PageMetrics.footnoteAnchors(p, footnotes)
        val notes = if (anchors.isEmpty()) null else {
            val per = Array(finalLines.size) { ArrayList<Int>() }
            anchors.forEach { (at, number) ->
                var line = 0
                for (i in lineStarts.indices) if (lineStarts[i] <= at) line = i
                per[line.coerceIn(0, per.lastIndex)].add(number)
            }
            per.map { it.toIntArray() }
        }
        val marker = markerText?.let { t ->
            val paint = paintFor(p.runs.firstOrNull()?.format?.copy(superscript = false, subscript = false, underline = false, strikethrough = false) ?: CharacterFormat(), fallback)
            Seg(t, paint, paint.measureText(t))
        }
        val edge = boxEdges.get()[p]
        // A text shorter than the drop keeps the letters' lines below it.
        val dropRoom = (dropLines - finalLines.size).coerceAtLeast(0) * pitch
        return PdfPara(finalLines, com.therealityteam.aeropages.model.ParagraphBoxes.beforePt(f, edge), com.therealityteam.aeropages.model.ParagraphBoxes.afterPt(f, edge) + dropRoom,
            inset[0], marker, keepWithNext, headingId, notes,
            f.keepTogether, f.widowControl, f.pageBreakBefore, firstOffset = inset[1], restOffset = inset[2], right = inset[3], markerW = markerW,
            box = f.takeIf { com.therealityteam.aeropages.model.ParagraphBoxes.boxed(it) }, edge = edge, drop = drop?.first)
    }

    // --- header / footer -------------------------------------------------------

    /**
     * The page's header, its top [PageSetup.headerPt] below the page's edge, and its footer, its foot
     * [PageSetup.footerPt] above the bottom edge, as Word places them (see HeaderFooters.layout): line by
     * line, each line's pictures in line and its text side by side as its paragraph aligns them, the text
     * on the line's foot in its runs' looks, or — a line with tabs — in the left, centre and right slots,
     * the page number and count filled in.
     */
    private fun drawHeaderFooter(canvas: Canvas, doc: Document, pg: PageSetup, pageNum: Int, pageCount: Int, shown: String = pageNum.toString()) {
        val left = pg.marginLeftPt
        val width = pg.widthPt - pg.marginLeftPt - pg.marginRightPt
        val fallback = doc.styles.resolve(com.therealityteam.aeropages.model.ParagraphStyle.NORMAL).sizePt
        for (header in listOf(true, false)) {
            val align = (if (header) doc.headerFor(pageNum) else doc.footerFor(pageNum))?.format?.align ?: TextAlign.LEFT
            for (l in HeaderFooters.layout(doc, pageNum, pageCount, pg, header, shown)) {
                val baseline = l.top + l.height - l.textHeight + l.textHeight * 0.8f
                fun paints(runs: List<TextRun>) = runs.map { r -> r to paintFor(r.format, fallback, r.text) }
                fun widthOf(runs: List<Pair<TextRun, Paint>>) = runs.sumOf { (r, paint) -> paint.measureText(r.text).toDouble() }.toFloat()
                fun drawRuns(runs: List<Pair<TextRun, Paint>>, x0: Float) {
                    var x = x0
                    for ((r, paint) in runs) { canvas.drawText(r.text, x, baseline, paint); x += paint.measureText(r.text) }
                }
                val slots = l.line.slots
                val xs = if (slots == null) {
                    val runs = paints(l.line.runs)
                    val (xs, textX) = HeaderFooters.across(l, align, widthOf(runs), left, width)
                    drawRuns(runs, textX)
                    xs
                } else {
                    val (lt, c, r) = slots.map { paints(it) }
                    drawRuns(lt, left)
                    drawRuns(c, left + (width - widthOf(c)) / 2f)
                    drawRuns(r, left + width - widthOf(r))
                    HeaderFooters.across(l, align, 0f, left, width).first
                }
                l.pictures.forEachIndexed { k, img ->
                    val y = l.top + l.height - img.heightPt
                    drawPicture(canvas, img, android.graphics.RectF(xs[k], y, xs[k] + img.widthPt, y + img.heightPt))
                }
            }
        }
    }

    // --- helpers ---------------------------------------------------------------

    /** The paint a run of [fmt] draws [text] in (see PdfFaces.paint). */
    private fun paintFor(fmt: CharacterFormat, fallbackSizePt: Float, text: CharSequence? = null): Paint {
        val p = PdfFaces.paint(text)
        p.typeface = PdfFaces.of(fmt.fontFamily, fmt.bold, fmt.italic)
        val script = fmt.superscript || fmt.subscript
        p.textSize = (fmt.fontSizePt ?: fallbackSizePt) * (if (script) 0.72f else 1f)
        p.color = (fmt.color ?: 0xFF000000L).toInt()
        p.isUnderlineText = fmt.underline
        p.isStrikeThruText = fmt.strikethrough
        // Tracked changes printed with their markup (Word's Print Markup): an insertion underlined, a deletion struck,
        // in the change's colour — when the changes show marked.
        if (com.therealityteam.aeropages.model.Markups.marks(fmt) && (fmt.inserted || fmt.deleted)) {
            p.color = REVISION_RED
            if (fmt.inserted) p.isUnderlineText = true
            if (fmt.deleted) p.isStrikeThruText = true
        }
        return p
    }

    /** A tracked change's colour on paper. */
    private const val REVISION_RED = 0xFFCC0000.toInt()

    /** Closing punctuation that may not start a line, opening punctuation that may not end one. */
    private const val NO_LINE_START = "，。、；：？！）」』】》〉”’…·,.;:?!)]}％%"
    private const val NO_LINE_END = "（「『【《〈“‘([{"


    /** Split into whitespace runs, words and single CJK characters, with newlines isolated. */
    private fun tokenize(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var ws = false
        fun push() { if (sb.isNotEmpty()) { out.add(sb.toString()); sb.clear() } }
        for (c in s) {
            when {
                c == '\n' -> { push(); out.add("\n") }
                c == '\t' -> { push(); out.add("\t") }
                PageMetrics.isCjk(c) -> { push(); out.add(c.toString()) }
                sb.isEmpty() -> { sb.append(c); ws = c.isWhitespace() }
                c.isWhitespace() == ws -> sb.append(c)
                else -> { push(); sb.append(c); ws = c.isWhitespace() }
            }
        }
        push()
        return out
    }
}
