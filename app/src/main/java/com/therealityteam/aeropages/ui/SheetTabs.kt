package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SheetEditor

/** Excel's standard colours, the bottom row of its Tab Color menu: dark red round to purple. */
internal val TAB_COLORS: List<Long> = listOf(
    0xFFC00000, 0xFFFF0000, 0xFFFFC000, 0xFFFFFF00, 0xFF92D050,
    0xFF00B050, 0xFF00B0F0, 0xFF0070C0, 0xFF002060, 0xFF7030A0,
)

/**
 * The bottom sheet-tab bar: the sheets that show (hidden ones are left out), each marked in its tab
 * colour as Excel shows it — a bar along the active tab's foot, the other tabs tinted all over. A tap
 * on another tab goes to its sheet; a tap on the active tab (its ▾) opens its commands ([onManage]);
 * "+" adds a sheet.
 */
@Composable
internal fun SheetTabBar(editor: SheetEditor, onManage: () -> Unit, onStatus: (String) -> Unit = {}) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    // Excel's sheet-tab menu: the active tab's ▾, or a press and hold on any tab (its sheet is picked first).
    var menuFor by remember { androidx.compose.runtime.mutableIntStateOf(if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "sheettabmenu") editor.sheetIndex else -1) }
    // `lockedtabmenu`: the workbook's structure protected, then the tab menu — its commands greyed out.
    if (com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "lockedtabmenu") androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000); editor.protectWorkbook(); menuFor = editor.sheetIndex
    }
    var confirmDelete by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    val commands = stringResource(R.string.sheet_commands)
    val accent = LocalAccent.current
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in editor.visibleSheets) {
            val s = editor.workbook.sheets[i]
            val active = i == editor.sheetIndex
            val tab = s.tabColor?.let { Color(it) }
            // Excel's tabs: the active one white, its name in the accent, a bar along its foot (the tab
            // colour, if it has one); the others flat, tinted in their colour.
            Row(
                Modifier.height(28.dp).clip(RoundedCornerShape(Aero.commandRadius))
                    .background(if (active) Color.White else tab?.copy(alpha = 0.25f) ?: Color.Transparent)
                    .drawBehind {
                        if (active) {
                            val h = 2.dp.toPx()
                            drawRect(tab ?: accent, Offset(10.dp.toPx(), size.height - h), Size(size.width - 20.dp.toPx(), h))
                        }
                    }
                    .semantics { selected = active; if (active) contentDescription = "${s.name}, $commands" }
                    .combinedClickable(onLongClick = { editor.selectSheet(i); menuFor = i }) { if (active) menuFor = i else editor.selectSheet(i) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(s.name, style = if (active) AeroType.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else AeroType.caption,
                    maxLines = 1, color = if (active) accent else PicoTheme.colorScheme.labelSecondary)
                if (active) DropCaret(PicoTheme.colorScheme.labelSecondary, width = 7.dp)
                if (menuFor == i) AnchoredFlyout({ menuFor = -1 }, above = true) {
                    SheetTabMenu(editor, i, onManage = onManage, onStatus = onStatus, onDelete = { confirmDelete = i }) { menuFor = -1 }
                }
            }
        }
        TitleIconButton("ic_plus_circle", stringResource(R.string.new_sheet), editor::addSheet, enabled = !editor.structureLocked, size = 28.dp)
    }
    // Deleting a sheet with something on it is asked first, as Excel asks (it can be undone).
    if (confirmDelete >= 0) {
        val name = editor.workbook.sheets.getOrNull(confirmDelete)?.name.orEmpty()
        AeroDialog(title = stringResource(R.string.delete_sheet), onDismiss = { confirmDelete = -1 }, width = 400.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), { confirmDelete = -1 })
                DialogButton(stringResource(R.string.delete), {
                    if (editor.deleteSheet(confirmDelete)) onStatus(Strings.get(R.string.status_sheet_deleted, name)); confirmDelete = -1
                }, danger = true)
            }
        }) {
            Text(stringResource(R.string.sheet_delete_confirm, name), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(400.dp))
        }
    }
}

