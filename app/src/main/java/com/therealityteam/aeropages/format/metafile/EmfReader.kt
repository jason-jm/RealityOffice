package com.therealityteam.aeropages.format.metafile

/**
 * An enhanced metafile ([MS-EMF]) read as the drawing its records do: its header's frame (the
 * picture's bounds, in hundredths of a millimetre) and reference device (its pixels a millimetre),
 * then each record played on [Gdi] — the shapes, lines, Béziers, arcs and paths; the pens, brushes
 * and fonts; text; bitmaps; the mapping (map mode, window, viewport, world transform); the clip and
 * the saved states. An EMF+ comment's own records are passed over (Office writes the same drawing
 * in plain records beside them); a record that does not read is skipped.
 */
internal object EmfReader {

    fun isEmf(b: ByteArray): Boolean =
        b.size >= 88 && u32(b, 0) == 1 && b[40] == ' '.code.toByte() && b[41] == 'E'.code.toByte() && b[42] == 'M'.code.toByte() && b[43] == 'F'.code.toByte()

    /** The picture's size in pixels at 96 dpi (its frame's), or null. */
    fun sizePx(b: ByteArray): Pair<Float, Float>? {
        if (!isEmf(b)) return null
        val w = (i32(b, 32) - i32(b, 24)) / 100f / 25.4f * 96f
        val h = (i32(b, 36) - i32(b, 28)) / 100f / 25.4f * 96f
        return if (w > 0f && h > 0f) w to h else null
    }

    fun read(b: ByteArray): MetafilePicture? {
        if (!isEmf(b)) return null
        val headerSize = u32(b, 4)
        val devW = i32(b, 72); val devH = i32(b, 76)
        var mmW = i32(b, 80).toFloat(); var mmH = i32(b, 84).toFloat()
        // A header that carries micrometres (version 2's extension) is the finer measure.
        if (headerSize >= 108 && b.size >= 108) {
            val umW = i32(b, 100); val umH = i32(b, 104)
            if (umW > 0 && umH > 0) { mmW = umW / 1000f; mmH = umH / 1000f }
        }
        val perMmX = if (devW > 0 && mmW > 0f) devW / mmW else 96f / 25.4f
        val perMmY = if (devH > 0 && mmH > 0f) devH / mmH else 96f / 25.4f
        val g = Gdi(wmf = false)
        g.unitsPerMm = (perMmX + perMmY) / 2f
        g.defaultTextHeight = 12f / 72f * 25.4f * perMmY
        // What it shows: its frame in the device's units, else its bounds.
        val fl = i32(b, 24); val ft = i32(b, 28); val fr = i32(b, 32); val fb = i32(b, 36)
        var left: Float; var top: Float; var width: Float; var height: Float
        if (fr > fl && fb > ft) {
            left = fl / 100f * perMmX; top = ft / 100f * perMmY
            width = (fr - fl) / 100f * perMmX; height = (fb - ft) / 100f * perMmY
        } else {
            left = i32(b, 8).toFloat(); top = i32(b, 12).toFloat()
            width = (i32(b, 16) - i32(b, 8) + 1).toFloat(); height = (i32(b, 20) - i32(b, 12) + 1).toFloat()
        }
        if (width <= 0f || height <= 0f) return null
        val widthPt = width / perMmX / 25.4f * 72f
        val heightPt = height / perMmY / 25.4f * 72f
        var o = 0
        while (o + 8 <= b.size) {
            val type = u32(b, o); val size = u32(b, o + 4)
            if (size < 8 || o + size > b.size) break
            runCatching { record(g, b, o, type, size) }
            if (type == EMR_EOF) break
            o += size
        }
        return MetafilePicture(left, top, width, height, widthPt, heightPt, g.items)
    }

    private const val EMR_EOF = 14

