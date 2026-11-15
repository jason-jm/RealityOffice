package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** PowerPoint's Selection Pane: objects listed top first, selected, hidden, renamed and reordered; names and visibility kept in pptx and ODP. */
class SlideSelectionPaneTest {

    /** A picture under two shapes (a named star, a hidden box) and a table on top. */
    private fun deck(): Presentation {
        val slide = Slide()
        slide.images += SlideImage(TestPng.blend(8, 8, 0x3366CC), "image/png", 8f, 8f, 0.1f, 0.1f, 0.3f, 0.3f)
        slide.shapes += SlideShape(ShapeType.STAR, xPct = 0.5f, yPct = 0.2f, wPct = 0.2f, hPct = 0.2f).apply { objectName = "Gold star" }
        slide.shapes += SlideShape(ShapeType.RECT, xPct = 0.6f, yPct = 0.5f, wPct = 0.2f, hPct = 0.2f).apply { hidden = true }
        slide.tables += SlideTable.create(2, 2).apply { xPct = 0.1f; yPct = 0.6f; wPct = 0.4f; hPct = 0.2f; objectName = "Budget" }
        return Presentation(mutableListOf(slide))
    }

    @Test
    fun thePaneListsTopFirstAndActsOnEachObject() {
        val e = SlideEditor().apply { load(deck()) }
        val objs = e.paneObjects()
        assertEquals(listOf("Budget", null, "Gold star", null), objs.map { it.objectName })
        assertTrue("the table is drawn on top", objs.first() is SlideTable)
        // A hidden object cannot be picked; a shown one is selected on the slide.
        e.selectObject(objs[1]); assertNull(e.selectedObject)
        e.selectObject(objs[2]); assertSame(objs[2], e.selectedObject)
        // Hiding the selected object drops the selection; one step back shows it again.
        e.setObjectHidden(objs[2], true)
        assertNull(e.selectedObject); assertTrue(e.slide.shapes[0].hidden)
        e.undo(); assertFalse(e.slide.shapes[0].hidden)
        // Show All / Hide All, each one step.
        e.setAllObjectsHidden(false); assertTrue(e.paneObjects().none { it.hidden })
        e.setAllObjectsHidden(true); assertTrue(e.paneObjects().all { it.hidden })
        e.undo(); e.undo(); assertTrue(e.slide.shapes[1].hidden)
        // Renaming: blank goes back to the pane's own name.
        val star = e.slide.shapes[0]
        e.renameObject(star, "  Big star "); assertEquals("Big star", e.slide.shapes[0].objectName)
        e.renameObject(e.slide.shapes[0], " "); assertNull(e.slide.shapes[0].objectName)
        // Up brings it forward among its kind: the star now drawn after the box.
        val s0 = e.slide.shapes[0]
        assertFalse(e.canMoveInPane(s0, up = false)); assertTrue(e.canMoveInPane(s0, up = true))
        e.moveInPane(s0, up = true)
        assertSame(s0, e.slide.shapes[1])
        e.undo(); assertEquals(ShapeType.STAR, e.slide.shapes[0].type)
    }

    @Test
    fun pptxKeepsNamesAndHidden() {
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("""name="Gold star""""))
        assertTrue(xml.contains("""name="Budget""""))
        assertTrue(xml.contains("""hidden="1""""))
        val s = PptxReader.read(bytes).slides[0]
        assertEquals("Gold star", s.shapes.first { it.type == ShapeType.STAR }.objectName)
        assertTrue(s.shapes.first { it.type == ShapeType.RECT }.hidden)
        assertNull("a numbered name is the pane's to give", s.shapes.first { it.type == ShapeType.RECT }.objectName)
        assertNull(s.images.single().objectName)
        assertEquals("Budget", s.tables.single().objectName)
    }

    @Test
    fun odpKeepsNamesAndHiddenAsLibreOfficeDoes() {
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertTrue(content.contains("""draw:name="Gold star""""))
        assertTrue(content.contains("""drawooo:display="none""""))
        assertTrue("the namespace is declared", content.contains("xmlns:drawooo="))
        val s = OdpReader.read(bytes).slides[0]
        assertEquals("Gold star", s.shapes.first { it.type == ShapeType.STAR }.objectName)
        assertTrue(s.shapes.first { it.type == ShapeType.RECT }.hidden)
        assertEquals("Budget", s.tables.single().objectName)
        assertFalse(s.images.single().hidden)
    }

    /** With `-Daero.sampleDir`: the deck, for LibreOffice and the app. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "selection.pptx").outputStream().use { PptxWriter.write(deck(), it) }
        File(dir, "selection.odp").outputStream().use { OdpWriter.write(deck(), it) }
    }
}
