package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ChartSeries
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Verifies free shapes + tables survive a PptxWriter -> PptxReader round trip. */
class PptxRoundTripTest {

    private fun shapeWith(type: ShapeType, text: String, fill: Long?, border: Long?): SlideShape {
        val s = SlideShape(type)
        s.box.paragraph.runs.clear()
        s.box.paragraph.runs.add(TextRun(text, CharacterFormat(fontSizePt = 18f)))
        s.xPct = 0.2f; s.yPct = 0.3f; s.wPct = 0.4f; s.hPct = 0.25f
        s.fill = fill; s.border = border
        return s
    }

    @Test
    fun shapesAndTablesRoundTrip() {
        val pres = Presentation(mutableListOf(Slide(
            title = SlideBox("标题", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER),
            body = SlideBox("正文一\n正文二", CharacterFormat(fontSizePt = 22f)),
        )))
        val slide = pres.slides[0]
        slide.shapes.add(shapeWith(ShapeType.ELLIPSE, "圆形", 0xFF2B579A, 0xFF1F3A6B))
        slide.shapes.add(shapeWith(ShapeType.STAR, "星形", null, 0xFFC0504D))

        val table = SlideTable.create(2, 3)
        table.headerRow = true; table.bandedRows = true
        val labels = listOf(listOf("A", "B", "C"), listOf("1", "2", "3"))
        for (r in 0..1) for (c in 0..2) {
            val box = table.cells[r][c]; box.paragraph.runs.clear()
            box.paragraph.runs.add(TextRun(labels[r][c], CharacterFormat(fontSizePt = 16f)))
        }
        slide.tables.add(table)

        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val back = PptxReader.read(bos.toByteArray())

        val s = back.slides[0]
        assertEquals("标题", s.title.text)
        assertTrue("body kept", s.body.text.contains("正文一") && s.body.text.contains("正文二"))

        assertEquals("two shapes", 2, s.shapes.size)
        val ellipse = s.shapes.firstOrNull { it.type == ShapeType.ELLIPSE }
        assertTrue("ellipse present", ellipse != null)
        assertEquals("ellipse text", "圆形", ellipse!!.box.text)
        assertEquals("ellipse fill", 0xFF2B579A, ellipse.fill)
        assertTrue("star present", s.shapes.any { it.type == ShapeType.STAR })

        assertEquals("one table", 1, s.tables.size)
        val t = s.tables[0]
        assertEquals("rows", 2, t.rows)
        assertEquals("cols", 3, t.cols)
        assertEquals("cell A", "A", t.cells[0][0].text)
        assertEquals("cell 3", "3", t.cells[1][2].text)
        assertTrue("header flag", t.headerRow)
    }

    @Test
    fun chartRoundTrips() {
        val pres = Presentation(mutableListOf(Slide()))
        val chart = SlideChart(
            type = ChartType.COLUMN,
            title = "销售额",
            categories = mutableListOf("Q1", "Q2", "Q3"),
            series = mutableListOf(
                ChartSeries("北区", mutableListOf(3f, 5f, 4f)),
                ChartSeries("南区", mutableListOf(2f, 6f, 7f)),
            ),
            showLegend = true,
        )
        pres.slides[0].charts.add(chart)

        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val back = PptxReader.read(bos.toByteArray())

        val charts = back.slides[0].charts
        assertEquals("one chart", 1, charts.size)
        val c = charts[0]
        assertEquals("type", ChartType.COLUMN, c.type)
        assertEquals("title", "销售额", c.title)
        assertEquals("categories", listOf("Q1", "Q2", "Q3"), c.categories.toList())
        assertEquals("series count", 2, c.series.size)
        assertEquals("series name", "南区", c.series[1].name)
        assertEquals("series values", listOf(2f, 6f, 7f), c.series[1].values.toList())
        assertTrue("legend", c.showLegend)
    }

