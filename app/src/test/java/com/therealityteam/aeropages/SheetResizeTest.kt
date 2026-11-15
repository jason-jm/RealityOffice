package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Resizing columns and rows: dragging a header edge (one undo step), double-tap auto-fit, CJK-aware widths. */
class SheetResizeTest {

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }

    @Test
    fun cjkTextMeasuresAboutTwiceAsWideAsLatin() {
        assertEquals(4 * 0.55f * 12f, SheetEditor.fitWidthDp("abcd", 12f), 0.001f)
        assertEquals(2 * 12f, SheetEditor.fitWidthDp("中文", 12f), 0.001f)
        assertEquals("font size scales it", 2 * SheetEditor.fitWidthDp("abcd", 12f), SheetEditor.fitWidthDp("abcd", 24f), 0.001f)
    }

    @Test
    fun autoFitSizesColumnsToTheirWidestText() {
        val e = editor()
        e.setCell(0, 0, "short")
        e.setCell(1, 0, "a considerably longer label in column A")
        e.setCell(0, 1, "季度营业收入与成本分析报告")                  // 13 CJK characters
        e.autoFitColumns(0..2)
        // Latin: 39 chars × 0.55 × 12 + 14 padding ≈ 271 dp ≈ 3.08 default widths.
        assertEquals((39 * 0.55f * 12f + 14f) / 88f, e.sheet.colWidthFactor(0), 0.01f)
        // CJK: 13 × 12 + 14 = 170 dp ≈ 1.93 — the old character count (13 / 8) gave 1.6, too narrow.
        assertEquals((13 * 12f + 14f) / 88f, e.sheet.colWidthFactor(1), 0.01f)
        assertTrue("an empty column goes back to the default", 2 !in e.sheet.colWidths)
        e.undo()
        assertEquals(1f, e.sheet.colWidthFactor(0), 0f)
    }

    @Test
    fun autoFitRowsFollowsTallFontsAndWrappedText() {
        val e = editor()
        e.setCell(0, 0, "Big")
        e.select(0, 0)
        e.sheet.cell(0, 0)!!.format.fontSizePt = 24f
        e.setCell(1, 0, "a long sentence that wraps inside a default-width cell several times over")
        e.sheet.cell(1, 0)!!.wrap = true
        e.autoFitRows(0..2)
        assertEquals(2f, e.sheet.rowHeightFactor(0), 0.01f)
        assertTrue("wrapped text gets several lines", e.sheet.rowHeightFactor(1) >= 3f)
        assertEquals(1f, e.sheet.rowHeightFactor(2), 0f)
    }

    @Test
    fun draggingAColumnEdgeIsOneUndoStep() {
        val e = editor()
        e.beginColResize(1)
        e.dragColWidth(0.25f); e.dragColWidth(0.25f); e.dragColWidth(0.5f)
        e.endResize()
        assertEquals(2f, e.sheet.colWidthFactor(1), 0.001f)
        e.undo()
        assertEquals("the whole drag undoes at once", 1f, e.sheet.colWidthFactor(1), 0f)
        // Limits: never thinner than a quarter, never wider than six default widths.
        e.beginColResize(1); e.dragColWidth(-5f); e.endResize()
        assertEquals(SheetEditor.MIN_COL_FACTOR, e.sheet.colWidthFactor(1), 0f)
        e.beginColResize(1); e.dragColWidth(50f); e.endResize()
        assertEquals(SheetEditor.MAX_COL_FACTOR, e.sheet.colWidthFactor(1), 0f)
    }

    @Test
    fun severalWholeColumnsOrRowsResizeTogether() {
        val e = editor()
        e.selectColumns(1, 3)
        e.beginColResize(2); e.dragColWidth(0.5f); e.endResize()
        assertEquals(listOf(1.5f, 1.5f, 1.5f), (1..3).map { e.sheet.colWidthFactor(it) })
        assertEquals("outside the selection", 1f, e.sheet.colWidthFactor(4), 0f)
        // A column outside the whole-column selection resizes alone.
        e.beginColResize(5); e.dragColWidth(1f); e.endResize()
        assertEquals(2f, e.sheet.colWidthFactor(5), 0f)
        assertEquals(1.5f, e.sheet.colWidthFactor(2), 0f)
        e.selectRows(4, 5)
        e.beginRowResize(5); e.dragRowHeight(1f); e.endResize()
        assertEquals(listOf(2f, 2f), (4..5).map { e.sheet.rowHeightFactor(it) })
    }
}
