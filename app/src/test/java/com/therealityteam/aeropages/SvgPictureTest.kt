package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.ImageSize
import com.therealityteam.aeropages.format.PictureType
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.svg.SvgColor
import com.therealityteam.aeropages.format.svg.SvgGradient
import com.therealityteam.aeropages.format.svg.SvgGroup
import com.therealityteam.aeropages.format.svg.SvgImage
import com.therealityteam.aeropages.format.svg.SvgItem
import com.therealityteam.aeropages.format.svg.SvgReader
import com.therealityteam.aeropages.format.svg.SvgShape
import com.therealityteam.aeropages.format.svg.SvgText
import com.therealityteam.aeropages.model.Freeform
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** SVG pictures read into what they draw: shapes, styles, gradients, clips, text — and SVG in Office packages. */
class SvgPictureTest {

    /** A 1×1 PNG. */
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119,
        0, 0, 0, 13, 73, 68, 65, 84, 120, -38, 99, -8, -1, -1, 63, 0, 5, -2, 2, -2, -89, 53, -127, -124, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    private fun read(svg: String) = SvgReader.read(svg.toByteArray())!!

    /** Every shape of [items], groups opened, in paint order. */
    private fun shapes(items: List<SvgItem>): List<SvgShape> = items.flatMap { if (it is SvgGroup) shapes(it.items) else listOfNotNull(it as? SvgShape) }

