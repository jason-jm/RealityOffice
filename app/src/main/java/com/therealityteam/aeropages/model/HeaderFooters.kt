package com.therealityteam.aeropages.model

/**
 * A header's or footer's content as the page shows it. Its paragraph holds its lines ('\n' between
 * them) and Word's page-number fields as the tokens [PAGE] and [PAGES]; a tab moves on to the next
 * of three slots — left, centre, right — as Word's Header and Footer styles place them with their
 * centre and right tab stops ("Title\t\tPage {PAGE}" puts the page number at the right margin).
 */
object HeaderFooters {
    /** The current page's number (Word's PAGE field, ODF's `text:page-number`). */
    const val PAGE = "{PAGE}"

    /** The document's page count (NUMPAGES / SECTIONPAGES, ODF's `text:page-count`). */
    const val PAGES = "{PAGES}"

    /** The token a field's code stands for: PAGE the page's number, NUMPAGES / SECTIONPAGES the page count; null for any other field. */
    fun fieldToken(code: CharSequence): String? = when (code.trim().split(' ', '\t').firstOrNull()?.uppercase()) {
        "PAGE" -> PAGE
        "NUMPAGES", "SECTIONPAGES" -> PAGES
        else -> null
    }

    /** [text] with the fields filled in for [page] of [pages]. */
    fun fill(text: String, page: Int, pages: Int): String = fill(text, page.toString(), pages)

    /** [text] with the fields filled in: the page's number as it [shown] (its section's format and start), of [pages]. */
    fun fill(text: String, shown: String, pages: Int): String =
        if ('{' !in text) text else text.replace(PAGE, shown).replace(PAGES, pages.toString())

    /**
     * Each page's number as it shows, from each page's section ([setups], one per page, a section's pages sharing
     * its setup): a section starting its numbers again from its start, any other going on from the page before,
     * each in its section's format (i, ii … then 1, 2 …).
     */
    fun pageNumbers(setups: List<PageSetup>): List<String> {
        var n = 0
        var last: PageSetup? = null
        return setups.map { pg ->
            n = if (pg !== last && pg.pageNumberStart != null) pg.pageNumberStart!! else n + 1
            last = pg
            Lists.number(n, pg.pageNumberFormat)
        }
    }

    /** One line of a header: its runs in the left, centre and right slots (null: the line has no tab, [align] places it). */
    class Line(val runs: List<TextRun>, val slots: List<List<TextRun>>?)

    /**
     * [p]'s lines for [page] of [pages], fields filled in: each split at its tabs into up to three
     * slots (a fourth and later go on in the right one).
     */
    fun lines(p: Paragraph, page: Int, pages: Int, shown: String = page.toString()): List<Line> {
        val lines = ArrayList<MutableList<MutableList<TextRun>>>()
        var slots = mutableListOf(mutableListOf<TextRun>())
        lines.add(slots)
        for (run in p.runs) {
            val text = fill(run.text, shown, pages)
            var from = 0
            for (i in text.indices) {
                val c = text[i]
                if (c != '\n' && c != '\t') continue
                if (i > from) slots.last().add(TextRun(text.substring(from, i), run.format))
                if (c == '\n') { slots = mutableListOf(mutableListOf()); lines.add(slots) }
                else if (slots.size < 3) slots.add(mutableListOf())
                else slots.last().add(TextRun(" ", run.format))
                from = i + 1
            }
            if (from < text.length) slots.last().add(TextRun(text.substring(from), run.format))
        }
        return lines.map { s ->
            if (s.size == 1) Line(s[0], null)
            else Line(s.flatten(), listOf(s[0], s.getOrElse(1) { mutableListOf() }, s.getOrElse(2) { mutableListOf() }))
        }
    }

    /** [runs] line by line, split at their line breaks, their field tokens kept (at least one line, maybe empty). */
    fun runLines(runs: List<TextRun>): List<List<TextRun>> {
        val out = ArrayList<MutableList<TextRun>>().apply { add(ArrayList()) }
        for (r in runs) r.text.split('\n').forEachIndexed { i, t ->
            if (i > 0) out.add(ArrayList())
            if (t.isNotEmpty()) out.last().add(TextRun(t, r.format))
        }
        return out
    }

