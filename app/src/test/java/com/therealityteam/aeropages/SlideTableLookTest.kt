package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideTable
import com.therealityteam.aeropages.model.SlideTableLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A slide table's look, shared by the canvas, the show and the PDF: fills by style and options, header text defaults. */
class SlideTableLookTest {
    private fun table(rows: Int, cols: Int) = SlideTable(MutableList(rows) { r -> MutableList(cols) { c -> SlideBox("r${r}c$c", CharacterFormat(fontSizePt = 14f)) } })

    @Test
    fun cellsTakeTheirStylesFillsByTheOptions() {
        val t = table(4, 3).apply { styleIndex = 2; headerRow = true; bandedRows = true; totalRow = true; firstColumn = true }
        val style = SlideTableLook.STYLES[2]
        assertEquals(style.header, SlideTableLook.fill(t, 0, 1))          // header row
        assertEquals(style.header, SlideTableLook.fill(t, 3, 1))          // total row
        assertEquals(style.band, SlideTableLook.fill(t, 2, 0))            // first column
        assertEquals(style.band, SlideTableLook.fill(t, 1, 1))            // banded row
        assertEquals(style.plain, SlideTableLook.fill(t, 2, 1))
        // Banded columns, the last column; an unknown style falls back to the first.
        t.bandedRows = false; t.bandedColumns = true; t.firstColumn = false; t.lastColumn = true
        assertEquals(style.band, SlideTableLook.fill(t, 2, 1))
        assertEquals(style.band, SlideTableLook.fill(t, 2, 2))
        t.styleIndex = 99
        assertEquals(SlideTableLook.STYLES[0], SlideTableLook.styleOf(t))
    }

    @Test
    fun headerAndTotalRowsDefaultToWhiteBoldText() {
        val t = table(3, 2).apply { headerRow = true; totalRow = true }
        val d = SlideTableLook.textDefaults(t, 0)!!
        assertEquals(0xFFFFFFFF, d.color); assertTrue(d.bold == true)
        assertEquals(d.color, SlideTableLook.textDefaults(t, 2)?.color)
        assertNull(SlideTableLook.textDefaults(t, 1))
        t.headerRow = false
        assertNull(SlideTableLook.textDefaults(t, 0))
    }

