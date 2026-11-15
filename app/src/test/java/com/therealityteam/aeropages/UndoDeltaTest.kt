package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SlideTransition
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delta-based undo (UndoHistory.kt): steps store only what changed, undo/redo round-trip,
 * coalescing stays scoped. The Gradle test task also sets `aero.verifyUndo`, so every
 * Sheets/Slides mutation in the whole suite is dry-run against a full snapshot.
 */
class UndoDeltaTest {

    // --- Pages -----------------------------------------------------------------

    private fun bigDoc(n: Int): Document {
        val d = Document(mutableListOf())
        for (i in 0 until n) d.blocks.add(ParagraphBlock(Paragraph.of("paragraph $i")))
        return d
    }

    @Test
    fun documentTypingStoresOneParagraphNotTheDocument() {
        val e = DocumentEditor(); e.load(bigDoc(300))
        e.setSelection(120, 0, 0)
        e.replaceText(120, "paragraph 120", "paragraph 120 edited")
        e.replaceText(120, "paragraph 120 edited", "paragraph 120 edited more")
        assertTrue(e.canUndo)
        e.toggleBold() // caret only: arms a pending format, no document change
        e.setAlign(com.therealityteam.aeropages.model.TextAlign.CENTER) // a command: closes the typing session
        assertEquals(2, e.undoDepth)
        assertEquals(1, e.lastUndoBlockCount())
        e.undo()
        assertEquals(com.therealityteam.aeropages.model.TextAlign.LEFT, e.document.paragraphs()[120].format.align)
        assertEquals("paragraph 120 edited more", e.document.paragraphs()[120].plainText())
        e.undo()
        assertEquals("paragraph 120", e.document.paragraphs()[120].plainText())
        assertEquals(120, e.active)
        assertFalse(e.canUndo)
        e.redo(); e.redo()
        assertEquals("paragraph 120 edited more", e.document.paragraphs()[120].plainText())
        assertEquals(com.therealityteam.aeropages.model.TextAlign.CENTER, e.document.paragraphs()[120].format.align)
        assertEquals(300, e.document.blocks.size)
    }

    @Test
    fun documentStructureAndMetadataRoundTrip() {
        val e = DocumentEditor(); e.load(bigDoc(5))
        e.setSelection(2, 3, 3)
        e.splitParagraph(2, 3)
        assertEquals(6, e.document.blocks.size)
        e.insertTable(2, 2)
        e.setPageColor(0xFFEEEEEE)
        e.addHeader()
        e.addComment("note")
        assertEquals(5, e.undoDepth)
        e.undo(); assertTrue(e.document.comments.isEmpty())
        e.undo(); assertNull(e.document.header)
        e.undo(); assertNull(e.document.pageColor)
        e.undo(); assertEquals(6, e.document.blocks.size)
        e.undo(); assertEquals(5, e.document.blocks.size)
        assertEquals("paragraph 2", e.document.paragraphs()[2].plainText())
        assertEquals(2, e.active); assertEquals(3, e.selStart)
        repeat(5) { e.redo() }
        assertEquals(7, e.document.blocks.size)
        assertEquals(1, e.document.comments.size)
        assertEquals(0xFFEEEEEE, e.document.pageColor)
        // A new edit after undo drops the redo branch.
        e.undo(); e.insertAtCaret("x")
        assertFalse(e.canRedo)
    }

    @Test
    fun liveCommentEditsBecomeOneUndoStep() {
        val e = DocumentEditor(); e.load(bigDoc(2))
        e.addComment("a")
        val c = e.document.comments[0]
        e.updateComment(c, "ab"); e.updateComment(c, "abc")
        assertTrue(e.canUndo)
        e.undo()
        assertEquals("a", e.document.comments[0].text)
        e.undo()
        assertTrue(e.document.comments.isEmpty())
    }

    // --- Sheets ----------------------------------------------------------------

    private fun sheetEditor(vararg cells: Triple<Int, Int, String>): SheetEditor {
        val e = SheetEditor()
        val wb = Workbook.blank()
        for ((r, c, t) in cells) wb.sheets[0].ensure(r, c).text = t
        e.load(wb)
        return e
    }

    @Test
    fun cellEditsCoalesceAndRestoreSelection() {
        val e = sheetEditor(Triple(0, 0, "1"))
        e.select(0, 0)
        e.setCell(0, 0, "12"); e.setCell(0, 0, "123")
        e.select(3, 3)
        e.setCell(3, 3, "new")
        assertEquals(2, e.undoDepth)
        e.undo()
        assertNull(e.sheet.cell(3, 3))
        assertEquals(3, e.selRow)
        e.undo()
        assertEquals("1", e.sheet.cell(0, 0)?.text)
        assertEquals(0, e.selRow)
        e.redo(); e.redo()
        assertEquals("123", e.sheet.cell(0, 0)?.text)
        assertEquals("new", e.sheet.cell(3, 3)?.text)
    }

