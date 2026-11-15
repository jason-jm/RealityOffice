package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CellBorder
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.PageBorderPages
import com.therealityteam.aeropages.model.PageBorders
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Word's page borders: where they stand, docx, ODT and .doc, and the editor's command. */
class PageBordersTest {

    private val red = CellBorder(LineStyle.DASHED, 1.5f, 0xFFC00000)

    private fun doc(b: PageBorders?) = Document().apply {
        page.marginTopPt = 72f; page.marginBottomPt = 72f; page.marginLeftPt = 90f; page.marginRightPt = 90f
        repeat(3) { blocks.add(ParagraphBlock(Paragraph.of("Page borders, as Word draws them around the page."))) }
        pageBorders = b
    }

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        }
        return ""
    }

    @Test
    fun theLinesStandFromTheEdgeOrTheText() {
        val pg = PageSetup(widthPt = 600f, heightPt = 800f, marginTopPt = 72f, marginBottomPt = 72f, marginLeftPt = 90f, marginRightPt = 90f)
        val line = CellBorder(LineStyle.THIN, 2f)
        assertArrayEquals("24 pt from the page's edge, to each line's middle", floatArrayOf(25f, 25f, 575f, 775f), PageBorders.box(line).box(pg), 0.001f)
        val fromText = PageBorders.box(line).copy(fromText = true, spaceTop = 4f, spaceBottom = 4f, spaceLeft = 4f, spaceRight = 4f)
        assertArrayEquals("4 pt out from the text", floatArrayOf(85f, 67f, 515f, 733f), fromText.box(pg), 0.001f)
        assertEquals("a double line is three widths thick", 3f, CellBorder(LineStyle.DOUBLE, 1f).thicknessPt, 0f)
        val firstOnly = PageBorders.box(line).copy(pages = PageBorderPages.FIRST)
        assertTrue(firstOnly.shows(0)); assertFalse(firstOnly.shows(1))
        assertTrue(PageBorders.box(line).copy(pages = PageBorderPages.NOT_FIRST).shows(3))
        assertFalse("nothing to show", PageBorders().shows(0))
    }

    @Test
    fun docxKeepsEverySide() {
        val b = PageBorders(top = red, bottom = red, left = CellBorder(LineStyle.DOUBLE, 0.75f), right = null,
            spaceTop = 20f, spaceBottom = 30f, spaceLeft = 24f, spaceRight = 24f, shadow = true, pages = PageBorderPages.NOT_FIRST)
        val out = ByteArrayOutputStream().also { DocxWriter.write(doc(b), it) }.toByteArray()
        val xml = part(out, "word/document.xml")
        assertTrue(xml.contains("""<w:pgBorders w:offsetFrom="page" w:display="notFirstPage">"""))
        assertTrue(xml.contains("""<w:top w:val="dashed" w:sz="12" w:space="20" w:color="C00000" w:shadow="1"/>"""))
        assertFalse("no right side", xml.contains("<w:right w:val"))
        assertEquals(b, DocxReader.read(ByteArrayInputStream(out)).pageBorders)
        val fromText = PageBorders.box(red).copy(fromText = true, spaceTop = 4f, spaceBottom = 4f, spaceLeft = 4f, spaceRight = 4f)
        val back = DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(doc(fromText), it) }.toByteArray()))
        assertEquals(fromText, back.pageBorders)
        assertNull("none written, none read", DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(doc(null), it) }.toByteArray())).pageBorders)
    }

    @Test
    fun odtSplitsTheMarginIntoBorderAndPaddingAndBack() {
        val b = PageBorders(top = red, bottom = red, left = red, right = CellBorder(LineStyle.DOUBLE, 0.75f), spaceTop = 20f, shadow = true)
        val source = doc(b)
        val out = ByteArrayOutputStream().also { OdtWriter.write(source, it) }.toByteArray()
        val styles = part(out, "styles.xml")
        assertTrue(styles.contains("""fo:margin-top="20.00pt" fo:padding-top="50.50pt" fo:border-top="1.50pt dashed #C00000""""))
        assertTrue(styles.contains("style:border-line-width-right"))
        assertTrue(styles.contains("style:shadow=\"#000000"))
        val back = OdtReader.read(out)
        assertEquals(b.copy(pages = PageBorderPages.ALL), back.pageBorders)
        // The text keeps its place.
        listOf(back.page.marginTopPt to 72f, back.page.marginBottomPt to 72f, back.page.marginLeftPt to 90f, back.page.marginRightPt to 90f)
            .forEach { (got, want) -> assertEquals(want, got, 0.02f) }
        // Measured from the text, ODT keeps the lines where they were (as from the page's edge).
        val fromText = PageBorders.box(red).copy(fromText = true, spaceTop = 4f, spaceBottom = 4f, spaceLeft = 4f, spaceRight = 4f)
        val again = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(doc(fromText), it) }.toByteArray())
        assertArrayEquals(fromText.box(source.page), again.pageBorders!!.box(again.page), 0.05f)
    }

    /**
     * LibreOffice's .doc of [writeSample]'s docx: a red dashed box 20 pt from the edge (sprmSBrcTop80 and sprmSBrcTop,
     * sprmSPgbProp 0x0020: from the page's edge). LibreOffice has no "all but the first page": it writes all pages.
     */
    @Test
    fun wordsBinaryFormatToo() {
        val f = File("src/test/resources/doc/page-borders.doc")
        if (!f.exists()) return
        val b = DocReader.read(f.inputStream()).pageBorders!!
        assertEquals(LineStyle.DASHED, b.top!!.style)
        assertEquals(1.5f, b.top!!.widthPt, 0.01f)
        assertEquals(0xFFC00000, b.top!!.color)
        assertEquals(20f, b.spaceTop, 0.01f)
        assertFalse(b.fromText)
        assertEquals(PageBorderPages.ALL, b.pages)
    }

    @Test
    fun theCommandIsOneUndoStep() {
        val e = DocumentEditor().apply { load(doc(null)) }
        e.setPageBorders(PageBorders.box(red))
        assertEquals(PageBorders.box(red), e.document.pageBorders)
        e.setPageBorders(PageBorders())
        assertNull("no sides: none", e.document.pageBorders)
        e.undo()
        assertEquals(PageBorders.box(red), e.document.pageBorders)
        e.undo()
        assertNull(e.document.pageBorders)
    }

    /** With `-Daero.sampleDir`: the document as docx and ODT, for LibreOffice (and its .doc above). */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val b = PageBorders(top = red, bottom = red, left = red, right = red, spaceTop = 20f, spaceBottom = 20f, spaceLeft = 20f, spaceRight = 20f,
            pages = PageBorderPages.NOT_FIRST)
        val d = doc(b).apply { repeat(60) { blocks.add(ParagraphBlock(Paragraph.of("Second page and on: a red dashed box 20 pt from the edge."))) } }
        File(dir, "page-borders.docx").outputStream().use { DocxWriter.write(d, it) }
        val shadow = doc(PageBorders.box(CellBorder(LineStyle.THIN, 1f, 0xFF1F4E79), shadow = true))
        File(dir, "page-borders-shadow.docx").outputStream().use { DocxWriter.write(shadow, it) }
        File(dir, "page-borders-shadow.odt").outputStream().use { OdtWriter.write(shadow, it) }
    }
}
