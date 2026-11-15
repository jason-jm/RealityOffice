package com.therealityteam.aeropages.format.pdf

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import com.therealityteam.aeropages.model.ShapeShadow
import kotlin.math.roundToInt

/**
 * A picture's outer shadow, drawn under it: an opaque picture's frame, or — a picture with
 * transparent parts (an icon, a cut-out) — its own silhouette, in the shadow's colour, blurred and
 * moved by the shadow's offset. The editor, the show and the PDF draw it alike.
 */
internal object PictureShadows {

    private data class Key(val bitmap: Bitmap, val src: Rect, val blurPx: Int)

    /** Silhouettes made so far (a few, the most recent): the blurred alpha of a picture's shown part and where it sits. */
    private val silhouettes = object : LinkedHashMap<Key, Pair<Bitmap, IntArray>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Pair<Bitmap, IntArray>>?) = size > 8
    }

    /**
     * [s] of the picture [bitmap] whose part [src] shows in [dst] on [c] — [pxPerPt] pixels a point
     * (the shadow's offset and blur are in points). Without a bitmap (not decoded yet), its frame.
     */
    fun draw(c: Canvas, bitmap: Bitmap?, src: Rect?, dst: RectF, s: ShapeShadow, pxPerPt: Float) {
        val dx = s.dxPt * pxPerPt; val dy = s.dyPt * pxPerPt
        val blur = s.blurPt * pxPerPt
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = s.color.toInt() }
        if (bitmap == null || !bitmap.hasAlpha() || src == null || src.isEmpty) {
            if (blur > 0f) paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            c.drawRect(dst.left + dx, dst.top + dy, dst.right + dx, dst.bottom + dy, paint)
            return
        }
        // The shown part's alpha, blurred in the bitmap's own pixels (the frame may scale it).
        val scaleX = dst.width() / src.width().coerceAtLeast(1)
        val blurBmp = (blur / scaleX.coerceAtLeast(1e-3f)).roundToInt().coerceIn(0, 64)
        val key = Key(bitmap, Rect(src), blurBmp)
        val (alpha, offset) = synchronized(silhouettes) { silhouettes[key] } ?: run {
            val part = if (src.left == 0 && src.top == 0 && src.width() == bitmap.width && src.height() == bitmap.height) bitmap
                else Bitmap.createBitmap(bitmap, src.left, src.top, src.width(), src.height())
            val offset = IntArray(2)
            val blurPaint = Paint().apply { if (blurBmp > 0) maskFilter = BlurMaskFilter(blurBmp.toFloat(), BlurMaskFilter.Blur.NORMAL) }
            (part.extractAlpha(blurPaint, offset) to offset).also { synchronized(silhouettes) { silhouettes[key] = it } }
        }
        val scaleY = dst.height() / src.height().coerceAtLeast(1)
        val left = dst.left + dx + offset[0] * scaleX
        val top = dst.top + dy + offset[1] * scaleY
        c.drawBitmap(alpha, null, RectF(left, top, left + alpha.width * scaleX, top + alpha.height * scaleY), paint)
    }
}
