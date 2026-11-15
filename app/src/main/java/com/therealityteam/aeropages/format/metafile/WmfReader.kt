package com.therealityteam.aeropages.format.metafile

import kotlin.math.abs

/**
 * A Windows metafile ([MS-WMF]) read as the drawing its records do. Its 16-bit coordinates are in the
 * window its records set, mapped onto the box its placeable header gives (in so many units an inch);
 * without that header they are drawn as they are and the window is the picture's frame. Each record
 * is played on [Gdi] — shapes, lines, arcs, polygons, text (in its font's character set), bitmaps,
 * the objects (each taking the lowest free slot), the window, the clip and the saved states.
 */
internal object WmfReader {

    private const val PLACEABLE = 0x9AC6CDD7.toInt()

    fun isWmf(b: ByteArray): Boolean {
        if (b.size >= 40 && u32(b, 0) == PLACEABLE) return true
        return b.size >= 18 && (u16(b, 0) == 1 || u16(b, 0) == 2) && u16(b, 2) == 9 && (u16(b, 4) == 0x0100 || u16(b, 4) == 0x0300)
    }

    /** The picture's size in pixels at 96 dpi (its placeable header's box), or null. */
    fun sizePx(b: ByteArray): Pair<Float, Float>? {
        if (b.size < 22 || u32(b, 0) != PLACEABLE) return null
        val inch = u16(b, 14).takeIf { it > 0 } ?: 1440
        val w = abs(i16(b, 10) - i16(b, 6)) / inch.toFloat() * 96f
        val h = abs(i16(b, 12) - i16(b, 8)) / inch.toFloat() * 96f
        return if (w > 0f && h > 0f) w to h else null
    }

    /** The window the records set last (the frame of a picture without a placeable header). */
    private class Window { var ox = 0f; var oy = 0f; var ex = 0f; var ey = 0f }

    fun read(b: ByteArray): MetafilePicture? {
        if (!isWmf(b)) return null
        var o = 0
        var box: FloatArray? = null
        var inch = 1440
        if (u32(b, 0) == PLACEABLE) {
            box = floatArrayOf(i16(b, 6).toFloat(), i16(b, 8).toFloat(), i16(b, 10).toFloat(), i16(b, 12).toFloat())
            inch = u16(b, 14).takeIf { it > 0 } ?: 1440
            o = 22
        }
        if (o + 18 > b.size) return null
        o += u16(b, o + 2).coerceAtLeast(9) * 2
        val g = Gdi(wmf = true)
        g.s.mapMode = Gdi.MM_ANISOTROPIC
        val placeable = box != null
        if (box != null) {
            val w = box[2] - box[0]; val h = box[3] - box[1]
            if (w == 0f || h == 0f) return null
            g.setWindowOrg(box[0], box[1]); g.setWindowExt(w, h)
            g.setViewportOrg(box[0], box[1]); g.setViewportExt(w, h)
        }
        g.defaultTextHeight = inch / 6f
        val window = Window()
        while (o + 6 <= b.size) {
            val words = u32(b, o).toLong() and 0xFFFFFFFFL
            val fn = u16(b, o + 4)
            if (fn == 0 || words < 3) break
            val size = (words * 2).coerceAtMost((b.size - o).toLong()).toInt()
            runCatching { record(g, b, o, fn, size, placeable, window) }
            o += size
        }
        val (l, t, w, h) = when {
            box != null -> listOf(box[0], box[1], box[2] - box[0], box[3] - box[1])
            window.ex != 0f && window.ey != 0f -> listOf(window.ox, window.oy, window.ex, window.ey)
            else -> bounds(g.items)?.let { r -> listOf(r[0], r[1], r[2] - r[0], r[3] - r[1]) } ?: return null
        }
        if (w == 0f || h == 0f) return null
        // Without a placeable header its units' size is not said: taken as a screen's pixels.
        val perInch = if (placeable) inch.toFloat() else 96f
        return MetafilePicture(l, t, w, h, abs(w) / perInch * 72f, abs(h) / perInch * 72f, g.items)
    }

