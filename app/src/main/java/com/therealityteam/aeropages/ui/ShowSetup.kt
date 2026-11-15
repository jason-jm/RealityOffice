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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.CustomShow
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShowPlan
import com.therealityteam.aeropages.model.ShowSetup
import com.therealityteam.aeropages.model.ShowType

/**
 * The Slide Show tab as PowerPoint lays it out: start the show (from the beginning, from this slide, a custom show), the
 * ways it can be given (presenter view, on the wall, in a window of its own), Set Up (the setup dialog, Hide Slide,
 * Rehearse Timings) and the three options PowerPoint keeps beside them as ticks.
 */
@Composable
internal fun ShowTabContent(
    editor: SlideEditor,
    onPlay: () -> Unit, onPlayCurrent: () -> Unit, onPresenter: () -> Unit,
    customMenuOpen: Boolean, onCustomMenu: () -> Unit,
    onShowSetup: () -> Unit, onRehearse: () -> Unit,
    onDetachedShow: () -> Unit, onSpatialShow: () -> Unit, onExportPdf: () -> Unit,
    group: @Composable (String, @Composable () -> Unit) -> Unit,
) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val p = editor.presentation
    group(stringResource(R.string.group_start_slide_show)) {
        RibbonButton("▶", stringResource(R.string.show_from_beginning), onPlay)
        RibbonButton("⏵", stringResource(R.string.show_from_current_slide), onPlayCurrent)
        RibbonButton("x_customshow", stringResource(R.string.show_custom_show), onCustomMenu, selected = customMenuOpen, dropdown = true)
    }
    group(stringResource(R.string.group_monitors)) {
        RibbonButton("👤", stringResource(R.string.presenter_view), onPresenter)
        RibbonButton("▣", stringResource(R.string.present_in_space), onSpatialShow)
        RibbonButton("x_newwindow", stringResource(R.string.detach_slide_show), onDetachedShow)
    }
    group(stringResource(R.string.group_set_up)) {
        RibbonButton("x_showsetup", stringResource(R.string.setup_slide_show), onShowSetup)
        RibbonButton("x_hideslide", stringResource(R.string.hide_slide), editor::toggleHidden, selected = editor.slide.hidden)
        RibbonButton("x_rehearse", stringResource(R.string.rehearse_timings), onRehearse)
        // PowerPoint's ticks beside Set Up: loop, timings, narration.
        Column(verticalArrangement = Arrangement.spacedBy(0.dp), modifier = Modifier.padding(start = 4.dp)) {
            SmallCheck(stringResource(R.string.show_option_loop), p.loopShow || p.show.type == ShowType.KIOSK, enabled = p.show.type != ShowType.KIOSK) { editor.setLoopShow(!p.loopShow) }
            SmallCheck(stringResource(R.string.use_timings), p.useTimings) { editor.setUseTimings(!p.useTimings) }
            SmallCheck(stringResource(R.string.play_narration), p.showNarration) { editor.setShowNarration(!p.showNarration) }
        }
    }
    group(stringResource(R.string.group_export)) {
        RibbonButton("export_pdf", stringResource(R.string.export_pdf), onExportPdf)
    }
}

/** A tick and its label at the ribbon's small size, three to a group. */
@Composable
private fun SmallCheck(label: String, on: Boolean, enabled: Boolean = true, toggle: () -> Unit) =
    CheckOption(label, on, toggle, Modifier.height(26.dp), enabled = enabled, small = true)

/** Custom Slide Show ▾: each custom show to play, and Custom Shows… to make and change them. */
@Composable
internal fun CustomShowPanel(editor: SlideEditor, onPlay: (String) -> Unit, onManage: () -> Unit) {
    val p = editor.presentation
    // PowerPoint's Custom Slide Show menu: each show to play, then Custom Shows….
    FlyoutMenu(width = 300.dp) {
        if (p.customShows.isEmpty()) MenuCommand(stringResource(R.string.custom_show_none), {}, enabled = false)
        p.customShows.forEach { cs -> MenuCommand(cs.name, { onPlay(cs.name) }, icon = "ic_play") }
        MenuDivider()
        MenuCommand(stringResource(R.string.custom_show_manage), onManage, icon = "ic_custom_show")
    }
}

