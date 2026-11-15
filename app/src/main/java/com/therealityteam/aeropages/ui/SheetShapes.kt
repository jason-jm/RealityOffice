package com.therealityteam.aeropages.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.windows.AlertDialog
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.model.SheetShape
import com.therealityteam.aeropages.model.ShapeType
import kotlin.math.roundToInt

/** Half a corner handle: the picked shape's box reaches this far past it so its handles stay touchable. */
private val SHAPE_HANDLE_PAD = 8.dp
/** A flat shape (a level line) still takes this much room to be tapped. */
private val THIN_HIT = 6.dp

/**
 * A shape or text box floating over the grid (Insert → Shapes): at [left], [top] px in the
 * scrolling body's content, [width] × [height] px, drawn [pxPerPt] px to a point. A tap picks it
 * (an unpicked one lets a drag scroll the sheet); the picked one shows a frame with a handle at
 * each corner — dragging the shape moves it ([onMove], by how far, in px), dragging a handle
 * resizes it freely about the opposite corner ([onFrame]: the new frame, in px from the old corner).
 * [version] changes with every edit, so a restyled shape redraws.
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun SheetShapeView(
    sh: SheetShape, left: Float, top: Float, width: Float, height: Float, selected: Boolean, pxPerPt: Float,
    minSizePx: Float, version: Int, onSelect: () -> Unit, onMove: (Float, Float) -> Unit, onFrame: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    val select by rememberUpdatedState(onSelect)
    val moved by rememberUpdatedState(onMove)
    val framed by rememberUpdatedState(onFrame)
    var move by remember(sh) { mutableStateOf(Offset.Zero) }
    var frame by remember(sh) { mutableStateOf<Rect?>(null) }
    val r = frame ?: Rect(0f, 0f, width, height)
    val line = sh.shape.type == ShapeType.LINE
    val pad = with(density) { (if (selected) SHAPE_HANDLE_PAD else if (r.width < THIN_HIT.toPx() || r.height < THIN_HIT.toPx()) THIN_HIT else 0.dp).toPx() }
    val label = sh.name.ifEmpty { stringResource(R.string.shape) }
    val frameColor = PicoTheme.colorScheme.interaction
    Box(
        Modifier
            .offset { IntOffset((left + move.x + r.left - pad).roundToInt(), (top + move.y + r.top - pad).roundToInt()) }
            .size(with(density) { (r.width + 2 * pad).toDp() }, with(density) { (r.height + 2 * pad).toDp() })
            .semantics { contentDescription = label }
            .pointerInput(sh, selected) {
                if (!selected) detectTapGestures(onTap = { select() })
                else detectDragGestures(
                    onDrag = { change, amount -> change.consume(); move += amount },
                    onDragEnd = { val m = move; move = Offset.Zero; if (m != Offset.Zero) moved(m.x, m.y) },
                    onDragCancel = { move = Offset.Zero },
                )
            },
    ) {
        val inner = Modifier.padding(with(density) { pad.toDp() }).fillMaxSize()
        ShapeFace(sh.shape, pxPerPt, version, Modifier.fillMaxSize(), inset = pad)
        if (selected) {
            Box(inner.border(1.dp, frameColor))
            val handleLabel = stringResource(R.string.a11y_picture_handle)
            for (corner in 0..3) {
                // 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left; the opposite corner stays put.
                val east = corner == 1 || corner == 2
                val south = corner >= 2
                var drag by remember(sh, corner) { mutableStateOf(Offset.Zero) }
                Box(
                    Modifier
                        .offset { IntOffset((if (east) r.width else 0f).roundToInt(), (if (south) r.height else 0f).roundToInt()) }
                        .size(SHAPE_HANDLE_PAD * 2)
                        .semantics { contentDescription = handleLabel }
                        .pointerInput(sh, corner, width, height) {
                            detectDragGestures(
                                onDragStart = { drag = Offset.Zero },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag += amount
                                    frame = freeFrame(width, height, if (east) drag.x else -drag.x, if (south) drag.y else -drag.y, east, south,
                                        if (line) 0f else minSizePx)
                                },
                                onDragEnd = { frame?.let { f -> frame = null; framed(f) } },
                                onDragCancel = { frame = null },
                            )
                        },
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { HandleDot() }
                }
            }
        }
    }
}

/** The frame a free corner drag gives a [w] × [h] shape: [growX] and [growY] px outward, about the opposite corner, at least [minPx] a side. */
internal fun freeFrame(w: Float, h: Float, growX: Float, growY: Float, east: Boolean, south: Boolean, minPx: Float): Rect {
    val nw = (w + growX).coerceAtLeast(minPx)
    val nh = (h + growY).coerceAtLeast(minPx)
    val l = if (east) 0f else w - nw
    val t = if (south) 0f else h - nh
    return Rect(l, t, l + nw, t + nh)
}

/** Edit Text on a picked shape: its text, lines and all; Apply puts it in the shape. */
@Composable
internal fun ShapeTextDialog(initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    // Line breaks inside a line (Shift+Enter in Slides) show as new lines here.
    var value by remember { mutableStateOf(TextFieldValue(shownBreaks(initial), TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    val label = stringResource(R.string.edit_text)
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AeroDialog(title = label, onDismiss = onDismiss, width = 420.dp, buttons = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(stringResource(R.string.cancel), onDismiss)
                DialogButton(stringResource(R.string.apply), { onApply(value.text) }, primary = true)
            }
        }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.width(420.dp)) {
                InputBox(value, { value = it }, Modifier.fillMaxWidth().focusRequester(focus), description = label, minLines = 3)
            }
        }
}
