package com.therealityteam.aeropages

import androidx.compose.runtime.mutableStateOf
import com.therealityteam.aeropages.ui.RibbonFold
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Office's Ribbon Display Options: tabs only, a tab click showing its commands, and back to always showing. */
class RibbonFoldTest {
    @Test
    fun tabsOnlyShowsATabsCommandsUntilItIsClickedAgain() {
        val kept = mutableStateOf(false)
        val fold = RibbonFold(kept)
        assertTrue("always shown to begin with", fold.shown)
        fold.tabClicked(alreadySelected = true)
        assertTrue("a tab click changes nothing while the ribbon always shows", fold.shown)
        fold.toggle()
        assertTrue("tabs only, kept", kept.value)
        assertFalse(fold.shown)
        fold.tabClicked(alreadySelected = false)
        assertTrue("a tab clicked: its commands show", fold.shown)
        fold.tabClicked(alreadySelected = false)
        assertTrue("another tab: still showing", fold.shown)
        fold.tabClicked(alreadySelected = true)
        assertFalse("the same tab again: put away", fold.shown)
        fold.tabClicked(alreadySelected = true)
        assertTrue(fold.peek)
        fold.collapsed = false
        assertFalse("always showing again: no peek left over", fold.peek)
        assertTrue(fold.shown)
        assertFalse(kept.value)
    }
}
