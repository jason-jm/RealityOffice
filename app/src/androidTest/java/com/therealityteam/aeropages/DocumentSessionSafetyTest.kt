package com.therealityteam.aeropages

import android.content.Context
import android.content.ClipData
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import com.therealityteam.aeropages.ui.RecentFiles
import com.therealityteam.aeropages.ui.SaveRequests
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.DeskKind
import com.therealityteam.aeropages.format.DocumentRepository
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.ui.DocumentSession
import com.therealityteam.aeropages.ui.OpenRequests
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the editor, shared session and real DOCX I/O on the device's document filesystem. */
@RunWith(AndroidJUnit4::class)
class DocumentSessionSafetyTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resumeWrite = CountDownLatch(1)

    @Before fun setUp() {
        dir = Files.createTempDirectory((app.getExternalFilesDir(null) ?: app.filesDir).toPath(), ".save-test-").toFile()
        context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(dir.name, mode)
        }
    }

    @After fun tearDown() {
        resumeWrite.countDown()
        runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        app.deleteSharedPreferences(dir.name)
        dir.deleteRecursively()
    }

    private class Pages : DocumentSession.Adapter<Document> {
        val editor = DocumentEditor()
        val writes = AtomicInteger()
        var beforeWrite: (Int, OutputStream) -> Unit = { _, _ -> }
        override val dirty get() = editor.dirty
        override val editVersion get() = editor.saveState.version
        override val sourcePath get() = editor.document.sourcePath
        override fun live() = editor.document
        override fun snapshot() = editor.document.copyOf()
        override fun load(model: Document, recovered: Boolean) = editor.load(model, recovered)
        override fun read(path: String) = DocumentRepository.open(path)
        override fun write(model: Document, out: OutputStream, ext: String) {
            beforeWrite(writes.incrementAndGet(), out)
            DocxWriter.write(model, out)
        }
        override fun detach(model: Document) { model.sourcePath = null }
        override fun markSaved(path: String, clean: Boolean) = editor.markSaved(path, clean)
        override fun markSaved() = editor.markSaved()
    }

    private fun session(a: Pages, window: String = OpenRequests.MAIN) = DocumentSession(
        context, scope, dir, DeskKind.PAGES, "docx", listOf("docx"), ".draft-pages.docx", "Document", a, window,
    )
    private suspend fun <T> ui(f: () -> T): T = withContext(Dispatchers.Main.immediate) { f() }
    private suspend fun until(test: () -> Boolean) = withTimeout(15_000) { while (!ui(test)) delay(10) }
    private fun text(doc: Document) = (doc.blocks.first() as ParagraphBlock).paragraph.plainText()
    private fun text(file: File) = text(DocumentRepository.open(file.path))

    @Test fun removingAndReopeningThroughAnotherPathPreservesRecentsAndPins() {
        val file = File(dir, "alias.docx").also { it.writeText("fixture") }
        val alias = File(dir, "./alias.docx").path
        RecentFiles.add(context, file.path)
        RecentFiles.setPinned(context, file.path, true)
        RecentFiles.remove(context, alias)
        assertTrue(RecentFiles.forHome(context, dir).isEmpty())
        assertTrue(RecentFiles.load(context).isEmpty())
        RecentFiles.add(context, file.path)
        assertEquals(file.path, RecentFiles.forHome(context, dir).single().path)
        assertTrue(RecentFiles.isPinned(RecentFiles.pinned(context), alias))
    }

    @Test fun androidResolvesViewAndShareForDocumentAttachments() {
        val uri = Uri.parse("content://example.documents/document/42")
        for (mime in listOf("application/vnd.oasis.opendocument.spreadsheet", "application/vnd.oasis.opendocument.presentation-template",
            "text/markdown", "text/rtf", "application/wps-office.ett")) {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            for (intent in listOf(view, send)) {
                intent.setPackage(context.packageName)
                val activity = context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
                assertEquals("Android should offer Reality Office for ${intent.action} $mime",
                    "com.therealityteam.aeropages.platform.LaunchActivity", activity?.name)
            }
        }
    }

    @Test fun aShareWithOnlyClipDataOpensTheAttachedDocument() {
        val uri = Uri.fromFile(File(dir, "shared.ods"))
        val share = Intent(Intent.ACTION_SEND).setType("application/vnd.oasis.opendocument.spreadsheet").apply {
            clipData = ClipData.newRawUri("shared workbook", uri)
        }
        try {
            OpenRequests.submit(share)
            val request = OpenRequests.pending.value.single { it.intent === share }
            assertEquals(uri, OpenRequests.documentUri(request.intent))
        } finally { OpenRequests.pending.value.filter { it.intent === share }.forEach(OpenRequests::consume) }
    }

    @Test fun sharingTextWithoutAnAttachmentCreatesDistinctReadableDocuments() = runBlocking {
        val text = "会议纪要 • résumé"
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val html = Intent(Intent.ACTION_SEND).setType("text/html")
            .putExtra(Intent.EXTRA_TEXT, "meeting").putExtra(Intent.EXTRA_HTML_TEXT, "<b>meeting</b>")
        try {
            OpenRequests.submit(share)
            assertTrue(OpenRequests.pending.value.any { it.intent === share })
            val first = File(checkNotNull(OpenRequests.resolvePath(context, dir, share)))
            val other = File(checkNotNull(OpenRequests.resolvePath(context, dir, share)))
            assertNotEquals(first, other)
            assertEquals("txt", first.extension)
            assertEquals(text, first.readText())
            assertEquals(text, other.readText())
            val formatted = File(checkNotNull(OpenRequests.resolvePath(context, dir, html)))
            assertEquals("html", formatted.extension)
            assertEquals("<b>meeting</b>", formatted.readText())
        } finally { OpenRequests.pending.value.filter { it.intent === share }.forEach(OpenRequests::consume) }
    }

    @Test fun multipleFileRequestsReserveDistinctWindowsAndKeepTheirOrder() {
        val intents = (1..4).map { Intent(Intent.ACTION_VIEW, Uri.fromFile(File(dir, "$it.ods"))) }
        try {
            intents.forEach(OpenRequests::submit)
            val requests = OpenRequests.pending.value.filter { it.intent in intents }
            assertEquals(intents, requests.map { it.intent })
            val assigned = requests.take(3).map { OpenRequests.redirectToSide(it)!!.target }
            assertEquals(OpenRequests.SIDE_WINDOWS, assigned)
            assertNull(OpenRequests.redirectToSide(requests.last()))
            assertEquals(assigned, OpenRequests.pending.value.filter { it.intent in intents }.map { it.target })
        } finally { OpenRequests.pending.value.filter { it.intent in intents }.forEach(OpenRequests::consume) }
    }

    @Test fun systemPickerCopiesGoToTheRightEditorEvenWhenResultsArriveTogether() = runBlocking {
        val a = Pages()
        val b = Pages()
        val destinations = (1..3).map { File(dir, "copy$it.docx") }
        ui {
            val first = session(a); val second = session(b, "aux")
            a.editor.insertAtCaret("first document"); b.editor.insertAtCaret("second document")
            SaveRequests.post(first.id, Uri.fromFile(destinations[0]))
            SaveRequests.post(second.id, Uri.fromFile(destinations[1]))
            SaveRequests.post(first.id, Uri.fromFile(destinations[2]))
        }
        until { destinations.all { it.isFile && it.length() > 0 } }
        assertEquals(listOf("first document", "second document", "first document"), destinations.map { text(it) })
    }

    @Test fun failedSystemCopySerializationPreservesTheExistingDestination() = runBlocking {
        val a = Pages()
        val s = ui { session(a) }
        val target = File(dir, "existing.docx").apply { writeText("original") }
        a.beforeWrite = { _, output -> output.write(42); throw IOException("copy interrupted") }
        ui { s.saveCopyTo(Uri.fromFile(target)) }
        until { s.status.contains("copy interrupted") }
        assertEquals("original", target.readText())
    }

    @Test fun aFailedExportLeavesTheExistingPdfIntact() = runBlocking {
        val a = Pages()
        val s = ui { session(a) }
        val pdf = File(dir, "Document.pdf").apply { writeText("previous complete export") }
        ui {
            s.export("pdf", "PDF") { _, out ->
                out.write(1)
                throw IOException("interrupted export")
            }
        }
        until { s.status.contains("interrupted export") }
        assertEquals("previous complete export", pdf.readText())
        assertFalse(dir.listFiles().orEmpty().any { it.extension == "tmp" })
        ui { s.export("pdf", "PDF") { _, out -> out.write("new complete export".toByteArray()) } }
        until { pdf.readText() == "new complete export" }
    }

    @Test fun saveAsAfterAConflictCompletesThePendingClose() = runBlocking {
        val a = Pages()
        val s = ui { session(a) }
        val original = File(dir, "shared.docx")
        val copy = File(dir, "my-changes.docx")
        original.outputStream().use { DocxWriter.write(a.snapshot(), it) }
        withContext(Dispatchers.Main) { s.openNow(original.path) }
        val external = DocumentEditor().apply { insertAtCaret("someone else's edit") }
        original.outputStream().use { DocxWriter.write(external.document, it) }
        var closed = false
        ui { a.editor.insertAtCaret("my edit"); s.guardDirty { closed = true }; s.saveAndRun() }
        until { s.conflictPrompt != null }
        ui { s.saveElsewhere(); s.saveAs(copy.path) }
        until { !a.dirty }
        assertTrue("the requested close should resume after Save As", closed)
        assertEquals("my edit", text(copy))
        assertEquals("someone else's edit", text(original))
    }

    private fun holdFirstWrite(a: Pages): CompletableDeferred<Unit> {
        val started = CompletableDeferred<Unit>()
        a.beforeWrite = { n, _ ->
            if (n == 1) {
                started.complete(Unit)
                check(resumeWrite.await(15, TimeUnit.SECONDS))
            }
        }
        return started
    }

    @Test fun overlappingSavesKeepTheNewestCompleteFile() = overlap(automaticFirst = false)
    @Test fun aManualSaveDuringAutosaveKeepsTheNewestCompleteFile() = overlap(automaticFirst = true)

    private fun overlap(automaticFirst: Boolean) = runBlocking {
        val a = Pages()
        val s = ui { session(a) }
        val target = File(dir, "overlap.docx")
        a.editor.insertAtCaret("original")
        target.outputStream().use { DocxWriter.write(a.snapshot(), it) }
        withContext(Dispatchers.Main) { s.openNow(target.path) }
        val started = holdFirstWrite(a)
        ui {
            a.editor.insertAtCaret("1")
            if (automaticFirst) scope.launch { s.autoSaveIfNeeded() } else s.save()
        }
        withTimeout(10_000) { started.await() }
        ui { a.editor.insertAtCaret("2"); s.save() }
        assertEquals("original", text(target))
        assertEquals(1, a.writes.get())
        resumeWrite.countDown()
        until { a.writes.get() == 2 && !a.dirty }
        assertEquals("12original", text(target))
        assertNull(s.conflictPrompt)
    }

    @Test fun anEditDuringSaveAsKeepsTheNewPathDirtyAndGuardsThePendingClose() = runBlocking {
        val a = Pages()
        val s = ui { session(a) }
        val target = File(dir, "new.docx")
        var left = false
        val started = holdFirstWrite(a)
        ui {
            a.editor.insertAtCaret("ONE")
            s.guardDirty { left = true }; s.saveAndRun(); s.saveAs(target.path)
        }
        withTimeout(10_000) { started.await() }
        ui {
            a.editor.insertAtCaret("-TWO")
            a.editor.setProps(a.live().props.copy(title = "edited during save"))
        }
        resumeWrite.countDown()
        until { s.unsavedPrompt != null }
        assertFalse(left)
        assertTrue(a.dirty)
        assertEquals(target.path, a.sourcePath)
        assertEquals("ONE", text(target))
        assertEquals("edited during save", a.live().props.title)
        ui { s.saveAndRun() }
        until { left }
        assertFalse(a.dirty)
        val saved = a.read(target.path)
        assertEquals("ONE-TWO", text(saved))
        assertEquals("edited during save", saved.props.title)
        assertEquals(2, saved.props.revision)
    }

    @Test fun autosaveKeepsNewEditsDirtyAndAWriteFailurePreservesTheOriginal() = runBlocking {
        val a = Pages()
        val s = ui { session(a) }
        val target = File(dir, "auto.docx")
        target.outputStream().use { DocxWriter.write(a.snapshot(), it) }
        withContext(Dispatchers.Main) { s.openNow(target.path) }
        val started = holdFirstWrite(a)
        ui { a.editor.insertAtCaret("A"); scope.launch { s.autoSaveIfNeeded() } }
        withTimeout(10_000) { started.await() }
        ui { a.editor.insertAtCaret("B") }
        resumeWrite.countDown()
        until { a.live().props.revision == 1 }
        assertEquals("A", text(target))
        assertTrue(a.dirty)
        val before = target.readBytes()
        a.beforeWrite = { _, out -> out.write(1); throw IOException("injected write failure") }
        withContext(Dispatchers.Main) { s.autoSaveIfNeeded() }
        assertArrayEquals(before, target.readBytes())
        assertTrue(a.dirty)
        assertFalse(dir.listFiles().orEmpty().any { it.extension == "tmp" })
        a.beforeWrite = { _, _ -> }
        withContext(Dispatchers.Main) { s.autoSaveIfNeeded() }
        assertEquals("AB", text(target))
        assertFalse(a.dirty)
    }

    @Test fun windowsRecoverTheirOwnDraftsIncludingTheLegacyMainDraft() = runBlocking {
        val main = Pages()
        val side = Pages()
        val first = ui { session(main) }
        val second = ui { session(side, "aux") }
        val legacy = File(dir, ".draft-pages.docx")
        val oldDocument = DocumentEditor().apply { insertAtCaret("legacy") }.document
        legacy.outputStream().use { DocxWriter.write(oldDocument, it) }
        ui { first.checkDraft(); second.checkDraft() }
        assertTrue(first.showDraftPrompt)
        assertFalse(second.showDraftPrompt)
        ui { first.recoverDraft(); side.editor.insertAtCaret("side") }
        until { main.dirty && first.sourcePath == null && text(main.live()) == "legacy" && !first.showDraftPrompt }
        withContext(Dispatchers.Main) { second.checkpointDraft() }
        assertEquals("legacy", text(legacy))
        val restarted = Pages()
        val resumed = ui { session(restarted, "aux").also { it.checkDraft() } }
        assertTrue(resumed.showDraftPrompt)
        ui { resumed.recoverDraft() }
        until { restarted.dirty && text(restarted.live()) == "side" }
        ui { first.discardDraft() }
        assertFalse(legacy.exists())
        assertEquals("side", text(resumed.draftFile))
        val target = File(dir, "restored.docx")
        ui { resumed.saveAs(target.path) }
        until { !restarted.dirty }
        assertEquals("side", text(target))
        assertFalse(resumed.draftFile.exists())
    }

    @Test fun twoWindowsSavingTheSamePathResolveAConflictBeforeReplacingIt() = runBlocking {
        val a = Pages()
        val b = Pages()
        val first = ui { session(a) }
        val second = ui { session(b, "aux") }
        val file = File(dir, "shared.docx")
        file.outputStream().use { DocxWriter.write(a.snapshot(), it) }
        withContext(Dispatchers.Main) { first.openNow(file.path); second.openNow(file.path) }
        val started = holdFirstWrite(a)
        ui { a.editor.insertAtCaret("A"); first.save() }
        withTimeout(10_000) { started.await() }
        ui { b.editor.insertAtCaret("B"); second.save() }
        resumeWrite.countDown()
        until { second.conflictPrompt != null }
        assertEquals("A", text(file))
        assertTrue(b.dirty)
        ui { second.overwriteConflict() }
        until { !b.dirty }
        assertEquals("B", text(file))
    }
}
