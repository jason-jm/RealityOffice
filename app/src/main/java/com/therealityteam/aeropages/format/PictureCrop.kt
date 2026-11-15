package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.format.odf.OdfPackage
import com.therealityteam.aeropages.format.odf.OdfValue
import com.therealityteam.aeropages.model.Croppable

/** A picture's crop in the files: DrawingML's `a:srcRect` (pptx, docx) and ODF's `fo:clip` (odp, odt). */
object PictureCrop {
    /** `a:srcRect` for [c] — thousandths of a percent cut from each side — or nothing when uncropped. */
    fun srcRect(c: Croppable): String {
        if (!c.cropped) return ""
        fun side(name: String, v: Float) = if (v > 0f) """ $name="${Math.round(v * 100_000f)}"""" else ""
        return "<a:srcRect" + side("l", c.cropL) + side("t", c.cropT) + side("r", c.cropR) + side("b", c.cropB) + "/>"
    }

    /**
     * An `a:srcRect` ([attr] reads its l / t / r / b) onto [c]. Only cutting counts: a negative side
     * (the picture shrunk inside its frame) is taken as none, and a crop leaving almost nothing is ignored.
     */
    fun readSrcRect(attr: (String) -> String?, c: Croppable) {
        fun side(name: String) = ((attr(name)?.toIntOrNull() ?: 0) / 100_000f).coerceIn(0f, Croppable.MAX_CUT)
        val l = side("l"); val t = side("t"); val r = side("r"); val b = side("b")
        if (l + r >= Croppable.MAX_CUT || t + b >= Croppable.MAX_CUT) return
        c.setCrop(l, t, r, b)
    }

    /**
     * `fo:clip="rect(top, right, bottom, left)"` for [c]: lengths of the picture's natural size (its
     * pixels at the resolution [data] declares), or null when uncropped or the size is unknown.
     */
    fun foClip(c: Croppable, data: ByteArray): String? {
        if (!c.cropped) return null
        val (nw, nh) = ImageSize.naturalPt(data) ?: return null
        return "rect(${OdfPackage.pt(c.cropT * nh)}, ${OdfPackage.pt(c.cropR * nw)}, ${OdfPackage.pt(c.cropB * nh)}, ${OdfPackage.pt(c.cropL * nw)})"
    }

    /** An `fo:clip` ([foClip]'s form; `auto` sides cut nothing) onto [c]. */
    fun readFoClip(clip: String?, data: ByteArray, c: Croppable) {
        val inner = clip?.trim()?.removePrefix("rect(")?.removeSuffix(")") ?: return
        val parts = inner.split(',', ' ').filter { it.isNotBlank() }.map { if (it == "auto") 0f else OdfValue.lengthPt(it) ?: return }
        if (parts.size != 4) return
        val (nw, nh) = ImageSize.naturalPt(data) ?: return
        val t = (parts[0] / nh).coerceIn(0f, Croppable.MAX_CUT); val r = (parts[1] / nw).coerceIn(0f, Croppable.MAX_CUT)
        val b = (parts[2] / nh).coerceIn(0f, Croppable.MAX_CUT); val l = (parts[3] / nw).coerceIn(0f, Croppable.MAX_CUT)
        if (l + r >= Croppable.MAX_CUT || t + b >= Croppable.MAX_CUT) return
        c.setCrop(l, t, r, b)
    }
}
