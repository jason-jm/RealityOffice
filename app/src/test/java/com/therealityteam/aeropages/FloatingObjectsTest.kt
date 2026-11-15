package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.PageFlow
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.FloatAlign
import com.therealityteam.aeropages.model.FloatFrame
import com.therealityteam.aeropages.model.FloatPlacement
import com.therealityteam.aeropages.model.FloatPosition
import com.therealityteam.aeropages.model.FloatWrap
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.TextBoxBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Floating pictures and text boxes: where they stand on the page (frames, offsets, alignments), how
 * the text goes by them, read from Word (wp:anchor), .doc (FSPA) and ODF (frames anchored to a
 * paragraph) and written back to docx and ODT.
 */
class FloatingObjectsTest {

    private val letter = PageSetup(widthPt = 612f, heightPt = 792f, marginTopPt = 72f, marginBottomPt = 72f, marginLeftPt = 90f, marginRightPt = 90f)

    private fun at(pos: FloatPosition, w: Float = 100f, h: Float = 50f, paraTop: Float = 200f) =
        FloatPlacement.place(pos, w, h, letter, 612f, 1f, 90f, 432f, paraTop)

    @Test
    fun placesFromItsFrames() {
        assertEquals("from the page's corner", 10f to 20f, at(FloatPosition(10f, FloatFrame.PAGE, null, 20f, FloatFrame.PAGE)))
        assertEquals("from the margins", 100f to 92f, at(FloatPosition(10f, FloatFrame.MARGIN, null, 20f, FloatFrame.MARGIN)))
        assertEquals("from its column and paragraph", 100f to 212f, at(FloatPosition(10f, FloatFrame.COLUMN, null, 12f, FloatFrame.PARAGRAPH)))
        assertEquals("right-aligned in the margins, bottom of the page", 422f to 742f,
            at(FloatPosition(hFrom = FloatFrame.MARGIN, hAlign = FloatAlign.END, vFrom = FloatFrame.PAGE, vAlign = FloatAlign.END)))
        assertEquals("centred on the page", 256f to 371f,
            at(FloatPosition(hFrom = FloatFrame.PAGE, hAlign = FloatAlign.CENTER, vFrom = FloatFrame.PAGE, vAlign = FloatAlign.CENTER)))
        assertEquals("in the right margin's strip; the bottom margin's band", 527f to 730f,
            at(FloatPosition(5f, FloatFrame.RIGHT_MARGIN, null, 10f, FloatFrame.BOTTOM_MARGIN)))
        // In pixels at 2 a point: the same place, twice as far.
        assertEquals(20f to 40f, FloatPlacement.place(FloatPosition(10f, FloatFrame.PAGE, null, 20f, FloatFrame.PAGE), 200f, 100f, letter, 1224f, 2f, 180f, 864f, 0f))
        // Kept across its column when the text goes around it: right-aligned stays inside.
        assertEquals(332f, FloatPlacement.acrossColumn(FloatPosition(hFrom = FloatFrame.PAGE, hAlign = FloatAlign.END), 100f, letter, 612f, 1f, 90f, 432f))
        assertEquals("no position: centred", 166f, FloatPlacement.acrossColumn(null, 100f, letter, 612f, 1f, 90f, 432f))
    }

    @Test
    fun aMarkOfNoHeightKeepsAHeadingWithItsText() {
        // A heading kept with the next paragraph, a floating object's mark between them: the heading still moves on.
        val heading = PageFlow.Lines(0f, floatArrayOf(20f), 0f, keepWithNext = true)
        val text = PageFlow.Lines(0f, floatArrayOf(20f, 40f, 60f), 0f)
        val filler = PageFlow.Solid(70f)
        val pages = PageFlow.paginate(listOf(filler, heading, PageFlow.Solid(0f), text), PageFlow.Geometry(height = 100f))
        assertEquals(2, pages.size)
        assertEquals("the heading went with its text", listOf(1, 2, 3), pages[1].pieces.map { it.item })
    }

