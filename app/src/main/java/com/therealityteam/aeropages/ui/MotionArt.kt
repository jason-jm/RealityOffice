package com.therealityteam.aeropages.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.AnimClass
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimStart
import com.therealityteam.aeropages.model.SlideTransition
import com.therealityteam.aeropages.model.TransitionDir
import com.therealityteam.aeropages.model.TransitionSpeed

/**
 * The Transitions and Animations tabs as PowerPoint's: each transition drawn as the old slide giving way to the new, each
 * animation as a star in its class's colour (entrance green, emphasis gold, exit red) doing what it does, Effect Options as
 * arrows, timing as steppers.
 */

/** A gallery choice: [draw]n above its [label], framed when [selected]. */
@Composable
internal fun MotionTile(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true, lines: Int = 1, draw: DrawScope.() -> Unit) {
    val accent = LocalAccent.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.widthIn(min = 50.dp, max = if (lines > 1) 66.dp else androidx.compose.ui.unit.Dp.Infinity).clip(RoundedCornerShape(8.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else Color.Transparent)
            .alpha(if (enabled) 1f else 0.4f)
            .press(enabled = enabled, onClick = onClick).semantics { contentDescription = label }
            .padding(horizontal = 2.dp, vertical = 5.dp),
    ) {
        Canvas(Modifier.size(44.dp, 30.dp)) { draw() }
        Text(label, fontSize = 11.sp, lineHeight = 13.sp, color = if (selected) accent else PicoTheme.colorScheme.labelPrimary, maxLines = lines,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = androidx.compose.ui.text.TextStyle(lineBreak = androidx.compose.ui.text.style.LineBreak.Heading))
    }
}

private val OLD = Color(0xFFCDD2D9)
private val PAPER = Color.White

