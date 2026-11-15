package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SheetEditor

/**
 * Excel's Watch Window (Formulas ▸ Formula Auditing): the cells being watched — each with its sheet, its defined name,
 * the cell, the value it shows now and its formula — kept up to date as the workbook changes. Add Watch… watches the
 * selection (or any reference); a tap on a watch picks it, again goes to its cell; Delete Watch removes the picked one.
 */
@Composable
internal fun SheetWatchWindow(editor: SheetEditor, modifier: Modifier, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val rows = editor.watchRows()
    var picked by remember { mutableStateOf<SheetEditor.Watch?>(null) }
    var adding by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Column(modifier.paneSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneHeader(stringResource(R.string.watch_window), onClose)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.add_watch) + "…", { adding = true }, primary = true, small = true)
            DialogButton(stringResource(R.string.delete_watch), { picked?.let { editor.removeWatch(it) }; picked = null },
                enabled = picked != null && rows.any { it.watch.seq == picked?.seq }, small = true)
        }
        if (rows.isEmpty()) {
            PaneEmpty("ic_eye", stringResource(R.string.watch_empty), Modifier.fillMaxWidth().weight(1f))
        } else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in rows) {
                PaneCard(selected = row.watch.seq == picked?.seq, onClick = { if (picked?.seq == row.watch.seq) editor.goToWatch(row.watch) else picked = row.watch }) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${row.sheet}!${row.cell}", style = AeroType.label, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (row.name.isNotEmpty()) Text(row.name, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.weight(1f))
                            Text(row.value, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (row.formula.isNotEmpty()) Text(row.formula, style = AeroType.caption.copy(fontFamily = FontFamily.Monospace),
                            color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (adding) AddWatchDialog(editor) { adding = false }
}

/** Excel's Add Watch: "Select the cells that you would like to watch the value of" — the selection, or any reference. */
@Composable
internal fun AddWatchDialog(editor: SheetEditor, onDismiss: () -> Unit) {
    var reference by remember { mutableStateOf(editor.watchReference()) }
    var invalid by remember { mutableStateOf(false) }
    fun add() { if (editor.addWatches(reference) >= 0) onDismiss() else invalid = true }
    AeroDialog(title = stringResource(R.string.add_watch), onDismiss = onDismiss, width = 380.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.add_watch), { add() }, primary = true, enabled = reference.isNotBlank())
    }) {
        Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogLabel(stringResource(R.string.watch_add_hint))
            InputBox(reference, { reference = it; invalid = false }, Modifier.fillMaxWidth(), description = stringResource(R.string.watch_add_hint), monospace = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }))
            if (invalid) FieldError(stringResource(R.string.go_to_invalid))
        }
    }
}
