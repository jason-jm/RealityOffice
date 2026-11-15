package com.therealityteam.aeropages.ui

import android.content.Context
import androidx.core.content.edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.AutoCorrect
import com.therealityteam.aeropages.editor.AutoCorrect.Kind

/** Where AutoCorrect's options are kept: the app's preferences, loaded at start, saved when changed. */
object AutoCorrectPrefs {
    private const val PREFS = "aeropages"

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, 0)
        AutoCorrect.options = AutoCorrect.Options(
            capitalizeSentences = p.getBoolean("ac.capitalize", true),
            twoInitialCaps = p.getBoolean("ac.twoInitials", true),
            replaceText = p.getBoolean("ac.replace", true),
            smartQuotes = p.getBoolean("ac.quotes", true),
            dashes = p.getBoolean("ac.dashes", true),
            ordinals = p.getBoolean("ac.ordinals", true),
            fractions = p.getBoolean("ac.fractions", true),
            autoLists = p.getBoolean("ac.lists", true),
            hyperlinks = p.getBoolean("ac.links", true),
            capitalizeDays = p.getBoolean("ac.days", true),
            skip = p.getStringSet("ac.skip", null)?.toSet() ?: emptySet(),
            // Each entry as "key<TAB>value" (a key has no white space).
            entries = p.getStringSet("ac.entries", null).orEmpty().mapNotNull { e ->
                e.indexOf('\t').takeIf { it > 0 }?.let { e.substring(0, it) to e.substring(it + 1) }
            }.toMap(),
        )
    }

    fun save(context: Context, o: AutoCorrect.Options) {
        AutoCorrect.options = o
        context.getSharedPreferences(PREFS, 0).edit {
            putBoolean("ac.capitalize", o.capitalizeSentences); putBoolean("ac.twoInitials", o.twoInitialCaps)
            putBoolean("ac.replace", o.replaceText); putBoolean("ac.quotes", o.smartQuotes); putBoolean("ac.dashes", o.dashes)
            putBoolean("ac.ordinals", o.ordinals); putBoolean("ac.fractions", o.fractions); putBoolean("ac.lists", o.autoLists)
            putBoolean("ac.links", o.hyperlinks); putBoolean("ac.days", o.capitalizeDays); putStringSet("ac.skip", o.skip)
            putStringSet("ac.entries", o.entries.map { (k, v) -> k + "\t" + v }.toSet())
        }
    }
}

/** The AutoCorrect Options button's first item: undoing what was just corrected. */
@Composable
private fun undoLabel(kind: Kind, was: String): String = when (kind) {
    Kind.CAPITALIZE, Kind.DAYS -> stringResource(R.string.ac_undo_capitalize)
    Kind.BULLETS -> stringResource(R.string.ac_undo_bullets)
    Kind.NUMBERING -> stringResource(R.string.ac_undo_numbering)
    Kind.HYPERLINK -> stringResource(R.string.ac_undo_hyperlink)
    Kind.ORDINAL -> stringResource(R.string.ac_undo_superscript)
    else -> stringResource(R.string.ac_change_back, was.trim())
}

/** Its second: no more corrections of this kind (of this word, for a misspelling or a symbol). */
@Composable
private fun stopLabel(kind: Kind, was: String): String = when (kind) {
    Kind.CAPITALIZE -> stringResource(R.string.ac_stop_capitalize)
    Kind.DAYS -> stringResource(R.string.ac_stop_days)
    Kind.TWO_INITIALS -> stringResource(R.string.ac_stop_two_initials)
    Kind.REPLACE -> stringResource(R.string.ac_stop_correcting, was.trim())
    Kind.DASH -> stringResource(R.string.ac_stop_dashes)
    Kind.ORDINAL -> stringResource(R.string.ac_stop_ordinals)
    Kind.FRACTION -> stringResource(R.string.ac_stop_fractions)
    Kind.BULLETS -> stringResource(R.string.ac_stop_bullets)
    Kind.NUMBERING -> stringResource(R.string.ac_stop_numbering)
    Kind.HYPERLINK -> stringResource(R.string.ac_stop_hyperlinks)
    Kind.QUOTES -> stringResource(R.string.ac_quotes)
}

/** The options with [fix]'s kind of correction stopped — a misspelling or a symbol alone. */
internal fun stopped(o: AutoCorrect.Options, kind: Kind, was: String): AutoCorrect.Options =
    if (kind == Kind.REPLACE) o.copy(skip = o.skip + was.trim().lowercase(java.util.Locale.ROOT)) else o.without(kind)

/** Word's AutoCorrect Options menu: undo this correction, stop making it, the options. */
@Composable
internal fun AutoCorrectMenu(kind: Kind, was: String, onUndo: () -> Unit, onStop: () -> Unit, onOptions: () -> Unit) {
    FlyoutMenu(width = 340.dp) {
        MenuCommand(undoLabel(kind, was), onUndo, icon = "ic_undo")
        MenuCommand(stopLabel(kind, was), onStop)
        MenuDivider()
        MenuCommand(stringResource(R.string.ac_control), onOptions, icon = "ic_lightning")
    }
}

