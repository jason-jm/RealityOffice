package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocSearch
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.SearchOptions
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word's Find and Replace: its options, special characters and wildcards, Previous / Next, Replace (All), Go To. */
class FindReplaceTest {

    private fun found(query: String, text: String, options: SearchOptions = SearchOptions()): List<String> {
        val regex = DocSearch.pattern(query, options) ?: return listOf("<invalid>")
        return regex.findAll(text).map { it.value }.toList()
    }

    @Test
    fun wordsWildcardsAndCodes() {
        val w = SearchOptions(wildcards = true)
        // Word's own examples.
        assertEquals(listOf("bat", "bit"), found("b?t", "bat bit bt", w))
        assertEquals(listOf("sad", "started"), found("s*d", "sad started", w))
        assertEquals(listOf("right", "sight", "tight"), found("[r-t]ight", "right sight tight might", w))
        assertEquals(listOf("just"), found("[!m]ust", "must just", w))
        assertEquals(listOf("100"), found("10{2}", "10 100", w))
        assertEquals(listOf("lot", "loot"), found("lo@t", "lot loot lt", w))
        assertEquals(listOf("inter"), found("<(inter)", "interest splinter", w))
        assertEquals(listOf("in"), found("(in)>", "in inside", w))
        assertEquals(listOf("?"), found("\\?", "why?", w))
        // Unbalanced brackets or a repeat of nothing: not taken.
        assertEquals(listOf("<invalid>"), found("(abc", "abc", w))
        assertEquals(listOf("<invalid>"), found("@a", "a", w))
        // Plain text: regular-expression characters are themselves; the codes are Word's.
        assertEquals(listOf("a.b"), found("a.b", "a.b axb"))
        assertEquals(listOf("x\ty"), found("x^ty", "x\ty"))
        assertEquals(listOf("7", "3"), found("^#", "a7b3"))
        assertEquals(listOf("<invalid>"), found("^p", "x"))
        // Case, and whole words (CJK text has no words of that kind: it matches as written).
        assertEquals(listOf("Cat", "cat"), found("cat", "Cat cat"))
        assertEquals(listOf("cat"), found("cat", "Cat cat", SearchOptions(matchCase = true)))
        assertEquals(listOf("cat"), found("cat", "cat catalog bobcat", SearchOptions(wholeWord = true)))
        assertEquals(listOf("苹果"), found("苹果", "大苹果树", SearchOptions(wholeWord = true)))
    }

    @Test
    fun replacementsSayWhatWasFound() {
        val w = SearchOptions(wildcards = true)
        val m = DocSearch.pattern("(John) (Smith)", w)!!.find("John Smith")!!
        assertEquals("Smith, John", DocSearch.expand("\\2, \\1", m, w))
        val p = DocSearch.pattern("42", SearchOptions())!!.find("x 42")!!
        assertEquals("[42]\t", DocSearch.expand("[^&]^t", p, SearchOptions()))
        val (before, hit, after) = DocSearch.context("The quick brown fox jumps over the lazy dog", 16, 19, around = 6)
        assertEquals("…brown ", before); assertEquals("fox", hit); assertTrue(after.startsWith(" jumps"))
    }

    private fun editor(vararg lines: String) = DocumentEditor().apply {
        load(Document(mutableListOf()).also { d -> lines.forEach { d.addParagraph(Paragraph.of(it)) } })
    }

    @Test
    fun nextAndPreviousGoRoundTheDocument() {
        val e = editor("one apple", "no fruit", "two apples, three apples")
        e.setSelection(1, 0, 0)
        e.setSearch("apple")
        assertEquals(3, e.searchHits.size)
        assertEquals(1, e.find())
        assertEquals(DocumentEditor.DocPos(2, 4) to DocumentEditor.DocPos(2, 9), e.selectionEnds())
        assertEquals(1, e.currentHit())
        assertEquals(2, e.find())
        assertEquals("round to the start", 0, e.find())
        assertEquals("and back round to the end", 2, e.find(back = true))
        assertEquals(1, e.find(back = true))
        e.setSearch("pear")
        assertNull(e.find())
        // The old single calls still work.
        assertTrue(e.findNext("Apple", false))
        assertFalse(e.findNext("Apple", true))
    }

    @Test
    fun replaceAndReplaceAll() {
        val e = editor("cat and cat", "a catalog", "Cat")
        e.setSelection(0, 0, 0)
        e.setSearch("cat", SearchOptions(wholeWord = true))
        assertEquals(3, e.searchHits.size)
        e.find()
        // Replace: the selected one, then the next is selected.
        assertEquals(0, e.replaceCurrent("dog"))
        assertEquals("dog and cat", e.document.locations()[0].paragraph.plainText())
        assertEquals(2, e.searchHits.size)
        assertEquals(2, e.replaceAllHits("[^&]"))
        assertEquals(listOf("dog and [cat]", "a catalog", "[Cat]"), e.document.paragraphs().map { it.plainText() })
        // One step undoes Replace All.
        e.undo()
        assertEquals(listOf("dog and cat", "a catalog", "Cat"), e.document.paragraphs().map { it.plainText() })
    }

    @Test
    fun replacingWhileTrackingMarksTheChange() {
        val e = editor("old text")
        e.toggleTrackChanges()
        e.setSearch("old")
        assertEquals(1, e.replaceAllHits("new"))
        val runs = e.document.locations()[0].paragraph.runs
        assertTrue(runs.any { it.text == "old" && it.format.deleted })
        assertTrue(runs.any { it.text == "new" && it.format.inserted })
        // Text deleted with Track Changes is no match.
        assertTrue(e.searchHits.isEmpty())
    }

    @Test
    fun goToHeadingsTablesAndPages() {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("Intro").also { it.format.style = ParagraphStyle.HEADING1 })
        doc.addParagraph(Paragraph.of("text"))
        doc.blocks.add(TableBlock(Table(mutableListOf(com.therealityteam.aeropages.model.TableRow(mutableListOf(
            com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph.of("a"))), com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph.of("b"))),
        ))))))
        doc.addParagraph(Paragraph.of("More").also { it.format.style = ParagraphStyle.HEADING2 })
        val e = DocumentEditor().apply { load(doc) }
        e.setSelection(0, 0, 0)
        assertTrue(e.goToNext(DocumentEditor.GoTo.TABLE))
        assertTrue(e.document.locations()[e.active] is com.therealityteam.aeropages.model.CellLoc)
        assertTrue(e.goToNext(DocumentEditor.GoTo.HEADING))
        assertEquals("More", e.activeParagraph.plainText())
        assertFalse(e.goToNext(DocumentEditor.GoTo.HEADING))
        assertTrue(e.goToNext(DocumentEditor.GoTo.HEADING, back = true))
        assertEquals("Intro", e.activeParagraph.plainText())
        // Pages as the canvas laid them out: the last paragraph is on page 2.
        val last = e.document.locations().lastIndex
        e.pageOfLocation = { i -> if (i == last) 2 else 1 }
        assertTrue(e.goToPage(2))
        assertEquals(last, e.active)
        assertEquals(2, e.caretPage)
        assertFalse(e.goToPage(5))
    }
}
