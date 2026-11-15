package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.XmlPull
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.DrawingShape
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.pptx.PresetGeometries
import com.therealityteam.aeropages.model.Freeform
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** DrawingML's preset geometries the app draws from their definitions: hexagons, chevrons, flowchart shapes, callouts … */
class PresetGeometriesTest {

    private fun sp(prst: String, av: String = "", cx: Long = 2000000, cy: Long = 1000000) = XmlPull.parse((
        """<p:sp xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
            """<p:nvSpPr><p:cNvPr id="4" name="S"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""" +
            """<a:prstGeom prst="$prst"><a:avLst>$av</a:avLst></a:prstGeom></p:spPr></p:sp>""").toByteArray())!!

    private fun read(prst: String, av: String = "", cx: Long = 2000000, cy: Long = 1000000) =
        DrawingShape.read(sp(prst, av, cx, cy), PptxReader.Palette.DEFAULT) { SlideBox("") }

    private fun end(op: Freeform.Op) = when (op) { is Freeform.Move -> op.x to op.y; is Freeform.Line -> op.x to op.y; is Freeform.Cubic -> op.x to op.y; else -> null }

    @Test
    fun aHexagonIsDrawnAsItsDefinitionSays() {
        val s = read("hexagon")
        assertEquals(ShapeType.FREEFORM, s.type); assertEquals("hexagon", s.preset); assertNull(s.presetAdjust)
        val ops = s.path!!.parts.single().ops
        // The corners a quarter of the short side in, from the left middle round.
        assertEquals(listOf(0f to 0.5f, 0.125f to 0f, 0.875f to 0f, 1f to 0.5f, 0.875f to 1f, 0.125f to 1f),
            ops.mapNotNull { end(it) }.map { (x, y) -> Math.round(x * 1000) / 1000f to Math.round(y * 1000) / 1000f })
        // Its own adjust value moves the corners.
        val narrow = read("hexagon", """<a:gd name="adj" fmla="val 50000"/>""")
        assertEquals("adj=val 50000", narrow.presetAdjust)
        assertEquals(0.25f, end(narrow.path!!.parts.single().ops[1])!!.first, 0.001f)
    }

    @Test
    fun theAppsOwnPresetsStayItsOwnUnlessTheirAdjustValuesDiffer() {
        assertEquals(ShapeType.ROUNDED, read("roundRect").type)
        assertEquals(ShapeType.ROUNDED, read("roundRect", """<a:gd name="adj" fmla="val 16667"/>""").type)
        val pill = read("roundRect", """<a:gd name="adj" fmla="val 50000"/>""")
        assertEquals("a capsule", ShapeType.FREEFORM, pill.type); assertEquals("roundRect", pill.preset)
        assertEquals(ShapeType.ARROW, read("rightArrow").type)
        assertEquals(ShapeType.FREEFORM, read("rightArrow", """<a:gd name="adj1" fmla="val 30000"/><a:gd name="adj2" fmla="val 60000"/>""").type)
        assertEquals(ShapeType.RECT, read("rect").type)
    }

    @Test
    fun everyDefinitionDraws() {
        for (name in PresetGeometries.names) {
            val f = PresetGeometries.freeform(name, null, 2000000.0, 1000000.0)
            assertNotNull("$name draws", f)
            assertTrue("$name draws something", f!!.drawsSomething)
            val b = f.bounds()!!
            // A callout's pointer and a cloud's bubbles reach outside the box; nothing lands far off.
            assertTrue("$name: ${b.toList()}", b[0] > -1.5f && b[1] > -1.5f && b[2] < 2.5f && b[3] < 2.5f && b[2] - b[0] > 0.1f && b[3] - b[1] > 0.05f)
        }
        assertTrue("dozens of them", PresetGeometries.names.size > 80)
    }

    @Test
    fun presetsKeepTheirNameInPptxAndTheirLookInOdp() {
        val deck = Presentation(mutableListOf(Slide(title = SlideBox("Presets")).also { slide ->
            listOf("chevron" to "", "flowChartDocument" to "", "wedgeRectCallout" to """<a:gd name="adj1" fmla="val 30000"/><a:gd name="adj2" fmla="val 80000"/>""").forEachIndexed { k, (p, av) ->
                slide.shapes.add(read(p, av).also { it.xPct = 0.1f + k * 0.3f; it.yPct = 0.3f; it.wPct = 0.2f; it.hPct = 0.2f })
            }
        }))
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(deck, it) }.toByteArray()
        val slideXml = String(java.util.zip.ZipInputStream(pptx.inputStream()).use { z -> generateSequence { z.nextEntry }.first { it.name == "ppt/slides/slide1.xml" }; z.readBytes() })
        assertTrue(slideXml.contains("""<a:prstGeom prst="chevron"><a:avLst/></a:prstGeom>"""))
        assertTrue(slideXml.contains("""<a:prstGeom prst="wedgeRectCallout"><a:avLst><a:gd name="adj1" fmla="val 30000"/><a:gd name="adj2" fmla="val 80000"/></a:avLst></a:prstGeom>"""))
        val back = PptxReader.read(pptx).slides[0].shapes
        assertEquals(listOf("chevron", "flowChartDocument", "wedgeRectCallout"), back.map { it.preset })
        assertEquals("adj1=val 30000|adj2=val 80000", back[2].presetAdjust)
        // In ODF, the outline itself.
        val odp = OdpReader.read(ByteArrayOutputStream().also { OdpWriter.write(deck, it) }.toByteArray()).slides[0].shapes
        assertEquals(listOf(ShapeType.FREEFORM, ShapeType.FREEFORM, ShapeType.FREEFORM), odp.map { it.type })
        assertEquals(deck.slides[0].shapes[0].path!!.parts.single().ops.size, odp[0].path!!.parts.single().ops.size)
        // At another size, a preset is worked out again (its corners stay a quarter of the short side).
        val wide = PresetGeometries.outline("hexagon", null, 400f, 100f)!!
        assertEquals(0.0625f, end(wide.parts.single().ops[1])!!.first, 0.001f)
    }

    /**
     * (Opt-in, `-Daero.presetSheet=<dir>` and `-Daero.presets=a,b,…`.) The presets on a grid — as a
     * pptx for LibreOffice to draw from its own definitions, and as SVG drawn from the app's — to
     * set side by side.
     */
    @Test
    fun presetSheetForComparison() {
        val dir = System.getProperty("aero.presetSheet")?.let { java.io.File(it) } ?: return
        val names = System.getProperty("aero.presets")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: PresetGeometries.names.sorted()
        dir.mkdirs()
        val cols = 4; val rows = 3; val per = cols * rows
        val slides = names.chunked(per).map { chunk ->
            Slide(title = SlideBox("")).also { slide ->
                slide.layout = com.therealityteam.aeropages.model.SlideLayout.BLANK
                chunk.forEachIndexed { k, name ->
                    val cx = 0.03f + (k % cols) * 0.24f; val cy = 0.05f + (k / cols) * 0.32f
                    val sh = com.therealityteam.aeropages.model.SlideShape(ShapeType.FREEFORM, SlideBox(""), cx + 0.02f, cy + 0.02f, 0.16f, 0.18f, fill = 0xFF9DC3E6, border = 0xFF1F4E79)
                    sh.preset = name; sh.path = PresetGeometries.freeform(name, null, 0.16 * 12192000, 0.18 * 6858000)
                    slide.shapes.add(sh)
                    slide.shapes.add(com.therealityteam.aeropages.model.SlideShape(ShapeType.TEXTBOX, SlideBox(name, com.therealityteam.aeropages.model.CharacterFormat(fontSizePt = 10f)), cx, cy + 0.24f, 0.22f, 0.05f, fill = null, border = null))
                }
            }
        }
        java.io.File(dir, "presets.pptx").writeBytes(ByteArrayOutputStream().also { PptxWriter.write(Presentation(slides.toMutableList()), it) }.toByteArray())
        // The app's drawing, page by page: 960 × 540 as the slide is.
        names.chunked(per).forEachIndexed { page, chunk ->
            val svg = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" width="960" height="540" viewBox="0 0 960 540"><rect width="960" height="540" fill="white"/>""")
            chunk.forEachIndexed { k, name ->
                val x0 = (0.05f + (k % cols) * 0.24f) * 960f; val y0 = (0.07f + (k / cols) * 0.32f) * 540f
                val w = 0.16f * 960f; val h = 0.18f * 540f
                val f = PresetGeometries.outline(name, null, w * 12700f, h * 12700f)
                // As the app paints it: every part's fill, then the outlines over them.
                val drawn = f?.parts.orEmpty().flatMap { listOf(it to true, it to false) }.sortedBy { if (it.second) 0 else 1 }
                drawn.forEach { (part, fillPass) ->
                    val d = StringBuilder()
                    part.ops.forEach { op ->
                        when (op) {
                            is Freeform.Move -> d.append("M ${x0 + op.x * w} ${y0 + op.y * h} ")
                            is Freeform.Line -> d.append("L ${x0 + op.x * w} ${y0 + op.y * h} ")
                            is Freeform.Cubic -> d.append("C ${x0 + op.x1 * w} ${y0 + op.y1 * h} ${x0 + op.x2 * w} ${y0 + op.y2 * h} ${x0 + op.x * w} ${y0 + op.y * h} ")
                            Freeform.Close -> d.append("Z ")
                        }
                    }
                    val shade = when { part.shade <= -0.4f -> "#5E7E9A"; part.shade < 0f -> "#7E9DB8"; part.shade >= 0.4f -> "#D4E6F5"; part.shade > 0f -> "#B9D5EE"; else -> "#9DC3E6" }
                    if (fillPass) { if (part.filled) svg.append("""<path d="$d" fill="$shade" fill-rule="evenodd" stroke="none"/>""") }
                    else if (part.stroked) svg.append("""<path d="$d" fill="none" stroke="#1F4E79" stroke-width="1"/>""")
                }
                svg.append("""<text x="${x0 - 0.02f * 960f}" y="${y0 + 0.235f * 540f}" font-size="10" font-family="sans-serif">$name</text>""")
            }
            java.io.File(dir, "ours-${page + 1}.svg").writeText(svg.append("</svg>").toString())
        }
    }
}