    @Test
    fun richTextRoundTrips() {
        // Title with bold+sized run, a shape with a coloured italic run.
        val title = SlideBox("", CharacterFormat(), TextAlign.CENTER)
        title.paragraph.runs.clear()
        title.paragraph.runs.add(TextRun("加粗标题", CharacterFormat(fontSizePt = 40f, bold = true)))
        val pres = Presentation(mutableListOf(Slide(title = title, body = SlideBox("普通正文", CharacterFormat(fontSizePt = 22f)))))
        val shape = SlideShape(ShapeType.RECT)
        shape.box.paragraph.runs.clear()
        shape.box.paragraph.runs.add(TextRun("红斜体", CharacterFormat(fontSizePt = 28f, italic = true, color = 0xFFCC0000)))
        pres.slides[0].shapes.add(shape)

        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val s = PptxReader.read(bos.toByteArray()).slides[0]

        assertEquals("加粗标题", s.title.text)
        val tf = s.title.paragraph.runs.first { it.text.isNotBlank() }.format
        assertTrue("title bold", tf.bold)
        assertEquals("title size", 40f, tf.fontSizePt)

        val sf = s.shapes.single().box.paragraph.runs.first { it.text.isNotBlank() }.format
        assertEquals("shape text", "红斜体", s.shapes.single().box.text)
        assertTrue("shape italic", sf.italic)
        assertEquals("shape color", 0xFFCC0000, sf.color)
        assertEquals("shape size", 28f, sf.fontSizePt)
    }

    @Test
    fun extendedRunAttributesRoundTrip() {
        val pres = Presentation(mutableListOf(Slide()))
        val sh = SlideShape(ShapeType.RECT)
        sh.box.paragraph.runs.clear()
        sh.box.paragraph.runs.add(TextRun("样式", CharacterFormat(
            fontSizePt = 24f, strikethrough = true, smallCaps = true, superscript = true,
            letterSpacingPt = 2f, fontFamily = "Times New Roman", highlight = 0xFFFFFF00,
        )))
        pres.slides[0].shapes.add(sh)

        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val f = PptxReader.read(bos.toByteArray()).slides[0].shapes.single().box.paragraph.runs.first { it.text.isNotBlank() }.format
        assertTrue("strike", f.strikethrough)
        assertTrue("smallcaps", f.smallCaps)
        assertTrue("superscript", f.superscript)
        assertEquals("letter spacing", 2f, f.letterSpacingPt)
        assertEquals("font family", "Times New Roman", f.fontFamily)
        assertEquals("highlight", 0xFFFFFF00, f.highlight)
    }

    @Test
    fun hyperlinkRoundTrips() {
        val title = SlideBox("", CharacterFormat(), TextAlign.CENTER)
        title.paragraph.runs.clear()
        title.paragraph.runs.add(TextRun("普通", CharacterFormat(fontSizePt = 40f)))
        title.paragraph.runs.add(TextRun("官网", CharacterFormat(fontSizePt = 40f, link = "https://example.com/path?a=1&b=2")))
        val pres = Presentation(mutableListOf(Slide(title = title, body = SlideBox("正文", CharacterFormat(fontSizePt = 22f)))))
        // also a link inside a free shape
        val sh = SlideShape(ShapeType.RECT)
        sh.box.paragraph.runs.clear()
        sh.box.paragraph.runs.add(TextRun("按钮", CharacterFormat(fontSizePt = 18f, link = "https://aero.example")))
        pres.slides[0].shapes.add(sh)

        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val s = PptxReader.read(bos.toByteArray()).slides[0]

        val linked = s.title.paragraph.runs.firstOrNull { it.format.link != null }
        assertTrue("title has a linked run", linked != null)
        assertEquals("title link url", "https://example.com/path?a=1&b=2", linked!!.format.link)
        assertEquals("title linked text", "官网", linked.text)
        assertEquals("shape link", "https://aero.example", s.shapes.single().box.paragraph.runs.first { it.format.link != null }.format.link)
    }

    @Test
    fun shapeFlipAndRotationRoundTrip() {
        val pres = Presentation(mutableListOf(Slide()))
        val sh = SlideShape(ShapeType.ARROW)
        sh.flipH = true; sh.flipV = false; sh.rotationDeg = 45f
        pres.slides[0].shapes.add(sh)
        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val s = PptxReader.read(bos.toByteArray()).slides[0].shapes.single()
        assertTrue("flipH", s.flipH)
        assertTrue("flipV off", !s.flipV)
        assertEquals("rotation", 45f, s.rotationDeg)
    }

    @Test
    fun chartColorSetRoundTrips() {
        val pres = Presentation(mutableListOf(Slide()))
        pres.slides[0].charts.add(SlideChart(
            type = ChartType.COLUMN, title = "C",
            categories = mutableListOf("a", "b"),
            series = mutableListOf(ChartSeries("s", mutableListOf(1f, 2f))),
            colorSet = 2,
        ))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        assertEquals(2, PptxReader.read(bos.toByteArray()).slides[0].charts.single().colorSet)
    }

