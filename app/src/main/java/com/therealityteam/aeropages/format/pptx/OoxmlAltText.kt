package com.therealityteam.aeropages.format.pptx

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.XmlNode

/**
 * A drawing's alternative text as Office keeps it on its non-visual properties (`p:cNvPr`, `xdr:cNvPr`, `wp:docPr`):
 * the `descr` attribute, and Office 2019's decorative mark in their extension list.
 */
internal object OoxmlAltText {
    const val DECORATIVE_NS = "http://schemas.microsoft.com/office/drawing/2017/decorative"
    private const val DECORATIVE_EXT = "{C183D7F6-B498-43B3-948B-1728B52AA6E4}"

    /** The `descr` and decorative mark of a non-visual properties element (null: neither). */
    fun read(nv: XmlNode?): Pair<String, Boolean> {
        if (nv == null) return "" to false
        val decorative = nv.descendants("decorative").firstOrNull()?.attr("val").let { it == "1" || it == "true" }
        return nv.attr("descr").orEmpty() to decorative
    }

    /** ` descr="…"` for [alt] (nothing when empty), to go in the element's start tag. */
    fun descr(alt: String): String = if (alt.isEmpty()) "" else """ descr="${OfficeXml.escape(alt)}""""

    /** The extension list marking the drawing decorative ([prefix] is DrawingML's main namespace prefix there), or nothing. */
    fun decorativeExt(decorative: Boolean, prefix: String = "a"): String = if (!decorative) "" else
        """<$prefix:extLst><$prefix:ext uri="$DECORATIVE_EXT"><adec:decorative xmlns:adec="$DECORATIVE_NS" val="1"/></$prefix:ext></$prefix:extLst>"""
}
