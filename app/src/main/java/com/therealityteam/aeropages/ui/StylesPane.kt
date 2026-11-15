package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.ParagraphStyle

/**
 * Word's Styles pane (Home ▸ Styles' launcher): every style, the caret's paragraph's one picked, each in its own look when
 * Show Preview is on (a style's colour kept, the rest in the pane's ink); a click applies it. Each style's ⋯ menu says
 * where it is used and offers Update to Match Selection, Modify… and its built-in definition back; Clear All under the
 * list takes the selection back to Normal without its direct formatting.
 */
@Composable
internal fun StylesPane(editor: DocumentEditor, modifier: Modifier, onModify: (ParagraphStyle) -> Unit, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    var preview by rememberLayoutFlag("pages.stylesPreview", true)
    val active = editor.activeStyleId
    var creating by remember { mutableStateOf(false) }
    Column(modifier.paneSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneHeader(stringResource(R.string.styles), onClose)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (s in ParagraphStyle.entries) StyleRow(editor, s, active.equals(editor.document.styles.idOf(s), ignoreCase = true), preview, onModify)
            // The document's own styles: from its file, or made here.
            val own = editor.ownStyles
            if (own.isNotEmpty()) PaneSection(stringResource(R.string.document_styles))
            for (d in own) OwnStyleRow(editor, d, active.equals(d.id, ignoreCase = true), preview)
        }
        CheckOption(stringResource(R.string.show_preview), preview, { preview = !preview }, small = true)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.new_style) + "…", { creating = true }, small = true)
            DialogButton(stringResource(R.string.clear_all_formatting), editor::clearAll, small = true)
        }
    }
    if (creating) CreateStyleDialog(editor) { creating = false }
}

