package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.DesignPicture
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeShadow
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideShape
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A PowerPoint deck's master and layout drawings — logos, bands, picture backgrounds — as the slides' designs. */
class PptxDesignsTest {

    /** A 1×1 PNG. */
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119,
        0, 0, 0, 13, 73, 68, 65, 84, 120, -38, 99, -8, -1, -1, 63, 0, 5, -2, 2, -2, -89, 53, -127, -124, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    /** Our own deck, its parts patched as a template's: [patch] gets each part's text (by name) and may return it changed. */
    private fun patched(deck: Presentation, extra: Map<String, ByteArray>, patch: (String, String) -> String): ByteArray {
        val written = ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(written.inputStream()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    val bytes = zin.readBytes()
                    val changed = if (e.name.endsWith(".xml") || e.name.endsWith(".rels")) patch(e.name, String(bytes)).toByteArray() else bytes
                    z.putNextEntry(ZipEntry(e.name)); z.write(changed); z.closeEntry()
                }
            }
            extra.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
        }
        return out.toByteArray()
    }

    private val img = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"

    @Test
    fun mastersAndLayoutsDrawUnderTheirSlides() {
        val deck = Presentation(mutableListOf(
            Slide(title = SlideBox("Title"), layout = SlideLayout.TITLE),
            Slide(title = SlideBox("Content"), body = SlideBox("Point")),
            Slide(title = SlideBox("Hidden graphics"), body = SlideBox("Point")),
        ))
        // The master: a logo picture and a band (a placeholder is not its drawing); the content layout: a picture background and a line.
        val logo = """<p:pic><p:nvPicPr><p:cNvPr id="10" name="Logo"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed="rIdLogo"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>""" +
            """<p:spPr><a:xfrm><a:off x="10668000" y="152400"/><a:ext cx="1219200" cy="609600"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""
        val band = """<p:sp><p:nvSpPr><p:cNvPr id="11" name="Band"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="6400800"/><a:ext cx="12192000" cy="457200"/></a:xfrm>""" +
            """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="C00000"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr></p:sp>"""
        val rule = """<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="12" name="Rule"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr><a:xfrm><a:off x="685800" y="1600200"/><a:ext cx="10820400" cy="0"/></a:xfrm>""" +
            """<a:prstGeom prst="line"><a:avLst/></a:prstGeom><a:ln w="19050"><a:solidFill><a:srgbClr val="404040"/></a:solidFill></a:ln></p:spPr></p:cxnSp>"""
        val bytes = patched(deck, mapOf("ppt/media/logo.png" to png, "ppt/media/paper.png" to png)) { name, text ->
            when (name) {
                "ppt/slideMasters/slideMaster1.xml" -> text.replace("</p:spTree>", "$logo$band</p:spTree>")
                "ppt/slideMasters/_rels/slideMaster1.xml.rels" -> text.replace("</Relationships>", """<Relationship Id="rIdLogo" Type="$img" Target="../media/logo.png"/></Relationships>""")
                "ppt/slideLayouts/slideLayout2.xml" -> text.replace("<p:cSld", """<p:cSld""").replaceFirst(Regex("<p:cSld([^>]*)>"),
                    """<p:cSld$1><p:bg><p:bgPr><a:blipFill><a:blip r:embed="rIdPaper"/><a:stretch><a:fillRect/></a:stretch></a:blipFill><a:effectLst/></p:bgPr></p:bg>""")
                    .replace("</p:spTree>", "$rule</p:spTree>")
                "ppt/slideLayouts/_rels/slideLayout2.xml.rels" -> text.replace("</Relationships>", """<Relationship Id="rIdPaper" Type="$img" Target="../media/paper.png"/></Relationships>""")
                "ppt/slides/slide3.xml" -> text.replaceFirst("<p:sld ", """<p:sld showMasterSp="0" """)
                else -> text
            }
        }
        val p = PptxReader.read(bytes)
        val title = p.designOf(p.slides[0])!!
        val content = p.designOf(p.slides[1])!!
        assertEquals("the master's logo and band under the title slide", listOf("pic", "shape"), title.items.map { if (it is DesignPicture) "pic" else "shape" })
        assertNull(title.picture)
        val logoItem = (title.items[0] as DesignPicture).image
        assertArrayEquals(png, logoItem.data); assertEquals(10668000f / 12192000f, logoItem.xPct, 0.001f)
        val bandItem = (title.items[1] as DesignShape).shape
        assertEquals(ShapeType.RECT, bandItem.type); assertEquals(0xFFC00000, bandItem.fill)
        assertEquals("the content layout: the master's drawing, then its own line", 3, content.items.size)
        assertEquals(ShapeType.LINE, (content.items[2] as DesignShape).shape.type)
        assertNotNull("and its picture background", content.picture); assertEquals(1f, content.picture!!.wPct)
        assertTrue(p.slides[0].designObjects)
        assertEquals("the same layout, the same design", p.slides[1].design, p.slides[2].design)
        assertFalse("hide background graphics", p.slides[2].designObjects)
        assertEquals(2, p.designs.size)
    }

    /**
     * (Opt-in, `-Daero.pptxDir=…`: LibreOffice's templates saved as pptx by LibreOffice.) Their master
     * pages' drawings come back from the pptx masters and layouts LibreOffice wrote them into.
     */
    @Test
    fun libreOfficesTemplatesSavedAsPptx() {
        val dir = System.getProperty("aero.pptxDir")?.let { java.io.File(it) }
        org.junit.Assume.assumeTrue(dir != null && dir.isDirectory)
        val report = StringBuilder()
        dir!!.listFiles { f -> f.name.endsWith(".pptx") }!!.sortedBy { it.name }.forEach { f ->
            val p = PptxReader.read(f.readBytes())
            val on = p.slides.count { p.designOf(it) != null }
            report.append("${f.name}: ${p.designs.size} designs, $on/${p.slides.size} slides on one; ")
            report.append(p.designs.joinToString { d -> "${d.name}[${d.items.size}${if (d.picture != null) "+pic" else ""}]" }).append('\n')
            assertTrue("${f.name}: its master pages draw", p.designs.isNotEmpty())
        }
        java.io.File(System.getProperty("java.io.tmpdir"), "aero-pptx-designs.txt").writeText(report.toString())
    }

    /** Every part of [bytes], by name. */
    private fun parts(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() } }
        return out
    }

    /** A deck on three designs: two drawing (one over a picture), one only a colour, and a plain slide. */
    private fun designedDeck(): Presentation {
        val paper = png.copyOf()
        val focus = SlideDesign("Focus", picture = SlideImage(paper, "image/png", 1f, 1f, 0f, 0f, 1f, 1f))
        focus.items.add(DesignShape(SlideShape(ShapeType.RECT, xPct = 0f, yPct = 0.9f, wPct = 1f, hPct = 0.1f, fill = 0xFFC00000, border = null)))
        focus.items.add(DesignPicture(SlideImage(png, "image/png", 1f, 1f, 0.85f, 0.03f, 0.1f, 0.1f, shadow = ShapeShadow())))
        val candy = SlideDesign("Candy", background = 0xFF203040)
        candy.items.add(DesignShape(SlideShape(ShapeType.ELLIPSE, xPct = 0.8f, yPct = 0.7f, wPct = 0.15f, hPct = 0.25f, fill = 0xFFFFC000)))
        candy.items.add(DesignPicture(SlideImage(png, "image/png", 1f, 1f, 0.02f, 0.02f, 0.08f, 0.08f)))
        val tint = SlideDesign("Tint", background = 0xFF336699)
        val slides = mutableListOf(
            Slide(title = SlideBox("Cover"), layout = SlideLayout.TITLE).apply { design = "Focus" },
            Slide(title = SlideBox("Agenda"), body = SlideBox("Point")).apply { design = "Focus" },
            Slide(title = SlideBox("Quiet"), body = SlideBox("Point")).apply { design = "Candy"; designObjects = false },
            Slide(title = SlideBox("Plain")),
            Slide(title = SlideBox("Tinted")).apply { design = "Tint" },
        )
        return Presentation(slides, designs = mutableListOf(focus, candy, tint))
    }

    @Test
    fun designsAreWrittenAsMasters() {
        val bytes = ByteArrayOutputStream().also { PptxWriter.write(designedDeck(), it) }.toByteArray()
        val p = PptxReader.read(bytes)
        assertEquals("a design drawing something comes back by its name — one for all its layouts", listOf("Focus", "Candy"), p.designs.map { it.name })
        assertEquals("Focus", p.slides[0].design); assertEquals("Focus", p.slides[1].design)
        val focus = p.designOf(p.slides[0])!!
        assertNotNull("its picture is the background", focus.picture); assertEquals(1f, focus.picture!!.wPct, 0.001f)
        assertEquals(2, focus.items.size)
        assertEquals(0xFFC00000, (focus.items[0] as DesignShape).shape.fill)
        val logo = (focus.items[1] as DesignPicture).image
        assertEquals(0.85f, logo.xPct, 0.001f); assertTrue("the picture keeps its shadow", ShapeShadow().sameAs(logo.shadow))
        val candy = p.designOf(p.slides[2])!!
        assertEquals("Candy", candy.name); assertEquals(0xFF203040, candy.background); assertEquals(2, candy.items.size)
        assertFalse("its graphics hidden on that slide", p.slides[2].designObjects)
        assertTrue(p.slides[0].designObjects)
        assertNull(p.slides[2].background)
        assertNull("the plain slide on the plain master", p.slides[3].design); assertNull(p.slides[3].background)
        assertNull("a colour alone is the slide's", p.slides[4].design); assertEquals(0xFF336699, p.slides[4].background)
        assertEquals(0xFF203040, p.backgroundOf(p.slides[2]))

        val files = parts(bytes)
        assertEquals("the plain master and one a drawing design", 3, files.keys.count { Regex("ppt/slideMasters/slideMaster\\d+\\.xml").matches(it) })
        assertEquals(15, files.keys.count { Regex("ppt/slideLayouts/slideLayout\\d+\\.xml").matches(it) })
        assertEquals("a picture saved once however many designs draw it", 2, files.keys.count { it.startsWith("ppt/media/design") })
        // Master and layout ids: one id space, no repeats.
        val ids = Regex("""<p:sld(?:Master|Layout)Id id="(\d+)"""").findAll(files.filterKeys { it == "ppt/presentation.xml" || it.startsWith("ppt/slideMasters/slideMaster") }
            .values.joinToString("") { String(it) }).map { it.groupValues[1] }.toList()
        assertEquals(3 + 15, ids.size); assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.toLong() >= 2147483648L })
        // Every part typed and every relationship's target there.
        val types = String(files["[Content_Types].xml"]!!)
        files.keys.filter { Regex("ppt/(slideMasters|slideLayouts|theme|slides)/[^/]+\\.xml").matches(it) }.forEach { assertTrue("$it typed", types.contains("PartName=\"/$it\"")) }
        files.keys.filter { it.endsWith(".rels") }.forEach { relsName ->
            val dir = relsName.substringBefore("_rels/")
            Regex("""Target="([^"]+)"(?! TargetMode="External")""").findAll(String(files[relsName]!!)).forEach { m ->
                val target = m.groupValues[1]
                if (target.startsWith("http")) return@forEach
                val resolved = java.net.URI("/$dir").resolve(target).path.trimStart('/')
                assertTrue("$relsName → $target", files.containsKey(resolved))
            }
        }
        assertTrue("PowerPoint names the master by its theme", String(files["ppt/theme/theme2.xml"]!!).contains("""name="Focus""""))
    }

    @Test
    fun designsSurviveAnotherRoundTrip() {
        val once = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(designedDeck(), it) }.toByteArray())
        val twice = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(once, it) }.toByteArray())
        assertEquals(once.designs.map { it.name }, twice.designs.map { it.name })
        assertEquals(once.designs.map { it.items.size }, twice.designs.map { it.items.size })
        assertEquals(once.slides.map { it.design }, twice.slides.map { it.design })
        assertEquals(once.slides.map { it.designObjects }, twice.slides.map { it.designObjects })
        assertEquals(once.slides.map { it.background }, twice.slides.map { it.background })
    }

    /**
     * (Opt-in, `-Daero.odpDir=…`: LibreOffice's templates as ODP.) Saved as pptx, their master
     * pages' drawings come back as the same designs; the decks go to `-Daero.outDir` for rendering.
     */
    @Test
    fun libreOfficesTemplatesToPptx() {
        val dir = System.getProperty("aero.odpDir")?.let { java.io.File(it) }
        org.junit.Assume.assumeTrue(dir != null && dir.isDirectory)
        val out = System.getProperty("aero.outDir")?.let { java.io.File(it).apply { mkdirs() } }
        dir!!.listFiles { f -> f.name.endsWith(".odp") }!!.sortedBy { it.name }.forEach { f ->
            val odp = com.therealityteam.aeropages.format.odf.OdpReader.read(f.readBytes())
            val bytes = ByteArrayOutputStream().also { PptxWriter.write(odp, it) }.toByteArray()
            out?.resolve(f.nameWithoutExtension + ".pptx")?.writeBytes(bytes)
            val p = PptxReader.read(bytes)
            odp.slides.forEachIndexed { i, s ->
                val want = odp.designOf(s)?.takeIf { it.drawsSomething }
                val got = p.designOf(p.slides[i])
                assertEquals("${f.name} slide ${i + 1}", want?.name, got?.name)
                assertEquals("${f.name} slide ${i + 1}", want?.items?.size, got?.items?.size)
                assertEquals("${f.name} slide ${i + 1}", want?.picture != null, got?.picture != null)
            }
        }
    }

    @Test
    fun ourOwnDecksHaveNone() {
        val deck = Presentation(mutableListOf(Slide(title = SlideBox("Plain"))))
        val p = PptxReader.read(ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray())
        assertTrue(p.designs.isEmpty()); assertNull(p.slides[0].design)
    }
}
