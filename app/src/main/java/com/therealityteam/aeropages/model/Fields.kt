package com.therealityteam.aeropages.model

/**
 * A field whose result the text shows (Word's SEQ — a caption's number —, REF — a cross-reference to a bookmark's text,
 * its number, above or below — and PAGEREF — the page it is on): its [code] as Word writes it (`SEQ Figure \* ARABIC`,
 * `REF _Ref1234 \h`). Its result is the text of the runs it is the [CharacterFormat.field] of; [Fields.update] works it
 * out again, as Word's Update Field does.
 */
class DocField(val code: String) {
    /** SEQ, REF, PAGEREF, STYLEREF — or "=" for a table formula (`=SUM(ABOVE)`). */
    val kind: String get() = if (code.trimStart().startsWith("=")) "=" else Fields.words(code).firstOrNull()?.uppercase().orEmpty()

    /** The word after the kind: a sequence's name, a bookmark's. */
    val target: String get() = Fields.words(code).getOrNull(1).orEmpty()

    /** Whether the code carries switch [s] (`\h`, `\p` …), any case. */
    fun has(s: String): Boolean = Fields.words(code).any { it.equals(s, ignoreCase = true) }

    /** The argument after switch [s] (`\* ARABIC` → "ARABIC", `\r 1` → "1"), or null. */
    fun after(s: String): String? = Fields.words(code).let { w -> w.indexOfFirst { it.equals(s, ignoreCase = true) }.takeIf { it >= 0 }?.let { w.getOrNull(it + 1) } }
}

/** Live fields: reading a code, finding each field's result in a document and working them out again. */
object Fields {
    /** The kinds the app keeps live (others show the result they came with, as plain text). */
    val LIVE = setOf("SEQ", "REF", "PAGEREF", "STYLEREF", "=")

    /** The heading level a STYLEREF names: `1`, or a heading style's name ending in its level ("Heading 1", "标题 1"). */
    fun styleLevel(target: String): Int? = target.trim().toIntOrNull() ?: Regex("""(\d+)\s*$""").find(target)?.groupValues?.get(1)?.toIntOrNull()

    /** [p]'s heading level (1–3), or null for a paragraph that is no heading. */
    private fun headingLevel(p: Paragraph): Int? = when (p.format.style) {
        ParagraphStyle.HEADING1 -> 1; ParagraphStyle.HEADING2 -> 2; ParagraphStyle.HEADING3 -> 3; else -> null
    }

    /** Where the chapter paragraph [para] is in starts: the last heading of [level] at or before it (an index into [locs]), -1 for none. */
    fun chapterStart(locs: List<ParaLoc>, para: Int, level: Int): Int {
        for (i in para.coerceAtMost(locs.lastIndex) downTo 0) if (headingLevel(locs[i].paragraph) == level) return i
        return -1
    }

    /**
     * The number of the chapter paragraph [para] is in, as Word's `STYLEREF 1 \s` shows it: its heading's number as
     * its list numbers it, the number only ("第2章" → "2", "1.3." → "1.3", "Appendix A" → "A"); "0" for a heading with
     * no number (Word's "Figure 0-1"); null outside any chapter.
     */
    fun chapterNumber(locs: List<ParaLoc>, labels: Map<Paragraph, String>, para: Int, level: Int): String? {
        val start = chapterStart(locs, para, level)
        if (start < 0) return null
        val label = labels[locs[start].paragraph]?.trim().orEmpty()
        Regex("""\d+(\.\d+)*""").find(label)?.let { return it.value }
        val bare = label.substringAfterLast(' ').trim('第', '章', '节', '.', '、', ')', '）', '(', '（', ' ')
        return bare.ifEmpty { "0" }
    }

    /** Whether the chapter paragraph [para] is in has a heading its list numbers (a chapter number other than Word's "0"). */
    fun chapterNumbered(locs: List<ParaLoc>, labels: Map<Paragraph, String>, para: Int, level: Int): Boolean =
        chapterStart(locs, para, level).let { it >= 0 && !labels[locs[it].paragraph].isNullOrBlank() }

