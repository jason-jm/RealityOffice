package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Slides: gradient fills and groups through pptx, group transforms of foreign decks, multi-selection editing and smart guides. */
class SlideGroupsAndGuidesTest {

    @Test
    fun gradientsAndGroupsRoundTrip() {
        val slide = Slide()
        val a = SlideShape(ShapeType.RECT, xPct = 0.1f, yPct = 0.1f, wPct = 0.2f, hPct = 0.2f, fill = 0xFF2B579A, fillGradient = 0xFFFFFFFF, gradientAngle = 45f, groupId = 3)
        val b = SlideShape(ShapeType.ELLIPSE, xPct = 0.4f, yPct = 0.3f, wPct = 0.2f, hPct = 0.2f, groupId = 3)
        val c = SlideShape(ShapeType.ROUNDED, xPct = 0.7f, yPct = 0.6f, wPct = 0.2f, hPct = 0.2f)
        slide.shapes.addAll(listOf(a, b, c))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(Presentation(mutableListOf(slide)), bos)
        val bytes = bos.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("""<a:gradFill rotWithShape="1"><a:gsLst><a:gs pos="0"><a:srgbClr val="2B579A"/></a:gs><a:gs pos="100000"><a:srgbClr val="FFFFFF"/></a:gs></a:gsLst><a:lin ang="2700000" scaled="0"/></a:gradFill>"""))
        assertTrue(xml.contains("""<p:grpSp><p:nvGrpSpPr>"""))
        assertTrue("group frame = bounding box, child space = slide space", xml.contains("""<a:off x="1219200" y="685800"/><a:ext cx="6096000" cy="2743200"/><a:chOff x="1219200" y="685800"/><a:chExt cx="6096000" cy="2743200"/>"""))
        assertEquals(1, Regex("<p:grpSp>").findAll(xml).count())
        val back = PptxReader.read(bytes).slides[0]
        assertEquals(3, back.shapes.size)
        val ba = back.shapes[0]; val bb = back.shapes[1]; val bc = back.shapes[2]
        assertEquals(0xFF2B579A, ba.fill); assertEquals(0xFFFFFFFF, ba.fillGradient); assertEquals(45f, ba.gradientAngle, 0.01f)
        assertEquals(0.1f, ba.xPct, 0.001f); assertEquals(0.4f, bb.xPct, 0.001f); assertEquals(0.2f, bb.wPct, 0.001f)
        assertNotNull(ba.groupId); assertEquals(ba.groupId, bb.groupId); assertNull(bc.groupId)
        assertNull(bb.fillGradient)
    }

    @Test
    fun foreignGroupTransformIsApplied() {
        val slideXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:grpSp><p:nvGrpSpPr><p:cNvPr id="4" name="Group 3"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
<p:grpSpPr><a:xfrm><a:off x="1000000" y="500000"/><a:ext cx="4000000" cy="2000000"/><a:chOff x="0" y="0"/><a:chExt cx="2000000" cy="1000000"/></a:xfrm></p:grpSpPr>
<p:sp><p:nvSpPr><p:cNvPr id="5" name="Rectangle 4"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="500000" y="250000"/><a:ext cx="1000000" cy="500000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:gradFill><a:gsLst><a:gs pos="100000"><a:srgbClr val="00FF00"/></a:gs><a:gs pos="0"><a:srgbClr val="FF0000"/></a:gs></a:gsLst><a:lin ang="0"/></a:gradFill></p:spPr><p:txBody><a:bodyPr/><a:p><a:r><a:t>in group</a:t></a:r></a:p></p:txBody></p:sp>
</p:grpSp>
</p:spTree></p:cSld></p:sld>"""
        val presentation = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>"""
        val presRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in listOf("ppt/presentation.xml" to presentation, "ppt/_rels/presentation.xml.rels" to presRels, "ppt/slides/slide1.xml" to slideXml)) {
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
        val s = PptxReader.read(bos.toByteArray()).slides[0].shapes.single()
        // child (500000, 250000) 1000000×500000 in a 2× group at (1000000, 500000) → (2000000, 1000000) 2000000×1000000
        assertEquals(2000000f / 12192000f, s.xPct, 0.0005f); assertEquals(1000000f / 6858000f, s.yPct, 0.0005f)
        assertEquals(2000000f / 12192000f, s.wPct, 0.0005f); assertEquals(1000000f / 6858000f, s.hPct, 0.0005f)
        assertEquals(1, s.groupId)
        assertEquals("stops sorted by position", 0xFFFF0000, s.fill); assertEquals(0xFF00FF00, s.fillGradient); assertEquals(0f, s.gradientAngle, 0.01f)
    }

    @Test
    fun multiSelectionMovesAlignsGroupsAndSnaps() {
        val e = SlideEditor(); e.load(Presentation(mutableListOf(Slide())))
        e.addShape(ShapeType.RECT); val a = e.selectedShape!!; a.xPct = 0.1f; a.yPct = 0.1f; a.wPct = 0.2f; a.hPct = 0.2f
        e.addShape(ShapeType.RECT); val b = e.selectedShape!!; b.xPct = 0.5f; b.yPct = 0.3f; b.wPct = 0.2f; b.hPct = 0.2f
        e.addShape(ShapeType.RECT); val c = e.selectedShape!!; c.xPct = 0.7f; c.yPct = 0.7f; c.wPct = 0.1f; c.hPct = 0.1f
        e.selectShape(a)
        assertEquals(listOf(a), e.selectedShapes)
        e.toggleMultiSelect(); e.selectShape(b)
        assertEquals(listOf(a, b), e.selectedShapes)
        e.moveSelectedShape(0.05f, 0.05f) // moves both; a's edges snap to nothing here
        assertEquals(0.15f, a.xPct, 0.001f); assertEquals(0.55f, b.xPct, 0.001f); assertEquals(0.35f, b.yPct, 0.001f)
        e.alignShape('T')
        assertEquals(0.15f, a.yPct, 0.001f); assertEquals(0.15f, b.yPct, 0.001f)
        e.groupSelected()
        assertNotNull(a.groupId); assertEquals(a.groupId, b.groupId)
        e.toggleMultiSelect(); e.clearSelection()
        e.selectShape(b) // a grouped shape brings its partner along
        assertEquals(setOf(a, b), e.selectedShapes.toSet())
        e.selectShape(c); e.toggleMultiSelect(); e.selectShape(a)
        assertEquals(3, e.selectedShapes.size)
        e.distributeSelected(true)
        // a (0.15..0.35) and c (0.7..0.8) stay; b is centred in the remaining gap: gap = (0.65 - 0.5) / 2 = 0.075 → x = 0.425
        assertEquals(0.425f, b.xPct, 0.001f)
        e.ungroupSelected()
        assertNull(a.groupId); assertNull(b.groupId)
        e.undo(); assertNotNull(e.slide.shapes[0].groupId)
        e.undo(); assertEquals(0.55f, e.slide.shapes[1].xPct, 0.001f)
        // Smart guide: c's left edge dragged near b's right edge snaps onto it and reports a vertical guide.
        e.toggleMultiSelect(); e.clearSelection(); e.selectShape(e.slide.shapes[2])
        val c2 = e.slide.shapes[2]; val b2 = e.slide.shapes[1]
        c2.xPct = 0.9f - 0.1f; c2.yPct = 0.85f
        e.moveSelectedShape(-(c2.xPct - (b2.xPct + b2.wPct)) + 0.008f, 0f)
        assertEquals(b2.xPct + b2.wPct, c2.xPct, 0.0005f)
        assertEquals(1, e.guides.size); assertTrue(e.guides[0].vertical); assertEquals(b2.xPct + b2.wPct, e.guides[0].pos, 0.0005f)
        e.clearGuides(); assertTrue(e.guides.isEmpty())
        e.deleteSelectedShape()
        assertEquals(2, e.slide.shapes.size)
        assertFalse(e.multiSelect)
    }
}