    @Test
    fun animationsRoundTrip() {
        val pres = Presentation(mutableListOf(Slide(
            title = SlideBox("标题", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER),
            body = SlideBox("正文", CharacterFormat(fontSizePt = 22f)),
        )))
        val slide = pres.slides[0]
        slide.title.anims.add(com.therealityteam.aeropages.model.ElementAnim(com.therealityteam.aeropages.model.AnimEffect.FADE))
        slide.body.anims.add(com.therealityteam.aeropages.model.ElementAnim(com.therealityteam.aeropages.model.AnimEffect.FLY_IN))
        val sh = shapeWith(ShapeType.RECT, "形", 0xFF217346, null)
        sh.box.anims.add(com.therealityteam.aeropages.model.ElementAnim(com.therealityteam.aeropages.model.AnimEffect.ZOOM))
        slide.shapes.add(sh)

        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val s = PptxReader.read(bos.toByteArray()).slides[0]

        assertEquals(com.therealityteam.aeropages.model.AnimEffect.FADE, s.title.anims.single().effect)
        assertEquals(com.therealityteam.aeropages.model.AnimEffect.FLY_IN, s.body.anims.single().effect)
        assertEquals(com.therealityteam.aeropages.model.AnimEffect.ZOOM, s.shapes.single().box.anims.single().effect)
        // Timing block must be present and well-formed enough to carry one build step per element.
        val xml = String(bos.toByteArray())  // not the slide xml directly, but ensures write didn't throw
        assertTrue(xml.isNotEmpty())
    }

    @Test
    fun animationDirectionAndTableChartMsRoundTrip() {
        val pres = Presentation(mutableListOf(Slide()))
        val sh = SlideShape(ShapeType.RECT)
        sh.box.anims.add(com.therealityteam.aeropages.model.ElementAnim(com.therealityteam.aeropages.model.AnimEffect.FLY_IN))
        sh.box.anims.single().durationMs = 800
        sh.box.anims.single().dir = com.therealityteam.aeropages.model.AnimDir.BOTTOM
        pres.slides[0].shapes.add(sh)
        val table = SlideTable.create(1, 2)
        table.anims.add(com.therealityteam.aeropages.model.ElementAnim(com.therealityteam.aeropages.model.AnimEffect.FADE))
        table.anims.single().durationMs = 1200
        pres.slides[0].tables.add(table)

        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val s = PptxReader.read(bos.toByteArray()).slides[0]

        val b = s.shapes.single().box
        assertEquals(com.therealityteam.aeropages.model.AnimDir.BOTTOM, b.anims.single().dir)
        assertEquals(800, b.anims.single().durationMs)
        assertEquals(1200, s.tables.single().anims.single().durationMs)
        assertEquals(com.therealityteam.aeropages.model.AnimEffect.FADE, s.tables.single().anims.single().effect)
    }

    @Test
    fun animationDurationRoundTrips() {
        val pres = Presentation(mutableListOf(Slide(
            title = SlideBox("标题", CharacterFormat(fontSizePt = 40f, bold = true), TextAlign.CENTER),
            body = SlideBox("正文", CharacterFormat(fontSizePt = 22f)),
        )))
        pres.slides[0].title.anims.add(com.therealityteam.aeropages.model.ElementAnim(com.therealityteam.aeropages.model.AnimEffect.FADE))
        pres.slides[0].title.anims.single().durationMs = 1000
        val sh = SlideShape(ShapeType.RECT)
        sh.box.anims.add(com.therealityteam.aeropages.model.ElementAnim(com.therealityteam.aeropages.model.AnimEffect.ZOOM))
        sh.box.anims.single().durationMs = 250
        pres.slides[0].shapes.add(sh)

        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val s = PptxReader.read(bos.toByteArray()).slides[0]

        assertEquals(com.therealityteam.aeropages.model.AnimEffect.FADE, s.title.anims.single().effect)
        assertEquals(1000, s.title.anims.single().durationMs)
        assertEquals(250, s.shapes.single().box.anims.single().durationMs)
    }

    @Test
    fun pieChartRoundTrips() {
        val pres = Presentation(mutableListOf(Slide()))
        pres.slides[0].charts.add(SlideChart(
            type = ChartType.PIE,
            title = "占比",
            categories = mutableListOf("甲", "乙", "丙"),
            series = mutableListOf(ChartSeries("份额", mutableListOf(40f, 35f, 25f))),
        ))
        val bos = ByteArrayOutputStream()
        PptxWriter.write(pres, bos)
        val c = PptxReader.read(bos.toByteArray()).slides[0].charts.single()
        assertEquals(ChartType.PIE, c.type)
        assertEquals(listOf("甲", "乙", "丙"), c.categories.toList())
        assertEquals(listOf(40f, 35f, 25f), c.series[0].values.toList())
    }

