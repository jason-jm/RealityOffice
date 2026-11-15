package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaFunctions
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import com.therealityteam.aeropages.ui.FunctionCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Insert Function's catalogue and AutoSum's ranges. */
class FunctionLibraryTest {

    @Test
    fun everyFunctionHasOneCategoryAndALine() {
        for (sig in FormulaFunctions.all) {
            val cats = FunctionCategory.entries.filter { sig.name in it.names }
            assertEquals("${sig.name} in exactly one category", 1, cats.size)
            assertNotNull("${sig.name} described", FunctionCategory.help(sig.name))
        }
        val known = FormulaFunctions.all.map { it.name }.toSet()
        FunctionCategory.entries.flatMap { it.names }.forEach { assertTrue("$it is a function the engine knows", it in known) }
        FunctionCategory.COMMON.forEach { assertTrue(it in known) }
        // Each description is there in every language.
        val ids = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        for (dir in listOf("values", "values-zh", "values-zh-rTW", "values-ja")) {
            val xml = File("src/main/res/$dir/strings.xml").readText()
            for (sig in FormulaFunctions.all) {
                val key = ids[FunctionCategory.help(sig.name)!!]!!
                assertTrue("$dir has $key", xml.contains("<string name=\"$key\">"))
            }
        }
    }

    @Test
    fun searchFindsByNameThenByWhatItDoes() {
        val words = mapOf(FunctionCategory.help("VLOOKUP")!! to "Looks for a value in the first column", FunctionCategory.help("TODAY")!! to "Returns today's date")
        val describe = { id: Int -> words[id] ?: "" }
        assertEquals(FunctionCategory.COMMON, FunctionCategory.find(null, true, "", describe).map { it.name })
        assertEquals(listOf("FINANCIAL"), listOf(FunctionCategory.of("PMT")!!.name))
        val sum = FunctionCategory.find(null, false, "sum", describe).map { it.name }
        assertEquals("names starting with the words first", "SUM", sum.first())
        assertTrue("DSUMish names further down", sum.contains("SUMIFS") && sum.contains("SUBTOTAL").not())
        assertEquals(listOf("TODAY"), FunctionCategory.find(null, false, "today's", describe).map { it.name })
        assertTrue(FunctionCategory.find(FunctionCategory.DATE, false, "", describe).all { FunctionCategory.of(it.name) == FunctionCategory.DATE })
    }

    private fun editor(): SheetEditor {
        val s = Sheet()
        s.ensure(0, 1).text = "Amount"
        listOf("10", "20", "30").forEachIndexed { i, v -> s.ensure(1 + i, 1).text = v }
        s.ensure(5, 0).text = "4"; s.ensure(5, 1).text = "6"
        return SheetEditor().apply { load(Workbook(mutableListOf(s))) }
    }

    @Test
    fun autoSumTakesTheNumbersAboveElseLeft() {
        val e = editor()
        e.select(4, 1)
        assertEquals("the run above, not the header", "B2:B4", e.autoSumRange())
        assertEquals("B2:B4", e.autoFunction("SUM"))
        assertEquals("=SUM(B2:B4)", e.sheet.cell(4, 1)!!.text)
        assertEquals("60", e.rawValue(4, 1))
        e.select(5, 2)
        assertEquals("nothing above: the run to the left", "A6:B6", e.autoSumRange())
        e.select(8, 4)
        assertNull(e.autoSumRange())
        assertNull(e.autoFunction("AVERAGE"))
        assertEquals("left open to type the range", "=AVERAGE(", e.sheet.cell(8, 4)!!.text)
    }
}
