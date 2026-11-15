package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for structural Sheets editing (insert/delete row+col, find/replace, dedupe). */
class SheetEditorTest {

    private fun editor(vararg cells: Triple<Int, Int, String>): SheetEditor {
        val e = SheetEditor()
        cells.forEach { (r, c, t) -> e.sheet.ensure(r, c).text = t }
        return e
    }

    @Test fun insertRowShiftsDown() {
        val e = editor(Triple(0, 0, "a"), Triple(1, 0, "b"))
        e.insertRowAt(0)
        assertNull(e.sheet.cell(0, 0))
        assertEquals("a", e.sheet.cell(1, 0)?.text)
        assertEquals("b", e.sheet.cell(2, 0)?.text)
    }

    @Test fun deleteRowShiftsUp() {
        val e = editor(Triple(0, 0, "a"), Triple(1, 0, "b"), Triple(2, 0, "c"))
        e.deleteRowAt(1)
        assertEquals("a", e.sheet.cell(0, 0)?.text)
        assertEquals("c", e.sheet.cell(1, 0)?.text)
        assertNull(e.sheet.cell(2, 0))
    }

    @Test fun insertAndDeleteColumn() {
        val e = editor(Triple(0, 0, "a"), Triple(0, 1, "b"))
        e.insertColAt(0)
        assertEquals("a", e.sheet.cell(0, 1)?.text)
        assertEquals("b", e.sheet.cell(0, 2)?.text)
        e.deleteColAt(1) // removes the now-"a" column
        assertEquals("b", e.sheet.cell(0, 1)?.text)
    }

    @Test fun replaceAndFind() {
        val e = editor(Triple(0, 0, "foo bar foo"), Triple(1, 0, "baz"))
        assertEquals(2, e.replaceAll("foo", "X"))
        assertEquals("X bar X", e.sheet.cell(0, 0)?.text)
        e.select(0, 0)
        assertEquals(1 to 0, run { e.sheet.ensure(1, 0).text = "find-me"; e.findNext("find") })
    }

    @Test fun conditionalFormatting() {
        val e = editor(Triple(0, 0, "60"), Triple(1, 0, "40"), Triple(2, 0, "100"))
        e.select(0, 0); e.extendSelection(2, 0) // A1:A3
        e.addCondFormat(com.therealityteam.aeropages.model.CondType.GREATER, 50.0, 0.0, 0xFFC6EFCE)
        assertEquals(0xFFC6EFCE, e.conditionalFill(0, 0)) // 60 > 50
        assertNull(e.conditionalFill(1, 0))                // 40 not > 50
        assertEquals(0xFFC6EFCE, e.conditionalFill(2, 0)) // 100 > 50
        assertNull(e.conditionalFill(5, 5))                // outside rule range
        // BETWEEN rule on a fresh editor
        val e2 = editor(Triple(0, 0, "5"), Triple(1, 0, "15"))
        e2.select(0, 0); e2.extendSelection(1, 0)
        e2.addCondFormat(com.therealityteam.aeropages.model.CondType.BETWEEN, 0.0, 10.0, 0xFFFFC7CE)
        assertEquals(0xFFFFC7CE, e2.conditionalFill(0, 0)) // 5 in [0,10]
        assertNull(e2.conditionalFill(1, 0))                // 15 not in [0,10]
        e.clearCondFormats()
        assertNull(e.conditionalFill(0, 0))
    }

