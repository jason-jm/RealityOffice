package com.therealityteam.aeropages.model

/**
 * Word's table formulas (Table Tools ▸ Data ▸ Formula — a `=` field in a cell): `=SUM(ABOVE)`, `=AVERAGE(LEFT)`,
 * `=B2*C2`, `=SUM(B2:B5)-D1`… Functions SUM, AVERAGE, COUNT, MAX, MIN, PRODUCT (over ABOVE / LEFT / BELOW / RIGHT,
 * cells and ranges) and ABS, INT, ROUND, MOD; + − × ÷ and brackets; cells named as Word names them (columns A, B…,
 * rows 1, 2…). ABOVE and the rest take the cells that way up to the first one holding no number (a header, a blank).
 * The result is written with the field's `\#` picture ("0.00", "#,##0.00", "¥#,##0.00").
 */
object TableFormulas {
    /** The table a paragraph (index [para] into [locs]) is in, and its cell's row and grid column; null outside tables. */
    fun cellOf(doc: Document, locs: List<ParaLoc>, para: Int): Triple<Table, Int, Int>? {
        val loc = locs.getOrNull(para) as? CellLoc ?: return null
        for (b in doc.blocks) {
            val t = (b as? TableBlock)?.table ?: continue
            t.rows.forEachIndexed { ri, row ->
                var g = 0
                for (c in row.cells) { if (c === loc.cell) return Triple(t, ri, g); g += c.gridSpan.coerceAtLeast(1) }
            }
        }
        return null
    }

    /** The formula of field code [code]: what follows its `=`, up to its switches. */
    fun formulaOf(code: String): String {
        val body = code.trim().removePrefix("=")
        val cut = body.indexOf('\\')
        return (if (cut >= 0) body.substring(0, cut) else body).trim()
    }

    /**
     * The number cell (row [r], grid column [c]) of [t] holds — its first number, written with [symbols] as Word reads
     * it with the system's (1,234.5 in English, 1.234,5 in German, 1 234,5 in French) — or null.
     */
    fun number(t: Table, r: Int, c: Int, symbols: NumberSymbols = NumberSymbols.current()): Double? {
        val row = t.rows.getOrNull(r) ?: return null
        var g = 0
        for (cell in row.cells) {
            val span = cell.gridSpan.coerceAtLeast(1)
            if (c < g + span) {
                if (cell.vMerged) return null
                val text = cell.content.joinToString(" ") { it.plainText() }
                return numberIn(text, symbols)
            }
            g += span
        }
        return null
    }

    /** The first number in [text] as [symbols] write it: groups of three after the first, a fraction after the decimal symbol. */
    fun numberIn(text: String, symbols: NumberSymbols = NumberSymbols.current()): Double? {
        val m = numberPattern(symbols).find(text) ?: return null
        val groups = groupsOf(symbols)
        val plain = m.value.filter { it !in groups }.replace(symbols.decimal, '.').replace('−', '-')
        return plain.toDoubleOrNull()
    }

    // The characters that group digits: the language's own, a typed space where it is a no-break one, a full-width comma beside a comma.
    private fun groupsOf(s: NumberSymbols): String = when (s.group) {
        ',' -> ",，"
        '\u00A0', '\u202F', ' ' -> "\u00A0\u202F "
        else -> s.group.toString()
    }

    private val patterns = HashMap<NumberSymbols, Regex>()

    private fun numberPattern(s: NumberSymbols): Regex = synchronized(patterns) {
        patterns.getOrPut(s) {
            val g = groupsOf(s).toList().joinToString("", "[", "]") { "\\x{%X}".format(java.util.Locale.ROOT, it.code) }
            val d = Regex.escape(s.decimal.toString())
            Regex("""[-+−]?(?:\d{1,3}(?:$g\d{3})+|\d+)(?:$d\d+)?|[-+−]?$d\d+""")
        }
    }

    /**
     * What [formula] (in the standard form, as the file keeps it) comes to in [t]'s cell ([row], [col]), its cells' numbers
     * read with [symbols]; null when it cannot be worked out (Word's syntax error).
     */
    fun evaluate(formula: String, t: Table, row: Int, col: Int, symbols: NumberSymbols = NumberSymbols.current()): Double? =
        runCatching { Parser(formula, t, row, col, symbols).run() }.getOrNull()?.takeIf { it.isFinite() }

    private class Parser(val src: String, val t: Table, val row: Int, val col: Int, val symbols: NumberSymbols) {
        var i = 0
        fun run(): Double { val v = expr(); skip(); require(i == src.length); return v }
        fun skip() { while (i < src.length && src[i].isWhitespace()) i++ }
        fun peek(): Char? { skip(); return src.getOrNull(i) }
        fun expr(): Double {
            var v = term()
            while (true) when (peek()) { '+' -> { i++; v += term() }; '-', '−' -> { i++; v -= term() }; else -> return v }
        }
        fun term(): Double {
            var v = factor()
            while (true) when (peek()) { '*', '×' -> { i++; v *= factor() }; '/', '÷' -> { i++; v /= factor() }; else -> return v }
        }
        fun factor(): Double {
            val c = peek() ?: error("end")
            if (c == '-' || c == '−') { i++; return -factor() }
            if (c == '+') { i++; return factor() }
            if (c == '(') { i++; val v = expr(); expect(')'); return v }
            if (c.isDigit() || c == '.') {
                val start = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                return src.substring(start, i).toDouble()
            }
            if (c.isLetter()) {
                val start = i
                while (i < src.length && src[i].isLetterOrDigit()) i++
                val word = src.substring(start, i).uppercase()
                if (peek() == '(') { i++; return call(word) }
                return refValue(word) ?: 0.0
            }
            error("unexpected $c")
        }
        fun expect(ch: Char) { require(peek() == ch); i++ }

