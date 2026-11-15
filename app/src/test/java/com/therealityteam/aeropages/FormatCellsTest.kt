package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormatChoices
import com.therealityteam.aeropages.editor.FormatChoices.Category
import com.therealityteam.aeropages.editor.FormatChoices.Choice
import com.therealityteam.aeropages.editor.NumberFormatting
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Format Cells: Excel's number choices as codes and back, and the whole dialog as one undo step. */
class FormatCellsTest {

    private fun shown(v: String, code: String?) =
        NumberFormatting.shown(v, Cell(text = v, numberFormat = code?.let { FormatCode.category(it) } ?: NumberFormat.GENERAL, formatCode = code)).text

    @Test
    fun choicesAreExcelCodes() {
        assertEquals("#,##0.00", FormatChoices.code(Choice(Category.NUMBER, 2, thousands = true)))
        assertEquals("0.0_);[Red](0.0)", FormatChoices.code(Choice(Category.NUMBER, 1, negative = 3)))
        assertEquals("\"$\"#,##0.00_);(\"$\"#,##0.00)", FormatChoices.code(Choice(Category.CURRENCY, 2, negative = 2, symbol = 1)))
        assertEquals("_(\"¥\"* #,##0.00_);_(\"¥\"* \\(#,##0.00\\);_(\"¥\"* \"-\"??_);_(@_)", FormatChoices.code(Choice(Category.ACCOUNTING, 2, symbol = 0)))
        assertEquals("0.000%", FormatChoices.code(Choice(Category.PERCENT, 3)))
        assertEquals("0.00E+00", FormatChoices.code(Choice(Category.SCIENTIFIC, 2)))
        assertNull(FormatChoices.code(Choice(Category.GENERAL)))
        // Every code the dialog can make is one the formatter reads, and comes back as the same choice.
        val all = buildList {
            for (d in 0..4) for (t in listOf(false, true)) for (n in 0..3) add(Choice(Category.NUMBER, d, t, n))
            for (s in FormatChoices.SYMBOLS.indices) { add(Choice(Category.CURRENCY, 2, symbol = s, negative = 1)); add(Choice(Category.ACCOUNTING, 0, symbol = s)) }
            FormatChoices.DATE_CODES.indices.forEach { add(Choice(Category.DATE, type = it)) }
            FormatChoices.TIME_CODES.indices.forEach { add(Choice(Category.TIME, type = it)) }
            FormatChoices.FRACTION_CODES.indices.forEach { add(Choice(Category.FRACTION, type = it)) }
        }
        for (c in all) {
            val code = FormatChoices.code(c)!!
            assertTrue(code, FormatCode.isValid(code))
            assertEquals(code, FormatChoices.code(FormatChoices.choiceOf(code, NumberFormat.GENERAL, -1)))
        }
        assertEquals(Category.CUSTOM, FormatChoices.choiceOf("0.0,,\"M\"", NumberFormat.NUMBER2, -1).category)
        assertEquals(Category.TEXT, FormatChoices.choiceOf("@", NumberFormat.GENERAL, -1).category)
        // How they show.
        assertEquals("(1,234.10)", shown("-1234.1", FormatChoices.code(Choice(Category.NUMBER, 2, true, 2))))
        assertEquals("1 1/4", shown("1.25", FormatChoices.code(Choice(Category.FRACTION, type = 0))))
    }

    @Test
    fun theDialogIsOneUndoStep() {
        val s = Sheet()
        for (r in 0..1) for (c in 0..1) s.ensure(r, c).text = "${r * 2 + c + 1}"
        val e = SheetEditor().apply { load(Workbook(mutableListOf(s))) }
        e.select(0, 0); e.extendSelection(1, 1)
        val pen = BorderLine(color = 0xFFCC0000)
        e.applyCellFormat(merge = null) { c, top, bottom, left, right ->
            c.formatCode = "0.00"; c.numberFormat = FormatCode.category("0.00")
            c.align = TextAlign.CENTER; c.format.bold = true; c.format.highlight = 0xFFFFF2CC
            if (top) c.borderTop = pen; if (bottom) c.borderBottom = pen
        }
        assertEquals("1.00", e.display(0, 0))
        assertTrue(s.cell(1, 1)!!.format.bold)
        assertEquals(pen, s.cell(0, 1)!!.borderTop); assertNull(s.cell(1, 1)!!.borderTop); assertEquals(pen, s.cell(1, 0)!!.borderBottom)
        e.undo()
        val back = e.sheet
        assertEquals("1", back.cell(0, 0)!!.text); assertNull(back.cell(0, 0)!!.formatCode)
        assertTrue(listOf(0, 1).all { r -> listOf(0, 1).all { c -> !back.cell(r, c)!!.format.bold && back.cell(r, c)!!.borderTop == null } })
        // Merging rides on the same step.
        e.applyCellFormat(merge = true) { _, _, _, _, _ -> }
        assertEquals(1, e.sheet.merges.size)
        e.undo()
        assertTrue(e.sheet.merges.isEmpty())
    }
}
