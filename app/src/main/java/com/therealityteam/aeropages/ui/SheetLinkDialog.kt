package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.model.CellLinks

/** A place in a workbook as the link dialog edits it: a sheet and a reference on it, or a defined name. */
private data class Place(val sheet: String, val ref: String, val name: String?)

/** [place] (`Sheet2!A1`, `'Q3 Sales'!B2`, `Total`, `A1`) taken apart; [ownSheet] for one naming no sheet. */
private fun placeOf(place: String, ownSheet: String, names: Collection<String>): Place {
    names.firstOrNull { it.equals(place, ignoreCase = true) }?.let { return Place(ownSheet, "A1", it) }
    val bang = place.lastIndexOf('!')
    if (bang < 0) return Place(ownSheet, place, null)
    val sheet = place.substring(0, bang).let { s -> if (s.length >= 2 && s.startsWith("'") && s.endsWith("'")) s.substring(1, s.length - 1).replace("''", "'") else s }
    return Place(sheet, place.substring(bang + 1), null)
}

/**
 * Excel's Insert Hyperlink for a cell: a web address, or a place in this workbook — a cell or range
 * on one of its sheets, or a defined name (`#Sheet2!A1`, `#Total`). With a link already on the cell it
 * changes or removes it. [onApplied] gets the new link.
 */
@Composable
internal fun SheetLinkDialog(editor: SheetEditor, onDismiss: () -> Unit, onApplied: (String) -> Unit, startInWorkbook: Boolean = false) {
    val current = remember { editor.sheet.cell(editor.selRow, editor.selCol)?.link }
    val currentPlace = CellLinks.place(current)
    val names = editor.workbook.names.keys.sortedBy { it.lowercase() }
    val sheets = editor.workbook.sheets.filter { !it.hidden }.map { it.name }
    val initial = remember { currentPlace?.let { placeOf(it, editor.sheet.name, names) } }
    var inWorkbook by remember { mutableStateOf(startInWorkbook || currentPlace != null) }
    var url by remember { mutableStateOf(current?.takeIf { currentPlace == null } ?: "https://") }
    var sheetName by remember { mutableStateOf(initial?.sheet ?: editor.sheet.name) }
    var ref by remember { mutableStateOf(initial?.ref ?: "A1") }
    var name by remember { mutableStateOf(initial?.name) }
    var bad by remember { mutableStateOf(false) }
    AeroDialog(title = stringResource(if (current != null) R.string.link_edit else R.string.title_insert_hyperlink), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null) DialogButton(stringResource(R.string.remove_link), { editor.setLink(null); onDismiss() })
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.apply), {
                    if (!inWorkbook) {
                        url.trim().takeIf { it.isNotEmpty() && it != "https://" }?.let(onApplied)
                    } else {
                        val n = name
                        val r = ref.trim().uppercase()
                        when {
                            n != null -> onApplied("#$n")
                            A1.parseTarget(r)?.takeIf { it.sheet == null } != null -> onApplied(CellLinks.toPlace(sheetName, r))
                            else -> bad = true
                        }
                    }
                }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(460.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceSegments(listOf(false, true), inWorkbook, { stringResource(if (it) R.string.link_this_workbook else R.string.link_web) }, { inWorkbook = it }, width = 440.dp)
                }
                if (!inWorkbook) {
                    InputBox(url, { url = it }, Modifier.fillMaxWidth(), placeholder = "https://")
                } else {
                    Text(stringResource(R.string.link_cell_reference), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
                    InputBox(ref, { ref = it; name = null; bad = false }, Modifier.fillMaxWidth(), placeholder = "A1")
                    if (bad) Text(stringResource(R.string.link_bad_place), style = AeroType.caption, color = PicoTheme.colorScheme.error)
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    ) {
                        PlaceGroup(stringResource(R.string.link_sheets))
                        sheets.forEach { s -> PlaceRow(s, 0, name == null && s == sheetName) { sheetName = s; name = null } }
                        if (names.isNotEmpty()) PlaceGroup(stringResource(R.string.link_defined_names))
                        names.forEach { n -> PlaceRow(n, 0, name == n) { name = n; bad = false } }
                    }
                }
            }
        }
}
