package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Defined names as Excel has them — ranges, constants and formulas — and the Name Manager's commands. */
class NamedFormulasTest {
    private fun book(): Workbook {
        val s = Sheet(name = "Sales")
        fun put(r: Int, c: Int, t: String) { s.cells[s.key(r, c)] = Cell(t) }
        put(0, 0, "Qty"); put(0, 1, "Unit price"); put(0, 2, "2024")
        put(1, 0, "2"); put(1, 1, "10"); put(1, 2, "x")
        put(2, 0, "3"); put(2, 1, "20"); put(2, 2, "y")
        put(3, 0, "4"); put(3, 1, "30"); put(3, 2, "z")
        val wb = Workbook(mutableListOf(s))
        wb.names["TaxRate"] = "0.08"
        wb.names["税率"] = "0.1"
        wb.names["Qty"] = "Sales!\$A\$2:\$A\$4"
        wb.names["Price"] = "Sales!\$B\$2:\$B\$4"
        wb.names["FirstTotal"] = "Sales!\$A\$2*Sales!\$B\$2"
        wb.names["Loop"] = "Loop+1"
        return wb
    }

    @Test
    fun namesStandForConstantsFormulasAndBlocks() {
        val e = SheetEditor().apply { load(book()) }
        fun at(f: String): String { e.setCell(6, 0, f); return e.display(6, 0) }
        assertEquals("8", at("=100*TaxRate"))
        assertEquals("10", at("=100*税率"))
        assertEquals("21", at("=FirstTotal+1"))
        // A block named in an expression is its values: SUMPRODUCT over two names, as over two ranges.
        assertEquals("200", at("=SUMPRODUCT(Qty*Price)"))
        assertEquals("9", at("=SUM(Qty)"))
        // A name naming itself.
        assertEquals("#NAME?", at("=Loop"))
        // The Name Manager's value column.
        assertEquals("0.08", e.nameValueText("TaxRate"))
        // An edited cell the formula name reads moves its value.
        e.setCell(6, 0, "=FirstTotal")
        e.setCell(1, 0, "5")
        assertEquals("50", e.display(6, 0))
    }

    @Test
    fun theNameManagerEditsInOneStep() {
        val e = SheetEditor().apply { load(book()) }
        assertEquals(SheetEditor.NameIssue.TAKEN, e.nameIssue(null, "taxrate"))
        assertEquals(SheetEditor.NameIssue.INVALID, e.nameIssue(null, "A1"))
        assertEquals(null, e.nameIssue("TaxRate", "TAXRATE"))
        assertTrue(e.setName("TaxRate", "VAT", "=0.2"))
        assertEquals("0.2", e.workbook.names["VAT"]); assertFalse("TaxRate" in e.workbook.names)
        assertFalse(e.setName(null, "VAT", "1"))
        e.undo()
        assertEquals("0.08", e.workbook.names["TaxRate"]); assertFalse("VAT" in e.workbook.names)
        // Create from Selection: the top row's labels name the columns under them, made into names as Excel makes them.
        e.select(0, 0); e.extendSelection(3, 2)
        assertEquals(true to false, e.guessNameLabels())
        assertEquals(3, e.createNamesFromSelection(top = true, left = false, bottom = false, right = false))
        assertEquals("'Sales'!\$B\$2:\$B\$4".replace("'", ""), e.workbook.names["Unit_price"]!!.replace("'", ""))
        assertEquals("Sales!\$C\$2:\$C\$4", e.workbook.names["_2024"]!!.replace("'", ""))
        assertEquals("Unit_price", SheetEditor.nameFromLabel(" Unit price "))
        assertEquals("_A1", SheetEditor.nameFromLabel("A1"))
        assertEquals(null, SheetEditor.nameFromLabel("  "))
        e.undo()
        assertFalse("Unit_price" in e.workbook.names)
    }

    @Test
    fun theFilesKeepThem() {
        val wb = book()
        val xlsx = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        assertEquals(wb.names, XlsxReader.read(xlsx).names)
        val ods = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()
        val content = String(OfficeXml.unzip(ods)["content.xml"]!!)
        assertTrue(content, content.contains("""<table:named-expression table:name="TaxRate" table:base-cell-address="${'$'}Sales.${'$'}A${'$'}1" table:expression="of:=0.08"/>"""))
        val back = OdsReader.read(ods).names
        assertEquals("0.08", back["TaxRate"]); assertEquals("0.1", back["税率"])
        assertEquals("Sales!\$A\$2:\$A\$4", back["Qty"]!!.replace("'", ""))
        assertEquals("Sales!\$A\$2*Sales!\$B\$2", back["FirstTotal"]!!.replace("'", ""))
    }

    /** With `-Daero.sampleDir`: the workbook as xlsx and ODS, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val wb = book().also { it.names.remove("Loop") }
        val s = wb.sheets[0]
        s.cells[s.key(5, 0)] = Cell("=SUMPRODUCT(Qty*Price)")
        s.cells[s.key(5, 1)] = Cell("=100*TaxRate")
        s.cells[s.key(5, 2)] = Cell("=FirstTotal+1")
        File(dir, "names.xlsx").outputStream().use { XlsxWriter.write(wb, it) }
        File(dir, "names.ods").outputStream().use { OdsWriter.write(wb, it) }
    }
}
