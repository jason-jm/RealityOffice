package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.ParagraphOps
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The run-splicing replace must behave exactly like the character-level rebuild it replaced. */
class ParagraphOpsReplaceTest {

    private val bold = CharacterFormat(bold = true)
    private val plain = CharacterFormat()

    private fun para() = Paragraph(mutableListOf(TextRun("Hello", bold.copy()), TextRun(" world", plain.copy())))

    @Test fun replaceAcrossRunBoundaryKeepsFormattingOnBothSides() {
        val p = para()
        ParagraphOps.replace(p, 3, 8, "XYZ", plain.copy())
        assertEquals("HelXYZrld", p.plainText())
        assertEquals(listOf("Hel" to true, "XYZrld" to false), p.runs.map { it.text to it.format.bold })
    }

    @Test fun typingWithTheNeighbourFormatMergesIntoOneRun() {
        val p = para()
        ParagraphOps.replace(p, 5, 5, "!", bold.copy())
        assertEquals("Hello! world", p.plainText())
        assertEquals(2, p.runs.size)
        assertEquals("Hello!", p.runs[0].text)
    }

    @Test fun deletingEverythingLeavesOneEmptyRunWithTheGivenFormat() {
        val p = para()
        ParagraphOps.replace(p, 0, 11, "", bold.copy())
        assertEquals("", p.plainText())
        assertEquals(1, p.runs.size)
        assertTrue(p.runs[0].format.bold)
    }

    @Test fun outOfRangeOffsetsAreClamped() {
        val p = para()
        ParagraphOps.replace(p, 50, 60, "?", plain.copy())
        assertEquals("Hello world?", p.plainText())
        ParagraphOps.replace(p, -5, 2, "J", bold.copy())
        assertEquals("Jllo world?", p.plainText())
    }
}
