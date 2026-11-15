package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.TableLook
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The Table tab's operations: rows and columns where the caret is, merges carried on, deleting, styles. */
class TableEditingTest {

    /** A paragraph, a 3 × 3 table with A1 … C3 in its cells, a paragraph; the caret in [cell] ("B2"). */
    private fun editor(cell: String = "B2"): Pair<DocumentEditor, Table> {
        val t = Table.of(3, 3)
        t.rows.forEachIndexed { r, row -> row.cells.forEachIndexed { c, x -> x.content[0] = Paragraph.of("${'A' + c}${r + 1}") } }
        val e = DocumentEditor().apply { load(Document(mutableListOf(ParagraphBlock(Paragraph.of("before")), TableBlock(t), ParagraphBlock(Paragraph.of("after"))))) }
        e.focusParagraph(1 + (cell[1] - '1') * 3 + (cell[0] - 'A'))
        return e to t
    }

    private fun Table.texts() = rows.map { r -> r.cells.joinToString(" ") { c -> c.content.joinToString("/") { it.plainText() }.ifEmpty { "_" } } }

    private fun DocumentEditor.caretText() = document.locations()[active].paragraph.plainText()

    @Test
    fun rowsGoAboveAndBelowTheCaret() {
        val (e, t) = editor()
        e.insertTableRow(above = true)
        assertEquals(listOf("A1 B1 C1", "_ _ _", "A2 B2 C2", "A3 B3 C3"), t.texts())
        assertEquals("the caret stays in its cell", "B2", e.caretText())
        e.insertTableRow(above = false)
        assertEquals(listOf("A1 B1 C1", "_ _ _", "A2 B2 C2", "_ _ _", "A3 B3 C3"), t.texts())
        e.undo(); e.undo()
        // Undo puts back copies of the blocks: the table as it was.
        assertEquals(listOf("A1 B1 C1", "A2 B2 C2", "A3 B3 C3"), (e.document.blocks[1] as TableBlock).table.texts())
    }

    @Test
    fun aRowInsideAVerticalMergeCarriesItOn() {
        val (e, t) = editor("A1")
        e.mergeCellDown()          // A1 over A2
        assertEquals(2, t.rows[0].cells[0].rowSpan)
        e.focusParagraph(e.document.locations().indexOfFirst { it.paragraph.plainText() == "B1" })
        e.insertTableRow(above = false)   // between rows 1 and 2, inside A's merge
        assertEquals(3, t.rows[0].cells[0].rowSpan)
        assertTrue(t.rows[1].cells[0].vMerged)
        assertFalse(t.rows[1].cells[1].vMerged)
    }

    @Test
    fun columnsGoLeftAndRightOfTheCaret() {
        val (e, t) = editor()
        e.insertTableColumn(left = true)
        assertEquals(listOf("A1 _ B1 C1", "A2 _ B2 C2", "A3 _ B3 C3"), t.texts())
        assertEquals("B2", e.caretText())
        e.insertTableColumn(left = false)
        assertEquals(listOf("A1 _ B1 _ C1", "A2 _ B2 _ C2", "A3 _ B3 _ C3"), t.texts())
        // A cell spanning the new column's place widens over it.
        val (e2, t2) = editor("A1")
        e2.mergeCellRight()        // A1 spans A–B
        e2.focusParagraph(e2.document.locations().indexOfFirst { it.paragraph.plainText() == "A2" })
        e2.insertTableColumn(left = false)    // between A and B
        assertEquals(3, t2.rows[0].cells[0].gridSpan)
        assertEquals(listOf(1, 1, 1, 1), t2.rows[1].cells.map { it.gridSpan })
    }

    @Test
    fun deletingColumnsAndTables() {
        val (e, t) = editor()
        e.deleteTableColumn()
        assertEquals(listOf("A1 C1", "A2 C2", "A3 C3"), t.texts())
        assertEquals("the caret goes to the cell now there", "C2", e.caretText())
        e.deleteTableColumn(); e.deleteTableColumn()
        assertTrue("no columns left, no table", e.document.blocks.none { it is TableBlock })
        assertEquals("before", e.caretText())
        e.undo()
        assertEquals(listOf("A1", "A2", "A3"), (e.document.blocks[1] as TableBlock).table.texts())

        val (e2, _) = editor()
        e2.deleteTable()
        assertEquals(listOf("before", "after"), e2.document.blocks.map { (it as ParagraphBlock).paragraph.plainText() })
        assertEquals("before", e2.caretText())
        assertFalse(e2.activeInTable)
    }

