package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.RevisionMark
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextRun

/**
 * Word's Review ▸ Compare: the revised document with the differences from the original marked as tracked changes by
 * [author] — paragraphs matched by their text, a changed paragraph compared word by word (each CJK character a word),
 * a paragraph only in the original kept with its text and mark deleted, one only in the revised with them inserted, text
 * kept but formatted otherwise a format revision that remembers its old look. A table compared with the original's of
 * the same rows and cells cell by cell, the same way; other blocks (pictures…, a table reshaped) are the revised
 * document's, unmarked.
 */
object DocCompare {
    /** The comparison: the marked document and how many insertions, deletions and formatting changes it holds (as Word's summary counts them). */
    class Result(val document: Document, val insertions: Int, val deletions: Int, val formatting: Int = 0)

    /** Paragraphs at least this alike (by shared words) are one paragraph changed; less alike, one removed and one added. */
    private const val ALIKE = 0.3

    /** Past this many cells of a word grid a paragraph is compared as a whole (removed and added). */
    private const val MAX_GRID = 4_000_000

    /**
     * Word's comparison settings (Compare ▸ More): which differences count — [formatting], [caseChanges] ("Apple" against
     * "apple"), [whiteSpace] (two spaces against one), [tables] (compared cell by cell, else the revised one unmarked) — and
     * whether the changes show by word or, at [characterLevel], by character.
     */
    data class Options(
        val formatting: Boolean = true, val caseChanges: Boolean = true, val whiteSpace: Boolean = true,
        val tables: Boolean = true, val characterLevel: Boolean = false,
    ) {
        /** Text as these settings compare it: case folded and runs of white space one space when those don't count. */
        fun key(text: String): String {
            var t = text
            if (!whiteSpace) t = t.replace(SPACES, " ")
            if (!caseChanges) t = t.lowercase()
            return t
        }
        private companion object { val SPACES = Regex("""\s+""") }
    }

    /** One comparison's reviewer and time, its settings, and its counts. */
    private class Pass(val author: String, val now: Long, val options: Options) { var ins = 0; var del = 0; var fmt = 0 }

    fun compare(original: Document, revised: Document, author: String, now: Long = System.currentTimeMillis(), options: Options = Options()): Result {
        val out = revised.copyOf()
        out.blocks.clear()
        val pass = Pass(author, now, options)
        val a = original.blocks; val b = revised.blocks
        val pairs = lcs(a.map { keyOf(it, options) }, b.map { keyOf(it, options) })
        var i = 0; var j = 0
        fun gap(toI: Int, toJ: Int) {
            // Each revised paragraph takes the next original one alike enough (those passed over were removed); none: added.
            // Each revised table takes the next original one of its shape (the paragraphs before that one were removed); none: unmarked.
            val oldParas = (i until toI).filter { a[it] is ParagraphBlock }
            fun para(k: Int) = (a[oldParas[k]] as ParagraphBlock).paragraph
            var o = 0
            var t = i
            fun remove() { out.blocks.add(ParagraphBlock(whole(para(o++), inserted = false, pass))); pass.del++ }
            for (x in j until toJ) {
                when (val blk = b[x]) {
                    is ParagraphBlock -> {
                        val np = blk.paragraph
                        val hit = (o until oldParas.size).firstOrNull { similarity(para(it), np, options) >= ALIKE }
                        if (hit == null) { out.blocks.add(ParagraphBlock(whole(np, inserted = true, pass))); pass.ins++; continue }
                        while (o < hit) remove()
                        t = maxOf(t, oldParas[o] + 1)
                        out.blocks.add(ParagraphBlock(changed(para(o++), np, pass)))
                    }
                    is TableBlock -> {
                        if (!options.tables) { out.blocks.add(blk.copyOf()); continue }
                        val h = (t until toI).firstOrNull { (a[it] as? TableBlock)?.table?.let { ot -> sameShape(ot, blk.table) } == true }
                        if (h == null) { out.blocks.add(blk.copyOf()); continue }
                        while (o < oldParas.size && oldParas[o] < h) remove()
                        out.blocks.add(TableBlock(table((a[h] as TableBlock).table, blk.table, pass)))
                        t = h + 1
                    }
                    else -> out.blocks.add(blk.copyOf())
                }
            }
            while (o < oldParas.size) remove()
        }
        for ((pi, pj) in pairs) {
            gap(pi, pj)
            val old = a[pi]; val new = b[pj]
            out.blocks.add(when {
                old is ParagraphBlock && new is ParagraphBlock -> ParagraphBlock(alike(old.paragraph, new.paragraph, pass))
                old is TableBlock && new is TableBlock && options.tables && sameShape(old.table, new.table) -> TableBlock(table(old.table, new.table, pass))
                else -> new.copyOf()
            })
            i = pi + 1; j = pj + 1
        }
        gap(a.size, b.size)
        if (out.blocks.isEmpty()) out.addParagraph(Paragraph(mutableListOf(TextRun(""))))
        return Result(out, pass.ins, pass.del, pass.fmt)
    }