    private fun record(g: Gdi, b: ByteArray, o: Int, fn: Int, size: Int, placeable: Boolean, window: Window) {
        val p = o + 6
        val end = o + size
        fun w(k: Int) = i16(b, p + k * 2).toFloat()
        fun uw(k: Int) = u16(b, p + k * 2)
        // The k-th word from the record's end (a blit's destination, whatever came before it).
        fun tail(k: Int) = i16(b, end - k * 2).toFloat()
        fun windowSet() {
            window.ox = g.s.wox; window.oy = g.s.woy; window.ex = g.s.wex; window.ey = g.s.wey
            // Without a box of its own to map onto, the window is drawn as it is.
            if (!placeable) { g.setViewportOrg(g.s.wox, g.s.woy); g.setViewportExt(g.s.wex, g.s.wey) }
        }
        when (fn) {
            0x020B -> { g.setWindowOrg(w(1), w(0)); windowSet() }
            0x020C -> { g.setWindowExt(w(1), w(0)); windowSet() }
            0x020F -> { g.setWindowOrg(g.s.wox + w(1), g.s.woy + w(0)); windowSet() }
            0x0410 -> if (w(0) != 0f && w(2) != 0f) { g.setWindowExt(g.s.wex * w(3) / w(2), g.s.wey * w(1) / w(0)); windowSet() }
            0x0201 -> g.s.bkColor = u32(b, p)
            0x0209 -> g.s.textColor = u32(b, p)
            0x0102 -> g.s.bkMode = uw(0)
            0x0106 -> g.s.fillMode = uw(0)
            0x012E -> g.s.textAlign = uw(0)
            0x0214 -> g.moveTo(w(1), w(0))
            0x0213 -> g.lineTo(w(1), w(0))
            0x041B -> g.rectangle(w(3), w(2), w(1), w(0))
            0x0418 -> g.ellipse(w(3), w(2), w(1), w(0))
            0x061C -> g.roundRect(w(5), w(4), w(3), w(2), w(1), w(0))
            0x0817, 0x081A, 0x0830 -> g.arc(when (fn) { 0x0817 -> Gdi.ARC; 0x081A -> Gdi.PIE; else -> Gdi.CHORD },
                w(7), w(6), w(5), w(4), w(3), w(2), w(1), w(0))
            0x0324 -> g.polygon(points(b, p + 2, uw(0), end))
            0x0325 -> g.polyline(points(b, p + 2, uw(0), end))
            0x0538 -> {
                val n = uw(0)
                var q = p + 2 + n * 2
                g.polyPolygon((0 until n).map { k -> points(b, q, uw(1 + k), end).also { q += it.size * 2 } })
            }
            0x0521 -> {
                // TEXTOUT: the string (padded to a word), then where.
                val len = uw(0)
                val at = p + 2
                val after = at + len + (len and 1)
                g.text(i16(b, after + 2).toFloat(), i16(b, after).toFloat(), g.decode(b, at, len), null, 0, null)
            }
            0x0A32 -> {
                // EXTTEXTOUT: where, the length, the options, a rectangle when they say, the string, its advances.
                val len = uw(2); val opts = uw(3)
                var q = p + 8
                val rect = if (opts and 6 != 0) floatArrayOf(i16(b, q).toFloat(), i16(b, q + 2).toFloat(), i16(b, q + 4).toFloat(), i16(b, q + 6).toFloat()).also { q += 8 } else null
                val text = g.decode(b, q, len)
                q += len + (len and 1)
                val dx = if (q + len * 2 <= end && len > 0) FloatArray(len) { k -> i16(b, q + k * 2).toFloat() } else null
                // Double-byte text has one advance a byte: a character's is the sum of its bytes'.
                val advances = dx?.let { d -> if (text.length == len) d else charAdvances(b, p + 8 + (if (rect != null) 8 else 0), len, text, d) }
                g.text(w(1), w(0), text, advances, opts, rect)
            }
            0x02FA -> {
                val width = w(1)
                g.add(Gdi.Pen(uw(0), width, u32(b, p + 6), geometric = width > 1f))
            }
            0x02FC -> g.add(Gdi.Brush(uw(0), u32(b, p + 2)))
            0x02FB -> {
                val charset = b[p + 13].toInt() and 0xFF
                var n = 0
                while (n < 32 && p + 18 + n < end && b[p + 18 + n].toInt() != 0) n++
                val faceBytes = b.copyOfRange(p + 18, p + 18 + n)
                val face = if (faceBytes.any { it < 0 }) String(faceBytes, runCatching { java.nio.charset.Charset.forName("GBK") }.getOrDefault(Charsets.ISO_8859_1))
                    else String(faceBytes, Charsets.ISO_8859_1)
                g.add(Gdi.Font(w(0), i16(b, p + 4), i16(b, p + 8), b[p + 10].toInt() != 0, b[p + 11].toInt() != 0, b[p + 12].toInt() != 0, face.trim().ifEmpty { null }, charset))
            }
            0x00F7, 0x06FF, 0x01F9 -> g.add(Gdi.Other)                       // a palette, a region, an old pattern brush
            0x0142 -> g.add(Gdi.Brush(3, 0x808080))                         // a DIB pattern brush: grey
            0x012D -> g.select(uw(0))
            0x01F0 -> g.delete(uw(0))
            0x001E -> g.save()
            0x0127 -> g.restore(w(0).toInt())
            0x0416 -> g.intersectClip(w(3), w(2), w(1), w(0))
            0x012C -> g.clipTo(null, intersect = false)                     // a region we cannot follow: no clip
            0x0B41 -> {
                // DIBSTRETCHBLT: its raster operation, the source's part, the destination, the bitmap (none: a fill).
                val rop = u32(b, p)
                if (size <= 28) { g.patBlt(tail(1), tail(2), tail(3), tail(4), rop); return }
                if (!drawn(rop)) return
                blit(g, b, p + 20, end, w(9), w(8), w(7), w(6), w(5).toInt(), w(4).toInt(), w(3).toInt(), w(2).toInt(), fromFoot = false)
            }
            0x0F43 -> {
                // STRETCHDIB: the bitmap's own origin, its foot for a bottom-up one.
                val rop = u32(b, p)
                if (!drawn(rop)) return
                blit(g, b, p + 22, end, w(10), w(9), w(8), w(7), w(6).toInt(), w(5).toInt(), w(4).toInt(), w(3).toInt(), fromFoot = true)
            }
            0x0940 -> {
                // DIBBITBLT: as large as it is.
                val rop = u32(b, p)
                if (size <= 24) { g.patBlt(tail(1), tail(2), tail(3), tail(4), rop); return }
                if (!drawn(rop)) return
                blit(g, b, p + 16, end, w(7), w(6), w(5), w(4), w(3).toInt(), w(2).toInt(), w(5).toInt(), w(4).toInt(), fromFoot = false)
            }
        }
    }

