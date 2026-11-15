package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.MergeRegion
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/** The OpenDocument writers: package shape, the content they emit, and a round trip through our readers. */
class OdfWriteTest {

    // --- .ods ------------------------------------------------------------------------------

    @Test
    fun odsCarriesValuesFormulasFormatsAndMerges() {
        val wb = Workbook.blank()
        val s = wb.sheets[0]
        s.name = "Data"
        listOf("City", "Sales", "Share").forEachIndexed { c, t -> s.ensure(0, c).text = t }
        s.ensure(0, 0).format.bold = true
        s.ensure(0, 0).format.highlight = 0xFFFFF2CC
        s.ensure(0, 0).bBottom = true
        listOf("Beijing" to 120, "Shanghai" to 95, "Shenzhen" to 60).forEachIndexed { i, (city, n) ->
            s.ensure(i + 1, 0).text = city
            s.ensure(i + 1, 1).text = n.toString()
            s.ensure(i + 1, 2).apply { text = "=B${i + 2}/SUM(B2:B4)"; numberFormat = NumberFormat.PERCENT }
        }
        s.ensure(4, 0).text = "Total"
        s.ensure(4, 1).text = "=SUM(B2:B4)"
        s.ensure(5, 0).apply { text = "199.5"; numberFormat = NumberFormat.CURRENCY_USD }
        s.ensure(5, 1).apply { text = "note"; comment = "checked" }
        s.ensure(5, 2).apply { text = "PICO"; link = "https://picoxr.com" }
        s.merges.add(MergeRegion(6, 0, 6, 1))
        s.ensure(6, 0).text = "Merged title"
        s.hiddenRows.add(3)
        s.colWidths[0] = 1.5f

        val bytes = ByteArrayOutputStream().also {
            OdsWriter.write(wb, it) { _, r, c ->
                when {
                    c == 2 && r in 1..3 -> com.therealityteam.aeropages.model.CachedValue.Number(listOf(0.436, 0.345, 0.218)[r - 1])
                    c == 1 && r == 4 -> com.therealityteam.aeropages.model.CachedValue.Number(275.0)
                    else -> null
                }
            }
        }.toByteArray()

        assertMimetypeFirst(bytes, "application/vnd.oasis.opendocument.spreadsheet")
        val xml = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertTrue("sheet name", xml.contains("""<table:table table:name="Data""""))
        assertTrue("formula", xml.contains("""table:formula="of:=SUM([.B2:.B4])""""))
        assertTrue("cached value", xml.contains("""office:value="275""""))
        assertTrue("percent cached as a fraction", xml.contains("""office:value="0.436""""))
        assertTrue("percentage", xml.contains("""office:value-type="percentage""""))
        assertTrue("currency", xml.contains("""office:value-type="currency" office:currency="USD""""))
        assertTrue("percent data style", xml.contains("<number:percentage-style"))
        assertTrue("merge", xml.contains("""table:number-columns-spanned="2""""))
        assertTrue("covered cell", xml.contains("<table:covered-table-cell/>"))
        // A filtered row says so, as LibreOffice writes it (rows hidden by hand collapse instead).
        assertTrue("filtered row", xml.contains("""table:visibility="filter""""))
        assertTrue("comment", xml.contains("<office:annotation><text:p>checked</text:p></office:annotation>"))
        assertTrue("link", xml.contains("""xlink:href="https://picoxr.com""""))
        assertTrue("bold header", xml.contains("""fo:font-weight="bold""""))
        assertTrue("fill", xml.contains("""fo:background-color="#FFF2CC""""))

        val back = OdsReader.read(bytes).sheets[0]
        assertEquals("Data", back.name)
        assertEquals("City", back.cell(0, 0)?.text)
        assertEquals("120", back.cell(1, 1)?.text)
        assertEquals("Total", back.cell(4, 0)?.text)
    }

    @Test
    fun formulasBecomeOdfSyntax() {
        assertEquals("of:=SUM([.B2:.B4])", OdsWriter.toOdfFormula("=SUM(B2:B4)"))
        assertEquals("of:=[.A1]+[Sheet2.B2]", OdsWriter.toOdfFormula("=A1+Sheet2!B2"))
        assertEquals("""of:=IF([.A1]>1;"yes, ok";"no")""", OdsWriter.toOdfFormula("""=IF(A1>1,"yes, ok","no")"""))
        assertEquals("of:=LOG10(100)", OdsWriter.toOdfFormula("=LOG10(100)"))
        assertEquals("of:=SUM([.\$B\$2:.\$B\$4])", OdsWriter.toOdfFormula("=SUM(\$B\$2:\$B\$4)"))
    }

    // --- .odt ------------------------------------------------------------------------------

    @Test
    fun odtCarriesHeadingsRunsListsTablesAndPictures() {
        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Report", CharacterFormat(bold = true))), ParagraphFormat(style = ParagraphStyle.HEADING1))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(
            TextRun("Bold ", CharacterFormat(bold = true)),
            TextRun("red", CharacterFormat(color = 0xFFCC0000)),
            TextRun(" and a link", CharacterFormat(link = "https://picoxr.com")),
        ))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("first")), ParagraphFormat(listKind = ListKind.BULLET))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("second")), ParagraphFormat(listKind = ListKind.BULLET))))
        val table = Table.of(2, 2)
        table.rows[0].cells[0].content[0] = Paragraph.of("A1")
        table.rows[1].cells[1].content[0] = Paragraph.of("B2")
        doc.blocks.add(TableBlock(table))
        doc.blocks.add(ImageBlock(byteArrayOf(1, 2, 3, 4), "image/png", 120f, 90f))
        doc.header = Paragraph.of("Header line")
        doc.footer = Paragraph.of("Footer line")

        val bytes = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        assertMimetypeFirst(bytes, "application/vnd.oasis.opendocument.text")
        val parts = OfficeXml.unzip(bytes)
        val xml = String(parts["content.xml"]!!, Charsets.UTF_8)
        assertTrue("heading", xml.contains("""text:outline-level="1""""))
        assertTrue("bold run", xml.contains("""fo:font-weight="bold""""))
        assertTrue("colour", xml.contains("""fo:color="#CC0000""""))
        assertTrue("link", xml.contains("""<text:a xlink:type="simple" xlink:href="https://picoxr.com""""))
        assertTrue("list", xml.contains("<text:list text:style-name=") && xml.contains("<text:list-item>"))
        assertTrue("table", xml.contains("<table:table table:name=\"Table1\""))
        assertTrue("image ref", xml.contains("""xlink:href="Pictures/image1.png""""))
        assertTrue("picture packaged", parts.containsKey("Pictures/image1.png"))
        val manifest = String(parts["META-INF/manifest.xml"]!!, Charsets.UTF_8)
        assertTrue("manifest lists picture", manifest.contains("""manifest:full-path="Pictures/image1.png" manifest:media-type="image/png""""))
        val styles = String(parts["styles.xml"]!!, Charsets.UTF_8)
        assertTrue("header", styles.contains("""<style:header><text:p text:style-name="MP1">Header line</text:p></style:header>"""))
        assertTrue("page size", styles.contains("""fo:page-width="612.00pt""""))

        val back = OdtReader.read(bytes)
        val texts = back.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.plainText() }
        assertTrue("round trip text: $texts", texts.any { it.contains("Report") } && texts.any { it.contains("Bold red") })
    }

    // --- .odp ------------------------------------------------------------------------------

    @Test
    fun odpCarriesSlidesShapesNotesAndPictures() {
        val deck = Presentation(mutableListOf(
            Slide(title = SlideBox("Deck title", CharacterFormat(fontSizePt = 40f, bold = true)), body = SlideBox("Line one\nLine two")).apply {
                notes = "Say hello"
                images.add(SlideImage(byteArrayOf(9, 9, 9), "image/png", 200f, 100f))
                shapes.add(SlideShape(ShapeType.ELLIPSE).apply { box.paragraph.runs[0].text = "Shape text" })
            },
            Slide(title = SlideBox("Second"), body = SlideBox("More")),
        ))

        val bytes = ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray()
        assertMimetypeFirst(bytes, "application/vnd.oasis.opendocument.presentation")
        val parts = OfficeXml.unzip(bytes)
        val xml = String(parts["content.xml"]!!, Charsets.UTF_8)
        assertEquals(2, Regex("<draw:page ").findAll(xml).count())
        assertTrue("title frame", xml.contains("""presentation:class="title""""))
        assertTrue("outline frame", xml.contains("""presentation:class="outline""""))
        assertTrue("notes", xml.contains("<presentation:notes>") && xml.contains("Say hello"))
        assertTrue("shape", xml.contains("""<draw:enhanced-geometry draw:type="ellipse"/>"""))
        assertTrue("shape text", xml.contains("Shape text"))
        assertTrue("picture", parts.containsKey("Pictures/image1.png") && xml.contains("""xlink:href="Pictures/image1.png""""))
        val styles = String(parts["styles.xml"]!!, Charsets.UTF_8)
        assertTrue("the page is the widescreen slide, point for point", styles.contains("""fo:page-width="960.00pt"""") && styles.contains("""fo:page-height="540.00pt""""))

        val back = OdpReader.read(bytes)
        assertEquals(2, back.slides.size)
        assertEquals("Deck title", back.slides[0].title.text)
        assertEquals("Second", back.slides[1].title.text)
    }

    // --- helpers ---------------------------------------------------------------------------

    /** ODF readers identify a package by its first entry: an uncompressed `mimetype`. */
    private fun assertMimetypeFirst(bytes: ByteArray, mimetype: String) {
        ZipInputStream(bytes.inputStream()).use { zip ->
            val first = zip.nextEntry!!
            assertEquals("mimetype", first.name)
            assertEquals(ZipEntry.STORED.toLong(), first.method.toLong())
            assertEquals(mimetype, zip.readBytes().toString(Charsets.US_ASCII))
        }
    }
}