/** Whether a correction gets Word's AutoCorrect Options button (a quote curled or an apostrophe does not: too many). */
internal fun hasOptionsButton(kind: Kind): Boolean = kind != Kind.QUOTES

/**
 * Word's AutoCorrect dialog: its AutoCorrect tab's switches and AutoFormat As You Type's, and the words and symbols
 * no longer corrected (each can be corrected again). Changes take effect at once and are kept.
 */
@Composable
internal fun AutoCorrectOptionsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var o by remember { mutableStateOf(AutoCorrect.options) }
    fun set(next: AutoCorrect.Options) { o = next; AutoCorrectPrefs.save(context, next) }
    AeroDialog(
        title = stringResource(R.string.ac_options),
        onDismiss = onDismiss,
        width = 460.dp,
        buttons = { DialogButton(stringResource(R.string.close), onDismiss, primary = true) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DialogLabel(stringResource(R.string.ac_section_autocorrect))
            CheckOption(stringResource(R.string.ac_two_initials), o.twoInitialCaps, { set(o.copy(twoInitialCaps = !o.twoInitialCaps)) })
            CheckOption(stringResource(R.string.ac_capitalize), o.capitalizeSentences, { set(o.copy(capitalizeSentences = !o.capitalizeSentences)) })
            CheckOption(stringResource(R.string.ac_days), o.capitalizeDays, { set(o.copy(capitalizeDays = !o.capitalizeDays)) })
            CheckOption(stringResource(R.string.ac_replace), o.replaceText, { set(o.copy(replaceText = !o.replaceText)) })
            // The typed sequences themselves are the same in every language (only the words around them are translated).
            val sep = if (java.util.Locale.getDefault().language in setOf("zh", "ja")) "、" else ", "
            Text(stringResource(R.string.ac_replace_detail, listOf("(c) → ©", "... → …", "--> → →").joinToString(sep)), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary,
                modifier = Modifier.padding(start = 30.dp))
            EntriesEditor(o.entries, enabled = o.replaceText) { set(o.copy(entries = it, skip = o.skip - it.keys.map { k -> k.lowercase(java.util.Locale.ROOT) }.toSet())) }
            DialogLabel(stringResource(R.string.ac_section_autoformat), Modifier.padding(top = 10.dp))
            CheckOption(stringResource(R.string.ac_quotes), o.smartQuotes, { set(o.copy(smartQuotes = !o.smartQuotes)) })
            CheckOption(stringResource(R.string.ac_ordinals), o.ordinals, { set(o.copy(ordinals = !o.ordinals)) })
            CheckOption(stringResource(R.string.ac_fractions), o.fractions, { set(o.copy(fractions = !o.fractions)) })
            CheckOption(stringResource(R.string.ac_dashes, "--"), o.dashes, { set(o.copy(dashes = !o.dashes)) })
            CheckOption(stringResource(R.string.ac_hyperlinks), o.hyperlinks, { set(o.copy(hyperlinks = !o.hyperlinks)) })
            CheckOption(stringResource(R.string.ac_lists), o.autoLists, { set(o.copy(autoLists = !o.autoLists)) })
            if (o.skip.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.ac_skipped, o.skip.sorted().joinToString(", ")), style = AeroType.body,
                        color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(300.dp))
                    DialogButton(stringResource(R.string.ac_correct_again), { set(o.copy(skip = emptySet())) }, small = true)
                }
            }
        }
    }
}

/**
 * Word's "Replace … With …": a key and what it becomes, Add (the same key again replaces its entry), and one's own
 * entries listed, each with Delete. The keys are what is typed; a key in small letters also matches typed with a capital.
 */
@Composable
private fun EntriesEditor(entries: Map<String, String>, enabled: Boolean, onChange: (Map<String, String>) -> Unit) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    val ok = AutoCorrect.validKey(key) && value.isNotEmpty()
    Column(Modifier.padding(start = 30.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DialogLabel(stringResource(R.string.ac_entry_replace))
                InputBox(key, { key = it.filterNot(Char::isWhitespace) }, Modifier.width(120.dp), description = stringResource(R.string.ac_entry_replace))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DialogLabel(stringResource(R.string.ac_entry_with))
                InputBox(value, { value = it }, Modifier.width(196.dp), description = stringResource(R.string.ac_entry_with))
            }
            DialogButton(stringResource(R.string.bookmark_add), {
                onChange(entries + (key to value)); key = ""; value = ""
            }, enabled = enabled && ok, small = true)
        }
        ListBox(Modifier.width(400.dp), height = 120.dp, empty = if (entries.isEmpty()) stringResource(R.string.ac_entries_empty) else null) {
            entries.entries.sortedBy { it.key.lowercase(java.util.Locale.ROOT) }.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.width(110.dp))
                    Text("→", style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(horizontal = 6.dp))
                    Text(v, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TitleIconButton("ic_delete", stringResource(R.string.ac_entry_delete, k), onClick = { onChange(entries - k) }, size = 32.dp)
                }
            }
        }
    }
}