/**
 * Excel's sheet-tab menu for sheet [index]: Insert (a new sheet), Delete, Rename…, Move Left / Right, Create a Copy,
 * Tab Color…, Hide, and Unhide… while sheets are hidden; Rename, Tab Color and Unhide open the sheet's commands
 * dialog ([onManage]), Delete asks first when the sheet holds anything ([onDelete]).
 */
@Composable
private fun SheetTabMenu(editor: SheetEditor, index: Int, onManage: () -> Unit, onStatus: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    // Protect Workbook (structure): the commands that change the sheets are greyed out, as Excel's are.
    val locked = editor.structureLocked
    fun run(action: () -> Unit): () -> Unit = { onDismiss(); action() }
    val sheet = editor.workbook.sheets[index]
    val visible = editor.visibleSheets
    val pos = visible.indexOf(index)
    FlyoutMenu(width = 240.dp) {
        MenuCommand(stringResource(R.string.new_sheet), run { editor.addSheet() }, icon = "ic_plus_circle", enabled = !locked)
        MenuCommand(stringResource(R.string.delete_sheet), run {
            if (sheet.cells.isEmpty()) { val n = sheet.name; if (editor.deleteSheet(index)) onStatus(Strings.get(R.string.status_sheet_deleted, n)) } else onDelete()
        }, icon = "ic_delete", enabled = visible.size > 1 && !locked)
        MenuCommand(stringResource(R.string.sheet_rename) + "…", run(onManage), icon = "ic_edit", enabled = !locked)
        MenuDivider()
        MenuCommand(stringResource(R.string.sheet_move_left), run { editor.moveSheet(index, -1) }, icon = "ic_chevron_left", enabled = pos > 0 && !locked)
        MenuCommand(stringResource(R.string.sheet_move_right), run { editor.moveSheet(index, 1) }, icon = "ic_chevron_right", enabled = pos in 0 until visible.lastIndex && !locked)
        MenuCommand(stringResource(R.string.sheet_duplicate), run { if (editor.duplicateSheet(index)) onStatus(Strings.get(R.string.status_sheet_duplicated, editor.sheet.name)) }, icon = "ic_copy", enabled = !locked)
        MenuDivider()
        MenuCommand(stringResource(R.string.sheet_tab_color) + "…", run(onManage), icon = "ic_palette", enabled = !locked)
        MenuCommand(stringResource(R.string.sheet_hide), run { val n = sheet.name; if (editor.hideSheet(index)) onStatus(Strings.get(R.string.status_sheet_hidden, n)) },
            icon = "ic_eye_slash", enabled = visible.size > 1 && !locked)
        if (visible.size < editor.workbook.sheets.size) MenuCommand(stringResource(R.string.sheet_unhide) + "…", run(onManage), enabled = !locked)
    }
}

/**
 * The active sheet's commands, as Excel's sheet-tab menu has them: Rename, Move left or right, a copy
 * (Move or Copy → Create a copy, placed right after it), Hide, Delete — asked again, since what refers
 * to the sheet becomes #REF! — its Tab Color, and Unhide for the hidden sheets. A command the sheet
 * refuses (a name Excel does not allow, the last sheet showing) says why in the dialog.
 */
