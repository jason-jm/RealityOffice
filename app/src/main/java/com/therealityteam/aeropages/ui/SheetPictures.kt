package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.model.SheetImage
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/** Half a corner handle: the picked picture's box reaches this far past the picture so its handles stay touchable. */
private val HANDLE_PAD = 8.dp

/**
 * A picture floating over the grid (Insert → Pictures): at [left], [top] px in the scrolling body's
 * content, [width] × [height] px. A tap picks it (an unpicked picture lets a drag scroll the sheet);
 * the picked one shows a frame with a handle at each corner — dragging the picture moves it
 * ([onMove], by how far, in px), dragging a handle resizes it about the opposite corner keeping its
 * proportions, as Excel's corner handles do a picture with its aspect locked ([onFrame]: the new
 * frame, in px from the old top-left).
 */
@androidx.compose.runtime.NonSkippableComposable
@Composable
internal fun SheetPictureView(
    img: SheetImage, left: Float, top: Float, width: Float, height: Float, selected: Boolean,
    minSizePx: Float, onSelect: () -> Unit, onMove: (Float, Float) -> Unit, onFrame: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    // The gesture detectors outlive a recomposition: they call whatever callbacks are current.
    val select by rememberUpdatedState(onSelect)
    val moved by rememberUpdatedState(onMove)
    val framed by rememberUpdatedState(onFrame)
    var move by remember(img) { mutableStateOf(Offset.Zero) }
    // While a handle is dragged: the frame it would give, from the picture's top-left.
    var frame by remember(img) { mutableStateOf<Rect?>(null) }
    val r = frame ?: Rect(0f, 0f, width, height)
    val pad = if (selected) with(density) { HANDLE_PAD.toPx() } else 0f
    val bitmap = rememberDecodedImage(img.data)
    // What a screen reader says: its alt text, else its name or "Picture" (decorative: so marked).
    val label = when {
        img.decorative -> stringResource(R.string.a11y_picture_decorative)
        img.altText.isNotEmpty() -> img.altText
        else -> img.name.ifEmpty { stringResource(R.string.picture) }
    }
    val frameColor = PicoTheme.colorScheme.interaction
    Box(
        Modifier
            .offset { IntOffset((left + move.x + r.left - pad).roundToInt(), (top + move.y + r.top - pad).roundToInt()) }
            .size(with(density) { (r.width + 2 * pad).toDp() }, with(density) { (r.height + 2 * pad).toDp() })
            .semantics { contentDescription = label }
            .pointerInput(img, selected) {
                if (!selected) detectTapGestures(onTap = { select() })
                else detectDragGestures(
                    onDrag = { change, amount -> change.consume(); move += amount },
                    onDragEnd = { val m = move; move = Offset.Zero; if (m != Offset.Zero) moved(m.x, m.y) },
                    onDragCancel = { move = Offset.Zero },
                )
            },
    ) {
        val inner = Modifier.padding(with(density) { pad.toDp() }).fillMaxSize()
        if (bitmap != null) {
            // Decoding may downsample: the crop is a fraction of whatever size the bitmap came out.
            val (l, t, rr, b) = img.visiblePixels(bitmap.width, bitmap.height)
            val painter = remember(bitmap, l, t, rr, b) { BitmapPainter(bitmap, IntOffset(l, t), IntSize(rr - l, b - t)) }
            // Its shadow under it, its lengths at the sheet's scale (the frame's pixels to its points).
            img.shadow?.let { s ->
                val pxPerPt = r.width / img.widthPt.coerceAtLeast(1f)
                androidx.compose.foundation.Canvas(inner) {
                    drawIntoCanvas { c ->
                        com.therealityteam.aeropages.format.pdf.PictureShadows.draw(c.nativeCanvas, bitmap.asAndroidBitmap(), android.graphics.Rect(l, t, rr, b),
                            android.graphics.RectF(0f, 0f, size.width, size.height), s, pxPerPt)
                    }
                }
            }
            Image(painter = painter, contentDescription = null, modifier = inner, contentScale = ContentScale.FillBounds)
        } else Box(inner.background(Color(0xFFEDEDED)))
        if (selected) {
            Box(inner.border(1.5.dp, frameColor))
            val handleLabel = stringResource(R.string.a11y_picture_handle)
            for (corner in 0..3) {
                // 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left; the opposite corner stays put.
                val east = corner == 1 || corner == 2
                val south = corner >= 2
                var drag by remember(img, corner) { mutableStateOf(Offset.Zero) }
                Box(
                    Modifier
                        .offset { IntOffset((if (east) r.width else 0f).roundToInt(), (if (south) r.height else 0f).roundToInt()) }
                        .size(HANDLE_PAD * 2)
                        .semantics { contentDescription = handleLabel }
                        .pointerInput(img, corner, width, height) {
                            detectDragGestures(
                                onDragStart = { drag = Offset.Zero },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag += amount
                                    frame = cornerFrame(width, height, if (east) drag.x else -drag.x, if (south) drag.y else -drag.y, east, south, minSizePx)
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

/**
 * The frame a corner drag gives a [w] × [h] picture: it grows by [grow] and [growY] px (outward
 * positive) along whichever axis moved it more, in proportion, about the opposite corner; at
 * least [minPx] on its shorter side.
 */
internal fun cornerFrame(w: Float, h: Float, grow: Float, growY: Float, east: Boolean, south: Boolean, minPx: Float): Rect {
    val byWidth = abs(grow) / w >= abs(growY) / h
    var f = if (byWidth) (w + grow) / w else (h + growY) / h
    f = f.coerceAtLeast(minPx / minOf(w, h))
    val nw = w * f; val nh = h * f
    val l = if (east) 0f else w - nw
    val t = if (south) 0f else h - nh
    return Rect(l, t, l + nw, t + nh)
}
