package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SheetShape
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextBoxBlock
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Shapes in OpenDocument files: turned as LibreOffice turns them, text boxes as frames, drawn in their order. */
class OdfShapesTest {

    private fun entry(bytes: ByteArray, name: String): String {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = zin.nextEntry ?: break; if (e.name == name) return zin.readBytes().decodeToString() }
        error("no $name")
    }

    private fun deck(): Presentation {
        // A blank slide (no title, no body): a turned box and a turned, framed text box.
        val s = Slide(title = SlideBox(""), body = SlideBox(""), layout = SlideLayout.BLANK)
        s.shapes.add(SlideShape(ShapeType.RECT, xPct = 0.4f, yPct = 0.4f, wPct = 0.2f, hPct = 0.2f, fill = 0xFF4472C4, border = null).also { it.rotationDeg = 30f })
        s.shapes.add(SlideShape(ShapeType.TEXTBOX, SlideBox("Turned note"), xPct = 0.1f, yPct = 0.1f, wPct = 0.2f, hPct = 0.1f, fill = 0xFFFFF2CC, border = 0xFFBF9000)
            .also { it.rotationDeg = -45f; it.anchor = ShapeAnchor.BOTTOM })
        return Presentation(mutableListOf(s))
    }

    @Test
    fun turnedShapesAndTextBoxesComeBackFromOpenDocument() {
        val bos = ByteArrayOutputStream()
        OdpWriter.write(deck(), bos)
        val content = entry(bos.toByteArray(), "content.xml")
        // A text box is a frame holding a text box, as Impress writes one; turned about its centre.
        assertTrue(content.contains("<draw:text-box>") && Regex("""<draw:frame draw:style-name="gr\d+"[^>]*draw:transform="rotate \(""").containsMatchIn(content))
        val back = OdpReader.read(bos.toByteArray()).slides[0]
        assertEquals("the text box is no title on a blank slide", "", back.title.text)
        assertEquals(SlideLayout.BLANK, back.layout)
        val (box, note) = back.shapes
        assertEquals(ShapeType.RECT, box.type); assertEquals(30f, box.rotationDeg, 0.01f)
        assertEquals(0.4f, box.xPct, 0.001f); assertEquals(0.4f, box.yPct, 0.001f); assertEquals(0.2f, box.wPct, 0.001f)
        assertEquals(ShapeType.TEXTBOX, note.type); assertEquals(-45f, note.rotationDeg, 0.01f)
        assertEquals(0.1f, note.xPct, 0.001f); assertEquals(0.1f, note.yPct, 0.001f)
        assertEquals(0xFFFFF2CC, note.fill); assertEquals(0xFFBF9000, note.border); assertEquals(ShapeAnchor.BOTTOM, note.anchor)
        assertEquals("Turned note", note.box.text)
    }

    @Test
    fun turnsAsLibreOfficeWritesThem() {
        // LibreOffice's own turn of a 192 × 108 pt box at (384, 216) by 30° clockwise: no svg:x / svg:y.
        val page = """<draw:page draw:name="p1" presentation:presentation-page-layout-name="AL1T20"><draw:custom-shape draw:style-name="gr1" svg:width="6.772cm" svg:height="3.809cm" draw:transform="rotate (-0.523598775598299) translate (14.953cm 6.182cm)"><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>""" +
            """<draw:frame presentation:class="page-number" svg:x="20cm" svg:y="17cm" svg:width="4cm" svg:height="1cm"><draw:text-box><text:p><text:page-number>&lt;number&gt;</text:page-number></text:p></draw:text-box></draw:frame></draw:page>"""
        val content = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"><office:body><office:presentation>$page</office:presentation></office:body></office:document-content>"""
        val styles = """<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"><office:automatic-styles><style:page-layout style:name="PM1"><style:page-layout-properties fo:page-width="960pt" fo:page-height="540pt"/></style:page-layout></office:automatic-styles></office:document-styles>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("styles.xml")); z.write(styles.toByteArray()); z.closeEntry()
        }
        val s = OdpReader.read(bos.toByteArray()).slides[0]
        assertEquals("the page number is the master's, not a text box", 1, s.shapes.size)
        val sh = s.shapes[0]
        assertEquals(30f, sh.rotationDeg, 0.01f)
        assertEquals(384f / 960f, sh.xPct, 0.001f); assertEquals(216f / 540f, sh.yPct, 0.001f)
        // And the app writes the same turn LibreOffice does.
        val out = ByteArrayOutputStream()
        OdpWriter.write(Presentation(mutableListOf(Slide(title = SlideBox(""), body = SlideBox(""), layout = SlideLayout.BLANK).also { it.shapes.add(sh) })), out)
        val m = Regex("""rotate \(([-0-9.E]+)\) translate \(([0-9.]+)pt ([0-9.]+)pt\)""").find(entry(out.toByteArray(), "content.xml"))!!
        assertEquals(-0.5235987755982988, m.groupValues[1].toDouble(), 1e-6)
        assertEquals(14.953 / 2.54 * 72, m.groupValues[2].toDouble(), 0.5)
        assertEquals(6.182 / 2.54 * 72, m.groupValues[3].toDouble(), 0.5)
    }

    @Test
    fun libreOfficeDecksReadAtTheirSlidesSize() {
        // LibreOffice writes its handouts' A4 page layout first; the slides' is the master page's.
        // A shape kept from PowerPoint names its preset ("ooxml-…"); a line is a connector.
        val page = """<draw:page draw:name="p1" draw:master-page-name="Title_20_Slide"><draw:custom-shape svg:x="25.8cm" svg:y="3.81cm" svg:width="6.773cm" svg:height="3.81cm"><draw:enhanced-geometry draw:type="ooxml-roundRect"/></draw:custom-shape>""" +
            """<draw:connector draw:type="line" svg:x1="26.416cm" svg:y1="16.383cm" svg:x2="31.835cm" svg:y2="9.525cm"/></draw:page>"""
        val content = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"><office:body><office:presentation>$page</office:presentation></office:body></office:document-content>"""
        val styles = """<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"><office:automatic-styles>""" +
            """<style:page-layout style:name="PM0"><style:page-layout-properties fo:page-width="21cm" fo:page-height="29.7cm" style:print-orientation="portrait"/></style:page-layout>""" +
            """<style:page-layout style:name="PM1"><style:page-layout-properties fo:page-width="33.867cm" fo:page-height="19.05cm" style:print-orientation="landscape"/></style:page-layout></office:automatic-styles>""" +
            """<office:master-styles><style:handout-master style:page-layout-name="PM0"/><style:master-page style:name="Title_20_Slide" style:page-layout-name="PM1"/></office:master-styles></office:document-styles>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("styles.xml")); z.write(styles.toByteArray()); z.closeEntry()
        }
        val p = OdpReader.read(bos.toByteArray())
        assertEquals(com.therealityteam.aeropages.model.SlideSize.WIDESCREEN, p.slideSize)
        val (rounded, line) = p.slides[0].shapes
        assertEquals(ShapeType.ROUNDED, rounded.type)
        assertEquals(0.762f, rounded.xPct, 0.002f); assertEquals(0.2f, rounded.yPct, 0.002f); assertEquals(0.2f, rounded.wPct, 0.002f)
        assertEquals(ShapeType.LINE, line.type); assertTrue("it runs up from its start", line.flipV)
        assertEquals(0.78f, line.xPct, 0.002f); assertEquals(0.5f, line.yPct, 0.002f); assertEquals(0.36f, line.hPct, 0.002f)
    }

    @Test
    fun drawnTextBoxesInOpenDocumentText() {
        val doc = Document(mutableListOf(
            ParagraphBlock(Paragraph.of("Before")),
            TextBoxBlock(mutableListOf(Paragraph.of("Word's text box")), 200f, 60f,
                SlideShape(ShapeType.TEXTBOX, fill = 0xFFDEEBF7, border = 0xFF2E75B6).also { it.anchor = ShapeAnchor.TOP }),
            TextBoxBlock(mutableListOf(Paragraph.of("A plain box")), 180f, 40f),
        ))
        val bos = ByteArrayOutputStream()
        OdtWriter.write(doc, bos)
        assertTrue("a text box is a frame, not a rectangle", !entry(bos.toByteArray(), "content.xml").contains("draw:custom-shape"))
        val back = OdtReader.read(bos.toByteArray()).blocks.filterIsInstance<TextBoxBlock>()
        assertEquals(2, back.size)
        val drawn = back[0].shape!!
        assertEquals(ShapeType.TEXTBOX, drawn.type); assertEquals(0xFFDEEBF7, drawn.fill); assertEquals(0xFF2E75B6, drawn.border)
        assertEquals(listOf("Word's text box"), back[0].paragraphs.map { it.plainText() })
        assertNull("a plain box stays plain", back[1].shape)
    }

    @Test
    fun sheetShapesKeepTheirOrder() {
        // The shape drawn on top sits in a cell above the one drawn first: cells list them row by row.
        val wb = Workbook.blank()
        val sheet = wb.sheets[0]
        sheet.ensure(0, 0).text = "Shapes"
        sheet.shapes.add(SheetShape(SlideShape(ShapeType.RECT, fill = 0xFFFF0000, border = null), 6, 1, 0f, 0f, 120f, 60f, "Back"))
        sheet.shapes.add(SheetShape(SlideShape(ShapeType.ELLIPSE, fill = 0xFF00B050, border = null).also { it.rotationDeg = 15f }, 2, 1, 4f, 6f, 90f, 90f, "Front"))
        val bos = ByteArrayOutputStream()
        OdsWriter.write(wb, bos)
        val back = OdsReader.read(bos.toByteArray()).sheets[0]
        assertEquals(listOf("Back", "Front"), back.shapes.map { it.name })
        val front = back.shapes[1]
        assertEquals(15f, front.shape.rotationDeg, 0.01f)
        assertEquals(4f, front.dxPt, 0.2f); assertEquals(6f, front.dyPt, 0.2f)
        assertEquals(2, front.row); assertEquals(1, front.col)
    }
}
