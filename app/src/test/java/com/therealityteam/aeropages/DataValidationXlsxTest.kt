package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.ValidationAlertStyle
import com.therealityteam.aeropages.model.ValidationOperator
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Data validation in xlsx: every attribute Excel writes, formulas, several ranges, Excel 2010's extension form. */
class DataValidationXlsxTest {

    private fun write(wb: Workbook): ByteArray = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()

    private fun sheetXml(bytes: ByteArray) = String(OfficeXml.unzip(bytes)["xl/worksheets/sheet1.xml"]!!, Charsets.UTF_8)

    /** [bytes] with its first worksheet rewritten by [edit]. */
    private fun patched(bytes: ByteArray, edit: (String) -> String): ByteArray {
        val parts = OfficeXml.unzip(bytes)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            parts.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                z.write(if (name == "xl/worksheets/sheet1.xml") edit(String(data, Charsets.UTF_8)).toByteArray() else data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun everyPartOfARuleRoundTrips() {
        val s = Sheet(name = "Tasks")
        s.validations += DataValidation(1, 2, 9, 2, ValidationType.LIST, items = listOf("Not started", "In \"progress\"", "Done"),
            dropdown = false, inputTitle = "Status", inputMessage = "Pick one.\nOr leave it blank.")
        s.validations += DataValidation(1, 3, 9, 3, ValidationType.DATE, operator = ValidationOperator.GREATER_OR_EQUAL, formula1 = "TODAY()",
            errorStyle = ValidationAlertStyle.WARNING, errorTitle = "Past date", errorMessage = "Due dates are today or later.")
        s.validations += DataValidation(1, 4, 9, 4, ValidationType.INTEGER, 0.0, 40.0, operator = ValidationOperator.NOT_BETWEEN,
            errorStyle = ValidationAlertStyle.INFORMATION, allowBlank = false, showInput = false)
        s.validations += DataValidation(1, 0, 9, 0, ValidationType.CUSTOM, formula1 = "COUNTIF(\$A\$2:\$A\$10,A2)=1", showError = false)
        s.validations += DataValidation(1, 1, 9, 1, ValidationType.LIST, formula1 = "Lists!\$A\$1:\$A\$5")
        s.validations += DataValidation(0, 5, 0, 5, ValidationType.TIME, 0.375, 0.75)
        s.validations += DataValidation(0, 6, 0, 6, ValidationType.TEXT_LENGTH, 10.0, operator = ValidationOperator.LESS_OR_EQUAL)
        s.validations += DataValidation(0, 7, 0, 7, ValidationType.ANY, inputMessage = "Anything goes")
        val bytes = write(Workbook(mutableListOf(s, Sheet(name = "Lists"))))
        val xml = sheetXml(bytes)
        assertTrue(xml.contains("""<dataValidation type="list" allowBlank="1" showDropDown="1" showInputMessage="1" showErrorMessage="1" promptTitle="Status" prompt="Pick one.&#10;Or leave it blank." sqref="C2:C10"><formula1>&quot;Not started,In &quot;&quot;progress&quot;&quot;,Done&quot;</formula1></dataValidation>"""))
        assertTrue(xml.contains("""<dataValidation type="date" errorStyle="warning" operator="greaterThanOrEqual" allowBlank="1" showInputMessage="1" showErrorMessage="1" errorTitle="Past date" error="Due dates are today or later." sqref="D2:D10"><formula1>TODAY()</formula1></dataValidation>"""))
        assertTrue(xml.contains("""<dataValidation type="whole" errorStyle="information" operator="notBetween" showErrorMessage="1" sqref="E2:E10"><formula1>0</formula1><formula2>40</formula2></dataValidation>"""))
        assertTrue(xml.contains("""<dataValidation type="custom" allowBlank="1" showInputMessage="1" sqref="A2:A10"><formula1>COUNTIF(${'$'}A${'$'}2:${'$'}A${'$'}10,A2)=1</formula1></dataValidation>"""))
        assertTrue("one cell is written as one", xml.contains("""sqref="F1"><formula1>0.375</formula1><formula2>0.75</formula2>"""))
        assertTrue(xml.contains("""<dataValidation type="none" allowBlank="1" showInputMessage="1" showErrorMessage="1" prompt="Anything goes" sqref="H1"/>""") ||
            xml.contains("""<dataValidation type="none" allowBlank="1" showInputMessage="1" showErrorMessage="1" prompt="Anything goes" sqref="H1"></dataValidation>"""))
        val back = XlsxReader.read(bytes).sheets[0]
        assertEquals(s.validations, back.validations)
    }

    @Test
    fun severalRangesAndExcel2010Lists() {
        val s = Sheet(name = "S")
        s.validations += DataValidation(0, 0, 0, 0, ValidationType.INTEGER, 1.0, 5.0)
        val bytes = patched(write(Workbook(mutableListOf(s, Sheet(name = "Lists"))))) { xml ->
            xml.replace(Regex("<dataValidations.*</dataValidations>"),
                """<dataValidations count="1"><dataValidation type="custom" showErrorMessage="1" sqref="A1:A3 C5:C6"><formula1>A1&gt;B1</formula1></dataValidation></dataValidations>""")
                .replace("</worksheet>", """<extLst><ext uri="{CCE6A557-97BC-4b89-ADB6-D9C93CAAB3DF}" xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main"><x14:dataValidations count="1" xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main"><x14:dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="1"><x14:formula1><xm:f>Lists!${'$'}A${'$'}1:${'$'}A${'$'}3</xm:f></x14:formula1><xm:sqref>E1:E20</xm:sqref></x14:dataValidation></x14:dataValidations></ext></extLst></worksheet>""")
        }
        val rules = XlsxReader.read(bytes).sheets[0].validations
        assertEquals(3, rules.size)
        assertEquals("A1>B1", rules[0].formula1)
        assertEquals(listOf(4, 2, 5, 2), rules[1].let { listOf(it.r1, it.c1, it.r2, it.c2) })
        assertEquals("the second range's formula is written for its own top-left cell", "C5>D5", rules[1].formula1)
        val list = rules[2]
        assertEquals(ValidationType.LIST, list.type)
        assertEquals("Lists!\$A\$1:\$A\$3", list.formula1)
        assertEquals(listOf(0, 4, 19, 4), listOf(list.r1, list.c1, list.r2, list.c2))
        assertTrue(list.dropdown)
    }

    @Test
    fun anEmptyListIsNotWritten() {
        val s = Sheet(name = "S")
        s.validations += DataValidation(0, 0, 0, 0, ValidationType.LIST)
        s.validations += DataValidation(1, 0, 1, 0, ValidationType.LIST, items = listOf("a"))
        val xml = sheetXml(write(Workbook(mutableListOf(s))))
        assertTrue(xml.contains("""<dataValidations count="1">"""))
        assertFalse(xml.contains("&quot;&quot;"))
    }

    @Test
    fun ooxmlsFlagsDefaultToOff() {
        val s = Sheet(name = "S")
        s.validations += DataValidation(0, 0, 0, 0, ValidationType.INTEGER, 1.0, 5.0)
        val bytes = patched(write(Workbook(mutableListOf(s)))) { xml ->
            xml.replace(Regex("<dataValidations.*</dataValidations>"),
                """<dataValidations count="1"><dataValidation type="whole" sqref="A1"><formula1>1</formula1><formula2>5</formula2></dataValidation></dataValidations>""")
        }
        val rule = XlsxReader.read(bytes).sheets[0].validations.single()
        assertFalse("no showErrorMessage: Excel lets any entry stand", rule.showError)
        assertFalse(rule.showInput)
        assertFalse(rule.allowBlank)
    }
}
