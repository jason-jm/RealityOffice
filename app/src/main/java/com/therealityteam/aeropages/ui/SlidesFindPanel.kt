package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SearchOptions
import com.therealityteam.aeropages.editor.SlideEditor

/**
 * PowerPoint's Find and Replace beside the window (modeless): what to find, with Match case, Find whole words only and
 * Word's wildcards; Previous / Next select each match in its text box, slide after slide, round the deck; Replace and
 * Replace All (speaker notes too, one undo step) — `^&` and `\1` in the replacement as in Pages.
 */
@Composable
internal fun SlidesFindPanel(editor: SlideEditor, replace: Boolean, onTab: (Boolean) -> Unit, initialQuery: String = "", onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var query by remember { mutableStateOf(initialQuery) }
    var replacement by remember { mutableStateOf("") }
    var options by remember { mutableStateOf(SearchOptions()) }
    var status by remember { mutableStateOf("") }
    val notFound = stringResource(R.string.msg_not_found, query)
    val badPattern = stringResource(R.string.find_bad_pattern)
    fun where(r: Pair<Int, Int>?): String = when {
        r != null -> Strings.get(R.string.find_hit_of, r.first + 1, r.second) + " · " + Strings.get(R.string.slide_n, editor.current + 1)
        com.therealityteam.aeropages.editor.DocSearch.pattern(query, options) == null -> badPattern
        else -> notFound
    }
    // Opened with a query (a dev hook's): its first match found once the deck is in.
    androidx.compose.runtime.LaunchedEffect(initialQuery) {
        if (initialQuery.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        status = where(editor.findText(initialQuery, options))
    }
    Box(Modifier.fillMaxSize().paneSurface()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PaneHeader(stringResource(R.string.find_and_replace), onClose)
            DialogTabs(listOf(false, true), replace, { stringResource(if (it) R.string.replace else R.string.find) }, onTab, width = 220.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.find_what), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
                InputBox(query, { query = it; status = "" }, Modifier.fillMaxWidth())
            }
            if (replace) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.replace_with), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
                InputBox(replacement, { replacement = it }, Modifier.fillMaxWidth())
            }
            Column {
                CheckRow(stringResource(R.string.match_case), options.matchCase) { options = options.copy(matchCase = !options.matchCase); status = "" }
                if (!options.wildcards) CheckRow(stringResource(R.string.find_whole_words), options.wholeWord) { options = options.copy(wholeWord = !options.wholeWord); status = "" }
                CheckRow(stringResource(R.string.find_wildcards), options.wildcards) { options = options.copy(wildcards = !options.wildcards); status = "" }
            }
            val any = query.isNotEmpty()
            WrapRow {
                if (replace) {
                    DialogButton(stringResource(R.string.replace), { status = where(editor.replaceFound(query, replacement, options)) }, enabled = any, small = true)
                    DialogButton(stringResource(R.string.replace_all), {
                        val n = editor.replaceAllFound(query, replacement, options)
                        status = if (n > 0) Strings.get(R.string.status_replaced_count, n) else where(null)
                    }, enabled = any, small = true)
                }
                DialogButton(stringResource(R.string.find_previous), { status = where(editor.findText(query, options, back = true)) }, enabled = any, small = true)
                DialogButton(stringResource(R.string.find_next), { status = where(editor.findText(query, options)) }, primary = !replace, enabled = any, small = true)
            }
            if (status.isNotEmpty()) Text(status, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}