    /** A live field for [code], or null for another kind. */
    fun of(code: CharSequence): DocField? = code.toString().trim().takeIf { it.isNotEmpty() }?.let { DocField(it) }?.takeIf { it.kind in LIVE }

    /** A field code's words: its kind, arguments and switches, a quoted one whole (its quotes gone). */
    fun words(code: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < code.length) {
            while (i < code.length && code[i].isWhitespace()) i++
            if (i >= code.length) break
            if (code[i] == '"') {
                val end = code.indexOf('"', i + 1).let { if (it < 0) code.length else it }
                out.add(code.substring(i + 1, end)); i = end + 1
            } else {
                val start = i
                while (i < code.length && !code[i].isWhitespace()) i++
                out.add(code.substring(start, i))
            }
        }
        return out
    }

    /** A field of a document as it shows: in paragraph [para] (an index into [Document.locations]), its result [start]..[end]. */
    data class Placed(val field: DocField, val para: Int, val start: Int, val end: Int)

    /** Every field of [doc] in text order (a result broken by text typed into it: from its first run to its last). */
    fun placed(doc: Document): List<Placed> {
        val out = ArrayList<Placed>()
        doc.locations().forEachIndexed { i, loc ->
            val at = java.util.IdentityHashMap<DocField, IntArray>()
            var pos = 0
            loc.paragraph.runs.forEach { r ->
                val f = r.format.field
                if (f != null) at.getOrPut(f) { intArrayOf(pos, pos) }[1] = pos + r.text.length
                pos += r.text.length
            }
            at.entries.sortedBy { it.value[0] }.forEach { (f, r) -> out.add(Placed(f, i, r[0], r[1])) }
        }
        return out
    }

    /** [n] as a sequence's `\*` switch writes it. */
    fun number(n: Int, format: String?): String = when (format) {
        "ALPHABETIC" -> Lists.number(n, NumFormat.UPPER_LETTER)
        "alphabetic" -> Lists.number(n, NumFormat.LOWER_LETTER)
        "ROMAN" -> Lists.number(n, NumFormat.UPPER_ROMAN)
        "roman" -> Lists.number(n, NumFormat.LOWER_ROMAN)
        "CHINESENUM3", "CHINESENUM1", "DBNUM1" -> Lists.number(n, NumFormat.CHINESE)
        "CIRCLENUM", "GB3" -> Lists.number(n, NumFormat.CIRCLED)
        else -> n.toString()
    }

    /**
     * What each field of [doc] comes to — sequences numbered in text order (`\c` repeats, `\r n` restarts, `\h` hides),
     * references the text of their bookmark (`\n` / `\r` / `\w`: its paragraph's number; `\p`: above or below), page
     * references [pageOf]'s page of their bookmark's paragraph (none known: as they are) — by field.
     */
    fun results(doc: Document, pageOf: ((Int) -> Int?)? = null, kinds: Set<String> = LIVE, pageShown: ((Int) -> String)? = null): Map<DocField, String> {
        val placed = placed(doc)
        if (placed.isEmpty()) return emptyMap()
        val locs = doc.locations()
        val marks by lazy { Bookmarks.of(doc).associateBy { it.name } }
        val labels by lazy { PageMetrics.listLabels(doc) }
        val counters = HashMap<String, Int>()
        val chapters = HashMap<String, Int>()
        val out = java.util.IdentityHashMap<DocField, String>()
        for (pl in placed) {
            val f = pl.field
            when (f.kind) {
                "SEQ" -> {
                    val id = f.target.lowercase()
                    // `\s n`: numbered again in each chapter (from each heading of level n).
                    f.after("\\s")?.toIntOrNull()?.let { level ->
                        val start = chapterStart(locs, pl.para, level)
                        if (chapters[id] != start) { chapters[id] = start; counters[id] = 0 }
                    }
                    val n = when {
                        f.after("\\r")?.toIntOrNull() != null -> f.after("\\r")!!.toInt()
                        f.has("\\c") -> counters[id] ?: 0
                        else -> (counters[id] ?: 0) + 1
                    }
                    counters[id] = n
                    if ("SEQ" in kinds) out[f] = if (f.has("\\h")) "" else number(n, f.after("\\*"))
                }
                "REF" -> if ("REF" in kinds) {
                    val m = marks[f.target]
                    out[f] = when {
                        m == null -> Texts.get(TextId.FIELD_REF_MISSING)
                        f.has("\\p") -> Texts.get(if (before(pl, m)) TextId.FIELD_BELOW else TextId.FIELD_ABOVE)
                        f.has("\\n") || f.has("\\r") || f.has("\\w") -> labels[locs.getOrNull(m.startPara)?.paragraph]?.trim()?.trimEnd('.', '、', ')', '）')?.trimStart('(', '（').orEmpty()
                        else -> textOf(locs, m)
                    }
                }
                "STYLEREF" -> if ("STYLEREF" in kinds) {
                    val level = styleLevel(f.target) ?: 1
                    // `\s` (and the other number switches): the chapter's number; otherwise its heading's text.
                    out[f] = if (f.has("\\s") || f.has("\\n") || f.has("\\r") || f.has("\\w")) chapterNumber(locs, labels, pl.para, level) ?: Texts.get(TextId.FIELD_STYLE_MISSING)
                        else chapterStart(locs, pl.para, level).takeIf { it >= 0 }?.let { locs[it].paragraph.plainText() } ?: Texts.get(TextId.FIELD_STYLE_MISSING)
                }
                // A table formula: what it comes to in its cell, in its \# picture; Word's message when it cannot be worked out.
                "=" -> if ("=" in kinds) {
                    val at = TableFormulas.cellOf(doc, locs, pl.para)
                    val v = at?.let { (t, r, c) -> TableFormulas.evaluate(TableFormulas.formulaOf(f.code), t, r, c) }
                    out[f] = if (v == null) Texts.get(TextId.FIELD_FORMULA_ERROR) else TableFormulas.format(v, f.after("\\#"))
                }
                "PAGEREF" -> if ("PAGEREF" in kinds) {
                    val m = marks[f.target]
                    val page = m?.let { pageOf?.invoke(it.startPara) }
                    when {
                        m == null -> out[f] = Texts.get(TextId.FIELD_REF_MISSING)
                        page == null -> Unit
                        f.has("\\p") -> out[f] = if (pageOf?.invoke(pl.para) == page) Texts.get(if (before(pl, m)) TextId.FIELD_BELOW else TextId.FIELD_ABOVE)
                            else Texts.get(TextId.FIELD_ON_PAGE, pageShown?.invoke(page) ?: page.toString())
                        // The page as its section numbers it (i, ii… before the body's 1, 2…).
                        else -> out[f] = pageShown?.invoke(page) ?: page.toString()
                    }
                }
            }
        }
        return out
    }

    /** Whether the field [pl] stands before bookmark [m] in the text. */
    private fun before(pl: Placed, m: Bookmarks.Found): Boolean = pl.para < m.startPara || (pl.para == m.startPara && pl.start <= m.start)

    /** Bookmark [m]'s text (its paragraphs joined by spaces; a point: nothing). */
    fun textOf(locs: List<ParaLoc>, m: Bookmarks.Found): String {
        if (!m.closed) return ""
        val sb = StringBuilder()
        for (i in m.startPara..m.endPara) {
            val t = locs.getOrNull(i)?.paragraph?.plainText() ?: continue
            val a = if (i == m.startPara) m.start.coerceIn(0, t.length) else 0
            val b = if (i == m.endPara) m.end.coerceIn(a, t.length) else t.length
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t, a, b)
        }
        return sb.toString()
    }

    /**
     * [doc]'s fields worked out again (see [results]) and their results put in their text, each in its first run's look;
     * how many changed.
     */
    fun update(doc: Document, pageOf: ((Int) -> Int?)? = null, kinds: Set<String> = LIVE, pageShown: ((Int) -> String)? = null): Int =
        // The sequences (and the chapter numbers before them) first: what a reference shows may hold a caption's number.
        apply(doc, pageOf, kinds.intersect(setOf("SEQ", "STYLEREF")), pageShown) +
            // Formulas twice: a total of totals reads the cells the first pass worked out.
            apply(doc, pageOf, kinds.intersect(setOf("=")), pageShown) + apply(doc, pageOf, kinds.intersect(setOf("=")), pageShown) +
            apply(doc, pageOf, kinds - "SEQ" - "STYLEREF" - "=", pageShown)

    private fun apply(doc: Document, pageOf: ((Int) -> Int?)?, kinds: Set<String>, pageShown: ((Int) -> String)?): Int {
        if (kinds.isEmpty()) return 0
        val results = results(doc, pageOf, kinds, pageShown)
        if (results.isEmpty()) return 0
        val locs = doc.locations()
        var changed = 0
        // Paragraph by paragraph, from the last field back, so earlier offsets stay right.
        placed(doc).groupBy { it.para }.forEach { (para, list) ->
            val p = locs[para].paragraph
            for (pl in list.sortedByDescending { it.start }) {
                // (An empty result — a hidden sequence — would leave the field nothing to stand in: it keeps its text.)
                val text = results[pl.field]?.takeIf { it.isNotEmpty() } ?: continue
                if (p.plainText().substring(pl.start, pl.end) == text) continue
                val fmt = p.runs.first { it.format.field === pl.field }.format.copyOf()
                com.therealityteam.aeropages.editor.ParagraphOps.replaceKeeping(p, pl.start, pl.end, text, fmt)
                changed++
            }
        }
        return changed
    }
}