    /** With `-Daero.sampleDir`: a deck with a styled table whose cells carry their own formatting and a link, for the show and the PDF. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { java.io.File(it).also { d -> d.mkdirs() } } ?: return
        val deck = sampleDeck()
        java.io.File(dir, "table-look.pptx").outputStream().use { com.therealityteam.aeropages.format.pptx.PptxWriter.write(deck, it) }
        java.io.File(dir, "table-look.odp").outputStream().use { com.therealityteam.aeropages.format.odf.OdpWriter.write(deck, it) }
    }

    @Test
    fun shadingGoesOnTheCellOrTheWholeTableAndThroughBothFormats() {
        val deck = sampleDeck()
        val e = com.therealityteam.aeropages.editor.SlideEditor().apply { load(deck) }
        val t = e.presentation.slides[0].tables.single()
        e.selectTable(t)
        // The cell being typed in: just that cell; one undo step.
        e.focus(t.cells[1][0])
        assertTrue(e.setTableShading(0xFFFFE699))
        assertEquals(0xFFFFE699, SlideTableLook.fill(t, 1, 0))
        assertEquals(SlideTableLook.styleFill(t, 1, 1), SlideTableLook.fill(t, 1, 1))
        e.undo()
        assertNull(e.presentation.slides[0].tables.single().cells[1][0].fill)
        // No cell being typed in: every cell; No Fill is transparent.
        val t2 = e.presentation.slides[0].tables.single()
        e.selectTable(t2)   // the table picked, not a cell typed in
        assertTrue(e.setTableShading(0L))
        assertTrue(shownCells(t2).all { it.fill == 0L })
        // Through pptx and ODP: a cell's own colour, No Fill, and the style still found though its first cell is shaded.
        val mixed = sampleDeck().also { d -> val x = d.slides[0].tables.single(); x.cells[0][0].fill = 0xFFFFE699; x.cells[2][1].fill = 0L }
        val pptx = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.pptx.PptxWriter.write(mixed, it) }.toByteArray()
        val odp = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdpWriter.write(mixed, it) }.toByteArray()
        for (back in listOf(com.therealityteam.aeropages.format.pptx.PptxReader.read(pptx), com.therealityteam.aeropages.format.odf.OdpReader.read(odp))) {
            val b = back.slides[0].tables.single()
            assertEquals(2, b.styleIndex)
            assertEquals(0xFFFFE699, b.cells[0][0].fill)
            assertEquals(0L, b.cells[2][1].fill)
            assertNull("a cell in the style's colour has no shading of its own", b.cells[1][0].fill)
        }
    }

    @Test
    fun bordersDrawWithThePenOnTheCellOrTheTableAndThroughBothFormats() {
        val look = SlideTableLook
        val side = SlideTableLook.Side.entries   // TOP, BOTTOM, LEFT, RIGHT
        val e = com.therealityteam.aeropages.editor.SlideEditor().apply { load(sampleDeck()) }
        fun t() = e.presentation.slides[0].tables.single()
        assertEquals("a plain edge is the style's grid", look.styleLine(t()), look.line(t(), 1, 1, side[0]))
        assertEquals("the double rule above the total", com.therealityteam.aeropages.model.LineStyle.DOUBLE, look.line(t(), 3, 1, side[0])?.style)
        e.selectTable(t())
        val pen = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.DASHED, 2.25f, 0xFFC00000)
        e.tablePen = pen
        // The picked table: its outside, inside left as it was.
        assertTrue(e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.OUTSIDE))
        assertEquals(pen, look.line(t(), 0, 0, side[0])); assertEquals(pen, look.line(t(), 3, 2, side[1])); assertEquals(pen, look.line(t(), 1, 0, side[2]))
        assertEquals(look.styleLine(t()), look.line(t(), 1, 1, side[0]))
        assertFalse("drawn already: nothing to do", e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.OUTSIDE))
        // The cell typed in: its top, which the cell above shares; again takes it away.
        e.focus(t().cells[2][1])
        assertTrue(e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.TOP))
        assertEquals(pen, look.line(t(), 2, 1, side[0])); assertEquals(pen, look.line(t(), 1, 1, side[1]))
        assertEquals("its other sides as they were", look.styleLine(t()), look.line(t(), 2, 1, side[2]))
        assertTrue(e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.TOP))
        assertNull(look.line(t(), 2, 1, side[0])); assertNull(look.line(t(), 1, 1, side[1]))
        // Inside vertical on one cell is nothing; all borders on it are its four sides.
        assertFalse(e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.INSIDE_V))
        // Each change one undo step.
        e.undo(); e.undo()
        assertEquals(look.styleLine(t()), look.line(t(), 1, 1, side[1]))
        assertEquals(pen, look.line(t(), 0, 0, side[0]))
        e.undo()
        assertEquals(look.styleLine(t()), look.line(t(), 0, 0, side[0]))
        // Through pptx and ODP every edge comes back as it draws: a pen line, none, the double rule, the grid.
        e.selectTable(t()); e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.OUTSIDE)
        e.focus(t().cells[1][1]); e.tablePen = pen.copy(color = 0xFF000000, style = com.therealityteam.aeropages.model.LineStyle.THIN)
        e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.RIGHT)
        e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.TOP); e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.TOP)
        val deck = e.presentation
        val pptx = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.pptx.PptxWriter.write(deck, it) }.toByteArray()
        val odp = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdpWriter.write(deck, it) }.toByteArray()
        val a = deck.slides[0].tables.single()
        for (back in listOf(com.therealityteam.aeropages.format.pptx.PptxReader.read(pptx), com.therealityteam.aeropages.format.odf.OdpReader.read(odp))) {
            val b = back.slides[0].tables.single()
            for (r in 0 until a.rows) for (c in 0 until a.cols) assertEquals("cell $r,$c", look.sides(a, r, c), look.sides(b, r, c))
        }
        // A cell whose lines are all the grid comes back with no borders of its own.
        val plain = sampleDeck()
        val pp = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.pptx.PptxWriter.write(plain, it) }.toByteArray()
        val po = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdpWriter.write(plain, it) }.toByteArray()
        for (back in listOf(com.therealityteam.aeropages.format.pptx.PptxReader.read(pp), com.therealityteam.aeropages.format.odf.OdpReader.read(po))) {
            val b = back.slides[0].tables.single()
            assertNull(b.cells[1][1].borders); assertNull(b.cells[0][0].borders)
            assertEquals(com.therealityteam.aeropages.model.LineStyle.DOUBLE, b.cells[3][1].borders?.top?.style)
        }
    }

    @Test
    fun anInsertedRowTakesItsNeighboursShadingAndBorders() {
        val look = SlideTableLook
        val e = com.therealityteam.aeropages.editor.SlideEditor().apply { load(sampleDeck()) }
        val t = e.presentation.slides[0].tables.single()
        e.selectTable(t)
        e.tablePen = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.THIN, 3f, 0xFFC00000)
        e.applyTableBorders(com.therealityteam.aeropages.editor.SlideEditor.TableBorders.OUTSIDE)
        e.focus(t.cells[3][0])
        e.addTableRow(below = true)
        val n = e.presentation.slides[0].tables.single()
        assertEquals(5, n.rows)
        assertEquals("the outside still closes under the new last row", e.tablePen, look.line(n, 4, 0, SlideTableLook.Side.BOTTOM))
        e.focus(n.cells[2][0])
        e.addTableRow(below = true)
        assertEquals("a row under the shaded cell is shaded too", 0xFFFFE699, e.presentation.slides[0].tables.single().cells[3][0].fill)
    }

    @Test
    fun cellSizeSetsARowsHeightAndAColumnsWidthAndBothFormatsKeepThem() {
        val e = com.therealityteam.aeropages.editor.SlideEditor().apply { load(sampleDeck()) }
        fun t() = e.presentation.slides[0].tables.single()
        val slide = e.presentation.slideSize
        e.selectTable(t())
        e.focus(t().cells[1][2])   // the notes column, row 1
        val w0 = e.tableColWidthPt!!; val h0 = e.tableRowHeightPt!!
        val tableW = t().wPct * slide.widthPt
        val first = t().colShare(0) * tableW
        assertTrue(e.setTableColWidth(w0 + 72f))
        assertEquals(w0 + 72f, e.tableColWidthPt!!, 0.05f)
        assertEquals("the other columns keep their width; the table grows", tableW + 72f, t().wPct * slide.widthPt, 0.05f)
        assertEquals(first, t().colShare(0) * t().wPct * slide.widthPt, 0.05f)
        assertTrue(e.setTableRowHeight(h0 * 2))
        assertEquals(h0 * 2, e.tableRowHeightPt!!, 0.05f)
        assertFalse("no change, no step", e.setTableRowHeight(h0 * 2))
        // A column put in beside a sized one takes its width; deleting it takes its weight away.
        e.addTableColumn(left = false)
        assertEquals(4, t().colWeights.size)
        assertEquals(t().colShare(2), t().colShare(3), 0.0001f)
        e.undo()
        assertEquals(3, t().colWeights.size)
        // Through pptx and ODP the shares come back; a table all alike keeps none.
        val deck = e.presentation
        val pptx = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.pptx.PptxWriter.write(deck, it) }.toByteArray()
        val odp = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdpWriter.write(deck, it) }.toByteArray()
        val a = deck.slides[0].tables.single()
        for (back in listOf(com.therealityteam.aeropages.format.pptx.PptxReader.read(pptx), com.therealityteam.aeropages.format.odf.OdpReader.read(odp))) {
            val b = back.slides[0].tables.single()
            for (c in 0 until a.cols) assertEquals("column $c", a.colShare(c), b.colShare(c), 0.002f)
            for (r in 0 until a.rows) assertEquals("row $r", a.rowShare(r), b.rowShare(r), 0.002f)
        }
        // Distribute: all alike again, one step each, then nothing to do.
        e.selectTable(t())
        assertTrue(e.distributeTable(rows = false)); assertTrue(e.distributeTable(rows = true))
        assertFalse(e.distributeTable(rows = true))
        assertTrue(t().colWeights.isEmpty() && t().rowWeights.isEmpty())
        val plain = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.pptx.PptxWriter.write(e.presentation, it) }.toByteArray()
        assertTrue(com.therealityteam.aeropages.format.pptx.PptxReader.read(plain).slides[0].tables.single().colWeights.isEmpty())
        e.undo()
        assertEquals("undoing Distribute Rows brings the row back", h0 * 2, t().rowShare(1) * t().hPct * slide.heightPt, 0.05f)
    }

    @Test
    fun aCellsTextSitsTopMiddleOrBottomThroughBothFormats() {
        val top = com.therealityteam.aeropages.model.ShapeAnchor.TOP
        val bottom = com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM
        val e = com.therealityteam.aeropages.editor.SlideEditor().apply { load(sampleDeck()) }
        fun t() = e.presentation.slides[0].tables.single()
        e.selectTable(t())
        // The table picked: every cell; the cell typed in: just it. One step each.
        assertTrue(e.setTableCellAnchor(bottom))
        assertTrue(shownCells(t()).all { it.cellAnchor == bottom })
        assertFalse("already so", e.setTableCellAnchor(bottom))
        e.focus(t().cells[2][2])
        assertTrue(e.setTableCellAnchor(com.therealityteam.aeropages.model.ShapeAnchor.CENTER))
        assertNull("the middle is the app's own", t().cells[2][2].cellAnchor)
        assertEquals(bottom, t().cells[2][1].cellAnchor)
        assertEquals(com.therealityteam.aeropages.model.ShapeAnchor.CENTER, e.tableCellAnchor)
        e.undo(); e.undo()
        assertEquals(top, t().cells[1][2].cellAnchor); assertNull(t().cells[0][0].cellAnchor)
        // pptx and ODP keep top, middle and bottom.
        t().cells[2][1].cellAnchor = bottom
        val deck = e.presentation
        val pptx = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.pptx.PptxWriter.write(deck, it) }.toByteArray()
        val odp = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdpWriter.write(deck, it) }.toByteArray()
        for (back in listOf(com.therealityteam.aeropages.format.pptx.PptxReader.read(pptx), com.therealityteam.aeropages.format.odf.OdpReader.read(odp))) {
            val b = back.slides[0].tables.single()
            assertEquals(top, b.cells[1][2].cellAnchor); assertEquals(bottom, b.cells[2][1].cellAnchor); assertNull(b.cells[0][0].cellAnchor)
        }
        // PowerPoint leaves anchor out for the top (ECMA-376's default): such a cell reads from the top.
        val bare = java.io.ByteArrayOutputStream()
        java.util.zip.ZipInputStream(pptx.inputStream()).use { zin ->
            java.util.zip.ZipOutputStream(bare).use { zout ->
                while (true) {
                    val en = zin.nextEntry ?: break
                    var bytes = zin.readBytes()
                    if (en.name == "ppt/slides/slide1.xml") bytes = String(bytes, Charsets.UTF_8).replace(Regex(""" anchor="(ctr|t|b)"(?= marL| ?>)"""), "").toByteArray()
                    zout.putNextEntry(java.util.zip.ZipEntry(en.name)); zout.write(bytes); zout.closeEntry()
                }
            }
        }
        val plain = com.therealityteam.aeropages.format.pptx.PptxReader.read(bare.toByteArray()).slides[0].tables.single()
        val cover = plain.coverage()
        assertTrue((0 until plain.rows).all { r -> (0 until plain.cols).all { c -> plain.covered(cover, r, c) || plain.cells[r][c].cellAnchor == top } })
    }

    /** The cells a table shows (not the places a merged cell covers). */
    private fun shownCells(t: SlideTable): List<SlideBox> {
        val cover = t.coverage()
        return (0 until t.rows).flatMap { r -> (0 until t.cols).filter { c -> !t.covered(cover, r, c) }.map { c -> t.cells[r][c] } }
    }

