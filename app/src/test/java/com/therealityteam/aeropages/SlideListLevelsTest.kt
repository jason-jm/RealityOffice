package com.therealityteam.aeropages

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLevels
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.ui.keepLevel
import com.therealityteam.aeropages.ui.listVisualTransformation
import com.therealityteam.aeropages.ui.listedText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Outline levels of slide text: tabs in the text, Tab / Shift+Tab, Enter, and pptx / ODP both ways. */
class SlideListLevelsTest {

    private val outline = "Plan\n\tResearch\n\t\tInterviews\n\tBuild\nShip"

    @Test
    fun levelsNumbersAndMarkers() {
        assertEquals(listOf(0, 1, 2, 1, 0), SlideLevels.levels(outline).toList())
        assertEquals("each level counts on its own, restarting under a shallower line", listOf(1, 1, 1, 2, 2), SlideLevels.numbers(SlideLevels.levels(outline)).toList())
        assertEquals("1.", SlideLevels.numberLabel(1, 0))
        assertEquals("b.", SlideLevels.numberLabel(2, 1))
        assertEquals("iv.", SlideLevels.numberLabel(4, 2))
        assertEquals("aa.", SlideLevels.numberLabel(27, 1))
        assertEquals("–", SlideLevels.marker(SlideListStyle.BULLET, 1, 1))
        assertEquals("", SlideLevels.marker(SlideListStyle.NONE, 3, 1))
        assertEquals("Plan\nResearch\nInterviews\nBuild\nShip", SlideLevels.strip(outline))
        assertEquals("nine levels at most", SlideLevels.MAX, SlideLevels.levelAt("\t".repeat(12) + "x", 0))
        assertTrue(SlideLevels.hasLevels(outline))
        assertFalse(SlideLevels.hasLevels("a\tb\nc"))
    }

    private fun box(text: String, style: SlideListStyle = SlideListStyle.BULLET) =
        SlideBox(Paragraph(mutableListOf(TextRun(text, CharacterFormat(fontSizePt = 24f))), ParagraphFormat()), style)

    @Test
    fun tabAndShiftTabMoveLinesKeepingTheirLook() {
        val e = SlideEditor()
        e.load(Presentation.blank())
        val b = e.slide.body
        b.paragraph.runs.clear()
        b.paragraph.runs.add(TextRun("Alpha\n", CharacterFormat(fontSizePt = 24f)))
        b.paragraph.runs.add(TextRun("Beta", CharacterFormat(fontSizePt = 24f, bold = true)))
        b.paragraph.runs.add(TextRun("\nGamma", CharacterFormat(fontSizePt = 24f)))
        b.listStyle = SlideListStyle.BULLET
        // Beta and Gamma selected: both go a level deeper; the selection moves with them.
        e.changeListLevel(b, 7, 14, 1)
        assertEquals("Alpha\n\tBeta\n\tGamma", b.text)
        assertEquals(8, e.selStart); assertEquals(16, e.selEnd)
        assertTrue("Beta keeps its bold", b.paragraph.runs.any { it.text.contains("Beta") && it.format.bold })
        e.changeListLevel(b, 8, 8, 1)
        assertEquals("Alpha\n\t\tBeta\n\tGamma", b.text)
        e.changeListLevel(b, 0, b.text.length, -1)
        assertEquals("Alpha\n\tBeta\nGamma", b.text)
        e.changeListLevel(b, 0, 0, -1)
        assertEquals("nothing above the first level", "Alpha\n\tBeta\nGamma", b.text)
        e.undo()
        assertEquals("Alpha\n\t\tBeta\n\tGamma", e.slide.body.text)
        // A selection ending just after a line break leaves the next line alone.
        e.changeListLevel(e.slide.body, 0, 6, 1)
        assertEquals("\tAlpha\n\t\tBeta\n\tGamma", e.slide.body.text)
    }

    @Test
    fun enterKeepsTheLevel() {
        val old = "One\n\tTwo"
        val typed = TextFieldValue("One\n\tTwo\n", TextRange(9))
        val kept = keepLevel(old, typed)
        assertEquals("One\n\tTwo\n\t", kept.text)
        assertEquals(TextRange(10), kept.selection)
        assertEquals("at the first level nothing is added", TextFieldValue("One\n", TextRange(4)), keepLevel("One", TextFieldValue("One\n", TextRange(4))))
        assertEquals("typing is untouched", "One\n\tTwox", keepLevel(old, TextFieldValue("One\n\tTwox", TextRange(9))).text)
        // Delete at the end of "One" joins the lines as text: Two's level tab goes.
        val joined = keepLevel(old, TextFieldValue("One\tTwo", TextRange(3)))
        assertEquals("OneTwo", joined.text)
        assertEquals(TextRange(3), joined.selection)
    }

