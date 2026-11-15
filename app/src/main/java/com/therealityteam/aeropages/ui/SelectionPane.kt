package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideMedia
import com.therealityteam.aeropages.model.SlideObject
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable

/**
 * PowerPoint's Selection Pane (Home ▸ Arrange ▸ Selection Pane, Alt+F10): the current slide's objects, the one drawn on
 * top first — tapped to select, renamed in place (the pencil, or tapping the selected one again), shown or hidden by
 * their eye, and moved forward or backward among their kind by the arrows in the header; Show All and Hide All above.
 */
@Composable
internal fun SelectionPane(editor: SlideEditor, modifier: Modifier, onClose: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val objects = editor.paneObjects()
    val picked = editor.selectedObject
    var renaming by remember { mutableStateOf<SlideObject?>(null) }
    Column(modifier.paneSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneHeader(stringResource(R.string.selection_pane), onClose) {
            TitleIconButton("ic_chevron_up", stringResource(R.string.bring_forward), { picked?.let { editor.moveInPane(it, up = true) } },
                enabled = picked != null && editor.canMoveInPane(picked, up = true), size = 32.dp)
            TitleIconButton("ic_chevron_down", stringResource(R.string.send_backward), { picked?.let { editor.moveInPane(it, up = false) } },
                enabled = picked != null && editor.canMoveInPane(picked, up = false), size = 32.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DialogButton(stringResource(R.string.pane_show_all), { editor.setAllObjectsHidden(false) }, enabled = objects.any { it.hidden }, small = true)
            DialogButton(stringResource(R.string.pane_hide_all), { editor.setAllObjectsHidden(true) }, enabled = objects.any { !it.hidden }, small = true)
        }
        if (objects.isEmpty()) {
            PaneEmpty("ic_select_multiple", stringResource(R.string.sel_empty), Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        val labels = defaultLabels(editor)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (o in objects) {
                val label = o.objectName ?: labels[o] ?: ""
                SelectionRow(o, label, selected = o === picked, editing = renaming === o,
                    onPick = { if (o === picked && !o.hidden) renaming = o else { renaming = null; editor.selectObject(o) } },
                    onRename = { renaming = o },
                    onRenamed = { name -> editor.renameObject(o, name); renaming = null },
                    onEye = { editor.setObjectHidden(o, !o.hidden) })
            }
        }
    }
}

/** One object: its kind's icon, its name (or a box to rename it), a pencil and its eye; a hidden one faded. */
@Composable
private fun SelectionRow(o: SlideObject, label: String, selected: Boolean, editing: Boolean, onPick: () -> Unit, onRename: () -> Unit,
                         onRenamed: (String) -> Unit, onEye: () -> Unit) {
    ListRow(selected, onPick, Modifier.fillMaxWidth()) {
        Box(Modifier.alpha(if (o.hidden) 0.45f else 1f)) { RibbonGlyph(iconOf(o), LocalAccent.current, 18.dp) }
        if (editing) {
            var text by remember(o) { mutableStateOf(label) }
            InputBox(text, { text = it }, Modifier.weight(1f), description = stringResource(R.string.sel_rename),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onRenamed(text) }))
            TitleIconButton("ic_check", stringResource(R.string.sel_rename), { onRenamed(text) }, size = 32.dp)
        } else {
            Text(label, style = AeroType.body, color = if (o.hidden) PicoTheme.colorScheme.labelTertiary else PicoTheme.colorScheme.labelPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 2.dp))
            if (selected) TitleIconButton("ic_edit", stringResource(R.string.sel_rename), onRename, size = 32.dp)
            TitleIconButton(if (o.hidden) "ic_eye_slash" else "ic_eye", stringResource(if (o.hidden) R.string.sel_show else R.string.sel_hide), onEye, size = 32.dp)
        }
    }
}

/** The icon for [o]'s kind. */
private fun iconOf(o: SlideObject): String = when (o) {
    is SlideChart -> "ic_chart"
    is SlideTable -> "ic_table"
    is SlideImage -> when (o.media?.kind) { SlideMedia.Kind.VIDEO -> "ic_video"; SlideMedia.Kind.AUDIO -> "ic_audio"; null -> "ic_picture" }
    is SlideShape -> when (o.type) { ShapeType.TEXTBOX -> "ic_textbox"; ShapeType.LINE -> "ic_shape_line"; ShapeType.ELLIPSE -> "ic_shape_oval"; else -> "ic_shape_rect" }
    else -> "ic_shape_rect"
}

/**
 * The names the pane gives objects without one of their own, as PowerPoint numbers them: "Rectangle 2", "Picture 1",
 * "TextBox 3" — each kind counted in drawing order.
 */
@Composable
private fun defaultLabels(editor: SlideEditor): Map<SlideObject, String> {
    val out = java.util.IdentityHashMap<SlideObject, String>()
    val slide = editor.slide
    val counts = HashMap<Int, Int>()
    fun name(res: Int): Int { val n = (counts[res] ?: 0) + 1; counts[res] = n; return n }
    val pictureRes = R.string.sel_picture; val videoRes = R.string.sel_video; val audioRes = R.string.sel_audio
    for (img in slide.images) {
        val res = when (img.media?.kind) { SlideMedia.Kind.VIDEO -> videoRes; SlideMedia.Kind.AUDIO -> audioRes; null -> pictureRes }
        out[img] = stringResource(res, name(res))
    }
    for (sh in slide.shapes) {
        val res = when (sh.type) {
            ShapeType.TEXTBOX -> R.string.sel_textbox; ShapeType.RECT -> R.string.sel_rectangle; ShapeType.ELLIPSE -> R.string.sel_oval
            ShapeType.LINE -> R.string.sel_line; else -> R.string.sel_shape
        }
        out[sh] = stringResource(res, name(res))
    }
    for (t in slide.tables) out[t] = stringResource(R.string.sel_table, name(R.string.sel_table))
    for (c in slide.charts) out[c] = stringResource(R.string.sel_chart, name(R.string.sel_chart))
    return out
}
