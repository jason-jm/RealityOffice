package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.CellBorder

/**
 * A look from the Table tab's style gallery (Word's Table Styles): the header row's fill and text, a
 * fill on every other row, and which rules are drawn in what line. Applied as the cells' own shading
 * and borders, so docx, ODT and .doc readers alike show it.
 */
data class TableLook(
    val header: Long?, val headerText: Long?, val boldHeader: Boolean, val band: Long?,
    val rules: Rules, val line: CellBorder,
) {
    /** Grid: every side; NONE: none; HORIZONTAL: a rule under each row; THREE_LINE: the academic table's three. */
    enum class Rules { GRID, NONE, HORIZONTAL, THREE_LINE }

    companion object {
        private fun line(color: Long?, width: Float = 0.5f) = CellBorder(widthPt = width, color = color)

        /** The gallery, in its order: plain looks first, then coloured ones with a header and bands. */
        val GALLERY = listOf(
            TableLook(null, null, true, null, Rules.GRID, line(null)),
            TableLook(null, null, true, null, Rules.NONE, line(null)),
            TableLook(null, null, true, null, Rules.THREE_LINE, line(null, 0.75f)),
            TableLook(0xFFF2F2F2, null, true, null, Rules.HORIZONTAL, line(0xFFBFBFBF)),
            TableLook(0xFF4472C4, 0xFFFFFFFF, true, 0xFFD9E2F3, Rules.GRID, line(0xFF8EAADB)),
            TableLook(0xFF70AD47, 0xFFFFFFFF, true, 0xFFE2EFDA, Rules.GRID, line(0xFFA9D08E)),
            TableLook(0xFFED7D31, 0xFFFFFFFF, true, 0xFFFBE4D5, Rules.GRID, line(0xFFF4B183)),
            TableLook(0xFF1F3864, 0xFFFFFFFF, true, 0xFFDEEAF6, Rules.HORIZONTAL, line(0xFF9DC3E6)),
        )
    }
}
