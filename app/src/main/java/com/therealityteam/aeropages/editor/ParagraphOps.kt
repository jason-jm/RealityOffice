package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TextRun

/**
 * Character-range editing primitives on a [Paragraph]'s runs.
 *
 * Approach: flatten the paragraph to a per-character list (each char carrying a
 * copy of its run's [CharacterFormat]), mutate that list, then re-coalesce
 * adjacent equal-format chars back into runs. This is O(n) per edit but trivially
 * correct for run splitting/merging, which is exactly what real inline
 * (selection-range) formatting in Word requires.
 *
 * Bookmark ends ([Paragraph.bookmarks]) move with the text around them: text typed where a mark
 * stands goes after it (into a bookmark at its start, not into one at its end, as in Word), and a
 * mark in text that goes away stands where that text was.
 */
object ParagraphOps {

    private class CharFmt(val ch: Char, val fmt: CharacterFormat)

    private fun flatten(p: Paragraph): MutableList<CharFmt> {
        val out = ArrayList<CharFmt>(p.plainText().length)
        for (run in p.runs) for (ch in run.text) out.add(CharFmt(ch, run.format.copyOf()))
        return out
    }

    /** Total character length of the paragraph. */
    fun length(p: Paragraph): Int = p.runs.sumOf { it.text.length }

    /** After characters [start, end) of [p] became [inserted] new ones: marks past them move with the text, marks inside them go to their start. */
    private fun shiftMarks(p: Paragraph, start: Int, end: Int, inserted: Int) {
        if (p.bookmarks.isEmpty()) return
        val delta = inserted - (end - start)
        // An index entry marked inside text that goes goes with it (Word's XE field is deleted with the text round it).
        if (end > start + 1) p.bookmarks.removeAll { it.offset in (start + 1) until end && com.therealityteam.aeropages.model.IndexEntries.isEntry(it.name) }
        p.bookmarks.forEach { m ->
            m.offset = when {
                m.offset <= start -> m.offset
                m.offset >= end -> m.offset + delta
                else -> start
            }
        }
        p.bookmarks.sortBy { it.offset }
    }

    /** After the characters of [p] not [kept] went away: each mark stands before the first kept character that stood at or after it. */
    private fun keepMarks(p: Paragraph, kept: BooleanArray) {
        if (p.bookmarks.isEmpty()) return
        val before = IntArray(kept.size + 1)
        for (i in kept.indices) before[i + 1] = before[i] + if (kept[i]) 1 else 0
        p.bookmarks.forEach { it.offset = before[it.offset.coerceIn(0, kept.size)] }
    }

    /** Re-coalesce a flattened list back into runs on [p]; [fallback] used when empty. */
    private fun rebuild(p: Paragraph, chars: List<CharFmt>, fallback: CharacterFormat) {
        val runs = ArrayList<TextRun>()
        var i = 0
        while (i < chars.size) {
            val fmt = chars[i].fmt
            val sb = StringBuilder()
            while (i < chars.size && chars[i].fmt == fmt) {
                sb.append(chars[i].ch); i++
            }
            runs.add(TextRun(sb.toString(), fmt.copyOf()))
        }
        if (runs.isEmpty()) runs.add(TextRun("", fallback.copyOf()))
        p.runs.clear()
        p.runs.addAll(runs)
    }

    /** Character format at [index] (clamped). Returns a copy. */
    fun formatAt(p: Paragraph, index: Int): CharacterFormat {
        if (p.runs.isEmpty()) return CharacterFormat()
        var pos = 0
        for (run in p.runs) {
            val end = pos + run.text.length
            if (index < end) return run.format.copyOf()
            pos = end
        }
        return p.runs.last().format.copyOf()
    }

    /** Apply [transform] to every character in [start, end). */
    fun applyFormat(p: Paragraph, start: Int, end: Int, transform: (CharacterFormat) -> Unit) {
        val chars = flatten(p)
        val s = start.coerceIn(0, chars.size)
        val e = end.coerceIn(s, chars.size)
        for (i in s until e) transform(chars[i].fmt)
        val fallback = chars.firstOrNull()?.fmt ?: CharacterFormat()
        rebuild(p, chars, fallback)
    }

