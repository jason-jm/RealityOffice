package com.therealityteam.aeropages.format.docx

import com.therealityteam.aeropages.format.XmlNode
import com.therealityteam.aeropages.model.FloatAlign
import com.therealityteam.aeropages.model.FloatFrame
import com.therealityteam.aeropages.model.FloatPosition
import com.therealityteam.aeropages.model.FloatWrap

/**
 * Word's floating drawings (`wp:anchor`, ECMA-376 20.4.2.3): where they stand — `wp:positionH` /
 * `wp:positionV`, each an offset from or an alignment in the frame `relativeFrom` names — and how the
 * text goes by them (the wrap element; `behindDoc` puts one wrapped by nothing behind the text).
 */
internal object DrawingAnchors {
    const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"

    /** A frame across, by `relativeFrom` (a character's place counts as its column's). */
    fun hFrame(rel: String?): FloatFrame = when (rel) {
        "page" -> FloatFrame.PAGE
        "margin" -> FloatFrame.MARGIN
        "leftMargin", "insideMargin" -> FloatFrame.LEFT_MARGIN
        "rightMargin", "outsideMargin" -> FloatFrame.RIGHT_MARGIN
        else -> FloatFrame.COLUMN
    }

    /** A frame down, by `relativeFrom` (a line's place counts as its paragraph's). */
    fun vFrame(rel: String?): FloatFrame = when (rel) {
        "page" -> FloatFrame.PAGE
        "margin" -> FloatFrame.MARGIN
        "topMargin", "insideMargin" -> FloatFrame.TOP_MARGIN
        "bottomMargin", "outsideMargin" -> FloatFrame.BOTTOM_MARGIN
        else -> FloatFrame.PARAGRAPH
    }

    fun alignOf(v: String?): FloatAlign? = when (v?.trim()) {
        "left", "top", "inside" -> FloatAlign.START
        "center" -> FloatAlign.CENTER
        "right", "bottom", "outside" -> FloatAlign.END
        else -> null
    }

    /** `wp:positionH` ([across]) or `wp:positionV` into [pos]: its frame, and its offset (EMU) or alignment. */
    fun readPosition(node: XmlNode, pos: FloatPosition, across: Boolean) {
        val rel = node.attr("relativeFrom")
        val align = alignOf(node.child("align")?.text)
        val offset = node.child("posOffset")?.text?.trim()?.toLongOrNull()?.let { Ooxml.emuToPt(it) } ?: 0f
        if (across) { pos.hFrom = hFrame(rel); pos.hAlign = align; pos.x = offset }
        else { pos.vFrom = vFrame(rel); pos.vAlign = align; pos.y = offset }
    }

    /** The wrapping a wrap element names (`wrapNone`: behind the text when [behindDoc], else in front); null for anything else. */
    fun wrapOf(name: String, behindDoc: Boolean): FloatWrap? = when (name) {
        "wrapNone" -> if (behindDoc) FloatWrap.BEHIND else FloatWrap.FRONT
        "wrapSquare", "wrapTight", "wrapThrough" -> FloatWrap.SQUARE
        "wrapTopAndBottom" -> FloatWrap.TOP_BOTTOM
        else -> null
    }

    private fun hRel(f: FloatFrame) = when (f) {
        FloatFrame.PAGE -> "page"
        FloatFrame.MARGIN -> "margin"
        FloatFrame.LEFT_MARGIN -> "leftMargin"
        FloatFrame.RIGHT_MARGIN -> "rightMargin"
        else -> "column"
    }

    private fun vRel(f: FloatFrame) = when (f) {
        FloatFrame.PAGE -> "page"
        FloatFrame.MARGIN, FloatFrame.COLUMN -> "margin"
        FloatFrame.TOP_MARGIN -> "topMargin"
        FloatFrame.BOTTOM_MARGIN -> "bottomMargin"
        else -> "paragraph"
    }

    private fun emu(pt: Float) = Math.round(pt * 12700.0)

    /**
     * A positioned table's `w:tblpPr`: from the page, the margins or its column / paragraph (Word's
     * "text"), at an offset in twips — its text's, its left border's place plus Word's default cell
     * margin — or aligned; the text kept an eighth of an inch away at its sides.
     */
    fun tablePositionXml(pos: FloatPosition): String = buildString {
        fun anchor(f: FloatFrame) = when (f) {
            FloatFrame.PAGE, FloatFrame.LEFT_MARGIN, FloatFrame.RIGHT_MARGIN, FloatFrame.TOP_MARGIN, FloatFrame.BOTTOM_MARGIN -> "page"
            FloatFrame.MARGIN -> "margin"
            else -> "text"
        }
        append("""<w:tblpPr w:leftFromText="180" w:rightFromText="180" w:vertAnchor="${anchor(pos.vFrom)}" w:horzAnchor="${anchor(pos.hFrom)}"""")
        pos.hAlign?.let { append(""" w:tblpXSpec="${when (it) { FloatAlign.START -> "left"; FloatAlign.CENTER -> "center"; FloatAlign.END -> "right" }}"""") }
            ?: append(""" w:tblpX="${Math.round(pos.x * 20) + 108}"""")
        pos.vAlign?.let { append(""" w:tblpYSpec="${when (it) { FloatAlign.START -> "top"; FloatAlign.CENTER -> "center"; FloatAlign.END -> "bottom" }}"""") }
            ?: append(""" w:tblpY="${Math.round(pos.y * 20)}"""")
        append("/>")
    }

    /**
     * An anchor's opening, through its wrapping (its `wp:docPr` follows): the position in CT_Anchor's
     * order — simplePos, positionH, positionV, extent ([cx] × [cy] EMU), effectExtent, the wrap — with
     * its stacking order ([z]) and whether it is behind the text.
     */
    fun anchorStart(pos: FloatPosition, cx: Long, cy: Long, z: Int): String = buildString {
        val behind = pos.wrap == FloatWrap.BEHIND
        append("""<wp:anchor distT="0" distB="0" distL="114300" distR="114300" simplePos="0" relativeHeight="${251658240 + z}" """)
        append("""behindDoc="${if (behind) 1 else 0}" locked="0" layoutInCell="1" allowOverlap="1"><wp:simplePos x="0" y="0"/>""")
        append("""<wp:positionH relativeFrom="${hRel(pos.hFrom)}">""")
        append(pos.hAlign?.let { "<wp:align>${when (it) { FloatAlign.START -> "left"; FloatAlign.CENTER -> "center"; FloatAlign.END -> "right" }}</wp:align>" }
            ?: "<wp:posOffset>${emu(pos.x)}</wp:posOffset>")
        append("</wp:positionH>")
        append("""<wp:positionV relativeFrom="${vRel(pos.vFrom)}">""")
        append(pos.vAlign?.let { "<wp:align>${when (it) { FloatAlign.START -> "top"; FloatAlign.CENTER -> "center"; FloatAlign.END -> "bottom" }}</wp:align>" }
            ?: "<wp:posOffset>${emu(pos.y)}</wp:posOffset>")
        append("</wp:positionV>")
        append("""<wp:extent cx="$cx" cy="$cy"/><wp:effectExtent l="0" t="0" r="0" b="0"/>""")
        append(when (pos.wrap) {
            FloatWrap.BEHIND, FloatWrap.FRONT -> "<wp:wrapNone/>"
            FloatWrap.TOP_BOTTOM -> "<wp:wrapTopAndBottom/>"
            FloatWrap.SQUARE -> """<wp:wrapSquare wrapText="bothSides"/>"""
        })
    }
}
