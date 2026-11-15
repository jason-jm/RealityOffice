package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.ChartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chart looks shared by the canvas, the PDF export and the writers: ink contrast, nice axes, labels. */
class ChartStyleTest {

    @Test
    fun inkContrastsWithTheBackground() {
        assertEquals("white slide (default)", ChartStyle.DARK_INK, ChartStyle.inkFor(null))
        assertEquals(ChartStyle.DARK_INK, ChartStyle.inkFor(0xFFFFFFFF))
        assertEquals("pale yellow", ChartStyle.DARK_INK, ChartStyle.inkFor(0xFFFFF2CC))
        assertEquals("near-black slide", ChartStyle.LIGHT_INK, ChartStyle.inkFor(0xFF1F1F1F))
        assertEquals("Word blue", ChartStyle.LIGHT_INK, ChartStyle.inkFor(0xFF2B579A))
        assertEquals("dark chart card", ChartStyle.LIGHT_INK, ChartStyle.inkFor(0xFF2B2B3A))
        assertTrue("the chosen ink is readable (WCAG 4.5:1)", ChartStyle.contrast(0xFFFFFFFF, ChartStyle.inkFor(null)) >= 4.5)
        assertEquals(21.0, ChartStyle.contrast(0xFF000000, 0xFFFFFFFF), 1e-6)
    }

    @Test
    fun axesUseRoundStepsAndIncludeZero() {
        val a = ChartStyle.axis(9.0, 20.0)
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, 20.0), a.ticks)
        assertEquals(ChartStyle.axis(6.25, 14.0).max, 15.0, 1e-9)
        assertEquals("120 → steps of 50", listOf(0.0, 50.0, 100.0, 150.0), ChartStyle.axis(60.0, 120.0).ticks)
        val neg = ChartStyle.axis(-3.0, 7.0)
        assertTrue("negatives extend below zero", neg.min <= -3.0 && neg.max >= 7.0)
        assertEquals(0.0, neg.ticks.first { it >= 0.0 }, 1e-9)
        assertEquals(0.5f, ChartStyle.axis(0.0, 20.0).fraction(10.0), 1e-6f)
        val flat = ChartStyle.axis(0.0, 0.0)
        assertTrue("all-zero data still gets a usable axis", flat.max > flat.min)
        assertEquals(0.0, ChartStyle.axis(Double.NaN, Double.POSITIVE_INFINITY).min, 1e-9)
    }

    @Test
    fun labelsAreShortAndLocaleIndependent() {
        assertEquals("12", ChartStyle.label(12.0))
        assertEquals("15.5", ChartStyle.label(15.5))
        assertEquals("0.3", ChartStyle.label(0.1 + 0.2))
        assertEquals("6.25", ChartStyle.label(6.25))
        assertEquals("-4", ChartStyle.label(-4.0))
        assertEquals("12500", ChartStyle.label(12500.0))
        assertEquals("12.5K", ChartStyle.label(12500.0, compact = true))
        assertEquals("2.5M", ChartStyle.label(2_500_000.0, compact = true))
        assertEquals("", ChartStyle.label(Double.NaN))
        assertEquals("palettes fall back to the first set", ChartStyle.PALETTES[0], ChartStyle.palette(99))
    }
}
