package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.EquationBlock
import com.therealityteam.aeropages.model.FracKind
import com.therealityteam.aeropages.model.MathLatex
import com.therealityteam.aeropages.model.MathNode
import com.therealityteam.aeropages.model.MathNode.Accent
import com.therealityteam.aeropages.model.MathNode.Bar
import com.therealityteam.aeropages.model.MathNode.Delimited
import com.therealityteam.aeropages.model.MathNode.Frac
import com.therealityteam.aeropages.model.MathNode.Limit
import com.therealityteam.aeropages.model.MathNode.Matrix
import com.therealityteam.aeropages.model.MathNode.Nary
import com.therealityteam.aeropages.model.MathNode.Radical
import com.therealityteam.aeropages.model.MathNode.Row
import com.therealityteam.aeropages.model.MathNode.Run
import com.therealityteam.aeropages.model.MathNode.Scripts
import com.therealityteam.aeropages.model.MathText
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Typing equations: LaTeX in and out, and the editor's Insert / Edit Equation. */
class EquationEditingTest {

    private fun lin(latex: String) = MathText.linear(MathLatex.parse(latex))

    @Test
    fun latexReadsTheUsualNotation() {
        assertEquals("x=(-b±√(b^2-4ac))/(2a)", lin("x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}"))
        val sum = (MathLatex.parse("\\sum_{i=1}^{n} i^2 = \\frac{n(n+1)(2n+1)}{6}") as Row).items
        val nary = sum[0] as Nary
        assertEquals("∑", nary.op); assertTrue(nary.under)
        assertEquals("the operator takes what follows it up to the relation", Scripts(Run("i"), null, Run("2")), nary.body)
        assertEquals(Run("="), sum[1])
        // The integral takes all that follows it: the formula is that one operator.
        val int = MathLatex.parse("\\int_0^\\infty e^{-x^2}\\,dx") as Nary
        assertEquals("∫", int.op); assertEquals(false, int.under); assertEquals(Run("∞"), int.sup)
        assertEquals(Delimited("(", ")", listOf(Matrix(listOf(listOf(Run("a"), Run("b")), listOf(Run("c"), Run("d")))))),
            MathLatex.parse("\\begin{pmatrix} a & b \\\\ c & d \\end{pmatrix}"))
        val cases = (MathLatex.parse("f(x) = \\begin{cases} x & x \\ge 0 \\\\ -x & x < 0 \\end{cases}") as Row).items.last() as Delimited
        assertEquals("{", cases.open); assertEquals("", cases.close)
        assertEquals(2, (cases.items.single() as Matrix).rows.size)
        assertEquals(Row(listOf(Accent(Run("x"), "̂"), Run("+"), Accent(Run("v"), "⃗"), Run("+"), Bar(Run("z")))),
            MathLatex.parse("\\hat{x} + \\vec{v} + \\overline{z}"))
        val lim = (MathLatex.parse("\\lim_{x \\to 0} \\frac{\\sin x}{x}") as Row).items
        assertEquals(Limit(Run("lim", plain = true), Row(listOf(Run("x"), Run("→"), Run("0"))), under = true), lim[0])
        assertEquals(MathNode.Func(Run("sin", plain = true), Run("x")), (lim[1] as Frac).num)
        assertEquals(Radical(Run("8"), Run("3")), MathLatex.parse("\\sqrt[3]{8}"))
        assertEquals(Run("if ", plain = true), MathLatex.parse("\\text{if }"))
        assertEquals(Scripts(Run("f"), null, Run("′", plain = true)), MathLatex.parse("f'"))
        assertEquals(Delimited("(", ")", listOf(Frac(Run("n"), Run("k"), FracKind.NO_BAR))), MathLatex.parse("\\binom{n}{k}"))
        assertEquals(Delimited("⟨", "⟩", listOf(Run("a"), Run("b")), "|"), MathLatex.parse("\\left\\langle a \\middle| b \\right\\rangle"))
    }

