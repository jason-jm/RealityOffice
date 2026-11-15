package com.therealityteam.aeropages.format.pptx

import com.therealityteam.aeropages.format.XmlNode
import com.therealityteam.aeropages.model.LineDash
import com.therealityteam.aeropages.model.LineEnd
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideShape

/**
 * A DrawingML shape as PowerPoint, Excel and Word write one (`p:sp` / `xdr:sp` / `wps:wsp`, a
 * connector's `p:cxnSp` / `xdr:cxnSp`): its preset geometry or custom one (see [DrawingGeometry]), its fill and outline — from `spPr`, else from the shape
 * style's references (`p:style` / `xdr:style`: fillRef, lnRef, fontRef — how Office's own shapes
 * get their accent fill and white text), the outline's dashes and a line's arrowheads, rotation and
 * flips, whether it is a text box, and where its text sits. Where the shape is lies with the caller.
 */
internal object DrawingShape {

    /**
     * An `a:outerShdw` as a shadow: its colour with its `a:alpha`, its distance along its direction
     * (60000ths of a degree, clockwise from the right) as an offset, its blur radius — EMU to points.
     */
    fun shadowOf(n: XmlNode, palette: PptxReader.Palette, placeholder: Long? = null): com.therealityteam.aeropages.model.ShapeShadow {
        // A theme's effect names its colour "phClr": the colour of the shape's effect reference.
        val phClr = n.children.firstOrNull { it.name == "schemeClr" }?.attr("val") == "phClr"
        val rgb = ((if (phClr) placeholder else palette.colorOf(n)) ?: 0xFF000000) and 0xFFFFFF
        val alpha = n.children.firstOrNull { it.name.endsWith("Clr") }?.child("alpha")?.attr("val")?.toFloatOrNull()?.div(100000f) ?: 1f
        val dist = (n.attr("dist")?.toFloatOrNull() ?: 0f) / 12700f
        val dir = Math.toRadians((n.attr("dir")?.toDoubleOrNull() ?: 0.0) / 60000.0)
        return com.therealityteam.aeropages.model.ShapeShadow(((Math.round(alpha.coerceIn(0f, 1f) * 255).toLong()) shl 24) or rgb,
            (dist * kotlin.math.cos(dir)).toFloat(), (dist * kotlin.math.sin(dir)).toFloat(), (n.attr("blurRad")?.toFloatOrNull() ?: 0f) / 12700f)
    }

    /** Preset geometries that are lines. */
    private val LINES = setOf("line", "straightConnector1", "bentConnector2", "bentConnector3", "curvedConnector3")

    /**
     * The presets the app draws as its own shapes, each with the adjust values it draws them with
     * (other values, and the preset is drawn from its definition): roundRect's corner, triangle's
     * apex, rightArrow's shaft and head; rect, ellipse and star5 have none that change the app's look.
     */
    private val OWN_PRESETS: Map<String, Regex> = mapOf(
        "rect" to Regex(".*"), "ellipse" to Regex(".*"), "star5" to Regex(".*"),
        "roundRect" to Regex("adj=val 16667"), "triangle" to Regex("adj=val 50000"),
        "rightArrow" to Regex("adj1=val 50000\\|adj2=val 50000|adj2=val 50000\\|adj1=val 50000"),
    )

    /** An inch in EMU: the size a shape without one is worked out at. */
    private const val EMU_INCH = 914400.0

    /** Office's theme line widths (lnStyleLst) by lnRef index, in points. */
    private val STYLE_LINE_PT = listOf(0f, 0.5f, 1f, 1.5f)