/** Set Up Slide Show, as a draft the dialog's OK applies in one undo step. */
private data class SetupDraft(
    val type: ShowType, val animations: Boolean, val narration: Boolean, val loop: Boolean, val timings: Boolean,
    /** 0: all the slides, 1: from … to, 2: a custom show. */
    val range: Int, val from: Int, val to: Int, val custom: String?,
) {
    fun setup(): ShowSetup = ShowSetup(type, animations, from = if (range == 1) from else 0, to = if (range == 1) to else 0, custom = if (range == 2) custom else null)

    companion object {
        fun of(p: Presentation): SetupDraft {
            val s = p.show
            val custom = s.custom?.takeIf { n -> p.customShows.any { it.name == n } }
            return SetupDraft(s.type, s.animations, p.showNarration, p.loopShow, p.useTimings,
                range = when { custom != null -> 2; s.from > 0 -> 1; else -> 0 },
                from = s.from.coerceAtLeast(1), to = if (s.to > 0) s.to else p.slides.size, custom = custom ?: p.customShows.firstOrNull()?.name)
        }
    }
}

/**
 * Set Up Slide Show (PowerPoint's dialog): the show type, its options, which slides it plays — all of them, a range, a
 * custom show — and how it moves on; what the setup plays is counted as it is changed.
 */
