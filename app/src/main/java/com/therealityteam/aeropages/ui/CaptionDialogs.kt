package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.CrossReferences
import com.therealityteam.aeropages.editor.CrossReferences.Content
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Fields

/** The separators Word offers between a caption's chapter number and its own: hyphen, period, colon, em dash, en dash. */
private val CHAPTER_SEPARATORS = listOf("-", ".", ":", "—", "–")

/** The numbering formats a caption's sequence can take: its `\*` switch and an example. */
private val CAPTION_FORMATS = listOf("ARABIC" to "1, 2, 3", "alphabetic" to "a, b, c", "ALPHABETIC" to "A, B, C", "roman" to "i, ii, iii", "ROMAN" to "I, II, III")

/**
 * Insert Caption (Word's): the label (Figure, Table, Equation, those the document numbers already, a new one), where it
 * goes (below or above the picked item), how its number is written, with or without the label, with its chapter's
 * number and a separator ([includeChapter] to start with it on) — the caption as it will read shown as it is set up.
 */
@Composable
internal fun InsertCaptionDialog(editor: DocumentEditor, includeChapter: Boolean = false, onDismiss: () -> Unit) {
    val defaults = listOf(stringResource(R.string.caption_figure), stringResource(R.string.caption_table), stringResource(R.string.caption_equation))
    val used = remember(editor.document) { CrossReferences.labels(editor.document) }
    var added by remember { mutableStateOf(emptyList<String>()) }
    val labels = (defaults + used + added).distinctBy { it.lowercase() }
    var label by remember(used) { mutableStateOf(used.firstOrNull() ?: defaults.first()) }
    var below by remember { mutableStateOf(true) }
    var format by remember { mutableStateOf("ARABIC") }
    var withLabel by remember { mutableStateOf(true) }
    var newLabel by remember { mutableStateOf("") }
    // Word's "Include chapter number": the Heading 1's number and a separator before the caption's own.
    var chapter by remember { mutableStateOf(includeChapter) }
    var separator by remember { mutableStateOf("-") }
    // The number it would take: one past the captions of the label so far — in its chapter when it carries the
    // chapter's number — (where it lands may renumber them).
    val doc = editor.document
    val here = editor.active
    val chapterNo = remember(doc, here) { Fields.chapterNumber(doc.locations(), com.therealityteam.aeropages.model.PageMetrics.listLabels(doc), here, 1) }
    val numbered = remember(doc, here) { Fields.chapterNumbered(doc.locations(), com.therealityteam.aeropages.model.PageMetrics.listLabels(doc), here, 1) }
    val next = remember(doc, label, chapter, here) {
        val from = if (chapter) Fields.chapterStart(doc.locations(), here, 1) else -1
        Fields.placed(doc).count { it.field.kind == "SEQ" && it.field.target.equals(label, ignoreCase = true) && it.para > from && it.para <= here } + 1
    }
    val preview = (if (withLabel) "$label " else "") + (if (chapter) (chapterNo ?: "0") + separator else "") + Fields.number(next, format)
    WideDialog(title = stringResource(R.string.insert_caption), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.insert), { editor.insertCaption(label, below, format, withLabel, separator.takeIf { chapter }); onDismiss() }, primary = true)
        }
    }) {
        // The caption as it will read.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel(stringResource(R.string.caption_preview))
            Text(preview, style = PicoTheme.typography.titleMedium, color = Color(CrossReferences.CAPTION_COLOR),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 14.dp, vertical = 6.dp))
        }
        // Word's Label drop-down, and New Label beside it.
        FieldRow(stringResource(R.string.caption_label), labelWidth = 96.dp) {
            ComboField(label, labels, { it }, label, { label = it }, width = 200.dp)
        }
        Row(Modifier.padding(start = 108.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InputBox(newLabel, { newLabel = it }, Modifier.width(200.dp), placeholder = stringResource(R.string.caption_new_label))
            val ok = newLabel.trim().let { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '_' } }
            DialogButton(stringResource(R.string.caption_add_label), { if (ok) { val l = newLabel.trim(); added = added + l; label = l; newLabel = "" } }, enabled = ok)
            if (newLabel.isNotBlank() && !ok) Text(stringResource(R.string.caption_label_invalid), style = AeroType.caption, color = PicoTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column {
                SectionLabel(stringResource(R.string.caption_position))
                RadioRow(stringResource(R.string.caption_below), below) { below = true }
                RadioRow(stringResource(R.string.caption_above), !below) { below = false }
            }
            Column {
                SectionLabel(stringResource(R.string.caption_numbering))
                ComboField(CAPTION_FORMATS.first { it.first == format }.second, CAPTION_FORMATS, { it.second }, CAPTION_FORMATS.first { it.first == format },
                    { format = it.first }, width = 160.dp)
                CheckRow(stringResource(R.string.caption_exclude_label), !withLabel) { withLabel = !withLabel }
                CheckRow(stringResource(R.string.caption_include_chapter), chapter) { chapter = !chapter }
                if (chapter) {
                    FieldRow(stringResource(R.string.caption_separator), labelWidth = 96.dp) {
                        ComboField(separator, CHAPTER_SEPARATORS, { it }, separator, { separator = it }, width = 96.dp)
                    }
                    // Word's chapter number is the heading's list number: an unnumbered chapter shows 0, none at all an error.
                    if (!numbered) Text(stringResource(R.string.caption_chapter_unnumbered), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                }
            }
        }
    }
}