        /** Function [name]'s arguments: positions, ranges, cells, expressions. */
        fun call(name: String): Double {
            val values = ArrayList<Double>()
            if (peek() != ')') do { values.addAll(argument()) } while (peek() == ',' && run { i++; true })
            expect(')')
            return when (name) {
                "SUM" -> values.sum()
                "AVERAGE" -> if (values.isEmpty()) 0.0 else values.average()
                "COUNT" -> values.size.toDouble()
                "MAX" -> values.maxOrNull() ?: 0.0
                "MIN" -> values.minOrNull() ?: 0.0
                "PRODUCT" -> values.fold(1.0) { a, b -> a * b }
                "ABS" -> kotlin.math.abs(values.single())
                "INT" -> kotlin.math.floor(values.single())
                "ROUND" -> { val n = values.getOrElse(1) { 0.0 }.toInt(); val f = Math.pow(10.0, n.toDouble()); Math.round(values[0] * f) / f }
                "MOD" -> values[0] % values[1]
                else -> error("function $name")
            }
        }

        /** One argument's numbers. */
        fun argument(): List<Double> {
            skip()
            val m = Regex("""(?i)^(ABOVE|LEFT|BELOW|RIGHT)\b""").find(src.substring(i))
            if (m != null) { i += m.value.length; return direction(m.value.uppercase()) }
            val r = Regex("""(?i)^([A-Z]+)(\d+)\s*:\s*([A-Z]+)(\d+)""").find(src.substring(i))
            if (r != null) {
                i += r.value.length
                val (c1, r1, c2, r2) = r.destructured
                val ca = colIndex(c1); val cb = colIndex(c2); val ra = r1.toInt() - 1; val rb = r2.toInt() - 1
                return buildList { for (rr in minOf(ra, rb)..maxOf(ra, rb)) for (cc in minOf(ca, cb)..maxOf(ca, cb)) number(t, rr, cc, symbols)?.let { add(it) } }
            }
            return listOf(expr())
        }

        /** The numbers that way from the formula's cell, up to the first cell with none. */
        fun direction(d: String): List<Double> {
            val (dr, dc) = when (d) { "ABOVE" -> -1 to 0; "BELOW" -> 1 to 0; "LEFT" -> 0 to -1; else -> 0 to 1 }
            val out = ArrayList<Double>()
            var r = row + dr; var c = col + dc
            while (r in t.rows.indices && c >= 0 && c < 64) {
                val v = number(t, r, c, symbols) ?: break
                out.add(v); r += dr; c += dc
            }
            return out
        }

        /** A cell named like B3 (column letters, row number). */
        fun refValue(word: String): Double? {
            val m = Regex("""^([A-Z]+)(\d+)$""").find(word) ?: error("name $word")
            return number(t, m.groupValues[2].toInt() - 1, colIndex(m.groupValues[1]), symbols)
        }

        fun colIndex(letters: String): Int = letters.uppercase().fold(0) { a, ch -> a * 26 + (ch - 'A' + 1) } - 1
    }

    /**
     * [v] written as picture [picture] says (Word's `\#`): its decimals (the 0 and # after the point), grouping (a comma
     * in it), and the text before and after (¥, %, 元); no picture: as few decimals as the value needs. The picture is
     * written in the standard form, as the file keeps it; the number comes out in [symbols] (1.234,50 in German).
     */
    fun format(v: Double, picture: String?, symbols: NumberSymbols = NumberSymbols.current()): String {
        if (picture.isNullOrBlank()) {
            val r = Math.round(v * 1e10) / 1e10
            return symbols.local(if (r == Math.floor(r) && kotlin.math.abs(r) < 1e15) r.toLong().toString() else r.toBigDecimal().stripTrailingZeros().toPlainString())
        }
        val first = picture.indexOfFirst { it == '0' || it == '#' }
        if (first < 0) return format(v, null, symbols)
        val last = picture.indexOfLast { it == '0' || it == '#' }
        val prefix = picture.substring(0, first)
        val suffix = picture.substring(last + 1)
        val body = picture.substring(first, last + 1)
        val dot = body.indexOf('.')
        val decimals = if (dot < 0) 0 else body.substring(dot + 1).count { it == '0' || it == '#' }
        val grouping = (if (dot < 0) body else body.substring(0, dot)).contains(',')
        val pattern = (if (grouping) "#,##0" else "0") + (if (decimals > 0) "." + "0".repeat(decimals) else "")
        val fmt = java.text.DecimalFormat(pattern, java.text.DecimalFormatSymbols(java.util.Locale.US)).apply { roundingMode = java.math.RoundingMode.HALF_UP }
        val shown = symbols.local(fmt.format(kotlin.math.abs(v)))
        return (if (v < 0) "-" else "") + prefix + shown + suffix
    }
}
