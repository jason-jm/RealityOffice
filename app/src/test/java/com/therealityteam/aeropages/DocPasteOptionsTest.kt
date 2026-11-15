package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocClipboard
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.DocumentEditor.DocPos
import com.therealityteam.aeropages.editor.DocumentEditor.PasteMode
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copy and paste in Pages keep the formatting, and Word's Paste Options paste the same text again another way. */
class DocPasteOptionsTest {

    /** A heading "Hello world" (world bold, red), a body line, and an empty paragraph to paste into. */
    private fun editor() = DocumentEditor().apply {
        val heading = Paragraph(mutableListOf(TextRun("Hello "), TextRun("world", CharacterFormat(bold = true, color = 0xFFC00000))),
            ParagraphFormat(style = ParagraphStyle.HEADING1))
        val body = Paragraph(mutableListOf(TextRun("Second line", CharacterFormat(italic = true))))
        load(Document(mutableListOf(ParagraphBlock(heading), ParagraphBlock(body), ParagraphBlock(Paragraph.of("")))))
    }

    private fun DocumentEditor.copyAndPasteAtEnd(from: DocPos, to: DocPos) {
        selectRange(from, to)
        val clip = copiedClip()!!
        DocClipboard.clip = clip
        moveCaret(2, 0)
        pasteClip(clip)
    }

    @Test
    fun aPasteKeepsTheCopiedFormattingAndParagraphMarks() {
        val e = editor()
        e.copyAndPasteAtEnd(DocPos(0, 0), DocPos(1, 6))
        val ps = e.document.paragraphs()
        assertEquals(4, ps.size)
        assertEquals("Hello world", ps[2].plainText())
        assertEquals("the copied paragraph mark brings the heading's style", ParagraphStyle.HEADING1, ps[2].format.style)
        val world = ps[2].runs.first { it.text.contains("world") }
        assertTrue(world.format.bold); assertEquals(0xFFC00000, world.format.color)
        assertEquals("Second", ps[3].plainText())
        assertTrue(ps[3].runs.first().format.italic)
        assertEquals("the last part joins the paragraph pasted into", ParagraphStyle.NORMAL, ps[3].format.style)
        val mark = e.pasteOptions!!
        assertEquals(PasteMode.KEEP_SOURCE, mark.mode)
        assertEquals(DocPos(3, 6), mark.end)
    }

    @Test
    fun pasteOptionsMergeOrKeepTheTextOnlyInOneUndoStep() {
        val e = editor()
        e.copyAndPasteAtEnd(DocPos(0, 0), DocPos(1, 6))

        assertTrue(e.repaste(PasteMode.MERGE))
        var ps = e.document.paragraphs()
        assertEquals(4, ps.size)
        assertEquals("merged: the formatting where it landed", ParagraphStyle.NORMAL, ps[2].format.style)
        val world = ps[2].runs.first { it.text.contains("world") }
        assertTrue("…keeping the copied emphasis", world.format.bold)
        assertNull("…but not its colour", world.format.color)
        assertEquals(PasteMode.MERGE, e.pasteOptions!!.mode)

        assertTrue(e.repaste(PasteMode.TEXT_ONLY))
        ps = e.document.paragraphs()
        assertEquals("Hello world", ps[2].plainText())
        assertTrue(ps[2].runs.none { it.format.bold })
        assertFalse(ps[3].runs.first().format.italic)

        e.undo()
        ps = e.document.paragraphs()
        assertEquals("one step takes the paste away, whichever way it went", 3, ps.size)
        assertEquals("", ps[2].plainText())
        assertNull("undo puts the button away", e.pasteOptions)
    }

    @Test
    fun withinOneParagraphTheTextKeepsItsFormatButNotItsParagraph() {
        val e = editor()
        e.copyAndPasteAtEnd(DocPos(0, 6), DocPos(0, 11))
        val p = e.document.paragraphs()[2]
        assertEquals("world", p.plainText())
        assertTrue(p.runs[0].format.bold)
        assertEquals(ParagraphStyle.NORMAL, p.format.style)
        assertNotNull(e.pasteOptions)
        // Typing puts the button away.
        e.replaceText(2, "world", "world!")
        assertNull(e.pasteOptions)
        assertFalse(e.repaste(PasteMode.TEXT_ONLY))
    }

    @Test
    fun theClipboardMatchesOnlyItsOwnText() {
        val e = editor()
        e.selectRange(DocPos(0, 0), DocPos(1, 6))
        DocClipboard.clip = e.copiedClip()
        assertNotNull(DocClipboard.matching("Hello world\r\nSecond"))
        assertNull(DocClipboard.matching("something else"))
    }

    @Test
    fun aTrackedPasteIsAnInsertion() {
        val e = editor()
        e.toggleTrackChanges()
        e.copyAndPasteAtEnd(DocPos(0, 6), DocPos(0, 11))
        val run = e.document.paragraphs()[2].runs.first { it.text == "world" }
        assertTrue(run.format.inserted)
        assertTrue("its own formatting still", run.format.bold)
    }
}
