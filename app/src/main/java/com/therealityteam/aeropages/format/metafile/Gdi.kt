package com.therealityteam.aeropages.format.metafile

import com.therealityteam.aeropages.model.Freeform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Windows GDI as a metafile plays it ([MS-EMF], [MS-WMF]): the drawing state its records change —
 * the objects (pens, brushes, fonts) and which are selected, the text and background colours and
 * modes, the mapping (world transform, window, viewport), the current position, the clip, a path
 * being recorded, the saved states — and the drawing, each call becoming the [MetafileItem]s it
 * draws, in the picture's (the reference device's) units. Coordinates come in logical units.
 */
internal class Gdi(private val wmf: Boolean) {

    val items = ArrayList<MetafileItem>()

    // --- objects ---------------------------------------------------------------------------------

    sealed interface Obj
    /** A pen: its style (PS_… with its ends, joins and whether it is geometric), its width in logical units (0: one device pixel), its colour (COLORREF). */
    class Pen(val style: Int, val width: Float, val color: Int, val geometric: Boolean = false) : Obj
    /** A brush: its style (BS_…) and colour (COLORREF). */
    class Brush(val style: Int, val color: Int) : Obj
    /**
     * A font: its height in logical units (negative: the characters' own height; positive: the cell's),
     * turn (tenths of a degree), weight, look, face and character set (what its 8-bit text is in).
     */
    class Font(val height: Float, val escapement: Int, val weight: Int, val italic: Boolean, val underline: Boolean, val strike: Boolean, val face: String?,
               val charset: Int = 0) : Obj
    /** A palette, a region, a pattern brush's bitmap …: a slot taken, nothing drawn with it. */
    object Other : Obj

    private val objects = HashMap<Int, Obj>()

    /** EMF: an object at the index its record gives. */
    fun put(index: Int, obj: Obj) { objects[index] = obj }

    /** WMF: an object in the lowest free slot of the table. */
    fun add(obj: Obj) {
        var i = 0
        while (objects.containsKey(i)) i++
        objects[i] = obj
    }

    fun delete(index: Int) { objects.remove(index) }

    fun select(index: Int) {
        val obj = if (!wmf && index and STOCK != 0) stock(index and STOCK.inv()) else objects[index]
        when (obj) {
            is Pen -> s.pen = obj
            is Brush -> s.brush = obj
            is Font -> s.font = obj
            else -> Unit
        }
    }

    private fun stock(n: Int): Obj? = when (n) {
        0 -> WHITE_BRUSH
        1 -> Brush(BS_SOLID, 0xC0C0C0)
        2 -> Brush(BS_SOLID, 0x808080)
        3 -> Brush(BS_SOLID, 0x404040)
        4 -> Brush(BS_SOLID, 0)
        5 -> Brush(BS_NULL, 0)
        6 -> Pen(PS_SOLID, 0f, 0xFFFFFF)
        7 -> BLACK_PEN
        8 -> Pen(PS_NULL, 0f, 0)
        in 10..17 -> null.also { s.font = null }     // the system fonts: the default text
        else -> null
    }

    // --- state -----------------------------------------------------------------------------------

