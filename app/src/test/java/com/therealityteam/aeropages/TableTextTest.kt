package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.SortKind
import com.therealityteam.aeropages.editor.TableSeparator
import com.therealityteam.aeropages.editor.TableText
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word's Convert Text to Table and Table to Text, and its table Sort. */
class TableTextTest {

    private fun texts(t: Table) = t.rows.map { r -> r.cells.map { c -> c.content.joinToString("|") { it.plainText() } } }

    @Test
    fun textBecomesATableKeepingItsLook() {
        val a = Paragraph(mutableListOf(TextRun("姓名\t", CharacterFormat()), TextRun("年龄", CharacterFormat(bold = true))))
        val b = Paragraph.of("张三\t30\t多余\t内容")
        // Columns: the most fields; more fields than columns run into the last cell.
        assertEquals(4, TableText.columnsFor(listOf(a, b), TableSeparator.TAB))
        val t = TableText.toTable(listOf(a, b), TableSeparator.TAB, null, 3)
        assertEquals(listOf(listOf("姓名", "年龄", ""), listOf("张三", "30", "多余\t内容")), texts(t))
        assertTrue("the bold run stays bold", t.rows[0].cells[1].content[0].runs.all { it.format.bold })
        // Commas of either width, spaces, a character of one's own.
        assertEquals(3, TableText.fields("a,b，c", TableSeparator.COMMA).size)
        assertEquals(listOf(0..0, 2..2), TableText.fields("x;y", TableSeparator.OTHER, ';'))
        // Paragraph marks: each paragraph a cell, the columns given.
        val p = TableText.toTable(listOf("1", "2", "3").map { Paragraph.of(it) }, TableSeparator.PARAGRAPH, null, 2)
        assertEquals(listOf(listOf("1", "2"), listOf("3", "")), texts(p))
    }

    @Test
    fun aTableBecomesText() {
        val t = Table(mutableListOf(
            TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("a"))), TableCell(mutableListOf(Paragraph.of("b"))))),
            TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("c1"), Paragraph.of("c2"))), TableCell(mutableListOf(Paragraph.of("d"))))),
        ))
        assertEquals(listOf("a\tb", "c1", "c2\td"), TableText.toText(t, TableSeparator.TAB, null).map { it.plainText() })
        assertEquals(listOf("a", "b", "c1", "c2", "d"), TableText.toText(t, TableSeparator.PARAGRAPH, null).map { it.plainText() })
        assertEquals(listOf("a;b", "c1", "c2;d"), TableText.toText(t, TableSeparator.OTHER, ';').map { it.plainText() })
    }

    private fun table(vararg rows: List<String>) = Table(rows.map { r -> TableRow(r.map { TableCell(mutableListOf(Paragraph.of(it))) }.toMutableList()) }.toMutableList())

    @Test
    fun sortingRows() {
        val t = table(listOf("姓名", "分数", "日期"), listOf("张三", "88", "2026-10-03"), listOf("李四", "9", "2026/9/30"), listOf("王五", "", "2026年10月1日"), listOf("赵六", "100", ""))
        val zh = java.text.Collator.getInstance(java.util.Locale.CHINA)
        // Pinyin order, the header row staying first.
        assertTrue(TableText.sort(t, 0, SortKind.TEXT, descending = false, header = true, collator = zh))
        assertEquals(listOf("姓名", "李四", "王五", "张三", "赵六"), t.rows.map { it.cells[0].content[0].plainText() })
        // Numbers by value (9 before 88), empty cells last either way.
        TableText.sort(t, 1, SortKind.NUMBER, descending = false, header = true)
        assertEquals(listOf("分数", "9", "88", "100", ""), t.rows.map { it.cells[1].content[0].plainText() })
        TableText.sort(t, 1, SortKind.NUMBER, descending = true, header = true)
        assertEquals(listOf("分数", "100", "88", "9", ""), t.rows.map { it.cells[1].content[0].plainText() })
        // Dates in their usual ways of writing.
        TableText.sort(t, 2, SortKind.DATE, descending = false, header = true)
        assertEquals(listOf("李四", "王五", "张三", "赵六"), t.rows.drop(1).map { it.cells[0].content[0].plainText() })
        // A cell spanning rows: not sorted.
        t.rows[1].cells[0].rowSpan = 2
        assertFalse(TableText.sort(t, 0, SortKind.TEXT, false, true))
    }

    @Test
    fun theEditorConvertsAndSortsInOneStepEach() {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("before"))
        doc.addParagraph(Paragraph.of("b\t2")); doc.addParagraph(Paragraph.of("a\t1"))
        doc.addParagraph(Paragraph.of("after"))
        val e = DocumentEditor().apply { load(doc) }
        e.selectRange(DocumentEditor.DocPos(1, 0), DocumentEditor.DocPos(2, 3))
        assertEquals(2, e.textToTableColumns(TableSeparator.TAB))
        assertTrue(e.convertTextToTable(TableSeparator.TAB, null, 2))
        assertEquals(3, e.document.blocks.size)
        assertTrue(e.document.blocks[1] is TableBlock)
        assertTrue("the caret is in the first cell", e.activeInTable)
        // Sort it (no header): a before b.
        assertTrue(e.sortTable(0, SortKind.TEXT, descending = false, header = false))
        assertEquals("a", (e.document.blocks[1] as TableBlock).table.rows[0].cells[0].content[0].plainText())
        // Back to text, joined by commas.
        assertTrue(e.convertTableToText(TableSeparator.COMMA, null))
        assertEquals(listOf("before", "a,1", "b,2", "after"), e.document.paragraphs().map { it.plainText() })
        // Each was one step.
        e.undo(); e.undo()
        assertEquals("b", (e.document.blocks[1] as TableBlock).table.rows[0].cells[0].content[0].plainText())
        e.undo()
        assertEquals(listOf("before", "b\t2", "a\t1", "after"), e.document.paragraphs().map { it.plainText() })
        // Text in a table cell is not converted.
        e.setSelection(0, 0, 0)
        e.insertTable(1, 1)
        assertEquals(null, e.selectedBodyParagraphs())
    }
}