@Composable
internal fun SheetManageDialog(editor: SheetEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val index = editor.sheetIndex
    val sheet = editor.sheet
    var name by remember(sheet) { mutableStateOf(sheet.name) }
    var problem by remember(sheet) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(sheet) { mutableStateOf(false) }
    fun refusal(): String? = when (editor.sheetRefusal) {
        SheetEditor.SheetRefusal.BAD_NAME -> Strings.get(R.string.err_sheet_name_invalid)
        SheetEditor.SheetRefusal.NAME_TAKEN -> Strings.get(R.string.err_sheet_name_taken, name.trim())
        SheetEditor.SheetRefusal.LAST_VISIBLE -> Strings.get(R.string.err_sheet_last_visible)
        SheetEditor.SheetRefusal.STRUCTURE_LOCKED -> Strings.get(R.string.err_structure_locked)
        null -> null
    }
    fun rename() {
        val n = name.trim()
        if (n == sheet.name) return
        if (editor.renameSheet(index, n)) { problem = null; onStatus(Strings.get(R.string.status_sheet_renamed, n)) } else problem = refusal()
    }
    WideDialog(
        title = stringResource(R.string.sheet_commands),
        onDismiss = onDismiss,
        buttons = {
            // Delete stands apart from Close and asks again: what refers to the sheet becomes #REF!.
            if (!confirmDelete) DialogButton(stringResource(R.string.delete), { confirmDelete = true; problem = null })
            DialogButton(stringResource(R.string.close), onDismiss, primary = true)
        },
    ) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Rename: the name as typed; Enter or Rename applies it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sheet_name), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(88.dp))
                InputBox(
                    name, { name = it; problem = null },
                    Modifier.weight(1f).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { rename(); true } else false
                    },
                    description = stringResource(R.string.sheet_name),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { rename() }),
                )
                DialogButton(stringResource(R.string.sheet_rename), ::rename, enabled = name.trim().isNotEmpty() && name.trim() != sheet.name)
            }
            problem?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 100.dp)) {
                    RibbonGlyph("ic_warning", Aero.danger, 16.dp)
                    Text(p, style = AeroType.caption, color = Aero.danger)
                }
            }
            // Arrange: among the tabs shown (greyed at either end), a copy right after it, hidden.
            PaneSection(stringResource(R.string.organize_sheets))
            val shown = editor.visibleSheets
            val at = shown.indexOf(index)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RibbonSmallButton("ic_chevron_left", stringResource(R.string.sheet_move_left), { editor.moveSheet(editor.sheetIndex, -1) }, enabled = at > 0)
                RibbonSmallButton("ic_chevron_right", stringResource(R.string.sheet_move_right), { editor.moveSheet(editor.sheetIndex, 1) }, enabled = at in 0 until shown.lastIndex)
                RibbonSmallButton("ic_copy", stringResource(R.string.sheet_duplicate), {
                    if (editor.duplicateSheet(index)) { onStatus(Strings.get(R.string.status_sheet_duplicated, editor.sheet.name)); onDismiss() }
                })
                RibbonSmallButton("ic_eye_slash", stringResource(R.string.sheet_hide), {
                    val n = sheet.name
                    if (editor.hideSheet(index)) { onStatus(Strings.get(R.string.status_sheet_hidden, n)); onDismiss() } else problem = refusal()
                }, enabled = shown.size > 1)
            }
            // Tab Color: Office's palette, as every colour menu has it.
            PaneSection(stringResource(R.string.sheet_tab_color))
            ColorPalette(sheet.tabColor, { c -> editor.setTabColor(editor.sheetIndex, c) }, noneLabel = stringResource(R.string.sheet_no_color))
            // Unhide: the hidden sheets (a very hidden one only a macro shows, as in Excel).
            val hidden = editor.unhidableSheets
            if (hidden.isNotEmpty()) {
                PaneSection(stringResource(R.string.sheet_hidden_sheets))
                Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    hidden.forEach { h ->
                        val hiddenName = editor.workbook.sheets[h].name
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RibbonGlyph("ic_eye_slash", PicoTheme.colorScheme.labelSecondary, 18.dp)
                            Text(hiddenName, style = AeroType.body, maxLines = 1, color = PicoTheme.colorScheme.labelPrimary,
                                modifier = Modifier.weight(1f).padding(start = 10.dp))
                            RibbonSmallButton("ic_eye", stringResource(R.string.sheet_unhide), {
                                if (editor.unhideSheet(h)) { onStatus(Strings.get(R.string.status_sheet_unhidden, hiddenName)); onDismiss() }
                            })
                        }
                    }
                }
            }
            // Asked again before a delete, with the reason in a warning band.
            if (confirmDelete) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Aero.commandRadius)).background(Aero.danger.copy(alpha = 0.08f)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RibbonGlyph("ic_warning", Aero.danger, 20.dp)
                Text(Strings.get(R.string.sheet_delete_confirm, sheet.name), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
                DialogButton(stringResource(R.string.cancel), { confirmDelete = false })
                DialogButton(stringResource(R.string.delete), {
                    val n = sheet.name
                    if (editor.deleteSheet(index)) { onStatus(Strings.get(R.string.status_sheet_deleted, n)); onDismiss() }
                    else { confirmDelete = false; problem = refusal() }
                }, danger = true)
            }
        }
    }
}
