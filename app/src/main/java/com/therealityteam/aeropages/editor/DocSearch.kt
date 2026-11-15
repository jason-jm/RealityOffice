package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.Document

/** How Find matches (Word's Find and Replace options): case, whole words only, Word's wildcards. */
data class SearchOptions(val matchCase: Boolean = false, val wholeWord: Boolean = false, val wildcards: Boolean = false)

/**
 * Word's Find: what a query matches in a document's paragraphs (body, tables, text boxes, in their order) and what a
 * replacement puts there.
 *
 * Without wildcards a query is its text, with Word's codes: `^t` tab, `^l` manual line break, `^s` non-breaking space,
 * `^~` non-breaking hyphen, `^-` optional hyphen, `^?` any character, `^#` any digit, `^$` any letter, `^w` white space,
 * `^^` a caret. With wildcards, Word's: `?` one character, `*` any run of them, `[abc]` / `[a-z]` / `[!abc]`, `{n}` /
 * `{n,}` / `{n,m}` and `@` (one or more) of what is before, `<` / `>` a word's start / end, `( )` groups, `\x` the
 * character x itself. A replacement's `^&` is what was found, `\1`…`\9` a group (wildcards), and the same codes.
 */
object DocSearch {
    /** A match: paragraph [para] (an index into the document's locations), characters [start] until [end]. */
    data class Hit(val para: Int, val start: Int, val end: Int)

    /** The pattern [query] stands for with [options]; null for an empty query or wildcards Word would not take. */
    fun pattern(query: String, options: SearchOptions): Regex? {
        if (query.isEmpty()) return null
        val body = (if (options.wildcards) wildcards(query) else plain(query)) ?: return null
        // Whole words: not part of a longer word of letters or digits (CJK text has no such words; it matches as written).
        val src = if (options.wholeWord && !options.wildcards) "(?<![$WORD])(?:$body)(?![$WORD])" else body
        return runCatching { if (options.matchCase) Regex(src) else Regex(src, setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
    }

    private const val WORD = "A-Za-z0-9_\\u00C0-\\u024F\\u0370-\\u03FF\\u0400-\\u04FF"

    /** A plain query with Word's `^` codes. */
    private fun plain(q: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < q.length) {
            val c = q[i]
            if (c == '^' && i + 1 < q.length) {
                sb.append(code(q[i + 1], wildcards = false) ?: return null)
                i += 2
                continue
            }
            sb.append(Regex.escape(c.toString()))
            i++
        }
        return sb.toString()
    }

    /** What a `^` code matches; null for one Word does not know (or that cannot be found within a paragraph, `^p`). */
    private fun code(c: Char, wildcards: Boolean): String? = when (c) {
        't' -> "\\t"
        'l', 'v' -> "\\n"
        's' -> "\\u00A0"
        '~' -> "\\u2011"
        '-' -> "\\u00AD"
        '^' -> "\\^"
        '?' -> if (wildcards) null else "(?s:.)"
        '#' -> if (wildcards) null else "\\p{Nd}"
        '$' -> if (wildcards) null else "\\p{L}"
        'w' -> if (wildcards) null else "[ \\t\\u00A0\\u3000]+"
        else -> null
    }

    /** Word's wildcards as a regular expression; null where they do not make one. */
    private fun wildcards(q: String): String? {
        val sb = StringBuilder()
        var i = 0
        var depth = 0
        var canRepeat = false
        while (i < q.length) {
            val c = q[i]
            when {
                c == '\\' && i + 1 < q.length -> { sb.append(Regex.escape(q[i + 1].toString())); i += 2; canRepeat = true; continue }
                c == '^' && i + 1 < q.length -> { sb.append(code(q[i + 1], wildcards = true) ?: return null); i += 2; canRepeat = true; continue }
                c == '?' -> { sb.append("(?s:.)"); canRepeat = true }
                c == '*' -> { sb.append("(?s:.*?)"); canRepeat = false }
                c == '@' -> { if (!canRepeat) return null; sb.append('+'); canRepeat = false }
                c == '<' -> { sb.append("\\b(?=\\w)"); canRepeat = false }
                c == '>' -> { sb.append("\\b(?<=\\w)"); canRepeat = false }
                c == '(' -> { sb.append('('); depth++; canRepeat = false }
                c == ')' -> { if (depth == 0) return null; sb.append(')'); depth--; canRepeat = true }
                c == '[' -> {
                    val close = q.indexOf(']', i + 2).takeIf { it > i } ?: return null
                    var set = q.substring(i + 1, close)
                    val negate = set.startsWith("!")
                    if (negate) set = set.substring(1)
                    if (set.isEmpty()) return null
                    // A range keeps its dash; anything else in the set is itself.
                    val inner = StringBuilder()
                    var k = 0
                    while (k < set.length) {
                        if (k + 2 < set.length && set[k + 1] == '-') { inner.append(esc(set[k])).append('-').append(esc(set[k + 2])); k += 3 }
                        else { inner.append(esc(set[k])); k++ }
                    }
                    sb.append(if (negate) "[^$inner]" else "[$inner]")
                    i = close + 1
                    canRepeat = true
                    continue
                }
                c == '{' -> {
                    val close = q.indexOf('}', i).takeIf { it > i } ?: return null
                    val spec = q.substring(i + 1, close).replace(';', ',')
                    if (!canRepeat || !Regex("""\d+(,\d*)?""").matches(spec)) return null
                    sb.append('{').append(spec).append('}')
                    i = close + 1
                    canRepeat = false
                    continue
                }
                else -> { sb.append(Regex.escape(c.toString())); canRepeat = true }
            }
            i++
        }
        return if (depth == 0) sb.toString() else null
    }

    private fun esc(c: Char): String = if (c in "\\^[]-") "\\$c" else c.toString()

    /** Every match of [regex] in [doc], in document order (an empty match is no match). */
    fun hits(doc: Document, regex: Regex): List<Hit> {
        val out = ArrayList<Hit>()
        doc.locations().forEachIndexed { i, loc ->
            val text = loc.paragraph.plainText()
            for (m in regex.findAll(text)) if (m.range.last >= m.range.first) out.add(Hit(i, m.range.first, m.range.last + 1))
        }
        return out
    }

    /** What [replacement] puts in place of [match] (Word's `^&`, `\n` groups with wildcards, and the codes). */
    fun expand(replacement: String, match: MatchResult, options: SearchOptions): String {
        val sb = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            val next = replacement.getOrNull(i + 1)
            when {
                c == '^' && next != null -> {
                    sb.append(when (next) {
                        '&' -> match.value
                        't' -> "\t"
                        'l', 'v' -> "\n"
                        's' -> " "
                        '~' -> "‑"
                        '-' -> "­"
                        '^' -> "^"
                        else -> "^$next"
                    })
                    i += 2
                }
                c == '\\' && options.wildcards && next != null && next in '1'..'9' -> {
                    sb.append(match.groups[next - '0']?.value.orEmpty()); i += 2
                }
                c == '\\' && options.wildcards && next != null -> { sb.append(next); i += 2 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /** A few words of [text] round [start] until [end], for a list of results: (before, found, after). */
    fun context(text: String, start: Int, end: Int, around: Int = 18): Triple<String, String, String> {
        val a = (start - around).coerceAtLeast(0)
        val b = (end + around * 2).coerceAtMost(text.length)
        val before = (if (a > 0) "…" else "") + text.substring(a, start)
        val after = text.substring(end, b) + if (b < text.length) "…" else ""
        return Triple(before.replace('\n', ' ').replace('\t', ' '), text.substring(start, end), after.replace('\n', ' ').replace('\t', ' '))
    }
}