    private fun record(g: Gdi, b: ByteArray, o: Int, type: Int, size: Int) {
        val p = o + 8
        val end = o + size
        fun i(at: Int) = i32(b, at).toFloat()
        when (type) {
            2 -> g.polyBezier(points32(b, p + 16, end))
            3 -> g.polygon(points32(b, p + 16, end))
            4 -> g.polyline(points32(b, p + 16, end))
            5 -> g.polyBezierTo(points32(b, p + 16, end))
            6 -> g.polylineTo(points32(b, p + 16, end))
            7 -> g.polyPolyline(polys(b, p + 16, end, short = false))
            8 -> g.polyPolygon(polys(b, p + 16, end, short = false))
            9 -> g.setWindowExt(i(p), i(p + 4))
            10 -> g.setWindowOrg(i(p), i(p + 4))
            11 -> g.setViewportExt(i(p), i(p + 4))
            12 -> g.setViewportOrg(i(p), i(p + 4))
            17 -> g.s.mapMode = u32(b, p)
            18 -> g.s.bkMode = u32(b, p)
            19 -> g.s.fillMode = u32(b, p)
            22 -> g.s.textAlign = u32(b, p)
            24 -> g.s.textColor = u32(b, p)
            25 -> g.s.bkColor = u32(b, p)
            27 -> g.moveTo(i(p), i(p + 4))
            30 -> g.intersectClip(i(p), i(p + 4), i(p + 8), i(p + 12))
            31 -> scale(i(p), i(p + 4), i(p + 8), i(p + 12)) { sx, sy -> g.setViewportExt(g.s.vex * sx, g.s.vey * sy) }
            32 -> scale(i(p), i(p + 4), i(p + 8), i(p + 12)) { sx, sy -> g.setWindowExt(g.s.wex * sx, g.s.wey * sy) }
            33 -> g.save()
            34 -> g.restore(i32(b, p))
            35 -> g.setWorld(xform(b, p))
            36 -> g.modifyWorld(xform(b, p), u32(b, p + 24))
            37 -> g.select(u32(b, p))
            38 -> {
                // LOGPEN: its style, its width (a POINTL's x), its colour.
                val width = i32(b, p + 8)
                g.put(u32(b, p), Gdi.Pen(u32(b, p + 4), width.toFloat(), u32(b, p + 16), geometric = width > 1))
            }
            39 -> g.put(u32(b, p), Gdi.Brush(u32(b, p + 4), u32(b, p + 8)))
            40 -> g.delete(u32(b, p))
            42 -> g.ellipse(i(p), i(p + 4), i(p + 8), i(p + 12))
            43 -> g.rectangle(i(p), i(p + 4), i(p + 8), i(p + 12))
            44 -> g.roundRect(i(p), i(p + 4), i(p + 8), i(p + 12), i(p + 16), i(p + 20))
            45, 46, 47, 55 -> g.arc(when (type) { 45 -> Gdi.ARC; 46 -> Gdi.CHORD; 47 -> Gdi.PIE; else -> Gdi.ARC_TO },
                i(p), i(p + 4), i(p + 8), i(p + 12), i(p + 16), i(p + 20), i(p + 24), i(p + 28))
            49 -> g.put(u32(b, p), Gdi.Other)                                    // a palette
            54 -> g.lineTo(i(p), i(p + 4))
            57 -> g.s.clockwise = u32(b, p) == 2
            59 -> g.beginPath()
            60 -> g.endPath()
            61 -> g.closeFigure()
            62 -> g.fillPath()
            63 -> g.strokeAndFillPath()
            64 -> g.strokePath()
            67 -> g.selectClipPath(u32(b, p))
            68 -> g.abortPath()
            75 -> {
                // A clip region (device units): its bounds, or (none given, a copy) no clip.
                val cb = u32(b, p); val mode = u32(b, p + 4)
                if (cb < 32) { if (mode == 5) g.clipTo(null, intersect = false) }
                else {
                    val r = p + 8 + 16
                    g.clipTo(floatArrayOf(i(r), i(r + 4), i(r + 8), i(r + 12)), intersect = mode == 1)
                }
            }
            76, 77, 114, 116 -> {
                // BITBLT, STRETCHBLT, ALPHABLEND, TRANSPARENTBLT: a source bitmap over the box, or (none) a fill.
                val x = i(p + 16); val y = i(p + 20); val w = i(p + 24); val h = i(p + 28)
                val rop = u32(b, p + 32)
                val offBmi = u32(b, p + 76); val cbBmi = u32(b, p + 80); val offBits = u32(b, p + 84); val cbBits = u32(b, p + 88)
                if (cbBmi == 0 || type == 76 && cbBits == 0) { g.patBlt(x, y, w, h, rop); return }
                if (!drawn(rop)) return
                val cxSrc = if (type == 76) w.toInt() else i32(b, p + 92); val cySrc = if (type == 76) h.toInt() else i32(b, p + 96)
                image(g, b, o + offBmi, cbBmi, o + offBits, cbBits, x, y, w, h, i32(b, p + 36), i32(b, p + 40), cxSrc, cySrc, end, fromFoot = false)
            }
            80 -> {
                // SETDIBITSTODEVICE: the bitmap at its own size.
                val cx = i32(b, p + 32); val cy = i32(b, p + 36)
                image(g, b, o + u32(b, p + 40), u32(b, p + 44), o + u32(b, p + 48), u32(b, p + 52), i(p + 16), i(p + 20), cx.toFloat(), cy.toFloat(),
                    i32(b, p + 24), i32(b, p + 28), cx, cy, end, fromFoot = true)
            }
            81 -> {
                // STRETCHDIBITS
                val rop = u32(b, p + 60)
                if (!drawn(rop)) return
                image(g, b, o + u32(b, p + 40), u32(b, p + 44), o + u32(b, p + 48), u32(b, p + 52), i(p + 16), i(p + 20), i(p + 64), i(p + 68),
                    i32(b, p + 24), i32(b, p + 28), i32(b, p + 32), i32(b, p + 36), end, fromFoot = true)
            }
            82 -> {
                // EXTCREATEFONTINDIRECTW: a LOGFONTW.
                val f = p + 4
                val face = String(b, f + 28, 64.coerceAtMost(end - (f + 28)).coerceAtLeast(0), Charsets.UTF_16LE).substringBefore('\u0000').trim()
                g.put(u32(b, p), Gdi.Font(i(f), i32(b, f + 8), i32(b, f + 16), b[f + 20].toInt() != 0, b[f + 21].toInt() != 0, b[f + 22].toInt() != 0, face.ifEmpty { null },
                    b[f + 23].toInt() and 0xFF))
            }
            83, 84 -> {
                // EXTTEXTOUTA / W: an EMRTEXT after the bounds and the graphics mode and scales.
                val t = p + 28
                val n = u32(b, t + 8)
                val offString = u32(b, t + 12); val options = u32(b, t + 16)
                val rect = floatArrayOf(i(t + 20), i(t + 24), i(t + 28), i(t + 32))
                val offDx = u32(b, t + 36)
                if (n < 0 || n > 100000) return
                val text = if (type == 84) String(b, o + offString, (n * 2).coerceAtMost(end - (o + offString)), Charsets.UTF_16LE)
                    else g.decode(b, o + offString, n.coerceAtMost(end - (o + offString)))
                val pairs = options and 0x2000 != 0                               // ETO_PDY: an x and a y each
                val dx = if (offDx > 0 && o + offDx + n * 4 <= end) FloatArray(n) { k -> i32(b, o + offDx + k * (if (pairs) 8 else 4)).toFloat() } else null
                g.text(i(t), i(t + 4), text, dx, options, rect)
            }
            85 -> g.polyBezier(points16(b, p + 16, end))
            86 -> g.polygon(points16(b, p + 16, end))
            87 -> g.polyline(points16(b, p + 16, end))
            88 -> g.polyBezierTo(points16(b, p + 16, end))
            89 -> g.polylineTo(points16(b, p + 16, end))
            90 -> g.polyPolyline(polys(b, p + 16, end, short = true))
            91 -> g.polyPolygon(polys(b, p + 16, end, short = true))
            93, 94 -> g.put(u32(b, p), Gdi.Brush(3, 0x808080))                     // a pattern brush: grey
            95 -> {
                // EXTCREATEPEN: a LOGPEN_EX after the bitmap's offsets; a cosmetic pen is a hairline.
                val style = u32(b, p + 20); val width = u32(b, p + 24); val brushStyle = u32(b, p + 28)
                val geometric = style and Gdi.PS_GEOMETRIC != 0
                g.put(u32(b, p), Gdi.Pen(if (brushStyle == Gdi.BS_NULL) Gdi.PS_NULL else style, if (geometric) width.toFloat() else 0f, u32(b, p + 32), geometric))
            }
            118 -> gradientFill(g, b, p, end)
        }
    }

