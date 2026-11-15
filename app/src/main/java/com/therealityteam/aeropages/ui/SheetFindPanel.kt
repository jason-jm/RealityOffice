package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.CellHit
import com.therealityteam.aeropages.editor.LookIn
import com.therealityteam.aeropages.editor.SheetEditor
import com.therealityteam.aeropages.editor.SheetFindOptions
import com.therealityteam.aeropages.model.Workbook

/**
 * Excel's Find and Replace, beside the window and modeless as Excel's is: Find (what — `*`, `?` and `~` as Excel's
 * wildcards — within the sheet or the workbook, by rows or by columns, in formulas, values or comments, case, entire
 * cells) with Find All (every cell listed: sheet, cell, what it holds — click to go there), Previous and Next; Replace
 * (in formulas, as Excel replaces) one cell at a time or all at once, in one undo step.
 */
@Composable
internal fun SheetFindPanel(editor: SheetEditor, replace: Boolean, onTab: (Boolean) -> Unit, initialQuery: String = "", onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var query by remember { mutableStateOf(initialQuery) }
    var replacement by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(SheetFindOptions()) }
    var status by remember { mutableStateOf("") }
    var all by remember { mutableStateOf<List<CellHit>?>(null) }
    // Opened with a query (a dev hook's): its cells listed once the file is in.
    androidx.compose.runtime.LaunchedEffect(initialQuery) {
        if (initialQuery.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        all = editor.findCells(initialQuery, SheetFindOptions())
    }
    val notFound = stringResource(R.string.msg_not_found, query)
    fun where(r: Pair<Int, Int>?): String = if (r == null) notFound else Strings.get(R.string.find_hit_of, r.first + 1, r.second)
    fun changed(q: String = query, o: SheetFindOptions = options) { query = q; options = o; status = ""; all = null }
    Box(Modifier.fillMaxSize().paneSurface()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PaneHeader(stringResource(R.string.find_and_replace), onClose)
            DialogTabs(listOf(false, true), replace, { stringResource(if (it) R.string.replace else R.string.find) }, onTab, width = 220.dp)
            Field(stringResource(R.string.find_what), query) { changed(q = it) }
            if (replace) Field(stringResource(R.string.replace_with), replacement) { replacement = it }
            // Within, Search, Look in — Excel's three choices — then case and entire cells.
            Choice(stringResource(R.string.sf_within), listOf(stringResource(R.string.sf_sheet) to false, stringResource(R.string.sf_workbook) to true), options.workbook) { changed(o = options.copy(workbook = it)) }
            Choice(stringResource(R.string.sf_search), listOf(stringResource(R.string.sf_by_rows) to false, stringResource(R.string.sf_by_columns) to true), options.byColumns) { changed(o = options.copy(byColumns = it)) }
            if (!replace) Choice(stringResource(R.string.sf_look_in), listOf(stringResource(R.string.sf_formulas) to LookIn.FORMULAS, stringResource(R.string.sf_values) to LookIn.VALUES,
                stringResource(R.string.sf_comments) to LookIn.COMMENTS), options.lookIn) { changed(o = options.copy(lookIn = it)) }
            Column {
                CheckRow(stringResource(R.string.match_case), options.matchCase) { changed(o = options.copy(matchCase = !options.matchCase)) }
                CheckRow(stringResource(R.string.sf_whole_cell), options.wholeCell) { changed(o = options.copy(wholeCell = !options.wholeCell)) }
            }
            Text(stringResource(R.string.sf_wildcards_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            val any = query.isNotEmpty()
            WrapRow {
                if (replace) {
                    DialogButton(stringResource(R.string.replace), { status = where(editor.replaceStep(query, replacement, options)); all = null }, enabled = any, small = true)
                    DialogButton(stringResource(R.string.replace_all), {
                        val n = editor.replaceAllCells(query, replacement, options)
                        status = if (n > 0) Strings.get(R.string.status_replaced_count, n) else notFound; all = null
                    }, enabled = any, small = true)
                } else {
                    DialogButton(stringResource(R.string.sf_find_all), { all = editor.findCells(query, options); status = "" }, enabled = any, small = true)
                }
                DialogButton(stringResource(R.string.find_previous), { status = where(editor.findStep(query, options, back = true)) }, enabled = any, small = true)
                DialogButton(stringResource(R.string.find_next), { status = where(editor.findStep(query, options)) }, primary = !replace, enabled = any, small = true)
            }
            if (status.isNotEmpty()) Text(status, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            all?.let { hits -> FoundCells(editor, hits, options) }
        }
    }
}

/** Find All's list: how many, then each cell — its sheet, its address, what it holds — click to go there. */
@Composable
private fun FoundCells(editor: SheetEditor, hits: List<CellHit>, o: SheetFindOptions) {
    Text(if (hits.isEmpty()) stringResource(R.string.nav_no_results) else stringResource(R.string.find_hits, hits.size),
        style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
    Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp)).background(Aero.field).verticalScroll(rememberScrollState()).padding(4.dp)) {
        // A long list shows its first 500 (Excel goes on; a headset's list need not).
        hits.take(500).forEach { h ->
            val s = editor.workbook.sheets[h.sheet]
            val cell = s.cell(h.row, h.col)
            val text = when (o.lookIn) { LookIn.COMMENTS -> cell?.comment.orEmpty(); else -> cell?.text.orEmpty() }
            val on = h.sheet == editor.sheetIndex && h.row == editor.selRow && h.col == editor.selCol
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(if (on) PicoTheme.colorScheme.interaction.copy(alpha = 0.14f) else Color.Transparent)
                    .press { editor.showCell(h) }.padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                if (o.workbook) Text(s.name, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.width(72.dp))
                Text(Workbook.colLabel(h.col) + (h.row + 1), style = AeroType.caption, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.width(52.dp))
                Text(text, style = AeroType.caption, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
        InputBox(value, onChange, Modifier.fillMaxWidth(), description = label)
    }
}

/** A labelled choice of a few: Excel's drop-downs (Within, Search, Look in). */
@Composable
private fun <T> Choice(label: String, choices: List<Pair<String, T>>, current: T, onPick: (T) -> Unit) {
    FieldRow(label, labelWidth = 72.dp) {
        val picked = choices.first { it.second == current }
        ComboField(picked.first, choices, { it.first }, picked, { onPick(it.second) }, width = 180.dp)
    }
}
