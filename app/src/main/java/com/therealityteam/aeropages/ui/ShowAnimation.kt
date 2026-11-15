package com.therealityteam.aeropages.ui

import androidx.compose.ui.graphics.isSpecified

import androidx.compose.ui.graphics.toArgb

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import com.therealityteam.aeropages.model.AnimClass
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimTarget
import com.therealityteam.aeropages.model.AnimTimeline
import com.therealityteam.aeropages.model.ElementAnim
import com.therealityteam.aeropages.model.SlideBox
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The values a slide element is drawn with during a show: opacity, scale, turn, a shift (in element
 * sizes) and how much of it a wipe shows, from which side.
 */
private class Motion(state: AnimTimeline.State) {
    val alpha = Animatable(if (state.visible) state.alpha else 0f)
    val scaleX = Animatable(state.scale)
    val scaleY = Animatable(state.scale)
    val turn = Animatable(0f)
    val dx = Animatable(0f)
    val dy = Animatable(0f)
    val wipe = Animatable(1f)
    var wipeFrom by mutableStateOf(AnimDir.LEFT)

    /** Settle on [state] at once: shown or not, with its lasting emphasis, nothing in motion. */
    suspend fun rest(state: AnimTimeline.State) {
        alpha.snapTo(if (state.visible) state.alpha else 0f)
        scaleX.snapTo(state.scale); scaleY.snapTo(state.scale)
        turn.snapTo(0f); dx.snapTo(0f); dy.snapTo(0f); wipe.snapTo(1f)
    }

