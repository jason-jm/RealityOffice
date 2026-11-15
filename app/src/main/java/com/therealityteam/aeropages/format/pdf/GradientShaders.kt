package com.therealityteam.aeropages.format.pdf

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import com.therealityteam.aeropages.model.GradientKind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A shape's two-colour gradient as a shader over its box ([x], [y], [w] × [h]): linear along its
 * angle, axial (mirrored from its centre line), radial (circles from its centre out to the
 * farthest corner) or rectangular (rectangles from its centre out to the edges — drawn once into
 * a small picture and stretched, as no platform shader draws one).
 */
internal object GradientShaders {

    /** The rectangular gradient's picture: [SIZE] pixels a side, stretched over the shape (smoothly filtered). */
    private const val SIZE = 64

    private data class RectKey(val first: Int, val second: Int, val cx: Float, val cy: Float)

    private val rectangles = object : LinkedHashMap<RectKey, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RectKey, Bitmap>?) = size > 16
    }

    fun shader(kind: GradientKind, first: Long, second: Long, angleDeg: Float, cx: Float, cy: Float, x: Float, y: Float, w: Float, h: Float): Shader {
        val a = first.toInt(); val b = second.toInt()
        val midX = x + w / 2f; val midY = y + h / 2f
        val ang = Math.toRadians(angleDeg.toDouble())
        // Half the box along the angle (0 = left→right, 90 = top→bottom).
        val dx = (cos(ang) * w / 2).toFloat(); val dy = (sin(ang) * h / 2).toFloat()
        return when (kind) {
            GradientKind.LINEAR -> LinearGradient(midX - dx, midY - dy, midX + dx, midY + dy, a, b, Shader.TileMode.CLAMP)
            // From the centre line (the second colour) out to each edge (the first), mirrored.
            GradientKind.AXIAL -> LinearGradient(midX, midY, midX + dx, midY + dy, b, a, Shader.TileMode.MIRROR)
            GradientKind.RADIAL -> {
                // Circles as wide as half the box's diagonal, wherever the centre is (as LibreOffice and PowerPoint draw them).
                val px = x + cx * w; val py = y + cy * h
                RadialGradient(px, py, (hypot(w, h) / 2f).coerceAtLeast(0.5f), a, b, Shader.TileMode.CLAMP)
            }
            GradientKind.RECTANGULAR -> {
                val key = RectKey(a, b, cx, cy)
                val bmp = synchronized(rectangles) { rectangles[key] } ?: rectangular(a, b, cx, cy).also { synchronized(rectangles) { rectangles[key] = it } }
                BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(Matrix().apply { setScale(w / SIZE, h / SIZE); postTranslate(x, y) })
                }
            }
        }
    }

    /** Rectangles out from ([cx], [cy]): [first] at the centre, [second] at the edges. */
    private fun rectangular(first: Int, second: Int, cx: Float, cy: Float): Bitmap {
        val px = IntArray(SIZE * SIZE)
        val spanX = maxOf(cx, 1f - cx).coerceAtLeast(1e-3f); val spanY = maxOf(cy, 1f - cy).coerceAtLeast(1e-3f)
        for (j in 0 until SIZE) for (i in 0 until SIZE) {
            val u = (i + 0.5f) / SIZE; val v = (j + 0.5f) / SIZE
            val t = maxOf(abs(u - cx) / spanX, abs(v - cy) / spanY).coerceIn(0f, 1f)
            px[j * SIZE + i] = mix(first, second, t)
        }
        return createBitmap(SIZE, SIZE).also { it.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE) }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = (a ushr shift) and 0xFF; val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
        }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
