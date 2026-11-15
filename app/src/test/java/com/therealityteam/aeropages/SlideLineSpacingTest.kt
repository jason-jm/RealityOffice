package com.therealityteam.aeropages

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.ui.slideBaseStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Slide line spacing: a box's multiple of single spacing, drawn and kept in pptx and ODP. */
class SlideLineSpacingTest {

    // A Title and Content slide (a new deck starts on a Title Slide, whose subtitle is centred).
    private fun deck(spacing: Float): Presentation = com.therealityteam.aeropages.model.Presentation(mutableListOf(com.therealityteam.aeropages.model.Slide(title = com.therealityteam.aeropages.model.SlideBox(""), body = com.therealityteam.aeropages.model.SlideBox(""), layout = com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT))).also { p ->
        val body = p.slides[0].body
        body.paragraph.runs.clear()
        body.paragraph.runs.add(TextRun("One\nTwo", CharacterFormat(fontSizePt = 24f)))
        body.listStyle = SlideListStyle.BULLET
        body.lineSpacing = spacing
    }

    @Test
    fun theLineSpacingListSetsTheBox() {
        val e = SlideEditor()
        e.load(deck(1f))
        e.setSelection(e.slide.body, 0, 0)
        e.setLineSpacing(1.5f)
        assertEquals(1.5f, e.slide.body.lineSpacing)
        assertEquals("the title keeps single spacing", 1f, e.slide.title.lineSpacing)
        e.undo()
        assertEquals(1f, e.slide.body.lineSpacing)
        // Drawn as a multiple of single spacing (1.2 em) of the box's largest text; single leaves the face's own.
        assertEquals(TextUnit.Unspecified, slideBaseStyle(e.slide.body).lineHeight)
        e.slide.body.lineSpacing = 1.5f
        assertEquals((1.5f * 1.2f).em, slideBaseStyle(e.slide.body).lineHeight)
    }

    @Test
    fun lineSpacingGoesToPowerPointAndBack() {
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(deck(1.5f), it) }.toByteArray()
        val slide = String(OfficeXml.unzip(bytes)["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(slide, slide.contains("""<a:pPr marL="342900" indent="-342900" algn="l"><a:lnSpc><a:spcPct val="150000"/></a:lnSpc><a:buFont typeface="Arial"/><a:buChar char="•"/></a:pPr>"""))
        assertEquals("single spacing says nothing", 2, Regex("<a:lnSpc>").findAll(slide).count())
        val back = PptxReader.read(bytes).slides[0]
        assertEquals(1.5f, back.body.lineSpacing, 0.0001f)
        assertEquals(1f, back.title.lineSpacing)
    }

    @Test
    fun powerPointsMasterAndExactSpacingAreRead() {
        val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
        val slide = """<p:sld $ns><p:cSld><p:spTree>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>Plan</a:t></a:r></a:p></p:txBody></p:sp>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>Research</a:t></a:r></a:p></p:txBody></p:sp>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="4" name="Box"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="100" y="100"/><a:ext cx="4000000" cy="1000000"/></a:xfrm><a:prstGeom prst="rect"/></p:spPr>""" +
            """<p:txBody><a:bodyPr/><a:p><a:pPr><a:lnSpc><a:spcPts val="3600"/></a:lnSpc></a:pPr><a:r><a:rPr sz="2400"/><a:t>Exact</a:t></a:r></a:p></p:txBody></p:sp>""" +
            """</p:spTree></p:cSld></p:sld>"""
        // Office's own master: titles and bodies at 90%.
        val master = """<p:sldMaster $ns><p:cSld><p:spTree/></p:cSld><p:txStyles>""" +
            """<p:titleStyle><a:lvl1pPr><a:lnSpc><a:spcPct val="90000"/></a:lnSpc><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle>""" +
            """<p:bodyStyle><a:lvl1pPr><a:lnSpc><a:spcPct val="90000"/></a:lnSpc><a:buChar char="•"/><a:defRPr sz="2800"/></a:lvl1pPr></p:bodyStyle>""" +
            """</p:txStyles></p:sldMaster>"""
        val rels = { target: String, type: String -> """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type" Target="$target"/></Relationships>""" }
        val files = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to rels("slides/slide1.xml", "slide"),
            "ppt/slides/slide1.xml" to slide,
            "ppt/slides/_rels/slide1.xml.rels" to rels("../slideLayouts/slideLayout1.xml", "slideLayout"),
            "ppt/slideLayouts/slideLayout1.xml" to """<p:sldLayout $ns><p:cSld><p:spTree/></p:cSld></p:sldLayout>""",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels("../slideMasters/slideMaster1.xml", "slideMaster"),
            "ppt/slideMasters/slideMaster1.xml" to master,
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        val s = PptxReader.read(bos.toByteArray()).slides[0]
        assertEquals("the master's 90% for the title", 0.9f, s.title.lineSpacing, 0.0001f)
        assertEquals(0.9f, s.body.lineSpacing, 0.0001f)
        val box = s.shapes.single { it.box.text == "Exact" }.box
        assertEquals("36 pt over 24 pt text: 1.25 of single (1.2 × 24)", 1.25f, box.lineSpacing, 0.0001f)
    }

    @Test
    fun lineSpacingGoesToOpenDocumentAndBack() {
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(deck(1.5f), it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertTrue(content.contains("""fo:line-height="150%""""))
        assertEquals(1.5f, OdpReader.read(bytes).slides[0].body.lineSpacing, 0.0001f)
        val single = String(OfficeXml.unzip(ByteArrayOutputStream().also { OdpWriter.write(deck(1f), it) }.toByteArray())["content.xml"]!!, Charsets.UTF_8)
        assertFalse(single.contains("fo:line-height"))
    }
}