/** [t] as a little scene: the old slide (pale grey) giving way to the new one (a pastel of [accent]). */
internal fun DrawScope.transitionArt(t: SlideTransition, accent: Color) {
    val w = size.width; val h = size.height
    val r = CornerRadius(2.dp.toPx())
    fun slide(x: Float, y: Float, sw: Float, sh: Float, c: Color) = drawRoundRect(c, Offset(x, y), Size(sw, sh), r)
    fun lines(x: Float, y: Float, sw: Float, sh: Float, c: Color) {
        for (k in 0 until 3) drawRect(c, Offset(x + sw * 0.15f, y + sh * (0.25f + k * 0.22f)), Size(sw * (if (k == 2) 0.45f else 0.7f), sh * 0.08f))
    }
    // The new slide in a pastel of the accent (PowerPoint's gallery is mostly pale slides, not a wall of colour).
    val newC = androidx.compose.ui.graphics.lerp(Color.White, accent, 0.55f)
    val ink = Color.White.copy(alpha = 0.8f)
    when (t) {
        SlideTransition.NONE -> {
            slide(0f, 0f, w, h, OLD)
            drawCircle(Color.White, h * 0.32f, Offset(w / 2, h / 2), style = Stroke(2.dp.toPx()))
            drawLine(Color.White, Offset(w / 2 - h * 0.22f, h / 2 + h * 0.22f), Offset(w / 2 + h * 0.22f, h / 2 - h * 0.22f), 2.dp.toPx())
        }
        SlideTransition.FADE -> {
            slide(0f, 0f, w, h, OLD)
            drawRoundRect(Brush.horizontalGradient(listOf(newC.copy(alpha = 0.1f), newC)), Offset.Zero, Size(w, h), r)
            lines(0f, 0f, w, h, ink)
        }
        SlideTransition.PUSH -> {
            slide(-w * 0.45f, 0f, w, h, OLD); slide(w * 0.55f, 0f, w, h, newC); lines(w * 0.55f, 0f, w, h, ink)
            arrow(Offset(w * 0.9f, h / 2), Offset(w * 0.62f, h / 2), Color.White)
        }
        SlideTransition.COVER -> {
            slide(0f, 0f, w, h, OLD); slide(w * 0.4f, 0f, w * 0.6f, h, newC)
            drawLine(Color(0x55000000), Offset(w * 0.4f, 0f), Offset(w * 0.4f, h), 2.dp.toPx())
            arrow(Offset(w * 0.9f, h / 2), Offset(w * 0.55f, h / 2), Color.White)
        }
        SlideTransition.UNCOVER -> {
            slide(0f, 0f, w, h, newC); lines(0f, 0f, w, h, ink); slide(-w * 0.45f, 0f, w, h, OLD)
            drawLine(Color(0x55000000), Offset(w * 0.55f, 0f), Offset(w * 0.55f, h), 2.dp.toPx())
            arrow(Offset(w * 0.45f, h / 2), Offset(w * 0.1f, h / 2), Color.White)
        }
        SlideTransition.WIPE -> {
            slide(0f, 0f, w, h, OLD)
            drawRoundRect(Brush.horizontalGradient(0f to newC, 0.55f to newC, 0.7f to newC.copy(alpha = 0f), endX = w), Offset.Zero, Size(w, h), r)
            lines(0f, 0f, w, h, ink)
        }
        SlideTransition.SPLIT -> {
            slide(0f, 0f, w, h, OLD); drawRect(newC, Offset(0f, h * 0.25f), Size(w, h * 0.5f))
            arrow(Offset(w / 2, h * 0.42f), Offset(w / 2, h * 0.08f), Color.White); arrow(Offset(w / 2, h * 0.58f), Offset(w / 2, h * 0.92f), Color.White)
        }
        SlideTransition.ZOOM -> {
            slide(0f, 0f, w, h, OLD); slide(w * 0.25f, h * 0.22f, w * 0.5f, h * 0.56f, newC); lines(w * 0.25f, h * 0.22f, w * 0.5f, h * 0.56f, ink)
            listOf(Offset(0.18f, 0.15f), Offset(0.82f, 0.15f), Offset(0.18f, 0.85f), Offset(0.82f, 0.85f)).forEach { c ->
                drawLine(Color.White, Offset(w * (0.5f + (c.x - 0.5f) * 0.6f), h * (0.5f + (c.y - 0.5f) * 0.6f)), Offset(w * c.x, h * c.y), 1.5f.dp.toPx())
            }
        }
        SlideTransition.DISSOLVE -> {
            slide(0f, 0f, w, h, OLD)
            val cols = 8; val rows = 5
            for (k in 0 until cols * rows) if ((k * 7 + k / cols) % 3 != 0) drawRect(newC, Offset(w * (k % cols) / cols, h * (k / cols) / rows), Size(w / cols + 0.5f, h / rows + 0.5f))
        }
        SlideTransition.CUT -> {
            slide(0f, 0f, w * 0.48f, h, OLD); slide(w * 0.52f, 0f, w * 0.48f, h, newC)
        }
        SlideTransition.WHEEL -> {
            slide(0f, 0f, w, h, OLD)
            val big = Rect(Offset(w / 2 - w, h / 2 - w), Size(2 * w, 2 * w))
            clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset.Zero, size), r)) }) {
                for (k in 0 until 4) drawArc(newC, -90f + k * 90f, 55f, true, big.topLeft, big.size)
            }
        }
        SlideTransition.RANDOM_BARS -> {
            slide(0f, 0f, w, h, OLD)
            listOf(0, 2, 3, 6, 8, 9).forEach { k -> drawRect(newC, Offset(0f, h * k / 10f), Size(w, h / 10f + 0.5f)) }
        }
        SlideTransition.SHAPE -> {
            slide(0f, 0f, w, h, OLD)
            drawCircle(newC, h * 0.42f, Offset(w / 2, h / 2))
        }
    }
}

private fun DrawScope.arrow(from: Offset, to: Offset, c: Color) {
    val sw = 1.6f.dp.toPx()
    drawLine(c, from, to, sw)
    val d = (to - from).let { it / it.getDistance().coerceAtLeast(0.01f) }
    val n = Offset(-d.y, d.x)
    val head = 3.5f.dp.toPx()
    drawPath(Path().apply { moveTo(to.x, to.y); lineTo(to.x - d.x * head + n.x * head * 0.7f, to.y - d.y * head + n.y * head * 0.7f); lineTo(to.x - d.x * head - n.x * head * 0.7f, to.y - d.y * head - n.y * head * 0.7f); close() }, c)
}