    /**
     * Replace [start, end) with [text] formatted as [fmt]. This is the typing hot path, so it
     * splices runs directly instead of flattening the paragraph to characters: the runs
     * before/after the range are sliced, the new run inserted, and equal-format neighbours
     * coalesced — the same result [rebuild] would produce, in O(runs) rather than O(chars).
     */
    fun replace(p: Paragraph, start: Int, end: Int, text: String, fmt: CharacterFormat) {
        val total = length(p)
        val s = start.coerceIn(0, total)
        val e = end.coerceIn(s, total)
        val spliced = ArrayList<TextRun>(p.runs.size + 2)
        spliced.addAll(slice(p, 0, s))
        if (text.isNotEmpty()) spliced.add(TextRun(text, textFormat(p, s, fmt)))
        spliced.addAll(slice(p, e, total))
        rebuildRuns(p, spliced, fmt)
        shiftMarks(p, s, e, text.length)
    }

    /**
     * [replace] for a field's own result ([com.therealityteam.aeropages.model.Fields.update]): the new text takes [fmt]
     * as it is, the field's included.
     */
    fun replaceKeeping(p: Paragraph, start: Int, end: Int, text: String, fmt: CharacterFormat) {
        val total = length(p)
        val s = start.coerceIn(0, total)
        val e = end.coerceIn(s, total)
        val spliced = ArrayList<TextRun>(p.runs.size + 2)
        spliced.addAll(slice(p, 0, s))
        if (text.isNotEmpty()) spliced.add(TextRun(text, fmt.copyOf()))
        spliced.addAll(slice(p, e, total))
        rebuildRuns(p, spliced, fmt)
        shiftMarks(p, s, e, text.length)
    }

    /**
     * The format new text at [at] takes when [fmt] is an equation's: never the equation's (new text
     * is not part of one, even typed next to it or inside it) but the text's round it — the nearest
     * run before [at] that is not an equation, else the nearest after, else plain text at its size.
     * Nor a field's: typed text is the writer's, not a field's result.
     */
    private fun textFormat(p: Paragraph, at: Int, fmt: CharacterFormat): CharacterFormat {
        // (A cross-reference's link is its own: the text typed after it does not take it either.)
        // New text is never a deletion, whatever it is typed next to.
        if (fmt.math == null) return fmt.copyOf().also { it.deleted = false; if (it.field != null) { it.field = null; it.link = null } }
        var pos = 0
        var before: CharacterFormat? = null
        var after: CharacterFormat? = null
        for (run in p.runs) {
            if (run.format.math == null && run.text.isNotEmpty()) {
                if (pos < at) before = run.format else if (after == null) after = run.format
            }
            pos += run.text.length
        }
        return ((before ?: after)?.copyOf() ?: CharacterFormat(fontSizePt = fmt.fontSizePt, color = fmt.color)).also { it.deleted = false; it.field = null }
    }

    /** Put [runs] (their own formats kept) at character [at]. */
    fun insertRuns(p: Paragraph, at: Int, runs: List<TextRun>) {
        val total = length(p)
        val a = at.coerceIn(0, total)
        val spliced = ArrayList<TextRun>(p.runs.size + runs.size + 1)
        spliced.addAll(slice(p, 0, a))
        runs.forEach { spliced.add(it.copyOf()) }
        spliced.addAll(slice(p, a, total))
        rebuildRuns(p, spliced, p.runs.firstOrNull()?.format ?: CharacterFormat())
        shiftMarks(p, a, a, runs.sumOf { it.text.length })
    }

