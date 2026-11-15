package com.therealityteam.aeropages

import com.therealityteam.aeropages.ui.OpenRequests
import org.junit.Assert.assertEquals
import com.therealityteam.aeropages.ui.browserSaveTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Side-window pool: "open in new window" always picks a window that is not showing a document. */
class OpenRequestsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun saveNamesKeepVersionNumbersAndStayInTheChosenFolder() {
        val dir = temp.root
        assertEquals(File(dir, "预算.v2.ods"), browserSaveTarget(dir, "预算.v2", "ods"))
        assertEquals(File(dir, "预算.v2.ods"), browserSaveTarget(dir, "预算.v2.xlsx", "ods"))
        assertEquals(File(dir, "预算.ODS"), browserSaveTarget(dir, "预算.ODS", "ods"))
        for (name in listOf("", ".", "..", ".docx", "../outside", "a/b", "a\\b", "a\nb")) {
            assertNull(name, browserSaveTarget(dir, name, "docx"))
        }
    }

    @Test fun failedImportLeavesNoPartialDocument() {
        val dir = temp.root
        val original = File(dir, "report.docx").apply { writeText("original") }
        val interrupted = object : InputStream() {
            private var remaining = 10000
            override fun read(): Int = if (remaining-- > 0) 1 else throw IOException("provider failed")
        }
        assertThrows(IOException::class.java) { runBlocking { OpenRequests.importDocument(dir, original.name, interrupted) } }
        assertEquals(listOf(original), dir.listFiles()!!.toList())
        assertEquals("original", original.readText())
    }

    @Test fun concurrentImportsOfTheSameNameKeepEveryCopy() = runBlocking {
        val dir = temp.root
        val contents = listOf("first workbook", "second workbook", "third workbook")
        val paths = contents.map { value -> async(Dispatchers.IO) {
            OpenRequests.importDocument(dir, "budget.ods", value.byteInputStream())
        } }.awaitAll()
        assertEquals(3, paths.toSet().size)
        assertEquals(contents, paths.map { File(it).readText() })
        assertTrue(dir.listFiles()!!.none { it.name.startsWith(".") })
    }

    @Test
    fun idleSideWindowSkipsBusyWindows() {
        OpenRequests.SIDE_WINDOWS.forEach { OpenRequests.setBusy(it, false) }
        assertEquals("aux", OpenRequests.idleSideWindow())
        OpenRequests.setBusy("aux", true)
        assertEquals("aux2", OpenRequests.idleSideWindow())
        OpenRequests.setBusy("aux2", true); OpenRequests.setBusy("aux3", true)
        assertEquals("an occupied document must never be replaced", null, OpenRequests.idleSideWindow())
        OpenRequests.setBusy("aux2", false)
        assertEquals("aux2", OpenRequests.idleSideWindow())
        OpenRequests.SIDE_WINDOWS.forEach { OpenRequests.setBusy(it, false) }
    }
}
