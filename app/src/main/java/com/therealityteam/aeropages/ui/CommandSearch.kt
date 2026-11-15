package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R

/** A command the title bar's Search can find: its [label], [icon], the tab it lives on ([where]) and what it does. */
internal class AppCommand(val label: String, val icon: String, val where: String, val run: () -> Unit)

/**
 * The commands of [all] whose names hold [query] (case and the "…" of a dialog's name ignored), the closest first:
 * those that start with it, then the rest in ribbon order.
 */
internal fun findCommands(all: List<AppCommand>, query: String): List<AppCommand> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    fun name(c: AppCommand) = c.label.replace("…", "").lowercase()
    val hits = all.filter { name(it).contains(q) }.distinctBy { it.label }
    return hits.filter { name(it).startsWith(q) } + hits.filterNot { name(it).startsWith(q) }
}

/**
 * The actions run from Search in this session, newest first, per module (up to five): Office's "Recently used actions".
 * Kept by name, so they are found again in the commands the ribbon builds each time.
 */
internal object RecentCommands {
    private const val MAX = 5
    private val used = mutableStateMapOf<String, List<String>>()
    fun of(module: String): List<String> = used[module].orEmpty()
    fun use(module: String, label: String) { used[module] = (listOf(label) + of(module).filter { it != label }).take(MAX) }
    fun clear() = used.clear()
}

/**
 * One window's title-bar Search: what is typed, and whether the field has the focus (kept a moment after it leaves, so a
 * click on a suggestion in the ribbon still lands). [active] while there is a query or the field is focused — the ribbon
 * then shows the results, or the recent and suggested actions, in place of the tab.
 */
@Stable
internal class CommandSearch(val module: String, initial: String) {
    var query by mutableStateOf(initial)
    var focused by mutableStateOf(false)
    val active get() = query.isNotBlank() || focused

    /** Runs [c] as picked from Search: it becomes the most recent action and the search ends. */
    fun run(c: AppCommand) { RecentCommands.use(module, c.label); query = ""; c.run() }
}

/** This window's Search for [module] ("pages", "slides", "sheets"); dev hook `commandsearch` starts with a query typed. */
@Composable
internal fun rememberCommandSearch(module: String): CommandSearch =
    remember { CommandSearch(module, if (devDialogProp() == "commandsearch") Strings.get(R.string.search_demo) else "") }

/** The window's Search field, for Office's Alt+Q: the app's root key handler asks it for the focus. */
internal val LocalSearchFocus = staticCompositionLocalOf { FocusRequester() }

/** Office's shortcut to Search (Alt+Q), for an app's root key handler: true when [e] is it and the field took the focus. */
internal fun searchShortcut(e: KeyEvent, focus: FocusRequester): Boolean {
    if (e.type != KeyEventType.KeyDown || !e.isAltPressed || e.key != Key.Q) return false
    return runCatching { focus.requestFocus() }.isSuccess
}

/**
 * Office's Search in the title bar (Microsoft Search): a quiet field with a magnifier; what is typed finds commands,
 * which the ribbon shows in place of the tab ([SearchCard]); Enter runs the first one, Esc clears the field and leaves it.
 * Alt+Q comes here ([searchShortcut]). Dev hook `commandsuggest`: the field focused three seconds in, two actions recent.
 */
