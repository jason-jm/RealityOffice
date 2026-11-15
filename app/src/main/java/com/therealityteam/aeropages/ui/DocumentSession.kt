package com.therealityteam.aeropages.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.therealityteam.aeropages.format.DeskFiles
import com.therealityteam.aeropages.format.DeskKind
import com.therealityteam.aeropages.format.FormatLosses
import com.therealityteam.aeropages.format.UnsupportedContent
import com.therealityteam.aeropages.model.DocProps
import com.therealityteam.aeropages.model.HasProps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R

/**
 * Shared document lifecycle for the Pages / Sheets / Slides modules: snapshot-safe save,
 * autosave and draft checkpoints, the unsaved-changes guard, recent files, storage-access
 * state and the status line.
 *
 * Every serialisation runs on a deep copy taken on the main thread before switching to IO,
 * so a background write never observes a model the user is still editing (iterating the
 * live document while it was mutated used to throw or produce a half-updated file), and
 * files are written to a temporary sibling first so a crash mid-write cannot truncate them.
 */
class DocumentSession<T>(
    private val context: Context,
    private val scope: CoroutineScope,
    val workDir: File,
    private val kind: DeskKind,
    /** Extension the module writes ("docx" / "xlsx" / "pptx"). */
    val extension: String,
    /** Every file type this module can write; the first is [extension]. */
    val writableExtensions: List<String> = listOf(extension),
    draftName: String,
    /** Base name offered for a never-saved blank document. */
    private val defaultName: String,
    private val adapter: Adapter<T>,
    windowId: String,
) {
    /** How the session talks to a module's editor and model. */
    interface Adapter<T> {
        val dirty: Boolean
        val editVersion: Long
        val sourcePath: String?
        /** Deep copy for background serialisation; called on the main thread. */
        fun snapshot(): T
        /** Serialise a snapshot in the format [ext] names; runs on an IO thread. */
        fun write(model: T, out: OutputStream, ext: String)
        /** Parse a file; runs on an IO thread. The result carries its own source path. */
        fun read(path: String): T
        fun load(model: T, recovered: Boolean = false)
        /** Forget the file a recovered draft was read from. */
        fun detach(model: T)
        /** Adopt the path even if newer edits still need saving. */
        fun markSaved(path: String, clean: Boolean)
        fun markSaved()
        /** What saving the document as it stands in the plain type [ext] (CSV, text) would leave out; empty when [ext] keeps it all. */
        fun losses(ext: String): Set<FormatLosses.Loss> = emptySet()
        fun live(): T
    }

    /**
     * A save's properties stamped on [snap]: made now if never saved (by the user, its author, when it has none), saved
     * now by the user (the name they review under), one save more; null when the module keeps no properties.
     */
    private fun stamp(snap: T): DocProps? {
        val live = adapter.live() as? HasProps ?: return null
        val user = loadReviewer(context).takeIf { it.isNotBlank() }
        val base = live.props.let { if (it.created == null && it.author.isBlank() && user != null) it.copy(author = user) else it }
        val p = base.saved(System.currentTimeMillis(), user)
        (snap as HasProps).props = p
        return p
    }

    // These container IDs survive a process restart. The main window also recovers pre-multiwindow drafts.
    val draftFile = File(workDir, if (windowId == OpenRequests.MAIN) draftName
        else "${draftName.substringBeforeLast('.')}-$windowId.${draftName.substringAfterLast('.')}")
    private var draftOwner: T? = null
    private var draftGeneration = 0L

    /** Identifies this session to the system-picker flows ([SaveRequests]). */
    val id: String = java.util.UUID.randomUUID().toString()
    var status by mutableStateOf("")
    var showSave by mutableStateOf(false)
    var showOpen by mutableStateOf(false)
    var showFileMenu by mutableStateOf(false)
    var showDraftPrompt by mutableStateOf(false)
    var autoSave by mutableStateOf(true)
    var storageAccess by mutableStateOf(checkStorageAccess(context))
        private set
    var recents by mutableStateOf(loadRecents(context).filter { DeskFiles.kindOfOrNull(File(it).name) == kind })
        private set
    /** Pending destructive action held behind the 保存/不保存/取消 prompt. */
    var unsavedPrompt by mutableStateOf<(() -> Unit)?>(null)
        private set
    private var afterSave: (() -> Unit)? = null
    /**
     * The save that was blocked because the source file changed on disk since we read or
     * wrote it (another app edited it); resolved by [overwriteConflict] / [saveElsewhere] / [cancelConflict].
     */
    class SaveConflict(val path: String, val afterSave: (() -> Unit)?, val overwrite: () -> Unit)
    var conflictPrompt by mutableStateOf<SaveConflict?>(null)
        private set
    private data class FileStamp(val modified: FileTime, val size: Long, val key: Any?)
    private fun fileStamp(path: String): FileStamp? = try {
        val a = Files.readAttributes(File(path).toPath(), BasicFileAttributes::class.java)
        FileStamp(a.lastModifiedTime(), a.size(), a.fileKey())
    } catch (_: IOException) { null }
    catch (_: SecurityException) { null }
    private var sourceStamp: FileStamp? = null
    private var autosavePaused = false

    /**
     * What the open file holds that saving would drop (macros, embedded objects, PivotTables …),
     * found just after it opens. While there is any, Save offers a copy and autosave leaves the file alone.
     */
    var unsupported by mutableStateOf<Set<UnsupportedContent.Kind>>(emptySet())
        private set
    /** A save held because it would write over a file holding [unsupported] content: its path and what runs after it. */
    var keepPrompt by mutableStateOf<Pair<String, (() -> Unit)?>?>(null)
        private set
    /** The banner about the file's content and where saving goes, closed for this file. */
    var bannerClosed by mutableStateOf(false)
    /** The open file was damaged: parts of it could be read only by salvaging malformed XML, so some of it may be missing. */
    var repaired by mutableStateOf(false)
        private set
    /** A save into a plain type that would drop part of the document (CSV, text), held for Save as / Keep / Cancel. */
    class LossyPrompt(val path: String, val then: (() -> Unit)?, val losses: Set<FormatLosses.Loss>)
    var lossyPrompt by mutableStateOf<LossyPrompt?>(null)
        private set
    /** Keep CSV / Keep Text was chosen for this document: it is saved in its plain type without asking again. */
    private var lossAccepted = false
    /** A file that could not be opened, said in a dialog: its name, whether a password locks it, and what the reader reported. */
    class OpenProblem(val name: String, val encrypted: Boolean, val details: String?)
    var openProblem by mutableStateOf<OpenProblem?>(null)
    private var autosaveHeld = false
    /** True when the file is in a type this module reads but does not write (.doc, .xls, .xlsm …): saving writes a new file beside it. */
    val savesBeside: Boolean get() = sourcePath?.let { File(it).extension.lowercase() !in writableExtensions } == true
    /** The name Save would write: the source's own, or the new file beside a source of another type. */
    val saveName: String? get() = sourcePath?.let { File(resolveSavePath(it, extensionFor(it))).name }
    /** The name a copy would get beside the source: "Report - Copy.xlsx", or the first free one after it. */
    fun copyName(): String? = sourcePath?.let { File(copyPathFor(it)).name }

    private fun copyPathFor(path: String): String {
        val f = File(resolveSavePath(path, extensionFor(path)))
        return freeName(File(f.parentFile, Strings.get(R.string.copy_file_name, f.nameWithoutExtension) + "." + f.extension).path)
    }

    private fun forgetContent() { unsupported = emptySet(); repaired = false; keepPrompt = null; bannerClosed = false; autosaveHeld = false }

    /** Whether saving over the source would drop something AeroDesk could not read or keep. */
    private val keepsOriginal: Boolean get() = unsupported.isNotEmpty() || repaired

    /** Base name offered for a never-saved document: the module's, or the template's it was made from. */
    var untitledName: String = defaultName

    val dirty: Boolean get() = adapter.dirty
    val sourcePath: String? get() = adapter.sourcePath
    val sourceName: String? get() = sourcePath?.let { File(it).name }
    val suggestedName: String get() = sourcePath?.let { File(it).nameWithoutExtension } ?: untitledName

    fun requestStorage() = requestAllFilesAccess(context)
    fun refreshStorage() { storageAccess = checkStorageAccess(context) }
    fun browseStart(): File = sourcePath?.let { File(it).parentFile } ?: browseStartDir(workDir, storageAccess)
    fun shortcuts(): List<Shortcut> = storageShortcuts(workDir)

    // --- unsaved-changes guard -------------------------------------------------

    fun guardDirty(action: () -> Unit) { if (adapter.dirty) unsavedPrompt = action else action() }
    fun cancelPrompt() { unsavedPrompt = null }
    fun discardAndRun() {
        val a = unsavedPrompt ?: return
        unsavedPrompt = null
        dropOwnedDraft()
        adapter.markSaved()
        a()
    }
    fun saveAndRun() {
        val a = unsavedPrompt ?: return
        unsavedPrompt = null
        val p = adapter.sourcePath
        if (p != null) saveGuarded(resolveSavePath(p, extensionFor(p)), then = a) else { afterSave = a; showSave = true }
    }

    init {
        // "Save a copy to…": the system picker's target for this session arrives here.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val destinations = SaveRequests.attach(id)
            try {
                for (uri in destinations) saveCopyTo(uri)
            } finally { SaveRequests.detach(id) }
        }
    }

    /** The name offered to the system picker: the current file's, else "<untitled>.<ext>". */
    val suggestedExtension: String get() = sourcePath?.let(::extensionFor) ?: extension
    fun suggestedFileName(): String = "$suggestedName.$suggestedExtension"

    /** Write a copy of the document to a content URI (cloud / network / another app's storage); the session keeps its own file. */
    fun saveCopyTo(uri: android.net.Uri) {
        val snap = adapter.snapshot()
        val fallbackExtension = suggestedExtension
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                try {
                    val name = withContext(Dispatchers.IO) {
                        val display = displayNameOf(uri)
                        val ext = File(display).extension.lowercase().takeIf { it in writableExtensions } ?: fallbackExtension
                        // Finish serialising before asking the provider to truncate an existing document.
                        val staged = File.createTempFile("office-copy-", ".tmp", context.cacheDir)
                        try {
                            staged.outputStream().use { adapter.write(snap, it, ext) }
                            currentCoroutineContext().ensureActive()
                            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                                staged.inputStream().use { it.copyTo(output) }
                            } ?: error("cannot open the selected destination")
                        } finally { staged.delete() }
                        display
                    }
                    status = Strings.get(R.string.status_copy_saved, name)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    status = Strings.get(R.string.err_save_failed, e.message)
                    com.therealityteam.aeropages.platform.Diagnostics.log("saveCopy", uri.toString(), e)
                }
            }
        }
    }

    private fun displayNameOf(uri: android.net.Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: (uri.lastPathSegment ?: uri.toString())

    // --- save ------------------------------------------------------------------

    /** The type a path is saved as: its own when this module can write it, else the module default. */
    private fun extensionFor(path: String): String =
        File(path).extension.lowercase().takeIf { it in writableExtensions } ?: extension

    fun save() { val p = adapter.sourcePath; if (p != null) saveGuarded(resolveSavePath(p, extensionFor(p))) else showSave = true }
    fun saveAs(target: String) {
        showSave = false
        val path = resolveSavePath(target, extensionFor(target))
        if (path == sourcePath) { saveGuarded(path); return }
        if (holdsLossy(path, null)) return
        saveTo(path)
    }

    /** Ask first when [path]'s type cannot keep the whole document (unless Keep was chosen already); true when held. */
    private fun holdsLossy(path: String, then: (() -> Unit)?): Boolean {
        if (lossAccepted) return false
        val lost = adapter.losses(extensionFor(path))
        if (lost.isEmpty()) return false
        lossyPrompt = LossyPrompt(path, then, lost)
        return true
    }
    /** The type a document saved instead of a plain one gets: the module's own. */
    fun betterName(path: String): String = File(freeName(resolveSavePath(path, extension))).name
    /** Keep This Format? → Save as .xlsx / .docx: the whole document goes to a file of the module's own type beside it, which the document then is. */
    fun saveBetter() {
        val p = lossyPrompt ?: return
        lossyPrompt = null
        val target = freeName(resolveSavePath(p.path, extension))
        saveTo(target, p.then, Strings.get(R.string.status_saved_better, File(target).name, File(p.path).name))
    }
    /** Keep This Format? → Keep CSV / Text: saved as asked, and not asked again for this document. */
    fun keepFormat() {
        val p = lossyPrompt ?: return
        lossyPrompt = null
        lossAccepted = true
        saveTo(p.path, p.then)
    }
    fun cancelLossy() { lossyPrompt = null; afterSave = null }
    fun dismissSave() { showSave = false; afterSave = null }

    /** True when [path] is the file we opened/saved and something else has written it since. */
    private fun changedOnDisk(path: String): Boolean {
        return sourceStamp != null && path == adapter.sourcePath && fileStamp(path) != sourceStamp
    }

    /**
     * Save, but never silently over something: over a file holding content AeroDesk cannot keep, ask
     * (a copy is the default); over another file already there — `report.xlsm` saved as `report.xlsx`
     * beside an existing one — take the first free name instead; over a file another app changed since
     * we read it, ask before overwriting its edits.
     */
    private fun saveGuarded(path: String, then: (() -> Unit)? = null) {
        if (path == adapter.sourcePath && keepsOriginal) { keepPrompt = path to then; return }
        val target = if (path != adapter.sourcePath) freeName(path) else path
        if (holdsLossy(target, then)) return
        val message = if (target != path) Strings.get(R.string.status_saved_free_name, File(path).name, File(target).name) else null
        saveTo(target, then, message)
    }
    fun overwriteConflict() { val conflict = conflictPrompt ?: return; conflictPrompt = null; conflict.overwrite() }
    fun saveElsewhere() {
        val conflict = conflictPrompt ?: return
        conflictPrompt = null
        afterSave = conflict.afterSave
        showSave = true
    }
    fun cancelConflict() { conflictPrompt = null }

    /** Keep the Original → Save a Copy: the changes go to a copy beside it, which the document then is; the original stays as it was. */
    fun saveCopy() {
        val (path, then) = keepPrompt ?: return
        keepPrompt = null
        val copy = copyPathFor(path)
        saveTo(copy, then, Strings.get(R.string.status_saved_copy, File(copy).name, File(path).name))
    }
    /** Keep the Original → Replace Original: written over, what AeroDesk cannot keep dropped, as the user chose. */
    fun replaceOriginal() {
        val (path, then) = keepPrompt ?: return
        keepPrompt = null
        saveTo(path, then)
    }
    fun cancelKeep() { keepPrompt = null; afterSave = null }

    /** Manual saves and timer checkpoints enter the same queue in request order, across all four windows. */
    private fun saveTo(path: String, then: (() -> Unit)? = null, message: String? = null, overwrite: Boolean = false) {
        val owner = adapter.live()
        val next = then ?: afterSave
        afterSave = null
        val requestedStamp = fileStamp(path)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                if (adapter.live() !== owner) return@withLock
                if (holdsLossy(path, next)) return@withLock
                val before = fileStamp(path)
                val conflict = if (path == sourcePath) changedOnDisk(path) else before != requestedStamp
                try {
                    if ((!overwrite && conflict) || !writeCurrent(path, before, automatic = false, message = message)) {
                        if (adapter.live() === owner) conflictPrompt = SaveConflict(path, next) { saveTo(path, next, message, overwrite = true) }
                    } else if (adapter.live() === owner && next != null) {
                        guardDirty(next)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { saveFailed(path, e) }
            }
        }
    }

    private fun saveFailed(path: String, e: Exception) {
        status = Strings.get(R.string.err_save_failed, e.message)
        com.therealityteam.aeropages.platform.Diagnostics.log("save", path, e)
    }

    /** Called under [writes]. Take the snapshot on the UI thread and only acknowledge its edit version. */
    private suspend fun writeCurrent(path: String, before: FileStamp?, automatic: Boolean, message: String? = null): Boolean {
        val owner = adapter.live()
        val version = adapter.editVersion
        val originalProps = (owner as? HasProps)?.props
        val snap = adapter.snapshot()
        val props = stamp(snap)
        val written = writeAtomic(File(path), mayReplace = { fileStamp(path) == before }) { adapter.write(snap, it, extensionFor(path)) }
        if (!written) return false
        if (adapter.live() !== owner) return true

        val clean = adapter.editVersion == version
        if (owner is HasProps && props != null) {
            // Preserve File > Info edits made since the snapshot, while recording the completed save.
            val current = owner.props
            owner.props = if (current == originalProps) props else current.copy(
                author = if (current.author == originalProps?.author) props.author else current.author,
                created = props.created, modified = props.modified, lastModifiedBy = props.lastModifiedBy, revision = props.revision,
            )
        }
        adapter.markSaved(path, clean)
        sourceStamp = fileStamp(path)
        autosavePaused = false
        if (!automatic) {
            forgetContent()
            rememberRecent(path)
        }
        if (clean) dropOwnedDraft()
        status = when {
            !clean -> Strings.get(R.string.msg_unsaved_changes, File(path).name)
            message != null -> message
            automatic -> Strings.get(R.string.status_autosaved, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
            else -> Strings.get(R.string.status_saved_to, File(path).name)
        }
        return true
    }

    /** Stage in the destination directory. If replacement fails, the existing file remains recoverable. */
    private suspend fun writeAtomic(target: File, mayReplace: () -> Boolean = { true }, write: (OutputStream) -> Unit): Boolean {
        val tmp = File.createTempFile(".realityoffice-", ".tmp", target.absoluteFile.parentFile)
        try {
            withContext(Dispatchers.IO) { tmp.outputStream().use(write) }
            if (!mayReplace()) return false
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return true
        } finally { tmp.delete() }
    }

    // --- open / new / drafts ---------------------------------------------------

    fun open(path: String, switchEditor: ((String) -> Unit)? = null) = guardDirty {
        val target = DeskFiles.kindOfOrNull(path)
        when {
            target == kind -> scope.launch { openNow(path) }
            target != null && switchEditor != null -> switchEditor(path)
            else -> openProblem = OpenProblem(File(path).name, encrypted = false, details = null)
        }
    }

    suspend fun openNow(path: String) {
        status = Strings.get(R.string.status_opening, File(path).name)
        val t0 = System.nanoTime()
        com.therealityteam.aeropages.platform.Trace.reset()
        // Read on one IO thread from start to end, so what had to be salvaged is counted for this file alone.
        val openedStamp = fileStamp(path)
        runCatching {
            withContext(Dispatchers.IO) {
                com.therealityteam.aeropages.format.XmlPull.takeSalvaged()
                Triple(adapter.read(path), com.therealityteam.aeropages.format.XmlPull.takeSalvaged(), UnsupportedContent.scan(path))
            }
        }
            .onSuccess { (model, salvaged, kinds) ->
                val readMs = (System.nanoTime() - t0) / 1_000_000
                adapter.load(model); sourceStamp = openedStamp; autosavePaused = false
                forgetContent(); repaired = salvaged > 0; lossAccepted = false; unsupported = kinds
                rememberRecent(path); status = Strings.get(R.string.status_opened, File(path).name)
                // Timing line for perf checks (`adb logcat -s AeroOpen`): parse time, then parse + load.
                runCatching { android.util.Log.i("AeroOpen", "${File(path).name}: read ${readMs} ms, loaded ${(System.nanoTime() - t0) / 1_000_000} ms; ${com.therealityteam.aeropages.platform.Trace.summary()}") }
            }
            .onFailure {
                // The document on screen stays as it was (a blank one when nothing was open), so nothing can be saved over the file.
                val encrypted = it is com.therealityteam.aeropages.format.EncryptedDocumentException
                status = if (encrypted) Strings.get(R.string.err_open_encrypted, File(path).name) else Strings.get(R.string.err_open_failed, it.message)
                openProblem = OpenProblem(File(path).name, encrypted, it.message)
                // A password is the file's choice, not a fault of the app's to report.
                if (!encrypted) com.therealityteam.aeropages.platform.Diagnostics.log("open", path, it)
            }
    }

    /** Replace the document (new / template) behind the unsaved-changes guard. */
    fun replaceWith(label: String, model: () -> T) = guardDirty { adapter.load(model()); sourceStamp = null; forgetContent(); lossAccepted = false; status = label }

    /**
     * A new document from [template] (the module's blank one or one of its templates) behind the
     * unsaved-changes guard; until it is saved it is offered under the template's name.
     */
    fun newFrom(template: DeskTemplate, blankStatus: String, model: () -> T) =
        replaceWith(if (template.blank) blankStatus else Strings.get(R.string.status_from_template, Strings.get(template.label))) {
            untitledName = if (template.blank) defaultName else Strings.get(template.label)
            model()
        }

    /** Names a document made from [template] at launch (see [newFrom]) and says so. */
    fun startedFrom(template: DeskTemplate) {
        untitledName = Strings.get(template.label)
        status = Strings.get(R.string.status_from_template, untitledName)
    }

    fun checkDraft() { if (draftFile.exists() && draftFile.length() > 0) showDraftPrompt = true }

    fun recoverDraft() {
        showDraftPrompt = false
        val generation = draftGeneration
        val owner = adapter.live()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                try {
                    val draft = withContext(Dispatchers.IO) { adapter.read(draftFile.absolutePath) }
                    if (draftGeneration != generation || adapter.live() !== owner) return@withLock
                    adapter.detach(draft)
                    adapter.load(draft, recovered = true)
                    draftOwner = draft
                    showDraftPrompt = false
                    sourceStamp = null
                    forgetContent()
                    lossAccepted = false
                    status = Strings.get(R.string.msg_draft_recovered)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    status = Strings.get(R.string.err_draft_recovery_failed, e.message)
                    com.therealityteam.aeropages.platform.Diagnostics.log("draft", draftFile.name, e)
                }
            }
        }
    }

    fun discardDraft() {
        draftGeneration++
        draftFile.delete()
        draftOwner = null
        showDraftPrompt = false
    }

    private fun dropOwnedDraft() {
        draftGeneration++
        if (draftOwner === adapter.live()) {
            draftFile.delete()
            draftOwner = null
        }
    }

    /** Checkpoint only the unsaved document that owns this window's recovery file. */
    internal suspend fun checkpointDraft() = writes.withLock {
        if (!adapter.dirty || sourcePath != null || showDraftPrompt) return@withLock
        val owner = adapter.live()
        // A draft from a previous process belongs to its recovery prompt until the user decides.
        if (draftOwner !== owner && draftFile.exists()) { checkDraft(); return@withLock }
        val generation = draftGeneration
        try {
            val snap = adapter.snapshot()
            val written = writeAtomic(draftFile, mayReplace = {
                adapter.live() === owner && sourcePath == null && draftGeneration == generation
            }) { adapter.write(snap, it, extensionFor(draftFile.path)) }
            if (written) draftOwner = owner
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { com.therealityteam.aeropages.platform.Diagnostics.log("draft", draftFile.name, e) }
    }

    /** Export next to the source file (or into the app directory) as `<name>.<ext>`; [block] runs on IO over a snapshot. */
    fun export(ext: String, label: String, block: (T, OutputStream) -> Unit) {
        val out = File(sourcePath?.let { File(it).parentFile } ?: workDir, "$suggestedName.$ext")
        val snap = adapter.snapshot()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            writes.withLock {
                try {
                    writeAtomic(out) { block(snap, it) }
                    status = Strings.get(R.string.status_exported, label, out.absolutePath)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    status = Strings.get(R.string.err_export_failed, label, e.message)
                    com.therealityteam.aeropages.platform.Diagnostics.log("export", label, e)
                }
            }
        }
    }

    private fun rememberRecent(path: String) {
        recents = saveRecent(context, path).filter { DeskFiles.kindOfOrNull(File(it).name) == kind }
    }

    /**
     * Background checkpoints: a draft for never-saved dirty documents, and autosave for dirty
     * documents that already live in a file of this module's format. Both snapshot on the main
     * thread first.
     */
    @Composable
    fun Effects() {
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                checkpointDraft()
                autoSaveIfNeeded()
            }
        }
    }

    internal suspend fun autoSaveIfNeeded() = writes.withLock {
        val path = sourcePath ?: return@withLock
        if (!autoSave || !adapter.dirty || File(path).extension.lowercase() !in writableExtensions) return@withLock
        if (keepsOriginal || (!lossAccepted && adapter.losses(extensionFor(path)).isNotEmpty())) {
            if (!autosaveHeld) {
                status = if (keepsOriginal) Strings.get(R.string.status_autosave_held_unsupported)
                    else Strings.get(R.string.status_autosave_held_lossy, File(path).extension.uppercase())
                autosaveHeld = true
            }
            return@withLock
        }
        try {
            if (changedOnDisk(path) || !writeCurrent(path, sourceStamp, automatic = true)) {
                if (!autosavePaused) status = Strings.get(R.string.status_autosave_paused_conflict)
                autosavePaused = true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { saveFailed(path, e) }
    }

    companion object {
        private val writes = Mutex()

        /**
         * [target] with the module's extension: a known document extension is replaced
         * (`report.doc` → `report.docx`, `data.csv` → `data.xlsx`), anything else is appended
         * (`报告.v2` → `报告.v2.docx`).
         */
        /** [path] when nothing is there yet, else the first free "name (2).ext", "name (3).ext" … beside it. */
        fun freeName(path: String, exists: (String) -> Boolean = { File(it).exists() }): String {
            if (!exists(path)) return path
            val f = File(path)
            val ext = if (f.extension.isEmpty()) "" else "." + f.extension
            var k = 2
            while (true) {
                val p = File(f.parentFile, "${f.nameWithoutExtension} ($k)$ext").path
                if (!exists(p)) return p
                k++
            }
        }

        fun resolveSavePath(target: String, extension: String): String {
            val ext = File(target).extension.lowercase()
            if (ext == extension) return target
            val base = if (ext.isNotEmpty() && ext in DeskFiles.OPENABLE) target.dropLast(ext.length + 1) else target
            return "$base.$extension"
        }
    }
}

/** The dialogs every module shows on top of its content: save/open browsers, draft recovery, unsaved-changes prompt. */
@Composable
fun <T> SessionDialogs(session: DocumentSession<T>, saveTitle: String, untitledLabel: String) {
    if (session.showSave) {
        FileBrowser(
            mode = BrowserMode.SAVE,
            title = saveTitle,
            saveExtensions = session.writableExtensions,
            initialSaveExtension = session.suggestedExtension,
            startDir = session.browseStart(),
            shortcuts = session.shortcuts(),
            suggestedName = session.suggestedName,
            hasStorageAccess = session.storageAccess,
            onRequestStorage = session::requestStorage,
            onRefresh = session::refreshStorage,
            onDismiss = session::dismissSave,
            onOpen = {},
            onSave = session::saveAs,
        )
    }
    if (session.showOpen) {
        val switchEditor = LocalOpenInCurrentWindow.current
        FileBrowser(
            mode = BrowserMode.OPEN,
            startDir = session.browseStart(),
            shortcuts = session.shortcuts(),
            suggestedName = "",
            hasStorageAccess = session.storageAccess,
            onRequestStorage = session::requestStorage,
            onRefresh = session::refreshStorage,
            onDismiss = { session.showOpen = false },
            onOpen = { path -> session.showOpen = false; session.open(path, switchEditor) },
            onSave = {},
        )
    }
    if (session.showDraftPrompt) {
        DraftRecoverDialog(savedAtMillis = session.draftFile.lastModified(), onRecover = session::recoverDraft, onDiscard = session::discardDraft)
    }
    session.unsavedPrompt?.let {
        UnsavedChangesDialog(
            docName = session.sourceName ?: untitledLabel,
            onSave = session::saveAndRun,
            onDiscard = session::discardAndRun,
            onCancel = session::cancelPrompt,
        )
    }
    session.conflictPrompt?.let { conflict ->
        FileChangedDialog(
            docName = File(conflict.path).name,
            onOverwrite = session::overwriteConflict,
            onSaveAs = session::saveElsewhere,
            onCancel = session::cancelConflict,
        )
    }
    session.openProblem?.let { p ->
        OpenProblemDialog(name = p.name, encrypted = p.encrypted, details = p.details, onDismiss = { session.openProblem = null })
    }
    session.lossyPrompt?.let { p ->
        LossyFormatDialog(
            fileName = File(p.path).name,
            type = File(p.path).extension.uppercase(),
            what = lossList(p.losses),
            betterType = "." + session.extension,
            betterName = session.betterName(p.path),
            onSaveBetter = session::saveBetter,
            onKeep = session::keepFormat,
            onCancel = session::cancelLossy,
        )
    }
    session.keepPrompt?.let {
        KeepOriginalDialog(
            docName = session.sourceName ?: untitledLabel,
            copyName = session.copyName() ?: "",
            what = unsupportedList(session.unsupported, session.repaired),
            onSaveCopy = session::saveCopy,
            onReplace = session::replaceOriginal,
            onCancel = session::cancelKeep,
        )
    }
}

/**
 * The message bar under the ribbon about the open file: what it holds that AeroDesk cannot edit, and
 * where saving goes — a copy beside it, or a new file of the module's type beside a file of another
 * (`.doc`, `.xls`, `.xlsm` …). ✕ closes it for this file.
 */
@Composable
fun <T> SessionBanner(session: DocumentSession<T>) {
    val kinds = session.unsupported
    val repaired = session.repaired
    val beside = session.savesBeside
    val name = session.sourceName
    if (name == null || session.bannerClosed || (kinds.isEmpty() && !repaired && !beside)) return
    val what = when {
        kinds.isNotEmpty() -> stringResource(R.string.banner_unsupported, unsupportedList(kinds, repaired))
        repaired -> stringResource(R.string.banner_repaired)
        File(name).extension.lowercase() in OLDER_DECKS -> stringResource(R.string.banner_older_deck, name)
        File(name).extension.lowercase() in OLDER_FORMATS -> stringResource(R.string.banner_older_format, name)
        else -> ""
    }
    val where = if (beside) stringResource(R.string.banner_saves_beside, session.saveName ?: "") else stringResource(R.string.banner_saves_copy)
    MessageBar(listOf(what, where).filter { it.isNotEmpty() }.joinToString(" "), rounded = true, onClose = { session.bannerClosed = true },
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = Aero.bandGap))
}

