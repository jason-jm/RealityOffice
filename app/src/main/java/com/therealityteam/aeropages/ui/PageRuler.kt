package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.TabAlign
import com.therealityteam.aeropages.model.TabStop
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where the ruler stands over the page holding the caret, in the canvas's pixels: the page's left edge and width, the
 * column the caret is in (its left edge from the page's, its width), every column (for the gaps between them), pixels
 * per point, and whether the caret's paragraph is the body's (a table cell's or a text box's has no indents to show).
 */
internal class RulerGeometry(
    val pageX: Float,
    val pageWidth: Float,
    val columnStart: Float,
    val columnWidth: Float,
    val columns: List<Pair<Float, Float>>,
    val pxPerPt: Float,
    val body: Boolean,
)

/** What a drag on the ruler holds. */
private enum class Grip { FIRST_LINE, HANGING, LEFT, RIGHT, TAB }

/** The ruler's strip, its markers' sizes (dp). */
private const val RULER_DP = 24f
private const val STRIP_TOP_DP = 4f
private const val STRIP_BOTTOM_DP = 19f
private const val MARK_DP = 5f

/**
 * Word's horizontal ruler over the page: the margins shaded, centimetres (inches in the US) counted out from the
 * column's edge, and the caret's paragraph's indents and tab stops — the first-line indent (the top triangle), the
 * hanging indent (the bottom one), the left indent (the box under it, moving both), the right indent, and each tab stop.
 * Drag a marker to move it, click the strip to set a tab stop of the kind the corner box shows (click the box for the
 * next kind), drag a tab stop down off the ruler to clear it. While dragging, [onGuide] gets the x of the line Word draws
 * down the page there (null when done).
 */
@Composable
internal fun PageRuler(editor: DocumentEditor, geometry: RulerGeometry, onGuide: (Float?) -> Unit, onOpenTabs: () -> Unit, modifier: Modifier = Modifier) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val density = LocalDensity.current
    val g by rememberUpdatedState(geometry)
    val measurer = rememberTextMeasurer()
    var tabKind by remember { mutableStateOf(TabAlign.LEFT) }
    // A drag in progress: what it holds, where (points from the column's edge), and whether a tab is pulled off.
    var grip by remember { mutableStateOf<Grip?>(null) }
    var gripPt by remember { mutableFloatStateOf(0f) }
    var gripFromPt by remember { mutableFloatStateOf(0f) }
    var pulledOff by remember { mutableStateOf(false) }
    // A click on a tab stop, to tell a double-click (Word opens the Tabs dialog).
    val lastTabClick = remember { LongArray(1) }
    val openTabs by rememberUpdatedState(onOpenTabs)
    val f = editor.activeParagraph.format
    val tabs = f.tabStops
    val defaultTab = editor.document.defaultTabPt.takeIf { it > 1f } ?: 36f
    val rulerName = stringResource(R.string.ruler_toggle)
    val labels = RulerLabels(
        stringResource(R.string.first_line_indent), stringResource(R.string.hanging_indent),
        stringResource(R.string.ruler_left_indent), stringResource(R.string.ruler_right_indent), stringResource(R.string.ruler_tab_stop),
    )
    val unitFormat = stringResource(if (Lengths.inches) R.string.unit_in else R.string.unit_cm, "%s")
    val snap = if (Lengths.inches) 4.5f else 72f / 25.4f * 2.5f
    fun snapPt(pt: Float) = Math.round(pt / snap) * snap

    // Where each marker stands now (points from the column's edge): a dragged one where the drag has it.
    val left = if (grip == Grip.LEFT || grip == Grip.HANGING) gripPt else f.indentLeftPt
    val first = when (grip) {
        Grip.FIRST_LINE -> gripPt
        Grip.LEFT -> gripPt + f.firstLineIndentPt
        else -> f.indentLeftPt + f.firstLineIndentPt
    }
    val rightEdge = if (grip == Grip.RIGHT) gripPt else g.columnWidth / g.pxPerPt - f.indentRightPt

    Box(modifier.fillMaxWidth().height(RULER_DP.dp).semantics { contentDescription = rulerName }) {
        val ink = PicoTheme.colorScheme.labelSecondary
        val accent = PicoTheme.colorScheme.interaction
        Canvas(
            Modifier.fillMaxWidth().height(RULER_DP.dp).pointerInput(editor) {
                val markPx = MARK_DP.dp.toPx() + 3.dp.toPx()
                val stripTop = STRIP_TOP_DP.dp.toPx()
                val stripBottom = STRIP_BOTTOM_DP.dp.toPx()
                val offRuler = size.height + 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val gm = g
                    val cs = gm.pageX + gm.columnStart
                    fun ptAt(x: Float) = (x - cs) / gm.pxPerPt
                    fun xOf(pt: Float) = cs + pt * gm.pxPerPt
                    val fmt = editor.activeParagraph.format
                    val l0 = fmt.indentLeftPt
                    val f0 = l0 + fmt.firstLineIndentPt
                    val r0 = gm.columnWidth / gm.pxPerPt - fmt.indentRightPt
                    val x = down.position.x
                    val y = down.position.y
                    val hit: Grip? = when {
                        !gm.body -> null
                        y < stripTop + MARK_DP.dp.toPx() + 2f && abs(x - xOf(f0)) <= markPx -> Grip.FIRST_LINE
                        y >= stripBottom && abs(x - xOf(l0)) <= markPx -> Grip.LEFT
                        y >= stripTop + 5f && abs(x - xOf(l0)) <= markPx -> Grip.HANGING
                        y >= stripTop + 5f && abs(x - xOf(r0)) <= markPx -> Grip.RIGHT
                        else -> null
                    }
                    val tab = if (hit == null && gm.body && y >= stripTop) fmt.tabStops.minByOrNull { abs(x - xOf(it.posPt)) }?.takeIf { abs(x - xOf(it.posPt)) <= markPx } else null
                    val what = hit ?: tab?.let { Grip.TAB }
                    if (what == null) {
                        // A click in the text's part of the strip sets a tab stop there.
                        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                        if (gm.body && abs(up.position.x - x) < 12f && y in stripTop..size.height.toFloat()) {
                            val pt = snapPt(ptAt(x))
                            if (pt > 0f && pt < gm.columnWidth / gm.pxPerPt) editor.addTabStop(TabStop(pt, tabKind))
                        }
                        return@awaitEachGesture
                    }
                    down.consume()
                    grip = what
                    gripFromPt = when (what) { Grip.FIRST_LINE -> f0; Grip.LEFT, Grip.HANGING -> l0; Grip.RIGHT -> r0; Grip.TAB -> tab!!.posPt }
                    gripPt = gripFromPt
                    pulledOff = false
                    onGuide(xOf(gripPt))
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val p = change.position
                        if (abs(p.x - x) > 2f) moved = true
                        val maxPt = gm.columnWidth / gm.pxPerPt
                        gripPt = snapPt(ptAt(p.x)).let { pt ->
                            when (what) {
                                Grip.RIGHT -> pt.coerceIn(maxOf(f0, l0) + 18f, maxPt)
                                Grip.TAB -> pt.coerceIn(0f, maxPt)
                                else -> pt.coerceIn(0f, r0 - 18f)
                            }
                        }
                        pulledOff = what == Grip.TAB && p.y > offRuler
                        onGuide(if (pulledOff) null else xOf(gripPt))
                    }
                    onGuide(null)
                    val at = gripPt
                    val done = grip
                    grip = null
                    if (!moved && !pulledOff) {
                        if (done == Grip.TAB) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastTabClick[0] < 450L) { lastTabClick[0] = 0L; openTabs() } else lastTabClick[0] = now
                        }
                        return@awaitEachGesture
                    }
                    when (done) {
                        Grip.FIRST_LINE -> editor.setIndents(l0, at - l0, fmt.indentRightPt)
                        Grip.HANGING -> editor.setIndents(at, f0 - at, fmt.indentRightPt)
                        Grip.LEFT -> editor.setIndents(at, fmt.firstLineIndentPt, fmt.indentRightPt)
                        Grip.RIGHT -> editor.setIndents(l0, fmt.firstLineIndentPt, gm.columnWidth / gm.pxPerPt - at)
                        Grip.TAB -> editor.moveTabStop(gripFromPt, if (pulledOff) null else at)
                        null -> Unit
                    }
                }
            },
        ) {
            val gm = g
            val cs = gm.pageX + gm.columnStart
            fun xOf(pt: Float) = cs + pt * gm.pxPerPt
            val top = STRIP_TOP_DP.dp.toPx()
            val bottom = STRIP_BOTTOM_DP.dp.toPx()
            // The page's margins shaded, the columns white.
            drawRect(Color(0xFFD9DCE1), Offset(gm.pageX, top), Size(gm.pageWidth, bottom - top))
            for ((c0, cw) in gm.columns) drawRect(Color.White, Offset(gm.pageX + c0, top), Size(cw, bottom - top))
            drawRect(Color(0xFFB8BCC4), Offset(gm.pageX, top), Size(gm.pageWidth, bottom - top), style = Stroke(1f))
            drawTicks(measurer, gm, cs, top, bottom, ink)
            if (!gm.body) return@Canvas
            // The default tab stops past the last of the paragraph's own: small ticks along the bottom.
            val lastTab = maxOf(tabs.maxOfOrNull { it.posPt } ?: 0f, left)
            var d = defaultTab
            while (d * gm.pxPerPt < gm.columnWidth) {
                if (d > lastTab) drawLine(Color(0xFF9AA0A8), Offset(xOf(d), bottom - 3f), Offset(xOf(d), bottom), 1.5f)
                d += defaultTab
            }
            // The paragraph's tab stops (a dragged one where it is going, faint when pulled off).
            for (t in tabs) {
                val dragging = grip == Grip.TAB && abs(t.posPt - gripFromPt) < 1f
                val at = if (dragging) gripPt else t.posPt
                drawTabMark(t.align, xOf(at), bottom - 2f, if (dragging && pulledOff) ink.copy(alpha = 0.3f) else ink, 4.dp.toPx())
            }
            // The indents: first line (pointing down from the top), hanging (pointing up), the left box, the right indent.
            val m = MARK_DP.dp.toPx()
            val fill = Color.White
            val edge = if (grip != null) accent else Color(0xFF5F6670)
            fun marker(x: Float, down: Boolean, y: Float) {
                val p = Path()
                if (down) { p.moveTo(x - m, y); p.lineTo(x + m, y); p.lineTo(x, y + m * 1.2f) } else { p.moveTo(x - m, y); p.lineTo(x + m, y); p.lineTo(x, y - m * 1.2f) }
                p.close()
                drawPath(p, fill); drawPath(p, edge, style = Stroke(1.5f))
            }
            marker(xOf(first), down = true, y = top - 1f)
            marker(xOf(left), down = false, y = bottom)
            drawRect(fill, Offset(xOf(left) - m, bottom), Size(2 * m, size.height - bottom - 1f))
            drawRect(edge, Offset(xOf(left) - m, bottom), Size(2 * m, size.height - bottom - 1f), style = Stroke(1.5f))
            marker(xOf(rightEdge), down = false, y = bottom)
        }
        // The kind of tab stop a click sets: Word's box at the ruler's left end, click for the next kind.
        if (g.body) {
            val kindName = stringResource(when (tabKind) { TabAlign.LEFT -> R.string.ruler_tab_left; TabAlign.CENTER -> R.string.ruler_tab_center; TabAlign.RIGHT -> R.string.ruler_tab_right; TabAlign.DECIMAL -> R.string.ruler_tab_decimal })
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(20.dp).clip(RoundedCornerShape(4.dp))
                    .background(Color.White).border(1.dp, Color(0xFFB8BCC4), RoundedCornerShape(4.dp))
                    .clickable { tabKind = TabAlign.entries[(tabKind.ordinal + 1) % TabAlign.entries.size] }
                    .semantics { contentDescription = kindName },
            ) {
                Canvas(Modifier.size(20.dp)) { drawTabMark(tabKind, size.width / 2f, size.height * 0.72f, ink, 5.dp.toPx()) }
            }
        }
        // While dragging: what moves and where it is now.
        val held = grip
        if (held != null && !pulledOff) {
            val name = when (held) { Grip.FIRST_LINE -> labels.firstLine; Grip.HANGING -> labels.hanging; Grip.LEFT -> labels.left; Grip.RIGHT -> labels.right; Grip.TAB -> labels.tab }
            val shown = if (held == Grip.RIGHT) g.columnWidth / g.pxPerPt - gripPt else if (held == Grip.FIRST_LINE) gripPt - left else gripPt
            val x = with(density) { (g.pageX + g.columnStart + gripPt * g.pxPerPt).toDp() }
            Text(
                "$name ${unitFormat.format(Lengths.number(shown))}",
                style = AeroType.caption, color = Color.White,
                modifier = Modifier.offset { IntOffset((x + 8.dp).roundToPx(), RULER_DP.dp.roundToPx() + 4.dp.roundToPx()) }
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xE0333A44)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** The ruler's names for what it moves. */
private class RulerLabels(val firstLine: String, val hanging: String, val left: String, val right: String, val tab: String)

/** The ruler's measure: a number each unit out from the column's edge (both ways), half and quarter ticks between. */
private fun DrawScope.drawTicks(measurer: TextMeasurer, g: RulerGeometry, cs: Float, top: Float, bottom: Float, ink: Color) {
    val unitPt = if (Lengths.inches) 72f else 72f / 2.54f
    val unitPx = unitPt * g.pxPerPt
    if (unitPx < 4f) return
    // Numbers every unit — every second one when they would crowd — and as many ticks between as there is room for.
    val every = if (unitPx < 22f) 2 else 1
    val parts = when { unitPx >= 48f -> 4; unitPx >= 20f -> 2; else -> 1 }
    val mid = (top + bottom) / 2f
    val style = TextStyle(fontSize = 9.sp, color = ink)
    val from = g.pageX
    val to = g.pageX + g.pageWidth
    var k = -kotlin.math.ceil((cs - from) / unitPx).toInt()
    while (cs + k * unitPx <= to) {
        val x0 = cs + k * unitPx
        if (k != 0 && abs(k) % every == 0 && x0 in from..to) {
            val layout = measurer.measure(abs(k).toString(), style)
            drawText(layout, topLeft = Offset(x0 - layout.size.width / 2f, mid - layout.size.height / 2f))
        }
        for (j in 1 until parts) {
            val x = x0 + j * unitPx / parts
            if (x < from || x > to) continue
            val h = if (parts == 4 && j == 2) 4f else 2.5f
            drawLine(ink.copy(alpha = 0.7f), Offset(x, mid - h), Offset(x, mid + h), 1f)
        }
        k++
    }
}

/** A tab stop's mark, Word's shapes: └ left, ┴ centre, ┘ right, ┴ with a dot decimal — standing on [baseY]. */
private fun DrawScope.drawTabMark(align: TabAlign, x: Float, baseY: Float, color: Color, arm: Float) {
    val w = 2f
    when (align) {
        TabAlign.LEFT -> { drawLine(color, Offset(x, baseY - arm), Offset(x, baseY), w); drawLine(color, Offset(x, baseY), Offset(x + arm, baseY), w) }
        TabAlign.RIGHT -> { drawLine(color, Offset(x, baseY - arm), Offset(x, baseY), w); drawLine(color, Offset(x - arm, baseY), Offset(x, baseY), w) }
        TabAlign.CENTER, TabAlign.DECIMAL -> {
            drawLine(color, Offset(x, baseY - arm), Offset(x, baseY), w); drawLine(color, Offset(x - arm, baseY), Offset(x + arm, baseY), w)
            if (align == TabAlign.DECIMAL) drawCircle(color, 1.6f, Offset(x + arm * 0.55f, baseY - arm * 0.55f))
        }
    }
}
