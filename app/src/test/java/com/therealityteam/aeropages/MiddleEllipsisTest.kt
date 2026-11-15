package com.therealityteam.aeropages

import com.therealityteam.aeropages.ui.middleEllipsis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long file names must keep BOTH ends visible (head + "…" + tail incl. extension). */
class MiddleEllipsisTest {

    @Test
    fun shortNamesUntouched() {
        assertEquals("报告.docx", middleEllipsis("报告.docx"))
        assertEquals("Quarterly Review.pptx", middleEllipsis("Quarterly Review.pptx"))
    }

    @Test
    fun longLatinNameKeepsExtension() {
        val name = "Quarterly Business Review Presentation - Final Draft with Stakeholder Feedback Incorporated 2026.pptx"
        val out = middleEllipsis(name, budget = 56)
        assertTrue("contains ellipsis", out.contains("…"))
        assertTrue("tail with extension kept", out.endsWith(".pptx"))
        assertTrue("head kept", out.startsWith("Quarterly Business Review"))
        assertTrue("actually shorter", out.length < name.length)
    }

    @Test
    fun longCjkNameWeightsDouble() {
        val name = "2026年第二季度产品发布会演示文稿-最终评审版-市场部与研发部联合修订-v3.2.pptx"
        val out = middleEllipsis(name, budget = 56)
        assertTrue(out.contains("…"))
        assertTrue("extension kept", out.endsWith(".pptx"))
        assertTrue(out.startsWith("2026年"))
        assertTrue(out.length < name.length)
    }

    @Test
    fun extremeNameTruncatesAtDefaultBudget() {
        // The wide dialog fits ~110 units; anything beyond still middle-truncates.
        val name = "项目方案".repeat(20) + "-final.docx" // 160 CJK units + tail
        val out = middleEllipsis(name)
        assertTrue(out.contains("…"))
        assertTrue("extension kept", out.endsWith(".docx"))
        assertTrue(out.length < name.length)
    }
}
