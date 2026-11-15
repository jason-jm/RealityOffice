package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocClipboard
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.DocumentEditor.DocPos
import com.therealityteam.aeropages.editor.DocumentEditor.PasteMode
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PowerPoint's paste: text keeps its formatting across slides, documents and decks, and Paste Options paste it again. */
class SlidePasteOptionsTest {

    /** A slide titled "Big red" (red, 40 pt) with a body "Notes" (18 pt). */
    private fun deck() = SlideEditor().apply {
        val slide = Slide(layout = SlideLayout.TITLE_CONTENT)
        slide.title.paragraph.runs.clear(); slide.title.paragraph.runs.add(TextRun("Big red", CharacterFormat(fontSizePt = 40f, color = 0xFFC00000, bold = true)))
        slide.body.paragraph.runs.clear(); slide.body.paragraph.runs.add(TextRun("Notes", CharacterFormat(fontSizePt = 18f)))
        load(Presentation(mutableListOf(slide)))
    }

    private fun SlideEditor.copyTitleIntoBody() {
        setSelection(slide.title, 0, slide.title.text.length)
        DocClipboard.clip = copiedClip()
        setSelection(slide.body, slide.body.text.length, slide.body.text.length)
        assertTrue(pasteClip(DocClipboard.clip!!))
    }

    @Test
    fun aPasteKeepsTheSourceFormattingAndTheOptionsChangeItInOneStep() {
        val e = deck()
        e.copyTitleIntoBody()
        val body = e.slide.body
        assertEquals("NotesBig red", body.text)
        val pasted = body.paragraph.runs.first { it.text.contains("Big") }
        assertEquals(40f, pasted.format.fontSizePt); assertEquals(0xFFC00000, pasted.format.color)
        assertNotNull(e.pasteOptionsFor(body))
        assertNull("not the title's", e.pasteOptionsFor(e.slide.title))

        assertTrue(e.repaste(PasteMode.MERGE))
        val merged = e.slide.body.paragraph.runs.first { it.text.contains("Big") }
        assertEquals("the destination's size", 18f, merged.format.fontSizePt)
        assertNull("…and colour", merged.format.color)
        assertTrue("…with the copied emphasis", merged.format.bold)

        assertTrue(e.repaste(PasteMode.TEXT_ONLY))
        assertTrue(e.slide.body.paragraph.runs.none { it.format.bold })
        assertEquals("NotesBig red", e.slide.body.text)

        e.undo()
        assertEquals("one step takes the paste away", "Notes", e.slide.body.text)
        assertNull(e.pasteOptions)
    }

    @Test
    fun typingPutsTheButtonAway() {
        val e = deck()
        e.copyTitleIntoBody()
        val body = e.slide.body
        e.replaceText(body, body.text, body.text + "!")
        assertNull(e.pasteOptions)
        assertFalse(e.repaste(PasteMode.TEXT_ONLY))
    }

    @Test
    fun paragraphsFromPagesBecomeLinesAndLinesBecomeParagraphs() {
        val doc = DocumentEditor().apply {
            load(Document(mutableListOf(ParagraphBlock(Paragraph(mutableListOf(TextRun("One", CharacterFormat(italic = true))))), ParagraphBlock(Paragraph.of("Two")))))
            selectRange(DocPos(0, 0), DocPos(1, 3))
        }
        val clip = doc.copiedClip()!!
        val e = deck()
        e.setSelection(e.slide.body, 0, e.slide.body.text.length)
        e.pasteClip(clip)
        assertEquals("One\nTwo", e.slide.body.text)
        assertTrue(e.slide.body.paragraph.runs.first().format.italic)

        // And back: a slide's two lines are two paragraphs in Pages.
        e.setSelection(e.slide.body, 0, e.slide.body.text.length)
        val back = e.copiedClip()!!
        assertEquals(2, back.paragraphs.size)
        val d2 = DocumentEditor().apply { load(Document(mutableListOf(ParagraphBlock(Paragraph.of(""))))) }
        d2.pasteClip(back)
        assertEquals(listOf("One", "Two"), d2.document.paragraphs().map { it.plainText() })
    }
}
