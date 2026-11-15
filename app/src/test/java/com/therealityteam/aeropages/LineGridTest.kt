package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.FloatFrame
import com.therealityteam.aeropages.model.FloatPosition
import com.therealityteam.aeropages.model.FloatWrap
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextBoxBlock
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Chinese documents' line grid (Word's docGrid, a section's line pitch) and the faces' own single lines: a body line
 * takes whole pitches, one off the grid keeps its own height; an empty paragraph is as tall as its mark; a text box
 * that does not wrap its text; character spacing.
 */
class LineGridTest {

    private fun para(text: String, size: Float, family: String? = null, spacing: Float = 1f, snap: Boolean = true) =
        Paragraph(mutableListOf(TextRun(text, CharacterFormat(fontSizePt = size, fontFamily = family))), ParagraphFormat(spaceAfterPt = 0f, lineSpacing = spacing, snapToGrid = snap))

    @Test
    fun linesTakeWholePitchesOfTheGrid() {
        // The classic Chinese faces are one em a line, Times New Roman 1.15, Calibri 1.22, an unknown face 1.2.
        assertEquals(1f, PageMetrics.singleLine("宋体")); assertEquals(1f, PageMetrics.singleLine("SimHei")); assertEquals(1f, PageMetrics.singleLine("仿宋_GB2312"))
        assertEquals(1.15f, PageMetrics.singleLine("Times New Roman")); assertEquals(1.22f, PageMetrics.singleLine("Calibri"))
        assertEquals(PageMetrics.SINGLE_LINE, PageMetrics.singleLine(null)); assertEquals(PageMetrics.SINGLE_LINE, PageMetrics.singleLine("Some Face"))
        // A cover's name lines: 22 pt 黑体 at triple spacing, 66 pt apart (as LibreOffice and Word set them).
        assertEquals(66f, PageMetrics.pitchPt(para("姓名", 22f, "黑体", 3f)), 0.01f)
        // On a 15.6 pt grid: 五号 宋体 one pitch, a 16 pt line of an unknown face (19.2 pt) two, 1.5 lines of one pitch 23.4.
        val grid = 15.6f
        assertEquals(15.6f, PageMetrics.pitchPt(para("正文", 10.5f, "宋体"), grid), 0.01f)
        assertEquals(31.2f, PageMetrics.pitchPt(para("标题", 16f), grid), 0.01f)
        assertEquals(23.4f, PageMetrics.pitchPt(para("正文", 10.5f, "宋体", 1.5f), grid), 0.01f)
        // Off the grid, or no grid: its own height.
        assertEquals(10.5f, PageMetrics.pitchPt(para("正文", 10.5f, "宋体", snap = false), grid), 0.01f)
        assertEquals(10.5f, PageMetrics.pitchPt(para("正文", 10.5f, "宋体")), 0.01f)
        // An empty paragraph's line is its mark's.
        assertEquals(26f * PageMetrics.SINGLE_LINE, PageMetrics.pitchPt(Paragraph(mutableListOf(TextRun("", CharacterFormat(fontSizePt = 26f))), ParagraphFormat(lineSpacing = 1f))), 0.01f)
        // Exactly 21 pt: whatever the letters and the grid; at least 21 pt: a taller line keeps its height.
        val exact = para("投标", 12f, "宋体").also { it.format.lineHeightPt = 21f; it.format.lineExact = true }
        assertEquals(21f, PageMetrics.pitchPt(exact, grid), 0.01f)
        assertEquals(21f, PageMetrics.pitchPt(exact.also { it.runs[0].format.fontSizePt = 30f }, grid), 0.01f)
        val least = para("投标", 12f, "宋体").also { it.format.lineHeightPt = 21f; it.format.lineExact = false }
        assertEquals(21f, PageMetrics.pitchPt(least), 0.01f)
        assertEquals(30f, PageMetrics.pitchPt(least.also { it.runs[0].format.fontSizePt = 30f }), 0.01f)
    }

