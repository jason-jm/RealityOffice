package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.SheetProtection

/**
 * Excel's Protect Sheet: "Protect worksheet and contents of locked cells", the password to unprotect it (entered twice,
 * as Excel asks to confirm it), and what all users may still do — Excel's fifteen permissions, the last choices kept.
 */
@Composable
internal fun ProtectSheetDialog(editor: SheetEditor, onDismiss: () -> Unit) {
    var options by remember { mutableStateOf(editor.sheet.protection.copy(passwords = emptyList())) }
    var protect by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    fun ok() {
        if (password.isNotEmpty() && password != again) { mismatch = true; return }
        editor.protectSheet(options, password); onDismiss()
    }
    AeroDialog(title = stringResource(R.string.protect_sheet), onDismiss = onDismiss, width = 440.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), { ok() }, primary = true, enabled = protect)
    }) {
        Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CheckOption(stringResource(R.string.ps_protect_contents), protect, { protect = !protect })
            DialogLabel(stringResource(R.string.ps_password))
            InputBox(password, { password = it; mismatch = false }, Modifier.fillMaxWidth(), description = stringResource(R.string.ps_password), password = true)
            if (password.isNotEmpty()) {
                DialogLabel(stringResource(R.string.pw_reenter))
                InputBox(again, { again = it; mismatch = false }, Modifier.fillMaxWidth(), description = stringResource(R.string.pw_reenter), password = true)
                if (mismatch) FieldError(stringResource(R.string.pw_mismatch))
                else Text(stringResource(R.string.pw_warning), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
            DialogLabel(stringResource(R.string.ps_allow), Modifier.padding(top = 4.dp))
            ListBox(height = 220.dp) {
                @Composable
                fun allow(label: Int, on: Boolean, set: (SheetProtection, Boolean) -> SheetProtection) =
                    CheckOption(stringResource(label), on, { options = set(options, !on) }, Modifier.fillMaxWidth(), small = true)
                allow(R.string.ps_select_locked, options.selectLocked) { o, v -> o.copy(selectLocked = v) }
                allow(R.string.ps_select_unlocked, options.selectUnlocked) { o, v -> o.copy(selectUnlocked = v) }
                allow(R.string.ps_format_cells, options.formatCells) { o, v -> o.copy(formatCells = v) }
                allow(R.string.ps_format_columns, options.formatColumns) { o, v -> o.copy(formatColumns = v) }
                allow(R.string.ps_format_rows, options.formatRows) { o, v -> o.copy(formatRows = v) }
                allow(R.string.ps_insert_columns, options.insertColumns) { o, v -> o.copy(insertColumns = v) }
                allow(R.string.ps_insert_rows, options.insertRows) { o, v -> o.copy(insertRows = v) }
                allow(R.string.ps_insert_hyperlinks, options.insertHyperlinks) { o, v -> o.copy(insertHyperlinks = v) }
                allow(R.string.ps_delete_columns, options.deleteColumns) { o, v -> o.copy(deleteColumns = v) }
                allow(R.string.ps_delete_rows, options.deleteRows) { o, v -> o.copy(deleteRows = v) }
                allow(R.string.ps_sort, options.sort) { o, v -> o.copy(sort = v) }
                allow(R.string.ps_autofilter, options.autoFilter) { o, v -> o.copy(autoFilter = v) }
                allow(R.string.ps_pivot, options.pivotTables) { o, v -> o.copy(pivotTables = v) }
                allow(R.string.ps_edit_objects, options.editObjects) { o, v -> o.copy(editObjects = v) }
                allow(R.string.ps_edit_scenarios, options.editScenarios) { o, v -> o.copy(editScenarios = v) }
            }
        }
    }
}

/** Excel's Protect Workbook: protect the structure, with an optional password (entered twice). */
@Composable
internal fun ProtectWorkbookDialog(editor: SheetEditor, onDismiss: () -> Unit) {
    var structure by remember { mutableStateOf(true) }
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    fun ok() {
        if (password.isNotEmpty() && password != again) { mismatch = true; return }
        editor.protectWorkbook(password); onDismiss()
    }
    AeroDialog(title = stringResource(R.string.protect_workbook), onDismiss = onDismiss, width = 400.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), { ok() }, primary = true, enabled = structure)
    }) {
        Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogLabel(stringResource(R.string.pwb_for))
            CheckOption(stringResource(R.string.pwb_structure), structure, { structure = !structure }, Modifier.padding(start = 12.dp))
            DialogLabel(stringResource(R.string.pw_optional), Modifier.padding(top = 4.dp))
            InputBox(password, { password = it; mismatch = false }, Modifier.fillMaxWidth(), description = stringResource(R.string.pw_optional), password = true)
            if (password.isNotEmpty()) {
                DialogLabel(stringResource(R.string.pw_reenter))
                InputBox(again, { again = it; mismatch = false }, Modifier.fillMaxWidth(), description = stringResource(R.string.pw_reenter), password = true)
                if (mismatch) FieldError(stringResource(R.string.pw_mismatch))
                else Text(stringResource(R.string.pw_warning), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            }
        }
    }
}

/** Unprotect Sheet / Unprotect Workbook when a password is set: [check] says whether it was right (and unprotected). */
@Composable
internal fun UnprotectDialog(title: String, check: (String) -> Boolean, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    fun ok() { if (check(password)) onDismiss() else wrong = true }
    AeroDialog(title = title, onDismiss = onDismiss, width = 380.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), { ok() }, primary = true)
    }) {
        Column(Modifier.width(340.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogLabel(stringResource(R.string.pw_password))
            InputBox(password, { password = it; wrong = false }, Modifier.fillMaxWidth(), description = stringResource(R.string.pw_password), password = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { ok() }))
            if (wrong) FieldError(stringResource(R.string.pw_wrong))
        }
    }
}

/** What Protect Sheet / Protect Workbook's buttons open, one at a time, from the ribbon or the Format menu. */
internal enum class ProtectAsk { NONE, SHEET, UNPROTECT_SHEET, WORKBOOK, UNPROTECT_WORKBOOK }

/** Hosts [ask]'s dialog. */
@Composable
internal fun ProtectDialogs(editor: SheetEditor, ask: ProtectAsk, onDone: () -> Unit) {
    when (ask) {
        ProtectAsk.SHEET -> ProtectSheetDialog(editor, onDone)
        ProtectAsk.UNPROTECT_SHEET -> UnprotectDialog(stringResource(R.string.unprotect_sheet), { editor.unprotectSheet(it) }, onDone)
        ProtectAsk.WORKBOOK -> ProtectWorkbookDialog(editor, onDone)
        ProtectAsk.UNPROTECT_WORKBOOK -> UnprotectDialog(stringResource(R.string.unprotect_workbook), { editor.unprotectWorkbook(it) }, onDone)
        ProtectAsk.NONE -> {}
    }
}

/** What pressing Protect Sheet does: protect (the dialog), unprotect at once without a password, or ask for it. */
internal fun sheetProtectAsk(editor: SheetEditor): ProtectAsk = when {
    !editor.isProtected -> ProtectAsk.SHEET
    editor.sheetPasswordSet -> ProtectAsk.UNPROTECT_SHEET
    else -> { editor.unprotectSheet(); ProtectAsk.NONE }
}

/** What pressing Protect Workbook does, likewise. */
internal fun workbookProtectAsk(editor: SheetEditor): ProtectAsk = when {
    !editor.structureLocked -> ProtectAsk.WORKBOOK
    editor.workbookPasswordSet -> ProtectAsk.UNPROTECT_WORKBOOK
    else -> { editor.unprotectWorkbook(); ProtectAsk.NONE }
}
