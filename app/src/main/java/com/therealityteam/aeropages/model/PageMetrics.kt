package com.therealityteam.aeropages.model

/**
 * The layout model the editor canvas and the PDF export share, in points (on screen 1 pt = 1 dp).
 * Both measure text with their own engine, but spacing, indents, line pitch, table and picture
 * geometry and the generated text (contents, bibliography, notes) come from here, so a document
 * breaks into the same pages on screen and on paper.
 */
object PageMetrics {
    /** Word's "single" line height relative to the font size. */
    const val SINGLE_LINE = 1.2f

    /** Width of the bullet / number column in front of a list item (one without a hanging indent). */
    const val LIST_MARKER = 28f

    /** The narrowest a hanging indent leaves a list item's marker. */
    const val MIN_MARKER = 14f

    /** Space above and below a table (every page's part of it): little, as Word has none. */
    const val TABLE_PAD = 2f

    /** Cell padding: left/right (Word's 0.08 in) and top/bottom (Word's none, a breath here). */
    const val CELL_PAD_H = 5.4f
    const val CELL_PAD_V = 2f

    /** The lowest a table row gets: a line of text. */
    const val ROW_MIN = 14f

    /** A table [w] wide in a column [colW] wide (one unit, [unit] to the point): how far in from the column's left edge it stands. */
    fun tableOffset(t: Table, w: Float, colW: Float, unit: Float = 1f): Float = when (t.align) {
        TextAlign.CENTER -> (colW - w) / 2f
        TextAlign.RIGHT -> colW - w
        else -> (t.indentPt * unit).coerceIn(-colW * 0.25f, (colW - w).coerceAtLeast(0f))
    }

    /** How tall [row] is at least (points): its own height, else a line; an exact height is its height. */
    fun rowMin(row: TableRow): Float = if (row.exactHeight) (row.heightPt ?: ROW_MIN) else maxOf(ROW_MIN, row.heightPt ?: 0f)

    /** [row]'s height (points) when its content needs [content]: at least [rowMin], or just its exact height. */
    fun rowHeight(row: TableRow, content: Float): Float = if (row.exactHeight && row.heightPt != null) row.heightPt!! else maxOf(rowMin(row), content)

    /** Space above and below a picture. */
    const val IMAGE_PAD = 6f

    /** Space above and below a displayed equation. */
    const val EQUATION_PAD = 6f

    /** Text box: space above and below its frame, and the inset inside the frame. */
    const val BOX_MARGIN = 4f
    const val BOX_INSET = 8f

    /**
     * A text box's frame height (without its margins) for text [textHeight] tall: a plain box is as
     * tall as its text; a drawn shape keeps at least its own [shapeHeight] (a line none), with room
     * for its text when it has some.
     */
    fun boxFrameHeight(box: TextBoxBlock, textHeight: Float, unit: Float = 1f): Float {
        val withText = textHeight + 2 * BOX_INSET * unit
        if (box.shape == null) return withText
        return maxOf(if (box.paragraphs.isEmpty()) 0f else withText, (box.heightPt ?: 0f) * unit)
    }

    /**
     * How wide a text box's paragraphs are set: its [inner] width — or, a box that does not wrap its text (Word's
     * "wrap text in shape" off, `wrap="none"`), as wide as each line wants ([UNWRAPPED]), the lines then placed as
     * their paragraphs align them and running past the box when they are longer.
     */
    fun boxTextWidth(box: TextBoxBlock, inner: Float): Float = if (box.shape?.noWrap == true) UNWRAPPED else inner

    /** A width no line reaches: the room a box's text has when it does not wrap. */
    const val UNWRAPPED = 100_000f

    /** The most text columns a page takes (Word's Columns dialog allows as many as fit; six do on A4). */
    const val MAX_COLUMNS = 6

    /** Word's least column width: text columns are never made narrower (more columns fit no more). */
    const val MIN_COLUMN = 36f

