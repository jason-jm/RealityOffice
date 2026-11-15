package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.NumberFormatting
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Excel's General alignment (numbers right, text left, TRUE / FALSE and errors centred), alignment
 * and character formats applied to the whole selection with Excel's toggles, and files keeping
 * General apart from a set left alignment.
 */
class CellAlignmentTest {

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }

    @Test
    fun generalAlignmentFollowsTheValue() {
        assertEquals(TextAlign.RIGHT, NumberFormatting.generalAlign("123", Cell("123")))
        assertEquals(TextAlign.RIGHT, NumberFormatting.generalAlign("-4.5", Cell("-4.5")))
        assertEquals(TextAlign.LEFT, NumberFormatting.generalAlign("abc", Cell("abc")))
        assertEquals(TextAlign.CENTER, NumberFormatting.generalAlign("TRUE", Cell("TRUE")))
        assertEquals(TextAlign.CENTER, NumberFormatting.generalAlign("#DIV/0!", Cell("=1/0")))
        assertEquals("text typed like an error stays text", TextAlign.LEFT, NumberFormatting.generalAlign("#tag", Cell("#tag")))
        assertEquals("a Text cell holds text", TextAlign.LEFT, NumberFormatting.generalAlign("00123", Cell("00123", formatCode = "@")))

        val e = editor()
        e.setCell(0, 0, "46294")
        e.select(0, 0)
        e.setFormatCode("yyyy\"年\"m\"月\"d\"日\"")
        e.setCell(1, 0, "=1/0")
        e.setCell(2, 0, "Total")
        e.setCell(3, 0, "=A1*2")
        assertEquals("a date is a number", TextAlign.RIGHT, e.displayAlign(0, 0))
        assertEquals(TextAlign.CENTER, e.displayAlign(1, 0))
        assertEquals(TextAlign.LEFT, e.displayAlign(2, 0))
        assertEquals("a formula's number", TextAlign.RIGHT, e.displayAlign(3, 0))
        e.setCell(3, 0, "=A3&\"!\"")
        assertEquals("…and its text", TextAlign.LEFT, e.displayAlign(3, 0))
    }

    @Test
    fun onlyLeftAlignedTextRunsOn() {
        val e = editor()
        e.setCell(0, 0, "A long title that runs on")
        e.setCell(1, 0, "12345678901234567890")
        e.setCell(2, 0, "=1/0")
        e.setCell(3, 0, "centred"); e.select(3, 0); e.setAlign(TextAlign.CENTER)
        e.setCell(4, 0, "wrapped"); e.select(4, 0); e.toggleWrap()
        e.setCell(5, 0, "merged"); e.select(5, 0); e.extendSelection(0, 1); e.mergeCells(center = false)
        e.setCell(6, 0, "left by hand"); e.select(6, 0); e.setAlign(TextAlign.LEFT)
        assertTrue(e.overflows(0, 0))
        assertFalse("numbers never run on", e.overflows(1, 0))
        assertFalse("nor errors", e.overflows(2, 0))
        assertFalse(e.overflows(3, 0))
        assertFalse(e.overflows(4, 0))
        assertFalse(e.overflows(5, 0))
        assertTrue(e.overflows(6, 0))
        assertFalse("empty", e.overflows(7, 0))
    }

    @Test
    fun numbersThatDoNotFitNeverShowCutOff() {
        assertEquals("1234.57", NumberFormatting.fitGeneral(1234.56789) { it.length <= 7 })
        assertEquals("1.23457E+23", NumberFormatting.fitGeneral(1.2345678901234568E23) { it.length <= 11 })
        assertEquals("1E+08", NumberFormatting.fitGeneral(123456789.0) { it.length <= 6 })
        assertNull(NumberFormatting.fitGeneral(123456789.0) { it.length <= 2 })
        val e = editor().also { it.dates = null }
        e.setCell(0, 0, "123456789012345678901234")
        e.setCell(1, 0, "46294"); e.select(1, 0); e.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.DATE)
        e.setCell(2, 0, "A long label that does not fit")
        e.setCell(3, 0, "12.5")
        assertEquals("a General number goes to E notation", "1.23457E+23", e.displayFitted(0, 0, 88f))
        assertEquals("2026-09-29", e.displayFitted(1, 0, 88f))
        assertEquals("a formatted number shows ####", "#####", e.displayFitted(1, 0, 44f))
        assertEquals("text is left to run on or be cut", "A long label that does not fit", e.displayFitted(2, 0, 44f))
        assertEquals("12.5", e.displayFitted(3, 0, 88f))
    }

    @Test
    fun alignmentCoversTheSelectionAndTogglesBackToGeneral() {
        val e = editor()
        e.setCell(0, 0, "1"); e.setCell(1, 1, "x")
        e.select(0, 0); e.extendSelection(1, 1)
        e.setAlign(TextAlign.CENTER)
        for (r in 0..1) for (c in 0..1) assertEquals(TextAlign.CENTER, e.sheet.cell(r, c)?.align)
        assertEquals(TextAlign.CENTER, e.displayAlign(0, 0))
        assertEquals(TextAlign.CENTER, e.activeAlign)
        e.setAlign(TextAlign.CENTER)
        for (r in 0..1) for (c in 0..1) assertNull("pressed again: back to General", e.sheet.cell(r, c)?.align)
        assertEquals(TextAlign.RIGHT, e.displayAlign(0, 0))
        e.setAlign(TextAlign.LEFT)
        assertEquals("a set left alignment holds for numbers", TextAlign.LEFT, e.displayAlign(0, 0))
        e.undo()
        assertNull(e.sheet.cell(0, 0)?.align)
        e.undo()
        assertEquals(TextAlign.CENTER, e.sheet.cell(1, 1)?.align)
    }

    @Test
    fun characterFormatsCoverTheSelectionWithExcelsToggle() {
        val e = editor()
        e.setCell(0, 0, "a"); e.setCell(0, 1, "b"); e.setCell(0, 2, "c")
        e.select(0, 1); e.toggleBold()
        // Active cell A1 is not bold: Bold makes the whole block bold, B1 included.
        e.select(0, 0); e.extendSelection(0, 2)
        e.toggleBold()
        for (c in 0..2) assertTrue(e.sheet.cell(0, c)!!.format.bold)
        e.toggleBold()
        for (c in 0..2) assertFalse(e.sheet.cell(0, c)!!.format.bold)
        e.setColor(0xFFFF0000)
        for (c in 0..2) assertEquals(0xFFFF0000, e.sheet.cell(0, c)!!.format.color)
        e.undo()
        for (c in 0..2) assertNull(e.sheet.cell(0, c)!!.format.color)
        e.undo()
        for (c in 0..2) assertTrue("one undo step per command", e.sheet.cell(0, c)!!.format.bold)
    }

    @Test
    fun partOfACellTogglesOnItsOwnCharacters() {
        val e = editor()
        e.setCell(0, 0, "Hello")
        e.select(0, 0)
        e.setTextSelection(0, 2)
        e.toggleBold()
        val runs = e.sheet.cell(0, 0)!!.richRuns()!!
        assertEquals("He", runs[0].text); assertTrue(runs[0].format.bold); assertFalse(runs[1].format.bold)
        e.toggleBold()
        assertNull("bold again: the characters were all bold, so it comes off", e.sheet.cell(0, 0)!!.richRuns())
        assertFalse(e.sheet.cell(0, 0)!!.format.bold)
    }

    @Test
    fun aProtectedSheetRefusesFormatting() {
        val e = editor()
        e.setCell(0, 0, "1")
        e.select(0, 0)
        e.toggleProtection()
        val notices = e.protectionNotice
        e.setAlign(TextAlign.RIGHT)
        e.toggleBold()
        assertNull(e.sheet.cell(0, 0)!!.align)
        assertFalse(e.sheet.cell(0, 0)!!.format.bold)
        assertEquals(notices + 2, e.protectionNotice)
    }

    private fun alignedBook(): Workbook {
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        s.ensure(0, 0).text = "1"                                            // General
        s.ensure(0, 1).apply { text = "2"; align = TextAlign.LEFT }
        s.ensure(0, 2).apply { text = "3"; align = TextAlign.CENTER }
        s.ensure(0, 3).apply { text = "x"; align = TextAlign.RIGHT }
        return wb
    }

    private fun assertAligned(s: com.therealityteam.aeropages.model.Sheet) {
        assertNull(s.cell(0, 0)!!.align)
        assertEquals(TextAlign.LEFT, s.cell(0, 1)!!.align)
        assertEquals(TextAlign.CENTER, s.cell(0, 2)!!.align)
        assertEquals(TextAlign.RIGHT, s.cell(0, 3)!!.align)
    }

    private fun entry(bytes: ByteArray, name: String): String =
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.first { it.name == name }; z.readBytes().decodeToString() }

    @Test
    fun xlsxKeepsGeneralApartFromLeft() {
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(alignedBook(), bos)
        assertTrue(entry(bos.toByteArray(), "xl/styles.xml").contains("""horizontal="left""""))
        assertAligned(XlsxReader.read(bos.toByteArray()).sheets[0])
    }

    @Test
    fun odsKeepsGeneralApartFromLeft() {
        val bos = ByteArrayOutputStream()
        OdsWriter.write(alignedBook(), bos)
        val content = entry(bos.toByteArray(), "content.xml")
        assertTrue("General follows the value type in Calc", content.contains("""style:text-align-source="value-type""""))
        assertTrue(content.contains("""style:text-align-source="fix""""))
        assertAligned(OdsReader.read(bos.toByteArray()).sheets[0])
        // Older files put a bare `fo:text-align="start"` on every cell for the default: that is General.
        // (No text-align-source anywhere, and a bare start on the styles that had no alignment.)
        val legacy = rezip(bos.toByteArray(), "content.xml") {
            it.replace(Regex("""\s*style:text-align-source="[a-z-]+""""), "")
                .replace(Regex("""(<style:table-cell-properties[^>]*/>)(</style:style>)"""), "$1<style:paragraph-properties fo:text-align=\"start\"/>$2")
        }
        val back = OdsReader.read(legacy).sheets[0]
        assertNull(back.cell(0, 0)!!.align)
        assertNull("a bare start is the default", back.cell(0, 1)!!.align)
        assertEquals(TextAlign.CENTER, back.cell(0, 2)!!.align)
    }

    /** [bytes] with entry [name] rewritten by [edit]. */
    private fun rezip(bytes: ByteArray, name: String, edit: (String) -> String): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zo ->
            ZipInputStream(ByteArrayInputStream(bytes)).use { zi ->
                generateSequence { zi.nextEntry }.forEach { e ->
                    val data = zi.readBytes()
                    zo.putNextEntry(java.util.zip.ZipEntry(e.name))
                    zo.write(if (e.name == name) edit(data.decodeToString()).toByteArray() else data)
                    zo.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun rightAlignedTextRunsOnToTheLeftAsInExcel() {
        val e = editor()
        e.setCell(0, 3, "INVOICE"); e.sheet.cell(0, 3)!!.align = com.therealityteam.aeropages.model.TextAlign.RIGHT
        assertTrue("right-aligned text may run on over empty cells to its left", e.overflowsLeft(0, 3))
        assertFalse("it does not run on to the right", e.overflows(0, 3))
        e.setCell(1, 3, "1234"); e.sheet.cell(1, 3)!!.align = com.therealityteam.aeropages.model.TextAlign.RIGHT
        assertFalse("a number never runs on (it shows ####)", e.overflowsLeft(1, 3))
        e.setCell(2, 3, "Wrapped"); e.sheet.cell(2, 3)!!.apply { align = com.therealityteam.aeropages.model.TextAlign.RIGHT; wrap = true }
        assertFalse("wrapping text stays in its cell", e.overflowsLeft(2, 3))
        e.setCell(3, 0, "Left"); assertFalse(e.overflowsLeft(3, 0))
    }
}
