package com.therealityteam.aeropages

import com.therealityteam.aeropages.ui.AppCommand
import com.therealityteam.aeropages.ui.CommandSearch
import com.therealityteam.aeropages.ui.RecentCommands
import com.therealityteam.aeropages.ui.findCommands
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The title bar's Search: which commands a query finds, and in what order. */
class CommandSearchTest {
    private fun cmd(label: String) = AppCommand(label, "", "Home") {}

    @Test
    fun namesHoldingTheQueryThoseStartingWithItFirst() {
        val all = listOf(cmd("Insert Footnote"), cmd("Page Number"), cmd("Page Setup…"), cmd("Page Color"), cmd("Footer"), cmd("Page Number"))
        assertEquals(listOf("Page Number", "Page Setup…", "Page Color"), findCommands(all, "page").map { it.label })
        assertEquals("the … of a dialog's name is ignored, and case", listOf("Page Setup…"), findCommands(all, "SETUP").map { it.label })
        assertEquals(listOf("Footer", "Insert Footnote"), findCommands(all, "foot").map { it.label })
        assertTrue(findCommands(all, "  ").isEmpty())
        assertEquals(listOf("页码", "页边距"), findCommands(listOf(cmd("插入页码"), cmd("页码"), cmd("页边距")), "页").map { it.label }.take(2))
    }

    @Test
    fun aPickedActionRunsEndsTheSearchAndIsRecentFirstPerModule() {
        RecentCommands.clear()
        var ran = 0
        val bold = AppCommand("Bold", "B", "Home") { ran++ }
        val margins = AppCommand("Margins", "", "Layout") {}
        val search = CommandSearch("pages", "bo")
        assertTrue(search.active)
        search.run(bold)
        assertEquals(1, ran)
        assertEquals("the search ends", "", search.query)
        assertFalse(search.active)
        search.run(margins); search.run(bold)
        assertEquals("newest first, no repeats", listOf("Bold", "Margins"), RecentCommands.of("pages"))
        assertTrue("each module its own", RecentCommands.of("sheets").isEmpty())
        repeat(7) { i -> RecentCommands.use("pages", "c$i") }
        assertEquals("five at most", listOf("c6", "c5", "c4", "c3", "c2"), RecentCommands.of("pages"))
        search.focused = true
        assertTrue("focused with nothing typed: the suggestions show", search.active)
        RecentCommands.clear()
    }
}
