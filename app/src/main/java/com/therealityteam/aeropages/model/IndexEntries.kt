package com.therealityteam.aeropages.model

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Word's index: the entries marked in the text (Word's `XE "Main:Sub"` fields, ODF's alphabetical index marks) and the
 * [IndexBlock] that lists them alphabetically with the pages they are on.
 *
 * An entry is a point mark among its paragraph's [Paragraph.bookmarks] — named [PREFIX], a serial, then the main entry
 * and its subentry — so the edits carry it with the text as they carry bookmarks; it is no bookmark (its name starts
 * with the reserved character [Bookmarks.isComment] leaves out).
 */
object IndexEntries {
    const val PREFIX = "\u0001x"
    private const val TEXT = '\u0002'
    private const val SUB = '\u0003'
    private val serial = java.util.concurrent.atomic.AtomicLong()

    /** A marked entry: its main entry and, under it, an optional subentry. */
    data class Entry(val main: String, val sub: String? = null)

    fun isEntry(name: String): Boolean = name.startsWith(PREFIX)

    /** The mark's name for [e] (each mark its own: a serial keeps two marks of one entry apart). */
    fun markName(e: Entry): String = PREFIX + serial.incrementAndGet() + TEXT + clean(e.main) + (e.sub?.let(::clean)?.takeIf { it.isNotEmpty() }?.let { SUB + it } ?: "")

    fun entryOf(name: String): Entry? {
        if (!isEntry(name)) return null
        val t = name.indexOf(TEXT).takeIf { it >= 0 } ?: return null
        val rest = name.substring(t + 1)
        val s = rest.indexOf(SUB)
        val e = if (s < 0) Entry(rest) else Entry(rest.substring(0, s), rest.substring(s + 1).ifEmpty { null })
        return e.takeIf { it.main.isNotEmpty() }
    }

    /** An entry's text as it can be kept: one line, no control characters, trimmed. */
    fun clean(text: String): String = text.map { if (it < ' ') ' ' else it }.joinToString("").trim()

    /** A marked entry where it stands: its paragraph ([para] indexes [Document.locations]) and the character before it. */
    data class Ref(val entry: Entry, val para: Int, val offset: Int, val paragraph: Paragraph)

    /** Every entry marked in [doc], in reading order. */
    fun refs(doc: Document): List<Ref> {
        val out = ArrayList<Ref>()
        doc.locations().forEachIndexed { i, loc ->
            loc.paragraph.bookmarks.forEach { m -> if (!m.end) entryOf(m.name)?.let { out.add(Ref(it, i, m.offset, loc.paragraph)) } }
        }
        return out
    }

    /** Whether [doc] has any entry marked. */
    fun any(doc: Document): Boolean = doc.locations().any { l -> l.paragraph.bookmarks.any { isEntry(it.name) } }

    /**
     * One line of an index: a letter heading ([heading]), a main entry (level 0) or a subentry (level 1), with the
     * paragraphs its entries stand in ([refs], in reading order — the pages come from them; empty for a main entry only
     * its subentries mark).
     */
    class Line(val text: String, val level: Int, val refs: List<Paragraph>, val heading: Boolean = false) {
        /** Its page numbers as the index shows them: each page once, in order ("2, 5, 7"). */
        fun pages(pageOf: (Paragraph) -> String?): String = refs.mapNotNull(pageOf).distinct().joinToString(", ")
    }

    /**
     * The index of [doc]: its main entries sorted as [locale] sorts words (case and accents only break ties), each
     * followed by its subentries; with [headings], a letter before each letter's entries (Word's `\h "A"`).
     */
    fun lines(doc: Document, headings: Boolean, locale: Locale = Locale.getDefault()): List<Line> {
        val refs = refs(doc)
        if (refs.isEmpty()) return emptyList()
        val coll = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
        val order = Comparator<String> { a, b -> coll.compare(a, b).takeIf { it != 0 } ?: a.compareTo(b) }
        val mains = java.util.TreeMap<String, Pair<MutableList<Paragraph>, java.util.TreeMap<String, MutableList<Paragraph>>>>(order)
        refs.forEach { r ->
            val (own, subs) = mains.getOrPut(r.entry.main) { ArrayList<Paragraph>() to java.util.TreeMap(order) }
            if (r.entry.sub == null) own.add(r.paragraph) else subs.getOrPut(r.entry.sub) { ArrayList() }.add(r.paragraph)
        }
        val out = ArrayList<Line>()
        var letter: String? = null
        mains.forEach { (main, entry) ->
            if (headings) headingOf(main)?.let { h -> if (h != letter) { letter = h; out.add(Line(h, -1, emptyList(), heading = true)) } }
            out.add(Line(main, 0, entry.first.distinct()))
            entry.second.forEach { (sub, paras) -> out.add(Line(sub, 1, paras.distinct())) }
        }
        return out
    }

