package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.SortKind
import com.therealityteam.aeropages.editor.TableSeparator
import com.therealityteam.aeropages.editor.TableText
import com.therealityteam.aeropages.editor.TextSortKey
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

/** Word's Sort Text: paragraphs sorted by their text or a field, as text, numbers or dates, in one undo step. */
class SortTextTest {
    private fun ps(vararg t: String) = t.map { Paragraph.of(it) }
    private fun texts(l: List<Paragraph>) = l.map { it.plainText() }
    private val en = Collator.getInstance(Locale.ENGLISH)

    @Test
    fun paragraphsSortByTextFieldsNumbersAndDates() {
        // Whole paragraphs, without case; blank ones first ascending (as Word), last descending.
        val words = ps("pear", "Apple", "", "banana")
        assertEquals(listOf("", "Apple", "banana", "pear"), texts(TableText.sortParagraphs(words, listOf(TextSortKey(-1)), collator = en)))
        assertEquals(listOf("pear", "banana", "Apple", ""), texts(TableText.sortParagraphs(words, listOf(TextSortKey(-1, descending = true)), collator = en)))
        // Case sensitive: lower case before upper in English's order.
        assertEquals(listOf("apple", "Apple"), texts(TableText.sortParagraphs(ps("Apple", "apple"), listOf(TextSortKey(-1)), caseSensitive = true, collator = Collator.getInstance(Locale.ENGLISH))))
        // A field as a number, a header kept on top, ties broken by the next key.
        val rows = ps("Fruit\tCount", "pear\t10", "fig\t2", "apple\t10", "kiwi\t")
        val sorted = TableText.sortParagraphs(rows, listOf(TextSortKey(1, SortKind.NUMBER, descending = true), TextSortKey(0)), header = true, collator = en)
        assertEquals(listOf("Fruit\tCount", "apple\t10", "pear\t10", "fig\t2", "kiwi\t"), texts(sorted))
        // Commas, dates.
        val dated = ps("b,2026-03-01", "a,2025-12-24", "c,2026-01-15")
        assertEquals(listOf("a,2025-12-24", "c,2026-01-15", "b,2026-03-01"),
            texts(TableText.sortParagraphs(dated, listOf(TextSortKey(1, SortKind.DATE)), TableSeparator.COMMA, collator = en)))
    }

    @Test
    fun theEditorSortsTheSelectionInOneStepTheirLookGoingWithThem() {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("Fruits"))
        doc.addParagraph(Paragraph(mutableListOf(TextRun("pear", CharacterFormat(bold = true)))))
        doc.addParagraph(Paragraph.of("apple"))
        doc.addParagraph(Paragraph.of("fig"))
        doc.addParagraph(Paragraph.of("The end"))
        val e = DocumentEditor().apply { load(doc) }
        e.selectRange(DocumentEditor.DocPos(1, 0), DocumentEditor.DocPos(3, 3))
        assertEquals(3, e.sortTextParagraphs()?.size)
        assertTrue(e.sortParagraphs(listOf(TextSortKey(-1))))
        assertEquals(listOf("Fruits", "apple", "fig", "pear", "The end"), e.document.paragraphs().map { it.plainText() })
        assertTrue("pear kept its bold", e.document.paragraphs()[3].runs.all { it.format.bold })
        e.undo()
        assertEquals(listOf("Fruits", "pear", "apple", "fig", "The end"), e.document.paragraphs().map { it.plainText() })
        // Nothing selected: the run of paragraphs the caret is in (the whole of it here).
        e.setSelection(2, 0, 0)
        assertEquals(5, e.sortTextParagraphs()?.size)
    }

    @Test
    fun breaksStayWhereTheyBelong() {
        val doc = Document(mutableListOf())
        val section = PageSetup()
        doc.addParagraph(Paragraph.of("b"))
        doc.addParagraph(Paragraph.of("a").also { it.format.sectionBreak = section })
        doc.addParagraph(Paragraph.of("next section"))
        val e = DocumentEditor().apply { load(doc) }
        e.selectRange(DocumentEditor.DocPos(0, 0), DocumentEditor.DocPos(1, 1))
        assertTrue(e.sortParagraphs(listOf(TextSortKey(-1))))
        val p = e.document.paragraphs()
        assertEquals(listOf("a", "b", "next section"), p.map { it.plainText() })
        assertTrue("the section break stays at the end of the sorted run", p[1].format.sectionBreak === section)
        assertEquals(null, p[0].format.sectionBreak)
        // A page break among them: not sorted.
        val withBreak = Document(mutableListOf())
        withBreak.addParagraph(Paragraph.of("z")); withBreak.addParagraph(Paragraph.of("\u000C")); withBreak.addParagraph(Paragraph.of("y"))
        val e2 = DocumentEditor().apply { load(withBreak) }
        e2.selectRange(DocumentEditor.DocPos(0, 0), DocumentEditor.DocPos(2, 1))
        assertFalse(e2.sortParagraphs(listOf(TextSortKey(-1))))
    }
}
