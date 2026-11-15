package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.PagePresets
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layout ▸ Margins, Size and Page Setup: the presets a page has, the dialog applied to a section or the document as one step. */
class PageSetupTest {

    @Test
    fun presetsAreRecognised() {
        // Chinese Word's Normal: 2.54 cm above and below, 3.18 cm at the sides; the English one 2.54 cm all round.
        val zh = PageSetup(marginTopPt = 72f, marginBottomPt = 72f, marginLeftPt = 90f, marginRightPt = 90f)
        assertEquals(PagePresets.MarginKind.NORMAL, PagePresets.marginsOf(zh, cjk = true))
        assertNull(PagePresets.marginsOf(zh, cjk = false))
        assertEquals(PagePresets.MarginKind.NORMAL, PagePresets.marginsOf(PageSetup(), cjk = false))
        assertEquals(PagePresets.MarginKind.NARROW, PagePresets.marginsOf(PageSetup(marginTopPt = 36f, marginBottomPt = 36f, marginLeftPt = 36f, marginRightPt = 36f), true))
        // A4 either way round; Letter; a size of its own is none of them.
        assertEquals("A4", PagePresets.paperOf(PageSetup(widthPt = 841.9f, heightPt = 595.3f))?.name)
        assertEquals("A4", PagePresets.paperOf(PageSetup(widthPt = 595f, heightPt = 842f))?.name)
        assertEquals("Letter", PagePresets.paperOf(PageSetup())?.name)
        assertNull(PagePresets.paperOf(PageSetup(widthPt = 500f, heightPt = 700f)))
        // Word's default grid: 15.6 pt, 44 lines on A4 with 2.54 cm above and below.
        val a4 = PageSetup(widthPt = PagePresets.mm(210f), heightPt = PagePresets.mm(297f), linePitchPt = 15.6f)
        assertEquals(44, PagePresets.linesOf(a4))
        assertEquals(40, PagePresets.linesOf(a4.copy(linePitchPt = PagePresets.pitchFor(a4, 40))))
    }

    private fun twoSections(): DocumentEditor {
        val d = Document(mutableListOf())
        d.blocks.add(ParagraphBlock(Paragraph.of("Front").also { it.format.sectionBreak = PageSetup() }))
        d.blocks.add(ParagraphBlock(Paragraph.of("Body")))
        return DocumentEditor().apply { load(d) }
    }

    @Test
    fun pageSetupAppliesToTheSectionOrTheDocument() {
        val e = twoSections()
        e.setSelection(1, 0, 0)
        e.applyPageSetup(wholeDocument = false) { it.marginLeftPt = 90f; it.linePitchPt = 15.6f }
        assertEquals(90f, e.document.page.marginLeftPt)
        assertEquals(72f, (e.document.blocks[0] as ParagraphBlock).paragraph.format.sectionBreak!!.marginLeftPt)
        // The whole document, and different odd and even pages, in one step back.
        e.applyPageSetup(wholeDocument = true, differentOddEven = true) { it.headerPt = 42.5f }
        assertTrue(e.document.differentOddEven)
        assertEquals(42.5f, e.document.page.headerPt); assertEquals(42.5f, (e.document.blocks[0] as ParagraphBlock).paragraph.format.sectionBreak!!.headerPt)
        e.undo()
        assertFalse(e.document.differentOddEven)
        assertEquals(36f, e.document.page.headerPt)
        assertEquals(15.6f, e.document.page.linePitchPt)
        // Margins of their own on each side.
        e.setMargins(72f, 72f, 90f, 90f)
        assertSame(PagePresets.MarginKind.NORMAL, PagePresets.marginsOf(e.currentPage, cjk = true))
    }

    @Test
    fun spacingStepsBySixPoints() {
        val e = DocumentEditor().apply { load(Document(mutableListOf(ParagraphBlock(Paragraph.of("A").also { it.format.spaceBeforePt = 0f; it.format.spaceAfterPt = 8f })))) }
        e.stepSpacing(6f, 0f); e.stepSpacing(0f, -6f); e.stepSpacing(0f, -6f)
        val f = (e.document.blocks[0] as ParagraphBlock).paragraph.format
        assertEquals(6f, f.spaceBeforePt); assertEquals(0f, f.spaceAfterPt)
    }
}
