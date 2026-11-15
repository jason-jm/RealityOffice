package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.model.ArrowHead
import com.therealityteam.aeropages.model.ArrowHeads
import com.therealityteam.aeropages.model.GradientKind
import com.therealityteam.aeropages.model.LineDash
import com.therealityteam.aeropages.model.LineEnd
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Lines and fills as ODF draws them, from `office:styles`: an outline's dashes as a
 * `draw:stroke-dash`, a line's arrowheads as `draw:marker`s, a two-colour fill as a
 * `draw:gradient`. Markers carry LibreOffice's names for DrawingML's heads ("msArrowEnd 5": the
 * head, then 3 × width + length + 1), so a trip through LibreOffice keeps their kinds and sizes;
 * dashes carry DrawingML's proportions as percentages of the line's width, as LibreOffice reads
 * PowerPoint's presets.
 */
internal object OdfLines {

    /** A marker's name for [end] on a line [strokePt] wide, as shown (spaces, not `_20_`). */
    fun markerName(end: LineEnd, strokePt: Float): String {
        val stem = when (end.head) {
            ArrowHead.ARROW -> "msArrowOpenEnd"
            ArrowHead.STEALTH -> "msArrowStealthEnd"
            ArrowHead.DIAMOND -> "msArrowDiamondEnd"
            ArrowHead.OVAL -> "msArrowOvalEnd"
            else -> "msArrowEnd"
        }
        val index = end.width.coerceIn(0, 2) * 3 + end.length.coerceIn(0, 2) + 1
        // An open arrow's strokes are as wide as the line, so its outline depends on that too (in 1/100 mm).
        return if (end.head == ArrowHead.ARROW) "$stem $index ${Math.round(strokePt * 2540f / 72f)}" else "$stem $index"
    }

    /** An ODF name for a shown one: spaces as `_20_`. */
    fun encoded(name: String): String = name.replace(" ", "_20_")

    /** How wide [end]'s marker is drawn on a line [strokePt] wide: the head's width — an open arrow's strokes reach half a line past it. */
    fun markerWidthPt(end: LineEnd, strokePt: Float): Float {
        val (w, l) = ArrowHeads.size(end, strokePt, ArrowHeads.MIN_STROKE_PT)
        return if (end.head == ArrowHead.ARROW) 2 * openArrowReach(w, l, strokePt) else w
    }

    /** How far out from the line an open arrow's stroked arms reach, its head [w] × [l] on a line [t] wide. */
    private fun openArrowReach(w: Float, l: Float, t: Float): Float = w / 2 + (t / 2) * l / hypot(w / 2, l)

    /** The `draw:marker` for [end] on a line [strokePt] wide, its tip at the top of its box, pointing out of the line. */
    fun markerXml(end: LineEnd, strokePt: Float): String {
        val name = markerName(end, strokePt)
        val (vw, vh, d) = if (end.head == ArrowHead.ARROW) {
            val (w, l) = ArrowHeads.size(end, strokePt, ArrowHeads.MIN_STROKE_PT)
            openArrow(w * 100f, l * 100f, strokePt * 100f)
        } else {
            val vw = 100f * ArrowHeads.factor(end.width); val vh = 100f * ArrowHeads.factor(end.length)
            val a = vw / 2
            Triple(vw, vh, when (end.head) {
                ArrowHead.STEALTH -> path(a, 0f, vw, vh, a, vh * ArrowHeads.STEALTH_NOTCH, 0f, vh)
                ArrowHead.DIAMOND -> path(a, 0f, vw, vh / 2, a, vh, 0f, vh / 2)
                ArrowHead.OVAL -> path(*FloatArray(64) { i ->
                    val t = 2.0 * Math.PI * (i / 2) / 32
                    if (i % 2 == 0) (a + a * cos(t)).toFloat() else (vh / 2 + vh / 2 * sin(t)).toFloat()
                })
                else -> path(a, 0f, vw, vh, 0f, vh)
            })
        }
        return """<draw:marker draw:name="${encoded(name)}" draw:display-name="$name" svg:viewBox="0 0 ${num(vw)} ${num(vh)}" svg:d="$d"/>"""
    }