    /** A grid document: a body paragraph off the grid, an empty paragraph with a 26 pt mark, spaced and small-capital text, a box that does not wrap. */
    private fun doc(): Document {
        val d = Document(mutableListOf())
        d.page.linePitchPt = 15.6f
        d.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("学校名称", CharacterFormat(fontSizePt = 26f, letterSpacingPt = 11f))), ParagraphFormat(align = TextAlign.CENTER))))
        d.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("", CharacterFormat(fontSizePt = 26f))), ParagraphFormat())))
        d.blocks.add(ParagraphBlock(para("Off the grid", 12f, snap = false)))
        d.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Small capitals", CharacterFormat(smallCaps = true, highlight = 0xFFFFFF00))))))
        d.blocks.add(ParagraphBlock(para("Exactly 21 pt", 12f).also { it.format.lineHeightPt = 21f; it.format.lineExact = true }))
        d.blocks.add(ParagraphBlock(para("At least 18 pt", 12f).also { it.format.lineHeightPt = 18f; it.format.lineExact = false }))
        d.blocks.add(TextBoxBlock(mutableListOf(Paragraph(mutableListOf(TextRun("[请输入专业]论文", CharacterFormat(fontSizePt = 16f))), ParagraphFormat(align = TextAlign.CENTER))), 137.5f, 38.4f,
            SlideShape(ShapeType.TEXTBOX, fill = null, border = null).also { it.noWrap = true },
            FloatPosition(141.5f, FloatFrame.COLUMN, null, 0f, FloatFrame.PARAGRAPH, null, FloatWrap.FRONT)))
        d.addParagraph(Paragraph.of("End"))
        return d
    }

    private fun check(back: Document, where: String) {
        assertEquals(where, 15.6f, back.page.linePitchPt!!, 0.05f)
        val paras = back.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }
        assertEquals(where, 11f, paras[0].runs.first().format.letterSpacingPt!!, 0.05f)
        assertEquals(where, 26f, paras[1].runs.first().format.fontSizePt!!, 0.01f)
        assertFalse(where, paras.first { it.plainText() == "Off the grid" }.format.snapToGrid)
        assertTrue(where, paras[0].format.snapToGrid)
        assertTrue(where, back.blocks.filterIsInstance<TextBoxBlock>().single().shape!!.noWrap)
        lineHeights(paras, where)
    }

    private fun lineHeights(paras: List<Paragraph>, where: String) {
        val exact = paras.first { it.plainText() == "Exactly 21 pt" }.format
        assertEquals(where, 21f, exact.lineHeightPt!!, 0.05f); assertTrue(where, exact.lineExact)
        val least = paras.first { it.plainText() == "At least 18 pt" }.format
        assertEquals(where, 18f, least.lineHeightPt!!, 0.05f); assertFalse(where, least.lineExact)
        assertNull(where, paras.first { it.plainText() == "Off the grid" }.format.lineHeightPt)
    }

    @Test
    fun wordAndOdfKeepTheGrid() {
        val d = doc()
        val docx = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
        check(DocxReader.read(ByteArrayInputStream(docx)), "docx")
        check(OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()), "odt")
        // No grid stays none.
        val plain = Document.blank()
        assertNull(DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(plain, it) }.toByteArray())).page.linePitchPt)
    }

    @Test
    fun wordNinetySevenKeepsThemToo() {
        // LibreOffice's .doc of the document above (see writeSample).
        val back = DocReader.read(File("src/test/resources/doc/line-grid.doc").inputStream())
        assertEquals(15.6f, back.page.linePitchPt!!, 0.05f)
        val paras = back.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }
        assertEquals(11f, paras.first { it.plainText() == "学校名称" }.runs.first().format.letterSpacingPt!!, 0.05f)
        // The empty paragraph is as tall as its mark, not its style's 10 or 12 pt.
        assertEquals(26f, paras[1].runs.first().format.fontSizePt!!, 0.01f)
        assertFalse(paras.first { it.plainText() == "Off the grid" }.format.snapToGrid)
        val caps = paras.first { it.plainText() == "Small capitals" }.runs.first().format
        assertTrue(caps.smallCaps); assertEquals(0xFFFFFF00, caps.highlight)
        // (LibreOffice writes its .doc text boxes wrapping; WPS's cover templates set WrapText none, which reads as noWrap.)
        assertEquals(1, back.blocks.filterIsInstance<TextBoxBlock>().size)
        lineHeights(paras, "doc")
    }

    /** With `-Daero.sampleDir`: the document as docx, for LibreOffice to make the .doc above of. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "line-grid.docx").outputStream().use { DocxWriter.write(doc(), it) }
        File(dir, "line-grid.odt").outputStream().use { OdtWriter.write(doc(), it) }
    }
}
