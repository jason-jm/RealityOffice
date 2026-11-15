package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The slide master: deck-wide background + title / body text defaults, kept on
 * `p:sldMaster` (`p:bg`, `p:txStyles`) in pptx and on the master page in odp; theme colours
 * (`a:schemeClr`) of foreign decks resolve through the theme part and the master's colour map.
 */
class SlideMasterTest {

    private fun deck(): Presentation = Presentation(mutableListOf(
        Slide(title = SlideBox("One", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER), body = SlideBox("body one", CharacterFormat(fontSizePt = 22f))),
        Slide(title = SlideBox("Two", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER), body = SlideBox("body two", CharacterFormat(fontSizePt = 22f))),
    ))

    private fun zipEntries(bytes: ByteArray): Map<String, String> {
        val zin = ZipInputStream(ByteArrayInputStream(bytes))
        val out = HashMap<String, String>()
        while (true) {
            val e = zin.nextEntry ?: break
            out[e.name] = zin.readBytes().decodeToString()
        }
        return out
    }

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test
    fun slidesInheritTheMasterBackgroundUnlessTheyOverride() {
        val e = SlideEditor().also { it.load(deck()) }
        assertNull(e.presentation.backgroundOf(e.presentation.slides[0]))
        e.setMasterBackground(0xFF1F1F1F)
        assertEquals(0xFF1F1F1F, e.presentation.backgroundOf(e.presentation.slides[0]))
        assertEquals(0xFF1F1F1F, e.presentation.backgroundOf(e.presentation.slides[1]))
        assertNull("slides keep no background of their own", e.presentation.slides[0].background)
        e.setBackground(0xFFFFF2CC)
        assertEquals(0xFFFFF2CC, e.presentation.backgroundOf(e.presentation.slides[0]))
        assertEquals(0xFF1F1F1F, e.presentation.backgroundOf(e.presentation.slides[1]))
        e.undo(); e.undo()
        assertTrue(e.master.isDefault)
        assertNull(e.presentation.backgroundOf(e.presentation.slides[0]))
    }

    @Test
    fun masterTextStyleRestampsSizesAndDefaultsColours() {
        val e = SlideEditor().also { it.load(deck()) }
        e.setMasterTitleStyle(font = "Georgia", sizePt = 48f, color = 0xFFFFFFFF, bold = true)
        e.setMasterBodyStyle(sizePt = 24f, color = 0xFFDDDDDD)
        val m = e.master
        assertEquals("Georgia", m.titleFont); assertEquals(48f, m.titleSizePt); assertEquals(0xFFFFFFFF, m.titleColor); assertEquals(true, m.titleBold)
        assertEquals(24f, m.bodySizePt); assertEquals(0xFFDDDDDD, m.bodyColor)
        assertTrue(e.presentation.slides.all { s -> s.title.paragraph.runs.all { it.format.fontSizePt == 48f } })
        assertTrue(e.presentation.slides.all { s -> s.body.paragraph.runs.all { it.format.fontSizePt == 24f } })
        assertTrue("colours stay inherited, not stamped", e.presentation.slides.all { s -> s.title.paragraph.runs.all { it.format.color == null } })
        e.undo()
        assertNull(e.master.bodySizePt)
        assertTrue(e.presentation.slides.all { s -> s.body.paragraph.runs.all { it.format.fontSizePt == 22f } })
        e.undo()
        assertTrue(e.master.isDefault)
        assertTrue(e.presentation.slides.all { s -> s.title.paragraph.runs.all { it.format.fontSizePt == 40f } })
        e.resetMaster()
        assertTrue(e.master.isDefault)
    }