    @Test
    fun insertRowRewritesFormulasOnOtherSheetsAndUndoes() {
        val e = sheetEditor(Triple(0, 0, "10"), Triple(2, 0, "=A1*2"))
        e.addSheet()
        e.setCell(0, 0, "=Sheet1!A3")
        e.selectSheet(0)
        e.select(1, 0)
        e.insertRowAt(1)
        assertEquals("=A1*2", e.sheet.cell(3, 0)?.text)
        assertEquals("=Sheet1!A4", e.workbook.sheets[1].cell(0, 0)?.text)
        e.undo()
        assertEquals("=A1*2", e.sheet.cell(2, 0)?.text)
        assertNull(e.sheet.cell(3, 0))
        assertEquals("=Sheet1!A3", e.workbook.sheets[1].cell(0, 0)?.text)
        e.redo()
        assertEquals("=Sheet1!A4", e.workbook.sheets[1].cell(0, 0)?.text)
    }

    @Test
    fun pasteReplaceAllAndPivotUndo() {
        val e = sheetEditor(Triple(0, 0, "apple"), Triple(1, 0, "apple pie"), Triple(5, 5, "x"))
        e.select(0, 0); e.copySelection()
        e.select(10, 10); e.pasteSelection()
        assertEquals("apple", e.sheet.cell(10, 10)?.text)
        assertEquals(3, e.replaceAll("apple", "pear")) // both source cells and the pasted copy
        assertEquals("pear pie", e.sheet.cell(1, 0)?.text)
        e.undo()
        assertEquals("apple pie", e.sheet.cell(1, 0)?.text)
        e.undo()
        assertNull(e.sheet.cell(10, 10))
        assertEquals(1, e.workbook.sheets.size)
        // Pivot adds a sheet; undo removes it and returns to the source sheet.
        e.select(0, 0); e.extendSelection(2, 1)
        e.setCell(0, 1, "1"); e.setCell(1, 1, "2"); e.setCell(2, 0, "apple"); e.setCell(2, 1, "3")
        e.select(0, 0); e.extendSelection(2, 1)
        val name = e.buildPivot(0, null, 1, com.therealityteam.aeropages.editor.PivotAgg.SUM)
        assertTrue(name != null)
        assertEquals(2, e.workbook.sheets.size)
        e.undo()
        assertEquals(1, e.workbook.sheets.size)
        assertEquals(0, e.sheetIndex)
        e.redo()
        assertEquals(2, e.workbook.sheets.size)
    }

    @Test
    fun sheetMetadataOpsUndo() {
        val e = sheetEditor(Triple(0, 0, "5"), Triple(1, 0, "6"))
        e.select(0, 0); e.extendSelection(1, 1)
        e.mergeCells()
        e.addCondFormat(com.therealityteam.aeropages.model.CondType.GREATER, 4.0, 0.0, 0xFFFF0000)
        e.adjustColWidth(0.5f); e.adjustColWidth(0.5f)
        e.freezeAtSelection()
        assertTrue(e.defineName("box"))
        assertEquals(5, e.undoDepth)
        e.undo(); assertTrue(e.workbook.names.isEmpty())
        e.undo(); assertFalse(e.isFrozen)
        e.undo(); assertEquals(1f, e.sheet.colWidthFactor(0))
        e.undo(); assertTrue(e.sheet.condFormats.isEmpty())
        e.undo(); assertTrue(e.sheet.merges.isEmpty())
        assertEquals("undoing the merge takes its centring back to General", null, e.sheet.cell(0, 0)?.align)
        repeat(5) { e.redo() }
        assertEquals(1, e.sheet.merges.size)
        assertEquals(2f, e.sheet.colWidthFactor(0))
        assertEquals("Sheet1!\$A\$1:\$B\$2", e.workbook.names["box"])
    }

    @Test
    fun deletedRowsWithTextToColumnsUndo() {
        val e = sheetEditor(Triple(0, 0, "a,b,c"), Triple(1, 0, "a,b,c"), Triple(2, 0, "d"))
        e.select(0, 0); e.extendSelection(1, 0)
        e.textToColumns(",")
        assertEquals("c", e.sheet.cell(0, 2)?.text)
        assertEquals("c", e.sheet.cell(1, 2)?.text)
        e.select(0, 0); e.extendSelection(1, 0)
        assertEquals(1, e.removeDuplicates())
        // As Excel's: only within A1:A2 — the row under it and the cells beside it stay.
        assertNull(e.sheet.cell(1, 0)); assertEquals("d", e.sheet.cell(2, 0)?.text); assertEquals("c", e.sheet.cell(1, 2)?.text)
        e.undo()
        assertEquals("a", e.sheet.cell(1, 0)?.text)
        assertEquals("c", e.sheet.cell(1, 2)?.text)
        assertEquals("d", e.sheet.cell(2, 0)?.text)
        e.undo()
        assertNull(e.sheet.cell(0, 2))
        assertEquals("a,b,c", e.sheet.cell(0, 0)?.text)
    }

