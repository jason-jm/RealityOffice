package com.therealityteam.aeropages.editor

/**
 * PowerPoint's "Slides from Outline": plain text where every unindented line starts a slide
 * (its title) and lines indented with a tab or spaces (or begun with "- " / "• ") are that
 * slide's text, one bullet per line. Deeper indents are deeper list levels — each distinct
 * indent of a slide's lines a level, as the leading tabs of [OutlineSlide.lines] say. Blank
 * lines are skipped. Text before the first title becomes a slide of its own so nothing is lost.
 */
object OutlineImport {
    class OutlineSlide(val title: String, val lines: List<String>)

    fun parse(text: String): List<OutlineSlide> {
        val out = ArrayList<OutlineSlide>()
        var title: String? = null
        var lines = ArrayList<Pair<Int, String>>()
        fun flush() {
            title?.let { t ->
                // Each distinct indent is a level, the shallowest the first.
                val widths = lines.map { it.first }.distinct().sorted()
                out.add(OutlineSlide(t, lines.map { (w, s) -> "\t".repeat(widths.indexOf(w).coerceAtMost(com.therealityteam.aeropages.model.SlideLevels.MAX)) + s }))
            }
            title = null; lines = ArrayList()
        }
        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            // A tab counts as four spaces.
            var width = 0
            for (ch in raw) { if (ch == '\t') width += 4 else if (ch == ' ') width++ else break }
            val body = raw.trimStart()
            val bulleted = body.startsWith("- ") || body.startsWith("• ")
            val content = body.removePrefix("- ").removePrefix("• ").trim()
            if (content.isEmpty()) continue
            if ((width > 0 || bulleted) && title != null) lines.add(width to content)
            else { flush(); title = content }
        }
        flush()
        return out
    }
}
