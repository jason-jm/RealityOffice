package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.SlideLinks

/** The "URL…" entry of the Hyperlink to: list (any other entry is a place in the show). */
private const val URL_ENTRY = "\u0000url"

/** One tab of Action Settings: none, or a hyperlink to a place in the show or to an address. */
private class ActionDraft(action: String?) {
    var linked by mutableStateOf(action != null)
    var place by mutableStateOf(if (action == null) SlideLinks.NEXT else if (SlideLinks.inShow(action)) action else URL_ENTRY)
    var url by mutableStateOf(action?.takeIf { !SlideLinks.inShow(it) } ?: "https://")

    /** The action this tab sets: null for none, or a URL left as typed with nothing after the scheme. */
    val action: String? get() = when {
        !linked -> null
        place != URL_ENTRY -> place
        else -> url.trim().takeIf { it.isNotEmpty() && it != "https://" && it != "http://" }
    }
}

/**
 * PowerPoint's Action Settings (Insert ▸ Links ▸ Action, and as an action button goes in): what the picked shape or
 * picture does in the show when it is clicked (Mouse Click) and when the pointer comes over it (Mouse Over) — nothing,
 * or a hyperlink to the next, previous, first or last slide, the end of the show, a slide of the presentation or an
 * address. One step back for both tabs.
 */
@Composable
internal fun ActionSettingsDialog(editor: SlideEditor, onDismiss: () -> Unit) {
    val current = remember { editor.targetActions }
    if (current == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onDismiss() }; return }
    val click = remember { ActionDraft(current.first) }
    val hover = remember { ActionDraft(current.second) }
    var overTab by remember { mutableStateOf(com.therealityteam.aeropages.BuildConfig.DEBUG && devDialogProp() == "actionhover") }
    val places = remember(editor.presentation.slides.size) { SlideLinks.JUMPS + editor.presentation.slides.map { SlideLinks.toSlide(it) } + URL_ENTRY }
    val urlLabel = stringResource(R.string.action_url)
    val draft = if (overTab) hover else click
    AeroDialog(title = stringResource(R.string.action_settings), onDismiss = onDismiss, width = 460.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), { editor.setObjectActions(click.action, hover.action); onDismiss() }, primary = true)
    }) {
        Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DialogTabs(listOf(false, true), overTab, { stringResource(if (it) R.string.action_mouse_over else R.string.action_mouse_click) }, { overTab = it }, width = 420.dp)
            DialogLabel(stringResource(if (overTab) R.string.action_on_hover else R.string.action_on_click))
            RadioOption(stringResource(R.string.action_none), !draft.linked, { draft.linked = false })
            RadioOption(stringResource(R.string.action_hyperlink_to), draft.linked, { draft.linked = true })
            Column(Modifier.padding(start = 30.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ComboField(
                    if (draft.place == URL_ENTRY) urlLabel else slideLinkLabel(editor, draft.place),
                    places, { if (it == URL_ENTRY) urlLabel else slideLinkLabel(editor, it) }, draft.place, { draft.place = it },
                    width = 390.dp, enabled = draft.linked,
                )
                if (draft.linked && draft.place == URL_ENTRY)
                    InputBox(draft.url, { draft.url = it }, Modifier.fillMaxWidth(), placeholder = "https://", description = urlLabel)
            }
            Text(stringResource(R.string.action_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        }
    }
}
