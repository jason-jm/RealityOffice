package com.therealityteam.aeropages.model

/** PowerPoint's effect classes: whether an effect brings its element in, stresses it or takes it away. */
enum class AnimClass { ENTRANCE, EMPHASIS, EXIT }

/**
 * An animation effect as PowerPoint's Animations gallery offers it: its class, PowerPoint's preset
 * id within that class (`p:cTn@presetID`), and whether it has a direction (Effect Options).
 */
enum class AnimEffect(val cls: AnimClass, val presetId: Int, val directional: Boolean = false) {
    APPEAR(AnimClass.ENTRANCE, 1),
    FADE(AnimClass.ENTRANCE, 10),
    FLY_IN(AnimClass.ENTRANCE, 2, directional = true),
    FLOAT_IN(AnimClass.ENTRANCE, 42, directional = true),
    SPLIT(AnimClass.ENTRANCE, 16),
    WIPE(AnimClass.ENTRANCE, 22, directional = true),
    ZOOM(AnimClass.ENTRANCE, 53),
    /** Grow & Turn. */
    GROW(AnimClass.ENTRANCE, 31),
    BOUNCE(AnimClass.ENTRANCE, 26),
    /** Spinner: spins in as it grows. */
    SPIN(AnimClass.ENTRANCE, 49),

    PULSE(AnimClass.EMPHASIS, 26),
    TEETER(AnimClass.EMPHASIS, 32),
    /** Emphasis Spin: one full turn. */
    ROTATE(AnimClass.EMPHASIS, 8),
    /** Grows to 150% and stays so. */
    GROW_SHRINK(AnimClass.EMPHASIS, 6),
    /** Turns half transparent and stays so. */
    TRANSPARENCY(AnimClass.EMPHASIS, 9),

    DISAPPEAR(AnimClass.EXIT, 1),
    FADE_OUT(AnimClass.EXIT, 10),
    FLY_OUT(AnimClass.EXIT, 2, directional = true),
    FLOAT_OUT(AnimClass.EXIT, 42, directional = true),
    SPLIT_OUT(AnimClass.EXIT, 16),
    WIPE_OUT(AnimClass.EXIT, 22, directional = true),
    ZOOM_OUT(AnimClass.EXIT, 53),
    SHRINK_TURN(AnimClass.EXIT, 31),
    ;

    /** Appear and Disappear happen at once: they take no time, whatever duration they carry. */
    val instant: Boolean get() = this == APPEAR || this == DISAPPEAR

    companion object {
        /** The effect a file names by [cls] and PowerPoint [preset] id: close relatives map to ours, others to a fade (entrance and exit) or a pulse. */
        fun of(cls: AnimClass, preset: Int): AnimEffect = entries.firstOrNull { it.cls == cls && it.presetId == preset } ?: when (cls) {
            AnimClass.ENTRANCE -> when (preset) {
                3 -> SPLIT                   // Blinds (and our own older files' Split)
                8 -> SPIN                    // older files' Spin
                6 -> GROW                    // older files' Grow
                23 -> ZOOM                   // Basic Zoom
                47 -> FLOAT_IN               // Float Down
                12, 37, 55 -> FLOAT_IN       // Peek In, Rise Up, Expand
                else -> FADE
            }
            AnimClass.EXIT -> when (preset) {
                23 -> ZOOM_OUT
                47 -> FLOAT_OUT
                3 -> SPLIT_OUT
                else -> FADE_OUT
            }
            AnimClass.EMPHASIS -> when (preset) {
                33, 34 -> PULSE              // Vertical Grow, Wave
                else -> PULSE
            }
        }
    }
}

/** Direction option for directional effects (效果选项: fly in / out, wipe, float). */
enum class AnimDir { LEFT, RIGHT, TOP, BOTTOM }

/** When an effect starts (PowerPoint's Start: On Click / With Previous / After Previous). */
enum class AnimStart { ON_CLICK, WITH_PREVIOUS, AFTER_PREVIOUS }

/**
 * One entry of a slide's animation list (PowerPoint's Animation Pane), kept on the element it
 * animates so it follows the element through copies, undo and deletion. [order] places it in the
 * slide's list across all elements.
 */