    @Test
    fun speakerNotesRoundTrip() {
        val p = Presentation()
        p.slides[0].title.paragraph.runs[0].text = "首页"
        p.slides[0].notes = "开场白：欢迎大家\n第二行备注"
        p.slides.add(Slide().also { it.title.paragraph.runs[0].text = "第二页" }) // no notes
        p.slides.add(Slide().also { it.notes = "结束语" })

        val bos = ByteArrayOutputStream()
        PptxWriter.write(p, bos)
        val back = PptxReader.read(bos.toByteArray())

        assertEquals(3, back.slides.size)
        assertEquals("开场白：欢迎大家\n第二行备注", back.slides[0].notes)
        assertEquals("", back.slides[1].notes)
        assertEquals("结束语", back.slides[2].notes)
    }

    @Test
    fun transitionHiddenAndCommentsRoundTrip() {
        val p = Presentation()
        p.slides[0].transition = com.therealityteam.aeropages.model.SlideTransition.FADE
        p.slides[0].comments.add(com.therealityteam.aeropages.model.SlideComment("Ann", "第一页的批注；含分隔符|测试"))
        p.slides.add(Slide().also {
            it.transition = com.therealityteam.aeropages.model.SlideTransition.PUSH
            it.hidden = true
        })
        p.slides.add(Slide().also { it.transition = com.therealityteam.aeropages.model.SlideTransition.COVER })
        p.slides[2].comments.add(com.therealityteam.aeropages.model.SlideComment("Ann", "第三页批注一"))
        p.slides[2].comments.add(com.therealityteam.aeropages.model.SlideComment("Bo", "第三页批注二"))

        val bos = ByteArrayOutputStream()
        PptxWriter.write(p, bos)
        val back = PptxReader.read(bos.toByteArray())

        assertEquals(com.therealityteam.aeropages.model.SlideTransition.FADE, back.slides[0].transition)
        assertEquals(com.therealityteam.aeropages.model.SlideTransition.PUSH, back.slides[1].transition)
        assertEquals(com.therealityteam.aeropages.model.SlideTransition.COVER, back.slides[2].transition)
        assertTrue("hidden kept", back.slides[1].hidden)
        assertTrue("others visible", !back.slides[0].hidden && !back.slides[2].hidden)
        assertEquals(listOf("第一页的批注；含分隔符|测试"), back.slides[0].comments.map { it.text })
        assertEquals(listOf("Ann" to "第三页批注一", "Bo" to "第三页批注二"), back.slides[2].comments.map { it.author to it.text })
    }

    @Test
    fun rehearseTimingsRoundTrip() {
        val p = Presentation()
        p.slides[0].advanceMs = 5200                       // no transition → bare <p:transition advTm/>
        p.slides.add(Slide().also {
            it.transition = com.therealityteam.aeropages.model.SlideTransition.FADE
            it.advanceMs = 12000                           // combined with a transition child
        })
        p.slides.add(Slide())                              // manual slide stays 0

        val bos = ByteArrayOutputStream()
        PptxWriter.write(p, bos)
        val back = PptxReader.read(bos.toByteArray())

        assertEquals(5200, back.slides[0].advanceMs)
        assertEquals(12000, back.slides[1].advanceMs)
        assertEquals(com.therealityteam.aeropages.model.SlideTransition.FADE, back.slides[1].transition)
        assertEquals(0, back.slides[2].advanceMs)
    }

    @Test
    fun multipleImagesRoundTrip() {
        val p = Presentation()
        val png1 = byteArrayOf(1, 2, 3, 4)
        val png2 = byteArrayOf(5, 6, 7, 8, 9)
        val png3 = byteArrayOf(10, 11)
        p.slides[0].images.add(com.therealityteam.aeropages.model.SlideImage(png1, "image/png", 300f, 200f))
        p.slides[0].images.add(com.therealityteam.aeropages.model.SlideImage(png2, "image/png", 300f, 200f))
        p.slides.add(Slide().also { it.images.add(com.therealityteam.aeropages.model.SlideImage(png3, "image/jpeg", 200f, 100f)) })

        val bos = ByteArrayOutputStream()
        PptxWriter.write(p, bos)
        val back = PptxReader.read(bos.toByteArray())

        assertEquals("slide 1 keeps both pictures", 2, back.slides[0].images.size)
        assertEquals(4, back.slides[0].images[0].data.size + 0)
        assertEquals(5, back.slides[0].images[1].data.size + 0)
        assertEquals("slide 2 keeps its picture", 1, back.slides[1].images.size)
        assertEquals("image/jpeg", back.slides[1].images[0].mime)
    }
}
