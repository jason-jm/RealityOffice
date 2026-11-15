package com.therealityteam.aeropages.model

/**
 * How Excel's built-in table styles draw a table — TableStyleLight1…21, Medium1…28 and
 * Dark1…11 — cell by cell: the fill, whether the text is bold and in what colour, and the lines
 * on the cell's sides. Each row of Excel's gallery is a family (ruled, header-filled, gridded,
 * banded in tints, dark …); a style's place in its row picks the colour: black, then the six
 * Office accents. A cell's own formatting always wins over its table's look.
 */
object TableLooks {
    /** What a table style gives one cell. */
    class Look(
        val fill: Long? = null, val bold: Boolean = false, val color: Long? = null,
        val top: BorderLine? = null, val bottom: BorderLine? = null, val left: BorderLine? = null, val right: BorderLine? = null,
    )

    enum class Family(val prefix: String, val count: Int) { LIGHT("Light", 21), MEDIUM("Medium", 28), DARK("Dark", 11) }

    /** One of Excel's built-in styles: its family and number (Medium 2 is Excel's default). */
    class Style(val family: Family, val number: Int) {
        val name: String get() = "TableStyle" + family.prefix + number
        /** 0 = black, 1…6 = the Office accents. */
        val accent: Int get() = if (family == Family.DARK && number > 7) DARK_PAIRS[number - 8] else (number - 1) % 7
        /** Which row of its family's gallery it sits in. */
        val variant: Int get() = (number - 1) / 7
    }

    /** Every built-in style, as Excel's gallery lists them. */
    val ALL: List<Style> = Family.entries.flatMap { f -> (1..f.count).map { Style(f, it) } }

    private val NAME = Regex("^TableStyle(Light|Medium|Dark)(\\d{1,2})$", RegexOption.IGNORE_CASE)

    fun parse(name: String?): Style? {
        val m = NAME.matchEntire(name ?: return null) ?: return null
        val family = Family.entries.first { it.prefix.equals(m.groupValues[1], ignoreCase = true) }
        val n = m.groupValues[2].toInt()
        return if (n in 1..family.count) Style(family, n) else null
    }

    /** Dark 8…11 pair up the accents; their first colour leads. */
    private val DARK_PAIRS = listOf(0, 1, 3, 5)

    private const val WHITE = 0xFFFFFFFF
    private const val BLACK = 0xFF000000
    // Black, then the Office accents; and their tints (lighter 80%, 60%, 40%) and shade (darker 25%).
    private val BASE = listOf(BLACK) + OFFICE_ACCENTS
    private val L80 = listOf(0xFFD9D9D9) + OFFICE_TINTS_20
    private val L60 = listOf(0xFFBFBFBF, 0xFFB4C6E7, 0xFFF8CBAD, 0xFFDBDBDB, 0xFFFFE699, 0xFFBDD7EE, 0xFFC6E0B4)
    private val L40 = listOf(0xFF808080, 0xFF8EA9DB, 0xFFF4B084, 0xFFC9C9C9, 0xFFFFD966, 0xFF9BC2E6, 0xFFA9D08E)
    private val D25 = listOf(0xFF595959, 0xFF305496, 0xFFC65911, 0xFF7B7B7B, 0xFFBF8F00, 0xFF2F75B5, 0xFF548235)

    private fun thin(c: Long) = BorderLine(LineStyle.THIN, c)
    private fun double(c: Long) = BorderLine(LineStyle.DOUBLE, c)

