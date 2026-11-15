package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetTable
import com.therealityteam.aeropages.model.StructuredRefs
import com.therealityteam.aeropages.model.TableLooks
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Excel tables: structured references, the built-in looks, xlsx and ODF, and the Table Design commands. */
class SheetTablesTest {

    /** Sales on Sheet1 (A1:D5) — Region, Qty, Unit Price, Amount = [@Qty]*[@[Unit Price]] — with a total row; an empty Summary sheet. */
    private fun salesBook(): Workbook {
        val s = Sheet("Sheet1")
        listOf(listOf("Region", "Qty", "Unit Price", "Amount"), listOf("East", "3", "10"), listOf("West", "5", "4"), listOf("North", "2", "7.5"))
            .forEachIndexed { r, row -> row.forEachIndexed { c, t -> s.ensure(r, c).text = t } }
        for (r in 1..3) s.ensure(r, 3).text = "=[@Qty]*[@[Unit Price]]"
        s.ensure(4, 0).text = "Total"
        s.ensure(4, 3).text = "=SUBTOTAL(109,[Amount])"
        s.tables.add(SheetTable("Sales", 0, 0, 4, 3, totalsRow = true))
        return Workbook(mutableListOf(s, Sheet("Summary")))
    }

    private fun value(wb: Workbook, sheet: Int, row: Int, col: Int, formula: String): String {
        wb.sheets[sheet].ensure(row, col).text = formula
        return FormulaEngine.display(wb.sheets[sheet], row, col, wb.sheets)
    }

    @Test
    fun structuredReferencesEvaluate() {
        val wb = salesBook()
        val s = wb.sheets[0]
        assertEquals("this row's Qty × Unit Price", "30", FormulaEngine.display(s, 1, 3, wb.sheets))
        assertEquals("15", FormulaEngine.display(s, 3, 3, wb.sheets))
        assertEquals("the total row's SUBTOTAL of its column", "65", FormulaEngine.display(s, 4, 3, wb.sheets))
        assertEquals("65", value(wb, 1, 0, 0, "=SUM(Sales[Amount])"))
        assertEquals("names in any case", "65", value(wb, 1, 0, 0, "=SUM(sales[amount])"))
        assertEquals("65", value(wb, 1, 0, 0, "=Sales[[#Totals],[Amount]]"))
        assertEquals("4", value(wb, 1, 0, 0, "=COUNTA(Sales[#Headers])"))
        assertEquals("a bare name is the data", "3", value(wb, 1, 0, 0, "=ROWS(Sales)"))
        assertEquals("5", value(wb, 1, 0, 0, "=ROWS(Sales[#All])"))
        assertEquals("4", value(wb, 1, 0, 0, "=ROWS(Sales[[#Headers],[#Data]])"))
        assertEquals("a span of columns", "3", value(wb, 1, 0, 0, "=COLUMNS(Sales[[Qty]:[Amount]])"))
        assertEquals("20", value(wb, 1, 0, 0, "=VLOOKUP(\"West\",Sales,4,0)"))
        assertEquals("30", value(wb, 1, 0, 0, "=SUMIFS(Sales[Amount],Sales[Region],\"East\")"))
        assertEquals("in an expression", "130", value(wb, 1, 0, 0, "=Sales[[#Totals],[Amount]]*2"))
        assertEquals("this row, from another sheet in line with it", "5", value(wb, 1, 2, 0, "=Sales[@Qty]"))
        assertEquals("#REF!", value(wb, 1, 0, 0, "=SUM(Sales[Bogus])"))
        assertEquals("no table round a reference without a name", "#REF!", value(wb, 1, 0, 0, "=SUM([Amount])"))
        assertEquals("#REF!", value(wb, 1, 0, 0, "=SUM(Nowhere[Amount])"))
        assertEquals("a string holds no reference", "Sales[Qty]", value(wb, 1, 0, 0, "=\"Sales[Qty]\""))
        // Without a total row there are no totals to name.
        s.tables[0].totalsRow = false
        assertEquals("#REF!", value(wb, 1, 0, 0, "=SUM(Sales[#Totals])"))
    }

