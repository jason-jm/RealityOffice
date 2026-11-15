package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.editor.SmartArtKind
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for Slides editor logic (SmartArt generation). */
class SlideEditorTest {

    private fun editorWithBody(body: String): SlideEditor {
        val e = SlideEditor()
        e.load(Presentation.blank())
        // set the body text via the diff-based replace
        e.replaceText(e.slide.body, e.slide.body.text, body)
        return e
    }

    @Test fun smartArtListFromBody() {
        val e = editorWithBody("步骤一\n步骤二\n步骤三")
        e.insertSmartArt(SmartArtKind.LIST)
        val shapes = e.slide.shapes
        assertEquals(3, shapes.size)
        assertEquals(listOf("步骤一", "步骤二", "步骤三"), shapes.map { it.box.text })
        assertTrue("all rounded", shapes.all { it.type == ShapeType.ROUNDED })
        // list layout: stacked vertically (increasing y), same x
        assertTrue("y increases", shapes[0].yPct < shapes[1].yPct && shapes[1].yPct < shapes[2].yPct)
        assertTrue("same x", shapes.all { kotlin.math.abs(it.xPct - shapes[0].xPct) < 1e-6 })
    }

    @Test fun smartArtProcessHorizontal() {
        val e = editorWithBody("A\nB\nC\nD")
        e.insertSmartArt(SmartArtKind.PROCESS)
        val shapes = e.slide.shapes
        assertEquals(4, shapes.size)
        // process layout: left-to-right (increasing x), same y
        assertTrue("x increases", shapes[0].xPct < shapes[1].xPct)
        assertTrue("same y", shapes.all { kotlin.math.abs(it.yPct - shapes[0].yPct) < 1e-6 })
    }

    @Test fun smartArtEmptyBodyUsesPlaceholders() {
        val e = SlideEditor(); e.load(Presentation.blank())
        e.replaceText(e.slide.body, e.slide.body.text, "") // clear body
        e.insertSmartArt(SmartArtKind.CYCLE)
        assertEquals(3, e.slide.shapes.size) // 3 placeholder nodes
    }

    // --- hide background graphics (隐藏背景图形) ----------------------------------------

    @Test fun hideBackgroundGraphicsUndoes() {
        val deck = Presentation.blank()
        val design = com.therealityteam.aeropages.model.SlideDesign("Band")
        design.items.add(com.therealityteam.aeropages.model.DesignShape(com.therealityteam.aeropages.model.SlideShape(ShapeType.RECT)))
        deck.designs.add(design); deck.slides[0].design = "Band"
        val e = SlideEditor(); e.load(deck)
        val before = e.slide.copyOf()
        e.setDesignObjects(false)
        assertEquals(false, e.slide.designObjects)
        assertTrue("a slide hiding its graphics is a different slide", !com.therealityteam.aeropages.editor.ModelDiff.sameSlide(before, e.slide))
        e.undo(); assertEquals(true, e.slide.designObjects)
        e.redo(); assertEquals(false, e.slide.designObjects)
        e.setDesignObjects(false); e.undo()
        assertEquals("setting it as it is changes nothing to undo", true, e.slide.designObjects)
    }

    // --- find & replace (查找替换) ------------------------------------------------

    /** 3 slides: hits in slide 1 body, slide 2 title + notes, slide 3 shape text. */
    private fun deckWithApples(): SlideEditor {
        val e = SlideEditor(); e.load(Presentation.blank())
        e.replaceText(e.slide.title, e.slide.title.text, "封面")
        e.replaceText(e.slide.body, e.slide.body.text, "苹果很甜")
        e.addSlide()
        e.replaceText(e.slide.title, e.slide.title.text, "苹果产量")
        e.replaceText(e.slide.body, e.slide.body.text, "正文")
        e.setNotes("备注里也有苹果")
        e.addSlide()
        e.replaceText(e.slide.title, e.slide.title.text, "尾页")
        e.replaceText(e.slide.body, e.slide.body.text, "无")
        e.addShape(ShapeType.RECT)
        e.replaceText(e.slide.shapes[0].box, "", "苹果图形")
        e.select(0)
        return e
    }