    private val png: ByteArray by lazy {
        // A 2×1 PNG.
        java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAEElEQVR4nGP4z8DAwMDAAAAQAAH/q8n8IQAAAABJRU5ErkJggg==")
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun anchor(rid: String, cx: Long, cy: Long, id: Int, h: String, v: String, wrap: String, behind: Boolean, flip: String = "") =
        """<w:r><w:drawing><wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="$id" behindDoc="${if (behind) 1 else 0}" locked="0" layoutInCell="1" allowOverlap="1">""" +
            """<wp:simplePos x="0" y="0"/>$h$v<wp:extent cx="$cx" cy="$cy"/><wp:effectExtent l="0" t="0" r="0" b="0"/>$wrap<wp:docPr id="$id" name="P$id"/><wp:cNvGraphicFramePr/>""" +
            """<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
            """<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="$id" name="p"/><pic:cNvPicPr/></pic:nvPicPr>""" +
            """<pic:blipFill><a:blip r:embed="$rid"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm$flip><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r>"""

    private fun wordDoc(): ByteArray {
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        val e = 12700L
        val body =
            // A cover's picture behind the text, from the page's corner, as wide as the page.
            """<w:p><w:r><w:t>Cover title</w:t></w:r>""" +
            anchor("rId1", 612 * e, 300 * e, 1, """<wp:positionH relativeFrom="page"><wp:posOffset>0</wp:posOffset></wp:positionH>""",
                """<wp:positionV relativeFrom="page"><wp:posOffset>0</wp:posOffset></wp:positionV>""", "<wp:wrapNone/>", behind = true) + "</w:p>" +
            // An empty paragraph holding only a mirrored logo in front of the text, right-aligned in the margins, 12 pt below the paragraph's top.
            "<w:p>" + anchor("rId1", 72 * e, 36 * e, 2, """<wp:positionH relativeFrom="margin"><wp:align>right</wp:align></wp:positionH>""",
                """<wp:positionV relativeFrom="paragraph"><wp:posOffset>${12 * e}</wp:posOffset></wp:positionV>""", "<wp:wrapNone/>", behind = false, flip = """ flipH="1"""") + "</w:p>" +
            // A picture the text goes around, 252 pt across its column.
            """<w:p><w:r><w:t>Text beside a picture.</w:t></w:r>""" +
            anchor("rId1", 90 * e, 90 * e, 3, """<wp:positionH relativeFrom="column"><wp:posOffset>${252 * e}</wp:posOffset></wp:positionH>""",
                """<wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV>""", """<wp:wrapSquare wrapText="bothSides"/>""", behind = false) + "</w:p>" +
            // An inline picture alone in its paragraph: the picture stands for it.
            """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="${72 * e}" cy="${36 * e}"/><wp:docPr id="9" name="i"/><a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
            """<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:blipFill><a:blip r:embed="rId1"/></pic:blipFill></pic:pic>""" +
            """</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""" +
            "<w:p><w:r><w:t>End.</w:t></w:r></w:p>"
        val doc = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$w" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>$body</w:body></w:document>"""
        return zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray(),
            "word/document.xml" to doc.toByteArray(),
            "word/_rels/document.xml.rels" to ("""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/p.png"/></Relationships>""").toByteArray(),
            "word/media/p.png" to png,
        ))
    }

    private fun kinds(blocks: List<Block>) = blocks.map {
        when (it) {
            is ParagraphBlock -> "p:" + it.paragraph.plainText()
            is ImageBlock -> "img" + (it.floating?.let { f -> ":" + f.wrap } ?: "")
            is TextBoxBlock -> "box" + (it.floating?.let { f -> ":" + f.wrap } ?: "")
            else -> it.javaClass.simpleName
        }
    }

    @Test
    fun wordsAnchoredDrawingsFloat() {
        val doc = DocxReader.read(ByteArrayInputStream(wordDoc()))
        assertEquals("a paragraph anchoring only a floating picture stays (it is a line of its own in Word)",
            listOf("p:Cover title", "img:BEHIND", "p:", "img:FRONT", "p:Text beside a picture.", "img:SQUARE", "img", "p:End."), kinds(doc.blocks))
        val (cover, logo, beside) = doc.blocks.filterIsInstance<ImageBlock>()
        assertEquals(FloatPosition(0f, FloatFrame.PAGE, null, 0f, FloatFrame.PAGE, null, FloatWrap.BEHIND), cover.floating)
        assertEquals(612f, cover.widthPt, 0.01f)
        assertEquals(FloatPosition(0f, FloatFrame.MARGIN, FloatAlign.END, 12f, FloatFrame.PARAGRAPH, null, FloatWrap.FRONT), logo.floating)
        assertTrue("mirrored", logo.flipH); assertFalse(logo.flipV)
        assertEquals(FloatPosition(252f, FloatFrame.COLUMN, null, 0f, FloatFrame.PARAGRAPH, null, FloatWrap.SQUARE), beside.floating)
        assertNull("an inline picture does not float", doc.blocks.filterIsInstance<ImageBlock>()[3].floating)
    }

    private fun entry(bytes: ByteArray, name: String) = String(OfficeXml.unzip(ByteArrayInputStream(bytes))[name]!!, Charsets.UTF_8)

    @Test
    fun floatingDrawingsGoBackToWordInTheirParagraph() {
        val doc = DocxReader.read(ByteArrayInputStream(wordDoc()))
        val out = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        val xml = entry(out, "word/document.xml")
        // The cover picture is a drawing of the title's paragraph: no paragraph of its own.
        assertTrue(Regex("""<w:p>(?:(?!</w:p>).)*Cover title(?:(?!</w:p>).)*<wp:anchor[^>]*behindDoc="1"""").containsMatchIn(xml))
        assertTrue(xml.contains("""<wp:positionH relativeFrom="margin"><wp:align>right</wp:align></wp:positionH>"""))
        assertTrue(xml.contains("""<wp:positionV relativeFrom="paragraph"><wp:posOffset>152400</wp:posOffset></wp:positionV>"""))
        assertTrue(xml.contains("""<a:xfrm flipH="1">"""))
        val back = DocxReader.read(ByteArrayInputStream(out))
        assertEquals(kinds(doc.blocks), kinds(back.blocks))
        assertEquals(doc.blocks.filterIsInstance<ImageBlock>().map { it.floating }, back.blocks.filterIsInstance<ImageBlock>().map { it.floating })
        assertEquals(listOf(false, true, false, false), back.blocks.filterIsInstance<ImageBlock>().map { it.flipH })
    }

    @Test
    fun floatingFramesGoToOdfAndBack() {
        val doc = Document(mutableListOf())
        doc.addParagraph(Paragraph.of("Cover title"))
        doc.blocks.add(ImageBlock(png, "image/png", 612f, 300f, floating = FloatPosition(0f, FloatFrame.PAGE, null, 0f, FloatFrame.PAGE, null, FloatWrap.BEHIND)))
        doc.blocks.add(TextBoxBlock(mutableListOf(Paragraph.of("Note")), 144f, 48f,
            floating = FloatPosition(0f, FloatFrame.MARGIN, FloatAlign.END, 24f, FloatFrame.PARAGRAPH, null, FloatWrap.FRONT)))
        doc.addParagraph(Paragraph.of("Text beside a picture."))
        doc.blocks.add(ImageBlock(png, "image/png", 90f, 90f, floating = FloatPosition(252f, FloatFrame.COLUMN, null, 0f, FloatFrame.PARAGRAPH, null, FloatWrap.SQUARE), flipV = true))
        doc.addParagraph(Paragraph.of("End."))
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        val content = entry(bytes, "content.xml")
        assertTrue("anchored to the paragraph, inside it", Regex("""<text:p [^>]*><draw:frame [^>]*text:anchor-type="paragraph" svg:x="0(\.0+)?pt" svg:y="0(\.0+)?pt"[^>]*><draw:image""").containsMatchIn(content))
        assertTrue(content.contains("""style:wrap="run-through" style:run-through="background" style:horizontal-pos="from-left" style:horizontal-rel="page" style:vertical-pos="from-top" style:vertical-rel="page""""))
        assertTrue(content.contains("""style:horizontal-pos="right" style:horizontal-rel="page-content""""))
        assertTrue(content.contains("""style:mirror="vertical""""))
        val back = OdtReader.read(bytes)
        assertEquals(kinds(doc.blocks), kinds(back.blocks))
        assertEquals(doc.blocks.mapNotNull { (it as? ImageBlock)?.floating ?: (it as? TextBoxBlock)?.floating },
            back.blocks.mapNotNull { (it as? ImageBlock)?.floating ?: (it as? TextBoxBlock)?.floating })
        assertTrue(back.blocks.filterIsInstance<ImageBlock>()[1].flipV)
    }

    @Test
    fun legacyWordShapesFloatToo() {
        val doc = DocReader.read(javaClass.getResourceAsStream("/doc/pictures.doc")!!)
        // pictures.doc: the floating picture LibreOffice wrote from a column-relative, square-wrapped anchor.
        val floating = doc.blocks.filterIsInstance<ImageBlock>().single { it.floating != null }.floating!!
        assertEquals(FloatWrap.SQUARE, floating.wrap)
        assertEquals(FloatFrame.COLUMN, floating.hFrom)
        assertEquals(252f, floating.x, 1f)
        assertEquals(FloatFrame.PARAGRAPH, floating.vFrom)
    }

    @Test
    fun positionedTablesFloat() {
        // Word's positioned tables (w:tblpPr): a cover's title table low on the page, a small one top right in the margins.
        val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        fun p(t: String) = """<w:p><w:r><w:t>$t</w:t></w:r></w:p>"""
        fun tbl(pos: String, width: Int, cells: List<String>) = """<w:tbl><w:tblPr>$pos<w:tblW w:w="$width" w:type="dxa"/></w:tblPr><w:tblGrid>""" +
            cells.joinToString("") { """<w:gridCol w:w="${width / cells.size}"/>""" } + "</w:tblGrid><w:tr>" + cells.joinToString("") { "<w:tc>${p(it)}</w:tc>" } + "</w:tr></w:tbl>"
        val body = p("Cover") +
            tbl("""<w:tblpPr w:leftFromText="180" w:rightFromText="180" w:vertAnchor="page" w:horzAnchor="page" w:tblpX="2815" w:tblpY="11433"/>""", 6860, listOf("Title of Article")) +
            p("After") +
            tbl("""<w:tblpPr w:vertAnchor="margin" w:horzAnchor="margin" w:tblpXSpec="right" w:tblpYSpec="top"/>""", 2880, listOf("Right", "Top")) +
            p("Inline") + tbl("", 4000, listOf("In the flow")) + p("End")
        val bytes = zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray(),
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$w"><w:body>$body</w:body></w:document>""".toByteArray(),
        ))
        val doc = DocxReader.read(ByteArrayInputStream(bytes))
        val tables = doc.blocks.filterIsInstance<com.therealityteam.aeropages.model.TableBlock>().map { it.table }
        // Its left border: tblpX less Word's cell margin (its text stands at tblpX, as LibreOffice shows it).
        assertEquals(FloatPosition(135.35f, FloatFrame.PAGE, null, 571.65f, FloatFrame.PAGE, null, FloatWrap.SQUARE), tables[0].floating)
        assertEquals(343f, tables[0].widthPt!!, 0.01f)
        assertEquals(FloatPosition(0f, FloatFrame.MARGIN, FloatAlign.END, 0f, FloatFrame.MARGIN, FloatAlign.START, FloatWrap.SQUARE), tables[1].floating)
        assertTrue("placed on the page, not in the flow", tables[0].floating!!.onPage)
        assertNull("a table in the flow", tables[2].floating)
        // Back to Word: the position before the width, as CT_TblPr orders them; and back again.
        val out = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        assertTrue(entry(out, "word/document.xml").contains("""<w:tblpPr w:leftFromText="180" w:rightFromText="180" w:vertAnchor="page" w:horzAnchor="page" w:tblpX="2815" w:tblpY="11433"/><w:tblW w:w="6860" w:type="dxa"/>"""))
        val back = DocxReader.read(ByteArrayInputStream(out)).blocks.filterIsInstance<com.therealityteam.aeropages.model.TableBlock>().map { it.table }
        assertEquals(tables.map { it.floating }, back.map { it.floating })
        assertEquals(tables.map { it.widthPt }, back.map { it.widthPt })
        // ODT: a frame anchored to the paragraph before it, holding the table in its text box (as LibreOffice keeps one).
        val odt = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        val content = entry(odt, "content.xml")
        assertTrue(Regex("""<draw:frame [^>]*text:anchor-type="paragraph" svg:x="135\.35pt" svg:y="571\.65pt" svg:width="343(\.0+)?pt"[^>]*><draw:text-box[^>]*><table:table""").containsMatchIn(content))
        val fromOdt = OdtReader.read(odt).blocks.filterIsInstance<com.therealityteam.aeropages.model.TableBlock>().map { it.table }
        assertEquals(tables.map { it.floating }, fromOdt.map { it.floating })
        assertEquals(343f, fromOdt[0].widthPt!!, 0.01f)
        assertEquals(listOf("Title of Article"), fromOdt[0].rows[0].cells.map { it.content[0].plainText() })
    }

    @Test
    fun legacyWordsPositionedTables() {
        // floating-tables.doc: LibreOffice's .doc of the same two positioned tables (sprmTPc, sprmTDxaAbs, sprmTDyaAbs).
        val doc = DocReader.read(javaClass.getResourceAsStream("/doc/floating-tables.doc")!!)
        val tables = doc.blocks.filterIsInstance<com.therealityteam.aeropages.model.TableBlock>().map { it.table }
        assertEquals(2, tables.size)
        val title = tables[0].floating!!
        assertEquals(FloatFrame.PAGE, title.hFrom); assertEquals(FloatFrame.PAGE, title.vFrom)
        assertEquals("its border, the cell margin left of where its text stands", 135.35f, title.x, 1f); assertEquals(571.65f, title.y, 1f)
        assertEquals(343f, tables[0].widthPt!!, 1f)
        val corner = tables[1].floating!!
        assertEquals(FloatAlign.END, corner.hAlign); assertEquals(FloatAlign.START, corner.vAlign)
        assertEquals(FloatFrame.MARGIN, corner.hFrom); assertEquals(FloatFrame.MARGIN, corner.vFrom)
    }

    @Test
    fun movingAFloatKeepsItsFramesAndDropsItsAlignment() {
        // Right-aligned in the margins, 12 pt below its paragraph's top: dragged to (300, 250) it is 210 pt into the
        // margins' box and 50 pt below the paragraph — offsets now, in the same frames.
        val pos = FloatPosition(0f, FloatFrame.MARGIN, FloatAlign.END, 12f, FloatFrame.PARAGRAPH, null, FloatWrap.FRONT)
        val moved = FloatPlacement.movedTo(pos, 300f, 250f, letter, 612f, 1f, 90f, 432f, 200f)
        assertEquals(FloatPosition(210f, FloatFrame.MARGIN, null, 50f, FloatFrame.PARAGRAPH, null, FloatWrap.FRONT), moved)
        assertEquals(300f to 250f, at(moved, paraTop = 200f))
        // In pixels at 2 a point the offsets are still points.
        assertEquals(105f, FloatPlacement.movedTo(pos, 390f, 0f, letter, 1224f, 2f, 180f, 864f, 0f).x)
    }

    @Test
    fun picturesChangeHowTheTextGoesByThem() {
        val e = com.therealityteam.aeropages.editor.DocumentEditor()
        val img = ImageBlock(png, "image/png", 100f, 50f)
        e.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("Anchor")), img, ParagraphBlock(Paragraph.of("After")))))
        val behind = FloatPosition(0f, FloatFrame.COLUMN, FloatAlign.CENTER, 20f, FloatFrame.PARAGRAPH, null, FloatWrap.BEHIND)
        e.setImageFloat(img, behind)
        assertEquals(behind, (e.document.blocks[1] as ImageBlock).floating)
        e.setImageFloat(e.document.blocks[1] as ImageBlock, null)
        assertNull("in line again", (e.document.blocks[1] as ImageBlock).floating)
        e.undo()
        assertEquals("undo gives back its place", behind, (e.document.blocks[1] as ImageBlock).floating)
        e.undo()
        assertNull((e.document.blocks[1] as ImageBlock).floating)
    }

    @Test
    fun shapesAreInsertedInFrontOfTheTextAndGoToWord() {
        val e = com.therealityteam.aeropages.editor.DocumentEditor()
        e.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("First")), ParagraphBlock(Paragraph.of("Second")))))
        e.setSelection(0, 2, 2)
        e.paragraphHeightPt = { 20f }
        e.insertShape("heart")
        val box = e.document.blocks[1] as TextBoxBlock
        assertEquals("a preset the app draws from its geometry", "heart", box.shape?.preset)
        assertEquals("in front of the text, just below its paragraph, centred",
            FloatPosition(0f, FloatFrame.COLUMN, FloatAlign.CENTER, 26f, FloatFrame.PARAGRAPH, null, FloatWrap.FRONT), box.floating)
        assertTrue("the caret is in its text", e.activeInTextBox)
        assertTrue(e.activeBox === box)
        // Wrapped otherwise, then deleted: one undo step each.
        e.setBoxFloat(box, box.floating!!.copy(wrap = FloatWrap.BEHIND))
        assertEquals(FloatWrap.BEHIND, (e.document.blocks[1] as TextBoxBlock).floating?.wrap)
        e.deleteBox(e.document.blocks[1] as TextBoxBlock)
        assertEquals(listOf("First", "Second"), e.document.blocks.map { (it as ParagraphBlock).paragraph.plainText() })
        assertEquals("the caret goes back to the paragraph before it", 0, e.active)
        e.undo()
        assertEquals(FloatWrap.BEHIND, (e.document.blocks[1] as TextBoxBlock).floating?.wrap)
        // To Word and back: an anchored wps shape.
        e.undo()
        val out = ByteArrayOutputStream().also { DocxWriter.write(e.document, it) }.toByteArray()
        val back = DocxReader.read(ByteArrayInputStream(out)).blocks.filterIsInstance<TextBoxBlock>().single()
        assertEquals("heart", back.shape?.preset)
        assertEquals(FloatWrap.FRONT, back.floating?.wrap)
        assertEquals(FloatAlign.CENTER, back.floating?.hAlign)
        assertEquals(26f, back.floating!!.y, 0.01f)
    }

    @Test
    fun textBoxesAndShapesAreFormatted() {
        val e = com.therealityteam.aeropages.editor.DocumentEditor()
        val box = TextBoxBlock(mutableListOf(Paragraph.of("Note")), 200f, 60f)
        e.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("Before")), box)))
        // A plain box takes Word's look first (white, a thin black rule), then the fill asked for.
        e.editBoxShape(box) { it.fill = 0xFFFFF2CC }
        val shaped = e.document.blocks[1] as TextBoxBlock
        assertEquals(com.therealityteam.aeropages.model.ShapeType.TEXTBOX, shaped.shape?.type)
        assertEquals(0xFFFFF2CC, shaped.shape?.fill)
        assertEquals(0xFF000000, shaped.shape?.border)
        e.editBoxShape(shaped) { it.shadow = com.therealityteam.aeropages.model.ShapeShadow.PRESETS[0] }
        e.scaleBox(e.document.blocks[1] as TextBoxBlock, 1.5f)
        assertEquals(300f, (e.document.blocks[1] as TextBoxBlock).widthPt!!, 0.01f)
        assertEquals(90f, (e.document.blocks[1] as TextBoxBlock).heightPt!!, 0.01f)
        e.undo(); e.undo()
        assertEquals(200f, (e.document.blocks[1] as TextBoxBlock).widthPt!!, 0.01f)
        assertNull((e.document.blocks[1] as TextBoxBlock).shape?.shadow)
        e.undo()
        assertNull("back to the plain box", (e.document.blocks[1] as TextBoxBlock).shape)
    }
}
