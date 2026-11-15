package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideTransition
import com.therealityteam.aeropages.model.TransitionSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Slide sections (节) and the full transition set with durations, in the editor and both file formats. */
class SlideSectionsAndTransitionsTest {

    private fun deck(): Presentation = Presentation(mutableListOf(
        Slide(title = SlideBox("Welcome")), Slide(title = SlideBox("Agenda")), Slide(title = SlideBox("Results")), Slide(title = SlideBox("Thanks")),
    ))

    @Test
    fun sectionsRideOnTheSlideThatStartsThem() {
        val e = SlideEditor().also { it.load(deck()) }
        assertNull(e.sectionOf(2))
        e.select(0); e.setSectionName("Intro")
        e.select(2); e.setSectionName("Body")
        assertEquals(listOf("Intro" to 0, "Body" to 2), e.sections())
        assertEquals("Intro", e.sectionOf(1))
        assertEquals("Body", e.sectionOf(3))

        e.select(2); e.setSectionName("Findings")           // rename
        assertEquals("Findings", e.sectionOf(2))
        e.undo()
        assertEquals("Body", e.sectionOf(2))
        e.removeSection()
        assertNull(e.presentation.slides[2].sectionName)
        assertEquals("Intro", e.sectionOf(3))               // now everything belongs to Intro
        e.undo()
        assertEquals("Body", e.sectionOf(3))
    }

    @Test
    fun transitionsAndSectionsRoundTripThroughPptx() {
        val p = deck()
        p.slides[0].sectionName = "Intro"
        p.slides[2].sectionName = "Body"
        p.slides[0].transition = SlideTransition.WIPE; p.slides[0].transitionSpeed = TransitionSpeed.SLOW
        p.slides[1].transition = SlideTransition.SPLIT
        p.slides[2].transition = SlideTransition.DISSOLVE; p.slides[2].transitionSpeed = TransitionSpeed.FAST
        p.slides[3].transition = SlideTransition.WHEEL

        val bytes = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        val pres = String(parts["ppt/presentation.xml"]!!, Charsets.UTF_8)
        assertTrue("section list", pres.contains("<p14:sectionLst") && pres.contains("""<p14:section name="Intro""""))
        assertTrue("Body holds slides 3 and 4", pres.contains("""<p14:section name="Body" id="{""") && pres.contains("""<p14:sldId id="258"/><p14:sldId id="259"/>"""))
        val s1 = String(parts["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
        assertTrue("slow wipe", s1.contains("""<p:transition spd="slow"><p:wipe dir="r"/></p:transition>"""))
        val s3 = String(parts["ppt/slides/slide3.xml"]!!, Charsets.UTF_8)
        assertTrue("fast dissolve", s3.contains("""<p:transition spd="fast"><p:dissolve/></p:transition>"""))

        val back = PptxReader.read(bytes)
        assertEquals(listOf("Intro", null, "Body", null), back.slides.map { it.sectionName })
        assertEquals(listOf(SlideTransition.WIPE, SlideTransition.SPLIT, SlideTransition.DISSOLVE, SlideTransition.WHEEL), back.slides.map { it.transition })
        assertEquals(listOf(TransitionSpeed.SLOW, TransitionSpeed.MEDIUM, TransitionSpeed.FAST, TransitionSpeed.MEDIUM), back.slides.map { it.transitionSpeed })
    }

    @Test
    fun sectionsStartingLaterGetADefaultSectionInFront() {
        val p = deck()
        p.slides[2].sectionName = "Late"
        val pres = String(OfficeXml.unzip(ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray())["ppt/presentation.xml"]!!, Charsets.UTF_8)
        // PowerPoint wants every slide in a section, so the leading slides get a default one.
        assertTrue(pres.indexOf("""name="Default Section"""") < pres.indexOf("""name="Late""""))
        val back = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray())
        assertEquals("Default Section", back.slides[0].sectionName)
        assertEquals("Late", back.slides[2].sectionName)
    }

    @Test
    fun transitionsRoundTripThroughOdp() {
        val p = deck()
        p.slides[0].transition = SlideTransition.PUSH; p.slides[0].transitionSpeed = TransitionSpeed.SLOW
        p.slides[1].transition = SlideTransition.SPLIT; p.slides[1].transitionSpeed = TransitionSpeed.FAST
        p.slides[2].transition = SlideTransition.NONE
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("""smil:type="pushWipe" smil:subtype="fromRight" presentation:transition-speed="slow""""))
        val back = OdpReader.read(bytes)
        assertEquals(SlideTransition.PUSH, back.slides[0].transition)
        assertEquals(TransitionSpeed.SLOW, back.slides[0].transitionSpeed)
        assertEquals(SlideTransition.SPLIT, back.slides[1].transition)
        assertEquals(TransitionSpeed.FAST, back.slides[1].transitionSpeed)
        assertEquals(SlideTransition.NONE, back.slides[2].transition)
    }
}
