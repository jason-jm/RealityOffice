package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Comment
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R

/** Footnotes pane: the document's footnotes and endnotes as cards, each its number, its text to edit and Delete. */
@Composable
fun FootnotesPane(editor: DocumentEditor, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val footnotes = editor.document.footnotes
    Column(
        modifier = modifier.fillMaxSize().paneSurface(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaneHeader(stringResource(R.string.footnote), onClose)
        if (footnotes.isEmpty() && editor.document.endnotes.isEmpty()) {
            PaneEmpty("ic_notes", stringResource(R.string.msg_no_footnotes))
            return@Column
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            footnotes.forEachIndexed { i, fn ->
                NoteCard((i + 1).toString(), fn.text, { editor.updateFootnote(fn, it) }, { editor.deleteFootnote(fn) }, key = fn, hint = stringResource(R.string.txt_footnote_placeholder))
            }
            val endnotes = editor.document.endnotes
            if (endnotes.isNotEmpty()) {
                PaneSection(stringResource(R.string.endnotes))
                endnotes.forEachIndexed { i, en ->
                    NoteCard(com.therealityteam.aeropages.model.Roman.of(i + 1), en.text, { editor.updateEndnote(en, it) }, { editor.deleteEndnote(en) }, key = en, hint = stringResource(R.string.txt_endnote_placeholder))
                }
            }
        }
    }
}

/** A footnote's or endnote's card: its mark in the accent, Delete, and its text in a field. */
@Composable
private fun NoteCard(mark: String, text: String, onEdit: (String) -> Unit, onDelete: () -> Unit, key: Any, hint: String) {
    PaneCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(Aero.tint(LocalAccent.current)), contentAlignment = Alignment.Center) {
                Text(mark, style = AeroType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = LocalAccent.current, maxLines = 1)
            }
            Box(Modifier.weight(1f))
            TitleIconButton("ic_delete", stringResource(R.string.delete), onDelete, size = 32.dp)
        }
        var value by remember(key) { mutableStateOf(text) }
        InputBox(value, { value = it; onEdit(it) }, Modifier.fillMaxWidth(), placeholder = hint, minLines = 2)
    }
}