    @Test fun bordersAndWrap() {
        val e = editor(Triple(0, 0, "a"), Triple(0, 1, "b"), Triple(1, 0, "c"), Triple(1, 1, "d"))
        e.select(0, 0); e.extendSelection(1, 1) // A1:B2
        e.applyBordersOutside()
        // top-left corner: top + left set, but not bottom/right (interior)
        assertEquals(true, e.sheet.cell(0, 0)?.bTop)
        assertEquals(true, e.sheet.cell(0, 0)?.bLeft)
        assertEquals(false, e.sheet.cell(0, 0)?.bBottom)
        assertEquals(false, e.sheet.cell(0, 0)?.bRight)
        // bottom-right corner: bottom + right set
        assertEquals(true, e.sheet.cell(1, 1)?.bBottom)
        assertEquals(true, e.sheet.cell(1, 1)?.bRight)
        assertEquals(false, e.sheet.cell(1, 1)?.bTop)
        e.applyBordersAll()
        assertEquals(true, e.sheet.cell(0, 0)?.bRight) // all sides now
        e.clearBorders()
        assertEquals(false, e.sheet.cell(0, 0)?.bTop)
        // wrap toggles across the selection
        e.toggleWrap()
        assertEquals(true, e.sheet.cell(1, 1)?.wrap)
    }

    @Test fun mergeAndUnmerge() {
        val e = editor(Triple(0, 0, "标题"))
        e.select(0, 0); e.extendSelection(0, 3) // A1:D1
        e.mergeCells()
        assertTrue(e.isMergedSelection)
        val m = e.sheet.mergeAt(0, 0)
        assertEquals(0, m?.c1); assertEquals(3, m?.c2)
        assertTrue(e.sheet.isMergeCovered(0, 1))      // covered, not anchor
        assertEquals(false, e.sheet.isMergeCovered(0, 0)) // anchor itself not "covered"
        e.toggleMergeCenter() // already merged → unmerge
        assertNull(e.sheet.mergeAt(0, 0))
    }

    @Test fun textToColumnsSplits() {
        val e = editor(Triple(0, 0, "a,b,c"), Triple(1, 0, "d,e"))
        e.select(0, 0); e.extendSelection(1, 0)
        e.textToColumns(",")
        assertEquals("a", e.sheet.cell(0, 0)?.text)
        assertEquals("b", e.sheet.cell(0, 1)?.text)
        assertEquals("c", e.sheet.cell(0, 2)?.text)
        assertEquals("e", e.sheet.cell(1, 1)?.text)
    }

    @Test fun showFormulasToggle() {
        val e = editor(Triple(0, 0, "=1+1"))
        assertEquals("2", e.display(0, 0))
        e.toggleShowFormulas()
        assertEquals("=1+1", e.display(0, 0))
        e.toggleShowFormulas()
        assertEquals("2", e.display(0, 0))
    }

    @Test fun commentsAndStatistics() {
        val e = editor(Triple(0, 0, "10"), Triple(0, 1, "=A1*2"), Triple(1, 0, "text"))
        e.select(0, 0); e.setComment("检查这个值")
        assertEquals("检查这个值", e.activeComment)
        e.clearComment(); assertNull(e.activeComment)
        e.select(1, 0); e.setComment("note")
        // Excel's Workbook Statistics: where the data ends, cells with data, formulas, comments.
        val stats = com.therealityteam.aeropages.model.SheetStats.of(e.sheet)
        assertEquals("B2", stats.end)
        assertEquals(3, stats.cells); assertEquals(1, stats.formulas); assertEquals(1, stats.comments)
    }

    @Test fun colWidthAndRowHeight() {
        val e = editor(Triple(0, 0, "这是一段很长很长很长的文本内容用于自动列宽"))
        e.select(0, 0)
        assertEquals(1f, e.sheet.colWidthFactor(0))
        e.adjustColWidth(+0.5f)
        assertEquals(1.5f, e.sheet.colWidthFactor(0))
        e.adjustColWidth(-2f) // clamps at 0.5
        assertEquals(0.5f, e.sheet.colWidthFactor(0))
        e.adjustRowHeight(+1f)
        assertEquals(2f, e.sheet.rowHeightFactor(0))
        e.autoFitColWidth()
        assertTrue("autofit widens", e.sheet.colWidthFactor(0) > 1f)
        e.resetColRowSize()
        assertEquals(1f, e.sheet.colWidthFactor(0))
        assertEquals(1f, e.sheet.rowHeightFactor(0))
    }

