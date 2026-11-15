package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimStart
import com.therealityteam.aeropages.model.ElementAnim
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Slide animations in ODP: the page's anim:par timing root as Impress writes it; hidden slides as Impress marks them. */
class OdpAnimationTest {

    private fun write(p: Presentation): Pair<ByteArray, String> {
        val bos = ByteArrayOutputStream()
        OdpWriter.write(p, bos)
        val bytes = bos.toByteArray()
        return bytes to String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
    }

    @Test
    fun everyElementKindStartAndDelayRoundTrips() {
        val slide = Slide(title = SlideBox("Title"), body = SlideBox("Body"))
        val pic = SlideImage(TestPng.header(40, 30), "image/png", 40f, 30f)
        val shape = SlideShape(ShapeType.ROUNDED, SlideBox("Box"))
        val table = SlideTable.create(1, 2)
        slide.images.add(pic); slide.shapes.add(shape); slide.tables.add(table)
        slide.title.anims.add(ElementAnim(AnimEffect.FADE, AnimStart.ON_CLICK, 500, 0, order = 0))
        slide.body.anims.add(ElementAnim(AnimEffect.FLY_IN, AnimStart.WITH_PREVIOUS, 600, 250, AnimDir.RIGHT, order = 1))
        pic.anims.add(ElementAnim(AnimEffect.PULSE, AnimStart.AFTER_PREVIOUS, 500, 0, order = 2))
        shape.box.anims.add(ElementAnim(AnimEffect.GROW_SHRINK, AnimStart.ON_CLICK, 700, 0, order = 3))
        table.anims.add(ElementAnim(AnimEffect.WIPE_OUT, AnimStart.ON_CLICK, 800, 100, AnimDir.TOP, order = 4))
        pic.anims.add(ElementAnim(AnimEffect.FADE_OUT, AnimStart.WITH_PREVIOUS, 500, 0, order = 5))
        val (bytes, xml) = write(Presentation(mutableListOf(slide)))
        assertTrue(xml.contains("""presentation:node-type="timing-root"""") && xml.contains("""presentation:node-type="main-sequence""""))
        assertTrue("three click groups", Regex("""<anim:par smil:begin="next" smil:fill="hold">""").findAll(xml).count() == 3)
        assertTrue(xml.contains("""<anim:par smil:begin="0.25s" smil:fill="hold" presentation:node-type="with-previous" presentation:preset-class="entrance" presentation:preset-id="ooo-entrance-fly-in" presentation:preset-sub-type="from-right">"""))
        assertTrue("an after-previous chain starts when the one before ends (250 + 600)", xml.contains("""<anim:par smil:begin="0.85s" smil:fill="hold"><anim:par smil:begin="0s" smil:fill="hold" presentation:node-type="after-previous" presentation:preset-class="emphasis" presentation:preset-id="ooo-emphasis-flash-bulb">"""))
        assertTrue(xml.contains("""smil:type="barWipe" smil:subtype="topToBottom" smil:mode="out""""))
        assertTrue("every animated element carries its id", Regex("""xml:id="id\d" draw:id="id\d"""").findAll(xml).count() == 5)
        assertTrue("the timing root comes after the shapes", xml.indexOf("timing-root") > xml.lastIndexOf("<table:table"))
        val back = OdpReader.read(bytes).slides[0]
        fun check(a: ElementAnim, effect: AnimEffect, start: AnimStart, ms: Int, delay: Int, order: Int, dir: AnimDir? = null) {
            assertEquals(effect, a.effect); assertEquals(start, a.start); assertEquals(ms, a.durationMs); assertEquals(delay, a.delayMs); assertEquals(order, a.order)
            dir?.let { assertEquals(it, a.dir) }
        }
        check(back.title.anims.single(), AnimEffect.FADE, AnimStart.ON_CLICK, 500, 0, 0)
        check(back.body.anims.single(), AnimEffect.FLY_IN, AnimStart.WITH_PREVIOUS, 600, 250, 1, AnimDir.RIGHT)
        check(back.images[0].anims[0], AnimEffect.PULSE, AnimStart.AFTER_PREVIOUS, 500, 0, 2)
        check(back.shapes[0].box.anims.single(), AnimEffect.GROW_SHRINK, AnimStart.ON_CLICK, 700, 0, 3)
        check(back.tables[0].anims.single(), AnimEffect.WIPE_OUT, AnimStart.ON_CLICK, 800, 100, 4, AnimDir.TOP)
        check(back.images[0].anims[1], AnimEffect.FADE_OUT, AnimStart.WITH_PREVIOUS, 500, 0, 5)
    }

    @Test
    fun everyEffectWritesAndReadsBackAsItself() {
        AnimEffect.entries.forEach { e ->
            val slide = Slide(title = SlideBox("T"))
            slide.title.anims.add(ElementAnim(e, durationMs = 800, dir = AnimDir.BOTTOM))
            val (bytes, _) = write(Presentation(mutableListOf(slide)))
            val a = OdpReader.read(bytes).slides[0].title.anims.single()
            assertEquals(e, a.effect)
            if (!e.instant) assertEquals(e.name, 800f, a.durationMs.toFloat(), 8f)
            if (e.directional) assertEquals(e.name, AnimDir.BOTTOM, a.dir)
        }
    }

    @Test
    fun whatStartsWithTheSlideAndHiddenSlides() {
        val slide = Slide(title = SlideBox("Auto"), body = SlideBox("Body"))
        slide.title.anims.add(ElementAnim(AnimEffect.ZOOM, AnimStart.AFTER_PREVIOUS, 500, 1000, order = 0))
        slide.body.anims.add(ElementAnim(AnimEffect.APPEAR, AnimStart.ON_CLICK, order = 1))
        slide.hidden = true
        val (bytes, xml) = write(Presentation(mutableListOf(slide, Slide(title = SlideBox("Shown")))))
        assertTrue(xml.contains("""<anim:seq smil:dur="indefinite" presentation:node-type="main-sequence"><anim:par smil:begin="0s" smil:fill="hold">"""))
        assertTrue("hidden the way Impress marks it", xml.contains("""presentation:visibility="hidden""""))
        assertFalse(xml.contains("""presentation:class="hidden""""))
        val back = OdpReader.read(bytes).slides
        assertTrue(back[0].hidden); assertFalse(back[1].hidden)
        back[0].title.anims.single().let { assertEquals(AnimStart.AFTER_PREVIOUS, it.start); assertEquals(1000, it.delayMs) }
        assertEquals(AnimStart.ON_CLICK, back[0].body.anims.single().start)
        assertTrue("a slide without effects gets no timing root", back[1].title.anims.isEmpty())
    }

    @Test
    fun aBodyBuiltByParagraphRoundTripsAndIdsAreUniqueAcrossPages() {
        val first = Slide(title = SlideBox("T1"), body = SlideBox("One\nTwo\n\nThree"))
        first.body.anims.add(ElementAnim(AnimEffect.FADE, AnimStart.ON_CLICK, 500, 0, order = 0, byParagraph = true))
        val second = Slide(title = SlideBox("T2"), body = SlideBox("Alpha"))
        second.title.anims.add(ElementAnim(AnimEffect.ZOOM, order = 0))
        val (bytes, xml) = write(Presentation(mutableListOf(first, second)))
        val ids = Regex("""xml:id="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals("xml:id is unique in the document: $ids", ids.size, ids.toSet().size)
        assertTrue("the paragraphs built one by one carry ids", Regex("""<text:p xml:id="id\d+" text:id="id\d+"""").findAll(xml).count() == 3)
        assertTrue(xml.contains("""anim:sub-item="text""""))
        val back = OdpReader.read(bytes).slides
        back[0].body.anims.single().let { assertTrue(it.byParagraph); assertEquals(AnimEffect.FADE, it.effect); assertEquals(AnimStart.ON_CLICK, it.start) }
        assertEquals("One\nTwo\n\nThree", back[0].body.text)
        assertEquals(AnimEffect.ZOOM, back[1].title.anims.single().effect)
    }

    @Test
    fun readsImpressOwnTiming() {
        // As LibreOffice Impress writes a deck (ids on the shapes, Impress's preset names and SMIL).
        val content = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" xmlns:anim="urn:oasis:names:tc:opendocument:xmlns:animation:1.0" xmlns:smil="urn:oasis:names:tc:opendocument:xmlns:smil-compatible:1.0" xmlns:xlink="http://www.w3.org/1999/xlink" office:version="1.3">
<office:body><office:presentation>
<draw:page draw:name="page1" draw:master-page-name="Default">
<draw:frame presentation:class="title" xml:id="id1" draw:id="id1" svg:width="20cm" svg:height="3cm" svg:x="1cm" svg:y="1cm"><draw:text-box><text:p>Hello</text:p></draw:text-box></draw:frame>
<draw:custom-shape xml:id="id2" draw:id="id2" svg:width="6cm" svg:height="2cm" svg:x="2cm" svg:y="8cm"><text:p>Box</text:p><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>
<anim:par smil:dur="indefinite" smil:restart="never" presentation:node-type="timing-root"><anim:seq smil:dur="indefinite" presentation:node-type="main-sequence">
<anim:par smil:begin="next" smil:fill="hold"><anim:par smil:begin="0s" smil:fill="hold">
<anim:par smil:begin="0s" smil:fill="hold" presentation:node-type="on-click" presentation:preset-class="entrance" presentation:preset-id="ooo-entrance-fly-in" presentation:preset-sub-type="from-left">
<anim:set smil:begin="0s" smil:dur="0.001s" smil:fill="hold" smil:targetElement="id2" smil:attributeName="visibility" smil:to="visible"/>
<anim:animate smil:dur="0.6s" smil:fill="hold" smil:targetElement="id2" smil:attributeName="x" smil:values="0-width/2;x" smil:keyTimes="0;1"/></anim:par>
<anim:par smil:begin="0.25s" smil:fill="hold" presentation:node-type="with-previous" presentation:preset-class="entrance" presentation:preset-id="ooo-entrance-dissolve-in">
<anim:set smil:begin="0s" smil:dur="0.001s" smil:fill="hold" smil:targetElement="id1" smil:attributeName="visibility" smil:to="visible"/>
<anim:transitionFilter smil:dur="1.5s" smil:targetElement="id1" smil:type="dissolve"/></anim:par>
</anim:par></anim:par>
<anim:par smil:begin="next" smil:fill="hold"><anim:par smil:begin="0s" smil:fill="hold">
<anim:par smil:begin="0s" smil:fill="hold" presentation:node-type="on-click" presentation:preset-class="emphasis" presentation:preset-id="ooo-emphasis-teeter">
<anim:animateTransform smil:dur="0.062s" smil:fill="hold" smil:autoReverse="true" smil:repeatCount="4" smil:targetElement="id2" smil:by="4" smil:attributeName="transform" svg:type="rotate"/></anim:par>
<anim:par smil:begin="0s" smil:fill="hold" presentation:node-type="with-previous" presentation:preset-class="motion-path" presentation:preset-id="ooo-motionpath-circle">
<anim:animateMotion smil:dur="2s" smil:fill="hold" smil:targetElement="id2" svg:path="m0 0h0.1"/></anim:par>
</anim:par></anim:par>
</anim:seq></anim:par>
</draw:page></office:presentation></office:body></office:document-content>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/vnd.oasis.opendocument.presentation".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
        }
        val slide = OdpReader.read(bos.toByteArray()).slides[0]
        assertEquals("Hello", slide.title.text)
        slide.shapes.single().box.anims.let { a ->
            assertEquals("the motion path is skipped", 2, a.size)
            assertEquals(AnimEffect.FLY_IN, a[0].effect); assertEquals(AnimDir.LEFT, a[0].dir); assertEquals(600, a[0].durationMs); assertEquals(0, a[0].order)
            assertEquals(AnimEffect.TEETER, a[1].effect); assertEquals("0.062 s × 2 × 4", 496, a[1].durationMs); assertEquals(2, a[1].order)
        }
        slide.title.anims.single().let {
            assertEquals("Impress's own dissolve reads as a fade", AnimEffect.FADE, it.effect)
            assertEquals(AnimStart.WITH_PREVIOUS, it.start); assertEquals(250, it.delayMs); assertEquals(1500, it.durationMs); assertEquals(1, it.order)
        }
    }
}
