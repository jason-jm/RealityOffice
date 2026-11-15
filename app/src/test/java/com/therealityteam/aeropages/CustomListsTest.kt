package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.AutoFill
import com.therealityteam.aeropages.editor.CustomLists
import com.therealityteam.aeropages.editor.SheetEditor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Excel's custom lists: built-in ones in every app language, the user's own, AutoFill continuing them, Sort ordering by them. */
class CustomListsTest {
    @After fun noUserLists() { CustomLists.user = emptyList() }

    private fun next(vararg seeds: String, count: Int = 3): List<String?> =
        AutoFill.fill(seeds.map { AutoFill.Seed(it) }, (seeds.size until seeds.size + count).toList()).map { it.text }

    @Test
    fun theBuiltInListsSpeakEveryAppLanguage() {
        val lists = CustomLists.builtIn
        assertTrue("German weekdays, Sunday first", lists.any { it.take(2) == listOf("Sonntag", "Montag") })
        assertTrue("French months, capitalised as Excel lists them", lists.any { it.firstOrNull() == "Janvier" })
        assertTrue("Russian months stand alone", lists.any { it.firstOrNull() == "Январь" })
        assertTrue("Korean weekdays", lists.any { it.firstOrNull() == "일요일" })
        assertTrue("none blank or numeric, each once", lists.all { l -> l.none { e -> e.isBlank() || e.all(Char::isDigit) } && l.size == l.distinctBy { it.lowercase() }.size })
        assertEquals("English first (May continues the short months)", listOf("Jun", "Jul", "Aug"), next("May"))
    }

    @Test
    fun autoFillContinuesThemInTheSeedsCapitals() {
        assertEquals(listOf("Dienstag", "Mittwoch", "Donnerstag"), next("Montag"))
        assertEquals(listOf("mardi", "mercredi", "jeudi"), next("lundi"))
        assertEquals(listOf("ФЕВРАЛЬ", "МАРТ", "АПРЕЛЬ"), next("ЯНВАРЬ"))
        assertEquals("every other entry", listOf("Abril", "Junio", "Agosto"), next("Diciembre", "Febrero"))
        // The user's own list.
        CustomLists.user = listOf(listOf("Low", "Medium", "High"))
        assertEquals(listOf("Medium", "High", "Low"), next("Low"))
    }

    @Test
    fun aListIsTypedOrImportedFromCells() {
        assertEquals(listOf("North", "South", "East"), CustomLists.parse("North\n South \n\nnorth\nEast"))
        assertEquals(listOf("A", "B"), CustomLists.parse("A, B"))
        val e = SheetEditor()
        listOf("Bronze", "Silver", "", "Gold", "silver").forEachIndexed { r, t -> if (t.isNotEmpty()) e.sheet.ensure(r, 1).text = t }
        assertEquals(listOf("Bronze", "Silver", "Gold"), e.listFromCells("B1:B5"))
        assertNull("not cells", e.listFromCells("nonsense"))
        assertEquals("Sun, Mon, Tue", CustomLists.label(listOf("Sun", "Mon", "Tue")))
    }

    @Test
    fun sortOrdersByTheListValuesNotInItAfter() {
        val e = SheetEditor()
        val rows = listOf("Priority" to "Task", "High" to "a", "Low" to "b", "Urgent" to "c", "medium" to "d", "Low" to "e")
        rows.forEachIndexed { r, (p, t) -> e.sheet.ensure(r, 0).text = p; e.sheet.ensure(r, 1).text = t }
        e.select(1, 0)
        val list = listOf("Low", "Medium", "High")
        assertTrue(e.sortLevels(listOf(SheetEditor.SortLevel(0, custom = list)), header = true))
        assertEquals(listOf("Priority", "Low", "Low", "medium", "High", "Urgent"), (0..5).map { e.sheet.cell(it, 0)!!.text })
        assertEquals("ties keep their order", listOf("b", "e"), (1..2).map { e.sheet.cell(it, 1)!!.text })
        assertTrue(e.sortLevels(listOf(SheetEditor.SortLevel(0, ascending = false, custom = list)), header = true))
        assertEquals("descending: the list reversed, the rest still after", listOf("High", "medium", "Low", "Low", "Urgent"), (1..5).map { e.sheet.cell(it, 0)!!.text })
        e.undo(); e.undo()
        assertEquals("High", e.sheet.cell(1, 0)!!.text)
    }
}
