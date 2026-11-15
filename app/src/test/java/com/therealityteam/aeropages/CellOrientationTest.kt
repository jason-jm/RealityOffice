package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.RowFit
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Excel's Alignment group: Orientation (turned and stacked text), Increase / Decrease Indent, Merge Across. */
class CellOrientationTest {

    /** Row 0: a header of turned labels; row 1 onwards: indented names. */
    private fun workbook(): Workbook {
        val s = Sheet(name = "Data")
        listOf(45, -45, 90, -90).forEachIndexed { i, deg -> s.cells[s.key(0, i)] = Cell("Q${i + 1}", rotation = deg) }
        s.cells[s.key(0, 4)] = Cell("Total", stacked = true)
        s.cells[s.key(1, 0)] = Cell("Revenue", align = TextAlign.LEFT, indent = 1)
        s.cells[s.key(2, 0)] = Cell("Costs", align = TextAlign.RIGHT, indent = 2)
        return Workbook(mutableListOf(s))
    }

    @Test
    fun orientationAndIndentAreUndoableCommands() {
        val e = SheetEditor().apply { load(Workbook(mutableListOf(Sheet(name = "S").also { it.cells[it.key(0, 0)] = Cell("Head") }))) }
        e.select(0, 0)
        e.setOrientation(45)
        assertEquals(45, e.sheet.cell(0, 0)!!.rotation)
        e.setOrientation(0, stacked = true)
        assertTrue(e.sheet.cell(0, 0)!!.stacked); assertEquals(0, e.sheet.cell(0, 0)!!.rotation)
        e.setOrientation(0, stacked = true)
        assertFalse("picking it again turns the text back", e.sheet.cell(0, 0)!!.stacked)
        e.undo()
        assertTrue(e.sheet.cell(0, 0)!!.stacked)
        // Increase Indent aligns General text left; right-aligned text keeps its side; Decrease stops at none.
        e.stepIndent(+1)
        assertEquals(1, e.activeIndent); assertEquals(TextAlign.LEFT, e.activeAlign)
        e.select(1, 0); e.setAlign(TextAlign.RIGHT); e.stepIndent(+1)
        assertEquals(TextAlign.RIGHT, e.activeAlign); assertEquals(1, e.activeIndent)
        e.stepIndent(-1); e.stepIndent(-1)
        assertEquals(0, e.activeIndent)
        repeat(20) { e.stepIndent(+1) }
        assertEquals(Cell.MAX_INDENT, e.activeIndent)
        // Clear Formats takes them all away.
        e.select(0, 0)
        e.clear(SheetEditor.ClearWhat.FORMATS)
        val c = e.sheet.cell(0, 0)!!
        assertEquals(0, c.indent); assertFalse(c.stacked); assertEquals(0, c.rotation)
    }

    @Test
    fun mergeAcrossMergesEachRowOnItsOwn() {
        val e = SheetEditor().apply { load(Workbook(mutableListOf(Sheet(name = "S")))) }
        e.select(1, 1); e.extendSelection(2, 2)                 // B2:D4
        e.mergeAcross()
        assertEquals(3, e.sheet.merges.size)
        assertTrue(e.sheet.merges.all { it.r1 == it.r2 && it.c1 == 1 && it.c2 == 3 })
        assertTrue(e.selectionHasMerge)
        e.unmergeCells()
        assertTrue(e.sheet.merges.isEmpty())
        e.undo(); e.undo()
        assertTrue("both steps undone", e.sheet.merges.isEmpty())
        e.select(1, 1); e.extendSelection(2, 0)                 // a single column: nothing to merge across
        e.mergeAcross()
        assertTrue(e.sheet.merges.isEmpty())
    }

    @Test
    fun xlsxWritesExcelsRotationCodesAndReadsThemBack() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(workbook(), it) }.toByteArray()
        val styles = String(OfficeXml.unzip(bytes)["xl/styles.xml"]!!, Charsets.UTF_8)
        for (code in listOf("45", "135", "90", "180", "255")) assertTrue(code, styles.contains("""textRotation="$code""""))
        assertTrue(styles.contains("""indent="2""""))
        val s = XlsxReader.read(bytes).sheets[0]
        assertEquals(listOf(45, -45, 90, -90), (0..3).map { s.cell(0, it)!!.rotation })
        assertTrue(s.cell(0, 4)!!.stacked)
        assertEquals(1, s.cell(1, 0)!!.indent); assertEquals(2, s.cell(2, 0)!!.indent)
        assertEquals(TextAlign.RIGHT, s.cell(2, 0)!!.align)
    }

    @Test
    fun odsWritesLibreOfficesPropertiesAndReadsItsOwn() {
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(workbook(), it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertTrue(content.contains("""style:rotation-angle="315""""))
        assertTrue(content.contains("""style:direction="ttb""""))
        assertTrue(content.contains("""fo:margin-left="15.00pt""""))
        val s = OdsReader.read(bytes).sheets[0]
        assertEquals(listOf(45, -45, 90, -90), (0..3).map { s.cell(0, it)!!.rotation })
        assertTrue(s.cell(0, 4)!!.stacked)
        assertEquals(1, s.cell(1, 0)!!.indent); assertEquals(2, s.cell(2, 0)!!.indent)
        assertNull("a plain cell is not turned", s.cell(1, 0)!!.takeIf { it.turned })
    }

    @Test
    fun turnedTextMakesTheRowTallAndStacksWholeLetters() {
        val plain = RowFit.cellFactor(Cell("Quarterly"), "Quarterly", 1f)
        val up = RowFit.cellFactor(Cell("Quarterly", rotation = 90), "Quarterly", 1f)
        val slant = RowFit.cellFactor(Cell("Quarterly", rotation = 45), "Quarterly", 1f)
        assertEquals(1f, plain, 0.001f)
        assertTrue("upright text is as tall as it is long", up > 3f)
        assertTrue(slant > plain && slant < up)
        assertEquals(5f, RowFit.cellFactor(Cell("Total", stacked = true), "Total", 1f), 0.001f)
        // A Thai vowel mark stays on its letter.
        assertEquals(listOf("ข้", "อ"), Cell.stackedLetters("ข้อ"))
        assertEquals(listOf("A", "b"), Cell.stackedLetters("A\nb"))
    }

    /** With `-Daero.sampleDir`: turned headers and indented rows, for LibreOffice and the app. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val wb = workbook()
        val s = wb.sheets[0]
        listOf(1200, 900, 300, 100).forEachIndexed { i, v -> s.cells[s.key(1, i + 1)] = Cell(v.toString()) }
        File(dir, "orientation.xlsx").outputStream().use { XlsxWriter.write(wb, it) }
        File(dir, "orientation.ods").outputStream().use { OdsWriter.write(wb, it) }
    }
}
