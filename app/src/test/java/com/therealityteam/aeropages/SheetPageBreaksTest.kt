package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetPages
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Excel's manual page breaks: where the pages break, the Breaks commands, and xlsx and ODS keeping them. */
class SheetPageBreaksTest {

    private fun sheet(rows: Int = 120, cols: Int = 6) = Sheet(name = "Data").also { s ->
        for (r in 0 until rows) for (c in 0 until cols) s.cells[s.key(r, c)] = Cell("${r * cols + c}")
    }

    @Test
    fun aManualBreakStartsAPageAndTheRestFlowOnFromIt() {
        val s = sheet()
        val plain = SheetPages.layout(s)!!
        assertTrue("120 rows of 15 pt need more than one A4 page", plain.rowPages.size > 1)
        val perPage = plain.rowPages[0].size
        s.print = s.print.copy(rowBreaks = listOf(10), colBreaks = listOf(3))
        val l = SheetPages.layout(s)!!
        assertEquals(10, l.rowStarts.first())
        assertEquals("the next automatic break counts from the manual one", 10 + perPage, l.rowStarts[1])
        assertEquals(listOf(0, 1, 2), l.colBands[0]); assertEquals(3, l.colStarts.first())
        // Excel's page order: down the rows and then over by default, across first when set.
        assertEquals(l.rowPages.size + 1, l.pageNumber(0, 1, overThenDown = false))
        assertEquals(2, l.pageNumber(0, 1, overThenDown = true))
        assertEquals(l.colBands.size + 1, l.pageNumber(1, 0, overThenDown = true))
        // Fitting to pages ignores manual breaks, as Excel does.
        s.print = s.print.copy(fitWidth = 1, fitHeight = 1)
        val fit = SheetPages.layout(s)!!
        assertEquals(1, fit.rowPages.size); assertEquals(1, fit.colBands.size)
    }

    @Test
    fun theBreaksCommandsAreOneStepEachAndMoveWithTheirRows() {
        val e = SheetEditor().apply { load(Workbook(mutableListOf(sheet()))) }
        e.select(7, 3)
        assertTrue(e.insertPageBreak())
        assertEquals(listOf(7), e.sheet.print.rowBreaks); assertEquals(listOf(3), e.sheet.print.colBreaks)
        assertTrue("the sheet shows its breaks after one is inserted", e.sheet.showPageBreaks)
        assertFalse("the same break again adds nothing", e.insertPageBreak())
        // In column A only the break across; in row 1 only the break down.
        e.select(20, 0); assertTrue(e.insertPageBreak())
        assertEquals(listOf(7, 20), e.sheet.print.rowBreaks); assertEquals(listOf(3), e.sheet.print.colBreaks)
        e.select(0, 0); assertFalse("A1 has none to add", e.insertPageBreak())
        // Rows inserted above move the breaks down; deleted, up.
        e.insertRowAt(2)
        assertEquals(listOf(8, 21), e.sheet.print.rowBreaks)
        e.deleteRowAt(0); e.deleteRowAt(0)
        assertEquals(listOf(6, 19), e.sheet.print.rowBreaks)
        e.undo(); e.undo(); e.undo()
        assertEquals(listOf(7, 20), e.sheet.print.rowBreaks)
        // Remove the one at the active cell, then reset them all.
        e.select(20, 0)
        assertTrue(e.canRemovePageBreak); assertTrue(e.removePageBreak())
        assertEquals(listOf(7), e.sheet.print.rowBreaks)
        assertTrue(e.resetPageBreaks())
        assertTrue(e.sheet.print.rowBreaks.isEmpty() && e.sheet.print.colBreaks.isEmpty())
        assertFalse(e.resetPageBreaks())
        e.undo(); e.undo()
        assertEquals(listOf(7, 20), e.sheet.print.rowBreaks)
        // Page Break Preview is a view, not an edit: no undo step.
        e.setPageBreakPreview(true)
        assertTrue(e.sheet.pageBreakPreview)
        e.undo()
        assertTrue(e.sheet.pageBreakPreview); assertEquals(listOf(7), e.sheet.print.rowBreaks)
    }