    /** How many columns a page of [pg] has: as set, no more than fit at Word's least width with their gaps. */
    fun columnsOf(pg: PageSetup): Int {
        val body = pg.widthPt - pg.marginLeftPt - pg.marginRightPt
        var n = pg.columns.coerceIn(1, MAX_COLUMNS)
        while (n > 1 && (body - pg.columnGapPt * (n - 1)) / n < MIN_COLUMN) n--
        return n
    }

    /** The gap between [pg]'s columns, in points (none with one column). */
    fun columnGapOf(pg: PageSetup): Float = if (columnsOf(pg) > 1) pg.columnGapPt.coerceAtLeast(0f) else 0f

    /** Footnote area: the gap above the separator rule, the rule, the gap below it. */
    const val NOTE_GAP_ABOVE = 6f
    const val NOTE_RULE = 1f
    const val NOTE_GAP_BELOW = 3f
    const val NOTE_RULE_WIDTH = 160f

    /** Font sizes of generated text. */
    const val GENERATED_HEADING_SIZE = 16f
    const val ENTRY_SIZE = 11f
    const val NOTE_SIZE = 9f
    const val ENDNOTE_SIZE = 10f

    /** Height of one table-of-contents line. */
    val TOC_LINE: Float = ENTRY_SIZE * SINGLE_LINE * 1.25f

    /** Indent per table-of-contents level. */
    const val TOC_INDENT = 18f

    /** Line spacing multiplier, kept sane for odd imports (0 would stack every line on the first). */
    fun lineSpacing(p: Paragraph): Float = p.format.lineSpacing.let { if (it.isNaN()) 1f else it.coerceIn(0.5f, 5f) }

    /** The paragraph's largest font size: its lines are spaced by it. */
    fun maxSizePt(p: Paragraph): Float {
        val base = p.format.style.sizePt
        var max = 0f
        p.runs.forEach { r -> if (r.text.isNotEmpty()) max = maxOf(max, r.format.fontSizePt ?: base) }
        return if (max > 0f) max else (p.runs.firstOrNull()?.format?.fontSizePt ?: base)
    }

    /** CJK characters (ideographs, kana, hangul, full-width forms): lines break between any two of them. */
    fun isCjk(c: Char): Boolean =
        c in '\u2E80'..'\u9FFF' || c in '\uAC00'..'\uD7AF' || c in '\uF900'..'\uFAFF' || c in '\uFF00'..'\uFFEF'

    /**
     * A quick guess at how many lines [p] takes in a column [width] wide, counting a CJK character as
     * one em and anything else as half an em ([emPerPt] converts points to the caller's unit). Each
     * run's mix of CJK and other characters is sampled from its first 64 characters, so the guess
     * costs next to nothing; it is used only until the real measurement arrives.
     */
    fun estimateLines(p: Paragraph, width: Float, emPerPt: Float): Int {
        if (width <= 0f) return 1
        var total = 0f
        p.runs.forEach { r ->
            val n = r.text.length
            if (n == 0) return@forEach
            val em = (r.format.fontSizePt ?: p.format.style.sizePt) * emPerPt
            val sample = minOf(n, 64)
            var cjk = 0
            for (i in 0 until sample) if (isCjk(r.text[i])) cjk++
            val perChar = em * (0.5f + 0.5f * cjk / sample)
            total += perChar * n
        }
        // Wrapping wastes part of each line: count lines as 92 % full.
        return (kotlin.math.ceil(total / (width * 0.92f)).toInt()).coerceAtLeast(1)
    }

    /** Whether [p] stays on the page of the paragraph after it: its own setting, else Heading 1–3 do (Word's heading styles). */
    fun keepsWithNext(p: Paragraph): Boolean = p.format.keepWithNext ?: when (p.format.style) {
        ParagraphStyle.HEADING1, ParagraphStyle.HEADING2, ParagraphStyle.HEADING3 -> true
        else -> false
    }

