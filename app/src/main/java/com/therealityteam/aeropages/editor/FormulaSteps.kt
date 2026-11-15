package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.Workbook

/**
 * Excel's Evaluate Formula, one step at a time: the formula as it stands, with [next] — the part the next step works
 * out, underlined in the dialog — and [last], what the step before put in (shown in italics). [next] is null once the
 * formula has come down to its result.
 */
class FormulaStep(val text: String, val next: IntRange?, val last: IntRange?)

object FormulaSteps {
    private val REF = Regex("""(?:(?:'(?:[^']|'')+'|[A-Za-z_][A-Za-z0-9_.]*)!)?\$?[A-Za-z]{1,3}\$?\d+(?::\$?[A-Za-z]{1,3}\$?\d+)?""")

    /** The first step: the formula itself (from its "="), the first part to work out underlined. */
    fun start(formula: String, names: Set<String>): FormulaStep {
        val text = formula.removePrefix("=")
        return FormulaStep(text, nextPart(text, names), null)
    }

    /**
     * The step after [step]: its underlined part replaced by what [evaluate] (given a formula without its "=") makes of it —
     * a reference by its value (a range by its values as {a,b;c,d}, by [cellValue] cell by cell), a call or a bracketed
     * part by its result; the last step leaves the result alone.
     */
    fun next(step: FormulaStep, names: Set<String>, evaluate: (String) -> String, cellValue: ((String) -> String)? = null): FormulaStep {
        val part = step.next ?: return step
        val piece = step.text.substring(part)
        val value = rangeLiteral(piece, cellValue) ?: evaluate(piece)
        val text = step.text.replaceRange(part, value)
        val placed = part.first until part.first + value.length
        return FormulaStep(text, nextPart(text, names), placed)
    }

    /** The values of a range reference written as Excel's dialog shows them — rows by ";", cells by "," — or null for anything else. */
    private fun rangeLiteral(piece: String, cellValue: ((String) -> String)?): String? {
        if (cellValue == null || ':' !in piece) return null
        val bang = piece.lastIndexOf('!')
        val prefix = if (bang >= 0) piece.substring(0, bang + 1) else ""
        val ends = piece.substring(bang + 1).split(':')
        if (ends.size != 2) return null
        val a = A1.parse(ends[0].replace("$", "")) ?: return null
        val b = A1.parse(ends[1].replace("$", "")) ?: return null
        val r1 = minOf(a.first, b.first); val r2 = maxOf(a.first, b.first)
        val c1 = minOf(a.second, b.second); val c2 = maxOf(a.second, b.second)
        if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 400) return null   // a big range is worked out as one array
        return (r1..r2).joinToString(";", "{", "}") { r ->
            (c1..c2).joinToString(",") { c -> cellValue(prefix + Workbook.colLabel(c) + (r + 1)) }
        }
    }

    /**
     * What to work out next in [text]: the first reference (or defined name) left to right; else the innermost call or
     * bracketed part; else the whole formula, when it is not a single value yet. Null when it is.
     */
    fun nextPart(text: String, names: Set<String>): IntRange? {
        val masked = mask(text)
        // A reference — not part of a longer name, not a function's name (LOG10( ), not inside "text" or {an array} —
        // or a defined name, whichever comes first.
        var found: IntRange? = null
        for (m in REF.findAll(masked)) {
            val before = masked.getOrNull(m.range.first - 1)
            val after = masked.getOrNull(m.range.last + 1)
            if (before != null && (before.isLetterOrDigit() || before == '_' || before == '.' || before == '$')) continue
            if (after != null && (after.isLetterOrDigit() || after == '_' || after == '(' || after == '.')) continue
            found = m.range; break
        }
        if (names.isNotEmpty()) for (m in Regex("""[A-Za-z_\\][A-Za-z0-9_.]*""").findAll(masked)) {
            if (found != null && m.range.first >= found.first) break
            val after = masked.getOrNull(m.range.last + 1)
            val before = masked.getOrNull(m.range.first - 1)
            if (after == '(' || after == '!' || before != null && (before.isLetterOrDigit() || before == '.')) continue
            if (names.any { it.equals(m.value, ignoreCase = true) }) { found = m.range; break }
        }
        if (found != null) return found
        // The innermost bracket, with the function name before it.
        var open = -1
        for ((i, ch) in masked.withIndex()) {
            if (ch == '(') open = i
            else if (ch == ')' && open >= 0) {
                var start = open
                while (start > 0 && (masked[start - 1].isLetterOrDigit() || masked[start - 1] == '.' || masked[start - 1] == '_')) start--
                return start..i
            }
        }
        return if (isValue(text)) null else text.indices
    }

    /** Whether [text] is a single value already: a number, "text", TRUE / FALSE, an error or {an array}. */
    private fun isValue(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (t.toDoubleOrNull() != null) return true
        if (t.equals("TRUE", true) || t.equals("FALSE", true)) return true
        if (t in FormulaEngine.ERRORS) return true
        val masked = mask(t)
        if (t.startsWith("\"") && t.endsWith("\"") && t.length >= 2 && masked.substring(1, masked.length - 1).all { it == ' ' }) return true
        if (t.startsWith("{") && t.endsWith("}") && masked.substring(1, masked.length - 1).all { it == ' ' }) return true
        return false
    }

    /** [text] with what is inside "strings" and {arrays} blanked out, so their characters are not read as references or brackets. */
    private fun mask(text: String): String {
        val sb = StringBuilder(text)
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '"' -> {
                    var j = i + 1
                    while (j < text.length) { if (text[j] == '"') { if (j + 1 < text.length && text[j + 1] == '"') { j += 2; continue }; break }; j++ }
                    for (k in i + 1 until minOf(j, text.length)) sb.setCharAt(k, ' ')
                    i = j + 1
                }
                '{' -> {
                    val j = text.indexOf('}', i).let { if (it < 0) text.length else it }
                    for (k in i + 1 until j) sb.setCharAt(k, ' ')
                    i = j + 1
                }
                '\'' -> {   // a quoted sheet name stays (it is part of a reference) but its spaces must not split it
                    var j = i + 1
                    while (j < text.length) { if (text[j] == '\'') { if (j + 1 < text.length && text[j + 1] == '\'') { j += 2; continue }; break }; j++ }
                    i = j + 1
                }
                else -> i++
            }
        }
        return sb.toString()
    }
}
