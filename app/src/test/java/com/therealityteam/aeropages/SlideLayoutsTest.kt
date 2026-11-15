package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.BoxFrame
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Slide layouts kept through PowerPoint and OpenDocument files, as each program keeps them. */
class SlideLayoutsTest {

    private fun deck() = Presentation(mutableListOf(
        Slide(title = SlideBox("Quarterly review", CharacterFormat(fontSizePt = 40f)), body = SlideBox("Board meeting"), layout = SlideLayout.TITLE),
        Slide(title = SlideBox("Just a title slide"), body = SlideBox(""), layout = SlideLayout.TITLE),
        Slide(title = SlideBox("Agenda"), body = SlideBox(""), layout = SlideLayout.TITLE_CONTENT),
        Slide(title = SlideBox("Results"), body = SlideBox("Revenue up\nCosts down"), layout = SlideLayout.TITLE_CONTENT),
        Slide(title = SlideBox("Part two"), body = SlideBox("What comes next"), layout = SlideLayout.SECTION),
        Slide(title = SlideBox("A chart"), body = SlideBox(""), layout = SlideLayout.BLANK),
        Slide(title = SlideBox(""), body = SlideBox(""), layout = SlideLayout.BLANK),
    ))

    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = zin.nextEntry ?: break; out[e.name] = zin.readBytes().decodeToString() }
        return out
    }

    @Test
    fun layoutsComeBackFromPowerPoint() {
        val bos = ByteArrayOutputStream()
        PptxWriter.write(deck(), bos)
        val back = PptxReader.read(bos.toByteArray())
        assertEquals(deck().slides.map { it.layout }, back.slides.map { it.layout })
        val parts = entries(bos.toByteArray())
        // PowerPoint's five layouts, each slide on its own, placeholders typed as that layout types them.
        assertEquals(listOf("title", "obj", "secHead", "titleOnly", "blank"),
            (1..5).map { k -> Regex("""<p:sldLayout [^>]*type="(\w+)"""").find(parts["ppt/slideLayouts/slideLayout$k.xml"]!!)!!.groupValues[1] })
        assertEquals(listOf(1, 1, 2, 2, 3, 4, 5), (1..7).map { i -> Regex("""slideLayout(\d)\.xml""").find(parts["ppt/slides/_rels/slide$i.xml.rels"]!!)!!.groupValues[1].toInt() })
        assertTrue(parts["ppt/slides/slide1.xml"]!!.contains("""<p:ph type="ctrTitle"/>""") && parts["ppt/slides/slide1.xml"]!!.contains("""<p:ph type="subTitle" idx="1"/>"""))
        assertTrue(parts["ppt/slides/slide4.xml"]!!.contains("""<p:ph idx="1"/>"""))
        assertTrue("the master has the placeholders its layouts inherit", parts["ppt/slideMasters/slideMaster1.xml"]!!.contains("""<p:ph type="body" idx="1"/>"""))
        assertTrue(parts["ppt/slideLayouts/slideLayout1.xml"]!!.contains("""<p:ph type="subTitle" idx="1"/>"""))
    }

    @Test
    fun layoutsComeBackFromOpenDocument() {
        val bos = ByteArrayOutputStream()
        OdpWriter.write(deck(), bos)
        val back = OdpReader.read(bos.toByteArray())
        assertEquals(deck().slides.map { it.layout }, back.slides.map { it.layout })
        // The empty placeholders come back where they were, asking for text.
        assertEquals(BoxFrame.defaultFor(SlideLayout.TITLE_CONTENT, false).yPct, back.slides[2].body.frame!!.yPct, 0.001f)
        val parts = entries(bos.toByteArray())
        val content = parts["content.xml"]!!
        assertTrue(content.contains("""presentation:presentation-page-layout-name="AL1T0""""))
        assertTrue("an empty outline is Impress's placeholder", Regex("""presentation:class="outline" presentation:user-transformed="true" presentation:placeholder="true"[^>]*><draw:text-box/>""").containsMatchIn(content))
        assertTrue("placeholders are presentation objects", Regex("""<draw:frame presentation:style-name="pr\d+"[^>]*presentation:class="title"""").containsMatchIn(content))
        val styles = parts["styles.xml"]!!
        assertTrue(styles.contains("""<style:presentation-page-layout style:name="AL1T0"><presentation:placeholder presentation:object="title""""))
        assertTrue(styles.contains("""presentation:object="subtitle""""))
    }

    @Test
    fun powerPointsOwnTitleSlidesAndSectionHeaders() {
        // A slide on PowerPoint's Title Slide layout names only its placeholders' types; a section
        // header's slide says nothing of itself — its layout does.
        val sp = { ph: String, text: String -> """<p:sp><p:nvSpPr><p:cNvPr id="2" name="x"/><p:cNvSpPr/><p:nvPr>$ph</p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:txBody></p:sp>""" }
        val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
        val slide = { body: String -> """<p:sld $ns><p:cSld><p:spTree>$body</p:spTree></p:cSld></p:sld>""" }
        val files = linkedMapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId1"/><p:sldId id="257" r:id="rId2"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to slide(sp("""<p:ph type="ctrTitle"/>""", "Launch") + sp("""<p:ph type="subTitle" idx="1"/>""", "2026")),
            "ppt/slides/slide2.xml" to slide(sp("""<p:ph type="title"/>""", "Part two") + sp("""<p:ph type="body" idx="1"/>""", "Next")),
            "ppt/slides/_rels/slide2.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout3.xml"/></Relationships>""",
            "ppt/slideLayouts/slideLayout3.xml" to """<p:sldLayout $ns type="secHead"><p:cSld name="Section Header"><p:spTree/></p:cSld></p:sldLayout>""",
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (name, xml) -> z.putNextEntry(ZipEntry(name)); z.write(xml.toByteArray()); z.closeEntry() } }
        val p = PptxReader.read(bos.toByteArray())
        assertEquals(listOf(SlideLayout.TITLE, SlideLayout.SECTION), p.slides.map { it.layout })
        assertEquals("2026", p.slides[0].body.text)
    }

    @Test
    fun libreOfficePagesTellTheirLayouts() {
        // Impress names a page's layout (its placeholders say which), and a PowerPoint section
        // header it opened becomes a master page of that name.
        val content = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"><office:body><office:presentation>
<draw:page draw:name="p1" draw:master-page-name="Default" presentation:presentation-page-layout-name="AL1T0"><draw:frame presentation:class="title" svg:x="1cm" svg:y="5cm" svg:width="20cm" svg:height="3cm"><draw:text-box><text:p>Welcome</text:p></draw:text-box></draw:frame><draw:frame presentation:class="subtitle" presentation:placeholder="true" svg:x="3cm" svg:y="9cm" svg:width="16cm" svg:height="3cm"><draw:text-box/></draw:frame></draw:page>
<draw:page draw:name="p2" draw:master-page-name="Default" presentation:presentation-page-layout-name="AL2T19"><draw:frame presentation:class="title" svg:x="1cm" svg:y="1cm" svg:width="20cm" svg:height="3cm"><draw:text-box><text:p>Pictures</text:p></draw:text-box></draw:frame></draw:page>
<draw:page draw:name="p3" draw:master-page-name="Section_20_Header"><draw:frame presentation:class="title" svg:x="1cm" svg:y="6cm" svg:width="20cm" svg:height="3cm"><draw:text-box><text:p>Part two</text:p></draw:text-box></draw:frame><draw:frame presentation:class="outline" svg:x="1cm" svg:y="10cm" svg:width="20cm" svg:height="2cm"><draw:text-box><text:p>Next</text:p></draw:text-box></draw:frame></draw:page>
<draw:page draw:name="p4" draw:master-page-name="Default"><draw:frame presentation:class="title" svg:x="1cm" svg:y="1cm" svg:width="20cm" svg:height="3cm"><draw:text-box><text:p>Old file</text:p></draw:text-box></draw:frame><draw:frame presentation:class="outline" presentation:placeholder="true" svg:x="1cm" svg:y="5cm" svg:width="20cm" svg:height="10cm"><draw:text-box/></draw:frame></draw:page>
</office:presentation></office:body></office:document-content>"""
        val styles = """<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"><office:styles>
<style:presentation-page-layout style:name="AL1T0"><presentation:placeholder presentation:object="title" svg:x="1cm" svg:y="5cm" svg:width="20cm" svg:height="3cm"/><presentation:placeholder presentation:object="subtitle" svg:x="3cm" svg:y="9cm" svg:width="16cm" svg:height="3cm"/></style:presentation-page-layout>
<style:presentation-page-layout style:name="AL2T19"><presentation:placeholder presentation:object="title" svg:x="1cm" svg:y="1cm" svg:width="20cm" svg:height="3cm"/></style:presentation-page-layout>
</office:styles></office:document-styles>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/vnd.oasis.opendocument.presentation".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("styles.xml")); z.write(styles.toByteArray()); z.closeEntry()
        }
        val p = OdpReader.read(bos.toByteArray())
        assertEquals(listOf(SlideLayout.TITLE, SlideLayout.BLANK, SlideLayout.SECTION, SlideLayout.TITLE_CONTENT), p.slides.map { it.layout })
        assertTrue("the empty subtitle keeps its place", p.slides[0].body.frame != null && p.slides[0].body.text.isEmpty())
    }
}
