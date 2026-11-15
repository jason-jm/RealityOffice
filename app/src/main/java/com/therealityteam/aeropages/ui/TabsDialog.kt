package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.TabAlign
import com.therealityteam.aeropages.model.TabLeader
import com.therealityteam.aeropages.model.TabStop
import kotlin.math.abs

/**
 * Word's Tabs dialog for the caret's paragraph (the selected ones): its tab stops listed — pick one to change it — a
 * position with an alignment and a leader to Set, Clear the picked one or Clear All, and the document's default tab
 * stops; OK puts them on the selected paragraphs in one step. Opened by double-clicking a tab stop on the ruler, or from
 * Layout ▸ Paragraph.
 */
@Composable
internal fun TabsDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    val start = remember { editor.activeParagraph.format.tabStops.sortedBy { it.posPt } }
    var stops by remember { mutableStateOf(start) }
    var picked by remember { mutableStateOf(start.firstOrNull()) }
    var pos by remember { mutableFloatStateOf(start.firstOrNull()?.posPt ?: 36f) }
    var align by remember { mutableStateOf(start.firstOrNull()?.align ?: TabAlign.LEFT) }
    var leader by remember { mutableStateOf(start.firstOrNull()?.leader ?: TabLeader.NONE) }
    var defaultTab by remember { mutableFloatStateOf(editor.document.defaultTabPt.takeIf { it > 1f } ?: 36f) }
    fun pick(t: TabStop?) { picked = t; if (t != null) { pos = t.posPt; align = t.align; leader = t.leader } }
    fun set() {
        val t = TabStop(pos, align, leader)
        stops = (stops.filterNot { abs(it.posPt - pos) < 1f || it == picked } + t).sortedBy { it.posPt }
        picked = t
    }
    val alignNames = mapOf(
        TabAlign.LEFT to stringResource(R.string.tabs_align_left), TabAlign.CENTER to stringResource(R.string.tabs_align_center),
        TabAlign.RIGHT to stringResource(R.string.tabs_align_right), TabAlign.DECIMAL to stringResource(R.string.tabs_align_decimal),
    )
    val leaderSamples = mapOf(TabLeader.NONE to stringResource(R.string.tabs_leader_none), TabLeader.DOT to "........", TabLeader.HYPHEN to "--------",
        TabLeader.UNDERSCORE to "_______", TabLeader.MIDDLE_DOT to "········")
    WideDialog(
        title = stringResource(R.string.tabs_title),
        onDismiss = onDismiss,
        buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.btn_ok), { editor.setTabs(stops, defaultTab); onDismiss() }, primary = true)
            }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            // The stops: a position to set, the paragraph's stops (pick one to change it), Set / Clear / Clear All.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(420.dp)) {
                SectionLabel(stringResource(R.string.tabs_position))
                LengthRow(stringResource(R.string.ruler_tab_stop), pos, { pos = it }, max = 1584f)
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 180.dp).clip(RoundedCornerShape(8.dp))
                        .background(Aero.field).verticalScroll(rememberScrollState()).padding(4.dp),
                ) {
                    if (stops.isEmpty()) Text(stringResource(R.string.tabs_none), style = AeroType.caption,
                        color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(8.dp))
                    for (t in stops) {
                        val on = t == picked
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (on) PicoTheme.colorScheme.interaction.copy(alpha = 0.14f) else Color.Transparent)
                                .press { pick(t) }.padding(horizontal = 8.dp),
                        ) {
                            Text(Lengths.label(t.posPt), style = AeroType.body, modifier = Modifier.width(110.dp))
                            Text(alignNames.getValue(t.align), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(110.dp))
                            if (t.leader != TabLeader.NONE) Text(leaderSamples.getValue(t.leader), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton(stringResource(R.string.tabs_set), ::set)
                    DialogButton(stringResource(R.string.tabs_clear), { picked?.let { p -> stops = stops - p; pick(stops.firstOrNull()) } })
                    DialogButton(stringResource(R.string.tabs_clear_all), { stops = emptyList(); pick(null) })
                }
            }
            // How a stop aligns its text and what fills up to it; the document's default stops.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(380.dp)) {
                SectionLabel(stringResource(R.string.tabs_alignment))
                // Word's Tabs dialog: Alignment and Leader as option buttons.
                WrapRow(spacing = 16.dp, lineSpacing = 0.dp) {
                    TabAlign.entries.forEach { a -> RadioOption(alignNames.getValue(a), align == a, { align = a }) }
                }
                SectionLabel(stringResource(R.string.tabs_leader))
                WrapRow(spacing = 16.dp, lineSpacing = 0.dp) {
                    TabLeader.entries.forEach { l -> RadioOption(leaderSamples.getValue(l), leader == l, { leader = l }) }
                }
                Box(Modifier.height(4.dp))
                SectionLabel(stringResource(R.string.tabs_default))
                LengthRow(stringResource(R.string.tabs_default_every), defaultTab, { defaultTab = it.coerceAtLeast(3f) }, min = 3f, max = 360f)
            }
        }
    }
}
