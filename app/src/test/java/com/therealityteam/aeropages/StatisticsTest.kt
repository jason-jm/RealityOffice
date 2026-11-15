package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.DeckStats
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetImage
import com.therealityteam.aeropages.model.SheetStats
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PowerPoint's deck statistics and Excel's Workbook Statistics. */
class StatisticsTest {
    @Test
    fun aDecksSlidesWordsNotesAndHiddenOnes() {
        val deck = Presentation(mutableListOf(
            Slide(title = SlideBox("季度回顾"), body = SlideBox("Revenue grew\nCosts fell")).also { it.notes = "Say thanks" },
            Slide(title = SlideBox("Next steps")).also { it.hidden = true },
        ))
        val st = DeckStats.of(deck)
        assertEquals(2, st.slides); assertEquals(1, st.hidden); assertEquals(1, st.notes); assertEquals(0, st.media)
        // 季度回顾 (4 characters) + "Revenue grew" + "Costs fell" + "Next steps": 4 + 6 = 10 words; 4 paragraphs.
        assertEquals(10, st.words)
        assertEquals(4, st.paragraphs)
    }

    @Test
    fun aSheetsEndAndCountsAndTheWorkbooksTotals() {
        val a = Sheet(name = "Data").also { s ->
            s.ensure(0, 0).text = "Region"; s.ensure(4, 3).text = "=SUM(D1:D4)"
            s.ensure(2, 1).comment = "check"
            s.images.add(SheetImage(TestPng.header(10, 10), "image/png", 0, 5, widthPt = 10f, heightPt = 10f))
        }
        val b = Sheet(name = "Empty")
        val here = SheetStats.of(a)
        assertEquals("D5", here.end)
        assertEquals(2, here.cells); assertEquals(1, here.formulas); assertEquals(1, here.comments); assertEquals(1, here.images)
        assertNull(SheetStats.of(b).end)
        val all = SheetStats.of(Workbook(mutableListOf(a, b)))
        assertEquals(2, all.cells); assertEquals(1, all.images)
    }
}
