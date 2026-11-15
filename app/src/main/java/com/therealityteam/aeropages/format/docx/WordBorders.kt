package com.therealityteam.aeropages.format.docx

import com.therealityteam.aeropages.model.CellBorder
import com.therealityteam.aeropages.model.CellBorders
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.Table
import org.xmlpull.v1.XmlPullParser

/**
 * Word's table borders (ECMA-376 17.4.38 `w:tblBorders`, 17.4.66 `w:tcBorders`): each side's line —
 * `w:val` its kind (single, double, dotted, dashed, dot-dash …; nil / none: no line), `w:sz` its width
 * in eighths of a point, `w:color` its colour — and how a table's cells come by theirs: a cell's own
 * border, else the table's (its outer edges, or the inside ones between cells), else its style's.
 */
internal object WordBorders {

    /** A border element's line; null for none (or nil). */
    fun lineOf(v: String?, sz: String?, color: String?): CellBorder? {
        val style = when (v ?: return null) {
            "nil", "none" -> return null
            "double", "triple", "doubleWave", "thinThickSmallGap", "thickThinSmallGap", "thinThickThinSmallGap",
            "thinThickMediumGap", "thickThinMediumGap", "thinThickThinMediumGap", "thinThickLargeGap",
            "thickThinLargeGap", "thinThickThinLargeGap" -> LineStyle.DOUBLE
            "dotted" -> LineStyle.DOTTED
            "dashed", "dashSmallGap" -> LineStyle.DASHED
            "dotDash", "dashDotStroked" -> LineStyle.DASH_DOT
            "dotDotDash" -> LineStyle.DASH_DOT_DOT
            else -> LineStyle.THIN
        }
        val width = (sz?.toFloatOrNull() ?: 4f).coerceIn(2f, 96f) / 8f
        // Black is the automatic colour (LibreOffice writes it out as 000000).
        return CellBorder(style, width, color?.takeIf { it != "auto" }?.let { Ooxml.parseColor(it) }?.takeIf { it != 0xFF000000L })
    }

