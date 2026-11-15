package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What other spreadsheets make of AeroDesk's xlsx and the other way round (checked against LibreOffice and Excel's units). */
class XlsxInteropTest {

    private fun write(wb: Workbook): ByteArray = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()

    /** [bytes] with its first worksheet rewritten by [edit]. */
    private fun patched(bytes: ByteArray, edit: (String) -> String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            OfficeXml.unzip(bytes).forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                z.write(if (name == "xl/worksheets/sheet1.xml") edit(String(data, Charsets.UTF_8)).toByteArray() else data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun columnWidthsInExcelsUnits() {
        val s = Sheet(name = "S")
        s.ensure(0, 0).text = "x"
        s.colWidths[1] = 2f
        val bytes = write(Workbook(mutableListOf(s)))
        val xml = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        // Excel's default column is 8.43 characters, stored with its padding as 9.140625 (64 px): twice that is 128 px.
        assertTrue(xml, xml.contains("""<col min="2" max="2" width="18.2813" customWidth="1"/>"""))
        assertEquals(2f, XlsxReader.read(bytes).sheets[0].colWidths[1]!!, 0.001f)
    }

    @Test
    fun sizesThatOnlyRestateTheDefaultsAreNoSizes() {
        val s = Sheet(name = "S")
        for (r in 0..3) s.ensure(r, 0).text = "row $r"
        val bytes = patched(write(Workbook(mutableListOf(s)))) { xml ->
            // What LibreOffice writes: the sheet's defaults, every row's fitted height and every column's width.
            xml.replace(Regex("<row r=\"1\">"), """<row r="1" ht="13.8" customHeight="false">""")
                .replace(Regex("<row r=\"2\">"), """<row r="2" ht="31.5" customHeight="false">""")
                .replace(Regex("<row r=\"3\">"), """<row r="3" ht="12" customHeight="true">""")
                .replace("<sheetData>", """<sheetFormatPr defaultColWidth="7.89453125" defaultRowHeight="12.8"/><cols><col min="1" max="1" width="7.89453125" customWidth="false"/><col min="2" max="2" width="20" customWidth="false"/></cols><sheetData>""")
        }
        val back = XlsxReader.read(bytes).sheets[0]
        assertNull("a fitted height of one line of text is the default", back.rowHeights[0])
        assertNull("a height not marked custom is fitted again from the cells", back.rowHeights[1])
        assertEquals("a custom height is kept, however small", 0.8f, back.rowHeights[2]!!, 0.001f)
        assertNull("the sheet's default width, written out, is no width of its own", back.colWidths[0])
        assertEquals(20 / 9.140625f, back.colWidths[1]!!, 0.001f)
    }

    @Test
    fun richRunsSpellOutTheirFont() {
        val s = Sheet(name = "S")
        val cell = s.ensure(0, 0)
        cell.text = "Bold red plain"
        cell.runs = mutableListOf(
            TextRun("Bold ", CharacterFormat(bold = true)),
            TextRun("red ", CharacterFormat(color = 0xFFFF0000)),
            TextRun("plain", CharacterFormat(fontFamily = "Georgia", fontSizePt = 14f)),
        )
        val bytes = write(Workbook(mutableListOf(s)))
        val xml = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        // Every run names its size and face (LibreOffice read runs without them as Cambria and the cell as bold).
        assertTrue(xml, xml.contains("""<r><rPr><b/><sz val="11"/><rFont val="Calibri"/></rPr><t xml:space="preserve">Bold </t></r>"""))
        assertTrue(xml, xml.contains("""<r><rPr><sz val="14"/><rFont val="Georgia"/></rPr><t xml:space="preserve">plain</t></r>"""))
        val runs = XlsxReader.read(bytes).sheets[0].cell(0, 0)!!.runs!!
        assertNull("the cell's own face is inherited, not the run's", runs[0].format.fontFamily)
        assertNull(runs[0].format.fontSizePt)
        assertEquals("Georgia", runs[2].format.fontFamily)
        assertEquals(14f, runs[2].format.fontSizePt)
    }
}
