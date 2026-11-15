package com.therealityteam.aeropages.model

/** A slide table's look (Table Design ▸ Table Styles): its header fill, banded fill, plain fill and border colour. */
data class SlideTableStyle(val header: Long, val band: Long, val plain: Long, val border: Long)

/**
 * How a slide table draws — the same on the canvas, in the show and in the PDF: each cell's fill by its table style and
 * the style options (header and total rows, first and last columns, banded rows and columns), and what its text
 * defaults to (white and bold in a header or total row, as PowerPoint's medium styles have it; dark elsewhere).
 */
object SlideTableLook {
    /** The table styles, by [SlideTable.styleIndex]. */
    val STYLES = listOf(
        SlideTableStyle(0xFF808080, 0xFFF0F0F0, 0xFFFFFFFF, 0xFFBFBFBF),
        SlideTableStyle(0xFFB81E6A, 0xFFF7DCE9, 0xFFFCEFF5, 0xFFE8A0C2),
        SlideTableStyle(0xFF2B579A, 0xFFD6E2F5, 0xFFEFF4FC, 0xFFA9C2E8),
        SlideTableStyle(0xFFC0504D, 0xFFF5DDDC, 0xFFFCEFEF, 0xFFE0A8A6),
        SlideTableStyle(0xFFE8A33D, 0xFFFBEFD8, 0xFFFEF8EE, 0xFFEDCF95),
        SlideTableStyle(0xFF9BBB59, 0xFFE7EFD7, 0xFFF4F8EC, 0xFFC8D9A3),
        SlideTableStyle(0xFF8064A2, 0xFFE4DCEC, 0xFFF2EEF7, 0xFFC2B0D6),
        SlideTableStyle(0xFF4BACC6, 0xFFD7EDF3, 0xFFEEF8FB, 0xFFA8D6E3),
    )

    /** The text colour a cell's runs fall back to outside the header and total rows. */
    const val TEXT = 0xFF1A1A1A

    fun styleOf(t: SlideTable): SlideTableStyle = STYLES.getOrElse(t.styleIndex) { STYLES[0] }

    /** Row [r] is the header or the total row: drawn in the header colour. */
    fun emphasized(t: SlideTable, r: Int): Boolean = (t.headerRow && r == 0) || (t.totalRow && r == t.rows - 1)

    /** Cell ([r], [c])'s fill: its own shading (transparent for No Fill), else its table style's. */
    fun fill(t: SlideTable, r: Int, c: Int): Long = t.cells.getOrNull(r)?.getOrNull(c)?.fill ?: styleFill(t, r, c)

    /** What the table style and its options give cell ([r], [c]), whatever its own shading. */
    fun styleFill(t: SlideTable, r: Int, c: Int): Long {
        val style = styleOf(t)
        return when {
            emphasized(t, r) -> style.header
            t.firstColumn && c == 0 -> style.band
            t.lastColumn && c == t.cols - 1 -> style.band
            t.bandedRows && r % 2 == 1 -> style.band
            t.bandedColumns && c % 2 == 1 -> style.band
            else -> style.plain
        }
    }

    private val ON_HEADER = MasterText(null, null, 0xFFFFFFFF, true)

    /** A colour that fills nothing: a cell's No Fill. */
    fun isNone(color: Long): Boolean = (color ushr 24) == 0L

    /**
     * After a reader has the table's options: the style whose fills most cells take ([actual] gives each cell's fill as
     * read), then each cell whose fill differs from that style's its own shading.
     */
    fun adopt(t: SlideTable, actual: (Int, Int) -> Long?) {
        // The places a merged cell covers show nothing of their own (LibreOffice writes its default fill there): not counted.
        val cover = t.coverage()
        fun shown(r: Int, c: Int) = !t.covered(cover, r, c)
        if ((0 until t.rows).none { r -> (0 until t.cols).any { c -> shown(r, c) && actual(r, c) != null } }) return   // no fills: a style part draws it
        var best = t.styleIndex; var most = -1
        for (i in STYLES.indices) {
            t.styleIndex = i
            var n = 0
            for (r in 0 until t.rows) for (c in 0 until t.cols) if (shown(r, c) && actual(r, c) == styleFill(t, r, c)) n++
            if (n > most) { most = n; best = i }
        }
        t.styleIndex = best
        for (r in 0 until t.rows) for (c in 0 until t.cols) {
            if (!shown(r, c)) continue
            val a = actual(r, c) ?: continue
            if (a != styleFill(t, r, c)) t.cells[r][c].fill = a
        }
    }

    /** After reading: the places a merged cell covers keep no look of their own (their fill and text position never show). */
    fun tidyCovered(t: SlideTable) {
        if (!t.hasMerges) return
        val cover = t.coverage()
        for (r in 0 until t.rows) for (c in 0 until t.cols) if (t.covered(cover, r, c)) t.cells[r][c].let { it.fill = null; it.cellAnchor = null }
    }

