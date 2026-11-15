package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.ChartBlock
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ShapeShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Word does after an insert: the object is ready, and its contextual tab can open (the counters say an insert happened). */
class InsertReadinessTest {
    @Test
    fun aTableInsertPutsTheCaretInItAndIsCounted() {
        val d = DocumentEditor()
        d.load(Document.blank())
        d.insertTable(2, 3)
        assertEquals(1, d.tableInserts)
        assertTrue("the caret is in the new table", d.activeInTable)
    }

    @Test
    fun aShapeOrTextBoxInsertIsCountedAndActive() {
        val d = DocumentEditor()
        d.load(Document.blank())
        d.insertShape("roundRect")
        assertEquals(1, d.shapeInserts)
        assertTrue(d.activeBox != null)
        d.insertTextBox()
        assertEquals(2, d.shapeInserts)
        assertTrue(d.activeInTextBox)
    }

    /** A PNG's signature and IHDR size only: [w] × [h] pixels (all ImageSize reads). */
    private fun png(w: Int, h: Int): ByteArray {
        fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            be(13) + "IHDR".toByteArray() + be(w) + be(h)
    }

    private fun DocumentEditor.picture() = document.blocks.filterIsInstance<ImageBlock>().single()

    @Test
    fun aPictureInsertIsPickedAndCounted() {
        val d = DocumentEditor()
        d.load(Document.blank())
        d.insertImage(png(800, 400), "image/png", 300f, 150f)
        assertEquals(1, d.imageInserts)
        assertTrue("the new picture is picked", d.selectedImage === d.picture())
    }

    @Test
    fun resetPictureUndoesCropShadowAndFlipsAndCanResetTheSize() {
        val d = DocumentEditor()
        d.load(Document.blank())
        d.insertImage(png(800, 400), "image/png", 300f, 150f)
        d.cropImage(d.picture(), 0.1f, 0f, 0.1f, 0f)
        assertEquals(240f, d.picture().widthPt, 0.01f)
        d.setImageShadow(d.picture(), ShapeShadow.PRESETS[0])
        d.setImageFlip(d.picture(), h = true, v = false)
        assertTrue(d.picture().flipH)

        d.resetImage(d.picture(), size = false)
        d.picture().let { p ->
            assertFalse(p.cropped); assertNull(p.shadow); assertFalse(p.flipH)
            assertEquals("the whole picture at the scale it was shown at", 300f, p.widthPt, 0.01f)
            assertEquals(150f, p.heightPt, 0.01f)
        }
        d.undo()
        assertTrue("one undo step brings it all back", d.picture().cropped && d.picture().flipH && d.picture().shadow != null)

        // And Size: its pixels at 96 dpi (600 × 300 pt), no wider than 400 pt.
        d.resetImage(d.picture(), size = true)
        assertEquals(400f, d.picture().widthPt, 0.01f)
        assertEquals(200f, d.picture().heightPt, 0.01f)
        val rev = d.revision
        d.resetImage(d.picture(), size = true)
        assertEquals("nothing left to reset: no new step", rev, d.revision)
    }

    @Test
    fun aChartInsertIsPickedCountedAndItsDesignCommandsAreOneStepEach() {
        val d = DocumentEditor()
        d.load(Document.blank())
        d.insertChart()
        assertEquals(1, d.chartInserts)
        val block = d.document.blocks.filterIsInstance<ChartBlock>().single()
        assertTrue(d.selectedChart === block)

        val cats = block.chart.categories.toList()
        val names = block.chart.series.map { it.name }
        val first = block.chart.series[0].values[1]
        d.editChart(block) { it.switchRowColumn() }
        val switched = d.document.blocks.filterIsInstance<ChartBlock>().single().chart
        assertEquals("the series become the categories", names, switched.categories)
        assertEquals(cats, switched.series.map { it.name })
        assertEquals("a value moves with its cell", first, switched.series[1].values[0])
        d.undo()
        assertEquals(cats, d.document.blocks.filterIsInstance<ChartBlock>().single().chart.categories)

        val now = d.document.blocks.filterIsInstance<ChartBlock>().single()
        val rev = d.revision
        d.setChartElements(now, now.chart.title, "", "", now.chart.showLegend, now.chart.legendPos, now.chart.axisFormat)
        assertEquals("nothing changed: no step", rev, d.revision)
        d.setChartElements(now, "Sales", "Quarter", "", false, now.chart.legendPos, now.chart.axisFormat)
        assertEquals("Sales", now.chart.title)
        assertFalse(now.chart.showLegend)
    }
}
