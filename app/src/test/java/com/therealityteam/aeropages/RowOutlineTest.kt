package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.RowGroup
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Row outline (Data → Group): nesting rules, folding, level buttons, structural edits and xlsx / ods fidelity. */
class RowOutlineTest {

    private fun editor(rows: Int = 12): SheetEditor = SheetEditor().apply {
        load(Workbook.blank())
        for (r in 0 until rows) setCell(r, 0, "v${r + 1}")
    }
    private fun SheetEditor.selectRows(r1: Int, r2: Int) { select(r1, 0); extendSelection(r2 - r1, 0) }
    private fun groups(e: SheetEditor) = e.sheet.rowGroups.map { Triple(it.r1, it.r2, it.collapsed) }

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }
    private fun part(bytes: ByteArray, name: String): String {
        val z = ZipInputStream(ByteArrayInputStream(bytes))
        while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().decodeToString() }
        error("no $name")
    }

    @Test
    fun groupsNestFoldAndFollowLevelButtons() {
        val e = editor()
        e.selectRows(1, 6); assertTrue(e.groupRows())                  // rows 2–7
        e.selectRows(2, 4); assertTrue(e.groupRows())                  // rows 3–5 inside
        e.selectRows(1, 6); assertFalse("duplicate", e.groupRows())
        e.selectRows(5, 8); assertFalse("partial overlap", e.groupRows())
        assertEquals(2, e.maxOutlineLevel)
        assertEquals(listOf(Triple(1, 6, false), Triple(2, 4, false)), groups(e))
        assertEquals(2, e.sheet.rowLevel(3)); assertEquals(1, e.sheet.rowLevel(6)); assertEquals(0, e.sheet.rowLevel(7))

        // Collapse the inner group with the selection inside: it moves to the summary row below.
        e.select(3, 0)
        e.toggleRowGroup(e.sheet.rowGroups.first { it.r1 == 2 })
        assertEquals(setOf(2, 3, 4), e.invisibleRows)
        assertEquals("selection left the folded rows", 5, e.selRow)
        assertFalse("folding is not a filter", e.hasFilter)
        // Level buttons: 1 = only the outermost summary rows, 3 = everything open.
        e.showOutlineLevel(1)
        assertEquals((1..6).toSet(), e.invisibleRows)
        e.showOutlineLevel(3)
        assertTrue(e.invisibleRows.isEmpty())
        e.showOutlineLevel(2)
        assertEquals(setOf(2, 3, 4), e.invisibleRows)
        e.undo()
        assertTrue("undo restores the fold state", e.invisibleRows.isEmpty())

        // Ungroup removes the deepest group inside the selection, then the enclosing one.
        e.selectRows(1, 6); assertTrue(e.ungroupRows())
        assertEquals(listOf(Triple(1, 6, false)), groups(e))
        e.selectRows(3, 3); assertTrue("innermost group around the selection", e.ungroupRows())
        assertTrue(e.sheet.rowGroups.isEmpty())
        assertFalse(e.ungroupRows())
        e.undo(); e.undo()
        assertEquals(2, e.sheet.rowGroups.size)
        e.clearOutline()
        assertTrue(e.sheet.rowGroups.isEmpty())
    }

    @Test
    fun groupsFollowRowInsertionAndDeletion() {
        val e = editor()
        e.selectRows(2, 4); e.groupRows()                              // rows 3–5
        e.insertRowAt(3)                                               // inside → the group grows
        assertEquals(listOf(Triple(2, 5, false)), groups(e))
        e.insertRowAt(0)                                               // above → it moves down
        assertEquals(listOf(Triple(3, 6, false)), groups(e))
        e.deleteRowAt(4)
        assertEquals(listOf(Triple(3, 5, false)), groups(e))
        e.undo(); e.undo(); e.undo()
        assertEquals(listOf(Triple(2, 4, false)), groups(e))
    }

    @Test
    fun xlsxRoundTripKeepsNestingAndFoldsWithoutFakeFilters() {
        val e = editor()
        e.selectRows(1, 6); e.groupRows()
        e.selectRows(2, 4); e.groupRows()
        e.toggleRowGroup(e.sheet.rowGroups.first { it.r1 == 2 })
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(e.workbook, bos)
        val xml = part(bos.toByteArray(), "xl/worksheets/sheet1.xml")
        assertTrue(xml.contains("""outlineLevelRow="2""""))
        assertTrue(xml.contains("""<row r="4" hidden="1" outlineLevel="2">"""))
        assertTrue("summary row below the folded group", xml.contains("""<row r="6" outlineLevel="1" collapsed="1">"""))
        val back = XlsxReader.read(bos.toByteArray()).sheets[0]
        assertEquals(listOf(Triple(1, 6, false), Triple(2, 4, true)), back.rowGroups.map { Triple(it.r1, it.r2, it.collapsed) })
        assertTrue("folded rows are not filter-hidden", back.hiddenRows.isEmpty())
    }

    @Test
    fun excelStyleOutlineIsRebuiltFromRowLevels() {
        // Rows 2–7 level 1 with 3–5 at level 2, the inner block collapsed (hidden + summary collapsed="1").
        val rows = buildString {
            append("""<row r="1"><c r="A1" t="inlineStr"><is><t>Head</t></is></c></row>""")
            append("""<row r="2" outlineLevel="1"/>""")
            for (r in 3..5) append("""<row r="$r" hidden="1" outlineLevel="2"/>""")
            append("""<row r="6" outlineLevel="1" collapsed="1"/><row r="7" outlineLevel="1"/>""")
            append("""<row r="9" hidden="1"><c r="A9" t="inlineStr"><is><t>filtered</t></is></c></row>""")
        }
        val sheet = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetFormatPr defaultRowHeight="15" outlineLevelRow="2"/><sheetData>$rows</sheetData></worksheet>"""
        val wb = XlsxReader.read(zipOf(mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="S" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheet,
        )))
        val s = wb.sheets[0]
        assertEquals(listOf(Triple(1, 6, false), Triple(2, 4, true)), s.rowGroups.map { Triple(it.r1, it.r2, it.collapsed) })
        // Row 9 is hidden but not folded; with no autoFilter in the sheet it was hidden by hand.
        assertEquals("only the really hidden row stays hidden", setOf(8), s.manualHiddenRows)
        assertTrue("no filter owns it", s.hiddenRows.isEmpty())
    }

    @Test
    fun odsRoundTripWritesNestedRowGroups() {
        val e = editor()
        e.selectRows(1, 6); e.groupRows()
        e.selectRows(2, 4); e.groupRows()
        e.toggleRowGroup(e.sheet.rowGroups.first { it.r1 == 2 })
        val bos = ByteArrayOutputStream()
        OdsWriter.write(e.workbook, bos)
        val xml = part(bos.toByteArray(), "content.xml")
        assertEquals(2, Regex("<table:table-row-group").findAll(xml).count())
        assertEquals(2, Regex("</table:table-row-group>").findAll(xml).count())
        assertTrue(xml.contains("""<table:table-row-group table:display="false">"""))
        val back = OdsReader.read(bos.toByteArray()).sheets[0]
        assertEquals(listOf(Triple(1, 6, false), Triple(2, 4, true)), back.rowGroups.map { Triple(it.r1, it.r2, it.collapsed) })
        assertTrue(back.hiddenRows.isEmpty())
        assertEquals("v8", back.cell(7, 0)!!.text)
    }

    @Test
    fun odsRepeatedBlankRowsKeepLaterRowsInPlace() {
        // Calc writes runs of empty rows as one repeated row; the rows after the run must not move up.
        val content = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"><office:body><office:spreadsheet>
            <table:table table:name="S">
              <table:table-row><table:table-cell office:value-type="string"><text:p>top</text:p></table:table-cell></table:table-row>
              <table:table-row table:number-rows-repeated="3"><table:table-cell/></table:table-row>
              <table:table-row><table:table-cell office:value-type="string"><text:p>fifth</text:p></table:table-cell></table:table-row>
              <table:table-row-group>
                <table:table-row><table:table-cell office:value-type="string"><text:p>g1</text:p></table:table-cell></table:table-row>
                <table:table-row><table:table-cell office:value-type="string"><text:p>g2</text:p></table:table-cell></table:table-row>
              </table:table-row-group>
              <table:table-row table:number-rows-repeated="1048569"><table:table-cell/></table:table-row>
            </table:table></office:spreadsheet></office:body></office:document-content>"""
        val s = OdsReader.read(zipOf(mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to content))).sheets[0]
        assertEquals("top", s.cell(0, 0)!!.text)
        assertEquals("fifth", s.cell(4, 0)!!.text)
        assertEquals("g1", s.cell(5, 0)!!.text)
        assertEquals(listOf(RowGroup(5, 6).let { Triple(it.r1, it.r2, it.collapsed) }), s.rowGroups.map { Triple(it.r1, it.r2, it.collapsed) })
        assertTrue("a long trailing run does not inflate the sheet", s.rowCount < 100)
    }

    @Test
    fun adjacentGroupsAtTheSameLevelMergeLikeExcel() {
        val e = editor()
        e.selectRows(1, 3); assertTrue(e.groupRows())
        e.selectRows(4, 6); assertTrue(e.groupRows())
        assertEquals("touching level-1 blocks become one", listOf(Triple(1, 6, false)), groups(e))
        // Nested siblings merge inside their parent too; blocks under different parents stay apart.
        e.selectRows(1, 2); e.groupRows()
        e.selectRows(3, 4); e.groupRows()
        assertEquals(listOf(Triple(1, 6, false), Triple(1, 4, false)), groups(e))
        e.undo()
        assertEquals(listOf(Triple(1, 6, false), Triple(1, 2, false)), groups(e))
    }

    @Test
    fun subtotalBuildsTheTwoLevelOutline() {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        listOf("Region" to "Sales", "East" to "10", "East" to "20", "West" to "5", "West" to "7").forEachIndexed { i, (a, b) -> e.setCell(i, 0, a); e.setCell(i, 1, b) }
        e.select(0, 0); e.extendSelection(4, 1)
        assertEquals(3, e.subtotal(groupCol = 0, fn = 9, targetCols = listOf(1), headerRow = true))
        // Rows: 0 header, 1–2 East, 3 East Total, 4–5 West, 6 West Total, 7 Grand Total.
        assertEquals(listOf(Triple(1, 6, false), Triple(1, 2, false), Triple(4, 5, false)), groups(e))
        e.showOutlineLevel(2)
        assertEquals("level 2: the subtotal rows only", setOf(1, 2, 4, 5), e.invisibleRows)
        e.showOutlineLevel(1)
        assertEquals("level 1: header and grand total", (1..6).toSet(), e.invisibleRows)
        e.undo(); e.undo(); e.undo()                                   // both level changes, then the subtotal
        assertTrue("undoing the subtotal removes its outline", e.sheet.rowGroups.isEmpty())
    }
}
