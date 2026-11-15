package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.NumberSymbols

/**
 * Formulas as Excel shows and takes them in a language whose decimal symbol is a comma: arguments apart with `;`
 * (`=ROUND(A1;2)`), numbers with the comma (`=A1*1,5`), and in array constants `.` between columns and `;` between rows
 * (`{1,5.2;3.4}`). Cells and files keep the standard formula (`ROUND(A1,2)`, `1.5`, `{1.5,2;3,4}`); [toLocal] is what
 * the formula bar shows, [toCanonical] what is kept of what was typed. Strings, quoted sheet names and bracketed parts
 * are left as they are; function names keep their dots (NORM.DIST). In a language that writes 1.5 nothing changes.
 *
 * Typed the English way (`=SUM(A1,B1)`) a formula still reads, as long as no number sits right after a comma: a comma
 * between digits is a decimal comma there (`=ROUND(3,2)` is ROUND(3.2)).
 */
object FormulaLocale {

    fun toLocal(formula: String, symbols: NumberSymbols): String = if (symbols.argument == ',') formula else convert(formula, symbols, local = true)

    fun toCanonical(formula: String, symbols: NumberSymbols): String = if (symbols.argument == ',') formula else convert(formula, symbols, local = false)

    /** The example formulas in a sentence (=COUNTIF($A:$A,A1)=1 in a hint) the language's way; the words left as they are. */
    fun inText(text: String, symbols: NumberSymbols): String =
        if (symbols.argument == ',') text else Regex("""=\S+""").replace(text) { m ->
            // The sentence's own punctuation after the formula (… =$A$1:$B$5), …) is not part of it.
            val f = m.value.trimEnd('.', ',', ';', ':', '。', '、', '，')
            toLocal(f, symbols) + m.value.substring(f.length)
        }

    private fun convert(f: String, sym: NumberSymbols, local: Boolean): String {
        val out = StringBuilder(f.length + 8)
        var array = 0
        var i = 0
        // Whether the character before belongs to a name or a reference (A1, $B$2, LOG10, Sheet1): digits there are no number.
        fun inName(): Boolean = out.isNotEmpty() && out.last().let { it.isLetterOrDigit() || it == '_' || it == '$' || it == '.' || it == '\\' }
        while (i < f.length) {
            val c = f[i]
            when {
                c == '"' || c == '\'' -> {
                    // A string or a quoted sheet name, its doubled quote included.
                    out.append(c); i++
                    while (i < f.length) {
                        out.append(f[i])
                        if (f[i] == c) { if (i + 1 < f.length && f[i + 1] == c) { out.append(c); i += 2; continue } else { i++; break } }
                        i++
                    }
                    continue
                }
                c == '[' -> {
                    var depth = 0
                    while (i < f.length) { val ch = f[i]; out.append(ch); i++; if (ch == '[') depth++ else if (ch == ']' && --depth == 0) break }
                    continue
                }
                c == '{' -> { array++; out.append(c) }
                c == '}' -> { array = (array - 1).coerceAtLeast(0); out.append(c) }
                c.isDigit() && !inName() -> {
                    // A number: its decimal point the other way round, its exponent as it is.
                    val from = if (local) '.' else sym.decimal
                    val to = if (local) sym.decimal else '.'
                    while (i < f.length && f[i].isDigit()) out.append(f[i++])
                    if (i + 1 < f.length && f[i] == from && f[i + 1].isDigit()) {
                        out.append(to); i++
                        while (i < f.length && f[i].isDigit()) out.append(f[i++])
                    }
                    if (i < f.length && (f[i] == 'E' || f[i] == 'e') && i + 1 < f.length &&
                        (f[i + 1].isDigit() || ((f[i + 1] == '+' || f[i + 1] == '-') && i + 2 < f.length && f[i + 2].isDigit()))) {
                        out.append(f[i++]); if (!f[i].isDigit()) out.append(f[i++])
                        while (i < f.length && f[i].isDigit()) out.append(f[i++])
                    }
                    continue
                }
                local && c == ',' -> out.append(if (array > 0) '.' else ';')
                !local && c == ';' -> out.append(if (array > 0) ';' else ',')
                !local && c == '.' && array > 0 -> out.append(',')
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
