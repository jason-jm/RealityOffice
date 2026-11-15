package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tables in a .doc — cells ending at cell marks, rows at their terminating paragraphs and what those
 * define — read from a file LibreOffice wrote (`resources/doc/tables.doc`, converted from a docx).
 */
class DocTablesTest {
    private val doc = DocReader.read(javaClass.getResourceAsStream("/doc/tables.doc")!!)

    @Test
    fun tablesStandBetweenTheirParagraphs() {
        val kinds = doc.blocks.map { if (it is TableBlock) "table" else (it as ParagraphBlock).paragraph.plainText() }
        assertEquals(listOf("Before the table.", "table", "Between the tables.", "table", "After."), kinds)
    }

    @Test
    fun cellsWidthsMergesShadingAndHeaderRows() {
        val t = (doc.blocks[1] as TableBlock).table
        assertEquals(listOf(2000f, 3000f, 2500f), t.columnWidths)
        assertTrue(t.borders)
        val (head, merged, last) = t.rows
        assertTrue("a header row repeats", head.header)
        assertEquals(listOf("Region", "Revenue", "Notes"), head.cells.map { it.content.single().plainText() })
        assertTrue(head.cells.all { it.shade == 0xFFD9E2F3 })
        assertEquals("merged across two columns", 2, merged.cells[0].gridSpan)
        assertEquals("North and South together", merged.cells[0].content.single().plainText())
        assertEquals("merged down two rows", 2, merged.cells[1].rowSpan)
        assertTrue(last.cells[2].vMerged)
        assertEquals("a cell's paragraphs", listOf("1,200", "second paragraph"), last.cells[1].content.map { it.plainText() })
    }

    @Test
    fun aTableWithoutBorders() {
        val t = (doc.blocks[3] as TableBlock).table
        assertFalse(t.borders)
        assertEquals(listOf("甲方：", "乙方："), t.rows.single().cells.map { it.content.single().plainText() })
    }
}
