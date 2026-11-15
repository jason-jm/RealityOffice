package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Rows hidden by hand (apart from a filter's), and whole rows / columns selected by tapping their headers. */
class RowHideAndHeaderSelectTest {

    private fun editor(rows: Int = 10): SheetEditor = SheetEditor().apply {
        load(Workbook.blank())
        setCell(0, 0, "Fruit")
        for (r in 1 until rows) setCell(r, 0, if (r % 2 == 0) "apple" else "pear")
    }
    private fun SheetEditor.selectRowRange(r1: Int, r2: Int) { select(r1, 0); extendSelection(r2 - r1, 0) }

    private fun part(bytes: ByteArray, name: String): String {
        val z = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        error("no $name")
    }

    @Test
    fun hideAndUnhideRowsLikeExcel() {
        val e = editor()
        e.selectRowRange(2, 3); assertTrue(e.hideRows())
        assertEquals(setOf(2, 3), e.invisibleRows)
        assertFalse("hiding by hand is not a filter", e.hasFilter)
        assertEquals("the active cell left the hidden rows", 4, e.selRow)
        e.select(1, 0); e.moveSelection(1, 0)
        assertEquals("arrow keys step over hidden rows", 4, e.selRow)
        e.selectRowRange(1, 4); assertTrue(e.unhideRows())
        assertTrue(e.sheet.manualHiddenRows.isEmpty())
        // The run next to the selection comes back when nothing is hidden inside it.
        e.selectRowRange(5, 6); e.hideRows()
        e.select(4, 0); assertTrue(e.unhideRows())
        assertFalse(e.unhideRows())
        e.selectRowRange(0, e.sheet.rowCount - 1)
        assertFalse("every row", e.hideRows())
        e.selectRowRange(8, 8); e.hideRows(); e.undo()
        assertTrue(e.sheet.manualHiddenRows.isEmpty())
    }

    @Test
    fun aFilterAndRowsHiddenByHandStayApart() {
        val e = editor()
        e.selectRowRange(1, 1); e.hideRows()                            // row 2 (pear) hidden by hand
        e.filterByValues(0, setOf("apple", "Fruit"))                    // hide every pear
        assertTrue(e.hasFilter)
        assertTrue(1 in e.invisibleRows && 3 in e.invisibleRows)
        e.clearFilter()
        assertFalse(e.hasFilter)
        assertEquals("clearing the filter keeps rows hidden by hand", setOf(1), e.invisibleRows)
    }

    @Test
    fun insertingAndDeletingRowsMovesRowsHiddenByHand() {
        val e = editor()
        e.selectRowRange(4, 5); e.hideRows()
        e.insertRowAt(1)
        assertEquals(setOf(5, 6), e.sheet.manualHiddenRows)
        e.deleteRowAt(0)
        assertEquals(setOf(4, 5), e.sheet.manualHiddenRows)
    }

    @Test
    fun tappingHeadersSelectsWholeColumnsRowsOrTheSheet() {
        val e = editor()
        e.selectColumns(2)
        assertTrue(e.wholeColumnsSelected); assertFalse(e.wholeRowsSelected)
        assertEquals(0, e.rangeRowMin()); assertEquals(e.sheet.rowCount - 1, e.rangeRowMax()); assertEquals(2, e.rangeColMin())
        assertTrue("a whole column hides like any selection", e.hideColumns())
        assertEquals(setOf(2), e.sheet.hiddenCols)
        e.selectRows(3, 4)
        assertTrue(e.wholeRowsSelected)
        assertEquals(3, e.rangeRowMin()); assertEquals(4, e.rangeRowMax())
        e.selectAll()
        assertTrue(e.wholeRowsSelected && e.wholeColumnsSelected)
    }

    @Test
    fun excelTellsRowsHiddenByHandFromFilteredOnes() {
        val manual = editor().apply { selectRowRange(2, 3); hideRows() }
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(manual.workbook, bos)
        val xml = part(bos.toByteArray(), "xl/worksheets/sheet1.xml")
        assertTrue(xml.contains("""<row r="3" hidden="1""""))
        assertFalse("no filter was written", xml.contains("<autoFilter"))
        val back = XlsxReader.read(bos.toByteArray()).sheets[0]
        assertEquals(setOf(2, 3), back.manualHiddenRows)
        assertTrue(back.hiddenRows.isEmpty())

        val filtered = editor().apply { filterByValues(0, setOf("apple", "Fruit")) }
        val bos2 = ByteArrayOutputStream()
        XlsxWriter.write(filtered.workbook, bos2)
        val back2 = XlsxReader.read(bos2.toByteArray()).sheets[0]
        assertTrue("the autoFilter keeps them filtered", back2.hiddenRows.isNotEmpty())
        assertTrue(back2.manualHiddenRows.isEmpty())
    }

    @Test
    fun openDocumentTellsRowsHiddenByHandFromFilteredOnes() {
        val e = editor()
        e.selectRowRange(2, 2); e.hideRows()
        e.filterByValues(0, setOf("apple", "Fruit"))                    // pears filtered; row 3 (an apple) is hidden by hand
        val bos = ByteArrayOutputStream()
        OdsWriter.write(e.workbook, bos)
        val xml = part(bos.toByteArray(), "content.xml")
        assertTrue(xml.contains("""table:visibility="filter""""))
        assertTrue(xml.contains("""table:visibility="collapse""""))
        val back = OdsReader.read(bos.toByteArray()).sheets[0]
        assertEquals(setOf(2), back.manualHiddenRows)
        assertEquals(e.sheet.hiddenRows, back.hiddenRows)
    }
}