    companion object {
        /**
         * Regional results: a table in style 2 with header, banded and total rows, a bold green figure, a long note that
         * wraps and a link — the sample the show, PDF and round-trip audits use.
         */
        fun sampleDeck(): com.therealityteam.aeropages.model.Presentation {
            val head = listOf("Region", "Q3", "Notes")
            val rows = listOf(listOf("North", "1,240", "Ahead of plan after the new store opened in August"), listOf("South", "980", "See the report"), listOf("Total", "2,220", ""))
            fun cell(text: String, f: CharacterFormat = CharacterFormat(fontSizePt = 16f)) = SlideBox(text, f)
            val cells = mutableListOf(head.map { cell(it) }.toMutableList())
            rows.forEachIndexed { i, r -> cells += r.mapIndexed { c, v ->
                when {
                    i == 0 && c == 1 -> cell(v, CharacterFormat(fontSizePt = 16f, bold = true, color = 0xFF2E7D32))
                    i == 1 && c == 2 -> SlideBox("", CharacterFormat(fontSizePt = 16f)).also { b ->
                        b.paragraph.runs.clear()
                        b.paragraph.runs += com.therealityteam.aeropages.model.TextRun("See the ", CharacterFormat(fontSizePt = 16f))
                        b.paragraph.runs += com.therealityteam.aeropages.model.TextRun("report", CharacterFormat(fontSizePt = 16f, link = "https://example.com/report"))
                    }
                    else -> cell(v)
                }
            }.toMutableList() }
            val t = SlideTable(cells, styleIndex = 2, headerRow = true, bandedRows = true, totalRow = true, xPct = 0.08f, yPct = 0.25f, wPct = 0.84f, hPct = 0.55f)
            t.colWeights.addAll(listOf(1f, 0.8f, 2.2f))   // a wide notes column (Table Layout ▸ Cell Size)
            cells[1][2].cellAnchor = com.therealityteam.aeropages.model.ShapeAnchor.TOP   // the long note from the top (Table Layout ▸ Alignment)
            cells[3][1].colSpan = 2   // the total across Q3 and Notes (a merged cell)
            cells[2][0].fill = 0xFFFFE699   // one cell shaded over the style (Table Design ▸ Shading)
            // A double rule above the total row (Table Design ▸ Borders): the cells on both sides of the edge say so.
            val grid = com.therealityteam.aeropages.model.SlideTableLook.styleLine(t)
            val rule = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.DOUBLE, 0.75f, 0xFF1F3864)
            for (c in 0 until 3) {
                cells[2][c].borders = com.therealityteam.aeropages.model.CellBorders(grid, rule, grid, grid)
                cells[3][c].borders = com.therealityteam.aeropages.model.CellBorders(rule, grid, grid, grid)
            }
            val slide = com.therealityteam.aeropages.model.Slide(title = SlideBox("Regional results", CharacterFormat(fontSizePt = 36f, bold = true)))
            slide.tables += t
            return com.therealityteam.aeropages.model.Presentation(mutableListOf(slide))
        }
    }

    /** A deck whose one table has every option, a style, and cells with their own look, alignment and links. */
    private fun richDeck(): com.therealityteam.aeropages.model.Presentation {
        val other = com.therealityteam.aeropages.model.Slide(title = SlideBox("Two", CharacterFormat(fontSizePt = 36f)))
        val t = table(3, 3).apply { styleIndex = 5; headerRow = true; totalRow = true; firstColumn = true; lastColumn = true; bandedRows = false; bandedColumns = true }
        t.cells[1][1] = SlideBox("", CharacterFormat(fontSizePt = 14f)).also { b ->
            b.paragraph.runs.clear()
            b.paragraph.runs += com.therealityteam.aeropages.model.TextRun("Up ", CharacterFormat(fontSizePt = 18f, bold = true, color = 0xFF2E7D32))
            b.paragraph.runs += com.therealityteam.aeropages.model.TextRun("12%", CharacterFormat(fontSizePt = 18f, italic = true, link = "https://example.com/q3"))
            b.paragraph.format.align = com.therealityteam.aeropages.model.TextAlign.CENTER
        }
        t.cells[2][2] = SlideBox("see two", CharacterFormat(fontSizePt = 14f, link = com.therealityteam.aeropages.model.SlideLinks.toSlide(other)))
        val first = com.therealityteam.aeropages.model.Slide(title = SlideBox("One", CharacterFormat(fontSizePt = 36f)))
        first.tables += t
        return com.therealityteam.aeropages.model.Presentation(mutableListOf(first, other))
    }

    @Test
    fun aTableKeepsItsStyleOptionsAndItsCellsLookThroughPptx() {
        val out = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.pptx.PptxWriter.write(richDeck(), it) }.toByteArray()
        val xml = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(out)).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "ppt/slides/slide1.xml" }.let { z.readBytes().decodeToString() }
        }
        assertTrue(xml, xml.contains("""<a:tblPr firstRow="1" firstCol="1" lastRow="1" lastCol="1" bandRow="0" bandCol="1"/>"""))
        assertTrue("half-point borders in the style's colour", xml.contains("""<a:lnL w="6350"><a:solidFill><a:srgbClr val="C8D9A3"/>"""))
        assertTrue("header text white and bold by the cell's list style", xml.contains("""<a:defRPr b="1"><a:solidFill><a:srgbClr val="FFFFFF"/>"""))
        val p = com.therealityteam.aeropages.format.pptx.PptxReader.read(out)
        val t = p.slides[0].tables.single()
        assertEquals(listOf(true, true, true, true, false, true), listOf(t.headerRow, t.totalRow, t.firstColumn, t.lastColumn, t.bandedRows, t.bandedColumns))
        assertEquals(5, t.styleIndex)
        val runs = t.cells[1][1].paragraph.runs
        assertEquals(listOf("Up ", "12%"), runs.map { it.text })
        assertTrue(runs[0].format.bold); assertEquals(0xFF2E7D32, runs[0].format.color); assertEquals(18f, runs[0].format.fontSizePt)
        assertTrue(runs[1].format.italic); assertEquals("https://example.com/q3", runs[1].format.link)
        assertEquals(com.therealityteam.aeropages.model.TextAlign.CENTER, t.cells[1][1].align)
        assertEquals(1, com.therealityteam.aeropages.model.SlideLinks.indexIn(p, t.cells[2][2].paragraph.runs.single().format.link))
        // A header cell's text is not made white in its runs: it takes the row's look, so it reads again if the header goes.
        assertNull(t.cells[0][0].paragraph.runs.single().format.color)
        assertTrue(!t.cells[0][0].paragraph.runs.single().format.bold)
    }

    @Test
    fun aTableKeepsItsStyleOptionsAndItsCellsLookThroughOdp() {
        val out = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdpWriter.write(richDeck(), it) }.toByteArray()
        val xml = java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(out)).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "content.xml" }.let { z.readBytes().decodeToString() }
        }
        assertTrue(xml, xml.contains("""table:use-first-row-styles="true" table:use-last-row-styles="true""""))
        assertTrue("cells name their fill as Impress writes it", xml.contains("""style:family="table-cell"><loext:graphic-properties draw:fill="solid" draw:fill-color="#9BBB59""""))
        val p = com.therealityteam.aeropages.format.odf.OdpReader.read(out)
        val t = p.slides[0].tables.single()
        assertEquals(listOf(true, true, true, true, false, true), listOf(t.headerRow, t.totalRow, t.firstColumn, t.lastColumn, t.bandedRows, t.bandedColumns))
        assertEquals(5, t.styleIndex)
        val runs = t.cells[1][1].paragraph.runs
        assertEquals(listOf("Up ", "12%"), runs.map { it.text })
        assertTrue(runs[0].format.bold); assertEquals(0xFF2E7D32, runs[0].format.color)
        assertTrue(runs[1].format.italic); assertEquals("https://example.com/q3", runs[1].format.link)
        assertEquals(com.therealityteam.aeropages.model.TextAlign.CENTER, t.cells[1][1].align)
        assertNull(t.cells[0][0].paragraph.runs.single().format.color)
    }

    /** LibreOffice's own ODP of a table writes no style options: they come from what its fills show. */
    @Test
    fun libreOfficesTableFillsGiveItsStyle() {
        val look = SlideTableLook.STYLES[2]
        fun hex(c: Long) = String.format(java.util.Locale.ROOT, "#%06x", c and 0xFFFFFF)
        val content = """<?xml version="1.0" encoding="UTF-8"?><office:document-content ${com.therealityteam.aeropages.format.odf.OdfPackage.NS} office:version="1.3">""" +
            """<office:automatic-styles>""" +
            listOf("ce1" to look.header, "ce2" to look.band, "ce3" to look.plain).joinToString("") { (n, c) ->
                """<style:style style:name="$n" style:family="table-cell"><loext:graphic-properties draw:fill="solid" draw:fill-color="${hex(c)}"/></style:style>""" } +
            """</office:automatic-styles><office:body><office:presentation><draw:page draw:name="page1"><draw:frame svg:x="1in" svg:y="1in" svg:width="6in" svg:height="2in"><table:table>""" +
            """<table:table-column/><table:table-row table:default-cell-style-name="ce1"><table:table-cell><text:p>Head</text:p></table:table-cell></table:table-row>""" +
            """<table:table-row table:default-cell-style-name="ce2"><table:table-cell><text:p>a</text:p></table:table-cell></table:table-row>""" +
            """<table:table-row table:default-cell-style-name="ce3"><table:table-cell><text:p>b</text:p></table:table-cell></table:table-row>""" +
            """<table:table-row table:default-cell-style-name="ce1"><table:table-cell><text:p>Total</text:p></table:table-cell></table:table-row>""" +
            """</table:table></draw:frame></draw:page></office:presentation></office:body></office:document-content>"""
        val zip = java.io.ByteArrayOutputStream().also { bo -> java.util.zip.ZipOutputStream(bo).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("mimetype")); z.write("application/vnd.oasis.opendocument.presentation".toByteArray())
            z.putNextEntry(java.util.zip.ZipEntry("content.xml")); z.write(content.toByteArray())
        } }.toByteArray()
        val t = com.therealityteam.aeropages.format.odf.OdpReader.read(zip).slides[0].tables.single()
        assertEquals(2, t.styleIndex)
        assertTrue(t.headerRow); assertTrue(t.totalRow); assertTrue(t.bandedRows)
        assertEquals("Head", t.cells[0][0].text)
    }

    /**
     * With `-Daero.tableLo=<dir>`: LibreOffice's pptx of our table-look.odp (`table-look.pptx`) and its ODP of our
     * table-look.pptx (`back/table-look.odp`) read back in style 2, with the header and total rows and the cells' look.
     */
    @Test
    fun libreOfficesTablesComeBack() {
        val dir = System.getProperty("aero.tableLo")?.let { java.io.File(it) }?.takeIf { it.isDirectory } ?: return
        val fromPptx = com.therealityteam.aeropages.format.pptx.PptxReader.read(java.io.File(dir, "table-look.pptx").readBytes()).slides[0].tables.single()
        val fromOdp = com.therealityteam.aeropages.format.odf.OdpReader.read(java.io.File(dir, "back/table-look.odp").readBytes()).slides[0].tables.single()
        for (t in listOf(fromPptx, fromOdp)) {
            assertEquals(2, t.styleIndex)
            assertTrue(t.headerRow); assertTrue(t.totalRow); assertTrue(t.bandedRows)
            val n = t.cells[1][1].paragraph.runs.first { it.text.isNotBlank() }
            assertEquals("1,240", n.text.trim()); assertTrue(n.format.bold); assertEquals(0xFF2E7D32, n.format.color)
            assertEquals("https://example.com/report", t.cells[2][2].paragraph.runs.first { it.text.contains("report") }.format.link)
        }
    }
}
