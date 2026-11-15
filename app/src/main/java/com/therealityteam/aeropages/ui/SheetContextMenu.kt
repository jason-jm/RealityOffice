package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SheetEditor

/** What a press-and-hold (Excel's right-click) on the grid asked about: the cells, a row header or a column header. */
internal enum class GridMenuKind { CELLS, ROWS, COLUMNS }

/** The dialogs and panes the grid's context menu opens: they live in the app around the grid. */
internal class GridMenuActions(
    val onPasteSpecial: () -> Unit = {},
    val onComment: () -> Unit = {},
    val onCellFormat: () -> Unit = {},
    val onLink: () -> Unit = {},
    val onDefineName: () -> Unit = {},
    val onFilter: () -> Unit = {},
    val onCustomSort: () -> Unit = {},
    val onCellSize: (rows: Boolean) -> Unit = {},
    val onStatus: (String) -> Unit = {},
    val onGoTo: () -> Unit = {},
)

/**
 * Excel's context menu for [kind], as a flyout under what was pressed: the clipboard (Cut, Copy, Paste, Paste
 * Special…), inserting and deleting, Clear Contents, then — for cells — sorting, the filter, the comment, Format
 * Cells…, Define Name… and Link…, or — for rows and columns — Format Cells…, Row Height… / Column Width…, Hide and
 * Unhide. Each item closes the menu and does what the ribbon's same command does; [onPickList] opens the active cell's
 * list of its column's entries (Pick From Drop-down List).
 */
