package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Whether a table draws borders — Word's Normal Table draws none — kept through every format. */
class TableBordersTest {
    private val w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private fun docx(body: String, styles: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            fun put(n: String, c: String) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""")
            put("word/document.xml", """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="$w"><w:body>$body<w:p/></w:body></w:document>""")
            styles?.let { put("word/styles.xml", it) }
        }
        return out.toByteArray()
    }

    private fun tbl(pr: String, tcPr: String = "") = "<w:tbl><w:tblPr>$pr</w:tblPr><w:tblGrid><w:gridCol w:w=\"3000\"/></w:tblGrid>" +
        "<w:tr><w:tc><w:tcPr>$tcPr</w:tcPr><w:p><w:r><w:t>x</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"

    private fun table(bytes: ByteArray) = DocxReader.read(ByteArrayInputStream(bytes)).blocks.filterIsInstance<TableBlock>().single().table

    /** Whether any of [t]'s cells draws a line. */
    private fun draws(t: Table) = t.rows.any { r -> r.cells.any { c -> !t.bordersOf(c).none } }

    @Test
    fun wordTablesBordersFromTheTableItsStyleOrItsCells() {
        val styles = """<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="$w">""" +
            """<w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/></w:style>""" +
            """<w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4"/><w:insideV w:val="single" w:sz="4"/></w:tblBorders></w:tblPr></w:style></w:styles>"""
        assertFalse("Word's Normal Table draws none", draws(table(docx(tbl(""), styles))))
        assertTrue("Table Grid draws them", draws(table(docx(tbl("""<w:tblStyle w:val="TableGrid"/>"""), styles))))
        assertTrue("the table's own", draws(table(docx(tbl("""<w:tblBorders><w:top w:val="single" w:sz="4"/></w:tblBorders>""")))))
        assertFalse("the table's own, all nil", draws(table(docx(tbl("""<w:tblStyle w:val="TableGrid"/><w:tblBorders><w:top w:val="nil"/><w:left w:val="none"/></w:tblBorders>"""), styles))))
        assertTrue("a cell's own", draws(table(docx(tbl("", """<w:tcBorders><w:bottom w:val="single" w:sz="4"/></w:tcBorders>""")))))
    }