    class State(
        var pen: Pen = BLACK_PEN, var brush: Brush = WHITE_BRUSH, var font: Font? = null,
        var textColor: Int = 0, var bkColor: Int = 0xFFFFFF, var bkMode: Int = OPAQUE, var textAlign: Int = 0, var fillMode: Int = ALTERNATE,
        var mapMode: Int = MM_TEXT,
        var wox: Float = 0f, var woy: Float = 0f, var wex: Float = 1f, var wey: Float = 1f,
        var vox: Float = 0f, var voy: Float = 0f, var vex: Float = 1f, var vey: Float = 1f,
        /** The world transform: x' = m11 x + m21 y + dx, y' = m12 x + m22 y + dy, as [m11, m12, m21, m22, dx, dy]. */
        var world: FloatArray = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f),
        var curX: Float = 0f, var curY: Float = 0f, var clip: FloatArray? = null, var clockwise: Boolean = false,
    ) {
        fun copy() = State(pen, brush, font, textColor, bkColor, bkMode, textAlign, fillMode, mapMode, wox, woy, wex, wey, vox, voy, vex, vey,
            world.copyOf(), curX, curY, clip?.copyOf(), clockwise)
    }

    var s = State()
    private val saved = ArrayList<State>()

    /** EMF: the reference device's units a millimetre (for the fixed mapping modes). */
    var unitsPerMm = 1f

    /** The height text without a font is drawn at, in the picture's units. */
    var defaultTextHeight = 12f

    fun save() { saved += s.copy() }

    /** Back to a saved state: [n] below 0, that many back; above, that one (counting from 1). */
    fun restore(n: Int) {
        val index = if (n < 0) saved.size + n else n - 1
        if (index < 0 || index >= saved.size) return
        s = saved[index]
        while (saved.size > index) saved.removeAt(saved.lastIndex)
    }

    // --- mapping ---------------------------------------------------------------------------------

    /** The window and viewport: x = ax · x' + bx, y = ay · y' + by, as [ax, bx, ay, by]. */
    private fun viewport(): FloatArray = when (s.mapMode) {
        MM_TEXT -> floatArrayOf(1f, s.vox - s.wox, 1f, s.voy - s.woy)
        MM_ISOTROPIC, MM_ANISOTROPIC -> {
            var sx = if (s.wex != 0f) s.vex / s.wex else 1f
            var sy = if (s.wey != 0f) s.vey / s.wey else 1f
            if (s.mapMode == MM_ISOTROPIC) { val k = min(abs(sx), abs(sy)); sx = k * sign(sx); sy = k * sign(sy) }
            floatArrayOf(sx, s.vox - s.wox * sx, sy, s.voy - s.woy * sy)
        }
        else -> {
            // A fixed unit, y up: so many millimetres a unit, the device's units a millimetre.
            val k = (MM_PER_UNIT[s.mapMode] ?: 1f) * unitsPerMm
            floatArrayOf(k, s.vox - s.wox * k, -k, s.voy + s.woy * k)
        }
    }

    /** Logical units to the picture's: [a, b, c, d, e, f] with x = a x + c y + e, y = b x + d y + f. */
    fun matrix(): FloatArray {
        val v = viewport(); val w = s.world
        return floatArrayOf(v[0] * w[0], v[2] * w[1], v[0] * w[2], v[2] * w[3], v[0] * w[4] + v[1], v[2] * w[5] + v[3])
    }

    private fun mx(m: FloatArray, x: Float, y: Float) = m[0] * x + m[2] * y + m[4]
    private fun my(m: FloatArray, x: Float, y: Float) = m[1] * x + m[3] * y + m[5]

    private fun mapped(ops: List<Freeform.Op>, m: FloatArray): List<Freeform.Op> = ops.map { op ->
        when (op) {
            is Freeform.Move -> Freeform.Move(mx(m, op.x, op.y), my(m, op.x, op.y))
            is Freeform.Line -> Freeform.Line(mx(m, op.x, op.y), my(m, op.x, op.y))
            is Freeform.Cubic -> Freeform.Cubic(mx(m, op.x1, op.y1), my(m, op.x1, op.y1), mx(m, op.x2, op.y2), my(m, op.x2, op.y2), mx(m, op.x, op.y), my(m, op.x, op.y))
            Freeform.Close -> Freeform.Close
        }
    }

    fun setWindowOrg(x: Float, y: Float) { s.wox = x; s.woy = y }
    fun setWindowExt(x: Float, y: Float) { if (x != 0f && y != 0f) { s.wex = x; s.wey = y } }
    fun setViewportOrg(x: Float, y: Float) { s.vox = x; s.voy = y }
    fun setViewportExt(x: Float, y: Float) { if (x != 0f && y != 0f) { s.vex = x; s.vey = y } }

    fun setWorld(m: FloatArray) { s.world = m.copyOf() }

    /** MWT_IDENTITY (1), MWT_LEFTMULTIPLY (2: [m] first), MWT_RIGHTMULTIPLY (3: [m] after), MWT_SET (4). */
    fun modifyWorld(m: FloatArray, mode: Int) {
        s.world = when (mode) {
            1 -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
            2 -> times(m, s.world)
            3 -> times(s.world, m)
            4 -> m.copyOf()
            else -> s.world
        }
    }

    /** [a] then [b] (row vectors: a × b). */
    private fun times(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
        a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5],
    )

    // --- clip ------------------------------------------------------------------------------------

    fun intersectClip(l: Float, t: Float, r: Float, b: Float) {
        val m = matrix()
        val x0 = mx(m, l, t); val y0 = my(m, l, t); val x1 = mx(m, r, b); val y1 = my(m, r, b)
        val rect = floatArrayOf(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))
        s.clip = s.clip?.let { c -> floatArrayOf(max(c[0], rect[0]), max(c[1], rect[1]), min(c[2], rect[2]), min(c[3], rect[3])) } ?: rect
    }

    /** A clip in the picture's units already (a region's bounds), or none. */
    fun clipTo(rect: FloatArray?, intersect: Boolean) {
        s.clip = if (rect == null) null else if (intersect) s.clip?.let { c -> floatArrayOf(max(c[0], rect[0]), max(c[1], rect[1]), min(c[2], rect[2]), min(c[3], rect[3])) } ?: rect else rect
    }

    // --- paths -----------------------------------------------------------------------------------

    private var path: ArrayList<Freeform.Op>? = null
    private var recording = false

    fun beginPath() { path = ArrayList(); recording = true }
    fun endPath() { recording = false }
    fun abortPath() { path = null; recording = false }
    fun closeFigure() { path?.let { if (it.isNotEmpty()) it += Freeform.Close } }
    fun fillPath() { path?.let { emit(it, fill = true, stroke = false) }; path = null; recording = false }
    fun strokePath() { path?.let { emit(it, fill = false, stroke = true) }; path = null; recording = false }
    fun strokeAndFillPath() { path?.let { emit(it, fill = true, stroke = true) }; path = null; recording = false }

    /** The path's bounds as the clip (with what [mode] says: RGN_AND 1, RGN_COPY 5), the path done. */
    fun selectClipPath(mode: Int) {
        val b = path?.let { Freeform(listOf(Freeform.Part(it))).bounds() }
        path = null; recording = false
        if (b != null) clipTo(b, intersect = mode == 1)
    }

    /** [ops] (logical) into the path being recorded, or drawn. */
    private fun draw(ops: List<Freeform.Op>, fill: Boolean, stroke: Boolean) {
        val d = mapped(ops, matrix())
        if (recording) path?.addAll(d) else emit(d, fill, stroke)
    }

    // --- drawing ---------------------------------------------------------------------------------

    fun moveTo(x: Float, y: Float) {
        s.curX = x; s.curY = y
        if (recording) path?.add(mapped(listOf(Freeform.Move(x, y)), matrix())[0])
    }

    fun lineTo(x: Float, y: Float) {
        if (recording) path?.add(mapped(listOf(Freeform.Line(x, y)), matrix())[0])
        else emit(mapped(listOf(Freeform.Move(s.curX, s.curY), Freeform.Line(x, y)), matrix()), fill = false, stroke = true)
        s.curX = x; s.curY = y
    }

    /** Points as x, y pairs: a closed, filled figure. */
    fun polygon(pts: FloatArray) { if (pts.size >= 4) draw(figure(pts, close = true), fill = true, stroke = true) }

    /** An open figure, outlined. */
    fun polyline(pts: FloatArray) { if (pts.size >= 4) draw(figure(pts, close = false), fill = false, stroke = true) }

    /** Closed figures filled as one (a figure inside another a hole by the fill mode). */
    fun polyPolygon(polys: List<FloatArray>) { draw(polys.filter { it.size >= 4 }.flatMap { figure(it, close = true) }, fill = true, stroke = true) }

    fun polyPolyline(polys: List<FloatArray>) { draw(polys.filter { it.size >= 4 }.flatMap { figure(it, close = false) }, fill = false, stroke = true) }

    /** Lines on from the current position. */
    fun polylineTo(pts: FloatArray) {
        if (pts.size < 2) return
        val ops = ArrayList<Freeform.Op>()
        if (!recording) ops += Freeform.Move(s.curX, s.curY)
        for (i in 0 until pts.size / 2) ops += Freeform.Line(pts[i * 2], pts[i * 2 + 1])
        draw(ops, fill = false, stroke = true)
        s.curX = pts[pts.size - 2]; s.curY = pts[pts.size - 1]
    }

    /** Béziers: the first point, then three a curve. */
    fun polyBezier(pts: FloatArray) {
        if (pts.size < 8) return
        val ops = ArrayList<Freeform.Op>()
        ops += Freeform.Move(pts[0], pts[1])
        cubics(pts, 2, ops)
        draw(ops, fill = false, stroke = true)
    }

    /** Béziers on from the current position: three points a curve. */
    fun polyBezierTo(pts: FloatArray) {
        if (pts.size < 6) return
        val ops = ArrayList<Freeform.Op>()
        if (!recording) ops += Freeform.Move(s.curX, s.curY)
        cubics(pts, 0, ops)
        draw(ops, fill = false, stroke = true)
        val last = (pts.size / 6) * 6
        s.curX = pts[last - 2]; s.curY = pts[last - 1]
    }

    private fun cubics(pts: FloatArray, from: Int, out: MutableList<Freeform.Op>) {
        var i = from
        while (i + 6 <= pts.size) {
            out += Freeform.Cubic(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], pts[i + 4], pts[i + 5])
            i += 6
        }
    }

    private fun figure(pts: FloatArray, close: Boolean): List<Freeform.Op> {
        val ops = ArrayList<Freeform.Op>(pts.size / 2 + 2)
        ops += Freeform.Move(pts[0], pts[1])
        for (i in 1 until pts.size / 2) ops += Freeform.Line(pts[i * 2], pts[i * 2 + 1])
        if (close) ops += Freeform.Close
        return ops
    }

    fun rectangle(l: Float, t: Float, r: Float, b: Float) {
        draw(listOf(Freeform.Move(l, t), Freeform.Line(r, t), Freeform.Line(r, b), Freeform.Line(l, b), Freeform.Close), fill = true, stroke = true)
    }

    /** A rectangle with round corners, each a quarter of an ellipse [w] × [h]. */
    fun roundRect(l0: Float, t0: Float, r0: Float, b0: Float, w: Float, h: Float) {
        val l = min(l0, r0); val r = max(l0, r0); val t = min(t0, b0); val b = max(t0, b0)
        val rx = (abs(w) / 2f).coerceAtMost((r - l) / 2f).toDouble(); val ry = (abs(h) / 2f).coerceAtMost((b - t) / 2f).toDouble()
        if (rx <= 0.0 || ry <= 0.0) { rectangle(l, t, r, b); return }
        val ops = ArrayList<Freeform.Op>()
        ops += Freeform.Move((l + rx).toFloat(), t)
        ops += Freeform.Line((r - rx).toFloat(), t)
        Freeform.arc(r - rx, t + ry, rx, ry, 0.0, -PI / 2, PI / 2, ops)
        ops += Freeform.Line(r, (b - ry).toFloat())
        Freeform.arc(r - rx, b - ry, rx, ry, 0.0, 0.0, PI / 2, ops)
        ops += Freeform.Line((l + rx).toFloat(), b)
        Freeform.arc(l + rx, b - ry, rx, ry, 0.0, PI / 2, PI / 2, ops)
        ops += Freeform.Line(l, (t + ry).toFloat())
        Freeform.arc(l + rx, t + ry, rx, ry, 0.0, PI, PI / 2, ops)
        ops += Freeform.Close
        draw(ops, fill = true, stroke = true)
    }

    fun ellipse(l: Float, t: Float, r: Float, b: Float) {
        val cx = (l + r) / 2.0; val cy = (t + b) / 2.0; val rx = abs(r - l) / 2.0; val ry = abs(b - t) / 2.0
        if (rx <= 0.0 || ry <= 0.0) return
        val ops = ArrayList<Freeform.Op>()
        ops += Freeform.Move((cx + rx).toFloat(), cy.toFloat())
        Freeform.arc(cx, cy, rx, ry, 0.0, 0.0, 2 * PI, ops)
        ops += Freeform.Close
        draw(ops, fill = true, stroke = true)
    }

    /**
     * An arc of the ellipse in the box, from where the ray to the first point meets it to where the ray
     * to the second does — anticlockwise as it shows unless the direction says clockwise — as an arc
     * ([ARC]), closed by its chord ([CHORD]) or by the centre ([PIE]), or on from the current position ([ARC_TO]).
     */
    fun arc(kind: Int, l0: Float, t0: Float, r0: Float, b0: Float, xs: Float, ys: Float, xe: Float, ye: Float) {
        val l = min(l0, r0); val r = max(l0, r0); val t = min(t0, b0); val b = max(t0, b0)
        val cx = (l + r) / 2.0; val cy = (t + b) / 2.0; val rx = (r - l) / 2.0; val ry = (b - t) / 2.0
        if (rx <= 0.0 || ry <= 0.0) return
        val a0 = atan2((ys - cy) / ry, (xs - cx) / rx)
        val a1 = atan2((ye - cy) / ry, (xe - cx) / rx)
        // Anticlockwise as it shows: against the angles of logical space unless the mapping mirrors it.
        val m = matrix()
        val mirrored = m[0] * m[3] - m[1] * m[2] < 0
        val positive = s.clockwise != mirrored
        var sweep = a1 - a0
        if (positive) { while (sweep <= 1e-9) sweep += 2 * PI; while (sweep > 2 * PI + 1e-9) sweep -= 2 * PI }
        else { while (sweep >= -1e-9) sweep -= 2 * PI; while (sweep < -2 * PI - 1e-9) sweep += 2 * PI }
        val sx = (cx + rx * kotlin.math.cos(a0)).toFloat(); val sy = (cy + ry * kotlin.math.sin(a0)).toFloat()
        val ops = ArrayList<Freeform.Op>()
        when (kind) {
            PIE -> { ops += Freeform.Move(cx.toFloat(), cy.toFloat()); ops += Freeform.Line(sx, sy) }
            ARC_TO -> { if (!recording) ops += Freeform.Move(s.curX, s.curY); ops += Freeform.Line(sx, sy) }
            else -> ops += Freeform.Move(sx, sy)
        }
        Freeform.arc(cx, cy, rx, ry, 0.0, a0, sweep, ops)
        if (kind == PIE || kind == CHORD) ops += Freeform.Close
        draw(ops, fill = kind == PIE || kind == CHORD, stroke = true)
        if (kind == ARC_TO) { s.curX = (cx + rx * kotlin.math.cos(a0 + sweep)).toFloat(); s.curY = (cy + ry * kotlin.math.sin(a0 + sweep)).toFloat() }
    }

    /** A box filled with [color] (ARGB), no outline: a background, a pattern copy. */
    fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int) {
        if (l == r || t == b) return
        val d = mapped(listOf(Freeform.Move(l, t), Freeform.Line(r, t), Freeform.Line(r, b), Freeform.Line(l, b), Freeform.Close), matrix())
        items += MetafilePath(d, color, true, null, 0f, null, 0, 0, s.clip?.copyOf())
    }

    /** A bit-block transfer with no source: its box filled as its raster operation says (the brush, black, white). */
    fun patBlt(l: Float, t: Float, w: Float, h: Float, rop: Int) {
        val color = when (rop) {
            BLACKNESS -> 0xFF000000.toInt()
            WHITENESS -> 0xFFFFFFFF.toInt()
            PATCOPY -> s.brush.takeIf { it.style == BS_SOLID }?.let { argb(it.color) } ?: return
            else -> return
        }
        fillRect(l, t, l + w, t + h, color)
    }

    /** A picture's bytes stretched over a box (logical), the part [src] of it shown. */
    fun image(data: ByteArray, x: Float, y: Float, w: Float, h: Float, src: IntArray?) {
        val m = matrix()
        items += MetafileImage(data, mx(m, x, y), my(m, x, y), mx(m, x + w, y + h), my(m, x + w, y + h), src, s.clip?.copyOf())
    }

    /**
     * Text at ([x], [y]) (or the current position, as the alignment may say) in the selected font and the
     * text colour; [dx]: each character's advance (logical); [options] ETO_OPAQUE fills [rect] with the
     * background colour first.
     */
    fun text(x: Float, y: Float, str: String, dx: FloatArray?, options: Int, rect: FloatArray?) {
        if (options and ETO_OPAQUE != 0 && rect != null && rect.size == 4) fillRect(rect[0], rect[1], rect[2], rect[3], argb(s.bkColor))
        if (str.isEmpty()) return
        val m = matrix()
        val atCurrent = s.textAlign and TA_UPDATECP != 0
        val lx = if (atCurrent) s.curX else x
        val ly = if (atCurrent) s.curY else y
        val f = s.font
        val hScale = hypot(m[0], m[1]); val vScale = hypot(m[2], m[3])
        val h = f?.height ?: 0f
        // A negative height is the characters' own; a positive one the cell's, its internal leading in it.
        val size = if (h == 0f) defaultTextHeight else (if (h < 0f) -h else h * 0.85f) * vScale
        val turned = -Math.toDegrees(atan2(m[1].toDouble(), m[0].toDouble())).toFloat()
        val angle = (f?.escapement ?: 0) / 10f + turned
        val align = when (s.textAlign and 6) { 6 -> 1; 2 -> 2; else -> 0 }
        val vAlign = when (s.textAlign and 24) { 24 -> 1; 8 -> 2; else -> 0 }
        val advances = dx?.let { a -> FloatArray(a.size) { a[it] * hScale } }
        val background = if (s.bkMode == OPAQUE && options and ETO_OPAQUE == 0) argb(s.bkColor) else null
        items += MetafileText(mx(m, lx, ly), my(m, lx, ly), str, f?.face, size, (f?.weight ?: 400) >= 600, f?.italic == true, f?.underline == true, f?.strike == true,
            argb(s.textColor), angle, align, vAlign, advances, background, s.clip?.copyOf())
        if (atCurrent) {
            val width = advances?.sum() ?: (str.length * size * 0.55f)
            if (hScale > 0f) s.curX = lx + width / hScale
        }
    }

    /**
     * 8-bit text as the selected font's character set has it: a Chinese, Japanese or Korean set's double
     * bytes, a Windows code page's single ones (a font of the default set with a Chinese face: GBK).
     */
    fun decode(b: ByteArray, at: Int, length: Int): String {
        val f = s.font
        val face = f?.face.orEmpty()
        val chineseFace = face.any { it.code in 0x2E80..0x9FFF } ||
            listOf("SimSun", "SimHei", "KaiTi", "FangSong", "YaHei", "NSimSun").any { face.contains(it, ignoreCase = true) }
        val name = when (f?.charset ?: 0) {
            128 -> "Shift_JIS"; 129 -> "x-windows-949"; 134 -> "GBK"; 136 -> "Big5"
            161 -> "windows-1253"; 162 -> "windows-1254"; 163 -> "windows-1258"; 177 -> "windows-1255"; 178 -> "windows-1256"
            186 -> "windows-1257"; 204 -> "windows-1251"; 222 -> "x-windows-874"; 238 -> "windows-1250"
            2 -> "ISO-8859-1"
            else -> if (chineseFace) "GBK" else "windows-1252"
        }
        val cs = runCatching { java.nio.charset.Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)
        return String(b, at, length.coerceAtMost(b.size - at).coerceAtLeast(0), cs).substringBefore('\u0000')
    }

    // --- emitting --------------------------------------------------------------------------------

    private fun emit(ops: List<Freeform.Op>, fill: Boolean, stroke: Boolean) {
        if (ops.none { it is Freeform.Line || it is Freeform.Cubic }) return
        val b = s.brush
        val fillColor = if (!fill) null else when (b.style) {
            BS_SOLID -> argb(b.color)
            BS_NULL -> null
            BS_HATCHED -> argb(b.color, 0x59)            // its lines' colour, as thin as the hatching's share
            else -> 0xFFBFBFBF.toInt()                     // a pattern: grey
        }
        val pen = s.pen
        val style = pen.style and 0x0F
        val strokeColor = if (!stroke || style == PS_NULL) null else argb(pen.color)
        if (fillColor == null && strokeColor == null) return
        val m = matrix()
        // (A width of 0 — a cosmetic pen's — is a hairline, one output pixel however large the picture shows.)
        val width = if (pen.width <= 0f) 0f else pen.width * sqrt(abs(m[0] * m[3] - m[1] * m[2]))
        val dash = when (style) {
            PS_DASH -> floatArrayOf(3f, 1f)
            PS_DOT, PS_ALTERNATE -> floatArrayOf(1f, 1f)
            PS_DASHDOT -> floatArrayOf(3f, 1f, 1f, 1f)
            PS_DASHDOTDOT -> floatArrayOf(3f, 1f, 1f, 1f, 1f, 1f)
            else -> null
        }
        val cap = when (pen.style and 0x0F00) { 0x0100 -> 1; 0x0200 -> 2; else -> 0 }
        val join = when (pen.style and 0xF000) { 0x1000 -> 1; 0x2000 -> 2; else -> 0 }
        items += MetafilePath(ops, fillColor, s.fillMode == ALTERNATE, strokeColor, width, dash, cap, join, s.clip?.copyOf())
    }

    companion object {
        const val STOCK = 0x80000000.toInt()

        const val PS_SOLID = 0; const val PS_DASH = 1; const val PS_DOT = 2; const val PS_DASHDOT = 3; const val PS_DASHDOTDOT = 4
        const val PS_NULL = 5; const val PS_ALTERNATE = 8; const val PS_GEOMETRIC = 0x10000
        const val BS_SOLID = 0; const val BS_NULL = 1; const val BS_HATCHED = 2
        const val OPAQUE = 2; const val ALTERNATE = 1
        const val MM_TEXT = 1; const val MM_ISOTROPIC = 7; const val MM_ANISOTROPIC = 8
        const val TA_UPDATECP = 1
        const val ETO_OPAQUE = 2
        const val ARC = 0; const val CHORD = 1; const val PIE = 2; const val ARC_TO = 3
        const val BLACKNESS = 0x00000042; const val WHITENESS = 0x00FF0062; const val PATCOPY = 0x00F00021

        /** Millimetres a logical unit in the fixed mapping modes (LOMETRIC, HIMETRIC, LOENGLISH, HIENGLISH, TWIPS). */
        val MM_PER_UNIT = mapOf(2 to 0.1f, 3 to 0.01f, 4 to 0.254f, 5 to 0.0254f, 6 to 25.4f / 1440f)

        val BLACK_PEN = Pen(PS_SOLID, 0f, 0)
        val WHITE_BRUSH = Brush(BS_SOLID, 0xFFFFFF)

        /** A COLORREF (0x00BBGGRR; a palette index or relative colour taken as its RGB) as ARGB. */
        fun argb(colorRef: Int, alpha: Int = 0xFF): Int =
            (alpha shl 24) or ((colorRef and 0xFF) shl 16) or (colorRef and 0xFF00) or ((colorRef shr 16) and 0xFF)
    }
}