/**
 * How the text shows its tracked changes (Word's Display for Review, as the editor sets it): 0 every change marked,
 * 1 simple markup (the changes taken, a bar beside each changed paragraph), 2 no markup (the changes taken), 3 the
 * original (the changes undone). The canvas's styling and the PDF read it.
 */
object Markups {
    @Volatile var shown: Int = 0

    /**
     * Show Markup (Word's): whether insertions and deletions and formatting changes show marked, and the reviewers whose
     * changes do not — a change not shown reads as taken (an insertion as plain text, a deletion gone). [version] moves
     * whenever these do (laid out paragraphs key on it).
     */
    @Volatile var insertionsDeletions: Boolean = true
    @Volatile var formatting: Boolean = true
    @Volatile var hiddenReviewers: Set<String> = emptySet()
    @Volatile var version: Int = 0

    /** Whether [f]'s change (if it carries one) is one Show Markup shows: its kind ticked, its reviewer not hidden. */
    fun visible(f: CharacterFormat): Boolean {
        val kind = when { f.deleted || f.inserted -> insertionsDeletions; f.formatChanged -> formatting; else -> return true }
        return kind && (f.revAuthor ?: "") !in hiddenReviewers
    }

    /** Whether a run formatted [f] shows: a deletion only as the original or marked (and shown), an insertion only when not the original. */
    fun shows(f: CharacterFormat): Boolean = when (shown) { 1, 2 -> !f.deleted; 3 -> !f.inserted; else -> !f.deleted || visible(f) }

    /** Whether the changes are marked (underlined, struck through, in their reviewer's colour). */
    val marked: Boolean get() = shown == 0

    /** Whether [f]'s change shows marked: every change marked and this one shown. */
    fun marks(f: CharacterFormat): Boolean = shown == 0 && visible(f)
}