    /** Play [a] on an element that stood as [base] before it. */
    suspend fun play(a: ElementAnim, base: AnimTimeline.State) = coroutineScope {
        val ms = a.durationMs
        val s = base.scale
        val shown = base.alpha
        fun ease() = tween<Float>(ms, easing = FastOutSlowInEasing)
        val (vx, vy) = when (a.dir) { AnimDir.LEFT -> -1f to 0f; AnimDir.RIGHT -> 1f to 0f; AnimDir.TOP -> 0f to -1f; AnimDir.BOTTOM -> 0f to 1f }
        when (a.effect) {
            AnimEffect.APPEAR -> alpha.snapTo(shown)
            AnimEffect.FADE -> { alpha.snapTo(0f); alpha.animateTo(shown, tween(ms, easing = LinearEasing)) }
            AnimEffect.FLY_IN -> {
                dx.snapTo(vx * FLY); dy.snapTo(vy * FLY); alpha.snapTo(shown)
                launch { dx.animateTo(0f, ease()) }; dy.animateTo(0f, ease())
            }
            AnimEffect.FLOAT_IN -> {
                dx.snapTo(vx * FLOAT); dy.snapTo(vy * FLOAT); alpha.snapTo(0f)
                launch { dx.animateTo(0f, ease()) }; launch { dy.animateTo(0f, ease()) }; alpha.animateTo(shown, ease())
            }
            AnimEffect.SPLIT -> { scaleX.snapTo(0f); alpha.snapTo(shown); scaleX.animateTo(s, ease()) }
            AnimEffect.WIPE -> { wipeFrom = a.dir; wipe.snapTo(0f); alpha.snapTo(shown); wipe.animateTo(1f, tween(ms, easing = LinearEasing)) }
            AnimEffect.ZOOM -> {
                scaleX.snapTo(s * 0.3f); scaleY.snapTo(s * 0.3f); alpha.snapTo(0f)
                launch { scaleX.animateTo(s, ease()) }; launch { scaleY.animateTo(s, ease()) }; alpha.animateTo(shown, ease())
            }
            AnimEffect.GROW -> {
                scaleX.snapTo(0f); scaleY.snapTo(0f); turn.snapTo(-90f); alpha.snapTo(0f)
                launch { scaleX.animateTo(s, ease()) }; launch { scaleY.animateTo(s, ease()) }; launch { turn.animateTo(0f, ease()) }
                alpha.animateTo(shown, ease())
            }
            AnimEffect.BOUNCE -> {
                dy.snapTo(-BOUNCE_DROP); alpha.snapTo(0f)
                launch { alpha.animateTo(shown, tween((ms / 4).coerceAtLeast(1))) }
                dy.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow))
            }
            AnimEffect.SPIN -> {
                scaleX.snapTo(s * 0.2f); scaleY.snapTo(s * 0.2f); turn.snapTo(-360f); alpha.snapTo(0f)
                launch { scaleX.animateTo(s, ease()) }; launch { scaleY.animateTo(s, ease()) }; launch { turn.animateTo(0f, ease()) }
                alpha.animateTo(shown, ease())
            }
            AnimEffect.PULSE -> {
                val half = tween<Float>((ms / 2).coerceAtLeast(1), easing = FastOutSlowInEasing)
                launch { scaleX.animateTo(s * 1.1f, half); scaleX.animateTo(s, half) }
                scaleY.animateTo(s * 1.1f, half); scaleY.animateTo(s, half)
            }
            AnimEffect.TEETER -> turn.animateTo(0f, keyframes {
                durationMillis = ms
                -TEETER_DEG at ms / 8; TEETER_DEG at ms * 3 / 8; -TEETER_DEG at ms * 5 / 8; TEETER_DEG at ms * 7 / 8
            })
            AnimEffect.ROTATE -> { turn.snapTo(0f); turn.animateTo(360f, ease()); turn.snapTo(0f) }
            AnimEffect.GROW_SHRINK -> {
                val to = s * AnimTimeline.GROW_SHRINK_SCALE
                launch { scaleX.animateTo(to, ease()) }; scaleY.animateTo(to, ease())
            }
            AnimEffect.TRANSPARENCY -> alpha.animateTo(AnimTimeline.TRANSPARENCY_ALPHA, ease())
            AnimEffect.DISAPPEAR -> alpha.snapTo(0f)
            AnimEffect.FADE_OUT -> alpha.animateTo(0f, tween(ms, easing = LinearEasing))
            AnimEffect.FLY_OUT -> {
                launch { dx.animateTo(vx * FLY, ease()) }; dy.animateTo(vy * FLY, ease())
                alpha.snapTo(0f)
            }
            AnimEffect.FLOAT_OUT -> { launch { dx.animateTo(vx * FLOAT, ease()) }; launch { dy.animateTo(vy * FLOAT, ease()) }; alpha.animateTo(0f, ease()) }
            AnimEffect.SPLIT_OUT -> { scaleX.animateTo(0f, ease()); alpha.snapTo(0f) }
            AnimEffect.WIPE_OUT -> { wipeFrom = a.dir; wipe.animateTo(0f, tween(ms, easing = LinearEasing)); alpha.snapTo(0f) }
            AnimEffect.ZOOM_OUT -> { launch { scaleX.animateTo(s * 0.3f, ease()) }; launch { scaleY.animateTo(s * 0.3f, ease()) }; alpha.animateTo(0f, ease()) }
            AnimEffect.SHRINK_TURN -> {
                launch { scaleX.animateTo(0f, ease()) }; launch { scaleY.animateTo(0f, ease()) }; launch { turn.animateTo(90f, ease()) }
                alpha.animateTo(0f, ease())
            }
        }
    }

    companion object {
        /** How far a fly starts or ends, in element sizes: far enough to come from off the element's side. */
        const val FLY = 1.6f
        /** A float's drift, in element sizes. */
        const val FLOAT = 0.3f
        const val BOUNCE_DROP = 0.6f
        const val TEETER_DEG = 5f
    }
}

/**
 * A slide element in the show — or, with [paragraph] ≥ 0, one paragraph of a text box built by
 * paragraph — drawn as the slide's animation [timeline] has it once [step] has played: hidden before
 * its entrance and after its exit, with lasting emphasis kept. When [animate] is on, the effects
 * [step] brings for it play (each after its start time in the step); stepping back or leaving the
 * slide shows the state without motion.
 */
