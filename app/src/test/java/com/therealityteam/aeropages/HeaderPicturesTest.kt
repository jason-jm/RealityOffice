package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.FloatAlign
import com.therealityteam.aeropages.model.FloatFrame
import com.therealityteam.aeropages.model.FloatPlacement
import com.therealityteam.aeropages.model.FloatPosition
import com.therealityteam.aeropages.model.FloatWrap
import com.therealityteam.aeropages.model.HfSlot
import com.therealityteam.aeropages.model.ImageBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pictures in headers and footers (a letterhead's logo): read from Word's header parts — anchored
 * where they stand, or in line as their paragraph aligns them — shown on the pages their header
 * shows on, and written back to Word and to ODF's master page.
 */
class HeaderPicturesTest {

    private val png: ByteArray by lazy {
        // A 2×1 PNG.
        java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAEElEQVR4nGP4z8DAwMDAAAAQAAH/q8n8IQAAAABJRU5ErkJggg==")
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body); z.closeEntry() } }
        return out.toByteArray()
    }

    private val e = 12700L
    private val ns = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
        """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing""""

    private fun graphic(cx: Long, cy: Long) =
        """<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
            """<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="1" name="logo"/><pic:cNvPicPr/></pic:nvPicPr>""" +
            """<pic:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>"""

    private fun inline(w: Int, h: Int) = """<w:r><w:drawing><wp:inline><wp:extent cx="${w * e}" cy="${h * e}"/><wp:docPr id="2" name="i"/>${graphic(w * e, h * e)}</wp:inline></w:drawing></w:r>"""

    private val rels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/logo.png"/></Relationships>"""

    /**
     * A letter: its header's logo right-aligned in the margins, 24 pt from the page's top, in front of the
     * text; the first page's header only a logo in line, centred; the footer's a small picture in line, right.
     */
    private fun letter(): ByteArray {
        val header = """<w:hdr $ns><w:p><w:r><w:t>ACME Ltd.</w:t></w:r><w:r><w:drawing>""" +
            """<wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="3" behindDoc="0" locked="0" layoutInCell="1" allowOverlap="1"><wp:simplePos x="0" y="0"/>""" +
            """<wp:positionH relativeFrom="margin"><wp:align>right</wp:align></wp:positionH><wp:positionV relativeFrom="page"><wp:posOffset>${24 * e}</wp:posOffset></wp:positionV>""" +
            """<wp:extent cx="${96 * e}" cy="${32 * e}"/><wp:effectExtent l="0" t="0" r="0" b="0"/><wp:wrapNone/><wp:docPr id="1" name="logo"/>${graphic(96 * e, 32 * e)}""" +
            """</wp:anchor></w:drawing></w:r></w:p></w:hdr>"""
        val first = """<w:hdr $ns><w:p><w:pPr><w:jc w:val="center"/></w:pPr>${inline(144, 48)}</w:p></w:hdr>"""
        val footer = """<w:ftr $ns><w:p><w:pPr><w:jc w:val="right"/></w:pPr>${inline(36, 36)}</w:p></w:ftr>"""
        val sect = """<w:sectPr><w:headerReference w:type="default" r:id="rIdH"/><w:headerReference w:type="first" r:id="rIdF"/><w:footerReference w:type="default" r:id="rIdB"/>""" +
            """<w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1800" w:bottom="1440" w:left="1800" w:header="720" w:footer="720" w:gutter="0"/><w:titlePg/></w:sectPr>"""
        val doc = """<?xml version="1.0" encoding="UTF-8"?><w:document $ns><w:body><w:p><w:r><w:t>Dear reader,</w:t></w:r></w:p>$sect</w:body></w:document>"""
        val docRels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rIdH" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>""" +
            """<Relationship Id="rIdF" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header2.xml"/>""" +
            """<Relationship Id="rIdB" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/></Relationships>"""
        return zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray(),
            "word/document.xml" to doc.toByteArray(),
            "word/_rels/document.xml.rels" to docRels.toByteArray(),
            "word/header1.xml" to header.toByteArray(), "word/_rels/header1.xml.rels" to rels.toByteArray(),
            "word/header2.xml" to first.toByteArray(), "word/_rels/header2.xml.rels" to rels.toByteArray(),
            "word/footer1.xml" to footer.toByteArray(), "word/_rels/footer1.xml.rels" to rels.toByteArray(),
            "word/media/logo.png" to png,
        ))
    }

    private val logo = FloatPosition(0f, FloatFrame.MARGIN, FloatAlign.END, 24f, FloatFrame.PAGE, null, FloatWrap.FRONT)
    // In line on the header's first line, its paragraph centred; the footer's right.
    private val firstLogo = FloatPlacement.inHeaderLine(0, com.therealityteam.aeropages.model.TextAlign.CENTER)
    private val footerPicture = FloatPlacement.inHeaderLine(0, com.therealityteam.aeropages.model.TextAlign.RIGHT)

    private fun positions(doc: Document) = HfSlot.entries.associateWith { slot -> doc.hfPictures[slot].orEmpty().map { it.floating } }.filterValues { it.isNotEmpty() }

    @Test
    fun wordsHeaderPicturesAreRead() {
        val doc = DocxReader.read(ByteArrayInputStream(letter()))
        assertEquals("ACME Ltd.", doc.header?.plainText())
        assertNull("the first page's header holds no text", doc.firstHeader)
        assertTrue(doc.differentFirstPage)
        assertEquals(mapOf(HfSlot.HEADER to listOf(logo), HfSlot.FIRST_HEADER to listOf(firstLogo), HfSlot.FOOTER to listOf(footerPicture)), positions(doc))
        val (w, h) = doc.hfPictures[HfSlot.HEADER]!!.single().let { it.widthPt to it.heightPt }
        assertEquals(96f, w, 0.01f); assertEquals(32f, h, 0.01f)
        // The pages they show on: the first page its own header's, the others the default's.
        assertEquals(144f, doc.hfPicturesFor(1, header = true).single().widthPt, 0.01f)
        assertEquals(96f, doc.hfPicturesFor(2, header = true).single().widthPt, 0.01f)
        // Word's first page without a footer of its own shows none.
        assertTrue(doc.hfPicturesFor(1, header = false).isEmpty())
        assertEquals(36f, doc.hfPicturesFor(2, header = false).single().widthPt, 0.01f)
        // On the page: the logo's right edge on the right margin, 24 pt down.
        assertEquals(426f to 24f, FloatPlacement.placeInHeader(doc.hfPicturesFor(2, true).single(), true, doc.page))
        // The first page's in its line: the line as tall as it, at the header distance, centred in the margins.
        assertNull(FloatPlacement.placeInHeader(doc.hfPicturesFor(1, true).single(), true, doc.page))
        val line = com.therealityteam.aeropages.model.HeaderFooters.layout(doc, 1, 1, doc.page, true).single()
        assertEquals(36f, line.top, 0.01f); assertEquals(48f, line.height, 0.01f)
        assertEquals(listOf(234f), com.therealityteam.aeropages.model.HeaderFooters.across(line, com.therealityteam.aeropages.model.TextAlign.LEFT, 0f, 90f, 432f).first)
        // The footer's: its foot at the footer distance, at the right margin.
        val foot = com.therealityteam.aeropages.model.HeaderFooters.layout(doc, 2, 2, doc.page, false).single()
        assertEquals(792f - 36f - 36f, foot.top, 0.01f)
        assertEquals(listOf(486f), com.therealityteam.aeropages.model.HeaderFooters.across(foot, com.therealityteam.aeropages.model.TextAlign.LEFT, 0f, 90f, 432f).first)
    }

    private fun entries(bytes: ByteArray) = OfficeXml.unzip(ByteArrayInputStream(bytes))

    @Test
    fun headerPicturesGoBackToWord() {
        val doc = DocxReader.read(ByteArrayInputStream(letter()))
        val out = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        val parts = entries(out)
        val document = String(parts["word/document.xml"]!!)
        val docRels = String(parts["word/_rels/document.xml.rels"]!!)
        // Each header and footer is a part of its own, its pictures with it: the first page's, a logo alone, too.
        fun partOf(type: String, kind: String): String {
            val id = Regex("""<w:${kind}Reference w:type="$type" r:id="([^"]+)"""").find(document)!!.groupValues[1]
            val target = Regex("""Id="$id"[^>]*Target="([^"]+)"""").find(docRels)?.groupValues?.get(1)
                ?: Regex("""Target="([^"]+)"[^>]*Id="$id"""").find(docRels)!!.groupValues[1]
            return "word/" + target
        }
        val first = partOf("first", "header")
        val firstXml = String(parts[first]!!)
        // In line, as it was, in its paragraph centred as it was.
        assertTrue(firstXml.contains("<wp:inline"))
        assertFalse(firstXml.contains("<wp:anchor"))
        assertTrue(Regex("""<w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline""").containsMatchIn(firstXml))
        val firstRels = String(parts[first.replace("word/", "word/_rels/") + ".rels"]!!)
        val media = Regex("""Target="(media/[^"]+)"""").find(firstRels)!!.groupValues[1]
        assertTrue("the picture is packaged", parts["word/$media"]!!.contentEquals(png))
        assertTrue(String(parts["[Content_Types].xml"]!!).contains("""Extension="png""""))
        val back = DocxReader.read(ByteArrayInputStream(out))
        assertEquals(positions(doc), positions(back))
        assertEquals("ACME Ltd.", back.header?.plainText())
        assertTrue(back.differentFirstPage)
    }

    @Test
    fun headerPicturesGoToOdfAndBack() {
        val doc = DocxReader.read(ByteArrayInputStream(letter()))
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        val parts = entries(bytes)
        val styles = String(parts["styles.xml"]!!)
        // In the master page's header: in line in its paragraph (as-char), or anchored to it; their styles among styles.xml's automatic ones.
        assertTrue(Regex("""<style:header-first><text:p [^>]*><draw:frame draw:style-name="(MFr\d+)" text:anchor-type="as-char" svg:width="144(\.0+)?pt"""").containsMatchIn(styles))
        assertTrue(styles.contains("""style:horizontal-pos="right" style:horizontal-rel="page-content" style:vertical-pos="from-top" style:vertical-rel="page""""))
        val pictures = parts.keys.filter { it.startsWith("Pictures/") }
        assertEquals(3, pictures.size)
        assertTrue(String(parts["META-INF/manifest.xml"]!!).contains(pictures.first()))
        val back = OdtReader.read(bytes)
        assertEquals(positions(doc), positions(back))
        assertEquals("ACME Ltd.", back.header?.plainText())
        assertTrue("a first page's header holding only a picture is its own", back.differentFirstPage)
        assertFalse(back.differentOddEven)
        assertTrue(back.hfPictures[HfSlot.HEADER]!!.single().data.contentEquals(png))
    }

    @Test
    fun aCopyHasItsOwnHeaderPictures() {
        val doc = DocxReader.read(ByteArrayInputStream(letter()))
        val copy = doc.copyOf()
        val mine = doc.hfPictures[HfSlot.HEADER]!!.single()
        val theirs = copy.hfPictures[HfSlot.HEADER]!!.single()
        assertNotSame(mine, theirs)
        assertEquals(mine.floating, theirs.floating)
        theirs.floating!!.x = 40f
        assertEquals(0f, mine.floating!!.x, 0f)
        assertTrue(copy.hfPictures.keys.containsAll(listOf(HfSlot.FIRST_HEADER, HfSlot.FOOTER)))
        assertTrue(ImageBlock::class.java.isInstance(theirs))
    }

    @Test
    fun aHeaderReachingIntoTheBodyTakesItsRoom() {
        val doc = Document(mutableListOf())
        doc.addParagraph(com.therealityteam.aeropages.model.Paragraph.of("Body"))
        val pg = doc.page   // Letter, 1 in margins, header and footer 36 pt from the edges
        val line = doc.styles.resolve(com.therealityteam.aeropages.model.ParagraphStyle.NORMAL).sizePt * com.therealityteam.aeropages.model.PageMetrics.SINGLE_LINE
        fun room(page: Int = 1) = com.therealityteam.aeropages.model.HeaderFooters.room(doc, page, pg)
        assertTrue(room().none)
        // Three lines of header from 36 pt down: what passes the top margin.
        doc.header = com.therealityteam.aeropages.model.Paragraph.of("One\nTwo\nThree")
        assertEquals(36f + 3 * line - 72f, room().top, 0.01f)
        // A logo 48 tall in the first line: that line as tall as the logo.
        val logo = ImageBlock(png, "image/png", 96f, 48f, floating = FloatPlacement.inHeaderLine(0, com.therealityteam.aeropages.model.TextAlign.LEFT))
        doc.hfPictures[HfSlot.HEADER] = mutableListOf(logo)
        assertEquals(36f + 48f + 2 * line - 72f, room().top, 0.01f)
        // The logo alone: 84, 12 into the body.
        doc.header = null
        assertEquals(12f, room().top, 0.01f)
        // Floating in front of the text it takes nothing; above and below the text, what reaches past the margin.
        logo.floating = FloatPosition(0f, FloatFrame.MARGIN, null, 60f, FloatFrame.PAGE, null, FloatWrap.FRONT)
        assertTrue(room().none)
        logo.floating!!.wrap = FloatWrap.TOP_BOTTOM
        assertEquals(36f, room().top, 0.01f)
        doc.hfPictures.remove(HfSlot.HEADER)
        // A footer's picture 60 tall in its line, its foot at the footer's distance: 24 above the bottom margin.
        doc.hfPictures[HfSlot.FOOTER] = mutableListOf(ImageBlock(png, "image/png", 200f, 60f,
            floating = FloatPlacement.inHeaderLine(0, com.therealityteam.aeropages.model.TextAlign.CENTER)))
        assertEquals(24f, room().foot, 0.01f)
        // One floating across the text mid-page: a stretch the text steps over (from the body's top).
        doc.hfPictures[HfSlot.FOOTER] = mutableListOf(ImageBlock(png, "image/png", 468f, 100f,
            floating = FloatPosition(0f, FloatFrame.MARGIN, null, 300f, FloatFrame.PAGE, null, FloatWrap.TOP_BOTTOM)))
        val span = room().spans.single()
        assertEquals(228f, span.top, 0.01f); assertEquals(328f, span.bottom, 0.01f); assertEquals(setOf(0), span.columns)
        // A narrow one the text could go beside (square wrapping) leaves it be, as in the body.
        doc.hfPictures[HfSlot.FOOTER]!!.single().apply { widthPt = 72f; floating!!.wrap = FloatWrap.SQUARE }
        assertTrue(room().spans.isEmpty())
        // Only on the pages that header shows on: the first page has its own (none).
        doc.header = com.therealityteam.aeropages.model.Paragraph.of("One\nTwo\nThree")
        doc.differentFirstPage = true
        assertEquals(0f, room(1).top, 0f)
        assertEquals(36f + 3 * line - 72f, room(2).top, 0.01f)
    }

    /** A letterhead whose logo is a paragraph of its own (centred) above the address's two lines (left). */
    private fun logoAboveAddress(): ByteArray {
        val header = """<w:hdr $ns><w:p><w:pPr><w:jc w:val="center"/></w:pPr>${inline(144, 48)}</w:p>""" +
            """<w:p><w:r><w:t>ACME Ltd.</w:t></w:r></w:p><w:p><w:r><w:t>1 Market Street</w:t></w:r></w:p></w:hdr>"""
        val sect = """<w:sectPr><w:headerReference w:type="default" r:id="rIdH"/><w:pgSz w:w="12240" w:h="15840"/>""" +
            """<w:pgMar w:top="1440" w:right="1800" w:bottom="1440" w:left="1800" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr>"""
        val doc = """<?xml version="1.0" encoding="UTF-8"?><w:document $ns><w:body><w:p><w:r><w:t>Dear reader,</w:t></w:r></w:p>$sect</w:body></w:document>"""
        val docRels = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rIdH" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/></Relationships>"""
        return zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""".toByteArray(),
            "word/document.xml" to doc.toByteArray(),
            "word/_rels/document.xml.rels" to docRels.toByteArray(),
            "word/header1.xml" to header.toByteArray(), "word/_rels/header1.xml.rels" to rels.toByteArray(),
            "word/media/logo.png" to png,
        ))
    }

    @Test
    fun aLogoAboveTheAddressIsALineOfItsOwn() {
        val doc = DocxReader.read(ByteArrayInputStream(logoAboveAddress()))
        // The logo's paragraph is the header's first line; the address's alignment is the header's.
        assertEquals("\nACME Ltd.\n1 Market Street", doc.header?.plainText())
        assertEquals(com.therealityteam.aeropages.model.TextAlign.LEFT, doc.header?.format?.align)
        assertEquals(listOf(firstLogo), positions(doc)[HfSlot.HEADER])
        val line = doc.styles.resolve(com.therealityteam.aeropages.model.ParagraphStyle.NORMAL).sizePt * com.therealityteam.aeropages.model.PageMetrics.SINGLE_LINE
        val laid = com.therealityteam.aeropages.model.HeaderFooters.layout(doc, 1, 1, doc.page, true)
        assertEquals(listOf(36f, 84f, 84f + line), laid.map { it.top })
        assertEquals(listOf(48f, line, line), laid.map { it.height })
        // The logo centred in its line, the address at the left margin below it; the body starts below the header.
        assertEquals(listOf(234f), com.therealityteam.aeropages.model.HeaderFooters.across(laid[0], com.therealityteam.aeropages.model.TextAlign.LEFT, 0f, 90f, 432f).first)
        assertEquals(90f, com.therealityteam.aeropages.model.HeaderFooters.across(laid[1], com.therealityteam.aeropages.model.TextAlign.LEFT, 60f, 90f, 432f).second, 0.01f)
        assertEquals(84f + 2 * line - 72f, com.therealityteam.aeropages.model.HeaderFooters.room(doc, 1, doc.page).top, 0.01f)
        // Back to Word: three paragraphs, the logo in line in the first, centred.
        val out = ByteArrayOutputStream().also { DocxWriter.write(doc, it) }.toByteArray()
        val headerPart = entries(out).entries.single { it.key.startsWith("word/header") && it.key.endsWith(".xml") }.value.let { String(it) }
        assertEquals(3, Regex("<w:p>").findAll(headerPart).count())
        assertTrue(Regex("""<w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline""").containsMatchIn(headerPart))
        val back = DocxReader.read(ByteArrayInputStream(out))
        assertEquals(doc.header?.plainText(), back.header?.plainText())
        assertEquals(positions(doc), positions(back))
        // And to ODF and back: the same lines.
        val odt = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray())
        assertEquals(doc.header?.plainText(), odt.header?.plainText())
        assertEquals(positions(doc), positions(odt))
        assertEquals(com.therealityteam.aeropages.model.TextAlign.LEFT, odt.header?.format?.align)
    }
}