/** A five-pointed star centred at [c] with outer radius [r] (inner 0.42 r), turned [turn] degrees. */
private fun starPath(c: Offset, r: Float, turn: Float = 0f): Path = Path().apply {
    for (k in 0 until 10) {
        val a = Math.toRadians((-90.0 + turn + k * 36.0))
        val rr = if (k % 2 == 0) r else r * 0.42f
        val x = c.x + (Math.cos(a) * rr).toFloat(); val y = c.y + (Math.sin(a) * rr).toFloat()
        if (k == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** [e] as PowerPoint draws it: a star in [color] (its class's) doing what the effect does. */
internal fun DrawScope.animArt(e: AnimEffect, color: Color) {
    val w = size.width; val h = size.height
    val c = Offset(w / 2, h / 2)
    val r = h * 0.42f
    val sw = 1.5f.dp.toPx()
    fun star(at: Offset = c, rr: Float = r, turn: Float = 0f, a: Float = 1f) = drawPath(starPath(at, rr, turn), color.copy(alpha = a))
    when (e) {
        AnimEffect.APPEAR, AnimEffect.DISAPPEAR -> star()
        AnimEffect.FADE, AnimEffect.FADE_OUT -> { drawPath(starPath(c, r), Brush.horizontalGradient(listOf(color.copy(alpha = 0.15f), color), startX = c.x - r, endX = c.x + r)) }
        AnimEffect.FLY_IN, AnimEffect.FLY_OUT -> { star(Offset(w * 0.62f, h / 2)); arrow(if (e == AnimEffect.FLY_IN) Offset(w * 0.05f, h / 2) else Offset(w * 0.45f, h / 2), if (e == AnimEffect.FLY_IN) Offset(w * 0.36f, h / 2) else Offset(w * 0.05f, h / 2), color) }
        AnimEffect.FLOAT_IN, AnimEffect.FLOAT_OUT -> { star(Offset(w / 2, h * 0.42f), r * 0.8f); drawLine(color.copy(alpha = 0.5f), Offset(w * 0.3f, h * 0.92f), Offset(w * 0.7f, h * 0.92f), sw) }
        AnimEffect.SPLIT, AnimEffect.SPLIT_OUT -> {
            clipPath(Path().apply { addRect(Rect(Offset(0f, 0f), Size(w / 2 - 1.5f.dp.toPx(), h))) }) { star(Offset(c.x - 2.dp.toPx(), c.y)) }
            clipPath(Path().apply { addRect(Rect(Offset(w / 2 + 1.5f.dp.toPx(), 0f), Size(w / 2, h))) }) { star(Offset(c.x + 2.dp.toPx(), c.y)) }
        }
        AnimEffect.WIPE, AnimEffect.WIPE_OUT -> {
            clipPath(Path().apply { addRect(Rect(Offset.Zero, Size(w * 0.55f, h))) }) { star() }
            drawPath(starPath(c, r), color.copy(alpha = 0.25f))
            drawLine(color, Offset(w * 0.55f, h * 0.05f), Offset(w * 0.55f, h * 0.95f), sw)
        }
        AnimEffect.ZOOM, AnimEffect.ZOOM_OUT -> { drawPath(starPath(c, r), color.copy(alpha = 0.3f), style = Stroke(sw)); star(rr = r * 0.55f) }
        AnimEffect.GROW, AnimEffect.SHRINK_TURN -> { star(turn = 20f); drawArc(color.copy(alpha = 0.6f), 200f, 100f, false, Offset(c.x - r * 1.1f, c.y - r * 1.1f), Size(r * 2.2f, r * 2.2f), style = Stroke(sw)) }
        AnimEffect.BOUNCE -> { star(Offset(w * 0.62f, h * 0.55f), r * 0.75f); listOf(0.12f to 0.7f, 0.24f to 0.45f, 0.36f to 0.62f).forEach { (x, y) -> drawCircle(color.copy(alpha = 0.5f), 1.6f.dp.toPx(), Offset(w * x, h * y)) } }
        AnimEffect.SPIN, AnimEffect.ROTATE -> {
            star(rr = r * 0.7f)
            drawArc(color, -60f, 280f, false, Offset(c.x - r * 1.05f, c.y - r * 1.05f), Size(r * 2.1f, r * 2.1f), style = Stroke(sw))
            arrow(Offset(c.x + r * 0.9f, c.y - r * 0.6f), Offset(c.x + r * 0.55f, c.y - r * 0.95f), color)
        }
        AnimEffect.PULSE -> { star(rr = r * 0.7f); drawPath(starPath(c, r * 1.05f), color.copy(alpha = 0.45f), style = Stroke(sw)) }
        AnimEffect.TEETER -> { rotate(-15f, c) { star() }; drawLine(color.copy(alpha = 0.5f), Offset(w * 0.15f, h * 0.2f), Offset(w * 0.15f, h * 0.45f), sw); drawLine(color.copy(alpha = 0.5f), Offset(w * 0.85f, h * 0.55f), Offset(w * 0.85f, h * 0.8f), sw) }
        AnimEffect.GROW_SHRINK -> { star(rr = r * 0.85f); arrow(Offset(w * 0.75f, h * 0.3f), Offset(w * 0.95f, h * 0.08f), color); arrow(Offset(w * 0.25f, h * 0.7f), Offset(w * 0.05f, h * 0.92f), color) }
        AnimEffect.TRANSPARENCY -> { clipPath(Path().apply { addRect(Rect(Offset.Zero, Size(w / 2, h))) }) { star() }; clipPath(Path().apply { addRect(Rect(Offset(w / 2, 0f), Size(w / 2, h))) }) { star(a = 0.35f) } }
    }
}

/** An arrow pointing the way [d] goes, drawn on a small slide. */
private fun DrawScope.wayArt(from: Offset, to: Offset, accent: Color) {
    drawRoundRect(OLD.copy(alpha = 0.5f), Offset.Zero, size, CornerRadius(2.dp.toPx()))
    arrow(from, to, accent)
}

/** Transitions: the gallery, Effect Options for the slide's transition, duration, advance after, Apply To All. */
@Composable
internal fun TransitionsTabContent(editor: SlideEditor, group: @Composable (String, @Composable () -> Unit) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val s = editor.slide
    val accent = LocalAccent.current
    group(stringResource(R.string.group_transition_to_slide)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SlideTransition.entries.forEach { t ->
                MotionTile(transitionName(t), s.transition == t, { editor.setTransition(t) }) { transitionArt(t, accent) }
            }
        }
    }
    // Effect Options: the ways the slide's transition can go, each an arrow on a slide.
    val options = TransitionDir.optionsOf(s.transition)
    if (options.isNotEmpty()) group(stringResource(R.string.effect_options)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                val current = TransitionDir.of(s.transition, s.transitionDir)
                options.forEach { d ->
                    MotionTile(dirName(d), current == d, { editor.setTransitionDir(d) }, lines = 2) { dirArt(d, accent) }
                }
            }
        }
    }
    group(stringResource(R.string.group_timing)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Duration: in seconds, stepping through the speeds the files keep (0.5, 0.75, 1 s).
            val speeds = TransitionSpeed.entries.sortedBy { it.ms }
            val at = speeds.indexOf(s.transitionSpeed)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.transition_speed), fontSize = 11.sp, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.widthIn(min = 52.dp))
                SpinBox(stringResource(R.string.seconds_value, seconds(s.transitionSpeed.ms)), { d -> speeds.getOrNull(at + d)?.let(editor::setTransitionSpeed) },
                    description = stringResource(R.string.transition_speed), canLess = at > 0, canMore = at < speeds.lastIndex, valueWidth = 52.dp, compact = true)
            }
            // Advance Slide: on a click, or after so many seconds as well.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.advance_after), fontSize = 11.sp, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.widthIn(min = 52.dp))
                SpinBox(if (s.advanceMs <= 0) stringResource(R.string.advance_on_click) else stringResource(R.string.seconds_value, seconds(s.advanceMs)),
                    { d -> editor.setAdvanceAfter((s.advanceMs + d * 1000).coerceAtLeast(0)) },
                    description = stringResource(R.string.advance_after), canLess = s.advanceMs > 0, valueWidth = 52.dp, compact = true)
            }
        }
        RibbonButton("x_applyall", stringResource(R.string.apply_to_all_transition), editor::applyTransitionToAll)
    }
}