    /**
     * The letter an entry is listed under: its first letter without accents, capital ("é" → "E"); "#" for one that starts
     * with a digit or a symbol; null for a script without capitals (Chinese, Japanese, Thai…), which goes on unheaded.
     */
    fun headingOf(text: String): String? {
        val c = text.firstOrNull { !it.isWhitespace() } ?: return null
        if (!c.isLetter()) return "#"
        val base = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first().uppercaseChar()
        return if (base.isUpperCase() && base.lowercaseChar() != base) base.toString() else null
    }

    // --- Word's XE and INDEX fields ---------------------------------------------------------------------------------

    /** Word's field code for [e]: `XE "Main:Sub"` (a colon or a quote in the text escaped as Word escapes them). */
    fun fieldCode(e: Entry): String = " XE \"" + esc(e.main) + (e.sub?.let { ":" + esc(it) } ?: "") + "\" "

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(":", "\\:").replace("\"", "\\\"")

    /** The entry an `XE` field's [code] marks (its levels after the first joined as the subentry), or null for another field. */
    fun fromFieldCode(code: CharSequence): Entry? {
        val s = code.toString().trim()
        if (!s.startsWith("XE", ignoreCase = true) || (s.length > 2 && !s[2].isWhitespace())) return null
        var i = 2
        while (i < s.length && s[i].isWhitespace()) i++
        val text = StringBuilder()
        if (i < s.length && s[i] == '"') {
            i++
            while (i < s.length && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.length && s[i + 1] in "\\:\"") { text.append('\\').append(s[i + 1]); i += 2 } else text.append(s[i++])
            }
        } else while (i < s.length && !s[i].isWhitespace()) text.append(s[i++])
        // Its levels: split at the colons not escaped.
        val levels = ArrayList<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < text.length) {
            val ch = text[k]
            if (ch == '\\' && k + 1 < text.length) { cur.append(text[k + 1]); k += 2; continue }
            if (ch == ':') { levels.add(cur.toString()); cur.clear() } else cur.append(ch)
            k++
        }
        levels.add(cur.toString())
        val main = clean(levels.first())
        if (main.isEmpty()) return null
        val sub = levels.drop(1).map(::clean).filter { it.isNotEmpty() }.joinToString(": ").ifEmpty { null }
        return Entry(main, sub)
    }

    /** Whether a field's [code] is Word's `INDEX`. */
    fun isIndexField(code: CharSequence): Boolean {
        val s = code.toString().trim()
        return s.startsWith("INDEX", ignoreCase = true) && (s.length == 5 || s[5].isWhitespace())
    }

    /** The index an `INDEX` field's [code] asks for: page numbers right-aligned (`\e` with a tab), letter headings (`\h`). */
    fun blockOf(code: CharSequence): IndexBlock {
        val s = code.toString()
        val e = Regex("""\\e\s+"([^"]*)"""").find(s)?.groupValues?.get(1)
        return IndexBlock(rightAlign = e != null && e.contains('\t'), headings = Regex("""\\h\s""").containsMatchIn("$s "))
    }

    /** Word's `INDEX` code for [b] (one column, as the app lays it out). */
    fun indexCode(b: IndexBlock): String = buildString {
        append(" INDEX")
        if (b.rightAlign) append(" \\e \"\t\"")
        if (b.headings) append(" \\h \"A\"")
        append(" \\c \"1\" ")
    }
}

/** Word's index (an `INDEX` field, ODF's alphabetical index): the marked entries, live, with their pages. */
class IndexBlock(val rightAlign: Boolean = false, val headings: Boolean = false) : Block {
    override fun copyOf(): Block = IndexBlock(rightAlign, headings)
}
