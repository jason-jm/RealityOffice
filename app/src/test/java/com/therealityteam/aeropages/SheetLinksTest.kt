package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.CellLinks
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Links from cells to places in their workbook (Excel's `#Sheet2!A1`), and HYPERLINK formulas, through editing, xlsx and ODS. */
class SheetLinksTest {

    /** An index sheet linking to a cell, a range on a sheet whose name needs quotes, a defined name, an address; the data sheets. */
    private fun workbook(): Workbook {
        val index = Sheet(name = "Index")
        val data = Sheet(name = "Data")
        val q3 = Sheet(name = "Q3 Sales")
        index.ensure(0, 0).apply { text = "Totals"; link = "#Data!B3" }
        index.ensure(1, 0).apply { text = "Third quarter"; link = "#'Q3 Sales'!A1:C4" }
        index.ensure(2, 0).apply { text = "The total"; link = "#Total" }
        index.ensure(3, 0).apply { text = "Site"; link = "https://example.com/" }
        index.ensure(4, 0).text = "=HYPERLINK(\"#Data!A1\",\"Back to data\")"
        data.ensure(2, 1).text = "42"
        q3.ensure(0, 0).text = "Q3"
        return Workbook(mutableListOf(index, data, q3), names = mutableMapOf("Total" to "Data!B3"))
    }

    private fun links(wb: Workbook) = wb.sheets[0].cells.entries.sortedBy { it.key }.mapNotNull { e -> e.value.link?.let { e.value.text to it } }

    @Test
    fun odfAndExcelNamePlacesAlike() {
        assertEquals("#Data!B3", CellLinks.fromOdf("#Data.B3"))
        assertEquals("#'Q3 Sales'!A1:C4", CellLinks.fromOdf("#'Q3 Sales'.A1:'Q3 Sales'.C4"))
        assertEquals("#Data!A1:B5", CellLinks.fromOdf("#\$Data.\$A\$1:.\$B\$5"))
        assertEquals("#Total", CellLinks.fromOdf("#Total"))
        assertEquals("https://example.com/#x", CellLinks.fromOdf("https://example.com/#x"))
        assertEquals("#Data.B3", CellLinks.toOdf("#Data!B3", "Index"))
        assertEquals("#'Q3 Sales'.A1:C4", CellLinks.toOdf("#'Q3 Sales'!A1:C4", "Index"))
        assertEquals("#'Q3 Sales'!A1:C4", CellLinks.fromOdf("#'Q3 Sales'.A1:C4"))
        // LibreOffice's xlsx (after an ODS) names a range's sheet both ways; a defined name with a dot stays.
        assertEquals("'Q3 Sales'!A1:C4", CellLinks.excelPlace("'Q3 Sales'.A1:'Q3 Sales'!C4"))
        assertEquals("Sales.Q1", CellLinks.excelPlace("Sales.Q1"))
        assertEquals("Data!B3", CellLinks.excelPlace("Data!B3"))
        assertEquals("#Index.C2", CellLinks.toOdf("#C2", "Index"))
        assertEquals("#Total", CellLinks.toOdf("#Total", "Index"))
        assertEquals("#'Q3 Sales'!A1", CellLinks.toPlace("Q3 Sales", "A1"))
        assertEquals("#Data!A1", CellLinks.toPlace("Data", "A1"))
        // A HYPERLINK formula's first argument, as written (quotes, nesting, and the friendly name after it).
        assertEquals("\"#Data!A1\"", CellLinks.hyperlinkLocation("=HYPERLINK(\"#Data!A1\",\"Go\")"))
        assertEquals("\"#\"&A1&\"!A1\"", CellLinks.hyperlinkLocation("= hyperlink ( \"#\"&A1&\"!A1\" )"))
        assertEquals("IF(B1,\"a,b\",C1)", CellLinks.hyperlinkLocation("=HYPERLINK(IF(B1,\"a,b\",C1),\"x\")"))
        assertNull(CellLinks.hyperlinkLocation("=SUM(A1:A3)"))
    }

    @Test
    fun linksGoToTheirPlace() {
        val e = SheetEditor()
        e.load(workbook())
        e.select(0, 0)
        assertEquals("#Data!B3", e.activeLink)
        assertTrue(e.followLink(e.activeLink!!))
        assertEquals(1, e.sheetIndex)
        assertEquals(2 to 1, e.selRow to e.selCol)
        // A range on a sheet whose name is quoted; a defined name; a cell on the sheet itself.
        assertTrue(e.followLink("#'Q3 Sales'!A1:C4"))
        assertEquals(2, e.sheetIndex)
        assertEquals(listOf(0, 0, 3, 2), listOf(e.rangeRowMin(), e.rangeColMin(), e.rangeRowMax(), e.rangeColMax()))
        assertTrue(e.followLink("#Total"))
        assertEquals(1 to (2 to 1), e.sheetIndex to (e.selRow to e.selCol))
        assertTrue(e.followLink("#D7"))
        assertEquals(1 to (6 to 3), e.sheetIndex to (e.selRow to e.selCol))
        // Nowhere: an address, a sheet gone, a name not defined (and no name is defined by trying).
        assertFalse(e.followLink("https://example.com/"))
        assertFalse(e.followLink("#Gone!A1"))
        assertFalse(e.followLink("#NoSuchName"))
        assertFalse("NoSuchName" in e.workbook.names)
        // A HYPERLINK formula's cell links where its formula says.
        e.selectSheet(0)
        e.select(4, 0)
        assertEquals("#Data!A1", e.activeLink)
        // Linking an empty cell to a place shows the place.
        e.select(6, 0)
        e.setLink("#Data!B3")
        assertEquals("Data!B3", e.sheet.cell(6, 0)?.text)
    }

    private fun partOf(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val en = z.nextEntry ?: break; if (en.name == name) return z.readBytes().toString(Charsets.UTF_8) }
        }
        error("no $name")
    }

    @Test
    fun placesGoThroughXlsxAndOds() {
        val wb = workbook()
        val expected = links(wb)
        val xlsx = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val sheet1 = partOf(xlsx, "xl/worksheets/sheet1.xml")
        assertTrue(sheet1.contains("""location="Data!B3" display="Totals""""))
        assertTrue(sheet1.contains("""location="'Q3 Sales'!A1:C4""""))
        // Only the address takes a relationship.
        assertEquals(1, Regex("relationships/hyperlink").findAll(partOf(xlsx, "xl/worksheets/_rels/sheet1.xml.rels")).count())
        assertEquals(expected, links(XlsxReader.read(xlsx)))
        val ods = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()
        assertTrue(partOf(ods, "content.xml").contains("""xlink:href="#Data.B3""""))
        assertEquals(expected, links(OdsReader.read(ods)))
    }

    @Test
    fun excelsLocationsAndAnAddressWithAPlace() {
        val main = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        val r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val parts = mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook xmlns="$main" xmlns:r="$r"><sheets><sheet name="One" sheetId="1" r:id="rId1"/><sheet name="Two" sheetId="2" r:id="rId2"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" Type="$r/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="$r/worksheet" Target="worksheets/sheet2.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="$main" xmlns:r="$r"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>to two</t></is></c></row>""" +
                """<row r="2"><c r="A2" t="inlineStr"><is><t>docs</t></is></c></row></sheetData>""" +
                """<hyperlinks><hyperlink ref="A1" location="Two!C3" display="to two"/><hyperlink ref="A2" r:id="rId7" location="intro"/></hyperlinks></worksheet>""",
            "xl/worksheets/_rels/sheet1.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId7" Type="$r/hyperlink" Target="https://example.com/doc" TargetMode="External"/></Relationships>""",
            "xl/worksheets/sheet2.xml" to """<worksheet xmlns="$main"><sheetData/></worksheet>""",
        )
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z -> parts.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        }.toByteArray()
        val wb = XlsxReader.read(bytes)
        assertEquals(listOf("to two" to "#Two!C3", "docs" to "https://example.com/doc#intro"), links(wb))
    }
}
