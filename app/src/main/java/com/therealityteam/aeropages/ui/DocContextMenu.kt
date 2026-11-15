package com.therealityteam.aeropages.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor

/**
 * Word's context menu for selected text (the selection bar's More): Font…, Paragraph…, Search for the selection,
 * Link…, New Comment — and in a table, inserting and deleting its rows and columns. The clipboard and bold / italic / underline are on the bar itself, as Word's mini toolbar has
 * them. [onFont], [onParagraph] and [onLink] open their dialogs.
 */
@Composable
internal fun TextContextMenu(editor: DocumentEditor, onFont: () -> Unit, onParagraph: () -> Unit, onLink: () -> Unit, onDismiss: () -> Unit) {
    fun run(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    val selected = editor.selectedText().trim().replace(Regex("\\s+"), " ")
    FlyoutMenu(width = 264.dp) {
        MenuCommand(stringResource(R.string.font) + "…", run(onFont), icon = "Aa")
        MenuCommand(stringResource(R.string.paragraph) + "…", run(onParagraph), icon = "↕")
        MenuDivider()
        // Word's Search "…": Find with the selection, its first words in the label.
        if (selected.isNotEmpty()) MenuCommand(stringResource(R.string.search) + " " + stringResource(R.string.search_quoted, selected.take(18) + if (selected.length > 18) "…" else ""),
            run { editor.setSearch(selected); editor.find(); editor.requestFindPanel(0) }, icon = "ic_find")
        MenuCommand(stringResource(R.string.link), run(onLink), icon = "ic_link")
        MenuCommand(stringResource(R.string.new_comment), run { editor.addComment(""); editor.requestCommentsPane() }, icon = "ic_comment_add")
        // On tracked changes, Word's Accept / Reject for them: named by their kind when they are all of one kind.
        val changes = editor.selectionChanges()
        if (changes.isNotEmpty()) {
            MenuDivider()
            val kind = changes.map { it.kind }.distinct().singleOrNull()
            val (acceptLabel, rejectLabel) = when (kind) {
                DocumentEditor.RevKind.INSERT -> R.string.accept_insertion to R.string.reject_insertion
                DocumentEditor.RevKind.DELETE -> R.string.accept_deletion to R.string.reject_deletion
                DocumentEditor.RevKind.FORMAT -> R.string.accept_format_change to R.string.reject_format_change
                null -> R.string.accept_change to R.string.reject_change
            }
            val unlocked = !editor.trackingLocked
            MenuCommand(stringResource(acceptLabel), run { editor.resolveSelection(accept = true) }, icon = "ic_accept", enabled = unlocked)
            MenuCommand(stringResource(rejectLabel), run { editor.resolveSelection(accept = false) }, icon = "ic_reject", enabled = unlocked)
        }
        // In a table, Word's Insert and Delete for its rows and columns.
        if (editor.activeInTable) {
            MenuDivider()
            MenuCommand(stringResource(R.string.insert_row_above), run { editor.insertTableRow(above = true) }, icon = "ic_insert_row_above")
            MenuCommand(stringResource(R.string.insert_row_below), run { editor.insertTableRow(above = false) }, icon = "ic_insert_row_below")
            MenuCommand(stringResource(R.string.insert_column_left), run { editor.insertTableColumn(left = true) }, icon = "ic_insert_col_left")
            MenuCommand(stringResource(R.string.insert_column_right), run { editor.insertTableColumn(left = false) }, icon = "ic_insert_col_right")
            MenuCommand(stringResource(R.string.delete_row), run { editor.deleteTableRow() }, icon = "ic_delete_row")
            MenuCommand(stringResource(R.string.delete_column), run { editor.deleteTableColumn() }, icon = "ic_delete_col")
            MenuCommand(stringResource(R.string.delete_table), run { editor.deleteTable() }, icon = "ic_delete")
        }
    }
}

/**
 * Word's Paste Options for text pasted in Pages: Keep Source Formatting (as copied), Merge Formatting (the formatting
 * where it landed, keeping bold, italic, underline and links) and Keep Text Only — [current] ticked; [onPick] pastes
 * the same text again that way.
 */
@Composable
internal fun DocPasteOptionsMenu(current: DocumentEditor.PasteMode, onPick: (DocumentEditor.PasteMode) -> Unit) {
    FlyoutMenu(width = 280.dp) {
        @Composable
        fun item(mode: DocumentEditor.PasteMode, label: Int, icon: String) =
            MenuCommand(stringResource(label), { onPick(mode) }, icon = icon, checked = current == mode)
        item(DocumentEditor.PasteMode.KEEP_SOURCE, R.string.paste_keep_source, "📋")
        item(DocumentEditor.PasteMode.MERGE, R.string.paste_merge_formatting, "ic_format_painter")
        item(DocumentEditor.PasteMode.TEXT_ONLY, R.string.paste_text_only, "Aa")
    }
}