    /** A side of a cell (Table Design ▸ Borders). */
    enum class Side { TOP, BOTTOM, LEFT, RIGHT }

    /** The style's grid: a 0.5 pt line in its border colour. */
    fun styleLine(t: SlideTable): CellBorder = CellBorder(LineStyle.THIN, 0.5f, styleOf(t).border)

    fun CellBorders.side(s: Side): CellBorder? = when (s) { Side.TOP -> top; Side.BOTTOM -> bottom; Side.LEFT -> left; Side.RIGHT -> right }

    /** [b] with [s]'s line [line]. */
    fun CellBorders.with(s: Side, line: CellBorder?): CellBorders = when (s) {
        Side.TOP -> copy(top = line); Side.BOTTOM -> copy(bottom = line); Side.LEFT -> copy(left = line); Side.RIGHT -> copy(right = line)
    }

    /** The cell across [s] from ([r], [c]) and its side facing back. */
    fun across(r: Int, c: Int, s: Side): Triple<Int, Int, Side> = when (s) {
        Side.TOP -> Triple(r - 1, c, Side.BOTTOM); Side.BOTTOM -> Triple(r + 1, c, Side.TOP)
        Side.LEFT -> Triple(r, c - 1, Side.RIGHT); Side.RIGHT -> Triple(r, c + 1, Side.LEFT)
    }

    /**
     * The line on cell ([r], [c])'s [side] (null: none): the cell's own borders; else, on a side it shares, its
     * neighbour's own; else the style's grid.
     */
    fun line(t: SlideTable, r: Int, c: Int, side: Side, cover: Array<IntArray>? = coverOf(t)): CellBorder? {
        ownerCell(t, r, c, cover)?.borders?.let { return it.side(side) }
        val (nr, nc, facing) = across(r, c, side)
        ownerCell(t, nr, nc, cover)?.borders?.let { return it.side(facing) }
        return styleLine(t)
    }

    /** All four of cell ([r], [c])'s lines as they draw. */
    fun sides(t: SlideTable, r: Int, c: Int, cover: Array<IntArray>? = coverOf(t)): CellBorders =
        CellBorders(line(t, r, c, Side.TOP, cover), line(t, r, c, Side.BOTTOM, cover), line(t, r, c, Side.LEFT, cover), line(t, r, c, Side.RIGHT, cover))

    /** [t]'s coverage when it has merged cells (see [SlideTable.coverage]), else null — every place its own cell. */
    fun coverOf(t: SlideTable): Array<IntArray>? = if (t.hasMerges) t.coverage() else null

    /** The place that shows ([r], [c]): a merged cell's own for the places it covers — its borders are the merged cell's. */
    fun ownerOf(t: SlideTable, r: Int, c: Int, cover: Array<IntArray>?): Pair<Int, Int> {
        val o = cover?.getOrNull(r)?.getOrNull(c) ?: return r to c
        return o / t.cols to o % t.cols
    }

    private fun ownerCell(t: SlideTable, r: Int, c: Int, cover: Array<IntArray>?): SlideBox? =
        ownerOf(t, r, c, cover).let { (or, oc) -> t.cells.getOrNull(or)?.getOrNull(oc) }

    /**
     * [line] draws as [t]'s style grid does — a solid line in its border colour, thin: LibreOffice rewrites the 0.5 pt
     * line as 0.26 pt in ODP and 1 pt in pptx.
     */
    fun likeStyle(t: SlideTable, line: CellBorder?): Boolean {
        val g = styleLine(t)
        return line != null && line.style == g.style && (line.color ?: 0xFF000000) == g.color && kotlin.math.abs(line.widthPt - g.widthPt) <= 0.55f
    }

    /**
     * After a reader has the table's style: each cell whose sides as read ([read]; a side the file leaves out comes as
     * the style's line) are not all the style's grid keeps them as its own borders.
     */
    fun adoptBorders(t: SlideTable, read: (Int, Int, Side) -> CellBorder?) {
        val grid = styleLine(t)
        for (r in 0 until t.rows) for (c in 0 until t.cols) {
            val got = Side.entries.map { read(r, c, it) }
            if (got.all { likeStyle(t, it) }) continue
            val s = got.map { if (likeStyle(t, it)) grid else it }
            t.cells[r][c].borders = CellBorders(s[0], s[1], s[2], s[3])
        }
    }

    /** What row [r]'s text falls back to where its runs say nothing: white bold in a header or total row, else null (dark). */
    fun textDefaults(t: SlideTable, r: Int): MasterText? = if (emphasized(t, r)) ON_HEADER else null
}