/** A way a transition goes, as an arrow (or arrows) on a slide. */
private fun DrawScope.dirArt(d: TransitionDir, accent: Color) {
    val w = size.width; val h = size.height
    when (d) {
        TransitionDir.FROM_RIGHT -> wayArt(Offset(w * 0.85f, h / 2), Offset(w * 0.2f, h / 2), accent)
        TransitionDir.FROM_LEFT -> wayArt(Offset(w * 0.15f, h / 2), Offset(w * 0.8f, h / 2), accent)
        TransitionDir.FROM_TOP -> wayArt(Offset(w / 2, h * 0.12f), Offset(w / 2, h * 0.85f), accent)
        TransitionDir.FROM_BOTTOM -> wayArt(Offset(w / 2, h * 0.88f), Offset(w / 2, h * 0.15f), accent)
        TransitionDir.HORIZONTAL_OUT -> { wayArt(Offset(w / 2, h * 0.45f), Offset(w / 2, h * 0.1f), accent); arrow(Offset(w / 2, h * 0.55f), Offset(w / 2, h * 0.9f), accent) }
        TransitionDir.HORIZONTAL_IN -> { wayArt(Offset(w / 2, h * 0.08f), Offset(w / 2, h * 0.4f), accent); arrow(Offset(w / 2, h * 0.92f), Offset(w / 2, h * 0.6f), accent) }
        TransitionDir.VERTICAL_OUT -> { wayArt(Offset(w * 0.45f, h / 2), Offset(w * 0.12f, h / 2), accent); arrow(Offset(w * 0.55f, h / 2), Offset(w * 0.88f, h / 2), accent) }
        TransitionDir.VERTICAL_IN -> { wayArt(Offset(w * 0.08f, h / 2), Offset(w * 0.4f, h / 2), accent); arrow(Offset(w * 0.92f, h / 2), Offset(w * 0.6f, h / 2), accent) }
        TransitionDir.IN -> { drawRoundRect(OLD.copy(alpha = 0.5f), Offset.Zero, size, CornerRadius(2.dp.toPx())); listOf(Offset(0.1f, 0.12f), Offset(0.9f, 0.12f), Offset(0.1f, 0.88f), Offset(0.9f, 0.88f)).forEach { c -> arrow(Offset(w * c.x, h * c.y), Offset(w * (0.5f + (c.x - 0.5f) * 0.35f), h * (0.5f + (c.y - 0.5f) * 0.35f)), accent) } }
        TransitionDir.OUT -> { drawRoundRect(OLD.copy(alpha = 0.5f), Offset.Zero, size, CornerRadius(2.dp.toPx())); listOf(Offset(0.1f, 0.12f), Offset(0.9f, 0.12f), Offset(0.1f, 0.88f), Offset(0.9f, 0.88f)).forEach { c -> arrow(Offset(w * (0.5f + (c.x - 0.5f) * 0.3f), h * (0.5f + (c.y - 0.5f) * 0.3f)), Offset(w * c.x, h * c.y), accent) } }
        TransitionDir.DEFAULT -> wayArt(Offset(w * 0.85f, h / 2), Offset(w * 0.2f, h / 2), accent)
    }
}