    @Test
    fun designThemesLiveOnTheMaster() {
        val e = SlideEditor().also { it.load(deck()) }
        e.setBackground(0xFF123456)
        e.applyThemeFull(2, 0xFF1F1F1F, 0xFFFFFFFF, 0xFFCCCCCC, TextAlign.LEFT)
        assertEquals(2, e.presentation.themeIndex)
        assertEquals(0xFF1F1F1F, e.master.background); assertEquals(0xFFFFFFFF, e.master.titleColor); assertEquals(0xFFCCCCCC, e.master.bodyColor)
        assertTrue("theme clears per-slide overrides", e.presentation.slides.all { it.background == null })
        assertEquals(TextAlign.LEFT, e.presentation.slides[0].title.align)
        e.setBackgroundAll(0xFF0000FF)
        assertEquals(0xFF0000FF, e.master.background)
        assertTrue(e.presentation.slides.all { it.background == null })
        e.undo(); e.undo()
        assertTrue(e.master.isDefault)
        assertEquals(0xFF123456, e.presentation.slides[0].background)
    }

    @Test
    fun pptxRoundTripKeepsTheMaster() {
        val pres = deck()
        pres.master.background = 0xFF1F1F1F
        pres.master.titleFont = "Georgia"; pres.master.titleSizePt = 44f; pres.master.titleColor = 0xFFFFFFFF; pres.master.titleBold = true
        pres.master.bodySizePt = 24f; pres.master.bodyColor = 0xFFDDDDDD; pres.master.bodyFont = "Arial"
        pres.slides[1].background = 0xFFFFF2CC
        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val parts = zipEntries(bos.toByteArray())
        val master = parts["ppt/slideMasters/slideMaster1.xml"]!!
        assertTrue(master.contains("<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"1F1F1F\"/>"))
        assertTrue(master.contains("<p:titleStyle><a:lvl1pPr><a:defRPr sz=\"4400\" b=\"1\"><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:latin typeface=\"Georgia\"/>"))
        assertTrue(master.contains("<p:bodyStyle><a:lvl1pPr><a:defRPr sz=\"2400\"><a:solidFill><a:srgbClr val=\"DDDDDD\"/></a:solidFill><a:latin typeface=\"Arial\"/>"))
        assertFalse("inheriting slide carries no p:bg", parts["ppt/slides/slide1.xml"]!!.contains("<p:bg>"))
        assertTrue(parts["ppt/slides/slide2.xml"]!!.contains("FFF2CC"))

        val back = PptxReader.read(bos.toByteArray())
        assertTrue(back.master.sameAs(pres.master))
        assertNull(back.slides[0].background)
        assertEquals(0xFFFFF2CC, back.slides[1].background)
        assertEquals(0xFF1F1F1F, back.backgroundOf(back.slides[0]))
        // Run sizes are explicit in the file, so the master size does not override them.
        assertEquals(40f, back.slides[0].title.paragraph.runs[0].format.fontSizePt)
    }