/** The binary formats the legacy readers take text and basic formatting from. */
/** Office 97 files — and WPS Office's, the same inside — read for their text and basic formatting. */
private val OLDER_FORMATS = setOf("doc", "dot", "wps", "wpt", "xls", "xlt", "et", "ett")

/** PowerPoint 97 decks (and WPS Presentation's), read for their slides, text, shapes and pictures. */
private val OLDER_DECKS = setOf("ppt", "pot", "pps", "dps", "dpt")

/** [kinds] named and listed as the language lists things ("macros, PivotTables" / "宏、数据透视表"), and the parts that could not be read when the file was [damaged]. */
internal fun unsupportedList(kinds: Set<UnsupportedContent.Kind>, damaged: Boolean = false): String =
    (kinds.map { Strings.get(unsupportedLabel(it)) } + (if (damaged) listOf(Strings.get(R.string.unsupported_damaged)) else emptyList()))
        .joinToString(Strings.get(R.string.list_separator))

/** What a plain type leaves out, listed the same way. */
internal fun lossList(losses: Set<FormatLosses.Loss>): String = losses.joinToString(Strings.get(R.string.list_separator)) {
    Strings.get(when (it) {
        FormatLosses.Loss.OTHER_SHEETS -> R.string.loss_other_sheets
        FormatLosses.Loss.FORMULAS -> R.string.loss_formulas
        FormatLosses.Loss.FORMATTING -> R.string.loss_formatting
        FormatLosses.Loss.OBJECTS -> R.string.loss_objects
        FormatLosses.Loss.CELL_EXTRAS -> R.string.loss_cell_extras
        FormatLosses.Loss.NOTES -> R.string.loss_notes
    })
}

