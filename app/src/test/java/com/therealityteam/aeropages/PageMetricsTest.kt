package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The layout model the editor canvas and the PDF export share. */
class PageMetricsTest {

    @Test
    fun linePitchIsTheLargestFontTimesSingleTimesSpacing() {
        val normal = Paragraph.of("text")                                   // 11 pt, 1.15 spacing
        assertEquals(11f * 1.2f * 1.15f, PageMetrics.pitchPt(normal), 1e-4f)
        val mixed = Paragraph(mutableListOf(TextRun("small "), TextRun("BIG", CharacterFormat(fontSizePt = 24f))), ParagraphFormat(lineSpacing = 2f))
        assertEquals(24f * 1.2f * 2f, PageMetrics.pitchPt(mixed), 1e-4f)
        val heading = Paragraph.of("Title", pFormat = ParagraphFormat(style = ParagraphStyle.HEADING1, lineSpacing = 1f))
        assertEquals(20f * 1.2f, PageMetrics.pitchPt(heading), 1e-4f)
    }

    @Test
    fun oddLineSpacingIsKeptSane() {
        assertEquals(0.5f, PageMetrics.lineSpacing(Paragraph.of("x", pFormat = ParagraphFormat(lineSpacing = 0f))), 0f)
        assertEquals(5f, PageMetrics.lineSpacing(Paragraph.of("x", pFormat = ParagraphFormat(lineSpacing = 40f))), 0f)
        assertEquals(1f, PageMetrics.lineSpacing(Paragraph.of("x", pFormat = ParagraphFormat(lineSpacing = Float.NaN))), 0f)
    }

    @Test
    fun anEmptyParagraphUsesItsRunSize() {
        val empty = Paragraph(mutableListOf(TextRun("", CharacterFormat(fontSizePt = 18f))), ParagraphFormat(lineSpacing = 1f))
        assertEquals(18f * 1.2f, PageMetrics.pitchPt(empty), 1e-4f)
    }

    @Test
    fun numberedListsCountAcrossTheWholeRunAndRestartAfterABreak() {
        fun num(t: String) = Paragraph.of(t, pFormat = ParagraphFormat(listKind = ListKind.NUMBER))
        val a = num("a"); val b = num("b"); val plain = Paragraph.of("plain"); val c = num("c"); val d = num("d")
        val ords = PageMetrics.listLabels(sequenceOf(a, b, plain, c, null, d))
        assertEquals("1.", ords[a]); assertEquals("2.", ords[b]); assertNull(ords[plain])
        assertEquals("1.", ords[c]); assertEquals("1.", ords[d])     // a table (null) between restarts the count
        assertEquals("2.", PageMetrics.markerOf(b, ords[b]))
        assertEquals("•", PageMetrics.markerOf(Paragraph.of("x", pFormat = ParagraphFormat(listKind = ListKind.BULLET)), null))
        assertNull(PageMetrics.markerOf(plain, null))
    }

    @Test
    fun cellWidthsFollowColumnWeightsAndSpans() {
        assertArrayEquals(intArrayOf(33, 33, 34), PageMetrics.split(floatArrayOf(1f, 1f, 1f), 100))
        val table = Table(mutableListOf(
            TableRow(mutableListOf(TableCell(gridSpan = 2), TableCell())),
            TableRow(mutableListOf(TableCell(), TableCell(), TableCell())),
        ), columnWidths = mutableListOf(1f, 1f, 2f))
        assertArrayEquals(floatArrayOf(2f, 2f), PageMetrics.cellWeights(table, table.rows[0]), 0f)
        assertArrayEquals(intArrayOf(200, 200), PageMetrics.cellWidths(table, table.rows[0], 400))
        assertArrayEquals(intArrayOf(100, 100, 200), PageMetrics.cellWidths(table, table.rows[1], 400))
    }

    @Test
    fun picturesShrinkToTheColumnKeepingTheirShape() {
        assertEquals(200f to 100f, PageMetrics.fitImage(200f, 100f, 400f))
        assertEquals(300f to 150f, PageMetrics.fitImage(600f, 300f, 300f))
    }

    @Test
    fun footnoteReferencesAreTheSuperscriptNumbersInRange() {
        val p = Paragraph(mutableListOf(
            TextRun("See "), TextRun("[1]", CharacterFormat(superscript = true)),
            TextRun(" and [2] in text, "), TextRun("[9]", CharacterFormat(superscript = true)),
            TextRun(" end"), TextRun("[2]", CharacterFormat(superscript = true)),
        ))
        // "[2]" in plain text is not a reference; [9] is out of range (3 footnotes).
        assertEquals(listOf(4 to 1, 32 to 2), PageMetrics.footnoteAnchors(p, 3))
        assertEquals(emptyList<Pair<Int, Int>>(), PageMetrics.footnoteAnchors(p, 0))
    }

    @Test
    fun theQuickLineGuessCountsCjkAsAFullEm() {
        val latin = Paragraph.of("a".repeat(100))                 // 11 pt: 100 × 5.5 = 550 wide
        val cjk = Paragraph.of("中".repeat(100))                  // 100 × 11 = 1100 wide
        assertEquals(2, PageMetrics.estimateLines(latin, 300f, 1f))   // 550 / (300 × 0.92) → 2
        assertEquals(4, PageMetrics.estimateLines(cjk, 300f, 1f))     // 1100 / 276 → 4
        assertEquals(1, PageMetrics.estimateLines(Paragraph.of(""), 300f, 1f))
        assertEquals(8, PageMetrics.estimateLines(cjk, 300f, 2f))     // twice the pixels per point
    }

    @Test
    fun generatedTextIsPlainParagraphsBothRenderersShare() {
        val h = PageMetrics.generatedHeading("Contents")
        assertEquals(16f, h.runs[0].format.fontSizePt)
        assertEquals("[ii] note", PageMetrics.endnote(1, "note").plainText())
        assertEquals("[3] foot", PageMetrics.footnote(3, "foot").plainText())
        assertEquals(PageMetrics.ENTRY_SIZE * PageMetrics.SINGLE_LINE * 1.25f, PageMetrics.TOC_LINE, 1e-4f)
    }
}
