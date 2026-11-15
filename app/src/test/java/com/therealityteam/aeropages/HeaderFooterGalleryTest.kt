package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.HeaderFooterEdits
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.HeaderFooters.PAGE
import com.therealityteam.aeropages.model.HeaderFooters.PAGES
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Insert → Header / Footer / Page Number: built-in layouts, page numbers placed, moved and removed, the formatting kept. */
class HeaderFooterGalleryTest {
    private val phrases = listOf(PAGE, "Page $PAGE", "Page $PAGE of $PAGES")

    private fun editor(footer: Paragraph? = null, header: Paragraph? = null) = DocumentEditor().apply {
        load(Document.blank().also { it.footer = footer; it.header = header })
    }

    private fun centred(vararg runs: TextRun) = Paragraph(runs.toMutableList(), ParagraphFormat(align = TextAlign.CENTER))

    @Test
    fun aPageNumberGoesWhereItIsPickedAndMoves() {
        val e = editor()
        e.insertPageNumber(header = false, align = TextAlign.RIGHT, number = PAGE, phrases = phrases)
        assertEquals(PAGE, e.document.footer!!.plainText())
        assertEquals(TextAlign.RIGHT, e.document.footer!!.format.align)
        assertEquals(false to TextAlign.RIGHT, e.pageNumberPlace)
        // To the top, centred: the footer it leaves empty goes.
        e.insertPageNumber(header = true, align = TextAlign.CENTER, number = "Page $PAGE of $PAGES", phrases = phrases)
        assertNull(e.document.footer)
        assertEquals(true to TextAlign.CENTER, e.pageNumberPlace)
        assertEquals("Page $PAGE of $PAGES", e.pageNumberText)
        // Each change is one step back.
        e.undo()
        assertEquals(PAGE, e.document.footer!!.plainText()); assertNull(e.document.header)
        e.removePageNumbers(phrases)
        assertNull(e.document.footer); assertNull(e.pageNumberPlace)
    }

    @Test
    fun aFootersTextStaysWhereItIsAndKeepsItsLook() {
        val bold = CharacterFormat(bold = true)
        val e = editor(footer = centred(TextRun("Confidential", bold)))
        e.insertPageNumber(header = false, align = TextAlign.RIGHT, number = PAGE, phrases = phrases)
        val f = e.document.footer!!
        assertEquals("\tConfidential\t$PAGE", f.plainText())
        assertTrue(f.runs.filter { "Confidential" in it.text }.all { it.format.bold })
        // Moved to the left: the centred text stays centred.
        e.insertPageNumber(header = false, align = TextAlign.LEFT, number = PAGE, phrases = phrases)
        assertEquals("$PAGE\tConfidential", e.document.footer!!.plainText())
        assertEquals(false to TextAlign.LEFT, e.pageNumberPlace)
        // Removed: the text keeps its place (a tab before it, the paragraph still centred for its other lines).
        e.removePageNumbers(phrases)
        assertEquals("\tConfidential", e.document.footer!!.plainText())
        // Where the slot is taken, the number goes on a line of its own.
        val taken = editor(footer = centred(TextRun("Draft")))
        taken.insertPageNumber(header = false, align = TextAlign.CENTER, number = PAGE, phrases = phrases)
        assertEquals("Draft\n$PAGE", taken.document.footer!!.plainText())
        // A left-aligned line's text alone in the left slot loses its tab when the number goes.
        val left = Paragraph(mutableListOf(TextRun("Notes\t\t$PAGE")), ParagraphFormat(align = TextAlign.CENTER))
        assertTrue(HeaderFooterEdits.removePageNumbers(left, phrases))
        assertEquals("Notes", left.plainText()); assertEquals(TextAlign.LEFT, left.format.align)
        // Words round the number that are not a page-number phrase stay; only the field goes.
        val words = Paragraph.of("Report — $PAGE")
        HeaderFooterEdits.removePageNumbers(words, phrases)
        assertEquals("Report — ", words.plainText())
    }

    @Test
    fun typingChangesOnlyWhatChanged() {
        val p = Paragraph(mutableListOf(TextRun("Annual", CharacterFormat(bold = true)), TextRun(" report")))
        HeaderFooterEdits.setText(p, "Annual report 2026")
        assertEquals("Annual", p.runs[0].text); assertTrue(p.runs[0].format.bold)
        assertEquals("Annual report 2026", p.plainText()); assertFalse(p.runs.last().format.bold)
        // A slot's text replaced alone.
        val three = Paragraph.of("[Type here]\t[Type here]\t[Type here]")
        val slot = HeaderFooterEdits.lines(three.plainText())[0].slots!![1]
        HeaderFooterEdits.replaceRange(three, slot.start, slot.end, "Q3 Review")
        assertEquals("[Type here]\tQ3 Review\t[Type here]", three.plainText())
        assertEquals(0..10, HeaderFooterEdits.placeholder(three.plainText()))
    }

    @Test
    fun aBuiltInHeaderFillsInAndOpensForTyping() {
        val e = editor()
        e.document.differentOddEven = true
        e.applyHeaderFooterLayout(header = true, text = "[Document title]\t\t$PAGE", align = TextAlign.LEFT)
        assertEquals("[Document title]\t\t$PAGE", e.document.header!!.plainText())
        assertEquals("[Document title]\t\t$PAGE", e.document.evenHeader!!.plainText())
        assertEquals(true to TextAlign.RIGHT, e.pageNumberPlace)
        // The canvas's first page showing the default header takes the request once.
        assertFalse(e.takeHeaderFooterEdit(header = false, kind = DocumentEditor.HeaderKind.DEFAULT))
        assertTrue(e.takeHeaderFooterEdit(header = true, kind = DocumentEditor.HeaderKind.DEFAULT))
        assertFalse(e.takeHeaderFooterEdit(header = true, kind = DocumentEditor.HeaderKind.DEFAULT))
        // The even pages' footer gets the page number too.
        e.insertPageNumber(header = false, align = TextAlign.CENTER, number = PAGE, phrases = phrases)
        assertEquals(PAGE, e.document.evenFooter!!.plainText())
        assertEquals("[Document title]", e.document.header!!.plainText())
    }

    @Test
    fun wordAndOdfKeepTheSlots() {
        val d = Document.blank()
        d.header = Paragraph(mutableListOf(TextRun("Q3 Review", CharacterFormat(bold = true)), TextRun("\t\t$PAGE")))
        d.footer = Paragraph(mutableListOf(TextRun("\tConfidential\tPage $PAGE of $PAGES")), ParagraphFormat(align = TextAlign.CENTER))
        for (back in listOf(
            DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray())),
            OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()),
        )) {
            assertEquals("Q3 Review\t\t$PAGE", back.header!!.plainText())
            assertTrue(back.header!!.runs.first().format.bold)
            assertEquals("\tConfidential\tPage $PAGE of $PAGES", back.footer!!.plainText())
            assertEquals(TextAlign.RIGHT, HeaderFooterEdits.pageNumberAlign(back.header))
        }
    }
}