    @Test
    fun referencesParseAndWriteOutInBothSpellings() {
        val wb = salesBook()
        val s = wb.sheets[0]
        val summary = wb.sheets[1]
        assertEquals("Sales[[#This Row],[Qty]]*Sales[[#This Row],[Unit Price]]", StructuredRefs.toFileForm("[@Qty]*[@[Unit Price]]", s, 1, 3))
        assertEquals("[@Qty]*[@[Unit Price]]", StructuredRefs.toUiForm("Sales[[#This Row],[Qty]]*Sales[[#This Row],[Unit Price]]", s, 1, 3))
        assertEquals("another table keeps its name", "Sales[@Qty]", StructuredRefs.toUiForm("Sales[[#This Row],[Qty]]", summary, 2, 0))
        assertEquals("SUBTOTAL(109,Sales[Amount])", StructuredRefs.toFileForm("SUBTOTAL(109,[Amount])", s, 4, 3))
        assertEquals("SUM(Sheet1!D2:D4)+Sheet1!B3", StructuredRefs.toA1("SUM(Sales[Amount])+Sales[@Qty]", summary, 2, 0, wb.sheets))
        assertEquals("a bare name too", "ROWS(Sheet1!A2:D4)", StructuredRefs.toA1("ROWS(Sales)", summary, 0, 0, wb.sheets))
        // Found outside strings and quoted sheet names only; an external workbook's index is no reference.
        val found = StructuredRefs.find("\"[x]\"&'a[b]'!A1&Sales[Qty]&[1]Sheet1!A1")
        assertEquals(1, found.size)
        assertEquals("Sales", found[0].table)
        assertEquals("Qty", found[0].col1)
        // Special characters in a column's name are escaped with an apostrophe.
        val ref = StructuredRefs.parseAt("T[['#Items]:[Price '[USD']]]", 0)!!
        assertEquals("#Items", ref.col1)
        assertEquals("Price [USD]", ref.col2)
        assertEquals("T[['#Items]:[Price '[USD']]]", StructuredRefs.render("T", emptySet(), "#Items", "Price [USD]", ui = false))
        assertEquals("T[[#Headers],[#Data],[Qty]]", StructuredRefs.render("T", setOf(StructuredRefs.Item.DATA, StructuredRefs.Item.HEADERS), "Qty", null, ui = false))
        // Renames reach every spelling.
        assertEquals("SUM(Orders[Qty])+ROWS(Orders)", StructuredRefs.renameTable("SUM(Sales[Qty])+ROWS(Sales)", "sales", "Orders", listOf("Sales")))
        assertEquals("[@Units]*2+SUM(Sales[[#Totals],[Units]])",
            StructuredRefs.renameColumn("[@Qty]*2+SUM(Sales[[#Totals],[Qty]])", s, 1, 3, s.tables[0], "Qty", "Units"))
    }

    @Test
    fun referenceShiftsLeaveColumnNamesAndStringsAlone() {
        assertEquals("SUM(T[Q1])+A2", A1.shiftRefs("SUM(T[Q1])+A1", 1, 0))
        assertEquals("\"A1\"&B2", A1.shiftRefs("\"A1\"&B1", 1, 0))
        assertEquals("T[[#This Row],[FY2024]]*C3", A1.shiftRefs("T[[#This Row],[FY2024]]*C2", 1, 0))
        // Inserting a row above the table moves it; a column named like a cell stays as it is.
        val e = SheetEditor().apply { load(salesBook()) }
        e.sheet.ensure(0, 6).text = "=SUM(Sales[Qty])+B2"
        e.insertRowAt(0)
        assertEquals("=SUM(Sales[Qty])+B3", e.sheet.cell(1, 6)!!.text)
        val t = e.sheet.tables.single()
        assertEquals(1, t.r1); assertEquals(5, t.r2)
        assertEquals("13", e.display(1, 6))
        // A row inserted inside grows it; one deleted inside shrinks it.
        e.insertRowAt(3)
        assertEquals(6, t.r2)
        e.deleteRowAt(3); e.deleteRowAt(3)
        assertEquals(4, t.r2)
        assertEquals("=SUBTOTAL(109,[Amount])", e.sheet.cell(4, 3)!!.text)
        assertEquals("30 + 15", "45", e.display(4, 3))
        // The header row going takes the table with it; its data stays as cells.
        e.deleteRowAt(1)
        assertTrue(e.sheet.tables.isEmpty())
        assertEquals("3", e.sheet.cell(1, 1)!!.text)
        e.undo()
        assertEquals(1, e.sheet.tables.size)
    }

