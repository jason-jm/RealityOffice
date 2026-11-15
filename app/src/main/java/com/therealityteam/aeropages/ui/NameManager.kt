package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SheetEditor

/**
 * Name Manager (Excel's): the workbook's defined names with what each comes to and what it refers to — a range, a
 * constant, a formula —, New / Edit / Delete, and the name being defined or changed below the list (its name, what it
 * refers to, the selection to use). [startNew] opens on a new name for the selection (Define Name).
 */
@Composable
internal fun NameManagerDialog(editor: SheetEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit, startNew: Boolean = false) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val names = editor.workbook.names.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    var selected by remember { mutableStateOf(names.keys.firstOrNull()) }
    // The name being defined or edited: the name before (null for a new one), and the draft's name and reference.
    var editing by remember { mutableStateOf(if (startNew) Triple<String?, String, String>(null, "", "=" + editor.selectionNameRef()) else null) }
    WideDialog(title = stringResource(R.string.title_name_manager), onDismiss = onDismiss, buttons = {
        DialogButton(stringResource(R.string.close), onDismiss, primary = editing == null)
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.name_new_more), { editing = Triple(null, "", "=" + editor.selectionNameRef()) })
            DialogButton(stringResource(R.string.name_edit_more), { selected?.let { n -> editing = Triple(n, n, com.therealityteam.aeropages.editor.FormulaLocale.toLocal("=" + (names[n] ?: ""), editor.symbols)) } }, enabled = selected != null)
            DialogButton(stringResource(R.string.delete), {
                selected?.let { n -> editor.deleteName(n); onStatus(Strings.get(R.string.status_deleted_name, n)); selected = null; if (editing?.first == n) editing = null }
            }, enabled = selected != null)
        }
        // The list: name, value, refers to.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Aero.field)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
                Header(stringResource(R.string.name_col_name), Modifier.weight(1f))
                Header(stringResource(R.string.name_col_value), Modifier.weight(1.2f))
                Header(stringResource(R.string.name_col_refers), Modifier.weight(1.6f))
            }
            Column(Modifier.fillMaxWidth().height(if (editing == null) 220.dp else 132.dp).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                if (names.isEmpty()) PaneEmpty("x_names", stringResource(R.string.msg_no_defined_names))
                names.forEach { (n, ref) ->
                    val on = n == selected
                    ListRow(on, { selected = n }) {
                        Text(n, style = if (on) AeroType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else AeroType.body,
                            maxLines = 1, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
                        Text(editor.nameValueText(n), style = AeroType.caption, maxLines = 1, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.weight(1.2f))
                        Text(com.therealityteam.aeropages.editor.FormulaLocale.toLocal("=$ref", editor.symbols), style = AeroType.caption, maxLines = 1, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.weight(1.6f))
                    }
                }
            }
        }
        editing?.let { (old, name, refers) ->
            val issue = editor.nameIssue(old, name).takeIf { name.isNotBlank() }
            val ok = name.isNotBlank() && issue == null && refers.removePrefix("=").isNotBlank()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FieldRow(stringResource(R.string.name_col_name), labelWidth = 72.dp) {
                    InputBox(name, { editing = Triple(old, it, refers) }, Modifier.width(260.dp))
                    when (issue) {
                        SheetEditor.NameIssue.INVALID -> Problem(stringResource(R.string.err_invalid_name))
                        SheetEditor.NameIssue.TAKEN -> Problem(stringResource(R.string.name_taken))
                        null -> Unit
                    }
                }
                FieldRow(stringResource(R.string.name_col_refers), labelWidth = 72.dp) {
                    InputBox(refers, { editing = Triple(old, name, it) }, Modifier.weight(1f), monospace = true)
                    DialogButton(stringResource(R.string.name_use_selection), { editing = Triple(old, name, "=" + editor.selectionNameRef()) })
                }
                Text(com.therealityteam.aeropages.editor.FormulaLocale.inText(stringResource(R.string.name_refers_hint), editor.symbols), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(start = 84.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    DialogButton(stringResource(R.string.cancel), { editing = null })
                    DialogButton(stringResource(R.string.apply), {
                        if (ok && editor.setName(old, name, com.therealityteam.aeropages.editor.FormulaLocale.toCanonical(refers.trim().let { if (it.startsWith("=")) it else "=$it" }, editor.symbols))) {
                            onStatus(Strings.get(R.string.status_name_defined, name.trim()))
                            selected = name.trim(); editing = null
                        }
                    }, primary = true, enabled = ok)
                }
            }
        }
    }
}

@Composable
private fun Header(text: String, modifier: Modifier) = DialogLabel(text, modifier)

@Composable
private fun Problem(text: String) = FieldError(text, Modifier.widthIn(max = 360.dp))

/** Create from Selection (Excel's): which edges of the selection hold the labels, guessed as Excel guesses them. */
@Composable
internal fun CreateNamesDialog(editor: SheetEditor, onDismiss: () -> Unit, onStatus: (String) -> Unit) {
    val guess = remember { editor.guessNameLabels() }
    var top by remember { mutableStateOf(guess.first) }
    var left by remember { mutableStateOf(guess.second) }
    var bottom by remember { mutableStateOf(false) }
    var right by remember { mutableStateOf(false) }
    val any = top || left || bottom || right
    WideDialog(title = stringResource(R.string.create_from_selection), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.apply), {
                if (any) {
                    val n = editor.createNamesFromSelection(top, left, bottom, right)
                    onStatus(if (n > 0) Strings.get(R.string.status_names_created, n) else Strings.get(R.string.status_no_names_created))
                    onDismiss()
                }
            }, primary = any, modifier = Modifier.alpha(if (any) 1f else 0.45f))
        }
    }) {
        Text(stringResource(R.string.create_names_hint), style = AeroType.body)
        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column {
                CheckRow(stringResource(R.string.create_names_top), top) { top = !top }
                CheckRow(stringResource(R.string.create_names_left), left) { left = !left }
            }
            Column {
                CheckRow(stringResource(R.string.create_names_bottom), bottom) { bottom = !bottom }
                CheckRow(stringResource(R.string.create_names_right), right) { right = !right }
            }
        }
        Box(Modifier.height(4.dp))
    }
}

/** Use in Formula ▾: the workbook's names, each put into the formula being written (or starting one). */
@Composable
internal fun UseNamePanel(editor: SheetEditor, onPick: (String) -> Unit, onManage: () -> Unit) {
    val names = editor.workbook.names.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (names.isEmpty()) Text(stringResource(R.string.msg_no_defined_names), style = AeroType.caption,
            color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.widthIn(max = 460.dp))
        names.take(12).forEach { n -> DialogButton(n, { onPick(n) }) }
        DialogButton(stringResource(R.string.title_name_manager), onManage, primary = true)
    }
}
