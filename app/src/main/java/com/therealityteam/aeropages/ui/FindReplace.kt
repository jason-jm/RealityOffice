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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.SearchOptions

/** The find panel's tabs, as Word's Find and Replace dialog has them. */
enum class FindTab { FIND, REPLACE, GOTO }

/**
 * Word's Find and Replace beside the window: Find (the query with its options — match case, whole words only, Word's
 * wildcards — its special characters a click away, which match of how many is selected, Previous / Next), Replace (the
 * same and what to put in, Replace / Replace All, honouring Track Changes) and Go To (a page by number, the next or
 * previous heading, table or comment, a bookmark). The query is the editor's search: the page shades every match while
 * it is open, and the navigation pane's search is the same.
 */
@Composable
fun FindReplacePanel(editor: DocumentEditor, tab: FindTab, onTab: (FindTab) -> Unit, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var replacement by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    // Which field a special character goes into: the query (0) or the replacement (1).
    var target by remember { mutableIntStateOf(0) }
    val query = editor.searchQuery
    val options = editor.searchOptions
    fun setQuery(q: String) { editor.setSearch(q, options); status = "" }
    fun setOptions(o: SearchOptions) { editor.setSearch(query, o); status = "" }

    Box(Modifier.fillMaxSize().paneSurface()) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PaneHeader(stringResource(R.string.find_and_replace), onClose)
            DialogTabs(FindTab.entries, tab, { stringResource(when (it) { FindTab.FIND -> R.string.find; FindTab.REPLACE -> R.string.replace; FindTab.GOTO -> R.string.goto_tab }) }, onTab, width = 300.dp)
            if (tab == FindTab.GOTO) {
                GoToPane(editor)
                return@Column
            }
            LabeledField(stringResource(R.string.find_what), query, ::setQuery, Modifier.onFocusChanged { if (it.isFocused) target = 0 })
            if (tab == FindTab.REPLACE) LabeledField(stringResource(R.string.replace_with), replacement, { replacement = it }, Modifier.onFocusChanged { if (it.isFocused) target = 1 })

            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                CheckRow(stringResource(R.string.match_case), options.matchCase) { setOptions(options.copy(matchCase = !options.matchCase)) }
                if (!options.wildcards) CheckRow(stringResource(R.string.find_whole_words), options.wholeWord) { setOptions(options.copy(wholeWord = !options.wholeWord)) }
                CheckRow(stringResource(R.string.find_wildcards), options.wildcards) { setOptions(options.copy(wildcards = !options.wildcards)) }
            }
            // Special characters (Word's Special ▾): what the query (or the replacement) can hold besides text.
            val forReplace = tab == FindTab.REPLACE && target == 1
            val specials = when {
                forReplace -> listOfNotNull(R.string.special_found to "^&", R.string.special_tab to "^t", R.string.special_line_break to "^l",
                    (R.string.special_group to "\\1").takeIf { options.wildcards })
                options.wildcards -> listOf(R.string.special_any_one to "?", R.string.special_any_run to "*", R.string.special_word_start to "<",
                    R.string.special_word_end to ">", R.string.special_one_or_more to "@", R.string.special_tab to "^t")
                else -> listOf(R.string.special_tab to "^t", R.string.special_any_char to "^?", R.string.special_any_digit to "^#",
                    R.string.special_any_letter to "^$", R.string.special_white_space to "^w", R.string.special_line_break to "^l")
            }
            Text(stringResource(if (forReplace) R.string.special_for_replace else R.string.special_for_find), style = AeroType.label,
                color = PicoTheme.colorScheme.labelSecondary)
            WrapRow {
                specials.forEach { (name, code) ->
                    DialogButton(stringResource(name), { if (forReplace) replacement += code else setQuery(query + code) }, small = true)
                }
            }

            // Where the search stands: which match is selected of how many, none, or wildcards Word would not take.
            val hits = editor.searchHits
            val current = editor.currentHit()
            val where = when {
                query.isEmpty() -> ""
                editor.searchPattern == null -> stringResource(R.string.find_bad_pattern)
                hits.isEmpty() -> stringResource(R.string.msg_not_found, query)
                current != null -> stringResource(R.string.find_hit_of, current + 1, hits.size)
                else -> stringResource(R.string.find_hits, hits.size)
            }
            if (where.isNotEmpty() || status.isNotEmpty()) {
                Text(status.ifEmpty { where }, style = AeroType.caption,
                    color = if (editor.searchPattern == null && query.isNotEmpty()) Aero.danger else PicoTheme.colorScheme.labelSecondary)
            }
            WrapRow {
                if (tab == FindTab.REPLACE) {
                    DialogButton(stringResource(R.string.replace), { editor.replaceCurrent(replacement) }, enabled = hits.isNotEmpty(), small = true)
                    DialogButton(stringResource(R.string.replace_all), {
                        status = Strings.get(R.string.status_replaced_count, editor.replaceAllHits(replacement))
                    }, enabled = hits.isNotEmpty(), small = true)
                }
                DialogButton(stringResource(R.string.find_previous), { editor.find(back = true) }, enabled = hits.isNotEmpty(), small = true)
                DialogButton(stringResource(R.string.find_next), { editor.find() }, primary = true, enabled = hits.isNotEmpty())
            }
        }
    }
}

