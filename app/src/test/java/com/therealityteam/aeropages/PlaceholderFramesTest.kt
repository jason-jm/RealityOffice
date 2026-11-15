package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.ModelDiff
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.BoxFrame
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Placeholder geometry: a slide's title / body show where the deck puts them — the shape's own
 * `a:xfrm`, else the layout placeholder with the same idx / type, else the master's — and the
 * frames round-trip through pptx and odp. Boxes without a frame follow the flow layout.
 */
class PlaceholderFramesTest {

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun assertFrame(msg: String, x: Float, y: Float, w: Float, h: Float, f: BoxFrame?) {
        assertNotNull("$msg: frame", f)
        assertEquals("$msg x", x, f!!.xPct, 0.002f); assertEquals("$msg y", y, f.yPct, 0.002f)
        assertEquals("$msg w", w, f.wPct, 0.002f); assertEquals("$msg h", h, f.hPct, 0.002f)
    }

    private val a = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
    private val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
    private val r = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private val relsHead = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
    private fun rel(id: String, type: String, target: String) =
        "<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type\" Target=\"$target\"/>"
    private fun xfrm(x: Int, y: Int, cx: Int, cy: Int) = "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm></p:spPr>"
    private fun ph(id: Int, type: String?, idx: String?, spPr: String, text: String?) = buildString {
        append("<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"ph$id\"/><p:cNvSpPr/><p:nvPr><p:ph")
        if (type != null) append(" type=\"$type\"")
        if (idx != null) append(" idx=\"$idx\"")
        append("/></p:nvPr></p:nvSpPr>$spPr<p:txBody><a:bodyPr/>")
        (text ?: "").split('\n').forEach { line -> append("<a:p><a:r><a:t>$line</a:t></a:r></a:p>") }
        append("</p:txBody></p:sp>")
    }