    @Test
    fun sizesAndSniffing() {
        val svg = """<?xml version="1.0"?><!-- a comment --><svg xmlns="http://www.w3.org/2000/svg" width="10cm" height="5cm"><rect width="1" height="1"/></svg>""".toByteArray()
        assertTrue(SvgReader.isSvg(svg))
        val (w, h) = ImageSize.of(svg)!!
        assertEquals(10f / 2.54f * 96f, w, 0.01f); assertEquals(5f / 2.54f * 96f, h, 0.01f)
        // A view box alone gives the size; a percentage size takes the view box's.
        assertEquals(24f to 12f, SvgReader.size("""<svg viewBox="0 0 24 12"/>""".toByteArray()))
        assertEquals(24f to 12f, SvgReader.size("""<svg width="100%" height="100%" viewBox="0 0 24 12"/>""".toByteArray()))
        // Gzipped (svgz).
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(svg) } }.toByteArray()
        assertTrue(SvgReader.isSvg(gz)); assertNotNull(SvgReader.read(gz))
        // Types by the bytes, not the name.
        assertEquals("image/svg+xml", PictureType.mimeOf(svg, "image3.png"))
        assertEquals("image/png", PictureType.mimeOf(png, "picture.svg"))
        assertEquals("image/jpeg", PictureType.mimeOf(byteArrayOf(-1, -40, -1, -32, 0, 16), null))
        assertEquals("image/gif", PictureType.mimeOf("GIF89a....".toByteArray(), null))
        assertFalse(SvgReader.isSvg(png))
        assertEquals("svg", PictureType.extOf("image/svg+xml")); assertEquals("jpeg", PictureType.extOf("image/jpeg"))
    }

    @Test
    fun stylesCascadeAsCssDoes() {
        val pic = read("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" style="color:#00ff00">
            <style>.a { fill: #123456; stroke: red } rect.b { stroke-width: 4 } #c { fill: hsl(120, 100%, 25%) } .x .y { fill: blue }</style>
            <g fill="orange" stroke-width="2">
              <rect class="a" x="1" y="1" width="10" height="10"/>
              <rect class="a b" x="1" y="1" width="10" height="10" style="fill: rgb(10%, 20%, 30%)"/>
              <circle id="c" cx="50" cy="50" r="10" fill="red"/>
              <ellipse cx="50" cy="50" rx="10" ry="5" fill="currentColor" stroke-dasharray="3 1 2"/>
              <rect x="0" y="0" width="5" height="5" fill="none" stroke="#abc" opacity="0.5" stroke-opacity=".5"/>
              <rect x="0" y="0" width="5" height="5" display="none"/>
              <rect x="0" y="0" width="5" height="5" visibility="hidden"/>
              <line x1="0" y1="0" x2="10" y2="10" stroke="navy"/>
              <rect class="y" width="3" height="3"/>
            </g>
          </svg>""")
        val s = shapes(pic.items)
        assertEquals(7, s.size)
        assertEquals("a class over the group's fill", SvgColor(0xFF123456.toInt()), s[0].fill)
        assertEquals(SvgColor(0xFFFF0000.toInt()), s[0].stroke); assertEquals("inherited", 2f, s[0].strokeWidth)
        assertEquals("the style attribute wins", SvgColor(0xFF1A334D.toInt()), s[1].fill); assertEquals(4f, s[1].strokeWidth)
        assertEquals("an id selector beats the attribute", SvgColor(0xFF008000.toInt()), s[2].fill)
        assertEquals("currentColor", SvgColor(0xFF00FF00.toInt()), s[3].fill)
        assertArrayEquals("an odd dash list is doubled", floatArrayOf(3f, 1f, 2f, 3f, 1f, 2f), s[3].dashes!!, 0f)
        assertNull(s[4].fill); assertEquals(SvgColor(0xFFAABBCC.toInt()), s[4].stroke); assertEquals(0.5f, s[4].opacity); assertEquals(0.5f, s[4].strokeOpacity)
        assertNull("a line is never filled", s[5].fill); assertEquals(SvgColor(0xFF000080.toInt()), s[5].stroke)
        assertEquals("a descendant selector is not applied", SvgColor(0xFFFFA500.toInt()), s[6].fill)
        // A rect's outline: four sides, closed.
        assertEquals(listOf(Freeform.Move(1f, 1f), Freeform.Line(11f, 1f), Freeform.Line(11f, 11f), Freeform.Line(1f, 11f), Freeform.Close), s[0].ops)
    }

    @Test
    fun gradientsUseAndText() {
        val pic = read("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="200" height="100">
            <defs>
              <linearGradient id="base"><stop offset="0" stop-color="#ff0000"/><stop offset="100%" style="stop-color:blue;stop-opacity:0.5"/></linearGradient>
              <linearGradient id="user" xlink:href="#base" x1="10" y1="0" x2="190" y2="0" gradientUnits="userSpaceOnUse" gradientTransform="translate(5,0)"/>
              <radialGradient id="glow" fx="0.25"><stop offset=".2" stop-color="white"/><stop offset=".1" stop-color="black"/></radialGradient>
              <linearGradient id="one"><stop stop-color="gold"/></linearGradient>
              <symbol id="dot" viewBox="0 0 10 10"><circle cx="5" cy="5" r="5"/></symbol>
              <clipPath id="clip" clipPathUnits="objectBoundingBox"><rect width=".5" height="1"/></clipPath>
            </defs>
            <rect width="200" height="20" fill="url(#user)"/>
            <rect y="20" width="200" height="20" fill="url(#glow)" clip-path="url(#clip)"/>
            <rect y="40" width="200" height="20" fill="url(#one)"/>
            <rect y="60" width="200" height="20" fill="url(#missing) #0f0"/>
            <use xlink:href="#dot" x="10" y="10" width="40" height="40" fill="purple"/>
            <text x="100" y="95" text-anchor="middle" font-family="'Open Sans', sans-serif" font-weight="bold" font-size="12pt">  Total:
              <tspan fill="red" dx="4">42</tspan>  </text>
            <image x="0" y="0" width="16" height="16" xlink:href="data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(png)}"/>
          </svg>""")
        val s = shapes(pic.items)
        val user = s[0].fill as SvgGradient
        assertFalse(user.radial); assertFalse(user.bboxUnits)
        assertEquals(listOf(10f, 0f, 190f, 0f), listOf(user.x1, user.y1, user.x2, user.y2))
        assertEquals("stops through the href", 2, user.stops.size)
        assertEquals(0xFFFF0000.toInt(), user.stops[0].second); assertEquals(0x7F0000FF, user.stops[1].second)
        assertEquals(5f, user.matrix!![4])
        val glow = s[1].fill as SvgGradient
        assertTrue(glow.radial); assertTrue(glow.bboxUnits)
        assertEquals(0.5f, glow.x1); assertEquals("the focus", 0.25f, glow.x2); assertEquals(0.5f, glow.r)
        assertEquals("offsets never go back", listOf(0.2f, 0.2f), glow.stops.map { it.first })
        assertTrue(s[1].clip!!.bboxUnits)
        assertEquals("a gradient of one stop is its colour", SvgColor(0xFFFFD700.toInt()), s[2].fill)
        assertEquals("a missing gradient's fallback", SvgColor(0xFF00FF00.toInt()), s[3].fill)
        // The symbol through use: placed at its x, y and fitted into its size; the use's fill passed on.
        val use = pic.items[4] as SvgGroup
        assertEquals(listOf(4f, 0f, 0f, 4f, 10f, 10f), use.matrix!!.toList())
        assertEquals(SvgColor(0xFF800080.toInt()), shapes(use.items).single().fill)
        // Text: white space collapsed, the tspan a run of its own after the first.
        val text = pic.items[5] as SvgText
        assertEquals(listOf("Total: ", "42"), text.spans.map { it.text })
        assertEquals(100f, text.spans[0].x); assertEquals(1, text.spans[0].anchor); assertEquals(16f, text.spans[0].size, 0.01f)
        assertEquals("Open Sans", text.spans[0].family); assertTrue(text.spans[0].bold)
        assertNull(text.spans[1].x); assertEquals(4f, text.spans[1].dx); assertEquals(SvgColor(0xFFFF0000.toInt()), text.spans[1].fill)
        val image = pic.items[6] as SvgImage
        assertArrayEquals(png, image.data); assertEquals(16f, image.w)
    }

    @Test
    fun libreOfficesMetropolisBackground() {
        val file = File("/Applications/LibreOffice.app/Contents/Resources/template/common/presnt/Metropolis.otp")
        assumeTrue("LibreOffice's templates are on this machine", file.exists())
        val files = com.therealityteam.aeropages.format.OfficeXml.unzip(file.readBytes())
        val svg = files.entries.first { it.key.endsWith(".svg") }.value
        val pic = SvgReader.read(svg)!!
        assertEquals(1366f, pic.width); assertEquals(768f, pic.height)
        val layer = pic.items.single() as SvgGroup
        assertEquals(-284.36218f, layer.matrix!![5], 0.001f)
        val bg = layer.items[0] as SvgShape
        assertEquals(0.95f, bg.opacity, 0.001f)
        val g = bg.fill as SvgGradient
        assertFalse(g.bboxUnits)
        assertEquals(listOf(0xFF366BB3.toInt(), 0xFF093A84.toInt()), g.stops.map { it.second })
        assertEquals(1.1205906f, g.matrix!![0], 1e-5f)
        // The flipped frames, the band, and the clipped rays.
        assertEquals(-1f, (layer.items[1] as SvgShape).matrix!![3])
        assertNull((layer.items[1] as SvgShape).fill)
        val clipped = layer.items.last() as SvgGroup
        assertNotNull(clipped.clip); assertEquals(1, clipped.clip!!.parts.size)
        // And the deck: its design's picture (a frame on its master page) is the SVG, sized as it declares.
        val deck = com.therealityteam.aeropages.format.odf.OdpReader.read(file.readBytes())
        val picture = deck.designs.flatMap { d -> d.items.filterIsInstance<com.therealityteam.aeropages.model.DesignPicture>().map { it.image } }.first()
        assertEquals("image/svg+xml", picture.mime)
        assertEquals("its own size (pixels, its aspect)", 1366f, picture.widthPt, 0.01f)
    }

    @Test
    fun officesSvgPicturesAreReadAsTheirSvg() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><circle cx="5" cy="5" r="5" fill="#4472c4"/></svg>""".toByteArray()
        // Written as PowerPoint writes one: the PNG stand-in on the blip, the SVG in its extension.
        val deck = Presentation(mutableListOf(Slide(title = SlideBox("Icon")).also { it.images.add(SlideImage(png, "image/png", 1f, 1f, 0.1f, 0.1f, 0.2f, 0.2f)) }))
        val written = ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray()
        val patched = ByteArrayOutputStream()
        ZipOutputStream(patched).use { out ->
            ZipInputStream(written.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    var bytes = zin.readBytes()
                    if (e.name == "ppt/slides/slide1.xml") bytes = String(bytes).replace(Regex("""<a:blip r:embed="(rId\d+)"/>"""),
                        """<a:blip r:embed="$1"><a:extLst><a:ext uri="{96DAC541-7B7A-43D3-8B79-37D633B846F1}"><asvg:svgBlip xmlns:asvg="http://schemas.microsoft.com/office/drawing/2016/SVG/main" r:embed="rIdSvg"/></a:ext></a:extLst></a:blip>""").toByteArray()
                    if (e.name == "ppt/slides/_rels/slide1.xml.rels") bytes = String(bytes).replace("</Relationships>",
                        """<Relationship Id="rIdSvg" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/icon.svg"/></Relationships>""").toByteArray()
                    out.putNextEntry(ZipEntry(e.name)); out.write(bytes); out.closeEntry()
                }
            }
            out.putNextEntry(ZipEntry("ppt/media/icon.svg")); out.write(svg); out.closeEntry()
        }
        val img = PptxReader.read(patched.toByteArray()).slides[0].images.single()
        assertArrayEquals("the SVG, not its stand-in", svg, img.data)
        assertEquals("image/svg+xml", img.mime)
        // Saved again (here, where no PNG can be drawn): the SVG goes as the picture itself, typed as one.
        val again = ByteArrayOutputStream().also { PptxWriter.write(PptxReader.read(patched.toByteArray()), it) }.toByteArray()
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(again.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        assertTrue(entries.keys.any { it.startsWith("ppt/media/") && it.endsWith(".svg") })
        assertTrue(String(entries["[Content_Types].xml"]!!).contains("""Extension="svg" ContentType="image/svg+xml""""))
        assertArrayEquals(svg, PptxReader.read(again).slides[0].images.single().data)
    }
}
