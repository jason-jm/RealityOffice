package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Fields
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableFormulas
import com.therealityteam.aeropages.model.TableRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Word's table formulas: =SUM(ABOVE) and the rest, worked out in their cells and kept live through docx. */
class TableFormulaTest {

    /** An expense table: 项目 | 数量 | 单价 | 金额, two items, a total row. */
    private fun table() = Table(listOf(
        listOf("项目", "数量", "单价", "金额"),
        listOf("纸", "2", "12.5", ""),
        listOf("笔", "10", "3", ""),
        listOf("合计", "", "", ""),
    ).map { r -> TableRow(r.map { TableCell(mutableListOf(Paragraph.of(it))) }.toMutableList()) }.toMutableList())

    @Test
    fun formulasComeToWhatWordSays() {
        val t = table()
        assertEquals(25.0, TableFormulas.evaluate("B2*C2", t, 1, 3)!!, 1e-9)
        // ABOVE stops at the header (no number); LEFT takes the numbers beside.
        assertEquals(12.0, TableFormulas.evaluate("SUM(ABOVE)", t, 3, 1)!!, 1e-9)
        assertEquals(14.5, TableFormulas.evaluate("SUM(LEFT)", t, 1, 3)!!, 1e-9)
        assertEquals(15.5, TableFormulas.evaluate("SUM(C2:C3)", t, 3, 2)!!, 1e-9)
        assertEquals(7.75, TableFormulas.evaluate("AVERAGE(C2:C3)", t, 3, 2)!!, 1e-9)
        assertEquals(2.0, TableFormulas.evaluate("COUNT(B2,B3)", t, 3, 1)!!, 1e-9)
        assertEquals(13.0, TableFormulas.evaluate("ROUND(12.5, 0)", t, 0, 0)!!, 1e-9)
        assertEquals(5.0, TableFormulas.evaluate("(B3-B2)/2+1", t, 0, 0)!!, 1e-9)
        assertNull(TableFormulas.evaluate("SUM(", t, 0, 0))
        assertNull(TableFormulas.evaluate("FOO(1)", t, 0, 0))
        // \\# pictures.
        assertEquals("1,234.50", TableFormulas.format(1234.5, "#,##0.00"))
        assertEquals("¥55.00", TableFormulas.format(55.0, "¥#,##0.00"))
        assertEquals("15.5", TableFormulas.format(15.5, null))
        assertEquals("100", TableFormulas.format(99.999999999999, null))
        assertEquals("-3", TableFormulas.format(-3.0, "0"))
    }

    @Test
    fun theEditorInsertsThemAndUpdatesTheTotalOfTotals() {
        val doc = Document(mutableListOf())
        doc.blocks.add(TableBlock(table()))
        val e = DocumentEditor().apply { load(doc) }
        fun cellIndex(r: Int, c: Int) = e.document.locations().indexOfFirst { it.paragraph === (e.document.blocks[0] as TableBlock).table.rows[r].cells[c].content[0] }
        // 金额 of each row: Word's guess beside numbers is =SUM(LEFT); here a product.
        e.setSelection(cellIndex(1, 3), 0, 0)
        assertEquals("=SUM(LEFT)", e.suggestedFormula)
        assertEquals("25.00", e.formulaPreview("=B2*C2", "0.00"))
        assertTrue(e.insertTableFormula("=B2*C2", "0.00"))
        e.setSelection(cellIndex(2, 3), 0, 0); e.insertTableFormula("=B3*C3", "0.00")
        // The total under them: =SUM(ABOVE), reading the formula cells.
        e.setSelection(cellIndex(3, 3), 0, 0)
        assertEquals("=SUM(ABOVE)", e.suggestedFormula)
        e.insertTableFormula("=SUM(ABOVE)", "¥#,##0.00")
        val t = (e.document.blocks[0] as TableBlock).table
        assertEquals("25.00", t.rows[1].cells[3].content[0].plainText())
        assertEquals("¥55.00", t.rows[3].cells[3].content[0].plainText())
        // A changed quantity: Update Fields works the products and then the total out again.
        e.setSelection(cellIndex(2, 1), 0, 2); e.typeOver("20")
        e.updateFields()
        assertEquals("¥85.00", (e.document.blocks[0] as TableBlock).table.rows[3].cells[3].content[0].plainText())
        // Kept as Word's fields.
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(e.document, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["word/document.xml"]!!)
        assertTrue(xml, xml.contains("""=SUM(ABOVE) \# &quot;¥#,##0.00&quot;"""))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(3, Fields.placed(back).count { it.field.kind == "=" })
        assertEquals(0, Fields.update(back))
    }

    @Test
    fun odfKeepsThemAsLibreOfficesTableFormulas() {
        val doc = Document(mutableListOf())
        doc.blocks.add(TableBlock(table()))
        val e = DocumentEditor().apply { load(doc) }
        fun at(r: Int, c: Int) = e.document.locations().indexOfFirst { it.paragraph === (e.document.blocks[0] as TableBlock).table.rows[r].cells[c].content[0] }
        e.setSelection(at(1, 3), 0, 0); e.insertTableFormula("=B2*C2", null)
        e.setSelection(at(2, 3), 0, 0); e.insertTableFormula("=AVERAGE(B3:C3)", null)
        e.setSelection(at(3, 3), 0, 0); e.insertTableFormula("=SUM(ABOVE)", null)
        val bytes = ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdtWriter.write(e.document, it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!)
        // Cells in brackets, AVERAGE as MEAN, ABOVE as the range it takes — as LibreOffice writes Word's.
        assertTrue(content, content.contains("""<text:table-formula text:formula="ooow:&lt;B2&gt;*&lt;C2&gt;">25</text:table-formula>"""))
        assertTrue(content.contains("""ooow:MEAN(&lt;B3:C3&gt;)"""))
        assertTrue(content.contains("""ooow:SUM(&lt;D3:D2&gt;)"""))
        val back = com.therealityteam.aeropages.format.odf.OdtReader.read(bytes)
        assertEquals(listOf("B2*C2", "AVERAGE(B3:C3)", "SUM(D3:D2)"), Fields.placed(back).filter { it.field.kind == "=" }.map { TableFormulas.formulaOf(it.field.code) })
        assertEquals(0, Fields.update(back))
    }

    /** With `-Daero.sampleDir`: the expense table with its formulas, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { java.io.File(it).also { d -> d.mkdirs() } } ?: return
        val doc = Document(mutableListOf())
        doc.blocks.add(TableBlock(table()))
        val e = DocumentEditor().apply { load(doc) }
        fun at(r: Int, c: Int) = e.document.locations().indexOfFirst { it.paragraph === (e.document.blocks[0] as TableBlock).table.rows[r].cells[c].content[0] }
        e.setSelection(at(1, 3), 0, 0); e.insertTableFormula("=B2*C2", "0.00")
        e.setSelection(at(2, 3), 0, 0); e.insertTableFormula("=B3*C3", "0.00")
        e.setSelection(at(3, 3), 0, 0); e.insertTableFormula("=SUM(ABOVE)", "#,##0.00")
        java.io.File(dir, "table-formulas.docx").outputStream().use { DocxWriter.write(e.document, it) }
        java.io.File(dir, "table-formulas.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(e.document, it) }
    }
}
