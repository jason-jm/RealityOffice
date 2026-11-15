package com.therealityteam.aeropages.platform

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * SDK-free geometry for putting the spatial show onto a detected wall (see `ui/SpatialShow.kt`).
 * Vectors are (x, y, z) float triples so this can be unit-tested on the JVM.
 */
object WallMath {
    /**
     * Which local axis (0 = x, 1 = y, 2 = z) is the plane's normal: the one along which the
     * plane's vertices do not spread. Null when there are too few vertices to tell.
     */
    fun normalAxis(vertices: List<FloatArray>): Int? {
        if (vertices.size < 3) return null
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (v in vertices) for (i in 0..2) { if (v[i] < min[i]) min[i] = v[i]; if (v[i] > max[i]) max[i] = v[i] }
        val spread = FloatArray(3) { max[it] - min[it] }
        val axis = (0..2).minByOrNull { spread[it] } ?: return null
        // The other two axes must actually span something, or these vertices are degenerate.
        val others = (0..2).filter { it != axis }
        return if (others.all { spread[it] > 1e-3f }) axis else null
    }

    /** [n] flipped, if needed, so it points from [from] toward [target] (in the horizontal plane). */
    fun toward(n: FloatArray, from: FloatArray, target: FloatArray): FloatArray {
        val dx = target[0] - from[0]; val dz = target[2] - from[2]
        val dot = n[0] * dx + n[2] * dz
        return if (dot < 0f) floatArrayOf(-n[0], -n[1], -n[2]) else n
    }

    /** Yaw (degrees about +Y) that turns a panel's +Z front onto the unit direction [n]. */
    fun yawDegrees(n: FloatArray): Float = Math.toDegrees(atan2(n[0].toDouble(), n[2].toDouble())).toFloat()

    /** Uniform scale that makes a panel [panelWidthM] wide cover about 85% of a wall [wallWidthM] wide, within 1–4 m. */
    fun fitScale(wallWidthM: Float, panelWidthM: Float): Float {
        val target = (wallWidthM * 0.85f).coerceIn(1.0f, 4.0f)
        return target / panelWidthM.coerceAtLeast(0.05f)
    }

    /** Preference for a wall at horizontal position (x, z) relative to the user: in front (−Z) and near wins. */
    fun score(x: Float, z: Float): Float {
        val d = sqrt(x * x + z * z).coerceAtLeast(0.1f)
        return (-z / d) - d / 10f
    }

    /** A vertical plane counts as a projection wall when it is at least [minW] × [minH] metres. */
    fun bigEnough(w: Float, h: Float, minW: Float = 1.0f, minH: Float = 0.8f): Boolean = w >= minW && h >= minH

    /** Normalised copy, or null for a zero vector. */
    fun unit(v: FloatArray): FloatArray? {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-6f) return null
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

    /** True when the direction is roughly horizontal (a wall normal, not a floor normal). */
    fun horizontal(n: FloatArray): Boolean = abs(n[1]) < 0.5f
}