@Composable
internal fun SheetContextMenu(editor: SheetEditor, kind: GridMenuKind, clipboard: TextClipboard?, actions: GridMenuActions, onDismiss: () -> Unit, onPickList: () -> Unit = {}) {
    fun run(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    // Excel's Sort and Filter submenus open in the menu's place (1, 2), a back arrow on top.
    var page by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(when (devDialogProp()) { "cellmenusort" -> 1; "cellmenufilter" -> 2; else -> 0 }) }
    if (page != 0) {
        FlyoutMenu(width = 300.dp, maxHeight = 520.dp) {
            androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                TitleIconButton("ic_chevron_left", stringResource(R.string.back), { page = 0 }, size = 32.dp)
                com.pico.spatial.ui.design.Text(stringResource(if (page == 1) R.string.sort_dialog else R.string.filter), style = AeroType.label,
                    color = com.pico.spatial.ui.design.PicoTheme.colorScheme.labelPrimary)
            }
            MenuDivider()
            val format = editor.sheet.cell(editor.selRow, editor.selCol)?.format
            if (page == 1) {
                MenuCommand(stringResource(R.string.sort_ascending), run {
                    actions.onStatus(Strings.get(if (editor.sortSelection(true)) R.string.status_sorted_asc else R.string.sort_no_data))
                }, icon = "x_sortasc")
                MenuCommand(stringResource(R.string.sort_descending), run {
                    actions.onStatus(Strings.get(if (editor.sortSelection(false)) R.string.status_sorted_desc else R.string.sort_no_data))
                }, icon = "x_sortdesc")
                MenuCommand(stringResource(R.string.put_cell_color_top), run { if (!editor.putActiveColorOnTop(font = false)) actions.onStatus(Strings.get(R.string.sort_no_data)) },
                    icon = "ic_fill")
                MenuCommand(stringResource(R.string.put_font_color_top), run { if (!editor.putActiveColorOnTop(font = true)) actions.onStatus(Strings.get(R.string.sort_no_data)) },
                    icon = "ic_font_color")
                MenuDivider()
                MenuCommand(stringResource(R.string.custom_sort) + "…", run { actions.onCustomSort() }, icon = "x_sortmulti")
            } else {
                val af = editor.autoFilter
                val column = af?.let { editor.display(it.r1, editor.selCol) }?.ifBlank { null } ?: com.therealityteam.aeropages.model.Workbook.colLabel(editor.selCol)
                MenuCommand(stringResource(R.string.reapply_filter), run { editor.reapplyFilter() }, enabled = af != null && af.columns.isNotEmpty())
                MenuCommand(stringResource(R.string.clear_filter_from, column), run { editor.setColumnFilter(editor.selCol, null) }, icon = "ic_filter_clear",
                    enabled = editor.columnFilter(editor.selCol) != null)
                MenuDivider()
                MenuCommand(stringResource(R.string.filter_by_selected_value), run { editor.filterByActiveCell() }, icon = "x_filter")
                MenuCommand(stringResource(R.string.filter_by_selected_color), run { editor.filterByActiveCellColor(font = false) }, icon = "ic_fill",
                    enabled = format?.highlight != null)
                MenuCommand(stringResource(R.string.filter_by_selected_font), run { editor.filterByActiveCellColor(font = true) }, icon = "ic_font_color",
                    enabled = format?.color != null)
                MenuDivider()
                MenuCommand(stringResource(R.string.filter), run { actions.onFilter() }, icon = "x_filter", checked = af != null)
            }
        }
        return
    }
    FlyoutMenu(width = 264.dp, maxHeight = 520.dp) {
        // The clipboard as one row of tiles (Office's paste options row), then the rest as a list.
        androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
            MenuTile("✂", stringResource(R.string.cut), run { cutCells(editor, clipboard); actions.onStatus(Strings.get(R.string.status_cut)) })
            MenuTile("⧉", stringResource(R.string.copy), run { copyCells(editor, clipboard); actions.onStatus(Strings.get(R.string.status_copied)) })
            MenuTile("📋", stringResource(R.string.paste), run { pasteCells(editor, clipboard) })
            MenuTile("123", stringResource(R.string.paste_values), run { editor.pasteSpecial(SheetEditor.PasteWhat.VALUES) }, enabled = editor.canPaste)
        }
        MenuCommand(stringResource(R.string.paste_special), run { if (editor.canPaste) actions.onPasteSpecial() else actions.onStatus(Strings.get(R.string.status_copy_cells_first)) },
            enabled = editor.canPaste)
        MenuDivider()
        when (kind) {
            GridMenuKind.CELLS -> {
                MenuCommand(stringResource(R.string.insert_row_above), run { editor.insertRowAt() }, icon = "x_insrow")
                MenuCommand(stringResource(R.string.insert_col_left), run { editor.insertColAt() }, icon = "x_inscol")
                MenuCommand(stringResource(R.string.delete_row), run { editor.deleteRowAt() }, icon = "ic_delete_row")
                MenuCommand(stringResource(R.string.delete_column), run { editor.deleteColAt() }, icon = "ic_delete_col")
            }
            GridMenuKind.ROWS -> {
                MenuCommand(stringResource(R.string.insert_row_above), run { editor.insertRowAt() }, icon = "x_insrow")
                MenuCommand(stringResource(R.string.delete_row), run { editor.deleteRowAt() }, icon = "ic_delete_row")
            }
            GridMenuKind.COLUMNS -> {
                MenuCommand(stringResource(R.string.insert_col_left), run { editor.insertColAt() }, icon = "x_inscol")
                MenuCommand(stringResource(R.string.delete_column), run { editor.deleteColAt() }, icon = "ic_delete_col")
            }
        }
        MenuCommand(stringResource(R.string.clear_contents), run { editor.clearSelection() }, icon = "⌫")
        MenuDivider()
        when (kind) {
            GridMenuKind.CELLS -> {
                // Excel's two submenus, each opening in the menu's place.
                MenuCommand(stringResource(R.string.sort_dialog), { page = 1 }, icon = "x_sortasc", trailing = "›")
                MenuCommand(stringResource(R.string.filter), { page = 2 }, icon = "x_filter", trailing = "›", checked = editor.autoFilter != null)
                MenuDivider()
                // Excel's Pick From Drop-down List: the column's entries around the cell, to pick one.
                MenuCommand(stringResource(R.string.pick_from_list), run(onPickList), icon = "ic_list", enabled = !editor.pickChoices().isNullOrEmpty())
                MenuCommand(stringResource(if (editor.activeHasComment) R.string.edit_comment else R.string.new_comment), run { actions.onComment() }, icon = "ic_comment")
                if (editor.activeHasComment) MenuCommand(stringResource(R.string.delete_comment), run {
                    editor.setComment(""); actions.onStatus(Strings.get(R.string.status_comment_deleted))
                }, icon = "ic_comment_delete")
                MenuCommand(stringResource(R.string.format_cells), run { actions.onCellFormat() }, icon = "x_cellsize")
                MenuCommand(stringResource(R.string.define_name), run { actions.onDefineName() }, icon = "x_names")
                MenuCommand(stringResource(R.string.link), run { actions.onLink() }, icon = "ic_link")
            }
            GridMenuKind.ROWS -> {
                MenuCommand(stringResource(R.string.format_cells), run { actions.onCellFormat() }, icon = "x_cellsize")
                MenuCommand(stringResource(R.string.row_height_more), run { actions.onCellSize(true) })
                MenuCommand(stringResource(R.string.hide_rows), run {
                    actions.onStatus(Strings.get(if (editor.hideRows()) R.string.status_rows_hidden else R.string.err_hide_rows))
                }, icon = "ic_eye_slash")
                if (editor.sheet.manualHiddenRows.isNotEmpty()) MenuCommand(stringResource(R.string.unhide_rows), run {
                    actions.onStatus(Strings.get(if (editor.unhideRows()) R.string.status_rows_unhidden else R.string.err_unhide_rows))
                })
            }
            GridMenuKind.COLUMNS -> {
                MenuCommand(stringResource(R.string.format_cells), run { actions.onCellFormat() }, icon = "x_cellsize")
                MenuCommand(stringResource(R.string.col_width_more), run { actions.onCellSize(false) })
                MenuCommand(stringResource(R.string.hide_columns), run {
                    actions.onStatus(Strings.get(if (editor.hideColumns()) R.string.status_cols_hidden else R.string.err_hide_cols))
                }, icon = "ic_eye_slash")
                if (editor.sheet.hiddenCols.isNotEmpty()) MenuCommand(stringResource(R.string.unhide_columns), run {
                    actions.onStatus(Strings.get(if (editor.unhideColumns()) R.string.status_cols_unhidden else R.string.err_unhide_cols))
                })
            }
        }
    }
}

