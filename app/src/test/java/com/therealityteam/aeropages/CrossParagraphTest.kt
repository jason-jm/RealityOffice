package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.DocumentEditor.DocPos
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Selections that run across paragraphs: extending, copying, deleting, typing over, pasting lines, formatting. */
class CrossParagraphTest {

    private fun editor(vararg texts: String): DocumentEditor {
        val d = Document()
        for (t in texts) d.addParagraph(Paragraph(mutableListOf(TextRun(t, CharacterFormat()))))
        return DocumentEditor().apply { load(d) }
    }

    private fun DocumentEditor.texts() = document.locations().map { it.paragraph.plainText() }
    private fun DocumentEditor.para(i: Int) = document.locations()[i].paragraph

    /** Selects from paragraph [a] offset [ao] to paragraph [b] offset [bo], as Shift+click would. */
    private fun DocumentEditor.span(a: Int, ao: Int, b: Int, bo: Int) { setSelection(a, ao, ao); extendTo(b, bo) }

    @Test
    fun extendingAcrossParagraphsMakesASpanAndComingBackUndoesIt() {
        val e = editor("Alpha", "Bravo", "Charlie")
        e.setSelection(0, 2, 2)
        e.extendTo(2, 3)
        assertEquals(DocPos(0, 2) to DocPos(2, 3), e.span())
        assertEquals("the caret's paragraph shows its own part", 0, e.selStart)
        assertEquals(3, e.selEnd)
        assertEquals("pha\nBravo\nCha", e.selectedText())
        assertEquals(0 until 3, e.spanPartOf(2, 7))
        assertEquals(2 until 5, e.spanPartOf(0, 5))
        assertEquals("a middle paragraph is covered whole", 0 until 5, e.spanPartOf(1, 5))
        assertNull(e.spanPartOf(3, 0))
        e.extendTo(0, 4)
        assertFalse("back in the anchor's paragraph", e.hasSpan)
        assertEquals("ph", e.selectedText())
        // Backwards: the anchor after the caret.
        e.setSelection(2, 4, 4)
        e.extendTo(0, 1)
        assertEquals(DocPos(0, 1) to DocPos(2, 4), e.span())
        assertEquals("the field shows from the caret to its paragraph's end", 5, e.selStart)
        e.setSelection(1, 0, 0)
        assertFalse("a click drops the span", e.hasSpan)
    }

