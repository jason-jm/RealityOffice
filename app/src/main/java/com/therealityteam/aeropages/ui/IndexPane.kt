package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.IndexEntries

/**
 * Word's Mark Index Entry, as a task pane beside the document (Word's dialog stays open while one selects text): the main
 * entry follows the selection, a subentry may go under it; Mark puts the entry after the selection, Mark All after the
 * selected text in every paragraph it appears in. Below, the entries the document has marked — tap one to go to it.
 */
@Composable
internal fun IndexEntryPane(editor: DocumentEditor, modifier: Modifier, onInsertIndex: () -> Unit, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val selected = editor.selectedText()
    var main by remember { mutableStateOf(IndexEntries.clean(selected).take(MAX_ENTRY)) }
    var sub by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    // As in Word, selecting other text puts it in as the main entry.
    LaunchedEffect(selected) {
        val s = IndexEntries.clean(selected).take(MAX_ENTRY)
        if (s.isNotEmpty() && s != main) { main = s; sub = ""; note = null }
    }
    val markedLabel = stringResource(R.string.status_index_marked)
    Column(modifier.paneSurface(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PaneHeader(stringResource(R.string.mark_index_entry), onClose)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DialogLabel(stringResource(R.string.index_main_entry))
            InputBox(main, { main = it.take(MAX_ENTRY); note = null }, Modifier.fillMaxWidth(), description = stringResource(R.string.index_main_entry))
            DialogLabel(stringResource(R.string.index_subentry))
            InputBox(sub, { sub = it.take(MAX_ENTRY); note = null }, Modifier.fillMaxWidth(), description = stringResource(R.string.index_subentry))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.index_mark), {
                if (editor.markIndexEntry(main, sub)) note = markedLabel.format(1)
            }, primary = true, enabled = main.isNotBlank(), small = true)
            // Mark All looks for the selected text (or, nothing selected, the main entry as typed).
            DialogButton(stringResource(R.string.index_mark_all), {
                val n = editor.markAllIndexEntries(selected.ifBlank { main }, main, sub)
                note = markedLabel.format(n)
            }, enabled = main.isNotBlank(), small = true)
        }
        note?.let { Text(it, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary) }
        val refs = editor.indexEntries()
        PaneSection(stringResource(R.string.index_marked_entries) + if (refs.isNotEmpty()) " · ${refs.size}" else "")
        if (refs.isEmpty()) PaneEmpty("ic_mark_entry", stringResource(R.string.index_no_entries), Modifier.fillMaxWidth().weight(1f))
        else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            refs.forEach { r -> EntryRow(editor, r) }
        }
        DialogButton(stringResource(R.string.insert_index) + "…", onInsertIndex, small = true)
    }
}

/** One marked entry: "Main: Sub" and the words it stands after; its delete button takes the mark out. */
@Composable
private fun EntryRow(editor: DocumentEditor, r: IndexEntries.Ref) {
    val text = r.paragraph.plainText()
    val before = text.substring(0, r.offset.coerceIn(0, text.length)).takeLast(28).trimStart()
    ListRow(selected = false, onClick = { editor.goToIndexEntry(r) }) {
        Column(Modifier.weight(1f)) {
            Text(r.entry.main + (r.entry.sub?.let { ": $it" } ?: ""), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (before.isNotEmpty()) Text("…$before", style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TitleIconButton("ic_delete", stringResource(R.string.index_remove_entry), { editor.removeIndexEntry(r) }, size = 32.dp)
    }
}

/**
 * Word's Index dialog (References ▸ Insert Index): page numbers after the entries or right-aligned after a dotted
 * leader, letter headings or not — with a preview of the document's own entries; OK inserts the index (or lays out
 * the one the document has anew).
 */
@Composable
internal fun InsertIndexDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    // The index the document has (read again once the file is in): its layout is where the options start.
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val current = editor.indexBlock
    var rightAlign by remember(current) { mutableStateOf(current?.rightAlign ?: false) }
    var headings by remember(current) { mutableStateOf(current?.headings ?: false) }
    val lines = remember(editor.revision, headings) { IndexEntries.lines(editor.document, headings) }
    AeroDialog(stringResource(R.string.group_index), onDismiss, 440.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), { editor.insertIndex(rightAlign, headings); onDismiss() }, primary = true)
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogLabel(stringResource(R.string.preview))
            IndexPreview(lines, rightAlign) { editor.document.pageCache[it.id] }
            CheckOption(stringResource(R.string.index_right_align), rightAlign, { rightAlign = !rightAlign })
            CheckOption(stringResource(R.string.index_headings), headings, { headings = !headings })
        }
    }
}

/** The index's first lines on a strip of paper, as the document will show them (or a sample when nothing is marked yet). */
@Composable
private fun IndexPreview(lines: List<IndexEntries.Line>, rightAlign: Boolean, pageOf: (com.therealityteam.aeropages.model.Paragraph) -> String?) {
    val ink = Color(0xFF1F1F1F)
    val shown = lines.take(8)
    Column(
        Modifier.fillMaxWidth().height(196.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.msg_index_empty), style = AeroType.caption, color = Color(0xFF616161))
            }
            return@Column
        }
        shown.forEach { l ->
            val pages = l.pages(pageOf).ifEmpty { if (l.heading || l.refs.isEmpty()) "" else "1" }
            val style = AeroType.caption.copy(color = ink, fontSize = 12.sp, fontWeight = if (l.heading) FontWeight.Bold else null)
            Row(Modifier.fillMaxWidth().padding(start = (l.level.coerceAtLeast(0) * 14).dp), verticalAlignment = Alignment.Bottom) {
                if (rightAlign && pages.isNotEmpty()) {
                    Text(l.text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.weight(1f).height(10.dp).padding(horizontal = 4.dp).drawBehind {
                        var x = 0f
                        while (x < size.width) { drawCircle(Color(0xFF9E9E9E), 1f, Offset(x, size.height - 2f)); x += 4f }
                    })
                    Text(pages, style = style)
                } else Text(if (pages.isEmpty()) l.text else "${l.text}, $pages", style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Word keeps an entry to 255 characters or so. */
private const val MAX_ENTRY = 255