    @Test fun chartFromSelection() {
        // A: categories; B,C: series with header row
        val e = editor(
            Triple(0, 0, "月份"), Triple(0, 1, "销售"), Triple(0, 2, "成本"),
            Triple(1, 0, "一月"), Triple(1, 1, "100"), Triple(1, 2, "60"),
            Triple(2, 0, "二月"), Triple(2, 1, "150"), Triple(2, 2, "80"),
        )
        e.select(0, 0); e.extendSelection(2, 2)
        assertTrue(e.insertChartFromSelection(com.therealityteam.aeropages.model.ChartType.COLUMN))
        val c = e.sheet.charts.single()
        assertEquals(listOf("一月", "二月"), c.categories.toList())
        assertEquals(2, c.series.size)
        assertEquals("销售", c.series[0].name)
        assertEquals(listOf(100f, 150f), c.series[0].values.toList())
        assertEquals("成本", c.series[1].name)
        assertEquals(listOf(60f, 80f), c.series[1].values.toList())
        e.deleteChart(0)
        assertTrue(e.sheet.charts.isEmpty())
        // single cell selection → rejected
        e.select(0, 0)
        assertEquals(false, e.insertChartFromSelection(com.therealityteam.aeropages.model.ChartType.PIE))
    }

    @Test fun removeDuplicateRows() {
        val e = editor(
            Triple(0, 0, "x"), Triple(0, 1, "1"),
            Triple(1, 0, "y"), Triple(1, 1, "2"),
            Triple(2, 0, "x"), Triple(2, 1, "1"), // duplicate of row 0
        )
        e.select(0, 0); e.extendSelection(2, 1) // selection A1:B3
        assertEquals(1, e.removeDuplicates())
        assertEquals("x", e.sheet.cell(0, 0)?.text)
        assertEquals("y", e.sheet.cell(1, 0)?.text)
        assertNull(e.sheet.cell(2, 0)) // the dup row removed, nothing shifted in below it
    }

    @Test fun fillSeriesNumericStep() {
        // Seeds 10, 20 in A1:A2; fill A1:A5 → step 10.
        val e = editor(Triple(0, 0, "10"), Triple(1, 0, "20"))
        e.select(0, 0)
        repeat(4) { e.extendSelection(1, 0) }
        assertTrue(e.fillSeries())
        assertEquals("30", e.sheet.cell(2, 0)?.text)
        assertEquals("40", e.sheet.cell(3, 0)?.text)
        assertEquals("50", e.sheet.cell(4, 0)?.text)
    }

    @Test fun fillSeriesSingleSeedStepsByOne() {
        val e = editor(Triple(0, 1, "5"))
        e.select(0, 1)
        repeat(3) { e.extendSelection(1, 0) }
        assertTrue(e.fillSeries())
        assertEquals("6", e.sheet.cell(1, 1)?.text)
        assertEquals("7", e.sheet.cell(2, 1)?.text)
        assertEquals("8", e.sheet.cell(3, 1)?.text)
    }

    @Test fun fillSeriesHorizontalAndTextFallback() {
        // Wider-than-tall selection fills right; 甲 continues Chinese Excel's stems list, plain text is copied.
        val e = editor(Triple(0, 0, "1"), Triple(0, 1, "3"), Triple(2, 0, "甲"), Triple(3, 0, "苹果"))
        e.select(0, 0)
        repeat(3) { e.extendSelection(0, 1) }
        assertTrue(e.fillSeries())
        assertEquals("5", e.sheet.cell(0, 2)?.text)
        assertEquals("7", e.sheet.cell(0, 3)?.text)
        e.select(2, 0)
        e.extendSelection(1, 2)
        assertTrue(e.fillSeries())
        assertEquals("乙", e.sheet.cell(2, 1)?.text)
        assertEquals("丙", e.sheet.cell(2, 2)?.text)
        assertEquals("苹果", e.sheet.cell(3, 1)?.text)
        assertEquals("苹果", e.sheet.cell(3, 2)?.text)
        // Single cell → rejected.
        e.select(5, 5)
        assertTrue(!e.fillSeries())
    }

