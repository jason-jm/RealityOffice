package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.CoverDesign
import com.therealityteam.aeropages.model.CoverPages
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.Texts

/**
 * Word's Cover Page gallery (Insert ▸ Pages ▸ Cover Page): the built-in covers drawn as small pages, two to a row — a
 * tap puts that cover at the start of the document, replacing the one it has — and Remove Current Cover Page.
 */
@Composable
internal fun CoverPageGallery(editor: DocumentEditor, onDone: () -> Unit) {
    val accent = Color(coverAccent(editor))
    val names = mapOf(CoverDesign.SIMPLE to R.string.cover_simple, CoverDesign.BAND to R.string.cover_band,
        CoverDesign.SIDELINE to R.string.cover_sideline, CoverDesign.BLOCK to R.string.cover_block)
    FlyoutMenu(width = 280.dp) {
        MenuHeading(stringResource(R.string.cover_builtin))
        CoverDesign.entries.chunked(2).forEach { row ->
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { d ->
                    Tile(stringResource(names.getValue(d)), null, false, { editor.insertCoverPage(d, coverFields(editor)); onDone() }, width = 118.dp) {
                        Canvas(Modifier.size(72.dp, 94.dp)) { coverArt(d, accent) }
                    }
                }
            }
        }
        MenuDivider()
        MenuCommand(stringResource(R.string.cover_remove), { editor.removeCoverPage(); onDone() }, icon = "ic_delete", enabled = editor.hasCoverPage)
    }
}

/** The colour a cover takes: the document's first heading style's, Word's blue when it has none. */
internal fun coverAccent(editor: DocumentEditor): Long =
    editor.document.styles.resolve(editor.document.styles.idOf(com.therealityteam.aeropages.model.ParagraphStyle.HEADING1)).color ?: 0xFF2F5496

/** What a new cover says: the file's title, subject and author (Word's placeholders where they are empty) and today's date. */
internal fun coverFields(editor: DocumentEditor): CoverPages.Fields {
    val props = editor.document.props
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG, java.util.Locale.getDefault()).format(java.util.Date())
    return CoverPages.Fields(
        props.title.ifBlank { Texts.get(TextId.COVER_TITLE) },
        props.subject.ifBlank { Texts.get(TextId.COVER_SUBTITLE) },
        props.author.ifBlank { Texts.get(TextId.COVER_AUTHOR) },
        date,
    )
}

/** A small page showing how [design] sets a cover out: grey bars for its text, [accent] where it puts colour. */
internal fun DrawScope.coverArt(design: CoverDesign, accent: Color) {
    val w = size.width; val h = size.height
    val r = CornerRadius(3.dp.toPx())
    drawRoundRect(Color.White, cornerRadius = r)
    val ink = Color(0xFF3A4350); val grey = Color(0xFFB8BEC6); val white = Color.White
    val m = w * 0.14f
    val bar = 3.dp.toPx()
    fun line(x0: Float, y: Float, x1: Float, c: Color, t: Float = bar) = drawLine(c, Offset(x0, y), Offset(x1, y), t, cap = StrokeCap.Round)
    when (design) {
        CoverDesign.SIMPLE -> {
            line(m, h * 0.32f, w - m, ink, bar * 1.8f)
            line(m, h * 0.40f, w - m, accent, bar * 0.7f)
            line(m, h * 0.47f, w * 0.62f, grey)
            line(m, h * 0.78f, w * 0.48f, ink)
            line(m, h * 0.85f, w * 0.38f, grey)
        }
        CoverDesign.BAND -> {
            drawRect(accent, Offset(0f, h * 0.22f), Size(w, h * 0.22f))
            line(m, h * 0.30f, w - m, white, bar * 1.8f)
            line(m, h * 0.37f, w * 0.6f, white)
            line(m, h * 0.78f, w * 0.48f, ink)
            line(m, h * 0.85f, w * 0.38f, grey)
        }
        CoverDesign.SIDELINE -> {
            line(m, h * 0.18f, w * 0.42f, grey)
            drawLine(accent, Offset(m, h * 0.26f), Offset(m, h * 0.44f), bar * 1.4f)
            line(m + bar * 2.6f, h * 0.30f, w - m, ink, bar * 1.8f)
            line(m + bar * 2.6f, h * 0.39f, w * 0.68f, accent)
            line(m, h * 0.82f, w * 0.48f, ink)
        }
        CoverDesign.BLOCK -> {
            drawRoundRect(accent, cornerRadius = r)
            line(m, h * 0.36f, w - m, white, bar * 1.8f)
            line(m, h * 0.44f, w * 0.6f, white.copy(alpha = 0.85f))
            line(m, h * 0.78f, w * 0.48f, white)
            line(m, h * 0.85f, w * 0.38f, white.copy(alpha = 0.75f))
        }
    }
    drawRoundRect(Color(0x33000000), cornerRadius = r, style = Stroke(1.dp.toPx()))
}
