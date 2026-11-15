package com.therealityteam.aeropages.ui

import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.format.pdf.ShapePainter
import com.therealityteam.aeropages.model.ArrowHead
import com.therealityteam.aeropages.model.LineDash
import com.therealityteam.aeropages.model.LineEnd
import com.therealityteam.aeropages.model.ShapeShadow
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.SlideShape

/**
 * [s]'s fill, outline, line and arrowheads, flipped as it is but not turned (the caller turns the
 * shape, text and all), [inset] px in from each side, [pxPerPt] px to a point — drawn by the PDF's
 * own painter, so the screen and the PDF never differ. [version] changes with every edit: a shape
 * is the same object after one, and its drawing must still follow.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun ShapeGeometry(s: SlideShape, pxPerPt: Float, version: Int, modifier: Modifier = Modifier, inset: Float = 0f) {
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") version
        drawIntoCanvas { c -> ShapePainter.shape(c.nativeCanvas, s, inset, inset, size.width - 2 * inset, size.height - 2 * inset, pxPerPt) }
    }
}

/**
 * A shape as it looks: its geometry (see [ShapeGeometry]), turned as the shape is — its text
 * neither mirrored nor clipped, placed top, middle or bottom as the shape says, its runs in their
 * faces and sizes. [inset] px of room round it take a flat line's touches.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun ShapeFace(s: SlideShape, pxPerPt: Float, version: Int, modifier: Modifier = Modifier, inset: Float = 0f) {
    Box(modifier.graphicsLayer(rotationZ = s.rotationDeg), contentAlignment = anchorAlignment(s.anchor)) {
        ShapeGeometry(s, pxPerPt, version, Modifier.fillMaxSize(), inset)
        if (s.box.text.isNotEmpty() && s.type != ShapeType.LINE) {
            val styled = TextStyling.annotatedOf(s.box.paragraph)
            val text = if (isListed(s.box)) listedText(styled, s.box.text, s.box.listStyle) else shownBreaks(styled)
            // DrawingML's default insets: a tenth of an inch at the sides, half that above and below.
            val density = LocalDensity.current
            // Text setting no colour in one that reads over the fill, as the PDF draws it.
            val readable = com.therealityteam.aeropages.model.MasterText(null, null, com.therealityteam.aeropages.model.TextColors.over(null, s.textBackdrop(null)), null)
            BasicText(text, style = slideBaseStyle(s.box, readable), modifier = Modifier.fillMaxWidth()
                .padding(with(density) { inset.toDp() })
                .padding(horizontal = with(density) { (7.2f * pxPerPt).toDp() }, vertical = with(density) { (3.6f * pxPerPt).toDp() }))
        }
    }
}

/** Outline weights PowerPoint and Excel list, in points. */
internal val LINE_WEIGHTS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2.25f, 3f, 4.5f, 6f)

/** The arrow styles offered for a line: (begin, end) — none, an end, a begin, both, and the other heads. */
internal val ARROW_STYLES: List<Pair<LineEnd, LineEnd>> = listOf(
    LineEnd.NONE to LineEnd.NONE,
    LineEnd.NONE to LineEnd(ArrowHead.TRIANGLE),
    LineEnd(ArrowHead.TRIANGLE) to LineEnd.NONE,
    LineEnd(ArrowHead.TRIANGLE) to LineEnd(ArrowHead.TRIANGLE),
    LineEnd.NONE to LineEnd(ArrowHead.ARROW),
    LineEnd(ArrowHead.ARROW) to LineEnd(ArrowHead.ARROW),
    LineEnd.NONE to LineEnd(ArrowHead.STEALTH),
    LineEnd(ArrowHead.OVAL) to LineEnd(ArrowHead.TRIANGLE),
    LineEnd(ArrowHead.OVAL) to LineEnd(ArrowHead.OVAL),
    LineEnd(ArrowHead.DIAMOND) to LineEnd(ArrowHead.DIAMOND),
)

/** "¼", "1½", "2¼" … as the Weight menu writes points. */
internal fun weightLabel(pt: Float): String {
    val whole = pt.toInt()
    val frac = when (Math.round((pt - whole) * 4)) { 1 -> "¼"; 2 -> "½"; 3 -> "¾"; else -> "" }
    return (if (whole == 0 && frac.isNotEmpty()) "" else whole.toString()) + frac
}

private fun dashName(d: LineDash): Int = when (d) {
    LineDash.SOLID -> R.string.dash_solid
    LineDash.SYS_DOT -> R.string.dash_round_dot
    LineDash.SYS_DASH -> R.string.dash_square_dot
    LineDash.DASH -> R.string.dash_dash
    LineDash.DASH_DOT -> R.string.dash_dash_dot
    LineDash.LONG_DASH -> R.string.dash_long_dash
    LineDash.LONG_DASH_DOT -> R.string.dash_long_dash_dot
    else -> R.string.dash_long_dash_dot_dot
}