@Composable
private fun dirName(d: TransitionDir): String = stringResource(when (d) {
    TransitionDir.FROM_RIGHT, TransitionDir.DEFAULT -> R.string.tip_direction_from_right
    TransitionDir.FROM_LEFT -> R.string.tip_direction_from_left
    TransitionDir.FROM_TOP -> R.string.tip_direction_from_top
    TransitionDir.FROM_BOTTOM -> R.string.tip_direction_from_bottom
    TransitionDir.HORIZONTAL_OUT -> R.string.dir_horizontal_out
    TransitionDir.HORIZONTAL_IN -> R.string.dir_horizontal_in
    TransitionDir.VERTICAL_OUT -> R.string.dir_vertical_out
    TransitionDir.VERTICAL_IN -> R.string.dir_vertical_in
    TransitionDir.IN -> R.string.dir_in
    TransitionDir.OUT -> R.string.dir_out
})

@Composable
internal fun transitionName(t: SlideTransition): String = stringResource(when (t) {
    SlideTransition.NONE -> R.string.transition_none
    SlideTransition.FADE -> R.string.anim_fade
    SlideTransition.PUSH -> R.string.transition_push
    SlideTransition.ZOOM -> R.string.anim_zoom
    SlideTransition.COVER -> R.string.transition_cover
    SlideTransition.UNCOVER -> R.string.transition_uncover
    SlideTransition.WIPE -> R.string.transition_wipe
    SlideTransition.SPLIT -> R.string.transition_split
    SlideTransition.DISSOLVE -> R.string.transition_dissolve
    SlideTransition.CUT -> R.string.transition_cut
    SlideTransition.WHEEL -> R.string.transition_wheel
    SlideTransition.RANDOM_BARS -> R.string.transition_random_bars
    SlideTransition.SHAPE -> R.string.transition_shape
})