/**
 * Cross-reference (Word's): what kind of item it refers to (a heading, a bookmark, the captions of each label the
 * document numbers), what of it it shows — the contents that kind offers —, which item, and whether it links to it.
 */
@Composable
internal fun CrossReferenceDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    val doc = editor.document
    val labels = remember(doc) { CrossReferences.labels(doc) }
    // 0: headings, 1: bookmarks, 2+: the captions of labels[i - 2].
    var type by remember(labels) { androidx.compose.runtime.mutableIntStateOf(if (labels.isNotEmpty()) 2 else 0) }
    var content by remember { mutableStateOf<Content?>(null) }
    var picked by remember { mutableStateOf<CrossReferences.Target?>(null) }
    var link by remember { mutableStateOf(true) }
    val items: List<CrossReferences.Target> = remember(doc, type) {
        when (type) {
            0 -> CrossReferences.headings(doc)
            1 -> CrossReferences.bookmarks(doc)
            else -> CrossReferences.captions(doc, labels[type - 2])
        }
    }
    val contents = when (type) {
        0 -> listOf(Content.TEXT, Content.PAGE, Content.NUMBER, Content.ABOVE_BELOW)
        1 -> listOf(Content.TEXT, Content.PAGE, Content.NUMBER, Content.ABOVE_BELOW)
        else -> listOf(Content.TEXT, Content.LABEL_NUMBER, Content.CAPTION_TEXT, Content.PAGE, Content.ABOVE_BELOW)
    }
    val shown = content?.takeIf { it in contents } ?: if (type >= 2) Content.LABEL_NUMBER else Content.TEXT
    // Word's: the first of the list is picked until another is.
    val target = picked?.takeIf { it in items } ?: items.firstOrNull()
    WideDialog(title = stringResource(R.string.cross_reference), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.insert), { target?.let { editor.insertCrossReference(it, shown, link); onDismiss() } }, primary = true, enabled = target != null)
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.xref_type))
                // Word's Reference type drop-down: Heading, Bookmark, then each caption label.
                val headingName = stringResource(R.string.xref_heading); val bookmarkName = stringResource(R.string.bookmark)
                val typeName: (Int) -> String = { t -> when (t) { 0 -> headingName; 1 -> bookmarkName; else -> labels.getOrElse(t - 2) { "" } } }
                ComboField(typeName(type), (0 until labels.size + 2).toList(), { typeName(it) }, type, { type = it; picked = null }, width = 240.dp)
                SectionLabel(stringResource(R.string.xref_content))
                contents.forEach { c -> RadioRow(contentName(c, type), c == shown) { content = c } }
                CheckRow(stringResource(R.string.xref_as_link), link) { link = !link }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1.3f)) {
                SectionLabel(stringResource(R.string.xref_which))
                ListBox(height = 280.dp, empty = if (items.isEmpty()) stringResource(when (type) { 0 -> R.string.xref_none_heading; 1 -> R.string.xref_none_bookmark; else -> R.string.xref_none_caption }) else null,
                    emptyIcon = "ic_cross_reference") {
                    items.forEach { t ->
                        val on = t == target
                        val indent = ((t as? CrossReferences.Heading)?.level ?: 0) * 16
                        ListRow(t.title, on, { picked = t }, Modifier.padding(start = indent.dp))
                    }
                }
            }
        }
    }
}

