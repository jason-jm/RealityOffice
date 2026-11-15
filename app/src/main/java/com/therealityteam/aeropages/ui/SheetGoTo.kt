package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetEditor.Special

/**
 * Excel's Find & Select menu (Home ▸ Editing): Find…, Replace…, Go To…, Go To Special…, then the quick ways to select
 * every formula, comment, conditional format, constant or validated cell of the sheet (or of the selection).
 */
@Composable
internal fun FindSelectMenu(
    editor: SheetEditor,
    onFind: () -> Unit,
    onReplace: () -> Unit,
    onGoTo: () -> Unit,
    onGoSpecial: () -> Unit,
    onStatus: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    fun quick(kind: Special) {
        if (editor.goToSpecial(SheetEditor.GoSpecial(kind)) == 0) onStatus(Strings.get(R.string.gs_none_found))
        onDismiss()
    }
    FlyoutMenu {
        MenuCommand(stringResource(R.string.find) + "…", { onDismiss(); onFind() }, icon = "ic_find", trailing = "Ctrl+F")
        MenuCommand(stringResource(R.string.replace) + "…", { onDismiss(); onReplace() }, icon = "ic_replace", trailing = "Ctrl+H")
        MenuCommand(stringResource(R.string.fs_go_to) + "…", { onDismiss(); onGoTo() }, icon = "ic_arrow_right", trailing = "Ctrl+G")
        MenuCommand(stringResource(R.string.go_special_title) + "…", { onDismiss(); onGoSpecial() }, icon = "x_target")
        MenuDivider()
        MenuCommand(stringResource(R.string.gs_formulas), { quick(Special.FORMULAS) }, icon = "x_fx")
        MenuCommand(stringResource(R.string.gs_comments), { quick(Special.COMMENTS) }, icon = "ic_comment")
        MenuCommand(stringResource(R.string.gs_cond), { quick(Special.CONDITIONAL_FORMATS) }, icon = "x_condfmt")
        MenuCommand(stringResource(R.string.gs_constants), { quick(Special.CONSTANTS) }, icon = "123")
        MenuCommand(stringResource(R.string.gs_validation), { quick(Special.DATA_VALIDATION) }, icon = "x_validate")
    }
}

/**
 * Excel's Go To (F5, Ctrl+G): the defined names and the references gone to before; a tap puts one in Reference, a
 * tap on the one already there goes. Special… opens Go To Special.
 */
@Composable
internal fun GoToDialog(editor: SheetEditor, onSpecial: () -> Unit, onDismiss: () -> Unit) {
    var reference by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val entries = remember(editor.revision) { editor.workbook.names.keys.sortedWith(String.CASE_INSENSITIVE_ORDER) + editor.goToRecent }
    fun go() { if (editor.goToReference(reference)) onDismiss() else invalid = true }
    AeroDialog(title = stringResource(R.string.go_to_title), onDismiss = onDismiss, width = 400.dp, buttons = {
        DialogButton(stringResource(R.string.go_special_button) + "…", { onDismiss(); onSpecial() })
        Spacer(Modifier.weight(1f))
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), { go() }, primary = true, enabled = reference.isNotBlank())
    }) {
        Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogLabel(stringResource(R.string.go_to_list))
            ListBox(height = 180.dp, empty = if (entries.isEmpty()) stringResource(R.string.go_to_empty) else null, emptyIcon = "x_target") {
                entries.forEach { e ->
                    ListRow(e, reference == e, { if (reference == e) go() else { reference = e; invalid = false } }, Modifier.fillMaxWidth())
                }
            }
            DialogLabel(stringResource(R.string.go_to_reference))
            InputBox(reference, { reference = it; invalid = false }, Modifier.fillMaxWidth(), placeholder = "A1",
                description = stringResource(R.string.go_to_reference), monospace = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() }))
            if (invalid) FieldError(stringResource(R.string.go_to_invalid))
        }
    }
}

/**
 * Excel's Go To Special: what to select — comments, constants or formulas (of which kinds of value), blanks, the
 * current region, row or column differences, precedents or dependents (direct or all levels), the last cell, the
 * visible cells, conditional formats or data validation (all, or the same as the active cell's). OK selects them
 * as areas; when there are none it says so and stays open.
 */