    /** What [t]'s style gives the cell at ([row], [col]); null when it has no built-in style. */
    fun lookAt(t: SheetTable, row: Int, col: Int): Look? {
        val st = parse(t.style) ?: return null
        val a = st.accent
        val header = t.headerRow && row == t.r1
        val totals = t.totalsRow && row == t.r2
        val body = !header && !totals
        val bandRow = t.bandedRows && body && (row - t.dataR1) % 2 == 0
        val bandCol = t.bandedCols && body && (col - t.c1) % 2 == 0
        val edgeCol = body && ((t.firstColumn && col == t.c1) || (t.lastColumn && col == t.c2))
        val outerTop = row == t.r1
        val outerBottom = row == t.r2
        val outerLeft = col == t.c1
        val outerRight = col == t.c2

        var fill: Long? = null
        var bold = false
        var color: Long? = null
        var top: BorderLine? = null
        var bottom: BorderLine? = null
        var left: BorderLine? = null
        var right: BorderLine? = null
        fun box(line: BorderLine) { top = line; bottom = line; left = line; right = line }
        fun outline(line: BorderLine) {
            if (outerTop) top = line
            if (outerBottom) bottom = line
            if (outerLeft) left = line
            if (outerRight) right = line
        }

        when (st.family) {
            Family.LIGHT -> when (st.variant) {
                // Light 1–7: ruled above and below, the header ruled off, the text in the colour's shade.
                0 -> {
                    color = if (a == 0) null else D25[a]
                    if (outerTop) top = thin(BASE[a])
                    if (outerBottom) bottom = thin(BASE[a])
                    if (bandCol) fill = L80[a]
                    if (bandRow) fill = L80[a]
                    if (edgeCol) bold = true
                    if (totals) { bold = true; top = thin(BASE[a]) }
                    if (header) { bold = true; bottom = thin(BASE[a]) }
                }
                // Light 8–14: outlined, the header filled; stripes are ruled rather than filled.
                1 -> {
                    outline(thin(BASE[a]))
                    if (bandCol) { left = thin(BASE[a]); right = thin(BASE[a]) }
                    if (bandRow) { top = thin(BASE[a]); bottom = thin(BASE[a]) }
                    if (edgeCol) bold = true
                    if (totals) { bold = true; top = double(BASE[a]) }
                    if (header) { fill = BASE[a]; bold = true; color = WHITE }
                }
                // Light 15–21: every cell ruled.
                else -> {
                    box(thin(BASE[a]))
                    if (bandCol) fill = L80[a]
                    if (bandRow) fill = L80[a]
                    if (edgeCol) bold = true
                    if (totals) { bold = true; top = double(BASE[a]) }
                    if (header) bold = true
                }
            }
            Family.MEDIUM -> when (st.variant) {
                // Medium 1–7 (Medium 2 is Excel's default): a filled header, light rules between rows, tinted stripes.
                0 -> {
                    top = thin(L40[a]); bottom = thin(L40[a])
                    if (bandCol) fill = L80[a]
                    if (bandRow) fill = L80[a]
                    if (edgeCol) bold = true
                    if (totals) { bold = true; top = double(BASE[a]) }
                    if (header) { fill = BASE[a]; bold = true; color = WHITE; top = null; bottom = null }
                }
                // Medium 8–14: all tinted, cells parted by white lines, header and totals filled.
                1 -> {
                    fill = L80[a]
                    box(thin(WHITE))
                    if (bandCol) fill = L60[a]
                    if (bandRow) fill = L60[a]
                    if (edgeCol) { fill = BASE[a]; bold = true; color = WHITE }
                    if (totals) { fill = BASE[a]; bold = true; color = WHITE; top = double(WHITE) }
                    if (header) { fill = BASE[a]; bold = true; color = WHITE; bottom = BorderLine(LineStyle.MEDIUM, WHITE) }
                }
                // Medium 15–21: outlined and ruled in the colour, the header filled.
                2 -> {
                    outline(thin(BASE[a]))
                    if (!outerTop) top = thin(BASE[a])
                    if (!outerBottom) bottom = thin(BASE[a])
                    if (bandCol) fill = L80[a]
                    if (bandRow) fill = L80[a]
                    if (edgeCol) bold = true
                    if (totals) { bold = true; top = double(BASE[a]) }
                    if (header) { fill = BASE[a]; bold = true; color = WHITE }
                }
                // Medium 22–28: all tinted and gridded, deeper stripes, a bold header.
                else -> {
                    fill = L80[a]
                    box(thin(L40[a]))
                    if (bandCol) fill = L60[a]
                    if (bandRow) fill = L60[a]
                    if (edgeCol) bold = true
                    if (totals) { bold = true; top = double(BASE[a]) }
                    if (header) bold = true
                }
            }
            Family.DARK -> if (st.variant == 0) {
                // Dark 1–7: shaded in the colour with white text under a black header.
                fill = if (a == 0) 0xFF737373 else D25[a]
                color = WHITE
                if (bandCol) fill = if (a == 0) 0xFF595959 else BASE[a]
                if (bandRow) fill = if (a == 0) 0xFF595959 else BASE[a]
                if (edgeCol) { bold = true; fill = if (a == 0) 0xFF404040 else D25[a] }
                if (totals) { bold = true; fill = BLACK; top = double(WHITE) }
                if (header) { fill = BLACK; bold = true; bottom = BorderLine(LineStyle.MEDIUM, WHITE) }
            } else {
                // Dark 8–11: tinted in the first colour of the pair under a black header.
                fill = L80[a]
                if (bandCol) fill = L60[a]
                if (bandRow) fill = L60[a]
                if (edgeCol) bold = true
                if (totals) { bold = true; top = double(BLACK) }
                if (header) { fill = BLACK; bold = true; color = WHITE }
            }
        }
        return Look(fill, bold, color, top, bottom, left, right)
    }

    /**
     * [cell] as its table draws it: the style's fill, weight, colour and lines wherever the cell
     * has none of its own; [cell] itself outside a table or without a built-in style. The result is
     * a copy — for drawing and for writing formats out, never to be stored.
     */
    fun styled(sheet: Sheet, row: Int, col: Int, cell: Cell?): Cell? {
        if (sheet.tables.isEmpty()) return cell
        val t = sheet.tableAt(row, col) ?: return cell
        val look = lookAt(t, row, col) ?: return cell
        return applied(cell, look)
    }

    /** [cell] (or a blank one) with [look] under its own formatting. */
    fun applied(cell: Cell?, look: Look): Cell {
        val out = cell?.copyOf() ?: Cell()
        val f = out.format
        if (f.highlight == null) f.highlight = look.fill
        if (look.bold) f.bold = true
        if (f.color == null) f.color = look.color
        if (out.borderTop == null) out.borderTop = look.top
        if (out.borderBottom == null) out.borderBottom = look.bottom
        if (out.borderLeft == null) out.borderLeft = look.left
        if (out.borderRight == null) out.borderRight = look.right
        return out
    }
}
