package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimStart
import com.therealityteam.aeropages.model.AnimTimeline
import com.therealityteam.aeropages.model.ElementAnim
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A slide's animation timeline (steps, start times, what shows when) and the editor's Animations commands. */
class AnimTimelineTest {

    private fun slideWithText(): Slide = Slide(title = SlideBox("Title"), body = SlideBox("Body"))

    @Test
    fun stepsAndStartTimes() {
        val s = slideWithText()
        s.title.anims.add(ElementAnim(AnimEffect.FADE, AnimStart.WITH_PREVIOUS, 400, 100, order = 0))   // starts with the slide
        s.body.anims.add(ElementAnim(AnimEffect.FLY_IN, AnimStart.ON_CLICK, 500, 0, order = 1))
        s.body.anims.add(ElementAnim(AnimEffect.PULSE, AnimStart.WITH_PREVIOUS, 1000, 200, order = 2))
        s.title.anims.add(ElementAnim(AnimEffect.FADE_OUT, AnimStart.AFTER_PREVIOUS, 300, 50, order = 3))
        s.body.anims.add(ElementAnim(AnimEffect.WIPE_OUT, AnimStart.ON_CLICK, 500, 0, order = 4))
        val t = AnimTimeline.of(s)
        assertEquals("two clicks after what starts with the slide", 2, t.clicks)
        assertEquals(listOf(100), t.steps[0].map { it.startMs })
        assertEquals("with previous shares the start; after previous waits for the step so far (200 + 1000), then its delay",
            listOf(0, 200, 1250), t.steps[1].map { it.startMs })
        assertEquals(1550, t.lengthOf(1))
        assertEquals(listOf(0), t.steps[2].map { it.startMs })
    }

    @Test
    fun whatShowsAtEachStep() {
        val s = slideWithText()
        s.title.anims.add(ElementAnim(AnimEffect.APPEAR, AnimStart.ON_CLICK, order = 0))
        s.body.anims.add(ElementAnim(AnimEffect.GROW_SHRINK, AnimStart.ON_CLICK, order = 1))
        s.body.anims.add(ElementAnim(AnimEffect.TRANSPARENCY, AnimStart.WITH_PREVIOUS, order = 2))
        s.title.anims.add(ElementAnim(AnimEffect.DISAPPEAR, AnimStart.ON_CLICK, order = 3))
        val t = AnimTimeline.of(s)
        assertFalse("an element whose first effect is an entrance waits for it", t.stateAt(s.title, -1).visible)
        assertFalse(t.stateAt(s.title, 0).visible)
        assertTrue(t.stateAt(s.title, 1).visible)
        assertFalse("gone after its exit", t.stateAt(s.title, 3).visible)
        assertTrue("an element that starts with an emphasis is there from the start", t.stateAt(s.body, 0).visible)
        assertEquals(1f, t.stateAt(s.body, 1).scale, 0f)
        assertEquals("grow/shrink and transparency last", 1.5f, t.stateAt(s.body, 2).scale, 0f)
        assertEquals(0.5f, t.stateAt(s.body, 3).alpha, 0f)
        val plain = SlideBox("x")
        assertTrue("an element without effects always shows", t.stateAt(plain, -1).visible)
    }

    @Test
    fun galleryPickAddRemoveAndReorderAreOneUndoStepEach() {
        val e = SlideEditor()
        e.load(Presentation(mutableListOf(slideWithText())))
        e.focus(e.slide.title)
        e.applyAnimation(AnimEffect.FADE)
        assertEquals(AnimEffect.FADE, e.slide.title.anims.single().effect)
        e.applyAnimation(AnimEffect.ZOOM)
        assertEquals("a gallery pick replaces the element's effect", AnimEffect.ZOOM, e.slide.title.anims.single().effect)
        e.setAnimStart(AnimStart.AFTER_PREVIOUS); e.setAnimDuration(1000); e.setAnimDelay(250)
        e.addAnimation(AnimEffect.PULSE)
        assertEquals(listOf(AnimEffect.ZOOM, AnimEffect.PULSE), e.slide.title.anims.map { it.effect })
        assertSame("the added effect becomes the current one", e.slide.title.anims[1], e.currentEffect)
        e.selectAnim(e.slide.title.anims[0])
        e.setAnimDirection(AnimDir.TOP)
        e.slide.title.anims[0].let { assertEquals(AnimStart.AFTER_PREVIOUS, it.start); assertEquals(1000, it.durationMs); assertEquals(250, it.delayMs); assertEquals(AnimDir.TOP, it.dir) }
        e.clearSelection(); e.focus(e.slide.body)
        e.applyAnimation(AnimEffect.FLY_IN)
        assertEquals(listOf(0, 1), e.slide.title.anims.map { it.order })
        assertEquals(2, e.slide.body.anims.single().order)
        e.moveAnimation(e.slide.body.anims.single(), -1)
        assertEquals(listOf(AnimEffect.ZOOM, AnimEffect.FLY_IN, AnimEffect.PULSE), e.animationList.map { it.anim.effect })
        e.removeAnimation(e.slide.title.anims[0])
        assertEquals("the list renumbers", listOf(0, 1), e.animationList.map { it.anim.order })
        e.undo()
        assertEquals(listOf(AnimEffect.ZOOM, AnimEffect.FLY_IN, AnimEffect.PULSE), e.animationList.map { it.anim.effect })
        assertNull("undo clears the pane's pick (it held the old objects)", e.selectedAnim)
        e.undo()
        assertEquals(listOf(AnimEffect.ZOOM, AnimEffect.PULSE, AnimEffect.FLY_IN), e.animationList.map { it.anim.effect })
        e.moveAnimation(e.slide.body.anims.single(), -5)
        assertEquals("a long move stops at the top", listOf(AnimEffect.FLY_IN, AnimEffect.ZOOM, AnimEffect.PULSE), e.animationList.map { it.anim.effect })
        e.focus(e.slide.body)
        e.applyAnimation(null)
        assertTrue("None removes the element's effects", e.slide.body.anims.isEmpty())
        assertEquals(listOf(0, 1), e.animationList.map { it.anim.order })
    }

