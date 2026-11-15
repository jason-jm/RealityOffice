package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Verifies Pages documents survive a DocxWriter -> DocxReader round trip (pure JVM). */
class DocxRoundTripTest {

    private fun para(text: String, fmt: CharacterFormat = CharacterFormat()): ParagraphBlock =
        ParagraphBlock(Paragraph(mutableListOf(TextRun(text, fmt))))

    @Test
    fun textAndFormattingRoundTrip() {
        val doc = Document()
        doc.blocks.add(para("普通段落"))
        doc.blocks.add(para("加粗", CharacterFormat(bold = true)))
        doc.blocks.add(para("红色斜体", CharacterFormat(italic = true, color = 0xFFCC0000)))
        val aligned = para("居中")
        aligned.paragraph.format.align = TextAlign.CENTER
        doc.blocks.add(aligned)

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))

        val texts = back.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph.plainText() }
        assertTrue("plain kept", texts.contains("普通段落"))
        val boldPara = back.blocks.filterIsInstance<ParagraphBlock>().first { it.paragraph.plainText() == "加粗" }
        assertTrue("bold kept", boldPara.paragraph.runs.first().format.bold)
        val redPara = back.blocks.filterIsInstance<ParagraphBlock>().first { it.paragraph.plainText() == "红色斜体" }
        assertTrue("italic kept", redPara.paragraph.runs.first().format.italic)
        assertEquals("color kept", 0xFFCC0000, redPara.paragraph.runs.first().format.color)
        val centered = back.blocks.filterIsInstance<ParagraphBlock>().first { it.paragraph.plainText() == "居中" }
        assertEquals("align kept", TextAlign.CENTER, centered.paragraph.format.align)
    }

    @Test
    fun stylesListsAndLinksRoundTrip() {
        val doc = Document()
        val h1 = para("第一章", CharacterFormat(fontSizePt = 20f, bold = true))
        h1.paragraph.format.style = com.therealityteam.aeropages.model.ParagraphStyle.HEADING1
        doc.blocks.add(h1)
        val bullet = para("列表项一")
        bullet.paragraph.format.listKind = com.therealityteam.aeropages.model.ListKind.BULLET
        doc.blocks.add(bullet)
        val numbered = para("编号项一")
        numbered.paragraph.format.listKind = com.therealityteam.aeropages.model.ListKind.NUMBER
        doc.blocks.add(numbered)
        val linked = ParagraphBlock(Paragraph(mutableListOf(
            TextRun("访问 ", CharacterFormat()),
            TextRun("官网", CharacterFormat(link = "https://example.com/?a=1&b=2")),
        )))
        doc.blocks.add(linked)

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        val paras = back.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }

        val heading = paras.first { it.plainText() == "第一章" }
        assertEquals("heading style", com.therealityteam.aeropages.model.ParagraphStyle.HEADING1, heading.format.style)
        val b = paras.first { it.plainText() == "列表项一" }
        assertEquals("bullet kept", com.therealityteam.aeropages.model.ListKind.BULLET, b.format.listKind)
        val n = paras.first { it.plainText() == "编号项一" }
        assertEquals("number kept", com.therealityteam.aeropages.model.ListKind.NUMBER, n.format.listKind)
        val lp = paras.first { it.plainText().contains("官网") }
        val linkRun = lp.runs.first { it.format.link != null }
        assertEquals("link url kept", "https://example.com/?a=1&b=2", linkRun.format.link)
        assertEquals("link text kept", "官网", linkRun.text)
    }

    @Test
    fun headerFooterAndCommentsRoundTrip() {
        val doc = Document()
        val body = para("正文内容")
        doc.blocks.add(body)
        doc.header = Paragraph(mutableListOf(TextRun("页眉文字", CharacterFormat())))
        doc.footer = Paragraph(mutableListOf(TextRun("页脚文字", CharacterFormat())))
        doc.comments.add(com.therealityteam.aeropages.model.Comment("审阅者", "请检查此段", body.paragraph.id))

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))

        assertTrue("header kept", back.header?.plainText()?.contains("页眉文字") == true)
        assertTrue("footer kept", back.footer?.plainText()?.contains("页脚文字") == true)
        assertEquals("one comment", 1, back.comments.size)
        assertEquals("comment author", "审阅者", back.comments[0].author)
        assertTrue("comment text", back.comments[0].text.contains("请检查此段"))
        // the comment must anchor to the paragraph that holds the body text
        val target = back.locations().firstOrNull { it.paragraph.id == back.comments[0].targetId }
        assertTrue("comment anchored", target != null && target.paragraph.plainText() == "正文内容")
    }

    @Test
    fun columnsRoundTrip() {
        val doc = Document()
        doc.blocks.add(para("第一段"))
        doc.page.columns = 2
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals("two columns kept", 2, back.page.columns)
        // single column stays default
        val doc1 = Document(); doc1.blocks.add(para("x"))
        val bos1 = ByteArrayOutputStream(); DocxWriter.write(doc1, bos1)
        assertEquals(1, DocxReader.read(ByteArrayInputStream(bos1.toByteArray())).page.columns)
    }

    @Test
    fun pageDecorRoundTrip() {
        val doc = Document()
        doc.blocks.add(para("正文"))
        doc.pageColor = 0xFFFFF2CC
        doc.watermark = "机密"
        doc.pageBorders = com.therealityteam.aeropages.model.PageBorders.box(com.therealityteam.aeropages.model.CellBorder(widthPt = 1.5f))

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))

        assertEquals("page color kept", 0xFFFFF2CC, back.pageColor)
        assertEquals("watermark kept", "机密", back.watermark)
        assertEquals("page border kept", doc.pageBorders, back.pageBorders)
    }

    @Test
    fun tableRoundTrip() {
        val doc = Document()
        doc.blocks.add(para("前文"))
        val table = Table.of(2, 3)
        table.rows[0].cells[0].content[0].runs.add(TextRun("表头A", CharacterFormat(bold = true)))
        table.rows[1].cells[2].content[0].runs.add(TextRun("数据C2", CharacterFormat()))
        doc.blocks.add(TableBlock(table))

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))

        val t = back.blocks.filterIsInstance<TableBlock>().firstOrNull()
        assertTrue("table present", t != null)
        assertEquals("rows", 2, t!!.table.rows.size)
        assertEquals("cols", 3, t.table.rows[0].cells.size)
        assertTrue("header text", t.table.rows[0].cells[0].content.any { it.plainText().contains("表头A") })
        assertTrue("cell text", t.table.rows[1].cells[2].content.any { it.plainText().contains("数据C2") })
    }
}

