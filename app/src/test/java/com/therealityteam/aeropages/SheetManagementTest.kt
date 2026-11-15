package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xls.XlsReader
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetState
import com.therealityteam.aeropages.model.SheetTable
import com.therealityteam.aeropages.model.ValidationType
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

/**
 * The sheet tab's commands — rename, delete, move, copy, hide / unhide, tab colour — and how hidden
 * sheets and tab colours travel through xlsx, ODS and .xls.
 */
class SheetManagementTest {

    private fun editor(vararg names: String, setup: (Workbook) -> Unit = {}): SheetEditor {
        val wb = Workbook(names.map { Sheet(name = it) }.toMutableList())
        setup(wb)
        return SheetEditor().apply { load(wb) }
    }

    private fun SheetEditor.text(sheet: Int, r: Int, c: Int): String? = workbook.sheets[sheet].cell(r, c)?.text

    // --- rename ---------------------------------------------------------------

    @Test fun renameRewritesEveryReferenceToTheSheet() {
        val e = editor("Data", "Calc") { wb ->
            val calc = wb.sheets[1]
            calc.ensure(0, 0).text = "=SUM(Data!A1:A3)+'Data'!B1+data!C1"
            calc.ensure(1, 0).text = "=\"Data!A1\"&Data!\$A:\$A"
            calc.validations += DataValidation(2, 0, 9, 0, ValidationType.LIST, formula1 = "Data!\$A\$1:\$A\$3")
            wb.names["Totals"] = "Data!\$B\$1:\$B\$3"
        }
        assertTrue(e.renameSheet(0, "Q1 Sales"))
        assertEquals("Q1 Sales", e.workbook.sheets[0].name)
        assertEquals("=SUM('Q1 Sales'!A1:A3)+'Q1 Sales'!B1+'Q1 Sales'!C1", e.text(1, 0, 0))
        assertEquals("text in quotes stays", "=\"Data!A1\"&'Q1 Sales'!\$A:\$A", e.text(1, 1, 0))
        assertEquals("'Q1 Sales'!\$A\$1:\$A\$3", e.workbook.sheets[1].validations[0].formula1)
        assertEquals("'Q1 Sales'!\$B\$1:\$B\$3", e.workbook.names["Totals"])
        e.undo()
        assertEquals("Data", e.workbook.sheets[0].name)
        assertEquals("=SUM(Data!A1:A3)+'Data'!B1+data!C1", e.text(1, 0, 0))
        assertEquals("Data!\$B\$1:\$B\$3", e.workbook.names["Totals"])
        e.redo()
        assertEquals("'Q1 Sales'!\$A\$1:\$A\$3", e.workbook.sheets[1].validations[0].formula1)
    }

    @Test fun renameFollowsNamesInAnyScript() {
        val e = editor("シート1", "数据", "Calc") { wb -> wb.sheets[2].ensure(0, 0).text = "=シート1!A1+数据!B2" }
        assertTrue(e.renameSheet(0, "Data"))
        assertTrue(e.renameSheet(1, "Summe 2"))
        assertEquals("=Data!A1+'Summe 2'!B2", e.text(2, 0, 0))
    }

    @Test fun renameRefusesNamesExcelDoesNotAllow() {
        val e = editor("Data", "Calc")
        for (bad in listOf("", "   ", "a".repeat(32), "Q1/Q2", "a:b", "x?", "[x]", "'quoted", "end'", "History", "history")) {
            assertFalse(bad, e.renameSheet(0, bad))
            assertEquals(bad, SheetEditor.SheetRefusal.BAD_NAME, e.sheetRefusal)
        }
        assertFalse(e.renameSheet(0, "calc"))
        assertEquals(SheetEditor.SheetRefusal.NAME_TAKEN, e.sheetRefusal)
        assertTrue("31 characters is fine", e.renameSheet(0, "b".repeat(31)))
        assertNull(e.sheetRefusal)
        assertTrue("a change of case is a rename", e.renameSheet(1, "CALC"))
        assertEquals("CALC", e.workbook.sheets[1].name)
    }