    @Test
    fun theFieldHidesTabsAndMapsTheCaret() {
        val b = box(outline)
        val raw = b.text
        val t = listVisualTransformation(b).filter(AnnotatedString(raw))
        val shown = t.text.text
        assertFalse("no tabs shown", shown.contains('\t'))
        assertTrue(shown.startsWith("• Plan​– Research"))
        val m = t.offsetMapping
        // Every offset maps both ways within bounds, monotonically.
        var last = -1
        for (o in 0..raw.length) {
            val x = m.originalToTransformed(o)
            assertTrue(x in 0..shown.length && x >= last)
            last = x
            assertTrue(m.transformedToOriginal(x) in 0..raw.length)
        }
        // A caret in a line's tabs shows at its text; a tap on a marker lands after the tabs.
        val research = raw.indexOf("Research")
        assertEquals(m.originalToTransformed(research), m.originalToTransformed(research - 1))
        assertEquals(research, m.transformedToOriginal(shown.indexOf("Research") - 1))
        // A line's end and the next line's start stay apart.
        val end = raw.indexOf('\n')
        assertEquals(m.originalToTransformed(end) + 1, m.originalToTransformed(end + 1) - 2)
        // Numbers count per level.
        val numbered = listedText(AnnotatedString(raw), raw, SlideListStyle.NUMBER).text
        assertTrue(numbered, numbered.contains("1. Plan") && numbered.contains("a. Research") && numbered.contains("b. Build") && numbered.contains("2. Ship"))
    }

