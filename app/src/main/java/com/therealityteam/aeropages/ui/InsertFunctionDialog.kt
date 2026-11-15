package com.therealityteam.aeropages.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.FormulaFunctions

/**
 * Excel's Insert Function: find a function by name or by what it does, or browse a category; its
 * signature and description show below the list; Insert starts it in the active cell. [category]
 * opens on a category (a Function Library button); null opens on the most used functions.
 */
@Composable
internal fun InsertFunctionDialog(category: FunctionCategory?, onDismiss: () -> Unit, onInsert: (FormulaFunctions.Signature) -> Unit) {
    var query by remember { mutableStateOf("") }
    // null + common: Most used; null + !common: All.
    var cat by remember { mutableStateOf(category) }
    var common by remember { mutableStateOf(category == null) }
    val found = remember(query, cat, common) { FunctionCategory.find(cat, common, query) { Strings.get(it) } }
    var picked by remember { mutableStateOf<FormulaFunctions.Signature?>(null) }
    LaunchedEffect(found) { if (picked !in found) picked = found.firstOrNull() }
    val listState = rememberLazyListState()
    LaunchedEffect(cat, common) { listState.scrollToItem(0) }

    WideDialog(title = stringResource(R.string.insert_function), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.fn_insert), { picked?.let(onInsert) }, primary = true, enabled = picked != null)
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            com.pico.spatial.ui.design.SearchField(value = query, onValueChange = { query = it }, onSearch = { query = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.fn_search_hint), maxLines = 1) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // Categories down the side: Most used, All, then Excel's.
                Column(Modifier.width(190.dp).height(320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CategoryRow(stringResource(R.string.fn_cat_common), common && cat == null) { common = true; cat = null }
                    CategoryRow(stringResource(R.string.fn_cat_all), !common && cat == null) { common = false; cat = null }
                    FunctionCategory.entries.forEach { c -> CategoryRow(stringResource(c.label), cat == c) { common = false; cat = c } }
                }
                Column(
                    Modifier.weight(1f).height(320.dp).clip(RoundedCornerShape(10.dp)).background(Aero.field),
                ) {
                    if (found.isEmpty()) {
                        Text(stringResource(R.string.fn_none_found), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(14.dp))
                    } else LazyColumn(state = listState) {
                        items(found, key = { it.name }) { sig ->
                            FunctionRow(sig, sig == picked, onClick = { picked = sig }, onDouble = { onInsert(sig) })
                        }
                    }
                }
            }
            // The function picked: how it is written and what it does.
            Column(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                picked?.let { sig ->
                    Text(sig.text, style = AeroType.body.copy(fontWeight = FontWeight.SemiBold), color = PicoTheme.colorScheme.labelPrimary)
                    FunctionCategory.help(sig.name)?.let { Text(stringResource(it), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary) }
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ListRow(label, selected, onClick)
}

/** A function in the list: its name and, beside it, the start of what it does. A second tap on the picked one inserts it. */
@Composable
private fun FunctionRow(sig: FormulaFunctions.Signature, selected: Boolean, onClick: () -> Unit, onDouble: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(if (selected) Aero.tint(LocalAccent.current) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { if (selected) onDouble() else onClick() }.controllerHapticFeedback(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(sig.name, style = AeroType.body.copy(fontWeight = FontWeight.SemiBold), color = PicoTheme.colorScheme.labelPrimary, maxLines = 1,
            modifier = Modifier.width(170.dp))
        FunctionCategory.help(sig.name)?.let {
            Text(stringResource(it), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}
