package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
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
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.VAlign
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Full OpenDocument round trips: what our writers emit, our readers read back. This is what makes
 * "save as .odt / .ods / .odp" safe — reopening the file has to give the same document, not just
 * the same words.
 */
class OdfRoundTripTest {

    @Test
    fun odtKeepsStructureFormattingAndPage() {
        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Quarterly report")), ParagraphFormat(style = ParagraphStyle.TITLE))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Overview")), ParagraphFormat(style = ParagraphStyle.HEADING1))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Detail")), ParagraphFormat(style = ParagraphStyle.HEADING2))))
        doc.blocks.add(ParagraphBlock(Paragraph(
            mutableListOf(
                TextRun("Bold", CharacterFormat(bold = true)),
                TextRun(" then "),
                TextRun("red italic", CharacterFormat(italic = true, color = 0xFFCC0000, fontSizePt = 14f)),
                TextRun(" and a link", CharacterFormat(link = "https://picoxr.com")),
            ),
            ParagraphFormat(align = TextAlign.CENTER),
        )))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("bullet one")), ParagraphFormat(listKind = ListKind.BULLET))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("bullet two")), ParagraphFormat(listKind = ListKind.BULLET))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("step one")), ParagraphFormat(listKind = ListKind.NUMBER))))
        val table = Table.of(2, 3)
        table.rows[0].cells[0].content[0] = Paragraph.of("Region")
        table.rows[0].cells[0].shade = 0xFFFFF2CC
        table.rows[0].cells[1].content[0] = Paragraph.of("Q1")
        table.rows[1].cells[0].content[0] = Paragraph.of("North")
        table.rows[1].cells[1].content[0] = Paragraph.of("wide")
        table.rows[1].cells[1].gridSpan = 2
        doc.blocks.add(TableBlock(table))
        doc.blocks.add(ImageBlock(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), "image/png", 200f, 150f))
        doc.header = Paragraph.of("Aero Desk")
        doc.footer = Paragraph.of("Page 1")
        doc.page.widthPt = 595f
        doc.page.heightPt = 842f
        doc.page.marginLeftPt = 56f

        val back = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray())
        val paragraphs = back.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }

        assertEquals(ParagraphStyle.TITLE, paragraphs[0].format.style)
        assertEquals("Quarterly report", paragraphs[0].plainText())
        assertEquals(ParagraphStyle.HEADING1, paragraphs[1].format.style)
        assertEquals(ParagraphStyle.HEADING2, paragraphs[2].format.style)

        val rich = paragraphs[3]
        assertEquals("Bold then red italic and a link", rich.plainText())
        assertEquals(TextAlign.CENTER, rich.format.align)
        assertTrue("bold run", rich.runs.first { it.text.contains("Bold") }.format.bold)
        val red = rich.runs.first { it.text.contains("red italic") }
        assertTrue(red.format.italic)
        assertEquals(0xFFCC0000, red.format.color)
        assertEquals(14f, red.format.fontSizePt)
        assertEquals("https://picoxr.com", rich.runs.first { it.text.contains("link") }.format.link)

        assertEquals(ListKind.BULLET, paragraphs.first { it.plainText() == "bullet one" }.format.listKind)
        assertEquals(ListKind.BULLET, paragraphs.first { it.plainText() == "bullet two" }.format.listKind)
        assertEquals(ListKind.NUMBER, paragraphs.first { it.plainText() == "step one" }.format.listKind)

        val backTable = back.blocks.filterIsInstance<TableBlock>().single().table
        assertEquals(2, backTable.rows.size)
        assertEquals("Region", backTable.rows[0].cells[0].content[0].plainText())
        assertEquals(0xFFFFF2CC, backTable.rows[0].cells[0].shade)
        assertEquals(2, backTable.rows[1].cells[1].gridSpan)

        val backImage = back.blocks.filterIsInstance<ImageBlock>().single()
        assertEquals(4, backImage.data.size)
        assertEquals(200f, backImage.widthPt, 0.5f)

        assertEquals("Aero Desk", back.header?.plainText())
        assertEquals("Page 1", back.footer?.plainText())
        assertEquals(595f, back.page.widthPt, 0.5f)
        assertEquals(56f, back.page.marginLeftPt, 0.5f)
    }

    @Test
    fun odsKeepsValuesFormulasStylesAndLayout() {
        val wb = Workbook(mutableListOf(Sheet(name = "Sales"), Sheet(name = "Notes")))
        val s = wb.sheets[0]
        s.ensure(0, 0).apply { text = "City"; format.bold = true; format.highlight = 0xFFFFF2CC; bBottom = true; align = TextAlign.CENTER }
        s.ensure(0, 1).text = "Amount"
        s.ensure(1, 0).apply { text = "Beijing"; wrap = true; vAlign = VAlign.TOP }
        s.ensure(1, 1).apply { text = "199.5"; numberFormat = NumberFormat.CURRENCY_USD }
        s.ensure(2, 0).text = "Shanghai"
        s.ensure(2, 1).apply { text = "0.436"; numberFormat = NumberFormat.PERCENT; decimals = 2 }
        s.ensure(3, 0).text = "Total"
        s.ensure(3, 1).apply { text = "=SUM(B2:B3)"; format.italic = true }
        s.ensure(4, 0).apply { text = "Linked"; link = "https://picoxr.com"; comment = "double checked" }
        s.merges.add(MergeRegion(5, 0, 5, 2))
        s.ensure(5, 0).text = "Merged banner"
        s.hiddenRows.add(2)
        s.colWidths[0] = 1.5f
        s.rowHeights[1] = 2f
        wb.sheets[1].ensure(0, 0).text = "second sheet"

        val bytes = ByteArrayOutputStream().also { OdsWriter.write(wb, it) { _, _, _ -> null } }.toByteArray()
        val back = OdsReader.read(bytes)

        assertEquals(listOf("Sales", "Notes"), back.sheets.map { it.name })
        val b = back.sheets[0]
        assertEquals("City", b.cell(0, 0)?.text)
        assertTrue("bold header", b.cell(0, 0)!!.format.bold)
        assertEquals(0xFFFFF2CC, b.cell(0, 0)!!.format.highlight)
        assertTrue("bottom border", b.cell(0, 0)!!.bBottom)
        assertEquals(TextAlign.CENTER, b.cell(0, 0)!!.align)
        assertTrue("wrap", b.cell(1, 0)!!.wrap)
        assertEquals(VAlign.TOP, b.cell(1, 0)!!.vAlign)
        assertEquals("199.5", b.cell(1, 1)?.text)
        assertEquals(NumberFormat.CURRENCY_USD, b.cell(1, 1)!!.numberFormat)
        assertEquals("0.436", b.cell(2, 1)?.text)
        assertEquals(NumberFormat.PERCENT, b.cell(2, 1)!!.numberFormat)
        assertEquals(2, b.cell(2, 1)!!.decimals)
        assertEquals("=SUM(B2:B3)", b.cell(3, 1)?.text)
        assertTrue("italic formula cell", b.cell(3, 1)!!.format.italic)
        assertEquals("https://picoxr.com", b.cell(4, 0)?.link)
        assertEquals("double checked", b.cell(4, 0)?.comment)
        assertEquals(1, b.merges.size)
        assertEquals(listOf(5, 0, 5, 2), b.merges[0].let { listOf(it.r1, it.c1, it.r2, it.c2) })
        assertTrue("hidden row", 2 in b.hiddenRows)
        assertEquals(1.5f, b.colWidths[0]!!, 0.05f)
        assertEquals(2f, b.rowHeights[1]!!, 0.05f)
        assertEquals("second sheet", back.sheets[1].cell(0, 0)?.text)
    }

    @Test
    fun odsFormulasSurviveBothDirections() {
        listOf("=SUM(B2:B4)", "=A1+Sheet2!B2", """=IF(A1>1,"yes","no")""", "=LOG10(100)", "=SUM(\$B\$2:\$B\$4)")
            .forEach { assertEquals(it, OdsReader.fromOdfFormula(OdsWriter.toOdfFormula(it))) }
    }

    @Test
    fun odpKeepsSlidesTextShapesAndNotes() {
        val deck = Presentation(mutableListOf(
            Slide(
                title = SlideBox("Roadmap", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER),
                body = SlideBox("First point\nSecond point", CharacterFormat(fontSizePt = 22f)),
            ).apply {
                notes = "Mention the timeline"
                background = 0xFFEAF3FF
                images.add(SlideImage(byteArrayOf(1, 2, 3), "image/png", 160f, 120f, xPct = 0.6f, yPct = 0.55f, wPct = 0.3f, hPct = 0.3f))
                shapes.add(SlideShape(ShapeType.ELLIPSE).apply {
                    xPct = 0.1f; yPct = 0.7f; wPct = 0.2f; hPct = 0.15f
                    box.paragraph.runs[0].text = "Note"
                    fill = 0xFFFFC000; border = 0xFF1F3864; borderWidthPt = 2.5f
                    anchor = com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM
                })
                shapes.add(SlideShape(ShapeType.TEXTBOX).apply { box.paragraph.runs[0].text = "Plain"; border = null; anchor = com.therealityteam.aeropages.model.ShapeAnchor.TOP })
            },
            Slide(title = SlideBox("Thanks"), body = SlideBox("")),
        ), slideSize = com.therealityteam.aeropages.model.SlideSize.WIDESCREEN)

        val back = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray())
        assertEquals(2, back.slides.size)
        assertTrue("widescreen", back.widescreen)
        val first = back.slides[0]
        assertEquals("Roadmap", first.title.text)
        assertTrue("title bold", first.title.paragraph.runs[0].format.bold)
        assertEquals(40f, first.title.paragraph.runs[0].format.fontSizePt)
        assertEquals("First point\nSecond point", first.body.text)
        assertEquals("Mention the timeline", first.notes)
        assertEquals(0xFFEAF3FF, first.background)
        assertEquals(1, first.images.size)
        assertEquals(3, first.images[0].data.size)
        assertEquals(0.6f, first.images[0].xPct, 0.02f)
        val shape = first.shapes.first { it.box.text == "Note" }
        assertEquals(ShapeType.ELLIPSE, shape.type)
        assertEquals(0.1f, shape.xPct, 0.02f)
        assertEquals(0.15f, shape.hPct, 0.02f)
        assertEquals("fill from the graphic style", 0xFFFFC000, shape.fill)
        assertEquals("outline colour", 0xFF1F3864, shape.border)
        assertEquals("outline width", 2.5f, shape.borderWidthPt, 0.05f)
        assertEquals("text anchor", com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM, shape.anchor)
        val plain = first.shapes.first { it.box.text == "Plain" }
        assertNull("text box: no fill", plain.fill)
        assertNull("text box: no outline", plain.border)
        assertEquals(com.therealityteam.aeropages.model.ShapeAnchor.TOP, plain.anchor)
        assertEquals("Thanks", back.slides[1].title.text)
        assertNull(back.slides[1].background)
    }
}
