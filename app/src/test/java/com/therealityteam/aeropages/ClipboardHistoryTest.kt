package com.therealityteam.aeropages

import com.therealityteam.aeropages.ui.ClipboardHistory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Office Clipboard's collection: newest first, a repeat moves to the top, at most 24 items. */
class ClipboardHistoryTest {
    @After fun empty() = ClipboardHistory.clear()

    @Test
    fun itemsCollectNewestFirstWithoutRepeatsUpToTheLimit() {
        ClipboardHistory.clear()
        ClipboardHistory.record("alpha"); ClipboardHistory.record("beta"); ClipboardHistory.record("alpha")
        assertEquals(listOf("alpha", "beta"), ClipboardHistory.items.map { it.text })
        ClipboardHistory.record("")
        assertEquals("nothing copied is not an item", 2, ClipboardHistory.items.size)
        repeat(30) { ClipboardHistory.record("item $it") }
        assertEquals(ClipboardHistory.MAX, ClipboardHistory.items.size)
        assertEquals("item 29", ClipboardHistory.items.first().text)
        ClipboardHistory.remove(ClipboardHistory.items.first())
        assertEquals("item 28", ClipboardHistory.items.first().text)
    }
}