    @Test fun insertRowRewritesFormulaRefs() {
        val e = editor(Triple(0, 0, "10"), Triple(4, 0, "=A1+1"), Triple(5, 0, "=SUM(A1:A5)"))
        e.insertRowAt(2) // insert above row index 2 (between A2 and A3)
        assertEquals("=A1+1", e.sheet.cell(5, 0)?.text)          // A1 < insert point: unchanged
        assertEquals("=SUM(A1:A6)", e.sheet.cell(6, 0)?.text)    // range end slides down
    }

    @Test fun deleteRowRewritesAndRefs() {
        val e = editor(
            Triple(0, 0, "1"), Triple(1, 0, "2"), Triple(2, 0, "3"),
            Triple(5, 0, "=A3"), Triple(6, 0, "=SUM(A1:A3)"), Triple(7, 0, "=A2"),
        )
        e.deleteRowAt(1) // delete the "2" row (0-based index 1)
        assertEquals("=A2", e.sheet.cell(4, 0)?.text)            // A3 → A2
        assertEquals("=SUM(A1:A2)", e.sheet.cell(5, 0)?.text)    // range shrinks
        assertEquals("=#REF!", e.sheet.cell(6, 0)?.text)         // deleted cell ref
    }

    @Test fun insertColRewritesRefsAndKeepsFunctions() {
        val e = editor(Triple(0, 0, "1"), Triple(0, 5, "=B1+LOG10(100)"), Triple(1, 5, "=SUM(A1:C1)"))
        e.insertColAt(1) // insert before column B
        assertEquals("=C1+LOG10(100)", e.sheet.cell(0, 6)?.text) // B1→C1, LOG10( untouched
        assertEquals("=SUM(A1:D1)", e.sheet.cell(1, 6)?.text)
    }

    @Test fun crossSheetRefsRewriteOnlyTargetSheet() {
        val e = editor(Triple(0, 0, "1"))
        e.workbook.sheets.add(com.therealityteam.aeropages.model.Sheet(name = "Other"))
        val other = e.workbook.sheets[1]
        other.ensure(0, 0).text = "=Sheet1!A3"     // refers to the ACTIVE sheet
        other.ensure(1, 0).text = "=A3"            // local to Other — must not shift
        e.sheet.ensure(0, 1).text = "=Other!A3"    // refers to ANOTHER sheet — must not shift
        e.insertRowAt(0)
        assertEquals("=Sheet1!A4", other.cell(0, 0)?.text)
        assertEquals("=A3", other.cell(1, 0)?.text)
        assertEquals("=Other!A3", e.sheet.cell(1, 1)?.text)
    }

    @Test fun dataBarAndColorScale() {
        val e = editor(Triple(0, 0, "0"), Triple(1, 0, "50"), Triple(2, 0, "100"))
        e.select(0, 0); repeat(2) { e.extendSelection(1, 0) }
        e.addCondFormat(com.therealityteam.aeropages.model.CondType.DATA_BAR, 0.0, 0.0, 0xFF638EC6L)
        // Bar fractions follow the range min..max.
        assertEquals(0.0f, e.conditionalBar(0, 0)!!.first, 1e-4f)
        assertEquals(0.5f, e.conditionalBar(1, 0)!!.first, 1e-4f)
        assertEquals(1.0f, e.conditionalBar(2, 0)!!.first, 1e-4f)
        assertNull("data bar leaves fill to the bar layer", e.conditionalFill(1, 0))
        assertNull("outside the rule", e.conditionalBar(3, 0))

        // Color scale: white at min, full colour at max, midpoint in between.
        val e2 = editor(Triple(0, 1, "0"), Triple(1, 1, "100"))
        e2.select(0, 1); e2.extendSelection(1, 0)
        e2.addCondFormat(com.therealityteam.aeropages.model.CondType.COLOR_SCALE, 0.0, 0.0, 0xFF63C384L)
        assertEquals(0xFFFFFFFFL, e2.conditionalFill(0, 1))
        assertEquals(0xFF63C384L, e2.conditionalFill(1, 1))
    }

