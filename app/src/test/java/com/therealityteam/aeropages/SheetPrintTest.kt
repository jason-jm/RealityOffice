package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.Paper
import com.therealityteam.aeropages.model.PrintArea
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetPrint
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** How a sheet prints: Excel's margins, scale and fit, print titles, gridlines and headings, header and footer — in xlsx and ODS. */
class SheetPrintTest {

    @Test
    fun headerCodes() {
        val parts = SheetPrint.fromExcel("&L&A&C&\"Arial,Bold\"&14Page &P of &N&R&D")
        assertEquals("&A", parts[0]); assertEquals("&D", parts[2])
        assertEquals("Page 2 of 5", SheetPrint.fill(parts[1], 2, 5, "Q3", "book.xlsx"))
        assertEquals("Q3 & book.xlsx", SheetPrint.fill("&A && &F", 1, 1, "Q3", "book.xlsx"))
        // Text before any section code is the centre's; the parts back as Excel writes them.
        assertEquals(listOf("", "Confidential", ""), SheetPrint.fromExcel("Confidential"))
        assertEquals("&LLeft&RPage &P", SheetPrint.toExcel(listOf("Left", "", "Page &P")))
        // Print titles: rows, columns, both.
        assertEquals((0..1) to null, XlsxReader.parsePrintTitles("Sheet1!\$1:\$2"))
        assertEquals((0..0) to (0..1), XlsxReader.parsePrintTitles("'My Sheet'!\$A:\$B,'My Sheet'!\$1:\$1"))
    }

    private fun book(): Workbook {
        val s = Sheet(name = "Sales Q3")
        for (r in 0..40) for (c in 0..12) s.cells[s.key(r, c)] = Cell(if (r == 0) "Head $c" else "${r * c}")
        s.landscape = true
        s.paper = Paper.A3
        s.printArea = PrintArea(0, 0, 40, 12)
        s.print = SheetPrint(marginTop = 72f, marginBottom = 54f, marginLeft = 36f, marginRight = 36f, marginHeader = 28.8f, marginFooter = 21.6f,
            fitWidth = 1, fitHeight = 0, gridlines = true, headings = true, centerH = true,
            titleRows = 0..0, titleCols = 0..0, header = listOf("&A", "", "&D"), footer = listOf("", "Page &P of &N", ""))
        return Workbook(mutableListOf(s, Sheet(name = "Plain").also { it.cells[it.key(0, 0)] = Cell("x") }))
    }

    @Test
    fun blackAndWhiteDraftAndCellErrorsAreKept() {
        val wb = book().also { it.sheets[0].print = it.sheets[0].print.copy(blackAndWhite = true, draft = true, errors = com.therealityteam.aeropages.model.ErrorPrint.DASH) }
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!)
        assertTrue(xml, Regex("""<pageSetup [^>]*blackAndWhite="1" draft="1" errors="dash"""").containsMatchIn(xml))
        assertEquals(wb.sheets[0].print, XlsxReader.read(bytes).sheets[0].print)
        // Each error setting, and Excel's defaults writing nothing.
        for (e in com.therealityteam.aeropages.model.ErrorPrint.entries) {
            wb.sheets[0].print = wb.sheets[0].print.copy(blackAndWhite = false, draft = false, errors = e)
            val b = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
            assertEquals(e, XlsxReader.read(b).sheets[0].print.errors)
            val x = String(OfficeXml.unzip(b)["xl/worksheets/sheet1.xml"]!!)
            assertEquals(e != com.therealityteam.aeropages.model.ErrorPrint.DISPLAYED, x.contains("errors="))
            assertTrue(!x.contains("blackAndWhite") && !x.contains("draft="))
        }
        assertEquals(listOf(null, "", "--", "#N/A"), com.therealityteam.aeropages.model.ErrorPrint.entries.map { it.text })
    }

    /** Samples (with `-Daero.sampleDir`): a sheet with fills, colours, an error and a picture printed black and white, errors as "--". */
    @Test
    fun writeInkSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { java.io.File(it).also { d -> d.mkdirs() } } ?: return
        val s = Sheet(name = "Ink")
        s.ensure(0, 0).also { it.text = "Region"; it.format.bold = true; it.format.highlight = 0xFF4472C4; it.format.color = 0xFFFFFFFF }
        s.ensure(0, 1).also { it.text = "Sales"; it.format.bold = true; it.format.highlight = 0xFF4472C4; it.format.color = 0xFFFFFFFF }
        listOf("North" to "120", "South" to "=1/0", "East" to "95").forEachIndexed { i, (a, b) ->
            s.ensure(i + 1, 0).text = a
            s.ensure(i + 1, 1).also { it.text = b; it.format.color = 0xFFC00000; it.format.highlight = 0xFFFFF2CC }
        }
        s.print = SheetPrint(gridlines = true, blackAndWhite = true, errors = com.therealityteam.aeropages.model.ErrorPrint.DASH)
        java.io.File(dir, "print-ink.xlsx").outputStream().use { XlsxWriter.write(Workbook(mutableListOf(s)), it) }
        s.print = SheetPrint(gridlines = true)
        java.io.File(dir, "print-ink-colour.xlsx").outputStream().use { XlsxWriter.write(Workbook(mutableListOf(s)), it) }
    }

