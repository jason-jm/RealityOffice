package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/** Merged slide table cells (PowerPoint's gridSpan / rowSpan, ODF's spans): drawn once over their places, kept in both formats. */
class SlideTableMergeTest {
    /** A 4×3 table whose top-left cell spans two rows and two columns. */
    private fun table(): SlideTable {
        val t = SlideTable.create(4, 3)
        t.cells[0][0].paragraph.runs.first().text = "Merged"
        t.cells[0][0].colSpan = 2; t.cells[0][0].rowSpan = 2
        t.cells[0][2].paragraph.runs.first().text = "C"
        t.cells[2][0].paragraph.runs.first().text = "Below"
        return t
    }
    private fun deck(t: SlideTable) = Presentation(mutableListOf(Slide(title = SlideBox("Merges", CharacterFormat(fontSizePt = 36f)), layout = SlideLayout.BLANK).also { it.tables += t }))

    @Test
    fun aMergedCellCoversThePlacesItSpans() {
        val t = table()
        val cover = t.coverage()
        assertTrue(t.hasMerges)
        assertFalse(t.covered(cover, 0, 0))
        assertTrue(t.covered(cover, 0, 1)); assertTrue(t.covered(cover, 1, 0)); assertTrue(t.covered(cover, 1, 1))
        assertFalse(t.covered(cover, 0, 2)); assertFalse(t.covered(cover, 2, 0))
        assertEquals(0, cover[1][1])
        // A span past the edge is clipped to the grid.
        t.cells[3][2].colSpan = 5
        assertEquals(1 to 1, t.spanOf(3, 2))
    }

    @Test
    fun pptxAndOdpKeepMergedCellsAsPowerPointAndImpressWriteThem() {
        val p = deck(table())
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        val xml = part(pptx, "ppt/slides/slide1.xml")
        assertTrue(xml.contains("""<a:tc rowSpan="2" gridSpan="2">"""))
        assertTrue("its row", xml.contains("""<a:tc hMerge="1">"""))
        assertTrue("below it", xml.contains("""<a:tc gridSpan="2" vMerge="1">"""))
        assertTrue("below and right", xml.contains("""<a:tc hMerge="1" vMerge="1">"""))
        val odp = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        val content = part(odp, "content.xml")
        assertTrue(content.contains("""table:number-columns-spanned="2" table:number-rows-spanned="2""""))
        assertEquals(3, Regex("<table:covered-table-cell").findAll(content).count())
        for ((name, back) in listOf("pptx" to PptxReader.read(pptx), "odp" to OdpReader.read(odp))) {
            val b = back.slides.single().tables.single()
            assertEquals(name, 4, b.rows); assertEquals(name, 3, b.cols)
            assertEquals(name, 2 to 2, b.spanOf(0, 0))
            assertEquals(name, "Merged", b.cells[0][0].text); assertEquals(name, "C", b.cells[0][2].text); assertEquals(name, "Below", b.cells[2][0].text)
            assertEquals(name, 1 to 1, b.spanOf(0, 1))
            assertTrue(name, b.covered(b.coverage(), 1, 1))
        }
    }

    @Test
    fun rowsAndColumnsInsertedOrDeletedKeepTheMergeAndSplitCellsUndoesIt() {
        val e = SlideEditor().apply { load(deck(table())) }
        fun t() = e.presentation.slides.single().tables.single()
        e.selectTable(t())
        // A row put in inside the merged cell: it grows over it.
        e.focus(t().cells[0][2])
        e.addTableRow(below = true)
        assertEquals(3 to 2, t().spanOf(0, 0))
        e.undo()
        assertEquals(2 to 2, t().spanOf(0, 0))
        // Its first row deleted: the merged cell moves down with its text, one row shorter.
        e.selectTable(t()); e.focus(t().cells[0][2])
        e.deleteTableRow()
        assertEquals("Merged", t().cells[0][0].text)
        assertEquals(1 to 2, t().spanOf(0, 0))
        e.undo()
        // A column inside it deleted: one column narrower.
        e.selectTable(t()); e.focus(t().cells[2][1])
        e.deleteTableColumn()
        assertEquals(2 to 1, t().spanOf(0, 0))
        e.undo()
        // Split Cells on the merged cell: its places are cells again; one step.
        e.selectTable(t()); e.focus(t().cells[0][0])
        assertTrue(e.canSplitTableCell)
        assertTrue(e.splitTableCell())
        assertFalse(t().hasMerges)
        assertFalse("not merged now", e.canSplitTableCell)
        e.undo()
        assertEquals(2 to 2, t().spanOf(0, 0))
    }

    @Test
    fun aMergedCellsEdgesAreItsOwnBorders() {
        val look = com.therealityteam.aeropages.model.SlideTableLook
        val side = com.therealityteam.aeropages.model.SlideTableLook.Side.entries
        val e = SlideEditor().apply { load(deck(table())) }
        fun t() = e.presentation.slides.single().tables.single()
        e.selectTable(t())
        val pen = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.DASHED, 2.25f, 0xFFC00000)
        e.tablePen = pen
        assertTrue(e.applyTableBorders(SlideEditor.TableBorders.OUTSIDE))
        // The merged cell's top over both its columns, its left down both its rows: the pen.
        assertEquals(pen, look.line(t(), 0, 1, side[0])); assertEquals(pen, look.line(t(), 1, 0, side[2]))
        // The merged cell typed in: its bottom runs under both columns; the cells below share it.
        e.focus(t().cells[0][0])
        val red = pen.copy(style = com.therealityteam.aeropages.model.LineStyle.THIN, widthPt = 1f)
        e.tablePen = red
        assertTrue(e.applyTableBorders(SlideEditor.TableBorders.BOTTOM))
        assertEquals(red, look.line(t(), 1, 1, side[1])); assertEquals(red, look.line(t(), 2, 1, side[0])); assertEquals(red, look.line(t(), 2, 0, side[0]))
        // pptx keeps the merged cell's lines on the places it covers.
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(e.presentation, it) }.toByteArray()
        val b = PptxReader.read(pptx).slides.single().tables.single()
        for (r in 0 until 4) for (c in 0 until 3) assertEquals("$r,$c", look.sides(t(), r, c), look.sides(b, r, c))
    }