    /**
     * Where a paragraph's lines start and end, from its left, first-line and right indents: the inset
     * of the whole paragraph from the column's left edge (never into the margin), the first line's and
     * the other lines' own offsets beyond it, and the inset from the right edge — as
     * [left, first, rest, right] in points.
     */
    fun indents(f: ParagraphFormat): FloatArray {
        // A list item's marker stands where its first line starts, all its lines after the marker (see markerWidth).
        if (f.listKind != ListKind.NONE) return floatArrayOf(maxOf(0f, f.indentLeftPt + f.firstLineIndentPt), 0f, 0f, maxOf(0f, f.indentRightPt))
        val firstStart = f.indentLeftPt + f.firstLineIndentPt
        val restStart = f.indentLeftPt
        val inset = maxOf(0f, minOf(firstStart, restStart))
        return floatArrayOf(inset, maxOf(0f, firstStart - inset), maxOf(0f, restStart - inset), maxOf(0f, f.indentRightPt))
    }

    /**
     * How wide a list item's marker column is: as Word lays a list out, the hanging indent's width —
     * the number out in the indent, the text at the left indent — else [LIST_MARKER]; 0 for no list.
     * A [label] too long for it ("1.1.1.", "（十二）") pushes the text on to the next 6 points past it.
     */
    fun markerWidth(f: ParagraphFormat, label: String? = null, sizePt: Float = 0f): Float {
        val base = when {
            f.listKind == ListKind.NONE -> return 0f
            f.firstLineIndentPt >= 0f -> LIST_MARKER
            else -> maxOf(f.indentLeftPt - maxOf(0f, f.indentLeftPt + f.firstLineIndentPt), MIN_MARKER)
        }
        if (label == null || sizePt <= 0f) return base
        val need = labelEms(label) * sizePt + LABEL_GAP
        return if (need <= base) base else kotlin.math.ceil(need / 6f) * 6f
    }

    /** The width of a list label in ems, as a sans-serif face and a CJK one set it. */
    fun labelEms(label: String): Float = label.sumOf { ch ->
        when {
            ch.code >= 0x2E80 || ch in "①②③④⑤⑥⑦⑧⑨⑩" -> 1.0
            ch in '0'..'9' -> 0.56
            ch == '.' || ch == ',' || ch == ' ' || ch == ':' -> 0.28
            ch == '(' || ch == ')' -> 0.33
            ch == 'i' || ch == 'l' || ch == 'I' -> 0.25
            ch == 'm' || ch == 'w' || ch == 'M' || ch == 'W' -> 0.85
            else -> 0.6
        }
    }.toFloat()

    /** The least room between a list label and its text. */
    const val LABEL_GAP = 3f

    /**
     * Distance between the paragraph's baselines: every line of it is this tall — its tallest single line (each run's
     * size times its face's [singleLine]; an empty paragraph's: its mark's), times its line spacing.
     */
    fun pitchPt(p: Paragraph, gridPt: Float? = null): Float {
        // An exact line height: the line is that tall, its letters and the grid notwithstanding.
        p.format.lineHeightPt?.takeIf { it > 0f && p.format.lineExact }?.let { return it.coerceAtMost(1584f) }
        val base = p.format.style.sizePt
        var max = 0f
        p.runs.forEach { r -> if (r.text.isNotEmpty()) max = maxOf(max, (r.format.fontSizePt ?: base) * singleLine(r.format.fontFamily)) }
        if (max <= 0f) max = p.runs.firstOrNull()?.format?.let { (it.fontSizePt ?: base) * singleLine(it.fontFamily) } ?: base * SINGLE_LINE
        // On a line grid ([gridPt], a body paragraph that keeps to it): as many pitches as the line needs.
        if (gridPt != null && gridPt > 0f && p.format.snapToGrid) max = gridLines(max, gridPt) * gridPt
        // At least a height: the single line (on its grid), no taller than it needs but not shorter than that.
        p.format.lineHeightPt?.takeIf { it > 0f }?.let { return maxOf(max, it.coerceAtMost(1584f)) }
        return max * lineSpacing(p)
    }