    @Test
    fun levelsGoToPowerPointAndBack() {
        val p = com.therealityteam.aeropages.model.Presentation(mutableListOf(com.therealityteam.aeropages.model.Slide(title = com.therealityteam.aeropages.model.SlideBox(""), body = com.therealityteam.aeropages.model.SlideBox(""), layout = com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT)))
        val s = p.slides[0]
        s.body.paragraph.runs.clear()
        s.body.paragraph.runs.add(TextRun(outline, CharacterFormat(fontSizePt = 24f)))
        s.body.listStyle = SlideListStyle.NUMBER
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val slide = String(OfficeXml.unzip(bytes)["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(slide, slide.contains("""<a:pPr marL="914400" lvl="1" indent="-457200" algn="l"><a:buFont typeface="+mj-lt"/><a:buAutoNum type="alphaLcPeriod"/></a:pPr>"""))
        assertTrue(slide.contains("""lvl="2" indent="-457200" algn="l"><a:buFont typeface="+mj-lt"/><a:buAutoNum type="romanLcPeriod"/>"""))
        assertFalse("no tabs in the text", slide.contains("<a:t>\t"))
        val back = PptxReader.read(bytes)
        assertEquals(outline, back.slides[0].body.text)
        assertEquals(SlideListStyle.NUMBER, back.slides[0].body.listStyle)
    }

    @Test
    fun powerPointsDeeperLevelsTakeTheMastersSizes() {
        // A body in PowerPoint's usual shape: levels in a:pPr@lvl, sizes from the master's body style.
        val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main""""
        val slide = """<p:sld $ns><p:cSld><p:spTree>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>Plan</a:t></a:r></a:p></p:txBody></p:sp>""" +
            """<p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/>""" +
            """<a:p><a:r><a:t>Research</a:t></a:r></a:p><a:p><a:pPr lvl="1"/><a:r><a:t>Interviews</a:t></a:r><a:br/><a:r><a:t>and notes</a:t></a:r></a:p>""" +
            """<a:p><a:pPr lvl="2"/><a:r><a:rPr sz="1400"/><a:t>Small print</a:t></a:r></a:p></p:txBody></p:sp>""" +
            """</p:spTree></p:cSld></p:sld>"""
        val master = """<p:sldMaster $ns><p:cSld><p:spTree/></p:cSld><p:txStyles><p:titleStyle/><p:bodyStyle>""" +
            """<a:lvl1pPr><a:buChar char="•"/><a:defRPr sz="2800"/></a:lvl1pPr><a:lvl2pPr><a:defRPr sz="2400"/></a:lvl2pPr><a:lvl3pPr><a:defRPr sz="2000"/></a:lvl3pPr>""" +
            """</p:bodyStyle></p:txStyles></p:sldMaster>"""
        val rels = { target: String, type: String -> """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type" Target="$target"/></Relationships>""" }
        val files = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId2"/></p:sldMasterIdLst><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to slide,
            "ppt/slides/_rels/slide1.xml.rels" to rels("../slideLayouts/slideLayout1.xml", "slideLayout"),
            "ppt/slideLayouts/slideLayout1.xml" to """<p:sldLayout $ns><p:cSld><p:spTree/></p:cSld></p:sldLayout>""",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels("../slideMasters/slideMaster1.xml", "slideMaster"),
            "ppt/slideMasters/slideMaster1.xml" to master,
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        val body = PptxReader.read(bos.toByteArray()).slides[0].body
        assertEquals("a line break goes on under the same bullet", "Research\n\tInterviews\u2028and notes\n\t\tSmall print", body.text)
        assertEquals(SlideListStyle.BULLET, body.listStyle)
        val sizes = body.paragraph.runs.filter { it.text.isNotBlank() && !it.text.startsWith("\t") && !it.text.startsWith("\u2028") }.associate { it.text to it.format.fontSizePt }
        assertEquals(28f, sizes["Research"])
        assertEquals("level 2 takes the master's 24 pt", 24f, sizes["Interviews"])
        assertEquals("a size of its own wins", 14f, sizes["Small print"])
    }

    @Test
    fun lineBreaksStayInsideTheirItem() {
        val raw = "First\u2028more of it\n\tSub\u2028"
        val b = box(raw)
        // On screen: the piece after a break is a paragraph of its own, under the item's text; the break's place a zero-width space.
        val t = listVisualTransformation(b).filter(AnnotatedString(raw))
        val shown = t.text.text
        assertEquals("•\u2002First\u200Bmore of it\u200B–\u2002Sub\u200B\u200B", shown)
        assertEquals("a paragraph per piece", 4, t.text.paragraphStyles.size)
        val m = t.offsetMapping
        for (o in 0..raw.length) { assertTrue(m.originalToTransformed(o) in 0..shown.length); assertTrue(m.transformedToOriginal(m.originalToTransformed(o)) in 0..raw.length) }
        assertEquals("the break's own place", shown.indexOf('\u200B'), m.originalToTransformed(5))
        assertEquals("the line after it", shown.indexOf("more"), m.originalToTransformed(6))
        // Elsewhere a break shows as a new line, one character for one.
        assertEquals("Quarterly\nreview", com.therealityteam.aeropages.ui.shownBreaks("Quarterly\u2028review"))

        // pptx: a:br inside the item's paragraph; ODP: text:line-break.
        val p = Presentation.blank()
        p.slides[0].body.paragraph.runs.clear()
        p.slides[0].body.paragraph.runs.add(TextRun(raw.dropLast(1), CharacterFormat(fontSizePt = 24f)))
        p.slides[0].body.listStyle = SlideListStyle.BULLET
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(pptx)["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(xml, Regex("""<a:t>First</a:t></a:r><a:br><a:rPr [^>]*sz="2400"[^>]*/></a:br><a:r>""").containsMatchIn(xml))
        assertEquals("two items, not three", 2, Regex("<a:p>").findAll(xml.substringAfter("idx=\"1\"")).count())
        assertEquals("First\u2028more of it\n\tSub", PptxReader.read(pptx).slides[0].body.text)
        val odp = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        assertTrue(String(OfficeXml.unzip(odp)["content.xml"]!!, Charsets.UTF_8).contains("First<text:line-break/>more of it"))
        assertEquals("First\u2028more of it\n\tSub", OdpReader.read(odp).slides[0].body.text)
    }

    @Test
    fun levelsGoToOpenDocumentAndBack() {
        val p = Presentation.blank()
        val s = p.slides[0]
        s.body.paragraph.runs.clear()
        s.body.paragraph.runs.add(TextRun(outline, CharacterFormat(fontSizePt = 24f)))
        s.body.listStyle = SlideListStyle.BULLET
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertTrue("deeper lines nest inside the item before them",
            content.contains("""Plan</text:p><text:list><text:list-item><text:p text:style-name=""") && content.contains("""Research</text:p><text:list><text:list-item>"""))
        assertTrue(content.contains("""<text:list-level-style-bullet text:level="2" text:bullet-char="–">"""))
        assertFalse(content.contains("\t"))
        assertEquals(outline, OdpReader.read(bytes).slides[0].body.text)
        // Without a list, a level is an indent.
        s.body.listStyle = SlideListStyle.NONE
        val plain = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        assertTrue(String(OfficeXml.unzip(plain)["content.xml"]!!, Charsets.UTF_8).contains("""fo:margin-left="72.00pt""""))
        val read = OdpReader.read(plain).slides[0].body
        assertEquals(outline, read.text)
        assertEquals(SlideListStyle.NONE, read.listStyle)
    }
}