    /**
     * The borders element [tag] the parser is on, consumed: each side it names — `start` / `end` as `left` /
     * `right` — its line (null: none). A side it does not name is not in the map.
     */
    fun readSet(p: XmlPullParser, tag: String): Map<String, CellBorder?> {
        val out = HashMap<String, CellBorder?>()
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.name == tag) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.namespace == Ooxml.W) {
                val side = when (p.name) { "start" -> "left"; "end" -> "right"; else -> p.name }
                if (side in SIDES) out[side] = lineOf(p.getAttributeValue(Ooxml.W, "val"), p.getAttributeValue(Ooxml.W, "sz"), p.getAttributeValue(Ooxml.W, "color"))
            }
            event = p.next()
        }
        return out
    }

    private val SIDES = setOf("top", "left", "bottom", "right", "insideH", "insideV")

    /** One side as Word writes it ([line] null: none). */
    fun xml(side: String, line: CellBorder?): String {
        if (line == null) return """<w:$side w:val="nil"/>"""
        val v = when (line.style) {
            LineStyle.DOUBLE -> "double"
            LineStyle.DOTTED -> "dotted"
            LineStyle.DASHED, LineStyle.MEDIUM_DASHED -> "dashed"
            LineStyle.DASH_DOT, LineStyle.MEDIUM_DASH_DOT, LineStyle.SLANT_DASH_DOT -> "dotDash"
            LineStyle.DASH_DOT_DOT, LineStyle.MEDIUM_DASH_DOT_DOT -> "dotDotDash"
            else -> "single"
        }
        val sz = Math.round(line.widthPt * 8).coerceIn(2, 96)
        return """<w:$side w:val="$v" w:sz="$sz" w:space="0" w:color="${line.color?.let { Ooxml.colorToHex(it) } ?: "auto"}"/>"""
    }

    /**
     * Page borders as `w:pgBorders` (CT_PageBorders: top, left, bottom, right): each side's line with its space (whole
     * points, at most 31, from the page's edge or the text) and the shadow on every side, as Word writes its Shadow box.
     */
    fun pageXml(b: com.therealityteam.aeropages.model.PageBorders): String = buildString {
        val display = when (b.pages) {
            com.therealityteam.aeropages.model.PageBorderPages.FIRST -> """ w:display="firstPage""""
            com.therealityteam.aeropages.model.PageBorderPages.NOT_FIRST -> """ w:display="notFirstPage""""
            else -> ""
        }
        append("""<w:pgBorders w:offsetFrom="${if (b.fromText) "text" else "page"}"$display>""")
        listOf("top" to (b.top to b.spaceTop), "left" to (b.left to b.spaceLeft), "bottom" to (b.bottom to b.spaceBottom), "right" to (b.right to b.spaceRight))
            .forEach { (side, ls) ->
                val (line, space) = ls
                line ?: return@forEach
                val one = xml(side, line).replace("""w:space="0"""", """w:space="${Math.round(space).coerceIn(0, 31)}"""")
                append(if (b.shadow) one.replace("/>", """ w:shadow="1"/>""") else one)
            }
        append("</w:pgBorders>")
    }

    /**
     * A `w:pBdr` the parser is on, consumed: each side's line and its space (points; Word writes none for 0), the
     * between line too (the bar line is not kept). Every side nil gives borders that are [com.therealityteam.aeropages.model.ParagraphBorders.none]
     * — a paragraph turning its style's off.
     */
    fun readParagraph(p: XmlPullParser): com.therealityteam.aeropages.model.ParagraphBorders {
        val lines = HashMap<String, CellBorder?>()
        val spaces = HashMap<String, Float>()
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.name == "pBdr") && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.namespace == Ooxml.W) {
                val side = when (p.name) { "start" -> "left"; "end" -> "right"; else -> p.name }
                if (side in PARAGRAPH_SIDES) {
                    lines[side] = lineOf(p.getAttributeValue(Ooxml.W, "val"), p.getAttributeValue(Ooxml.W, "sz"), p.getAttributeValue(Ooxml.W, "color"))
                    p.getAttributeValue(Ooxml.W, "space")?.toFloatOrNull()?.let { spaces[side] = it.coerceIn(0f, 31f) }
                }
            }
            event = p.next()
        }
        return com.therealityteam.aeropages.model.ParagraphBorders(
            top = lines["top"], bottom = lines["bottom"], left = lines["left"], right = lines["right"], between = lines["between"],
            // A side with a line and no space: 0 (the schema's); a side without a line keeps Word's distance for when one is set.
            spaceTop = if (lines["top"] != null) spaces["top"] ?: 0f else 1f, spaceBottom = if (lines["bottom"] != null) spaces["bottom"] ?: 0f else 1f,
            spaceLeft = if (lines["left"] != null) spaces["left"] ?: 0f else 4f, spaceRight = if (lines["right"] != null) spaces["right"] ?: 0f else 4f,
        )
    }

    private val PARAGRAPH_SIDES = setOf("top", "left", "bottom", "right", "between")

    /**
     * Paragraph borders as `w:pBdr` (CT_PBdr: top, left, bottom, right, between), each side with its space in whole
     * points (at most 31); null (or none): every side nil — a paragraph turning its style's off.
     */
    fun paragraphXml(b: com.therealityteam.aeropages.model.ParagraphBorders?): String = buildString {
        append("<w:pBdr>")
        listOf("top" to (b?.top to (b?.spaceTop ?: 0f)), "left" to (b?.left to (b?.spaceLeft ?: 0f)), "bottom" to (b?.bottom to (b?.spaceBottom ?: 0f)),
            "right" to (b?.right to (b?.spaceRight ?: 0f)), "between" to (b?.between to (b?.spaceTop ?: 0f)))
            .forEach { (side, ls) ->
                val (line, space) = ls
                append(xml(side, line).replace("""w:space="0"""", """w:space="${Math.round(space).coerceIn(0, 31)}""""))
            }
        append("</w:pBdr>")
    }

    /** A `w:shd`'s colour: its fill, a solid pattern's colour; null for none (auto, nil). */
    fun shadingOf(p: XmlPullParser): Long? {
        val v = p.getAttributeValue(Ooxml.W, "val")
        if (v == "nil") return null
        val hex = if (v == "solid") p.getAttributeValue(Ooxml.W, "color") else p.getAttributeValue(Ooxml.W, "fill")
        return hex?.takeIf { it != "auto" }?.let { Ooxml.parseColor(it) }
    }

    /** Paragraph shading as `w:shd` (a clear pattern over the fill); null: none (auto) — a paragraph turning its style's off. */
    fun shadingXml(fill: Long?): String =
        """<w:shd w:val="clear" w:color="auto" w:fill="${fill?.let { Ooxml.colorToHex(it) } ?: "auto"}"/>"""

    /** A side worked out for a cell: its line, and whether the cell itself set it (`w:tcBorders`) rather than the table. */
    private class Side(var line: CellBorder?, val own: Boolean)

    /**
     * Each cell of [table] given its borders: its own sides ([cells], by row and cell; a side named
     * there wins, nil included), else the table's [sides] — the outer ones on the table's edges, the
     * inside ones between cells. Where one of two cells sharing an edge set it itself and the other did
     * not, its line is the edge's for both (Word puts a cell's border before the table's).
     */
    fun resolve(table: Table, sides: Map<String, CellBorder?>, cells: (Int, Int) -> Map<String, CellBorder?>?) {
        val rows = table.rows.size
        val cols = table.rows.maxOfOrNull { r -> r.cells.sumOf { it.gridSpan.coerceAtLeast(1) } } ?: 0
        val got = table.rows.mapIndexed { ri, row ->
            var g = 0
            row.cells.mapIndexed { ci, cell ->
                val span = cell.gridSpan.coerceAtLeast(1)
                val first = g; g += span
                val own = cells(ri, ci) ?: emptyMap()
                val lastRow = ri + cell.rowSpan.coerceAtLeast(1) - 1 >= rows - 1
                fun side(name: String, edge: Boolean, inside: String) =
                    if (name in own) Side(own[name], true) else Side(sides[if (edge) name else inside], false)
                arrayOf(side("top", ri == 0, "insideH"), side("bottom", lastRow, "insideH"),
                    side("left", first == 0, "insideV"), side("right", first + span >= cols, "insideV"))
            }
        }
        // Edges two cells share: one a cell set itself rules both.
        fun settle(a: Side, b: Side) {
            if (a.own && !b.own) b.line = a.line else if (b.own && !a.own) a.line = b.line
        }
        got.forEachIndexed { ri, row ->
            for (ci in 0 until row.size - 1) settle(row[ci][3], row[ci + 1][2])
            row.forEachIndexed { ci, s ->
                val below = ri + table.rows[ri].cells[ci].rowSpan.coerceAtLeast(1)
                table.cellAtGrid(below, table.gridColOf(ri, ci))?.let { k -> got.getOrNull(below)?.getOrNull(k)?.let { settle(s[1], it[0]) } }
            }
        }
        table.adoptBorders { ri, ci -> got[ri][ci].let { CellBorders(it[0].line, it[1].line, it[2].line, it[3].line) } }
    }

    /**
     * [table]'s borders as Word writes them: a grid of one line as its `w:tblBorders` (every side, inside
     * ones too), none as nil everywhere; cells with their own as each cell's `w:tcBorders` (the table's nil).
     */
    fun tableXml(table: Table): String = buildString {
        append("<w:tblBorders>")
        val line = table.gridLine.takeIf { table.borders }
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { append(xml(it, line)) }
        append("</w:tblBorders>")
    }

    /** A cell's own borders as `w:tcBorders` (CT_TcBorders: top, left, bottom, right); "" for one following the table. */
    fun cellXml(b: CellBorders?): String =
        if (b == null) "" else "<w:tcBorders>" + xml("top", b.top) + xml("left", b.left) + xml("bottom", b.bottom) + xml("right", b.right) + "</w:tcBorders>"
}