    @Test
    fun brokenInputStillReads() {
        // Nothing thrown, and what can be read is kept.
        assertEquals(Frac(Run("a"), Row(emptyList())), MathLatex.parse("\\frac{a}{"))
        assertEquals(Run("foo", plain = true), MathLatex.parse("\\foo"))
        assertEquals("a+b", lin("{a+b"))
        assertEquals(Row(emptyList()), MathLatex.parse("  "))
        MathLatex.parse("\\left( x")
        MathLatex.parse("\\begin{matrix} a & b")
        MathLatex.parse("x^^_}")
    }

    @Test
    fun whatIsWrittenReadsBack() {
        fun norm(s: String) = s.replace("⁡", "").replace("(", "").replace(")", "").replace(" ", "")
        val samples = listOf(
            "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}",
            "\\int_{-\\infty}^{\\infty} e^{-x^2} dx = \\sqrt{\\pi}",
            "\\sum_{k=1}^{n} k = \\frac{n(n+1)}{2}",
            "A = \\begin{bmatrix} a & b \\\\ c & d \\end{bmatrix}",
            "f(x) = \\begin{cases} x & x \\geq 0 \\\\ -x & x < 0 \\end{cases}",
            "\\vec{v} \\cdot \\hat{x} + \\overline{z} = a_{i}^{2} + \\sqrt[3]{8}",
            "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1",
            "\\left\\{ x \\in \\mathbb{R} \\middle| x > 0 \\right\\}",
            "\\alpha + \\beta \\neq \\gamma \\leq \\Omega",
            "\\text{area} = \\pi r^2",
            "\\underbrace{a + b}_{n}",
        )
        for (latex in samples) {
            val first = MathLatex.parse(latex)
            val written = MathLatex.write(first)
            assertEquals("$latex → $written", first, MathLatex.parse(written))
        }
        // Formulas read from Word write out and read back to the same text.
        val word = Row(listOf(Run("x"), Run("="), Frac(Row(listOf(Run("-b±"), Radical(Row(listOf(Scripts(Run("b"), null, Run("2")), Run("-4ac")))))), Run("2a"))))
        assertEquals(norm(MathText.linear(word)), norm(MathText.linear(MathLatex.parse(MathLatex.write(word)))))
    }

    @Test
    fun insertingAndEditingEquations() {
        val e = DocumentEditor()
        e.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("Area: ")))))
        e.setSelection(0, 6, 6)
        e.insertEquation(MathLatex.parse("\\pi r^2"), display = false)
        val p = (e.document.blocks[0] as ParagraphBlock).paragraph
        assertEquals("Area: πr2", p.plainText())
        assertNull("the caret after the equation is out of it", e.inlineMathAtCaret)
        e.setSelection(0, 6, 6)
        assertNotNull(e.inlineMathAtCaret)
        e.updateInlineMath(MathLatex.parse("2\\pi r"))
        assertEquals("Area: 2πr", p.plainText())
        assertEquals(MathLatex.parse("2\\pi r"), e.inlineMathAtCaret!!.math)
        e.undo()
        assertEquals("Area: πr2", (e.document.blocks[0] as ParagraphBlock).paragraph.plainText())
        // A displayed one goes after the paragraph, picked; editing it is one step.
        e.setSelection(0, 0, 0)
        e.insertEquation(MathLatex.parse("E = mc^2"), display = true)
        val block = e.document.blocks[1] as EquationBlock
        assertSame(block, e.selectedEquation)
        e.updateEquation(block, MathLatex.parse("E^2 = (pc)^2 + (mc^2)^2"))
        assertEquals("E^2=(pc)^2+(mc^2)^2", MathText.linear(block.math))
        e.undo()
        assertEquals("E=mc^2", MathText.linear((e.document.blocks[1] as EquationBlock).math))
        // In a table cell there is no room for a block: the equation goes in the text.
        val table = Table.of(1, 1)
        val doc = Document(mutableListOf(TableBlock(table)))
        val f = DocumentEditor(); f.load(doc)
        f.setSelection(0, 0, 0)
        f.insertEquation(MathLatex.parse("a^2"), display = true)
        // The table and the paragraph Word keeps after it: no equation block.
        assertEquals(2, f.document.blocks.size)
        assertTrue(f.document.blocks.none { it is EquationBlock })
        assertEquals("a2", table.rows[0].cells[0].content[0].plainText())
    }
}