    /**
     * An open arrow as a marker (markers are filled): its two arms stroked [t] wide along the lines
     * from the tip back to the head's corners ([w] × [l]), mitred at the tip as PowerPoint draws
     * them, and a stem as wide down the middle to the box's foot, where the line meets it. Returns
     * the box's width and height and the outline.
     */
    private fun openArrow(w: Float, l: Float, t: Float): Triple<Float, Float, String> {
        val h = t / 2
        val r = hypot(w / 2, l)
        val sin = (w / 2) / r
        val reach = openArrowReach(w, l, t)
        val outY = l - h * (w / 2) / r               // an arm's outer back corner
        val inX = w / 2 - h * l / r; val inY = l + h * (w / 2) / r
        val tipY = -h / sin                          // the mitre's point
        val innerTip = h / sin
        val pts = ArrayList<Float>()
        fun p(x: Float, y: Float) { pts.add(x + reach); pts.add(y - tipY) }
        p(0f, tipY); p(reach, outY); p(inX, inY)
        if (inX > h) {
            // Where the stem's sides meet the arms' inner edges.
            val y = innerTip + h / inX * (inY - innerTip)
            p(h, y); p(h, inY); p(-h, inY); p(-h, y)
        }
        p(-inX, inY); p(-reach, outY)
        return Triple(2 * reach, inY - tipY, path(*pts.toFloatArray()))
    }

    private fun path(vararg xy: Float): String = buildString {
        for (i in xy.indices step 2) append(if (i == 0) "M" else "L").append(num(xy[i])).append(' ').append(num(xy[i + 1]))
        append('Z')
    }

    private fun num(v: Float): String = String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")

    /** The `draw:stroke-dash` named [name] for [dash]: its dash, the dots after it, the gap — in widths of the line. */
    fun dashXml(dash: LineDash, name: String): String {
        val dots = if (dash.dots > 0) """ draw:dots2="${dash.dots}" draw:dots2-length="100%"""" else ""
        return """<draw:stroke-dash draw:name="$name" draw:style="rect" draw:dots1="1" draw:dots1-length="${pct(dash.dashLength)}"$dots draw:distance="${pct(dash.gap)}"/>"""
    }

    private fun pct(widths: Float): String = "${Math.round(widths * 100)}%"

    /**
     * The `draw:gradient` named [name] from [start] to [end] along [angleDeg] — DrawingML's angle (0
     * left to right, turning clockwise), which ODF measures from top to bottom, anticlockwise.
     */
    fun gradientXml(name: String, start: Long, end: Long, angleDeg: Float, kind: GradientKind = GradientKind.LINEAR, cx: Float = 0.5f, cy: Float = 0.5f): String {
        // The app's first colour is a radial gradient's centre and an axial one's edges; Impress's start
        // colour is a radial one's edge and an axial one's centre line.
        val centred = kind == GradientKind.RADIAL || kind == GradientKind.RECTANGULAR
        val swap = centred || kind == GradientKind.AXIAL
        val from = if (swap) end else start
        val to = if (swap) start else end
        val style = when (kind) { GradientKind.LINEAR -> "linear"; GradientKind.AXIAL -> "axial"; GradientKind.RADIAL -> "radial"; GradientKind.RECTANGULAR -> "rectangular" }
        val centre = if (centred) """draw:cx="${num(cx * 100f)}%" draw:cy="${num(cy * 100f)}%" """ else ""
        return """<draw:gradient draw:name="$name" draw:style="$style" $centre""" +
            """draw:start-color="${OdfPackage.color(from)}" draw:end-color="${OdfPackage.color(to)}" """ +
            """draw:start-intensity="100%" draw:end-intensity="100%" draw:angle="${num(odfAngle(angleDeg))}deg" draw:border="0%"/>"""
    }

    /**
     * A `draw:opacity` gradient: the fill as opaque as [start] at the app's first colour and [end] at its
     * second (0..1), run as [gradientXml] runs the colours.
     */
    fun opacityXml(name: String, start: Float, end: Float, angleDeg: Float, kind: GradientKind = GradientKind.LINEAR, cx: Float = 0.5f, cy: Float = 0.5f): String {
        val centred = kind == GradientKind.RADIAL || kind == GradientKind.RECTANGULAR
        val swap = centred || kind == GradientKind.AXIAL
        val from = if (swap) end else start
        val to = if (swap) start else end
        val style = when (kind) { GradientKind.LINEAR -> "linear"; GradientKind.AXIAL -> "axial"; GradientKind.RADIAL -> "radial"; GradientKind.RECTANGULAR -> "rectangular" }
        val centre = if (centred) """draw:cx="${num(cx * 100f)}%" draw:cy="${num(cy * 100f)}%" """ else ""
        return """<draw:opacity draw:name="$name" draw:style="$style" $centre""" +
            """draw:start="${num(from * 100f)}%" draw:end="${num(to * 100f)}%" draw:angle="${num(odfAngle(angleDeg))}deg" draw:border="0%"/>"""
    }

