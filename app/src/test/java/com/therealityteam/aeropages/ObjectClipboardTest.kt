package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.ObjectClipboard
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.ChartBlock
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextBoxBlock
import com.therealityteam.aeropages.model.Workbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pictures, shapes, charts and tables copied in one module and pasted in another — as Office pastes between its apps. */
class ObjectClipboardTest {

    @After fun forget() = ObjectClipboard.clear()

    /** A 1×1 PNG: a picture with pixels to size it by. */
    private val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 31, 21, -60, -119,
        0, 0, 0, 13, 73, 68, 65, 84, 120, -38, 99, -8, -1, -1, 63, 0, 5, -2, 2, -2, -89, 53, -127, -124, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)

    private fun deck(vararg slides: Slide) = SlideEditor().also { it.load(Presentation(slides.toMutableList())) }

    @Test
    fun aSlideShapePastesOnASheetAtItsSize() {
        val shape = SlideShape(ShapeType.ROUNDED, SlideBox("Decision"), xPct = 0.2f, yPct = 0.3f, wPct = 0.25f, hPct = 0.2f, fill = 0xFF70AD47, border = null)
        val slides = deck(Slide(title = SlideBox("A")).also { it.shapes.add(shape) })
        slides.selectShape(shape)
        assertEquals("the words it carries go beside it", "Decision", slides.copySelectedObject())
        val sheet = SheetEditor().also { it.load(Workbook.blank()); it.select(3, 2) }
        assertTrue(sheet.pasteItem(ObjectClipboard.item!!))
        val pasted = sheet.sheet.shapes.single()
        assertEquals(3, pasted.row); assertEquals(2, pasted.col)
        assertEquals(0.25f * 960f, pasted.widthPt, 0.01f); assertEquals(0.2f * 540f, pasted.heightPt, 0.01f)
        assertEquals(ShapeType.ROUNDED, pasted.shape.type); assertEquals("Decision", pasted.shape.box.text); assertEquals(0xFF70AD47, pasted.shape.fill)
        assertSame("it is picked", pasted, sheet.selectedShape)
        sheet.undo()
        assertTrue(sheet.sheet.shapes.isEmpty())
    }

    @Test
    fun aSheetPicturePastesOnASlideCentredWithItsCrop() {
        val sheet = SheetEditor().also { it.load(Workbook.blank()); it.select(1, 1) }
        assertTrue(sheet.insertImage(png, "image/png"))
        sheet.selectedImage!!.also { it.widthPt = 192f; it.heightPt = 108f; it.setCrop(0.1f, 0f, 0.1f, 0f) }
        assertEquals("", sheet.copySelectedObject())
        // Cut takes it off the sheet, the clipboard keeps it.
        sheet.cutSelectedObject()
        assertTrue(sheet.sheet.images.isEmpty())
        val slides = deck(Slide(title = SlideBox("B")))
        slides.pasteObject()
        val img = slides.slide.images.single()
        assertEquals(0.2f, img.wPct, 0.001f); assertEquals(0.2f, img.hPct, 0.001f)
        assertEquals("centred", 0.4f, img.xPct, 0.001f); assertEquals(0.4f, img.yPct, 0.001f)
        assertEquals(0.1f, img.cropL, 0.0001f); assertEquals(0.1f, img.cropR, 0.0001f)
        assertSame(img, slides.selectedImage)
    }

    @Test
    fun aPageChartPastesOnASlideAndBack() {
        val chart = ChartBlock(SlideChart.sample(ChartType.LINE).also { it.title = "Visitors" }, 360f, 216f)
        val doc = DocumentEditor().also { it.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("Intro")), chart))) }
        doc.selectChart(chart)
        assertEquals("Visitors", doc.copySelectedObject())
        val slides = deck(Slide(title = SlideBox("C")))
        slides.pasteObject()
        val pasted = slides.slide.charts.single()
        assertEquals(ChartType.LINE, pasted.type); assertEquals("Visitors", pasted.title)
        assertEquals(chart.chart.series.map { it.values }, pasted.series.map { it.values })
        assertEquals(360f / 960f, pasted.wPct, 0.001f)
        // Copied on the slide, it pastes on a page beside the caret's paragraph.
        slides.copySelectedObject()
        doc.pasteItem(ObjectClipboard.item!!)
        val charts = doc.document.blocks.filterIsInstance<ChartBlock>()
        assertEquals(2, charts.size)
        assertEquals(360f, charts[1].widthPt, 0.5f)
    }

    @Test
    fun slidesAndShapesAndTablesOnAPage() {
        val table = SlideTable(mutableListOf(mutableListOf(SlideBox("Region"), SlideBox("Sales")), mutableListOf(SlideBox("North"), SlideBox("42"))))
        val note = SlideShape(ShapeType.TEXTBOX, SlideBox("First line\nSecond line"), wPct = 0.3f, hPct = 0.1f, fill = null, border = 0xFF2E75B6)
        val picture = SlideImage(png, "image/png", 96f, 96f, 0.1f, 0.1f, 0.1f, 0.2f)
        val slides = deck(Slide(title = SlideBox("D")).also { it.tables.add(table); it.shapes.add(note); it.images.add(picture) })
        val doc = DocumentEditor().also { it.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("Report"))))) }

        slides.selectTable(table)
        assertEquals("Region\tSales\nNorth\t42", slides.copySelectedObject())
        doc.pasteItem(ObjectClipboard.item!!)
        val t = doc.document.blocks.filterIsInstance<TableBlock>().single().table
        assertEquals(listOf("Region", "Sales", "North", "42"), t.rows.flatMap { r -> r.cells.map { it.content.joinToString("") { p -> p.plainText() } } })
        assertTrue("the header row stays one", t.rows[0].header)

        slides.selectShape(note)
        slides.copySelectedObject()
        doc.pasteItem(ObjectClipboard.item!!)
        val box = doc.document.blocks.filterIsInstance<TextBoxBlock>().single()
        assertEquals(listOf("First line", "Second line"), box.paragraphs.map { it.plainText() })
        assertEquals(ShapeType.TEXTBOX, box.shape!!.type); assertEquals(0xFF2E75B6, box.shape!!.border)

        slides.selectImage(picture)
        slides.copySelectedObject()
        doc.pasteItem(ObjectClipboard.item!!)
        val img = doc.document.blocks.filterIsInstance<ImageBlock>().single()
        assertEquals(96f, img.widthPt, 0.01f); assertEquals(108f, img.heightPt, 0.01f)

        // A slide's table on a sheet is its cells.
        slides.selectTable(table)
        slides.copySelectedObject()
        val sheet = SheetEditor().also { it.load(Workbook.blank()); it.select(0, 0) }
        sheet.pasteItem(ObjectClipboard.item!!)
        assertEquals("North", sheet.sheet.cell(1, 0)!!.text); assertEquals("42", sheet.sheet.cell(1, 1)!!.text)
    }

    @Test
    fun aSheetChartPastesOnASlideWithItsData() {
        val sheet = SheetEditor().also { it.load(Workbook.blank()) }
        listOf(listOf("Month", "Sales"), listOf("Jan", "10"), listOf("Feb", "14")).forEachIndexed { r, row -> row.forEachIndexed { c, t -> sheet.sheet.ensure(r, c).text = t } }
        sheet.select(0, 0); sheet.extendSelection(2, 1)
        assertTrue(sheet.insertChartFromSelection(ChartType.COLUMN))
        assertEquals(sheet.sheet.charts[0].title, sheet.copyChart(0))
        val slides = deck(Slide(title = SlideBox("F")))
        slides.pasteObject()
        val pasted = slides.slide.charts.single()
        assertEquals(listOf(10f, 14f), pasted.series.single().values)
        assertEquals("a copy of the data, not a reference to cells the deck has not got", null, pasted.sourceRef)
        assertTrue(sheet.sheet.charts[0].sourceRef != null)
    }

    @Test
    fun duplicatingLeavesTheClipboardAlone() {
        val a = SlideShape(ShapeType.RECT, SlideBox("A"))
        val b = SlideShape(ShapeType.ELLIPSE, SlideBox("B"))
        val slides = deck(Slide(title = SlideBox("E")).also { it.shapes.add(a); it.shapes.add(b) })
        slides.selectShape(a)
        slides.copySelectedObject()
        slides.selectShape(b)
        slides.duplicateSelectedObject()
        assertEquals(3, slides.slide.shapes.size)
        assertEquals("the copy of A is still what pastes", "A", (ObjectClipboard.item as ObjectClipboard.Shape).shape.box.text)
        // A paste on a slide lands beside where the copy sat.
        slides.pasteObject()
        val pasted = slides.slide.shapes.last()
        assertNotNull(pasted)
        assertEquals(a.xPct + 0.03f, pasted.xPct, 0.001f)
    }
}
