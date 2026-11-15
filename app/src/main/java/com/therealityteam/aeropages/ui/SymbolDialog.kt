package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R

/**
 * Insert → Symbol's menu (Word's, Excel's and PowerPoint's): the symbols used last, then common ones, and More
 * Symbols… for the dialog. [onPick] inserts one.
 */
@Composable
internal fun SymbolMenu(onPick: (String) -> Unit, onMore: () -> Unit) {
    val context = LocalContext.current
    val shown = remember { Symbols.menu(Symbols.recent(context)) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        shown.forEach { sym ->
            SymbolCell(sym, selected = false, size = 34) { Symbols.remember(context, sym); onPick(sym) }
        }
        DialogButton(stringResource(R.string.more_symbols), onMore, modifier = Modifier.padding(start = 8.dp))
    }
}

/**
 * Word's Symbol dialog: the subsets down the side, the subset's characters in a grid, a search by name or code, the
 * recently used, and the picked character large with its Unicode name and code. Insert (or tapping the picked
 * character again) puts it in with [onInsert] and keeps the dialog open, as Word's does.
 */
@Composable
internal fun SymbolDialog(onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var subset by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<String?>(null) }
    var recent by remember { mutableStateOf(Symbols.recent(context)) }
    val chars = if (query.isNotBlank()) remember(query) { Symbols.search(query) } else Symbols.SUBSETS[subset].chars
    fun insert(s: String) {
        Symbols.remember(context, s); recent = Symbols.used(recent, s)
        onInsert(s)
    }
    WideDialog(title = stringResource(R.string.symbol), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.close), onDismiss)
            DialogButton(stringResource(R.string.insert), { picked?.let(::insert) }, primary = true, enabled = picked != null)
        }
    }) {
        com.pico.spatial.ui.design.SearchField(value = query, onValueChange = { query = it }, onSearch = { query = it }, modifier = Modifier.width(360.dp),
            placeholder = { Text(stringResource(R.string.symbol_search), maxLines = 1) })
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
            // The subsets, in a list well of their own so the last one visible reads as scrolled, not cut.
            ListBox(Modifier.width(216.dp), height = 292.dp) {
                Symbols.SUBSETS.forEachIndexed { i, s ->
                    val on = query.isBlank() && i == subset
                    ListRow(stringResource(s.label), on, { subset = i; query = "" })
                }
            }
            // The grid.
            ListBox(Modifier.weight(1f), height = 292.dp, empty = if (chars.isEmpty()) stringResource(R.string.symbol_none) else null, emptyIcon = "ic_find") {
                Column(Modifier.padding(2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    chars.chunked(COLUMNS).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { s -> SymbolCell(s, selected = s == picked) { if (s == picked) insert(s) else picked = s } }
                        }
                    }
                }
            }
        }
        // The recently used, and the one picked: large, named, its code.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.symbol_recent))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    if (recent.isEmpty()) Text(stringResource(R.string.symbol_recent_none), style = AeroType.caption,
                        color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(vertical = 6.dp))
                    recent.forEach { s -> SymbolCell(s, selected = s == picked, size = 30) { if (s == picked) insert(s) else picked = s } }
                }
            }
            picked?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)) {
                        Text(s, fontSize = 30.sp, color = Color(0xFF1A1A1A))
                    }
                    Column(Modifier.width(220.dp)) {
                        Text(Symbols.name(s).lowercase().replaceFirstChar { it.uppercase() }.ifEmpty { "—" }, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
                        Text(stringResource(R.string.symbol_code, Symbols.code(s)), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                    }
                }
            }
        }
    }
}

private const val COLUMNS = 14

/** One character of the grid: on white, as it will look in the text; [selected] outlined. */
@Composable
private fun SymbolCell(s: String, selected: Boolean, size: Int = 36, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size.dp).clip(RoundedCornerShape(5.dp))
        .background(if (selected) LocalAccent.current.copy(alpha = 0.18f) else Color.White)
        .border(if (selected) 2.dp else 0.5.dp, if (selected) LocalAccent.current else Color(0x22000000), RoundedCornerShape(5.dp))
        .semantics { contentDescription = Symbols.name(s).ifEmpty { s } }
        .press(onClick = onClick)) {
        Text(s, fontSize = (size * 0.5f).sp, color = Color(0xFF1A1A1A), maxLines = 1)
    }
}