    /** The characters [start, end) of the equation in the text at [at] (start ≤ at < end), or null when [at] is in none. */
    fun mathAt(p: Paragraph, at: Int): Pair<com.therealityteam.aeropages.model.InlineMath, IntRange>? {
        var pos = 0
        var i = 0
        while (i < p.runs.size) {
            val math = p.runs[i].format.math
            if (math == null) { pos += p.runs[i].text.length; i++; continue }
            // The group: this run and those after it tied to the same equation.
            val start = pos
            while (i < p.runs.size && p.runs[i].format.math === math) { pos += p.runs[i].text.length; i++ }
            if (at in start until pos) return math to (start until pos)
        }
        return null
    }

    /** Copies of the runs covering characters [from, to), trimmed to that range. */
    private fun slice(p: Paragraph, from: Int, to: Int): List<TextRun> {
        if (to <= from) return emptyList()
        val out = ArrayList<TextRun>()
        var pos = 0
        for (run in p.runs) {
            val runStart = pos
            val runEnd = pos + run.text.length
            pos = runEnd
            if (runEnd <= from) continue
            if (runStart >= to) break
            val a = maxOf(from, runStart) - runStart
            val b = minOf(to, runEnd) - runStart
            if (b > a) out.add(TextRun(run.text.substring(a, b), run.format.copyOf()))
        }
        return out
    }

