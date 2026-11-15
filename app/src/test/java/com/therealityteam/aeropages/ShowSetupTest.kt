package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CustomShow
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShowPlan
import com.therealityteam.aeropages.model.ShowSetup
import com.therealityteam.aeropages.model.ShowType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Set Up Slide Show and custom shows: which slides a show plays and how, kept in PowerPoint's and Impress's files. */
class ShowSetupTest {
    private fun deck(): Presentation {
        val p = Presentation.blank()
        p.slides.clear()
        for (i in 1..6) p.slides.add(Slide(title = SlideBox("Slide $i")))
        p.slides[3].hidden = true
        p.customShows = listOf(
            CustomShow("Short", listOf(p.slides[4].id, p.slides[0].id, p.slides[3].id)),
            CustomShow("Team & Board", listOf(p.slides[1].id, p.slides[2].id)),
        )
        return p
    }

    /** The custom shows as the positions of their slides (ids differ after a read). */
    private fun shows(p: Presentation) = p.customShows.map { cs -> cs.name to cs.slides.map { id -> p.slides.indexOfFirst { it.id == id } } }

    @Test
    fun thePlanFollowsTheSetup() {
        val p = deck()
        // All the visible slides; the hidden fourth one is passed over.
        assertEquals(listOf(0, 1, 2, 4, 5), ShowPlan.of(p))
        // Slides 2 to 5.
        p.show = ShowSetup(from = 2, to = 5)
        assertEquals(listOf(1, 2, 4), ShowPlan.of(p))
        // From slide 3 to the end.
        p.show = ShowSetup(from = 3)
        assertEquals(listOf(2, 4, 5), ShowPlan.of(p))
        // A custom show plays its slides in its order, a hidden one too.
        p.show = ShowSetup(custom = "Short")
        assertEquals(listOf(4, 0, 3), ShowPlan.of(p))
        // Played from the ribbon, another custom show; From Current Slide goes on from the slide, whatever the setup.
        assertEquals(listOf(1, 2), ShowPlan.of(p, custom = "Team & Board"))
        assertEquals(listOf(3, 4, 5), ShowPlan.of(p, fromSlide = 3))
        // A custom show whose slides are all gone plays the deck.
        p.customShows = listOf(CustomShow("Gone", listOf(-1L)))
        p.show = ShowSetup(custom = "Gone")
        assertEquals(listOf(0, 1, 2, 4, 5), ShowPlan.of(p))
    }

    @Test
    fun powerPointKeepsIt() {
        val p = deck().apply {
            show = ShowSetup(ShowType.KIOSK, animations = false, from = 2, to = 5)
            loopShow = true; useTimings = false; showNarration = false
        }
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        val props = String(parts["ppt/presProps.xml"]!!)
        assertTrue(props, props.contains("""<p:showPr loop="1" showNarration="0" showAnimation="0" useTimings="0"><p:kiosk/><p:sldRg st="2" end="5"/></p:showPr>"""))
        assertTrue(String(parts["ppt/presentation.xml"]!!).contains("""<p:custShow name="Team &amp; Board" id="1">"""))
        val back = PptxReader.read(bytes)
        assertEquals(p.show, back.show)
        assertTrue(back.loopShow); assertFalse(back.useTimings); assertFalse(back.showNarration)
        assertEquals(shows(p), shows(back))
        // A custom show set up to play, by its id.
        p.show = ShowSetup(ShowType.WINDOW, custom = "Team & Board")
        val again = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray())
        assertEquals(p.show, again.show)
    }

    @Test
    fun impressKeepsWhatItCan() {
        val p = deck().apply { show = ShowSetup(ShowType.WINDOW, animations = false, from = 3); loopShow = true; useTimings = false }
        val bytes = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        val back = OdpReader.read(bytes)
        assertEquals(p.show, back.show)
        assertTrue(back.loopShow); assertFalse(back.useTimings)
        assertEquals(shows(p), shows(back))
        // A kiosk; a custom show to play.
        p.show = ShowSetup(ShowType.KIOSK, custom = "Short")
        val kiosk = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray())
        assertEquals(p.show, kiosk.show)
    }

    @Test
    fun customShowsAreOneStepBack() {
        val e = SlideEditor().apply { load(deck()) }
        e.setShowSetup(ShowSetup(custom = "Short"), loop = true)
        e.setCustomShows(listOf(CustomShow("Brief", e.presentation.customShows[0].slides)), renamed = mapOf("Short" to "Brief"))
        assertEquals("Brief", e.presentation.show.custom)
        // Removing the show Set Up plays: the whole deck again.
        e.setCustomShows(emptyList())
        assertEquals(null, e.presentation.show.custom)
        e.undo(); e.undo()
        assertEquals("Short", e.presentation.show.custom)
        assertEquals(listOf("Short", "Team & Board"), e.presentation.customShows.map { it.name })
        e.undo()
        assertEquals(ShowSetup(), e.presentation.show); assertFalse(e.presentation.loopShow)
    }

    /** With `-Daero.sampleDir`: the deck as pptx and ODP, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val p = deck().apply { show = ShowSetup(ShowType.KIOSK, animations = false, from = 2, to = 5); loopShow = true; useTimings = false; showNarration = false }
        File(dir, "show-setup.pptx").outputStream().use { PptxWriter.write(p, it) }
        File(dir, "show-setup.odp").outputStream().use { OdpWriter.write(p, it) }
        val q = deck().apply { show = ShowSetup(ShowType.WINDOW, custom = "Team & Board") }
        File(dir, "show-custom.pptx").outputStream().use { PptxWriter.write(q, it) }
        File(dir, "show-custom.odp").outputStream().use { OdpWriter.write(q, it) }
    }
}
