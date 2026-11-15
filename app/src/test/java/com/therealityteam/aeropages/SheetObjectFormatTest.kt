package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.ShapeShadow
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's Picture Format and Shape Format commands on a sheet: each one undo step, and the inserts that open the tabs. */
class SheetObjectFormatTest {
    private fun editor() = SheetEditor().also { it.load(Workbook.blank()); it.select(1, 1) }

    @Test
    fun aPictureInsertIsCountedAndResetPictureUndoesCropAndShadowThenSize() {
        val ed = editor()
        assertTrue(ed.insertImage(TestPng.header(200, 100), "image/png"))
        assertEquals(1, ed.imageInserts)
        val img = ed.selectedImage!!
        ed.resizeImage(img, 300f, 150f)
        ed.cropImage(img, 0.1f, 0f, 0.1f, 0f)
        ed.setImageShadow(img, ShapeShadow.PRESETS[0])
        assertEquals(240f, img.widthPt, 0.01f)

        ed.resetImage(ed.sheet.images.single(), size = false)
        ed.sheet.images.single().let { p ->
            assertFalse(p.cropped); assertNull(p.shadow)
            assertEquals("the whole picture at the scale it was shown at", 300f, p.widthPt, 0.01f)
        }
        ed.undo()
        assertTrue("one undo step brings the crop and shadow back", ed.sheet.images.single().let { it.cropped && it.shadow != null })

        // And Size: its pixels at 96 dpi (150 × 75 pt).
        ed.resetImage(ed.sheet.images.single(), size = true)
        assertEquals(150f, ed.sheet.images.single().widthPt, 0.01f)
        assertEquals(75f, ed.sheet.images.single().heightPt, 0.01f)
    }

    @Test
    fun arrangeMovesThePickedObjectAmongItsKindOneStepAtATime() {
        val ed = editor()
        repeat(3) { ed.insertShape(ShapeType.RECT) }
        assertEquals(3, ed.shapeInserts)
        val (a, b, c) = ed.sheet.shapes.toList()
        ed.selectShape(a)
        ed.arrangeSelected(forward = true, toEdge = false)
        assertEquals(listOf(b, a, c), ed.sheet.shapes.toList())
        ed.arrangeSelected(forward = true, toEdge = true)
        assertEquals(listOf(b, c, a), ed.sheet.shapes.toList())
        val rev = ed.revision
        ed.arrangeSelected(forward = true, toEdge = true)
        assertEquals("already at the front: no step", rev, ed.revision)
        ed.arrangeSelected(forward = false, toEdge = true)
        assertEquals(listOf(a, b, c), ed.sheet.shapes.toList())
        ed.undo()
        assertEquals(listOf(b, c, a).map { it.name }, ed.sheet.shapes.map { it.name })
    }

    @Test
    fun aShapeStyleSetsFillOutlineAndTextTogetherAndSizeKeepsTheCorner() {
        val ed = editor()
        ed.insertShape(ShapeType.RECT, text = "Go")
        val sh = ed.selectedShape!!
        val corner = ed.floatingOrigin(sh)
        ed.applyShapeStyle(sh, 0xFF217346, 0xFF155030, 0xFFFFFFFF)
        assertEquals(0xFF217346, sh.shape.fill)
        assertEquals(0xFF155030, sh.shape.border)
        assertEquals(0xFFFFFFFF, sh.shape.box.paragraph.runs.first().format.color)
        ed.setShapeSize(sh, 200f, null)
        assertEquals(200f, sh.widthPt, 0.01f)
        assertEquals(64f, sh.heightPt, 0.01f)
        assertEquals(corner, ed.floatingOrigin(sh))
        ed.undo()
        assertSame(ed.sheet.shapes.single(), ed.sheet.shapes.first())
        assertEquals(96f, ed.sheet.shapes.single().widthPt, 0.01f)
    }
}
