package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Scenario Manager: named input sets, show with undo, summary sheet, xlsx `<scenarios>` round trip. */
class ScenarioTest {

    private fun editor(): SheetEditor = SheetEditor().apply {
        load(Workbook.blank())
        setCell(0, 0, "100"); setCell(1, 0, "5")          // price, quantity
        setCell(2, 0, "=A1*A2")                             // revenue
    }
    private fun SheetEditor.selectBlock(r1: Int, c1: Int, r2: Int, c2: Int) { select(r1, c1); extendSelection(r2 - r1, c2 - c1) }

    @Test
    fun scenariosStoreShowAndUndo() {
        val e = editor()
        e.selectBlock(0, 0, 1, 0)
        assertTrue(e.addScenario("Base"))
        e.setCell(0, 0, "120"); e.setCell(1, 0, "8")
        assertTrue(e.addScenario("Best"))
        assertEquals(listOf("Base", "Best"), e.sheet.scenarios.map { it.name })
        assertTrue(e.showScenario("Base"))
        assertEquals("100", e.rawValue(0, 0)); assertEquals("500", e.rawValue(2, 0))
        e.undo()
        assertEquals("120", e.rawValue(0, 0)); assertEquals("960", e.rawValue(2, 0))
        assertFalse(e.addScenario("  "))
        e.addScenario("Best")                              // same name replaces
        assertEquals(2, e.sheet.scenarios.size)
        e.deleteScenario("Base")
        assertEquals(listOf("Best"), e.sheet.scenarios.map { it.name })
        e.undo()
        assertEquals(2, e.sheet.scenarios.size)
        assertFalse(e.showScenario("nope"))
    }

    @Test
    fun summarySheetListsInputsAndResultsPerScenario() {
        val e = editor()
        e.selectBlock(0, 0, 1, 0)
        e.addScenario("Base")
        e.setCell(0, 0, "120"); e.setCell(1, 0, "8")
        e.addScenario("Best")
        e.select(2, 0)                                       // the result cell
        val name = e.scenarioSummary()
        assertNotNull(name)
        val s = e.sheet
        assertEquals(name, s.name)
        assertEquals("Cell", s.cell(0, 0)!!.text); assertEquals("Current Values", s.cell(0, 1)!!.text)
        assertEquals("Base", s.cell(0, 2)!!.text); assertEquals("Best", s.cell(0, 3)!!.text)
        assertEquals("A1", s.cell(1, 0)!!.text); assertEquals("120", s.cell(1, 1)!!.text); assertEquals("100", s.cell(1, 2)!!.text); assertEquals("120", s.cell(1, 3)!!.text)
        assertEquals("A2", s.cell(2, 0)!!.text)
        assertEquals("A3", s.cell(3, 0)!!.text); assertEquals("960", s.cell(3, 1)!!.text); assertEquals("500", s.cell(3, 2)!!.text); assertEquals("960", s.cell(3, 3)!!.text)
        assertEquals("inputs untouched", "120", e.workbook.sheets[0].cell(0, 0)!!.text)
        e.undo()
        assertEquals(1, e.workbook.sheets.size)
    }

    @Test
    fun xlsxRoundTripKeepsScenarios() {
        val e = editor()
        e.selectBlock(0, 0, 1, 0)
        e.addScenario("Base")
        e.setCell(0, 0, "120"); e.addScenario("Best & <Bold>")
        val bos = ByteArrayOutputStream()
        XlsxWriter.write(e.workbook, bos)
        val xml = java.util.zip.ZipInputStream(bos.toByteArray().inputStream()).let { z ->
            var out = ""
            while (true) { val en = z.nextEntry ?: break; if (en.name == "xl/worksheets/sheet1.xml") out = z.readBytes().decodeToString() }
            out
        }
        assertTrue("scenarios element after sheetData", xml.indexOf("<scenarios") > xml.indexOf("</sheetData>"))
        assertTrue(xml.contains("""<inputCells r="A1" val="100"/>"""))
        val back = XlsxReader.read(bos.toByteArray())
        val sc = back.sheets[0].scenarios
        assertEquals(listOf("Base", "Best & <Bold>"), sc.map { it.name })
        assertEquals("100", sc[0].values[0L]); assertEquals("5", sc[0].values[1000L])
        assertEquals("120", sc[1].values[0L])
    }
}
