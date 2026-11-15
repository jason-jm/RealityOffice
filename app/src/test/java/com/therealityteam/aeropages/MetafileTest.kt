package com.therealityteam.aeropages

import com.therealityteam.aeropages.TestCfb.le16
import com.therealityteam.aeropages.TestCfb.le32
import com.therealityteam.aeropages.format.ImageSize
import com.therealityteam.aeropages.format.metafile.Dib
import com.therealityteam.aeropages.format.metafile.MetafileImage
import com.therealityteam.aeropages.format.metafile.MetafilePath
import com.therealityteam.aeropages.format.metafile.MetafileText
import com.therealityteam.aeropages.format.metafile.Metafiles
import com.therealityteam.aeropages.model.Freeform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EMF and WMF pictures read as what they draw, from metafiles built record by record ([MS-EMF],
 * [MS-WMF]): shapes in their pens and brushes, the mapping, paths, clips, saved states, text in its
 * font (a Chinese one's double-byte text too) and bitmaps.
 */
class MetafileTest {

    // --- EMF ---------------------------------------------------------------------------------------

    private fun emfRecord(type: Int, vararg params: ByteArray): ByteArray {
        val body = params.fold(ByteArray(0)) { a, p -> a + p }
        val padded = body + ByteArray((4 - body.size % 4) % 4)
        return le32(type) + le32(8 + padded.size) + padded
    }

    private fun i(vararg v: Int) = v.fold(ByteArray(0)) { a, x -> a + le32(x) }
    private fun f(v: Float) = le32(java.lang.Float.floatToIntBits(v))
    private fun ref(rgb: Int) = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)

    /** An EMF: a 25 mm square frame on a device of 4 units a millimetre (so 100 units across), then [records] and the end. */
    private fun emf(vararg records: ByteArray): ByteArray {
        val body = records.fold(ByteArray(0)) { a, r -> a + r } + emfRecord(14, i(0, 0, 20))
        val header = i(0, 0, 99, 99) + i(0, 0, 2500, 2500) + " EMF".toByteArray() + i(0x10000) + i(0) + i(0) +
            le16(4) + le16(0) + i(0, 0, 0) + i(1000, 1000) + i(250, 250)
        val h = emfRecord(1, header)
        return h + body
    }

    @Test fun anEmfsShapesInTheirPensAndBrushes() {
        val pic = Metafiles.read(emf(
            emfRecord(39, i(1, 0, ref(0xFF0000), 0)),                    // a solid red brush
            emfRecord(37, i(1)),
            emfRecord(38, i(2, 0, 2, 0, ref(0x0000FF))),                 // a blue pen 2 units wide
            emfRecord(37, i(2)),
            emfRecord(43, i(10, 10, 50, 40)),                            // a rectangle
            emfRecord(86, i(0, 0, 0, 0), i(3), le16(60) + le16(10) + le16(90) + le16(10) + le16(75) + le16(40)),   // a triangle (16-bit points)
            emfRecord(37, i(0x80000005.toInt())),                        // NULL_BRUSH
            emfRecord(42, i(10, 60, 30, 90)),                            // an ellipse, outlined only
        ))!!
        assertEquals(0f, pic.left); assertEquals(100f, pic.width, 0.01f); assertEquals(100f, pic.height, 0.01f)
        assertEquals(25f / 25.4f * 72f, pic.widthPt, 0.01f)
        val paths = pic.items.filterIsInstance<MetafilePath>()
        assertEquals(3, paths.size)
        val rect = paths[0]
        assertEquals(0xFFFF0000.toInt(), rect.fill); assertEquals(0xFF0000FF.toInt(), rect.stroke); assertEquals(2f, rect.strokeWidth, 0.01f)
        assertEquals(Freeform.Move(10f, 10f), rect.ops[0]); assertEquals(Freeform.Line(50f, 40f), rect.ops[2])
        assertEquals(Freeform.Line(90f, 10f), paths[1].ops[1])
        assertNull(paths[2].fill); assertNotNull(paths[2].stroke)
        assertTrue(paths[2].ops.any { it is Freeform.Cubic })
    }

    @Test fun theMappingTheWorldTransformAndSavedStates() {
        val pic = Metafiles.read(emf(
            emfRecord(17, i(8)),                                          // MM_ANISOTROPIC
            emfRecord(9, i(1000, 1000)), emfRecord(11, i(100, 100)),     // window 1000 → viewport 100: a tenth
            emfRecord(43, i(100, 100, 500, 500)),                         // (10, 10) – (50, 50)
            emfRecord(33),                                                // SAVEDC
            emfRecord(35, f(2f) + f(0f) + f(0f) + f(2f) + f(0f) + f(0f)), // world: twice as large
            emfRecord(43, i(100, 100, 200, 200)),                         // (20, 20) – (40, 40)
            emfRecord(34, i(-1)),                                         // RESTOREDC: the world as it was
            emfRecord(43, i(100, 100, 200, 200)),                         // (10, 10) – (20, 20)
        ))!!
        val rects = pic.items.filterIsInstance<MetafilePath>()
        assertEquals(Freeform.Line(50f, 50f), rects[0].ops[2])
        assertEquals(Freeform.Move(20f, 20f), rects[1].ops[0]); assertEquals(Freeform.Line(40f, 40f), rects[1].ops[2])
        assertEquals(Freeform.Line(20f, 20f), rects[2].ops[2])
    }

    @Test fun pathsAndClips() {
        val pic = Metafiles.read(emf(
            emfRecord(30, i(0, 0, 50, 50)),                               // INTERSECTCLIPRECT
            emfRecord(59),                                                // BEGINPATH
            emfRecord(27, i(10, 10)), emfRecord(54, i(40, 10)), emfRecord(54, i(40, 40)),
            emfRecord(61), emfRecord(60),                                 // CLOSEFIGURE, ENDPATH
            emfRecord(62, i(0, 0, 0, 0)),                                 // FILLPATH
            emfRecord(75, i(0, 5)),                                       // EXTSELECTCLIPRGN, no region, copy: no clip
            emfRecord(43, i(60, 60, 70, 70)),
        ))!!
        val paths = pic.items.filterIsInstance<MetafilePath>()
        val filled = paths[0]
        assertNotNull(filled.fill); assertNull(filled.stroke)
        assertEquals(listOf(Freeform.Move(10f, 10f), Freeform.Line(40f, 10f), Freeform.Line(40f, 40f), Freeform.Close), filled.ops)
        assertEquals(50f, filled.clip!![2], 0.01f)
        assertNull(paths[1].clip)
    }

    @Test fun emfTextInItsFont() {
        val face = "Arial".toByteArray(Charsets.UTF_16LE).copyOf(64)
        // LOGFONTW: height -20, width, escapement 900 (a quarter turn), orientation, weight 700, italic …
        val logfont = i(-20, 0, 900, 0, 700) + byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0) + face
        val text = "Hi".toByteArray(Charsets.UTF_16LE)
        // EMRTEXT: reference (20, 80), 2 characters, the string 76 bytes into the record (after its header, bounds, mode,
        // scales and this), no options, a rectangle, the advances after the string.
        val emrText = i(20, 80, 2, 76, 0) + i(0, 0, 0, 0) + i(80)
        val pic = Metafiles.read(emf(
            emfRecord(82, i(3) + logfont),
            emfRecord(37, i(3)),
            emfRecord(24, i(ref(0x00AA00))),                              // the text colour
            emfRecord(18, i(1)),                                          // TRANSPARENT
            emfRecord(22, i(24)),                                         // TA_BASELINE
            emfRecord(84, i(0, 0, 0, 0) + i(1) + f(1f) + f(1f) + emrText + text + i(11, 12)),
        ))!!
        val t = pic.items.filterIsInstance<MetafileText>().single()
        assertEquals("Hi", t.text); assertEquals("Arial", t.face); assertEquals(20f, t.size, 0.01f)
        assertTrue(t.bold); assertTrue(t.italic); assertEquals(0xFF00AA00.toInt(), t.color)
        assertEquals(90f, t.angleDeg, 0.01f); assertEquals(1, t.vAlign); assertNull(t.background)
        assertEquals(20f, t.x, 0.01f); assertEquals(80f, t.y, 0.01f)
        assertEquals(listOf(11f, 12f), t.advances!!.toList())
    }

    @Test fun emfBitmapsAsFiles() {
        // A 2 × 2, 24-bit bottom-up DIB stretched over (10, 10) – (30, 30).
        val info = i(40, 2, 2) + le16(1) + le16(24) + i(0, 16, 0, 0, 0, 0)
        val bits = ByteArray(16) { 0x7F }
        // STRETCHDIBITS: bounds, dest x y, src x y w h, offBmi offBits (after the 80-byte fixed part), usage, rop, dest w h.
        val fixed = 8 + 72
        val rec = emfRecord(81, i(0, 0, 0, 0) + i(10, 10, 0, 0, 2, 2) + i(fixed, info.size, fixed + info.size, bits.size) + i(0, 0x00CC0020, 20, 20) + info + bits)
        val img = Metafiles.read(emf(rec))!!.items.filterIsInstance<MetafileImage>().single()
        assertEquals('B'.code.toByte(), img.data[0]); assertEquals('M'.code.toByte(), img.data[1])
        assertEquals(14 + info.size + bits.size, img.data.size)
        assertEquals(10f, img.x0); assertEquals(30f, img.x1); assertNull(img.src)
    }

    @Test fun anEmfsSize() {
        val size = ImageSize.of(emf())!!
        assertEquals(25f / 25.4f * 96f, size.first, 0.01f)
        assertFalse(Metafiles.isMetafile(ByteArray(100)))
    }

    @Test fun libreOfficesEmfAndWmfReadAsTheirDrawing() {
        // One drawing LibreOffice exported as each: shapes, a dashed curve, text in Latin and Chinese, a turned word.
        for (name in listOf("drawing.emf", "drawing.wmf")) {
            val pic = Metafiles.read(javaClass.classLoader!!.getResourceAsStream("metafile/$name")!!.readBytes())!!
            val texts = pic.items.filterIsInstance<MetafileText>()
            assertEquals(name, listOf("Hello EMF", "中文测试", "turned"), texts.map { it.text })
            assertEquals(name, 29.9f, texts.last().angleDeg, 0.5f)
            assertEquals(name, "SimSun", texts[1].face)
            assertTrue(name, pic.items.filterIsInstance<MetafilePath>().any { it.fill == 0xFF4472C4.toInt() })
            assertEquals(name, 279.4f, pic.widthPt, 0.5f)
        }
    }

    // --- WMF ---------------------------------------------------------------------------------------

    private fun w(vararg v: Int) = v.fold(ByteArray(0)) { a, x -> a + le16(x) }

    private fun wmfRecord(fn: Int, vararg params: ByteArray): ByteArray {
        val body = params.fold(ByteArray(0)) { a, p -> a + p }
        val padded = body + ByteArray(body.size % 2)
        return le32(3 + padded.size / 2) + le16(fn) + padded
    }

    /** A placeable WMF: a 1000 × 500 box, 1000 units an inch, then [records] and the end. */
    private fun wmf(vararg records: ByteArray): ByteArray {
        val body = records.fold(ByteArray(0)) { a, r -> a + r } + wmfRecord(0)
        val placeable = le32(0x9AC6CDD7.toInt()) + le16(0) + w(0, 0, 1000, 500) + le16(1000) + le32(0) + le16(0)
        val header = w(1, 9, 0x0300) + le32(9 + body.size / 2) + w(4) + le32(16) + w(0)
        return placeable + header + body
    }

    @Test fun aWmfsShapesObjectsAndText() {
        // 宋体 and 中文 in GBK, the font's character set (134).
        val face = "宋体".toByteArray(charset("GBK")).copyOf(32)
        val words = "中文".toByteArray(charset("GBK"))
        val pic = Metafiles.read(wmf(
            wmfRecord(0x020B, w(0, 0)), wmfRecord(0x020C, w(500, 1000)),      // window (0, 0), 1000 × 500 (y, x)
            wmfRecord(0x02FC, w(0) + le32(ref(0x00FF00)) + w(0)),              // slot 0: a green brush
            wmfRecord(0x012D, w(0)),
            wmfRecord(0x02FA, w(5, 0, 0) + le32(0)),                           // slot 1: a null pen
            wmfRecord(0x012D, w(1)),
            wmfRecord(0x041B, w(300, 400, 100, 100)),                          // a rectangle (bottom, right, top, left)
            wmfRecord(0x01F0, w(0)),                                           // slot 0 freed …
            wmfRecord(0x02FC, w(0) + le32(ref(0x0000FF)) + w(0)),              // … and taken by a blue brush
            wmfRecord(0x012D, w(0)),
            wmfRecord(0x0418, w(450, 900, 350, 600)),                          // an ellipse
            wmfRecord(0x02FB, w(0xFFFF - 99, 0, 0, 0, 400) + byteArrayOf(0, 0, 0, 134.toByte(), 0, 0, 0, 0) + face),   // height -100
            wmfRecord(0x012D, w(2)),
            wmfRecord(0x0521, w(words.size) + words + w(200, 100)),            // TEXTOUT at (100, 200)
        ))!!
        assertEquals(72f, pic.widthPt, 0.01f); assertEquals(36f, pic.heightPt, 0.01f)
        assertEquals(1000f, pic.width); assertEquals(500f, pic.height)
        val paths = pic.items.filterIsInstance<MetafilePath>()
        assertEquals(0xFF00FF00.toInt(), paths[0].fill); assertNull(paths[0].stroke)
        assertEquals(Freeform.Move(100f, 100f), paths[0].ops[0])
        assertEquals(0xFF0000FF.toInt(), paths[1].fill)
        val t = pic.items.filterIsInstance<MetafileText>().single()
        assertEquals("中文", t.text); assertEquals("宋体", t.face); assertEquals(100f, t.size, 0.01f)
        assertEquals(100f, t.x); assertEquals(200f, t.y)
    }

    @Test fun aWmfsSizeAndAPackedDib() {
        val size = ImageSize.of(wmf())!!
        assertEquals(96f, size.first, 0.01f); assertEquals(48f, size.second, 0.01f)
        // A packed DIB: its info (40 bytes, a 2-colour table) then its bits.
        val dib = le32(40) + le32(1) + le32(1) + le16(1) + le16(1) + le32(0) + le32(4) + le32(0) + le32(0) + le32(2) + le32(0) +
            le32(0) + le32(0xFFFFFF) + ByteArray(4)
        assertEquals(48, Dib.infoSize(dib))
        val file = Dib.file(dib)!!
        assertEquals(14 + 48, (file[10].toInt() and 0xFF) or ((file[11].toInt() and 0xFF) shl 8))
    }
}
