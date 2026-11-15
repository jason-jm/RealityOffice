package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.ParagraphStyle
import androidx.compose.ui.res.stringResource
import com.therealityteam.aeropages.R

/**
 * Word's navigation pane: a search box over the document (its matches shaded on the page — the find panel's search is
 * the same), then the headings, indented by level — click one to go there; the selected one's outline tools promote,
 * demote or move its section — or, while searching, the results: each match in its sentence, click to select it.
 */
@Composable
fun NavigationPane(editor: DocumentEditor, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var showResults by remember { mutableStateOf(true) }
    val query = editor.searchQuery
    Column(
        modifier = modifier
            .fillMaxSize()
            .paneSurface(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PaneHeader(stringResource(R.string.navigation))
        // Search the document: SpatialUI's search field (its clear button empties the search).
        com.pico.spatial.ui.design.SearchField(
            value = query,
            onValueChange = { editor.setSearch(it); showResults = true },
            onSearch = { editor.setSearch(it); showResults = true },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.nav_search_hint), maxLines = 1) },
        )
        // Word's Navigation pane tabs: Headings | Results.
        ChoiceSegments(listOf(false, true), query.isNotEmpty() && showResults, { stringResource(if (it) R.string.nav_results else R.string.nav_headings) },
            { r -> showResults = r && query.isNotEmpty() }, width = 268.dp)
        if (query.isNotEmpty() && showResults) Results(editor) else Headings(editor)
    }
}

/** The search's results: how many, then each in a few words of its paragraph, the match in bold; the selected one shaded. */
@Composable
private fun Results(editor: DocumentEditor) {
    val hits = editor.searchHits
    val current = editor.currentHit()
    Text(
        when {
            editor.searchPattern == null -> stringResource(R.string.find_bad_pattern)
            hits.isEmpty() -> stringResource(R.string.nav_no_results)
            current != null -> stringResource(R.string.find_hit_of, current + 1, hits.size)
            else -> stringResource(R.string.find_hits, hits.size)
        },
        style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary,
    )
    val locs = editor.document.locations()
    // A long search lists its first 300 (Word too stops listing).
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        hits.take(300).forEachIndexed { i, h ->
            val text = locs.getOrNull(h.para)?.paragraph?.plainText() ?: return@forEachIndexed
            val (before, found, after) = com.therealityteam.aeropages.editor.DocSearch.context(text, h.start.coerceAtMost(text.length), h.end.coerceAtMost(text.length))
            val shown = buildAnnotatedString {
                append(before)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = androidx.compose.ui.graphics.Color(0x80FFE45C))) { append(found) }
                append(after)
            }
            ListRow(i == current, { editor.showHit(h) }) {
                Text(shown, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 2)
            }
        }
    }
}

/** The document's headings in order, indented by level (Word's Headings tab). */
@Composable
private fun Headings(editor: DocumentEditor) {
    val headings = editor.document.locations().mapIndexedNotNull { index, loc ->
        val style = loc.paragraph.format.style
        if (isHeading(style)) Triple(index, loc.paragraph.plainText(), levelOf(style)) else null
    }
    if (headings.isEmpty()) {
        PaneEmpty("ic_list", stringResource(R.string.msg_no_headings))
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        headings.forEach { (index, text, level) ->
            val selected = index == editor.active
            // Word's headings: indented by level, the one the caret is in tinted with an accent bar at its start.
            ListRow(selected, { editor.focusParagraph(index) }, Modifier.padding(start = (level * 14).dp)) {
                Box(Modifier.width(3.dp).height(18.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .background(if (selected) LocalAccent.current else androidx.compose.ui.graphics.Color.Transparent))
                Text(
                    text.ifBlank { stringResource(R.string.label_untitled_heading) },
                    style = if (level == 0) AeroType.body.copy(fontWeight = FontWeight.SemiBold) else AeroType.body,
                    color = if (selected) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                // Word's outline tools on the selected heading: promote / demote the level, move the section.
                if (selected) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        RibbonMiniButton("ic_chevron_left", stringResource(R.string.nav_promote), { editor.promoteHeading(index) })
                        RibbonMiniButton("ic_chevron_right", stringResource(R.string.nav_demote), { editor.demoteHeading(index) })
                        RibbonMiniButton("ic_chevron_up", stringResource(R.string.nav_move_up), { editor.moveSectionUp(index) })
                        RibbonMiniButton("ic_chevron_down", stringResource(R.string.nav_move_down), { editor.moveSectionDown(index) })
                    }
                }
            }
        }
    }
}

private fun isHeading(style: ParagraphStyle): Boolean = when (style) {
    ParagraphStyle.TITLE, ParagraphStyle.HEADING1, ParagraphStyle.HEADING2, ParagraphStyle.HEADING3 -> true
    else -> false
}

private fun levelOf(style: ParagraphStyle): Int = when (style) {
    ParagraphStyle.TITLE -> 0
    ParagraphStyle.HEADING1 -> 0
    ParagraphStyle.HEADING2 -> 1
    ParagraphStyle.HEADING3 -> 2
    else -> 0
}