    /** How many pitches of a [gridPt] line grid a line [heightPt] tall takes (a hair over one still takes one). */
    fun gridLines(heightPt: Float, gridPt: Float): Int = kotlin.math.ceil(heightPt / gridPt - 0.02f).toInt().coerceAtLeast(1)

    /**
     * Word's "single" line for text in [family], in ems: the face's own height — ascent, descent and line gap — as Word
     * sets it (and LibreOffice with its metric-compatible faces): one em for the classic Chinese and Japanese faces
     * (SimSun, SimHei, FangSong, KaiTi, MingLiU, MS Mincho … — a cover in 22 pt 黑体 at triple spacing has its lines
     * 66 pt apart), 1.15 for Times New Roman and Arial, 1.17 Cambria, 1.22 Calibri, 1.32 Microsoft YaHei; [SINGLE_LINE]
     * for a face not known, or none.
     */
    fun singleLine(family: String?): Float {
        val f = family?.trim()?.lowercase() ?: return SINGLE_LINE
        if (f.isEmpty()) return SINGLE_LINE
        return LINE_EMS[f] ?: SINGLE_LINE
    }

    private val LINE_EMS: Map<String, Float> = buildMap {
        listOf("宋体", "simsun", "新宋体", "nsimsun", "黑体", "simhei", "仿宋", "fangsong", "仿宋_gb2312", "fangsong_gb2312", "楷体", "kaiti",
            "楷体_gb2312", "kaiti_gb2312", "隶书", "lisu", "幼圆", "youyuan", "细明体", "mingliu", "新细明体", "pmingliu", "標楷體", "dfkai-sb",
            "ｍｓ 明朝", "ms mincho", "ｍｓ ｐ明朝", "ms pmincho", "ｍｓ ゴシック", "ms gothic", "ｍｓ ｐゴシック", "ms pgothic").forEach { put(it, 1f) }
        listOf("times new roman", "arial", "liberation serif", "liberation sans", "helvetica").forEach { put(it, 1.15f) }
        put("cambria", 1.17f)
        put("calibri", 1.22f); put("carlito", 1.22f)
        put("微软雅黑", 1.32f); put("microsoft yahei", 1.32f)
    }

    /** The superscript `[n]` footnote references in [p]: (character offset, footnote number) pairs. */
    fun footnoteAnchors(p: Paragraph, footnoteCount: Int): List<Pair<Int, Int>> {
        if (footnoteCount == 0) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        var pos = 0
        p.runs.forEach { r ->
            if (r.format.superscript) FOOTNOTE_REF.findAll(r.text).forEach { m ->
                m.groupValues[1].toIntOrNull()?.takeIf { it in 1..footnoteCount }?.let { out.add(pos + m.range.first to it) }
            }
            pos += r.text.length
        }
        return out
    }

    private val FOOTNOTE_REF = Regex("""\[(\d+)]""")

    /**
     * The list labels of [paragraphs] in reading order (see [Lists.labels]): a box's or a header's own.
     * Null in the sequence is something other than a paragraph, which restarts the plain lists.
     */
    fun listLabels(paragraphs: Sequence<Paragraph?>, lists: Map<Int, ListDef> = emptyMap()): Map<Paragraph, String> = Lists.labels(paragraphs, lists)

    /**
     * Every list label of the document's body and tables, in reading order — a list goes on counting
     * through tables and on into them, as Word's does; the plain lists start over in each cell.
     */
    fun listLabels(doc: Document): Map<Paragraph, String> = Lists.labels(sequence {
        for (b in doc.blocks) when (b) {
            is ParagraphBlock -> yield(b.paragraph)
            is TableBlock -> {
                yield(null)
                for (row in b.table.rows) for (cell in row.cells) { cell.content.forEach { yield(it) }; yield(null) }
            }
            else -> yield(null)
        }
    }, doc.lists)

    /** The list marker in front of [p] ("•", "3.", "1.2.", "（一）"), or null when it is not a list item. */
    fun markerOf(p: Paragraph, label: String?): String? = when (p.format.listKind) {
        ListKind.NONE -> null
        ListKind.BULLET -> label ?: "•"
        ListKind.NUMBER -> label ?: "1."
    }