    @Test
    fun excelKeepsIt() {
        val wb = book()
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!)
        assertTrue(xml, xml.contains("""<pageSetUpPr fitToPage="1"/>"""))
        assertTrue(xml, xml.contains("""<printOptions horizontalCentered="1" headings="1" gridLines="1"/>"""))
        val back = XlsxReader.read(bytes)
        val s = back.sheets[0]
        assertEquals(wb.sheets[0].print, s.print)
        assertTrue(s.landscape); assertEquals(Paper.A3, s.paper)
        // A sheet left as it was stays Excel's defaults: its margins (as Excel writes them on every sheet), no page setup.
        assertEquals(SheetPrint(), back.sheets[1].print)
        val plain = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet2.xml"]!!)
        assertTrue(plain.contains("""<pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>""") && !plain.contains("pageSetup"))
        // LibreOffice's way of naming the paper: its width and height.
        val lo = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!).replace("""paperSize="8"""", """paperWidth="297mm" paperHeight="420mm"""")
        val files = OfficeXml.unzip(bytes).toMutableMap().also { it["xl/worksheets/sheet1.xml"] = lo.toByteArray() }
        assertEquals(Paper.A3, XlsxReader.read(zip(files)).sheets[0].paper)
    }

    private fun zip(files: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        java.util.zip.ZipOutputStream(bos).use { z -> files.forEach { (n, b) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()

    @Test
    fun odfKeepsIt() {
        val wb = book()
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()
        val back = OdsReader.read(bytes)
        val s = back.sheets[0]
        // ODS keeps it all, the print titles as the table's header rows and columns.
        assertEquals(wb.sheets[0].print, s.print)
        val xml = String(OfficeXml.unzip(bytes)["content.xml"]!!)
        assertTrue(xml.contains("<table:table-header-columns><table:table-column ") && xml.contains("<table:table-header-rows><table:table-row "))
        // Header rows that an outline group would cut through are left as plain rows (the file stays well nested).
        val grouped = book().also { it.sheets[0].rowGroups.add(com.therealityteam.aeropages.model.RowGroup(0, 3, false)); it.sheets[0].print = it.sheets[0].print.copy(titleRows = 2..5) }
        val g = OdsReader.read(ByteArrayOutputStream().also { OdsWriter.write(grouped, it) }.toByteArray()).sheets[0]
        assertEquals(null, g.print.titleRows); assertEquals(0..0, g.print.titleCols)
        assertTrue(s.landscape); assertEquals(Paper.A3, s.paper)
        assertEquals(0, s.printArea!!.r1); assertEquals(12, s.printArea!!.c2)
        assertEquals(SheetPrint(), back.sheets[1].print)
    }

    @Test
    fun pageSetupIsOneStepBack() {
        val e = SheetEditor().apply { load(Workbook(mutableListOf(Sheet().also { it.cells[it.key(0, 0)] = Cell("a") }))) }
        e.setPrint(landscape = true, paper = Paper.LEGAL) { it.copy(scale = 80, gridlines = true) }
        assertEquals(80, e.sheet.print.scale); assertTrue(e.sheet.landscape)
        e.select(0, 0); e.extendSelection(1, 0)
        e.setPrintTitleRowsFromSelection()
        assertEquals(0..1, e.sheet.print.titleRows)
        e.undo(); e.undo()
        assertEquals(SheetPrint(), e.sheet.print); assertEquals(Paper.A4, e.sheet.paper)
    }

    /** With `-Daero.sampleDir`: the workbook as xlsx and ODS, for LibreOffice to print. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "sheet-print.xlsx").outputStream().use { XlsxWriter.write(book(), it) }
        File(dir, "sheet-print.ods").outputStream().use { OdsWriter.write(book(), it) }
    }
}
