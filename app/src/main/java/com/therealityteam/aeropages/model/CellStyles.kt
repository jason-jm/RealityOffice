package com.therealityteam.aeropages.model

/**
 * Excel's built-in cell styles (Home → Cell Styles), each the formats it sets and nothing else —
 * as Excel's styles include only some aspects: Good sets the font and fill, a heading its font and
 * bottom border, Comma only the number format; Normal clears them all.
 */
enum class CellStyle(val group: Group, internal val spec: Spec) {
    NORMAL(Group.GOOD_BAD, Spec(font = Font(), fill = Fill(null), borders = NO_BORDERS, number = NumberFormat.GENERAL to -1)),
    GOOD(Group.GOOD_BAD, Spec(font = Font(color = 0xFF006100), fill = Fill(0xFFC6EFCE))),
    BAD(Group.GOOD_BAD, Spec(font = Font(color = 0xFF9C0006), fill = Fill(0xFFFFC7CE))),
    NEUTRAL(Group.GOOD_BAD, Spec(font = Font(color = 0xFF9C5700), fill = Fill(0xFFFFEB9C))),

    CALCULATION(Group.DATA, Spec(font = Font(bold = true, color = 0xFFFA7D00), fill = Fill(0xFFF2F2F2), borders = all(BorderLine(LineStyle.THIN, 0xFF7F7F7F)))),
    CHECK_CELL(Group.DATA, Spec(font = Font(bold = true, color = 0xFFFFFFFF), fill = Fill(0xFFA5A5A5), borders = all(BorderLine(LineStyle.DOUBLE, 0xFF3F3F3F)))),
    EXPLANATORY(Group.DATA, Spec(font = Font(italic = true, color = 0xFF7F7F7F))),
    INPUT(Group.DATA, Spec(font = Font(color = 0xFF3F3F76), fill = Fill(0xFFFFCC99), borders = all(BorderLine(LineStyle.THIN, 0xFF7F7F7F)))),
    LINKED_CELL(Group.DATA, Spec(font = Font(color = 0xFFFA7D00), borders = listOf(null, BorderLine(LineStyle.DOUBLE, 0xFFFF8001), null, null))),
    NOTE(Group.DATA, Spec(fill = Fill(0xFFFFFFCC), borders = all(BorderLine(LineStyle.THIN, 0xFFB2B2B2)))),
    OUTPUT(Group.DATA, Spec(font = Font(bold = true, color = 0xFF3F3F3F), fill = Fill(0xFFF2F2F2), borders = all(BorderLine(LineStyle.THIN, 0xFF3F3F3F)))),
    WARNING(Group.DATA, Spec(font = Font(color = 0xFFFF0000))),

    HEADING_1(Group.TITLES, Spec(font = Font(bold = true, color = TITLE_INK, sizePt = 15f), borders = listOf(null, BorderLine(LineStyle.THICK, 0xFF4472C4), null, null))),
    HEADING_2(Group.TITLES, Spec(font = Font(bold = true, color = TITLE_INK, sizePt = 13f), borders = listOf(null, BorderLine(LineStyle.THICK, 0xFFA2B8E1), null, null))),
    HEADING_3(Group.TITLES, Spec(font = Font(bold = true, color = TITLE_INK, sizePt = 11f), borders = listOf(null, BorderLine(LineStyle.MEDIUM, 0xFF8EA9DB), null, null))),
    HEADING_4(Group.TITLES, Spec(font = Font(bold = true, color = TITLE_INK, sizePt = 11f))),
    TITLE(Group.TITLES, Spec(font = Font(color = TITLE_INK, sizePt = 18f))),
    TOTAL(Group.TITLES, Spec(font = Font(bold = true), borders = listOf(BorderLine(LineStyle.THIN, 0xFF4472C4), BorderLine(LineStyle.DOUBLE, 0xFF4472C4), null, null))),

    ACCENT_1(Group.THEMED, accent(0)), ACCENT_2(Group.THEMED, accent(1)), ACCENT_3(Group.THEMED, accent(2)),
    ACCENT_4(Group.THEMED, accent(3)), ACCENT_5(Group.THEMED, accent(4)), ACCENT_6(Group.THEMED, accent(5)),
    ACCENT_1_20(Group.THEMED, tint(0)), ACCENT_2_20(Group.THEMED, tint(1)), ACCENT_3_20(Group.THEMED, tint(2)),
    ACCENT_4_20(Group.THEMED, tint(3)), ACCENT_5_20(Group.THEMED, tint(4)), ACCENT_6_20(Group.THEMED, tint(5)),