    /** [sp] as a shape; [text] makes its box from its text body (the caller's rich text reader). */
    fun read(sp: XmlNode, palette: PptxReader.Palette, text: (XmlNode?) -> SlideBox): SlideShape {
        val connector = sp.name == "cxnSp"
        val textBox = sp.descendants("cNvSpPr").firstOrNull()?.attr("txBox") == "1"
        val spPr = sp.child("spPr")
        val prst = spPr?.child("prstGeom")?.attr("prst")
        // A custom geometry: its paths, worked out at the shape's size (what its guides' w and h are);
        // a preset the app has no drawing of (a hexagon, a chevron, a flowchart shape …), or one of its
        // own with other adjust values, worked out from the preset's definition.
        val ext = spPr?.child("xfrm")?.child("ext")
        val cx = ext?.attr("cx")?.toDoubleOrNull() ?: EMU_INCH
        val cy = ext?.attr("cy")?.toDoubleOrNull() ?: EMU_INCH
        val avLst = spPr?.child("prstGeom")?.child("avLst")
        val adjust = PresetGeometries.adjustOf(avLst)
        val fromPreset = prst != null && !connector && prst !in LINES && (prst !in OWN_PRESETS || adjust != null && !OWN_PRESETS.getValue(prst).matches(adjust)) &&
            PresetGeometries.has(prst)
        val path = spPr?.child("custGeom")?.let { DrawingGeometry.read(it, cx, cy) }
            ?: if (fromPreset) PresetGeometries.freeform(prst!!, avLst, cx, cy) else null
        val type = when {
            connector || prst in LINES -> ShapeType.LINE
            path != null -> ShapeType.FREEFORM
            prst == "roundRect" -> ShapeType.ROUNDED
            prst == "ellipse" -> ShapeType.ELLIPSE
            prst == "triangle" -> ShapeType.TRIANGLE
            prst == "star5" -> ShapeType.STAR
            prst == "rightArrow" -> ShapeType.ARROW
            else -> if (textBox) ShapeType.TEXTBOX else ShapeType.RECT
        }
        val sh = SlideShape(type, text(sp.child("txBody")))
        sh.path = path
        if (fromPreset && path != null) { sh.preset = prst; sh.presetAdjust = adjust }
        val style = sp.child("style")
        fun ref(name: String): Pair<Int, Long?>? = style?.child(name)?.let { r -> (r.attr("idx")?.toIntOrNull() ?: 0) to palette.colorOf(r) }
        // Fill: spPr's own (none, solid, a gradient's first and last stops), else the style's fillRef.
        sh.fill = when {
            type == ShapeType.LINE -> null
            spPr?.child("noFill") != null -> null
            spPr?.child("solidFill") != null -> palette.fillColorOf(spPr.child("solidFill"))
            spPr?.child("gradFill") != null -> null
            else -> ref("fillRef")?.let { (idx, color) -> if (idx == 0) null else color }
        }
        spPr?.child("gradFill")?.let { grad ->
            val stops = grad.child("gsLst")?.children("gs")?.sortedBy { it.attr("pos")?.toIntOrNull() ?: 0 } ?: emptyList()
            val first = stops.firstOrNull()?.let { palette.fillColorOf(it) }
            val last = stops.lastOrNull()?.let { palette.fillColorOf(it) }
            if (first != null) { sh.fill = first; sh.fillGradient = last ?: first }
            grad.child("lin")?.attr("ang")?.toFloatOrNull()?.let { sh.gradientAngle = it / 60000f }
            val path = grad.child("path")
            if (path != null) {
                // A path gradient: its first stop at the centre, its last at the edge; the centre is its fill-to rectangle's.
                sh.gradientKind = if (path.attr("path") == "rect") com.therealityteam.aeropages.model.GradientKind.RECTANGULAR else com.therealityteam.aeropages.model.GradientKind.RADIAL
                path.child("fillToRect")?.let { r ->
                    fun v(k: String) = (r.attr(k)?.toFloatOrNull() ?: 0f) / 100000f
                    sh.gradientCx = ((v("l") + 1f - v("r")) / 2f).coerceIn(0f, 1f)
                    sh.gradientCy = ((v("t") + 1f - v("b")) / 2f).coerceIn(0f, 1f)
                }
            } else if (stops.size == 3 && first != null && first == last && (stops[1].attr("pos")?.toIntOrNull() ?: 0) in 40000..60000) {
                // One colour at both ends and another in the middle: an axial gradient (as Impress's axial goes to pptx).
                sh.gradientKind = com.therealityteam.aeropages.model.GradientKind.AXIAL
                sh.fillGradient = palette.fillColorOf(stops[1]) ?: first
            }
        }
        // Outline: spPr's a:ln (noFill: none), else the style's lnRef; its width, else the theme's.
        val ln = spPr?.child("ln")
        val lnRef = ref("lnRef")
        sh.border = when {
            ln?.child("noFill") != null -> null
            ln?.child("solidFill") != null -> palette.fillColorOf(ln.child("solidFill"))
            lnRef != null -> if (lnRef.first == 0) null else lnRef.second
            else -> ln?.descendants("srgbClr")?.firstNotNullOfOrNull { n -> n.attr("val")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000L or it } }
        }
        val width = ln?.attr("w")?.toFloatOrNull()?.div(12700f) ?: lnRef?.first?.let { STYLE_LINE_PT.getOrNull(it) }
        width?.let { if (it > 0f) sh.borderWidthPt = it.coerceIn(0.25f, 24f) }
        // Dashes: a preset, or a custom pattern (each a:ds a dash and the space after it, in
        // thousandths of a percent of the line's width) as the preset nearest it.
        ln?.child("prstDash")?.attr("val")?.let { sh.dash = LineDash.of(it) }
        ln?.child("custDash")?.children("ds")?.takeIf { it.isNotEmpty() }?.let { ds ->
            fun share(v: String?) = (v?.toFloatOrNull() ?: 0f) / 100000f
            sh.dash = LineDash.nearest(ds.map { share(it.attr("d")) }, ds.map { share(it.attr("sp")) }.average().toFloat())
        }
        // A line's (or a freeform's) arrowheads: a:headEnd at its first point, a:tailEnd at its last.
        if (type == ShapeType.LINE || type == ShapeType.FREEFORM) {
            ln?.child("headEnd")?.let { sh.beginArrow = LineEnd.of(it.attr("type"), it.attr("w"), it.attr("len")) }
            ln?.child("tailEnd")?.let { sh.endArrow = LineEnd.of(it.attr("type"), it.attr("w"), it.attr("len")) }
        }
        // An outer shadow: in the shape's own effect list, else in the theme's effect style its style
        // references (idx 1-3; 0 is none) — how PowerPoint 2007's shapes got their soft shadow.
        val effects = spPr?.child("effectLst")
        if (effects != null) effects.child("outerShdw")?.let { sh.shadow = shadowOf(it, palette) }
        else style?.child("effectRef")?.let { r ->
            val idx = r.attr("idx")?.toIntOrNull() ?: 0
            palette.effects.getOrNull(idx - 1)?.child("effectLst")?.child("outerShdw")?.let { sh.shadow = shadowOf(it, palette, palette.colorOf(r)) }
        }
        val xfrm = spPr?.child("xfrm")
        xfrm?.attr("rot")?.toFloatOrNull()?.let { sh.rotationDeg = it / 60000f }
        sh.flipH = xfrm?.attr("flipH").let { it == "1" || it == "true" }
        sh.flipV = xfrm?.attr("flipV").let { it == "1" || it == "true" }
        // Text anchor: explicit bodyPr@anchor; a text box without one is top-anchored per the spec,
        // a drawn shape keeps its centred look.
        // (Word's shapes keep their body properties beside the text, not in it.)
        sh.anchor = when ((sp.child("txBody")?.child("bodyPr") ?: sp.child("bodyPr"))?.attr("anchor")) {
            "t" -> ShapeAnchor.TOP
            "ctr" -> ShapeAnchor.CENTER
            "b" -> ShapeAnchor.BOTTOM
            else -> if (textBox) ShapeAnchor.TOP else ShapeAnchor.CENTER
        }
        sh.noWrap = (sp.child("txBody")?.child("bodyPr") ?: sp.child("bodyPr"))?.attr("wrap") == "none"
        // The style's fontRef colours the text its runs leave uncoloured.
        ref("fontRef")?.second?.let { c -> sh.box.paragraph.runs.forEach { if (it.format.color == null) it.format.color = c } }
        return sh
    }
}