    @Test
    fun pageBreakPreviewHasItsOwnZoomAndIsSaved() {
        val e = SheetEditor().apply { load(Workbook(mutableListOf(sheet()))) }
        e.setZoom(120)
        e.setPageBreakPreview(true)
        assertEquals("Excel's 60 % the first time", 60, e.sheet.viewZoom)
        e.setZoom(75)
        e.setPageBreakPreview(false)
        assertEquals(120, e.sheet.viewZoom)
        e.setPageBreakPreview(true)
        assertEquals(75, e.sheet.viewZoom)
        // The copy a save writes keeps the view.
        val copy = e.workbook.copyOf().sheets[0]
        assertTrue(copy.pageBreakPreview); assertEquals(75, copy.previewZoom); assertEquals(120, copy.zoom)
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(e.workbook.copyOf(), it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!)
        assertTrue(xml, xml.contains("""zoomScale="75" zoomScaleNormal="120" zoomScaleSheetLayoutView="75""""))
        val back = XlsxReader.read(bytes).sheets[0]
        assertTrue(back.pageBreakPreview); assertEquals(120, back.zoom); assertEquals(75, back.previewZoom)
        // Excel writing only the shown zoom: it is the preview's when the sheet is in the preview.
        val only = xml.replace(""" zoomScaleNormal="120" zoomScaleSheetLayoutView="75"""", "")
        val files = OfficeXml.unzip(bytes).toMutableMap().also { it["xl/worksheets/sheet1.xml"] = only.toByteArray() }
        XlsxReader.read(zip(files)).sheets[0].let { assertEquals(100, it.zoom); assertEquals(75, it.previewZoom) }
        // ODS: Calc's PageViewZoomValue, and the page break view.
        val ods = ByteArrayOutputStream().also { OdsWriter.write(e.workbook.copyOf(), it) }.toByteArray()
        val settings = String(OfficeXml.unzip(ods)["settings.xml"]!!)
        assertTrue(settings, settings.contains("""<config:config-item config:name="PageViewZoomValue" config:type="int">75</config:config-item>"""))
        assertTrue(settings, settings.contains("""<config:config-item config:name="ShowPageBreakPreview" config:type="boolean">true</config:config-item>"""))
        OdsReader.read(ods).sheets[0].let { assertTrue(it.pageBreakPreview); assertEquals(75, it.previewZoom); assertEquals(120, it.zoom) }
    }

    @Test
    fun linesAreDraggedAsExcelDragsThem() {
        val e = SheetEditor().apply { load(Workbook(mutableListOf(sheet()))) }
        val perPage = e.pageLayout()!!.rowStarts.first()
        // A manual break moves; dragged off the range it goes.
        e.select(10, 0); e.insertPageBreak()
        assertTrue(e.movePageEdge(true, 10, 20))
        assertEquals(listOf(20), e.sheet.print.rowBreaks)
        assertTrue(e.movePageEdge(true, 20, 0))
        assertTrue(e.sheet.print.rowBreaks.isEmpty())
        // An automatic break dragged up becomes manual; the scale stays.
        assertTrue(e.movePageEdge(true, perPage, perPage - 5))
        assertEquals(listOf(perPage - 5), e.sheet.print.rowBreaks); assertEquals(100, e.sheet.print.scale)
        e.undo()
        assertTrue(e.sheet.print.rowBreaks.isEmpty())
        // Dragged past what a page holds, the scale shrinks until it holds it.
        assertTrue(e.movePageEdge(true, perPage, perPage + 10))
        val l = e.pageLayout()!!
        assertEquals("the first page ends at the dragged break", perPage + 10, l.rowStarts.first())
        assertTrue(e.sheet.print.scale in 10..99)
        assertEquals("only just: one percent more and it would not hold", false,
            com.therealityteam.aeropages.model.SheetPages.layout(e.sheet, e.sheet.print.copy(scale = e.sheet.print.scale + 1))!!.rowStarts.first() == perPage + 10)
        // The print range's edges set the print area, the other edge staying.
        assertTrue(e.movePageEdge(true, 0, 5))
        assertEquals(5, e.sheet.printArea!!.r1); assertEquals(119, e.sheet.printArea!!.r2); assertEquals(5, e.sheet.printArea!!.c2)
        assertTrue(e.movePageEdge(false, 6, 4))
        assertEquals(3, e.sheet.printArea!!.c2); assertEquals(5, e.sheet.printArea!!.r1)
        assertFalse("nothing moves", e.movePageEdge(false, 2, 2))
        // Fit to pages: dragging a break turns it into the scale it prints at.
        e.setPrint { it.copy(fitWidth = 1, fitHeight = 1) }
        val fit = e.pageLayout()!!
        assertTrue(e.movePageEdge(true, 60, 70) || e.movePageEdge(true, 70, 60))
        assertFalse(e.sheet.print.fits)
        assertTrue(e.sheet.print.scale <= (fit.scale * 100f).toInt())
    }

    @Test
    fun excelKeepsTheBreaksAndThePreview() {
        val s = sheet().also { it.print = it.print.copy(rowBreaks = listOf(10, 50), colBreaks = listOf(3), overThenDown = true); it.pageBreakPreview = true }
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(Workbook(mutableListOf(s)), it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!)
        assertTrue(xml, xml.contains("""<rowBreaks count="2" manualBreakCount="2"><brk id="10" max="16383" man="1"/><brk id="50" max="16383" man="1"/></rowBreaks>"""))
        assertTrue(xml, xml.contains("""<colBreaks count="1" manualBreakCount="1"><brk id="3" max="1048575" man="1"/></colBreaks>"""))
        assertTrue(xml, xml.contains("""view="pageBreakPreview""""))
        assertTrue(xml, xml.contains("""pageOrder="overThenDown""""))
        val back = XlsxReader.read(bytes).sheets[0]
        assertEquals(listOf(10, 50), back.print.rowBreaks); assertEquals(listOf(3), back.print.colBreaks)
        assertTrue(back.pageBreakPreview); assertTrue(back.print.overThenDown)
        // Excel's automatic breaks (no man) are not the sheet's to keep.
        val auto = xml.replace("""<brk id="50" max="16383" man="1"/>""", """<brk id="50" max="16383"/>""")
        val files = OfficeXml.unzip(bytes).toMutableMap().also { it["xl/worksheets/sheet1.xml"] = auto.toByteArray() }
        assertEquals(listOf(10), XlsxReader.read(zip(files)).sheets[0].print.rowBreaks)
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        java.util.zip.ZipOutputStream(bos).use { z -> for ((n, b) in files) { z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    @Test
    fun odfKeepsTheBreaksOnItsRowsAndColumns() {
        val s = sheet().also { it.print = it.print.copy(rowBreaks = listOf(10, 50), colBreaks = listOf(3), overThenDown = true) }
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(Workbook(mutableListOf(s)), it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!)
        assertTrue(content, content.contains("""fo:break-before="page""""))
        val back = OdsReader.read(bytes).sheets[0]
        assertEquals(listOf(10, 50), back.print.rowBreaks); assertEquals(listOf(3), back.print.colBreaks)
        assertEquals(s.cells.size, back.cells.size)
        assertTrue("LibreOffice's print-page-order", back.print.overThenDown)
        assertTrue(String(OfficeXml.unzip(bytes)["styles.xml"]!!).contains("""style:print-page-order="ltr""""))
    }

    /** With `-Daero.sampleDir`: a sheet with breaks above rows 11 and 51 and left of D, as xlsx and ODS for LibreOffice (and printed across first). */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        fun book() = Workbook(mutableListOf(sheet().also { it.print = it.print.copy(rowBreaks = listOf(10, 50), colBreaks = listOf(3)) }))
        File(dir, "page-breaks.xlsx").outputStream().use { XlsxWriter.write(book(), it) }
        File(dir, "page-breaks.ods").outputStream().use { OdsWriter.write(book(), it) }
        val across = Workbook(mutableListOf(sheet().also { it.print = it.print.copy(rowBreaks = listOf(10, 50), colBreaks = listOf(3), overThenDown = true) }))
        File(dir, "page-order.xlsx").outputStream().use { XlsxWriter.write(across, it) }
        File(dir, "page-order.ods").outputStream().use { OdsWriter.write(across, it) }
    }
}
