package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.CellSpread
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

/** Excel's other horizontal alignments — Fill, Center Across Selection, Distributed — and Shrink to fit. */
class CellSpreadTest {

    /** A1 centred across A1:C1; A2 filled with "-"; A3 distributed; A4 shrunk to fit. */
    private fun workbook(): Workbook {
        val s = Sheet(name = "S")
        s.cells[s.key(0, 0)] = Cell("Quarterly report", align = TextAlign.CENTER, spread = CellSpread.CENTER_ACROSS)
        for (c in 1..2) s.cells[s.key(0, c)] = Cell("", align = TextAlign.CENTER, spread = CellSpread.CENTER_ACROSS)
        s.cells[s.key(1, 0)] = Cell("-", align = TextAlign.LEFT, spread = CellSpread.FILL)
        s.cells[s.key(2, 0)] = Cell("Name", align = TextAlign.JUSTIFY, spread = CellSpread.DISTRIBUTED)
        s.cells[s.key(3, 0)] = Cell("A rather long description", shrink = true)
        return Workbook(mutableListOf(s))
    }

    @Test
    fun xlsxKeepsExcelsAlignments() {
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(workbook(), it) }.toByteArray()
        val styles = String(OfficeXml.unzip(bytes)["xl/styles.xml"]!!, Charsets.UTF_8)
        for (h in listOf("centerContinuous", "fill", "distributed")) assertTrue(h, styles.contains("""horizontal="$h""""))
        assertTrue(styles.contains("""shrinkToFit="1""""))
        val s = XlsxReader.read(bytes).sheets[0]
        assertEquals(CellSpread.CENTER_ACROSS, s.cell(0, 0)!!.spread); assertEquals(TextAlign.CENTER, s.cell(0, 0)!!.align)
        assertEquals(CellSpread.CENTER_ACROSS, s.cell(0, 2)!!.spread)
        assertEquals(CellSpread.FILL, s.cell(1, 0)!!.spread); assertEquals(TextAlign.LEFT, s.cell(1, 0)!!.align)
        assertEquals(CellSpread.DISTRIBUTED, s.cell(2, 0)!!.spread)
        assertTrue(s.cell(3, 0)!!.shrink)
    }

    @Test
    fun odsKeepsWhatLibreOfficeHas() {
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(workbook(), it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertTrue(content.contains("""style:repeat-content="true""""))
        assertTrue(content.contains("""css3t:text-justify="distribute""""))
        assertTrue(content.contains("""style:shrink-to-fit="true""""))
        assertTrue("the namespace is declared", content.contains("xmlns:css3t="))
        val s = OdsReader.read(bytes).sheets[0]
        // ODF has no Center Across Selection: it comes back centred.
        assertNull(s.cell(0, 0)!!.spread); assertEquals(TextAlign.CENTER, s.cell(0, 0)!!.align)
        assertEquals(CellSpread.FILL, s.cell(1, 0)!!.spread)
        assertEquals(CellSpread.DISTRIBUTED, s.cell(2, 0)!!.spread); assertEquals(TextAlign.JUSTIFY, s.cell(2, 0)!!.align)
        assertTrue(s.cell(3, 0)!!.shrink)
    }

    @Test
    fun theEditorSpansAcrossAndKeepsTheseCellsToThemselves() {
        val e = SheetEditor().apply { load(workbook()) }
        assertTrue(e.centersAcross(0, 0))
        assertTrue(e.acrossContinues(0, 1)); assertTrue(e.acrossContinues(0, 2)); assertFalse(e.acrossContinues(0, 3))
        assertFalse("filled and shrunk text does not run on", e.overflows(1, 0) || e.overflows(3, 0))
        // A plain alignment button replaces the special one.
        e.select(1, 0); e.setAlign(TextAlign.RIGHT)
        assertNull(e.sheet.cell(1, 0)!!.spread); assertEquals(TextAlign.RIGHT, e.sheet.cell(1, 0)!!.align)
        e.undo()
        assertEquals(CellSpread.FILL, e.sheet.cell(1, 0)!!.spread)
        // Clear Formats takes them away.
        e.select(3, 0); e.clear(SheetEditor.ClearWhat.FORMATS)
        assertFalse(e.sheet.cell(3, 0)!!.shrink)
    }

    /** With `-Daero.sampleDir`: the four, with a value beside the centred title, for LibreOffice and the app. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val wb = workbook()
        File(dir, "halign.xlsx").outputStream().use { XlsxWriter.write(wb, it) }
        File(dir, "halign.ods").outputStream().use { OdsWriter.write(wb, it) }
    }
}