@Composable
internal fun ShowElement(timeline: AnimTimeline, target: AnimTarget, step: Int, animate: Boolean, modifier: Modifier = Modifier, paragraph: Int = -1, content: @Composable () -> Unit) {
    val end = timeline.stateAt(target, step, paragraph)
    val playing = if (animate) timeline.steps.getOrNull(step).orEmpty().filter { it.target === target && it.paragraph == paragraph } else emptyList()
    val begin = if (playing.isEmpty()) end else timeline.stateAt(target, step - 1, paragraph)
    val motion = remember(target, paragraph) { Motion(begin) }
    LaunchedEffect(target, paragraph, step, animate) {
        if (playing.isEmpty()) { motion.rest(end); return@LaunchedEffect }
        motion.rest(begin)
        coroutineScope {
            // Each effect starts from the state the ones before it in the step leave.
            var before = begin
            for (t in playing.sortedBy { it.startMs }) {
                val base = before
                launch { delay(t.startMs.toLong()); motion.play(t.anim, base) }
                before = when (t.anim.effect.cls) {
                    AnimClass.ENTRANCE -> base.copy(visible = true)
                    AnimClass.EXIT -> base.copy(visible = false)
                    AnimClass.EMPHASIS -> when (t.anim.effect) {
                        AnimEffect.GROW_SHRINK -> base.copy(scale = base.scale * AnimTimeline.GROW_SHRINK_SCALE)
                        AnimEffect.TRANSPARENCY -> base.copy(alpha = AnimTimeline.TRANSPARENCY_ALPHA)
                        else -> base
                    }
                }
            }
        }
        motion.rest(end)
    }
    Box(
        modifier
            .graphicsLayer {
                alpha = motion.alpha.value
                scaleX = motion.scaleX.value; scaleY = motion.scaleY.value
                rotationZ = motion.turn.value
                translationX = motion.dx.value * size.width; translationY = motion.dy.value * size.height
            }
            .drawWithContent {
                val p = motion.wipe.value
                if (p >= 1f) drawContent()
                else when (motion.wipeFrom) {
                    AnimDir.LEFT -> clipRect(right = size.width * p) { this@drawWithContent.drawContent() }
                    AnimDir.RIGHT -> clipRect(left = size.width * (1f - p)) { this@drawWithContent.drawContent() }
                    AnimDir.TOP -> clipRect(bottom = size.height * p) { this@drawWithContent.drawContent() }
                    AnimDir.BOTTOM -> clipRect(top = size.height * (1f - p)) { this@drawWithContent.drawContent() }
                }
            },
    ) { content() }
}

/**
 * A text box's [text] in the show: one text, or — when an effect builds [box] by paragraph — each
 * line on its own, played as the [timeline] has that paragraph. [text] carries the box's lines in
 * order (list markers included), one per `\n`.
 */
@Composable
internal fun ShowText(timeline: AnimTimeline, box: SlideBox, step: Int, animate: Boolean, text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    // A box that is an equation shows it typeset.
    com.therealityteam.aeropages.model.MathText.soleEquation(box.paragraph)?.let { eq ->
        val first = box.paragraph.runs.first { it.format.math != null }.format
        EquationText(eq.math, first.fontSizePt ?: box.defaultSizePt, box.align,
            first.color?.toInt() ?: style.color.let { c -> if (c.isSpecified) c.toArgb() else 0xFF1A1A1A.toInt() }, modifier)
        return
    }
    // A list, or lines at deeper levels, shows line by line with its markers and indents.
    val listed = isListed(box)
    if (!timeline.byParagraph(box)) {
        BasicText(if (listed) listedText(text, box.text, box.listStyle) else shownBreaks(text), style = style, modifier = modifier)
        return
    }
    val pieces = if (listed) listedParagraphs(text, box.text, box.listStyle) else {
        var from = 0
        text.text.split('\n').map { line -> shownBreaks(text.subSequence(from, from + line.length)).also { from += line.length + 1 } }
    }
    Column(modifier) {
        pieces.forEachIndexed { i, piece ->
            ShowElement(timeline, box, step, animate, Modifier.fillMaxWidth(), paragraph = i) {
                // A blank line keeps its height.
                BasicText(if (piece.isEmpty()) AnnotatedString(" ") else piece, style = style, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
