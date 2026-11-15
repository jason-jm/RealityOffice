package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Bookmarks
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphStyle

/*
 * Links in Pages: the dialog that makes or changes one — to a web address, or to a place in this
 * document (a heading, a bookmark) —, the Bookmarks dialog, and what the bar beside a link says
 * about where it goes.
 */

/** Where a link goes, as the bar beside it says: a web address as written, a bookmark by its name — one round a heading by the heading. */
internal fun linkLabel(editor: DocumentEditor, link: String): String {
    val name = Bookmarks.targetOf(link) ?: return link
    val found = Bookmarks.of(editor.document).firstOrNull { it.name == name }
    val heading = found?.takeIf { it.hidden }?.let { editor.document.locations().getOrNull(it.startPara)?.paragraph?.plainText()?.trim() }
    return heading?.takeIf { it.isNotEmpty() } ?: Bookmarks.outlineTarget(name) ?: name
}

/** The headings a link can go to, with their level (0 for Heading 1). */
private fun headingsOf(editor: DocumentEditor): List<Pair<Paragraph, Int>> = editor.document.locations().mapNotNull { loc ->
    val level = when (loc.paragraph.format.style) {
        ParagraphStyle.HEADING1 -> 0
        ParagraphStyle.HEADING2 -> 1
        ParagraphStyle.HEADING3 -> 2
        else -> return@mapNotNull null
    }
    (loc.paragraph to level).takeIf { loc.paragraph.plainText().isNotBlank() }
}

/**
 * Insert Hyperlink in Pages: a web address, or a place in this document — a heading (linked through
 * a bookmark round it) or a bookmark. With the caret in a link (its text selected first) it changes
 * where that link goes. [onApplied] reports what was done.
 */
@Composable
internal fun DocLinkDialog(editor: DocumentEditor, onDismiss: () -> Unit, onApplied: () -> Unit, startInDocument: Boolean = false) {
    val current = remember { editor.linkAtCaret()?.first }
    var inDocument by remember { mutableStateOf(startInDocument || (current != null && Bookmarks.targetOf(current) != null)) }
    var url by remember { mutableStateOf(current?.takeIf { Bookmarks.targetOf(it) == null } ?: "https://") }
    val headings = remember { headingsOf(editor) }
    val marks = remember { editor.bookmarks() }
    // The place picked: a heading paragraph or a bookmark's name (the current link's, when it goes to one).
    var picked by remember { mutableStateOf<Any?>(Bookmarks.targetOf(current)) }
    AeroDialog(title = stringResource(if (current != null) R.string.link_edit else R.string.title_insert_hyperlink), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.apply), {
                    val place = picked
                    when {
                        !inDocument -> if (url.isNotBlank() && url.trim() != "https://") { editor.applyLink(url.trim()); onApplied() }
                        place is Paragraph -> { editor.linkToHeading(place); onApplied() }
                        place is String -> { editor.applyLink("#$place", display = place); onApplied() }
                    }
                }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(460.dp)) {
                // Word's Link to: an existing web page or a place in this document.
                ChoiceSegments(listOf(false, true), inDocument, { stringResource(if (it) R.string.link_this_document else R.string.link_web) }, { inDocument = it }, width = 440.dp)
                if (!inDocument) {
                    Text(stringResource(R.string.tip_hyperlink_selection), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                    InputBox(url, { url = it }, Modifier.fillMaxWidth(), placeholder = "https://")
                } else if (headings.isEmpty() && marks.isEmpty()) {
                    Text(stringResource(R.string.link_no_places), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary)
                } else {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    ) {
                        if (headings.isNotEmpty()) PlaceGroup(stringResource(R.string.link_headings))
                        headings.forEach { (p, level) ->
                            PlaceRow(p.plainText().trim(), level, picked === p) { picked = p }
                        }
                        if (marks.isNotEmpty()) PlaceGroup(stringResource(R.string.bookmarks))
                        marks.forEach { b -> PlaceRow(b.name, 0, picked == b.name, icon = "ic_bookmark") { picked = b.name } }
                    }
                }
            }
        }
}

