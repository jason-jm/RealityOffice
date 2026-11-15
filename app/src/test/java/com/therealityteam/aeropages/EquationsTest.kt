package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.EstimateMathFont
import com.therealityteam.aeropages.format.MathItem
import com.therealityteam.aeropages.format.MathLayout
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.EquationBlock
import com.therealityteam.aeropages.model.FracKind
import com.therealityteam.aeropages.model.InlineMath
import com.therealityteam.aeropages.model.MathNode
import com.therealityteam.aeropages.model.MathNode.Delimited
import com.therealityteam.aeropages.model.MathNode.Frac
import com.therealityteam.aeropages.model.MathNode.Matrix
import com.therealityteam.aeropages.model.MathNode.Nary
import com.therealityteam.aeropages.model.MathNode.Radical
import com.therealityteam.aeropages.model.MathNode.Row
import com.therealityteam.aeropages.model.MathNode.Run
import com.therealityteam.aeropages.model.MathNode.Scripts
import com.therealityteam.aeropages.model.MathText
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Equations: Word's OMML and ODF's MathML both ways, the typesetter, and how the editor treats them. */
class EquationsTest {

    /** x = (−b ± √(b² − 4ac)) / 2a */
    private val quadratic = Row(listOf(
        Run("x"), Run("="),
        Frac(Row(listOf(Run("-b±"), Radical(Row(listOf(Scripts(Run("b"), null, Run("2")), Run("-4ac")))))), Run("2a")),
    ))
    /** ∑_{i=1}^{n} i² */
    private val sum = Nary("∑", Run("i=1"), Run("n"), Scripts(Run("i"), null, Run("2")), under = true)
    /** [a b; c d] */
    private val matrix = Delimited("[", "]", listOf(Matrix(listOf(listOf(Run("a"), Run("b")), listOf(Run("c"), Run("d"))))))

