package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.SlideLinks

/** What a place in the show is called in the link dialog and the editor: the show's steps by name, a slide by its number and title. */
@Composable
internal fun slideLinkLabel(editor: SlideEditor, link: String): String = when (link) {
    SlideLinks.NEXT -> stringResource(R.string.link_next_slide)
    SlideLinks.PREVIOUS -> stringResource(R.string.link_previous_slide)
    SlideLinks.FIRST -> stringResource(R.string.link_first_slide)
    SlideLinks.LAST -> stringResource(R.string.link_last_slide)
    SlideLinks.END -> stringResource(R.string.link_end_show)
    else -> SlideLinks.indexIn(editor.presentation, link)?.let { i ->
        stringResource(R.string.link_slide_n, i + 1, editor.presentation.slides[i].title.text.lines().firstOrNull()?.trim().orEmpty())
    } ?: link
}

/**
 * Insert Hyperlink in Slides: a web address, or a place in the show — the next, previous, first or
 * last slide, the end of the show, or a slide of the presentation. It links the selected text, or —
 * a shape or picture selected with no text selected — makes a click on that object go there (as
 * PowerPoint's link on an object does); with a link already there it can change or remove it.
 */
@Composable
internal fun SlideLinkDialog(editor: SlideEditor, onDismiss: () -> Unit, startInShow: Boolean = false) {
    val current = remember { editor.currentLink }
    var inShow by remember { mutableStateOf(startInShow || SlideLinks.inShow(current)) }
    var url by remember { mutableStateOf(current?.takeIf { !SlideLinks.inShow(it) } ?: "https://") }
    var picked by remember { mutableStateOf(current?.takeIf { SlideLinks.inShow(it) }) }
    val slides = editor.presentation.slides
    AeroDialog(title = stringResource(if (current != null) R.string.link_edit else R.string.title_insert_hyperlink), onDismiss = onDismiss, width = 460.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null) DialogButton(stringResource(R.string.remove_link), { editor.applyLink(null); onDismiss() })
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.apply), {
                    val target = if (inShow) picked else url.trim().takeIf { it.isNotEmpty() && it != "https://" }
                    if (target != null) { editor.applyLink(target); onDismiss() }
                }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.width(460.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceSegments(listOf(false, true), inShow, { stringResource(if (it) R.string.link_this_presentation else R.string.link_web) }, { inShow = it }, width = 440.dp)
                }
                Text(stringResource(R.string.link_object_tip), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                if (!inShow) {
                    InputBox(url, { url = it }, Modifier.fillMaxWidth(), placeholder = "https://")
                } else Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                ) {
                    SlideLinks.JUMPS.forEach { j -> ShowPlaceRow(slideLinkLabel(editor, j), picked == j) { picked = j } }
                    slides.forEachIndexed { i, s ->
                        val link = SlideLinks.toSlide(s)
                        val label = slideLinkLabel(editor, link) + if (s.hidden) " " + stringResource(R.string.link_hidden_slide) else ""
                        ShowPlaceRow(label, picked == link, top = i == 0) { picked = link }
                    }
                }
            }
        }
}

/** A place in the show the link can go to, picked or not; [top]: the first slide, set apart from the show's steps above it. */
@Composable
private fun ShowPlaceRow(text: String, picked: Boolean, top: Boolean = false, pick: () -> Unit) =
    ListRow(text, picked, pick, Modifier.padding(top = if (top) 8.dp else 0.dp))