class ElementAnim(
    var effect: AnimEffect,
    var start: AnimStart = AnimStart.ON_CLICK,
    var durationMs: Int = 500,
    var delayMs: Int = 0,
    var dir: AnimDir = AnimDir.LEFT,
    var order: Int = 0,
    /** Effect Options' By Paragraph: a text box's paragraphs (its lines) play one by one instead of the box as one object. */
    var byParagraph: Boolean = false,
) {
    /** How long the effect runs in its step: its duration, or nothing for an instant one. */
    val lengthMs: Int get() = if (effect.instant) 0 else durationMs

    fun copyOf(): ElementAnim = ElementAnim(effect, start, durationMs, delayMs, dir, order, byParagraph)
    fun sameAs(o: ElementAnim): Boolean =
        effect == o.effect && start == o.start && durationMs == o.durationMs && delayMs == o.delayMs && dir == o.dir &&
            order == o.order && byParagraph == o.byParagraph

    companion object {
        const val MIN_MS = 100
        const val MAX_MS = 20_000
    }
}

/** An element that can carry animation effects: a text box (title, body, a shape's text), a picture, a table or a chart. */
interface AnimTarget {
    val anims: MutableList<ElementAnim>
}

/**
 * A slide's animation timeline: its effects in order, split into the steps a show plays. Step 0
 * holds what starts with the slide (effects before the first On Click one); each On Click effect
 * opens the next step, With Previous and After Previous ones join it. Within a step every effect
 * has a start time: With Previous starts with the effect before it, After Previous once everything
 * before it in the step has finished; each then waits its own delay.
 */
class AnimTimeline private constructor(val entries: List<Entry>, private val units: List<Unit>, val steps: List<List<Timed>>) {

    /** One effect of the slide's list with the element it animates. */
    class Entry(val target: AnimTarget, val anim: ElementAnim)

    /**
     * What a show plays as one: an effect on a whole element ([paragraph] -1), or on one paragraph of
     * a text box whose effect is built by paragraph. The first paragraph starts as the effect does;
     * the others come one per click when it starts on a click, else each after the one before.
     */
    class Unit(val target: AnimTarget, val anim: ElementAnim, val paragraph: Int, val start: AnimStart)

    /** A unit placed in its step: [startMs] after the step begins. */
    class Timed(val target: AnimTarget, val anim: ElementAnim, val startMs: Int, val paragraph: Int = -1) {
        val endMs: Int get() = startMs + anim.lengthMs
    }

    /** How an element shows once some steps have played: visible or not, and what lasting emphasis did to it. */
    data class State(val visible: Boolean, val scale: Float = 1f, val alpha: Float = 1f)

    /** The clicks the slide takes before the show moves on. */
    val clicks: Int get() = steps.size - 1

    /** How long step [step] runs, its last effect's end. */
    fun lengthOf(step: Int): Int = steps.getOrNull(step)?.maxOfOrNull { it.endMs } ?: 0

    /** Whether [target] has effects built by paragraph, whose paragraphs a show draws one by one. */
    fun byParagraph(target: AnimTarget): Boolean = units.any { it.target === target && it.paragraph >= 0 }

    /**
     * [target]'s state once steps 0..[step] have played ([step] -1: before the slide's first step) —
     * of the whole element, or with [paragraph] ≥ 0 of that paragraph under effects built by paragraph.
     */
    fun stateAt(target: AnimTarget, step: Int, paragraph: Int = -1): State {
        val own = units.filter { it.target === target && it.paragraph == paragraph }
        if (own.isEmpty()) return State(visible = true)
        var visible = own.first().anim.effect.cls != AnimClass.ENTRANCE
        var scale = 1f
        var alpha = 1f
        for (s in 0..step.coerceAtMost(steps.lastIndex)) for (t in steps[s]) {
            if (t.target !== target || t.paragraph != paragraph) continue
            when (t.anim.effect.cls) {
                AnimClass.ENTRANCE -> visible = true
                AnimClass.EXIT -> visible = false
                AnimClass.EMPHASIS -> when (t.anim.effect) {
                    AnimEffect.GROW_SHRINK -> scale *= GROW_SHRINK_SCALE
                    AnimEffect.TRANSPARENCY -> alpha = TRANSPARENCY_ALPHA
                    else -> {}
                }
            }
        }
        return State(visible, scale, alpha)
    }

