package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.model.AnimClass
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimStart
import com.therealityteam.aeropages.model.AnimTarget
import com.therealityteam.aeropages.model.AnimTimeline
import com.therealityteam.aeropages.model.ElementAnim
import java.util.Locale

/**
 * A slide's animations in ODF: the page's `anim:par` timing root holding the main sequence — click
 * groups (`smil:begin="next"`, or `0s` for what starts with the slide), chains starting at their
 * time in the group, and one `anim:par` per effect naming LibreOffice's preset (class, id, direction),
 * how it starts and its own delay, with the SMIL behaviours that play it (visibility, transition
 * filters, animate / animateTransform), as LibreOffice Impress writes them. Effects find their
 * shapes by `xml:id` / `draw:id`.
 */
internal object OdfAnimations {

    /** LibreOffice's preset ids for our effects (what Impress assigns them on importing PowerPoint's). */
    private val PRESETS: Map<AnimEffect, String> = mapOf(
        AnimEffect.APPEAR to "ooo-entrance-appear",
        AnimEffect.FADE to "ooo-entrance-fade-in",
        AnimEffect.FLY_IN to "ooo-entrance-fly-in",
        AnimEffect.FLOAT_IN to "ooo-entrance-ascend",
        AnimEffect.SPLIT to "ooo-entrance-split",
        AnimEffect.WIPE to "ooo-entrance-wipe",
        AnimEffect.ZOOM to "ooo-entrance-fade-in-and-zoom",
        AnimEffect.GROW to "ooo-entrance-turn-and-grow",
        AnimEffect.BOUNCE to "ooo-entrance-bounce",
        AnimEffect.SPIN to "ooo-entrance-spin-in",
        AnimEffect.PULSE to "ooo-emphasis-flash-bulb",
        AnimEffect.TEETER to "ooo-emphasis-teeter",
        AnimEffect.ROTATE to "ooo-emphasis-spin",
        AnimEffect.GROW_SHRINK to "ooo-emphasis-grow-and-shrink",
        AnimEffect.TRANSPARENCY to "ooo-emphasis-transparency",
        AnimEffect.DISAPPEAR to "ooo-exit-disappear",
        AnimEffect.FADE_OUT to "ooo-exit-fade-out",
        AnimEffect.FLY_OUT to "ooo-exit-fly-out",
        AnimEffect.FLOAT_OUT to "ooo-exit-descend",
        AnimEffect.SPLIT_OUT to "ooo-exit-split",
        AnimEffect.WIPE_OUT to "ooo-exit-wipe",
        AnimEffect.ZOOM_OUT to "ooo-exit-fade-out-and-zoom",
        AnimEffect.SHRINK_TURN to "ooo-exit-turn-and-grow",
    )
    private val BY_PRESET: Map<String, AnimEffect> = PRESETS.entries.associate { (e, id) -> id to e } + mapOf(
        // Impress's own relatives of ours.
        "ooo-entrance-descend" to AnimEffect.FLOAT_IN,
        "ooo-entrance-zoom" to AnimEffect.ZOOM,
        "ooo-entrance-expand" to AnimEffect.ZOOM,
        "ooo-entrance-grow-and-turn" to AnimEffect.GROW,
        "ooo-entrance-spinner" to AnimEffect.SPIN,
        "ooo-entrance-dissolve-in" to AnimEffect.FADE,
        "ooo-exit-ascend" to AnimEffect.FLOAT_OUT,
        "ooo-exit-zoom" to AnimEffect.ZOOM_OUT,
        "ooo-exit-contract" to AnimEffect.ZOOM_OUT,
        "ooo-exit-shrink-and-turn" to AnimEffect.SHRINK_TURN,
        "ooo-exit-dissolve" to AnimEffect.FADE_OUT,
        "ooo-emphasis-rotate" to AnimEffect.ROTATE,
    )

    private fun classOf(c: AnimClass) = when (c) { AnimClass.ENTRANCE -> "entrance"; AnimClass.EMPHASIS -> "emphasis"; AnimClass.EXIT -> "exit" }
    private fun sideOf(d: AnimDir) = when (d) { AnimDir.LEFT -> "left"; AnimDir.RIGHT -> "right"; AnimDir.TOP -> "top"; AnimDir.BOTTOM -> "bottom" }