    /** Raster operations that draw their source (copying, painting, merging it); masks and inversions are left out. */
    private fun drawn(rop: Int) = rop != 0x008800C6 && rop != 0x00660046 && rop != 0x00550009 && rop != 0x00BB0226

    private fun image(g: Gdi, b: ByteArray, bmiAt: Int, cbBmi: Int, bitsAt: Int, cbBits: Int, x: Float, y: Float, w: Float, h: Float,
                      xSrc: Int, ySrc: Int, cxSrc: Int, cySrc: Int, end: Int, fromFoot: Boolean) {
        if (bmiAt < 0 || bitsAt < 0 || bmiAt + cbBmi > end || bitsAt + cbBits > end || cbBmi < 12) return
        val bmi = b.copyOfRange(bmiAt, bmiAt + cbBmi)
        val file = Dib.file(bmi, b.copyOfRange(bitsAt, bitsAt + cbBits)) ?: return
        g.image(file, x, y, w, h, Dib.source(bmi, xSrc, ySrc, cxSrc, cySrc, fromFoot))
    }

    /** GRADIENTFILL's rectangles, each filled with its corners' colours' average (its triangles likewise). */
    private fun gradientFill(g: Gdi, b: ByteArray, p: Int, end: Int) {
        val nVer = u32(b, p + 16); val nTri = u32(b, p + 20); val mode = u32(b, p + 24)
        val v = p + 28
        if (nVer <= 0 || v + nVer * 16 > end) return
        fun x(k: Int) = i32(b, v + k * 16).toFloat()
        fun y(k: Int) = i32(b, v + k * 16 + 4).toFloat()
        fun channel(k: Int, c: Int) = u16(b, v + k * 16 + 8 + c * 2) ushr 8
        fun mix(ks: List<Int>): Int {
            val r = ks.sumOf { channel(it, 0) } / ks.size; val gg = ks.sumOf { channel(it, 1) } / ks.size; val bl = ks.sumOf { channel(it, 2) } / ks.size
            return (0xFF shl 24) or (r shl 16) or (gg shl 8) or bl
        }
        val idx = v + nVer * 16
        for (k in 0 until nTri) {
            if (mode == 2) {
                val at = idx + k * 12
                if (at + 12 > end) return
                val a = u32(b, at); val c = u32(b, at + 4); val d = u32(b, at + 8)
                if (listOf(a, c, d).any { it !in 0 until nVer }) continue
                val saved = g.s.brush; val pen = g.s.pen
                g.s.brush = Gdi.Brush(Gdi.BS_SOLID, argbToRef(mix(listOf(a, c, d)))); g.s.pen = Gdi.Pen(Gdi.PS_NULL, 0f, 0)
                g.polygon(floatArrayOf(x(a), y(a), x(c), y(c), x(d), y(d)))
                g.s.brush = saved; g.s.pen = pen
            } else {
                val at = idx + k * 8
                if (at + 8 > end) return
                val a = u32(b, at); val c = u32(b, at + 4)
                if (a !in 0 until nVer || c !in 0 until nVer) continue
                g.fillRect(x(a), y(a), x(c), y(c), mix(listOf(a, c)))
            }
        }
    }