    @Test
    fun deletingASpanJoinsItsEndsInOneUndoStep() {
        val e = editor("Alpha", "Bravo", "Charlie", "Delta")
        e.span(0, 2, 2, 3)
        e.deleteSelection()
        assertEquals(listOf("Alrlie", "Delta"), e.texts())
        assertEquals(0, e.active); assertEquals(2, e.selStart)
        assertFalse(e.hasSpan)
        e.undo()
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), e.texts())
    }

    @Test
    fun typingOverASpanReplacesItAndLineBreaksMakeParagraphs() {
        val e = editor("Alpha", "Bravo", "Charlie")
        e.span(0, 2, 1, 5)
        e.typeOver("X")
        assertEquals(listOf("AlX", "Charlie"), e.texts())
        assertEquals(3, e.selStart)
        e.moveCaret(1, 3)
        e.typeOver("1\n2\n3")
        assertEquals(listOf("AlX", "Cha1", "2", "3rlie"), e.texts())
        assertEquals("the caret after the pasted text", 3, e.active)
        assertEquals(1, e.selStart)
        e.undo()
        assertEquals("one undo step", listOf("AlX", "Charlie"), e.texts())
    }

    @Test
    fun pastingLinesKeepsTheParagraphsShape() {
        val e = editor("Title here")
        e.para(0).format.style = ParagraphStyle.HEADING1
        e.moveCaret(0, 5)
        e.insertAtCaret("one\r\ntwo")
        assertEquals(listOf("Titleone", "two here"), e.texts())
        assertEquals(ParagraphStyle.HEADING1, e.para(1).format.style)
    }

    @Test
    fun characterFormattingCoversEveryParagraphOfTheSpan() {
        val e = editor("Alpha", "Bravo", "Charlie")
        e.span(0, 3, 2, 2)
        e.toggleBold()
        assertTrue(e.para(1).runs.all { it.format.bold })
        assertEquals("before the span stays plain", false, e.para(0).runs.first().format.bold)
        assertEquals("Alp", e.para(0).runs.first().text)
        assertEquals("Ch", e.para(2).runs.first { it.format.bold }.text)
        // Word's toggle: all bold → the next press takes it off.
        e.toggleBold()
        assertTrue(e.document.locations().all { loc -> loc.paragraph.runs.none { it.format.bold } })
        // Partly bold → the press makes it all bold.
        e.setSelection(1, 0, 2); e.toggleBold()
        e.span(0, 0, 2, 7)
        e.toggleBold()
        assertTrue(e.document.locations().all { loc -> loc.paragraph.runs.all { it.format.bold } })
    }

    @Test
    fun paragraphFormattingCoversEveryParagraphTheSpanTouches() {
        val e = editor("Alpha", "Bravo", "Charlie", "Delta")
        e.span(0, 1, 2, 0)          // ends at the very start of Charlie: Charlie is not touched
        e.setAlign(TextAlign.CENTER)
        assertEquals(listOf(TextAlign.CENTER, TextAlign.CENTER), (0..1).map { e.para(it).format.align })
        assertEquals(false, e.para(2).format.align == TextAlign.CENTER)
        e.span(1, 0, 3, 2)
        e.toggleBullet()
        assertEquals(listOf(ListKind.NONE, ListKind.BULLET, ListKind.BULLET, ListKind.BULLET), (0..3).map { e.para(it).format.listKind })
        e.setStyle(ParagraphStyle.HEADING2)
        assertEquals(ParagraphStyle.HEADING2, e.para(3).format.style)
    }

    @Test
    fun selectAllAndCaretMovesAcrossParagraphs() {
        val e = editor("Alpha", "Bravo", "Charlie")
        e.selectAll()
        assertEquals(DocPos(0, 0) to DocPos(2, 7), e.span())
        assertEquals("Alpha\nBravo\nCharlie", e.selectedText())
        val reveal = e.revealRequest
        e.moveCaret(1, 99)
        assertFalse(e.hasSpan)
        assertEquals(1, e.active); assertEquals("clamped to the paragraph", 5, e.selStart)
        assertTrue("moving the caret asks the canvas to show it", e.revealRequest > reveal)
        e.moveCaret(2, 0, extend = true)
        assertFalse("from the end of Bravo to the start of Charlie is an empty span", e.selectedText().any { it.isLetter() })
        e.selectAll(); e.changeCase(com.therealityteam.aeropages.editor.CaseMode.UPPER)
        assertEquals(listOf("ALPHA", "BRAVO", "CHARLIE"), e.texts())
    }

    @Test
    fun pointerSelectionsSetBothEndsAndPaintTheirParts() {
        val e = editor("Alpha", "Bravo", "Charlie")
        e.selectRange(DocPos(2, 3), DocPos(0, 1))              // dragged backwards from Charlie
        assertEquals(DocPos(0, 1) to DocPos(2, 3), e.span())
        assertEquals("the caret follows the pointer", DocPos(0, 1), e.selectionFocus())
        assertEquals(DocPos(2, 3), e.selectionAnchor())
        assertEquals(1 until 5, e.selectionPartOf(0, 5))
        assertEquals(0 until 3, e.selectionPartOf(2, 7))
        // Within one paragraph: the caret paragraph's own selection, painted when drawn as plain text.
        e.selectRange(DocPos(1, 1), DocPos(1, 4))
        assertFalse(e.hasSpan)
        assertEquals(1 until 4, e.selectionPartOf(1, 5))
        assertNull(e.selectionPartOf(0, 5))
        assertEquals(DocPos(1, 1) to DocPos(1, 4), e.selectionEnds())
        e.moveCaret(1, 2)
        assertNull("a caret paints nothing", e.selectionPartOf(1, 5))
    }

    @Test
    fun spansIntoATableOnlyClearTheirText() {
        val e = editor("Before")
        e.moveCaret(0, 6)
        e.insertTable(1, 2)
        val all = e.document.locations()
        val cell = all.indexOfFirst { it is com.therealityteam.aeropages.model.CellLoc }
        e.setSelection(cell, 0, 0); e.insertAtCaret("cell text")
        e.span(0, 3, cell, 4)
        e.deleteSelection()
        assertEquals("Bef", e.para(0).plainText())
        assertEquals("the table stays; its first cell lost the covered text", " text", e.para(cell).plainText())
    }
}
