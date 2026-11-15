package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Excel's automatic row height: rows fit their largest type and wrapped lines unless given a height of their own. */
class RowFitTest {

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }

    @Test
    fun largerTypeRaisesItsRowAndUndoGivesItBack() {
        val e = editor()
        e.setCell(0, 0, "Quarterly report")
        e.select(0, 0)
        e.setFontSize(24f)
        assertEquals("24-point type needs twice the default row", 2f, e.sheet.rowHeightFactor(0), 0.01f)
        assertTrue("a fitted height is no height of its own", e.sheet.rowHeights.isEmpty())
        e.setFontSize(9f)
        assertEquals("smaller type gives the height back", 1f, e.sheet.rowHeightFactor(0), 0f)
        e.setFontSize(36f)
        e.undo()
        assertEquals("undo takes the row back with the type", 1f, e.sheet.rowHeightFactor(0), 0f)
    }

    @Test
    fun wrappingTextGrowsItsRowAndAWiderColumnShrinksIt() {
        val e = editor()
        e.setCell(1, 0, "a long sentence that wraps inside a default-width cell several times over")
        e.select(1, 0)
        e.toggleWrap()
        val tall = e.sheet.rowHeightFactor(1)
        assertTrue("several lines: $tall", tall >= 3f)
        e.beginColResize(0); e.dragColWidth(3f); e.endResize()
        assertTrue("a wider column needs fewer lines", e.sheet.rowHeightFactor(1) < tall)
        e.setCell(1, 0, "short")
        assertEquals(1f, e.sheet.rowHeightFactor(1), 0f)
    }

    @Test
    fun aRowGivenAHeightKeepsItUntilAutoFit() {
        val e = editor()
        e.setCell(0, 0, "x")
        e.beginRowResize(0); e.dragRowHeight(0.5f); e.endResize()
        assertEquals(1.5f, e.sheet.rowHeightFactor(0), 0.001f)
        e.select(0, 0); e.setFontSize(28f)
        assertEquals("a height the row was given stays", 1.5f, e.sheet.rowHeightFactor(0), 0.001f)
        e.autoFitRows(0..0)
        assertFalse(0 in e.sheet.rowHeights)
        assertEquals("AutoFit hands the row back to its content", 28f / 12f, e.sheet.rowHeightFactor(0), 0.01f)
        e.undo()
        assertEquals(1.5f, e.sheet.rowHeightFactor(0), 0.001f)
    }

    @Test
    fun mixedTypeCountsItsLargestRun() {
        val e = editor()
        e.setCell(2, 1, "Big small")
        e.sheet.cell(2, 1)!!.runs = mutableListOf(
            com.therealityteam.aeropages.model.TextRun("Big ", com.therealityteam.aeropages.model.CharacterFormat(fontSizePt = 30f)),
            com.therealityteam.aeropages.model.TextRun("small", com.therealityteam.aeropages.model.CharacterFormat()),
        )
        e.autoFitRows(2..2)
        assertEquals(2.5f, e.sheet.rowHeightFactor(2), 0.01f)
    }

    @Test
    fun filesKeepGivenAndFittedHeightsApart() {
        val e = editor()
        e.setCell(0, 0, "Title"); e.select(0, 0); e.setFontSize(24f)
        e.setCell(1, 0, "given")
        e.beginRowResize(1); e.dragRowHeight(1f); e.endResize()
        e.beginRowResize(5); e.dragRowHeight(0.5f); e.endResize()   // an empty row with a height of its own
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(e.workbook, bos)
        val xml = String(OfficeXml.unzip(bos.toByteArray())["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        assertTrue("a fitted height is cached, not custom", xml.contains("""<row r="1" ht="30.00">"""))
        assertTrue(xml.contains("""<row r="2" ht="30.00" customHeight="1">"""))
        assertTrue("an empty row keeps its height", xml.contains("""<row r="6" ht="22.50" customHeight="1">"""))
        val back = SheetEditor().apply { load(XlsxReader.read(bos.toByteArray())) }
        assertNull("read back, row 1 fits its type again", back.sheet.rowHeights[0])
        assertEquals(2f, back.sheet.rowHeightFactor(0), 0.01f)
        assertEquals(2f, back.sheet.rowHeights[1]!!, 0.01f)
        assertEquals(1.5f, back.sheet.rowHeights[5]!!, 0.01f)
        // ODS says the same with use-optimal-row-height.
        val ods = ByteArrayOutputStream()
        OdsWriter.write(e.workbook, ods)
        val content = String(OfficeXml.unzip(ods.toByteArray())["content.xml"]!!, Charsets.UTF_8)
        assertTrue(content.contains("""style:use-optimal-row-height="true""""))
        assertTrue(content.contains("""style:use-optimal-row-height="false""""))
        val odsBack = SheetEditor().apply { load(OdsReader.read(ods.toByteArray())) }
        assertNull(odsBack.sheet.rowHeights[0])
        assertEquals(2f, odsBack.sheet.rowHeightFactor(0), 0.01f)
        assertEquals(2f, odsBack.sheet.rowHeights[1]!!, 0.01f)
    }
}