    private fun keyOf(x: Block, o: Options): String = when (x) {
        is ParagraphBlock -> "P" + o.key(x.paragraph.plainText())
        is TableBlock -> "T" + x.table.rows.joinToString("\u0001") { r -> r.cells.joinToString("\u0002") { c -> c.content.joinToString("\n") { o.key(it.plainText()) } } }
        else -> "B" + x.javaClass.simpleName + x.hashCode()
    }

    /** Two tables with as many rows, each with as many cells: compared cell by cell. */
    private fun sameShape(a: Table, b: Table): Boolean =
        a.rows.size == b.rows.size && a.rows.indices.all { a.rows[it].cells.size == b.rows[it].cells.size }

    /** The revised table, each cell's paragraphs compared with the original's in the same place. */
    private fun table(old: Table, new: Table, pass: Pass): Table {
        val t = new.copyOf()
        t.rows.forEachIndexed { r, row ->
            row.cells.forEachIndexed { c, cell ->
                val compared = paragraphs(old.rows[r].cells[c].content, new.rows[r].cells[c].content, pass)
                cell.content.clear(); cell.content.addAll(compared)
            }
        }
        return t
    }

    /**
     * A cell's paragraphs against the original's: matched by text, then alike, the rest removed or added — one paragraph
     * in place of one compared word by word however unalike (a cell's "North" → "South" one paragraph, as Word shows it).
     */
    private fun paragraphs(olds: List<Paragraph>, news: List<Paragraph>, pass: Pass): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        val pairs = lcs(olds.map { pass.options.key(it.plainText()) }, news.map { pass.options.key(it.plainText()) })
        var i = 0; var j = 0
        fun gap(toI: Int, toJ: Int) {
            if (toI - i == 1 && toJ - j == 1) { out.add(changed(olds[i], news[j], pass)); return }
            var o = i
            for (x in j until toJ) {
                val np = news[x]
                val hit = (o until toI).firstOrNull { similarity(olds[it], np, pass.options) >= ALIKE }
                if (hit == null) { out.add(whole(np, inserted = true, pass)); pass.ins++; continue }
                while (o < hit) { out.add(whole(olds[o++], inserted = false, pass)); pass.del++ }
                out.add(changed(olds[o++], np, pass))
            }
            while (o < toI) { out.add(whole(olds[o++], inserted = false, pass)); pass.del++ }
        }
        for ((pi, pj) in pairs) {
            gap(pi, pj)
            out.add(alike(olds[pi], news[pj], pass))
            i = pi + 1; j = pj + 1
        }
        gap(olds.size, news.size)
        if (out.isEmpty()) out.add(Paragraph(mutableListOf(TextRun(""))))
        return out
    }

    /** Two paragraphs alike under the settings: of one text, compared for formatting; differing only in case or spacing that don't count, word by word. */
    private fun alike(old: Paragraph, new: Paragraph, pass: Pass): Paragraph =
        if (old.plainText() == new.plainText()) restyled(old, new, pass) else changed(old, new, pass)

    /** Two paragraphs of one text: the revised one, each stretch formatted otherwise a format revision (its old look kept). */
    private fun restyled(old: Paragraph, new: Paragraph, pass: Pass): Paragraph {
        if (!pass.options.formatting) return new.copyOf()
        val olds = old.runs.flatMap { r -> List(r.text.length) { r.format } }
        val runs = ArrayList<TextRun>()
        var k = 0
        var wasChanged = false
        for (r in new.runs) for (ch in r.text) {
            val o = olds.getOrNull(k++)
            val f = r.format.copyOf()
            val differs = o != null && !sameLook(o, f)
            if (differs) reformatted(f, o!!, pass)
            if (differs && !wasChanged) pass.fmt++
            wasChanged = differs
            val prev = runs.lastOrNull()
            if (prev != null && prev.format == f) prev.text += ch else runs.add(TextRun(ch.toString(), f))
        }
        if (runs.none { it.format.formatChanged }) return new.copyOf()
        val c = new.copyOf()
        return Paragraph(runs, c.format, bookmarks = c.bookmarks)
    }

    /** Alike to look at (a link counts; revisions, equations and fields do not). */
    private fun sameLook(a: CharacterFormat, b: CharacterFormat) = a.look() == b.look()

    /** [f] flagged as a format revision of [pass]'s, from [old]'s look. */
    private fun reformatted(f: CharacterFormat, old: CharacterFormat, pass: Pass) {
        f.formatChanged = true; f.before = old.look()
        f.revAuthor = pass.author; f.revDate = pass.now
    }

    /** [p] whole as an insertion or a deletion: every run and its mark. */
    private fun whole(p: Paragraph, inserted: Boolean, pass: Pass): Paragraph {
        val c = p.copyOf()
        val q = Paragraph(c.runs, c.format, bookmarks = c.bookmarks)
        q.runs.forEach { mark(it.format, inserted, pass.author, pass.now) }
        q.format.revisionMark = RevisionMark(inserted, pass.author, pass.now)
        return q
    }

    private fun mark(f: CharacterFormat, inserted: Boolean, author: String, now: Long) {
        if (inserted) f.inserted = true else f.deleted = true
        f.revAuthor = author; f.revDate = now
    }

    /** One paragraph changed: the revised one's look, its words against the original's — kept (perhaps reformatted), removed, added. */
    private fun changed(old: Paragraph, new: Paragraph, pass: Pass): Paragraph {
        val ta = tokens(old, pass.options.characterLevel); val tb = tokens(new, pass.options.characterLevel)
        val runs = ArrayList<TextRun>()
        var last = 0   // 0 kept, 1 removed, 2 added, 3 kept reformatted: a change counts once however many words it has
        fun add(o: Op) {
            val f = o.tok.format.copyOf()
            var kind = o.kind
            if (kind == 1) mark(f, inserted = false, pass.author, pass.now) else if (kind == 2) mark(f, inserted = true, pass.author, pass.now)
            else if (pass.options.formatting && o.old != null && o.tok.text.isNotBlank() && !sameLook(o.old.format, f)) { reformatted(f, o.old.format, pass); kind = 3 }
            if (kind != 0 && kind != last) when (kind) { 1 -> pass.del++; 2 -> pass.ins++; else -> pass.fmt++ }
            last = kind
            val prev = runs.lastOrNull()
            if (prev != null && prev.format == f) prev.text += o.tok.text else runs.add(TextRun(o.tok.text, f))
        }
        val ops = ArrayList<Op>()
        if (ta.size.toLong() * tb.size > MAX_GRID) {
            ta.forEach { ops.add(Op(1, it)) }; tb.forEach { ops.add(Op(2, it)) }
        } else {
            val pairs = lcs(ta.map { pass.options.key(it.text) }, tb.map { pass.options.key(it.text) })
            var i = 0; var j = 0
            for ((pi, pj) in pairs) {
                while (i < pi) ops.add(Op(1, ta[i++]))
                while (j < pj) ops.add(Op(2, tb[j++]))
                ops.add(Op(0, tb[pj], ta[pi])); i = pi + 1; j = pj + 1
            }
            while (i < ta.size) ops.add(Op(1, ta[i++]))
            while (j < tb.size) ops.add(Op(2, tb[j++]))
        }
        tidy(ops).forEach { add(it) }
        if (runs.isEmpty()) runs.add(TextRun("", new.runs.firstOrNull()?.format?.copyOf() ?: CharacterFormat()))
        return Paragraph(runs, new.format.copyOf())
    }

    private class Tok(val text: String, val format: CharacterFormat)

    /** A token kept (0, with the original's [old] beside it), removed (1) or added (2). */
    private class Op(val kind: Int, val tok: Tok, val old: Tok? = null)

    /**
     * As Word shows a change: a space kept between two changes belongs to them ("the same" → "a stronger", not "the|a| |same|stronger"),
     * and within each run of changes what was removed comes before what was added.
     */
    private fun tidy(ops: List<Op>): List<Op> {
        val joined = ArrayList<Op>(ops.size + 4)
        for ((k, o) in ops.withIndex()) {
            val old = o.old
            val between = o.kind == 0 && o.tok.text.isBlank() &&
                (joined.lastOrNull()?.kind ?: 0) != 0 && (ops.getOrNull(k + 1)?.kind ?: 0) != 0
            if (between && old != null) { joined.add(Op(1, old)); joined.add(Op(2, o.tok)) } else joined.add(o)
        }
        val out = ArrayList<Op>(joined.size)
        var k = 0
        while (k < joined.size) {
            if (joined[k].kind == 0) { out.add(joined[k++]); continue }
            val end = (k until joined.size).firstOrNull { joined[it].kind == 0 } ?: joined.size
            val run = joined.subList(k, end)
            run.filterTo(out) { it.kind == 1 }; run.filterTo(out) { it.kind == 2 }
            k = end
        }
        return out
    }

    /** Words, spaces and single signs of a paragraph's runs (a word never across two runs), each CJK character on its own. */
    private val WORD = Regex("""\s+|[\p{IsHan}\p{IsHiragana}\p{IsKatakana}\p{IsHangul}]|[\p{L}\p{N}_'’]+|.""")
    /** At character level: each character on its own (a run of spaces still one). */
    private val CHAR = Regex("""(?s)\s+|.""")

    private fun tokens(p: Paragraph, characters: Boolean = false): List<Tok> =
        p.runs.flatMap { r -> (if (characters) CHAR else WORD).findAll(r.text).map { Tok(it.value, r.format) }.toList() }

    /** How alike two paragraphs are: the share of their words (not spaces) in common, as the settings compare words. */
    private fun similarity(a: Paragraph, b: Paragraph, o: Options): Double {
        val wa = WORD.findAll(a.plainText()).map { o.key(it.value) }.filter { it.isNotBlank() }.toList()
        val wb = WORD.findAll(b.plainText()).map { o.key(it.value) }.filter { it.isNotBlank() }.toList()
        if (wa.isEmpty() && wb.isEmpty()) return 1.0
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        val counts = HashMap<String, Int>(); wa.forEach { counts.merge(it, 1, Int::plus) }
        var shared = 0
        wb.forEach { w -> val n = counts[w] ?: 0; if (n > 0) { shared++; counts[w] = n - 1 } }
        return 2.0 * shared / (wa.size + wb.size)
    }

    /** The longest common subsequence of [a] and [b] as index pairs (common start and end taken first, so long alike documents stay cheap). */
    internal fun lcs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size; var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
        val n = endA - start; val m = endB - start
        val middle = ArrayList<Pair<Int, Int>>()
        if (n > 0 && m > 0 && n.toLong() * m <= MAX_GRID) {
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (x in n - 1 downTo 0) for (y in m - 1 downTo 0)
                dp[x][y] = if (a[start + x] == b[start + y]) dp[x + 1][y + 1] + 1 else maxOf(dp[x + 1][y], dp[x][y + 1])
            var x = 0; var y = 0
            while (x < n && y < m) {
                when {
                    a[start + x] == b[start + y] -> { middle.add(start + x to start + y); x++; y++ }
                    dp[x + 1][y] >= dp[x][y + 1] -> x++
                    else -> y++
                }
            }
        }
        return (0 until start).map { it to it } + middle + (0 until a.size - endA).map { endA + it to endB + it }
    }
}
