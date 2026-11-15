package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.BackgroundShade
import com.therealityteam.aeropages.model.GradientKind
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A background that shades from its colour (Format Background → Gradient fill): on a slide, a design
 * and the master; through pptx and odp and back, from a theme's background style, and in the editor.
 */
class BackgroundShadeTest {

    private val radial = BackgroundShade(0xFF1F3864, 0f, GradientKind.RADIAL, 0.5f, 0.5f)
    private val linear = BackgroundShade(0xFFFFFFFF, 45f)

    /** A master shading navy out from a light centre, a slide of its own shading orange to white, one inheriting, one on a design shading axially. */
    private fun deck(): Presentation {
        val p = Presentation(mutableListOf(
            Slide(title = SlideBox("Inherits")),
            Slide(title = SlideBox("Its own"), background = 0xFFED7D31).also { it.backgroundShade = linear },
            Slide(title = SlideBox("On a design")).also { it.design = "Banded" },
        ))
        p.master.background = 0xFFDEEBF7; p.master.backgroundShade = radial
        // A design that draws (a picture) and shades axially from green.
        val pic = SlideImage(png, "image/png", 1f, 1f, 0.8f, 0.8f, 0.1f, 0.1f)
        p.designs += SlideDesign("Banded", 0xFF70AD47, null, mutableListOf(com.therealityteam.aeropages.model.DesignPicture(pic)),
            backgroundShade = BackgroundShade(0xFFE2F0D9, 90f, GradientKind.AXIAL))
        return p
    }

    /** A one-pixel PNG. */
    private val png: ByteArray = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==")

    private fun check(back: Presentation) {
        assertEquals(0xFFDEEBF7, back.master.background); assertEquals(radial, back.master.backgroundShade)
        val (inherits, own, onDesign) = back.slides
        assertNull(inherits.background); assertNull(inherits.backgroundShade)
        assertEquals(radial, back.backgroundShadeOf(inherits))
        assertEquals(0xFFED7D31, own.background); assertEquals(linear.second, own.backgroundShade!!.second)
        assertEquals(45f, own.backgroundShade!!.angleDeg, 0.5f)
        val d = back.designOf(onDesign)!!
        assertEquals(0xFF70AD47, d.background); assertEquals(GradientKind.AXIAL, d.backgroundShade!!.kind); assertEquals(0xFFE2F0D9, d.backgroundShade!!.second)
        assertEquals(d.backgroundShade, back.backgroundShadeOf(onDesign))
    }

    @Test fun pptxKeepsBackgroundGradients() {
        val bos = ByteArrayOutputStream().also { PptxWriter.write(deck(), it) }
        val parts = OfficeXml.unzip(bos.toByteArray()).mapValues { String(it.value) }
        assertTrue(parts["ppt/slides/slide2.xml"]!!.contains("<p:bg><p:bgPr><a:gradFill"))
        check(PptxReader.read(bos.toByteArray()))
    }

    @Test fun odpKeepsBackgroundGradients() {
        val bos = ByteArrayOutputStream().also { OdpWriter.write(deck(), it) }
        val styles = String(OfficeXml.unzip(bos.toByteArray())["styles.xml"]!!)
        assertTrue(styles.contains("""draw:fill="gradient""""))
        check(OdpReader.read(bos.toByteArray()))
    }

    @Test fun aThemesBackgroundStyleShadesInTheReferencesColour() {
        // bgRef idx 1003: the theme's third background fill, a gradient from a 40% tint of the colour to a 50% shade of it.
        val a = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
        val p = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""
        val r = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
        val rel = { id: String, type: String, target: String -> "<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/$type\" Target=\"$target\"/>" }
        val relsHead = "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        val theme = """<a:theme $a name="T"><a:themeElements><a:clrScheme name="S">
            <a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F3864"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
            <a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>
            <a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6>
            <a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>
            <a:fontScheme name="F"><a:majorFont><a:latin typeface="Georgia"/></a:majorFont><a:minorFont><a:latin typeface="Verdana"/></a:minorFont></a:fontScheme>
            <a:fmtScheme name="M"><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
            <a:gradFill><a:gsLst><a:gs pos="0"><a:schemeClr val="phClr"><a:tint val="40000"/></a:schemeClr></a:gs><a:gs pos="100000"><a:schemeClr val="phClr"><a:shade val="50000"/></a:schemeClr></a:gs></a:gsLst>
            <a:lin ang="5400000" scaled="0"/></a:gradFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"""
        val master = """<p:sldMaster $a $p $r><p:cSld><p:bg><p:bgRef idx="1003"><a:schemeClr val="accent1"/></p:bgRef></p:bg><p:spTree/></p:cSld>
            <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/></p:sldMaster>"""
        val layout = """<p:sldLayout $a $p $r><p:cSld><p:spTree/></p:cSld></p:sldLayout>"""
        val slide = """<p:sld $a $p $r><p:cSld><p:spTree/></p:cSld></p:sld>"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            mapOf(
                "ppt/theme/theme1.xml" to theme,
                "ppt/slideMasters/slideMaster1.xml" to master,
                "ppt/slideMasters/_rels/slideMaster1.xml.rels" to relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml") + rel("rId2", "theme", "../theme/theme1.xml") + "</Relationships>",
                "ppt/slideLayouts/slideLayout1.xml" to layout,
                "ppt/slides/slide1.xml" to slide,
                "ppt/slides/_rels/slide1.xml.rels" to relsHead + rel("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml") + "</Relationships>",
            ).forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
        }
        val pres = PptxReader.read(bos.toByteArray())
        // 4472C4 tinted 40% (40% colour, 60% white) and shaded 50% (half way to black).
        assertEquals(0xFFB4C7E7, pres.master.background)
        assertEquals(BackgroundShade(0xFF223962, 90f), pres.master.backgroundShade)
        assertEquals(pres.master.backgroundShade, pres.backgroundShadeOf(pres.slides.single()))
    }

    @Test fun theEditorSetsAShadeAndUndoesIt() {
        val e = SlideEditor().also { it.load(deck()) }
        e.select(0)
        e.setBackgroundShade(0xFF000000, BackgroundShade(0xFF7F7F7F, 0f))
        assertEquals(0xFF7F7F7F, e.presentation.backgroundShadeOf(e.slide)!!.second)
        e.undo()
        assertNull(e.slide.background); assertNull(e.slide.backgroundShade)
        // A plain colour leaves no gradient behind.
        e.select(1)
        e.setBackground(0xFF00B050)
        assertNull(e.slide.backgroundShade)
        // Applying a slide's gradient to all: the master takes colour and gradient alike.
        e.select(1)
        e.setBackgroundShade(0xFF002060, linear)
        e.setBackgroundAll(e.slide.background)
        assertEquals(0xFF002060, e.presentation.master.background); assertEquals(linear, e.presentation.master.backgroundShade)
        assertTrue(e.presentation.slides.all { it.background == null && it.backgroundShade == null })
    }
}
