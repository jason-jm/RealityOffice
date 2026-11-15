package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Sparkline
import com.therealityteam.aeropages.model.SparklineType
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Sparklines (in-cell mini charts) and sheet protection: editor behaviour and both file formats. */
class SparklinesAndProtectionTest {

    private fun editor(): SheetEditor {
        val e = SheetEditor()
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        // Two rows of four numbers, B..E.
        listOf(3, 5, 2, 8).forEachIndexed { i, n -> s.ensure(0, 1 + i).text = n.toString() }
        listOf(-1, 4, -2, 6).forEachIndexed { i, n -> s.ensure(1, 1 + i).text = n.toString() }
        s.ensure(0, 0).text = "north"; s.ensure(1, 0).text = "south"
        e.load(wb)
        return e
    }

    @Test
    fun sparklinesGoBesideTheirRowsAndReadTheirValues() {
        val e = editor()
        e.select(0, 1); e.extendSelection(1, 3)             // B1:E2
        e.insertSparklines(SparklineType.COLUMN)
        val sheet = e.sheet
        assertEquals(2, sheet.sparklines.size)
        val first = sheet.sparklineAt(0, 5)                 // F1
        assertNotNull(first)
        assertEquals(SparklineType.COLUMN, first!!.type)
        assertEquals(listOf(0, 1, 0, 4), listOf(first.r1, first.c1, first.r2, first.c2))
        assertEquals(listOf(3.0, 5.0, 2.0, 8.0), e.sparklineValues(first))
        assertEquals(listOf(-1.0, 4.0, -2.0, 6.0), e.sparklineValues(sheet.sparklineAt(1, 5)!!))

        // A single column of data gets one sparkline below it.
        e.select(0, 1); e.extendSelection(1, 0)             // B1:B2
        e.insertSparklines(SparklineType.LINE)
        val below = sheet.sparklineAt(2, 1)
        assertNotNull(below)
        assertEquals(SparklineType.LINE, below!!.type)

        e.undo()
        assertNull(sheet.sparklineAt(2, 1))
        e.undo()
        assertTrue(sheet.sparklines.isEmpty())
        e.redo()
        assertEquals(2, sheet.sparklines.size)

        e.select(0, 5)
        e.clearSparklines()
        assertNull(sheet.sparklineAt(0, 5))
        assertNotNull(sheet.sparklineAt(1, 5))
    }

    @Test
    fun protectionRefusesEditsExceptOnUnlockedCells() {
        val e = editor()
        e.select(0, 1); e.toggleUnlockedSelection()         // B1 stays editable
        e.toggleProtection()
        assertTrue(e.isProtected)
        val before = e.protectionNotice

        e.setCell(0, 2, "99")                               // C1 is locked
        assertEquals("5", e.display(0, 2))
        assertEquals(before + 1, e.protectionNotice)
        e.insertRowAt(0)
        assertEquals("north", e.display(0, 0))              // structure untouched
        assertEquals(before + 2, e.protectionNotice)

        e.setCell(0, 1, "42")                               // B1 is unlocked
        assertEquals("42", e.display(0, 1))
        assertFalse(e.isLocked(0, 1)); assertTrue(e.isLocked(0, 2))

        e.toggleProtection()
        assertFalse(e.isProtected)
        e.setCell(0, 2, "99")
        assertEquals("99", e.display(0, 2))
        e.undo(); e.undo()                                  // the edit, then the unprotect
        assertTrue(e.isProtected)
    }

    @Test
    fun xlsxRoundTripsSparklinesAndProtection() {
        val e = editor()
        e.select(0, 1); e.extendSelection(1, 3)
        e.insertSparklines(SparklineType.WIN_LOSS)
        e.select(1, 0); e.toggleUnlockedSelection()
        e.toggleProtection()
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(e.workbook, it) }.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        val sheetXml = String(parts["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        val stylesXml = String(parts["xl/styles.xml"]!!, Charsets.UTF_8)
        assertTrue("sheetProtection", sheetXml.contains("""<sheetProtection sheet="1""""))
        assertTrue("sparkline group", sheetXml.contains("""<x14:sparklineGroup displayEmptyCellsAs="gap" type="stacked">"""))
        assertTrue("sparkline source", sheetXml.contains("<xm:f>'Sheet1'!B1:E1</xm:f><xm:sqref>F1</xm:sqref>"))
        assertTrue("extLst last", sheetXml.trimEnd().endsWith("</extLst></worksheet>"))
        assertTrue("unlocked xf", stylesXml.contains("applyProtection=\"1\"") && stylesXml.contains("<protection locked=\"0\"/>"))

        val back = XlsxReader.read(bytes).sheets[0]
        assertTrue(back.protected)
        assertEquals(2, back.sparklines.size)
        val f1 = back.sparklineAt(0, 5)!!
        assertEquals(SparklineType.WIN_LOSS, f1.type)
        assertEquals(listOf(0, 1, 0, 4), listOf(f1.r1, f1.c1, f1.r2, f1.c2))
        assertTrue("A2 unlocked", back.cell(1, 0)!!.unlocked)
        assertFalse("A1 locked", back.cell(0, 0)!!.unlocked)
    }

    @Test
    fun odsRoundTripsSparklinesAndProtection() {
        val e = editor()
        e.select(0, 1); e.extendSelection(0, 3)
        e.insertSparklines(SparklineType.LINE)
        e.sheet.sparklines[0].markers = true
        e.select(0, 0); e.toggleUnlockedSelection()
        e.toggleProtection()
        val bytes = ByteArrayOutputStream().also { OdsWriter.write(e.workbook, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertTrue("protected table", xml.contains("""table:protected="true""""))
        assertTrue("calcext sparkline", xml.contains("""<calcext:sparkline calcext:cell-address="Sheet1.F1" calcext:data-range="Sheet1.B1:Sheet1.E1"/>"""))
        assertTrue("cell-protect none", xml.contains("""style:cell-protect="none""""))

        val back = OdsReader.read(bytes).sheets[0]
        assertTrue(back.protected)
        val sp = back.sparklineAt(0, 5)!!
        assertEquals(SparklineType.LINE, sp.type)
        assertTrue(sp.markers)
        assertEquals(listOf(0, 1, 0, 4), listOf(sp.r1, sp.c1, sp.r2, sp.c2))
        assertTrue(back.cell(0, 0)!!.unlocked)
        assertFalse(back.cell(0, 1)!!.unlocked)
    }

    @Test
    fun sparklineCopiesAreIndependent() {
        val s = Sparkline(0, 5, 0, 1, 0, 4, SparklineType.COLUMN, 0xFF112233, markers = true)
        val c = s.copyOf()
        c.type = SparklineType.LINE
        assertEquals(SparklineType.COLUMN, s.type)
        assertEquals(0xFF112233, c.color)
    }
}
