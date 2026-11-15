package com.therealityteam.aeropages

import com.therealityteam.aeropages.platform.WallMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geometry behind the spatial show's wall placement (SDK-free part of ui/SpatialShow.kt). */
class WallMathTest {

    private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    @Test
    fun normalAxisIsTheOneTheVerticesDoNotSpan() {
        // A plane in the local XY plane → normal along Z.
        assertEquals(2, WallMath.normalAxis(listOf(v(-1f, -1f, 0f), v(1f, -1f, 0f), v(1f, 1f, 0f), v(-1f, 1f, 0f))))
        // A plane in the local XZ plane → normal along Y.
        assertEquals(1, WallMath.normalAxis(listOf(v(-1f, 0f, -1f), v(1f, 0f, -1f), v(0f, 0f, 1f))))
        // Too few or collinear vertices: unknown.
        assertNull(WallMath.normalAxis(listOf(v(0f, 0f, 0f), v(1f, 0f, 0f))))
        assertNull(WallMath.normalAxis(listOf(v(0f, 0f, 0f), v(1f, 0f, 0f), v(2f, 0f, 0f))))
    }

    @Test
    fun normalIsFlippedTowardTheUser() {
        val wallAt = v(0f, 1.5f, -3f)
        val user = v(0f, 1.5f, 0f)
        val away = WallMath.toward(v(0f, 0f, -1f), wallAt, user)
        assertEquals(1f, away[2], 1e-6f)            // now points +Z, back into the room
        val fine = WallMath.toward(v(0f, 0f, 1f), wallAt, user)
        assertEquals(1f, fine[2], 1e-6f)
    }

    @Test
    fun yawTurnsThePanelFrontOntoTheNormal() {
        assertEquals(0f, WallMath.yawDegrees(v(0f, 0f, 1f)), 1e-4f)      // wall behind the user's start: face +Z
        assertEquals(180f, kotlin.math.abs(WallMath.yawDegrees(v(0f, 0f, -1f))), 1e-4f)
        assertEquals(90f, WallMath.yawDegrees(v(1f, 0f, 0f)), 1e-4f)
        assertEquals(-90f, WallMath.yawDegrees(v(-1f, 0f, 0f)), 1e-4f)
    }

    @Test
    fun fitScaleCoversMostOfTheWallWithinLimits() {
        assertEquals(1.7f, WallMath.fitScale(2f, 1f), 1e-5f)            // 85% of a 2 m wall
        assertEquals(4f, WallMath.fitScale(10f, 1f), 1e-5f)             // never wider than 4 m
        assertEquals(1f, WallMath.fitScale(0.5f, 1f), 1e-5f)            // never narrower than 1 m
        assertEquals(2f, WallMath.fitScale(4f, 1.7f), 1e-5f)            // scale is relative to the panel's own width
    }

    @Test
    fun wallsInFrontAndNearScoreBest() {
        val front = WallMath.score(0f, -2f)
        val farFront = WallMath.score(0f, -6f)
        val side = WallMath.score(2f, 0f)
        val behind = WallMath.score(0f, 2f)
        assertTrue(front > farFront)
        assertTrue(front > side)
        assertTrue(side > behind)
    }

    @Test
    fun sizeAndOrientationFilters() {
        assertTrue(WallMath.bigEnough(2f, 1f))
        assertFalse(WallMath.bigEnough(0.6f, 2f))
        assertTrue(WallMath.horizontal(v(0.7f, 0.1f, 0.7f)))
        assertFalse(WallMath.horizontal(v(0f, 1f, 0f)))
        assertNull(WallMath.unit(v(0f, 0f, 0f)))
        assertEquals(1f, WallMath.unit(v(0f, 0f, 5f))!![2], 1e-6f)
    }
}