/** One of the document's own styles: its name (in its look with a preview), and its ⋯ menu — where used, update, delete. */
@Composable
private fun OwnStyleRow(editor: DocumentEditor, d: com.therealityteam.aeropages.model.StyleDef, picked: Boolean, preview: Boolean) {
    var menu by remember { mutableStateOf(false) }
    ListRow(picked, { editor.setStyleId(d.id) }) {
        val r = editor.resolvedStyle(d.id)
        val ink = PicoTheme.colorScheme.labelPrimary
        if (preview) Text(
            d.name, modifier = Modifier.weight(1f).padding(vertical = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = r.sizePt.coerceIn(12f, 20f).sp, fontFamily = TextStyling.familyOf(r.fontFamily),
            fontWeight = if (r.bold) FontWeight.SemiBold else FontWeight.Normal, fontStyle = if (r.italic) FontStyle.Italic else FontStyle.Normal,
            color = r.color?.let { Color(it) } ?: ink,
        ) else Text(d.name, modifier = Modifier.weight(1f), style = AeroType.body, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("¶", style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(horizontal = 4.dp))
        Box {
            TitleIconButton("ic_more", stringResource(R.string.more_actions), { menu = true }, size = 28.dp)
            if (menu) AnchoredFlyout({ menu = false }) {
                FlyoutMenu(width = 300.dp) {
                    MenuHeading(stringResource(R.string.style_in_use, d.name, editor.styleUses(d.id)))
                    MenuCommand(stringResource(R.string.update_style_to_match, d.name), { editor.updateStyleIdFromSelection(d.id); menu = false })
                    MenuDivider()
                    MenuCommand(stringResource(R.string.delete_style, d.name), { editor.deleteStyle(d.id); menu = false }, icon = "ic_delete")
                }
            }
        }
    }
}

/**
 * Word's Create New Style from Formatting: a name (the next "Style N" offered), the style it is based on (the caret's
 * paragraph's), and a sample in the paragraph's look; OK makes the style and applies it to the selected paragraphs.
 */
@Composable
internal fun CreateStyleDialog(editor: DocumentEditor, onDismiss: () -> Unit) {
    val first = remember {
        var k = 1
        while (editor.newStyleIssue(Strings.get(R.string.style_default_name, k)) != null) k++
        Strings.get(R.string.style_default_name, k)
    }
    var name by remember { mutableStateOf(first) }
    val issue = editor.newStyleIssue(name)
    val p = editor.activeParagraph
    val run = (p.runs.firstOrNull { it.text.isNotBlank() } ?: p.runs.firstOrNull())?.format
    val base = editor.activeStyleId.let { id -> editor.document.styles[id]?.name?.let { n -> ParagraphStyle.entries.firstOrNull { it.wordName.equals(n, true) }?.display ?: n } ?: id }
    AeroDialog(
        title = stringResource(R.string.create_style_title),
        onDismiss = onDismiss,
        width = 440.dp,
        buttons = {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.btn_ok), { if (editor.createStyle(name) != null) onDismiss() }, primary = true, enabled = issue == null)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogLabel(stringResource(R.string.style_name))
            InputBox(name, { name = it }, Modifier.fillMaxWidth(), description = stringResource(R.string.style_name))
            when (issue) {
                DocumentEditor.StyleNameIssue.TAKEN -> FieldError(stringResource(R.string.style_name_taken))
                DocumentEditor.StyleNameIssue.EMPTY -> FieldError(stringResource(R.string.style_name_empty))
                null -> {}
            }
            Text(stringResource(R.string.style_based_on, base), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
            DialogLabel(stringResource(R.string.preview), Modifier.padding(top = 4.dp))
            // The paragraph's look on paper.
            Box(Modifier.fillMaxWidth().background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) { // design-style: paper
                Text(
                    p.plainText().trim().take(80).ifEmpty { "AaBbCc" },
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontSize = (run?.fontSizePt ?: 11f).coerceIn(9f, 24f).sp, fontFamily = TextStyling.familyOf(run?.fontFamily),
                    fontWeight = if (run?.bold == true) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (run?.italic == true) FontStyle.Italic else FontStyle.Normal,
                    color = run?.color?.let { Color(it) } ?: Color(0xFF1A1A1A),
                    textAlign = when (p.format.align) {
                        com.therealityteam.aeropages.model.TextAlign.CENTER -> androidx.compose.ui.text.style.TextAlign.Center
                        com.therealityteam.aeropages.model.TextAlign.RIGHT -> androidx.compose.ui.text.style.TextAlign.End
                        else -> androidx.compose.ui.text.style.TextAlign.Start
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A style in the pane: its name (in its look with a preview), the paragraph mark of a paragraph style, and its ⋯ menu. */
@Composable
private fun StyleRow(editor: DocumentEditor, s: ParagraphStyle, picked: Boolean, preview: Boolean, onModify: (ParagraphStyle) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ListRow(picked, { editor.setStyle(s) }) {
        val r = editor.resolvedStyle(s)
        val ink = PicoTheme.colorScheme.labelPrimary
        if (preview) Text(
            s.display, modifier = Modifier.weight(1f).padding(vertical = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = r.sizePt.coerceIn(12f, 20f).sp, fontFamily = TextStyling.familyOf(r.fontFamily),
            fontWeight = if (r.bold) FontWeight.SemiBold else FontWeight.Normal, fontStyle = if (r.italic) FontStyle.Italic else FontStyle.Normal,
            color = r.color?.let { Color(it) } ?: ink,
        ) else Text(s.display, modifier = Modifier.weight(1f), style = AeroType.body, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("¶", style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(horizontal = 4.dp))
        Box {
            TitleIconButton("ic_more", stringResource(R.string.more_actions), { menu = true }, size = 28.dp)
            if (menu) AnchoredFlyout({ menu = false }) {
                FlyoutMenu(width = 300.dp) {
                    MenuHeading(stringResource(R.string.style_in_use, s.display, editor.styleUses(s)))
                    MenuCommand(stringResource(R.string.update_style_to_match, s.display), { editor.updateStyleFromSelection(s); menu = false })
                    MenuCommand(stringResource(R.string.modify_style) + "…", { menu = false; onModify(s) }, icon = "ic_edit")
                    MenuDivider()
                    MenuCommand(stringResource(R.string.reset_style), { editor.resetStyle(s); menu = false })
                }
            }
        }
    }
}

/** Word's Modify Style as a dialog (from the Styles pane), for [style]. */
@Composable
internal fun ModifyStyleDialog(editor: DocumentEditor, style: ParagraphStyle, onDismiss: () -> Unit) {
    AeroDialog(stringResource(R.string.label_modify_style, style.display), onDismiss, width = 560.dp, buttons = {}) {
        Box(Modifier.width(560.dp), contentAlignment = Alignment.TopStart) { ModifyStylePanel(editor, style, heading = false, onPicked = onDismiss) }
    }
}

