package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Word's Font and Paragraph dialogs do to the document: what was changed in them, as one undo step. */
class WordFormatDialogsTest {
    private fun editor(vararg texts: String) = DocumentEditor().apply {
        load(Document(texts.map { ParagraphBlock(Paragraph.of(it)) }.toMutableList()))
    }

    @Test
    fun theFontDialogChangesTheSelectionInOneStep() {
        val e = editor("Hello world")
        e.setSelection(0, 0, 5)
        e.formatCharacters { it.bold = true; it.fontSizePt = 20f; it.letterSpacingPt = 1f; it.smallCaps = true }
        val runs = e.document.paragraphs()[0].runs
        assertEquals("Hello", runs[0].text)
        assertTrue(runs[0].format.bold); assertEquals(20f, runs[0].format.fontSizePt); assertEquals(1f, runs[0].format.letterSpacingPt); assertTrue(runs[0].format.smallCaps)
        assertFalse("the rest stays as it was", runs[1].format.bold)
        e.undo()
        assertTrue("one step undoes all of it", e.document.paragraphs()[0].runs.none { it.format.bold || it.format.smallCaps })
    }

    @Test
    fun theParagraphDialogChangesEverySelectedParagraphInOneStep() {
        val e = editor("First paragraph", "Second paragraph", "Third")
        e.setSelection(0, 2, 2); e.extendTo(1, 3)
        e.formatParagraphs { f ->
            f.align = TextAlign.JUSTIFY; f.indentLeftPt = 36f; f.firstLineIndentPt = -18f
            f.lineHeightPt = 18f; f.lineExact = true; f.pageBreakBefore = true
        }
        val (a, b, c) = e.document.paragraphs()
        for (p in listOf(a, b)) {
            assertEquals(TextAlign.JUSTIFY, p.format.align); assertEquals(36f, p.format.indentLeftPt); assertEquals(-18f, p.format.firstLineIndentPt)
            assertEquals(18f, p.format.lineHeightPt); assertTrue(p.format.lineExact); assertTrue(p.format.pageBreakBefore)
        }
        assertNull("the third was not selected", c.format.lineHeightPt)
        e.undo()
        assertTrue(e.document.paragraphs().none { it.format.pageBreakBefore || it.format.lineHeightPt != null || it.format.indentLeftPt != 0f })
    }

    @Test
    fun powerPointsFontAndParagraphDialogsWorkOnTheBoxBeingEdited() {
        val e = SlideEditor().apply { load(Presentation(mutableListOf(Slide(title = SlideBox("Quarterly review"))))) }
        val title = e.slide.title
        e.focus(title)
        e.setSelection(title, 0, 9)
        e.formatCharacters { it.italic = true; it.color = 0xFFC00000 }
        assertTrue(title.paragraph.runs[0].format.italic); assertEquals("Quarterly", title.paragraph.runs[0].text)
        assertFalse(title.paragraph.runs[1].format.italic)
        e.formatParagraph(TextAlign.RIGHT, 1.5f)
        assertEquals(TextAlign.RIGHT, title.paragraph.format.align); assertEquals(1.5f, title.lineSpacing)
        e.undo()
        assertEquals("the paragraph dialog is one step", 1f, e.slide.title.lineSpacing)
        assertTrue(e.slide.title.paragraph.runs[0].format.italic)
        val before = e.canUndo
        e.focus(e.slide.title)
        e.formatParagraph(e.slide.title.paragraph.format.align, e.slide.title.lineSpacing)
        assertEquals("nothing changed: no step", before, e.canUndo)
    }

    @Test
    fun theStylesPanesClearAllAndUpdateToMatch() {
        val e = editor("Results", "Body text")
        e.setSelection(0, 0, 0); e.setStyle(ParagraphStyle.HEADING1)
        e.setSelection(0, 0, 7); e.setColor(0xFFC00000)
        assertEquals(1, e.styleUses(ParagraphStyle.HEADING1))
        e.setSelection(0, 3, 3)
        e.clearAll()
        val p = e.document.paragraphs()[0]
        assertEquals(ParagraphStyle.NORMAL, p.format.style)
        assertTrue("direct formatting gone", p.runs.all { it.format.color == null && !it.format.bold })
        e.undo()
        assertEquals("one step", ParagraphStyle.HEADING1, e.document.paragraphs()[0].format.style)
        assertEquals(0xFFC00000, e.document.paragraphs()[0].runs[0].format.color)
        // Update Heading 2 to match the body paragraph made 30 pt: the style is defined from it.
        e.setSelection(1, 0, 9); e.setFontSize(30f)
        e.updateStyleFromSelection(ParagraphStyle.HEADING2)
        assertEquals(30f, e.resolvedStyle(ParagraphStyle.HEADING2).sizePt)
        assertEquals("the paragraph keeps its own style", ParagraphStyle.NORMAL, e.document.paragraphs()[1].format.style)
    }
}
