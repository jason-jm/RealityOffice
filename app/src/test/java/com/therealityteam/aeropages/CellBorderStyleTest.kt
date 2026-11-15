package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetEditor.BorderPreset
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Border line styles and colours: Excel's Borders menu on a selection, and xlsx / ods keeping every line. */
class CellBorderStyleTest {

    private fun editor() = SheetEditor().apply { load(Workbook.blank()) }
    private fun SheetEditor.block() { select(1, 1); extendSelection(1, 1) }   // B2:C3
    private fun SheetEditor.sides(r: Int, c: Int) = sheet.cell(r, c)?.let { listOf(it.borderTop, it.borderBottom, it.borderLeft, it.borderRight) }
        ?: listOf(null, null, null, null)

    @Test
    fun presetsDrawTheirEdges() {
        val e = editor()
        e.block()
        e.applyBorders(BorderPreset.OUTSIDE)
        val t = BorderLine.THIN
        assertEquals(listOf(t, null, t, null), e.sides(1, 1))
        assertEquals(listOf(t, null, null, t), e.sides(1, 2))
        assertEquals(listOf(null, t, t, null), e.sides(2, 1))
        assertEquals(listOf(null, t, null, t), e.sides(2, 2))
        e.applyBorders(BorderPreset.NONE)
        e.applyBorders(BorderPreset.THICK_BOX)
        assertEquals(BorderLine.THICK, e.sheet.cell(2, 2)!!.borderRight)
        e.applyBorders(BorderPreset.NONE)
        e.applyBorders(BorderPreset.TOP_DOUBLE_BOTTOM)
        assertEquals(listOf(t, null, null, null), e.sides(1, 1))
        assertEquals(listOf(null, BorderLine.DOUBLE, null, null), e.sides(2, 2))
        // The pen: every line of the block in a red dashed line.
        e.applyBorders(BorderPreset.NONE)
        e.borderPen = BorderLine(LineStyle.DASHED, 0xFFFF0000)
        e.applyBorders(BorderPreset.ALL)
        val red = BorderLine(LineStyle.DASHED, 0xFFFF0000)
        for (r in 1..2) for (c in 1..2) assertEquals(listOf(red, red, red, red), e.sides(r, c))
        e.applyBorders(BorderPreset.BOTTOM_DOUBLE)
        assertEquals("thick and double keep the pen's colour", BorderLine(LineStyle.DOUBLE, 0xFFFF0000), e.sheet.cell(2, 1)!!.borderBottom)
        e.undo()
        assertEquals("one undo step", red, e.sheet.cell(2, 1)!!.borderBottom)
        e.applyBorders(BorderPreset.NONE)
        assertEquals(listOf(null, null, null, null), e.sides(1, 1))
    }

    @Test
    fun theBooleanSidesStillWork() {
        val e = editor()
        val cell = e.sheet.ensure(0, 0)
        cell.bTop = true
        assertEquals(BorderLine.THIN, cell.borderTop)
        cell.borderBottom = BorderLine.DOUBLE
        cell.bBottom = true
        assertEquals("setting a side keeps its line", BorderLine.DOUBLE, cell.borderBottom)
        cell.bBottom = false
        assertNull(cell.borderBottom)
    }

    @Test
    fun aProtectedSheetRefusesBorders() {
        val e = editor()
        e.toggleProtection()
        e.block()
        e.applyBorders(BorderPreset.ALL)
        assertNull(e.sheet.cell(1, 1)?.borderTop)
    }

    private fun styledBook(styles: List<LineStyle>): Workbook {
        val wb = Workbook.blank()
        styles.forEachIndexed { i, st ->
            wb.sheets[0].ensure(i, 0).apply {
                text = st.name
                borderBottom = BorderLine(st)
                borderTop = BorderLine(st, 0xFF1F4E79)
            }
        }
        return wb
    }

    private fun entry(bytes: ByteArray, name: String): String =
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.first { it.name == name }; z.readBytes().decodeToString() }

    @Test
    fun xlsxKeepsEveryLineStyleAndColour() {
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(styledBook(LineStyle.entries), bos)
        val styles = entry(bos.toByteArray(), "xl/styles.xml")
        assertTrue(styles.contains("""<bottom style="double"><color auto="1"/></bottom>"""))
        assertTrue(styles.contains("""<top style="mediumDashDot"><color rgb="FF1F4E79"/></top>"""))
        val back = XlsxReader.read(bos.toByteArray()).sheets[0]
        LineStyle.entries.forEachIndexed { i, st ->
            assertEquals(BorderLine(st), back.cell(i, 0)!!.borderBottom)
            assertEquals(BorderLine(st, 0xFF1F4E79), back.cell(i, 0)!!.borderTop)
        }
    }

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test
    fun excelsIndexedAndBlackColours() {
        val styles = """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""" +
            """<fonts count="1"><font><sz val="11"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>""" +
            """<borders count="2"><border/><border><left style="medium"><color indexed="10"/></left><right style="thin"><color rgb="FF000000"/></right>""" +
            """<top style="thick"><color theme="1"/></top><bottom style="none"/></border></borders>""" +
            """<cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="0" borderId="1" applyBorder="1"/></cellXfs></styleSheet>"""
        val sheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" s="1"><v>1</v></c></row></sheetData></worksheet>"""
        val wb = XlsxReader.read(zipOf(mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/styles.xml" to styles,
            "xl/worksheets/sheet1.xml" to sheet,
        )))
        val a1 = wb.sheets[0].cell(0, 0)!!
        assertEquals("indexed 10 is the palette's red", BorderLine(LineStyle.MEDIUM, 0xFFFF0000), a1.borderLeft)
        assertEquals("black reads as automatic", BorderLine.THIN, a1.borderRight)
        assertEquals("a theme colour falls back to automatic", BorderLine.THICK, a1.borderTop)
        assertNull("style none is no border", a1.borderBottom)
    }

    @Test
    fun odsKeepsTheLinesItCanSay() {
        val kept = listOf(LineStyle.HAIR, LineStyle.THIN, LineStyle.DOTTED, LineStyle.DASHED, LineStyle.MEDIUM, LineStyle.MEDIUM_DASHED, LineStyle.THICK, LineStyle.DOUBLE)
        val bos = ByteArrayOutputStream()
        OdsWriter.write(styledBook(kept), bos)
        val content = entry(bos.toByteArray(), "content.xml")
        assertTrue(content.contains("""fo:border-bottom="2.01pt double #000000""""))
        assertTrue(content.contains("""style:border-line-width-bottom="""))
        assertTrue(content.contains("""fo:border-top="2.49pt solid #1F4E79""""))
        val back = OdsReader.read(bos.toByteArray()).sheets[0]
        kept.forEachIndexed { i, st ->
            assertEquals(BorderLine(st), back.cell(i, 0)!!.borderBottom)
            assertEquals(BorderLine(st, 0xFF1F4E79), back.cell(i, 0)!!.borderTop)
        }
    }

    @Test
    fun pasteFormatsBringsTheLines() {
        val e = editor()
        e.select(0, 0); e.applyBorders(BorderPreset.BOTTOM_DOUBLE)
        e.copySelection()
        e.select(3, 3); e.pasteSpecial(SheetEditor.PasteWhat.FORMATS)
        assertEquals(BorderLine.DOUBLE, e.sheet.cell(3, 3)!!.borderBottom)
    }
}