private fun unsupportedLabel(kind: UnsupportedContent.Kind): Int = when (kind) {
    UnsupportedContent.Kind.MACROS -> R.string.unsupported_macros
    UnsupportedContent.Kind.EMBEDDED_OBJECTS -> R.string.unsupported_embedded
    UnsupportedContent.Kind.CONTROLS -> R.string.unsupported_controls
    UnsupportedContent.Kind.SMARTART -> R.string.unsupported_smartart
    UnsupportedContent.Kind.MEDIA -> R.string.unsupported_media
    UnsupportedContent.Kind.PIVOT_TABLES -> R.string.unsupported_pivot
    UnsupportedContent.Kind.SLICERS -> R.string.unsupported_slicers
    UnsupportedContent.Kind.EXTERNAL_LINKS -> R.string.unsupported_links
    UnsupportedContent.Kind.DATA_CONNECTIONS -> R.string.unsupported_connections
    UnsupportedContent.Kind.DATA_MODEL -> R.string.unsupported_data_model
    UnsupportedContent.Kind.CHART_SHEETS -> R.string.unsupported_chart_sheets
    UnsupportedContent.Kind.NEW_CHART_TYPES -> R.string.unsupported_new_charts
    UnsupportedContent.Kind.COMMENT_THREADS -> R.string.unsupported_comment_threads
    UnsupportedContent.Kind.INK -> R.string.unsupported_ink
    UnsupportedContent.Kind.SIGNATURES -> R.string.unsupported_signatures
}