/** Animations: Preview; the gallery of the current effect's class (all three classes in tabs); Effect Options; Advanced; Timing; Reorder. */
@Composable
internal fun AnimationsTabContent(editor: SlideEditor, animPaneOpen: Boolean, onToggleAnimPane: () -> Unit, onPreview: () -> Unit,
                                  group: @Composable (String, @Composable () -> Unit) -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = editor.revision
    val effect = editor.currentEffect
    var cls by androidx.compose.runtime.remember(effect?.effect?.cls) { androidx.compose.runtime.mutableStateOf(effect?.effect?.cls ?: AnimClass.ENTRANCE) }
    var adding by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    group(stringResource(R.string.preview)) { RibbonButton("▶", stringResource(R.string.preview), onPreview) }
    group(stringResource(R.string.group_animation)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The three classes, each its colour; then the class's effects as stars doing what they do.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(AnimClass.ENTRANCE to R.string.anim_class_entrance, AnimClass.EMPHASIS to R.string.anim_class_emphasis, AnimClass.EXIT to R.string.anim_class_exit).forEach { (k, label) ->
                    val on = cls == k
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).press { cls = k }.padding(horizontal = 4.dp, vertical = 1.dp)) {
                        Canvas(Modifier.size(9.dp)) { drawCircle(animClassColor(k)) }
                        Text(stringResource(label), fontSize = 11.sp, color = if (on) PicoTheme.colorScheme.labelPrimary else PicoTheme.colorScheme.labelSecondary)
                    }
                }
                if (adding) Text(stringResource(R.string.tip_anim_add), fontSize = 11.sp, color = LocalAccent.current)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                if (!adding) MotionTile(stringResource(R.string.none), effect == null, { editor.applyAnimation(null) }) {
                    drawCircle(OLD, size.height * 0.36f, center, style = Stroke(1.6f.dp.toPx()))
                    drawLine(OLD, Offset(center.x - size.height * 0.25f, center.y + size.height * 0.25f), Offset(center.x + size.height * 0.25f, center.y - size.height * 0.25f), 1.6f.dp.toPx())
                }
                AnimEffect.entries.filter { it.cls == cls }.forEach { e ->
                    MotionTile(animName(e), !adding && effect?.effect == e, {
                        if (adding) { editor.addAnimation(e); adding = false } else editor.applyAnimation(e)
                    }, lines = 2) { animArt(e, animClassColor(e.cls)) }
                }
            }
        }
    }
    // Effect Options as PowerPoint's: one button, its menu the side a directional effect comes from (or goes to) and —
    // for a text box — the sequence, as one object or by paragraph.
    group(stringResource(R.string.effect_options)) {
        val directional = effect?.effect?.directional == true
        val byParagraph = effect != null && editor.canBuildByParagraph
        var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            RibbonButton("ic_effect_options", stringResource(R.string.effect_options), { open = true }, enabled = directional || byParagraph, dropdown = true)
            if (open) AnchoredFlyout({ open = false }) {
                FlyoutMenu(width = 220.dp) {
                    if (directional) {
                        MenuHeading(stringResource(R.string.anim_direction))
                        listOf(
                            Triple(R.string.tip_direction_from_bottom, AnimDir.BOTTOM, TransitionDir.FROM_BOTTOM),
                            Triple(R.string.tip_direction_from_left, AnimDir.LEFT, TransitionDir.FROM_LEFT),
                            Triple(R.string.tip_direction_from_right, AnimDir.RIGHT, TransitionDir.FROM_RIGHT),
                            Triple(R.string.tip_direction_from_top, AnimDir.TOP, TransitionDir.FROM_TOP),
                        ).forEach { (label, dir, art) ->
                            val color = animClassColor(effect?.effect?.cls ?: cls)
                            MenuCommand(stringResource(label), { editor.setAnimDirection(dir); open = false }, checked = effect?.dir == dir,
                                leading = { Canvas(Modifier.size(26.dp, 18.dp)) { dirArt(art, color) } })
                        }
                    }
                    if (byParagraph) {
                        if (directional) MenuDivider()
                        MenuHeading(stringResource(R.string.anim_sequence))
                        MenuCommand(stringResource(R.string.anim_as_one_object), { editor.setAnimByParagraph(false); open = false }, checked = effect?.byParagraph == false)
                        MenuCommand(stringResource(R.string.anim_by_paragraph), { editor.setAnimByParagraph(true); open = false }, checked = effect?.byParagraph == true)
                    }
                }
            }
        }
    }
    group(stringResource(R.string.group_advanced_animation)) {
        RibbonButton("ic_plus", stringResource(R.string.anim_add), { adding = !adding }, selected = adding, tooltip = stringResource(R.string.tip_anim_add))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RibbonSmallButton("☰", stringResource(R.string.animation_pane), onToggleAnimPane, selected = animPaneOpen, compactAt = 1)
            RibbonSmallButton("逐项", stringResource(R.string.appear_one_by_one), editor::animateOneByOne, tooltip = stringResource(R.string.tip_one_by_one), compactAt = 1)
        }
    }
    // Timing: when it starts; its duration and delay stepped by a quarter of a second, as PowerPoint's spinners step.
    group(stringResource(R.string.group_timing)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.anim_start_label), fontSize = 11.sp, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(44.dp))
                // PowerPoint's Start drop-down (On Click, With Previous, After Previous).
                val starts = listOf(AnimStart.ON_CLICK to R.string.anim_on_click, AnimStart.WITH_PREVIOUS to R.string.anim_with_previous, AnimStart.AFTER_PREVIOUS to R.string.anim_after_previous)
                val picked = starts.firstOrNull { it.first == effect?.start }
                ComboField(picked?.let { stringResource(it.second) } ?: "–", starts, { stringResource(it.second) }, picked, { editor.setAnimStart(it.first) },
                    width = 112.dp, enabled = effect != null, compact = true)
            }
            val timed = effect != null && !effect.effect.instant
            Stepper(stringResource(R.string.anim_duration_label), effect?.durationMs?.takeIf { timed }, enabled = timed) { editor.setAnimDuration(it.coerceIn(250, 60_000)) }
            Stepper(stringResource(R.string.anim_delay_label), effect?.delayMs, enabled = effect != null) { editor.setAnimDelay(it.coerceIn(0, 60_000)) }
        }
        // Reorder Animation, stacked beside the timing as PowerPoint has it.
        val list = editor.animationList
        val at = effect?.let { e -> list.indexOfFirst { it.anim === e } } ?: -1
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 4.dp)) {
            RibbonMiniButton("▲", stringResource(R.string.anim_move_earlier), { effect?.let { editor.moveAnimation(it, -1) } }, enabled = at > 0)
            RibbonMiniButton("▼", stringResource(R.string.anim_move_later), { effect?.let { editor.moveAnimation(it, 1) } }, enabled = at in 0 until list.lastIndex)
        }
    }
}

/** [label], − / [ms] in seconds / + by a quarter of a second. */
@Composable
private fun Stepper(label: String, ms: Int?, enabled: Boolean, onSet: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (enabled) 1f else 0.45f)) {
        Text(label, fontSize = 11.sp, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.width(44.dp))
        SpinBox(ms?.let { stringResource(R.string.seconds_value, seconds(it)) } ?: "–", { d -> if (enabled && ms != null) onSet(ms + d * 250) },
            description = label, canLess = enabled && ms != null, canMore = enabled && ms != null, valueWidth = 48.dp, compact = true)
    }
}
