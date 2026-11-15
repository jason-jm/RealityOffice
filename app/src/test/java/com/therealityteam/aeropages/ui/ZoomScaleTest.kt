package com.therealityteam.aeropages.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomScaleTest {
    @Test
    fun hundredSitsInTheMiddleAndTheEndsAreTheLimits() {
        assertEquals(0.5f, ZoomScale.fraction(100, 25, 400), 1e-6f)
        assertEquals(0f, ZoomScale.fraction(25, 25, 400), 1e-6f)
        assertEquals(1f, ZoomScale.fraction(400, 25, 400), 1e-6f)
        assertEquals(25, ZoomScale.percent(0f, 25, 400))
        assertEquals(400, ZoomScale.percent(1f, 25, 400))
    }

    @Test
    fun theSliderSnapsTo100NearTheMiddleAndRoundTripsElsewhere() {
        assertEquals(100, ZoomScale.percent(0.51f, 25, 400))
        assertEquals(100, ZoomScale.percent(0.49f, 25, 400))
        for (p in listOf(25, 40, 62, 150, 250, 399)) assertEquals(p, ZoomScale.percent(ZoomScale.fraction(p, 25, 400), 25, 400))
    }
}
