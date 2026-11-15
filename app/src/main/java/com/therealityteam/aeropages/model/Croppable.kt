package com.therealityteam.aeropages.model

/**
 * A picture that can be cropped: the fraction of its source bitmap cut away at each side
 * (PowerPoint's and Word's `a:srcRect`, ODF's `fo:clip`); its frame shows the rest. Slide pictures
 * and document pictures both carry one.
 */
interface Croppable {
    var cropL: Float
    var cropT: Float
    var cropR: Float
    var cropB: Float

    val cropped: Boolean get() = cropL > 0f || cropT > 0f || cropR > 0f || cropB > 0f

    /** The source pixels the frame shows of a [width] × [height] bitmap: [left, top, right, bottom], at least a pixel each way. */
    fun visiblePixels(width: Int, height: Int): IntArray {
        val l = (cropL * width).toInt().coerceIn(0, width - 1)
        val t = (cropT * height).toInt().coerceIn(0, height - 1)
        val r = (width - (cropR * width).toInt()).coerceIn(l + 1, width)
        val b = (height - (cropB * height).toInt()).coerceIn(t + 1, height)
        return intArrayOf(l, t, r, b)
    }

    /** The whole picture's size at the scale of a [w] × [h] frame that shows the part the crop leaves. */
    fun uncroppedSize(w: Float, h: Float): Pair<Float, Float> = w / keptWidth() to h / keptHeight()

    /** The fractions of the source width and height the crop keeps. */
    fun keptWidth(): Float = (1f - cropL - cropR).coerceAtLeast(0.01f)
    fun keptHeight(): Float = (1f - cropT - cropB).coerceAtLeast(0.01f)

    fun setCrop(l: Float, t: Float, r: Float, b: Float) { cropL = l; cropT = t; cropR = r; cropB = b }

    companion object {
        /** The most a crop may cut from a picture each way: at least 5% of it stays. */
        const val MAX_CUT = 0.95f

        /** [l], [t], [r], [b] as a crop may hold them: no side negative, each pair leaving at least 5%. */
        fun clamp(l: Float, t: Float, r: Float, b: Float): FloatArray {
            val nl = l.coerceIn(0f, MAX_CUT); val nt = t.coerceIn(0f, MAX_CUT)
            return floatArrayOf(nl, nt, r.coerceIn(0f, MAX_CUT - nl), b.coerceIn(0f, MAX_CUT - nt))
        }
    }
}
