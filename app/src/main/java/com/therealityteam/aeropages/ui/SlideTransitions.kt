package com.therealityteam.aeropages.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import com.therealityteam.aeropages.model.SlideTransition
import com.therealityteam.aeropages.model.TransitionDir

/**
 * How the show moves from slide to slide, as PowerPoint's transitions go: fades, pushes, covers and uncovers, wipes and
 * splits from their side, zooms in or out — as Compose's own enter and exit animations — and random bars, a shape, a wheel
 * and a dissolve drawn as the arriving slide revealed bar by bar, through a growing circle, spoke by spoke, block by block.
 */
internal object SlideTransitions {
    /** Kinds drawn by clipping the arriving slide ([reveal]) as the transition runs. */
    fun drawn(t: SlideTransition): Boolean = t == SlideTransition.RANDOM_BARS || t == SlideTransition.SHAPE || t == SlideTransition.WHEEL || t == SlideTransition.DISSOLVE

    /** The enter and exit animations for arriving at a slide with [t] going [dir], [ms] long; [forward]: moving on (back mirrors the sides). */
    fun spec(t: SlideTransition, dir: TransitionDir, ms: Int, forward: Boolean): ContentTransform {
        val d = TransitionDir.of(t, dir)
        val spec = tween<androidx.compose.ui.unit.IntOffset>(ms)
        // The old slide stays as it is until the new one has arrived (a fade that holds until its very end).
        val keep = fadeOut(tween(ms, easing = { if (it < 1f) 0f else 1f }))
        // The side the new slide comes from, mirrored when going back.
        val from = if (forward) d else when (d) {
            TransitionDir.FROM_RIGHT -> TransitionDir.FROM_LEFT; TransitionDir.FROM_LEFT -> TransitionDir.FROM_RIGHT
            TransitionDir.FROM_TOP -> TransitionDir.FROM_BOTTOM; TransitionDir.FROM_BOTTOM -> TransitionDir.FROM_TOP
            else -> d
        }
        fun slideIn(): EnterTransition = when (from) {
            TransitionDir.FROM_LEFT -> slideInHorizontally(spec) { -it }
            TransitionDir.FROM_TOP -> slideInVertically(spec) { -it }
            TransitionDir.FROM_BOTTOM -> slideInVertically(spec) { it }
            else -> slideInHorizontally(spec) { it }
        }
        fun slideOut(): ExitTransition = when (from) {
            TransitionDir.FROM_LEFT -> slideOutHorizontally(spec) { it }
            TransitionDir.FROM_TOP -> slideOutVertically(spec) { it }
            TransitionDir.FROM_BOTTOM -> slideOutVertically(spec) { -it }
            else -> slideOutHorizontally(spec) { -it }
        }
        return when (t) {
            SlideTransition.FADE -> fadeIn(tween(ms)) togetherWith fadeOut(tween(ms))
            SlideTransition.PUSH -> slideIn() togetherWith slideOut()
            // Cover: the new slide slides over the old, which stays put; uncover: the old slides away off the new.
            SlideTransition.COVER -> slideIn() togetherWith keep
            SlideTransition.UNCOVER -> ContentTransform(EnterTransition.None, slideOut(), targetContentZIndex = -1f)
            SlideTransition.WIPE -> when (from) {
                TransitionDir.FROM_RIGHT -> expandHorizontally(tween(ms), expandFrom = Alignment.End, clip = true)
                TransitionDir.FROM_TOP -> expandVertically(tween(ms), expandFrom = Alignment.Top, clip = true)
                TransitionDir.FROM_BOTTOM -> expandVertically(tween(ms), expandFrom = Alignment.Bottom, clip = true)
                else -> expandHorizontally(tween(ms), expandFrom = Alignment.Start, clip = true)
            } togetherWith keep
            // Split: the new slide opens out from the middle line, or the old closes in on it.
            SlideTransition.SPLIT -> when (d) {
                TransitionDir.VERTICAL_OUT -> expandHorizontally(tween(ms), expandFrom = Alignment.CenterHorizontally, clip = true) togetherWith keep
                TransitionDir.HORIZONTAL_IN -> ContentTransform(EnterTransition.None, shrinkVertically(tween(ms), shrinkTowards = Alignment.CenterVertically, clip = true), targetContentZIndex = -1f)
                TransitionDir.VERTICAL_IN -> ContentTransform(EnterTransition.None, shrinkHorizontally(tween(ms), shrinkTowards = Alignment.CenterHorizontally, clip = true), targetContentZIndex = -1f)
                else -> expandVertically(tween(ms), expandFrom = Alignment.CenterVertically, clip = true) togetherWith keep
            }
            SlideTransition.ZOOM ->
                if (d == TransitionDir.OUT) (scaleIn(tween(ms), initialScale = 1.4f) + fadeIn(tween(ms))) togetherWith (scaleOut(tween(ms), targetScale = 0.6f) + fadeOut(tween(ms)))
                else (scaleIn(tween(ms), initialScale = 0.6f) + fadeIn(tween(ms))) togetherWith (scaleOut(tween(ms), targetScale = 1.4f) + fadeOut(tween(ms)))
            SlideTransition.RANDOM_BARS, SlideTransition.SHAPE, SlideTransition.WHEEL, SlideTransition.DISSOLVE -> EnterTransition.None togetherWith keep
            SlideTransition.CUT, SlideTransition.NONE -> EnterTransition.None togetherWith ExitTransition.None
        }
    }