    // --- delete ---------------------------------------------------------------

    @Test fun deletingASheetTurnsItsReferencesIntoRefErrors() {
        val e = editor("Data", "Calc", "Other") { wb ->
            wb.sheets[1].ensure(0, 0).text = "=Data!A1+1"
            wb.sheets[1].ensure(1, 0).text = "=SUM('Data'!A1:B2)*Other!A1"
            wb.names["Src"] = "Data!\$A\$1"
        }
        e.selectSheet(2)
        assertTrue(e.deleteSheet(0))
        assertEquals(listOf("Calc", "Other"), e.workbook.sheets.map { it.name })
        assertEquals("=#REF!+1", e.text(0, 0, 0))
        assertEquals("=SUM(#REF!)*Other!A1", e.text(0, 1, 0))
        assertEquals("#REF!", e.workbook.names["Src"])
        assertEquals("the active sheet stays the one shown", "Other", e.sheet.name)
        e.undo()
        assertEquals(listOf("Data", "Calc", "Other"), e.workbook.sheets.map { it.name })
        assertEquals("=Data!A1+1", e.text(1, 0, 0))
        assertEquals("Data!\$A\$1", e.workbook.names["Src"])
    }

    @Test fun theLastSheetShowingCannotBeDeletedOrHidden() {
        val e = editor("Only", "Gone") { wb -> wb.sheets[1].state = SheetState.HIDDEN }
        assertFalse(e.deleteSheet(0))
        assertEquals(SheetEditor.SheetRefusal.LAST_VISIBLE, e.sheetRefusal)
        assertFalse(e.hideSheet(0))
        assertEquals(SheetEditor.SheetRefusal.LAST_VISIBLE, e.sheetRefusal)
        assertTrue("a hidden sheet can go", e.deleteSheet(1))
        assertEquals(listOf("Only"), e.workbook.sheets.map { it.name })
    }

    @Test fun deletingTheActiveSheetShowsTheNextOne() {
        val e = editor("A", "B", "C", "D") { wb -> wb.sheets[2].state = SheetState.HIDDEN }
        e.selectSheet(1)
        assertTrue(e.deleteSheet(1))
        assertEquals("the hidden C is skipped", "D", e.sheet.name)
        e.selectSheet(e.workbook.sheets.indexOfFirst { it.name == "D" })
        assertTrue(e.deleteSheet(e.sheetIndex))
        assertEquals("the last one gone, the one before shows", "A", e.sheet.name)
    }

    // --- move -----------------------------------------------------------------

    @Test fun movingSkipsHiddenSheetsAndUndoes() {
        val e = editor("A", "B", "C") { wb -> wb.sheets[1].state = SheetState.HIDDEN }
        assertTrue(e.moveSheet(0, 1))
        assertEquals(listOf("B", "C", "A"), e.workbook.sheets.map { it.name })
        assertEquals("A", e.sheet.name)
        assertFalse("nothing further right", e.moveSheet(e.sheetIndex, 1))
        assertTrue(e.moveSheet(e.sheetIndex, -1))
        assertEquals(listOf("B", "A", "C"), e.workbook.sheets.map { it.name })
        e.undo(); e.undo()
        assertEquals(listOf("A", "B", "C"), e.workbook.sheets.map { it.name })
        assertFalse("not past the first", e.moveSheet(0, -1))
    }

    @Test fun formulasStillReadAMovedSheet() {
        val e = editor("Data", "Calc") { wb ->
            wb.sheets[0].ensure(0, 0).text = "5"
            wb.sheets[1].ensure(0, 0).text = "=Data!A1*2"
        }
        e.selectSheet(1)
        assertTrue(e.moveSheet(1, -1))
        assertEquals(listOf("Calc", "Data"), e.workbook.sheets.map { it.name })
        assertEquals("Calc", e.sheet.name)
        assertEquals("10", e.display(0, 0))
    }

    // --- copy -----------------------------------------------------------------

