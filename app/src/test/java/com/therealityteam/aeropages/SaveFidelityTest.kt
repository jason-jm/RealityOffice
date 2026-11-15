package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TextRun
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** What the round-trip audit found lost or changed on save, kept now. */
class SaveFidelityTest {

    @Test
    fun onlyRealNumbersAreStoredAsNumbers() {
        fun stored(text: String, code: String? = null) = Cell(text).also { it.formatCode = code }.storedNumber()
        assertEquals(123.0, stored("123")!!, 0.0)
        assertEquals(-0.5, stored("-0.5")!!, 0.0)
        assertEquals(1.5e10, stored("1.5E+10")!!, 0.0)
        assertNull("a code with leading zeros is text", stored("00123"))
        assertNull("24 digits would come back rounded", stored("123456789012345678901234"))
        assertNull("a text-formatted cell", stored("42", "@"))
        assertNull(stored(" 12")); assertNull(stored("12.")); assertNull(stored("+5")); assertNull(stored("1,000"))

        val wb = Workbook.blank()
        wb.sheets[0].ensure(0, 0).text = "00123"
        wb.sheets[0].ensure(1, 0).text = "123456789012345678901234"
        wb.sheets[0].ensure(2, 0).text = "42"
        val xlsx = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val sheetXml = String(OfficeXml.unzip(xlsx)["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)
        assertTrue("Excel keeps the zeros", sheetXml.contains("""<c r="A1" t="inlineStr"><is><t xml:space="preserve">00123</t></is></c>"""))
        assertTrue(sheetXml.contains("""<c r="A3"><v>42</v></c>"""))
        val ods = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()
        val back = OdsReader.read(ods).sheets[0]
        assertEquals("00123", back.cells[0L]!!.text)
        assertEquals("123456789012345678901234", back.cells[1000L]!!.text)
        assertEquals("42", back.cells[2000L]!!.text)
    }

    @Test
    fun openDocumentKeepsItsRulesInOrderAndDashDotBorders() {
        val wb = Workbook.blank()
        val sheet = wb.sheets[0]
        // Declared right to left: the order the first-that-covers rule depends on.
        sheet.validations.add(DataValidation(1, 3, 5, 3, ValidationType.INTEGER, 0.0, 40.0))
        sheet.validations.add(DataValidation(1, 1, 5, 1, ValidationType.LIST, items = listOf("A", "B")))
        sheet.ensure(0, 0).text = "x"
        sheet.ensure(0, 0).borderBottom = BorderLine(LineStyle.DASH_DOT)
        sheet.ensure(0, 1).text = "y"
        sheet.ensure(0, 1).borderBottom = BorderLine(LineStyle.MEDIUM_DASH_DOT_DOT)
        val back = OdsReader.read(ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()).sheets[0]
        assertEquals(listOf(ValidationType.INTEGER, ValidationType.LIST), back.validations.map { it.type })
        assertEquals(LineStyle.DASH_DOT, back.cells[0L]!!.borderBottom!!.style)
        assertEquals(LineStyle.MEDIUM_DASH_DOT_DOT, back.cells[1L]!!.borderBottom!!.style)
        assertTrue("default columns stay default", back.colWidths.isEmpty())
    }

    @Test
    fun aPlainRunInABoldHeadingStaysPlainThroughOpenDocument() {
        val doc = Document()
        doc.blocks.add(ParagraphBlock(Paragraph(mutableListOf(TextRun("Plain heading", CharacterFormat(bold = false)), TextRun(" bold part", CharacterFormat(bold = true))),
            ParagraphFormat(style = ParagraphStyle.HEADING1))))
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(doc, it) }.toByteArray()
        val styles = String(OfficeXml.unzip(bytes)["styles.xml"]!!, Charsets.UTF_8)
        assertFalse("a heading keeps lines together only where a paragraph says so", styles.contains("keep-together"))
        val runs = (OdtReader.read(bytes).blocks.single() as ParagraphBlock).paragraph.runs
        assertEquals(listOf(false, true), runs.map { it.format.bold })
    }
}