    private fun docx(body: String): ByteArray {
        val xml = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
            """xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><w:body>$body</w:body></w:document>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry() }
        return bos.toByteArray()
    }

    @Test
    fun wordsEquationsAreReadDisplayedAndInline() {
        // As Word writes them: a displayed quadratic formula, and "where a≠0" with an equation in the text.
        val display = """<w:p><m:oMathPara><m:oMathParaPr><m:jc m:val="center"/></m:oMathParaPr><m:oMath>""" +
            """<m:r><m:t>x=</m:t></m:r><m:f><m:num><m:r><m:t>-b±</m:t></m:r><m:rad><m:radPr><m:degHide m:val="1"/></m:radPr><m:deg/><m:e>""" +
            """<m:sSup><m:e><m:r><m:t>b</m:t></m:r></m:e><m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup><m:r><m:t>-4ac</m:t></m:r></m:e></m:rad></m:num>""" +
            """<m:den><m:r><m:t>2a</m:t></m:r></m:den></m:f></m:oMath></m:oMathPara></w:p>"""
        val inline = """<w:p><w:r><w:t xml:space="preserve">where </w:t></w:r><m:oMath><m:r><w:rPr><w:sz w:val="24"/></w:rPr><m:t>a≠0</m:t></m:r></m:oMath>""" +
            """<w:r><w:t xml:space="preserve"> holds, and </w:t></w:r><m:oMath><m:nary><m:naryPr><m:chr m:val="∑"/><m:limLoc m:val="undOvr"/></m:naryPr>""" +
            """<m:sub><m:r><m:t>i</m:t></m:r></m:sub><m:sup><m:r><m:t>n</m:t></m:r></m:sup><m:e><m:r><m:t>i</m:t></m:r></m:e></m:nary></m:oMath></w:p>"""
        val integral = """<w:p><m:oMathPara><m:oMath><m:nary><m:sub><m:r><m:t>0</m:t></m:r></m:sub><m:sup><m:r><m:t>1</m:t></m:r></m:sup>""" +
            """<m:e><m:r><m:t>f</m:t></m:r><m:d><m:e><m:r><m:t>x</m:t></m:r></m:e></m:d><m:r><m:t>dx</m:t></m:r></m:e></m:nary></m:oMath></m:oMathPara></w:p>"""
        val doc = DocxReader.read(ByteArrayInputStream(docx(display + inline + integral)))
        val eq = doc.blocks[0] as EquationBlock
        assertEquals(TextAlign.CENTER, eq.align)
        assertEquals("x=(-b±√(b^2-4ac))/2a", MathText.linear(eq.math))
        val p = (doc.blocks[1] as ParagraphBlock).paragraph
        assertEquals("the text spells the equations (their scripts raised and lowered)", "where a≠0 holds, and ∑in i", p.plainText())
        val a = p.runs.first { it.text.startsWith("a") }
        assertEquals("letters in italic, from the run's own size", true, a.format.italic)
        assertEquals(12f, a.format.fontSizePt)
        assertTrue(p.runs.filter { it.text == "i" || it.text == "n" }.any { it.format.subscript } && p.runs.any { it.format.superscript })
        // An n-ary with no m:chr is an integral, its limits beside it.
        val int = (doc.blocks[2] as EquationBlock).math as Nary
        assertEquals("∫", int.op)
        assertFalse(int.under)
        assertEquals(Delimited("(", ")", listOf(Run("x"))), (int.body as Row).items[1])
    }

    @Test
    fun equationsGoBackToWordAsTheyCame() {
        val doc = Document(mutableListOf())
        doc.blocks.add(EquationBlock(quadratic))
        doc.blocks.add(EquationBlock(matrix, TextAlign.LEFT, 14f))
        val inline = InlineMath(sum)
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Total: ")).apply { addAll(MathText.runs(inline, CharacterFormat(fontSizePt = 11f))) })))
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val xml = String(OfficeXml.unzip(bos.toByteArray())["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\""))
        assertTrue(xml.contains("<m:oMathPara><m:oMathParaPr><m:jc m:val=\"center\"/></m:oMathParaPr><m:oMath>"))
        assertTrue(xml.contains("""<m:rad><m:radPr><m:degHide m:val="1"/></m:radPr><m:deg></m:deg>"""))
        assertTrue("the size rides on the runs", xml.contains("""<w:sz w:val="28"/>"""))
        assertTrue(xml.contains("""<m:naryPr><m:chr m:val="∑"/><m:limLoc m:val="undOvr"/></m:naryPr>"""))
        val back = DocxReader.read(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(quadratic, (back.blocks[0] as EquationBlock).math)
        assertEquals(matrix, (back.blocks[1] as EquationBlock).math)
        assertEquals(TextAlign.LEFT, (back.blocks[1] as EquationBlock).align)
        assertEquals(14f, (back.blocks[1] as EquationBlock).sizePt)
        val p = (back.blocks[2] as ParagraphBlock).paragraph
        assertEquals(sum, p.runs.last().format.math!!.math)
    }

    @Test
    fun typingNeverJoinsAnEquationAndEditingOneMakesItText() {
        val e = DocumentEditor()
        val inline = InlineMath(Scripts(Run("x"), null, Run("2")))
        val p = Paragraph(mutableListOf(TextRun("Let ")).apply { addAll(MathText.runs(inline, CharacterFormat())) })
        e.load(Document(mutableListOf(ParagraphBlock(p))))
        val text = p.plainText()
        assertEquals("Let x2", text)
        e.replaceText(0, text, "$text is")
        assertNull("typed after the equation: plain text", p.runs.last().format.math)
        assertEquals(" is", p.runs.last().text)
        assertSame(inline, p.runs[1].format.math)
        // Still intact: it goes back as an equation.
        fun saved(): String { val bos = ByteArrayOutputStream(); DocxWriter.write(e.document, bos); return String(OfficeXml.unzip(bos.toByteArray())["word/document.xml"]!!, Charsets.UTF_8) }
        assertTrue(saved().contains("<m:oMath><m:sSup>"))
        // Typing inside it breaks it into text, formatted as it was.
        val now = p.plainText()
        e.replaceText(0, now, now.replace("x2", "xy2"))
        assertFalse(saved().contains("<m:oMath>"))
        assertTrue(saved().contains("Cambria Math"))
    }

    @Test
    fun aDisplayedEquationIsDeletedAsOneAndComesBackWithUndo() {
        val e = DocumentEditor()
        val eq = EquationBlock(sum)
        e.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("Before")), eq, ParagraphBlock(Paragraph.of("After")))))
        e.selectEquation(eq)
        assertSame(eq, e.selectedEquation)
        e.deleteEquation(eq)
        assertEquals(2, e.document.blocks.size)
        assertNull(e.selectedEquation)
        e.undo()
        assertEquals(sum, (e.document.blocks[1] as EquationBlock).math)
    }

    @Test
    fun theTypesetterStacksRaisesAndStretches() {
        val size = 10f
        val font = EstimateMathFont
        val x = MathLayout.layout(Run("x"), font, size)
        val frac = MathLayout.layout(Frac(Run("a"), Run("b")), font, size)
        assertTrue("a fraction reaches above and below the line", frac.ascent > x.ascent && frac.descent > 0f)
        val bar = frac.items.filterIsInstance<MathItem.Rule>().single()
        assertEquals("its bar on the math axis", -0.25f * size, bar.y + bar.h / 2, 0.01f)
        val glyphs = frac.items.filterIsInstance<MathItem.Glyphs>()
        assertTrue("numerator over the bar, denominator under it", glyphs.first { it.text == "a" }.y < bar.y && glyphs.first { it.text == "b" }.y > bar.y + bar.h)
        val sq = MathLayout.layout(Scripts(Run("x"), Run("i"), Run("2")), font, size).items.filterIsInstance<MathItem.Glyphs>()
        assertTrue("superscript raised, subscript lowered, both smaller", sq[1].y < 0f && sq[2].y > 0f && sq[1].size < size)
        val root = MathLayout.layout(Radical(Run("x")), font, size)
        assertTrue("the radical's sign is drawn", root.items.any { it is MathItem.Stroke } && root.width > x.width)
        val big = MathLayout.layout(sum, font, size)
        val ops = big.items.filterIsInstance<MathItem.Glyphs>()
        val op = ops.first { it.text == "∑" }
        assertTrue("a displayed sum is set larger, its limits under and over it", op.size > size &&
            ops.first { it.text == "n" }.y < op.y - 0.6f * size && ops.first { it.text == "i" && it.size < size }.y > op.y)
        val tall = MathLayout.layout(Delimited("(", ")", listOf(Frac(Run("a"), Run("b")))), font, size)
        val paren = tall.items.filterIsInstance<MathItem.Stroke>()
        assertEquals("brackets round a fraction stretch to it", 2, paren.size)
        val m = MathLayout.layout(matrix, font, size)
        assertTrue("a two-row matrix is about two lines tall", m.height > 1.8f * size)
        // A displayed equation too wide for its column is set smaller.
        val wide = Row(List(18) { Run("x+") })
        val full = MathLayout.layout(wide, font, size).width
        assertTrue(full > 200f && MathLayout.layoutFitting(wide, font, size, 200f).width <= 201f)
    }

    @Test
    fun linearTextAndInlineRuns() {
        assertEquals("∑_(i=1)^n▒i^2", MathText.linear(sum))
        assertEquals("[(■(a&b@c&d))]", MathText.linear(matrix))
        val runs = MathText.runs(InlineMath(Frac(Run("1"), Run("2"), FracKind.BAR)), CharacterFormat(fontSizePt = 12f))
        assertEquals("1/2", runs.joinToString("") { it.text })
        assertTrue(runs.all { it.format.fontFamily == "Cambria Math" && it.format.fontSizePt == 12f })
    }

    @Test
    fun odtKeepsEquationsAsFormulaObjects() {
        val doc = Document(mutableListOf())
        doc.blocks.add(EquationBlock(quadratic))
        val inline = InlineMath(Scripts(Run("e"), null, Row(listOf(Run("iπ")))))
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Euler: ")).apply { addAll(MathText.runs(inline, CharacterFormat(fontSizePt = 11f))) })))
        doc.blocks.add(EquationBlock(sum))
        val bos = ByteArrayOutputStream()
        OdtWriter.write(doc, bos)
        val parts = OfficeXml.unzip(bos.toByteArray())
        val content = String(parts["content.xml"]!!, Charsets.UTF_8)
        assertTrue(content.contains("""<draw:object xlink:href="./Object 1" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/>"""))
        val manifest = String(parts["META-INF/manifest.xml"]!!, Charsets.UTF_8)
        assertTrue(manifest.contains("""manifest:full-path="Object 1/" manifest:media-type="application/vnd.oasis.opendocument.formula""""))
        val mathml = String(parts["Object 1/content.xml"]!!, Charsets.UTF_8)
        assertTrue(mathml.contains("<mfrac>") && mathml.contains("<msqrt>") && mathml.contains("<msup><mrow><mi>b</mi></mrow><mrow><mn>2</mn></mrow></msup>"))
        assertTrue(String(parts["Object 3/content.xml"]!!, Charsets.UTF_8).contains("""<munderover><mo stretchy="false">∑</mo>"""))
        val back = OdtReader.read(bos.toByteArray())
        // MathML writes a minus as a minus sign, and a product as a row.
        fun norm(s: String) = s.replace('−', '-').replace("(", "").replace(")", "")
        assertEquals(norm(MathText.linear(quadratic)), norm(MathText.linear((back.blocks[0] as EquationBlock).math)))
        val p = (back.blocks[1] as ParagraphBlock).paragraph
        assertEquals("Euler: e^(iπ)", p.plainText().let { it.substring(0, 7) + MathText.linear(p.runs.last().format.math!!.math) })
        assertEquals(MathText.linear(sum), MathText.linear((back.blocks[2] as EquationBlock).math))
    }

    @Test
    fun libreOfficeMathMlIsRead() {
        // As LibreOffice Math writes "sum from{i=1} to{n} i = {n(n+1)} over 2" and "left( a over b right)".
        val content = """<math xmlns="http://www.w3.org/1998/Math/MathML" display="block"><semantics><mrow>""" +
            """<munderover><mo stretchy="false">∑</mo><mrow><mi>i</mi><mo stretchy="false">=</mo><mn>1</mn></mrow><mi>n</mi></munderover><mi>i</mi>""" +
            """<mo stretchy="false">=</mo><mfrac><mrow><mi>n</mi><mrow><mo fence="true" form="prefix" stretchy="false">(</mo><mrow><mi>n</mi><mo stretchy="false">+</mo><mn>1</mn></mrow><mo fence="true" form="postfix" stretchy="false">)</mo></mrow></mrow><mn>2</mn></mfrac>""" +
            """<mrow><mo fence="true" form="prefix" stretchy="true">(</mo><mfrac><mi>a</mi><mi>b</mi></mfrac><mo fence="true" form="postfix" stretchy="true">)</mo></mrow>""" +
            """</mrow><annotation encoding="StarMath 5.0">sum from{i=1} to{n} i = …</annotation></semantics></math>"""
        val node = com.therealityteam.aeropages.format.odf.OdfNode.parse(content.toByteArray())
        val math = com.therealityteam.aeropages.format.odf.MathMl.read(node) as Row
        val nary = math.items[0] as Nary
        assertEquals("∑", nary.op)
        assertTrue(nary.under)
        assertEquals(Run("i"), nary.body)
        assertEquals(Run("=", plain = true), math.items[1])
        assertEquals("the fence pair is one group", Delimited("(", ")", listOf(Row(listOf(Run("n"), Run("+", plain = true), Run("1", plain = true))))),
            ((math.items[2] as Frac).num as Row).items[1])
        assertEquals(Delimited("(", ")", listOf(Frac(Run("a"), Run("b")))), math.items[3])
    }
}