/** The 文件 dropdown wired to a session. */
@Composable
fun <T> SessionFileMenu(
    session: DocumentSession<T>,
    newLabel: String,
    onNew: () -> Unit,
    onExportPdf: () -> Unit,
    templates: List<DeskTemplate> = emptyList(),
    onNewFrom: (DeskTemplate) -> Unit = {},
    /** Export the sheet as CSV (Sheets'), under Export as PDF. */
    onExportCsv: (() -> Unit)? = null,
    /** File → Info. */
    onInfo: (() -> Unit)? = null,
) {
    if (!session.showFileMenu) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val switchEditor = LocalOpenInCurrentWindow.current
    FileMenu(
        dirty = session.dirty,
        autoSave = session.autoSave,
        recents = session.recents.map { FileEntry(File(it).name, it) },
        onDismiss = { session.showFileMenu = false },
        onNew = onNew,
        templates = templates,
        onNewFrom = onNewFrom,
        onOpen = { session.showOpen = true },
        onSave = session::save,
        onSaveAs = { session.showSave = true },
        onExportPdf = onExportPdf,
        onToggleAutoSave = { session.autoSave = !session.autoSave },
        onOpenRecent = { path -> session.open(path, switchEditor) },
        newLabel = newLabel,
        onOpenSystem = { com.therealityteam.aeropages.platform.PickerActivity.openDocument(context) },
        onSaveCopy = { com.therealityteam.aeropages.platform.PickerActivity.createDocument(context, session.id, session.suggestedFileName(), session.suggestedExtension) },
        onExportCsv = onExportCsv,
        onInfo = onInfo,
    )
}
