package com.therealityteam.aeropages.platform

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crash reports and a rolling session log, kept in the app's private storage, plus a one-click
 * diagnostics bundle (zip) the user can export from the launcher and send along with a bug report.
 * Nothing leaves the device unless the user exports it.
 */
object Diagnostics {
    private const val MAX_LOG_BYTES = 200 * 1024L
    private const val MAX_CRASHES = 10
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    /** Reports written in the same millisecond still get distinct names (the old nanoTime % 1000 suffix could collide). */
    private val crashSeq = java.util.concurrent.atomic.AtomicInteger()

    /** Where reports live; set by [install] (tests point it at a temp folder). */
    @Volatile var dir: File = File(System.getProperty("java.io.tmpdir") ?: ".", "aerodesk-diagnostics")

    /** Hook the process: uncaught exceptions are written to a report before the system handler runs. */
    fun install(context: Context, appInfo: String) {
        dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { recordCrash(thread.name, error, appInfo) }
            previous?.uncaughtException(thread, error)
        }
        log("app", "start $appInfo")
    }

    /** Write a crash report (header + full stack trace); only the newest [MAX_CRASHES] are kept. */
    @Synchronized
    fun recordCrash(threadName: String, error: Throwable, appInfo: String = ""): File {
        dir.mkdirs()
        val file = File(dir, "crash-${fileStamp.format(Date())}-${String.format(java.util.Locale.ROOT, "%03d", crashSeq.incrementAndGet() % 1000)}.txt")
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        file.writeText("Reality Office crash ${stamp.format(Date())}\n$appInfo\nthread: $threadName\n\n$sw")
        // Also to logcat, so a crash seen through adb / instrumentation carries its stack trace
        // (android.util.Log is a stub in JVM unit tests, hence the guard).
        runCatching { android.util.Log.e("RealityOffice", "Uncaught exception on thread $threadName", error) }
        crashFiles().drop(MAX_CRASHES).forEach { it.delete() }
        log("crash", "${error.javaClass.simpleName}: ${error.message}")
        return file
    }

    /** Append one line to the session log (an error the UI only shows as a status line, an export failure…); rotates at 200 KB. */
    @Synchronized
    fun log(tag: String, message: String, error: Throwable? = null) {
        runCatching {
            dir.mkdirs()
            val f = File(dir, "aero.log")
            if (f.length() > MAX_LOG_BYTES) { File(dir, "aero-1.log").delete(); f.renameTo(File(dir, "aero-1.log")) }
            val detail = error?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            f.appendText("${stamp.format(Date())} [$tag] $message$detail\n")
        }
    }

    /** Crash reports, newest first. */
    fun crashFiles(): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.name.startsWith("crash-") }.sortedByDescending { it.name }

    fun clearCrashes() { crashFiles().forEach { it.delete() } }

    /** Bundle the crash reports, the logs and [summary] (app / device facts) into the zip at [target]. */
    fun export(summary: String, target: File): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("summary.txt")); zip.write(summary.toByteArray()); zip.closeEntry()
            val files = crashFiles() + listOf(File(dir, "aero.log"), File(dir, "aero-1.log")).filter { it.exists() }
            files.forEach { f -> zip.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
        }
        log("diagnostics", "exported ${target.name}")
        return target
    }
}
