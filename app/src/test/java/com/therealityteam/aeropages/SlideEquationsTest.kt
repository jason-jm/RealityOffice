package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.MathNode.Frac
import com.therealityteam.aeropages.model.MathNode.Row
import com.therealityteam.aeropages.model.MathNode.Run
import com.therealityteam.aeropages.model.MathNode.Scripts
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** PowerPoint's equations (`a14:m` in alternate content, a picture as the fallback): read, kept, and written back. */
class SlideEquationsTest {

    private val m = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    private fun run(t: String, sz: Int) = """<m:r><a:rPr lang="en-US" sz="$sz" i="1"><a:latin typeface="Cambria Math"/></a:rPr><m:t>$t</m:t></m:r>"""

    /** A deck whose slide has what PowerPoint writes for Insert → Equation: the shape twice, with the formula and as a picture. */
    private fun powerPointDeck(boxParagraphs: String): ByteArray {
        val bos = ByteArrayOutputStream()
        PptxWriter.write(Presentation(mutableListOf(Slide(title = SlideBox("Equations"), body = SlideBox("")))), bos)
        val parts = LinkedHashMap(OfficeXml.unzip(bos.toByteArray()))
        val sp = { body: String, fill: String ->
            """<p:sp><p:nvSpPr><p:cNvPr id="40" name="TextBox 39"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="2000000" y="2600000"/><a:ext cx="5000000" cy="900000"/></a:xfrm>""" +
                """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>$fill</p:spPr><p:txBody><a:bodyPr wrap="square"><a:spAutoFit/></a:bodyPr><a:lstStyle/>$body</p:txBody></p:sp>"""
        }
        val eq = """<mc:AlternateContent xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"><mc:Choice xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main" Requires="a14">""" +
            sp(boxParagraphs, "<a:noFill/>") + "</mc:Choice><mc:Fallback>" +
            sp("""<a:p><a:r><a:rPr lang="en-US"><a:noFill/></a:rPr><a:t> </a:t></a:r></a:p>""", """<a:blipFill><a:blip r:embed="rId99"/><a:stretch><a:fillRect/></a:stretch></a:blipFill>""") +
            "</mc:Fallback></mc:AlternateContent>"
        parts["ppt/slides/slide1.xml"] = String(parts["ppt/slides/slide1.xml"]!!, Charsets.UTF_8).replace("</p:spTree>", "$eq</p:spTree>").toByteArray()
        parts["ppt/media/fallback.png"] = TestPng.header(40, 20)
        val rels = "ppt/slides/_rels/slide1.xml.rels"
        parts[rels] = String(parts[rels]!!, Charsets.UTF_8).replace("</Relationships>",
            """<Relationship Id="rId99" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/fallback.png"/></Relationships>""").toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> parts.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private val displayed = """<a:p><a14:m><m:oMathPara xmlns:m="$m"><m:oMathParaPr><m:jc m:val="centerGroup"/></m:oMathParaPr><m:oMath>""" +
        run("x=", 2800) + "<m:f><m:num>" + run("a", 2800) + "</m:num><m:den>" + run("b", 2800) + "</m:den></m:f></m:oMath></m:oMathPara></a14:m></a:p>"
    private val inText = """<a:p><a:r><a:rPr lang="en-US" sz="2000"/><a:t>where </a:t></a:r><a14:m><m:oMath xmlns:m="$m"><m:sSup><m:e>""" + run("c", 2000) +
        "</m:e><m:sup>" + run("2", 2000) + """</m:sup></m:sSup></m:oMath></a14:m><a:r><a:rPr lang="en-US" sz="2000"/><a:t> is known.</a:t></a:r></a:p>"""

    @Test
    fun powerPointsEquationsAreReadOnceWithoutTheirPictures() {
        val slide = PptxReader.read(powerPointDeck(displayed + inText)).slides[0]
        assertEquals("the shape once (its picture copy dropped)", 1, slide.shapes.size)
        assertTrue("and no stray picture", slide.images.isEmpty())
        val runs = slide.shapes[0].box.paragraph.runs
        val maths = runs.mapNotNull { it.format.math }.distinct()
        assertEquals(2, maths.size)
        assertEquals(Row(listOf(Run("x="), Frac(Run("a"), Run("b")))), maths[0].math)
        assertEquals(Scripts(Run("c"), null, Run("2")), maths[1].math)
        assertEquals("the formula's own size", 28f, runs.first { it.format.math === maths[0] }.format.fontSizePt)
        assertEquals("a box with text keeps its alignment", TextAlign.LEFT, slide.shapes[0].box.align)
        assertEquals("a box holding only a displayed equation is centred", TextAlign.CENTER,
            PptxReader.read(powerPointDeck(displayed)).slides[0].shapes[0].box.align)
    }

    @Test
    fun equationsGoBackAsPowerPointWritesThem() {
        val deck = PptxReader.read(powerPointDeck(displayed + inText))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(deck, bos)
        val xml = String(OfficeXml.unzip(bos.toByteArray())["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("""<mc:Choice xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main" xmlns:m="$m" Requires="a14">"""))
        assertTrue("a line holding only the equation is displayed", xml.contains("""<a14:m><m:oMathPara><m:oMathParaPr><m:jc m:val="centerGroup"/></m:oMathParaPr><m:oMath>"""))
        assertTrue("one in the text stays in the text", xml.contains("""<a:t>where </a:t></a:r><a14:m><m:oMath><m:sSup>"""))
        val fallback = xml.substringAfter("<mc:Fallback>").substringBefore("</mc:Fallback>")
        assertFalse("the fallback has the equations as text, for readers without Office Math", fallback.contains("a14:m"))
        assertTrue(fallback.contains("<a:t>x</a:t>"))
        val back = PptxReader.read(bos.toByteArray()).slides[0]
        assertEquals(1, back.shapes.size)
        assertEquals(deck.slides[0].shapes[0].box.paragraph.runs.mapNotNull { it.format.math?.math }.distinct(),
            back.shapes[0].box.paragraph.runs.mapNotNull { it.format.math?.math }.distinct())
    }

    @Test
    fun editingInsideAnEquationMakesItText() {
        val e = SlideEditor()
        e.load(PptxReader.read(powerPointDeck(displayed)))
        val box = e.slide.shapes[0].box
        val text = box.text
        e.replaceText(box, text, text.replace("a", "ab"))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(e.presentation, bos)
        val xml = String(OfficeXml.unzip(bos.toByteArray())["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertFalse(xml.contains("AlternateContent"))
        assertTrue(xml.contains("<a:t>x</a:t>"))
    }
}

/** Insert → Equation on a slide: an equation text box, typeset; editing it; the ribbon key's state. */
class SlideEquationBoxTest {
    @Test
    fun insertingAndEditingAnEquationBox() {
        val e = SlideEditor()
        e.load(Presentation(mutableListOf(Slide(title = SlideBox("T"), body = SlideBox("")))))
        e.insertEquation(com.therealityteam.aeropages.model.MathLatex.parse("\\frac{a}{b}"))
        val shape = e.slide.shapes.single()
        assertEquals(null, shape.border)
        assertEquals(TextAlign.CENTER, shape.box.align)
        val eq = com.therealityteam.aeropages.model.MathText.soleEquation(shape.box.paragraph)
        assertEquals(Frac(Run("a"), Run("b")), eq!!.math)
        assertEquals("the new box is the focused one: the Equation key edits it", eq, e.focusedEquation)
        e.setEquation(shape.box, com.therealityteam.aeropages.model.MathLatex.parse("\\sqrt{x}"))
        assertEquals("√x", com.therealityteam.aeropages.model.MathText.linear(com.therealityteam.aeropages.model.MathText.soleEquation(e.slide.shapes.single().box.paragraph)!!.math))
        e.undo()
        assertEquals(Frac(Run("a"), Run("b")), com.therealityteam.aeropages.model.MathText.soleEquation(e.slide.shapes.single().box.paragraph)!!.math)
        e.undo()
        assertTrue(e.slide.shapes.isEmpty())
        // A box with other text is no equation box.
        val p = com.therealityteam.aeropages.model.Paragraph(mutableListOf(com.therealityteam.aeropages.model.TextRun("Let ")).apply {
            addAll(com.therealityteam.aeropages.model.MathText.runs(com.therealityteam.aeropages.model.InlineMath(Run("x")), com.therealityteam.aeropages.model.CharacterFormat()))
        })
        assertEquals(null, com.therealityteam.aeropages.model.MathText.soleEquation(p))
    }
}