    /** A PowerPoint-like deck: master + three layouts, four slides exercising each inheritance step. */
    private fun foreignDeck(): ByteArray {
        // Master: title 6.875% / 5.3% / 86.25% / 19.3%; body 6.875% / 26.6% / 86.25% / 63.4%.
        val master = "<p:sldMaster $a $p $r><p:cSld><p:spTree>" +
            ph(2, "title", null, xfrm(838200, 365125, 10515600, 1325563), null) +
            ph(3, "body", "1", xfrm(838200, 1825625, 10515600, 4351338), null) +
            "</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/></p:sldMaster>"
        // Layout 1 (Title and Content): the title inherits the master's frame, the body has its own (10% / 29.2% / 80% / 58.3%).
        val layout1 = "<p:sldLayout $a $p $r type=\"obj\"><p:cSld name=\"Title and Content\"><p:spTree>" +
            ph(2, "title", null, "<p:spPr/>", null) + ph(3, null, "1", xfrm(1219200, 2000000, 9753600, 4000000), null) +
            "</p:spTree></p:cSld></p:sldLayout>"
        // Layout 2 (Two Content): two content placeholders side by side (idx 1 left, idx 2 right).
        val layout2 = "<p:sldLayout $a $p $r type=\"twoObj\"><p:cSld name=\"Two Content\"><p:spTree>" +
            ph(2, "title", null, xfrm(838200, 365125, 10515600, 1325563), null) +
            ph(3, null, "1", xfrm(838200, 1825625, 5181600, 4351338), null) +
            ph(4, null, "2", xfrm(6172200, 1825625, 5181600, 4351338), null) +
            "</p:spTree></p:cSld></p:sldLayout>"
        // Layout 3 (Title Only): no body placeholder at all.
        val layout3 = "<p:sldLayout $a $p $r type=\"titleOnly\"><p:cSld name=\"Title Only\"><p:spTree>" +
            ph(2, "title", null, xfrm(838200, 365125, 10515600, 1325563), null) + "</p:spTree></p:cSld></p:sldLayout>"
        // Layout 4: an empty spTree (some producers) → placeholders fall through to the master.
        val layout4 = "<p:sldLayout $a $p $r><p:cSld><p:spTree/></p:cSld></p:sldLayout>"
        // Slide 1: title with its own xfrm; content placeholder typed only by idx (`<p:ph idx="1"/>`).
        val slide1 = "<p:sld $a $p $r><p:cSld><p:spTree>" +
            ph(2, "title", null, xfrm(609600, 300000, 10972800, 1000000), "Agenda") + ph(3, null, "1", "<p:spPr/>", "One\nTwo") +
            "</p:spTree></p:cSld></p:sld>"
        // Slide 2: two-content — the second column has no slot in the model and becomes a text box.
        val slide2 = "<p:sld $a $p $r><p:cSld><p:spTree>" +
            ph(2, "title", null, "<p:spPr/>", "Compare") + ph(3, null, "1", "<p:spPr/>", "Left") + ph(4, null, "2", "<p:spPr/>", "Right") +
            "</p:spTree></p:cSld></p:sld>"
        // Slide 3: title only.
        val slide3 = "<p:sld $a $p $r><p:cSld><p:spTree>" + ph(2, "title", null, "<p:spPr/>", "Just a title") + "</p:spTree></p:cSld></p:sld>"
        // Slide 4: empty layout → the master's frames; a centred title (ctrTitle) + subtitle.
        val slide4 = "<p:sld $a $p $r><p:cSld><p:spTree>" +
            ph(2, "ctrTitle", null, "<p:spPr/>", "The End") + ph(3, "subTitle", "1", "<p:spPr/>", "Thanks") + "</p:spTree></p:cSld></p:sld>"
        val slideRels = { layout: Int -> relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout$layout.xml") + "</Relationships>" }
        return zipOf(mapOf(
            "ppt/slideMasters/slideMaster1.xml" to master,
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml") + "</Relationships>",
            "ppt/slideLayouts/slideLayout1.xml" to layout1,
            "ppt/slideLayouts/slideLayout2.xml" to layout2,
            "ppt/slideLayouts/slideLayout3.xml" to layout3,
            "ppt/slideLayouts/slideLayout4.xml" to layout4,
            "ppt/slides/slide1.xml" to slide1, "ppt/slides/_rels/slide1.xml.rels" to slideRels(1),
            "ppt/slides/slide2.xml" to slide2, "ppt/slides/_rels/slide2.xml.rels" to slideRels(2),
            "ppt/slides/slide3.xml" to slide3, "ppt/slides/_rels/slide3.xml.rels" to slideRels(3),
            "ppt/slides/slide4.xml" to slide4, "ppt/slides/_rels/slide4.xml.rels" to slideRels(4),
        ))
    }

    @Test
    fun placeholdersInheritFramesFromSlideThenLayoutThenMaster() {
        val bytes = foreignDeck()
        System.getProperty("aero.sampleDir")?.let { java.io.File(it).apply { mkdirs() }.resolve("placeholders.pptx").writeBytes(bytes) }
        val pres = PptxReader.read(bytes)
        assertEquals(4, pres.slides.size)
        val s1 = pres.slides[0]
        assertEquals("Agenda", s1.title.text)
        assertEquals("an idx-only content placeholder is the body", "One\nTwo", s1.body.text)
        assertFrame("slide's own xfrm", 0.05f, 0.04374f, 0.9f, 0.14581f, s1.title.frame)
        assertFrame("layout body idx=1", 0.1f, 0.29163f, 0.8f, 0.58327f, s1.body.frame)
        val s2 = pres.slides[1]
        assertEquals("Compare", s2.title.text)
        assertEquals("Left", s2.body.text)
        assertFrame("layout title", 0.06875f, 0.05324f, 0.8625f, 0.19328f, s2.title.frame)
        assertFrame("left column", 0.06875f, 0.2662f, 0.425f, 0.63449f, s2.body.frame)
        val right = s2.shapes.single()
        assertEquals("second column kept as a text box", ShapeType.TEXTBOX, right.type)
        assertEquals("Right", right.box.text)
        assertEquals(0.50625f, right.xPct, 0.002f); assertEquals(0.425f, right.wPct, 0.002f)
        assertNull("placeholder text boxes have no fill", right.fill)
        assertEquals("content placeholders anchor their text at the top", ShapeAnchor.TOP, right.anchor)
        val s3 = pres.slides[2]
        assertEquals("Just a title", s3.title.text)
        assertFrame("title-only layout", 0.06875f, 0.05324f, 0.8625f, 0.19328f, s3.title.frame)
        assertNull("no body placeholder on a Title Only layout", s3.body.frame)
        assertEquals("", s3.body.text)
        val s4 = pres.slides[3]
        assertEquals("The End", s4.title.text); assertEquals("Thanks", s4.body.text)
        assertFrame("master title", 0.06875f, 0.05324f, 0.8625f, 0.19328f, s4.title.frame)
        assertFrame("master body", 0.06875f, 0.2662f, 0.8625f, 0.63449f, s4.body.frame)
    }

    @Test
    fun pptxRoundTripKeepsFramesAndWritesTheFlowGeometryOtherwise() {
        val framed = Slide(title = SlideBox("T", CharacterFormat(fontSizePt = 40f), TextAlign.LEFT), body = SlideBox("B", CharacterFormat(fontSizePt = 22f)))
        framed.title.frame = BoxFrame(0.12f, 0.08f, 0.5f, 0.2f)
        framed.body.frame = BoxFrame(0.55f, 0.35f, 0.4f, 0.5f)
        val flowing = Slide(title = SlideBox("Centred", CharacterFormat(fontSizePt = 40f), TextAlign.CENTER), body = SlideBox("", CharacterFormat(fontSizePt = 22f)), layout = SlideLayout.TITLE)
        val pres = Presentation(mutableListOf(framed, flowing))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val back = PptxReader.read(bos.toByteArray())
        assertFrame("title frame", 0.12f, 0.08f, 0.5f, 0.2f, back.slides[0].title.frame)
        assertFrame("body frame", 0.55f, 0.35f, 0.4f, 0.5f, back.slides[0].body.frame)
        val d = BoxFrame.defaultFor(SlideLayout.TITLE, true)
        assertFrame("a flowing title slide is written at its layout's geometry", d.xPct, d.yPct, d.wPct, d.hPct, back.slides[1].title.frame)
        assertEquals("Centred", back.slides[1].title.text)
    }

    @Test
    fun odpRoundTripKeepsFrames() {
        val s = Slide(title = SlideBox("T", CharacterFormat(fontSizePt = 40f)), body = SlideBox("B", CharacterFormat(fontSizePt = 22f)))
        s.title.frame = BoxFrame(0.2f, 0.1f, 0.6f, 0.15f)
        s.body.frame = BoxFrame(0.1f, 0.3f, 0.8f, 0.6f)
        val bos = ByteArrayOutputStream()
        OdpWriter.write(Presentation(mutableListOf(s)), bos)
        val back = OdpReader.read(bos.toByteArray())
        assertFrame("odp title", 0.2f, 0.1f, 0.6f, 0.15f, back.slides[0].title.frame)
        assertFrame("odp body", 0.1f, 0.3f, 0.8f, 0.6f, back.slides[0].body.frame)
        assertEquals("T", back.slides[0].title.text); assertEquals("B", back.slides[0].body.text)
    }

    @Test
    fun shapeTextAnchorsReadWriteAndDefaultPerShapeKind() {
        // A drawn shape with anchor="b", a text box (txBox) without an anchor (top per the spec), a
        // drawn shape without one (kept centred, AeroDesk's look).
        fun sp(id: Int, txBox: Boolean, anchor: String?, text: String) = "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"s$id\"/><p:cNvSpPr" +
            (if (txBox) " txBox=\"1\"" else "") + "/><p:nvPr/></p:nvSpPr>" + xfrm(1000000, 1000000, 3000000, 2000000).replace("<p:spPr>", "<p:spPr>").replace("</p:spPr>", "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr>") +
            "<p:txBody><a:bodyPr" + (if (anchor != null) " anchor=\"$anchor\"" else "") + "/><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp>"
        val slide = "<p:sld $a $p $r><p:cSld><p:spTree>" + sp(2, false, "b", "Bottom") + sp(3, true, null, "Box") + sp(4, false, null, "Plain") + "</p:spTree></p:cSld></p:sld>"
        val pres = PptxReader.read(zipOf(mapOf("ppt/slides/slide1.xml" to slide)))
        val shapes = pres.slides[0].shapes
        assertEquals(3, shapes.size)
        assertEquals(ShapeAnchor.BOTTOM, shapes.first { it.box.text == "Bottom" }.anchor)
        assertEquals("txBox without an anchor is top-anchored", ShapeAnchor.TOP, shapes.first { it.box.text == "Box" }.anchor)
        assertEquals("a drawn shape without an anchor stays centred", ShapeAnchor.CENTER, shapes.first { it.box.text == "Plain" }.anchor)
        // Round trip: the anchor is written explicitly, text boxes as txBox.
        val s = Slide(title = SlideBox("T"), body = SlideBox("B"))
        s.shapes.add(SlideShape(ShapeType.TEXTBOX, SlideBox("tb"), anchor = ShapeAnchor.TOP))
        s.shapes.add(SlideShape(ShapeType.RECT, SlideBox("rc"), anchor = ShapeAnchor.BOTTOM))
        s.shapes.add(SlideShape(ShapeType.ELLIPSE, SlideBox("el")))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(Presentation(mutableListOf(s)), bos)
        val back = PptxReader.read(bos.toByteArray()).slides[0].shapes
        assertEquals(listOf(ShapeAnchor.TOP, ShapeAnchor.BOTTOM, ShapeAnchor.CENTER), back.map { it.anchor })
        assertEquals(ShapeType.TEXTBOX, back[0].type)
        // The editor cycles the anchor with undo, and the diff notices.
        val e = SlideEditor()
        e.load(Presentation(mutableListOf(s.copyOf())))
        e.selectShape(e.slide.shapes[2])
        e.cycleShapeAnchor()
        assertEquals(ShapeAnchor.BOTTOM, e.slide.shapes[2].anchor)
        e.cycleShapeAnchor()
        assertEquals(ShapeAnchor.TOP, e.slide.shapes[2].anchor)
        e.undo()
        assertEquals(ShapeAnchor.BOTTOM, e.slide.shapes[2].anchor)
        assertFalse(ModelDiff.sameShape(s.shapes[2], e.slide.shapes[2]))
    }

    @Test
    fun editorFreesMovesAndReflowsPlaceholders() {
        val e = SlideEditor()
        e.load(Presentation.blank())
        assertFalse(e.placeholdersFree)
        e.moveBox(e.slide.title, 0.1f, 0.1f)                 // no frame: ignored
        assertNull(e.slide.title.frame)
        e.freePlaceholders()
        assertTrue(e.placeholdersFree)
        val d = BoxFrame.defaultFor(e.slide.layout, true)
        assertFrame("pinned at the flow position", d.xPct, d.yPct, d.wPct, d.hPct, e.slide.title.frame)
        e.moveBox(e.slide.title, 0.02f, 0.9f)
        assertEquals(d.xPct + 0.02f, e.slide.title.frame!!.xPct, 1e-4f)
        assertEquals("clamped inside the slide", 1f - d.hPct, e.slide.title.frame!!.yPct, 1e-4f)
        e.resizeBox(e.slide.body, -0.3f, 0.5f)
        val b = e.slide.body.frame!!
        assertEquals(BoxFrame.defaultFor(e.slide.layout, false).wPct - 0.3f, b.wPct, 1e-4f)
        assertEquals("height clamped to the slide", 1f - b.yPct, b.hPct, 1e-4f)
        e.undo(); e.undo()
        assertFrame("undo restores the pinned frame", d.xPct, d.yPct, d.wPct, d.hPct, e.slide.title.frame)
        e.undo()
        assertNull("undo of free layout", e.slide.title.frame)
        e.freePlaceholders()
        e.setLayout(SlideLayout.SECTION)
        assertNull("choosing a layout re-flows", e.slide.title.frame)
        e.freePlaceholders(); e.autoPlaceholders()
        assertFalse(e.placeholdersFree)
        // The diff (undo verification) sees a moved frame.
        val x = SlideBox("x"); val y = SlideBox("x")
        assertTrue(ModelDiff.sameBox(x, y))
        y.frame = BoxFrame(0f, 0f, 1f, 1f)
        assertFalse(ModelDiff.sameBox(x, y))
        x.frame = BoxFrame(0f, 0f, 1f, 1f)
        assertTrue(ModelDiff.sameBox(x, y))
        assertTrue("copies carry the frame", x.copyOf().frame!!.sameAs(x.frame))
    }

    @Test
    fun blankSlidesStayBlankThroughPowerPoint() {
        // A blank slide with a title (PowerPoint's Title Only) and one with nothing (Blank): their empty
        // placeholders are left out, so neither comes back asking for text.
        val titled = Slide(title = SlideBox("Lines", CharacterFormat(fontSizePt = 32f)), body = SlideBox(""), layout = SlideLayout.BLANK)
        val empty = Slide(title = SlideBox(""), body = SlideBox(""), layout = SlideLayout.BLANK)
        empty.shapes.add(SlideShape(ShapeType.RECT))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(Presentation(mutableListOf(titled, empty)), bos)
        val parts = com.therealityteam.aeropages.format.OfficeXml.unzip(bos.toByteArray())
        val first = String(parts["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(first.contains("""<p:ph type="title"/>""")); assertFalse(first.contains("""type="body""""))
        assertFalse(String(parts["ppt/slides/slide2.xml"]!!, Charsets.UTF_8).contains("<p:ph "))
        val back = PptxReader.read(bos.toByteArray())
        assertEquals(SlideLayout.BLANK, back.slides[0].layout); assertEquals("Lines", back.slides[0].title.text)
        assertEquals(SlideLayout.BLANK, back.slides[1].layout); assertEquals(1, back.slides[1].shapes.size)
    }
}