    @Test fun pasteAdjustsRelativeRefs() {
        // C1 = "=A1+B1" copied from (0,2), pasted at (2,3): refs shift by (+2,+1).
        val e = editor(Triple(0, 0, "1"), Triple(0, 1, "2"), Triple(0, 2, "=A1+B1"))
        e.select(0, 2); e.copySelection()
        e.select(2, 3); e.pasteSelection()
        assertEquals("=B3+C3", e.sheet.cell(2, 3)?.text)
    }

    @Test fun pasteKeepsAbsoluteAnchors() {
        val e = editor(Triple(0, 2, "=${'$'}A${'$'}1+A${'$'}1+${'$'}A1+SUM(A1:B2)"))
        e.select(0, 2); e.copySelection()
        e.select(1, 3); e.pasteSelection()
        // $A$1 fixed; A$1 col shifts only; $A1 row shifts only; range shifts both ends.
        assertEquals("=${'$'}A${'$'}1+B${'$'}1+${'$'}A2+SUM(B2:C3)", e.sheet.cell(1, 3)?.text)
    }

    @Test fun pasteOutOfBoundsBecomesRef() {
        val e = editor(Triple(1, 1, "=A1"))
        e.select(1, 1); e.copySelection()
        e.select(0, 0); e.pasteSelection() // shift (-1,-1): A1 → out of grid
        assertEquals("=#REF!", e.sheet.cell(0, 0)?.text)
    }

    @Test fun cutPasteKeepsRefsVerbatim() {
        val e = editor(Triple(0, 0, "10"), Triple(0, 2, "=A1*2"))
        e.select(0, 2); e.cutSelection()
        e.select(3, 3); e.pasteSelection()
        assertEquals("formula still points at original data", "=A1*2", e.sheet.cell(3, 3)?.text)
        assertNull("source cleared by cut", e.sheet.cell(0, 2))
    }

    @Test fun displayCacheInvalidatesOnEdit() {
        val e = editor(Triple(0, 0, "10"), Triple(1, 0, "=A1*2"))
        assertEquals("20", e.display(1, 0))
        assertEquals("cached value stable", "20", e.display(1, 0))
        e.setCell(0, 0, "50") // edit bumps revision → cache cleared
        assertEquals("100", e.display(1, 0))
        // Chained formulas re-evaluate correctly after undo too.
        e.undo()
        assertEquals("20", e.display(1, 0))
    }

    @Test fun displayCachePerformanceSmoke() {
        // 200 rows of chained formulas: a second full pass must hit the cache (no blowup).
        val e = editor(Triple(0, 0, "1"))
        for (r in 1 until 200) e.sheet.ensure(r, 0).text = "=A$r+1"
        val t0 = System.nanoTime()
        for (r in 0 until 200) e.display(r, 0)
        val coldMs = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        for (r in 0 until 200) e.display(r, 0)
        val warmMs = (System.nanoTime() - t1) / 1_000_000
        assertEquals("200", e.display(199, 0))
        assertTrue("warm pass ($warmMs ms) much faster than cold ($coldMs ms)", warmMs <= coldMs)
    }

    @Test fun autoFilterByValues() {
        val e = editor(
            Triple(0, 0, "城市"), Triple(1, 0, "北京"), Triple(2, 0, "上海"), Triple(3, 0, "北京"), Triple(4, 0, "广州"),
        )
        e.select(0, 0)
        assertEquals(listOf("上海", "北京", "广州"), e.columnValues(0))
        e.filterByValues(0, setOf("北京"))
        assertTrue("non-matching rows hidden", e.hiddenRows.containsAll(listOf(2, 4)))
        assertTrue("matching rows visible", !e.hiddenRows.contains(1) && !e.hiddenRows.contains(3))
        assertTrue("header row always visible", !e.hiddenRows.contains(0))
        e.clearFilter()
        assertTrue(e.hiddenRows.isEmpty())
    }