    /** Each cell's share of a row's width: the weights of the grid columns it spans. */
    fun cellWeights(table: Table, row: TableRow): FloatArray {
        var col = 0
        return FloatArray(row.cells.size) { i ->
            val span = row.cells[i].gridSpan.coerceAtLeast(1)
            val w = (0 until span).sumOf { table.weightOf(col + it).toDouble() }.toFloat().coerceAtLeast(0.1f)
            col += span
            w
        }
    }

    /** Cell widths for a row [total] wide, rounded to whole units with the last cell taking the rest (as on screen). */
    fun cellWidths(table: Table, row: TableRow, total: Int): IntArray = split(cellWeights(table, row), total)

    /** Splits [total] by [weights], rounding each part and giving the last one what remains. */
    fun split(weights: FloatArray, total: Int): IntArray {
        val sum = weights.sum().coerceAtLeast(0.1f)
        val out = IntArray(weights.size)
        var used = 0
        weights.forEachIndexed { i, w ->
            out[i] = if (i == weights.lastIndex) (total - used).coerceAtLeast(0) else Math.round(total * w / sum).also { used += it }
        }
        return out
    }

    /** A picture's size on the page: its own size, scaled down to fit [maxWidth]. */
    fun fitImage(widthPt: Float, heightPt: Float, maxWidth: Float): Pair<Float, Float> {
        val w = widthPt.coerceAtLeast(1f)
        val h = heightPt.coerceAtLeast(1f)
        if (w <= maxWidth || maxWidth <= 0f) return w to h
        return maxWidth to h * maxWidth / w
    }

    // --- generated text: both renderers draw these same paragraphs ------------------------

    /** Heading of a generated section (Contents, Bibliography). */
    fun generatedHeading(text: String): Paragraph = Paragraph(
        mutableListOf(TextRun(text, CharacterFormat(bold = true, fontSizePt = GENERATED_HEADING_SIZE))),
        ParagraphFormat(spaceBeforePt = 0f, spaceAfterPt = 8f, lineSpacing = 1f),
    )

    /** An entry of a generated section (a bibliography source). */
    fun generatedEntry(text: String): Paragraph = Paragraph(
        mutableListOf(TextRun(text, CharacterFormat(fontSizePt = ENTRY_SIZE))),
        ParagraphFormat(spaceBeforePt = 0f, spaceAfterPt = 4f, lineSpacing = 1f),
    )

    /** A grey hint in place of an empty generated section. */
    fun generatedHint(text: String): Paragraph = Paragraph(
        mutableListOf(TextRun(text, CharacterFormat(fontSizePt = ENDNOTE_SIZE, color = 0xFF888888))),
        ParagraphFormat(spaceBeforePt = 0f, spaceAfterPt = 4f, lineSpacing = 1f),
    )

    /** The "Endnotes" heading after the body. */
    fun endnotesHeading(text: String): Paragraph = Paragraph(
        mutableListOf(TextRun(text, CharacterFormat(bold = true, fontSizePt = 12f))),
        ParagraphFormat(spaceBeforePt = 18f, spaceAfterPt = 4f, lineSpacing = 1f),
    )

    /** One endnote: "[iv] text". */
    fun endnote(index: Int, text: String): Paragraph = Paragraph(
        mutableListOf(TextRun("[${Roman.of(index + 1)}] $text", CharacterFormat(fontSizePt = ENDNOTE_SIZE, color = 0xFF444444))),
        ParagraphFormat(spaceBeforePt = 0f, spaceAfterPt = 2f, lineSpacing = 1f),
    )

    /** One footnote at a page bottom: "[3] text". */
    fun footnote(number: Int, text: String): Paragraph = Paragraph(
        mutableListOf(TextRun("[$number] $text", CharacterFormat(fontSizePt = NOTE_SIZE, color = 0xFF444444))),
        ParagraphFormat(spaceBeforePt = 0f, spaceAfterPt = 1f, lineSpacing = 1f),
    )
}