    companion object {
        const val GROW_SHRINK_SCALE = 1.5f
        const val TRANSPARENCY_ALPHA = 0.5f

        /** The animated elements of [slide] in the order ties are broken in: title, body, pictures, shapes, tables, charts. */
        fun targets(slide: Slide): List<AnimTarget> = buildList {
            add(slide.title); add(slide.body)
            addAll(slide.images); slide.shapes.forEach { add(it.box) }; addAll(slide.tables); addAll(slide.charts)
        }

        /** [slide]'s effects in list order (a stable sort on [ElementAnim.order], so ties keep the element order). */
        fun entriesOf(slide: Slide): List<Entry> =
            targets(slide).flatMap { t -> t.anims.map { Entry(t, it) } }.sortedBy { it.anim.order }

        /** Renumber [slide]'s effects 0, 1, 2… in their list order. */
        fun normalize(slide: Slide) { entriesOf(slide).forEachIndexed { i, e -> e.anim.order = i } }

        /** The paragraphs (non-blank lines, by index) of [target] an effect built by paragraph plays one by one. */
        fun paragraphsOf(target: AnimTarget): List<Int> =
            (target as? SlideBox)?.text?.split('\n')?.withIndex()?.filter { it.value.isNotBlank() }?.map { it.index }.orEmpty()

        /** [entries] as the units a show plays: an effect built by paragraph on a box of two or more paragraphs becomes one per paragraph. */
        fun units(entries: List<Entry>): List<Unit> = entries.flatMap { e ->
            val paras = if (e.anim.byParagraph) paragraphsOf(e.target) else emptyList()
            if (paras.size < 2) listOf(Unit(e.target, e.anim, -1, e.anim.start))
            else paras.mapIndexed { k, p ->
                val start = when {
                    k == 0 -> e.anim.start
                    e.anim.start == AnimStart.ON_CLICK -> AnimStart.ON_CLICK
                    else -> AnimStart.AFTER_PREVIOUS
                }
                Unit(e.target, e.anim, p, start)
            }
        }

        /**
         * [items] (in list order; [start] and [anim] give each one's start and effect) grouped the way
         * PowerPoint and ODF store a slide's animations: click groups — the first one starts with the
         * slide when its first item is not On Click — each a list of chains, the items that start
         * together with the chain's start (ms) in the group. After Previous opens a chain once
         * everything before it in the group has finished; With Previous joins the chain running.
         */
        fun <T> groups(items: List<T>, start: (T) -> AnimStart, anim: (T) -> ElementAnim): List<List<Pair<Int, List<T>>>> {
            val groups = ArrayList<MutableList<Pair<Int, MutableList<T>>>>()
            var chainStart = 0
            var groupEnd = 0
            for ((i, item) in items.withIndex()) {
                val a = anim(item)
                val s = start(item)
                when {
                    s == AnimStart.ON_CLICK || i == 0 -> {
                        groups.add(ArrayList()); chainStart = 0; groupEnd = 0
                        groups.last().add(0 to mutableListOf(item))
                    }
                    s == AnimStart.AFTER_PREVIOUS -> { chainStart = groupEnd; groups.last().add(chainStart to mutableListOf(item)) }
                    else -> groups.last().last().second.add(item)
                }
                groupEnd = maxOf(groupEnd, chainStart + a.delayMs + a.lengthMs)
            }
            return groups
        }

        fun of(slide: Slide): AnimTimeline {
            val entries = entriesOf(slide)
            val units = units(entries)
            val steps = ArrayList<MutableList<Timed>>()
            steps.add(ArrayList())
            var chainStart = 0      // when the units running together in this step began
            var stepEnd = 0         // when everything so far in this step has finished
            for (u in units) {
                val a = u.anim
                when (u.start) {
                    AnimStart.ON_CLICK -> { steps.add(ArrayList()); chainStart = 0; stepEnd = 0 }
                    AnimStart.AFTER_PREVIOUS -> chainStart = stepEnd
                    AnimStart.WITH_PREVIOUS -> {}
                }
                val start = chainStart + a.delayMs
                steps.last().add(Timed(u.target, a, start, u.paragraph))
                stepEnd = maxOf(stepEnd, start + a.lengthMs)
            }
            return AnimTimeline(entries, units, steps)
        }
    }
}