    @Test fun aCopyComesRightAfterWithANumberedName() {
        val e = editor("Sales", "Other") { wb -> wb.sheets[0].ensure(0, 0).text = "x"; wb.sheets[0].tabColor = 0xFF00B050 }
        assertTrue(e.duplicateSheet(0))
        assertEquals(listOf("Sales", "Sales (2)", "Other"), e.workbook.sheets.map { it.name })
        assertEquals("the copy is the active sheet", 1, e.sheetIndex)
        assertEquals("x", e.text(1, 0, 0))
        assertEquals(0xFF00B050, e.workbook.sheets[1].tabColor)
        assertTrue(e.duplicateSheet(1))
        assertEquals("Sales (3)", e.workbook.sheets[2].name)
        e.workbook.sheets[1].ensure(0, 0).text = "changed"
        assertEquals("a copy is its own", "x", e.text(0, 0, 0))
        val long = "L".repeat(31)
        e.renameSheet(0, long)
        assertTrue(e.duplicateSheet(0))
        assertEquals("kept to 31 characters", "L".repeat(27) + " (2)", e.workbook.sheets[1].name)
    }

    @Test fun aCopysTablesGetNamesOfTheirOwn() {
        val e = editor("Data") { wb ->
            val s = wb.sheets[0]
            listOf("Item", "Qty").forEachIndexed { c, t -> s.ensure(0, c).text = t }
            s.ensure(1, 0).text = "a"; s.ensure(1, 1).text = "2"
            s.tables += SheetTable("Table1", 0, 0, 1, 1)
            s.ensure(3, 0).text = "=SUM(Table1[Qty])"
        }
        assertTrue(e.duplicateSheet(0))
        val copy = e.workbook.sheets[1]
        assertEquals("Table2", copy.tables[0].name)
        assertEquals("=SUM(Table2[Qty])", copy.cell(3, 0)?.text)
        assertEquals("the original keeps its table", "=SUM(Table1[Qty])", e.text(0, 3, 0))
    }

    // --- hide / unhide, tab colour ---------------------------------------------

    @Test fun hidingTheActiveSheetShowsTheNextAndUnhideBringsItBack() {
        val e = editor("A", "B", "C", "D") { wb -> wb.sheets[3].state = SheetState.VERY_HIDDEN }
        e.selectSheet(1)
        assertTrue(e.hideSheet(1))
        assertEquals(SheetState.HIDDEN, e.workbook.sheets[1].state)
        assertEquals("C", e.sheet.name)
        assertEquals(listOf(0, 2), e.visibleSheets)
        assertEquals("a very hidden sheet is not offered", listOf(1), e.unhidableSheets)
        assertEquals(SheetEditor.GoTo.INVALID, e.goTo("B!A1"))
        e.selectSheet(1)
        assertEquals("a hidden sheet cannot be selected", "C", e.sheet.name)
        assertTrue(e.unhideSheet(1))
        assertEquals("B", e.sheet.name)
        assertEquals(SheetState.VISIBLE, e.workbook.sheets[1].state)
        e.undo()
        assertEquals(SheetState.HIDDEN, e.workbook.sheets[1].state)
        assertFalse("undo never leaves a hidden sheet on screen", e.sheet.hidden)
        e.undo()
        assertEquals(SheetState.VISIBLE, e.workbook.sheets[1].state)
    }

    @Test fun tabColourSetsClearsAndUndoes() {
        val e = editor("A")
        e.setTabColor(0, 0xFFC00000)
        assertEquals(0xFFC00000, e.sheet.tabColor)
        e.setTabColor(0, null)
        assertNull(e.sheet.tabColor)
        e.undo()
        assertEquals(0xFFC00000, e.sheet.tabColor)
        e.undo()
        assertNull(e.sheet.tabColor)
    }

    @Test fun aWorkbookHidingEverySheetOpensOnItsFirst() {
        val e = editor("A", "B") { wb -> wb.sheets.forEach { it.state = SheetState.HIDDEN } }
        assertFalse(e.workbook.sheets[0].hidden)
        assertEquals(0, e.sheetIndex)
        val f = editor("A", "B", "C") { wb -> wb.sheets[0].state = SheetState.HIDDEN }
        assertEquals("opens on the first shown", "B", f.sheet.name)
    }

