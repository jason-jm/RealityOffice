package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimStart
import com.therealityteam.aeropages.model.ElementAnim
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
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

/** Slide animations in pptx: PowerPoint's main sequence of click groups, with every effect class, start and delay. */
class PptxAnimationTest {

    private fun write(slide: Slide): Pair<ByteArray, String> {
        val bos = ByteArrayOutputStream()
        PptxWriter.write(Presentation(mutableListOf(slide)), bos)
        val bytes = bos.toByteArray()
        return bytes to String(OfficeXml.unzip(bytes)["ppt/slides/slide1.xml"]!!, Charsets.UTF_8)
    }

    @Test
    fun writerEmitsEffectBehavioursAndDirections() {
        val slide = Slide()
        slide.title.anims.add(ElementAnim(AnimEffect.FADE, durationMs = 600, order = 0))
        slide.body.anims.add(ElementAnim(AnimEffect.FLY_IN, durationMs = 800, dir = AnimDir.BOTTOM, order = 1))
        val shape = SlideShape(ShapeType.RECT)
        shape.box.anims.add(ElementAnim(AnimEffect.WIPE, dir = AnimDir.LEFT, order = 2))
        slide.shapes.add(shape)
        val (bytes, xml) = write(slide)
        assertTrue(xml.contains("""<p:animEffect transition="in" filter="fade"><p:cBhvr><p:cTn id="""))
        assertTrue(xml.contains("""dur="600""""))
        assertTrue(xml.contains("""presetID="2" presetClass="entr" presetSubtype="4""""))
        assertTrue(xml.contains("""<p:attrName>ppt_y</p:attrName>""") && xml.contains("""1+#ppt_h/2"""))
        assertTrue(xml.contains("""filter="wipe(left)""""))
        assertTrue("a fade has no direction", xml.contains("""presetID="10" presetClass="entr" presetSubtype="0""""))
        assertTrue(xml.contains("""<p:bldP spid="4" grpId="0" animBg="1"/>"""))
        assertFalse("the alt text stays the user's", xml.contains("aero-anim") || xml.contains("descr="))
        val back = PptxReader.read(bytes).slides[0]
        back.title.anims.single().let { assertEquals(AnimEffect.FADE, it.effect); assertEquals(600, it.durationMs); assertEquals(0, it.order) }
        back.body.anims.single().let { assertEquals(AnimEffect.FLY_IN, it.effect); assertEquals(AnimDir.BOTTOM, it.dir); assertEquals(800, it.durationMs); assertEquals(1, it.order) }
        back.shapes[0].box.anims.single().let { assertEquals(AnimEffect.WIPE, it.effect); assertEquals(AnimDir.LEFT, it.dir); assertEquals(2, it.order) }
    }

    @Test
    fun everyEffectClassStartAndDelayRoundTrips() {
        val slide = Slide()
        val pic = SlideImage(TestPng.header(40, 30), "image/png", 40f, 30f)
        slide.images.add(pic)
        val table = SlideTable.create(1, 2)
        slide.tables.add(table)
        // Title: in on click 1; picture: pulses with it after 250 ms, then spins round after it; table:
        // fades in on click 2 and grows; title: flies out to the right after the table.
        slide.title.anims.add(ElementAnim(AnimEffect.FADE, AnimStart.ON_CLICK, 500, 0, order = 0))
        pic.anims.add(ElementAnim(AnimEffect.PULSE, AnimStart.WITH_PREVIOUS, 1000, 250, order = 1))
        pic.anims.add(ElementAnim(AnimEffect.ROTATE, AnimStart.AFTER_PREVIOUS, 2000, 0, order = 2))
        table.anims.add(ElementAnim(AnimEffect.FADE, AnimStart.ON_CLICK, 500, 0, order = 3))
        table.anims.add(ElementAnim(AnimEffect.GROW_SHRINK, AnimStart.WITH_PREVIOUS, 1000, 0, order = 4))
        slide.title.anims.add(ElementAnim(AnimEffect.FLY_OUT, AnimStart.AFTER_PREVIOUS, 750, 500, AnimDir.RIGHT, order = 5))
        val (bytes, xml) = write(slide)
        assertEquals("two click groups", 2, Regex("""<p:cond delay="indefinite"/></p:stCondLst>""").findAll(xml).count())
        assertTrue(xml.contains("""presetClass="emph" presetSubtype="0" fill="hold" grpId="0" nodeType="withEffect"><p:stCondLst><p:cond delay="250"/>"""))
        assertTrue("an after-previous chain starts when the one before ends (250 + 1000)", xml.contains("""<p:cond delay="1250"/></p:stCondLst><p:childTnLst><p:par><p:cTn id="""))
        assertTrue(xml.contains("""presetID="8" presetClass="emph" presetSubtype="0" fill="hold" grpId="1" nodeType="afterEffect""""))
        assertTrue(xml.contains("""presetID="2" presetClass="exit" presetSubtype="2" fill="hold" grpId="1" nodeType="afterEffect"><p:stCondLst><p:cond delay="500"/>"""))
        assertTrue("an exit hides its shape as it ends", xml.contains("""<p:cond delay="749"/></p:stCondLst></p:cTn><p:tgtEl><p:spTgt spid="2"/></p:tgtEl><p:attrNameLst><p:attrName>style.visibility</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="hidden"/>"""))
        assertTrue("frames build as graphics, pictures not at all", xml.contains("""<p:bldGraphic spid=""") && !xml.contains("""<p:bldP spid="4""""))
        val back = PptxReader.read(bytes).slides[0]
        fun check(a: ElementAnim, effect: AnimEffect, start: AnimStart, ms: Int, delay: Int, order: Int) {
            assertEquals(effect, a.effect); assertEquals(start, a.start); assertEquals(ms, a.durationMs); assertEquals(delay, a.delayMs); assertEquals(order, a.order)
        }
        check(back.title.anims[0], AnimEffect.FADE, AnimStart.ON_CLICK, 500, 0, 0)
        check(back.images[0].anims[0], AnimEffect.PULSE, AnimStart.WITH_PREVIOUS, 1000, 250, 1)
        check(back.images[0].anims[1], AnimEffect.ROTATE, AnimStart.AFTER_PREVIOUS, 2000, 0, 2)
        check(back.tables[0].anims[0], AnimEffect.FADE, AnimStart.ON_CLICK, 500, 0, 3)
        check(back.tables[0].anims[1], AnimEffect.GROW_SHRINK, AnimStart.WITH_PREVIOUS, 1000, 0, 4)
        check(back.title.anims[1], AnimEffect.FLY_OUT, AnimStart.AFTER_PREVIOUS, 750, 500, 5)
        assertEquals(AnimDir.RIGHT, back.title.anims[1].dir)
    }

    @Test
    fun aBodyBuiltByParagraphRoundTrips() {
        val slide = Slide(title = com.therealityteam.aeropages.model.SlideBox("T"), body = com.therealityteam.aeropages.model.SlideBox("One\nTwo\n\nThree"))
        slide.body.anims.add(ElementAnim(AnimEffect.FLY_IN, AnimStart.ON_CLICK, 500, 0, AnimDir.LEFT, 0, byParagraph = true))
        slide.title.anims.add(ElementAnim(AnimEffect.FADE, AnimStart.AFTER_PREVIOUS, order = 1))
        val (bytes, xml) = write(slide)
        assertTrue("one click group per non-blank paragraph", Regex("""<p:cond delay="indefinite"/></p:stCondLst>""").findAll(xml).count() == 3)
        listOf(0, 1, 3).forEach { assertTrue(xml, xml.contains("""<p:spTgt spid="3"><p:txEl><p:pRg st="$it" end="$it"/></p:txEl></p:spTgt>""")) }
        assertFalse("the blank paragraph has no effect", xml.contains("""<p:pRg st="2" end="2"/>"""))
        assertTrue(xml.contains("""<p:bldP spid="3" grpId="0" build="p"/>"""))
        val back = PptxReader.read(bytes).slides[0]
        back.body.anims.single().let { assertTrue(it.byParagraph); assertEquals(AnimEffect.FLY_IN, it.effect); assertEquals(AnimStart.ON_CLICK, it.start); assertEquals(0, it.order) }
        back.title.anims.single().let { assertEquals(AnimStart.AFTER_PREVIOUS, it.start); assertEquals(1, it.order) }
    }

    @Test
    fun effectsBeforeTheFirstClickStartWithTheSlide() {
        val slide = Slide()
        slide.title.anims.add(ElementAnim(AnimEffect.ZOOM, AnimStart.AFTER_PREVIOUS, 500, 1000, order = 0))
        slide.body.anims.add(ElementAnim(AnimEffect.APPEAR, AnimStart.ON_CLICK, order = 1))
        val (bytes, xml) = write(slide)
        assertTrue(xml.contains("""<p:cond delay="indefinite"/><p:cond evt="onBegin" delay="0"><p:tn val="2"/></p:cond>"""))
        val back = PptxReader.read(bytes).slides[0]
        assertEquals(AnimStart.AFTER_PREVIOUS, back.title.anims.single().start)
        assertEquals(1000, back.title.anims.single().delayMs)
        assertEquals(AnimEffect.APPEAR, back.body.anims.single().effect)
    }

    @Test
    fun everyEffectWritesAndReadsBackAsItself() {
        AnimEffect.entries.forEach { e ->
            val slide = Slide()
            slide.title.anims.add(ElementAnim(e, durationMs = 800, dir = AnimDir.TOP))
            val (bytes, _) = write(slide)
            val a = PptxReader.read(bytes).slides[0].title.anims.single()
            assertEquals(e, a.effect)
            if (e.directional) assertEquals(e.name, AnimDir.TOP, a.dir)
        }
    }

    @Test
    fun readerRecoversPresetsFromForeignTimingTrees() {
        val slideXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title 1"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="en-US"/><a:t>Hello</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="3" name="Content 2"/><p:cNvSpPr/><p:nvPr><p:ph idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:p><a:r><a:t>One</a:t></a:r></a:p><a:p><a:r><a:t>Two</a:t></a:r></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="7" name="Rectangle 6"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="1000000" y="1000000"/><a:ext cx="2000000" cy="1000000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:p><a:r><a:rPr lang="en-US"/><a:t>Box</a:t></a:r></a:p></p:txBody></p:sp>
</p:spTree></p:cSld>
<p:timing><p:tnLst><p:par><p:cTn id="1" dur="indefinite" restart="never" nodeType="tmRoot"><p:childTnLst><p:seq concurrent="1" nextAc="seek"><p:cTn id="2" dur="indefinite" nodeType="mainSeq"><p:childTnLst>
<p:par><p:cTn id="3" fill="hold"><p:stCondLst><p:cond delay="indefinite"/></p:stCondLst><p:childTnLst><p:par><p:cTn id="4" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:par><p:cTn id="5" presetID="2" presetClass="entr" presetSubtype="8" fill="hold" nodeType="clickEffect"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:set><p:cBhvr><p:cTn id="6" dur="1" fill="hold"/><p:tgtEl><p:spTgt spid="7"/></p:tgtEl><p:attrNameLst><p:attrName>style.visibility</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="visible"/></p:to></p:set>
<p:anim calcmode="lin" valueType="num"><p:cBhvr additive="base"><p:cTn id="7" dur="700" fill="hold"/><p:tgtEl><p:spTgt spid="7"/></p:tgtEl><p:attrNameLst><p:attrName>ppt_x</p:attrName></p:attrNameLst></p:cBhvr><p:tavLst><p:tav tm="0"><p:val><p:strVal val="0-#ppt_w/2"/></p:val></p:tav><p:tav tm="100000"><p:val><p:strVal val="#ppt_x"/></p:val></p:tav></p:tavLst></p:anim>
</p:childTnLst></p:cTn></p:par>
<p:par><p:cTn id="8" presetID="10" presetClass="entr" presetSubtype="0" fill="hold" nodeType="withEffect"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:set><p:cBhvr><p:cTn id="9" dur="1" fill="hold"/><p:tgtEl><p:spTgt spid="2"/></p:tgtEl><p:attrNameLst><p:attrName>style.visibility</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="visible"/></p:to></p:set>
<p:animEffect transition="in" filter="fade"><p:cBhvr><p:cTn id="10" dur="1500"/><p:tgtEl><p:spTgt spid="2"/></p:tgtEl></p:cBhvr></p:animEffect>
</p:childTnLst></p:cTn></p:par>
</p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:par>
<p:par><p:cTn id="11" fill="hold"><p:stCondLst><p:cond delay="indefinite"/></p:stCondLst><p:childTnLst><p:par><p:cTn id="12" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:par><p:cTn id="13" presetID="47" presetClass="entr" presetSubtype="0" fill="hold" grpId="0" nodeType="clickEffect"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:animEffect transition="in" filter="fade"><p:cBhvr><p:cTn id="14" dur="1000"/><p:tgtEl><p:spTgt spid="3"><p:txEl><p:pRg st="0" end="0"/></p:txEl></p:spTgt></p:tgtEl></p:cBhvr></p:animEffect>
</p:childTnLst></p:cTn></p:par>
</p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:par>
<p:par><p:cTn id="15" fill="hold"><p:stCondLst><p:cond delay="indefinite"/></p:stCondLst><p:childTnLst><p:par><p:cTn id="16" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:par><p:cTn id="17" presetID="47" presetClass="entr" presetSubtype="0" fill="hold" grpId="0" nodeType="clickEffect"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:animEffect transition="in" filter="fade"><p:cBhvr><p:cTn id="18" dur="1000"/><p:tgtEl><p:spTgt spid="3"><p:txEl><p:pRg st="1" end="1"/></p:txEl></p:spTgt></p:tgtEl></p:cBhvr></p:animEffect>
</p:childTnLst></p:cTn></p:par>
</p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:par>
<p:par><p:cTn id="19" fill="hold"><p:stCondLst><p:cond delay="indefinite"/></p:stCondLst><p:childTnLst><p:par><p:cTn id="20" fill="hold"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:par><p:cTn id="21" presetID="0" presetClass="path" presetSubtype="0" fill="hold" nodeType="clickEffect"><p:stCondLst><p:cond delay="0"/></p:stCondLst><p:childTnLst>
<p:animMotion origin="layout" path="M 0 0 L 0.25 0 E" pathEditMode="relative"><p:cBhvr><p:cTn id="22" dur="2000" fill="hold"/><p:tgtEl><p:spTgt spid="7"/></p:tgtEl></p:cBhvr></p:animMotion>
</p:childTnLst></p:cTn></p:par>
</p:childTnLst></p:cTn></p:par></p:childTnLst></p:cTn></p:par>
</p:childTnLst></p:cTn></p:seq></p:childTnLst></p:cTn></p:par></p:tnLst></p:timing>
</p:sld>"""
        val presentation = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst></p:presentation>"""
        val presRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/></Relationships>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, c) in listOf("ppt/presentation.xml" to presentation, "ppt/_rels/presentation.xml.rels" to presRels, "ppt/slides/slide1.xml" to slideXml)) {
                z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry()
            }
        }
        val slide = PptxReader.read(bos.toByteArray()).slides[0]
        assertEquals("Hello", slide.title.text)
        assertEquals(1, slide.shapes.size)
        slide.shapes[0].box.anims.single().let {
            assertEquals(AnimEffect.FLY_IN, it.effect); assertEquals(AnimDir.LEFT, it.dir); assertEquals(700, it.durationMs)
            assertEquals(AnimStart.ON_CLICK, it.start); assertEquals(0, it.order)
        }
        slide.title.anims.single().let {
            assertEquals(AnimEffect.FADE, it.effect); assertEquals(1500, it.durationMs); assertEquals(AnimStart.WITH_PREVIOUS, it.start); assertEquals(1, it.order)
        }
        // A body built paragraph by paragraph counts once (Descend, the float-down entrance); the motion path is skipped.
        slide.body.anims.single().let { assertEquals(AnimEffect.FLOAT_IN, it.effect); assertEquals(1000, it.durationMs); assertEquals(2, it.order) }
    }
}
