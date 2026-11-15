package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.BoxEdge
import com.therealityteam.aeropages.model.CellBorder
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphBorders
import com.therealityteam.aeropages.model.ParagraphBoxes
import com.therealityteam.aeropages.model.StyleDef
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Word's paragraph borders and shading: boxes, the room they take, the commands, docx, ODT and .doc. */
class ParagraphBordersTest {

    private val blue = CellBorder(LineStyle.THIN, 1f, 0xFF2F5496)

    private fun para(text: String, b: ParagraphBorders? = null, fill: Long? = null) =
        Paragraph.of(text).also { it.format.borders = b; it.format.shading = fill }

    private fun doc(vararg ps: Paragraph) = Document(ps.map { ParagraphBlock(it) }.toMutableList<com.therealityteam.aeropages.model.Block>())

    private fun part(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        }
        return ""
    }

    @Test
    fun likeNeighboursAreOneBox() {
        val box = ParagraphBorders.box(blue).copy(between = blue)
        val a = para("a", box); val b = para("b", box); val c = para("c", box)
        val alone = para("d", box).also { it.format.indentLeftPt = 36f }
        val plain = para("e")
        val filled = para("f", fill = 0xFFDEEAF6); val filled2 = para("g", fill = 0xFFDEEAF6)
        val edges = ParagraphBoxes.edges(doc(a, b, c, alone, plain, filled, filled2))
        assertEquals(BoxEdge(first = true, last = false), edges[a])
        assertEquals(BoxEdge(first = false, last = false), edges[b])
        assertEquals(BoxEdge(first = false, last = true), edges[c])
        assertNull("other indents: a box of its own", edges[alone])
        assertNull(edges[plain])
        assertEquals("shading alone makes a box too", BoxEdge(first = true, last = false), edges[filled])
        // A table between ends a box.
        val x = para("x", box); val y = para("y", box)
        val t = TableBlock(Table(mutableListOf(TableRow(mutableListOf(TableCell())))))
        val d = Document(mutableListOf(ParagraphBlock(x), t, ParagraphBlock(y)))
        assertTrue(ParagraphBoxes.edges(d).isEmpty())
    }

    @Test
    fun theLinesTakeTheirRoomAboveAndBelow() {
        val box = ParagraphBorders.box(blue).copy(between = blue, spaceTop = 4f, spaceBottom = 2f)
        val f = para("a", box).format.also { it.spaceBeforePt = 6f; it.spaceAfterPt = 8f }
        assertEquals("alone: the top line and its space", 6f + 1f + 4f, ParagraphBoxes.beforePt(f, null), 0.001f)
        assertEquals(8f + 1f + 2f, ParagraphBoxes.afterPt(f, null), 0.001f)
        assertEquals("inside a box: the between line over it, no bottom", 6f + 1f + 4f, ParagraphBoxes.beforePt(f, BoxEdge(first = false, last = false)), 0.001f)
        assertEquals(8f, ParagraphBoxes.afterPt(f, BoxEdge(first = false, last = false)), 0.001f)
        val noBetween = f.copy(borders = box.copy(between = null))
        assertEquals(6f, ParagraphBoxes.beforePt(noBetween, BoxEdge(first = false, last = true)), 0.001f)
        assertEquals("a double line is three widths", 6f + 3f + 4f, ParagraphBoxes.beforePt(f.copy(borders = box.copy(top = CellBorder(LineStyle.DOUBLE, 1f))), null), 0.001f)
    }

    @Test
    fun theMenusCommandsToggleAndUndoInOneStep() {
        val e = DocumentEditor().apply { load(doc(para("one"), para("two"), para("three"))) }
        e.selectRange(DocumentEditor.DocPos(0, 0), DocumentEditor.DocPos(1, 1))
        e.applyParagraphBorders(DocumentEditor.ParaBorderPreset.BOTTOM, blue)
        val two = (e.document.blocks[1] as ParagraphBlock).paragraph
        assertEquals(blue, two.format.borders?.bottom)
        assertEquals("the selection shares the result", (e.document.blocks[0] as ParagraphBlock).paragraph.format.borders, two.format.borders)
        e.applyParagraphBorders(DocumentEditor.ParaBorderPreset.BOTTOM, blue)
        assertNull("again: off", e.paragraphBorders)
        e.applyParagraphBorders(DocumentEditor.ParaBorderPreset.ALL, blue)
        assertEquals(ParagraphBorders.box(blue).copy(between = blue), e.paragraphBorders)
        e.applyParagraphBorders(DocumentEditor.ParaBorderPreset.INSIDE, blue)
        assertNull(e.paragraphBorders?.between)
        e.setParagraphShading(0xFFFFF2CC)
        e.setParagraphShading(0xFFFFF2CC)   // the same: no step
        e.undo()
        assertNull("one step back: no shading", e.paragraphShading)
        e.undo()
        assertEquals(blue, e.paragraphBorders?.between)
        // A horizontal line: an empty paragraph with a line under it.
        e.setSelection(2, 0, 0)
        e.insertHorizontalLine()
        val line = (e.document.blocks[3] as ParagraphBlock).paragraph
        assertTrue(line.isEmpty()); assertEquals(0.75f, line.format.borders!!.bottom!!.widthPt, 0f); assertNull(line.format.borders!!.top)
        e.undo()
        assertEquals(3, e.document.blocks.size)
    }

    @Test
    fun docxKeepsBordersShadingAndAStylesLine() {
        val d = doc(para("Boxed", ParagraphBorders.box(blue).copy(between = blue, spaceTop = 2f, spaceLeft = 6f), 0xFFDEEAF6), para("Plain"))
        // A style with a bottom line (Word 2007's Title), and a paragraph of it that turns it off.
        d.styles.put(StyleDef("Ruled", "Ruled", basedOn = d.styles.idOf(com.therealityteam.aeropages.model.ParagraphStyle.NORMAL),
            borders = ParagraphBorders(bottom = CellBorder(LineStyle.THIN, 0.5f), spaceBottom = 4f)))
        d.blocks.add(ParagraphBlock(para("Ruled one").also { it.format.rawStyleId = "Ruled"; it.format.borders = d.styles["Ruled"]!!.borders }))
        d.blocks.add(ParagraphBlock(para("Ruled, line off").also { it.format.rawStyleId = "Ruled" }))
        val out = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
        val xml = part(out, "word/document.xml")
        assertTrue(xml.contains("""<w:pBdr><w:top w:val="single" w:sz="8" w:space="2" w:color="2F5496"/><w:left w:val="single" w:sz="8" w:space="6" w:color="2F5496"/>"""))
        assertTrue(xml.contains("""<w:shd w:val="clear" w:color="auto" w:fill="DEEAF6"/>"""))
        assertTrue("turning the style's off", xml.contains("""<w:pBdr><w:top w:val="nil"/><w:left w:val="nil"/><w:bottom w:val="nil"/>"""))
        assertTrue(part(out, "word/styles.xml").contains("""<w:bottom w:val="single" w:sz="4" w:space="4" w:color="auto"/>"""))
        val back = DocxReader.read(ByteArrayInputStream(out))
        val ps = back.blocks.map { (it as ParagraphBlock).paragraph }
        assertEquals(d.blocks.map { (it as ParagraphBlock).paragraph.format.borders }, ps.map { it.format.borders })
        assertEquals(0xFFDEEAF6, ps[0].format.shading)
        assertNull(ps[1].format.shading)
        assertEquals("the style keeps its line", 4f, back.styles["Ruled"]!!.borders!!.spaceBottom, 0f)
    }

    @Test
    fun odtSplitsTheIndentIntoMarginBorderAndPadding() {
        val box = ParagraphBorders.box(blue).copy(spaceLeft = 4f, spaceRight = 4f, spaceTop = 1f, spaceBottom = 1f)
        val p = para("Boxed", box, 0xFFDEEAF6).also { it.format.indentLeftPt = 36f }
        val out = ByteArrayOutputStream().also { OdtWriter.write(doc(p, para("Plain")), it) }.toByteArray()
        val content = part(out, "content.xml")
        assertTrue("the margin outside the line and its space", content.contains("""fo:margin-left="31.00pt""""))
        assertTrue(content.contains("""fo:border-left="1.00pt solid #2F5496" fo:padding-left="4.00pt""""))
        assertTrue(content.contains("""fo:background-color="#DEEAF6""""))
        val back = OdtReader.read(out)
        val f = (back.blocks[0] as ParagraphBlock).paragraph.format
        assertEquals("the text keeps its indent", 36f, f.indentLeftPt, 0.01f)
        assertEquals(box, f.borders)
        assertEquals(0xFFDEEAF6, f.shading)
        assertNull((back.blocks[1] as ParagraphBlock).paragraph.format.borders)
    }

    /** LibreOffice's .doc of [writeSample]'s docx: the box with the line between (BRC sprms) and the fill (SHD). */
    @Test
    fun wordsBinaryFormatToo() {
        val f = File("src/test/resources/doc/para-borders.doc")
        if (!f.exists()) return
        val ps = DocReader.read(f.inputStream()).blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }
        val boxed = ps.first { it.plainText().startsWith("Boxed") }.format
        assertEquals(LineStyle.THIN, boxed.borders!!.top!!.style)
        assertEquals(1f, boxed.borders!!.top!!.widthPt, 0.01f)
        assertEquals(0xFF2F5496, boxed.borders!!.left!!.color)
        assertEquals(0xFFDEEAF6, boxed.shading)
        val ruled = ps.first { it.plainText().startsWith("A line under") }.format
        assertTrue(ruled.borders!!.bottom != null); assertNull(ruled.borders!!.top)
        assertFalse(ps.first { it.plainText().startsWith("Plain") }.format.borders?.none == false)
    }

    /** With `-Daero.sampleDir`: boxed paragraphs (one box with the line between), a ruled one and a filled one, as docx and ODT. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val box = ParagraphBorders.box(blue).copy(between = blue)
        val d = doc(
            para("Plain paragraph before the box."),
            para("Boxed paragraph one: the top line over it, the sides down the whole box.", box, 0xFFDEEAF6),
            para("Boxed paragraph two: the line between above it, the bottom line under it.", box, 0xFFDEEAF6),
            para("A line under this heading-like paragraph.", ParagraphBorders(bottom = CellBorder(LineStyle.THIN, 1.5f, 0xFFC00000), spaceBottom = 2f)),
            para("Plain paragraph after.", fill = null),
            para("A filled paragraph, no lines.", fill = 0xFFFFF2CC),
        )
        File(dir, "para-borders.docx").outputStream().use { DocxWriter.write(d, it) }
        File(dir, "para-borders.odt").outputStream().use { OdtWriter.write(d, it) }
    }
}