class DocxTrackChangesTest {

    @Test
    fun trackChangesRoundTrip() {
        val doc = com.therealityteam.aeropages.model.Document()
        // One paragraph with: kept text + tracked insertion + tracked deletion.
        val p = Paragraph(mutableListOf(
            TextRun("保留的文本，", CharacterFormat()),
            TextRun("新插入的", CharacterFormat(inserted = true)),
            TextRun("已删除的", CharacterFormat(deleted = true)),
            TextRun("结尾。", CharacterFormat()),
        ))
        doc.blocks.add(ParagraphBlock(p))

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val xml = run { // raw document.xml for structural assertions
            val zin = java.util.zip.ZipInputStream(ByteArrayInputStream(bos.toByteArray()))
            var out = ""
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name == "word/document.xml") out = zin.readBytes().decodeToString()
            }
            out
        }
        assertTrue("w:ins emitted", xml.contains("<w:ins ") && xml.contains("w:author=\"Reality Office\""))
        assertTrue("w:del with delText", xml.contains("<w:del ") && xml.contains("<w:delText"))
        assertTrue("deleted text not in a plain w:t", !xml.contains("<w:t xml:space=\"preserve\">已删除的"))

        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        val rp = back.blocks.filterIsInstance<ParagraphBlock>().first().paragraph
        assertEquals("all four runs' text kept", "保留的文本，新插入的已删除的结尾。", rp.plainText())
        val ins = rp.runs.first { it.text == "新插入的" }
        assertTrue("inserted flag kept", ins.format.inserted)
        assertTrue("inserted not marked deleted", !ins.format.deleted)
        val del = rp.runs.first { it.text == "已删除的" }
        assertTrue("deleted flag kept", del.format.deleted)
        val kept = rp.runs.first { it.text == "保留的文本，" }
        assertTrue("plain run unmarked", !kept.format.inserted && !kept.format.deleted)

        // Accept-all on the re-read doc applies the revision: deletion removed, insertion kept.
        com.therealityteam.aeropages.editor.ParagraphOps.removeDeleted(rp)
        rp.runs.forEach { it.format.inserted = false }
        assertEquals("accepted text", "保留的文本，新插入的结尾。", rp.plainText())
    }

    @Test
    fun secondSaveKeepsRevisions() {
        // write → read → write again: revisions must survive a resave cycle too.
        val doc = com.therealityteam.aeropages.model.Document()
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(
            TextRun("A", CharacterFormat(inserted = true)),
            TextRun("B", CharacterFormat(deleted = true)),
        ))))
        val bos1 = ByteArrayOutputStream()
        DocxWriter.write(doc, bos1)
        val mid = DocxReader.read(ByteArrayInputStream(bos1.toByteArray()))
        val bos2 = ByteArrayOutputStream()
        DocxWriter.write(mid, bos2)
        val back = DocxReader.read(ByteArrayInputStream(bos2.toByteArray()))
        val rp = back.blocks.filterIsInstance<ParagraphBlock>().first().paragraph
        assertTrue("ins survives resave", rp.runs.first { it.text == "A" }.format.inserted)
        assertTrue("del survives resave", rp.runs.first { it.text == "B" }.format.deleted)
    }
}

