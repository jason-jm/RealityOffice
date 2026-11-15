package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** P2 features: data validation, named ranges, keyword filter, CSV export. */
class SheetsP2Test {

    @Test fun validationRejectsAndAccepts() {
        val e = SheetEditor()
        e.select(0, 0); e.extendSelection(2, 0)
        e.addValidation(ValidationType.INTEGER, 1.0, 10.0, emptyList())
        e.setCell(0, 0, "5")
        assertEquals("5", e.sheet.cell(0, 0)?.text)
        assertNull(e.validationError)
        e.setCell(1, 0, "99")          // out of range → rejected
        assertNull(e.sheet.cell(1, 0)?.text?.takeIf { it.isNotEmpty() })
        assertEquals(com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.VALIDATION_OP_BETWEEN,
            com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.VALIDATION_KIND_INTEGER), "1", "10"), e.validationError)
        e.setCell(1, 0, "abc")         // not an integer → rejected
        assertEquals(com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.VALIDATION_INTEGER_REQUIRED), e.validationError)
        // outside the rule range → accepted freely
        e.setCell(5, 5, "anything")
        assertEquals("anything", e.sheet.cell(5, 5)?.text)
    }

    @Test fun listValidation() {
        val e = SheetEditor()
        e.select(0, 0)
        e.addValidation(ValidationType.LIST, 0.0, 0.0, listOf("是", "否"))
        e.setCell(0, 0, "是")
        assertEquals("是", e.sheet.cell(0, 0)?.text)
        e.setCell(0, 0, "也许")
        assertTrue(e.validationError!!.startsWith(com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.VALIDATION_LIST_ONLY, "").trimEnd()))
        assertEquals("是", e.sheet.cell(0, 0)?.text) // unchanged
    }

    @Test fun validationRoundTripsToXlsx() {
        val s = Sheet(name = "V")
        s.ensure(0, 0).text = "3"
        s.validations.add(DataValidation(0, 0, 4, 0, ValidationType.INTEGER, 1.0, 10.0))
        s.validations.add(DataValidation(0, 1, 4, 1, ValidationType.LIST, items = listOf("红", "绿", "蓝")))
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(Workbook(mutableListOf(s)), bos)
        val a = XlsxReader.read(bos.toByteArray()).sheets[0]
        assertEquals(2, a.validations.size)
        val intRule = a.validations.first { it.type == ValidationType.INTEGER }
        assertEquals(1.0, intRule.min, 0.001)
        assertEquals(10.0, intRule.max, 0.001)
        val listRule = a.validations.first { it.type == ValidationType.LIST }
        assertEquals(listOf("红", "绿", "蓝"), listRule.items)
    }

    @Test fun namedRangesInFormulas() {
        val e = SheetEditor()
        listOf(10, 20, 30).forEachIndexed { r, v -> e.sheet.ensure(r, 0).text = v.toString() }
        e.select(0, 0); e.extendSelection(2, 0)
        assertTrue(e.defineName("销售额"))
        // Absolute, as Excel stores names (it reads a relative one relative to the cell using it).
        assertEquals("Sheet1!\$A\$1:\$A\$3", e.workbook.names["销售额"])
        e.sheet.ensure(5, 5).text = "=SUM(销售额)"
        assertEquals("60", e.display(5, 5))
        e.sheet.ensure(6, 5).text = "=销售额"   // a block named alone is its values (an array, shown as one cell shows it)
        assertEquals("10, 20, 30", e.display(6, 5))
        // names round-trip through xlsx
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(e.workbook, bos)
        val back = XlsxReader.read(bos.toByteArray())
        assertEquals("Sheet1!\$A\$1:\$A\$3", back.names["销售额"])
        e.deleteName("销售额")
        assertNull(e.workbook.names["销售额"])
    }

    @Test fun filterContainsHidesRows() {
        val e = SheetEditor()
        listOf("名称", "苹果汁", "橙汁", "苹果派").forEachIndexed { r, v -> e.sheet.ensure(r, 0).text = v }
        e.select(0, 0)
        e.filterContains("苹果")
        assertTrue("橙汁 hidden", 2 in e.hiddenRows)
        assertTrue("苹果汁 visible", 1 !in e.hiddenRows)
        assertTrue("header visible", 0 !in e.hiddenRows)
        e.clearFilter()
        assertTrue(e.hiddenRows.isEmpty())
    }

    @Test fun csvExportEscapes() {
        val e = SheetEditor()
        e.sheet.ensure(0, 0).text = "普通"
        e.sheet.ensure(0, 1).text = "带,逗号"
        e.sheet.ensure(1, 0).text = "带\"引号\""
        e.sheet.ensure(1, 1).text = "=1+1"
        val csv = e.toCsv()
        val lines = csv.split("\n")
        assertEquals("普通,\"带,逗号\"", lines[0])
        assertEquals("\"带\"\"引号\"\"\",2", lines[1]) // formula exported as its value
    }
}