    @Test fun newSheetsSkipNamesInUse() {
        val e = editor("Sheet2")
        e.addSheet()
        assertEquals(listOf("Sheet2", "Sheet3"), e.workbook.sheets.map { it.name })
    }

    // --- xlsx -------------------------------------------------------------------

    private fun entry(bytes: ByteArray, name: String): String =
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.first { it.name == name }; z.readBytes().decodeToString() }

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun managedBook() = Workbook(mutableListOf(
        Sheet(name = "Shown", tabColor = 0xFFFF0000).also { it.ensure(0, 0).text = "1" },
        Sheet(name = "Hidden", state = SheetState.HIDDEN),
        Sheet(name = "Secret", state = SheetState.VERY_HIDDEN, tabColor = 0xFF7030A0),
    ))

    @Test fun xlsxKeepsHiddenSheetsAndTabColours() {
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(managedBook(), bos)
        val workbook = entry(bos.toByteArray(), "xl/workbook.xml")
        assertTrue(workbook, workbook.contains("""<sheet name="Hidden" sheetId="2" state="hidden""""))
        assertTrue(workbook, workbook.contains("""<sheet name="Secret" sheetId="3" state="veryHidden""""))
        assertFalse("the first sheet shows: no book view needed", workbook.contains("bookViews"))
        assertTrue(entry(bos.toByteArray(), "xl/worksheets/sheet1.xml").contains("""<sheetPr><tabColor rgb="FFFF0000"/></sheetPr>"""))
        val back = XlsxReader.read(bos.toByteArray())
        assertEquals(listOf(SheetState.VISIBLE, SheetState.HIDDEN, SheetState.VERY_HIDDEN), back.sheets.map { it.state })
        assertEquals(listOf(0xFFFF0000, null, 0xFF7030A0), back.sheets.map { it.tabColor })
    }

    @Test fun xlsxWithAHiddenFirstSheetOpensOnTheFirstShown() {
        val wb = Workbook(mutableListOf(Sheet(name = "Setup", state = SheetState.HIDDEN), Sheet(name = "Report")))
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(wb, bos)
        val workbook = entry(bos.toByteArray(), "xl/workbook.xml")
        assertTrue(workbook, workbook.contains("""<bookViews><workbookView activeTab="1" firstSheet="1"/></bookViews><sheets>"""))
    }

    @Test fun xlsxFilterModeAndTabColourShareTheSheetProperties() {
        val s = Sheet(name = "F", tabColor = 0xFF00B0F0)
        s.ensure(0, 0).text = "h"; s.ensure(1, 0).text = "a"
        s.autoFilter = com.therealityteam.aeropages.model.AutoFilter(0, 0, 1, 0)
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(Workbook(mutableListOf(s)), bos)
        val xml = entry(bos.toByteArray(), "xl/worksheets/sheet1.xml")
        assertTrue(xml, xml.contains("""<sheetPr filterMode="1"><tabColor rgb="FF00B0F0"/></sheetPr>"""))
    }

    @Test fun xlsxTabColoursFromTheThemeWithTints() {
        val theme = """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office"><a:themeElements><a:clrScheme name="Office">""" +
            """<a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""" +
            """<a:dk2><a:srgbClr val="1F497D"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2><a:accent1><a:srgbClr val="4F81BD"/></a:accent1>""" +
            """<a:accent2><a:srgbClr val="C0504D"/></a:accent2><a:accent3><a:srgbClr val="9BBB59"/></a:accent3><a:accent4><a:srgbClr val="8064A2"/></a:accent4>""" +
            """<a:accent5><a:srgbClr val="4BACC6"/></a:accent5><a:accent6><a:srgbClr val="F79646"/></a:accent6><a:hlink><a:srgbClr val="0000FF"/></a:hlink>""" +
            """<a:folHlink><a:srgbClr val="800080"/></a:folHlink></a:clrScheme></a:themeElements></a:theme>"""
        fun sheet(tab: String) = """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetPr>$tab</sheetPr><sheetData/></worksheet>"""
        val tabs = listOf(
            """<tabColor theme="4" tint="-0.249977111117893"/>""", // Blue, Accent 1, Darker 25%
            """<tabColor theme="4" tint="0.39997558519241921"/>""", // Blue, Accent 1, Lighter 40%
            """<tabColor theme="0"/>""", """<tabColor theme="1"/>""", """<tabColor theme="3"/>""", // lt1, dk1, dk2: Excel puts the light colours first
            """<tabColor indexed="10"/>""",
        )
        val parts = linkedMapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                tabs.indices.joinToString("") { """<sheet name="S$it" sheetId="${it + 1}" r:id="rId${it + 1}"/>""" } + "</sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                tabs.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } + "</Relationships>",
            "xl/theme/theme1.xml" to theme,
        )
        tabs.forEachIndexed { i, t -> parts["xl/worksheets/sheet${i + 1}.xml"] = sheet(t) }
        val wb = XlsxReader.read(zipOf(parts))
        assertEquals(listOf(0xFF376092, 0xFF95B3D7, 0xFFFFFFFF, 0xFF000000, 0xFF1F497D, 0xFFFF0000), wb.sheets.map { it.tabColor })
    }

    // --- ODS --------------------------------------------------------------------

    @Test fun odsKeepsHiddenSheetsAndTabColours() {
        val bos = ByteArrayOutputStream()
        OdsWriter.write(managedBook(), bos) { _, _, _ -> null }
        val content = entry(bos.toByteArray(), "content.xml")
        assertTrue(content, content.contains("""<style:table-properties table:display="true" table:tab-color="#FF0000" tableooo:tab-color="#FF0000"/>"""))
        assertTrue(content, content.contains("""<style:table-properties table:display="false"/>"""))
        assertTrue(content, content.contains("""<table:table table:name="Hidden" table:style-name="ta2">"""))
        assertTrue(content, content.contains("xmlns:tableooo=\"http://openoffice.org/2009/table\""))
        val back = OdsReader.read(bos.toByteArray())
        assertEquals("ODF has no very hidden: it reads back hidden", listOf(SheetState.VISIBLE, SheetState.HIDDEN, SheetState.HIDDEN), back.sheets.map { it.state })
        assertEquals(listOf(0xFFFF0000, null, 0xFF7030A0), back.sheets.map { it.tabColor })
    }

    @Test fun odsReadsLibreOfficesTableStyles() {
        val content = """<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
            """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:tableooo="http://openoffice.org/2009/table"><office:automatic-styles>""" +
            """<style:style style:name="ta1" style:family="table" style:master-page-name="Default"><style:table-properties table:display="true" style:writing-mode="lr-tb"/></style:style>""" +
            """<style:style style:name="ta2" style:family="table" style:master-page-name="Default"><style:table-properties table:display="false" style:writing-mode="lr-tb"/></style:style>""" +
            """<style:style style:name="ta3" style:family="table" style:master-page-name="Default"><style:table-properties table:display="true" style:writing-mode="lr-tb" tableooo:tab-color="#2a6099"/></style:style>""" +
            // LibreOffice 26 writes ODF 1.3's own attribute.
            """<style:style style:name="ta4" style:family="table"><style:table-properties table:display="true" table:tab-color="#c00000"/></style:style>""" +
            """</office:automatic-styles><office:body><office:spreadsheet>""" +
            """<table:table table:name="Plain" table:style-name="ta1"/><table:table table:name="Away" table:style-name="ta2"/><table:table table:name="Blue" table:style-name="ta3"/>""" +
            """<table:table table:name="Red" table:style-name="ta4"/>""" +
            """</office:spreadsheet></office:body></office:document-content>"""
        val wb = OdsReader.read(zipOf(mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to content)))
        assertEquals(listOf("Plain", "Away", "Blue", "Red"), wb.sheets.map { it.name })
        assertEquals(listOf(false, true, false, false), wb.sheets.map { it.hidden })
        assertEquals(listOf(null, null, 0xFF2A6099, 0xFFC00000), wb.sheets.map { it.tabColor })
    }

    // --- .xls (BIFF8) ---------------------------------------------------------------

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun le32(v: Int) = le16(v and 0xFFFF) + le16((v shr 16) and 0xFFFF)
    private fun rec(type: Int, body: ByteArray) = le16(type) + le16(body.size) + body
    private fun bof(dt: Int) = rec(0x0809, le16(0x0600) + le16(dt) + le16(0x0DBB) + le16(0x07CC) + le32(0) + le32(0x06))
    private val eof get() = rec(0x000A, byteArrayOf())
    private fun boundSheet(pos: Int, state: Int, name: String, wide: Boolean = false) = rec(0x0085,
        le32(pos) + byteArrayOf(state.toByte(), 0, name.length.toByte(), (if (wide) 1 else 0).toByte()) + name.toByteArray(if (wide) Charsets.UTF_16LE else Charsets.ISO_8859_1))
    private fun label(r: Int, c: Int, text: String) = rec(0x0204, le16(r) + le16(c) + le16(0) + le16(text.length) + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1))
    private fun sheetExt(icv: Int) = rec(0x0862, le16(0x0862) + le16(0) + ByteArray(8) + le32(0x14) + le32(icv))

    /** A Workbook stream: globals naming [names] (with their hsState), then one worksheet per name holding its name in A1. */
    private fun biff(names: List<Pair<String, Int>>, tabIcv: Int? = null, positions: Boolean = true, wide: Boolean = false): ByteArray {
        val sheets = names.mapIndexed { i, (n, _) -> bof(0x10) + label(0, 0, if (wide) "s$i" else n) + (if (i == 0 && tabIcv != null) sheetExt(tabIcv) else byteArrayOf()) + eof }
        val globalsSize = bof(0x05).size + names.sumOf { (n, s) -> boundSheet(0, s, n, wide).size } + eof.size
        var pos = globalsSize
        val bound = names.mapIndexed { i, (n, s) -> boundSheet(if (positions) pos else 0, s, n, wide).also { pos += sheets[i].size } }
        val stream = bof(0x05) + bound.reduce { a, b -> a + b } + eof + sheets.reduce { a, b -> a + b }
        return stream + ByteArray(maxOf(0, 4096 - stream.size))
    }

    @Test fun xlsSheetsTakeTheirNamesStatesAndTabColour() {
        val wb = XlsReader.read(TestCfb.build(mapOf("Workbook" to biff(listOf("Data" to 0, "Lookup" to 1, "Macros" to 2), tabIcv = 0x0A))))
        assertEquals(listOf("Data", "Lookup", "Macros"), wb.sheets.map { it.name })
        assertEquals(listOf(SheetState.VISIBLE, SheetState.HIDDEN, SheetState.VERY_HIDDEN), wb.sheets.map { it.state })
        assertEquals("each sheet's cells stay its own", listOf("Data", "Lookup", "Macros"), wb.sheets.map { it.cell(0, 0)?.text })
        assertEquals("palette index 10 is red", listOf(0xFFFF0000, null, null), wb.sheets.map { it.tabColor })
    }

    @Test fun xlsNamesFollowTheOrderWhenPositionsAreMissing() {
        val wb = XlsReader.read(TestCfb.build(mapOf("Workbook" to biff(listOf("Erste" to 0, "Zweite" to 0), positions = false))))
        assertEquals(listOf("Erste", "Zweite"), wb.sheets.map { it.name })
    }

    @Test fun xlsSheetNamesInUnicode() {
        val wb = XlsReader.read(TestCfb.build(mapOf("Workbook" to biff(listOf("数据" to 0, "汇总" to 0), wide = true))))
        assertEquals(listOf("数据", "汇总"), wb.sheets.map { it.name })
        assertEquals(listOf("s0", "s1"), wb.sheets.map { it.cell(0, 0)?.text })
    }
}
