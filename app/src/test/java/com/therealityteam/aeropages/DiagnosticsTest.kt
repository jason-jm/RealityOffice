package com.therealityteam.aeropages

import com.therealityteam.aeropages.platform.Diagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Crash reports are written and pruned, the session log rotates, and everything exports as one zip. */
class DiagnosticsTest {
    @Test
    fun crashReportsLogAndExportBundle() {
        val dir = File(System.getProperty("java.io.tmpdir"), "aero-diag-${System.nanoTime()}")
        Diagnostics.dir = dir
        val report = Diagnostics.recordCrash("main", IllegalStateException("boom"), "AeroDesk test")
        val text = report.readText()
        assertTrue(text.contains("AeroDesk test")); assertTrue(text.contains("thread: main")); assertTrue(text.contains("IllegalStateException: boom"))
        assertTrue(text.contains("DiagnosticsTest")) // the stack trace itself
        repeat(12) { Diagnostics.recordCrash("worker", RuntimeException("n$it")) }
        assertEquals("only the newest ten reports are kept", 10, Diagnostics.crashFiles().size)
        Diagnostics.log("save", "/tmp/x.docx", java.io.IOException("disk full"))
        val log = File(dir, "aero.log").readText()
        assertTrue(log.contains("[save] /tmp/x.docx — IOException: disk full")); assertTrue(log.contains("[crash] RuntimeException: n11"))
        // Rotation: a log past 200 KB moves aside and a fresh one starts.
        File(dir, "aero.log").appendText("x".repeat(210 * 1024))
        Diagnostics.log("app", "after rotation")
        assertTrue(File(dir, "aero-1.log").exists()); assertTrue(File(dir, "aero.log").readText().contains("after rotation"))
        val zip = Diagnostics.export("summary line", File(File(dir, "out"), "diag.zip"))
        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            assertTrue(names.contains("summary.txt")); assertTrue(names.contains("aero.log")); assertTrue(names.contains("aero-1.log"))
            assertEquals(10, names.count { it.startsWith("crash-") })
            assertEquals("summary line", z.getInputStream(z.getEntry("summary.txt")).readBytes().toString(Charsets.UTF_8))
        }
        Diagnostics.clearCrashes()
        assertTrue(Diagnostics.crashFiles().isEmpty())
        dir.deleteRecursively()
    }
}