    /** Install [runs] on [p], dropping empty runs and merging equal-format neighbours (mirrors [rebuild]). */
    private fun rebuildRuns(p: Paragraph, runs: List<TextRun>, fallback: CharacterFormat) {
        val merged = ArrayList<TextRun>(runs.size)
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && last.format == run.format) last.text += run.text else merged.add(run)
        }
        if (merged.isEmpty()) merged.add(TextRun("", fallback.copyOf()))
        p.runs.clear()
        p.runs.addAll(merged)
    }

    /**
     * Split [p] at character [at]: [p] keeps [0, at), and the returned paragraph
     * (sharing [p]'s paragraph format) holds [at, len). Run formatting on both
     * sides is preserved; bookmarks starting at the cut go with the text after it
     * (a point there too), ones ending there stay.
     */
    fun splitAt(p: Paragraph, at: Int): Paragraph {
        val chars = flatten(p)
        val cut = at.coerceIn(0, chars.size)
        val head = chars.subList(0, cut).toList()
        val tail = chars.subList(cut, chars.size).toList()
        val fallback = chars.firstOrNull()?.fmt ?: CharacterFormat()
        val tailPara = Paragraph(mutableListOf(), p.format.copyOf())
        // A drop cap stays with the paragraph's first letters: the new paragraph after a cut has none, the one left before a cut at the start neither.
        if (cut == 0) p.format.dropCap = null else tailPara.format.dropCap = null
        rebuild(p, head, fallback)
        rebuild(tailPara, tail, fallback)
        if (p.bookmarks.isNotEmpty()) {
            val startsHere = p.bookmarks.filter { it.offset == cut && !it.end }.mapTo(HashSet()) { it.name }
            val moving = p.bookmarks.filter { it.offset > cut || (it.offset == cut && (!it.end || it.name in startsHere)) }
            p.bookmarks.removeAll(moving)
            moving.forEach { tailPara.bookmarks.add(it.copy(offset = it.offset - cut)) }
        }
        return tailPara
    }

    /** Append [other]'s runs (and bookmark ends) onto [p] and re-coalesce. */
    fun append(p: Paragraph, other: Paragraph) {
        val before = length(p)
        val combined = flatten(p).apply { addAll(flatten(other)) }
        val fallback = combined.firstOrNull()?.fmt ?: CharacterFormat()
        rebuild(p, combined, fallback)
        other.bookmarks.forEach { p.bookmarks.add(it.copy(offset = before + it.offset)) }
    }

    /** Removes the characters of [p] that [drop] picks; bookmark ends stay where the text around them is. */
    private fun removeWhere(p: Paragraph, drop: (CharacterFormat) -> Boolean) {
        val all = flatten(p)
        val kept = BooleanArray(all.size) { !drop(all[it].fmt) }
        keepMarks(p, kept)
        val chars = all.filterIndexed { i, _ -> kept[i] }
        rebuild(p, chars, chars.firstOrNull()?.fmt ?: CharacterFormat())
    }

    /** Remove all characters marked as track-changes insertions. */
    fun removeInserted(p: Paragraph, author: String? = null) =
        removeWhere(p) { it.inserted && (author == null || (it.revAuthor ?: "") == author) }

    /** Remove all characters marked as track-changes deletions (used by Accept). */
    fun removeDeleted(p: Paragraph, author: String? = null) =
        removeWhere(p) { it.deleted && (author == null || (it.revAuthor ?: "") == author) }

    /**
     * Clear the revision marks in place, keeping the text (used by Accept/Reject). A format revision cleared by a
     * rejection ([restoreFormat]) brings back the look it remembers ([CharacterFormat.before]); one that remembers none
     * (read from a format that keeps none) is only un-flagged.
     */
    fun clearMarks(p: Paragraph, clearInserted: Boolean, clearDeleted: Boolean, author: String? = null, clearFormat: Boolean = true, restoreFormat: Boolean = false) {
        val chars = flatten(p)
        chars.forEach {
            if (author != null && (it.fmt.revAuthor ?: "") != author) return@forEach
            if (clearInserted) it.fmt.inserted = false
            if (clearDeleted) it.fmt.deleted = false
            if (clearFormat) unflagFormat(it.fmt, restoreFormat)
            if (!it.fmt.inserted && !it.fmt.deleted) { it.fmt.revAuthor = null; it.fmt.revDate = null }
        }
        rebuild(p, chars, chars.firstOrNull()?.fmt ?: CharacterFormat())
    }

    /** [f]'s format revision resolved: with [restore] (a rejection) the look before it back. */
    private fun unflagFormat(f: CharacterFormat, restore: Boolean) {
        if (f.formatChanged && restore) f.before?.let { f.takeLook(it) }
        f.formatChanged = false; f.before = null
    }

    /** Accept the revisions in [start, end): struck text goes away, insertions and format marks become plain. */
    fun acceptRange(p: Paragraph, start: Int, end: Int) = resolveRange(p, start, end, accept = true)
    /** Reject the revisions in [start, end): inserted text goes away, struck text becomes plain, reformatted text looks as it did. */
    fun rejectRange(p: Paragraph, start: Int, end: Int) = resolveRange(p, start, end, accept = false)
    private fun resolveRange(p: Paragraph, start: Int, end: Int, accept: Boolean) {
        val chars = flatten(p)
        val kept = ArrayList<CharFmt>(chars.size)
        val stays = BooleanArray(chars.size) { true }
        chars.forEachIndexed { i, c ->
            if (i in start until end) {
                if (if (accept) c.fmt.deleted else c.fmt.inserted) { stays[i] = false; return@forEachIndexed }
                c.fmt.inserted = false; c.fmt.deleted = false; unflagFormat(c.fmt, restore = !accept); c.fmt.revAuthor = null; c.fmt.revDate = null
            }
            kept.add(c)
        }
        keepMarks(p, stays)
        rebuild(p, kept, kept.firstOrNull()?.fmt ?: CharacterFormat())
    }

    /**
     * Track-changes replace of [start, end) with [text]: rather than removing the
     * range, its non-inserted characters are marked deleted (struck through, kept);
     * characters that were themselves un-accepted insertions are dropped; [text] is
     * added as an insertion right after the struck range. Returns the new caret offset.
     */
    fun trackedReplace(p: Paragraph, start: Int, end: Int, text: String, fmt: CharacterFormat, author: String? = null, date: Long? = null): Int {
        val chars = flatten(p)
        val s = start.coerceIn(0, chars.size)
        val e = end.coerceIn(s, chars.size)
        val rebuilt = ArrayList<CharFmt>(chars.size + text.length)
        rebuilt.addAll(chars.subList(0, s))
        var keptInRange = 0
        for (i in s until e) {
            val c = chars[i]
            if (c.fmt.inserted && !c.fmt.deleted) continue // deleting an un-accepted insertion → drop
            c.fmt.deleted = true; c.fmt.revAuthor = author; c.fmt.revDate = date
            rebuilt.add(c)
            keptInRange++
        }
        val insFmt = textFormat(p, s, fmt).apply { inserted = true; deleted = false; formatChanged = false; before = null; revAuthor = author; revDate = date }
        for (ch in text) rebuilt.add(CharFmt(ch, insFmt.copyOf()))
        rebuilt.addAll(chars.subList(e, chars.size))
        rebuild(p, rebuilt, insFmt)
        // Marks in the struck text stay in it; marks after it move past the inserted text.
        if (p.bookmarks.isNotEmpty()) {
            val dropped = (e - s) - keptInRange
            p.bookmarks.forEach { m ->
                m.offset = when {
                    m.offset <= s -> m.offset
                    // The struck characters kept are the ones now marked deleted (un-accepted insertions went).
                    m.offset < e -> s + (s until m.offset).count { i -> chars[i].fmt.deleted }
                    else -> m.offset - dropped + text.length
                }
            }
            p.bookmarks.sortBy { it.offset }
        }
        // For a pure deletion put the caret BEFORE the struck text, so the next
        // Backspace targets the previous (still-normal) char and deletion continues.
        // When text was inserted, put the caret after the inserted text.
        return if (text.isEmpty()) s else s + keptInRange + text.length
    }

    /** Plain text of [p] (cached convenience). */
    fun text(p: Paragraph): String = p.plainText()

    /**
     * Transform the text in [start, end) via [transform], preserving per-character
     * formatting positionally (used by case conversion). Output may differ in
     * length; surplus characters inherit the last source character's format.
     */
    /**
     * [transform] applied to [start] until [end] of [p] run by run — from the last back, so each run's text keeps its
     * look even when its length changes; how many characters changed (counted along the shorter, plus any difference).
     */
    fun mapRuns(p: Paragraph, start: Int, end: Int, transform: (String) -> String): Int {
        val ranges = ArrayList<Pair<Int, Int>>()
        var pos = 0
        for (r in p.runs) { val e = pos + r.text.length; if (e > start && pos < end && e > pos) ranges.add(maxOf(pos, start) to minOf(e, end)); pos = e }
        var changed = 0
        val text = p.plainText()
        for ((a, b) in ranges.asReversed()) {
            val before = text.substring(a, b)
            val after = transform(before)
            if (after == before) continue
            changed += (0 until minOf(before.length, after.length)).count { before[it] != after[it] } + kotlin.math.abs(before.length - after.length)
            mapText(p, a, b) { after }
        }
        return changed
    }

    fun mapText(p: Paragraph, start: Int, end: Int, transform: (String) -> String) {
        val chars = flatten(p)
        val s = start.coerceIn(0, chars.size)
        val e = end.coerceIn(s, chars.size)
        if (e <= s) return
        val original = buildString { for (i in s until e) append(chars[i].ch) }
        val mapped = transform(original)
        val rebuilt = ArrayList<CharFmt>(chars.size - (e - s) + mapped.length)
        rebuilt.addAll(chars.subList(0, s))
        for (i in mapped.indices) {
            // A longer text takes the look of the range's own last character, never that of what follows it.
            val fmt = chars[(s + i).coerceAtMost(e - 1)].fmt
            rebuilt.add(CharFmt(mapped[i], fmt.copyOf()))
        }
        rebuilt.addAll(chars.subList(e, chars.size))
        rebuild(p, rebuilt, chars.firstOrNull()?.fmt ?: CharacterFormat())
        p.bookmarks.forEach { m ->
            m.offset = when {
                m.offset <= s -> m.offset
                m.offset >= e -> m.offset + mapped.length - (e - s)
                else -> s + minOf(m.offset - s, mapped.length)
            }
        }
    }
}
