package com.therealityteam.aeropages.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.DeskFiles
import com.therealityteam.aeropages.format.DeskKind
import com.therealityteam.aeropages.model.Presentation
import java.io.File

/**
 * PowerPoint's Reuse Slides pane (Home ▸ New Slide ▾ ▸ Reuse Slides): Browse… (or one of the recent presentations) opens a
 * deck, its slides shown as thumbnails; a click inserts that slide after the current one, Insert All Slides every one,
 * and "Keep source formatting" decides whether they keep their own design or take this deck's theme.
 */
@Composable
internal fun ReuseSlidesPane(
    editor: SlideEditor,
    source: Presentation?,
    sourcePath: String?,
    onBrowse: () -> Unit,
    onOpenRecent: (String) -> Unit,
    onStatus: (String) -> Unit,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    var keepSource by remember { mutableStateOf(false) }
    fun reuse(indices: List<Int>) {
        val p = source ?: return
        val n = editor.reuseSlides(p, indices, keepSource, sourcePath?.let { File(it).nameWithoutExtension } ?: "Reused")
        if (n > 0) onStatus(Strings.get(R.string.status_slides_reused, n))
    }
    Column(modifier.paneSurface(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PaneHeader(stringResource(R.string.reuse_slides), onClose)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DialogButton(stringResource(R.string.reuse_browse), onBrowse, primary = source == null, small = true)
            if (sourcePath != null) Text(File(sourcePath).name, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (source == null) {
            // The recent presentations, as the pane's first places to look (this deck left out).
            val context = LocalContext.current
            val recent = remember { RecentFiles.load(context).filter { DeskFiles.kindOfOrNull(it.path) == DeskKind.SLIDES && it.path != editor.presentation.sourcePath }.take(8) }
            if (recent.isEmpty()) {
                PaneEmpty("ic_new_slide", stringResource(R.string.reuse_hint), Modifier.fillMaxWidth().weight(1f))
                return@Column
            }
            DialogLabel(stringResource(R.string.reuse_recent))
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (r in recent) ListRow(false, { onOpenRecent(r.path) }, Modifier.fillMaxWidth()) {
                    RibbonGlyph("ic_new_slide", LocalAccent.current, 18.dp)
                    Text(File(r.path).name, style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            return@Column
        }
        Text(stringResource(R.string.reuse_click_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Two to a row, each with its number under it.
            source.slides.indices.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (i in pair) ReuseThumb(source, i, { reuse(listOf(i)) }, Modifier.weight(1f))
                    if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                }
            }
        }
        CheckOption(stringResource(R.string.reuse_keep_source), keepSource, { keepSource = !keepSource })
        DialogButton(stringResource(R.string.reuse_insert_all), { reuse(source.slides.indices.toList()) }, modifier = Modifier.fillMaxWidth(), small = true)
    }
}

/** One slide of the deck being reused: its picture (a click inserts it) and its number. */
@Composable
private fun ReuseThumb(deck: Presentation, index: Int, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(6.dp)
    val label = stringResource(R.string.reuse_slides) + " " + (index + 1)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DeckThumbnail(deck, deck.slides[index], index + 1, 0,
            Modifier.fillMaxWidth().aspectRatio(deck.slideSize.aspect).clip(shape)
                .border(1.dp, PicoTheme.colorScheme.dividerLine, shape)
                .press(onClick = onClick).semantics { contentDescription = label })
        Text((index + 1).toString(), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(bottom = 2.dp))
    }
}