    @Test
    fun selectPicksABlockThatMergeCellsAndShadingWorkOn() {
        val e = SlideEditor().apply { load(deck(table())) }
        fun t() = e.presentation.slides.single().tables.single()
        t().cells[2][2].paragraph.runs.first().text = "Right"
        e.selectTable(t())
        // Select Row around the cell typed in; Merge Cells: one cell spanning it, the texts line by line.
        e.focus(t().cells[2][1])
        assertFalse("one cell is not a block to merge", e.canMergeTableCells)
        assertTrue(e.selectTableCells(SlideEditor.TableSelect.ROW))
        assertTrue(e.tableRange!!.contentEquals(intArrayOf(2, 0, 2, 2)))
        assertTrue(e.canMergeTableCells)
        assertTrue(e.mergeTableCells())
        assertEquals(1 to 3, t().spanOf(2, 0))
        assertEquals("Below\nRight", t().cells[2][0].text)
        assertEquals("", t().cells[2][2].text)
        assertEquals(null, e.tableRange)
        e.undo()
        assertEquals(1 to 1, t().spanOf(2, 0)); assertEquals("Right", t().cells[2][2].text)
        // A block meeting a merged cell takes all of it: Select Row on the second row is rows 1–2… of the merge (rows 0–1).
        e.selectTable(t()); e.focus(t().cells[1][2])
        e.selectTableCells(SlideEditor.TableSelect.ROW)
        assertTrue(e.tableRange!!.contentEquals(intArrayOf(0, 0, 1, 2)))
        // Shading on a picked column: just its cells.
        e.focus(t().cells[3][2]); e.selectTableCells(SlideEditor.TableSelect.COLUMN)
        assertTrue(e.setTableShading(0xFFFFE699))
        assertEquals(0xFFFFE699, t().cells[3][2].fill); assertEquals(0xFFFFE699, t().cells[0][2].fill); assertEquals(null, t().cells[3][1].fill)
        // Moving in a cell ends the block.
        e.setSelection(t().cells[3][2], 0, 0)
        e.setSelection(t().cells[3][2], 1, 1)
        assertEquals(null, e.tableRange)
    }

    private fun part(zip: ByteArray, name: String): String {
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val en = z.nextEntry ?: break
                if (en.name == name) return z.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("no $name")
    }
}
