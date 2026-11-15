package com.therealityteam.aeropages.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class WrapShapeTest {
    @Test
    fun theLinesBesideAnObjectAreParagraphsOfTheirOwn() {
        val text = AnnotatedString("a".repeat(100))
        val shape = WrapShape(intArrayOf(10, 20, 30), floatArrayOf(0f, 0f, 0f), 40, 100)
        val shown = shape.apply(text, TextUnit.Unspecified, TextUnit.Unspecified)
        assertEquals(100, shown.length)
        assertEquals(listOf(10 to 20, 20 to 30, 30 to 40, 40 to 100), shown.paragraphStyles.map { it.start to it.end })
    }
}