    @Test
    fun splittingAMergedCellAndDistributing() {
        val (e, t) = editor("A1")
        e.mergeCellRight(); e.mergeCellDown()
        e.splitMergedCell()
        assertEquals(listOf(3, 3, 3), t.rows.map { it.cells.size })
        assertTrue(t.rows.all { r -> r.cells.all { it.gridSpan == 1 && it.rowSpan == 1 && !it.vMerged } })
        e.adjustActiveColumnWidth(0.6f)
        assertTrue(t.columnWidths.isNotEmpty())
        e.distributeTableColumns()
        assertTrue(t.columnWidths.isEmpty())
    }

    @Test
    fun aStyleSetsHeaderBandsAndRulesThatFilesKeep() {
        val (e, t) = editor()
        val blue = TableLook.GALLERY[4]
        e.applyTableLook(blue)
        assertEquals(blue.header, t.rows[0].cells[1].shade)
        assertTrue(t.rows[0].cells[0].content[0].runs.all { it.format.bold && it.format.color == 0xFFFFFFFF })
        assertEquals("the first data row banded", blue.band, t.rows[1].cells[0].shade)
        assertNull(t.rows[2].cells[0].shade)
        assertTrue(t.rows[0].header)
        val back = (DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(e.document, it) }.toByteArray())).blocks[1] as TableBlock).table
        assertEquals(blue.header, back.rows[0].cells[0].shade)
        assertEquals(blue.band, back.rows[1].cells[2].shade)
        // The three-line table: a heavy rule over and under, a light one under the header, nothing else.
        e.applyTableLook(TableLook.GALLERY[2])
        val first = t.bordersOf(t.rows[0].cells[0]); val mid = t.bordersOf(t.rows[1].cells[1]); val last = t.bordersOf(t.rows[2].cells[2])
        assertEquals(1.5f, first.top!!.widthPt); assertEquals(0.75f, first.bottom!!.widthPt)
        assertNull(mid.left); assertNull(mid.bottom)
        assertEquals(1.5f, last.bottom!!.widthPt)
        assertNull("the header's white text went with the blue", t.rows[0].cells[0].content[0].runs[0].format.color)
        e.undo()
        assertEquals(blue.header, (e.document.blocks[1] as TableBlock).table.rows[0].cells[0].shade)
    }
}

/** Slides' table rows and columns go where the cell being typed in is, as PowerPoint's do. */
class SlideTableEditingTest {
    @Test
    fun rowsAndColumnsAtTheFocusedCell() {
        val t = com.therealityteam.aeropages.model.SlideTable.create(3, 3)
        t.cells.forEachIndexed { r, row -> row.forEachIndexed { c, b -> b.paragraph.runs[0].text = "${'A' + c}${r + 1}" } }
        val slide = com.therealityteam.aeropages.model.Slide().also { it.tables += t }
        val e = com.therealityteam.aeropages.editor.SlideEditor().apply { load(com.therealityteam.aeropages.model.Presentation(mutableListOf(slide))) }
        e.selectTable(t); e.focus(t.cells[1][1])
        fun texts() = t.cells.map { r -> r.joinToString(" ") { it.text.ifEmpty { "_" } } }
        e.addTableRow(below = false)
        assertEquals(listOf("A1 B1 C1", "_ _ _", "A2 B2 C2", "A3 B3 C3"), texts())
        e.addTableColumn(left = true)
        assertEquals("A2 _ B2 C2", texts()[2])
        e.deleteTableColumn()   // the focused B2's column
        assertEquals(listOf("A1 _ C1", "_ _ _", "A2 _ C2", "A3 _ C3"), texts())
        e.focus(t.cells[2][0])
        e.deleteTableRow()
        assertEquals(listOf("A1 _ C1", "_ _ _", "A3 _ C3"), texts())
    }
}
