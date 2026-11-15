package com.therealityteam.aeropages

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TabAlign
import com.therealityteam.aeropages.model.TabLeader
import com.therealityteam.aeropages.model.TabStop
import com.therealityteam.aeropages.ui.PagesView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** View ▸ Ruler's indents and tab stops, dragged into place, and View ▸ Zoom's One Page, Multiple Pages and Page Width. */
class RulerAndZoomTest {

    private fun editor(vararg lines: String) = DocumentEditor().apply {
        load(Document(mutableListOf()).also { d -> lines.forEach { d.addParagraph(Paragraph.of(it)) } })
    }

    @Test
    fun theIndentMarkersSetTheParagraphsIndents() {
        val e = editor("one", "two", "three")
        e.setSelection(1, 0, 0)
        // The first-line marker at 57 pt, the hanging one (and the lines after the first) at 36 pt, the right one 18 pt in.
        e.setIndents(36f, 21f, 18f)
        val f = e.document.locations()[1].paragraph.format
        assertEquals(36f, f.indentLeftPt); assertEquals(21f, f.firstLineIndentPt); assertEquals(18f, f.indentRightPt)
        assertEquals(0f, e.document.locations()[0].paragraph.format.indentLeftPt)
        // Hanging: the first line out of the left indent — never into the margin.
        e.setIndents(18f, -40f, 0f)
        assertEquals(-18f, f.firstLineIndentPt)
        e.setIndents(-10f, 0f, -5f)
        assertEquals(0f, f.indentLeftPt); assertEquals(0f, f.indentRightPt)
        // Each drag is one step to undo.
        e.undo(); e.undo()
        val back = e.document.locations()[1].paragraph.format
        assertEquals(36f, back.indentLeftPt); assertEquals(21f, back.firstLineIndentPt)
        // Across a selection of paragraphs, all of them.
        e.selectRange(DocumentEditor.DocPos(0, 0), DocumentEditor.DocPos(2, 2))
        e.setIndents(72f, 0f, 0f)
        assertTrue(e.document.locations().all { it.paragraph.format.indentLeftPt == 72f })
    }

    @Test
    fun tabStopsAreSetMovedAndCleared() {
        val e = editor("a\tb", "c\td")
        e.selectRange(DocumentEditor.DocPos(0, 0), DocumentEditor.DocPos(1, 1))
        e.addTabStop(TabStop(144f))
        e.addTabStop(TabStop(72f, TabAlign.RIGHT, TabLeader.DOT))
        // One within a point of a stop takes its place; the stops stay in order.
        e.addTabStop(TabStop(144.5f, TabAlign.CENTER))
        val stops = { i: Int -> e.document.locations()[i].paragraph.format.tabStops }
        assertEquals(listOf(72f, 144.5f), stops(0).map { it.posPt })
        assertEquals(stops(0), stops(1))
        // Moving keeps its kind and leader.
        e.moveTabStop(72f, 200f)
        assertEquals(TabStop(200f, TabAlign.RIGHT, TabLeader.DOT), stops(0).last())
        assertEquals(listOf(144.5f, 200f), stops(1).map { it.posPt })
        // Dragged off the ruler: gone; a paragraph without that stop is left as it is.
        e.setSelection(0, 0, 0)
        e.moveTabStop(144.5f, null)
        assertEquals(listOf(200f), stops(0).map { it.posPt })
        assertEquals(2, stops(1).size)
        e.undo()
        assertEquals(2, stops(0).size)
    }

    @Test
    fun theTabsDialogSetsTheStopsAndTheDefaultSpacing() {
        val e = editor("Name\tPage", "other")
        e.setSelection(0, 0, 0)
        e.setTabs(listOf(TabStop(400f, TabAlign.RIGHT, TabLeader.DOT), TabStop(72f)), 28f)
        assertEquals(listOf(72f, 400f), e.document.locations()[0].paragraph.format.tabStops.map { it.posPt })
        assertEquals(TabLeader.DOT, e.document.locations()[0].paragraph.format.tabStops.last().leader)
        assertTrue(e.document.locations()[1].paragraph.format.tabStops.isEmpty())
        assertEquals(28f, e.document.defaultTabPt)
        // One step: the stops and the default spacing come back together.
        e.undo()
        assertTrue(e.document.locations()[0].paragraph.format.tabStops.isEmpty())
        assertEquals(36f, e.document.defaultTabPt)
    }

    private fun view() = PagesView(mutableFloatStateOf(1f), mutableStateOf(false), mutableStateOf(true))

    @Test
    fun theZoomPresetsFitThePageToTheWindow() {
        val v = view()
        // Before the canvas is laid out there is nothing to fit to.
        v.onePage(595f, 842f)
        assertEquals(1f, v.zoom)
        v.canvasWidthDp = 1000f; v.canvasHeightDp = 600f
        // One Page: the whole A4 page with the space above and below it.
        v.onePage(595f, 842f)
        assertEquals(600f / (842f + 32f), v.zoom, 0.0001f)
        assertFalse(v.multiPage)
        // Multiple Pages: two across (here the height still decides), pages side by side.
        v.multiplePages(595f, 842f)
        assertTrue(v.multiPage)
        assertEquals(600f / (842f + 32f), v.zoom, 0.0001f)
        // Zooming in or out keeps them side by side; a picked zoom puts one to a row again.
        v.step(0.1f)
        assertEquals(0.8f, v.zoom, 0.0001f)
        assertTrue(v.multiPage)
        v.pageWidth(595f)
        assertEquals((1000f - 48f) / 595f, v.zoom, 0.0001f)
        assertFalse(v.multiPage)
        // A wide, short window: two pages across take less than the height allows.
        v.canvasWidthDp = 900f; v.canvasHeightDp = 2000f
        v.multiplePages(595f, 842f)
        assertEquals((900f - 48f) / (2 * 595f + 16f), v.zoom, 0.0001f)
        v.zoomTo(10f)
        assertEquals(PagesView.MAX_ZOOM, v.zoom)
        assertEquals(400, v.percent)
    }
}
