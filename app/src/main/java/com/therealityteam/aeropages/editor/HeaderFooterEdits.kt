package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.HeaderFooters
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TextAlign

/**
 * Edits to a header's or footer's paragraph that keep its runs' formatting: its text changed where it
 * changed, a page number put in a line's left, centre or right slot (Word's Page Number gallery), page
 * numbers taken out again. A line's slots are its parts between tabs (see [HeaderFooters]); a line
 * without tabs sits where its paragraph aligns it.
 */
object HeaderFooterEdits {
    /** A line's slot: [index] 0 left, 1 centre, 2 right; [start]..[end] its text in the paragraph's. */
    class Slot(val index: Int, val start: Int, val end: Int)

    /** A line of the paragraph: [start]..[end] (its line break not included), its [slots] when it has tabs. */
    class Line(val start: Int, val end: Int, val slots: List<Slot>?)

    fun slotOf(align: TextAlign) = when (align) {
        TextAlign.CENTER -> 1
        TextAlign.RIGHT -> 2
        else -> 0
    }

    fun alignOf(slot: Int) = when (slot) {
        1 -> TextAlign.CENTER
        2 -> TextAlign.RIGHT
        else -> TextAlign.LEFT
    }

    /** [text]'s lines and, for those with tabs, their slots (a fourth and later part goes on in the right one). */
    fun lines(text: String): List<Line> {
        val out = ArrayList<Line>()
        var from = 0
        while (true) {
            val nl = text.indexOf('\n', from).let { if (it < 0) text.length else it }
            val line = text.substring(from, nl)
            out.add(Line(from, nl, if ('\t' !in line) null else slots(line, from)))
            if (nl >= text.length) break
            from = nl + 1
        }
        return out
    }

    private fun slots(line: String, base: Int): List<Slot> {
        val out = ArrayList<Slot>()
        var from = 0
        for (i in line.indices) {
            if (line[i] != '\t' || out.size == 2) continue
            out.add(Slot(out.size, base + from, base + i))
            from = i + 1
        }
        out.add(Slot(out.size, base + from, base + line.length))
        return out
    }

    /** [p]'s text made [text], only what differs replaced (the rest keeps its runs' formatting). */
    fun setText(p: Paragraph, text: String) = replaceRange(p, 0, p.plainText().length, text)

    /** [start]..[end] of [p]'s text made [text], only what differs replaced. */
    fun replaceRange(p: Paragraph, start: Int, end: Int, text: String) {
        val old = p.plainText().substring(start, end)
        if (old == text) return
        var pre = 0
        while (pre < old.length && pre < text.length && old[pre] == text[pre]) pre++
        var suf = 0
        while (suf < old.length - pre && suf < text.length - pre && old[old.length - 1 - suf] == text[text.length - 1 - suf]) suf++
        val at = start + pre
        ParagraphOps.replace(p, at, end - suf, text.substring(pre, text.length - suf), ParagraphOps.formatAt(p, (at - 1).coerceAtLeast(0)))
    }

    /** Where [p] shows its page number: the slot of its first [HeaderFooters.PAGE] as an alignment; null without one. */
    fun pageNumberAlign(p: Paragraph?): TextAlign? {
        p ?: return null
        val text = p.plainText()
        val at = text.indexOf(HeaderFooters.PAGE).takeIf { it >= 0 } ?: return null
        val line = lines(text).first { at in it.start..it.end }
        val slots = line.slots ?: return if (p.format.align == TextAlign.JUSTIFY) TextAlign.LEFT else p.format.align
        return alignOf(slots.first { at in it.start..it.end }.index)
    }

    /** The text of the slot (or line) holding [p]'s first page number — "Page {PAGE} of {PAGES}" — or null. */
    fun pageNumberText(p: Paragraph?): String? {
        p ?: return null
        val text = p.plainText()
        val at = text.indexOf(HeaderFooters.PAGE).takeIf { it >= 0 } ?: return null
        val line = lines(text).first { at in it.start..it.end }
        val slot = line.slots?.first { at in it.start..it.end }
        return text.substring(slot?.start ?: line.start, slot?.end ?: line.end).trim()
    }