    /**
     * How a header's line [lineRuns] holding [inLine] pictures is aligned: as its paragraph ([align]), or —
     * a line of pictures alone — as their own paragraph aligned them.
     */
    fun lineAlign(lineRuns: List<TextRun>, inLine: List<ImageBlock>, align: TextAlign): TextAlign =
        when (inLine.firstOrNull()?.floating?.hAlign?.takeIf { lineRuns.all { it.text.isBlank() } }) {
            null -> align
            FloatAlign.START -> TextAlign.LEFT
            FloatAlign.CENTER -> TextAlign.CENTER
            FloatAlign.END -> TextAlign.RIGHT
        }

    /** One line's height as printed: its largest letters' (an empty line: the paragraph's first run's), single-spaced. */
    fun lineHeightPt(line: Line, p: Paragraph?, fallbackSizePt: Float): Float =
        line.runs.maxOfOrNull { (it.format.fontSizePt ?: fallbackSizePt) * PageMetrics.singleLine(it.format.fontFamily) }
            ?: p?.runs?.firstOrNull()?.format?.let { (it.fontSizePt ?: fallbackSizePt) * PageMetrics.singleLine(it.fontFamily) }
            ?: (fallbackSizePt * PageMetrics.SINGLE_LINE)

    /**
     * A header's or footer's line laid out on its page: its [top] and [height] (points from the page's top),
     * its text's height ([textHeight] — the text sits on the line's foot, as it sits on the baseline of a
     * picture beside it), its [pictures] in line, left to right.
     */
    class LaidLine(val line: Line, val top: Float, val height: Float, val textHeight: Float, val pictures: List<ImageBlock>) {
        val bottom: Float get() = top + height
    }

    /**
     * Page [pageNumber]'s (of [pageCount]) header ([header]) or footer on a page of [pg], line by line, top
     * down: each line as tall as its text or its tallest picture in line (Word's line grows to hold such a
     * picture — a letterhead's logo above its address), the header's first line at the header distance
     * from the top, the footer's last at the footer distance from the bottom. Empty when it shows nothing.
     */
    fun layout(doc: Document, pageNumber: Int, pageCount: Int, pg: PageSetup, header: Boolean, shown: String = pageNumber.toString()): List<LaidLine> {
        val p = if (header) doc.headerFor(pageNumber) else doc.footerFor(pageNumber)
        val inLine = doc.hfPicturesFor(pageNumber, header).filter { it.floating?.line != null }
        val text = p?.let { lines(it, pageNumber, pageCount, shown) } ?: emptyList()
        val count = maxOf(text.size, (inLine.maxOfOrNull { it.floating!!.line!! } ?: -1) + 1)
        if (count == 0) return emptyList()
        val fallback = doc.styles.resolve(ParagraphStyle.NORMAL).sizePt
        val rows = (0 until count).map { i ->
            val line = text.getOrElse(i) { Line(emptyList(), null) }
            val pics = inLine.filter { it.floating!!.line == i }
            // A line of pictures alone (past the text's lines) is as tall as they are.
            val textH = if (i < text.size || pics.isEmpty()) lineHeightPt(line, p, fallback) else 0f
            Triple(line, textH, pics)
        }
        val total = rows.sumOf { (_, textH, pics) -> maxOf(textH, pics.maxOfOrNull { it.heightPt } ?: 0f).toDouble() }.toFloat()
        var y = if (header) pg.headerPt else pg.heightPt - pg.footerPt - total
        return rows.map { (line, textH, pics) ->
            val h = maxOf(textH, pics.maxOfOrNull { it.heightPt } ?: 0f)
            LaidLine(line, y, h, textH, pics).also { y += h }
        }
    }

