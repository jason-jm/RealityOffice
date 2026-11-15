package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocCompare
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word's comparison settings (Compare ▸ More): formatting, case changes, white space, tables; character or word level. */
class CompareSettingsTest {
    private fun doc(vararg lines: String) = Document(mutableListOf()).also { d -> lines.forEach { d.addParagraph(Paragraph.of(it)) } }
    private fun marked(d: Document) = d.paragraphs().flatMap { it.runs }.filter { it.format.inserted || it.format.deleted || it.format.formatChanged }

    @Test
    fun caseAndWhiteSpaceCountOnlyWhenAsked() {
        val a = doc("The Annual Plan is final.", "Two  spaces here.")
        val b = doc("the annual plan is final.", "Two spaces here.")
        val strict = DocCompare.compare(a, b, "R")
        assertTrue(strict.insertions > 0 && strict.deletions > 0)
        val loose = DocCompare.compare(a, b, "R", options = DocCompare.Options(caseChanges = false, whiteSpace = false))
        assertEquals(0, loose.insertions); assertEquals(0, loose.deletions)
        assertTrue(marked(loose.document).isEmpty())
        // The text is the revised document's.
        assertEquals(listOf("the annual plan is final.", "Two spaces here."), loose.document.paragraphs().map { it.plainText() })
        // Only white space off: the case change still counts, the spacing doesn't.
        val space = DocCompare.compare(a, b, "R", options = DocCompare.Options(whiteSpace = false))
        assertEquals(listOf("The Annual Plan"), space.document.paragraphs()[0].runs.filter { it.format.deleted }.map { it.text })
        assertTrue(space.document.paragraphs()[1].runs.none { it.format.inserted || it.format.deleted })
    }

    @Test
    fun formattingAndTablesCanBeLeftOut() {
        val a = Document(mutableListOf()).apply {
            addParagraph(Paragraph.of("The plan is final."))
            blocks.add(TableBlock(Table(mutableListOf(TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("North")))))))))
        }
        val b = Document(mutableListOf()).apply {
            addParagraph(Paragraph(mutableListOf(TextRun("The plan is "), TextRun("final", CharacterFormat(bold = true)), TextRun("."))))
            blocks.add(TableBlock(Table(mutableListOf(TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("South")))))))))
        }
        val all = DocCompare.compare(a, b, "R")
        assertEquals(1, all.formatting); assertEquals(1, all.insertions); assertEquals(1, all.deletions)
        val none = DocCompare.compare(a, b, "R", options = DocCompare.Options(formatting = false, tables = false))
        assertEquals(0, none.formatting); assertEquals(0, none.insertions); assertEquals(0, none.deletions)
        assertTrue(marked(none.document).isEmpty())
        assertTrue(none.document.paragraphs()[0].runs.first { it.text == "final" }.format.bold)
        assertEquals("South", (none.document.blocks[1] as TableBlock).table.rows[0].cells[0].content[0].plainText())
    }

    @Test
    fun characterLevelMarksOnlyTheLettersThatChanged() {
        val a = doc("The colour is grey."); val b = doc("The color is gray.")
        val words = DocCompare.compare(a, b, "R")
        assertEquals(listOf("colour", "grey"), words.document.paragraphs()[0].runs.filter { it.format.deleted }.map { it.text })
        val chars = DocCompare.compare(a, b, "R", options = DocCompare.Options(characterLevel = true)).document.paragraphs()[0]
        assertEquals(listOf("u", "e"), chars.runs.filter { it.format.deleted }.map { it.text })
        assertEquals(listOf("a"), chars.runs.filter { it.format.inserted }.map { it.text })
        assertEquals("The colour is greay.", chars.plainText())
    }
}