    @Test fun findNextAdvancesAcrossSlidesAndWraps() {
        val e = deckWithApples()
        assertEquals(0, e.findNext("苹果"))           // slide 1 body
        assertEquals("苹果很甜", e.focusedBox()?.text)
        assertEquals(1, e.findNext("苹果"))           // slide 2 title
        assertEquals("苹果产量", e.focusedBox()?.text)
        assertEquals(2, e.findNext("苹果"))           // slide 3 shape
        assertEquals("苹果图形", e.focusedBox()?.text)
        assertEquals(0, e.findNext("苹果"))           // wraps back to slide 1
        assertEquals(null, e.findNext("不存在的词"))
    }

    @Test fun findSelectsEachMatchInItsBox() {
        val e = SlideEditor(); e.load(Presentation.blank())
        e.replaceText(e.slide.body, e.slide.body.text, "cat, a catalog, a cat")
        val o = com.therealityteam.aeropages.editor.SearchOptions(wholeWord = true)
        assertEquals(1 to 2, e.findText("cat", o)?.let { (i, n) -> i + 1 to n })
        assertEquals(0 to 3, e.selStart to e.selEnd)
        assertEquals(1, e.findText("cat", o)?.first)
        assertEquals(18 to 21, e.selStart to e.selEnd)
        assertEquals("round again", 0, e.findText("cat", o)?.first)
        assertEquals(1, e.findText("cat", o, back = true)?.first)
        // Replace the selected one, then the next is selected; Replace All with what was found in it.
        e.replaceFound("cat", "dog", o)
        assertEquals("cat, a catalog, a dog", e.slide.body.text)
        assertEquals(0 to 3, e.selStart to e.selEnd)
        assertEquals(2, e.replaceAllFound("c?t", "[^&]", com.therealityteam.aeropages.editor.SearchOptions(wildcards = true)))
        assertEquals("[cat], a [cat]alog, a dog", e.slide.body.text)
    }

    @Test fun findRespectsMatchCase() {
        val e = SlideEditor(); e.load(Presentation.blank())
        e.replaceText(e.slide.body, e.slide.body.text, "Hello world")
        assertEquals(0, e.findNext("hello", matchCase = false))
        assertEquals(null, e.findNext("hello", matchCase = true))
    }

    @Test fun replaceAllCoversBoxesTablesAndNotes() {
        val e = deckWithApples()
        e.select(2)
        e.addTable(1, 1)
        e.replaceText(e.slide.tables[0].cells[0][0], "", "表格苹果")
        val n = e.replaceAll("苹果", "橙子")
        assertEquals(5, n) // body + title + notes + shape + table cell
        assertEquals("橙子很甜", e.presentation.slides[0].body.text)
        assertEquals("橙子产量", e.presentation.slides[1].title.text)
        assertEquals("备注里也有橙子", e.presentation.slides[1].notes)
        assertEquals("橙子图形", e.presentation.slides[2].shapes[0].box.text)
        assertEquals("表格橙子", e.presentation.slides[2].tables[0].cells[0][0].text)
        // Undo restores everything in one step.
        e.undo()
        assertEquals("苹果很甜", e.presentation.slides[0].body.text)
        assertEquals(0, e.replaceAll("不存在的词", "x"))
    }

    @Test fun rehearseTimingsApplyAndEnableUseTimings() {
        val e = SlideEditor(); e.load(Presentation.blank())
        e.addSlide(); e.addSlide()
        e.setUseTimings(false)
        e.applyRehearseTimings(listOf(4200, 800, 9000))
        assertEquals(4200, e.presentation.slides[0].advanceMs)
        assertEquals(1000, e.presentation.slides[1].advanceMs) // floor 1s
        assertEquals(9000, e.presentation.slides[2].advanceMs)
        assertTrue(e.presentation.useTimings)
        // all-zero timings are a no-op
        val before = e.presentation.slides[0].advanceMs
        e.applyRehearseTimings(listOf(0, 0, 0))
        assertEquals(before, e.presentation.slides[0].advanceMs)
    }

    @Test fun replaceAllKeepsRunFormatting() {
        val e = SlideEditor(); e.load(Presentation.blank())
        e.replaceText(e.slide.body, e.slide.body.text, "红色苹果好吃")
        // Bold the "苹果" span (offsets 2..4).
        e.setSelection(e.slide.body, 2, 4)
        e.toggleBold()
        val n = e.replaceAll("苹果", "蜜桃")
        assertEquals(1, n)
        val p = e.presentation.slides[0].body.paragraph
        assertEquals("红色蜜桃好吃", p.plainText())
        val boldByChar = buildList { p.runs.forEach { r -> r.text.forEach { _ -> add(r.format.bold) } } }
        assertEquals(listOf(false, false, true, true, false, false), boldByChar)
    }
}