/** One of the context menu's clipboard tiles: the icon over its name, 60 dp wide; faint and inert when not [enabled]. */
@Composable
private fun MenuTile(icon: String, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    androidx.compose.foundation.layout.Column(
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .alpha(if (enabled) 1f else 0.4f).press(enabled = enabled, onClick = onClick).padding(vertical = 8.dp),
    ) {
        RibbonGlyph(icon, com.pico.spatial.ui.design.PicoTheme.colorScheme.labelPrimary, 20.dp)
        com.pico.spatial.ui.design.Text(label, style = AeroType.caption, color = com.pico.spatial.ui.design.PicoTheme.colorScheme.labelPrimary,
            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * Excel's Paste Options menu, under the button by what was just pasted: Paste (all of it, the formulas, transposed),
 * Paste Values (the values, with their number formats too), Other Paste Options (the formatting, a link). [current]
 * — how the paste went — is ticked; [onPick] pastes the same cells again that way.
 */
@Composable
internal fun PasteOptionsMenu(current: SheetEditor.PasteKind, onPick: (SheetEditor.PasteKind) -> Unit) {
    FlyoutMenu(width = 264.dp) {
        @Composable
        fun item(kind: SheetEditor.PasteKind, label: Int, icon: String) =
            MenuCommand(stringResource(label), { onPick(kind) }, icon = icon, checked = current == kind)
        MenuHeading(stringResource(R.string.paste))
        item(SheetEditor.PasteKind.ALL, R.string.paste, "📋")
        item(SheetEditor.PasteKind.FORMULAS, R.string.paste_what_formulas, "fx")
        item(SheetEditor.PasteKind.TRANSPOSE, R.string.paste_transpose, "ic_switch_row_col")
        MenuHeading(stringResource(R.string.paste_values))
        item(SheetEditor.PasteKind.VALUES, R.string.paste_what_values, "123")
        item(SheetEditor.PasteKind.VALUES_AND_NUMBER_FORMATS, R.string.paste_what_values_formats, "%")
        MenuHeading(stringResource(R.string.paste_other_options))
        item(SheetEditor.PasteKind.FORMATS, R.string.paste_what_formats, "ic_format_painter")
        item(SheetEditor.PasteKind.LINK, R.string.paste_link, "ic_link")
    }
}