    /** A SMIL clock value in seconds ("0.5s"). */
    private fun secs(ms: Int): String =
        if (ms % 1000 == 0) "${ms / 1000}s" else String.format(Locale.ROOT, "%.3f", ms / 1000.0).trimEnd('0').trimEnd('.') + "s"

    /** What one effect node plays: the effect on the shape — or the paragraph ([paragraph]) — with id [id], starting as [start]. */
    class Unit(val id: String, val anim: ElementAnim, val start: AnimStart, val paragraph: Boolean = false)

    /** The page's timing root for [units] in list order; "" when there are none. */
    fun timing(units: List<Unit>): String = buildString {
        if (units.isEmpty()) return@buildString
        append("""<anim:par smil:dur="indefinite" smil:restart="never" presentation:node-type="timing-root">""")
        append("""<anim:seq smil:dur="indefinite" presentation:node-type="main-sequence">""")
        AnimTimeline.groups(units, { it.start }) { it.anim }.forEach { group ->
            val auto = group.first().second.first().start != AnimStart.ON_CLICK
            append("""<anim:par smil:begin="${if (auto) "0s" else "next"}" smil:fill="hold">""")
            for ((start, chain) in group) {
                append("""<anim:par smil:begin="${secs(start)}" smil:fill="hold">""")
                for (u in chain) {
                    val a = u.anim
                    val node = when (u.start) { AnimStart.ON_CLICK -> "on-click"; AnimStart.WITH_PREVIOUS -> "with-previous"; AnimStart.AFTER_PREVIOUS -> "after-previous" }
                    val sub = if (a.effect.directional) """ presentation:preset-sub-type="from-${sideOf(a.dir)}"""" else ""
                    append("""<anim:par smil:begin="${secs(a.delayMs)}" smil:fill="hold" presentation:node-type="$node" presentation:preset-class="${classOf(a.effect.cls)}" presentation:preset-id="${PRESETS.getValue(a.effect)}"$sub>""")
                    append(behaviours(u.id, a, u.paragraph))
                    append("</anim:par>")
                }
                append("</anim:par>")
            }
            append("</anim:par>")
        }
        append("</anim:seq></anim:par>")
    }

