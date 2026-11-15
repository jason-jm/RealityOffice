package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.PAGE_BREAK
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Test

/** Word's Page Break (Ctrl+Enter): split at the caret, the break between, the caret at the top of the new page. */
class PageBreakCommandTest {
    private fun editor(text: String) = DocumentEditor().apply { load(Document(mutableListOf(ParagraphBlock(Paragraph.of(text))))) }

    @Test
    fun theParagraphSplitsAtTheCaret() {
        val e = editor("HelloWorld")
        e.setSelection(0, 5, 5)
        e.insertPageBreak()
        assertEquals(listOf("Hello", PAGE_BREAK, "World"), e.document.paragraphs().map { it.plainText() })
        assertEquals(listOf(2, 0, 0), listOf(e.active, e.selStart, e.selEnd))
        e.undo()
        assertEquals(listOf("HelloWorld"), e.document.paragraphs().map { it.plainText() })
    }

    @Test
    fun atTheEndANewEmptyPageTakesTheCaret() {
        val e = editor("Hello")
        e.setSelection(0, 5, 5)
        e.insertPageBreak()
        assertEquals(listOf("Hello", PAGE_BREAK, ""), e.document.paragraphs().map { it.plainText() })
        assertEquals(2, e.active)
    }

    @Test
    fun aSelectionIsReplacedByTheBreak() {
        val e = editor("Hello big World")
        e.setSelection(0, 5, 9)
        e.insertPageBreak()
        assertEquals(listOf("Hello", PAGE_BREAK, " World"), e.document.paragraphs().map { it.plainText() })
    }
}