class DocxFootnotesTest {

    @Test
    fun footnotesRoundTrip() {
        val doc = com.therealityteam.aeropages.model.Document()
        doc.footnotes.add(com.therealityteam.aeropages.model.Footnote("第一条脚注"))
        doc.footnotes.add(com.therealityteam.aeropages.model.Footnote("第二条脚注"))
        // Body text with superscript [1] and [2] anchors (what insertFootnote produces).
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(
            TextRun("正文", CharacterFormat()),
            TextRun("[1]", CharacterFormat(superscript = true)),
            TextRun("后续", CharacterFormat()),
            TextRun("[2]", CharacterFormat(superscript = true)),
        ))))

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val xml = run {
            val zin = java.util.zip.ZipInputStream(ByteArrayInputStream(bos.toByteArray()))
            val out = HashMap<String, String>()
            while (true) {
                val e = zin.nextEntry ?: break
                out[e.name] = zin.readBytes().decodeToString()
            }
            out
        }
        assertTrue("footnotes part written", xml.containsKey("word/footnotes.xml"))
        assertTrue("separator note present", xml["word/footnotes.xml"]!!.contains("w:type=\"separator\""))
        assertTrue("anchor is a real footnoteReference", xml["word/document.xml"]!!.contains("<w:footnoteReference w:id=\"1\"/>"))
        assertTrue("marker text not duplicated in body", !xml["word/document.xml"]!!.contains("[1]"))

        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(2, back.footnotes.size)
        assertEquals("第一条脚注", back.footnotes[0].text)
        assertEquals("第二条脚注", back.footnotes[1].text)
        val p = back.blocks.filterIsInstance<ParagraphBlock>().first().paragraph
        assertEquals("正文[1]后续[2]", p.plainText())
        val anchor = p.runs.first { it.text == "[1]" }
        assertTrue("anchor is superscript again", anchor.format.superscript)
    }

    @Test
    fun superscriptTextThatIsNotAFootnoteStaysText() {
        val doc = com.therealityteam.aeropages.model.Document()
        // No footnotes — superscript "[1]" is ordinary text, must NOT become a reference.
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("[1]", CharacterFormat(superscript = true))))))
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals("[1]", back.blocks.filterIsInstance<ParagraphBlock>().first().paragraph.plainText())
        assertEquals(0, back.footnotes.size)
    }

    @Test
    fun coalescedAdjacentMarkersRoundTrip() {
        // Adjacent insertFootnote anchors coalesce into ONE superscript run "[1][2]".
        val doc = com.therealityteam.aeropages.model.Document()
        doc.footnotes.add(com.therealityteam.aeropages.model.Footnote("一"))
        doc.footnotes.add(com.therealityteam.aeropages.model.Footnote("二"))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(
            TextRun("文", CharacterFormat()),
            TextRun("[1][2]", CharacterFormat(superscript = true)),
        ))))
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val docXml = run {
            val zin = java.util.zip.ZipInputStream(ByteArrayInputStream(bos.toByteArray()))
            var out = ""
            while (true) { val e = zin.nextEntry ?: break; if (e.name == "word/document.xml") out = zin.readBytes().decodeToString() }
            out
        }
        assertTrue(docXml.contains("<w:footnoteReference w:id=\"1\"/>"))
        assertTrue(docXml.contains("<w:footnoteReference w:id=\"2\"/>"))
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        val p = back.blocks.filterIsInstance<ParagraphBlock>().first().paragraph
        assertEquals("文[1][2]", p.plainText())
        assertEquals(2, back.footnotes.size)
    }
}

class DocxSourcesTest {