    @Test
    fun aBodyBuiltByParagraphPlaysItsLinesOneByOne() {
        val s = Slide(title = SlideBox("Title"), body = SlideBox("One\nTwo\n\nThree"))
        s.body.anims.add(ElementAnim(AnimEffect.FLY_IN, AnimStart.ON_CLICK, order = 0, byParagraph = true))
        s.title.anims.add(ElementAnim(AnimEffect.PULSE, AnimStart.AFTER_PREVIOUS, 400, order = 1))
        val t = AnimTimeline.of(s)
        assertEquals("a click per non-blank line; the title's pulse follows the last", 3, t.clicks)
        assertEquals(listOf(0), t.steps[1].map { it.paragraph })
        assertEquals(listOf(3, -1), t.steps[3].map { it.paragraph })
        assertTrue(t.byParagraph(s.body))
        assertFalse("each line waits for its click", t.stateAt(s.body, 1, paragraph = 1).visible)
        assertTrue(t.stateAt(s.body, 2, paragraph = 1).visible)
        assertTrue("the box itself stays: its lines do the showing", t.stateAt(s.body, -1).visible)
        assertTrue("a blank line has nothing to wait for", t.stateAt(s.body, -1, paragraph = 2).visible)
        // Starting after the previous effect, the lines follow one another by themselves.
        s.body.anims[0].start = AnimStart.AFTER_PREVIOUS
        s.body.anims[0].durationMs = 300
        val auto = AnimTimeline.of(s)
        assertEquals(0, auto.clicks)
        assertEquals(listOf(0, 300, 600, 900), auto.steps[0].map { it.startMs })
        // A single line plays as one object whatever the setting.
        val one = Slide(body = SlideBox("Only line"))
        one.body.anims.add(ElementAnim(AnimEffect.FADE, byParagraph = true))
        assertFalse(AnimTimeline.of(one).byParagraph(one.body))
    }

    @Test
    fun theBodyBuildsByParagraphByDefaultAndEffectOptionsSwitchIt() {
        val e = SlideEditor()
        e.load(Presentation(mutableListOf(Slide(title = SlideBox("Title"), body = SlideBox("A\nB")))))
        e.focus(e.slide.body)
        assertTrue(e.canBuildByParagraph)
        e.applyAnimation(AnimEffect.FADE)
        assertTrue("PowerPoint builds a text placeholder by paragraph", e.slide.body.anims.single().byParagraph)
        e.setAnimByParagraph(false)
        assertFalse(e.slide.body.anims.single().byParagraph)
        e.undo()
        assertTrue(e.slide.body.anims.single().byParagraph)
        e.focus(e.slide.title)
        assertFalse(e.canBuildByParagraph)
        e.applyAnimation(AnimEffect.FADE)
        assertFalse(e.slide.title.anims.single().byParagraph)
    }

    @Test
    fun oneByOneAndPastedElementsJoinTheEndOfTheList() {
        val e = SlideEditor()
        e.load(Presentation(mutableListOf(slideWithText())))
        e.addShape(ShapeType.RECT)
        e.addShape(ShapeType.ELLIPSE)
        e.selectShape(e.slide.shapes[0])
        e.applyAnimation(AnimEffect.SPIN)
        e.animateOneByOne()
        assertTrue("the title shows with the slide", e.slide.title.anims.isEmpty())
        assertEquals(listOf(AnimEffect.SPIN, AnimEffect.APPEAR, AnimEffect.APPEAR), e.animationList.map { it.anim.effect })
        assertSame(e.slide.body, e.animationList[1].target)
        assertEquals("a click each", 3, AnimTimeline.of(e.slide).clicks)
        e.selectShape(e.slide.shapes[0])
        e.duplicateSelectedObject()
        val copy = e.slide.shapes.last()
        assertEquals("the copy's effect comes last, as PowerPoint puts it", 3, copy.box.anims.single().order)
        assertEquals(AnimEffect.SPIN, e.animationList.last().anim.effect)
        e.animateOneByOne()
        assertEquals("nothing left to add", 4, e.animationList.size)
    }
}