    /** The arriving slide shown as far as [progress] (0…1) of a drawn transition [t] going [dir] has revealed it. */
    fun Modifier.reveal(t: SlideTransition, dir: TransitionDir, progress: () -> Float): Modifier = drawWithContent {
        val p = progress()
        if (p >= 1f) { drawContent(); return@drawWithContent }
        val path = revealPath(t, TransitionDir.of(t, dir), p, size)
        clipPath(path) { this@drawWithContent.drawContent() }
    }

    /** The part of a slide [size] large that [t] going [d] shows at [p]. */
    internal fun revealPath(t: SlideTransition, d: TransitionDir, p: Float, size: Size): Path {
        val path = Path()
        val w = size.width; val h = size.height
        when (t) {
            SlideTransition.RANDOM_BARS -> {
                // Bars in a fixed random order: horizontal ones down the slide, vertical ones across it.
                val n = 48
                val shown = (p * n).toInt()
                val vertical = d == TransitionDir.VERTICAL_OUT
                order(n, 7).take(shown).forEach { k ->
                    if (vertical) path.addRect(Rect(Offset(w * k / n, 0f), Size(w / n + 0.5f, h)))
                    else path.addRect(Rect(Offset(0f, h * k / n), Size(w, h / n + 0.5f)))
                }
            }
            SlideTransition.DISSOLVE -> {
                val cols = 24; val rows = 14
                val shown = (p * cols * rows).toInt()
                order(cols * rows, 11).take(shown).forEach { k ->
                    val c = k % cols; val r = k / cols
                    path.addRect(Rect(Offset(w * c / cols, h * r / rows), Size(w / cols + 0.5f, h / rows + 0.5f)))
                }
            }
            SlideTransition.SHAPE -> {
                val reach = kotlin.math.hypot(w, h) / 2f
                val centre = Offset(w / 2f, h / 2f)
                if (d == TransitionDir.OUT) {
                    // Out: the old slide shrinks away through a closing circle — the new one shows outside it.
                    val r = reach * (1f - p)
                    path.fillType = PathFillType.EvenOdd
                    path.addRect(Rect(Offset.Zero, size))
                    path.addOval(Rect(centre - Offset(r, r), Size(2 * r, 2 * r)))
                } else {
                    val r = reach * p
                    path.addOval(Rect(centre - Offset(r, r), Size(2 * r, 2 * r)))
                }
            }
            SlideTransition.WHEEL -> {
                // Four spokes sweeping round from twelve, three, six and nine o'clock.
                val reach = kotlin.math.hypot(w, h)
                val box = Rect(Offset(w / 2f - reach, h / 2f - reach), Size(2 * reach, 2 * reach))
                for (k in 0 until 4) {
                    path.moveTo(w / 2f, h / 2f)
                    path.arcTo(box, -90f + k * 90f, 90f * p, false)
                    path.close()
                }
            }
            else -> path.addRect(Rect(Offset.Zero, size))
        }
        return path
    }

    /** 0 until [n] in a fixed shuffled order ([seed]). */
    private fun order(n: Int, seed: Int): List<Int> = orders.getOrPut(n * 31 + seed) { (0 until n).shuffled(java.util.Random(seed.toLong())) }
    private val orders = java.util.concurrent.ConcurrentHashMap<Int, List<Int>>()
}
