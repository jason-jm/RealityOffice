package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.PageFlow
import com.therealityteam.aeropages.editor.PageFlow.Break
import com.therealityteam.aeropages.editor.PageFlow.Geometry
import com.therealityteam.aeropages.editor.PageFlow.Lines
import com.therealityteam.aeropages.editor.PageFlow.Piece
import com.therealityteam.aeropages.editor.PageFlow.Rows
import com.therealityteam.aeropages.editor.PageFlow.Solid
import com.therealityteam.aeropages.editor.TableSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Page composition shared by the editor canvas and the PDF export (Word's pagination rules). */
class PageFlowTest {

    /** A paragraph of [n] lines, each [h] tall. */
    private fun para(n: Int, h: Float = 10f, before: Float = 0f, after: Float = 0f, keep: Boolean = false, notes: List<IntArray>? = null) =
        Lines(before, FloatArray(n) { (it + 1) * h }, after, keep, notes)

    /** (item, from, to) of every piece, page by page. */
    private fun shape(pages: List<PageFlow.Page>): List<List<Triple<Int, Int, Int>>> =
        pages.map { p -> p.pieces.map { Triple(it.item, it.from, it.to) } }

    private fun slices(pages: List<PageFlow.Page>, item: Int): List<TableSlice> =
        pages.flatMap { p -> p.pieces.filter { it.item == item }.mapNotNull { it.slice } }

    // --- paragraphs ------------------------------------------------------------------------

    @Test
    fun paragraphsThatFitShareAPage() {
        val pages = PageFlow.paginate(listOf(para(3), para(4)), Geometry(100f))
        assertEquals(listOf(listOf(Triple(0, 0, 3), Triple(1, 0, 4))), shape(pages))
    }

    @Test
    fun aParagraphContinuesOnTheNextPageBetweenLines() {
        // 6 lines used; the second paragraph's 10 lines: 4 fit here, 6 continue.
        val pages = PageFlow.paginate(listOf(para(6), para(10)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 6), Triple(1, 0, 4)), shape(pages)[0])
        assertEquals(listOf(Triple(1, 4, 10)), shape(pages)[1])
        assertEquals(100f, pages[0].pieces.sumOf { it.height.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun aLoneFirstLineNeverStaysAtThePageFoot() {
        // Room for one line only: the whole paragraph moves (orphan control).
        val pages = PageFlow.paginate(listOf(para(9), para(5)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 9)), shape(pages)[0])
        assertEquals(listOf(Triple(1, 0, 5)), shape(pages)[1])
    }

    @Test
    fun aLoneLastLineNeverStartsAPage() {
        // 5 of 6 lines would fit: one more moves down so the next page gets two (widow control).
        val pages = PageFlow.paginate(listOf(para(5), para(6)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 5), Triple(1, 0, 4)), shape(pages)[0])
        assertEquals(listOf(Triple(1, 4, 6)), shape(pages)[1])
    }

    @Test
    fun aThreeLineParagraphMovesWhole() {
        // Two of three lines fit: 2+1 leaves a widow and 1+2 an orphan, so it moves.
        val pages = PageFlow.paginate(listOf(para(8), para(3)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 8)), shape(pages)[0])
        assertEquals(listOf(Triple(1, 0, 3)), shape(pages)[1])
    }

    @Test
    fun aParagraphTallerThanAPageRunsOverSeveralPages() {
        val pages = PageFlow.paginate(listOf(para(25)), Geometry(100f))
        assertEquals(listOf(listOf(Triple(0, 0, 10)), listOf(Triple(0, 10, 20)), listOf(Triple(0, 20, 25))), shape(pages))
    }

    @Test
    fun spaceAfterMayHangPastTheFootButSpaceBeforeCounts() {
        // 9 lines + 20 after = 110: the lines fit, the spacing hangs; the next item starts a new page.
        val pages = PageFlow.paginate(listOf(para(9, after = 20f), para(1)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 9)), shape(pages)[0])
        assertEquals(1, pages[1].pieces.size)
        // Space before is part of the first piece: 12 before + 9 lines = 102 > 100 → split after 8 lines… of which widow control keeps 7.
        val split = PageFlow.paginate(listOf(para(9, before = 12f)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 7)), shape(split)[0])
        assertEquals(12f + 70f, split[0].pieces[0].height, 0.01f)
    }

    @Test
    fun aHeadingKeepsWithTheParagraphAfterIt() {
        // Heading (2 lines) fits at the foot, but not with the next paragraph's first two lines: it moves on.
        val pages = PageFlow.paginate(listOf(para(7), para(2, keep = true), para(6)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 7)), shape(pages)[0])
        assertEquals(listOf(Triple(1, 0, 2), Triple(2, 0, 6)), shape(pages)[1])
    }

    @Test
    fun aHeadingThatFitsWithItsFollowerStays() {
        val pages = PageFlow.paginate(listOf(para(4), para(2, keep = true), para(6)), Geometry(100f))
        assertEquals(listOf(Triple(0, 0, 4), Triple(1, 0, 2), Triple(2, 0, 4)), shape(pages)[0])
    }

    @Test
    fun aManualBreakStartsANewPageAndATrailingBreakAddsNone() {
        val pages = PageFlow.paginate(listOf(para(1), Break, para(1), Break), Geometry(100f))
        assertEquals(listOf(listOf(Triple(0, 0, 1)), listOf(Triple(2, 0, 1))), shape(pages))
        assertEquals(1, PageFlow.paginate(emptyList(), Geometry(100f)).size)
    }

    // --- footnotes ----------------------------------------------------------------------------

    @Test
    fun footnotesAreReservedOnThePageThatReferencesThem() {
        val notes = List(5) { if (it == 2) intArrayOf(1) else IntArray(0) }
        val g = Geometry(100f, noteHeight = { 15f }, noteArea = 5f)
        // 5 lines (50) + note (20) = 70; the next paragraph gets the remaining 30: 3 lines of 6 → widow rule leaves 3 here? 6-3=3 ≥ 2, fine.
        val pages = PageFlow.paginate(listOf(para(5, notes = notes), para(6)), g)
        assertEquals(listOf(1), pages[0].notes)
        assertEquals(listOf(Triple(0, 0, 5), Triple(1, 0, 3)), shape(pages)[0])
        assertTrue(pages[1].notes.isEmpty())
    }

    @Test
    fun aLineWhoseNoteDoesNotFitMovesWithItsNote() {
        // 8 lines used; the next paragraph's first line carries a 40-tall note: it cannot fit here.
        val g = Geometry(100f, noteHeight = { 40f }, noteArea = 0f)
        val notes = listOf(intArrayOf(7), IntArray(0), IntArray(0))
        val pages = PageFlow.paginate(listOf(para(8), para(3, notes = notes)), g)
        assertEquals(listOf(Triple(0, 0, 8)), shape(pages)[0])
        assertEquals(listOf(7), pages[1].notes)
    }

    // --- columns --------------------------------------------------------------------------------

    @Test
    fun columnsFillLeftToRightBeforeANewPage() {
        val pages = PageFlow.paginate(listOf(para(25)), Geometry(100f, columns = 2))
        assertEquals(2, pages.size)
        assertEquals(2, pages[0].frames.size)
        assertEquals(Triple(0, 0, 10), pages[0].frames[0].single().let { Triple(it.item, it.from, it.to) })
        assertEquals(Triple(0, 10, 20), pages[0].frames[1].single().let { Triple(it.item, it.from, it.to) })
        assertEquals(Triple(0, 20, 25), pages[1].frames[0].single().let { Triple(it.item, it.from, it.to) })
    }

    // --- tables (ported from the old TableSplit) ------------------------------------------------

    @Test
    fun aTableThatFitsIsOneSlice() {
        val pages = PageFlow.paginate(listOf(Solid(400f), Rows(floatArrayOf(20f, 20f, 20f), headerCount = 1)), Geometry(500f))
        assertEquals(listOf(TableSlice(0..2, repeatHeader = false)), slices(pages, 1))
        assertTrue(slices(pages, 1)[0].isWhole)
    }

    @Test
    fun rowsSpillOntoFollowingPagesWithTheHeaderRepeated() {
        val heights = FloatArray(10) { 30f }          // header + 9 rows of 30
        val pages = PageFlow.paginate(listOf(Solid(30f), Rows(heights, headerCount = 1)), Geometry(130f))
        // Page 1 has room for 3 rows (header + 2); every next page: header (30) + 3 rows (90) = 120 ≤ 130.
        assertEquals(
            listOf(TableSlice(0..2, false), TableSlice(3..5, true), TableSlice(6..8, true), TableSlice(9..9, true)),
            slices(pages, 1),
        )
        assertEquals(120f, pages[1].pieces.single().height, 0.01f)
    }

    @Test
    fun withoutHeaderRowsNothingRepeats() {
        val pages = PageFlow.paginate(listOf(Rows(FloatArray(5) { 40f }, headerCount = 0)), Geometry(100f))
        assertEquals(listOf(TableSlice(0..1, false), TableSlice(2..3, false), TableSlice(4..4, false)), slices(pages, 0))
    }

    @Test
    fun aTableWhoseFirstBodyRowDoesNotFitStartsOnTheNextPage() {
        // Only the header row would fit after the paragraph: the table moves rather than strand it.
        val pages = PageFlow.paginate(listOf(Solid(450f), Rows(floatArrayOf(20f, 900f, 20f), headerCount = 1)), Geometry(500f))
        assertEquals(listOf(Triple(0, 0, 0)), shape(pages)[0])
        // A row taller than a page still gets a page, with the header repeated above it.
        assertEquals(listOf(TableSlice(0..0, false), TableSlice(1..1, true), TableSlice(2..2, true)), slices(pages, 1))
    }

    @Test
    fun theHeaderMovesWithTheTableInsteadOfSplitting() {
        // Two header rows, room for one: the whole table starts on the next page, unsplit.
        val pages = PageFlow.paginate(listOf(Solid(140f), Rows(floatArrayOf(50f, 50f, 20f, 20f), headerCount = 2)), Geometry(200f))
        assertEquals(listOf(TableSlice(0..3, false)), slices(pages, 1))
        assertEquals(2, pages.size)
    }

    @Test
    fun aHeaderTallerThanHalfAPageDoesNotRepeat() {
        val pages = PageFlow.paginate(listOf(Rows(floatArrayOf(60f, 30f, 30f, 30f), headerCount = 1)), Geometry(100f))
        assertEquals(listOf(TableSlice(0..1, false), TableSlice(2..3, false)), slices(pages, 0))
    }

    @Test
    fun tablePaddingIsPaidOnEveryPiece() {
        val pages = PageFlow.paginate(listOf(Rows(FloatArray(4) { 40f }, headerCount = 0, padTop = 5f, padBottom = 5f)), Geometry(100f))
        assertEquals(listOf(TableSlice(0..1, false), TableSlice(2..3, false)), slices(pages, 0))
        assertEquals(90f, pages[0].pieces.single().height, 0.01f)
    }

    @Test
    fun piecesRecordTheirHeights() {
        val pages = PageFlow.paginate(listOf(para(2, before = 4f, after = 6f), Solid(12f)), Geometry(100f))
        assertEquals(listOf(30f, 12f), pages[0].pieces.map(Piece::height))
    }

    // --- bands: the text steps over a floating object's height ------------------------------

    private val gap = PageFlow.GAP

    @Test
    fun linesStepOverABand() {
        // A band 30..50 down page 1: three lines above it, a gap, five below; the last two go on to page 2.
        val bands = mapOf(0 to listOf(PageFlow.Band(30f, 50f, setOf(0))))
        val pages = PageFlow.paginate(listOf(para(10)), Geometry(100f), bands)
        assertEquals(listOf(listOf(Triple(0, 0, 3), Triple(gap, 0, 0), Triple(0, 3, 8)), listOf(Triple(0, 8, 10))), shape(pages))
        assertEquals(20f, pages[0].pieces[1].height)
        // A band from the page's top: the text starts below it.
        val top = PageFlow.paginate(listOf(para(3)), Geometry(100f), mapOf(0 to listOf(PageFlow.Band(0f, 40f, setOf(0)))))
        assertEquals(listOf(Triple(gap, 0, 0), Triple(0, 0, 3)), shape(top)[0])
        // A picture that would cross it goes below it whole.
        val solid = PageFlow.paginate(listOf(para(2), Solid(25f)), Geometry(100f), mapOf(0 to listOf(PageFlow.Band(30f, 60f, setOf(0)))))
        assertEquals(listOf(Triple(0, 0, 2), Triple(gap, 0, 0), Triple(1, 0, 0)), shape(solid)[0])
        assertEquals(40f, solid[0].pieces[1].height)
    }

    @Test
    fun aTableBreaksAroundABandWithoutRepeatingItsHeader() {
        val table = Rows(FloatArray(6) { 10f }, headerCount = 1)
        val pages = PageFlow.paginate(listOf(table), Geometry(100f), mapOf(0 to listOf(PageFlow.Band(25f, 45f, setOf(0)))))
        assertEquals(listOf(TableSlice(0 until 2, false), TableSlice(2 until 6, false)), slices(pages, 0))
        assertEquals(gap, pages[0].pieces[1].item)
    }

    @Test
    fun aBandLandsOnThePageItsMarkIsOn() {
        // A mark (no height) after two pages' worth of text: its band is on page 3 — not page 1.
        val band = PageFlow.Band(0f, 50f, setOf(0))
        val items = listOf(para(10), para(10), Solid(0f, keepWithNext = true), para(4))
        val flowed = PageFlow.paginateAround(items, Geometry(100f)) { if (it == 2) band else null }
        val pages = flowed.pages
        assertEquals(3, pages.size)
        assertEquals(mapOf(2 to 2), flowed.bandPages)
        assertEquals("the text after the mark starts below the band — the mark with it", listOf(Triple(gap, 0, 0), Triple(2, 0, 0), Triple(3, 0, 4)), shape(pages)[2])
        assertEquals(listOf(Triple(0, 0, 10)), shape(pages)[0])
        // Its own band pushing its paragraph on does not move it: a mark before a paragraph that fits page 1 without
        // the band keeps its band on page 1 though the text now goes on to page 2.
        val stays = PageFlow.paginateAround(listOf(para(6), Solid(0f, keepWithNext = true), para(3)), Geometry(100f)) { if (it == 1) PageFlow.Band(50f, 80f, setOf(0)) else null }
        assertEquals(mapOf(1 to 0), stays.bandPages)
        assertEquals(listOf(listOf(Triple(0, 0, 5), Triple(gap, 0, 0), Triple(0, 5, 6)), listOf(Triple(1, 0, 0), Triple(2, 0, 3))),
            shape(stays.pages))
        // A plain mark of no height would stay at the full page's foot.
        assertEquals(listOf(Triple(1, 0, 10), Triple(2, 0, 0)), shape(PageFlow.paginate(listOf(para(10), para(10), Solid(0f), para(4)), Geometry(100f)))[1])
    }

    @Test
    fun linesBesideAFloatingObjectAreLaidOutAgainThere() {
        // A paragraph of 6 lines at the top; an object beside lines 2-4 (20..50): laid out there, it takes 8 lines.
        val asked = ArrayList<Triple<Int, Float, List<PageFlow.Side>>>()
        val side = PageFlow.Side(20f, 50f, setOf(0), left = 0f, right = 40f)
        val g = Geometry(200f, reflow = { item, top, sides -> asked.add(Triple(item, top, sides)); if (item == 1) para(8) else null })
        val pages = PageFlow.paginate(listOf(para(2), para(6), para(3)), g, sides = mapOf(0 to listOf(side)))
        assertEquals(listOf(listOf(Triple(0, 0, 2), Triple(1, 0, 8), Triple(2, 0, 3))), shape(pages))
        // Asked for the paragraph at 20 (and, its extent judged generously, the one above — laid out as measured).
        assertEquals(listOf(20f to listOf(side)), asked.filter { it.first == 1 }.map { it.second to it.third }.distinct())
        assertEquals(mapOf(1 to PageFlow.Wrap(20f, listOf(side))), pages[0].wraps)
        // An object on page 2 only: the paragraph on page 1 is laid out as measured.
        val later = PageFlow.paginate(listOf(para(2), para(6)), g, sides = mapOf(1 to listOf(side)))
        assertTrue(later[0].wraps.isEmpty())
    }

    @Test
    fun objectsMovingWithTheirParagraphFollowItDownThePage() {
        // Two marks: the first's object (beside the lines below it) narrows its paragraph, which pushes the second
        // mark down; the second's object is taken from where its mark ends up.
        val items = listOf(Solid(0f, keepWithNext = true), para(4), Solid(0f, keepWithNext = true), para(2))
        val g = Geometry(200f, reflow = { item, _, sides -> if (item == 1 && sides.isNotEmpty()) para(6) else null })
        val seen = HashMap<Int, MutableList<Float>>()
        val flowed = PageFlow.paginateAround(items, g, setOf(0, 2)) { item, at ->
            seen.getOrPut(item) { ArrayList() }.add(at.y)
            if (item == 0) PageFlow.Room(sides = listOf(PageFlow.Side(at.y, at.y + 30f, setOf(0), 0f, 50f))) else PageFlow.Room(bands = listOf(PageFlow.Band(at.y, at.y + 10f, setOf(0))))
        }
        // Pass 1: the second mark at 40; with the first object beside it the paragraph takes 6 lines (and steps over the
        // second object's band at 40..50): 70; then 60 — where it stays, its own band's gap above the paragraph after it.
        assertEquals(60f, flowed.marks.getValue(2).y)
        assertEquals(listOf(40f, 70f, 60f), seen.getValue(2).distinct())
        assertEquals(mapOf(0 to 0, 2 to 0), flowed.bandPages)
        assertEquals(listOf(Triple(0, 0, 0), Triple(1, 0, 6), Triple(gap, 0, 0), Triple(2, 0, 0), Triple(3, 0, 2)), shape(flowed.pages)[0])
    }

    @Test
    fun aHeaderOrFooterThatReachesIntoTheBodyTakesItsRoom() {
        // A header 20 below the top margin: the text starts below it, on every page.
        val pushed = PageFlow.paginate(listOf(para(10)), Geometry(100f, room = { PageFlow.PageRoom(top = 20f) }))
        assertEquals(listOf(listOf(Triple(gap, 0, 0), Triple(0, 0, 8)), listOf(Triple(gap, 0, 0), Triple(0, 8, 10))), shape(pushed))
        assertEquals(20f, pushed[0].pieces[0].height)
        // A footer 30 above the bottom margin: the columns end above it.
        assertEquals(listOf(listOf(Triple(0, 0, 7)), listOf(Triple(0, 7, 10))),
            shape(PageFlow.paginate(listOf(para(10)), Geometry(100f, room = { PageFlow.PageRoom(foot = 30f) }))))
        // The first page's own header only (a letterhead's banner).
        assertEquals(listOf(listOf(Triple(gap, 0, 0), Triple(0, 0, 5)), listOf(Triple(0, 5, 10))),
            shape(PageFlow.paginate(listOf(para(10)), Geometry(100f, room = { if (it == 0) PageFlow.PageRoom(top = 50f) else null }))))
        // A picture of the header's between: the text steps over its stretch.
        assertEquals(listOf(Triple(0, 0, 4), Triple(gap, 0, 0), Triple(0, 4, 6)),
            shape(PageFlow.paginate(listOf(para(6)), Geometry(100f, room = { PageFlow.PageRoom(bands = listOf(PageFlow.Band(40f, 60f, setOf(0)))) })))[0])
        // Something taller than what the header leaves still goes on the page (a gap is no content), and the flow ends.
        val tall = PageFlow.paginate(listOf(Solid(50f), Solid(50f)), Geometry(100f, room = { PageFlow.PageRoom(top = 95f) }))
        assertEquals(listOf(listOf(Triple(gap, 0, 0), Triple(0, 0, 0)), listOf(Triple(gap, 0, 0), Triple(1, 0, 0))), shape(tall))
        // Footnotes sit above the footer's room.
        val noted = PageFlow.paginate(listOf(para(10, notes = List(10) { if (it == 0) intArrayOf(1) else IntArray(0) })),
            Geometry(100f, noteHeight = { 10f }, room = { PageFlow.PageRoom(foot = 30f) }))
        assertEquals(listOf(Triple(0, 0, 6)), shape(noted)[0])
        assertEquals(listOf(1), noted[0].notes)
    }
}
