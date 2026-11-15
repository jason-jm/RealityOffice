package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.OfficeXml

/**
 * A frame's alternative text in ODF: `svg:desc` (else `svg:title`) after its content, and LibreOffice's decorative mark
 * (`loext:decorative`, on the frame or in its graphic style).
 */
internal object OdfAltText {
    fun read(frame: OdfNode, look: OdfStyles.GraphicStyle?): Pair<String, Boolean> =
        (frame.child("desc")?.text ?: frame.child("title")?.text).orEmpty().trim() to
            (frame.attr("decorative") == "true" || look?.decorative == true)

    /** The `svg:desc` for [alt] (nothing when empty): it follows the frame's image, before its event listeners. */
    fun desc(alt: String): String = if (alt.isEmpty()) "" else "<svg:desc>${OfficeXml.escape(alt)}</svg:desc>"

    /** LibreOffice's decorative mark, for a frame's graphic properties. */
    fun decorativeProp(decorative: Boolean): String = if (decorative) """ loext:decorative="true"""" else ""
}