/** How the dialog names content [c] for reference type [type] (0 headings, 1 bookmarks, else captions). */
@Composable
private fun contentName(c: Content, type: Int): String = stringResource(when (c) {
    Content.TEXT -> when (type) { 0 -> R.string.xref_c_heading_text; 1 -> R.string.xref_c_bookmark_text; else -> R.string.xref_c_entire_caption }
    Content.LABEL_NUMBER -> R.string.xref_c_label_number
    Content.CAPTION_TEXT -> R.string.xref_c_caption_text
    Content.PAGE -> R.string.xref_c_page
    Content.NUMBER -> if (type == 0) R.string.xref_c_heading_number else R.string.xref_c_paragraph_number
    Content.ABOVE_BELOW -> R.string.xref_c_above_below
})

/**
 * Insert Table of Figures (Word's): which caption label it lists — those the document numbers, and Word's three —
 * with the entries it would list now, as they read, before it is inserted at the caret.
 */
@Composable
internal fun TableOfFiguresDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    val defaults = listOf(stringResource(R.string.caption_figure), stringResource(R.string.caption_table), stringResource(R.string.caption_equation))
    val used = remember(editor.document) { CrossReferences.labels(editor.document) }
    val labels = (used + defaults).distinctBy { it.lowercase() }
    var label by remember(used) { mutableStateOf(used.firstOrNull() ?: defaults.first()) }
    val entries = remember(editor.document, label) { CrossReferences.captions(editor.document, label) }
    WideDialog(title = stringResource(R.string.insert_table_of_figures), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.insert), { editor.insertTableOfFigures(label); onDismiss() }, primary = true)
        }
    }) {
        FieldRow(stringResource(R.string.caption_label), labelWidth = 96.dp) {
            ComboField(label, labels, { it }, label, { label = it }, width = 200.dp)
        }
        SectionLabel(stringResource(R.string.tof_entries))
        Column(
            Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (entries.isEmpty()) Text(stringResource(R.string.tof_none_yet), style = AeroType.caption, color = Color(0xFF6B6B6B))
            entries.forEach { c ->
                // As the table will list it: the whole caption, a dotted leader to its page.
                Box(Modifier.fillMaxWidth().height(24.dp).drawBehind {
                    val y = size.height * 0.72f
                    drawLine(Color(0xFF9E9E9E), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1.5f,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(0.5f, 5f)))
                }) {
                    Text(c.title, style = AeroType.body, color = Color(0xFF1F1F1F), maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterStart).padding(end = 40.dp).background(Color.White).padding(end = 6.dp))
                    editor.pageOfLocation(c.para)?.let { page ->
                        Text(editor.pageLabelOf(page), style = AeroType.body, color = Color(0xFF1F1F1F),
                            modifier = Modifier.align(Alignment.CenterEnd).background(Color.White).padding(start = 6.dp))
                    }
                }
            }
        }
    }
}
