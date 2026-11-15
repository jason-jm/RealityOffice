package com.therealityteam.aeropages.format.metafile

import com.therealityteam.aeropages.model.Freeform

/**
 * A metafile — an EMF or a WMF, the drawing Windows records for clip art, a chart or a diagram
 * pasted as a picture, an embedded object's preview — as what it draws: every item in one space
 * (the reference device's units, its window, viewport and world transform applied), the part of
 * that space the picture shows ([left], [top], [width], [height]; a negative extent mirrors) and the
 * picture's size in points.
 */
internal class MetafilePicture(
    val left: Float, val top: Float, val width: Float, val height: Float,
    val widthPt: Float, val heightPt: Float,
    val items: List<MetafileItem>,
)

/** One thing a metafile draws, inside [clip] (left, top, right, bottom in its space; null: anywhere). */
internal sealed class MetafileItem(val clip: FloatArray?)

/**
 * A path, filled ([fill], ARGB; even-odd when [evenOdd], else winding) and outlined ([stroke], ARGB,
 * [strokeWidth] in the picture's units — 0 a hairline, one output pixel wide — dashed as [dash] says
 * in widths, its ends and joins as [cap] and [join] say: 0 round, 1 square / bevel, 2 flat / mitre).
 */
internal class MetafilePath(
    val ops: List<Freeform.Op>, val fill: Int?, val evenOdd: Boolean,
    val stroke: Int?, val strokeWidth: Float, val dash: FloatArray?, val cap: Int, val join: Int,
    clip: FloatArray?,
) : MetafileItem(clip)

/**
 * Text: its reference point ([x], [y]) where [align] (0 left, 1 centre, 2 right) and [vAlign] (0 top,
 * 1 baseline, 2 bottom) put it, in a font [size] units tall, turned [angleDeg] anticlockwise about the
 * point; each character [advances] along when given; over [background] (ARGB) when it has one.
 */
internal class MetafileText(
    val x: Float, val y: Float, val text: String,
    val face: String?, val size: Float, val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean,
    val color: Int, val angleDeg: Float, val align: Int, val vAlign: Int, val advances: FloatArray?, val background: Int?,
    clip: FloatArray?,
) : MetafileItem(clip)

/**
 * A picture (a BMP, PNG or JPEG file's bytes) stretched over the box from ([x0], [y0]) to ([x1], [y1])
 * — mirrored when it runs the other way — showing the part [src] of it (left, top, width, height in
 * its pixels; null: all of it).
 */
internal class MetafileImage(val data: ByteArray, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val src: IntArray?, clip: FloatArray?) : MetafileItem(clip)

/** EMF and WMF pictures: whether bytes are one, what one draws, its size. */
internal object Metafiles {
    fun isMetafile(data: ByteArray): Boolean = EmfReader.isEmf(data) || WmfReader.isWmf(data)

    fun read(data: ByteArray): MetafilePicture? = if (EmfReader.isEmf(data)) EmfReader.read(data) else WmfReader.read(data)

    /** Its size in pixels at 96 dpi, as its header says, or null. */
    fun sizePx(data: ByteArray): Pair<Float, Float>? = EmfReader.sizePx(data) ?: WmfReader.sizePx(data)
}
