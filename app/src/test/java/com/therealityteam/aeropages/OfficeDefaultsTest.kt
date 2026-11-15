package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideTransition
import com.therealityteam.aeropages.model.TransitionSpeed
import com.therealityteam.aeropages.model.VAlign
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * What a file leaves unsaid, said as the app means it — its fonts, sizes, alignment and speeds —
 * so Word, PowerPoint, Excel and LibreOffice show it as the app does; and what they write read as they mean it.
 */
class OfficeDefaultsTest {

    private fun entries(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = zin.nextEntry ?: break; out[e.name] = zin.readBytes().decodeToString() }
        return out
    }

    private fun zipOf(vararg parts: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (name, xml) -> z.putNextEntry(ZipEntry(name)); z.write(xml.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test
    fun documentsNameTheirFont() {
        val doc = Document(mutableListOf(ParagraphBlock(Paragraph.of("Hello"))))
        val docx = entries(ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray())
        // Word's own way: the theme's body font (Calibri), not Times New Roman for want of one.
        assertTrue(docx["word/styles.xml"]!!.contains("""<w:rFonts w:asciiTheme="minorHAnsi" w:eastAsiaTheme="minorEastAsia" w:hAnsiTheme="minorHAnsi" w:cstheme="minorBidi"/>"""))
        assertTrue(docx["word/theme/theme1.xml"]!!.contains("""<a:minorFont><a:latin typeface="Calibri"/>"""))
        assertTrue(docx["word/_rels/document.xml.rels"]!!.contains("Target=\"theme/theme1.xml\""))
        assertTrue(docx["[Content_Types].xml"]!!.contains("/word/theme/theme1.xml"))
        val odt = entries(ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray())
        assertTrue(odt["styles.xml"]!!.contains("""<style:default-style style:family="paragraph"><style:paragraph-properties style:tab-stop-distance="36.00pt"/><style:text-properties style:font-name="Calibri" fo:font-family="Calibri" fo:font-size="11.00pt""""))
        assertTrue(odt["styles.xml"]!!.contains("""<style:font-face style:name="Calibri" svg:font-family="Calibri" style:font-family-generic="swiss""""))
    }

    @Test
    fun slidesNameTheirDefaults() {
        val p = Presentation(mutableListOf(
            Slide(title = SlideBox("Unsized title"), body = SlideBox("Unsized body")).also { it.transition = SlideTransition.FADE },
            Slide(title = SlideBox("Plain")),
        ))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(p, bos)
        val parts = entries(bos.toByteArray())
        // Unsized placeholder text as the app shows it: titles 40 pt, bodies 22 pt, in the theme's fonts.
        val content = parts["ppt/slideLayouts/slideLayout2.xml"]!!
        assertTrue(content.contains("""<a:defRPr sz="4000"><a:latin typeface="+mj-lt"/></a:defRPr>"""))
        assertTrue(content.contains("""<a:defRPr sz="2200"><a:latin typeface="+mn-lt"/></a:defRPr>"""))
        // Other text in the theme's body font and the colour map's text colour (which a dark slide's map turns light).
        assertTrue(parts["ppt/slideMasters/slideMaster1.xml"]!!.contains("""<p:otherStyle><a:lvl1pPr><a:defRPr><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/></a:defRPr>"""))
        // A medium transition says so (no speed is "fast" to PowerPoint); no transition, no element.
        assertTrue(parts["ppt/slides/slide1.xml"]!!.contains("""<p:transition spd="med"><p:fade/></p:transition>"""))
        assertTrue(!parts["ppt/slides/slide2.xml"]!!.contains("p:transition"))
        val back = PptxReader.read(bos.toByteArray())
        assertEquals(TransitionSpeed.MEDIUM, back.slides[0].transitionSpeed)
        assertEquals("the master is as it was", true, back.master.sameAs(p.master))
    }

    @Test
    fun transitionSpeedsAsPowerPointAndLibreOfficeMeanThem() {
        val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main""""
        val title = """<p:cSld><p:spTree><p:sp><p:nvSpPr><p:cNvPr id="2" name="t"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>T</a:t></a:r></a:p></p:txBody></p:sp></p:spTree></p:cSld>"""
        val slides = listOf(
            """<p:transition><p:fade/></p:transition>""",                        // no speed: fast
            """<p:transition spd="slow" p14:dur="700"><p:fade/></p:transition>""", // the duration wins: medium
            """<p:transition spd="slow" p14:dur="2000"></p:transition>""",        // LibreOffice's empty one: none
        )
        val files = arrayListOf(
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst>${slides.indices.joinToString("") { """<p:sldId id="${256 + it}" r:id="rId${it + 1}"/>""" }}</p:sldIdLst><p:sldSz cx="12192000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${slides.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide${it + 1}.xml"/>""" }}</Relationships>""",
        )
        slides.forEachIndexed { i, t -> files.add("ppt/slides/slide${i + 1}.xml" to """<p:sld $ns>$title$t</p:sld>""") }
        val p = PptxReader.read(zipOf(*files.toTypedArray()))
        assertEquals(listOf(SlideTransition.FADE, SlideTransition.FADE, SlideTransition.NONE), p.slides.map { it.transition })
        assertEquals(listOf(TransitionSpeed.FAST, TransitionSpeed.MEDIUM, TransitionSpeed.MEDIUM), p.slides.map { it.transitionSpeed })
    }

    @Test
    fun libreOfficePagesTimingAndCharts() {
        // LibreOffice: a nominal "fast" in the page style, the real duration in the page's timing; a
        // chart frame holding the chart object and a picture of it.
        val chart = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:chart><chart:chart chart:class="chart:bar"><chart:plot-area><chart:series chart:values-cell-range-address="local-table.B2:B3"/></chart:plot-area><table:table table:name="local-table"><table:table-rows><table:table-row><table:table-cell/><table:table-cell office:value-type="string"><text:p>S</text:p></table:table-cell></table:table-row><table:table-row><table:table-cell office:value-type="string"><text:p>a</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="3"/></table:table-row><table:table-row><table:table-cell office:value-type="string"><text:p>b</text:p></table:table-cell><table:table-cell office:value-type="float" office:value="5"/></table:table-row></table:table-rows></table:table></office:chart></office:body></office:document-content>"""
        val content = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:smil="urn:oasis:names:tc:opendocument:xmlns:smil-compatible:1.0" xmlns:anim="urn:oasis:names:tc:opendocument:xmlns:animation:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink">""" +
            """<office:automatic-styles><style:style style:name="dp1" style:family="drawing-page"><style:drawing-page-properties presentation:transition-speed="fast" smil:type="fade" smil:subtype="crossfade"/></style:style></office:automatic-styles>""" +
            """<office:body><office:presentation><draw:page draw:name="p1" draw:style-name="dp1">""" +
            """<draw:frame svg:x="2cm" svg:y="2cm" svg:width="10cm" svg:height="6cm"><draw:object xlink:href="./Object 1"/><draw:image xlink:href="./ObjectReplacements/Object 1"/></draw:frame>""" +
            """<anim:par presentation:node-type="timing-root"><anim:seq presentation:node-type="main-sequence"/><anim:transitionFilter smil:dur="1s" smil:type="fade" smil:subtype="crossfade"/></anim:par>""" +
            """</draw:page></office:presentation></office:body></office:document-content>"""
        val s = OdpReader.read(zipOf("content.xml" to content, "Object 1/content.xml" to chart, "ObjectReplacements/Object 1" to "not a picture")).slides[0]
        assertEquals(SlideTransition.FADE, s.transition)
        assertEquals(TransitionSpeed.SLOW, s.transitionSpeed)
        assertEquals("the chart, not its picture", 1, s.charts.size)
        assertEquals(0, s.images.size)
    }

    @Test
    fun cellsSitWhereExcelPutsThem() {
        // Excel's default is the bottom of the cell; the app's middle (a new cell's) is written out.
        val wb = Workbook.blank()
        wb.sheets[0].ensure(0, 0).text = "middle"
        wb.sheets[0].ensure(1, 0).apply { text = "bottom"; vAlign = VAlign.BOTTOM }
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        assertTrue(entries(bos.toByteArray())["xl/styles.xml"]!!.contains("""<alignment vertical="center"/>"""))
        val back = XlsxReader.read(bos.toByteArray()).sheets[0]
        assertEquals(VAlign.MIDDLE, back.cell(0, 0)!!.vAlign)
        assertEquals(VAlign.BOTTOM, back.cell(1, 0)!!.vAlign)
        // A workbook from Excel names no alignment: its cells sit at the bottom.
        val ns = """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
        val excel = zipOf(
            "xl/workbook.xml" to """<workbook $ns><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/styles.xml" to """<styleSheet $ns><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs></styleSheet>""",
            "xl/worksheets/sheet1.xml" to """<worksheet $ns><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>x</t></is></c></row></sheetData></worksheet>""",
        )
        assertEquals(VAlign.BOTTOM, XlsxReader.read(excel).sheets[0].cell(0, 0)!!.vAlign)
    }
}