    @Test
    fun foreignDeckResolvesThemeColoursAndInheritsFromLayoutAndMaster() {
        val a = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
        val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
        val r = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val rel = { id: String, type: String, target: String -> "<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type\" Target=\"$target\"/>" }
        val relsHead = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        val theme = """<a:theme $a name="T"><a:themeElements><a:clrScheme name="S">
            <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
            <a:dk2><a:srgbClr val="1F3864"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
            <a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
            <a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4>
            <a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6>
            <a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>
            <a:fontScheme name="F"><a:majorFont><a:latin typeface="Georgia"/></a:majorFont><a:minorFont><a:latin typeface="Verdana"/></a:minorFont></a:fontScheme>
            </a:themeElements></a:theme>"""
        // Master: dark background via bgRef → bg2 (mapped to dk2), light title text (tx1 mapped to lt1), theme fonts.
        val master = """<p:sldMaster $a $p $r><p:cSld><p:bg><p:bgRef idx="1001"><a:schemeClr val="bg2"/></p:bgRef></p:bg><p:spTree/></p:cSld>
            <p:clrMap bg1="dk1" tx1="lt1" bg2="dk2" tx2="lt2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
            <p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4400" b="1"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mj-lt"/></a:defRPr></a:lvl1pPr></p:titleStyle>
            <p:bodyStyle><a:lvl1pPr><a:defRPr sz="2800"><a:solidFill><a:schemeClr val="tx1"><a:lumMod val="75000"/></a:schemeClr></a:solidFill><a:latin typeface="+mn-lt"/></a:defRPr></a:lvl1pPr></p:bodyStyle></p:txStyles></p:sldMaster>"""
        val layout1 = """<p:sldLayout $a $p $r><p:cSld><p:spTree/></p:cSld></p:sldLayout>"""
        val layout2 = """<p:sldLayout $a $p $r><p:cSld><p:bg><p:bgPr><a:solidFill><a:schemeClr val="accent1"/></a:solidFill></p:bgPr></p:bg><p:spTree/></p:cSld></p:sldLayout>"""
        // Slide 1: inherits everything; a run in accent1 shaded 50%, another tinted 40%; no sz on the title run.
        val slide1 = """<p:sld $a $p $r><p:cSld><p:spTree>
            <p:sp><p:nvSpPr><p:cNvPr id="2" name="Title"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:p><a:r><a:t>Hello</a:t></a:r></a:p></p:txBody></p:sp>
            <p:sp><p:nvSpPr><p:cNvPr id="3" name="Body"/><p:cNvSpPr/><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody>
              <a:p><a:r><a:rPr lang="en"><a:solidFill><a:schemeClr val="accent1"><a:shade val="50000"/></a:schemeClr></a:solidFill></a:rPr><a:t>Dark</a:t></a:r>
                   <a:r><a:rPr lang="en"><a:solidFill><a:schemeClr val="accent1"><a:tint val="40000"/></a:schemeClr></a:solidFill></a:rPr><a:t>Light</a:t></a:r></a:p></p:txBody></p:sp>
            </p:spTree></p:cSld></p:sld>"""
        // Slide 2: layout background (accent1); slide 3: its own background = the master's (inherits).
        val slide2 = """<p:sld $a $p $r><p:cSld><p:spTree/></p:cSld></p:sld>"""
        val slide3 = """<p:sld $a $p $r><p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="1F3864"/></a:solidFill></p:bgPr></p:bg><p:spTree/></p:cSld></p:sld>"""
        val bytes = zipOf(mapOf(
            "ppt/theme/theme1.xml" to theme,
            "ppt/slideMasters/slideMaster1.xml" to master,
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml") + rel("rId2", "theme", "../theme/theme1.xml") + "</Relationships>",
            "ppt/slideLayouts/slideLayout1.xml" to layout1,
            "ppt/slideLayouts/slideLayout2.xml" to layout2,
            "ppt/slides/slide1.xml" to slide1,
            "ppt/slides/_rels/slide1.xml.rels" to relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml") + "</Relationships>",
            "ppt/slides/slide2.xml" to slide2,
            "ppt/slides/_rels/slide2.xml.rels" to relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout2.xml") + "</Relationships>",
            "ppt/slides/slide3.xml" to slide3,
            "ppt/slides/_rels/slide3.xml.rels" to relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml") + "</Relationships>",
        ))
        System.getProperty("aero.sampleDir")?.let { java.io.File(it).apply { mkdirs() }.resolve("theme.pptx").writeBytes(bytes) }
        val pres = PptxReader.read(bytes)
        val m = pres.master
        assertEquals("bgRef bg2 → dk2", 0xFF1F3864, m.background)
        assertEquals("tx1 → lt1", 0xFFFFFFFF, m.titleColor)
        assertEquals(44f, m.titleSizePt); assertEquals(true, m.titleBold); assertEquals("Georgia", m.titleFont)
        assertEquals(28f, m.bodySizePt); assertEquals("Verdana", m.bodyFont)
        assertEquals("75% luminance of white", 0xFFBFBFBF, m.bodyColor)
        assertEquals(3, pres.slides.size)
        val s1 = pres.slides[0]
        assertNull("inherits the master background", s1.background)
        assertEquals(0xFF1F3864, pres.backgroundOf(s1))
        assertEquals("Hello", s1.title.text)
        assertEquals("title size from the master text style", 44f, s1.title.paragraph.runs[0].format.fontSizePt)
        assertNull("no explicit colour: rendered with the master's", s1.title.paragraph.runs[0].format.color)
        val dark = s1.body.paragraph.runs.first { it.text == "Dark" }.format.color!!
        val light = s1.body.paragraph.runs.first { it.text == "Light" }.format.color!!
        assertEquals("accent1 shaded 50%", 0xFF223962, dark)
        assertEquals("accent1 tinted 40%", 0xFFB4C7E7, light)
        assertEquals("layout background applies", 0xFF4472C4, pres.slides[1].background)
        assertNull("same as the master → inherits", pres.slides[2].background)
    }

