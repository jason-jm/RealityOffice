package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.model.AutoFilter
import com.therealityteam.aeropages.model.ColumnFilter
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.FilterCondition
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.ValidationAlertStyle
import com.therealityteam.aeropages.model.ValidationOperator
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Validation rules, defined names and AutoFilters in OpenDocument spreadsheets, as LibreOffice Calc keeps them. */
class OdsDataRulesTest {

    private fun write(wb: Workbook): ByteArray = ByteArrayOutputStream().also { OdsWriter.write(wb, it) }.toByteArray()

    private fun content(bytes: ByteArray) = String(OfficeXml.unzip(bytes)["content.xml"]!!, Charsets.UTF_8)

    @Test
    fun validationRulesRoundTrip() {
        val s = Sheet(name = "Tasks")
        s.ensure(0, 0).text = "Task"; s.ensure(3, 1).text = "x"
        val rules = listOf(
            DataValidation(1, 2, 9, 2, ValidationType.LIST, items = listOf("Not started", "In \"progress\"", "Done"),
                dropdown = false, inputTitle = "Status", inputMessage = "Pick one.\nOr leave it blank."),
            DataValidation(1, 3, 9, 3, ValidationType.DATE, operator = ValidationOperator.GREATER_OR_EQUAL, formula1 = "TODAY()",
                errorStyle = ValidationAlertStyle.WARNING, errorTitle = "Past date", errorMessage = "Due dates are today or later."),
            DataValidation(1, 4, 9, 4, ValidationType.INTEGER, 0.0, 40.0, operator = ValidationOperator.NOT_BETWEEN,
                errorStyle = ValidationAlertStyle.INFORMATION, allowBlank = false, showInput = false),
            DataValidation(1, 0, 9, 0, ValidationType.CUSTOM, formula1 = "COUNTIF(\$A\$2:\$A\$10,A2)=1", showError = false),
            DataValidation(1, 1, 9, 1, ValidationType.LIST, formula1 = "Lists!\$A\$1:\$A\$5"),
            DataValidation(0, 5, 0, 5, ValidationType.TIME, 0.375, 0.75),
            DataValidation(0, 6, 0, 6, ValidationType.TEXT_LENGTH, 10.0, operator = ValidationOperator.LESS_OR_EQUAL),
            DataValidation(0, 7, 0, 7, ValidationType.ANY, inputMessage = "Anything goes"),
            DataValidation(12, 8, 14, 9, ValidationType.DECIMAL, 1.5, operator = ValidationOperator.NOT_EQUAL, formula2 = null),
        )
        s.validations += rules
        val bytes = write(Workbook(mutableListOf(s, Sheet(name = "Lists"))))
        val xml = content(bytes)
        assertTrue(xml.contains("""table:condition="of:cell-content-is-whole-number() and cell-content-is-not-between(0,40)""""))
        assertTrue(xml.contains("""table:condition="of:cell-content-is-in-list([Lists.${'$'}A${'$'}1:.${'$'}A${'$'}5])""""))
        assertTrue(xml.contains("""table:condition="of:is-true-formula(COUNTIF([.${'$'}A${'$'}2:.${'$'}A${'$'}10];[.A2])=1)""""))
        assertTrue(xml.contains("""table:base-cell-address="Tasks.A2""""))
        assertTrue("an empty cell a rule covers names it", xml.contains("""<table:table-cell table:content-validation-name="val"""))
        val back = OdsReader.read(bytes).sheets[0].validations.sortedWith(compareBy({ it.r1 }, { it.c1 }))
        assertEquals(rules.sortedWith(compareBy({ it.r1 }, { it.c1 })), back)
    }

    @Test
    fun aWholeColumnRuleIsAFewElements() {
        val s = Sheet(name = "S")
        s.ensure(0, 0).text = "Header"
        s.validations += DataValidation(1, 0, 1_048_575, 0, ValidationType.LIST, items = listOf("a", "b"))
        val bytes = write(Workbook(mutableListOf(s)))
        val xml = content(bytes)
        assertTrue(xml.length < 20_000)
        assertTrue(xml.contains("""table:number-rows-repeated="1048575""""))
        val rule = OdsReader.read(bytes).sheets[0].validations.single()
        assertEquals(listOf(1, 0, 1_048_575, 0), listOf(rule.r1, rule.c1, rule.r2, rule.c2))
    }

    @Test
    fun namesFiltersAndQuotedSheets() {
        val data = Sheet(name = "My Data")
        listOf("Region", "North", "South", "", "West").forEachIndexed { r, t -> if (t.isNotEmpty()) data.ensure(r, 0).text = t }
        listOf("Sales", "10", "40", "25", "5").forEachIndexed { r, t -> data.ensure(r, 1).text = t }
        data.autoFilter = AutoFilter(0, 0, 4, 1, mutableMapOf(
            0 to ColumnFilter(values = setOf("North", "South"), blanks = true),
            1 to ColumnFilter(blanks = false, condition = FilterCondition(FilterCondition.Op.GREATER, "6")),
        ))
        data.hiddenRows += 4
        val main = Sheet(name = "Sheet1")
        main.ensure(0, 0).text = "='My Data'!B2*2"
        val wb = Workbook(mutableListOf(main, data))
        wb.names["Regions"] = "'My Data'!\$A\$2:\$A\$5"
        wb.names["Rate"] = "Sheet1!\$C\$1"
        val bytes = write(wb)
        val xml = content(bytes)
        assertTrue(xml.contains("""table:formula="of:=['My Data'.B2]*2""""))
        assertTrue(xml.contains("""table:cell-range-address="${'$'}'My Data'.${'$'}A${'$'}2:.${'$'}A${'$'}5""""))
        assertTrue(xml.contains("""table:target-range-address="'My Data'.A1:'My Data'.B5" table:display-filter-buttons="true""""))
        val back = OdsReader.read(bytes)
        assertEquals("='My Data'!B2*2", back.sheets[0].cell(0, 0)!!.text)
        assertEquals(wb.names, back.names)
        val af = back.sheets[1].autoFilter!!
        assertEquals(data.autoFilter, af)
        assertEquals(setOf(4), back.sheets[1].hiddenRows)
    }

    @Test
    fun readsWhatLibreOfficeWrites() {
        // The shapes of LibreOffice Calc's content.xml: a dependent list on the other sheet, a
        // bare comparison, repeated cells naming the rule, and a filter on one value.
        val content = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:of="urn:oasis:names:tc:opendocument:xmlns:of:1.2" office:version="1.3">
<office:body><office:spreadsheet>
<table:content-validations>
<table:content-validation table:name="val1" table:condition="of:cell-content-is-in-list([${'$'}Lists.${'$'}A${'$'}1:.${'$'}A${'$'}3])" table:allow-empty-cell="true" table:base-cell-address="Sheet1.B2" table:display-list="unsorted"><table:error-message table:message-type="stop" table:display="true"/></table:content-validation>
<table:content-validation table:name="val2" table:condition="of:cell-content()&gt;[.D1]" table:allow-empty-cell="true" table:base-cell-address="Sheet1.C2"><table:help-message table:title="Budget" table:display="true"><text:p>More than D1.</text:p></table:help-message><table:error-message table:message-type="warning" table:display="true"/></table:content-validation>
<table:content-validation table:name="val3" table:condition="of:cell-content-is-whole-number() and cell-content-is-between(0,40)" table:allow-empty-cell="true" table:base-cell-address="Sheet1.D2"><table:help-message table:display="true"/><table:error-message table:message-type="stop" table:display="true"/></table:content-validation>
</table:content-validations>
<table:table table:name="Sheet1">
<table:table-column table:number-columns-repeated="4"/>
<table:table-row><table:table-cell office:value-type="string"><text:p>Item</text:p></table:table-cell><table:table-cell table:number-columns-repeated="2"/><table:table-cell office:value-type="float" office:value="100"><text:p>100</text:p></table:table-cell></table:table-row>
<table:table-row table:number-rows-repeated="4"><table:table-cell/><table:table-cell table:content-validation-name="val1"/><table:table-cell table:content-validation-name="val2"/><table:table-cell table:content-validation-name="val3"/></table:table-row>
<table:table-row><table:table-cell office:value-type="string"><text:p>Pens</text:p></table:table-cell><table:table-cell table:content-validation-name="val1" office:value-type="string"><text:p>Blue</text:p></table:table-cell><table:table-cell table:content-validation-name="val2" office:value-type="float" office:value="150"><text:p>150</text:p></table:table-cell><table:table-cell/></table:table-row>
</table:table>
<table:table table:name="Lists"><table:table-column/><table:table-row><table:table-cell office:value-type="string"><text:p>Blue</text:p></table:table-cell></table:table-row></table:table>
<table:database-ranges><table:database-range table:name="__Anonymous_Sheet_DB__0" table:target-range-address="Sheet1.A1:Sheet1.D6" table:display-filter-buttons="true"><table:filter><table:filter-condition table:field-number="0" table:value="Pens" table:operator="="/></table:filter></table:database-range></table:database-ranges>
</office:spreadsheet></office:body></office:document-content>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("mimetype")); z.write("application/vnd.oasis.opendocument.spreadsheet".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("content.xml")); z.write(content.toByteArray()); z.closeEntry()
        }
        val wb = OdsReader.read(out.toByteArray())
        val rules = wb.sheets[0].validations.sortedBy { it.c1 }
        assertEquals(3, rules.size)
        assertEquals("LibreOffice puts a comma between the bounds", listOf(0.0, 40.0), listOf(rules[2].min, rules[2].max))
        assertEquals(ValidationType.INTEGER, rules[2].type)
        assertEquals(ValidationType.LIST, rules[0].type)
        assertEquals("Lists!\$A\$1:\$A\$3", rules[0].formula1)
        assertEquals("the runs join into one block", listOf(1, 1, 5, 1), rules[0].let { listOf(it.r1, it.c1, it.r2, it.c2) })
        assertEquals(ValidationType.DECIMAL, rules[1].type)
        assertEquals(ValidationOperator.GREATER, rules[1].operator)
        assertEquals("D1", rules[1].formula1)
        assertEquals(ValidationAlertStyle.WARNING, rules[1].errorStyle)
        assertEquals("More than D1.", rules[1].inputMessage)
        assertEquals(setOf("Pens"), wb.sheets[0].autoFilter!!.columns[0]!!.values)
    }
}