@Composable
internal fun TitleSearchBox(search: CommandSearch, commands: List<AppCommand>, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val label = stringResource(R.string.search)
    val shape = RoundedCornerShape(15.dp)
    val focus = LocalSearchFocus.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf<Job?>(null) }
    val latest by rememberUpdatedState(commands)
    LaunchedEffect(Unit) {
        if (devDialogProp() == "commandsuggest") {
            delay(3_000)
            runCatching { focus.requestFocus() }
            val cs = withTimeoutOrNull(5_000) { snapshotFlow { latest }.first { it.size > 3 } } ?: return@LaunchedEffect
            if (RecentCommands.of(search.module).isEmpty()) RecentCommands.use(search.module, cs[3].label)
            RecentCommands.use(search.module, cs[0].label)
        }
    }
    val query = search.query
    Row(
        modifier.width(280.dp).height(30.dp).clip(shape).background(Aero.field)
            .then(if (search.focused && leaving == null) Modifier.border(1.5.dp, accent, shape) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RibbonGlyph("ic_find", PicoTheme.colorScheme.labelSecondary, 14.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                query, { search.query = it }, singleLine = true,
                textStyle = AeroType.command.copy(color = PicoTheme.colorScheme.labelPrimary),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { findCommands(commands, query).firstOrNull()?.let(search::run) },
                    onDone = { findCommands(commands, query).firstOrNull()?.let(search::run) },
                ),
                modifier = Modifier.semantics { contentDescription = label }.focusRequester(focus)
                    .onFocusChanged { f ->
                        leaving?.cancel(); leaving = null
                        if (f.isFocused) search.focused = true
                        else if (search.focused) leaving = scope.launch { delay(350); search.focused = false; leaving = null }
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { search.query = ""; focusManager.clearFocus(); true } else false
                    },
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(label, style = AeroType.command, color = PicoTheme.colorScheme.labelTertiary, maxLines = 1)
                    inner()
                },
            )
        }
        if (query.isNotEmpty()) TitleIconButton("ic_close", stringResource(R.string.clear), { search.query = "" }, size = 24.dp)
    }
}

/**
 * What Search shows in the ribbon's place while [CommandSearch.active]. With a query: "Results for “…”", each command as a
 * ribbon button with the tab it is on beneath (none: Office's no-results line), and, when [findLabel] is given, a group
 * that finds the words in the file itself ([onFindText]). Focused with nothing typed: Office's "Recently used actions"
 * and "Suggested actions" — the commands of the tab in front ([currentTab]) not already recent.
 */
@Composable
internal fun SearchCard(
    search: CommandSearch, commands: List<AppCommand>, currentTab: String,
    findLabel: String? = null, onFindText: (String) -> Unit = {},
) {
    val query = search.query.trim()
    if (query.isEmpty()) {
        val recent = RecentCommands.of(search.module).mapNotNull { l -> commands.firstOrNull { it.label == l } }
        // The tab in front's commands (a contextual tab has none of its own here: the first tab's).
        val tabCommands = commands.filter { it.where == currentTab }.ifEmpty { commands.filter { it.where == commands.firstOrNull()?.where } }
        val suggested = tabCommands.filter { it !in recent }.take(6)
        if (recent.isNotEmpty()) RibbonGroup(stringResource(R.string.search_recent), last = suggested.isEmpty()) {
            recent.forEach { c -> RibbonButton(c.icon, c.label, { search.run(c) }, tooltip = "${c.label} · ${c.where}") }
        }
        if (suggested.isNotEmpty()) RibbonGroup(stringResource(R.string.search_suggested), last = true) {
            suggested.forEach { c -> RibbonButton(c.icon, c.label, { search.run(c) }, tooltip = "${c.label} · ${c.where}") }
        }
        return
    }
    val hits = findCommands(commands, query)
    RibbonGroup(stringResource(R.string.search_results, query), last = findLabel == null) {
        if (hits.isEmpty()) {
            Text(stringResource(R.string.search_no_results), style = AeroType.body, color = PicoTheme.colorScheme.labelSecondary,
                modifier = Modifier.padding(horizontal = 16.dp))
        } else hits.take(12).forEach { c ->
            RibbonButton(c.icon, c.label, { search.run(c) }, tooltip = "${c.label} · ${c.where}")
        }
    }
    if (findLabel != null) RibbonGroup(findLabel, last = true) {
        val quoted = stringResource(R.string.search_quoted, query)
        RibbonButton("ic_find", quoted, { search.query = ""; onFindText(query) }, tooltip = "$findLabel · $quoted")
    }
}