    /** A shadow's graphic properties (with a leading space; LibreOffice's blur as `loext:shadow-blur`), or "" for none. */
    fun shadowAttrs(s: com.therealityteam.aeropages.model.ShapeShadow?): String {
        s ?: return ""
        return """ draw:shadow="visible" draw:shadow-offset-x="${OdfPackage.pt(s.dxPt)}" draw:shadow-offset-y="${OdfPackage.pt(s.dyPt)}" """ +
            """draw:shadow-color="${OdfPackage.color(s.color)}" draw:shadow-opacity="${Math.round(((s.color ushr 24) and 0xFF) / 2.55f)}%"""" +
            (if (s.blurPt > 0f) """ loext:shadow-blur="${OdfPackage.pt(s.blurPt)}"""" else "")
    }

    /** The app's kind for an ODF gradient style (ellipsoid taken as radial, square as rectangular). */
    fun gradientKind(style: String?): GradientKind = when (style) {
        "axial" -> GradientKind.AXIAL
        "radial", "ellipsoid" -> GradientKind.RADIAL
        "square", "rectangular" -> GradientKind.RECTANGULAR
        else -> GradientKind.LINEAR
    }

    /**
     * A gradient's direction (the app's degrees: 0 left→right, 90 top→bottom) seen in a shape
     * mirrored across ([flipH]) and up and down ([flipV]) — its own inverse. The app mirrors a
     * shape's fill with it, as PowerPoint does; Impress mirrors a custom shape's outline alone.
     */
    fun mirrored(deg: Float, flipH: Boolean, flipV: Boolean): Float {
        var a = deg
        if (flipH) a = 180f - a
        if (flipV) a = -a
        return (a % 360f + 360f) % 360f
    }

    /** DrawingML's gradient angle as ODF's, and back (the mapping is its own inverse). */
    fun odfAngle(deg: Float): Float = ((90f - deg) % 360f + 360f) % 360f

    // --- reading ------------------------------------------------------------------------------

    /** The preset nearest [dash] on a line [strokePt] wide (a hairline taken as ¾ pt). */
    fun dashOf(dash: OdfStyles.StrokeDash, strokePt: Float): LineDash {
        val w = strokePt.coerceAtLeast(0.75f)
        fun widths(v: String?): Float {
            val s = v?.trim() ?: return 1f
            if (s.endsWith("%")) return s.dropLast(1).toFloatOrNull()?.div(100f) ?: 1f
            return OdfValue.lengthPt(s)?.div(w) ?: 1f
        }
        val marks = List(dash.dots1.coerceIn(0, 8)) { widths(dash.dots1Length) } + List(dash.dots2.coerceIn(0, 8)) { widths(dash.dots2Length) }
        return LineDash.nearest(marks, widths(dash.distance))
    }

    private val MS_ARROW = Regex("^msArrow(Open|Stealth|Diamond|Oval)?End (\\d)")

    /**
     * The line end a marker named [name] gives, drawn [widthPt] wide on a line [strokePt] wide:
     * LibreOffice's DrawingML names say kind and sizes outright; its own markers go by kind
     * (Arrow, Arrow concave, Line Arrow, Circle, Square 45 …), sized from how wide they are drawn
     * and their box's proportions.
     */
    fun endOf(name: String, marker: OdfStyles.Marker?, widthPt: Float?, strokePt: Float): LineEnd {
        val shown = (marker?.name ?: name.replace("_20_", " ")).trim()
        MS_ARROW.find(shown)?.let { m ->
            val head = when (m.groupValues[1]) {
                "Open" -> ArrowHead.ARROW
                "Stealth" -> ArrowHead.STEALTH
                "Diamond" -> ArrowHead.DIAMOND
                "Oval" -> ArrowHead.OVAL
                else -> ArrowHead.TRIANGLE
            }
            val n = (m.groupValues[2].toInt() - 1).coerceIn(0, 8)
            return LineEnd(head, n / 3, n % 3)
        }
        val lower = shown.lowercase(Locale.ROOT)
        val head = when {
            "circle" in lower || "oval" in lower || "dimension" in lower -> ArrowHead.OVAL
            "square" in lower || "diamond" in lower -> ArrowHead.DIAMOND
            "concave" in lower || "stealth" in lower -> ArrowHead.STEALTH
            "line arrow" in lower -> ArrowHead.ARROW
            else -> ArrowHead.TRIANGLE
        }
        val w = widthPt ?: return LineEnd(head)
        val width = ArrowHeads.sizeOf(w, strokePt, ArrowHeads.MIN_STROKE_PT)
        val length = if (marker != null && marker.viewW > 0f) ArrowHeads.sizeOf(w * marker.viewH / marker.viewW, strokePt, ArrowHeads.MIN_STROKE_PT) else width
        return LineEnd(head, width, length)
    }
}
