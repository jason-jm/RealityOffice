package com.therealityteam.aeropages.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.format.DeskFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Documents handed to Aero Desk from outside — a file manager's ACTION_VIEW or a share
 * (ACTION_SEND). The launch activity posts the intent here; the window named by [Request.target] takes it. A busy
 * window redirects the request to an available side window.
 */
object OpenRequests {
    /** The default window container. */
    const val MAIN = "main"
    /** Side window containers (declared in Main.kt), used in this order as they become free. */
    val SIDE_WINDOWS = listOf("aux", "aux2", "aux3")

    /** A document to open; [target] is the window container that should show it. */
    class Request(val intent: Intent, val target: String)

    private val _pending = MutableStateFlow<List<Request>>(emptyList())
    val pending: StateFlow<List<Request>> = _pending

    /** Which windows currently show a document (a window that never opened counts as free). */
    private val busy = HashMap<String, Boolean>()
    @Synchronized fun setBusy(window: String, showingDocument: Boolean) { busy[window] = showingDocument }
    /** A request reserves its destination until the editor takes ownership, including while importing the file. */
    @Synchronized fun idleSideWindow(excluding: String? = null): String? = SIDE_WINDOWS.firstOrNull { window ->
        window != excluding && busy[window] != true && pending.value.none { it.target == window }
    }

    /** Queue [intent] when it carries an openable document; other intents (the plain launch) are ignored. */
    fun submit(intent: Intent?) {
        if (intent == null) return
        val carries = documentUri(intent) != null || sharedText(intent) != null
        if (carries) _pending.update { it + Request(intent, MAIN) }
    }

    /** Hand [request] to a free side window; returns the container id to open. */
    @Synchronized fun redirectToSide(request: Request): Request? {
        val next = idleSideWindow(excluding = request.target)?.let { Request(request.intent, it) }
        _pending.update { list -> if (next == null) list - request else list.map { if (it === request) next else it } }
        return next
    }

    /** "Open in new window": queue a local document for a free side window; returns the container id to open. */
    @Synchronized fun openInSideWindow(path: String, from: String? = null): Request? {
        val id = idleSideWindow(excluding = from) ?: return null
        val next = Request(Intent(Intent.ACTION_VIEW, Uri.fromFile(File(path))), id)
        _pending.update { it + next }
        return next
    }

    fun consume(request: Request) { _pending.update { it - request } }

    fun documentUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: intent.data
        else -> null
    }

    private fun sharedText(intent: Intent): CharSequence? {
        if (intent.action != Intent.ACTION_SEND) return null
        val ext = DeskFiles.extensionForMime(intent.type)
        if (ext !in sharedTextExtensions) return null
        val html = if (ext == "html") intent.getStringExtra(Intent.EXTRA_HTML_TEXT) else null
        return (html ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)?.takeIf { it.isNotEmpty() }
    }

    /**
     * A local path for the intent's document: a `file:` URI is used directly; a `content:` URI
     * is copied into `<workDir>/Imported/<display name>` (saving then writes that copy). Null when
     * nothing readable was attached.
     */
    suspend fun resolvePath(context: Context, workDir: File, intent: Intent): String? = withContext(Dispatchers.IO) {
        val uri = documentUri(intent)
        if (uri == null) {
            val text = sharedText(intent) ?: return@withContext null
            val ext = DeskFiles.extensionForMime(intent.type) ?: return@withContext null
            val name = "${context.getString(R.string.document_label)}.$ext"
            return@withContext text.toString().byteInputStream().use { importDocument(importDirectory(workDir), name, it) }
        }
        if (uri.scheme == "file") return@withContext uri.path?.takeIf { File(it).isFile && File(it).canRead() && DeskFiles.kindOfOrNull(it) != null }
        if (uri.scheme != "content") return@withContext null
        val given = (displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/'))
            ?.trim()?.takeIf { it.isNotEmpty() && it != "." && it != ".." && !it.contains('\\') && it.none(Char::isISOControl) } ?: "document"
        // A name without an extension a module opens takes its type's ("document" sent as a workbook opens in Sheets).
        val name = if (DeskFiles.kindOfOrNull(given) != null) given else {
            val ext = DeskFiles.extensionForMime(intent.type) ?: DeskFiles.extensionForMime(context.contentResolver.getType(uri))
            if (ext == null) return@withContext null
            "$given.$ext"
        }
        context.contentResolver.openInputStream(uri)?.use { importDocument(importDirectory(workDir), name, it) }
    }

    private fun importDirectory(workDir: File) = File(workDir, "Imported").also { it.mkdirs() }

    internal suspend fun importDocument(dir: File, name: String, input: InputStream): String {
        val staged = File.createTempFile(".import-", ".tmp", dir)
        try {
            staged.outputStream().use { input.copyTo(it) }
            currentCoroutineContext().ensureActive()
            val base = File(dir, name)
            // Moving without REPLACE_EXISTING preserves previous copies. The lock also serialises imports on Android filesystems.
            return synchronized(importLock) {
                val target = File(DocumentSession.freeName(base.path))
                java.nio.file.Files.move(staged.toPath(), target.toPath())
                target.absolutePath
            }
        } finally { staged.delete() }
    }

    private val importLock = Any()
    private val sharedTextExtensions = setOf("txt", "md", "html", "csv", "rtf")

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains('\\') }
}