    /**
     * Where a laid-out line's pictures and text go across the margins' box ([left], [width] wide), the
     * text [textW] wide: side by side, the pictures first, placed together as the paragraph aligns them
     * ([align]; a line of pictures alone as their own paragraph did). A line with tab slots keeps its
     * text in its slots and its pictures at the left. Returns the pictures' left edges and the text's.
     */
    fun across(l: LaidLine, align: TextAlign, textW: Float, left: Float, width: Float): Pair<List<Float>, Float> {
        val picsW = l.pictures.sumOf { it.widthPt.toDouble() }.toFloat()
        val placing = lineAlign(l.line.runs, l.pictures, align)
        val group = picsW + (if (l.line.slots == null) textW else 0f)
        val start = if (l.line.slots != null) left else when (placing) {
            TextAlign.CENTER -> left + (width - group) / 2f
            TextAlign.RIGHT -> left + width - group
            else -> left
        }
        var x = start
        val xs = l.pictures.map { img -> x.also { x += img.widthPt } }
        return xs to start + picsW
    }

    /** A stretch down the body ([top]..[bottom] from its top) a header's or footer's picture keeps the text out of, in its [columns]. */
    class Span(val top: Float, val bottom: Float, val columns: Set<Int>)

    /**
     * What a page's header and footer take from its body (points): [top] from the body's top, [foot] from its
     * foot, and [spans] between.
     */
    class Room(val top: Float = 0f, val foot: Float = 0f, val spans: List<Span> = emptyList()) {
        val none: Boolean get() = top <= 0f && foot <= 0f && spans.isEmpty()
    }

    /**
     * What page [pageNumber]'s header and footer take from its body on a page of [pg]. Word and LibreOffice
     * start the body below a header that reaches past the top margin — its lines from the header distance,
     * their pictures in line, or a floating picture the text keeps clear of — and end it above a footer that
     * reaches past the bottom margin, rather than let them overlap; a floating picture of theirs standing in
     * between keeps the text out of its stretch of the page. (Pictures behind or in front of the text take
     * nothing; so do narrow ones the text could go beside, as in the body — see FloatPlacement.textKeepsClear.)
     */
    fun room(doc: Document, pageNumber: Int, pg: PageSetup): Room {
        val head = layout(doc, pageNumber, 1, pg, true)
        val foot0 = layout(doc, pageNumber, 1, pg, false)
        var top = (head.lastOrNull()?.bottom ?: 0f) - pg.marginTopPt
        var foot = foot0.firstOrNull()?.let { pg.heightPt - pg.marginBottomPt - it.top } ?: 0f
        val spans = ArrayList<Span>()
        val bodyH = pg.heightPt - pg.marginTopPt - pg.marginBottomPt
        val cols = PageMetrics.columnsOf(pg)
        val gap = PageMetrics.columnGapOf(pg)
        val colW = ((pg.widthPt - pg.marginLeftPt - pg.marginRightPt) - gap * (cols - 1)) / cols
        for (header in listOf(true, false)) for (img in doc.hfPicturesFor(pageNumber, header)) {
            val pos = img.floating ?: continue
            val (x, y) = FloatPlacement.placeInHeader(img, header, pg, paraTop = paraTop(header, if (header) head else foot0, pg)) ?: continue
            val t = y - pg.marginTopPt
            val b = t + img.heightPt
            if (b <= 0f || t >= bodyH) continue
            val columns = HashSet<Int>()
            var widest = 0f
            for (c in 0 until cols) {
                val cx = pg.marginLeftPt + c * (colW + gap)
                val overlap = minOf(x + img.widthPt, cx + colW) - maxOf(x, cx)
                if (overlap > 0f) { columns.add(c); widest = maxOf(widest, overlap) }
            }
            if (columns.isEmpty() || !FloatPlacement.textKeepsClear(pos, widest, colW, onPage = true)) continue
            when {
                t <= 0f -> top = maxOf(top, b)
                b >= bodyH -> foot = maxOf(foot, bodyH - t)
                else -> spans.add(Span(t, b, columns))
            }
        }
        return Room(top.coerceAtLeast(0f), foot.coerceAtLeast(0f), spans)
    }

    /** The top a header's or footer's floating picture moving with its paragraph counts from: its first line's (none: where it would be). */
    fun paraTop(header: Boolean, laid: List<LaidLine>, pg: PageSetup): Float =
        laid.firstOrNull()?.top ?: if (header) pg.headerPt else pg.heightPt - pg.footerPt - 14f
}
