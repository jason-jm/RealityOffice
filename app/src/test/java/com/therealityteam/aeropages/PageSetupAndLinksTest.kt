package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Paper
import com.therealityteam.aeropages.model.PrintArea
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Sheets page setup (orientation, paper, print area, headings) and cell hyperlinks: editor + xlsx round trip. */
class PageSetupAndLinksTest {

    private fun roundTrip(wb: Workbook): Workbook {
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        return XlsxReader.read(bos.toByteArray())
    }

    @Test
    fun pageSetupAndPrintAreaRoundTrip() {
        val wb = Workbook(mutableListOf(Sheet(name = "Sales Q3"), Sheet(name = "Notes")))
        wb.sheets[0].ensure(0, 0).text = "x"
        wb.sheets[0].landscape = true
        wb.sheets[0].paper = Paper.LETTER
        wb.sheets[0].printArea = PrintArea(1, 0, 9, 3)
        wb.sheets[0].showHeadings = false
        wb.sheets[1].ensure(0, 0).text = "y"
        wb.names["Total"] = "'Sales Q3'!A1:A9"
        val back = roundTrip(wb)
        val s = back.sheets[0]
        assertTrue(s.landscape)
        assertEquals(Paper.LETTER, s.paper)
        assertFalse(s.showHeadings)
        val pa = s.printArea!!
        assertEquals(listOf(1, 0, 9, 3), listOf(pa.r1, pa.c1, pa.r2, pa.c2))
        assertNull(back.sheets[1].printArea)
        assertFalse(back.sheets[1].landscape)
        assertTrue(back.sheets[1].showHeadings)
        // Built-in names never leak into the user-visible name list.
        assertEquals(mapOf("Total" to "'Sales Q3'!A1:A9"), back.names)
    }

    @Test
    fun printAreaReferenceParsing() {
        assertEquals(listOf(0, 0, 9, 2), XlsxReader.parsePrintArea("Sheet1!\$A\$1:\$C\$10").let { listOf(it!!.r1, it.c1, it.r2, it.c2) })
        assertEquals(listOf(4, 1, 4, 1), XlsxReader.parsePrintArea("'My Sheet'!B5").let { listOf(it!!.r1, it.c1, it.r2, it.c2) })
        assertNull(XlsxReader.parsePrintArea("#REF!"))
    }

    @Test
    fun hyperlinksRoundTripAndEdit() {
        val e = SheetEditor()
        e.select(2, 1)
        e.setLink("https://example.com/report")
        assertEquals("https://example.com/report", e.sheet.cell(2, 1)?.text) // empty cell shows the URL
        assertEquals("https://example.com/report", e.activeLink)
        e.setCell(2, 1, "Report")
        e.select(0, 0); e.setCell(0, 0, "Home"); e.setLink("pico.com")
        val back = roundTrip(e.workbook)
        assertEquals("https://example.com/report", back.sheets[0].cell(2, 1)?.link)
        assertEquals("Report", back.sheets[0].cell(2, 1)?.text)
        assertEquals("pico.com", back.sheets[0].cell(0, 0)?.link)
        // Removing the link keeps the text; undo restores it.
        e.setLink(null)
        assertNull(e.activeLink)
        assertEquals("Home", e.sheet.cell(0, 0)?.text)
        e.undo()
        assertEquals("pico.com", e.activeLink)
    }

    @Test
    fun pageSetupIsUndoableAndPrintAreaFollowsRows() {
        val e = SheetEditor()
        e.select(2, 0); e.extendSelection(3, 2)
        e.setPrintAreaFromSelection()
        assertEquals(listOf(2, 0, 5, 2), e.sheet.printArea!!.let { listOf(it.r1, it.c1, it.r2, it.c2) })
        e.setLandscape(true); e.setPaper(Paper.LETTER); e.toggleHeadings()
        assertTrue(e.sheet.landscape); assertEquals(Paper.LETTER, e.sheet.paper); assertFalse(e.sheet.showHeadings)
        e.insertRowAt(0)
        assertEquals(listOf(3, 0, 6, 2), e.sheet.printArea!!.let { listOf(it.r1, it.c1, it.r2, it.c2) })
        e.undo(); e.undo(); e.undo(); e.undo()
        assertTrue(e.sheet.showHeadings); assertEquals(Paper.A4, e.sheet.paper); assertFalse(e.sheet.landscape)
        assertEquals(listOf(2, 0, 5, 2), e.sheet.printArea!!.let { listOf(it.r1, it.c1, it.r2, it.c2) })
        e.undo()
        assertNull(e.sheet.printArea)
        e.select(0, 0); e.setPrintAreaFromSelection() // single cell = clear
        assertNull(e.sheet.printArea)
    }
}