/** Go To: what to go to, then a page number or Previous / Next — or, for bookmarks, which one. */
@Composable
private fun GoToPane(editor: DocumentEditor) {
    var kind by remember { mutableStateOf(DocumentEditor.GoTo.PAGE) }
    var page by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val none = stringResource(R.string.goto_none)
    val noPage = stringResource(R.string.goto_no_page)
    // Word's Go to what: list box.
    DialogLabel(stringResource(R.string.goto_what))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(DocumentEditor.GoTo.PAGE to R.string.goto_page, DocumentEditor.GoTo.HEADING to R.string.goto_heading, DocumentEditor.GoTo.TABLE to R.string.goto_table,
            DocumentEditor.GoTo.COMMENT to R.string.goto_comment, DocumentEditor.GoTo.BOOKMARK to R.string.goto_bookmark).forEach { (k, name) ->
            ListRow(stringResource(name), kind == k, { kind = k; status = "" })
        }
    }
    fun step(back: Boolean) {
        val ok = when (kind) {
            DocumentEditor.GoTo.PAGE -> editor.goToPage((editor.caretPage + if (back) -1 else 1).coerceAtLeast(1))
            DocumentEditor.GoTo.COMMENT -> editor.goToComment(back)
            else -> editor.goToNext(kind, back)
        }
        status = if (ok) "" else none
    }
    when (kind) {
        DocumentEditor.GoTo.PAGE -> {
            // A page number, or +n / −n pages from where the caret is (as Word takes them).
            LabeledField(stringResource(R.string.goto_page_number), page, { page = it.filter { c -> c.isDigit() || c == '+' || c == '-' } })
            Text(stringResource(R.string.goto_current_page, editor.caretPage), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            WrapRow {
                DialogButton(stringResource(R.string.goto_go), {
                    val n = when {
                        page.startsWith("+") -> page.drop(1).toIntOrNull()?.let { editor.caretPage + it }
                        page.startsWith("-") -> page.drop(1).toIntOrNull()?.let { editor.caretPage - it }
                        else -> page.toIntOrNull()
                    }
                    status = if (n != null && editor.goToPage(n.coerceAtLeast(1))) "" else noPage
                }, primary = true, enabled = page.isNotEmpty(), small = true)
                DialogButton(stringResource(R.string.find_previous), { step(true) }, small = true)
                DialogButton(stringResource(R.string.find_next), { step(false) }, small = true)
            }
        }
        DocumentEditor.GoTo.BOOKMARK -> {
            val marks = editor.bookmarks(hidden = false)
            if (marks.isEmpty()) Text(stringResource(R.string.goto_no_bookmarks), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                marks.forEach { m ->
                    ListRow(m.name, false, { editor.goToBookmark(m.name) })
                }
            }
        }
        else -> WrapRow {
            DialogButton(stringResource(R.string.find_previous), { step(true) }, small = true)
            DialogButton(stringResource(R.string.find_next), { step(false) }, primary = true)
        }
    }
    if (status.isNotEmpty()) Text(status, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
}

@Composable
private fun LabeledField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
        InputBox(value, onChange, Modifier.fillMaxWidth().then(modifier), description = label)
    }
}
