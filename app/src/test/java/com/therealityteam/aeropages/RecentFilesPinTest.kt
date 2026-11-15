package com.therealityteam.aeropages

import com.therealityteam.aeropages.ui.RecentFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Office's Pinned: the pinned documents that still exist, once each however their paths were written, latest use first. */
class RecentFilesPinTest {
    @Test
    fun thePinnedListKeepsWhatExistsOnceEachLatestFirst() {
        val dir = Files.createTempDirectory("pins").toFile()
        val a = File(dir, "a.docx").apply { writeText("a"); setLastModified(1_000) }
        val b = File(dir, "b.xlsx").apply { writeText("b"); setLastModified(2_000) }
        val link = File(dir, "link").also { Files.createSymbolicLink(it.toPath(), dir.toPath()) }
        val gone = File(dir, "gone.pptx")
        val pinned = setOf(a.path, File(link, "b.xlsx").path, gone.path)
        // a was opened lately (the recent list knows when); b only has its file's time.
        val known = listOf(RecentFiles.Recent(a.path, 9_000))
        val list = RecentFiles.pinnedOf(pinned, known)
        assertEquals(listOf("a.docx", "b.xlsx"), list.map { it.file.name })
        assertEquals(9_000L, list[0].at)
        assertTrue("pinned through another path", RecentFiles.isPinned(pinned, b.path))
        assertFalse(RecentFiles.isPinned(pinned, File(dir, "c.docx").path))
        dir.deleteRecursively()
    }
}
