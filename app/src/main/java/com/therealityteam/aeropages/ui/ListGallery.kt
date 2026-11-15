package com.therealityteam.aeropages.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.ListDef
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.ListLevel
import com.therealityteam.aeropages.model.Lists
import com.therealityteam.aeropages.model.NumFormat

/**
 * Word's numbering and bullet libraries (编号库 / 项目符号库): each look as a tile showing its first
 * items, the active list's own look marked; restarting the numbers at the active item and continuing
 * the list before; none (the list goes).
 */
@Composable
internal fun ListsPanel(editor: DocumentEditor, bullets: Boolean, onPicked: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val active = editor.activeList
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(if (bullets) R.string.bullet_library else R.string.numbering_library), style = AeroType.label, color = PicoTheme.colorScheme.labelSecondary)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (bullets) BULLET_LOOKS else NUMBER_LOOKS).forEach { def ->
                val shown = sample(def)
                ListTile(shown, selected = active != null && active.second.levels.take(2) == def.levels.take(2), label = shown.joinToString(" ")) {
                    editor.applyListDef(def); onPicked()
                }
            }
        }
        // Word's commands under the library: None, Restart at 1, Continue Numbering.
        MenuDivider()
        FlyoutMenu(width = 280.dp) {
            MenuCommand(stringResource(R.string.none), {
                if (bullets) { if (editor.activeParagraph.format.listKind == ListKind.BULLET) editor.toggleBullet() }
                else if (editor.activeParagraph.format.listKind == ListKind.NUMBER) editor.toggleNumber()
                onPicked()
            }, icon = "ic_no_color")
            if (!bullets && active != null && editor.activeParagraph.format.listKind == ListKind.NUMBER) {
                MenuCommand(stringResource(R.string.restart_numbering), { editor.restartNumbering(); onPicked() }, icon = "ic_numbering")
                MenuCommand(stringResource(R.string.continue_numbering), { editor.continueNumbering(); onPicked() }, icon = "ic_arrow_down")
            }
        }
    }
}

/** The labels a list's first items show: three of the first level, or — an outline — one of each of three levels. */
private fun sample(def: ListDef): List<String> {
    val outline = def.levels.getOrNull(1)?.text?.contains("%1") == true
    val counts = IntArray(9); val seen = BooleanArray(9)
    return if (outline) (0..2).map { lv -> counts[lv] = def.level(lv).start; seen[lv] = true; Lists.label(def, lv, counts, seen) }
    else (0..2).map { n -> counts[0] = def.level(0).start + n; seen[0] = true; Lists.label(def, 0, counts, seen) }
}

@Composable
private fun ListTile(lines: List<String>, selected: Boolean, label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(96.dp, 84.dp).clip(shape)
            .background(if (selected) Aero.tint(LocalAccent.current) else Aero.field)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) LocalAccent.current else androidx.compose.ui.graphics.Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerHapticFeedback(interactionSource = interaction)
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        GazeFill(shape)
        val outline = lines.size == 3 && lines[1].length > lines[0].length && lines[2].length > lines[1].length && lines[1].startsWith(lines[0].trimEnd('.', ')'))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = if (outline) (i * 8).dp else 0.dp)) {
                    Text(t, style = AeroType.label, color = if (selected) PicoTheme.colorScheme.labelPrimaryLight else PicoTheme.colorScheme.labelPrimary, maxLines = 1)
                    Box(Modifier.padding(start = 4.dp).fillMaxWidth().height(2.dp).background(PicoTheme.colorScheme.labelTertiary))
                }
            }
        }
    }
}

/** A list with level 0 as [first] shows it, the levels under it the Chinese document's way (or the bullets'). */
private fun numbered(first: ListLevel): ListDef = ListDef(listOf(first) + ListDef.CHINESE.levels.drop(1).mapIndexed { i, l ->
    if (first.format == NumFormat.CHINESE) l else ListDef.NUMBERS.level(i + 1)
})

private fun bulleted(ch: String): ListDef = ListDef(List(9) { i -> ListLevel(NumFormat.BULLET, if (i == 0) ch else Lists.bulletFor(i)) })

/** The numbering library: Word's and WPS's usual looks. */
private val NUMBER_LOOKS = listOf(
    ListDef.NUMBERS,
    numbered(ListLevel(NumFormat.DECIMAL, "%1)")),
    numbered(ListLevel(NumFormat.DECIMAL, "%1、")),
    ListDef.CHINESE,
    numbered(ListLevel(NumFormat.CHINESE, "（%1）")),
    numbered(ListLevel(NumFormat.CIRCLED, "%1")),
    numbered(ListLevel(NumFormat.UPPER_LETTER, "%1.")),
    numbered(ListLevel(NumFormat.LOWER_LETTER, "%1)")),
    numbered(ListLevel(NumFormat.UPPER_ROMAN, "%1.")),
    numbered(ListLevel(NumFormat.LOWER_ROMAN, "%1.")),
    ListDef(listOf(ListLevel(NumFormat.CHINESE, "第%1章"), ListLevel(NumFormat.CHINESE, "第%2节")) + ListDef.CHINESE.levels.drop(2)),
    ListDef.OUTLINE,
)

/** The bullet library. */
private val BULLET_LOOKS = listOf("•", "◦", "▪", "■", "◆", "➢", "✓", "★", "–").map { bulleted(it) }
