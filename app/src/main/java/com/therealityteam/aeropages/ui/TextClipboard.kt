package com.therealityteam.aeropages.ui

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import com.therealityteam.aeropages.editor.ObjectClipboard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Plain-text copy / paste over Compose's [Clipboard], the suspend API that replaced the
 * deprecated `ClipboardManager` in the Compose release the Spatial SDK ships. Commands stay
 * fire-and-forget: [copy] and [paste] launch on the composition's scope (the main thread), so
 * a paste callback runs as soon as the system clipboard answers.
 */
internal class TextClipboard(private val clipboard: Clipboard, private val scope: CoroutineScope) {
    fun copy(text: String) {
        if (text.isEmpty()) return
        lastCopied = text
        lastObjectLabel = null
        // Any other copy than Pages' own formatted one (which sets it first) leaves no formatting to paste.
        if (com.therealityteam.aeropages.editor.DocClipboard.clip?.text != text) com.therealityteam.aeropages.editor.DocClipboard.clip = null
        ClipboardHistory.record(text)
        scope.launch { clipboard.setClipEntry(ClipData.newPlainText("Reality Office", text).toClipEntry()) }
    }

    /**
     * Copy the text that goes with the object just put on [ObjectClipboard] (its words, for other
     * apps), in a clip labelled for that copy alone: a paste that still finds that clip there pastes
     * the object, any other clip means something was copied since.
     */
    fun copyObject(text: String) {
        val label = OBJECT_LABEL + (++objectCopies)
        lastCopied = text
        com.therealityteam.aeropages.editor.DocClipboard.clip = null
        lastObjectLabel = label
        ClipboardHistory.record(text, ObjectClipboard.item)
        scope.launch { clipboard.setClipEntry(ClipData.newPlainText(label, text.ifEmpty { " " }).toClipEntry()) }
    }

    /** Copy beside the object just put on [ObjectClipboard] its words — else what it is — for other apps. */
    fun copyCurrentObject() {
        val item = ObjectClipboard.item ?: return
        copyObject(ObjectClipboard.textOf(item).ifBlank {
            Strings.get(when (item) {
                is ObjectClipboard.Picture -> com.therealityteam.aeropages.R.string.picture
                is ObjectClipboard.Shape -> com.therealityteam.aeropages.R.string.shape
                is ObjectClipboard.Chart -> com.therealityteam.aeropages.R.string.chart_label
                is ObjectClipboard.Table -> com.therealityteam.aeropages.R.string.table
            })
        })
    }

    /** Whether the last thing copied in the app was an object (what Ctrl+V in a text field should leave to [pasteObjectOrText]). */
    val objectCopiedLast: Boolean get() = lastObjectLabel != null && ObjectClipboard.item != null

    /**
     * Paste what was copied last: [onObject] when the clipboard still holds the clip copied with the
     * object on [ObjectClipboard], else [onText] with its text. When the system will not let the
     * app read the clipboard, the app's own last copy decides.
     */
    fun pasteObjectOrText(onObject: (ObjectClipboard.Item) -> Unit, onText: (String) -> Unit) {
        scope.launch {
            val clip = runCatching { clipboard.getClipEntry()?.clipData }.getOrNull()
            val item = ObjectClipboard.item
            if (clip != null) {
                val label = clip.description?.label?.toString()
                if (item != null && label != null && label == lastObjectLabel) { onObject(item); return@launch }
                val text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                if (!text.isNullOrEmpty()) { onText(text); return@launch }
            }
            if (item != null && lastObjectLabel != null) onObject(item) else lastCopied?.takeIf { it.isNotEmpty() }?.let(onText)
        }
    }

    /**
     * Calls [onText] with the clipboard's text when it holds a non-empty text item. When the
     * system will not let the app read it — Android refuses apps its window focus rules do not
     * count as focused, which a spatial window with no key focus can be — the text last copied in
     * the app pastes instead, so copy and paste within AeroDesk always work.
     */
    fun paste(onText: (String) -> Unit) {
        scope.launch {
            val clip = runCatching { clipboard.getClipEntry()?.clipData }.getOrNull()
            val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            val use = if (!text.isNullOrEmpty()) text else lastCopied
            if (!use.isNullOrEmpty()) onText(use)
        }
    }

    private companion object {
        /** The last text copied in the app (every module, every window of the process). */
        @Volatile var lastCopied: String? = null
        /** The clip label of the last object copied, while no text has been copied since. */
        @Volatile var lastObjectLabel: String? = null
        @Volatile var objectCopies = 0
        const val OBJECT_LABEL = "Reality Office object "
    }
}

@Composable
internal fun rememberTextClipboard(): TextClipboard {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, scope) { TextClipboard(clipboard, scope) }
}