    @Test
    fun theLooksFollowExcelsStyles() {
        val t = SheetTable("T", 0, 0, 4, 2, totalsRow = true)
        val header = TableLooks.lookAt(t, 0, 0)!!
        assertEquals(0xFF4472C4, header.fill); assertTrue(header.bold); assertEquals(0xFFFFFFFF, header.color)
        assertEquals("the first data row is striped", 0xFFD9E1F2, TableLooks.lookAt(t, 1, 1)!!.fill)
        assertNull(TableLooks.lookAt(t, 2, 1)!!.fill)
        assertEquals("light rules between rows", BorderLine(LineStyle.THIN, 0xFF8EA9DB), TableLooks.lookAt(t, 2, 1)!!.bottom)
        val total = TableLooks.lookAt(t, 4, 1)!!
        assertTrue(total.bold); assertEquals(BorderLine(LineStyle.DOUBLE, 0xFF4472C4), total.top)
        t.bandedRows = false; t.firstColumn = true
        assertNull(TableLooks.lookAt(t, 1, 1)!!.fill)
        assertTrue(TableLooks.lookAt(t, 2, 0)!!.bold)
        t.style = "TableStyleDark11"
        assertEquals("Dark 11 heads in black", 0xFF000000, TableLooks.lookAt(t, 0, 0)!!.fill)
        assertNull(TableLooks.parse("TableStyleDark12"))
        assertNull(TableLooks.parse("MyStyle"))
        assertEquals(60, TableLooks.ALL.size)
        // A cell's own formatting wins over the look.
        val s = Sheet().apply { tables.add(SheetTable("T", 0, 0, 3, 1)) }
        s.ensure(1, 0).format.highlight = 0xFFFF0000
        assertEquals(0xFFFF0000, TableLooks.styled(s, 1, 0, s.cell(1, 0))!!.format.highlight)
        assertEquals(0xFFD9E1F2, TableLooks.styled(s, 1, 1, s.cell(1, 1))!!.format.highlight)
        assertNull("outside the table nothing changes", TableLooks.styled(s, 9, 9, null))
    }

    @Test
    fun tablesGoToExcelAndComeBack() {
        val wb = salesBook()
        val bytes = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        val table = String(parts["xl/tables/table1.xml"]!!, Charsets.UTF_8)
        assertTrue(table, table.contains("""id="1" name="Sales" displayName="Sales" ref="A1:D5" totalsRowCount="1">"""))
        assertTrue("the filter leaves the total row out", table.contains("""<autoFilter ref="A1:D4"/>"""))
        assertTrue(table.contains("""<tableColumn id="1" name="Region" totalsRowLabel="Total"/>"""))
        assertTrue(table.contains("""<tableColumn id="3" name="Unit Price"/>"""))
        assertTrue(table.contains("""<tableColumn id="4" name="Amount" totalsRowFunction="sum"/>"""))
        assertTrue(table.contains("""<tableStyleInfo name="TableStyleMedium2" showFirstColumn="0" showLastColumn="0" showRowStripes="1" showColumnStripes="0"/>"""))
        val sheet = String(parts["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        assertTrue(sheet.contains("""<tableParts count="1"><tablePart r:id="rIdT1"/></tableParts>"""))
        assertTrue("files name the table and spell this row out", sheet.contains("<f>Sales[[#This Row],[Qty]]*Sales[[#This Row],[Unit Price]]</f>"))
        assertTrue(sheet.contains("<f>SUBTOTAL(109,Sales[Amount])</f>"))
        assertTrue("headers are text", sheet.contains("""<c r="B1" t="inlineStr"><is><t xml:space="preserve">Qty</t></is></c>"""))
        assertTrue(String(parts["xl/worksheets/_rels/sheet1.xml.rels"]!!, Charsets.UTF_8).contains("""relationships/table" Target="../tables/table1.xml""""))
        assertTrue(String(parts["[Content_Types].xml"]!!, Charsets.UTF_8).contains("""<Override PartName="/xl/tables/table1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/>"""))
        assertFalse("no sheet filter over the table", sheet.contains("<autoFilter"))

        val back = XlsxReader.read(bytes)
        val t = back.sheets[0].tables.single()
        assertTrue(t.sameAs(wb.sheets[0].tables[0]))
        assertEquals("shown as Excel shows it", "=[@Qty]*[@[Unit Price]]", back.sheets[0].cell(1, 3)!!.text)
        assertEquals("65", FormulaEngine.display(back.sheets[0], 4, 3, back.sheets))
    }