    @Test
    fun sourcesRoundTrip() {
        val doc = com.therealityteam.aeropages.model.Document()
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("正文", CharacterFormat())))))
        doc.sources.add(com.therealityteam.aeropages.model.Source("鲁迅", "呐喊", "1923"))
        doc.sources.add(com.therealityteam.aeropages.model.Source("A; B | C", "Title; with | separators", "2024"))
        doc.watermark = "机密" // watermark + sources share docProps/custom.xml

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))

        assertEquals("机密", back.watermark)
        assertEquals(2, back.sources.size)
        assertEquals("鲁迅", back.sources[0].author)
        assertEquals("呐喊", back.sources[0].title)
        assertEquals("1923", back.sources[0].year)
        assertEquals("A; B | C", back.sources[1].author)
        assertEquals("Title; with | separators", back.sources[1].title)
    }

    @Test
    fun sourcesAloneWithoutWatermark() {
        val doc = com.therealityteam.aeropages.model.Document()
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("x", CharacterFormat())))))
        doc.sources.add(com.therealityteam.aeropages.model.Source("作者", "标题", "2026"))
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(1, back.sources.size)
        assertEquals("标题", back.sources[0].title)
        assertEquals(null, back.watermark)
    }
}

class DocxVMergeTest {

    @Test
    fun vMergeRoundTrip() {
        val doc = com.therealityteam.aeropages.model.Document()
        val table = Table.of(3, 2)
        table.rows[0].cells[0].content[0].runs.add(TextRun("跨行单元格", CharacterFormat()))
        table.rows[0].cells[0].rowSpan = 2
        table.rows[1].cells[0].vMerged = true
        table.rows[2].cells[0].content[0].runs.add(TextRun("下方", CharacterFormat()))
        doc.blocks.add(TableBlock(table))

        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val xml = run {
            val zin = java.util.zip.ZipInputStream(ByteArrayInputStream(bos.toByteArray()))
            var out = ""
            while (true) { val e = zin.nextEntry ?: break; if (e.name == "word/document.xml") out = zin.readBytes().decodeToString() }
            out
        }
        assertTrue("restart emitted", xml.contains("<w:vMerge w:val=\"restart\"/>"))
        assertTrue("continue emitted", xml.contains("<w:vMerge/>"))

        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        val bt = back.blocks.filterIsInstance<TableBlock>().first().table
        assertEquals("anchor span restored", 2, bt.rows[0].cells[0].rowSpan)
        assertTrue("covered flag restored", bt.rows[1].cells[0].vMerged)
        assertTrue("row below unaffected", !bt.rows[2].cells[0].vMerged && bt.rows[2].cells[0].rowSpan == 1)
    }

    @Test
    fun editorMergeDownAndSplit() {
        val ed = com.therealityteam.aeropages.editor.DocumentEditor()
        val doc = com.therealityteam.aeropages.model.Document()
        val table = Table.of(2, 2)
        table.rows[0].cells[0].content[0].runs.add(TextRun("上", CharacterFormat()))
        table.rows[1].cells[0].content[0].runs.add(TextRun("下", CharacterFormat()))
        doc.blocks.add(TableBlock(table))
        ed.load(doc)
        ed.focusParagraph(0) // first cell's paragraph
        ed.mergeCellDown()
        assertEquals(2, table.rows[0].cells[0].rowSpan)
        assertTrue(table.rows[1].cells[0].vMerged)
        assertTrue("content moved up", table.rows[0].cells[0].content.any { it.plainText() == "下" })
        ed.splitCellVertical()
        assertEquals(1, table.rows[0].cells[0].rowSpan)
        assertTrue(!table.rows[1].cells[0].vMerged)
    }

    @Test
    fun inCellImageStaysInItsCell() {
        // Hand-built docx: a table whose cell contains a drawing → the picture stays inside that cell.
        val zipBytes = run {
            val bos = ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(bos).use { z ->
                fun put(name: String, text: String) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
                put("[Content_Types].xml", """<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>""")
                put("word/_rels/document.xml.rels", """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId9" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/></Relationships>""")
                z.putNextEntry(java.util.zip.ZipEntry("word/media/image1.png")); z.write(byteArrayOf(0x50, 0x4E, 0x47, 0x21)); z.closeEntry()
                put("word/document.xml", """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><w:body><w:tbl><w:tr><w:tc><w:p><w:r><w:t>含图单元格</w:t></w:r><w:r><w:drawing><wp:inline><wp:extent cx="1270000" cy="635000"/><a:blip r:embed="rId9"/></wp:inline></w:drawing></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:r><w:t>after</w:t></w:r></w:p></w:body></w:document>""")
            }
            bos.toByteArray()
        }
        val doc = DocxReader.read(ByteArrayInputStream(zipBytes))
        assertTrue("nothing is hoisted to document level", doc.blocks.none { it is com.therealityteam.aeropages.model.ImageBlock })
        val bt = doc.blocks.filterIsInstance<TableBlock>().first().table
        val cell = bt.rows[0].cells[0]
        assertEquals("含图单元格", cell.content[0].plainText())
        assertEquals("in-cell image kept in the cell", 1, cell.images.size)
        assertEquals(4, cell.images[0].data.size)
    }
}