    COMMA(Group.NUMBER, Spec(number = NumberFormat.THOUSANDS to 2)),
    COMMA_0(Group.NUMBER, Spec(number = NumberFormat.THOUSANDS to -1)),
    CURRENCY(Group.NUMBER, Spec(number = NumberFormat.CURRENCY_CNY to 2)),
    CURRENCY_0(Group.NUMBER, Spec(number = NumberFormat.CURRENCY_CNY to 0)),
    PERCENT(Group.NUMBER, Spec(number = NumberFormat.PERCENT to 0));

    /** Excel's sections of the Cell Styles gallery. */
    enum class Group { GOOD_BAD, DATA, TITLES, THEMED, NUMBER }

    /** For the Themed styles: which accent (0–5), else -1. */
    val accentIndex: Int get() = when {
        name.startsWith("ACCENT_") -> name.removePrefix("ACCENT_").substringBefore('_').toInt() - 1
        else -> -1
    }

    /** The font a style sets: weight, slant, colour (null = automatic) and, when given, its size. */
    internal data class Font(val bold: Boolean = false, val italic: Boolean = false, val color: Long? = null, val sizePt: Float? = null)
    /** The fill a style sets (null colour = none). */
    internal data class Fill(val color: Long?)
    /** What a style sets; a null aspect is left as the cell has it. Borders are top, bottom, left, right. */
    internal class Spec(
        val font: Font? = null, val fill: Fill? = null, val borders: List<BorderLine?>? = null,
        val number: Pair<NumberFormat, Int>? = null,
    )

    /** The style on [cell]: each aspect it includes replaces the cell's own. */
    fun applyTo(cell: Cell) {
        spec.font?.let { f ->
            cell.format.bold = f.bold; cell.format.italic = f.italic; cell.format.color = f.color
            if (this == NORMAL) { cell.format.underline = false; cell.format.strikethrough = false; cell.format.fontSizePt = null; cell.format.fontFamily = null }
            f.sizePt?.let { cell.format.fontSizePt = it }
            cell.runs = null
        }
        spec.fill?.let { cell.format.highlight = it.color }
        spec.borders?.let { b -> cell.borderTop = b[0]; cell.borderBottom = b[1]; cell.borderLeft = b[2]; cell.borderRight = b[3] }
        spec.number?.let { (fmt, d) -> cell.numberFormat = fmt; cell.decimals = d; cell.formatCode = null }
        if (this == NORMAL) { cell.align = null; cell.vAlign = VAlign.MIDDLE; cell.wrap = false; cell.rotation = 0; cell.stacked = false; cell.indent = 0; cell.spread = null; cell.shrink = false }
    }

}

/** The Office theme's accents: blue, orange, grey, gold, light blue, green (top level: the enum's entries read them while it is built). */
val OFFICE_ACCENTS: List<Long> = listOf(0xFF4472C4, 0xFFED7D31, 0xFFA5A5A5, 0xFFFFC000, 0xFF5B9BD5, 0xFF70AD47)
/** Their 20% tints (the light bands of tables and the 20% cell styles). */
val OFFICE_TINTS_20: List<Long> = listOf(0xFFD9E1F2, 0xFFFCE4D6, 0xFFEDEDED, 0xFFFFF2CC, 0xFFDDEBF7, 0xFFE2EFDA)

private const val TITLE_INK = 0xFF44546A
private val NO_BORDERS: List<BorderLine?> = listOf(null, null, null, null)
private fun all(line: BorderLine) = listOf(line, line, line, line)
private fun accent(i: Int) = CellStyle.Spec(font = CellStyle.Font(color = 0xFFFFFFFF), fill = CellStyle.Fill(OFFICE_ACCENTS[i]))
private fun tint(i: Int) = CellStyle.Spec(fill = CellStyle.Fill(OFFICE_TINTS_20[i]))

/**
 * A table look from Excel's Format as Table gallery: one of the six accents, light (a bold header
 * ruled in the accent, the table closed by accent lines) or medium (a bold white header on the
 * accent); both band the data rows with the accent's 20% tint.
 */
data class TableStyle(val accent: Int, val medium: Boolean) {
    val color: Long get() = OFFICE_ACCENTS[accent]
    val band: Long get() = OFFICE_TINTS_20[accent]
    /** Excel's name for the look: Light 2–7 and Medium 2–7 are these in the six accents. */
    val xlsxName: String get() = "TableStyle" + (if (medium) "Medium" else "Light") + (accent + 2)

    companion object {
        val ALL: List<TableStyle> = listOf(false, true).flatMap { medium -> (0..5).map { TableStyle(it, medium) } }
    }
}