    @Test
    fun paletteMath() {
        assertEquals(0xFF808080, PptxReader.Palette.lum(0xFF000000) { it + 0.5f })
        assertEquals(0xFF808080, PptxReader.Palette.lum(0xFFFFFFFF) { it * 0.5f })
        val halfRed = PptxReader.Palette.lum(0xFFFF8080) { it * 0.5f }
        assertEquals("hue kept at full saturation: no green / blue", 0L, halfRed and 0xFFFF)
        assertTrue("red channel about 0.75", ((halfRed shr 16) and 0xFF) in 0xBE..0xC2)
        assertEquals(0xFF808080, PptxReader.Palette.mix(0xFF000000, 0xFFFFFFFF, 0.5f))
        assertEquals(0xFFFFFFFF, PptxReader.Palette.mix(0xFF123456, 0xFFFFFFFF, 1f))
    }

    @Test
    fun odpKeepsTheMasterBackground() {
        val pres = deck()
        pres.master.background = 0xFF1F1F1F
        pres.master.titleColor = 0xFFFFFFFF
        pres.slides[1].background = 0xFFFFF2CC
        val bos = ByteArrayOutputStream()
        OdpWriter.write(pres, bos)
        val parts = zipEntries(bos.toByteArray())
        assertTrue(parts["styles.xml"]!!.contains("draw:fill-color=\"#1f1f1f\"") || parts["styles.xml"]!!.contains("draw:fill-color=\"#1F1F1F\""))
        // The master's text is Impress's: its `Default-title` style, which the pages' titles take theirs from.
        assertTrue(Regex("""<style:style style:name="Default-title"[^>]*>.*?fo:color="#(?i:ffffff)"""").containsMatchIn(parts["styles.xml"]!!))
        assertTrue(parts["content.xml"]!!.contains("style:parent-style-name=\"Default-title\""))
        val back = OdpReader.read(bos.toByteArray())
        assertEquals(0xFF1F1F1F, back.master.background)
        assertEquals("the master's colour comes back as the master's", 0xFFFFFFFF, back.master.titleColor)
        assertNull("its titles inherit it", back.slides[0].title.paragraph.runs[0].format.color)
        assertNull("on the pale page too, where it shows dark", back.slides[1].title.paragraph.runs[0].format.color)
        assertEquals(com.therealityteam.aeropages.model.TextColors.DARK, back.titleTextOn(back.slides[1]).color)
        assertNull("page without a fill inherits", back.slides[0].background)
        assertEquals(0xFF1F1F1F, back.backgroundOf(back.slides[0]))
        assertEquals(0xFFFFF2CC, back.slides[1].background)
    }

    @Test
    fun copyOfClonesTheMaster() {
        val pres = deck()
        pres.master.titleColor = 0xFF112233
        val copy = pres.copyOf()
        copy.master.titleColor = 0xFF000000
        assertEquals(0xFF112233, pres.master.titleColor)
        assertFalse(pres.master.sameAs(copy.master))
    }
}
