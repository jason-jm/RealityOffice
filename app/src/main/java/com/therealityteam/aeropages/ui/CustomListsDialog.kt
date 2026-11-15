package com.therealityteam.aeropages.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.CustomLists
import com.therealityteam.aeropages.editor.SheetEditor

/** Where the user's custom lists are kept: the app's preferences, loaded at start, saved when changed. */
object CustomListPrefs {
    private const val PREFS = "aeropages"
    private const val KEY = "cl.user"

    fun load(context: Context) {
        val raw = context.getSharedPreferences(PREFS, 0).getString(KEY, null) ?: return
        CustomLists.user = raw.split('\u001E').map { it.split('\u001F').filter { e -> e.isNotEmpty() } }.filter { it.size >= 2 }
    }

    fun save(context: Context, lists: List<List<String>>) {
        CustomLists.user = lists
        context.getSharedPreferences(PREFS, 0).edit { putString(KEY, lists.joinToString("\u001E") { it.joinToString("\u001F") }) }
    }
}

/**
 * Excel's Custom Lists dialog (Edit Custom Lists): NEW LIST and every list on the left — the built-in ones read-only, the
 * user's own editable — the chosen list's entries on the right (one per line), Add and Delete, and Import list from cells
 * (the selection, or any reference). With [onPick] (Sort's Order ▸ Custom List) OK sorts by the chosen list.
 */
@Composable
internal fun CustomListsDialog(editor: SheetEditor, onDismiss: () -> Unit, onPick: ((List<String>) -> Unit)? = null) {
    val context = LocalContext.current
    var user by remember { mutableStateOf(CustomLists.user) }
    val builtIn = CustomLists.builtIn
    // -1: NEW LIST; then the built-in lists, then the user's.
    var chosen by remember { mutableIntStateOf(-1) }
    fun listAt(i: Int): List<String>? = when { i < 0 -> null; i < builtIn.size -> builtIn[i]; else -> user.getOrNull(i - builtIn.size) }
    var entries by remember { mutableStateOf("") }
    var reference by remember { mutableStateOf(editor.watchReference()) }
    var badImport by remember { mutableStateOf(false) }
    val own = chosen >= builtIn.size
    fun choose(i: Int) { chosen = i; entries = listAt(i)?.joinToString("\n").orEmpty() }
    fun save(next: List<List<String>>) { user = next; CustomListPrefs.save(context, next) }
    AeroDialog(title = stringResource(R.string.custom_lists), onDismiss = onDismiss, width = 600.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), {
            listAt(chosen)?.let { l -> onPick?.invoke(l) }
            onDismiss()
        }, primary = true, enabled = onPick == null || chosen >= 0)
    }) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(270.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DialogLabel(stringResource(R.string.cl_lists))
                    ListBox(Modifier.fillMaxWidth(), height = 250.dp) {
                        ListRow(stringResource(R.string.cl_new_list), chosen == -1, { choose(-1) })
                        (builtIn + user).forEachIndexed { i, l ->
                            ListRow(CustomLists.label(l), chosen == i, { choose(i) })
                        }
                    }
                }
                Column(Modifier.width(276.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DialogLabel(stringResource(R.string.cl_entries))
                    // A built-in list is shown, not edited (as in Excel); a new or own list is typed one entry per line.
                    if (chosen in 0 until builtIn.size) ListBox(Modifier.fillMaxWidth(), height = 200.dp) {
                        builtIn[chosen].forEach { e -> Text(e, style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) }
                    } else InputBox(entries, { entries = it }, Modifier.fillMaxWidth().height(200.dp), description = stringResource(R.string.cl_entries), minLines = 8, maxLines = 40)
                    Text(stringResource(R.string.cl_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val parsed = CustomLists.parse(entries)
                        DialogButton(stringResource(R.string.cl_add), {
                            if (own) save(user.toMutableList().also { it[chosen - builtIn.size] = parsed }) else { save(user + listOf(parsed)); choose(builtIn.size + user.size - 1) }
                        }, enabled = parsed.size >= 2 && (chosen < 0 || own), small = true)
                        DialogButton(stringResource(R.string.delete), {
                            save(user.toMutableList().also { it.removeAt(chosen - builtIn.size) }); choose(-1)
                        }, enabled = own, small = true)
                    }
                }
            }
            DialogLabel(stringResource(R.string.cl_import))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputBox(reference, { reference = it; badImport = false }, Modifier.width(300.dp), description = stringResource(R.string.cl_import), monospace = true)
                DialogButton(stringResource(R.string.cl_import_button), {
                    val got = editor.listFromCells(reference)
                    if (got == null || got.size < 2) badImport = true
                    else { save(user + listOf(got)); choose(builtIn.size + user.size - 1) }
                }, small = true)
            }
            if (badImport) FieldError(stringResource(R.string.go_to_invalid))
            if (chosen in 0 until builtIn.size) Text(stringResource(R.string.cl_builtin_note), style = AeroType.caption,
                color = PicoTheme.colorScheme.labelSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
