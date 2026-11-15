package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Filtered-out rows live on the sheet: undoable, shifted with row edits, saved as hidden rows + autoFilter. */
class FilterPersistenceTest {

    private fun editor(): SheetEditor {
        val e = SheetEditor()
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        listOf("City", "Beijing", "Shanghai", "Beijing", "Shenzhen").forEachIndexed { r, t -> s.ensure(r, 0).text = t }
        listOf("Sales", "10", "20", "30", "40").forEachIndexed { r, t -> s.ensure(r, 1).text = t }
        e.load(wb)
        return e
    }

    @Test
    fun filterHidesRowsUndoRestoresAndRowsShift() {
        val e = editor()
        e.filterByValues(0, setOf("Beijing"))
        assertEquals(setOf(2, 4), e.hiddenRows)
        assertTrue(e.hasFilter)
        e.select(0, 0); e.insertRowAt(0)
        assertEquals(setOf(3, 5), e.hiddenRows)
        e.undo()
        assertEquals(setOf(2, 4), e.hiddenRows)
        e.undo()
        assertFalse(e.hasFilter)
        e.redo()
        assertEquals(setOf(2, 4), e.hiddenRows)
        e.clearFilter()
        assertFalse(e.hasFilter)
    }

    @Test
    fun hiddenRowsRoundTripThroughXlsx() {
        val e = editor()
        e.filterByValues(0, setOf("Beijing"))
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(e.workbook, bos)
        val bytes = bos.toByteArray()
        val sheetXml = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        assertTrue(sheetXml.contains("""<row r="3" hidden="1">"""))
        assertTrue(sheetXml.contains("""<sheetPr filterMode="1"/>"""))
        assertTrue("the criteria go with the filter", sheetXml.contains("""<autoFilter ref="A1:B5"><filterColumn colId="0"><filters><filter val="Beijing"/></filters></filterColumn></autoFilter>"""))
        val workbookXml = String(OfficeXml.unzip(bytes)["xl/workbook.xml"]!!, Charsets.UTF_8)
        assertTrue(workbookXml.contains("_xlnm._FilterDatabase"))
        val back = XlsxReader.read(bytes)
        assertEquals(setOf(2, 4), back.sheets[0].hiddenRows)
        assertTrue(back.names.isEmpty()) // the built-in filter name is not a user name
    }
}
