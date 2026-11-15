package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.ValidationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regressions from the 2026-09 review: undo ordering, number-formatted numeric consumers, region shifting. */
class SheetEditorRegressionTest {

    private fun editor(vararg cells: Triple<Int, Int, String>): SheetEditor {
        val e = SheetEditor()
        cells.forEach { (r, c, t) -> e.sheet.ensure(r, c).text = t }
        return e
    }

    @Test fun undoRestoresUnmerge() {
        val e = editor(Triple(0, 0, "x"))
        e.select(0, 0); e.extendSelection(1, 1); e.mergeCells()
        e.unmergeCells()
        assertNull(e.sheet.mergeAt(0, 0))
        e.undo()
        assertNotNull("merge back after undo", e.sheet.mergeAt(0, 0))
    }

    @Test fun undoRestoresClearedRulesValidationsAndNames() {
        val e = editor(Triple(0, 0, "5"))
        e.select(0, 0); e.extendSelection(2, 0)
        e.addCondFormat(CondType.GREATER, 1.0, 0.0, 0xFFFF0000)
        e.addValidation(ValidationType.INTEGER, 0.0, 10.0, emptyList())
        assertTrue(e.defineName("区域"))
        e.clearCondFormats(); e.undo()
        assertEquals(1, e.sheet.condFormats.size)
        e.clearValidations(); e.undo()
        assertEquals(1, e.sheet.validations.size)
        e.deleteName("区域"); e.undo()
        assertTrue("name back", e.workbook.names.containsKey("区域"))
    }

    @Test fun undoRestoresReplaceAll() {
        val e = editor(Triple(0, 0, "foo bar"), Triple(1, 0, "foo"))
        assertEquals(2, e.replaceAll("foo", "X"))
        e.undo()
        assertEquals("foo bar", e.sheet.cell(0, 0)?.text)
        assertEquals("foo", e.sheet.cell(1, 0)?.text)
    }

    @Test fun numberFormattedCellsStillDriveRulesChartsAndPivots() {
        val e = editor(Triple(0, 0, "1500"), Triple(1, 0, "2500"))
        e.select(0, 0); e.extendSelection(1, 0)
        e.setNumberFormat(NumberFormat.THOUSANDS)
        assertEquals("1,500", e.display(0, 0))
        e.addCondFormat(CondType.GREATER, 100.0, 0.0, 0xFFFF0000)
        assertEquals(0xFFFF0000L, e.conditionalFill(0, 0))
        assertEquals("raw value keeps the number", "1500", e.rawValue(0, 0))
        assertTrue(e.insertChartFromSelection(ChartType.COLUMN))
        assertEquals(listOf(1500f, 2500f), e.sheet.charts.single().series.single().values.toList())
        val p = editor(Triple(0, 0, "区域"), Triple(0, 1, "金额"), Triple(1, 0, "北"), Triple(1, 1, "10"), Triple(2, 0, "北"), Triple(2, 1, "20"))
        p.select(1, 1); p.extendSelection(1, 0); p.setNumberFormat(NumberFormat.CURRENCY_CNY)
        p.select(0, 0); p.extendSelection(2, 1)
        assertNotNull(p.buildPivot(0, null, 1, com.therealityteam.aeropages.editor.PivotAgg.SUM))
        assertEquals("30", p.sheet.cell(1, 1)?.text)
    }

    @Test fun insertAndDeleteRowsKeepRegionsAligned() {
        val e = editor(Triple(2, 1, "m"))
        e.select(2, 1); e.extendSelection(1, 1); e.mergeCells() // B3:C4
        e.select(2, 1); e.extendSelection(1, 0)
        e.addCondFormat(CondType.LESS, 0.0, 0.0, 0xFF00FF00)       // B3:B4
        e.addValidation(ValidationType.DECIMAL, 0.0, 1.0, emptyList())
        e.sheet.rowHeights[2] = 2f
        e.insertRowAt(0)
        val m = e.sheet.merges.single()
        assertEquals(3, m.r1); assertEquals(4, m.r2)
        assertEquals(3, e.sheet.condFormats.single().r1)
        assertEquals(4, e.sheet.validations.single().r2)
        assertEquals(2f, e.sheet.rowHeightFactor(3))
        assertEquals("m", e.sheet.cell(3, 1)?.text)
        e.deleteRowAt(0)
        assertEquals(2, e.sheet.merges.single().r1)
        // Deleting a row inside the merge shrinks it to a single row (still 2 columns wide).
        e.deleteRowAt(3)
        assertEquals(2, e.sheet.merges.single().r2)
        assertEquals(2, e.sheet.merges.single().c2)
        e.deleteRowAt(2)
        assertTrue("merge gone with its last row", e.sheet.merges.isEmpty())
    }

    @Test fun insertColumnShiftsColumnRegionsAndWidths() {
        val e = editor(Triple(0, 2, "c"))
        e.select(0, 2); e.extendSelection(0, 1); e.mergeCells() // C1:D1
        e.sheet.colWidths[2] = 1.5f
        e.insertColAt(0)
        assertEquals(3, e.sheet.merges.single().c1)
        assertEquals(1.5f, e.sheet.colWidthFactor(3))
        assertEquals("c", e.sheet.cell(0, 3)?.text)
    }

    @Test fun removeDuplicatesRewritesFormulaRefs() {
        val e = editor(
            Triple(0, 0, "x"), Triple(0, 1, "1"),
            Triple(1, 0, "x"), Triple(1, 1, "1"),
            Triple(2, 0, "y"), Triple(2, 1, "2"),
            Triple(5, 0, "=B3"),
        )
        e.select(0, 0); e.extendSelection(2, 1)
        assertEquals(1, e.removeDuplicates())
        assertEquals("y", e.sheet.cell(1, 0)?.text)
        // The formula below the block stays where it is, following the cell that moved up (Excel deletes within the block).
        assertEquals("=B2", e.sheet.cell(5, 0)?.text)
    }

    @Test fun columnCountIsClampedToTheGridLimit() {
        val e = editor()
        e.select(0, 0)
        e.pasteTsv((0 until 1200).joinToString("\t") { "v$it" })
        assertTrue(e.sheet.colCount <= Sheet.MAX_COLS)
        assertEquals("v999", e.sheet.cell(0, 999)?.text)
        assertNull("nothing aliased into row 1", e.sheet.cell(1, 0))
    }

    @Test fun selectionChangesKeepEvaluatedValuesCached() {
        val e = editor(Triple(0, 0, "2"), Triple(1, 0, "=A1*3"))
        assertEquals("6", e.display(1, 0))
        e.select(3, 3)
        e.sheet.cells[e.sheet.key(0, 0)]!!.text = "5" // silent model edit: no content bump, cache still serves 6
        assertEquals("6", e.display(1, 0))
        e.setCell(0, 0, "5") // a real edit invalidates
        assertEquals("15", e.display(1, 0))
    }
}