    private fun sample(borders: Boolean) = Document(mutableListOf()).also { d ->
        d.blocks.add(TableBlock(Table(mutableListOf(
            TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("wide")), gridSpan = 2))),
            TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("a"))), TableCell(mutableListOf(Paragraph.of("b"))))),
        ), borders = borders, columnWidths = mutableListOf(1f, 1f))))
        d.addParagraph(Paragraph.of(""))
    }

    @Test
    fun bordersAndMergesSurviveDocxAndOdt() {
        for (borders in listOf(true, false)) {
            val viaDocx = table(ByteArrayOutputStream().also { DocxWriter.write(sample(borders), it) }.toByteArray())
            assertEquals("docx keeps borders=$borders", borders, viaDocx.borders)
            val odt = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(sample(borders), it) }.toByteArray())
            val viaOdt = odt.blocks.filterIsInstance<TableBlock>().single().table
            assertEquals("ODT keeps borders=$borders", borders, viaOdt.borders)
            assertEquals("a cell across two columns stays one cell", listOf(1, 2), viaOdt.rows.map { it.cells.size })
            assertEquals(2, viaOdt.rows[0].cells[0].gridSpan)
        }
    }

    @Test
    fun aCellSpanningAcrossAndDownSurvivesOdt() {
        val d = Document(mutableListOf())
        d.blocks.add(TableBlock(Table(mutableListOf(
            TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("block")), gridSpan = 2, rowSpan = 2), TableCell(mutableListOf(Paragraph.of("c"))))),
            TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("")), gridSpan = 2, vMerged = true), TableCell(mutableListOf(Paragraph.of("d"))))),
            TableRow(mutableListOf(TableCell(mutableListOf(Paragraph.of("e"))), TableCell(mutableListOf(Paragraph.of("f"))), TableCell(mutableListOf(Paragraph.of("g"))))),
        ), columnWidths = mutableListOf(1f, 1f, 1f))))
        d.addParagraph(Paragraph.of(""))
        val t = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()).blocks.filterIsInstance<TableBlock>().single().table
        assertEquals(listOf(2, 2, 3), t.rows.map { it.cells.size })
        assertEquals(2, t.rows[0].cells[0].rowSpan); assertEquals(2, t.rows[0].cells[0].gridSpan)
        assertTrue(t.rows[1].cells[0].vMerged); assertEquals(2, t.rows[1].cells[0].gridSpan)
        assertEquals("d", t.rows[1].cells[1].content.single().plainText())
    }

    private val blue = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.THIN, 1.5f, 0xFF1F4E79)
    private val rule = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.THIN, 0.75f)

    /** A 2×2 table under [pr], its cells' tcPr [tcPr] (row by row). */
    private fun grid(pr: String, vararg tcPr: String) = "<w:tbl><w:tblPr>$pr</w:tblPr><w:tblGrid><w:gridCol w:w=\"3000\"/><w:gridCol w:w=\"3000\"/></w:tblGrid>" +
        (0 until 2).joinToString("") { r -> "<w:tr>" + (0 until 2).joinToString("") { c -> "<w:tc><w:tcPr>${tcPr.getOrElse(r * 2 + c) { "" }}</w:tcPr><w:p><w:r><w:t>$r$c</w:t></w:r></w:p></w:tc>" } + "</w:tr>" } +
        "</w:tbl>"

    @Test
    fun wordCellBordersAreReadSideBySide() {
        // A "booktabs" table: thick blue rules above and below, a thin one under the header row, no verticals;
        // the second header cell double red on its right, the last cell dotted on its left.
        val t = table(docx(grid(
            """<w:tblBorders><w:top w:val="single" w:sz="12" w:color="1F4E79"/><w:bottom w:val="single" w:sz="12" w:color="1F4E79"/><w:insideH w:val="nil"/></w:tblBorders>""",
            """<w:tcBorders><w:bottom w:val="single" w:sz="6"/></w:tcBorders>""",
            """<w:tcBorders><w:bottom w:val="single" w:sz="6"/><w:right w:val="double" w:sz="4" w:color="FF0000"/></w:tcBorders>""",
            "",
            """<w:tcBorders><w:start w:val="dotted" w:sz="4"/></w:tcBorders>""",
        )))
        assertFalse("not one line everywhere", t.borders)
        val c = t.rows.map { r -> r.cells.map { it.borders!! } }
        assertEquals(com.therealityteam.aeropages.model.CellBorders(top = blue, bottom = rule), c[0][0])
        assertEquals(com.therealityteam.aeropages.model.CellBorders(top = blue, bottom = rule,
            right = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.DOUBLE, 0.5f, 0xFFFF0000)), c[0][1])
        // The edge under the header is the header cells' own line for the row below too (a cell's border before the table's).
        assertEquals(rule, c[1][0].top)
        assertEquals(blue, c[1][0].bottom)
        // The last cell's dotted left is its neighbour's right as well.
        val dotted = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.DOTTED, 0.5f)
        assertEquals(dotted, c[1][1].left)
        assertEquals(dotted, c[1][0].right)
        assertEquals(null, c[0][0].left)
    }

    @Test
    fun oneLineEverywhereIsTheTablesGrid() {
        val sides = listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { """<w:$it w:val="single" w:sz="8" w:color="808080"/>""" }
        val t = table(docx(grid("<w:tblBorders>$sides</w:tblBorders>")))
        assertTrue(t.borders)
        assertEquals(com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.THIN, 1f, 0xFF808080), t.gridLine)
        assertTrue(t.rows.all { r -> r.cells.all { it.borders == null } })
        // Back to Word as one tblBorders, the same line on every side.
        val xml = String(com.therealityteam.aeropages.format.OfficeXml.unzip(ByteArrayInputStream(ByteArrayOutputStream().also {
            DocxWriter.write(Document(mutableListOf(TableBlock(t), com.therealityteam.aeropages.model.ParagraphBlock(Paragraph.of("")))), it)
        }.toByteArray()))["word/document.xml"]!!)
        assertTrue(xml.contains("""<w:insideV w:val="single" w:sz="8" w:space="0" w:color="808080"/>"""))
        assertFalse(xml.contains("<w:tcBorders>"))
    }

    /** A table whose cells each have their own borders: a double outer frame, dashed inside, one thick green bottom. */
    private fun bordered(): Document {
        val frame = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.DOUBLE, 0.5f)
        val dashed = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.DASHED, 0.5f, 0xFF7F7F7F)
        val green = com.therealityteam.aeropages.model.CellBorder(com.therealityteam.aeropages.model.LineStyle.THIN, 3f, 0xFF00B050)
        val t = Table.of(2, 2)
        t.rows.forEachIndexed { r, row -> row.cells.forEachIndexed { c, cell ->
            cell.content[0] = Paragraph.of("$r$c")
            cell.borders = com.therealityteam.aeropages.model.CellBorders(
                top = if (r == 0) frame else dashed, bottom = if (r == 1) (if (c == 1) green else frame) else dashed,
                left = if (c == 0) frame else dashed, right = if (c == 1) frame else dashed)
        } }
        t.borders = false
        return Document(mutableListOf(TableBlock(t), com.therealityteam.aeropages.model.ParagraphBlock(Paragraph.of(""))))
    }

    @Test
    fun eachCellsBordersGoThroughDocxAndOdt() {
        val want = (bordered().blocks[0] as TableBlock).table.rows.map { r -> r.cells.map { it.borders } }
        val viaDocx = table(ByteArrayOutputStream().also { DocxWriter.write(bordered(), it) }.toByteArray())
        assertEquals(want, viaDocx.rows.map { r -> r.cells.map { it.borders } })
        val viaOdt = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(bordered(), it) }.toByteArray()).blocks.filterIsInstance<TableBlock>().single().table
        assertEquals(want, viaOdt.rows.map { r -> r.cells.map { it.borders } })
    }

    @Test
    fun theBordersMenuDrawsSidesWithTheirNeighbours() {
        val e = com.therealityteam.aeropages.editor.DocumentEditor().apply { load(Document(mutableListOf(TableBlock(Table.of(3, 3).also { it.borders = false }), com.therealityteam.aeropages.model.ParagraphBlock(Paragraph.of(""))))) }
        val t = (e.document.blocks[0] as TableBlock).table
        e.focusParagraph(4)   // the middle cell
        e.borderLine = rule
        e.applyTableBorders(com.therealityteam.aeropages.editor.DocumentEditor.TableBorderPreset.BOTTOM)
        assertEquals(rule, t.rows[1].cells[1].borders!!.bottom)
        assertEquals("the cell below shares the line", rule, t.rows[2].cells[1].borders!!.top)
        assertEquals(null, t.rows[1].cells[1].borders!!.top)
        // Outside borders: the table's edges only, the inside ones as they were.
        e.applyTableBorders(com.therealityteam.aeropages.editor.DocumentEditor.TableBorderPreset.OUTSIDE)
        assertEquals(rule, t.rows[0].cells[0].borders!!.top); assertEquals(rule, t.rows[2].cells[2].borders!!.right)
        assertEquals(null, t.rows[0].cells[0].borders!!.right)
        assertEquals(rule, t.rows[1].cells[1].borders!!.bottom)
        // All borders: one line everywhere, the table's grid of it; undone, the outline again.
        e.applyTableBorders(com.therealityteam.aeropages.editor.DocumentEditor.TableBorderPreset.ALL)
        val after = (e.document.blocks[0] as TableBlock).table
        assertTrue(after.borders); assertEquals(rule, after.gridLine)
        e.undo()
        val back = (e.document.blocks[0] as TableBlock).table
        assertFalse(back.borders)
        assertEquals(rule, back.rows[0].cells[0].borders!!.top)
        e.applyTableBorders(com.therealityteam.aeropages.editor.DocumentEditor.TableBorderPreset.NONE)
        val none = (e.document.blocks[0] as TableBlock).table
        assertFalse(none.borders); assertTrue(none.rows.all { r -> r.cells.all { it.borders == null } })
    }
}