@Composable
internal fun GoToSpecialDialog(editor: SheetEditor, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(Special.COMMENTS) }
    var numbers by remember { mutableStateOf(true) }
    var text by remember { mutableStateOf(true) }
    var logicals by remember { mutableStateOf(true) }
    var errors by remember { mutableStateOf(true) }
    var allLevels by remember { mutableStateOf(false) }
    var same by remember { mutableStateOf(false) }
    var none by remember { mutableStateOf(false) }
    @Composable
    fun option(k: Special, label: Int) = RadioOption(stringResource(label), kind == k, { kind = k; none = false }, Modifier.fillMaxWidth())
    val types = kind == Special.CONSTANTS || kind == Special.FORMULAS
    val levels = kind == Special.PRECEDENTS || kind == Special.DEPENDENTS
    val rules = kind == Special.CONDITIONAL_FORMATS || kind == Special.DATA_VALIDATION
    AeroDialog(title = stringResource(R.string.go_special_title), onDismiss = onDismiss, width = 540.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), {
            val n = editor.goToSpecial(SheetEditor.GoSpecial(kind, numbers, text, logicals, errors, allLevels, same))
            if (n == 0) none = true else onDismiss()
        }, primary = true, enabled = !types || numbers || text || logicals || errors)
    }) {
        Column(Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogLabel(stringResource(R.string.gs_select))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    option(Special.COMMENTS, R.string.gs_comments)
                    option(Special.CONSTANTS, R.string.gs_constants)
                    option(Special.FORMULAS, R.string.gs_formulas)
                    Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        CheckOption(stringResource(R.string.gs_numbers), numbers, { numbers = !numbers; none = false }, enabled = types, small = true)
                        CheckOption(stringResource(R.string.gs_text), text, { text = !text; none = false }, enabled = types, small = true)
                        CheckOption(stringResource(R.string.gs_logicals), logicals, { logicals = !logicals; none = false }, enabled = types, small = true)
                        CheckOption(stringResource(R.string.gs_errors), errors, { errors = !errors; none = false }, enabled = types, small = true)
                    }
                    option(Special.BLANKS, R.string.gs_blanks)
                    option(Special.CURRENT_REGION, R.string.gs_current_region)
                    option(Special.LAST_CELL, R.string.gs_last_cell)
                    option(Special.VISIBLE_CELLS, R.string.gs_visible)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    option(Special.ROW_DIFFERENCES, R.string.gs_row_diff)
                    option(Special.COLUMN_DIFFERENCES, R.string.gs_col_diff)
                    option(Special.PRECEDENTS, R.string.gs_precedents)
                    option(Special.DEPENDENTS, R.string.gs_dependents)
                    Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RadioOption(stringResource(R.string.gs_direct), !allLevels, { allLevels = false; none = false }, enabled = levels)
                        RadioOption(stringResource(R.string.gs_all_levels), allLevels, { allLevels = true; none = false }, enabled = levels)
                    }
                    option(Special.CONDITIONAL_FORMATS, R.string.gs_cond)
                    option(Special.DATA_VALIDATION, R.string.gs_validation)
                    Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RadioOption(stringResource(R.string.gs_all), !same, { same = false; none = false }, enabled = rules)
                        RadioOption(stringResource(R.string.gs_same), same, { same = true; none = false }, enabled = rules)
                    }
                }
            }
            if (none) Row(verticalAlignment = Alignment.CenterVertically) { FieldError(stringResource(R.string.gs_none_found)) }
            else Text(" ", style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}

/**
 * Excel's Evaluate Formula (Formulas ▸ Formula Auditing): the active cell's formula worked out a step at a time —
 * the part the next step works out underlined, what the last step put in italics; Evaluate takes the step, Restart
 * starts again once the result is reached.
 */
@Composable
internal fun EvaluateFormulaDialog(editor: SheetEditor, onDismiss: () -> Unit) {
    val first = remember { editor.evaluationStart() }
    var step by remember { mutableStateOf(first) }
    val reference = remember {
        "${editor.sheet.name}!" + com.therealityteam.aeropages.model.A1.rangeRef(editor.selRow, editor.selCol, editor.selRow, editor.selCol)
    }
    val accent = LocalAccent.current
    val done = step?.next == null
    AeroDialog(title = stringResource(R.string.evaluate_formula), onDismiss = onDismiss, width = 560.dp, buttons = {
        if (first != null) DialogButton(stringResource(if (done) R.string.ef_restart else R.string.ef_evaluate),
            { step = if (done) first else step?.let { editor.evaluationNext(it) } }, primary = true)
        DialogButton(stringResource(R.string.close), onDismiss)
    }) {
        Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val s = step
            if (s == null) { FieldError(stringResource(R.string.ef_no_formula)); return@Column }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogLabel(stringResource(R.string.go_to_reference))
                Text(reference, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
            }
            DialogLabel(stringResource(R.string.ef_evaluation))
            val shown = androidx.compose.ui.text.buildAnnotatedString {
                append("=" + s.text)
                s.last?.let { addStyle(androidx.compose.ui.text.SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), it.first + 1, it.last + 2) }
                s.next?.let {
                    addStyle(androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        color = accent, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), it.first + 1, it.last + 2)
                }
            }
            ListBox(height = 120.dp) {
                Text(shown, style = AeroType.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.padding(12.dp))
            }
            Text(stringResource(R.string.ef_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}
