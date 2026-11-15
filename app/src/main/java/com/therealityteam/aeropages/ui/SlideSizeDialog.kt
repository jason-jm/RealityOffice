package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.SlideResize
import com.therealityteam.aeropages.model.SlideScale
import com.therealityteam.aeropages.model.SlideSize
import com.therealityteam.aeropages.model.SlideSizePreset

/** The name "Slides sized for" gives [preset] (null: a size of one's own). */
@Composable
internal fun slideSizeName(preset: SlideSizePreset?): String = stringResource(when (preset) {
    SlideSizePreset.STANDARD -> R.string.slide_size_standard
    SlideSizePreset.LETTER -> R.string.slide_size_letter
    SlideSizePreset.LEDGER -> R.string.slide_size_ledger
    SlideSizePreset.A3 -> R.string.slide_size_a3
    SlideSizePreset.A4 -> R.string.slide_size_a4_paper
    SlideSizePreset.B4_ISO -> R.string.slide_size_b4
    SlideSizePreset.B5_ISO -> R.string.slide_size_b5
    SlideSizePreset.SLIDES_35MM -> R.string.slide_size_35mm
    SlideSizePreset.OVERHEAD -> R.string.slide_size_overhead
    SlideSizePreset.BANNER -> R.string.slide_size_banner
    SlideSizePreset.SCREEN_16_9 -> R.string.slide_size_16_9
    SlideSizePreset.SCREEN_16_10 -> R.string.slide_size_16_10
    SlideSizePreset.WIDESCREEN -> R.string.slide_size_widescreen
    null -> R.string.slide_size_custom_name
})

/**
 * PowerPoint's Design ▸ Slide Size ▸ Custom Slide Size: "Slides sized for" (its sizes, in either orientation), the width and
 * height (centimetres or inches, typed or stepped — typing makes it a size of its own), Number slides from, the slides'
 * orientation, and the shape they take drawn beside. OK hands the size and first number to [onApply], which asks Maximize
 * or Ensure Fit when the slides change shape.
 */
@Composable
internal fun CustomSlideSizeDialog(editor: SlideEditor, onApply: (SlideSize, Int) -> Unit, onDismiss: () -> Unit) {
    val start = editor.presentation.slideSize
    var size by remember(start) { mutableStateOf(start) }
    var preset by remember(start) { mutableStateOf(SlideSizePreset.of(start)) }
    var first by remember(start) { mutableIntStateOf(editor.presentation.firstSlideNumber) }
    val portrait = size.heightPt > size.widthPt
    fun typed(w: Float, h: Float) {
        val next = SlideSize(w.coerceIn(SlideSize.MIN_PT, SlideSize.MAX_PT), h.coerceIn(SlideSize.MIN_PT, SlideSize.MAX_PT))
        // Typing keeps the named size only while it still is that size (either way round), as PowerPoint does.
        if (preset?.let { it.size == next || it.size == next.turned(false) } != true) preset = null
        size = next
    }
    val title = stringResource(R.string.slide_size)
    AeroDialog(title, onDismiss, width = 560.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
        DialogButton(stringResource(R.string.btn_ok), { onApply(SlideSize.of(size.widthPt, size.heightPt), first); onDismiss() }, primary = true)
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.width(320.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogLabel(stringResource(R.string.slides_sized_for))
                val options: List<SlideSizePreset?> = SlideSizePreset.entries + listOf(null)
                ComboField(slideSizeName(preset), options, { slideSizeName(it) }, preset, { p ->
                    preset = p
                    if (p != null) size = p.size.turned(portrait)
                }, width = 320.dp)
                LengthRow(stringResource(R.string.ps_width), size.widthPt, { typed(it, size.heightPt) }, min = SlideSize.MIN_PT, max = SlideSize.MAX_PT)
                LengthRow(stringResource(R.string.ps_height), size.heightPt, { typed(size.widthPt, it) }, min = SlideSize.MIN_PT, max = SlideSize.MAX_PT)
                val numberLabel = stringResource(R.string.slide_number_from)
                FieldRow(numberLabel, labelWidth = 136.dp) {
                    var text by remember { mutableStateOf(first.toString()) }
                    SpinFrame({ d -> first = (first + d).coerceIn(0, 9999); text = first.toString() }, description = numberLabel,
                        canLess = first > 0, canMore = first < 9999) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = text,
                            onValueChange = { t -> val digits = t.filter { it.isDigit() }.take(4); text = digits; digits.toIntOrNull()?.let { first = it } },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            textStyle = AeroType.command.copy(color = PicoTheme.colorScheme.labelPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.End),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(LocalAccent.current),
                            modifier = Modifier.width(56.dp).padding(end = 4.dp).semantics { contentDescription = numberLabel },
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogLabel(stringResource(R.string.orientation))
                RadioOption(stringResource(R.string.portrait), portrait, { size = size.turned(true) })
                RadioOption(stringResource(R.string.landscape), !portrait, { size = size.turned(false) })
                Box(Modifier.padding(top = 8.dp).size(150.dp, 120.dp), contentAlignment = Alignment.Center) {
                    val ink = PicoTheme.colorScheme.labelTertiary
                    val accent = LocalAccent.current
                    Canvas(Modifier.size(150.dp, 120.dp)) { slidePicture(size, ink, accent) }
                }
            }
        }
    }
}