    /**
     * [p]'s page numbers taken out: a slot that holds one of [phrases] ("{PAGE}", "Page {PAGE} of {PAGES}" …) as a
     * whole is emptied, otherwise only the field tokens go; a line left empty goes too (not the only one), and a
     * line's empty slots at its end. True when something changed.
     */
    fun removePageNumbers(p: Paragraph, phrases: List<String>): Boolean {
        val before = p.plainText()
        while (true) {
            val text = p.plainText()
            val at = text.indexOf(HeaderFooters.PAGE).takeIf { it >= 0 } ?: text.indexOf(HeaderFooters.PAGES).takeIf { it >= 0 } ?: break
            val line = lines(text).first { at in it.start..it.end }
            val slot = line.slots?.first { at in it.start..it.end }
            val s = slot?.start ?: line.start
            val e = slot?.end ?: line.end
            val part = text.substring(s, e)
            if (phrases.any { it.trim() == part.trim() }) ParagraphOps.replace(p, s, e, "", ParagraphOps.formatAt(p, s))
            else {
                val token = if (text.startsWith(HeaderFooters.PAGE, at)) HeaderFooters.PAGE else HeaderFooters.PAGES
                ParagraphOps.replace(p, at, at + token.length, "", ParagraphOps.formatAt(p, at))
            }
        }
        tidy(p)
        return p.plainText() != before
    }

    /**
     * Empty slots at a line's end dropped — text left in the left slot alone keeps a tab unless the paragraph aligns
     * left (or is that one line, then aligned left), so it stays where it was; empty lines dropped (one stays).
     */
    private fun tidy(p: Paragraph) {
        var text = p.plainText()
        val single = lines(text).size == 1
        for (line in lines(text).asReversed()) {
            var end = line.end
            while (end > line.start && text[end - 1] == '\t') end--
            if (end == line.end) continue
            if ('\t' !in text.substring(line.start, end) && end > line.start && slotOf(p.format.align) != 0) {
                if (single) p.format.align = TextAlign.LEFT else end++
            }
            if (end < line.end) ParagraphOps.replace(p, end, line.end, "", ParagraphOps.formatAt(p, end))
        }
        text = p.plainText()
        val all = lines(text)
        for ((i, line) in all.withIndex().reversed()) {
            if (all.size == 1 || line.end > line.start) continue
            // The line and the break before it (the first line: the break after it).
            if (i > 0) ParagraphOps.replace(p, line.start - 1, line.end, "", ParagraphOps.formatAt(p, line.start - 1))
            else ParagraphOps.replace(p, line.start, line.end + 1, "", ParagraphOps.formatAt(p, line.start))
            text = p.plainText()
            if (lines(text).size == 1) break
        }
    }

    /**
     * [number] (its text with [HeaderFooters.PAGE]) put in [p] where [align] says: in an empty paragraph as its one
     * line, aligned so; otherwise in that slot of its first line (a header's) or last line (a footer's) when that slot
     * is free — the line given tabs when it had none — else on a line of its own at the end.
     */
    fun placePageNumber(p: Paragraph, align: TextAlign, number: String, header: Boolean) {
        val k = slotOf(align)
        val text = p.plainText()
        if (text.isBlank()) {
            setText(p, number)
            p.format.align = align
            return
        }
        val all = lines(text)
        val line = if (header) all.first() else all.last()
        val own = slotOf(p.format.align)
        val slots = line.slots
        if (slots == null) {
            when {
                line.end == line.start && all.size == 1 -> { setText(p, number); p.format.align = align }
                line.end == line.start -> insert(p, line.start, if (own == k) number else tabbed(k, number))
                own == k -> newLine(p, k, own, number)
                else -> {
                    // The line's text in its own slot, the number in its: tabs before and after the text.
                    val last = maxOf(own, k)
                    val prefix = (0 until own).joinToString("") { (if (it == k) number else "") + "\t" }
                    val suffix = (own + 1..last).joinToString("") { "\t" + (if (it == k) number else "") }
                    insert(p, line.end, suffix)
                    insert(p, line.start, prefix)
                }
            }
            return
        }
        val slot = slots.getOrNull(k)
        when {
            slot == null -> insert(p, line.end, "\t".repeat(k - slots.size + 1) + number)
            slot.end == slot.start -> insert(p, slot.start, number)
            else -> newLine(p, k, own, number)
        }
    }

    private fun newLine(p: Paragraph, k: Int, own: Int, number: String) =
        insert(p, p.plainText().length, "\n" + if (own == k) number else tabbed(k, number))

    /** [number] alone on a line in slot [k]: the tabs that put it there. */
    private fun tabbed(k: Int, number: String) = if (k == 0) number + "\t" else "\t".repeat(k) + number

    private fun insert(p: Paragraph, at: Int, text: String) {
        if (text.isEmpty()) return
        ParagraphOps.replace(p, at, at, text, ParagraphOps.formatAt(p, (at - 1).coerceAtLeast(0)))
    }

    /** The first "[…]" placeholder in [text] (a gallery's "[Type here]"), to select when editing starts. */
    fun placeholder(text: String): IntRange? {
        val s = text.indexOf('[').takeIf { it >= 0 } ?: return null
        val e = text.indexOf(']', s).takeIf { it > s } ?: return null
        if (text.substring(s, e).any { it == '\n' || it == '\t' }) return null
        return s..e
    }
}