    // --- Slides ----------------------------------------------------------------

    @Test
    fun slideListOpsUndoAndRedo() {
        val e = SlideEditor()
        e.replaceText(e.slide.title, e.slide.title.text, "one")
        e.addSlide(); e.replaceText(e.slide.title, e.slide.title.text, "two")
        e.addSlide(); e.replaceText(e.slide.title, e.slide.title.text, "three")
        e.select(0); e.moveSlide(2)
        assertEquals(listOf("two", "three", "one"), e.presentation.slides.map { it.title.text })
        e.select(1); e.deleteSlide()
        assertEquals(listOf("two", "one"), e.presentation.slides.map { it.title.text })
        e.duplicateSlide()
        assertEquals(listOf("two", "one", "one"), e.presentation.slides.map { it.title.text })
        e.undo(); assertEquals(listOf("two", "one"), e.presentation.slides.map { it.title.text })
        e.undo(); assertEquals(listOf("two", "three", "one"), e.presentation.slides.map { it.title.text })
        e.undo(); assertEquals(listOf("one", "two", "three"), e.presentation.slides.map { it.title.text })
        assertEquals(0, e.current)
        e.redo(); e.redo(); e.redo()
        assertEquals(listOf("two", "one", "one"), e.presentation.slides.map { it.title.text })
    }

    @Test
    fun deckWideOpsUndo() {
        val e = SlideEditor()
        e.addSlide()
        e.setTransitionAll(SlideTransition.PUSH)
        e.setFooter("foot", true)
        e.applyThemeFull(2, 0xFF112233, 0xFFFFFFFF, null, null)
        e.undo()
        assertEquals(0, e.presentation.themeIndex)
        assertNull(e.presentation.master.background)
        e.undo()
        assertEquals("", e.presentation.footer)
        e.undo()
        assertTrue(e.presentation.slides.all { it.transition == SlideTransition.NONE })
        e.redo(); e.redo(); e.redo()
        assertEquals(2, e.presentation.themeIndex)
        assertEquals("foot", e.presentation.footer)
        // The theme background lives on the master; every slide inherits it.
        assertEquals(0xFF112233, e.presentation.master.background)
        assertTrue(e.presentation.slides.all { it.transition == SlideTransition.PUSH && e.presentation.backgroundOf(it) == 0xFF112233 })
    }

    @Test
    fun shapeDragCoalescesPerSlideOnly() {
        val e = SlideEditor()
        e.addShape(ShapeType.RECT)
        val x0 = e.selectedShape!!.xPct
        e.moveSelectedShape(0.05f, 0f); e.moveSelectedShape(0.05f, 0f)
        e.addSlide()
        e.addShape(ShapeType.ELLIPSE)
        e.moveSelectedShape(0.1f, 0f)
        e.undo() // the second slide's drag
        assertEquals(0.30f, e.presentation.slides[1].shapes[0].xPct, 1e-4f)
        e.undo() // adding the ellipse
        assertTrue(e.presentation.slides[1].shapes.isEmpty())
        e.undo() // adding slide 2
        assertEquals(1, e.slideCount)
        e.undo() // the whole first drag, as one step
        assertEquals(x0, e.presentation.slides[0].shapes[0].xPct, 1e-4f)
    }

    @Test
    fun tableAndChartEditsUndo() {
        val e = SlideEditor()
        e.addTable(2, 2)
        e.addTableRow()
        e.addChart(ChartType.PIE)
        e.setChartCell(0, 0, 9f); e.setChartCell(0, 0, 10f)
        assertEquals(10f, e.selectedChart!!.series[0].values[0])
        e.undo()
        assertEquals(4.3f, e.slide.charts[0].series[0].values[0])
        e.undo()
        assertTrue(e.slide.charts.isEmpty())
        e.undo()
        assertEquals(2, e.slide.tables[0].rows)
        e.redo(); e.redo(); e.redo()
        assertEquals(3, e.slide.tables[0].rows)
        assertEquals(10f, e.slide.charts[0].series[0].values[0])
    }

    @Test
    fun sheetWithManyCellsKeepsDeltasSmall() {
        val wb = Workbook(mutableListOf(Sheet(rowCount = 5000)))
        for (r in 0 until 5000) wb.sheets[0].ensure(r, 0).text = r.toString()
        val e = SheetEditor(); e.load(wb)
        e.select(10, 0)
        e.setCell(10, 0, "changed")
        e.undo()
        assertEquals("10", e.sheet.cell(10, 0)?.text)
        assertEquals(5000, e.sheet.cells.size)
    }
}
