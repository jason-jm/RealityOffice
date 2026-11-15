package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Excel's AutoComplete and Pick From Drop-down List: the entries of the column around the cell being typed in. */
class AutoCompleteTest {

    /** A1:A3 Apple, Banana, Apricot and a number; A5 empty; A6 Cherry (another block). */
    private fun fruit(): SheetEditor = SheetEditor().apply {
        load(Workbook.blank())
        listOf("Apple", "Banana", "Apricot", "42").forEachIndexed { r, t -> setCell(r, 0, t) }
        setCell(6, 0, "Cherry")
    }

    @Test
    fun aUniqueEntryCompletesWhatWasTyped() {
        val e = fruit()
        assertEquals("Banana", e.autoComplete(4, 0, "b"))
        assertEquals("Apple", e.autoComplete(4, 0, "App"))
        assertNull("two entries begin with A", e.autoComplete(4, 0, "A"))
        assertNull("already the whole entry", e.autoComplete(4, 0, "apple"))
        assertNull("numbers never complete", e.autoComplete(4, 0, "4"))
        assertNull("formulas never complete", e.autoComplete(4, 0, "=A"))
        assertNull("the block ends at the empty row", e.autoComplete(4, 0, "Ch"))
        assertEquals("from below too", "Cherry", e.autoComplete(5, 0, "c"))
    }

    @Test
    fun anAcceptedCompletionTakesTheEntrysCase() {
        val e = fruit()
        e.select(4, 0)
        e.typeCell(4, 0, "banana"); e.noteCompletion("Banana")
        e.commitEntry()
        assertEquals("Banana", e.sheet.cell(4, 0)!!.text)

        e.select(5, 0)
        e.typeCell(5, 0, "Cherry pie"); e.noteCompletion("Cherry")
        e.commitEntry()
        assertEquals("typed on past it: as typed", "Cherry pie", e.sheet.cell(5, 0)!!.text)
    }

    @Test
    fun thePickListOffersTheColumnsEntriesInOrder() {
        val e = fruit()
        assertEquals(listOf("Apple", "Apricot", "Banana"), e.pickChoices(4, 0))
        assertNull("an empty column has nothing to pick", e.pickChoices(4, 3))
        e.select(4, 0)
        e.pickFromList("Apricot")
        assertEquals("Apricot", e.sheet.cell(4, 0)!!.text)
    }
}