    @Test
    fun excelsTablePartIsRead() {
        // As Excel writes a filtered table with no sheet-level filter: the criteria live in the table part.
        val ns = """xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
        fun c(ref: String, text: String) = """<c r="$ref" t="inlineStr"><is><t>$text</t></is></c>"""
        val sheet = """<worksheet $ns><sheetData>""" +
            """<row r="2">${c("B2", "Region")}${c("C2", "Units")}</row>""" +
            """<row r="3">${c("B3", "East")}<c r="C3"><v>4</v></c><c r="D3"><f>Orders[[#This Row],[Units]]*2</f><v>8</v></c></row>""" +
            """<row r="4" hidden="1">${c("B4", "West")}<c r="C4"><v>6</v></c></row>""" +
            """<row r="5">${c("B5", "East")}<c r="C5"><v>1</v></c></row>""" +
            """<row r="6" hidden="1">${c("B6", "North")}<c r="C6"><v>9</v></c></row>""" +
            """</sheetData><tableParts count="1"><tablePart r:id="rId1"/></tableParts></worksheet>"""
        val table = """<table $ns id="3" name="Table3" displayName="Orders" ref="B2:C6" totalsRowShown="0">""" +
            """<autoFilter ref="B2:C6"><filterColumn colId="0"><filters><filter val="East"/></filters></filterColumn></autoFilter>""" +
            """<tableColumns count="2"><tableColumn id="1" name="Region"/><tableColumn id="2" name="Units"/></tableColumns>""" +
            """<tableStyleInfo name="TableStyleLight9" showFirstColumn="0" showLastColumn="0" showRowStripes="1" showColumnStripes="0"/></table>"""
        val files = mapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "xl/workbook.xml" to """<workbook $ns><sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to sheet,
            "xl/worksheets/_rels/sheet1.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table3.xml"/></Relationships>""",
            "xl/tables/table3.xml" to table,
        )
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> files.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        val wb = XlsxReader.read(bos.toByteArray())
        val s = wb.sheets[0]
        val t = s.tables.single()
        assertEquals("formulas use the display name", "Orders", t.name)
        assertEquals(1, t.r1); assertEquals(1, t.c1); assertEquals(5, t.r2); assertEquals(2, t.c2)
        assertEquals("TableStyleLight9", t.style)
        assertTrue(t.filterButton); assertTrue(t.bandedRows); assertFalse(t.totalsRow)
        assertEquals("the table's filter is the sheet's", setOf("East"), s.autoFilter!!.columns[1]!!.values)
        assertEquals("the rows it hid are filtered, not hidden by hand", setOf(3, 5), s.hiddenRows)
        assertTrue(s.manualHiddenRows.isEmpty())
        assertEquals("outside the table this row keeps the name", "=Orders[@Units]*2", s.cell(2, 3)!!.text)
        assertEquals("8", FormulaEngine.display(s, 2, 3, wb.sheets))
        // And out again: the criteria go back into the table part, none at sheet level.
        val again = OfficeXml.unzip(ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray())
        val part = String(again["xl/tables/table1.xml"]!!, Charsets.UTF_8)
        assertTrue(part, part.contains("""<autoFilter ref="B2:C6"><filterColumn colId="0"><filters><filter val="East"/></filters></filterColumn></autoFilter>"""))
        assertFalse(String(again["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8).contains("<autoFilter"))
    }

    @Test
    fun openDocumentGetsTheLookAndTheCells() {
        val wb = salesBook()
        val bytes = ByteArrayOutputStream().also { out -> OdsWriter.write(wb, out) { si, r, c -> FormulaEngine.cached(wb.sheets[si], r, c, wb.sheets) } }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)
        assertFalse("no structured references in ODF", content.contains("Sales["))
        assertTrue(content, content.contains("[.D2:.D4]"))
        assertTrue("the header's blue is the cell's own now", content.contains("#4472c4", ignoreCase = true))
        val back = OdsReader.read(bytes)
        assertEquals("65", FormulaEngine.display(back.sheets[0], 4, 3, back.sheets))
        assertEquals("30", FormulaEngine.display(back.sheets[0], 1, 3, back.sheets))
    }

    private fun quarters(e: SheetEditor) {
        listOf(listOf("Region", "Q1", "Q2"), listOf("North", "10", "12"), listOf("South", "8", "9"), listOf("West", "5", "7"))
            .forEachIndexed { r, row -> row.forEachIndexed { c, t -> e.setCell(r, c, t) } }
    }

    @Test
    fun insertTableAndItsDesignOptions() {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        quarters(e)
        e.setCell(0, 5, "=SUM(Table1[Q1])")
        e.select(1, 1)
        assertTrue(e.formatAsTable(SheetTable.DEFAULT_STYLE))
        val t = e.selectedTable!!
        assertEquals("Table1", t.name)
        assertTrue("its filter buttons are the sheet's filter", e.filterIsTable(t))
        assertEquals("23", e.display(0, 5))
        // A block overlapping the table makes no second table.
        e.select(5, 0); e.extendSelection(-3, 1)
        assertFalse(e.formatAsTable("TableStyleLight1"))
        assertEquals(SheetEditor.TableRefusal.OVERLAPS_TABLE, e.tableRefusal)
        assertEquals(1, e.sheet.tables.size)

        // Total row: the row below, "Total" under the first column, the last one summed.
        assertTrue(e.setTableOption(t, SheetEditor.TableOption.TOTAL_ROW, true, "Total"))
        assertEquals(4, t.r2)
        assertEquals("Total", e.sheet.cell(4, 0)!!.text)
        assertEquals("=SUBTOTAL(109,[Q2])", e.sheet.cell(4, 2)!!.text)
        assertEquals("28", e.display(4, 2))
        assertEquals(SheetEditor.TotalFunction.SUM, e.totalFunctionAt(t, 2))
        e.setTotalFunction(t, 1, SheetEditor.TotalFunction.MAX)
        assertEquals("10", e.display(4, 1))
        assertEquals("the filter keeps off the total row", 3, e.sheet.autoFilter!!.r2)

        // Header row off keeps the names; back on, they return above the data.
        assertTrue(e.setTableOption(t, SheetEditor.TableOption.HEADER_ROW, false))
        val off = e.sheet.tables.single()
        assertEquals(1, off.r1)
        assertEquals(listOf("Region", "Q1", "Q2"), off.columns)
        assertEquals("", e.sheet.cell(0, 1)?.text ?: "")
        assertEquals("formulas still reach the column", "23", e.display(0, 5))
        assertTrue(e.setTableOption(off, SheetEditor.TableOption.HEADER_ROW, true))
        assertEquals(0, off.r1)
        assertEquals("Q1", e.sheet.cell(0, 1)!!.text)
        e.undo(); e.undo()
        assertEquals("Q1", e.sheet.cell(0, 1)!!.text)

        // Rename: every formula follows; names Excel refuses are refused.
        val table = e.sheet.tables.single()
        assertTrue(e.renameTable(table, "Regions"))
        assertEquals("=SUM(Regions[Q1])", e.sheet.cell(0, 5)!!.text)
        assertFalse(e.renameTable(table, "A1"))
        assertEquals(SheetEditor.TableRefusal.BAD_NAME, e.tableRefusal)
        e.sheet.tables.add(SheetTable("Other", 20, 0, 22, 1))
        assertFalse(e.renameTable(table, "other"))
        assertEquals(SheetEditor.TableRefusal.NAME_TAKEN, e.tableRefusal)
        e.undo()
        assertEquals("=SUM(Table1[Q1])", e.sheet.cell(0, 5)!!.text)
    }

    @Test
    fun typingRenamesColumnsAndGrowsTheTable() {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        quarters(e)
        e.select(0, 0)
        assertTrue(e.formatAsTable(SheetTable.DEFAULT_STYLE))
        e.setCell(0, 5, "=SUM(Table1[Q1])")
        e.setCell(1, 5, "=[@Q1]")
        // A header typed over renames its column wherever a formula names it.
        e.typeCell(0, 1, "Spring")
        e.commitEntry()
        assertEquals("=SUM(Table1[Spring])", e.sheet.cell(0, 5)!!.text)
        assertEquals("23", e.display(0, 5))
        // A repeated header is numbered, as Excel numbers it.
        e.typeCell(0, 2, "Spring")
        e.commitEntry()
        assertEquals("Spring2", e.sheet.cell(0, 2)!!.text)
        // A value just below the table takes the table over its row: one undo step of its own.
        val t = e.sheet.tables.single()
        e.typeCell(4, 0, "East")
        e.commitEntry()
        assertEquals(4, t.r2)
        assertEquals("the filter grows with it", 4, e.sheet.autoFilter!!.r2)
        e.setCell(4, 1, "6")
        assertEquals("29", e.display(0, 5))
        e.undo()
        e.undo()
        assertEquals(3, e.sheet.tables.single().r2)
        assertEquals("East", e.sheet.cell(4, 0)!!.text)
        // Beside the header row: a new column, named by what is typed.
        e.typeCell(0, 3, "Q3")
        e.commitEntry()
        assertEquals(3, e.sheet.tables.single().c2)
        assertEquals(listOf("Region", "Spring", "Spring2", "Q3"), e.sheet.tables.single().columnNames(e.sheet))
    }

    @Test
    fun calculatedColumnsAndCopiesOutOfTheTable() {
        val e = SheetEditor().apply { load(Workbook.blank()) }
        quarters(e)
        e.select(0, 0)
        assertTrue(e.formatAsTable(SheetTable.DEFAULT_STYLE))
        // A header typed beside the table adds a column; a formula in it fills the column.
        e.typeCell(0, 3, "Both"); e.commitEntry()
        e.typeCell(1, 3, "=[@Q1]+[@Q2]"); e.commitEntry()
        assertEquals("=[@Q1]+[@Q2]", e.sheet.cell(2, 3)!!.text)
        assertEquals("22", e.display(1, 3))
        assertEquals("17", e.display(2, 3))
        assertEquals("12", e.display(3, 3))
        e.undo()
        assertTrue(e.sheet.cell(2, 3)?.text.isNullOrEmpty())
        e.redo()
        assertEquals("=[@Q1]+[@Q2]", e.sheet.cell(3, 3)!!.text)
        // A row added below takes the calculated column's formula.
        e.typeCell(4, 0, "East"); e.commitEntry()
        assertEquals(4, e.sheet.tables.single().r2)
        assertEquals("=[@Q1]+[@Q2]", e.sheet.cell(4, 3)!!.text)
        e.setCell(4, 1, "3"); e.setCell(4, 2, "4")
        assertEquals("7", e.display(4, 3))
        // Copied out of the table a formula names it; within the table it stays as written.
        e.select(1, 3); e.copySelection()
        e.select(1, 6); assertTrue(e.pasteSpecial(SheetEditor.PasteWhat.ALL))
        assertEquals("=Table1[@Q1]+Table1[@Q2]", e.sheet.cell(1, 6)!!.text)
        assertEquals("22", e.display(1, 6))
        e.select(2, 3); assertTrue(e.pasteSpecial(SheetEditor.PasteWhat.ALL))
        assertEquals("=[@Q1]+[@Q2]", e.sheet.cell(2, 3)!!.text)
    }

    @Test
    fun convertToRangeKeepsTheLookAndTheValues() {
        val e = SheetEditor().apply { load(salesBook()) }
        e.selectSheet(1)
        e.setCell(0, 0, "=SUM(Sales[Amount])")
        e.selectSheet(0)
        e.select(1, 1)
        val t = e.selectedTable!!
        assertTrue(e.convertTableToRange(t))
        assertTrue(e.sheet.tables.isEmpty())
        assertEquals("formulas name the cells instead", "=\$B2*\$C2", e.sheet.cell(1, 3)!!.text)
        assertEquals("=SUBTOTAL(109,\$D\$2:\$D\$4)", e.sheet.cell(4, 3)!!.text)
        assertEquals("=SUM(Sheet1!\$D\$2:\$D\$4)", e.workbook.sheets[1].cell(0, 0)!!.text)
        assertEquals("65", e.display(4, 3))
        assertEquals("the look stays, as the cells' own", 0xFF4472C4, e.sheet.cell(0, 0)!!.format.highlight)
        assertTrue(e.sheet.cell(0, 0)!!.format.bold)
        assertEquals(0xFFD9E1F2, e.sheet.cell(1, 2)!!.format.highlight)
        e.undo()
        assertEquals("Sales", e.sheet.tables.single().name)
        assertEquals("=[@Qty]*[@[Unit Price]]", e.sheet.cell(1, 3)!!.text)
        assertNull(e.sheet.cell(0, 0)!!.format.highlight)
    }
}
