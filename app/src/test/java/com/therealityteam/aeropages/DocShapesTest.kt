package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.ArrowHead
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.LineDash
import com.therealityteam.aeropages.model.LineEnd
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextBoxBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Word's drawn shapes (and ODF's) in a document: read, kept, written back — where they used to be dropped. */
class DocShapesTest {

    private fun docxOf(bodyXml: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml"))
            z.write("""<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>""".toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(bodyXml.toByteArray()); z.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun entry(bytes: ByteArray, name: String): String {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = zin.nextEntry ?: break; if (e.name == name) return zin.readBytes().decodeToString() }
        error("no $name")
    }

    private val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml""""

    /** One wps shape in a drawing, as Word writes it (with the VML fallback it repeats it in). */
    private fun drawing(cx: Long, cy: Long, wsp: String) =
        """<w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing><wp:anchor><wp:extent cx="$cx" cy="$cy"/><a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">$wsp</a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>""" +
            """<mc:Fallback><w:pict><v:rect><v:textbox><w:txbxContent><w:p><w:r><w:t>fallback</w:t></w:r></w:p></w:txbxContent></v:textbox></v:rect></w:pict></mc:Fallback></mc:AlternateContent></w:r>"""

    private val wordShapes = """<w:document $ns><w:body>
<w:p><w:r><w:t>Flow chart</w:t></w:r></w:p>
<w:p>""" +
        // A rounded box coloured by Word's default shape style: accent 1 fill, a darker rule, white text in the middle.
        drawing(1828800, 914400, """<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1828800" cy="914400"/></a:xfrm><a:prstGeom prst="roundRect"><a:avLst/></a:prstGeom></wps:spPr>""" +
            """<wps:style><a:lnRef idx="2"><a:schemeClr val="accent1"><a:shade val="50000"/></a:schemeClr></a:lnRef><a:fillRef idx="1"><a:schemeClr val="accent1"/></a:fillRef><a:effectRef idx="0"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></wps:style>""" +
            """<wps:txbx><w:txbxContent><w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>Start</w:t></w:r></w:p></w:txbxContent></wps:txbx><wps:bodyPr anchor="ctr"/></wps:wsp>""") +
        "</w:p><w:p>" +
        // An arrow connector, dashed, running up-left (both flips).
        drawing(1270000, 635000, """<wps:wsp><wps:cNvCnPr/><wps:spPr><a:xfrm flipH="1" flipV="1"><a:off x="0" y="0"/><a:ext cx="1270000" cy="635000"/></a:xfrm><a:prstGeom prst="straightConnector1"><a:avLst/></a:prstGeom>""" +
            """<a:ln w="19050"><a:solidFill><a:srgbClr val="C00000"/></a:solidFill><a:prstDash val="dash"/><a:tailEnd type="triangle"/></a:ln></wps:spPr><wps:bodyPr/></wps:wsp>""") +
        "</w:p><w:p>" +
        // A gold star with no text at all.
        drawing(914400, 914400, """<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="914400" cy="914400"/></a:xfrm><a:prstGeom prst="star5"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="FFC000"/></a:solidFill><a:ln><a:noFill/></a:ln></wps:spPr><wps:bodyPr/></wps:wsp>""") +
        """</w:p>
<w:p><w:r><w:t>After</w:t></w:r></w:p>
</w:body></w:document>"""

    @Test
    fun wordsDrawnShapesAreKept() {
        val doc = DocxReader.read(ByteArrayInputStream(docxOf(wordShapes)))
        // Each shape floats from a paragraph of its own: the paragraph stays (a line in Word), the shape after it.
        assertEquals(listOf("ParagraphBlock", "ParagraphBlock", "TextBoxBlock", "ParagraphBlock", "TextBoxBlock", "ParagraphBlock", "TextBoxBlock", "ParagraphBlock"),
            doc.blocks.map { it::class.simpleName })
        val box = doc.blocks[2] as TextBoxBlock
        assertNotNull("anchored: it floats", box.floating)
        val shape = box.shape!!
        assertEquals(ShapeType.ROUNDED, shape.type)
        assertEquals("the theme's accent 1, from the shape style", 0xFF4472C4, shape.fill)
        assertTrue("a darker rule", shape.border != null && shape.border != shape.fill)
        assertEquals(ShapeAnchor.CENTER, shape.anchor)
        assertEquals(listOf("Start"), box.paragraphs.map { it.plainText() })
        assertEquals("white text, from the style's font colour", 0xFFFFFFFF, box.paragraphs[0].runs[0].format.color)
        assertEquals(144f, box.widthPt!!, 0.5f); assertEquals(72f, box.heightPt!!, 0.5f)

        val arrow = (doc.blocks[4] as TextBoxBlock)
        assertTrue("a line holds no text", arrow.paragraphs.isEmpty())
        val line = arrow.shape!!
        assertEquals(ShapeType.LINE, line.type)
        assertEquals(LineDash.DASH, line.dash); assertEquals(ArrowHead.TRIANGLE, line.endArrow.head)
        assertTrue(line.flipH && line.flipV)
        assertEquals(0xFFC00000, line.border); assertEquals(1.5f, line.borderWidthPt, 0.01f)

        val star = (doc.blocks[6] as TextBoxBlock)
        assertEquals(ShapeType.STAR, star.shape!!.type)
        assertEquals(0xFFFFC000, star.shape!!.fill); assertNull(star.shape!!.border)
        assertTrue(star.paragraphs.isEmpty())
        assertTrue("the fallback's copy is not read", doc.paragraphs().none { it.plainText() == "fallback" })
        // A shape keeps its own height even with little text; one without text takes just its own.
        assertEquals(72f, PageMetrics.boxFrameHeight(box, 14f), 0.01f)
        assertEquals(50f, PageMetrics.boxFrameHeight(arrow, 0f), 0.01f)
    }

    private fun docWithShapes(): Document = Document().also {
        it.blocks.add(ParagraphBlock(Paragraph.of("Intro")))
        it.blocks.add(TextBoxBlock(mutableListOf(Paragraph.of("Decision")), 150f, 80f,
            SlideShape(ShapeType.ELLIPSE, fill = 0xFF70AD47, border = 0xFF375623, borderWidthPt = 2f, dash = LineDash.SYS_DOT, anchor = ShapeAnchor.BOTTOM)
                .also { it.fillGradient = 0xFFA9D18E; it.gradientAngle = 90f }))
        it.blocks.add(TextBoxBlock(mutableListOf(), 200f, 0f,
            SlideShape(ShapeType.LINE, fill = null, border = 0xFF2F5597, borderWidthPt = 1.5f, beginArrow = LineEnd(ArrowHead.OVAL), endArrow = LineEnd(ArrowHead.STEALTH, 2, 2))))
        it.blocks.add(ParagraphBlock(Paragraph.of("Outro")))
    }

    private fun assertSameShapes(a: Document, b: Document) {
        assertEquals(a.blocks.map { it::class.simpleName }, b.blocks.map { it::class.simpleName })
        a.blocks.zip(b.blocks).forEach { (x, y) ->
            if (x !is TextBoxBlock) return@forEach
            y as TextBoxBlock
            val s = x.shape!!; val t = y.shape!!
            assertEquals(s.type, t.type); assertEquals(s.fill, t.fill); assertEquals(s.fillGradient, t.fillGradient)
            assertEquals(s.border, t.border); assertEquals(s.borderWidthPt, t.borderWidthPt, 0.02f); assertEquals(s.dash, t.dash)
            assertEquals(s.beginArrow, t.beginArrow); assertEquals(s.endArrow, t.endArrow); assertEquals(s.anchor, t.anchor)
            assertEquals(x.widthPt!!, y.widthPt!!, 0.5f); assertEquals(x.heightPt ?: 0f, y.heightPt ?: 0f, 0.5f)
            assertEquals(x.paragraphs.map { it.plainText() }, y.paragraphs.map { it.plainText() })
        }
    }

    @Test
    fun shapesGoToWordAndBack() {
        val doc = docWithShapes()
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val body = entry(bos.toByteArray(), "word/document.xml")
        assertTrue(body.contains("""<a:prstGeom prst="ellipse">"""))
        assertTrue("a line is a connector without a text body", body.contains("<wps:cNvCnPr/>") && body.contains("""<a:tailEnd type="stealth" w="lg" len="lg"/>"""))
        assertTrue(body.contains("""anchor="b""""))
        assertSameShapes(doc, DocxReader.read(ByteArrayInputStream(bos.toByteArray())))
    }

    @Test
    fun shapesGoToOpenDocumentAndBack() {
        val doc = docWithShapes()
        val bos = ByteArrayOutputStream()
        OdtWriter.write(doc, bos)
        val content = entry(bos.toByteArray(), "content.xml")
        assertTrue(content.contains("""<draw:enhanced-geometry draw:type="ellipse"/>"""))
        assertTrue(entry(bos.toByteArray(), "styles.xml").contains("draw:name=\"msArrowStealthEnd_20_9\""))
        val back = OdtReader.read(bos.toByteArray())
        assertSameShapes(doc, back)
        assertTrue("the shape's text is its own, not its anchor paragraph's", back.paragraphs().count { it.plainText() == "Decision" } == 1)
    }
}
