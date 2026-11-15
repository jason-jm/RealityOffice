package com.therealityteam.aeropages

import android.content.Intent
import android.content.ContextWrapper
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.format.UnsupportedContent
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import com.therealityteam.aeropages.ui.AeroDesk
import com.therealityteam.aeropages.ui.OpenRequests
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Saving over a file that holds what AeroDesk cannot keep (here a workbook with a PivotTable part):
 * the banner says so, Save asks, Save a Copy leaves the original byte for byte as it was and the
 * document becomes the copy, and Replace Original writes over it as asked.
 *
 *   ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.therealityteam.aeropages.KeepOriginalTest
 */
@RunWith(AndroidJUnit4::class)
class KeepOriginalTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var workDir: File
    private val navigator = RecordingNavigator()
    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    @Before
    fun setUp() {
        TestSupport.installCrashLogger()
        TestSupport.clearDevProps()
        workDir = Files.createTempDirectory((context.getExternalFilesDir(null) ?: context.filesDir).toPath(), ".file-test-").toFile()
        val isolated = object : ContextWrapper(rule.activity) {
            override fun getExternalFilesDir(type: String?) = workDir
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(workDir.name, mode)
        }
        rule.setContent {
            // The main window: it takes the open requests a file manager's VIEW intent posts.
            PicoTheme { CompositionLocalProvider(LocalContext provides isolated, LocalSpatialNavigator provides navigator) { AeroDesk(external = OpenRequests.MAIN) } }
        }
    }

    @After
    fun tearDown() {
        workDir.deleteRecursively()
        context.deleteSharedPreferences(workDir.name)
    }

    @Test
    fun aFifthDocumentLeavesTheOccupiedEditorAndItsUnsavedChangesIntact() = guarded("full-windows") {
        val original = workbookWithPivotPart("original")
        openAndEdit(original)
        try {
            rule.runOnIdle { OpenRequests.SIDE_WINDOWS.forEach { OpenRequests.setBusy(it, true) } }
            OpenRequests.submit(Intent(Intent.ACTION_VIEW, Uri.fromFile(File(workDir, "fifth.xlsx"))))
            waitFor(hasText(str(R.string.no_free_document_window)))
            pill(R.string.btn_ok)
            assertTrue(navigator.opened.isEmpty())
            fileSave()
            pill(R.string.btn_save_copy)
            val copy = File(workDir, str(R.string.copy_file_name, "original") + ".xlsx")
            rule.waitUntil(TIMEOUT) { copy.isFile && copy.length() > 0 }
            assertEquals("=1+1", XlsxReader.read(copy.readBytes()).sheets[0].cell(1, 0)?.text)
        } finally {
            rule.runOnIdle { OpenRequests.SIDE_WINDOWS.forEach { OpenRequests.setBusy(it, false) } }
        }
    }

    @Test
    fun editingTheRelocatedWorkbookKeepsAll48OriginalCells() = guarded("relocated") {
        val original = File(workDir, "Budget.xlsx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("alternate-workbook.xlsx").use { input ->
            original.outputStream().use { input.copyTo(it) }
        }
        val cells = XlsxReader.read(original.readBytes()).sheets.single().cells.mapValues { it.value.text }
        assertEquals(48, cells.size)
        OpenRequests.submit(Intent(Intent.ACTION_VIEW, Uri.fromFile(original)))
        waitFor(hasText("Monthly income"))
        goTo("A22")
        formulaBar().performTextInput("New entry")
        rule.waitForIdle()
        fileSave()
        rule.waitUntil(TIMEOUT) { XlsxReader.read(original.readBytes()).sheets.single().cell(21, 0)?.text == "New entry" }
        val saved = XlsxReader.read(original.readBytes()).sheets.single().cells
        assertEquals(49, saved.size)
        for ((key, text) in cells) assertEquals(text, saved[key]?.text)
    }

    @Test
    fun saveACopyLeavesTheOriginalAsItWas() = guarded("keep-copy") {
        val original = workbookWithPivotPart("$PREFIX-Keep")
        val before = original.readBytes()
        openAndEdit(original)
        fileSave()
        pill(R.string.btn_save_copy)
        val copy = File(workDir, str(R.string.copy_file_name, "$PREFIX-Keep") + ".xlsx")
        rule.waitUntil(TIMEOUT) { copy.exists() && copy.length() > 0 }
        assertArrayEquals("the original is untouched", before, original.readBytes())
        assertEquals("=1+1", XlsxReader.read(copy.readBytes()).sheets[0].cell(1, 0)?.text)
        assertTrue("the copy is AeroDesk's own", UnsupportedContent.scan(copy.path).isEmpty())
        // The document is the copy now: the banner has gone, and Save writes it straight away.
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(hasText(str(R.string.unsupported_pivot), substring = true)).fetchSemanticsNodes().isEmpty() }
        goTo("A3")
        formulaBar().performTextInput("again")
        rule.waitForIdle()
        fileSave()
        rule.waitUntil(TIMEOUT) { XlsxReader.read(copy.readBytes()).sheets[0].cell(2, 0)?.text == "again" }
        assertArrayEquals("still untouched", before, original.readBytes())
    }

    @Test
    fun replaceOriginalWritesOverIt() = guarded("keep-replace") {
        val original = workbookWithPivotPart("$PREFIX-Replace")
        openAndEdit(original)
        fileSave()
        pill(R.string.btn_replace_original)
        rule.waitUntil(TIMEOUT) { UnsupportedContent.scan(original.path).isEmpty() }
        assertEquals("=1+1", XlsxReader.read(original.readBytes()).sheets[0].cell(1, 0)?.text)
        assertTrue("no copy was made", workDir.listFiles()!!.none { it.name.startsWith(str(R.string.copy_file_name, "$PREFIX-Replace")) })
    }

    @Test
    fun aCsvHoldingMoreThanCsvCanIsSavedAsAWorkbook() = guarded("keep-csv") {
        val csv = File(workDir, "$PREFIX-Data.csv").also { it.writeText("Region,Sales\nNorth,120\n") }
        val before = csv.readBytes()
        OpenRequests.submit(Intent(Intent.ACTION_VIEW, Uri.fromFile(csv)))
        waitFor(hasText("North"))
        // A second sheet: more than a CSV holds.
        val newSheet = hasContentDescription(str(R.string.new_sheet)) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(newSheet).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(newSheet).onFirst().performClick()
        rule.waitForIdle()
        fileSave()
        waitFor(hasText(str(R.string.title_lossy_format)))
        pill(R.string.btn_save_better, ".xlsx")
        val book = File(workDir, "$PREFIX-Data.xlsx")
        rule.waitUntil(TIMEOUT) { book.exists() && book.length() > 0 }
        assertEquals(2, XlsxReader.read(book.readBytes()).sheets.size)
        assertArrayEquals("the CSV is untouched", before, csv.readBytes())
    }

    @Test
    fun aDamagedFileIsKeptAndItsChangesGoToACopy() = guarded("keep-damaged") {
        val damaged = damagedWorkbook("$PREFIX-Damaged")
        val before = damaged.readBytes()
        OpenRequests.submit(Intent(Intent.ACTION_VIEW, Uri.fromFile(damaged)))
        waitFor(hasText("kept"))
        waitFor(hasText(str(R.string.banner_repaired), substring = true))
        fileSave()
        pill(R.string.btn_save_copy)
        val copy = File(workDir, str(R.string.copy_file_name, "$PREFIX-Damaged") + ".xlsx")
        rule.waitUntil(TIMEOUT) { copy.exists() && copy.length() > 0 }
        assertArrayEquals("the damaged original is left as it was", before, damaged.readBytes())
    }

    // --- helpers ----------------------------------------------------------------------------

    /** On failure, leave the semantics trees in Download (the app's folder goes with the test APKs) and in the log. */
    private fun guarded(name: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            runCatching {
                val roots = rule.onAllNodes(androidx.compose.ui.test.isRoot(), useUnmergedTree = true)
                val n = roots.fetchSemanticsNodes().size
                val text = (0 until n).joinToString("\n") { i -> "-- root $i\n" + roots[i].printToString(Int.MAX_VALUE) }
                File("/sdcard/Download", "aerodesk-ui-dump-$name.txt").writeText(text)
                text.lines().forEach { android.util.Log.i("KeepOriginalDump", "[$name] $it") }
            }
            throw t
        }
    }

    /** A workbook AeroDesk reads fine (A1 "kept") that also carries a PivotTable part it would not write back. */
    private fun workbookWithPivotPart(name: String): File {
        val wb = Workbook(mutableListOf(Sheet(name = "Data").also { it.ensure(0, 0).text = "kept" }))
        val plain = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(plain)).use { zin ->
                generateSequence { zin.nextEntry }.forEach { e -> z.putNextEntry(ZipEntry(e.name)); z.write(zin.readBytes()); z.closeEntry() }
            }
            z.putNextEntry(ZipEntry("xl/pivotTables/pivotTable1.xml")); z.write("<pivotTableDefinition/>".toByteArray()); z.closeEntry()
        }
        return File(workDir, "$name.xlsx").also { it.writeBytes(out.toByteArray()) }
    }

    /** A workbook whose sheet XML was cut off before its end, as an interrupted copy leaves it; A1 "kept" still reads. */
    private fun damagedWorkbook(name: String): File {
        val wb = Workbook(mutableListOf(Sheet(name = "Data").also { it.ensure(0, 0).text = "kept"; it.ensure(5, 0).text = "lost" }))
        val plain = ByteArrayOutputStream().also { XlsxWriter.write(wb, it) }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            ZipInputStream(ByteArrayInputStream(plain)).use { zin ->
                generateSequence { zin.nextEntry }.forEach { e ->
                    var bytes = zin.readBytes()
                    if (e.name == "xl/worksheets/sheet1.xml") bytes = bytes.toString(Charsets.UTF_8).let { it.substring(0, it.indexOf("<row r=\"6\"")) }.toByteArray()
                    z.putNextEntry(ZipEntry(e.name)); z.write(bytes); z.closeEntry()
                }
            }
        }
        return File(workDir, "$name.xlsx").also { it.writeBytes(out.toByteArray()) }
    }

    /** Open [file] as a file manager would hand it over, wait for the banner, and put a formula in A2. */
    private fun openAndEdit(file: File) {
        OpenRequests.submit(Intent(Intent.ACTION_VIEW, Uri.fromFile(file)))
        waitFor(hasText("kept"))
        waitFor(hasText(str(R.string.unsupported_pivot), substring = true))
        goTo("A2")
        formulaBar().performTextInput("=1+1")
        rule.waitForIdle()
    }

    /** Go to [ref] through the Name Box, as typing it there and pressing Enter does. */
    private fun goTo(ref: String) {
        val nameBox = hasSetTextAction() and hasContentDescription(str(R.string.name_box))
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(nameBox, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(nameBox, useUnmergedTree = true).performTextReplacement(ref)
        rule.onNode(nameBox, useUnmergedTree = true).performImeAction()
        rule.waitForIdle()
    }

    /** File ▸ Save. */
    private fun fileSave() {
        rule.onAllNodes(hasText(str(R.string.menu_file)) and hasClickAction()).onFirst().performClick()
        val save = hasText(str(R.string.save)) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(save).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(save).onFirst().performClick()
        rule.waitForIdle()
    }

    private fun pill(id: Int, vararg args: Any) {
        val m = hasText(str(id, *args)) and hasClickAction()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().performClick()
        rule.waitForIdle()
    }

    private fun formulaBar() = rule.onNode(hasSetTextAction() and hasAnySibling(hasText("fx")), useUnmergedTree = true)

    private fun waitFor(matcher: SemanticsMatcher) {
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object {
        const val PREFIX = "UiTest"
        const val TIMEOUT = 20_000L
    }
}
