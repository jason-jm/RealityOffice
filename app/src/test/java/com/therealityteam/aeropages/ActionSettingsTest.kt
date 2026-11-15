package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideLinks
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** PowerPoint's Action Settings: click and mouse-over actions on shapes and pictures, action buttons, pptx and ODP. */
class ActionSettingsTest {

    private fun titled(t: String) = Slide(title = SlideBox(t, CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER))

    /** Three slides; on the first a button that goes on with a click and to the third when pointed at, and a picture that ends the show on mouse over. */
    private fun deck(): Presentation {
        val a = titled("One"); val b = titled("Two"); val c = titled("Three")
        a.shapes.add(SlideShape(ShapeType.ROUNDED, SlideBox("Go", CharacterFormat(fontSizePt = 18f)), action = SlideLinks.NEXT).also { it.hoverAction = SlideLinks.toSlide(c) })
        a.images.add(SlideImage(TestPng.blend(4, 4, 0x3366CC), "image/png", 1f, 1f).also { it.hoverAction = SlideLinks.END })
        b.shapes.add(SlideShape(ShapeType.RECT, SlideBox("Web", CharacterFormat(fontSizePt = 18f))).also { it.hoverAction = "https://example.com/" })
        return Presentation(mutableListOf(a, b, c))
    }

    /** Each shape's and picture's click and mouse-over actions, slides named by index. */
    private fun actions(p: Presentation): List<String> = buildList {
        fun say(link: String?) = SlideLinks.indexIn(p, link)?.let { "slide $it" } ?: link.toString()
        p.slides.forEachIndexed { i, s ->
            s.shapes.forEach { add("$i shape ${it.box.text}: ${say(it.action)} / ${say(it.hoverAction)}") }
            s.images.forEach { add("$i picture: ${say(it.action)} / ${say(it.hoverAction)}") }
        }
    }

    private fun entry(zip: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        }
        error("no $name")
    }

    @Test
    fun mouseOverActionsGoThroughPptxAndOdp() {
        val d = deck()
        val expected = actions(d)
        assertEquals(listOf("0 shape Go: #next / slide 2", "0 picture: null / #end", "1 shape Web: null / https://example.com/"), expected)
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(d, it) }.toByteArray()
        val slide1 = entry(pptx, "ppt/slides/slide1.xml")
        // After the click, in CT_NonVisualDrawingProps order; a slide jump through a slide relationship.
        assertTrue(slide1, Regex("""<a:hlinkClick [^>]*ppaction://hlinkshowjump\?jump=nextslide[^>]*/><a:hlinkHover [^>]*ppaction://hlinksldjump""").containsMatchIn(slide1))
        assertTrue(slide1, Regex("""<a:hlinkHover [^>]*action="ppaction://hlinkshowjump\?jump=endshow"/>""").containsMatchIn(slide1))
        assertEquals(expected, actions(PptxReader.read(pptx)))
        val odp = ByteArrayOutputStream().also { OdpWriter.write(d, it) }.toByteArray()
        val content = entry(odp, "content.xml")
        assertTrue(content, content.contains("script:event-name=\"dom:mouseover\""))
        assertEquals(expected, actions(OdpReader.read(odp)))
    }

    @Test
    fun actionSettingsAreOneStepBack() {
        val editor = SlideEditor()
        editor.load(deck())
        assertNull(editor.targetActions)
        assertFalse(editor.setObjectActions(SlideLinks.LAST, null))
        val go = editor.slide.shapes[0]
        editor.selectShape(go)
        assertEquals(SlideLinks.NEXT to SlideLinks.toSlide(editor.presentation.slides[2]), editor.targetActions)
        assertTrue(editor.setObjectActions(SlideLinks.LAST, " "))
        assertEquals(SlideLinks.LAST, go.action); assertNull(go.hoverAction)
        assertFalse(editor.setObjectActions(SlideLinks.LAST, null))           // nothing changes
        editor.undo()
        val back = editor.presentation.slides[0].shapes[0]
        assertEquals(SlideLinks.NEXT, back.action)
        assertEquals(2, SlideLinks.indexIn(editor.presentation, back.hoverAction))
        // A picture takes them too.
        val pic = editor.presentation.slides[0].images[0]
        editor.selectImage(pic)
        assertTrue(editor.setObjectActions("https://example.com/", null))
        assertEquals("https://example.com/", pic.action); assertNull(pic.hoverAction)
    }

    @Test
    fun anActionButtonComesWithItsActionAndAsksForSettings() {
        val editor = SlideEditor()
        editor.load(deck())
        val before = editor.actionButtonsInserted
        editor.addPreset("actionButtonBackPrevious")
        val back = editor.selectedShape!!
        assertEquals("actionButtonBackPrevious", back.preset)
        assertEquals(SlideLinks.PREVIOUS, back.action)
        assertEquals(before + 1, editor.actionButtonsInserted)
        editor.addPreset("actionButtonBeginning")
        assertEquals(SlideLinks.FIRST, editor.selectedShape!!.action)
        editor.addPreset("actionButtonEnd")
        assertEquals(SlideLinks.LAST, editor.selectedShape!!.action)
        editor.addPreset("actionButtonBlank")
        assertNull(editor.selectedShape!!.action)                             // none until Action Settings says
        assertEquals(before + 4, editor.actionButtonsInserted)
        editor.addPreset("hexagon")
        assertEquals(before + 4, editor.actionButtonsInserted)                // an ordinary shape doesn't ask
        assertNull(editor.selectedShape!!.action)
    }

    @Test
    fun copiesAndRemapsCarryTheMouseOver() {
        val d = deck()
        val sh = d.slides[0].shapes[0]
        assertEquals(sh.hoverAction, sh.copyOf().hoverAction)
        val img = d.slides[0].images[0]
        assertEquals(SlideLinks.END, img.copyOf().hoverAction)
    }

    /** With `-Daero.sampleDir`: the deck with click and mouse-over actions, for LibreOffice and the app. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { java.io.File(it).also { d -> d.mkdirs() } } ?: return
        java.io.File(dir, "actions.pptx").outputStream().use { PptxWriter.write(deck(), it) }
        java.io.File(dir, "actions.odp").outputStream().use { OdpWriter.write(deck(), it) }
        java.io.File(dir, "click-actions.pptx").outputStream().use { PptxWriter.write(clickDeck(), it) }
    }

    /** Every kind of click action on one slide: next, a slide, an address, the end; a picture to the first; Back on the second slide. */
    private fun clickDeck(): Presentation {
        val a = titled("One"); val b = titled("Two"); val c = titled("Three")
        fun button(label: String, x: Float, action: String) = SlideShape(ShapeType.RECT, SlideBox(label, CharacterFormat(fontSizePt = 18f)), xPct = x, yPct = 0.6f, wPct = 0.15f, hPct = 0.12f, action = action)
        a.shapes.add(button("Next", 0.05f, SlideLinks.NEXT)); a.shapes.add(button("Three", 0.25f, SlideLinks.toSlide(c)))
        a.shapes.add(button("Web", 0.45f, "https://example.com/")); a.shapes.add(button("End", 0.65f, SlideLinks.END))
        a.images.add(SlideImage(TestPng.blend(4, 4, 0x3366CC), "image/png", 1f, 1f, 0.8f, 0.2f, 0.1f, 0.1f, action = SlideLinks.FIRST))
        b.shapes.add(button("Back", 0.05f, SlideLinks.PREVIOUS))
        return Presentation(mutableListOf(a, b, c))
    }
}
