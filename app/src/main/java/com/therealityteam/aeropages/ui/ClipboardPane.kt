package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.ObjectClipboard

/**
 * The Office Clipboard: what was copied or cut in the app — in any module, any window — newest first, up to 24 items as
 * Office keeps. A text copy is its text; an object's copy (a picture, a shape, a chart, a table) is the object with its
 * words. The same text copied again moves to the top rather than repeating.
 */
internal object ClipboardHistory {
    /** One collected item: its words and, for an object, the object itself. */
    class Clip(val id: Long, val text: String, val item: ObjectClipboard.Item?)

    const val MAX = 24
    private var nextId = 1L
    val items = mutableStateListOf<Clip>()

    fun record(text: String, item: ObjectClipboard.Item? = null) {
        if (text.isEmpty() && item == null) return
        if (item == null) items.removeAll { it.item == null && it.text == text }
        items.add(0, Clip(nextId++, text, item))
        while (items.size > MAX) items.removeAt(items.lastIndex)
    }

    fun remove(clip: Clip) { items.removeAll { it.id == clip.id } }
    fun clear() = items.clear()
}

/**
 * Office's Clipboard pane (Home ▸ Clipboard's launcher): Paste All and Clear All, then every collected item as a card —
 * an icon for what it is and its first lines — that pastes at the caret when clicked ([onPasteText] for words,
 * [onPasteObject] for an object), each with a menu to paste or delete it. Paste All pastes them in the order they were
 * copied, one after another.
 */
@Composable
internal fun ClipboardPane(
    modifier: Modifier,
    onPasteText: (String) -> Unit,
    onPasteObject: (ObjectClipboard.Item) -> Unit,
    onClose: () -> Unit,
) {
    val items = ClipboardHistory.items
    fun paste(c: ClipboardHistory.Clip) { val item = c.item; if (item != null) onPasteObject(item) else onPasteText(c.text) }
    Column(modifier.paneSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneHeader(stringResource(R.string.clipboard), onClose)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // Paste All: the text items in the order copied, a line apart (objects paste on their own).
            DialogButton(stringResource(R.string.paste_all), {
                val words = items.reversed().filter { it.item == null }.joinToString("\n") { it.text }
                if (words.isNotEmpty()) onPasteText(words)
            }, enabled = items.any { it.item == null }, small = true)
            DialogButton(stringResource(R.string.clipboard_clear_all), ClipboardHistory::clear, enabled = items.isNotEmpty(), small = true)
            Box(Modifier.weight(1f))
            Text(stringResource(R.string.clipboard_count, items.size, ClipboardHistory.MAX), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
        if (items.isEmpty()) {
            PaneEmpty("ic_paste", stringResource(R.string.clipboard_empty), Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        Text(stringResource(R.string.clipboard_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in items) ClipCard(c, { paste(c) })
        }
    }
}

/** One collected item: what it is, its first three lines, and its menu (Paste, Delete). */
@Composable
private fun ClipCard(c: ClipboardHistory.Clip, onPaste: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    PaneCard(onClick = onPaste) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            val icon = when (c.item) {
                is ObjectClipboard.Picture -> "ic_picture"
                is ObjectClipboard.Shape -> "ic_shape_rect"
                is ObjectClipboard.Chart -> "ic_chart"
                is ObjectClipboard.Table -> "ic_table"
                null -> "ic_textbox"
            }
            RibbonGlyph(icon, LocalAccent.current, 18.dp)
            Text(
                c.text.ifBlank { stringResource(objectName(c.item)) }, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Box {
                TitleIconButton("ic_more", stringResource(R.string.more_actions), { menu = true }, size = 28.dp)
                if (menu) AnchoredFlyout({ menu = false }) {
                    FlyoutMenu(width = 180.dp) {
                        MenuCommand(stringResource(R.string.paste), { menu = false; onPaste() }, icon = "ic_paste")
                        MenuCommand(stringResource(R.string.delete), { menu = false; ClipboardHistory.remove(c) }, icon = "ic_delete")
                    }
                }
            }
        }
    }
}

private fun objectName(item: ObjectClipboard.Item?): Int = when (item) {
    is ObjectClipboard.Picture -> R.string.picture
    is ObjectClipboard.Shape -> R.string.shape
    is ObjectClipboard.Chart -> R.string.chart_label
    is ObjectClipboard.Table -> R.string.table
    null -> R.string.clipboard
}
