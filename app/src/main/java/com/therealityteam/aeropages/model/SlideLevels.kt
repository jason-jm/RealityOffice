package com.therealityteam.aeropages.model

/**
 * Outline levels of a slide text box's lines — PowerPoint's paragraph levels (`a:pPr@lvl`, 0–8),
 * ODF's nested lists. A line's level is the tab characters it starts with, as Tab at the start of
 * a line makes it in PowerPoint: typing, undo, copying and pasting keep levels with the text.
 * Deeper lines indent a step further each; a list box marks them with the level's bullet, or
 * numbers them level by level (1. / a. / i.), a shallower item restarting the deeper counts.
 */
object SlideLevels {
    /** The deepest level (PowerPoint's nine). */
    const val MAX = 8

    /**
     * A line break inside a line — PowerPoint's Shift+Enter (`a:br`), ODF's `text:line-break`: the
     * text goes on on a new line of the same item, under its bullet, at its level. (A '\n' starts
     * the next item.)
     */
    const val LINE_BREAK = '\u2028'

    /** Bullets by level, as Office's classic outline gives them. */
    private val BULLETS = listOf("•", "–", "•", "–", "»", "•", "–", "•", "–")

    /** Level of the line starting at [start] of [text]: its leading tabs, at most [MAX]. */
    fun levelAt(text: CharSequence, start: Int): Int {
        var i = start
        while (i < text.length && text[i] == '\t' && i - start < MAX) i++
        return i - start
    }

    /** Where each line of [text] starts. */
    fun lineStarts(text: CharSequence): IntArray {
        val out = ArrayList<Int>()
        out.add(0)
        for (i in text.indices) if (text[i] == '\n') out.add(i + 1)
        return out.toIntArray()
    }

    /** Each line's level. */
    fun levels(text: CharSequence): IntArray = lineStarts(text).map { levelAt(text, it) }.toIntArray()

    /** Whether any line of [text] is deeper than the first level. */
    fun hasLevels(text: CharSequence): Boolean = text.startsWith('\t') || text.contains("\n\t")

    /** The line of [text] holding offset [at]. */
    fun lineOf(text: CharSequence, at: Int): Int {
        var line = 0
        for (i in 0 until at.coerceAtMost(text.length)) if (text[i] == '\n') line++
        return line
    }

    /** [text] without its lines' level tabs. */
    fun strip(text: String): String = if (!hasLevels(text)) text else text.split('\n').joinToString("\n") { it.substring(levelAt(it, 0)) }

    fun bullet(level: Int): String = BULLETS[level.coerceIn(0, MAX)]

    /** The number of each line within its level: counted per level, restarting under each shallower line. */
    fun numbers(levels: IntArray): IntArray {
        val counts = IntArray(MAX + 1)
        return IntArray(levels.size) { i ->
            val l = levels[i]
            counts[l]++
            for (d in l + 1..MAX) counts[d] = 0
            counts[l]
        }
    }

    /** Number [n] as level [level] shows it: 1. at the first level, a. at the second, i. at the third, and round again. */
    fun numberLabel(n: Int, level: Int): String = when (level % 3) {
        0 -> "$n."
        1 -> alpha(n) + "."
        else -> roman(n).lowercase() + "."
    }

    /** The marker a line shows in a list of [style]: its level's bullet, or its number; "" for none. */
    fun marker(style: SlideListStyle, level: Int, number: Int): String = when (style) {
        SlideListStyle.BULLET -> bullet(level)
        SlideListStyle.NUMBER -> numberLabel(number, level)
        SlideListStyle.NONE -> ""
    }

    /** PowerPoint's automatic numbering scheme for a level (`a:buAutoNum@type`). */
    fun autoNumType(level: Int): String = when (level % 3) { 0 -> "arabicPeriod"; 1 -> "alphaLcPeriod"; else -> "romanLcPeriod" }

    /** ODF's number format for a level (`style:num-format`). */
    fun odfNumFormat(level: Int): String = when (level % 3) { 0 -> "1"; 1 -> "a"; else -> "i" }

    /** a, b … z, aa, ab … */
    private fun alpha(n: Int): String {
        var k = n.coerceAtLeast(1)
        val sb = StringBuilder()
        while (k > 0) { k--; sb.insert(0, 'a' + k % 26); k /= 26 }
        return sb.toString()
    }

    private fun roman(n: Int): String {
        var k = n.coerceIn(1, 3999)
        val sb = StringBuilder()
        for ((v, s) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
            while (k >= v) { sb.append(s); k -= v }
        }
        return sb.toString()
    }

    /**
     * The width of [marker] in ems of the text round it, roughly as a sans-serif face sets it —
     * how far wrapped lines hang under a line's text.
     */
    fun markerEms(marker: String): Float = marker.sumOf { ch ->
        when (ch) {
            '•' -> 0.35; '–' -> 0.5; '»' -> 0.5; '.' -> 0.28; ' ' -> 0.28
            in '0'..'9' -> 0.56
            'i', 'l', 'j' -> 0.22
            'm', 'w' -> 0.83
            else -> 0.5
        }
    }.toFloat()

    /** The indent per level, in ems. */
    const val STEP_EMS = 1.3f

    /** The gap between a marker and its line's text, in ems. */
    const val GAP_EMS = 0.5f
}
