package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A document ends with a paragraph, as in Word and LibreOffice: after a table at its end there is a line to type on. */
class DocumentEndTest {

    @Test fun aTableInsertedAtTheEndHasAParagraphAfterIt() {
        val ed = DocumentEditor().also { it.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("Intro"))))) }
        ed.setSelection(0, 5, 5)
        ed.insertTable(2, 2)
        assertEquals(listOf("ParagraphBlock", "TableBlock", "ParagraphBlock"), ed.document.blocks.map { it.javaClass.simpleName })
        // Undone together.
        ed.undo()
        assertEquals(1, ed.document.blocks.size)
        ed.redo()
        assertEquals(3, ed.document.blocks.size)
    }

    @Test fun aFileEndingWithATableGetsItsParagraphOnOpenWithoutBeingChanged() {
        val ed = DocumentEditor().also { it.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("Intro")), TableBlock(Table.of(1, 2))))) }
        assertTrue(ed.document.blocks.last() is ParagraphBlock)
        assertEquals(3, ed.document.blocks.size)
        assertFalse("nothing to save for it", ed.dirty)
    }
}
