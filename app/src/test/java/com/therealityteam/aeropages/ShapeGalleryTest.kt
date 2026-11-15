package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.XmlPull
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.DrawingGeometry
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.pptx.PresetGeometries
import com.therealityteam.aeropages.model.Freeform
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.ui.ShapeCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Insert → Shapes: PowerPoint's gallery of presets, each drawn from its definition, its shaded faces too. */
class ShapeGalleryTest {

    @Test
    fun theGalleryShowsWhatTheAppDraws() {
        val shown = ShapeCatalog.shown
        assertEquals("PowerPoint's eight categories", 8, shown.size)
        val all = shown.flatMap { it.second }
        assertTrue("well over a hundred shapes: ${all.size}", all.size > 130)
        assertEquals("each once", all.size, all.toSet().size)
        all.forEach { name -> assertTrue(name, ShapeCatalog.draws(name)) }
        // The ones without a drawing yet are left out, not shown blank.
        assertFalse("ribbon2" in all)
        assertEquals("Flow chart predefined process", ShapeCatalog.spoken("flowChartPredefinedProcess"))
        assertEquals("Star 10", ShapeCatalog.spoken("star10"))
    }

    @Test
    fun aPickedShapeIsInsertedAndUndone() {
        val e = SlideEditor()
        e.load(Presentation.blank())
        e.addPreset("hexagon")
        val s = e.slide.shapes.single()
        assertEquals(ShapeType.FREEFORM, s.type); assertEquals("hexagon", s.preset)
        assertNotNull(s.path)
        assertEquals("square on the slide", s.wPct * e.presentation.slideSize.widthPt, s.hPct * e.presentation.slideSize.heightPt, 0.5f)
        assertEquals(0.5f, s.xPct + s.wPct / 2f, 0.001f)
        assertTrue("selected to type in", e.selectedShape === s)
        e.addPreset("roundRect")
        assertEquals("the app's own rounded rectangle", ShapeType.ROUNDED, e.slide.shapes[1].type)
        assertNull(e.slide.shapes[1].preset)
        e.addPreset("noSuchShape")
        assertEquals(2, e.slide.shapes.size)
        e.undo(); e.undo()
        assertTrue(e.slide.shapes.isEmpty())
    }

    @Test
    fun insertedShapesAreSavedAsTheirPresets() {
        val e = SlideEditor()
        e.load(Presentation.blank())
        e.addPreset("curvedRightArrow"); e.addPreset("blockArc")
        // Resized: ODF gets the outline worked out at the new size, the arc's thickness a share of its short side.
        val arc = e.slide.shapes[1]; arc.wPct *= 3f
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(e.presentation, it) }.toByteArray()
        assertEquals(listOf("curvedRightArrow", "blockArc"), PptxReader.read(pptx).slides[0].shapes.map { it.preset })
        val odp = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(e.presentation, it) }.toByteArray()).slides[0].shapes
        val back = odp[1].path!!
        val atSize = PresetGeometries.outline("blockArc", null, arc.wPct * 960f * 12700f, arc.hPct * 540f * 12700f)!!
        val b1 = back.bounds()!!; val b2 = atSize.bounds()!!
        assertEquals(b2[3], b1[3], 0.01f)
        // The inner arc's lowest point: as thick as the short side says, not stretched from the old size.
        val innerStart = (atSize.parts.single().ops.filterIsInstance<Freeform.Line>().first())
        val backLine = back.parts.single().ops.filterIsInstance<Freeform.Line>().first()
        assertEquals(innerStart.x, backLine.x, 0.01f)
    }

    @Test
    fun shadedFacesAreReadDrawnAndKept() {
        // A cube: its top a little lighter, its side a little darker, as PowerPoint draws them.
        val cube = PresetGeometries.freeform("cube", null, 2000000.0, 1000000.0)!!
        assertTrue(cube.parts.any { it.shade == Freeform.DARKEN_LESS })
        assertTrue(cube.parts.any { it.shade == Freeform.LIGHTEN_LESS })
        // A custom geometry's path fill modes, and back.
        val geom = XmlPull.parse(("""<a:custGeom xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:pathLst>""" +
            """<a:path w="10" h="10"><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="10" y="0"/></a:lnTo><a:lnTo><a:pt x="10" y="10"/></a:lnTo><a:close/></a:path>""" +
            """<a:path w="10" h="10" fill="darken" stroke="0"><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="10" y="10"/></a:lnTo><a:lnTo><a:pt x="0" y="10"/></a:lnTo><a:close/></a:path>""" +
            """</a:pathLst></a:custGeom>""").toByteArray())!!
        val f = DrawingGeometry.read(geom, 100.0, 100.0)!!
        assertEquals(listOf(0f, Freeform.DARKEN), f.parts.map { it.shade })
        assertTrue(DrawingGeometry.write(f).contains("""fill="darken" stroke="0""""))
        // In ODF: H darkens, K lightens a little.
        val path = com.therealityteam.aeropages.format.odf.OdfGeometry.enhancedPath(f)
        assertTrue(path, path.contains("H M"))
        val deck = Presentation.blank()
        deck.slides[0].shapes.add(com.therealityteam.aeropages.model.SlideShape(ShapeType.FREEFORM).also { it.path = f })
        val again = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray()).slides[0].shapes.single().path!!
        assertEquals(listOf(0f, Freeform.DARKEN), again.parts.map { it.shade })
    }
}