/**
 * The outline menu's line choices: weight, dashes and — for a line — arrows, each drawn as it
 * will look and the shape's own marked. Picking calls [onWeight], [onDash] or [onArrows]; a null
 * [onArrows] leaves the arrows out (a closed shape has no ends).
 */
@Composable
internal fun LineOptions(
    shape: SlideShape?,
    onWeight: (Float) -> Unit,
    onDash: (LineDash) -> Unit,
    onArrows: ((LineEnd, LineEnd) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OptionLabel(stringResource(R.string.line_weight))
            LINE_WEIGHTS.forEach { pt ->
                val label = stringResource(R.string.line_weight_pt, weightLabel(pt))
                LineSample(label, shape?.borderWidthPt == pt, { onWeight(pt) }) { SlideShape(ShapeType.LINE, border = it, borderWidthPt = pt, fill = null) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OptionLabel(stringResource(R.string.line_dashes))
            LineDash.MENU.forEach { d ->
                LineSample(stringResource(dashName(d)), shape?.dash == d, { onDash(d) }) { SlideShape(ShapeType.LINE, border = it, borderWidthPt = 2f, fill = null, dash = d) }
            }
        }
        if (onArrows != null) Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OptionLabel(stringResource(R.string.line_arrows))
            ARROW_STYLES.forEachIndexed { i, (begin, end) ->
                val label = stringResource(R.string.arrow_style_n, i + 1)
                LineSample(label, shape != null && shape.beginArrow.head == begin.head && shape.endArrow.head == end.head, { onArrows(begin, end) }) {
                    SlideShape(ShapeType.LINE, border = it, borderWidthPt = 1.5f, fill = null, beginArrow = begin, endArrow = end)
                }
            }
        }
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(text, style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(56.dp), maxLines = 1)
}

/** A choice drawn as the line it gives: [sample] makes that line in the given colour. */
@Composable
private fun LineSample(label: String, selected: Boolean, onClick: () -> Unit, sample: (Long) -> SlideShape) {
    val ink = PicoTheme.colorScheme.labelPrimary
    val line = remember(ink) { sample(ink.toArgb().toLong() and 0xFFFFFFFFL) }
    val interaction = remember { MutableInteractionSource() }
    Tooltip(label) { a11y ->
        Box(
            Modifier.size(width = 44.dp, height = 30.dp).clip(RoundedCornerShape(6.dp))
                .background(if (selected) Aero.tint(LocalAccent.current) else Aero.field)
                .then(a11y)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).controllerHapticFeedback(interactionSource = interaction),
            contentAlignment = Alignment.Center,
        ) {
            GazeFill(RoundedCornerShape(6.dp))
            // A level line across the swatch, in from its ends so the arrowheads fit.
            Canvas(Modifier.fillMaxSize()) {
                val pad = 9.dp.toPx()
                drawIntoCanvas { c -> ShapePainter.shape(c.nativeCanvas, line, pad, size.height / 2, size.width - 2 * pad, 0f, density) }
            }
        }
    }
}

/** A choice drawn as the shadow it gives (null: none): a small white square casting it, the shape's own marked in the accent's tint. */
@Composable
internal fun ShadowSample(label: String, shadow: ShapeShadow?, selected: Boolean, onClick: () -> Unit) {
    val sample = remember(shadow) { SlideShape(ShapeType.RECT, fill = 0xFFFFFFFF, border = 0xFFB4B4B4, borderWidthPt = 0.75f, shadow = shadow) }
    val interaction = remember { MutableInteractionSource() }
    Tooltip(label) { a11y ->
        Box(
            Modifier.size(width = 52.dp, height = 36.dp).clip(RoundedCornerShape(Aero.commandRadius))
                .background(if (selected) Aero.tint(LocalAccent.current) else Aero.field)
                .then(a11y)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick).controllerHapticFeedback(interactionSource = interaction),
            contentAlignment = Alignment.Center,
        ) {
            GazeFill(RoundedCornerShape(6.dp))
            Canvas(Modifier.fillMaxSize()) {
                val w = 24.dp.toPx(); val h = 16.dp.toPx()
                val nudge = 2.dp.toPx()   // up and left a little, so an offset shadow has room
                drawIntoCanvas { c -> ShapePainter.shape(c.nativeCanvas, sample, (size.width - w) / 2f - nudge, (size.height - h) / 2f - nudge, w, h, density) }
            }
        }
    }
}