    private fun argbToRef(c: Int) = ((c and 0xFF) shl 16) or (c and 0xFF00) or ((c shr 16) and 0xFF)

    private inline fun scale(xNum: Float, xDen: Float, yNum: Float, yDen: Float, set: (Float, Float) -> Unit) {
        if (xDen != 0f && yDen != 0f) set(xNum / xDen, yNum / yDen)
    }

    private fun xform(b: ByteArray, at: Int) = FloatArray(6) { java.lang.Float.intBitsToFloat(i32(b, at + it * 4)) }

    /** A count then that many POINTLs. */
    private fun points32(b: ByteArray, at: Int, end: Int): FloatArray {
        val n = u32(b, at).coerceAtMost((end - at - 4) / 8).coerceAtLeast(0)
        return FloatArray(n * 2) { k -> i32(b, at + 4 + k * 4).toFloat() }
    }

    /** A count then that many POINTSs. */
    private fun points16(b: ByteArray, at: Int, end: Int): FloatArray {
        val n = u32(b, at).coerceAtMost((end - at - 4) / 4).coerceAtLeast(0)
        return FloatArray(n * 2) { k -> i16(b, at + 4 + k * 2).toFloat() }
    }

    /** POLYPOLY…: the figures' count, all their points' count, each one's count, then the points. */
    private fun polys(b: ByteArray, at: Int, end: Int, short: Boolean): List<FloatArray> {
        val nPolys = u32(b, at)
        if (nPolys <= 0 || nPolys > 100000) return emptyList()
        val counts = IntArray(nPolys) { u32(b, at + 8 + it * 4) }
        var q = at + 8 + nPolys * 4
        val size = if (short) 4 else 8
        return counts.map { n ->
            val count = n.coerceAtLeast(0).coerceAtMost((end - q) / size)
            val pts = FloatArray(count * 2) { k -> if (short) i16(b, q + k * 2).toFloat() else i32(b, q + k * 4).toFloat() }
            q += count * size
            pts
        }
    }
}

internal fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
internal fun i16(b: ByteArray, o: Int) = u16(b, o).toShort().toInt()
internal fun u32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
internal fun i32(b: ByteArray, o: Int) = u32(b, o)