@Composable
internal fun ShowSetupDialog(editor: SlideEditor, onDismiss: () -> Unit, onCustomShows: () -> Unit) {
    val p = editor.presentation
    var d by remember(p) { mutableStateOf(SetupDraft.of(p)) }
    val n = p.slides.size
    WideDialog(title = stringResource(R.string.setup_slide_show), onDismiss = onDismiss, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onDismiss)
            DialogButton(stringResource(R.string.apply), {
                editor.setShowSetup(d.setup(), loop = d.loop, timings = d.timings, narration = d.narration)
                onDismiss()
            }, primary = true)
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.show_type))
                RadioRow(stringResource(R.string.show_type_speaker), d.type == ShowType.SPEAKER) { d = d.copy(type = ShowType.SPEAKER) }
                RadioRow(stringResource(R.string.show_type_window), d.type == ShowType.WINDOW) { d = d.copy(type = ShowType.WINDOW) }
                RadioRow(stringResource(R.string.show_type_kiosk), d.type == ShowType.KIOSK) { d = d.copy(type = ShowType.KIOSK) }
                if (d.type == ShowType.KIOSK) Hint(stringResource(R.string.show_type_kiosk_desc))
                Box(Modifier.height(10.dp))
                SectionLabel(stringResource(R.string.show_options))
                // A kiosk always loops.
                Box(Modifier.alpha(if (d.type == ShowType.KIOSK) 0.45f else 1f)) {
                    CheckRow(stringResource(R.string.show_option_loop), d.loop || d.type == ShowType.KIOSK) { if (d.type != ShowType.KIOSK) d = d.copy(loop = !d.loop) }
                }
                CheckRow(stringResource(R.string.show_option_no_narration), !d.narration) { d = d.copy(narration = !d.narration) }
                CheckRow(stringResource(R.string.show_option_no_animation), !d.animations) { d = d.copy(animations = !d.animations) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                SectionLabel(stringResource(R.string.show_slides))
                RadioRow(stringResource(R.string.show_slides_all), d.range == 0) { d = d.copy(range = 0) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RadioRow(stringResource(R.string.show_slides_from), d.range == 1) { d = d.copy(range = 1) }
                    SlideNumber(d.from, 1, n) { v -> d = d.copy(range = 1, from = v, to = maxOf(d.to, v)) }
                    Text(stringResource(R.string.show_slides_to), style = AeroType.body)
                    SlideNumber(d.to, 1, n) { v -> d = d.copy(range = 1, to = v, from = minOf(d.from, v)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.alpha(if (p.customShows.isEmpty()) 0.45f else 1f)) {
                        RadioRow(stringResource(R.string.show_slides_custom), d.range == 2) { if (p.customShows.isNotEmpty()) d = d.copy(range = 2) }
                    }
                    if (p.customShows.isNotEmpty()) {
                        val names = p.customShows.map { it.name }
                        ComboField(d.custom?.takeIf { it in names } ?: names.first(), names, { it }, d.custom.takeIf { d.range == 2 }, { d = d.copy(range = 2, custom = it) }, width = 180.dp)
                    }
                }
                // On its own line, under the choice: its name is long in some languages (Benutzerdefinierte Präsentationen…).
                DialogButton(stringResource(R.string.custom_show_manage), onCustomShows, modifier = Modifier.padding(start = 28.dp), small = true)
                Box(Modifier.height(10.dp))
                SectionLabel(stringResource(R.string.advance_slides))
                Box(Modifier.alpha(if (d.type == ShowType.KIOSK) 0.45f else 1f)) {
                    RadioRow(stringResource(R.string.advance_manually), !d.timings && d.type != ShowType.KIOSK) { if (d.type != ShowType.KIOSK) d = d.copy(timings = false) }
                }
                RadioRow(stringResource(R.string.advance_using_timings), d.timings || d.type == ShowType.KIOSK) { d = d.copy(timings = true) }
                val rehearsed = p.slides.count { it.advanceMs > 0 }
                Hint(if (rehearsed == 0) stringResource(R.string.msg_not_rehearsed)
                    else stringResource(R.string.msg_rehearsed_progress, rehearsed, n, showClock(p.slides.sumOf { it.advanceMs } / 1000)))
                Box(Modifier.height(6.dp))
                // What the setup plays, counted as it changes.
                val plays = ShowPlan.of(Presentation(p.slides, show = d.setup(), customShows = p.customShows)).size
                Text(stringResource(R.string.show_slides_count, plays, n), style = AeroType.label, color = LocalAccent.current)
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(text, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(start = 28.dp))

/** A slide number between [min] and [max], stepped by − / +. */
@Composable
private fun SlideNumber(value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    SpinBox(value.toString(), { d -> onChange((value + d).coerceIn(min, max)) }, canLess = value > min, canMore = value < max, valueWidth = 28.dp, compact = true)
}

/**
 * Custom Shows (PowerPoint's Define Custom Show): the deck's custom shows — new, edit, copy, remove, show — and, editing
 * one, its name and its slides: those of the presentation on the left (a tap adds one), the show's in its order on the
 * right (moved up or down, taken out). Each change is one undo step.
 */
@Composable
internal fun CustomShowsDialog(editor: SlideEditor, onDismiss: () -> Unit, onPlay: (String) -> Unit, editFirst: Boolean = false) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val p = editor.presentation
    var selected by remember { mutableStateOf(p.customShows.firstOrNull()?.name) }
    // The show being edited: its name before (null for a new one) and the draft ([editFirst]: the first, for a dev hook).
    var editing by remember { mutableStateOf<Pair<String?, CustomShow>?>(if (editFirst) p.customShows.firstOrNull()?.let { it.name to it } else null) }
    val edit = editing
    if (edit != null) {
        CustomShowEditor(p, edit.first, edit.second, onCancel = { editing = null }) { done ->
            val shows = p.customShows.toMutableList()
            val at = shows.indexOfFirst { it.name == edit.first }
            if (at >= 0) shows[at] = done else shows.add(done)
            editor.setCustomShows(shows, renamed = edit.first?.let { mapOf(it to done.name) } ?: emptyMap())
            selected = done.name
            editing = null
        }
        return
    }
    WideDialog(title = stringResource(R.string.custom_shows_title), onDismiss = onDismiss, buttons = {
        DialogButton(stringResource(R.string.close), onDismiss)
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ListBox(Modifier.weight(1f), height = 240.dp,
                empty = if (p.customShows.isEmpty()) stringResource(R.string.custom_show_none) else null, emptyIcon = "ic_custom_show") {
                p.customShows.forEach { cs ->
                    val on = cs.name == selected
                    val count = cs.slides.count { id -> p.slides.any { it.id == id } }
                    ListRow(on, { selected = cs.name }) {
                        Text(cs.name, style = if (on) AeroType.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) else AeroType.body,
                            color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.custom_show_slide_count, count), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                    }
                }
            }
            val current = p.customShows.firstOrNull { it.name == selected }
            val newName = stringResource(R.string.custom_show_default_name, p.customShows.size + 1)
            val copyName = current?.let { stringResource(R.string.custom_show_copy_name, it.name) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(112.dp)) {
                DialogButton(stringResource(R.string.custom_show_new), { editing = null to CustomShow(unique(p, newName), emptyList()) }, modifier = Modifier.fillMaxWidth(), primary = p.customShows.isEmpty())
                DialogButton(stringResource(R.string.custom_show_edit), { current?.let { editing = it.name to it } }, modifier = Modifier.fillMaxWidth(), enabled = current != null)
                DialogButton(stringResource(R.string.custom_show_copy), {
                    if (current != null && copyName != null) {
                        val copy = current.copy(name = unique(p, copyName))
                        editor.setCustomShows(p.customShows + copy); selected = copy.name
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = current != null)
                DialogButton(stringResource(R.string.custom_show_remove), {
                    if (current != null) {
                        val rest = p.customShows.filter { it.name != current.name }
                        editor.setCustomShows(rest); selected = rest.firstOrNull()?.name
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = current != null)
                DialogButton(stringResource(R.string.custom_show_play), { current?.let { onPlay(it.name) } }, Modifier.fillMaxWidth(), primary = current != null, enabled = current != null)
            }
        }
    }
}

/** [name], or it with a number after it when another custom show of [p] has it. */
private fun unique(p: Presentation, name: String): String {
    if (p.customShows.none { it.name == name }) return name
    var k = 2
    while (p.customShows.any { it.name == "$name ($k)" }) k++
    return "$name ($k)"
}

/** Defining one custom show: its name, the presentation's slides to add from, the show's slides in their order. */
@Composable
private fun CustomShowEditor(p: Presentation, oldName: String?, start: CustomShow, onCancel: () -> Unit, onDone: (CustomShow) -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var picked by remember { mutableStateOf(start.slides.filter { id -> p.slides.any { it.id == id } }) }
    val taken = p.customShows.any { it.name == name.trim() && it.name != oldName }
    val ok = name.isNotBlank() && !taken && picked.isNotEmpty()
    val untitled = stringResource(R.string.label_untitled_heading)
    val hiddenMark = stringResource(R.string.custom_show_hidden)
    fun label(id: Long): String {
        val i = p.slides.indexOfFirst { it.id == id }
        val s = p.slides.getOrNull(i) ?: return ""
        return "${i + 1}. ${s.title.text.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { untitled }}" + if (s.hidden) " $hiddenMark" else ""
    }
    WideDialog(title = stringResource(R.string.custom_shows_title), onDismiss = onCancel, buttons = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(stringResource(R.string.cancel), onCancel)
            DialogButton(stringResource(R.string.apply), { if (ok) onDone(CustomShow(name.trim(), picked)) }, primary = true, enabled = ok)
        }
    }) {
        FieldRow(stringResource(R.string.custom_show_name), labelWidth = 128.dp) {
            InputBox(name, { name = it }, Modifier.width(320.dp))
            if (taken) FieldError(stringResource(R.string.custom_show_name_taken))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogLabel(stringResource(R.string.custom_show_in_deck))
                ListBox(height = 260.dp) {
                    p.slides.forEach { s ->
                        val inShow = s.id in picked
                        // A click adds the slide to the end of the show (again, if it is in it already: PowerPoint's).
                        ListRow(false, { picked = picked + s.id }) {
                            Text(label(s.id), style = AeroType.body, maxLines = 1,
                                color = if (inShow) PicoTheme.colorScheme.labelSecondary else PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
                            RibbonGlyph("ic_plus", LocalAccent.current, 18.dp)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogLabel(stringResource(R.string.custom_show_in_show))
                ListBox(height = 260.dp, empty = if (picked.isEmpty()) stringResource(R.string.custom_show_pick_hint) else null, emptyIcon = "ic_custom_show") {
                    picked.forEachIndexed { k, id ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 10.dp)) {
                            Text("${k + 1}", style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(22.dp))
                            Text(label(id), style = AeroType.body, maxLines = 1, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f))
                            TitleIconButton("ic_arrow_up", stringResource(R.string.anim_move_earlier), { picked = picked.toMutableList().also { java.util.Collections.swap(it, k, k - 1) } }, enabled = k > 0, size = 28.dp)
                            TitleIconButton("ic_arrow_down", stringResource(R.string.anim_move_later), { picked = picked.toMutableList().also { java.util.Collections.swap(it, k, k + 1) } }, enabled = k < picked.lastIndex, size = 28.dp)
                            TitleIconButton("ic_close", stringResource(R.string.custom_show_remove), { picked = picked.toMutableList().also { it.removeAt(k) } }, size = 28.dp)
                        }
                    }
                }
            }
        }
    }
}