    @Test fun multiKeySort() {
        // (group, score): sort by group asc then score asc.
        val e = editor(
            Triple(0, 0, "B"), Triple(0, 1, "2"),
            Triple(1, 0, "A"), Triple(1, 1, "9"),
            Triple(2, 0, "B"), Triple(2, 1, "1"),
            Triple(3, 0, "A"), Triple(3, 1, "3"),
        )
        e.select(0, 0); e.extendSelection(3, 0); e.extendSelection(0, 1)
        e.sortByColumns(primaryCol = 0, secondaryCol = 1, ascending = true)
        assertEquals(listOf("A" to "3", "A" to "9", "B" to "1", "B" to "2"),
            (0..3).map { r -> (e.sheet.cell(r, 0)?.text ?: "") to (e.sheet.cell(r, 1)?.text ?: "") })
    }

    @Test fun numberFormatsAndDecimals() {
        val e = editor(Triple(0, 0, "1234.5678"), Triple(1, 0, "0.5"), Triple(2, 0, "45449.75"))
        e.select(0, 0)
        e.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.SCIENTIFIC)
        assertEquals("1.23E+03", e.display(0, 0))
        e.setDecimals(1)
        assertEquals("1.2E+03", e.display(0, 0))
        // 增加/减少小数位 over a plain number starts from NUMBER2.
        e.select(1, 0)
        e.adjustDecimals(+1)
        assertEquals("0.500", e.display(1, 0)) // default 2 + 1 = 3 places
        // TIME/DATE_TIME from an Excel serial (45449.75 = 2024-06-06 18:00).
        e.select(2, 0)
        e.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.TIME)
        assertEquals("18:00", e.display(2, 0))
        e.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.DATE_TIME)
        assertTrue(e.display(2, 0).endsWith("18:00"))
    }

    @Test fun decimalsRoundTripXlsx() {
        val e = editor(Triple(0, 0, "1234.5678"), Triple(1, 0, "0.25"), Triple(2, 0, "45449.75"))
        e.select(0, 0); e.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.THOUSANDS); e.setDecimals(3)
        e.select(1, 0); e.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.PERCENT); e.setDecimals(2)
        e.select(2, 0); e.setNumberFormat(com.therealityteam.aeropages.model.NumberFormat.TIME)
        val bos = java.io.ByteArrayOutputStream()
        com.therealityteam.aeropages.format.xlsx.XlsxWriter.write(e.workbook, bos)
        val back = com.therealityteam.aeropages.format.xlsx.XlsxReader.read(bos.toByteArray())
        val s = back.sheets[0]
        assertEquals(com.therealityteam.aeropages.model.NumberFormat.THOUSANDS, s.cell(0, 0)?.numberFormat)
        assertEquals(3, s.cell(0, 0)?.decimals)
        assertEquals(com.therealityteam.aeropages.model.NumberFormat.PERCENT, s.cell(1, 0)?.numberFormat)
        assertEquals(2, s.cell(1, 0)?.decimals)
        assertEquals(com.therealityteam.aeropages.model.NumberFormat.TIME, s.cell(2, 0)?.numberFormat)
    }

    @Test fun tsvExportAndImport() {
        val e = editor(Triple(0, 0, "名称"), Triple(0, 1, "数量"), Triple(1, 0, "苹果"), Triple(1, 1, "=1+2"))
        e.select(0, 0); e.extendSelection(1, 0); e.extendSelection(0, 1)
        assertEquals("名称\t数量\n苹果\t3", e.selectionTsv()) // formulas export evaluated
        // Import external TSV at C1.
        e.select(0, 3)
        e.pasteTsv("甲\t乙\n丙\t丁")
        assertEquals("甲", e.sheet.cell(0, 3)?.text)
        assertEquals("丁", e.sheet.cell(1, 4)?.text)
    }
}
