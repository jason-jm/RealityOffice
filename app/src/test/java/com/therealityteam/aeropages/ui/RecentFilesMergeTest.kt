package com.therealityteam.aeropages.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecentFilesMergeTest {
    /** A file reached through a linked folder (/sdcard → /storage/emulated/0) is listed once, at its latest use. */
    @Test
    fun aFileUnderTwoPathsIsListedOnce() {
        val root = Files.createTempDirectory("recents").toFile()
        val real = File(root, "emulated").apply { mkdirs() }
        val link = File(root, "sdcard")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val doc = File(real, "smoke.docx").apply { writeText("x"); setLastModified(1_000_000L) }
        val other = File(real, "tables.xlsx").apply { writeText("y"); setLastModified(2_000_000L) }
        val recent = listOf(RecentFiles.Recent(File(link, "smoke.docx").path, 5_000_000L))

        val merged = RecentFiles.merge(recent, listOf(doc, other), emptySet(), limit = 10)

        assertEquals(listOf("smoke.docx", "tables.xlsx"), merged.map { File(it.path).name })
        assertEquals(5_000_000L, merged.first().at)
        root.deleteRecursively()
    }

    /** Taking a file off the list hides it whichever path it was dismissed under. */
    @Test
    fun aDismissedFileStaysOffUnderEitherPath() {
        val root = Files.createTempDirectory("recents").toFile()
        val real = File(root, "emulated").apply { mkdirs() }
        val link = File(root, "sdcard")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val doc = File(real, "smoke.docx").apply { writeText("x") }

        val merged = RecentFiles.merge(emptyList(), listOf(doc), setOf(File(link, "smoke.docx").path), limit = 10)

        assertEquals(emptyList<RecentFiles.Recent>(), merged)
        root.deleteRecursively()
    }
}
