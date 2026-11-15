package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages editor behaviors that don't need Android — pending caret format (Word-style). */
class DocumentEditorTest {

    private fun editorWith(text: String): DocumentEditor {
        val ed = DocumentEditor()
        ed.load(Document.blank())
        ed.setSelection(0, 0, 0)
        if (text.isNotEmpty()) ed.replaceText(0, "", text)
        return ed
    }

    @Test
    fun collapsedToggleDoesNotReformatParagraph() {
        val ed = editorWith("hello world")
        ed.setSelection(0, 5, 5)
        ed.toggleBold()
        // The document is untouched; only a pending format is armed.
        assertTrue(ed.activeParagraph.runs.none { it.format.bold })
        assertNotNull(ed.pendingFormat)
        assertTrue("ribbon state shows armed bold", ed.currentFormat.bold)
    }

    @Test
    fun pendingFormatAppliesToNextTypedText() {
        val ed = editorWith("ab")
        ed.setSelection(0, 1, 1)
        ed.toggleBold()
        // Type "X" at the caret (field text "ab" -> "aXb").
        ed.replaceText(0, "ab", "aXb")
        val p = ed.activeParagraph
        assertEquals("aXb", p.plainText())
        val bolds = buildList { var i = 0; p.runs.forEach { r -> r.text.forEach { _ -> add(r.format.bold); i++ } } }
        assertEquals(listOf(false, true, false), bolds)
        // Pending is consumed: typing again at the caret is plain.
        assertNull(ed.pendingFormat)
    }

    @Test
    fun pendingFormatStacksToggles() {
        val ed = editorWith("ab")
        ed.setSelection(0, 1, 1)
        ed.toggleBold()
        ed.toggleItalic()
        ed.replaceText(0, "ab", "aXb")
        val run = ed.activeParagraph.runs.first { it.text == "X" }
        assertTrue(run.format.bold)
        assertTrue(run.format.italic)
        // Toggling twice cancels while pending (Y types plain; it may coalesce with "b").
        ed.setSelection(0, 2, 2)
        ed.toggleBold(); ed.toggleBold()
        ed.replaceText(0, "aXb", "aXYb")
        val charBold = buildList { ed.activeParagraph.runs.forEach { r -> r.text.forEach { _ -> add(r.format.bold) } } }
        assertFalse("Y (index 2) is plain", charBold[2])
    }

    @Test
    fun caretMoveDropsPendingFormat() {
        val ed = editorWith("hello")
        ed.setSelection(0, 2, 2)
        ed.toggleBold()
        assertNotNull(ed.pendingFormat)
        ed.setSelection(0, 4, 4) // move away
        assertNull(ed.pendingFormat)
        ed.replaceText(0, "hello", "helXlo")
        assertTrue(ed.activeParagraph.runs.none { it.format.bold })
    }

    @Test
    fun rangedToggleStillFormatsSelection() {
        val ed = editorWith("hello")
        ed.setSelection(0, 0, 5)
        ed.toggleBold()
        assertTrue(ed.activeParagraph.runs.all { it.format.bold })
        assertNull("no pending when a real selection was formatted", ed.pendingFormat)
    }

    @Test
    fun deleteFootnoteRenumbersAnchors() {
        val ed = editorWith("")
        // Three footnotes inserted in sequence: text becomes "[1][2][3]".
        ed.insertFootnote(); ed.insertFootnote(); ed.insertFootnote()
        assertEquals(3, ed.document.footnotes.size)
        assertEquals("[1][2][3]", ed.activeParagraph.plainText())
        // Delete the middle footnote: anchor [2] removed, [3] renumbers to [2].
        ed.deleteFootnote(ed.document.footnotes[1])
        assertEquals(2, ed.document.footnotes.size)
        assertEquals("[1][2]", ed.activeParagraph.plainText())
        // Both remaining anchors are still superscript.
        assertTrue(ed.activeParagraph.runs.filter { it.text.startsWith("[") }.all { it.format.superscript })
    }

    @Test
    fun tsvPastesAsTable() {
        val ed = editorWith("正文")
        val ok = ed.insertTableFromTsv("名称\t数量\n苹果\t3")
        assertTrue(ok)
        val table = ed.document.blocks.filterIsInstance<com.therealityteam.aeropages.model.TableBlock>().first().table
        assertEquals(2, table.rows.size)
        assertEquals("名称", table.rows[0].cells[0].content[0].plainText())
        assertEquals("3", table.rows[1].cells[1].content[0].plainText())
        // Non-tabular text is declined so the caller falls back to plain paste.
        assertFalse(ed.insertTableFromTsv("just a sentence"))
    }
}
