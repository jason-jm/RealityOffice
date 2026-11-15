package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.PivotAgg
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.Texts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pivot-table builder (数据透视表) + selection statistics (状态栏聚合). */
class PivotTest {
    private fun pivotName(n: Int) = Texts.get(TextId.PIVOT_SHEET_N, n)
    private val total get() = Texts.get(TextId.TOTAL)


    /** 销售表: 区域 / 产品 / 销量, selection covers the whole table incl. header. */
    private fun salesEditor(): SheetEditor {
        val e = SheetEditor()
        val rows = listOf(
            listOf("区域", "产品", "销量"),
            listOf("华北", "苹果", "10"),
            listOf("华北", "香蕉", "20"),
            listOf("华南", "苹果", "30"),
            listOf("华南", "香蕉", "40"),
            listOf("华南", "苹果", "5"),
        )
        rows.forEachIndexed { r, cols -> cols.forEachIndexed { c, t -> e.sheet.ensure(r, c).text = t } }
        e.select(0, 0)
        e.extendSelection(5, 2)
        return e
    }

    @Test fun pivotRowsOnly() {
        val e = salesEditor()
        val name = e.buildPivot(rowField = 0, colField = null, valueField = 2, agg = PivotAgg.SUM)
        assertEquals(pivotName(1), name)
        val s = e.sheet // editor switched to the new pivot sheet
        assertEquals(pivotName(1), s.name)
        assertEquals("区域", s.cell(0, 0)?.text)
        assertEquals(PivotAgg.SUM.display + ":销量", s.cell(0, 1)?.text)
        assertEquals("华北", s.cell(1, 0)?.text)
        assertEquals("30", s.cell(1, 1)?.text)
        assertEquals("华南", s.cell(2, 0)?.text)
        assertEquals("75", s.cell(2, 1)?.text)
        assertEquals(total, s.cell(3, 0)?.text)
        assertEquals("105", s.cell(3, 1)?.text)
        assertEquals(true, s.cell(0, 0)?.format?.bold)
    }

    @Test fun pivotRowsAndColumns() {
        val e = salesEditor()
        e.buildPivot(rowField = 0, colField = 1, valueField = 2, agg = PivotAgg.SUM)
        val s = e.sheet
        // Header: 区域 | 苹果 | 香蕉 | 总计
        assertEquals("苹果", s.cell(0, 1)?.text)
        assertEquals("香蕉", s.cell(0, 2)?.text)
        assertEquals(total, s.cell(0, 3)?.text)
        // 华北: 苹果 10, 香蕉 20, 总计 30
        assertEquals("10", s.cell(1, 1)?.text)
        assertEquals("20", s.cell(1, 2)?.text)
        assertEquals("30", s.cell(1, 3)?.text)
        // 华南: 苹果 30+5=35, 香蕉 40, 总计 75
        assertEquals("35", s.cell(2, 1)?.text)
        assertEquals("40", s.cell(2, 2)?.text)
        assertEquals("75", s.cell(2, 3)?.text)
        // 总计行: 45 / 60 / 105
        assertEquals("45", s.cell(3, 1)?.text)
        assertEquals("60", s.cell(3, 2)?.text)
        assertEquals("105", s.cell(3, 3)?.text)
    }

    @Test fun pivotAverageAndCount() {
        val e = salesEditor()
        e.buildPivot(rowField = 0, colField = null, valueField = 2, agg = PivotAgg.AVERAGE)
        var s = e.sheet
        assertEquals("15", s.cell(1, 1)?.text)  // 华北 (10+20)/2
        assertEquals("25", s.cell(2, 1)?.text)  // 华南 (30+40+5)/3
        assertEquals("21", s.cell(3, 1)?.text)  // 全部 105/5

        val e2 = salesEditor()
        e2.buildPivot(rowField = 0, colField = null, valueField = 1, agg = PivotAgg.COUNT)
        s = e2.sheet
        assertEquals("2", s.cell(1, 1)?.text)   // 华北 两条记录
        assertEquals("3", s.cell(2, 1)?.text)   // 华南 三条
        assertEquals("5", s.cell(3, 1)?.text)
    }

    @Test fun pivotNamesAreUniqueAndUndoable() {
        val e = salesEditor()
        assertEquals(pivotName(1), e.buildPivot(0, null, 2, PivotAgg.SUM))
        // Re-select the source table on sheet 0 and build a second pivot.
        e.selectSheet(0)
        e.select(0, 0); e.extendSelection(5, 2)
        assertEquals(pivotName(2), e.buildPivot(0, null, 2, PivotAgg.MAX))
        assertEquals(3, e.workbook.sheets.size)
        e.undo()
        assertEquals(2, e.workbook.sheets.size)
    }

    @Test fun pivotRejectsHeaderOnlySelection() {
        val e = SheetEditor()
        e.sheet.ensure(0, 0).text = "标题"
        e.select(0, 0)
        assertNull(e.buildPivot(0, null, 0, PivotAgg.SUM))
    }
}
