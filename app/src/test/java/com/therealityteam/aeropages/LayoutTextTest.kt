package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Text a slide leaves unset is its layout placeholder's before its master's — PowerPoint's title
 * slide has 60 pt titles, its section header grey text — and a master that sets nothing (LibreOffice
 * 26 keeps each master page's look on a layout) takes the first slide's layout's look.
 */
class LayoutTextTest {

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" """ +
        """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
    private val relsHead = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"""
    private fun rel(id: String, type: String, target: String) =
        """<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type" Target="$target"/>"""
    private val clrMap = """<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>"""

    /** A placeholder: its type / idx, its list style's first level ([lvl1], inside `a:lvl1pPr`) and its lines (each a run: [rPr] its attributes, [rPrKids] its fill and face). */
    private fun ph(id: Int, type: String?, idx: String?, lvl1: String = "", lines: List<String> = emptyList(), rPr: String = "", rPrKids: String = "",
        spPr: String = "<p:spPr/>", lvl1Attrs: String = "", pPr: String = "") = buildString {
        append("""<p:sp><p:nvSpPr><p:cNvPr id="$id" name="ph$id"/><p:cNvSpPr/><p:nvPr><p:ph""")
        if (type != null) append(""" type="$type"""")
        if (idx != null) append(""" idx="$idx"""")
        append("""/></p:nvPr></p:nvSpPr>$spPr<p:txBody><a:bodyPr/>""")
        append(if (lvl1.isEmpty() && lvl1Attrs.isEmpty()) "<a:lstStyle/>" else "<a:lstStyle><a:lvl1pPr$lvl1Attrs>$lvl1</a:lvl1pPr></a:lstStyle>")
        if (lines.isEmpty()) append("<a:p/>")
        lines.forEach { append("""<a:p>$pPr<a:r><a:rPr lang="en-US"$rPr>$rPrKids</a:rPr><a:t>$it</a:t></a:r></a:p>""") }
        append("</p:txBody></p:sp>")
    }

    private fun deck(master: String, layouts: List<String>, slides: List<Pair<String, Int>>): ByteArray {
        val parts = LinkedHashMap<String, String>()
        parts["ppt/slideMasters/slideMaster1.xml"] = master
        parts["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = relsHead + layouts.indices.joinToString("") { rel("rId${it + 1}", "slideLayout", "../slideLayouts/slideLayout${it + 1}.xml") } + "</Relationships>"
        layouts.forEachIndexed { i, l ->
            parts["ppt/slideLayouts/slideLayout${i + 1}.xml"] = l
            parts["ppt/slideLayouts/_rels/slideLayout${i + 1}.xml.rels"] = relsHead + rel("rId1", "slideMaster", "../slideMasters/slideMaster1.xml") + "</Relationships>"
        }
        slides.forEachIndexed { i, (s, layout) ->
            parts["ppt/slides/slide${i + 1}.xml"] = s
            parts["ppt/slides/_rels/slide${i + 1}.xml.rels"] = relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout$layout.xml") + "</Relationships>"
        }
        return zipOf(parts)
    }

    private fun slide(vararg sps: String) = "<p:sld $ns><p:cSld><p:spTree>${sps.joinToString("")}</p:spTree></p:cSld></p:sld>"
    private fun layout(type: String, name: String, vararg sps: String, bg: String = "") =
        """<p:sldLayout $ns type="$type"><p:cSld name="$name">$bg<p:spTree>${sps.joinToString("")}</p:spTree></p:cSld></p:sldLayout>"""

    /** PowerPoint's own template, as its masters and layouts set text: 44 / 28 pt on the master, 60 / 24 on the title slide and section header. */
    private fun powerPointDeck(): ByteArray {
        val master = "<p:sldMaster $ns><p:cSld><p:spTree>" + ph(2, "title", null) + ph(3, "body", "1") + "</p:spTree></p:cSld>$clrMap" +
            """<p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle>""" +
            """<p:bodyStyle><a:lvl1pPr><a:defRPr sz="2800"/></a:lvl1pPr><a:lvl2pPr><a:defRPr sz="2400"/></a:lvl2pPr></p:bodyStyle></p:txStyles></p:sldMaster>"""
        val titleSlide = layout("title", "Title Slide", ph(2, "ctrTitle", null, """<a:defRPr sz="6000"/>""", lvl1Attrs = """ algn="ctr""""),
            ph(3, "subTitle", "1", """<a:buNone/><a:defRPr sz="2400"/>""", lvl1Attrs = """ marL="0" indent="0" algn="ctr""""))
        val content = layout("obj", "Title and Content", ph(2, "title", null), ph(3, null, "1"))
        val section = layout("secHead", "Section Header", ph(2, "title", null, """<a:defRPr sz="6000"/>"""),
            ph(3, "body", "1", """<a:buNone/><a:defRPr sz="2400"><a:solidFill><a:srgbClr val="595959"/></a:solidFill></a:defRPr>"""),
            ph(4, "body", "2", """<a:defRPr sz="2000" b="1"/>""", spPr = """<p:spPr><a:xfrm><a:off x="838200" y="5000000"/><a:ext cx="10515600" cy="800000"/></a:xfrm></p:spPr>"""))
        return deck(master, listOf(titleSlide, content, section), listOf(
            slide(ph(2, "ctrTitle", null, lines = listOf("Quarterly review")), ph(3, "subTitle", "1", lines = listOf("Finance team"))) to 1,
            slide(ph(2, "title", null, lines = listOf("Agenda")), ph(3, null, "1", lines = listOf("Numbers"))) to 2,
            slide(ph(2, "title", null, lines = listOf("Part two")), ph(3, "body", "1", lines = listOf("What changed")), ph(4, "body", "2", lines = listOf("Bold note"))) to 3,
            slide(ph(2, "ctrTitle", null, lines = listOf("Its own size"), rPr = """ sz="3200"""", pPr = """<a:pPr algn="l"/>"""), ph(3, "subTitle", "1", lines = listOf("Black"),
                rPrKids = """<a:solidFill><a:srgbClr val="000000"/></a:solidFill>""")) to 1,
        ))
    }

    @Test
    fun titleSlidesAndSectionHeadersTakeTheirLayoutsText() {
        val bytes = powerPointDeck()
        System.getProperty("aero.sampleDir")?.let { java.io.File(it).apply { mkdirs() }.resolve("layouttext.pptx").writeBytes(bytes) }
        val p = PptxReader.read(bytes)
        assertEquals(44f, p.master.titleSizePt); assertEquals(28f, p.master.bodySizePt)
        val title = p.slides[0]
        assertEquals("the title slide's 60 pt title", 60f, title.title.paragraph.runs.first().format.fontSizePt)
        assertEquals("its 24 pt subtitle", 24f, title.body.paragraph.runs.first().format.fontSizePt)
        val content = p.slides[1]
        assertEquals(44f, content.title.paragraph.runs.first().format.fontSizePt)
        assertEquals(28f, content.body.paragraph.runs.first().format.fontSizePt)
        assertNull("the master's colour stays the master's", content.body.paragraph.runs.first().format.color)
        val section = p.slides[2]
        assertEquals(SlideLayout.SECTION, section.layout)
        assertEquals(60f, section.title.paragraph.runs.first().format.fontSizePt)
        val text = section.body.paragraph.runs.first().format
        assertEquals(24f, text.fontSizePt); assertEquals("its grey", 0xFF595959, text.color)
        val note = section.shapes.single { it.box.text == "Bold note" }.box.paragraph.runs.first().format
        assertEquals("a further placeholder its own layout's", 20f, note.fontSizePt); assertEquals(true, note.bold)
        assertEquals("centred as its layout centres it", TextAlign.CENTER, title.title.align)
        assertEquals(TextAlign.CENTER, title.body.align)
        assertEquals("the master's left", TextAlign.LEFT, content.title.align)
        assertNull("its layout's idx 1 placing nothing, the master's (none) — not idx 2's frame", section.body.frame)
        assertEquals("a size of the run's own kept", 32f, p.slides[3].title.paragraph.runs.first().format.fontSizePt)
        assertEquals("its paragraph's own left kept", TextAlign.LEFT, p.slides[3].title.align)
        assertEquals("a colour of its own kept", 0xFF000000, p.slides[3].body.paragraph.runs.first().format.color)
    }

    @Test
    fun theAppsOwnDecksReadAsBefore() {
        val deck = Presentation(mutableListOf(
            Slide(title = SlideBox("Title"), body = SlideBox("Subtitle")).apply { layout = SlideLayout.TITLE },
            Slide(title = SlideBox("Points"), body = SlideBox("One\nTwo")),
            Slide(title = SlideBox("Section"), body = SlideBox("Next")).apply { layout = SlideLayout.SECTION },
        ))
        val back = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray())
        back.slides.forEach { s ->
            (s.title.paragraph.runs + s.body.paragraph.runs).filter { it.text.isNotBlank() }.forEach { r ->
                assertNull("${r.text}: no face of its own", r.format.fontFamily); assertNull(r.format.color)
            }
            assertEquals(40f, s.title.paragraph.runs.first().format.fontSizePt)
            assertEquals(22f, s.body.paragraph.runs.first().format.fontSizePt)
        }
        // A title slide's left-aligned title says so: its layout centres what it leaves unsaid.
        val left = Presentation(mutableListOf(Slide(title = SlideBox("Left title", align = TextAlign.LEFT), body = SlideBox("Left subtitle")).apply { layout = SlideLayout.TITLE },
            Slide(title = SlideBox("No subtitle yet", align = TextAlign.CENTER), body = SlideBox("")).apply { layout = SlideLayout.TITLE }))
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(left, it) }.toByteArray()
        System.getProperty("aero.sampleDir")?.let { java.io.File(it).apply { mkdirs() }.resolve("titleslides.pptx").writeBytes(bytes) }
        val again = PptxReader.read(bytes).slides[0]
        assertEquals(TextAlign.LEFT, again.title.align); assertEquals(TextAlign.LEFT, again.body.align)
    }

    @Test
    fun choosingTheTitleSlideLayoutCentresTheSubtitle() {
        val e = com.therealityteam.aeropages.editor.SlideEditor()
        // From a Title and Content slide (a new deck already starts on a Title Slide).
        e.load(Presentation(mutableListOf(Slide(title = SlideBox(""), body = SlideBox(""), layout = SlideLayout.TITLE_CONTENT))))
        assertEquals(TextAlign.LEFT, e.slide.body.align)
        e.setLayout(SlideLayout.TITLE)
        assertEquals("a title slide's subtitle, centred", TextAlign.CENTER, e.slide.body.align)
        e.setLayout(SlideLayout.TITLE_CONTENT)
        assertEquals("back to text from the left", TextAlign.LEFT, e.slide.body.align)
        e.undo()
        assertEquals(TextAlign.CENTER, e.slide.body.align); assertEquals(SlideLayout.TITLE, e.slide.layout)
        // Text aligned otherwise keeps its own.
        e.setLayout(SlideLayout.SECTION); e.slide.body.paragraph.format.align = TextAlign.RIGHT
        e.setLayout(SlideLayout.TITLE)
        assertEquals(TextAlign.RIGHT, e.slide.body.align)
    }

    /** As LibreOffice 26 writes a deck: a bare slide master, each master page a layout with its background and text. */
    private fun libreOffice26Deck(): ByteArray {
        val master = "<p:sldMaster $ns><p:cSld><p:spTree/></p:cSld>$clrMap</p:sldMaster>"
        val bg = """<p:bg><p:bgPr><a:solidFill><a:srgbClr val="1F3864"/></a:solidFill></p:bgPr></p:bg>"""
        val titleLvl = """<a:defRPr sz="4400" b="0"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:latin typeface="Georgia"/></a:defRPr>"""
        val bodyLvl = """<a:defRPr sz="2400"><a:solidFill><a:srgbClr val="D9D9D9"/></a:solidFill><a:latin typeface="Verdana"/></a:defRPr>"""
        val number = """<p:sp><p:nvSpPr><p:cNvPr id="9" name="n"/><p:cNvSpPr/><p:nvPr><p:ph type="sldNum" idx="3"/></p:nvPr></p:nvSpPr>""" +
            """<p:spPr><a:xfrm><a:off x="9000000" y="6356160"/><a:ext cx="2743200" cy="364680"/></a:xfrm></p:spPr><p:txBody><a:bodyPr/>""" +
            """<a:lstStyle><a:lvl1pPr algn="r"><a:defRPr sz="1600"><a:solidFill><a:srgbClr val="BFBFBF"/></a:solidFill></a:defRPr></a:lvl1pPr></a:lstStyle><a:p/></p:txBody></p:sp>"""
        val content = layout("obj", "Title and Content", ph(2, "title", null, titleLvl), ph(3, "body", null, bodyLvl), number, bg = bg)
        val other = layout("title", "Title Slide", ph(2, "title", null, """<a:defRPr sz="5400"><a:solidFill><a:srgbClr val="FFE699"/></a:solidFill><a:latin typeface="Georgia"/></a:defRPr>"""),
            bg = """<p:bg><p:bgPr><a:solidFill><a:srgbClr val="7A1F2B"/></a:solidFill></p:bgPr></p:bg>""")
        // LibreOffice spells out a run's look.
        val run = """<a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:latin typeface="Georgia"/>"""
        return deck(master, listOf(content, other), listOf(
            slide(ph(2, "title", null, lines = listOf("Master-driven deck"), rPrKids = run), ph(3, "body", null, lines = listOf("Inherits"))) to 1,
            slide(ph(2, "title", null, lines = listOf("Second"))) to 1,
            slide(ph(2, "title", null, lines = listOf("On its own master"))) to 2,
        ))
    }

    @Test
    fun aBareMasterTakesTheFirstSlidesLayout() {
        val p = PptxReader.read(libreOffice26Deck())
        val m = p.master
        assertEquals(0xFF1F3864, m.background)
        assertEquals("Georgia", m.titleFont); assertEquals(44f, m.titleSizePt); assertEquals(0xFFFFFFFF, m.titleColor); assertNull(m.titleBold)
        assertEquals("Verdana", m.bodyFont); assertEquals(24f, m.bodySizePt); assertEquals(0xFFD9D9D9, m.bodyColor)
        val n = m.chrome[com.therealityteam.aeropages.model.SlideChrome.Part.NUMBER]
        assertNotNull("where its layout puts the number", n); assertEquals(16f, n!!.sizePt)
        assertNull("the master's background, inherited", p.slides[0].background)
        assertNull(p.slides[1].background)
        val body = p.slides[0].body.paragraph.runs.first { it.text.isNotBlank() }.format
        assertNull("the master's face and colour, inherited", body.fontFamily); assertNull(body.color)
        assertEquals(24f, body.fontSizePt)
        // The other master page's slide: its own background and title look.
        val third = p.slides[2]
        assertEquals(0xFF7A1F2B, third.background)
        val t = third.title.paragraph.runs.first().format
        assertEquals(54f, t.fontSizePt); assertEquals(0xFFFFE699, t.color)
        assertTrue("nothing drawn: no designs", p.designs.isEmpty())
    }
}