/** Sources pane (Manage Sources): each source a card — author, title, year — with Insert Citation and Delete. */
@Composable
fun SourcesPane(editor: DocumentEditor, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val sources = editor.document.sources
    Column(
        modifier = modifier.fillMaxSize().paneSurface(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaneHeader(stringResource(R.string.manage_sources), onClose) {
            RibbonSmallButton("ic_plus", stringResource(R.string.new_source), { editor.addSource() })
        }
        if (sources.isEmpty()) {
            PaneEmpty("ic_book", stringResource(R.string.msg_no_sources))
            return@Column
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { s ->
                PaneCard {
                    var author by remember(s) { mutableStateOf(s.author) }
                    var title by remember(s) { mutableStateOf(s.title) }
                    var year by remember(s) { mutableStateOf(s.year) }
                    fun push() = editor.updateSource(s, author, title, year)
                    SourceField(stringResource(R.string.author), author) { author = it; push() }
                    SourceField(stringResource(R.string.source_title), title) { title = it; push() }
                    SourceField(stringResource(R.string.year), year) { year = it; push() }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DialogButton(stringResource(R.string.insert_citation), { editor.insertCitation(s) }, primary = true, small = true)
                        Box(Modifier.weight(1f))
                        TitleIconButton("ic_delete", stringResource(R.string.delete), { editor.deleteSource(s) }, size = 32.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.width(44.dp))
        InputBox(value, onChange, Modifier.weight(1f), placeholder = label, description = label)
    }
}

/**
 * Word's Comments and Reviewing pane: who is reviewing (their initials and name), the comment threads as cards —
 * initials, author, when, the text (Edit in the thread's … menu), replies under it, a reply box on the thread the reader
 * is on, Resolve / Reopen, Go To, Delete — and the tracked changes, by reviewer (accept or reject all of theirs) and one
 * by one.
 */
@Composable
fun CommentsPane(editor: DocumentEditor, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val context = androidx.compose.ui.platform.LocalContext.current
    val comments = editor.document.comments
    val revisions = editor.revisions()

    Column(
        modifier = modifier.fillMaxSize().paneSurface(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaneHeader(stringResource(R.string.comments_and_changes), onClose)
        // The reviewer: stamped on new comments and tracked changes (Word's user name).
        var name by remember { mutableStateOf(editor.reviewer) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Avatar(name.ifBlank { "?" }, 32.dp)
            InputBox(
                name, { name = it; editor.changeReviewer(it); saveReviewer(context, it) }, Modifier.weight(1f),
                placeholder = stringResource(R.string.reviewer), description = stringResource(R.string.reviewer),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val roots = comments.filter { it.parentId == null }
            PaneSection(stringResource(R.string.comment) + if (roots.isNotEmpty()) "  ${roots.size}" else "")
            if (roots.isEmpty()) PaneEmpty("ic_comment", stringResource(R.string.msg_no_comments))
            roots.forEach { comment -> CommentCard(editor, comment) }

            PaneSection(stringResource(R.string.changes) + if (revisions.isNotEmpty()) "  ${revisions.size}" else "")
            if (revisions.isEmpty()) Text(stringResource(R.string.msg_no_changes), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            // One row per reviewer: accept or reject everything they changed.
            editor.revisionAuthors().forEach { author ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(TextStyling.revisionColor(author)))
                    Text(
                        "${author.ifBlank { stringResource(R.string.unknown_author) }} · ${revisions.count { it.author == author }}",
                        style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.weight(1f),
                    )
                    TitleIconButton("x_acceptall", stringResource(R.string.accept), { editor.acceptChangesBy(author) }, enabled = !editor.trackingLocked, size = 32.dp)
                    TitleIconButton("x_rejectall", stringResource(R.string.reject), { editor.rejectChangesBy(author) }, enabled = !editor.trackingLocked, size = 32.dp)
                }
            }
            revisions.forEach { r -> RevisionCard(editor, r) }
        }
    }
}

@Composable
private fun CommentCard(editor: DocumentEditor, comment: Comment) {
    // The comment the reader is on (Previous / Next, its mark in the margin) is framed and takes replies.
    val current = editor.currentComment === comment
    PaneCard(
        modifier = Modifier.alpha(if (comment.resolved && !current) 0.6f else 1f),
        selected = current,
        onClick = { editor.currentComment = comment },
    ) {
        CommentBody(editor, comment, top = true)
        editor.repliesOf(comment).forEach { reply ->
            Column(Modifier.fillMaxWidth().padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { CommentBody(editor, reply, top = false) }
        }
        if (current && !comment.resolved) {
            var text by remember(comment) { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                InputBox(text, { text = it }, Modifier.weight(1f), placeholder = stringResource(R.string.reply), maxLines = 4)
                DialogButton(stringResource(R.string.reply), { if (text.isNotBlank()) { editor.replyComment(comment, text.trim()); text = "" } },
                    primary = text.isNotBlank(), enabled = text.isNotBlank(), small = true)
            }
        }
    }
}

/** One comment of a thread: initials, author and when, its … menu, then its text (a field while it is edited). */
@Composable
private fun CommentBody(editor: DocumentEditor, comment: Comment, top: Boolean) {
    var editing by remember(comment) { mutableStateOf(comment.text.isEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Avatar(comment.author.ifBlank { "?" }, if (top) 28.dp else 24.dp)
        Column(Modifier.weight(1f)) {
            Text(comment.author.ifBlank { stringResource(R.string.unknown_author) }, style = AeroType.command.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = PicoTheme.colorScheme.labelPrimary, maxLines = 1)
            val sub = buildList {
                if (comment.dateMs > 0) add(formatWhen(comment.dateMs))
                if (top && comment.resolved) add(stringResource(R.string.resolved))
            }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
        }
        var menu by remember { mutableStateOf(false) }
        Box {
            TitleIconButton("ic_more", stringResource(R.string.more_actions), { menu = !menu }, selected = menu, size = 32.dp)
            if (menu) AnchoredFlyout(onDismiss = { menu = false }) {
                FlyoutMenu(width = 240.dp) {
                    MenuCommand(stringResource(R.string.edit), { editing = true; menu = false }, icon = "ic_edit")
                    if (top) {
                        MenuCommand(stringResource(if (comment.resolved) R.string.reopen else R.string.resolve), { editor.toggleResolved(comment); menu = false }, icon = "ic_check")
                        MenuCommand(stringResource(R.string.jump_to_paragraph), {
                            val idx = editor.indexOfParagraphId(comment.targetId)
                            if (idx >= 0) editor.focusParagraph(idx)
                            menu = false
                        }, icon = "ic_arrow_right")
                    }
                    MenuDivider()
                    MenuCommand(stringResource(if (top) R.string.delete_thread else R.string.delete), { editor.deleteComment(comment); menu = false }, icon = "ic_delete")
                }
            }
        }
    }
    if (editing) {
        var value by remember(comment) { mutableStateOf(comment.text) }
        InputBox(
            value, { value = it; editor.updateComment(comment, it) }, Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.comment_placeholder), minLines = 2, maxLines = 8,
        )
        if (comment.text.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            DialogButton(stringResource(R.string.done), { editing = false }, small = true)
        }
    } else {
        Text(comment.text, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(start = if (top) 36.dp else 32.dp))
    }
}

@Composable
private fun RevisionCard(editor: DocumentEditor, r: DocumentEditor.Revision) {
    PaneCard(onClick = { editor.focusParagraph(r.paragraphIndex) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(TextStyling.revisionColor(r.author)))
            Column(Modifier.weight(1f)) {
                val kind = when (r.kind) {
                    DocumentEditor.RevKind.INSERT -> stringResource(R.string.rev_inserted)
                    DocumentEditor.RevKind.DELETE -> stringResource(R.string.rev_deleted)
                    DocumentEditor.RevKind.FORMAT -> stringResource(R.string.rev_formatted)
                }
                Text(
                    kind + " · " + r.author.ifBlank { stringResource(R.string.unknown_author) } + (if (r.dateMs > 0) " · " + formatWhen(r.dateMs) else ""),
                    style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1,
                )
                Text(r.text.take(80), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2,
                    textDecoration = if (r.kind == DocumentEditor.RevKind.DELETE) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                // A format revision: what it changed, as Word's balloon says it ("Bold, Not Italic, Font Size: 14").
                if (r.traits.isNotEmpty()) Text(
                    r.traits.map { traitText(it) }.joinToString(", "),
                    style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 2,
                )
            }
            TitleIconButton("ic_accept", stringResource(R.string.accept), { editor.acceptRevision(r) }, enabled = !editor.trackingLocked, size = 32.dp)
            TitleIconButton("ic_reject", stringResource(R.string.reject), { editor.rejectRevision(r) }, enabled = !editor.trackingLocked, size = 32.dp)
        }
    }
}

@Composable
private fun traitText(t: DocumentEditor.Trait): String {
    val name = stringResource(
        when (t.kind) {
            DocumentEditor.TraitKind.BOLD -> R.string.bold
            DocumentEditor.TraitKind.ITALIC -> R.string.italic
            DocumentEditor.TraitKind.UNDERLINE -> R.string.underline
            DocumentEditor.TraitKind.STRIKETHROUGH -> R.string.strikethrough
            DocumentEditor.TraitKind.SUPERSCRIPT -> R.string.superscript
            DocumentEditor.TraitKind.SUBSCRIPT -> R.string.subscript
            DocumentEditor.TraitKind.SMALL_CAPS -> R.string.small_caps
            DocumentEditor.TraitKind.FONT -> R.string.font
            DocumentEditor.TraitKind.SIZE -> R.string.font_size
            DocumentEditor.TraitKind.COLOR -> R.string.font_color
            DocumentEditor.TraitKind.HIGHLIGHT -> R.string.text_highlight_color
            DocumentEditor.TraitKind.SPACING -> R.string.character_spacing
            DocumentEditor.TraitKind.LINK -> R.string.link
        },
    )
    return when {
        t.value != null -> "$name: ${t.value}"
        t.on -> name
        else -> stringResource(R.string.rev_not, name)
    }
}

private fun formatWhen(ms: Long): String = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
