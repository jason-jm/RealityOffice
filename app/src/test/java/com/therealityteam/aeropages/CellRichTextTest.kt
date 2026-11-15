package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.ModelDiff
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Mixed formatting inside a cell: Excel's rich runs (`<si><r><rPr>` shared strings, `<is><r>`
 * inline strings) and Calc's `text:span`s become [com.therealityteam.aeropages.model.Cell.runs],
 * round-trip through xlsx and ods, and are dropped once the text is edited.
 */
class CellRichTextTest {

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun richWorkbook(): Workbook {
        val wb = Workbook.blank()
        val c = wb.sheets[0].ensure(0, 0)
        c.text = "Bold red plain"
        c.runs = mutableListOf(
            TextRun("Bold ", CharacterFormat(bold = true)),
            TextRun("red ", CharacterFormat(color = 0xFFFF0000, fontSizePt = 14f, fontFamily = "Arial")),
            TextRun("plain", CharacterFormat()),
        )
        wb.sheets[0].ensure(0, 1).text = "just text"
        return wb
    }

    @Test
    fun xlsxRoundTripKeepsRuns() {
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(richWorkbook(), bos)
        val back = XlsxReader.read(bos.toByteArray())
        val c = back.sheets[0].cell(0, 0)!!
        assertEquals("Bold red plain", c.text)
        val runs = c.richRuns()
        assertNotNull("rich runs survive", runs)
        assertEquals(3, runs!!.size)
        assertTrue(runs[0].format.bold); assertEquals("Bold ", runs[0].text)
        assertEquals(0xFFFF0000, runs[1].format.color); assertEquals(14f, runs[1].format.fontSizePt); assertEquals("Arial", runs[1].format.fontFamily)
        assertFalse(runs[2].format.bold)
        assertNull("plain cells carry no runs", back.sheets[0].cell(0, 1)!!.runs)
    }

    @Test
    fun excelSharedStringsWithRunsAreRead() {
        val sst = """<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
            <si><r><rPr><b/><sz val="11"/><color rgb="FF0000FF"/><rFont val="Calibri"/></rPr><t>Total</t></r><r><rPr><i/><sz val="11"/></rPr><t xml:space="preserve"> (est.)</t></r></si>
            <si><t>Plain</t></si></sst>"""
        val sheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c>
            <c r="C1" t="inlineStr"><is><r><rPr><strike/></rPr><t>old</t></r><r><t> new</t></r></is></c></row></sheetData></worksheet>"""
        val wb = XlsxReader.read(zipOf(mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/sharedStrings.xml" to sst,
            "xl/worksheets/sheet1.xml" to sheet,
        )))
        val s = wb.sheets[0]
        val a1 = s.cell(0, 0)!!
        assertEquals("Total (est.)", a1.text)
        val runs = a1.richRuns()!!
        assertEquals(2, runs.size)
        assertTrue(runs[0].format.bold); assertEquals(0xFF0000FF, runs[0].format.color); assertEquals("Calibri", runs[0].format.fontFamily)
        assertTrue(runs[1].format.italic); assertEquals(" (est.)", runs[1].text)
        assertNull("plain shared string", s.cell(0, 1)!!.runs)
        val c1 = s.cell(0, 2)!!
        assertEquals("old new", c1.text)
        assertTrue("inline rich run", c1.richRuns()!![0].format.strikethrough)
        assertFalse(c1.richRuns()!![1].format.strikethrough)
    }

    @Test
    fun odsRoundTripKeepsRunsAsSpans() {
        val bos = ByteArrayOutputStream()
        OdsWriter.write(richWorkbook(), bos)
        val bytes = bos.toByteArray()
        val back = OdsReader.read(bytes)
        val c = back.sheets[0].cell(0, 0)!!
        assertEquals("Bold red plain", c.text)
        val runs = c.richRuns()
        assertNotNull(runs)
        assertEquals(3, runs!!.size)
        assertTrue(runs[0].format.bold)
        assertEquals(0xFFFF0000, runs[1].format.color); assertEquals(14f, runs[1].format.fontSizePt)
        assertNull(back.sheets[0].cell(0, 1)!!.runs)
    }

    @Test
    fun editingTheTextDropsTheRunsAndTheDiffSeesThem() {
        val e = SheetEditor()
        e.load(richWorkbook())
        assertNotNull(e.sheet.cell(0, 0)!!.richRuns())
        e.setCell(0, 0, "Bold red plain")          // unchanged text keeps the formatting
        assertNotNull(e.sheet.cell(0, 0)!!.richRuns())
        e.setCell(0, 0, "changed")
        assertNull(e.sheet.cell(0, 0)!!.runs)
        e.undo()
        assertEquals("Bold red plain", e.sheet.cell(0, 0)!!.text)
        assertNotNull("undo restores the runs", e.sheet.cell(0, 0)!!.richRuns())
        val a = richWorkbook().sheets[0].cell(0, 0)!!
        val b = a.copyOf()
        assertTrue(ModelDiff.sameCell(a, b))
        b.runs!![0].format.bold = false
        assertFalse(ModelDiff.sameCell(a, b))
        // Stale runs (text changed underneath by a bulk edit) are ignored rather than shown wrongly.
        val stale = richWorkbook().sheets[0].cell(0, 0)!!
        stale.text = "other"
        assertNull(stale.richRuns())
    }

    @Test
    fun formattingASelectionInsideTheCellMakesRuns() {
        val e = SheetEditor()
        e.load(Workbook.blank())
        e.setCell(0, 0, "Total 42")
        // Bold the word "Total" through the formula bar's selection.
        e.setTextSelection(0, 5)
        e.toggleBold()
        val runs = e.sheet.cell(0, 0)!!.richRuns()!!
        assertEquals(listOf("Total", " 42"), runs.map { it.text })
        assertTrue(runs[0].format.bold); assertFalse(runs[1].format.bold)
        assertFalse("the cell's own format is untouched", e.sheet.cell(0, 0)!!.format.bold)
        // Colour the number: a second range, merged with the existing runs.
        e.setTextSelection(6, 8)
        e.setColor(0xFFFF0000)
        val runs2 = e.sheet.cell(0, 0)!!.richRuns()!!
        assertEquals(listOf("Total", " ", "42"), runs2.map { it.text })
        assertEquals(0xFFFF0000, runs2[2].format.color)
        // Bold everything (no partial selection): applies to the cell and every run, keeping the mix.
        e.setTextSelection(0, 0)
        e.toggleItalic()
        assertTrue(e.sheet.cell(0, 0)!!.format.italic)
        assertTrue(e.sheet.cell(0, 0)!!.richRuns()!!.all { it.format.italic })
        // Un-bolding "Total" makes the runs agree again → back to one format, no runs.
        e.setTextSelection(0, 5)
        e.toggleBold()
        e.setTextSelection(6, 8)
        e.setColor(null)
        assertNull("uniform again", e.sheet.cell(0, 0)!!.runs)
        assertTrue(e.sheet.cell(0, 0)!!.format.italic)
        // Undo walks back through every step.
        e.undo(); e.undo()
        assertEquals(listOf("Total", " ", "42"), e.sheet.cell(0, 0)!!.richRuns()!!.map { it.text })
        // A formula never gets partial formatting.
        e.setCell(1, 0, "=1+1")
        e.select(1, 0); e.setTextSelection(1, 2)
        e.toggleBold()
        assertNull(e.sheet.cell(1, 0)!!.runs)
        assertTrue(e.sheet.cell(1, 0)!!.format.bold)
    }
}
