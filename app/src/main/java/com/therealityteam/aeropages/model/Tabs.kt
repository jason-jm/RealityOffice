package com.therealityteam.aeropages.model

/** How text lines up at a tab stop: starting there, centred on it, ending there, or its decimal point there. */
enum class TabAlign { LEFT, CENTER, RIGHT, DECIMAL }

/** What fills the space a tab leaves: nothing, dots, hyphens, a rule, middle dots. */
enum class TabLeader { NONE, DOT, HYPHEN, UNDERSCORE, MIDDLE_DOT }

/** A paragraph's tab stop, [posPt] from the left edge of the text column (Word's: from the page margin). */
data class TabStop(val posPt: Float, val align: TabAlign = TabAlign.LEFT, val leader: TabLeader = TabLeader.NONE)

/**
 * Where a paragraph's tabs take its text, as Word lays them out: each tab moves on to the next stop
 * after where it stands — the paragraph's own (left, centre, right or decimal), the left indent where
 * the first line hangs out from it, else the next of the document's default stops past the last
 * custom one — the text after a centre, right or decimal tab placed against the stop. The canvas
 * and the PDF both ask here, each measuring text its own way, so a tab spans the same width on
 * screen and on paper.
 */
object TabLayout {
    /** One tab: how far it advances (points) and what fills the space. */
    class Tab(val advance: Float, val leader: TabLeader)

    /**
     * Each tab of [p] in order, its text measured by [measure] (points), in a column [width] points
     * wide whose lines start and end as [PageMetrics.indents] has them; [defaultTab] apart where the
     * paragraph sets no stop. Lines wrap between words (and between CJK characters) as the text's do.
     */
    fun tabs(p: Paragraph, width: Float, defaultTab: Float, measure: (String, CharacterFormat) -> Float): List<Tab> {
        val text = StringBuilder()
        val formats = ArrayList<CharacterFormat>()
        for (r in p.runs) { text.append(r.text); repeat(r.text.length) { formats.add(r.format) } }
        if ('\t' !in text) return emptyList()
        val f = p.format
        val inset = PageMetrics.indents(f)
        val marker = PageMetrics.markerWidth(f)
        val firstStart = inset[0] + inset[1] + marker
        val restStart = inset[0] + inset[2] + marker
        val right = (width - inset[3]).coerceAtLeast(firstStart + 1f)
        val stops = f.tabStops.sortedBy { it.posPt }
        val lastCustom = stops.lastOrNull()?.posPt ?: 0f
        // A first line hanging out of the left indent stops there too (a number, then its text).
        val hang = (inset[0] + inset[2]).takeIf { it > inset[0] + inset[1] }
        val step = if (defaultTab > 1f) defaultTab else 36f
        fun width(from: Int, to: Int): Float {
            var w = 0f; var i = from
            while (i < to) {
                var j = i + 1
                while (j < to && formats[j] === formats[i]) j++
                w += measure(text.substring(i, j), formats[i]); i = j
            }
            return w
        }
        val out = ArrayList<Tab>()
        var x = firstStart
        var lineStart = firstStart
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> { x = restStart; lineStart = restStart; i++ }
                c == '\t' -> {
                    // What follows, to the next tab or line break: placed against a centre, right or decimal stop.
                    var end = i + 1
                    while (end < text.length && text[end] != '\t' && text[end] != '\n') end++
                    val stop = stops.firstOrNull { it.posPt > x + 0.5f }
                    val custom = stop ?: hang?.takeIf { it > x + 0.5f }?.let { TabStop(it) }
                    val chosen = custom ?: TabStop(((maxOf(x, lastCustom) / step).toInt() + 1) * step)
                    val start = when (chosen.align) {
                        TabAlign.LEFT -> chosen.posPt
                        TabAlign.RIGHT -> chosen.posPt - width(i + 1, end)
                        TabAlign.CENTER -> chosen.posPt - width(i + 1, end) / 2f
                        TabAlign.DECIMAL -> {
                            val dot = (i + 1 until end).firstOrNull { text[it] == '.' || text[it] == '．' } ?: end
                            chosen.posPt - width(i + 1, dot)
                        }
                    }.coerceAtLeast(x)
                    if (start > right) {
                        // No stop left on this line: the text after the tab goes on to the next one.
                        out.add(Tab((right - x).coerceAtLeast(0f), TabLeader.NONE)); x = restStart; lineStart = restStart
                    } else {
                        out.add(Tab(start - x, chosen.leader)); x = start
                    }
                    i++
                }
                else -> {
                    // A word (or one CJK character, or a run of spaces): onto the next line when it does not fit.
                    var j = i + 1
                    if (!PageMetrics.isCjk(c)) while (j < text.length && text[j] != '\t' && text[j] != '\n' && !PageMetrics.isCjk(text[j]) && text[j].isWhitespace() == c.isWhitespace()) j++
                    val w = width(i, j)
                    if (!c.isWhitespace() && x + w > right && x > lineStart + 0.5f) { x = restStart; lineStart = restStart }
                    x += w
                    i = j
                }
            }
        }
        return out
    }
}