    /** Raster operations that draw their source; masks and inversions are left out. */
    private fun drawn(rop: Int) = rop != 0x008800C6 && rop != 0x00660046 && rop != 0x00550009 && rop != 0x00BB0226

    private fun blit(g: Gdi, b: ByteArray, at: Int, end: Int, x: Float, y: Float, w: Float, h: Float, xSrc: Int, ySrc: Int, cxSrc: Int, cySrc: Int, fromFoot: Boolean) {
        if (at + 40 > end) return
        val dib = b.copyOfRange(at, end)
        val infoSize = Dib.infoSize(dib).coerceAtMost(dib.size)
        val file = Dib.file(dib) ?: return
        g.image(file, x, y, w, h, Dib.source(dib.copyOfRange(0, infoSize), xSrc, ySrc, cxSrc, cySrc, fromFoot))
    }

    /** POINTs (x, y, 16 bits each), as many as said and the record holds. */
    private fun points(b: ByteArray, at: Int, n: Int, end: Int): FloatArray {
        val count = n.coerceAtMost((end - at) / 4).coerceAtLeast(0)
        return FloatArray(count * 2) { k -> i16(b, at + k * 2).toFloat() }
    }

    /** One advance a character from one a byte: a double-byte character's two summed. */
    private fun charAdvances(b: ByteArray, at: Int, len: Int, text: String, dx: FloatArray): FloatArray {
        val out = FloatArray(text.length)
        var k = 0
        var i = 0
        while (i < len && k < out.size) {
            val lead = b[at + i].toInt() and 0xFF
            val wide = lead >= 0x81 && i + 1 < len
            out[k++] = dx[i] + (if (wide) dx[i + 1] else 0f)
            i += if (wide) 2 else 1
        }
        return out
    }

    /** The box everything drawn covers (left, top, right, bottom), or null. */
    private fun bounds(items: List<MetafileItem>): FloatArray? {
        var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY; var r = Float.NEGATIVE_INFINITY; var bt = Float.NEGATIVE_INFINITY
        fun take(x: Float, y: Float) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y }
        for (item in items) when (item) {
            is MetafilePath -> com.therealityteam.aeropages.model.Freeform(listOf(com.therealityteam.aeropages.model.Freeform.Part(item.ops))).bounds()?.let { take(it[0], it[1]); take(it[2], it[3]) }
            is MetafileImage -> { take(item.x0, item.y0); take(item.x1, item.y1) }
            is MetafileText -> take(item.x, item.y)
        }
        return if (l < r && t < bt) floatArrayOf(l, t, r, bt) else null
    }
}
