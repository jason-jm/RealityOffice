package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimStart
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Freeform
import com.therealityteam.aeropages.model.MathNode
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/**
 * Writes the three sample documents used to verify a build on a device
 * (`-Daero.sampleDir=<dir>`); a normal test run skips it.
 */
class SampleFileMakerTest {

    @Test
    fun writeSamples() {
        val dir = System.getProperty("aero.sampleDir")
        assumeNotNull(dir)
        writeTo(File(dir!!))
    }

    internal fun writeTo(out: File) {
        out.mkdirs()

        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Release smoke test", CharacterFormat(bold = true))), ParagraphFormat(style = ParagraphStyle.TITLE))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Bold ", CharacterFormat(bold = true)), TextRun("italic ", CharacterFormat(italic = true)), TextRun("plain.")))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Second paragraph with a longer line so wrapping is visible in the page canvas.")))))
        File(out, "smoke.docx").outputStream().use { DocxWriter.write(doc, it) }

        // A two-section document: portrait intro, then a landscape two-column section.
        val sectioned = Document()
        sectioned.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Portrait section", CharacterFormat(bold = true))), ParagraphFormat(style = ParagraphStyle.HEADING1))))
        sectioned.blocks.add(ParagraphBlock(Paragraph.of("This first section is portrait with one column, the usual page setup.").also {
            it.format.sectionBreak = com.therealityteam.aeropages.model.PageSetup(widthPt = 595f, heightPt = 842f)
        }))
        sectioned.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Landscape section", CharacterFormat(bold = true))), ParagraphFormat(style = ParagraphStyle.HEADING1))))
        repeat(3) { i ->
            sectioned.blocks.add(ParagraphBlock(Paragraph.of("Paragraph ${i + 1} of the landscape, two-column section — wide pages flow text across two columns.")))
        }
        sectioned.page.widthPt = 842f
        sectioned.page.heightPt = 595f
        sectioned.page.columns = 2
        File(out, "sections.docx").outputStream().use { DocxWriter.write(sectioned, it) }
        File(out, "sections.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(sectioned, it) }

        // Footnote + endnotes: "[1]" at the page bottom, "[i]"/"[ii]" collected after the body.
        val noted = Document()
        noted.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Notes sample", CharacterFormat(bold = true))), ParagraphFormat(style = ParagraphStyle.TITLE))))
        noted.blocks.add(ParagraphBlock(Paragraph(mutableListOf(
            TextRun("A claim with a footnote"), TextRun("[1]", CharacterFormat(superscript = true)),
            TextRun(" and an endnote"), TextRun("[i]", CharacterFormat(superscript = true)),
            TextRun(", then a second endnote"), TextRun("[ii]", CharacterFormat(superscript = true)), TextRun("."),
        ))))
        noted.footnotes.add(com.therealityteam.aeropages.model.Footnote("The footnote sits at the bottom of this page."))
        noted.endnotes.add(com.therealityteam.aeropages.model.Endnote("First endnote, printed after the body."))
        noted.endnotes.add(com.therealityteam.aeropages.model.Endnote("Second endnote with its roman marker."))
        File(out, "endnotes.docx").outputStream().use { DocxWriter.write(noted, it) }
        File(out, "endnotes.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(noted, it) }

        // Pictures inside table cells (the table-images ANR watch item): a 3×2 table, a picture per cell, one shaded cell.
        // A real PNG without AWT (not on the unit-test classpath): solid colour with a white centre block, zlib + CRC by hand.
        fun pngOf(w: Int, h: Int, rgb: Int): ByteArray {
            val raw = java.io.ByteArrayOutputStream()
            for (y in 0 until h) {
                raw.write(0)   // filter: none
                for (x in 0 until w) {
                    val inner = x in (w / 4) until (w * 3 / 4) && y in (h / 4) until (h * 3 / 4)
                    val c = if (inner) 0xFFFFFF else rgb
                    raw.write((c shr 16) and 0xFF); raw.write((c shr 8) and 0xFF); raw.write(c and 0xFF)
                }
            }
            val deflater = java.util.zip.Deflater()
            deflater.setInput(raw.toByteArray()); deflater.finish()
            val zipped = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!deflater.finished()) zipped.write(buf, 0, deflater.deflate(buf))
            fun chunk(type: String, data: ByteArray): ByteArray {
                val out = java.io.ByteArrayOutputStream()
                val dos = java.io.DataOutputStream(out)
                dos.writeInt(data.size); dos.write(type.toByteArray(Charsets.US_ASCII)); dos.write(data)
                val crc = java.util.zip.CRC32().also { it.update(type.toByteArray(Charsets.US_ASCII)); it.update(data) }
                dos.writeInt(crc.value.toInt())
                return out.toByteArray()
            }
            val ihdr = java.io.ByteArrayOutputStream().also { val d = java.io.DataOutputStream(it); d.writeInt(w); d.writeInt(h); d.write(8); d.write(2); d.write(0); d.write(0); d.write(0) }.toByteArray()
            return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + chunk("IDAT", zipped.toByteArray()) + chunk("IEND", ByteArray(0))
        }
        val pictured = Document()
        pictured.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Pictures in cells", CharacterFormat(bold = true))), ParagraphFormat(style = ParagraphStyle.TITLE))))
        val rows = (0 until 2).map { r ->
            com.therealityteam.aeropages.model.TableRow((0 until 2).map { c ->
                com.therealityteam.aeropages.model.TableCell(
                    mutableListOf(Paragraph.of("Cell ${'A' + c}${r + 1}")),
                    images = mutableListOf(com.therealityteam.aeropages.model.ImageBlock(pngOf(160, 100, listOf(0x2B579A, 0x217346, 0xC43E1C)[r]), "image/png", 160f, 100f)),
                ).also { if (r == 1 && c == 1) it.shade = 0xFFFFF2CC }
            }.toMutableList())
        }.toMutableList()
        pictured.blocks.add(com.therealityteam.aeropages.model.TableBlock(com.therealityteam.aeropages.model.Table(rows)))
        pictured.blocks.add(ParagraphBlock(Paragraph.of("After the table.")))
        File(out, "table-images.docx").outputStream().use { DocxWriter.write(pictured, it) }

        // A long table: a bold header row that repeats on every page, then 45 data rows.
        val longDoc = Document()
        longDoc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Long table", CharacterFormat(bold = true))), ParagraphFormat(style = ParagraphStyle.TITLE))))
        val header = com.therealityteam.aeropages.model.TableRow(mutableListOf(
            com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph(mutableListOf(TextRun("No.", CharacterFormat(bold = true)))))),
            com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph(mutableListOf(TextRun("Item", CharacterFormat(bold = true)))))),
            com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph(mutableListOf(TextRun("Amount", CharacterFormat(bold = true)))))),
        ), header = true).also { it.cells.forEach { c -> c.shade = 0xFFE7E6E6 } }
        val dataRows = (1..45).map { i ->
            com.therealityteam.aeropages.model.TableRow(mutableListOf(
                com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph.of("$i"))),
                com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph.of("Line item number $i"))),
                com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph.of("${i * 12}.00"))),
            ))
        }
        longDoc.blocks.add(com.therealityteam.aeropages.model.TableBlock(com.therealityteam.aeropages.model.Table((listOf(header) + dataRows).toMutableList())))
        longDoc.blocks.add(ParagraphBlock(Paragraph.of("After the long table.")))
        File(out, "long-table.docx").outputStream().use { DocxWriter.write(longDoc, it) }

        // A text box (callout) between two paragraphs.
        val boxed = Document()
        boxed.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Text boxes", CharacterFormat(bold = true))), ParagraphFormat(style = ParagraphStyle.TITLE))))
        boxed.blocks.add(ParagraphBlock(Paragraph.of("The paragraph before the callout box.")))
        boxed.blocks.add(com.therealityteam.aeropages.model.TextBoxBlock(mutableListOf(
            Paragraph(mutableListOf(TextRun("Did you know?", CharacterFormat(bold = true)))),
            Paragraph.of("Text boxes keep their own paragraphs in a frame, and Word round-trips them as wps shapes."),
        ), widthPt = 300f))
        boxed.blocks.add(ParagraphBlock(Paragraph.of("The paragraph after the callout box.")))
        File(out, "textbox.docx").outputStream().use { DocxWriter.write(boxed, it) }

        // Table of contents over several pages: three chapters with sections, enough text to paginate.
        val tocDoc = com.therealityteam.aeropages.model.Document(mutableListOf())
        tocDoc.blocks.add(com.therealityteam.aeropages.model.TocBlock())
        fun heading(text: String, style: com.therealityteam.aeropages.model.ParagraphStyle) =
            tocDoc.addParagraph(com.therealityteam.aeropages.model.Paragraph.of(text, pFormat = com.therealityteam.aeropages.model.ParagraphFormat(style = style)))
        val filler = "The quarterly review covers revenue, costs and the outlook for every region in detail, with notes on each market."
        listOf("Overview", "Regional results", "Outlook").forEachIndexed { ci, chapter ->
            heading("${ci + 1}. $chapter", com.therealityteam.aeropages.model.ParagraphStyle.HEADING1)
            listOf("Summary", "Figures").forEachIndexed { si, section ->
                heading("${ci + 1}.${si + 1} $section", com.therealityteam.aeropages.model.ParagraphStyle.HEADING2)
                repeat(14) { tocDoc.addParagraph(com.therealityteam.aeropages.model.Paragraph.of(filler)) }
            }
        }
        File(out, "toc.docx").outputStream().use { DocxWriter.write(tocDoc, it) }

        // Long Chinese paragraphs: full-width characters wrap twice as often as Latin text, so the
        // page breaks here show whether the canvas measures text or only guesses.
        val zhDoc = com.therealityteam.aeropages.model.Document(mutableListOf())
        val zh = "本季度的经营回顾涵盖了各个区域的收入、成本以及未来展望，并对每一个市场的变化趋势做出了详细的说明。" +
            "团队在产品质量、交付效率与客户满意度方面都取得了明显的进步，同时也发现了一些需要在下个季度重点改进的问题。"
        listOf("概述", "区域业绩", "展望").forEachIndexed { ci, chapter ->
            zhDoc.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("${ci + 1}. $chapter",
                pFormat = com.therealityteam.aeropages.model.ParagraphFormat(style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1)))
            repeat(9) { k -> zhDoc.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（${ci + 1}.${k + 1}）" + zh + zh)) }
        }
        File(out, "long-zh.docx").outputStream().use { DocxWriter.write(zhDoc, it) }

        // Indents: a first line two characters in (首行缩进), a hanging one (悬挂缩进, a reference list),
        // right indents (a quotation), and the first line set at 16 pt — two characters of its own size.
        val indented = com.therealityteam.aeropages.model.Document(mutableListOf())
        fun para(text: String, left: Float = 0f, first: Float = 0f, right: Float = 0f, size: Float? = null) = indented.addParagraph(
            com.therealityteam.aeropages.model.Paragraph(mutableListOf(com.therealityteam.aeropages.model.TextRun(text, CharacterFormat(fontSizePt = size))),
                com.therealityteam.aeropages.model.ParagraphFormat(indentLeftPt = left, firstLineIndentPt = first, indentRightPt = right)))
        indented.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("段落缩进",
            pFormat = com.therealityteam.aeropages.model.ParagraphFormat(style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1)))
        para("首行缩进两个字符：$zh", first = 22f)
        para("三号字的首行缩进两个字符：$zh", first = 32f, size = 16f)
        para("悬挂缩进：[1] 张三，李四. 空间计算中的文档排版[J]. 计算机学报, 2026, 49(3): 1-12. 第二行起与编号后的文字对齐。", left = 22f, first = -22f)
        para("左右各缩进四个字符的引文：$zh", left = 44f, right = 44f)
        para("Hanging indent in English: Smith, J. (2026). Typesetting documents in spatial computing. Journal of Examples, 12(3), 45-67.", left = 36f, first = -36f)
        File(out, "indents.docx").outputStream().use { DocxWriter.write(indented, it) }
        File(out, "indents.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(indented, it) }

        // Headers and footers: the header's three slots (left title, centre mark, the page number and count
        // at the right margin), a two-line centred footer, over three pages.
        val headed = com.therealityteam.aeropages.model.Document(mutableListOf())
        headed.header = com.therealityteam.aeropages.model.Paragraph(mutableListOf(
            com.therealityteam.aeropages.model.TextRun("季度报告", CharacterFormat(bold = true, fontSizePt = 10f)),
            com.therealityteam.aeropages.model.TextRun("\t内部资料\t第 ${com.therealityteam.aeropages.model.HeaderFooters.PAGE} 页 / 共 ${com.therealityteam.aeropages.model.HeaderFooters.PAGES} 页", CharacterFormat(fontSizePt = 10f)),
        ), com.therealityteam.aeropages.model.ParagraphFormat(spaceAfterPt = 0f))
        headed.footer = com.therealityteam.aeropages.model.Paragraph(mutableListOf(
            com.therealityteam.aeropages.model.TextRun("AeroDesk · 页脚第一行\n第二行：Page ${com.therealityteam.aeropages.model.HeaderFooters.PAGE}", CharacterFormat(fontSizePt = 9f, color = 0xFF555555)),
        ), com.therealityteam.aeropages.model.ParagraphFormat(align = TextAlign.CENTER, spaceAfterPt = 0f))
        repeat(30) { headed.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（${it + 1}）" + zh)) }
        File(out, "headers.docx").outputStream().use { DocxWriter.write(headed, it) }
        File(out, "headers.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(headed, it) }

        // Tab stops: a form's labels and values, a contents list with dot leaders to the right margin, a
        // column of amounts on their decimal points, a centred title, default stops, a hanging list item.
        val tabbed = com.therealityteam.aeropages.model.Document(mutableListOf())
        fun tp(text: String, vararg stops: com.therealityteam.aeropages.model.TabStop, left: Float = 0f, first: Float = 0f) = tabbed.addParagraph(
            com.therealityteam.aeropages.model.Paragraph(mutableListOf(com.therealityteam.aeropages.model.TextRun(text)),
                com.therealityteam.aeropages.model.ParagraphFormat(tabStops = stops.toList(), indentLeftPt = left, firstLineIndentPt = first, spaceAfterPt = 4f)))
        val R = com.therealityteam.aeropages.model.TabAlign.RIGHT
        val dots = com.therealityteam.aeropages.model.TabLeader.DOT
        tp("\t制表位", com.therealityteam.aeropages.model.TabStop(234f, com.therealityteam.aeropages.model.TabAlign.CENTER))
        tp("姓名：\t张三", com.therealityteam.aeropages.model.TabStop(90f))
        tp("Fax Number:\t+86 10 1234 5678", com.therealityteam.aeropages.model.TabStop(90f))
        tp("第一章 概述\t1", com.therealityteam.aeropages.model.TabStop(468f, R, dots))
        tp("第二章 区域业绩\t12", com.therealityteam.aeropages.model.TabStop(468f, R, dots))
        tp("Appendix A — Data\t127", com.therealityteam.aeropages.model.TabStop(468f, R, dots))
        tp("收入\t1,234.56\t签字：\t", com.therealityteam.aeropages.model.TabStop(200f, com.therealityteam.aeropages.model.TabAlign.DECIMAL),
            com.therealityteam.aeropages.model.TabStop(300f), com.therealityteam.aeropages.model.TabStop(468f, R, com.therealityteam.aeropages.model.TabLeader.UNDERSCORE))
        tp("成本\t98.7", com.therealityteam.aeropages.model.TabStop(200f, com.therealityteam.aeropages.model.TabAlign.DECIMAL))
        tp("甲方：\t\t\t\t乙方：")
        tp("1.\t悬挂缩进的列表项：编号之后的制表符跳到左缩进处，换行后的文字也从那里开始，" + zh, left = 36f, first = -36f)
        File(out, "tabs.docx").outputStream().use { DocxWriter.write(tabbed, it) }
        File(out, "tabs.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(tabbed, it) }

        // Floating pictures and text boxes: a banner behind the title from the page's top-left corner, a logo in front
        // at the margins' right edge, a picture the text goes around 300 pt across, a mirrored one in the left margin's
        // strip, a note box in front centred at the page's foot.
        val floating = com.therealityteam.aeropages.model.Document(mutableListOf())
        floating.addParagraph(com.therealityteam.aeropages.model.Paragraph(mutableListOf(com.therealityteam.aeropages.model.TextRun("Floating pictures and text boxes",
            com.therealityteam.aeropages.model.CharacterFormat(fontSizePt = 28f, bold = true, color = 0xFFFFFFFF))), com.therealityteam.aeropages.model.ParagraphFormat(spaceAfterPt = 60f)))
        floating.blocks.add(com.therealityteam.aeropages.model.ImageBlock(pngOf(306, 80, 0x2B579A), "image/png", 612f, 160f,
            floating = com.therealityteam.aeropages.model.FloatPosition(0f, com.therealityteam.aeropages.model.FloatFrame.PAGE, null, 0f, com.therealityteam.aeropages.model.FloatFrame.PAGE, null, com.therealityteam.aeropages.model.FloatWrap.BEHIND)))
        floating.blocks.add(com.therealityteam.aeropages.model.ImageBlock(pngOf(72, 72, 0xC43E1C), "image/png", 54f, 54f,
            floating = com.therealityteam.aeropages.model.FloatPosition(0f, com.therealityteam.aeropages.model.FloatFrame.MARGIN, com.therealityteam.aeropages.model.FloatAlign.END,
                170f, com.therealityteam.aeropages.model.FloatFrame.PAGE, null, com.therealityteam.aeropages.model.FloatWrap.FRONT)))
        floating.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("The picture to the right floats 300 pt across the column, the text going around it; " + zh))
        floating.blocks.add(com.therealityteam.aeropages.model.ImageBlock(pngOf(120, 90, 0x217346), "image/png", 120f, 90f,
            floating = com.therealityteam.aeropages.model.FloatPosition(300f, com.therealityteam.aeropages.model.FloatFrame.COLUMN, null, 0f, com.therealityteam.aeropages.model.FloatFrame.PARAGRAPH, null, com.therealityteam.aeropages.model.FloatWrap.SQUARE)))
        floating.blocks.add(com.therealityteam.aeropages.model.ImageBlock(pngOf(60, 120, 0x7030A0), "image/png", 40f, 80f,
            floating = com.therealityteam.aeropages.model.FloatPosition(20f, com.therealityteam.aeropages.model.FloatFrame.LEFT_MARGIN, null, 0f, com.therealityteam.aeropages.model.FloatFrame.PARAGRAPH, null, com.therealityteam.aeropages.model.FloatWrap.FRONT),
            flipH = true))
        repeat(6) { floating.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（${it + 1}）" + zh)) }
        floating.blocks.add(com.therealityteam.aeropages.model.TextBoxBlock(mutableListOf(com.therealityteam.aeropages.model.Paragraph.of("A note in front of the text, centred at the page's foot.")), 300f, 48f,
            com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.RECT, fill = 0xFFFFF2CC, border = 0xFFBF9000),
            com.therealityteam.aeropages.model.FloatPosition(0f, com.therealityteam.aeropages.model.FloatFrame.PAGE, com.therealityteam.aeropages.model.FloatAlign.CENTER,
                0f, com.therealityteam.aeropages.model.FloatFrame.BOTTOM_MARGIN, com.therealityteam.aeropages.model.FloatAlign.START, com.therealityteam.aeropages.model.FloatWrap.FRONT)))
        floating.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("End."))
        // A positioned table, as a cover's title table: low on the page, 300 pt wide, no borders.
        floating.blocks.add(com.therealityteam.aeropages.model.TableBlock(com.therealityteam.aeropages.model.Table(
            mutableListOf(com.therealityteam.aeropages.model.TableRow(mutableListOf(com.therealityteam.aeropages.model.TableCell(mutableListOf(
                com.therealityteam.aeropages.model.Paragraph(mutableListOf(com.therealityteam.aeropages.model.TextRun("A positioned table", com.therealityteam.aeropages.model.CharacterFormat(fontSizePt = 16f, bold = true)))),
                com.therealityteam.aeropages.model.Paragraph.of("Placed 560 pt down the page, 150 pt from its left edge.")))))),
            borders = false, columnWidths = mutableListOf(6000f),
            floating = com.therealityteam.aeropages.model.FloatPosition(150f, com.therealityteam.aeropages.model.FloatFrame.PAGE, null, 560f, com.therealityteam.aeropages.model.FloatFrame.PAGE, null, com.therealityteam.aeropages.model.FloatWrap.SQUARE),
            widthPt = 300f)))
        floating.addParagraph(com.therealityteam.aeropages.model.Paragraph.of(""))
        File(out, "floating.docx").outputStream().use { DocxWriter.write(floating, it) }
        File(out, "floating.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(floating, it) }

        // A letterhead: the header's logo right-aligned in the margins beside the company's line, the first page's
        // header a banner of its own (no text), the footer a small mark at the left of the centred page number.
        val letterhead = com.therealityteam.aeropages.model.Document(mutableListOf())
        letterhead.header = com.therealityteam.aeropages.model.Paragraph.of("ACME Spatial Ltd. · 1 Market Street · 示例公司")
        letterhead.footer = com.therealityteam.aeropages.model.Paragraph(mutableListOf(com.therealityteam.aeropages.model.TextRun(
            "Page ${com.therealityteam.aeropages.model.HeaderFooters.PAGE} of ${com.therealityteam.aeropages.model.HeaderFooters.PAGES}")),
            com.therealityteam.aeropages.model.ParagraphFormat(align = com.therealityteam.aeropages.model.TextAlign.CENTER))
        letterhead.firstFooter = letterhead.footer!!.copyOf()
        letterhead.differentFirstPage = true
        fun hf(png: ByteArray, w: Float, h: Float, pos: com.therealityteam.aeropages.model.FloatPosition) =
            mutableListOf(com.therealityteam.aeropages.model.ImageBlock(png, "image/png", w, h, floating = pos))
        letterhead.hfPictures[com.therealityteam.aeropages.model.HfSlot.HEADER] = hf(pngOf(96, 32, 0x2B579A), 72f, 24f,
            com.therealityteam.aeropages.model.FloatPosition(0f, com.therealityteam.aeropages.model.FloatFrame.MARGIN, com.therealityteam.aeropages.model.FloatAlign.END,
                30f, com.therealityteam.aeropages.model.FloatFrame.PAGE, null, com.therealityteam.aeropages.model.FloatWrap.FRONT))
        letterhead.hfPictures[com.therealityteam.aeropages.model.HfSlot.FIRST_HEADER] = hf(pngOf(432, 40, 0xC43E1C), 468f, 40f,
            com.therealityteam.aeropages.model.FloatPosition(0f, com.therealityteam.aeropages.model.FloatFrame.MARGIN, null,
                20f, com.therealityteam.aeropages.model.FloatFrame.PAGE, null, com.therealityteam.aeropages.model.FloatWrap.TOP_BOTTOM))
        val mark = com.therealityteam.aeropages.model.FloatPosition(0f, com.therealityteam.aeropages.model.FloatFrame.MARGIN, null,
            792f - 36f - 18f, com.therealityteam.aeropages.model.FloatFrame.PAGE, null, com.therealityteam.aeropages.model.FloatWrap.FRONT)
        letterhead.hfPictures[com.therealityteam.aeropages.model.HfSlot.FOOTER] = hf(pngOf(24, 24, 0x217346), 18f, 18f, mark)
        letterhead.hfPictures[com.therealityteam.aeropages.model.HfSlot.FIRST_FOOTER] = hf(pngOf(24, 24, 0x217346), 18f, 18f, mark.copy())
        letterhead.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("Dear reader,"))
        repeat(14) { letterhead.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（${it + 1}）" + zh)) }
        File(out, "letterhead.docx").outputStream().use { DocxWriter.write(letterhead, it) }
        File(out, "letterhead.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(letterhead, it) }

        // A letterhead set in its lines, as Word users insert one: the logo in line, centred, a line of its own above
        // the address's three lines (the header reaches past the top margin: the body starts below it); the footer's
        // two lines of text over a small mark in its own line (it reaches past the bottom margin); a footnote.
        val lined = com.therealityteam.aeropages.model.Document(mutableListOf())
        lined.header = com.therealityteam.aeropages.model.Paragraph(mutableListOf(
            com.therealityteam.aeropages.model.TextRun("\n"),
            com.therealityteam.aeropages.model.TextRun("ACME Spatial Ltd.", CharacterFormat(bold = true, fontSizePt = 14f)),
            com.therealityteam.aeropages.model.TextRun("\n1 Market Street · Springfield\nTel. 555-0100 · 示例公司")),
            com.therealityteam.aeropages.model.ParagraphFormat(align = com.therealityteam.aeropages.model.TextAlign.CENTER))
        lined.hfPictures[com.therealityteam.aeropages.model.HfSlot.HEADER] = mutableListOf(com.therealityteam.aeropages.model.ImageBlock(pngOf(160, 48, 0x2B579A), "image/png", 120f, 36f,
            floating = com.therealityteam.aeropages.model.FloatPlacement.inHeaderLine(0, com.therealityteam.aeropages.model.TextAlign.CENTER)))
        lined.footer = com.therealityteam.aeropages.model.Paragraph(mutableListOf(com.therealityteam.aeropages.model.TextRun(
            "Confidential — for the addressee only\nPage ${com.therealityteam.aeropages.model.HeaderFooters.PAGE} of ${com.therealityteam.aeropages.model.HeaderFooters.PAGES}")),
            com.therealityteam.aeropages.model.ParagraphFormat(align = com.therealityteam.aeropages.model.TextAlign.CENTER))
        lined.hfPictures[com.therealityteam.aeropages.model.HfSlot.FOOTER] = mutableListOf(com.therealityteam.aeropages.model.ImageBlock(pngOf(24, 24, 0x217346), "image/png", 24f, 24f,
            floating = com.therealityteam.aeropages.model.FloatPlacement.inHeaderLine(2, com.therealityteam.aeropages.model.TextAlign.CENTER)))
        lined.addParagraph(com.therealityteam.aeropages.model.Paragraph(mutableListOf(com.therealityteam.aeropages.model.TextRun("Dear reader, this letter's first line starts below its letterhead"),
            com.therealityteam.aeropages.model.TextRun("[1]", CharacterFormat(superscript = true)), com.therealityteam.aeropages.model.TextRun("."))))
        lined.footnotes.add(com.therealityteam.aeropages.model.Footnote("The footnote stands above the footer, which reaches into the bottom margin."))
        repeat(14) { lined.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（${it + 1}）" + zh)) }
        File(out, "letterhead-lines.docx").outputStream().use { DocxWriter.write(lined, it) }
        File(out, "letterhead-lines.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(lined, it) }

        // Table borders, side by side: a plain grid; three rules (booktabs); a double frame with dashed lines
        // inside and one thick green bottom; a form whose fields are lines to write on; merged cells.
        val bd = com.therealityteam.aeropages.model.Document(mutableListOf())
        fun line(style: com.therealityteam.aeropages.model.LineStyle, w: Float, color: Long? = null) = com.therealityteam.aeropages.model.CellBorder(style, w, color)
        val solid = com.therealityteam.aeropages.model.LineStyle.THIN
        fun tableOf(rows: List<List<String>>): com.therealityteam.aeropages.model.Table =
            com.therealityteam.aeropages.model.Table.of(rows.size, rows[0].size).also { t -> rows.forEachIndexed { r, row -> row.forEachIndexed { c, v -> t.rows[r].cells[c].content[0] = com.therealityteam.aeropages.model.Paragraph.of(v) } } }
        val data = listOf(listOf("Region", "Q1", "Q2", "Q3"), listOf("North", "120", "135", "150"), listOf("South", "98", "102", "117"), listOf("East", "143", "151", "166"))
        bd.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("1. A plain grid (half a point, automatic)"))
        bd.blocks.add(com.therealityteam.aeropages.model.TableBlock(tableOf(data)))
        bd.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("2. Three rules: thick above and below, thin under the header"))
        val booktabs = tableOf(data)
        booktabs.adoptBorders { r, _ ->
            val top = if (r == 0) line(solid, 1.5f, 0xFF1F4E79) else if (r == 1) line(solid, 0.75f) else null
            val bottom = if (r == 3) line(solid, 1.5f, 0xFF1F4E79) else if (r == 0) line(solid, 0.75f) else null
            com.therealityteam.aeropages.model.CellBorders(top = top, bottom = bottom)
        }
        bd.blocks.add(com.therealityteam.aeropages.model.TableBlock(booktabs))
        bd.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("3. A double frame, dashed lines inside, a thick green bottom under the last figure"))
        val framed = tableOf(data)
        val frame = line(com.therealityteam.aeropages.model.LineStyle.DOUBLE, 0.75f, 0xFF7030A0)
        val dashed = line(com.therealityteam.aeropages.model.LineStyle.DASHED, 0.5f, 0xFF7F7F7F)
        framed.adoptBorders { r, c ->
            com.therealityteam.aeropages.model.CellBorders(
                top = if (r == 0) frame else dashed, bottom = if (r == 3) (if (c == 3) line(solid, 3f, 0xFF00B050) else frame) else dashed,
                left = if (c == 0) frame else dashed, right = if (c == 3) frame else dashed)
        }
        bd.blocks.add(com.therealityteam.aeropages.model.TableBlock(framed))
        bd.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("4. A form: lines to write on"))
        val form = tableOf(listOf(listOf("Name", "", "Date", ""), listOf("Address", "", "Phone", "")))
        form.adoptBorders { _, c -> com.therealityteam.aeropages.model.CellBorders(bottom = if (c % 2 == 1) line(com.therealityteam.aeropages.model.LineStyle.DOTTED, 1f) else null) }
        bd.blocks.add(com.therealityteam.aeropages.model.TableBlock(form))
        bd.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("5. Merged cells, every side red"))
        val merged = tableOf(listOf(listOf("Team", "Results", ""), listOf("Alpha", "12", "14"), listOf("", "15", "16")))
        merged.rows[0].cells[1].gridSpan = 2; merged.rows[0].cells.removeAt(2)
        merged.rows[1].cells[0].rowSpan = 2; merged.rows[2].cells[0].vMerged = true
        merged.adoptBorders { _, _ -> com.therealityteam.aeropages.model.CellBorders.all(line(solid, 1f, 0xFFC00000)) }
        bd.blocks.add(com.therealityteam.aeropages.model.TableBlock(merged))
        bd.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("End."))
        File(out, "table-borders.docx").outputStream().use { DocxWriter.write(bd, it) }
        File(out, "table-borders.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(bd, it) }

        // Links and bookmarks: contents whose entries go to their headings; a sentence linking to a bookmark (a
        // range in a later section), to a heading (through a hidden bookmark round it) and to the web; a point
        // bookmark; filler so that each section starts on a later page.
        val ln = com.therealityteam.aeropages.model.Document(mutableListOf())
        fun heading(t: String) = com.therealityteam.aeropages.model.Paragraph.of(t).also { it.format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1 }
        fun mark(p: com.therealityteam.aeropages.model.Paragraph, name: String, from: Int, to: Int) {
            p.bookmarks.add(com.therealityteam.aeropages.model.BookmarkMark(name, from, false)); p.bookmarks.add(com.therealityteam.aeropages.model.BookmarkMark(name, to, true))
        }
        fun filler(n: Int) = repeat(n) { ln.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（${it + 1}）" + zh)) }
        ln.blocks.add(com.therealityteam.aeropages.model.TocBlock())
        ln.addParagraph(heading("1. Overview"))
        ln.addParagraph(com.therealityteam.aeropages.model.Paragraph(mutableListOf(
            com.therealityteam.aeropages.model.TextRun("The method is set out in "),
            com.therealityteam.aeropages.model.TextRun("the method section", CharacterFormat(link = "#Method")),
            com.therealityteam.aeropages.model.TextRun("; the figures are under "),
            com.therealityteam.aeropages.model.TextRun("Results", CharacterFormat(link = "#_Results")),
            com.therealityteam.aeropages.model.TextRun(". The project's site: "),
            com.therealityteam.aeropages.model.TextRun("example.com", CharacterFormat(link = "https://example.com/")),
            com.therealityteam.aeropages.model.TextRun("."))))
        filler(12)
        ln.addParagraph(heading("2. Method"))
        ln.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("We measured the light at three heights.").also { mark(it, "Method", 3, 38) })
        filler(12)
        ln.addParagraph(heading("3. Results").also { mark(it, "_Results", 0, 10) })
        ln.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("The light fell by half at each step.").also { mark(it, "Here", 4, 4) })
        File(out, "links.docx").outputStream().use { DocxWriter.write(ln, it) }
        File(out, "links.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(ln, it) }

        // Lists: chapter headings numbered 第一章 / 第一节 (in the contents too), a Chinese document's 一、（一）1. list
        // counting on after a paragraph between, an outline 1 / 1.1 / 1.1.1, circled numbers restarted, bullets.
        val lst = com.therealityteam.aeropages.model.Document(mutableListOf())
        val chapters = com.therealityteam.aeropages.model.ListDef(listOf(
            com.therealityteam.aeropages.model.ListLevel(com.therealityteam.aeropages.model.NumFormat.CHINESE, "第%1章"),
            com.therealityteam.aeropages.model.ListLevel(com.therealityteam.aeropages.model.NumFormat.CHINESE, "第%2节")) + com.therealityteam.aeropages.model.ListDef.CHINESE.levels.drop(2))
        lst.lists[1] = chapters; lst.lists[2] = com.therealityteam.aeropages.model.ListDef.CHINESE; lst.lists[3] = com.therealityteam.aeropages.model.ListDef.OUTLINE
        lst.lists[4] = com.therealityteam.aeropages.model.ListDef(List(9) { com.therealityteam.aeropages.model.ListLevel(com.therealityteam.aeropages.model.NumFormat.CIRCLED, "%${it + 1}") })
        lst.lists[5] = com.therealityteam.aeropages.model.ListDef(List(9) { com.therealityteam.aeropages.model.ListLevel(com.therealityteam.aeropages.model.NumFormat.BULLET, listOf("➢", "✓", "■")[it % 3]) })
        lst.lists[6] = lst.lists[4]!!
        fun li(t: String, id: Int, level: Int = 0, kind: com.therealityteam.aeropages.model.ListKind = com.therealityteam.aeropages.model.ListKind.NUMBER) = lst.addParagraph(
            com.therealityteam.aeropages.model.Paragraph.of(t, pFormat = com.therealityteam.aeropages.model.ParagraphFormat(listKind = kind, listId = id, listLevel = level,
                indentLeftPt = 24f + 21f * level, firstLineIndentPt = -24f, spaceAfterPt = 4f)))
        fun chapter(t: String, level: Int) = lst.addParagraph(com.therealityteam.aeropages.model.Paragraph.of(t, pFormat = com.therealityteam.aeropages.model.ParagraphFormat(
            style = if (level == 0) com.therealityteam.aeropages.model.ParagraphStyle.HEADING1 else com.therealityteam.aeropages.model.ParagraphStyle.HEADING2,
            listKind = com.therealityteam.aeropages.model.ListKind.NUMBER, listId = 1, listLevel = level)))
        lst.blocks.add(com.therealityteam.aeropages.model.TocBlock())
        chapter("总则", 0); chapter("目的与范围", 1)
        li("为规范公司文件管理，制定本办法。", 2); li("适用范围", 2, 1); li("总部各部门", 2, 2); li("各分公司", 2, 2); li("术语", 2, 1)
        lst.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（中间的说明段落不打断编号。）"))
        li("职责分工", 2); li("办公室负责归档。", 2, 1)
        chapter("流程", 0); chapter("步骤", 1)
        li("Plan", 3); li("Collect the data", 3, 1); li("Check each source", 3, 2); li("Write it up", 3, 1); li("Review", 3)
        li("第一步", 4); li("第二步", 4); li("第三步", 4)
        lst.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("重新编号："))
        li("再一次", 6); li("再两次", 6)
        li("要点", 5, kind = com.therealityteam.aeropages.model.ListKind.BULLET); li("已完成", 5, 1, com.therealityteam.aeropages.model.ListKind.BULLET); li("细节", 5, 2, com.therealityteam.aeropages.model.ListKind.BULLET)
        File(out, "lists.docx").outputStream().use { DocxWriter.write(lst, it) }
        File(out, "lists.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(lst, it) }

        // Page numbers: a front section numbered i, ii (its own start), the body from 1, each page's footer "— n —".
        // A table of contents first: its pages as the sections number them (Preface i, Chapter one 1…).
        val pn = com.therealityteam.aeropages.model.Document(mutableListOf())
        pn.blocks.add(com.therealityteam.aeropages.model.TocBlock())
        pn.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("Preface").also { it.format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1 })
        repeat(30) { pn.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（${it + 1}）" + zh)) }
        pn.blocks.add(com.therealityteam.aeropages.model.ParagraphBlock(com.therealityteam.aeropages.model.Paragraph.of("").also {
            it.format.sectionBreak = com.therealityteam.aeropages.model.PageSetup(pageNumberFormat = com.therealityteam.aeropages.model.NumFormat.LOWER_ROMAN, pageNumberStart = 1)
        }))
        pn.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("Chapter one").also { it.format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1 })
        repeat(30) { pn.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（${it + 1}）" + zh)) }
        pn.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("Chapter two").also { it.format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1 })
        repeat(10) { pn.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("（${it + 1}）" + zh)) }
        pn.page.pageNumberStart = 1
        pn.footer = com.therealityteam.aeropages.model.Paragraph.of("\t— ${com.therealityteam.aeropages.model.HeaderFooters.PAGE} —")
        File(out, "page-numbers.docx").outputStream().use { DocxWriter.write(pn, it) }
        File(out, "page-numbers.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(pn, it) }

        // Line and page breaks: a kept-together block that would straddle page 1's foot moves to page 2
        // whole, and "Chapter 2" asks for a page break before it.
        val breaks = com.therealityteam.aeropages.model.Document(mutableListOf())
        fun h1(text: String, pageBreak: Boolean = false) = breaks.addParagraph(com.therealityteam.aeropages.model.Paragraph.of(text,
            pFormat = com.therealityteam.aeropages.model.ParagraphFormat(style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1, pageBreakBefore = pageBreak)))
        h1("Chapter 1")
        repeat(14) { breaks.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("Filler paragraph ${it + 1}: " + filler)) }
        breaks.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("KEPT TOGETHER — " + filler.repeat(4),
            pFormat = com.therealityteam.aeropages.model.ParagraphFormat(keepTogether = true)))
        breaks.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("After the kept block."))
        h1("Chapter 2", pageBreak = true)
        breaks.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("Chapter 2 starts on its own page."))
        File(out, "breaks.docx").outputStream().use { DocxWriter.write(breaks, it) }

        // A deck whose look comes from the slide master: dark background, light serif titles, grey body.
        val mastered = Presentation(mutableListOf(
            Slide(title = SlideBox("Master-driven deck", CharacterFormat(fontSizePt = 44f, bold = true), TextAlign.CENTER), body = SlideBox(Paragraph(mutableListOf(
                TextRun("Slides inherit the master background and text colours.\n", CharacterFormat(fontSizePt = 24f)),
                TextRun("Only this line sets its own colour.", CharacterFormat(fontSizePt = 24f, color = 0xFFFFC000)),
            )))),
            Slide(title = SlideBox("Own background", CharacterFormat(fontSizePt = 44f, bold = true), TextAlign.CENTER), body = SlideBox("This slide overrides the master background.", CharacterFormat(fontSizePt = 24f)), background = 0xFFFFF2CC),
        ))
        mastered.master.background = 0xFF1F3864
        mastered.master.titleColor = 0xFFFFFFFF; mastered.master.titleFont = "Georgia"; mastered.master.titleSizePt = 44f
        mastered.master.bodyColor = 0xFFD9D9D9; mastered.master.bodySizePt = 24f
        File(out, "master.pptx").outputStream().use { PptxWriter.write(mastered, it) }

        // Links and clicks in a show: an agenda whose lines go to their slides, buttons for the next slide and
        // the end, a picture that goes to the last part, a web link; the parts' buttons back, first, last, previous.
        val agenda = Slide(title = SlideBox("Agenda", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER))
        val partA = Slide(title = SlideBox("Part A", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER))
        val partB = Slide(title = SlideBox("Part B", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER))
        agenda.body.paragraph.runs.clear()
        agenda.body.paragraph.runs.addAll(listOf(
            TextRun("Part A — the light\n", CharacterFormat(fontSizePt = 24f, link = com.therealityteam.aeropages.model.SlideLinks.toSlide(partA))),
            TextRun("Part B — the shade\n", CharacterFormat(fontSizePt = 24f, link = com.therealityteam.aeropages.model.SlideLinks.toSlide(partB))),
            TextRun("More at ", CharacterFormat(fontSizePt = 24f)),
            TextRun("example.com", CharacterFormat(fontSizePt = 24f, link = "https://example.com/"))))
        fun button(label: String, x: Float, y: Float, action: String) = com.therealityteam.aeropages.model.SlideShape(
            com.therealityteam.aeropages.model.ShapeType.ROUNDED, SlideBox(label, CharacterFormat(fontSizePt = 18f), TextAlign.CENTER),
            x, y, 0.16f, 0.09f, fill = 0xFF4472C4, border = null, action = action)
        agenda.shapes.add(button("Next ▶", 0.80f, 0.86f, com.therealityteam.aeropages.model.SlideLinks.NEXT))
        agenda.shapes.add(button("End ■", 0.04f, 0.86f, com.therealityteam.aeropages.model.SlideLinks.END))
        agenda.images.add(com.therealityteam.aeropages.model.SlideImage(pngOf(64, 48, 0x70AD47), "image/png", 64f, 48f, 0.70f, 0.30f, 0.16f, 0.16f,
            action = com.therealityteam.aeropages.model.SlideLinks.toSlide(partB)))
        partA.shapes.add(button("◀ Agenda", 0.04f, 0.86f, com.therealityteam.aeropages.model.SlideLinks.toSlide(agenda)))
        partA.shapes.add(button("Last ⏭", 0.80f, 0.86f, com.therealityteam.aeropages.model.SlideLinks.LAST))
        partB.shapes.add(button("◀ Previous", 0.04f, 0.86f, com.therealityteam.aeropages.model.SlideLinks.PREVIOUS))
        partB.shapes.add(button("⏮ First", 0.80f, 0.86f, com.therealityteam.aeropages.model.SlideLinks.FIRST))
        val linked = Presentation(mutableListOf(agenda, partA, partB))
        File(out, "slide-links.pptx").outputStream().use { PptxWriter.write(linked, it) }
        File(out, "slide-links.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(linked, it) }
        // A styled slide table whose cells keep their own look and links (the table writers once dropped them).
        val tableLook = SlideTableLookTest.sampleDeck()
        File(out, "table-look.pptx").outputStream().use { PptxWriter.write(tableLook, it) }
        File(out, "table-look.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(tableLook, it) }

        // Chart elements: axis titles, legend on each side, a radar chart with its rings.
        fun elementsChart(type: com.therealityteam.aeropages.model.ChartType, pos: com.therealityteam.aeropages.model.LegendPos) =
            com.therealityteam.aeropages.model.SlideChart(
                type = type, title = "Quarterly revenue",
                categories = mutableListOf("Q1", "Q2", "Q3", "Q4", "Q5"),
                series = mutableListOf(
                    com.therealityteam.aeropages.model.ChartSeries("North", mutableListOf(42f, 55f, 48f, 61f, 58f)),
                    com.therealityteam.aeropages.model.ChartSeries("South", mutableListOf(30f, 38f, 52f, 45f, 50f)),
                ),
                legendPos = pos, catAxisTitle = "Quarter", valAxisTitle = "Revenue (k€)",
                xPct = 0.08f, yPct = 0.22f, wPct = 0.84f, hPct = 0.7f,
            )
        val elements = Presentation(mutableListOf(
            Slide(title = SlideBox("Column, legend right"), body = SlideBox("")).apply { charts.add(elementsChart(com.therealityteam.aeropages.model.ChartType.COLUMN, com.therealityteam.aeropages.model.LegendPos.RIGHT)) },
            Slide(title = SlideBox("Bar, legend top"), body = SlideBox("")).apply { charts.add(elementsChart(com.therealityteam.aeropages.model.ChartType.BAR, com.therealityteam.aeropages.model.LegendPos.TOP)) },
            Slide(title = SlideBox("Radar, legend left"), body = SlideBox("")).apply { charts.add(elementsChart(com.therealityteam.aeropages.model.ChartType.RADAR, com.therealityteam.aeropages.model.LegendPos.LEFT)) },
        ))
        File(out, "chart-elements.pptx").outputStream().use { PptxWriter.write(elements, it) }

        // Format Axis: a share chart on a fixed 0–100 % axis in 20 % steps, and revenue from ¥50k up in ¥ thousands.
        val share = com.therealityteam.aeropages.model.SlideChart(
            type = com.therealityteam.aeropages.model.ChartType.COLUMN, title = "Market share",
            categories = mutableListOf("Q1", "Q2", "Q3", "Q4"),
            series = mutableListOf(com.therealityteam.aeropages.model.ChartSeries("North", mutableListOf(0.32f, 0.41f, 0.38f, 0.55f)),
                com.therealityteam.aeropages.model.ChartSeries("South", mutableListOf(0.21f, 0.27f, 0.35f, 0.3f))),
            showValues = true, valMin = 0.0, valMax = 1.0, valMajor = 0.2, valFormat = "0%",
        )
        val revenue = com.therealityteam.aeropages.model.SlideChart(
            type = com.therealityteam.aeropages.model.ChartType.LINE, title = "Revenue",
            categories = mutableListOf("Jan", "Feb", "Mar", "Apr", "May"),
            series = mutableListOf(com.therealityteam.aeropages.model.ChartSeries("2026", mutableListOf(62000f, 58000f, 71000f, 84000f, 90000f))),
            valMin = 50000.0, valFormat = "\"¥\"#,##0,\"k\"",
        )
        val axisDeck = Presentation(mutableListOf(
            Slide(title = SlideBox("Percent axis 0–100%"), body = SlideBox("")).apply { charts.add(share) },
            Slide(title = SlideBox("Axis from ¥50k"), body = SlideBox("")).apply { charts.add(revenue) },
        ))
        File(out, "chart-axis.pptx").outputStream().use { PptxWriter.write(axisDeck, it) }

        // Column outline + hidden columns: months grouped per quarter (Q2 folded), a hidden notes column.
        val colBook = Workbook.blank()
        val cs = colBook.sheets[0]
        val months = listOf("Jan", "Feb", "Mar", "Q1", "Apr", "May", "Jun", "Q2", "Notes", "Total")
        months.forEachIndexed { c, m -> cs.ensure(0, c + 1).text = m }
        listOf("North", "South", "East").forEachIndexed { r, region ->
            cs.ensure(r + 1, 0).text = region
            for (m in 0 until 3) cs.ensure(r + 1, m + 1).text = "${10 + r + m}"
            cs.ensure(r + 1, 4).text = "=SUM(B${r + 2}:D${r + 2})"
            for (m in 0 until 3) cs.ensure(r + 1, m + 5).text = "${20 + r + m}"
            cs.ensure(r + 1, 8).text = "=SUM(F${r + 2}:H${r + 2})"
            cs.ensure(r + 1, 9).text = "internal note"
            cs.ensure(r + 1, 10).text = "=E${r + 2}+I${r + 2}"
        }
        cs.colGroups.add(com.therealityteam.aeropages.model.RowGroup(1, 3))
        cs.colGroups.add(com.therealityteam.aeropages.model.RowGroup(5, 7, collapsed = true))
        cs.hiddenCols.add(9)
        cs.manualHiddenRows.add(2)   // the South row, hidden by hand
        File(out, "outline-cols.xlsx").outputStream().use { XlsxWriter.write(colBook, it) }
        File(out, "master.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(mastered, it) }

        val wb = Workbook.blank()
        val sheet = wb.sheets[0]
        listOf("City", "Sales", "Share").forEachIndexed { c, t -> sheet.ensure(0, c).text = t }
        listOf("Beijing" to 120, "Shanghai" to 95, "Shenzhen" to 60).forEachIndexed { i, (city, n) ->
            sheet.ensure(i + 1, 0).text = city
            sheet.ensure(i + 1, 1).text = n.toString()
            sheet.ensure(i + 1, 2).text = "=B${i + 2}/SUM(B2:B4)"
        }
        sheet.ensure(4, 0).text = "Total"
        sheet.ensure(4, 1).text = "=SUM(B2:B4)"
        sheet.ensure(1, 1).comment = "Q3 figure, unaudited"   // the grid's red note marker
        sheet.ensure(6, 0).apply {                                // mixed formatting inside one cell
            text = "Bold red plain"
            runs = mutableListOf(
                com.therealityteam.aeropages.model.TextRun("Bold ", com.therealityteam.aeropages.model.CharacterFormat(bold = true)),
                com.therealityteam.aeropages.model.TextRun("red ", com.therealityteam.aeropages.model.CharacterFormat(color = 0xFFFF0000)),
                com.therealityteam.aeropages.model.TextRun("plain", com.therealityteam.aeropages.model.CharacterFormat()),
            )
        }
        File(out, "smoke.xlsx").outputStream().use { XlsxWriter.write(wb, it) }

        // Row outline: regions with their cities grouped; the West block folded.
        val ol = Workbook.blank()
        val os = ol.sheets[0]
        listOf("Region", "Sales").forEachIndexed { c, t -> os.ensure(0, c).text = t }
        listOf("Beijing" to 120, "Tianjin" to 40, "East total" to -1, "Shanghai" to 95, "Hangzhou" to 50, "Suzhou" to 30, "West total" to -2, "Grand total" to -3)
            .forEachIndexed { i, (name, v) ->
                os.ensure(i + 1, 0).text = name
                os.ensure(i + 1, 1).text = when (v) { -1 -> "=SUM(B2:B3)"; -2 -> "=SUM(B5:B7)"; -3 -> "=B4+B8"; else -> v.toString() }
            }
        os.rowGroups.add(com.therealityteam.aeropages.model.RowGroup(1, 7))
        os.rowGroups.add(com.therealityteam.aeropages.model.RowGroup(1, 2))
        os.rowGroups.add(com.therealityteam.aeropages.model.RowGroup(4, 6, collapsed = true))
        File(out, "outline.xlsx").outputStream().use { XlsxWriter.write(ol, it) }

        // Sparklines beside each city's quarterly numbers, and a protected sheet with one unlocked cell.
        val spark = Workbook.blank()
        val sp = spark.sheets[0]
        listOf("City", "Q1", "Q2", "Q3", "Q4", "Trend").forEachIndexed { c, t -> sp.ensure(0, c).apply { text = t; format.bold = true } }
        listOf("Beijing" to listOf(12, 15, 9, 21), "Shanghai" to listOf(8, 6, 11, 14), "Shenzhen" to listOf(5, -2, 7, 3)).forEachIndexed { i, (city, q) ->
            sp.ensure(i + 1, 0).text = city
            q.forEachIndexed { k, n -> sp.ensure(i + 1, 1 + k).text = n.toString() }
        }
        sp.sparklines.add(com.therealityteam.aeropages.model.Sparkline(1, 5, 1, 1, 1, 4, com.therealityteam.aeropages.model.SparklineType.LINE, markers = true))
        sp.sparklines.add(com.therealityteam.aeropages.model.Sparkline(2, 5, 2, 1, 2, 4, com.therealityteam.aeropages.model.SparklineType.COLUMN))
        sp.sparklines.add(com.therealityteam.aeropages.model.Sparkline(3, 5, 3, 1, 3, 4, com.therealityteam.aeropages.model.SparklineType.WIN_LOSS))
        sp.ensure(5, 0).apply { text = "editable"; unlocked = true }
        sp.protected = true
        sp.colWidths[5] = 1.6f
        File(out, "sparklines.xlsx").outputStream().use { XlsxWriter.write(spark, it) }

        // Custom number format codes: each row's code in A and a value shown through it in B.
        val codes = Workbook.blank()
        val fs = codes.sheets[0]
        val day = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(1899, 12, 30), java.time.LocalDate.of(2026, 9, 29)).toString()
        listOf("Format code", "Shows").forEachIndexed { c, t -> fs.ensure(0, c).apply { text = t; format.bold = true } }
        listOf(
            "yyyy\"年\"m\"月\"d\"日\"" to day, "dddd, mmmm d, yyyy" to day, "#,##0.00;[Red]-#,##0.00" to "-1234.5",
            "0.0,,\"M\"" to "1500000", "[h]:mm:ss" to "1.5", "# ?/?" to "1.5", "0.00E+00" to "12345",
            "[DBNum2][\$-804]General" to "1234", "@" to "00123", "\"¥\"#,##0.00;[Red]\"¥\"-#,##0.00" to "-99.5",
            "上午/下午h\"时\"mm\"分\"" to "0.75", "000-0000" to "5551234",
            // AM/PM in the device's language ([$-409] keeps English), Excel's accounting format (built-in 43), a Japanese era date.
            "h:mm AM/PM" to "0.75", "[\$-409]h:mm AM/PM" to "0.75", "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)" to "-1234.5",
            "[\$-411]ggge\"年\"m\"月\"d\"日\"" to day,
        ).forEachIndexed { i, (code, value) ->
            fs.ensure(i + 1, 0).text = code
            fs.ensure(i + 1, 1).apply { text = value; formatCode = code; numberFormat = com.therealityteam.aeropages.model.FormatCode.category(code) }
        }
        fs.colWidths[0] = 2.6f
        fs.colWidths[1] = 2.4f
        File(out, "custom-formats.xlsx").outputStream().use { XlsxWriter.write(codes, it) }
        File(out, "custom-formats.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(codes, it) }

        // Long text running on over empty cells (and cut where the next cell has something).
        val over = Workbook.blank()
        val ov = over.sheets[0]
        ov.ensure(0, 0).apply { text = "Quarterly sales by region — overview for the board"; format.bold = true; format.fontSizePt = 16f }
        ov.ensure(1, 0).text = "Stops at the next value"; ov.ensure(1, 2).text = "C2"
        ov.ensure(2, 0).text = "123456789012345678901234"
        ov.ensure(3, 0).text = "区域销售季度概览（长标题会延伸到右侧空白单元格）"
        ov.ensure(4, 1).apply { text = "Centred stays inside"; align = com.therealityteam.aeropages.model.TextAlign.CENTER }
        File(out, "overflow.xlsx").outputStream().use { XlsxWriter.write(over, it) }

        // Border line styles: a small statement (thick header rule, total with a double bottom) and every style beside it.
        val lines = Workbook.blank()
        val ls = lines.sheets[0]
        val thin = com.therealityteam.aeropages.model.BorderLine.THIN
        listOf("Item", "Q1", "Q2").forEachIndexed { c, t -> ls.ensure(0, c).apply { text = t; format.bold = true; borderBottom = com.therealityteam.aeropages.model.BorderLine.THICK } }
        listOf(listOf("Rent", "1200", "1250"), listOf("Travel", "340", "410"), listOf("Software", "99", "129")).forEachIndexed { i, row ->
            row.forEachIndexed { c, t -> ls.ensure(1 + i, c).apply { text = t; borderBottom = com.therealityteam.aeropages.model.BorderLine(com.therealityteam.aeropages.model.LineStyle.HAIR) } }
        }
        listOf("Total", "=SUM(B2:B4)", "=SUM(C2:C4)").forEachIndexed { c, t ->
            ls.ensure(4, c).apply { text = t; format.bold = true; borderTop = thin; borderBottom = com.therealityteam.aeropages.model.BorderLine.DOUBLE }
        }
        com.therealityteam.aeropages.model.LineStyle.entries.forEachIndexed { i, st ->
            ls.ensure(1 + i, 4).apply { text = st.name.lowercase(); borderBottom = com.therealityteam.aeropages.model.BorderLine(st, if (i % 3 == 0) 0xFFC00000 else null) }
        }
        ls.colWidths[4] = 1.8f
        File(out, "borders.xlsx").outputStream().use { XlsxWriter.write(lines, it) }

        // Cell styles and a table look, applied through the editor as the galleries apply them.
        val styled = com.therealityteam.aeropages.editor.SheetEditor().apply { load(Workbook.blank()) }
        listOf(listOf("Region", "Q1", "Q2"), listOf("North", "10", "12"), listOf("South", "8", "9"), listOf("West", "5", "7"), listOf("East", "11", "13"))
            .forEachIndexed { r, row -> row.forEachIndexed { c, t -> styled.setCell(r, c, t) } }
        styled.select(1, 1); styled.formatAsTable(com.therealityteam.aeropages.model.TableStyle(0, medium = true))
        com.therealityteam.aeropages.model.CellStyle.entries.forEachIndexed { i, st ->
            styled.setCell(i, 4, st.name.lowercase().replace('_', ' '))
            styled.select(i, 4); styled.applyCellStyle(st)
        }
        styled.sheet.colWidths[4] = 1.8f
        File(out, "styles.xlsx").outputStream().use { XlsxWriter.write(styled.workbook, it) }

        // Data rules in OpenDocument: a list rule with an input message, a whole-number rule, a
        // defined name and an AutoFilter hiding one row — written as .ods (and .xlsx to compare).
        val ruled = com.therealityteam.aeropages.editor.SheetEditor().apply { load(Workbook.blank()) }
        listOf(listOf("Task", "Owner", "Status", "Hours"), listOf("Design", "Ann", "Done", "6"), listOf("Build", "Ben", "In progress", "30"),
            listOf("Launch", "Cy", "Blocked", "90"), listOf("Review", "Di", "Done", "4"))
            .forEachIndexed { r, row -> row.forEachIndexed { c, t -> ruled.sheet.ensure(r, c).text = t } }
        ruled.select(1, 2); ruled.extendSelection(8, 0)
        ruled.setValidation(com.therealityteam.aeropages.model.DataValidation(0, 0, 0, 0, com.therealityteam.aeropages.model.ValidationType.LIST,
            items = listOf("Not started", "In progress", "Done"), inputTitle = "Status", inputMessage = "Pick the task's status from the list."))
        ruled.select(1, 3); ruled.extendSelection(8, 0)
        ruled.setValidation(com.therealityteam.aeropages.model.DataValidation(0, 0, 0, 0, com.therealityteam.aeropages.model.ValidationType.INTEGER, 0.0, 40.0))
        ruled.select(1, 1); ruled.extendSelection(3, 0); ruled.defineName("Owners")
        ruled.select(0, 0); ruled.toggleAutoFilter()
        ruled.setColumnFilter(0, com.therealityteam.aeropages.model.ColumnFilter(values = setOf("Design", "Build", "Launch")))
        File(out, "rules.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(ruled.workbook, it) }
        File(out, "rules.xlsx").outputStream().use { XlsxWriter.write(ruled.workbook, it) }
        // Every kind of rule, for checking what LibreOffice makes of them.
        val every = Workbook(mutableListOf(com.therealityteam.aeropages.model.Sheet(name = "Tasks"), com.therealityteam.aeropages.model.Sheet(name = "Lists")))
        every.sheets[1].ensure(0, 0).text = "Red"; every.sheets[1].ensure(1, 0).text = "Green"
        every.sheets[0].validations += listOf(
            com.therealityteam.aeropages.model.DataValidation(1, 0, 9, 0, com.therealityteam.aeropages.model.ValidationType.CUSTOM, formula1 = "COUNTIF(\$A\$2:\$A\$10,A2)=1"),
            com.therealityteam.aeropages.model.DataValidation(1, 1, 9, 1, com.therealityteam.aeropages.model.ValidationType.LIST, formula1 = "Lists!\$A\$1:\$A\$2"),
            com.therealityteam.aeropages.model.DataValidation(1, 2, 9, 2, com.therealityteam.aeropages.model.ValidationType.DATE, operator = com.therealityteam.aeropages.model.ValidationOperator.GREATER_OR_EQUAL, formula1 = "TODAY()", errorStyle = com.therealityteam.aeropages.model.ValidationAlertStyle.WARNING),
            com.therealityteam.aeropages.model.DataValidation(1, 3, 9, 3, com.therealityteam.aeropages.model.ValidationType.INTEGER, 0.0, 40.0, operator = com.therealityteam.aeropages.model.ValidationOperator.NOT_BETWEEN, errorStyle = com.therealityteam.aeropages.model.ValidationAlertStyle.INFORMATION),
            com.therealityteam.aeropages.model.DataValidation(1, 4, 9, 4, com.therealityteam.aeropages.model.ValidationType.TIME, 0.375, 0.75),
            com.therealityteam.aeropages.model.DataValidation(1, 5, 9, 5, com.therealityteam.aeropages.model.ValidationType.TEXT_LENGTH, 10.0, operator = com.therealityteam.aeropages.model.ValidationOperator.LESS_OR_EQUAL),
            com.therealityteam.aeropages.model.DataValidation(1, 6, 9, 6, com.therealityteam.aeropages.model.ValidationType.DECIMAL, 1.5, operator = com.therealityteam.aeropages.model.ValidationOperator.NOT_EQUAL),
            com.therealityteam.aeropages.model.DataValidation(1, 7, 9, 7, com.therealityteam.aeropages.model.ValidationType.ANY, inputTitle = "Note", inputMessage = "Anything goes"),
        )
        File(out, "rules-all.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(every, it) }

        // Rows that fit their content: a 24-point title, wrapping text, mixed type, and a row given a
        // height with its cells at the top, the middle and the bottom.
        val fit = com.therealityteam.aeropages.editor.SheetEditor().apply { load(Workbook.blank()) }
        fit.setCell(0, 0, "Quarterly review"); fit.select(0, 0); fit.setFontSize(24f); fit.toggleBold()
        fit.setCell(1, 0, "Region"); fit.setCell(1, 1, "Notes"); fit.setCell(1, 2, "Sales")
        fit.setCell(2, 0, "North"); fit.setCell(2, 1, "Strong quarter: two new customers signed and the backlog cleared before the holidays."); fit.setCell(2, 2, "1200")
        fit.select(2, 1); fit.toggleWrap()
        fit.setCell(3, 0, "South"); fit.setCell(3, 1, "Flat"); fit.setCell(3, 2, "940")
        fit.setCell(4, 0, "Top"); fit.setCell(4, 1, "Middle"); fit.setCell(4, 2, "Bottom")
        fit.beginRowResize(4); fit.dragRowHeight(1.5f); fit.endResize()
        fit.sheet.cell(4, 0)!!.vAlign = com.therealityteam.aeropages.model.VAlign.TOP
        fit.sheet.cell(4, 2)!!.vAlign = com.therealityteam.aeropages.model.VAlign.BOTTOM
        fit.sheet.colWidths[1] = 2.2f
        File(out, "rowfit.xlsx").outputStream().use { XlsxWriter.write(fit.workbook, it) }

        // Conditional formats of every kind, a column each (header in row 1, twelve rows of data below).
        val cf = com.therealityteam.aeropages.model.Sheet(name = "Rules")
        val heads = listOf("Value", "Fruit", "Code", "Score", "Heat", "Bars", "Arrows", "Lights", "Rating", "Band", "Date", "Check")
        heads.forEachIndexed { c, h -> cf.ensure(0, c).text = h }
        val fruits = listOf("Banana", "apple", "Mango", "Cherry", "Orange", "Grape", "Pear", "Plum", "Melon", "Kiwi", "Lemon", "Papaya")
        val cfCodes = listOf("A1", "B2", "A1", "C3", "D4", "B2", "E5", "F6", "G7", "A1", "H8", "I9")
        val today = java.time.LocalDate.now()
        fun serial(d: java.time.LocalDate) = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(1899, 12, 30), d).toString()
        for (i in 0 until 12) {
            val r = i + 1
            cf.ensure(r, 0).text = "${i + 1}"
            cf.ensure(r, 1).text = fruits[i]
            cf.ensure(r, 2).text = cfCodes[i]
            cf.ensure(r, 3).text = "${listOf(72, 95, 41, 88, 63, 99, 57, 34, 80, 66, 91, 45)[i]}"
            for (c in 4..8) cf.ensure(r, c).text = "${(i * 9 + c * 7) % 100}"
            cf.ensure(r, 9).text = "row ${r + 1}"
            cf.ensure(r, 10).apply { text = serial(today.minusDays(i * 2L)); numberFormat = com.therealityteam.aeropages.model.NumberFormat.DATE }
            cf.ensure(r, 11).text = when (i % 4) { 0 -> ""; 1 -> "=1/0"; 2 -> "ok"; else -> "=A2*2" }
        }
        val rose = com.therealityteam.aeropages.editor.CondEval.PRESETS[0]
        val sun = com.therealityteam.aeropages.editor.CondEval.PRESETS[1]
        val leaf = com.therealityteam.aeropages.editor.CondEval.PRESETS[2]
        fun on(c: Int, rule: com.therealityteam.aeropages.model.CondFormat) = cf.condFormats.add(rule.over(1, c, 12, c))
        on(0, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.GREATER, 8.0, style = rose))
        on(0, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.LESS_EQUAL, 3.0, style = leaf))
        on(1, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.TEXT_CONTAINS, text = "an", style = sun))
        on(2, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.DUPLICATES, style = rose))
        on(3, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.TOP, 3.0, style = com.therealityteam.aeropages.model.CondStyle(fill = 0xFFC6EFCE, font = 0xFF006100, bold = true)))
        on(3, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.BOTTOM, 2.0, style = com.therealityteam.aeropages.model.CondStyle(font = 0xFFC00000, italic = true)))
        on(4, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.COLOR_SCALE, stops = listOf(
            com.therealityteam.aeropages.model.ScalePoint(com.therealityteam.aeropages.model.ScaleKind.MIN) to 0xFFF8696B,
            com.therealityteam.aeropages.model.ScalePoint(com.therealityteam.aeropages.model.ScaleKind.PERCENTILE, 50.0) to 0xFFFFEB84,
            com.therealityteam.aeropages.model.ScalePoint(com.therealityteam.aeropages.model.ScaleKind.MAX) to 0xFF63BE7B)))
        on(5, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.DATA_BAR, color = 0xFF638EC6, barMin = 0, barMax = 100))
        on(6, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.ICON_SET, icons = com.therealityteam.aeropages.model.IconSetKind.ARROWS_3))
        on(7, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.ICON_SET, icons = com.therealityteam.aeropages.model.IconSetKind.TRAFFIC_LIGHTS_3))
        on(8, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.ICON_SET, icons = com.therealityteam.aeropages.model.IconSetKind.RATING_5))
        on(9, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.FORMULA, formula1 = "MOD(ROW(),2)=0", style = com.therealityteam.aeropages.model.CondStyle(fill = 0xFFDDEBF7)))
        on(10, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.DATE, period = com.therealityteam.aeropages.model.DatePeriod.LAST_7_DAYS, style = sun))
        on(11, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.ERRORS, style = rose))
        on(11, com.therealityteam.aeropages.model.CondFormat(0, 0, 0, 0, com.therealityteam.aeropages.model.CondType.BLANKS, style = com.therealityteam.aeropages.model.CondStyle(fill = 0xFFE7E6E6)))
        File(out, "condformats.xlsx").outputStream().use { XlsxWriter.write(Workbook(mutableListOf(cf)), it) }
        File(out, "condformats.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(Workbook(mutableListOf(cf)), it) }

        // Links within a workbook: an index sheet whose cells go to a cell, to a range on a sheet with a quoted
        // name, to a defined name, to the web, and a HYPERLINK formula; each data sheet links back.
        val index = com.therealityteam.aeropages.model.Sheet(name = "Index")
        val dataSheet = com.therealityteam.aeropages.model.Sheet(name = "Data")
        val q3 = com.therealityteam.aeropages.model.Sheet(name = "Q3 Sales")
        index.ensure(0, 0).text = "Contents"
        index.ensure(1, 0).apply { text = "Totals"; link = "#Data!B3" }
        index.ensure(2, 0).apply { text = "Third quarter"; link = "#'Q3 Sales'!A1:C4" }
        index.ensure(3, 0).apply { text = "The total"; link = "#Total" }
        index.ensure(4, 0).apply { text = "Our site"; link = "https://example.com/" }
        index.ensure(5, 0).text = "=HYPERLINK(\"#Data!A1\",\"Data, from its top\")"
        dataSheet.ensure(0, 0).text = "Region"; dataSheet.ensure(0, 1).text = "Sales"
        dataSheet.ensure(1, 0).text = "North"; dataSheet.ensure(1, 1).text = "30"
        dataSheet.ensure(2, 0).text = "Total"; dataSheet.ensure(2, 1).text = "=SUM(B2:B2)"
        dataSheet.ensure(4, 0).apply { text = "Back to the index"; link = "#Index!A1" }
        q3.ensure(0, 0).text = "Month"; q3.ensure(0, 1).text = "Units"
        listOf("Jul" to 12, "Aug" to 15, "Sep" to 18).forEachIndexed { i, (m, n) -> q3.ensure(i + 1, 0).text = m; q3.ensure(i + 1, 1).text = n.toString() }
        q3.ensure(5, 0).apply { text = "Back to the index"; link = "#Index!A1" }
        val linkedBook = Workbook(mutableListOf(index, dataSheet, q3), names = mutableMapOf("Total" to "Data!\$B\$3"))
        File(out, "sheet-links.xlsx").outputStream().use { XlsxWriter.write(linkedBook, it) }
        File(out, "sheet-links.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(linkedBook, it) }

        // Logical values and Excel's error values: comparisons, typed TRUE / FALSE, AND / OR / COUNTIF over them,
        // a lookup that finds nothing (#N/A, and IFNA's stand-in), text in arithmetic (#VALUE!), a division by 0 —
        // saved with each formula's value beside it, as the app saves.
        val checks = com.therealityteam.aeropages.model.Sheet(name = "Checks")
        listOf("Item", "Qty", "Over 5?", "In stock", "Price", "Value").forEachIndexed { c, t -> checks.ensure(0, c).apply { text = t; format.bold = true } }
        listOf(Triple("pear", "12", "TRUE"), Triple("fig", "3", "FALSE"), Triple("kiwi", "7", "TRUE"), Triple("plum", "0", "TRUE")).forEachIndexed { i, (item, qty, stock) ->
            val r = i + 1
            checks.ensure(r, 0).text = item
            checks.ensure(r, 1).text = qty
            checks.ensure(r, 2).text = "=B${r + 1}>5"
            checks.ensure(r, 3).text = stock
            checks.ensure(r, 4).text = "=IFNA(VLOOKUP(A${r + 1},\$H\$2:\$I\$4,2,FALSE),\"none\")"
            checks.ensure(r, 5).text = "=B${r + 1}*E${r + 1}"
        }
        listOf("All in stock?" to "=AND(D2:D5)", "Any over 5?" to "=OR(C2:C5)", "In stock" to "=COUNTIF(D2:D5,TRUE)",
            "Kiwi's price" to "=VLOOKUP(\"kiwi\",H2:I4,2,FALSE)", "Not found?" to "=ISNA(B10)", "Per item" to "=F2/B5",
            "Text" to "=IF(B2>5,\"many\")&\"!\"", "Same?" to "=0.1+0.2=0.3").forEachIndexed { i, (label, formula) ->
            checks.ensure(6 + i, 0).text = label
            checks.ensure(6 + i, 1).text = formula
        }
        checks.ensure(0, 7).apply { text = "Fruit"; format.bold = true }; checks.ensure(0, 8).apply { text = "Price"; format.bold = true }
        listOf("pear" to "1.5", "fig" to "2.25", "plum" to "0.8").forEachIndexed { i, (f, p) -> checks.ensure(i + 1, 7).text = f; checks.ensure(i + 1, 8).text = p }
        checks.colWidths[0] = 1.4f
        val logicalBook = Workbook(mutableListOf(checks))
        val logicalValues = { si: Int, r: Int, c: Int -> com.therealityteam.aeropages.editor.FormulaEngine.cached(logicalBook.sheets[si], r, c, logicalBook.sheets) }
        File(out, "logicals.xlsx").outputStream().use { XlsxWriter.write(logicalBook, it, logicalValues) }
        File(out, "logicals.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(logicalBook, it, logicalValues) }

        // Formulas: the monthly income-tax formula with array constants, a loan's payment and schedule, an investment's
        // return, statistics, dates, text and lookups — the functions FormulaOracleTest checks against LibreOffice.
        val calc = com.therealityteam.aeropages.model.Sheet(name = "Calculations")
        listOf(
            "Monthly income" to "20000", "Income tax" to "=MAX((B1-5000)*{0.03,0.1,0.2,0.25,0.3,0.35,0.45}-{0,210,1410,2660,4410,7160,15160},0)",
            "Loan" to "300000", "Annual rate" to "0.049", "Years" to "30",
            "Monthly payment" to "=PMT(B4/12,B5*12,-B3)", "Interest, year 1" to "=-CUMIPMT(B4/12,B5*12,B3,1,12,0)",
            "Principal, year 1" to "=-CUMPRINC(B4/12,B5*12,B3,1,12,0)", "Cash flows" to "-10000", "" to "3000", " " to "4200", "  " to "6800",
            "Return (IRR)" to "=IRR(B9:B12)", "Net present value" to "=NPV(0.08,B10:B12)+B9", "Scores" to "=TEXTJOIN(\", \",TRUE,D2:D9)",
            "Median, sd" to "=MEDIAN(D2:D9)&\", \"&FIXED(STDEV.S(D2:D9),2)", "Top quartile" to "=QUARTILE.INC(D2:D9,3)",
            "Working days in March" to "=NETWORKDAYS(DATE(2024,3,1),EOMONTH(DATE(2024,3,1),0))", "Week of the year" to "=WEEKNUM(TODAY())&\" / ISO \"&ISOWEEKNUM(TODAY())",
            "Grade of 86" to "=LOOKUP(86,{0,60,70,80,90},{\"F\",\"D\",\"C\",\"B\",\"A\"})", "Name" to "=PROPER(\"ada lovelace\")",
        ).forEachIndexed { r, (label, v) -> calc.ensure(r, 0).text = label.trim(); calc.ensure(r, 1).text = v }
        calc.ensure(0, 3).apply { text = "Scores"; format.bold = true }
        listOf("78", "92", "85", "64", "99", "71", "88", "90").forEachIndexed { r, v -> calc.ensure(r + 1, 3).text = v }
        listOf(1, 5, 6, 7, 13, 14).forEach { calc.ensure(it, 1).numberFormat = com.therealityteam.aeropages.model.NumberFormat.NUMBER2 }
        calc.ensure(3, 1).numberFormat = com.therealityteam.aeropages.model.NumberFormat.PERCENT
        calc.ensure(12, 1).numberFormat = com.therealityteam.aeropages.model.NumberFormat.PERCENT
        calc.colWidths[0] = 2.2f; calc.colWidths[1] = 1.6f
        val calcBook = Workbook(mutableListOf(calc))
        val calcValues = { si: Int, r: Int, c: Int -> com.therealityteam.aeropages.editor.FormulaEngine.cached(calcBook.sheets[si], r, c, calcBook.sheets) }
        File(out, "formulas.xlsx").outputStream().use { XlsxWriter.write(calcBook, it, calcValues) }
        File(out, "formulas.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(calcBook, it, calcValues) }

        // Sorting: a header over items whose Qty mixes numbers, TRUE and a blank, and Total formulas reading
        // their own row — sort by Qty (`-d sortquick`) or through Custom Sort (`-d customsort`).
        val sortSheet = com.therealityteam.aeropages.model.Sheet(name = "Stock")
        listOf("Item", "Qty", "Total").forEachIndexed { c, t -> sortSheet.ensure(0, c).text = t }
        listOf("pear" to "12", "Apple" to "3", "fig" to "", "banana" to "TRUE", "apple pie" to "7", "cherry" to "-2").forEachIndexed { i, (item, qty) ->
            sortSheet.ensure(i + 1, 0).text = item
            if (qty.isNotEmpty()) sortSheet.ensure(i + 1, 1).text = qty
            sortSheet.ensure(i + 1, 2).text = "=B${i + 2}*2"
        }
        val sortBook = Workbook(mutableListOf(sortSheet))
        File(out, "sort.xlsx").outputStream().use { o ->
            XlsxWriter.write(sortBook, o) { si, r, c -> com.therealityteam.aeropages.editor.FormulaEngine.cached(sortBook.sheets[si], r, c, sortBook.sheets) }
        }

        // Cropped pictures: a four-colour picture (red, green / blue, yellow quarters) whole on the left
        // and cropped to its green top-right quarter on the right, in pptx and odp.
        fun quadPng(w: Int, h: Int): ByteArray {
            val raw = java.io.ByteArrayOutputStream()
            for (y in 0 until h) {
                raw.write(0)
                for (x in 0 until w) {
                    val c = when { y < h / 2 && x < w / 2 -> 0xD9342B; y < h / 2 -> 0x2E9E4F; x < w / 2 -> 0x2B579A; else -> 0xF2C12E }
                    raw.write((c shr 16) and 0xFF); raw.write((c shr 8) and 0xFF); raw.write(c and 0xFF)
                }
            }
            val deflater = java.util.zip.Deflater(); deflater.setInput(raw.toByteArray()); deflater.finish()
            val zipped = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
            while (!deflater.finished()) zipped.write(buf, 0, deflater.deflate(buf))
            fun chunk(type: String, data: ByteArray): ByteArray {
                val o = java.io.ByteArrayOutputStream(); val d = java.io.DataOutputStream(o)
                d.writeInt(data.size); d.write(type.toByteArray(Charsets.US_ASCII)); d.write(data)
                d.writeInt(java.util.zip.CRC32().also { it.update(type.toByteArray(Charsets.US_ASCII)); it.update(data) }.value.toInt())
                return o.toByteArray()
            }
            val ihdr = java.io.ByteArrayOutputStream().also { val d = java.io.DataOutputStream(it); d.writeInt(w); d.writeInt(h); d.write(8); d.write(2); d.write(0); d.write(0); d.write(0) }.toByteArray()
            return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + chunk("IDAT", zipped.toByteArray()) + chunk("IEND", ByteArray(0))
        }
        val croppedDeck = com.therealityteam.aeropages.editor.SlideEditor()
        croppedDeck.load(com.therealityteam.aeropages.model.Presentation(mutableListOf(com.therealityteam.aeropages.model.Slide(title = com.therealityteam.aeropages.model.SlideBox("Cropped pictures", CharacterFormat(fontSizePt = 36f, bold = true), com.therealityteam.aeropages.model.TextAlign.CENTER), body = com.therealityteam.aeropages.model.SlideBox("")))))
        val quad = quadPng(320, 240)
        croppedDeck.setImage(quad, "image/png", 320f, 240f)
        croppedDeck.selectedImage!!.apply { xPct = 0.06f; yPct = 0.3f; wPct = 0.4f; hPct = 0.4f * (16f / 9f) / (4f / 3f) }
        croppedDeck.setImage(quad, "image/png", 320f, 240f)
        croppedDeck.selectedImage!!.apply { xPct = 0.54f; yPct = 0.3f; wPct = 0.4f; hPct = 0.4f * (16f / 9f) / (4f / 3f) }
        croppedDeck.cropImage(0.5f, 0f, 0f, 0.5f)
        File(out, "crop.pptx").outputStream().use { PptxWriter.write(croppedDeck.presentation, it) }
        File(out, "crop.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(croppedDeck.presentation, it) }

        // The same picture in a document: whole, cropped to its green quarter, and cropped to its
        // bottom half inside a table cell, in docx and odt.
        val cellPicture = com.therealityteam.aeropages.model.ImageBlock(quad, "image/png", 160f, 120f)
        val croppedPicture = com.therealityteam.aeropages.model.ImageBlock(quad, "image/png", 240f, 180f)
        val croppedDoc = com.therealityteam.aeropages.editor.DocumentEditor()
        croppedDoc.load(Document().apply {
            blocks.clear()
            blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Cropped pictures")), ParagraphFormat(style = ParagraphStyle.HEADING1))))
            blocks.add(ParagraphBlock(Paragraph.of("The whole picture:")))
            blocks.add(com.therealityteam.aeropages.model.ImageBlock(quad, "image/png", 240f, 180f))
            blocks.add(ParagraphBlock(Paragraph.of("Cropped to its green quarter, at the same scale:")))
            blocks.add(croppedPicture)
            blocks.add(com.therealityteam.aeropages.model.TableBlock(com.therealityteam.aeropages.model.Table(mutableListOf(com.therealityteam.aeropages.model.TableRow(mutableListOf(
                com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph.of("In a cell, cropped to its bottom half:"))),
                com.therealityteam.aeropages.model.TableCell(mutableListOf(Paragraph.of("Blue and yellow")), images = mutableListOf(cellPicture)),
            ))))))
        })
        croppedDoc.cropImage(croppedPicture, 0.5f, 0f, 0f, 0.5f)
        croppedDoc.cropImage(cellPicture, 0f, 0.5f, 0f, 0f)
        File(out, "crop.docx").outputStream().use { DocxWriter.write(croppedDoc.document, it) }
        File(out, "crop.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(croppedDoc.document, it) }

        // Animations: the title shows with the slide; click 1 flies the body in from the left while the
        // picture zooms in 250 ms later, then the shape pulses; click 2 grows the shape; click 3 fades the picture out.
        val animDeck = com.therealityteam.aeropages.model.Presentation(mutableListOf(com.therealityteam.aeropages.model.Slide(
            title = com.therealityteam.aeropages.model.SlideBox("Animations", CharacterFormat(fontSizePt = 36f, bold = true), com.therealityteam.aeropages.model.TextAlign.CENTER),
            body = com.therealityteam.aeropages.model.SlideBox("First point\nSecond point", CharacterFormat(fontSizePt = 24f)),
        )))
        animDeck.slides[0].let { s ->
            s.body.frame = com.therealityteam.aeropages.model.BoxFrame(0.06f, 0.3f, 0.4f, 0.4f)
            s.body.anims.add(com.therealityteam.aeropages.model.ElementAnim(AnimEffect.FLY_IN, AnimStart.ON_CLICK, 600, 0, com.therealityteam.aeropages.model.AnimDir.LEFT, order = 0))
            val pic = com.therealityteam.aeropages.model.SlideImage(quad, "image/png", 320f, 240f, 0.52f, 0.3f, 0.3f, 0.3f * (16f / 9f) / (4f / 3f))
            pic.anims.add(com.therealityteam.aeropages.model.ElementAnim(AnimEffect.ZOOM, AnimStart.WITH_PREVIOUS, 500, 250, order = 1))
            pic.anims.add(com.therealityteam.aeropages.model.ElementAnim(AnimEffect.FADE_OUT, AnimStart.ON_CLICK, 800, 0, order = 4))
            s.images.add(pic)
            val shape = com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.ROUNDED,
                com.therealityteam.aeropages.model.SlideBox("Look here", CharacterFormat(fontSizePt = 20f, bold = true, color = 0xFFFFFFFF), com.therealityteam.aeropages.model.TextAlign.CENTER),
                0.6f, 0.78f, 0.18f, 0.12f)
            shape.fill = 0xFF2B579A
            shape.box.anims.add(com.therealityteam.aeropages.model.ElementAnim(AnimEffect.PULSE, AnimStart.AFTER_PREVIOUS, 500, 0, order = 2))
            shape.box.anims.add(com.therealityteam.aeropages.model.ElementAnim(AnimEffect.GROW_SHRINK, AnimStart.ON_CLICK, 700, 0, order = 3))
            s.shapes.add(shape)
        }
        // Bullets built by paragraph: each click flies the next one in.
        animDeck.slides.add(com.therealityteam.aeropages.model.Slide(
            title = com.therealityteam.aeropages.model.SlideBox("By paragraph", CharacterFormat(fontSizePt = 36f, bold = true), com.therealityteam.aeropages.model.TextAlign.CENTER),
            body = com.therealityteam.aeropages.model.SlideBox("First point\nSecond point\nThird point", CharacterFormat(fontSizePt = 24f)),
        ).also { s ->
            s.body.listStyle = com.therealityteam.aeropages.model.SlideListStyle.BULLET
            s.body.anims.add(com.therealityteam.aeropages.model.ElementAnim(AnimEffect.FLY_IN, AnimStart.ON_CLICK, 500, 0, com.therealityteam.aeropages.model.AnimDir.LEFT, order = 0, byParagraph = true))
        })
        // A third, hidden slide: skipped by the show, marked the way each format marks it.
        animDeck.slides.add(com.therealityteam.aeropages.model.Slide(title = com.therealityteam.aeropages.model.SlideBox("Hidden slide")).also { it.hidden = true })
        File(out, "anim.pptx").outputStream().use { PptxWriter.write(animDeck, it) }
        File(out, "anim.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(animDeck, it) }

        // A picture squeezed into a frame of another shape stretches to it, as Office draws it: the
        // four-colour picture at its 4:3 on the left, in a 2:1 frame on the right (and in a document).
        val stretchedDeck = com.therealityteam.aeropages.editor.SlideEditor()
        stretchedDeck.load(com.therealityteam.aeropages.model.Presentation(mutableListOf(com.therealityteam.aeropages.model.Slide(title = com.therealityteam.aeropages.model.SlideBox("Stretched to the frame", CharacterFormat(fontSizePt = 36f, bold = true), com.therealityteam.aeropages.model.TextAlign.CENTER), body = com.therealityteam.aeropages.model.SlideBox("")))))
        stretchedDeck.setImage(quad, "image/png", 320f, 240f)
        stretchedDeck.selectedImage!!.apply { xPct = 0.06f; yPct = 0.3f; wPct = 0.3f; hPct = 0.3f * (16f / 9f) / (4f / 3f) }
        stretchedDeck.setImage(quad, "image/png", 320f, 240f)
        stretchedDeck.selectedImage!!.apply { xPct = 0.44f; yPct = 0.3f; wPct = 0.5f; hPct = 0.5f * (16f / 9f) / 2f }
        File(out, "stretch.pptx").outputStream().use { PptxWriter.write(stretchedDeck.presentation, it) }

        // Every slide layout: a title slide with its subtitle (and one without), content with and
        // without text, a section header, a title-only slide and a blank one.
        val layoutDeck = Presentation(mutableListOf(
            Slide(title = SlideBox("Quarterly review", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER), body = SlideBox("Board meeting · October 2026", CharacterFormat(fontSizePt = 20f), TextAlign.CENTER), layout = SlideLayout.TITLE),
            Slide(title = SlideBox("Agenda", CharacterFormat(fontSizePt = 32f, bold = true)), body = SlideBox(""), layout = SlideLayout.TITLE_CONTENT),
            Slide(title = SlideBox("Results", CharacterFormat(fontSizePt = 32f, bold = true)), body = SlideBox("Revenue up 12%\nCosts down 4%", CharacterFormat(fontSizePt = 24f)), layout = SlideLayout.TITLE_CONTENT),
            Slide(title = SlideBox("Part two", CharacterFormat(fontSizePt = 36f, bold = true)), body = SlideBox("What comes next", CharacterFormat(fontSizePt = 20f)), layout = SlideLayout.SECTION),
            Slide(title = SlideBox("Title only", CharacterFormat(fontSizePt = 32f, bold = true)), body = SlideBox(""), layout = SlideLayout.BLANK),
            Slide(title = SlideBox(""), body = SlideBox(""), layout = SlideLayout.BLANK),
        ))
        File(out, "layouts.pptx").outputStream().use { PptxWriter.write(layoutDeck, it) }
        File(out, "stretch.docx").outputStream().use { stream ->
            DocxWriter.write(Document().apply {
                blocks.clear()
                blocks.add(ParagraphBlock(Paragraph.of("At its own shape:")))
                blocks.add(com.therealityteam.aeropages.model.ImageBlock(quad, "image/png", 240f, 180f))
                blocks.add(ParagraphBlock(Paragraph.of("Squeezed to 2:1:")))
                blocks.add(com.therealityteam.aeropages.model.ImageBlock(quad, "image/png", 300f, 150f))
            }, stream)
        }

        // Equations: displayed ones of every kind the typesetter knows, and some in the text, in docx and odt.
        run {
            fun r(t: String, plain: Boolean = false) = MathNode.Run(t, plain)
            fun row(vararg n: MathNode) = MathNode.Row(n.toList())
            fun sup(b: String, s: String) = MathNode.Scripts(r(b), null, r(s))
            val quadratic = row(r("x"), r("="), MathNode.Frac(row(r("-b±"), MathNode.Radical(row(sup("b", "2"), r("-4ac")))), r("2a")))
            val gauss = row(MathNode.Nary("∫", row(r("-"), r("∞", true)), r("∞", true), row(MathNode.Scripts(r("e", true), null, row(r("-"), sup("x", "2"))), r("dx")), under = false),
                r("="), MathNode.Radical(r("π")))
            val sum = row(MathNode.Nary("∑", row(r("k"), r("="), r("1", true)), r("n"), r("k"), under = true), r("="), MathNode.Frac(row(r("n"), MathNode.Delimited("(", ")", listOf(row(r("n"), r("+"), r("1", true))))), r("2", true)))
            val limit = row(MathNode.Func(MathNode.Limit(r("lim", true), row(r("x"), r("→"), r("0", true))), MathNode.Frac(row(MathNode.Func(r("sin", true), r("x"))), r("x"))), r("="), r("1", true))
            val matrix = row(r("A"), r("="), MathNode.Delimited("[", "]", listOf(MathNode.Matrix(listOf(listOf(r("a"), r("b")), listOf(r("c"), r("d")))))),
                r(","), MathNode.Scripts(r("A"), null, row(r("-"), r("1", true))), r("="), MathNode.Frac(r("1", true), row(r("ad"), r("-"), r("bc"))),
                MathNode.Delimited("[", "]", listOf(MathNode.Matrix(listOf(listOf(r("d"), row(r("-"), r("b"))), listOf(row(r("-"), r("c")), r("a")))))))
            val cases = row(MathNode.Func(r("f", false), MathNode.Delimited("(", ")", listOf(r("x")))), r("="), MathNode.Delimited("{", "", listOf(MathNode.Stack(listOf(
                row(r("x"), r(",", true), r(" if ", true), r("x"), r("≥"), r("0", true)), row(r("-"), r("x"), r(",", true), r(" if ", true), r("x"), r("<"), r("0", true)))))))
            val accents = row(MathNode.Accent(r("v"), "\u20D7"), r("·"), MathNode.Accent(r("x"), "\u0302"), r("+"), MathNode.Bar(r("z")), r("="), MathNode.Scripts(r("a"), r("i"), r("2")),
                r("+"), MathNode.Radical(r("8"), r("3")))
            val doc = Document().apply {
                blocks.clear()
                blocks.add(ParagraphBlock(Paragraph.of("Equations", pFormat = ParagraphFormat(style = ParagraphStyle.HEADING1))))
                blocks.add(ParagraphBlock(Paragraph.of("The quadratic formula:")))
                blocks.add(com.therealityteam.aeropages.model.EquationBlock(quadratic))
                blocks.add(ParagraphBlock(Paragraph.of("The Gaussian integral and a finite sum:")))
                blocks.add(com.therealityteam.aeropages.model.EquationBlock(gauss))
                blocks.add(com.therealityteam.aeropages.model.EquationBlock(sum))
                blocks.add(ParagraphBlock(Paragraph.of("A limit, a matrix and its inverse, a function by cases:")))
                blocks.add(com.therealityteam.aeropages.model.EquationBlock(limit))
                blocks.add(com.therealityteam.aeropages.model.EquationBlock(matrix))
                blocks.add(com.therealityteam.aeropages.model.EquationBlock(cases))
                blocks.add(com.therealityteam.aeropages.model.EquationBlock(accents))
                val inline = com.therealityteam.aeropages.model.InlineMath(row(sup("a", "2"), r("+"), sup("b", "2"), r("="), sup("c", "2")))
                val half = com.therealityteam.aeropages.model.InlineMath(MathNode.Frac(r("1", true), r("2", true)))
                blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("In a right triangle ")).apply {
                    addAll(com.therealityteam.aeropages.model.MathText.runs(inline, CharacterFormat(fontSizePt = 11f)))
                    add(TextRun(", and its area is "))
                    addAll(com.therealityteam.aeropages.model.MathText.runs(half, CharacterFormat(fontSizePt = 11f)))
                    add(TextRun(" of the rectangle's."))
                })))
            }
            File(out, "equations.docx").outputStream().use { DocxWriter.write(doc, it) }
            // The same equations on a slide: an equation text box, and one in a text box's text.
            val deckEditor = com.therealityteam.aeropages.editor.SlideEditor()
            deckEditor.load(Presentation(mutableListOf(Slide(title = SlideBox("Equations on a slide", CharacterFormat(fontSizePt = 36f, bold = true)), body = SlideBox("")))))
            deckEditor.insertEquation(quadratic)
            deckEditor.slide.shapes[0].apply { yPct = 0.3f; hPct = 0.22f }
            deckEditor.insertEquation(sum)
            deckEditor.slide.shapes[1].apply { yPct = 0.56f; hPct = 0.2f }
            val note = com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX, SlideBox(com.therealityteam.aeropages.model.Paragraph(
                mutableListOf(TextRun("In a right triangle ", CharacterFormat(fontSizePt = 20f))).apply {
                    addAll(com.therealityteam.aeropages.model.MathText.runs(com.therealityteam.aeropages.model.InlineMath(row(sup("a", "2"), r("+"), sup("b", "2"), r("="), sup("c", "2"))), CharacterFormat(fontSizePt = 20f)))
                    add(TextRun(".", CharacterFormat(fontSizePt = 20f)))
                }))).apply { xPct = 0.15f; yPct = 0.82f; wPct = 0.7f; hPct = 0.1f }
            deckEditor.slide.shapes.add(note)
            File(out, "slideeq.pptx").outputStream().use { PptxWriter.write(deckEditor.presentation, it) }
            // Outline levels: a bulleted body three levels deep (long lines wrap under their text),
            // a numbered one (1. / a. / i.), and a text box indented without bullets.
            fun body(text: String, style: com.therealityteam.aeropages.model.SlideListStyle) =
                SlideBox(com.therealityteam.aeropages.model.Paragraph(mutableListOf(TextRun(text, CharacterFormat(fontSizePt = 24f)))), style)
            val levels = Presentation(mutableListOf(
                Slide(title = SlideBox("Launch plan", CharacterFormat(fontSizePt = 36f, bold = true)), body = body(
                    "Research\n\tCustomer interviews in three regions, recorded and summarised for the whole team\n\t\tNorth\n\t\tSouth\n" +
                        "\tCompetitor review\nBuild\n\tBeta in May\nShip", com.therealityteam.aeropages.model.SlideListStyle.BULLET)),
                Slide(title = SlideBox("Steps", CharacterFormat(fontSizePt = 36f, bold = true)), body = body(
                    "Prepare\n\tGather data\n\tClean it\n\t\tDuplicates\n\t\tGaps\nAnalyse\n\tModel\nReport", com.therealityteam.aeropages.model.SlideListStyle.NUMBER)),
            ))
            levels.slides[1].shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX,
                body("Notes\n\tindented once\n\t\tand twice", com.therealityteam.aeropages.model.SlideListStyle.NONE)).apply {
                xPct = 0.6f; yPct = 0.3f; wPct = 0.35f; hPct = 0.3f; box.paragraph.format.align = com.therealityteam.aeropages.model.TextAlign.LEFT })
            // Line spacing: a body at 1.5 beside a text box at single.
            levels.slides.add(Slide(title = SlideBox("Spacing", CharacterFormat(fontSizePt = 36f, bold = true)), body = body(
                "Double-spaced text reads well from across the room\n\tone and a half lines apart\nEach line keeps its level", com.therealityteam.aeropages.model.SlideListStyle.BULLET)
                .apply { lineSpacing = 1.5f }))
            levels.slides[2].shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX,
                body("Single\nspacing\nhere", com.therealityteam.aeropages.model.SlideListStyle.NONE)).apply {
                xPct = 0.62f; yPct = 0.62f; wPct = 0.3f; hPct = 0.3f; box.paragraph.format.align = com.therealityteam.aeropages.model.TextAlign.LEFT })
            levels.slides[2].body.frame = com.therealityteam.aeropages.model.BoxFrame(0.05625f, 0.26667f, 0.52f, 0.63f)
            // Line breaks inside items (PowerPoint's Shift+Enter): the text goes on under the same bullet.
            levels.slides.add(Slide(title = SlideBox("Quarterly\u2028review", CharacterFormat(fontSizePt = 36f, bold = true)), body = body(
                "First point\u2028continues on a second line\n\tA sub point\u2028and more of it\nLast point", com.therealityteam.aeropages.model.SlideListStyle.BULLET)))
            File(out, "levels.pptx").outputStream().use { PptxWriter.write(levels, it) }
            File(out, "levels.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(levels, it) }
            File(out, "equations.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(doc, it) }
        }

        // Charts in a document: a column chart (Word's sample data) and a line chart between paragraphs.
        run {
            val line = com.therealityteam.aeropages.model.SlideChart(type = com.therealityteam.aeropages.model.ChartType.LINE, title = "Visitors",
                categories = mutableListOf("Jan", "Feb", "Mar", "Apr"),
                series = mutableListOf(com.therealityteam.aeropages.model.ChartSeries("Site", mutableListOf(120f, 180f, 150f, 240f)),
                    com.therealityteam.aeropages.model.ChartSeries("App", mutableListOf(80f, 95f, 160f, 210f))))
            val doc = Document().apply {
                blocks.clear()
                blocks.add(ParagraphBlock(Paragraph.of("Charts", pFormat = ParagraphFormat(style = ParagraphStyle.HEADING1))))
                blocks.add(ParagraphBlock(Paragraph.of("Sales by quarter:")))
                blocks.add(com.therealityteam.aeropages.model.ChartBlock(com.therealityteam.aeropages.model.SlideChart.sample(com.therealityteam.aeropages.model.ChartType.COLUMN), 432f, 252f))
                blocks.add(ParagraphBlock(Paragraph.of("Visitors by month:")))
                blocks.add(com.therealityteam.aeropages.model.ChartBlock(line, 400f, 220f))
                blocks.add(ParagraphBlock(Paragraph.of("The end.")))
            }
            File(out, "doccharts.docx").outputStream().use { DocxWriter.write(doc, it) }
            File(out, "doccharts.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(doc, it) }
        }

        // Pictures over a sheet: the four-colour picture beside a small table (6 pt into D2), and the
        // same picture cropped to its green quarter further down (D12), in xlsx and ODS.
        val picBook = com.therealityteam.aeropages.model.Workbook.blank()
        picBook.sheets[0].let { s ->
            listOf("Product", "Q1", "Q2").forEachIndexed { c, t -> s.ensure(0, c).text = t; s.ensure(0, c).format.bold = true }
            listOf(listOf("Apples", "120", "135"), listOf("Pears", "80", "95"), listOf("Plums", "45", "60")).forEachIndexed { r, row ->
                row.forEachIndexed { c, t -> s.ensure(r + 1, c).text = t }
            }
            s.images.add(com.therealityteam.aeropages.model.SheetImage(quad, "image/png", row = 1, col = 3, dxPt = 6f, dyPt = 4f, widthPt = 160f, heightPt = 120f, name = "Quarters"))
            s.images.add(com.therealityteam.aeropages.model.SheetImage(quad, "image/png", row = 11, col = 3, widthPt = 80f, heightPt = 60f, name = "Green quarter").also { it.setCrop(0.5f, 0f, 0f, 0.5f) })
        }
        File(out, "sheetpic.xlsx").outputStream().use { XlsxWriter.write(picBook, it) }
        File(out, "sheetpic.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(picBook, it) }

        // Tables: SalesData (A1:E6, Excel's Medium 2) with a calculated Amount column and a total row,
        // Staff (H1:J5, Light 9, first column and banded columns), and a Summary sheet reaching both
        // by structured references — in xlsx (table parts) and ODS (their looks and plain ranges).
        val tableBook = com.therealityteam.aeropages.model.Workbook.blank()
        tableBook.sheets[0].let { s ->
            s.name = "Sales"
            listOf("Region", "Product", "Qty", "Unit Price", "Amount").forEachIndexed { c, t -> s.ensure(0, c).text = t }
            listOf(listOf("East", "Apples", "120", "1.5"), listOf("West", "Pears", "80", "2.25"), listOf("North", "Plums", "45", "3"), listOf("South", "Apples", "60", "1.5"))
                .forEachIndexed { r, row -> row.forEachIndexed { c, t -> s.ensure(r + 1, c).text = t } }
            for (r in 1..4) s.ensure(r, 4).text = "=[@Qty]*[@[Unit Price]]"
            s.ensure(5, 0).text = "Total"
            s.ensure(5, 2).text = "=SUBTOTAL(109,[Qty])"
            s.ensure(5, 4).text = "=SUBTOTAL(109,[Amount])"
            s.tables.add(com.therealityteam.aeropages.model.SheetTable("SalesData", 0, 0, 5, 4, totalsRow = true))
            listOf("Name", "Team", "Hours").forEachIndexed { c, t -> s.ensure(0, 7 + c).text = t }
            listOf(listOf("Ada", "Ops", "38"), listOf("Lin", "Sales", "41"), listOf("Mo", "Ops", "35"), listOf("Sam", "Dev", "40"))
                .forEachIndexed { r, row -> row.forEachIndexed { c, t -> s.ensure(r + 1, 7 + c).text = t } }
            s.tables.add(com.therealityteam.aeropages.model.SheetTable("Staff", 0, 7, 4, 9, style = "TableStyleLight9", bandedCols = true, firstColumn = true))
            s.colWidths[3] = 1.3f
        }
        tableBook.sheets.add(com.therealityteam.aeropages.model.Sheet("Summary").also { s ->
            listOf(
                "Total amount" to "=SUM(SalesData[Amount])", "Apples sold" to "=SUMIFS(SalesData[Qty],SalesData[Product],\"Apples\")",
                "Ops hours" to "=SUMIFS(Staff[Hours],Staff[Team],\"Ops\")", "Regions" to "=ROWS(SalesData)", "Grand total" to "=SalesData[[#Totals],[Amount]]",
            ).forEachIndexed { r, (label, f) -> s.ensure(r, 0).text = label; s.ensure(r, 1).text = f }
            s.colWidths[0] = 1.6f
        })
        File(out, "tables.xlsx").outputStream().use { XlsxWriter.write(tableBook, it) }

        // Shapes over a sheet: a rounded callout in Office blue with white text, a ruled text box with
        // a bulleted note, a green arrow turned a little, a red line and a gold star — xlsx and ODS.
        val shapeBook = com.therealityteam.aeropages.model.Workbook.blank()
        shapeBook.sheets[0].let { s ->
            listOf("Month", "Visitors").forEachIndexed { c, t -> s.ensure(0, c).text = t; s.ensure(0, c).format.bold = true }
            listOf("Jan" to "1200", "Feb" to "1850", "Mar" to "2400").forEachIndexed { r, (m, v) -> s.ensure(r + 1, 0).text = m; s.ensure(r + 1, 1).text = v }
            fun shape(type: com.therealityteam.aeropages.model.ShapeType, text: String, fill: Long?, border: Long?, color: Long, align: com.therealityteam.aeropages.model.TextAlign = com.therealityteam.aeropages.model.TextAlign.CENTER) =
                com.therealityteam.aeropages.model.SlideShape(type, SlideBox(text, CharacterFormat(fontSizePt = 11f, color = color), align), fill = fill, border = border)
            s.shapes.add(com.therealityteam.aeropages.model.SheetShape(shape(com.therealityteam.aeropages.model.ShapeType.ROUNDED, "Record month!", 0xFF4472C4, 0xFF2F528F, 0xFFFFFFFF),
                1, 3, 6f, 2f, 110f, 44f, "Callout"))
            val note = shape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX, "Notes\n\tweb only\n\tno ads", 0xFFFFFFFF, 0xFF808080, 0xFF000000, com.therealityteam.aeropages.model.TextAlign.LEFT)
                .apply { anchor = com.therealityteam.aeropages.model.ShapeAnchor.TOP; borderWidthPt = 0.75f; box.listStyle = com.therealityteam.aeropages.model.SlideListStyle.BULLET }
            s.shapes.add(com.therealityteam.aeropages.model.SheetShape(note, 5, 0, 0f, 4f, 130f, 62f, "Notes"))
            s.shapes.add(com.therealityteam.aeropages.model.SheetShape(shape(com.therealityteam.aeropages.model.ShapeType.ARROW, "up", 0xFF70AD47, null, 0xFFFFFFFF).apply { rotationDeg = -20f },
                3, 3, 10f, 0f, 90f, 36f, "Arrow"))
            s.shapes.add(com.therealityteam.aeropages.model.SheetShape(shape(com.therealityteam.aeropages.model.ShapeType.LINE, "", null, 0xFFC00000, 0xFF000000).apply { borderWidthPt = 2f; flipV = true },
                5, 3, 0f, 10f, 100f, 40f, "Trend"))
            s.shapes.add(com.therealityteam.aeropages.model.SheetShape(shape(com.therealityteam.aeropages.model.ShapeType.STAR, "", 0xFFFFC000, 0xFFBF8F00, 0xFF000000),
                1, 6, 0f, 0f, 48f, 48f, "Star"))
        }
        File(out, "shapes.xlsx").outputStream().use { XlsxWriter.write(shapeBook, it) }
        File(out, "shapes.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(shapeBook, it) }

        // Sheet tabs: coloured tabs, a hidden sheet and a very hidden one, and a summary reading two
        // of the others by name (one quoted, one hidden).
        val tabBook = Workbook(mutableListOf(
            com.therealityteam.aeropages.model.Sheet(name = "Summary", tabColor = 0xFF0070C0),
            com.therealityteam.aeropages.model.Sheet(name = "Q1 Sales", tabColor = 0xFF00B050),
            com.therealityteam.aeropages.model.Sheet(name = "Lookup", state = com.therealityteam.aeropages.model.SheetState.HIDDEN, tabColor = 0xFFC00000),
            com.therealityteam.aeropages.model.Sheet(name = "Macros", state = com.therealityteam.aeropages.model.SheetState.VERY_HIDDEN),
        )).apply {
            val q1 = sheets[1]
            q1.ensure(0, 0).text = "Region"; q1.ensure(0, 1).text = "Sales"
            listOf("North" to "120", "South" to "90").forEachIndexed { r, (n, v) -> q1.ensure(r + 1, 0).text = n; q1.ensure(r + 1, 1).text = v }
            sheets[2].ensure(0, 0).text = "Rate"; sheets[2].ensure(0, 1).text = "0.2"
            sheets[3].ensure(0, 0).text = "kept out of sight"
            val sum = sheets[0]
            sum.ensure(0, 0).text = "Total"; sum.ensure(0, 1).text = "=SUM('Q1 Sales'!B2:B3)"
            sum.ensure(1, 0).text = "Tax"; sum.ensure(1, 1).text = "=B1*Lookup!B1"
        }
        File(out, "sheets.xlsx").outputStream().use { XlsxWriter.write(tabBook, it) }
        File(out, "sheets.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(tabBook, it) }

        // Lines: the ten arrow styles down the left, the eight dashes down the right, a gradient box
        // with a dashed rule and a thick open arrow running up — pptx, ODP, and over a sheet in xlsx / ODS.
        run {
            val arrows = listOf(
                com.therealityteam.aeropages.model.LineEnd.NONE to com.therealityteam.aeropages.model.LineEnd.NONE,
                com.therealityteam.aeropages.model.LineEnd.NONE to com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE),
                com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE) to com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE),
                com.therealityteam.aeropages.model.LineEnd.NONE to com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.ARROW),
                com.therealityteam.aeropages.model.LineEnd.NONE to com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.STEALTH, 2, 2),
                com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.OVAL) to com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE),
                com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.DIAMOND) to com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.DIAMOND),
                com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE, 0, 0) to com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE, 2, 2),
            )
            val lineSlide = Slide(title = SlideBox("Lines, arrows and dashes", CharacterFormat(fontSizePt = 32f, bold = true)), body = SlideBox(""), layout = com.therealityteam.aeropages.model.SlideLayout.BLANK)
            arrows.forEachIndexed { i, (b, e) ->
                lineSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.LINE, fill = null, border = 0xFF2F5597, borderWidthPt = 2f,
                    xPct = 0.06f, yPct = 0.2f + i * 0.09f, wPct = 0.34f, hPct = 0f, beginArrow = b, endArrow = e))
            }
            com.therealityteam.aeropages.model.LineDash.MENU.forEachIndexed { i, d ->
                lineSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.LINE, fill = null, border = 0xFF000000, borderWidthPt = 2.25f,
                    xPct = 0.48f, yPct = 0.2f + i * 0.09f, wPct = 0.22f, hPct = 0f, dash = d))
            }
            lineSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.ROUNDED, SlideBox("Gradient", CharacterFormat(fontSizePt = 16f, bold = true, color = 0xFFFFFFFF), com.therealityteam.aeropages.model.TextAlign.CENTER),
                fill = 0xFF4472C4, border = 0xFFC00000, borderWidthPt = 2f, xPct = 0.76f, yPct = 0.2f, wPct = 0.2f, hPct = 0.2f, dash = com.therealityteam.aeropages.model.LineDash.DASH).also { it.fillGradient = 0xFF70AD47; it.gradientAngle = 45f })
            lineSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.LINE, fill = null, border = 0xFFC55A11, borderWidthPt = 4.5f,
                xPct = 0.78f, yPct = 0.5f, wPct = 0.16f, hPct = 0.36f, endArrow = com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.ARROW)).also { it.flipV = true })
            val lineDeck = Presentation(mutableListOf(lineSlide))
            File(out, "lines.pptx").outputStream().use { PptxWriter.write(lineDeck, it) }
            File(out, "lines.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(lineDeck, it) }

            // Freeforms: a gold star with its name, a red heart of curves (mirrored), a ring with its hole,
            // an open blue wave, a turned green flag — pptx, ODP, and over a sheet in xlsx / ODS.
            fun outline(vararg ops: Freeform.Op, filled: Boolean = true) = Freeform.Part(ops.toList(), filled)
            val star = Freeform(listOf(outline(Freeform.Move(0.5f, 0f), Freeform.Line(0.62f, 0.36f), Freeform.Line(1f, 0.38f), Freeform.Line(0.7f, 0.61f), Freeform.Line(0.81f, 1f),
                Freeform.Line(0.5f, 0.77f), Freeform.Line(0.19f, 1f), Freeform.Line(0.3f, 0.61f), Freeform.Line(0f, 0.38f), Freeform.Line(0.38f, 0.36f), Freeform.Close)))
            val heart = Freeform(listOf(outline(Freeform.Move(0.5f, 0.25f), Freeform.Cubic(0.5f, 0f, 0f, 0f, 0f, 0.3f), Freeform.Cubic(0f, 0.6f, 0.35f, 0.75f, 0.5f, 1f),
                Freeform.Cubic(0.65f, 0.75f, 1f, 0.6f, 1f, 0.3f), Freeform.Cubic(1f, 0f, 0.5f, 0f, 0.5f, 0.25f), Freeform.Close)))
            val k = 0.5523f
            fun circle(cx: Float, cy: Float, r: Float) = listOf(Freeform.Move(cx + r, cy), Freeform.Cubic(cx + r, cy + k * r, cx + k * r, cy + r, cx, cy + r),
                Freeform.Cubic(cx - k * r, cy + r, cx - r, cy + k * r, cx - r, cy), Freeform.Cubic(cx - r, cy - k * r, cx - k * r, cy - r, cx, cy - r),
                Freeform.Cubic(cx + k * r, cy - r, cx + r, cy - k * r, cx + r, cy), Freeform.Close)
            val ring = Freeform(listOf(Freeform.Part(circle(0.5f, 0.5f, 0.5f) + circle(0.5f, 0.5f, 0.28f))))
            val wave = Freeform(listOf(outline(Freeform.Move(0f, 0.5f), Freeform.Cubic(0.17f, 0f, 0.33f, 0f, 0.5f, 0.5f), Freeform.Cubic(0.67f, 1f, 0.83f, 1f, 1f, 0.5f), filled = false)))
            val flag = Freeform(listOf(outline(Freeform.Move(0f, 0f), Freeform.Line(1f, 0.15f), Freeform.Line(0.55f, 0.35f), Freeform.Line(1f, 0.55f), Freeform.Line(0f, 0.7f), Freeform.Close),
                outline(Freeform.Move(0f, 0f), Freeform.Line(0f, 1f), filled = false)))
            fun free(path: Freeform, text: String, fill: Long?, border: Long?, x: Float, y: Float, w: Float, h: Float) =
                com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.FREEFORM, SlideBox(text, CharacterFormat(fontSizePt = 18f, bold = true, color = 0xFF3A2A00), com.therealityteam.aeropages.model.TextAlign.CENTER),
                    x, y, w, h, fill = fill, border = border, borderWidthPt = 2f, path = path)
            val freeSlide = Slide(title = SlideBox("Freeforms", CharacterFormat(fontSizePt = 32f, bold = true)), body = SlideBox(""), layout = com.therealityteam.aeropages.model.SlideLayout.BLANK)
            freeSlide.shapes.add(free(star, "Star", 0xFFFFC000, 0xFFBF8F00, 0.05f, 0.25f, 0.24f, 0.42f))
            freeSlide.shapes.add(free(heart, "", 0xFFE84C3D, 0xFF9E2A1E, 0.34f, 0.25f, 0.2f, 0.34f).also { it.flipH = true })
            freeSlide.shapes.add(free(ring, "", 0xFF2E75B6, null, 0.6f, 0.25f, 0.16f, 0.28f))
            freeSlide.shapes.add(free(wave, "", null, 0xFF2E75B6, 0.08f, 0.76f, 0.5f, 0.14f).also { it.borderWidthPt = 4f })
            freeSlide.shapes.add(free(flag, "", 0xFF70AD47, 0xFF375623, 0.8f, 0.3f, 0.12f, 0.4f).also { it.rotationDeg = 12f })
            val freeDeck = Presentation(mutableListOf(freeSlide))
            File(out, "freeform.pptx").outputStream().use { PptxWriter.write(freeDeck, it) }
            File(out, "freeform.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(freeDeck, it) }
            val freeBook = com.therealityteam.aeropages.model.Workbook.blank()
            freeBook.sheets[0].ensure(0, 0).text = "Freeforms over cells"
            freeBook.sheets[0].shapes.add(com.therealityteam.aeropages.model.SheetShape(free(star, "", 0xFFFFC000, 0xFFBF8F00, 0f, 0f, 1f, 1f), 2, 1, 0f, 0f, 90f, 86f, "Star"))
            freeBook.sheets[0].shapes.add(com.therealityteam.aeropages.model.SheetShape(free(heart, "", 0xFFE84C3D, null, 0f, 0f, 1f, 1f), 2, 4, 0f, 0f, 80f, 70f, "Heart"))
            File(out, "freeform.xlsx").outputStream().use { XlsxWriter.write(freeBook, it) }
            File(out, "freeform.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(freeBook, it) }

            // SVG pictures: a badge (a gradient, a ring, text) and an icon with a clip — on a slide, a page and a sheet.
            val badge = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 120" width="200" height="120">
                <defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4f8fe0"/><stop offset="1" stop-color="#1c4f9c"/></linearGradient>
                <clipPath id="c"><circle cx="160" cy="60" r="30"/></clipPath></defs>
                <rect x="4" y="4" width="192" height="112" rx="18" fill="url(#g)" stroke="#0d2c5e" stroke-width="3"/>
                <circle cx="160" cy="60" r="34" fill="none" stroke="#ffd34d" stroke-width="6" stroke-dasharray="12 6"/>
                <g clip-path="url(#c)"><rect x="130" y="30" width="30" height="60" fill="#ffd34d"/><rect x="160" y="30" width="30" height="60" fill="#ff8a3d"/></g>
                <text x="70" y="70" text-anchor="middle" font-family="sans-serif" font-weight="bold" font-size="28" fill="#ffffff">SVG</text>
                </svg>""".trimIndent().toByteArray()
            val svgDeck = Presentation(mutableListOf(Slide(title = SlideBox("An SVG picture", CharacterFormat(fontSizePt = 32f, bold = true)), body = SlideBox(""),
                layout = com.therealityteam.aeropages.model.SlideLayout.BLANK).also { it.images.add(com.therealityteam.aeropages.model.SlideImage(badge, "image/svg+xml", 200f, 120f, 0.25f, 0.3f, 0.5f, 0.5f)) }))
            File(out, "svgpicture.pptx").outputStream().use { PptxWriter.write(svgDeck, it) }
            File(out, "svgpicture.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(svgDeck, it) }
            val svgDoc = Document(mutableListOf(ParagraphBlock(Paragraph.of("An SVG picture on a page:")), com.therealityteam.aeropages.model.ImageBlock(badge, "image/svg+xml", 240f, 144f)))
            File(out, "svgpicture.docx").outputStream().use { DocxWriter.write(svgDoc, it) }
            File(out, "svgpicture.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(svgDoc, it) }
            val svgBook = com.therealityteam.aeropages.model.Workbook.blank()
            svgBook.sheets[0].ensure(0, 0).text = "An SVG picture over the cells"
            svgBook.sheets[0].images.add(com.therealityteam.aeropages.model.SheetImage(badge, "image/svg+xml", 2, 1, 0f, 0f, 200f, 120f, "Badge"))
            File(out, "svgpicture.xlsx").outputStream().use { XlsxWriter.write(svgBook, it) }
            File(out, "svgpicture.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(svgBook, it) }

            // Gradients every way they run: linear, axial, radial from the centre and from a corner, rectangular.
            val gradSlide = Slide(title = SlideBox("Gradients", CharacterFormat(fontSizePt = 32f, bold = true)), body = SlideBox(""), layout = com.therealityteam.aeropages.model.SlideLayout.BLANK)
            listOf(
                Triple(com.therealityteam.aeropages.model.GradientKind.LINEAR, 0.5f to 0.5f, "Linear"),
                Triple(com.therealityteam.aeropages.model.GradientKind.AXIAL, 0.5f to 0.5f, "Axial"),
                Triple(com.therealityteam.aeropages.model.GradientKind.RADIAL, 0.5f to 0.5f, "Radial"),
                Triple(com.therealityteam.aeropages.model.GradientKind.RADIAL, 0f to 0f, "From a corner"),
                Triple(com.therealityteam.aeropages.model.GradientKind.RECTANGULAR, 0.5f to 0.5f, "Rectangular"),
            ).forEachIndexed { k, (kind, c, label) ->
                gradSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.RECT,
                    SlideBox(label, CharacterFormat(fontSizePt = 14f, bold = true, color = 0xFF1F1F1F), com.therealityteam.aeropages.model.TextAlign.CENTER),
                    0.04f + k * 0.19f, 0.32f, 0.17f, 0.4f, fill = 0xFFFFF2CC, border = null, fillGradient = 0xFFC55A11, gradientAngle = 0f,
                    gradientKind = kind, gradientCx = c.first, gradientCy = c.second))
            }
            // See-through fills: a half-clear red disc over a blue bar, and purple fading out to the right.
            gradSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.RECT, SlideBox(""), 0.04f, 0.78f, 0.5f, 0.12f,
                fill = 0xFF2E75B6, border = null))
            gradSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.ELLIPSE,
                SlideBox("50%", CharacterFormat(fontSizePt = 14f, bold = true, color = 0xFF1F1F1F), com.therealityteam.aeropages.model.TextAlign.CENTER),
                0.3f, 0.74f, 0.14f, 0.2f, fill = 0x80C00000, border = null))
            gradSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.RECT,
                SlideBox("Fading", CharacterFormat(fontSizePt = 14f, bold = true, color = 0xFF1F1F1F), com.therealityteam.aeropages.model.TextAlign.CENTER),
                0.6f, 0.78f, 0.34f, 0.12f, fill = 0xFF7030A0, border = null, fillGradient = 0x007030A0, gradientAngle = 0f))
            // Shadows: PowerPoint's four presets on filled shapes, a turned one, a hard grey one, and an outline-only ring.
            val shadowSlide = Slide(title = SlideBox("Shadows", CharacterFormat(fontSizePt = 32f, bold = true)), body = SlideBox(""), layout = com.therealityteam.aeropages.model.SlideLayout.BLANK)
            val shadows = com.therealityteam.aeropages.model.ShapeShadow.PRESETS + com.therealityteam.aeropages.model.ShapeShadow(0xFF7F7F7F, 6f, 6f, 0f)
            shadows.forEachIndexed { k, sh ->
                shadowSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(if (k == 3) com.therealityteam.aeropages.model.ShapeType.ELLIPSE else com.therealityteam.aeropages.model.ShapeType.ROUNDED,
                    SlideBox("${k + 1}", CharacterFormat(fontSizePt = 18f, bold = true, color = 0xFFFFFFFF), com.therealityteam.aeropages.model.TextAlign.CENTER),
                    0.05f + k * 0.18f, 0.3f, 0.13f, 0.22f, fill = 0xFF4472C4, border = null, shadow = sh).also { if (k == 4) it.rotationDeg = 20f })
            }
            shadowSlide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.ELLIPSE, SlideBox(""), 0.4f, 0.64f, 0.14f, 0.24f,
                fill = null, border = 0xFFC00000, borderWidthPt = 6f, shadow = com.therealityteam.aeropages.model.ShapeShadow.PRESETS[0]))
            // Pictures cast them too: an opaque one its frame's, an icon on a clear ground its own round one.
            fun disc(n: Int, rgb: Int): ByteArray {
                val raw = java.io.ByteArrayOutputStream()
                for (y in 0 until n) {
                    raw.write(0)
                    for (x in 0 until n) {
                        val dx = x - n / 2 + 0.5f; val dy = y - n / 2 + 0.5f
                        val inside = dx * dx + dy * dy <= (n / 2f - 1) * (n / 2f - 1)
                        raw.write((rgb shr 16) and 0xFF); raw.write((rgb shr 8) and 0xFF); raw.write(rgb and 0xFF); raw.write(if (inside) 255 else 0)
                    }
                }
                val deflater = java.util.zip.Deflater().also { it.setInput(raw.toByteArray()); it.finish() }
                val zipped = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192)
                while (!deflater.finished()) zipped.write(buf, 0, deflater.deflate(buf))
                fun chunk(type: String, data: ByteArray): ByteArray {
                    val out = java.io.ByteArrayOutputStream(); val dos = java.io.DataOutputStream(out)
                    dos.writeInt(data.size); dos.write(type.toByteArray(Charsets.US_ASCII)); dos.write(data)
                    dos.writeInt(java.util.zip.CRC32().also { it.update(type.toByteArray(Charsets.US_ASCII)); it.update(data) }.value.toInt())
                    return out.toByteArray()
                }
                val ihdr = java.io.ByteArrayOutputStream().also { val d = java.io.DataOutputStream(it); d.writeInt(n); d.writeInt(n); d.write(8); d.write(6); d.write(0); d.write(0); d.write(0) }.toByteArray()
                return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + chunk("IHDR", ihdr) + chunk("IDAT", zipped.toByteArray()) + chunk("IEND", ByteArray(0))
            }
            shadowSlide.images.add(com.therealityteam.aeropages.model.SlideImage(pngOf(160, 100, 0x217346), "image/png", 160f, 100f, 0.62f, 0.62f, 0.16f, 0.18f,
                shadow = com.therealityteam.aeropages.model.ShapeShadow.PRESETS[0]))
            shadowSlide.images.add(com.therealityteam.aeropages.model.SlideImage(disc(96, 0xED7D31), "image/png", 96f, 96f, 0.82f, 0.62f, 0.1f, 0.1778f,
                shadow = com.therealityteam.aeropages.model.ShapeShadow(0x80000000, 4f, 4f, 3f)))
            val shadowDeck = Presentation(mutableListOf(shadowSlide))
            // Pictures with shadows on a page and over a sheet.
            val shadowDoc = Document(mutableListOf(ParagraphBlock(Paragraph.of("A photo with its shadow, and an icon casting its own:")),
                com.therealityteam.aeropages.model.ImageBlock(pngOf(240, 150, 0x2B579A), "image/png", 240f, 150f, shadow = com.therealityteam.aeropages.model.ShapeShadow.PRESETS[0]),
                com.therealityteam.aeropages.model.ImageBlock(disc(96, 0xED7D31), "image/png", 72f, 72f, shadow = com.therealityteam.aeropages.model.ShapeShadow(0x80000000, 4f, 4f, 3f))))
            File(out, "picshadow.docx").outputStream().use { DocxWriter.write(shadowDoc, it) }
            File(out, "picshadow.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(shadowDoc, it) }
            val shadowBook = com.therealityteam.aeropages.model.Workbook.blank()
            shadowBook.sheets[0].ensure(0, 0).text = "Pictures with shadows"
            shadowBook.sheets[0].images.add(com.therealityteam.aeropages.model.SheetImage(pngOf(160, 100, 0x217346), "image/png", 2, 1, 0f, 0f, 160f, 100f, "Photo").also { it.shadow = com.therealityteam.aeropages.model.ShapeShadow.PRESETS[0] })
            shadowBook.sheets[0].images.add(com.therealityteam.aeropages.model.SheetImage(disc(96, 0xED7D31), "image/png", 2, 5, 0f, 0f, 72f, 72f, "Icon").also { it.shadow = com.therealityteam.aeropages.model.ShapeShadow(0x80000000, 4f, 4f, 3f) })
            File(out, "picshadow.xlsx").outputStream().use { XlsxWriter.write(shadowBook, it) }
            File(out, "picshadow.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(shadowBook, it) }
            File(out, "shadows.pptx").outputStream().use { PptxWriter.write(shadowDeck, it) }
            File(out, "shadows.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(shadowDeck, it) }

            // Video and sound: a clip shown by its poster frame, a sound by its icon, and a video linked from elsewhere.
            // (The clip's bytes are a stand-in: what is checked is that the file keeps them and their place.)
            val clip = "AeroDesk sample clip".toByteArray()
            fun mediaSlide(title: String, picture: com.therealityteam.aeropages.model.SlideImage) =
                com.therealityteam.aeropages.model.Slide(title = com.therealityteam.aeropages.model.SlideBox(title, CharacterFormat(fontSizePt = 32f, bold = true)),
                    layout = com.therealityteam.aeropages.model.SlideLayout.BLANK).also { it.images.add(picture) }
            val mediaDeck = Presentation(mutableListOf(
                mediaSlide("A video", com.therealityteam.aeropages.model.SlideImage(pngOf(320, 180, 0x1F4E79), "image/png", 320f, 180f, 0.2f, 0.3f, 0.6f, 0.6f,
                    media = com.therealityteam.aeropages.model.SlideMedia(com.therealityteam.aeropages.model.SlideMedia.Kind.VIDEO, "video/mp4", clip, null, "clip.mp4"))),
                mediaSlide("A sound", com.therealityteam.aeropages.model.SlideImage(pngOf(64, 64, 0x404040), "image/png", 64f, 64f, 0.45f, 0.45f, 0.1f, 0.1778f,
                    media = com.therealityteam.aeropages.model.SlideMedia(com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO, "audio/mpeg", "AeroDesk sample sound".toByteArray(), null, "chime.mp3"))),
                mediaSlide("A linked video", com.therealityteam.aeropages.model.SlideImage(pngOf(320, 180, 0x7030A0), "image/png", 320f, 180f, 0.2f, 0.3f, 0.6f, 0.6f,
                    media = com.therealityteam.aeropages.model.SlideMedia(com.therealityteam.aeropages.model.SlideMedia.Kind.VIDEO, "video/mp4", null, "https://example.com/talk.mp4", "talk.mp4"))),
            ))
            File(out, "media.pptx").outputStream().use { PptxWriter.write(mediaDeck, it) }
            File(out, "media.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(mediaDeck, it) }
            // Words to proof: typos in a title, a body, a shape and the notes, and a word written twice.
            val typos = Presentation(mutableListOf(
                Slide(title = SlideBox("Teh launch plan", CharacterFormat(fontSizePt = 40f, bold = true)), body = SlideBox("We will recieve the the report on Monday\nEveryone shoud read it")).also { s ->
                    s.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX, xPct = 0.6f, yPct = 0.75f, wPct = 0.3f, hPct = 0.1f).also { it.box.paragraph.runs.clear(); it.box.paragraph.runs.add(TextRun("Draft  verison", CharacterFormat(fontSizePt = 18f))) })
                    s.notes = "Remmber to thank the team"
                },
            ))
            File(out, "proofing.pptx").outputStream().use { PptxWriter.write(typos, it) }
            val typoBook = Workbook.blank().also { wb ->
                val sh = wb.sheets[0]
                sh.ensure(0, 0).text = "Quartrly results"; sh.ensure(0, 1).text = "Revenu"; sh.ensure(0, 2).text = "Costs"
                sh.ensure(1, 0).text = "North"; sh.ensure(1, 1).text = "1250"; sh.ensure(1, 2).text = "900"
                sh.ensure(2, 0).text = "Soth"; sh.ensure(2, 1).text = "1410"; sh.ensure(2, 2).text = "1020"
                sh.ensure(4, 0).text = "Figures are are provisional"
            }
            File(out, "proofing.xlsx").outputStream().use { XlsxWriter.write(typoBook, it) }
            // Clips that play (the instrumentation tests' own, from tools/make-test-media.sh): a video and a sound to try in the show.
            val clips = File("src/androidTest/assets/media")
            if (File(clips, "clip.mp4").isFile && File(clips, "tone.m4a").isFile && File(clips, "poster.png").isFile) {
                val playback = Presentation(mutableListOf(
                    mediaSlide("Tap the video to play it", com.therealityteam.aeropages.model.SlideImage(File(clips, "poster.png").readBytes(), "image/png", 640f, 360f, 0.2f, 0.25f, 0.6f, 0.6f,
                        media = com.therealityteam.aeropages.model.SlideMedia(com.therealityteam.aeropages.model.SlideMedia.Kind.VIDEO, "video/mp4", File(clips, "clip.mp4").readBytes(), null, "clip.mp4"))),
                    mediaSlide("Tap the speaker to hear it", com.therealityteam.aeropages.model.SlideImage(pngOf(64, 64, 0x404040), "image/png", 64f, 64f, 0.45f, 0.45f, 0.1f, 0.1778f,
                        media = com.therealityteam.aeropages.model.SlideMedia(com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO, "audio/mp4", File(clips, "tone.m4a").readBytes(), null, "tone.m4a"))),
                ))
                File(out, "playback.pptx").outputStream().use { PptxWriter.write(playback, it) }
                File(out, "playback.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(playback, it) }
            }
            // EMF and WMF pictures (a drawing LibreOffice exported as each): drawn by the app's own metafile player.
            val emf = javaClass.classLoader?.getResourceAsStream("metafile/drawing.emf")?.readBytes()
            val wmf = javaClass.classLoader?.getResourceAsStream("metafile/drawing.wmf")?.readBytes()
            if (emf != null && wmf != null) {
                fun metaSlide(title: String, data: ByteArray, mime: String) = Slide(title = SlideBox(title, CharacterFormat(fontSizePt = 24f, bold = true)), body = SlideBox(""),
                    layout = com.therealityteam.aeropages.model.SlideLayout.BLANK).also { it.images += com.therealityteam.aeropages.model.SlideImage(data, mime, 279f, 204f, 0.15f, 0.2f, 0.7f, 0.7f) }
                val metaDeck = Presentation(mutableListOf(metaSlide("EMF", emf, "image/x-emf"), metaSlide("WMF", wmf, "image/x-wmf")))
                File(out, "metafiles.pptx").outputStream().use { PptxWriter.write(metaDeck, it) }
                File(out, "metafiles.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(metaDeck, it) }
            }
            // DrawingML's presets on four slides, each drawn from its definition (and kept as its preset in pptx).
            val presetNames = com.therealityteam.aeropages.format.pptx.PresetGeometries.names.sorted()
            val presetSlides = presetNames.chunked(28).mapIndexed { page, names ->
                Slide(title = SlideBox("Presets ${page + 1}", CharacterFormat(fontSizePt = 20f, bold = true)), body = SlideBox(""), layout = com.therealityteam.aeropages.model.SlideLayout.BLANK).also { slide ->
                    names.forEachIndexed { k, name ->
                        val col = k % 7; val row = k / 7
                        val w = 0.1f; val h = 0.14f
                        val path = com.therealityteam.aeropages.format.pptx.PresetGeometries.freeform(name, null, (w * 12192000).toDouble(), (h * 6858000).toDouble())
                        slide.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.FREEFORM,
                            SlideBox(name, CharacterFormat(fontSizePt = 7f, color = 0xFF1F1F1F), com.therealityteam.aeropages.model.TextAlign.CENTER),
                            0.03f + col * 0.137f, 0.16f + row * 0.21f, w, h, fill = 0xFFBDD7EE, border = 0xFF2E75B6, borderWidthPt = 1f, path = path,
                            preset = name))
                    }
                }
            }
            File(out, "presets.pptx").outputStream().use { PptxWriter.write(Presentation(presetSlides.toMutableList()), it) }
            // A background of its own shading navy to sky blue down the slide, and one shading out from the centre.
            val shadedSlide = Slide(title = SlideBox("Background gradient", CharacterFormat(fontSizePt = 32f, bold = true, color = 0xFFFFFFFF)), body = SlideBox(""),
                layout = com.therealityteam.aeropages.model.SlideLayout.BLANK, background = 0xFF1F3864).also { it.backgroundShade = com.therealityteam.aeropages.model.BackgroundShade(0xFF9DC3E6, 90f) }
            val radialSlide = Slide(title = SlideBox("Radial background", CharacterFormat(fontSizePt = 32f, bold = true)), body = SlideBox(""),
                layout = com.therealityteam.aeropages.model.SlideLayout.BLANK, background = 0xFFFFFFFF).also {
                    it.backgroundShade = com.therealityteam.aeropages.model.BackgroundShade(0xFFF4B183, 0f, com.therealityteam.aeropages.model.GradientKind.RADIAL)
                }
            val gradDeck = Presentation(mutableListOf(gradSlide, shadedSlide, radialSlide))
            File(out, "gradients.pptx").outputStream().use { PptxWriter.write(gradDeck, it) }
            File(out, "gradients.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(gradDeck, it) }

            // A 4:3 deck (PowerPoint's Standard): a title, bullets, and a box in the lower-right quarter
            // with a line to its corner — its frames are fractions of the 10-inch slide.
            val standard = Presentation(mutableListOf(
                Slide(title = SlideBox("Standard 4:3 slide", CharacterFormat(fontSizePt = 40f, bold = true), com.therealityteam.aeropages.model.TextAlign.CENTER),
                    body = SlideBox("Ten inches wide\nText in points\nShapes where they were")).also { sl ->
                    sl.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.ROUNDED,
                        SlideBox("Lower right", CharacterFormat(fontSizePt = 20f, color = 0xFFFFFFFF), com.therealityteam.aeropages.model.TextAlign.CENTER),
                        xPct = 0.5f, yPct = 0.6f, wPct = 0.45f, hPct = 0.3f, fill = 0xFF4472C4, border = null))
                    sl.shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.LINE, fill = null, border = 0xFFC00000, borderWidthPt = 3f,
                        xPct = 0.05f, yPct = 0.95f, wPct = 0.45f, hPct = 0f, endArrow = com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE)))
                },
            ), slideSize = com.therealityteam.aeropages.model.SlideSize.STANDARD)
            File(out, "standard.pptx").outputStream().use { PptxWriter.write(standard, it) }

            // A document with drawn shapes in its flow: a rounded box, an arrow down, a gradient oval
            // with its text at the foot, a star — docx and ODT.
            val shapeDoc = com.therealityteam.aeropages.model.Document().also { d ->
                d.blocks.add(com.therealityteam.aeropages.model.ParagraphBlock(com.therealityteam.aeropages.model.Paragraph.of("Order flow")))
                fun p(t: String, color: Long) = com.therealityteam.aeropages.model.Paragraph.of(t).also { it.runs.forEach { r -> r.format.color = color; r.format.fontSizePt = 14f } }
                d.blocks.add(com.therealityteam.aeropages.model.TextBoxBlock(mutableListOf(p("Order received", 0xFFFFFFFF)), 180f, 60f,
                    com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.ROUNDED, fill = 0xFF4472C4, border = 0xFF2F528F, borderWidthPt = 1.5f,
                        anchor = com.therealityteam.aeropages.model.ShapeAnchor.CENTER)))
                d.blocks.add(com.therealityteam.aeropages.model.TextBoxBlock(mutableListOf(), 0f.coerceAtLeast(1f), 40f,
                    com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.LINE, fill = null, border = 0xFF404040, borderWidthPt = 2f,
                        endArrow = com.therealityteam.aeropages.model.LineEnd(com.therealityteam.aeropages.model.ArrowHead.TRIANGLE))))
                d.blocks.add(com.therealityteam.aeropages.model.TextBoxBlock(mutableListOf(p("In stock?", 0xFF1A1A1A)), 200f, 90f,
                    com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.ELLIPSE, fill = 0xFFFFE699, border = 0xFFBF9000, borderWidthPt = 1f,
                        dash = com.therealityteam.aeropages.model.LineDash.DASH, anchor = com.therealityteam.aeropages.model.ShapeAnchor.CENTER).also { it.fillGradient = 0xFFFFC000; it.gradientAngle = 90f }))
                d.blocks.add(com.therealityteam.aeropages.model.TextBoxBlock(mutableListOf(), 72f, 72f,
                    com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.STAR, fill = 0xFFC00000, border = null)))
                d.blocks.add(com.therealityteam.aeropages.model.ParagraphBlock(com.therealityteam.aeropages.model.Paragraph.of("Shapes keep their look in Word, LibreOffice and the PDF.")))
            }
            File(out, "shapes.docx").outputStream().use { com.therealityteam.aeropages.format.docx.DocxWriter.write(shapeDoc, it) }
            File(out, "shapes.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(shapeDoc, it) }
            File(out, "standard.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(standard, it) }

            val lineBook = com.therealityteam.aeropages.model.Workbook.blank()
            val s = lineBook.sheets[0]
            listOf("Step", "Owner").forEachIndexed { c, t -> s.ensure(0, c).text = t; s.ensure(0, c).format.bold = true }
            listOf("Plan" to "Ada", "Build" to "Lin", "Ship" to "Mo").forEachIndexed { r, (a, b) -> s.ensure(r + 1, 0).text = a; s.ensure(r + 1, 1).text = b }
            arrows.take(5).forEachIndexed { i, (b, e) ->
                s.shapes.add(com.therealityteam.aeropages.model.SheetShape(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.LINE, fill = null,
                    border = 0xFF4472C4, borderWidthPt = 1.5f, beginArrow = b, endArrow = e), 1 + i * 2, 3, 0f, 7f, 120f, 0f, "Arrow ${i + 1}"))
            }
            listOf(com.therealityteam.aeropages.model.LineDash.SYS_DOT, com.therealityteam.aeropages.model.LineDash.DASH, com.therealityteam.aeropages.model.LineDash.LONG_DASH_DOT).forEachIndexed { i, d ->
                s.shapes.add(com.therealityteam.aeropages.model.SheetShape(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.RECT,
                    SlideBox(d.ooxml, CharacterFormat(fontSizePt = 11f, color = 0xFF000000), com.therealityteam.aeropages.model.TextAlign.CENTER),
                    fill = 0xFFDDEBF7, border = 0xFF2F5597, borderWidthPt = 2f, dash = d), 1 + i * 3, 6, 0f, 0f, 96f, 40f, "Box ${i + 1}"))
            }
            File(out, "lines.xlsx").outputStream().use { XlsxWriter.write(lineBook, it) }
            File(out, "lines.ods").outputStream().use { com.therealityteam.aeropages.format.odf.OdsWriter.write(lineBook, it) }
        }
        File(out, "tables.ods").outputStream().use { o ->
            com.therealityteam.aeropages.format.odf.OdsWriter.write(tableBook, o) { si, r, c -> com.therealityteam.aeropages.editor.FormulaEngine.cached(tableBook.sheets[si], r, c, tableBook.sheets) }
        }

        val deck = Presentation(mutableListOf(
            Slide(title = SlideBox("Release smoke test", CharacterFormat(fontSizePt = 40f, bold = true)), body = SlideBox("Minified build\nOpened from a file intent")),
            Slide(title = SlideBox("Second slide"), body = SlideBox("Bullet one\nBullet two")),
        ))
        File(out, "smoke.pptx").outputStream().use { PptxWriter.write(deck, it) }

        // Sections and the wider transition set.
        val sectionedDeck = Presentation(mutableListOf(
            Slide(title = SlideBox("Welcome", CharacterFormat(fontSizePt = 40f, bold = true)), body = SlideBox("Kick-off")).apply { sectionName = "Introduction"; transition = com.therealityteam.aeropages.model.SlideTransition.WIPE },
            Slide(title = SlideBox("Agenda"), body = SlideBox("Three topics")).apply { transition = com.therealityteam.aeropages.model.SlideTransition.SPLIT },
            Slide(title = SlideBox("Results"), body = SlideBox("Up and to the right")).apply { sectionName = "Findings"; transition = com.therealityteam.aeropages.model.SlideTransition.DISSOLVE; transitionSpeed = com.therealityteam.aeropages.model.TransitionSpeed.SLOW },
            Slide(title = SlideBox("Thanks"), body = SlideBox("Questions?")).apply { sectionName = "Wrap-up"; transition = com.therealityteam.aeropages.model.SlideTransition.WHEEL },
        ))
        File(out, "sections.pptx").outputStream().use { PptxWriter.write(sectionedDeck, it) }

        // The OpenDocument side of the same three documents.
        File(out, "smoke.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(doc, it) }
        File(out, "smoke.ods").outputStream().use { o ->
            com.therealityteam.aeropages.format.odf.OdsWriter.write(wb, o) { si, r, c ->
                com.therealityteam.aeropages.editor.FormulaEngine.cached(wb.sheets[si], r, c, wb.sheets, wb.names)
            }
        }
        File(out, "smoke.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(deck, it) }

        // Header & footer: today's date, a footer and slide numbers — not on the title slide — and a dark slide.
        val hfDeck = Presentation(mutableListOf(
            Slide(title = SlideBox("Quarterly review", CharacterFormat(fontSizePt = 40f, bold = true)), body = SlideBox("Header & footer"), layout = com.therealityteam.aeropages.model.SlideLayout.TITLE),
            Slide(title = SlideBox("Agenda"), body = SlideBox("Results\nPlans")),
            Slide(title = SlideBox("On a dark slide"), body = SlideBox("The footer reads in white"), background = 0xFF1F2A44),
        ), footer = "AeroDesk · 季度汇报", showSlideNumber = true, date = com.therealityteam.aeropages.model.SlideDate("yyyy\"年\"m\"月\"d\"日\""), hideFooterOnTitle = true)
        File(out, "headerfooter.pptx").outputStream().use { PptxWriter.write(hfDeck, it) }
        File(out, "headerfooter.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(hfDeck, it) }

        // Automatic text colour: dark slides' text without a colour of its own reads light; a pale box keeps dark text.
        val autoDeck = Presentation(mutableListOf(
            Slide(title = SlideBox("Light slide"), body = SlideBox("Dark text, as always")),
            Slide(title = SlideBox("Dark slide"), body = SlideBox("Text reads light here\nWithout a colour of its own"), background = 0xFF1F2A44).apply {
                shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.ROUNDED, SlideBox("Pale box"), xPct = 0.55f, yPct = 0.62f, wPct = 0.18f, hPct = 0.16f, fill = 0xFFDDE6F2))
                shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.ROUNDED, SlideBox("Deep box"), xPct = 0.76f, yPct = 0.62f, wPct = 0.18f, hPct = 0.16f, fill = 0xFF7A1F2B, border = null))
                shapes.add(com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.TEXTBOX, SlideBox("A loose text box"), xPct = 0.06f, yPct = 0.66f, wPct = 0.4f, hPct = 0.1f, fill = null, border = null))
            },
        ))
        autoDeck.master.titleColor = 0xFF203864
        File(out, "autocolor.pptx").outputStream().use { PptxWriter.write(autoDeck, it) }
        File(out, "autocolor.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(autoDeck, it) }

        // Master text styles: the deck's title and body look, and a design's own title colour over its band.
        val bandDesign = com.therealityteam.aeropages.model.SlideDesign("Band", items = mutableListOf(com.therealityteam.aeropages.model.DesignShape(
            com.therealityteam.aeropages.model.SlideShape(com.therealityteam.aeropages.model.ShapeType.RECT, xPct = 0f, yPct = 0f, wPct = 1f, hPct = 0.24f, fill = 0xFF7A1F2B, border = null))),
            titleText = com.therealityteam.aeropages.model.MasterText(null, null, 0xFFFFE699, null))
        val masterTextDeck = Presentation(mutableListOf(
            Slide(title = SlideBox("Georgia, dark blue, bold", CharacterFormat(fontSizePt = 36f)), body = SlideBox("Body text in Verdana\nFrom the master", CharacterFormat(fontSizePt = 20f))),
            Slide(title = SlideBox("A design's own title colour", CharacterFormat(fontSizePt = 36f)), body = SlideBox("Over its band", CharacterFormat(fontSizePt = 20f))).apply { design = "Band" },
        ), designs = mutableListOf(bandDesign))
        masterTextDeck.master.apply { titleFont = "Georgia"; titleSizePt = 36f; titleColor = 0xFF203864; titleBold = true; bodyFont = "Verdana"; bodySizePt = 20f; bodyColor = 0xFF404040 }
        File(out, "mastertext.pptx").outputStream().use { PptxWriter.write(masterTextDeck, it) }
        File(out, "mastertext.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(masterTextDeck, it) }

        // Pictures turned and mirrored: a red | blue picture as it is, flipped each way, turned 30°, and a quarter left and flipped.
        fun halves(w: Int, h: Int): ByteArray {
            val raw = java.io.ByteArrayOutputStream()
            for (y in 0 until h) { raw.write(0); for (x in 0 until w) { val red = x < w / 2; val top = y < h / 3; raw.write(if (red) 220 else 40); raw.write(if (top) 200 else if (red) 40 else 80); raw.write(if (red) 40 else 220) } }
            fun chunk(type: String, data: ByteArray): ByteArray {
                val o = java.io.ByteArrayOutputStream(); val d = java.io.DataOutputStream(o)
                d.writeInt(data.size); d.write(type.toByteArray()); d.write(data)
                val crc = java.util.zip.CRC32(); crc.update(type.toByteArray()); crc.update(data); d.writeInt(crc.value.toInt()); return o.toByteArray()
            }
            val ihdr = java.io.ByteArrayOutputStream().also { val d = java.io.DataOutputStream(it); d.writeInt(w); d.writeInt(h); d.write(8); d.write(2); d.write(0); d.write(0); d.write(0) }.toByteArray()
            val idat = java.io.ByteArrayOutputStream().also { java.util.zip.DeflaterOutputStream(it).use { z -> z.write(raw.toByteArray()) } }.toByteArray()
            return byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", ihdr) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
        }
        val halvesPng = halves(80, 48)
        fun pic(x: Float, rot: Float = 0f, fh: Boolean = false, fv: Boolean = false) =
            com.therealityteam.aeropages.model.SlideImage(halvesPng, "image/png", 80f, 48f, x, 0.36f, 0.15f, 0.15f * 48f / 80f * (16f / 9f), rotationDeg = rot, flipH = fh, flipV = fv)
        val rotDeck = Presentation(mutableListOf(Slide(title = SlideBox("Turned and mirrored pictures"), layout = com.therealityteam.aeropages.model.SlideLayout.BLANK).apply {
            images.add(pic(0.03f)); images.add(pic(0.22f, fh = true)); images.add(pic(0.41f, fv = true)); images.add(pic(0.60f, rot = 30f)); images.add(pic(0.80f, rot = -90f, fh = true))
        }))
        File(out, "picturerotate.pptx").outputStream().use { PptxWriter.write(rotDeck, it) }
        File(out, "picturerotate.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(rotDeck, it) }

        println("samples written to ${out.absolutePath}")
    }
}