    /** The SMIL that plays [a] on shape (or [paragraph]) [id], after Impress's own for each preset. */
    private fun behaviours(id: String, a: ElementAnim, paragraph: Boolean = false): String = buildString {
        val d = secs(a.durationMs)
        // Impress marks a paragraph's motion with anim:sub-item="text".
        val tgt = """smil:targetElement="$id"""" + if (paragraph) """ anim:sub-item="text"""" else ""
        fun visible(value: String, at: String) = append("""<anim:set smil:begin="$at" smil:dur="0.001s" smil:fill="hold" $tgt smil:attributeName="visibility" smil:to="$value"/>""")
        fun fade(out: Boolean = false) = append("""<anim:transitionFilter smil:dur="$d" $tgt smil:type="fade" smil:subtype="crossfade"${if (out) """ smil:mode="out"""" else ""}/>""")
        fun move(attr: String, values: String) = append("""<anim:animate smil:dur="$d" smil:fill="hold" $tgt smil:attributeName="$attr" smil:values="$values" smil:keyTimes="0;1"/>""")
        fun scale(from: String?, to: String?, by: String? = null, dur: String = d, extra: String = "") = append(
            """<anim:animateTransform smil:dur="$dur" smil:fill="hold"$extra $tgt""" + (from?.let { """ smil:from="$it"""" } ?: "") +
                (to?.let { """ smil:to="$it"""" } ?: "") + (by?.let { """ smil:by="$it"""" } ?: "") + """ smil:attributeName="transform" svg:type="scale"/>""")
        fun rotate(by: String, dur: String = d, extra: String = "") =
            append("""<anim:animateTransform smil:dur="$dur" smil:fill="hold"$extra $tgt smil:by="$by" smil:attributeName="transform" svg:type="rotate"/>""")
        fun wipe(out: Boolean) {
            val (sub, reverse) = when (a.dir) { AnimDir.LEFT -> "leftToRight" to false; AnimDir.RIGHT -> "leftToRight" to true; AnimDir.TOP -> "topToBottom" to false; AnimDir.BOTTOM -> "topToBottom" to true }
            append("""<anim:transitionFilter smil:dur="$d" $tgt smil:type="barWipe" smil:subtype="$sub"${if (reverse) """ smil:direction="reverse"""" else ""}${if (out) """ smil:mode="out"""" else ""}/>""")
        }
        /** The x or y ramp of a fly (from / to just off the slide) or a float (a short drift), on the [a.dir] side. */
        fun ramp(far: Boolean, inward: Boolean) {
            val (attr, off) = when (a.dir) {
                AnimDir.LEFT -> "x" to (if (far) "0-width/2" else "x-.1")
                AnimDir.RIGHT -> "x" to (if (far) "1+width/2" else "x+.1")
                AnimDir.TOP -> "y" to (if (far) "0-height/2" else "y-.1")
                AnimDir.BOTTOM -> "y" to (if (far) "1+height/2" else "y+.1")
            }
            move(attr, if (inward) "$off;$attr" else "$attr;$off")
        }
        val ms = a.durationMs
        if (a.effect.cls == AnimClass.ENTRANCE) visible("visible", "0s")
        when (a.effect) {
            AnimEffect.APPEAR, AnimEffect.DISAPPEAR -> {}
            AnimEffect.FADE -> fade()
            AnimEffect.FLY_IN -> ramp(far = true, inward = true)
            AnimEffect.FLOAT_IN -> { fade(); ramp(far = false, inward = true) }
            AnimEffect.SPLIT -> append("""<anim:transitionFilter smil:dur="$d" $tgt smil:type="barnDoorWipe" smil:subtype="vertical" smil:direction="reverse"/>""")
            AnimEffect.WIPE -> wipe(out = false)
            AnimEffect.ZOOM -> { fade(); scale("0,0", "1,1") }
            AnimEffect.GROW -> { fade(); scale("0,0", "1,1"); rotate("-90") }
            AnimEffect.BOUNCE -> { fade(); move("y", "y-.25;y") }
            AnimEffect.SPIN -> { fade(); scale("0,0", "1,1"); rotate("360") }
            AnimEffect.PULSE -> scale(null, null, by = "0.1,0.1", dur = secs((ms / 2).coerceAtLeast(1)), extra = """ smil:autoReverse="true"""")
            AnimEffect.TEETER -> rotate("4", dur = secs((ms / 8).coerceAtLeast(1)), extra = """ smil:autoReverse="true" smil:repeatCount="4"""")
            AnimEffect.ROTATE -> rotate("360")
            AnimEffect.GROW_SHRINK -> scale(null, null, by = "0.5,0.5")
            AnimEffect.TRANSPARENCY -> append("""<anim:set smil:dur="$d" smil:fill="hold" $tgt smil:attributeName="opacity" smil:to="0.5"/>""")
            AnimEffect.FADE_OUT -> fade(out = true)
            AnimEffect.FLY_OUT -> ramp(far = true, inward = false)
            AnimEffect.FLOAT_OUT -> { fade(out = true); ramp(far = false, inward = false) }
            AnimEffect.SPLIT_OUT -> append("""<anim:transitionFilter smil:dur="$d" $tgt smil:type="barnDoorWipe" smil:subtype="vertical" smil:mode="out"/>""")
            AnimEffect.WIPE_OUT -> wipe(out = true)
            AnimEffect.ZOOM_OUT -> { fade(out = true); scale("1,1", "0,0") }
            AnimEffect.SHRINK_TURN -> { fade(out = true); scale("1,1", "0,0"); rotate("90") }
        }
        if (a.effect.cls == AnimClass.EXIT) visible("hidden", if (a.effect == AnimEffect.DISAPPEAR) "0s" else secs((ms - 1).coerceAtLeast(0)))
    }

    /** A SMIL clock value ("0.5s", "250ms", "1.2", "00:00:01.5") in ms; null for "next", "indefinite" and the like. */
    private fun ms(v: String?): Int? {
        val t = v?.trim() ?: return null
        return when {
            t.endsWith("ms") -> t.removeSuffix("ms").toDoubleOrNull()
            t.endsWith("s") -> t.removeSuffix("s").toDoubleOrNull()?.times(1000)
            t.contains(':') -> t.split(':').map { it.toDoubleOrNull() ?: return null }.fold(0.0) { acc, x -> acc * 60 + x } * 1000
            else -> t.toDoubleOrNull()?.times(1000)
        }?.let { Math.round(it).toInt() }
    }

    /**
     * The animations of [page] onto the elements [targets] names by id, numbered in list order:
     * each effect `anim:par` with a preset class and a target; its duration is its longest behaviour
     * (twice when auto-reversing, times its repeat count). Motion paths, sounds and the like are skipped.
     */
    fun read(page: OdfNode, targets: Map<String, AnimTarget>, paragraphs: Map<String, AnimTarget> = emptyMap()) {
        if (targets.isEmpty() && paragraphs.isEmpty()) return
        val root = page.children("par").firstOrNull { it.attr("node-type") == "timing-root" } ?: return
        val main = root.children.firstOrNull { it.attr("node-type") == "main-sequence" } ?: return
        var order = 0
        // Impress builds a box by paragraph as one effect per paragraph, each naming its text:p: the
        // first becomes the box's effect, built by paragraph; the rest (same class and preset) are its own.
        val byParagraph = java.util.IdentityHashMap<AnimTarget, MutableSet<Pair<AnimClass, String?>>>()
        for (group in main.children("par")) for (chain in group.children("par")) for (effect in chain.children("par")) {
            val cls = when (effect.attr("preset-class")) { "entrance" -> AnimClass.ENTRANCE; "emphasis" -> AnimClass.EMPHASIS; "exit" -> AnimClass.EXIT; else -> continue }
            val behaviours = effect.children
            val ref = behaviours.firstNotNullOfOrNull { it.attr("targetElement") } ?: continue
            val paragraphBox = paragraphs[ref]
            val target = targets[ref] ?: paragraphBox ?: continue
            if (paragraphBox != null && !byParagraph.getOrPut(paragraphBox) { HashSet() }.add(cls to effect.attr("preset-id"))) continue
            val preset = effect.attr("preset-id")
            val chosen = BY_PRESET[preset]?.takeIf { it.cls == cls } ?: when (cls) {
                AnimClass.ENTRANCE -> AnimEffect.FADE
                AnimClass.EMPHASIS -> AnimEffect.PULSE
                AnimClass.EXIT -> AnimEffect.FADE_OUT
            }
            val start = when (effect.attr("node-type")) {
                "with-previous" -> AnimStart.WITH_PREVIOUS
                "after-previous" -> AnimStart.AFTER_PREVIOUS
                else -> AnimStart.ON_CLICK
            }
            val dur = behaviours.mapNotNull { b ->
                ms(b.attr("dur"))?.takeIf { it > 1 }?.let { d ->
                    val rev = if (b.attr("autoReverse") == "true") 2 else 1
                    val times = b.attr("repeatCount")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                    Math.round(d * rev * times).toInt()
                }
            }.maxOrNull() ?: 500
            val sub = effect.attr("preset-sub-type").orEmpty()
            val dir = when {
                sub.endsWith("right") -> AnimDir.RIGHT
                sub.endsWith("top") -> AnimDir.TOP
                sub.endsWith("bottom") -> AnimDir.BOTTOM
                else -> AnimDir.LEFT
            }
            target.anims.add(ElementAnim(chosen, start, dur.coerceIn(ElementAnim.MIN_MS, ElementAnim.MAX_MS),
                (ms(effect.attr("begin")) ?: 0).coerceIn(0, ElementAnim.MAX_MS), dir, order++, byParagraph = paragraphBox != null))
        }
    }
}