/** A slide of [size] fitted into the canvas: white paper, a hairline edge, a title bar and a few lines of text. */
private fun DrawScope.slidePicture(size: SlideSize, ink: Color, accent: Color) {
    val k = minOf(this.size.width / size.widthPt, this.size.height / size.heightPt) * 0.92f
    val w = size.widthPt * k; val h = size.heightPt * k
    val r = Rect(Offset((this.size.width - w) / 2f, (this.size.height - h) / 2f), Size(w, h))
    drawRect(Color.White, r.topLeft, r.size)
    drawRect(ink, r.topLeft, r.size, style = Stroke(1.dp.toPx()))
    content(r, accent)
}

/** What sits on a slide filling [r]: a title bar and three lines of text, in proportion. */
private fun DrawScope.content(r: Rect, accent: Color) {
    val line = (r.height * 0.06f).coerceAtLeast(1.5f)
    drawRect(accent.copy(alpha = 0.85f), Offset(r.left + r.width * 0.1f, r.top + r.height * 0.14f), Size(r.width * 0.8f, line * 2f))
    for (i in 0 until 3) {
        drawRect(Color(0xFFB8BEC6), Offset(r.left + r.width * 0.1f, r.top + r.height * (0.42f + i * 0.16f)), Size(r.width * (if (i == 2) 0.5f else 0.8f), line))
    }
}

/**
 * PowerPoint's question when the slides change shape: maximize the content (as large as the new slide allows, some running
 * past its edges) or scale it down to ensure it fits — each drawn from the actual sizes, [from] to [to], the content where
 * it would land on the new slide (dashed past its edges).
 */
@Composable
internal fun SlideScaleDialog(from: SlideSize, to: SlideSize, onPick: (SlideScale) -> Unit, onDismiss: () -> Unit) {
    AeroDialog(stringResource(R.string.slide_size), onDismiss, width = 500.dp, buttons = {
        DialogButton(stringResource(R.string.cancel), onDismiss)
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.slide_scale_prompt), style = AeroType.body, color = PicoTheme.colorScheme.labelPrimary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                listOf(SlideScale.MAXIMIZE to (R.string.slide_scale_maximize to R.string.slide_scale_maximize_detail),
                    SlideScale.ENSURE_FIT to (R.string.slide_scale_fit to R.string.slide_scale_fit_detail)).forEach { (scale, names) ->
                    Tile(stringResource(names.first), stringResource(names.second), false, { onPick(scale); onDismiss() }, width = 212.dp) {
                        val ink = PicoTheme.colorScheme.labelTertiary
                        val accent = LocalAccent.current
                        Canvas(Modifier.fillMaxWidth().height(100.dp)) { scaledPicture(from, to, scale, ink, accent) }
                    }
                }
            }
        }
    }
}

/** The new slide [to] fitted into the canvas and the old one's content scaled onto it by [scale], dashed where it runs off. */
private fun DrawScope.scaledPicture(from: SlideSize, to: SlideSize, scale: SlideScale, ink: Color, accent: Color) {
    val s = SlideResize.factor(from, to, scale)
    // Room for what runs past the edges: the larger of the slide and the scaled content decides the drawing's scale.
    val cw = from.widthPt * s; val ch = from.heightPt * s
    val k = minOf(size.width / maxOf(to.widthPt, cw), size.height / maxOf(to.heightPt, ch)) * 0.9f
    val slide = Rect(Offset((size.width - to.widthPt * k) / 2f, (size.height - to.heightPt * k) / 2f), Size(to.widthPt * k, to.heightPt * k))
    val content = Rect(Offset((size.width - cw * k) / 2f, (size.height - ch * k) / 2f), Size(cw * k, ch * k))
    drawRect(Color.White, slide.topLeft, slide.size)
    // The content: faint past the edges, full on the slide.
    drawRect(accent.copy(alpha = 0.06f), content.topLeft, content.size)
    clipRect(slide.left, slide.top, slide.right, slide.bottom) { content(content, accent) }
    drawRect(accent.copy(alpha = 0.7f), content.topLeft, content.size,
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
    drawRect(ink, slide.topLeft, slide.size, style = Stroke(1.dp.toPx()))
}

/** Whether moving the deck to [to] needs the Maximize / Ensure Fit question. */
internal fun asksToScale(editor: SlideEditor, to: SlideSize): Boolean = SlideResize.reshapes(editor.presentation.slideSize, to)

/** A menu's picture of a slide [size]'s shape (PowerPoint's 4:3 and 16:9 thumbnails), in the accent when it is the deck's. */
@Composable
internal fun SlideShapeIcon(size: SlideSize, on: Boolean) {
    val ink = if (on) LocalAccent.current else PicoTheme.colorScheme.labelSecondary
    Canvas(Modifier.size(28.dp, 20.dp)) {
        val k = minOf(this.size.width / size.widthPt, this.size.height / size.heightPt)
        val w = size.widthPt * k; val h = size.heightPt * k
        val r = Rect(Offset((this.size.width - w) / 2f, (this.size.height - h) / 2f), Size(w, h))
        val stroke = 1.5.dp.toPx()
        drawRect(ink, Offset(r.left + stroke / 2, r.top + stroke / 2), Size(r.width - stroke, r.height - stroke), style = Stroke(stroke))
        drawRect(ink, Offset(r.left + r.width * 0.2f, r.top + r.height * 0.3f), Size(r.width * 0.6f, stroke))
    }
}
