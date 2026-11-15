package com.therealityteam.aeropages.model

/**
 * How a paragraph's drop cap ([ParagraphFormat.dropCap]) is set, the same on the canvas and in the PDF: the letters
 * sized so their capitals run from the first line's cap height down to the [DropCap.lines]th line's baseline, sitting
 * on that baseline; dropped, the text's first lines are indented past them by their width and the distance; in the
 * margin, they stand that far left of the text and the lines keep their place.
 */
object DropCaps {
    /** How tall a capital letter is for its size: Latin, Greek and Cyrillic capitals about 0.7 em, a CJK character nearly the whole em. */
    fun capRatio(text: String): Float = if (text.any { isWide(it) }) 0.86f else 0.7f

    private fun isWide(c: Char): Boolean = c.code in 0x2E80..0x9FFF || c.code in 0xAC00..0xD7AF || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF

    /**
     * How many characters a new drop cap on [p] takes: its first letter, with the opening quotes and brackets before it
     * (Word keeps “T or （1 together); 0 when the paragraph starts with a space, a tab or a break.
     */
    fun firstLetters(p: Paragraph): Int {
        val text = buildString { for (r in p.runs) { append(r.text); if (length > 8) break } }
        if (text.isEmpty() || text[0].isWhitespace() || text[0] == '\u000C') return 0
        var n = 0
        while (n < text.length - 1 && opens(text[n]) && !text[n + 1].isWhitespace()) n++
        return n + 1
    }

    private fun opens(c: Char): Boolean = Character.getType(c).let { it == Character.START_PUNCTUATION.toInt() || it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() } || c == '"' || c == '\''

    /** How many of [p]'s first characters its drop cap sets: none without one, or when the text starts with a space, a tab or a break. */
    fun length(p: Paragraph): Int {
        val d = p.format.dropCap ?: return 0
        val text = buildString { for (r in p.runs) { append(r.text); if (length > d.length) break } }
        if (text.isEmpty() || text[0].isWhitespace() || text[0] == '\u000C') return 0
        var n = 0
        while (n < d.length && n < text.length && !text[n].isWhitespace()) n++
        return n
    }

    /** The letters [p]'s drop cap sets (empty: none). */
    fun text(p: Paragraph): String {
        val n = length(p)
        if (n == 0) return ""
        return buildString { for (r in p.runs) { append(r.text); if (length >= n) break } }.take(n)
    }

    /** The character format of the drop cap's first letter, in its font. */
    fun format(p: Paragraph): CharacterFormat {
        val base = p.runs.firstOrNull { it.text.isNotEmpty() }?.format?.copyOf() ?: CharacterFormat()
        p.format.dropCap?.fontFamily?.let { base.fontFamily = it }
        return base
    }

    /**
     * Word's drop cap as the files keep it — [frame], a paragraph of its own holding the letters set large — made [para]'s
     * first letters: in [para]'s size and font (the letters' own font kept as the drop cap's, when it differs), the
     * bookmarks of both kept where they are. [cap] is the frame's drop (its length set here).
     */
    fun merge(frame: Paragraph, para: Paragraph, cap: DropCap) {
        val letters = frame.runs.filter { it.text.isNotEmpty() }
        val n = letters.sumOf { it.text.length }
        val body = para.runs.firstOrNull { it.text.isNotEmpty() }?.format
        val capFont = letters.firstOrNull()?.format?.fontFamily
        para.runs.addAll(0, letters.map { r ->
            TextRun(r.text, r.format.copyOf().also { f -> f.fontSizePt = body?.fontSizePt; f.fontFamily = body?.fontFamily })
        })
        val moved = para.bookmarks.map { it.copy(offset = it.offset + n) }
        para.bookmarks.clear(); para.bookmarks.addAll(frame.bookmarks); para.bookmarks.addAll(moved)
        para.format.dropCap = cap.copy(length = n, fontFamily = capFont?.takeIf { it != body?.fontFamily })
    }

    /** Whether [para] can take a drop cap's letters from the frame before it: it starts with text. */
    fun takesLetters(para: Paragraph): Boolean {
        val first = para.runs.firstOrNull { it.text.isNotEmpty() }?.text?.first() ?: return false
        return !first.isWhitespace() && first != '\u000C' && para.format.dropCap == null
    }

    /**
     * The drop cap's size for a [lines]-line drop with lines [pitch] apart and text of [bodySize] (all in one unit):
     * its capitals ([capRatio] of the size) as tall as the body's capitals plus the lines between.
     */
    fun size(text: String, lines: Int, pitch: Float, bodySize: Float): Float {
        val ratio = capRatio(text)
        return ((lines - 1).coerceAtLeast(0) * pitch + ratio * bodySize) / ratio
    }
}