@Composable
internal fun PlaceGroup(title: String) {
    Text(title, style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(top = 4.dp))
}

/** A place a link can go to: its text, set in by its [level], picked or not. */
@Composable
internal fun PlaceRow(text: String, level: Int, picked: Boolean, icon: String? = null, pick: () -> Unit) {
    ListRow(picked, pick, Modifier.padding(start = (level * 16).dp)) {
        if (icon != null) { RibbonGlyph(icon, PicoTheme.colorScheme.labelSecondary, 16.dp); Box(Modifier.width(8.dp)) }
        Text(text, style = if (picked) AeroType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else AeroType.body,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = PicoTheme.colorScheme.labelPrimary)
    }
}

/** A bookmark name made from [text] (the selection): its letters, digits and underscores, spaces as underscores — none when it would not start with a letter. */
internal fun suggestedBookmarkName(text: String): String {
    val s = text.trim().replace(Regex("""\s+"""), "_").filter { it.isLetterOrDigit() || it == '_' }.take(40)
    return s.takeIf { Bookmarks.isValidName(it) }.orEmpty()
}

/**
 * Word's Bookmark dialog: a name for the selection (or the caret's place) and Add; the document's
 * bookmarks — Word's hidden ones too when asked — each to go to or delete.
 */
@Composable
internal fun BookmarkDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(suggestedBookmarkName(editor.selectedText())) }
    var hidden by remember { mutableStateOf(false) }
    var bad by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val list = editor.bookmarks(hidden)
    val paragraphs = editor.document.locations()
    // Word's: the list's pick is the bookmark the name box names.
    val picked = list.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    AeroDialog(title = stringResource(R.string.bookmarks), onDismiss = onDismiss, width = 520.dp, buttons = { DialogButton(stringResource(R.string.close), onDismiss) }) {
        Text(stringResource(R.string.bookmark_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(520.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                InputBox(name, { name = it; bad = false }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.bookmark_name))
                if (bad) FieldError(stringResource(R.string.bookmark_bad_name))
                ListBox(height = 220.dp, empty = if (list.isEmpty()) stringResource(R.string.bookmark_none) else null, emptyIcon = "ic_bookmark") {
                    list.forEach { b ->
                        // What it marks: its text (a point: the text it stands before).
                        val text = paragraphs.getOrNull(b.startPara)?.paragraph?.plainText().orEmpty()
                        val from = b.start.coerceIn(0, text.length)
                        val to = if (b.endPara == b.startPara && b.end > b.start) b.end.coerceIn(from, text.length) else text.length
                        val shown = text.substring(from, to).replace('\n', ' ').trim()
                        val on = b == picked
                        ListRow(on, { name = b.name; bad = false }) {
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(b.name, style = if (on) AeroType.body.copy(fontWeight = FontWeight.SemiBold) else AeroType.body,
                                    color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (shown.isNotEmpty()) Text(shown, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                CheckOption(stringResource(R.string.bookmark_hidden), hidden, { hidden = !hidden })
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(112.dp)) {
                DialogButton(stringResource(R.string.bookmark_add), {
                    if (editor.addBookmark(name.trim())) name = "" else bad = true
                }, Modifier.fillMaxWidth(), primary = true, enabled = name.isNotBlank())
                DialogButton(stringResource(R.string.delete), { picked?.let { editor.deleteBookmark(it.name); name = "" } }, Modifier.fillMaxWidth(), enabled = picked != null)
                DialogButton(stringResource(R.string.bookmark_go_to), { picked?.let { if (editor.goToBookmark(it.name)) onDismiss() } }, Modifier.fillMaxWidth(), enabled = picked != null)
            }
        }
    }
}
