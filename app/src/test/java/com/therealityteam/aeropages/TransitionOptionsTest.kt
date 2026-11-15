package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideTransition
import com.therealityteam.aeropages.model.TransitionDir
import com.therealityteam.aeropages.model.TransitionSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Transitions with their Effect Options — the side, the orientation, in or out — in PowerPoint's and Impress's files. */
class TransitionOptionsTest {
    private val cases = listOf(
        SlideTransition.PUSH to TransitionDir.FROM_TOP,
        SlideTransition.COVER to TransitionDir.FROM_LEFT,
        SlideTransition.UNCOVER to TransitionDir.FROM_BOTTOM,
        SlideTransition.WIPE to TransitionDir.FROM_RIGHT,
        SlideTransition.SPLIT to TransitionDir.VERTICAL_IN,
        SlideTransition.RANDOM_BARS to TransitionDir.VERTICAL_OUT,
        SlideTransition.ZOOM to TransitionDir.OUT,
        SlideTransition.SHAPE to TransitionDir.IN,
        SlideTransition.WHEEL to TransitionDir.DEFAULT,
    )

    private fun deck(): Presentation {
        val p = Presentation.blank()
        p.slides.clear()
        cases.forEachIndexed { i, (t, d) -> p.slides.add(Slide(title = com.therealityteam.aeropages.model.SlideBox("Slide ${i + 1}")).also { it.transition = t; it.transitionDir = d; it.transitionSpeed = TransitionSpeed.SLOW }) }
        return p
    }

    private fun check(back: Presentation, where: String) {
        cases.forEachIndexed { i, (t, d) ->
            assertEquals("$where $i", t, back.slides[i].transition)
            assertEquals("$where $i", TransitionDir.of(t, d), TransitionDir.of(t, back.slides[i].transitionDir))
        }
    }

    @Test
    fun theyRoundTrip() {
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }.toByteArray()
        val xml = String(OfficeXml.unzip(pptx)["ppt/slides/slide1.xml"]!!)
        assertTrue(xml, xml.contains("""<p:push dir="d"/>"""))
        check(PptxReader.read(pptx), "pptx")
        check(OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }.toByteArray()), "odp")
    }

    @Test
    fun optionsBelongToTheirTransition() {
        assertEquals(TransitionDir.FROM_RIGHT, TransitionDir.of(SlideTransition.PUSH, TransitionDir.DEFAULT))
        assertEquals(TransitionDir.FROM_LEFT, TransitionDir.of(SlideTransition.WIPE, TransitionDir.IN))
        assertEquals(TransitionDir.DEFAULT, TransitionDir.of(SlideTransition.FADE, TransitionDir.FROM_TOP))
        assertTrue(TransitionDir.optionsOf(SlideTransition.CUT).isEmpty())
    }

    @Test
    fun applyToAllTakesTheTimingToo() {
        val e = SlideEditor().apply { load(deck()) }
        e.select(0)
        e.setAdvanceAfter(3000)
        e.applyTransitionToAll()
        assertTrue(e.presentation.slides.all { it.transition == SlideTransition.PUSH && it.transitionDir == TransitionDir.FROM_TOP && it.advanceMs == 3000 })
        e.undo()
        assertEquals(SlideTransition.SHAPE, e.presentation.slides[7].transition)
        assertEquals(0, e.presentation.slides[7].advanceMs)
    }

    /** With `-Daero.sampleDir`: the deck as pptx and ODP, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "transitions.pptx").outputStream().use { PptxWriter.write(deck(), it) }
        File(dir, "transitions.odp").outputStream().use { OdpWriter.write(deck(), it) }
    }
}
