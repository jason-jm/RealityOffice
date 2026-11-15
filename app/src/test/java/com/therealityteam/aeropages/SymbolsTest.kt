package com.therealityteam.aeropages

import com.therealityteam.aeropages.ui.Symbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Insert → Symbol: the subsets, the search by name or code, the recently used list. */
class SymbolsTest {

    @Test
    fun everySubsetHoldsNamedCharactersOnce() {
        for (s in Symbols.SUBSETS) {
            assertTrue(s.chars.isNotEmpty())
            assertEquals("no character twice in a subset", s.chars.size, s.chars.distinct().size)
            for (c in s.chars) assertTrue("$c (${Symbols.code(c)}) has a Unicode name", Symbols.name(c).isNotEmpty())
        }
        assertEquals("GREEK SMALL LETTER ALPHA", Symbols.name("α"))
        assertEquals("U+03B1", Symbols.code("α"))
        assertEquals("U+00B0", Symbols.code("°"))
    }

    @Test
    fun searchFindsByNameWordsOrCode() {
        assertTrue(Symbols.search("arrow").containsAll(listOf("←", "→", "⇒")))
        assertTrue("all the words", Symbols.search("right arrow").let { "→" in it && "←" !in it })
        assertEquals(listOf("°"), Symbols.search("00B0"))
        assertEquals(listOf("→"), Symbols.search("U+2192"))
        assertTrue(Symbols.search("yen").contains("¥"))
        assertTrue(Symbols.search("").isEmpty())
        assertTrue(Symbols.search("no such symbol at all").isEmpty())
    }

    @Test
    fun recentlyUsedComeFirstAndAreKept() {
        var recent = emptyList<String>()
        recent = Symbols.used(recent, "α"); recent = Symbols.used(recent, "→"); recent = Symbols.used(recent, "α")
        assertEquals(listOf("α", "→"), recent)
        assertEquals(recent, Symbols.decode(Symbols.encode(recent)))
        assertEquals(listOf("α", "→", "©", "®"), Symbols.menu(recent, 4))
        assertEquals(20, (0 until 30).fold(emptyList<String>()) { r, i -> Symbols.used(r, Symbols.SUBSETS[1].chars[i]) }.size)
        assertEquals(emptyList<String>(), Symbols.decode("zz"))
    }
}
