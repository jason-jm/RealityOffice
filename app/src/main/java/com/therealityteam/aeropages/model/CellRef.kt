package com.therealityteam.aeropages.model

/** A1-style cell reference helpers shared by the formula engine, the editor and the xlsx reader. */
object A1 {
    /** Column letters to a zero-based index: A→0, Z→25, AA→26. Empty → -1. */
    fun colIndex(letters: String): Int {
        var v = 0
        for (ch in letters) v = v * 26 + (ch.uppercaseChar() - 'A' + 1)
        return v - 1
    }

    /** `B3` / `$B$3` → (row=2, col=1), zero-based; null when the text is not a cell reference. */
    fun parse(ref: String): Pair<Int, Int>? {
        val clean = ref.replace("$", "")
        val letters = clean.takeWhile { it.isLetter() }
        val digits = clean.substring(letters.length)
        if (letters.isEmpty() || digits.isEmpty() || !digits.all { it.isDigit() }) return null
        val row = digits.toIntOrNull() ?: return null
        if (row < 1) return null
        return (row - 1) to colIndex(letters)
    }

    /** Zero-based (row, col) → `B3`. */
    fun ref(row: Int, col: Int): String = Workbook.colLabel(col) + (row + 1)

    /** `A1:C5` (or one cell), with any sheet qualifier / `$` ignored → normalised [r1, c1, r2, c2]; null when malformed. */
    fun parseRange(ref: String): IntArray? {
        val body = ref.substringAfterLast('!').replace("$", "")
        val parts = body.split(':')
        val a = parse(parts[0].trim()) ?: return null
        val b = if (parts.size > 1) parse(parts[1].trim()) ?: return null else a
        return intArrayOf(minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second))
    }

    /** Zero-based block → `A1:C5` (a single cell stays `A1`). */
    fun rangeRef(r1: Int, c1: Int, r2: Int, c2: Int): String =
        if (r1 == r2 && c1 == c2) ref(r1, c1) else ref(r1, c1) + ":" + ref(r2, c2)

    /** Excel's grid: a reference past its last row or column is not a cell but text (a name). */
    const val EXCEL_ROWS = 1_048_576
    const val EXCEL_COLS = 16_384

    /**
     * What the Name Box can go to: a cell or a block, whole columns (`B:D`: [r1] = [r2] = -1) or
     * whole rows (`3:5`: [c1] = [c2] = -1), on [sheet] when the entry names one.
     */
    class Target(val sheet: String?, val r1: Int, val c1: Int, val r2: Int, val c2: Int) {
        val wholeColumns: Boolean get() = r1 < 0
        val wholeRows: Boolean get() = c1 < 0
    }

    /**
     * A Name Box entry as a reference: `C5`, `$B$2:D10`, `B:D`, `3:5`, `Sheet2!A1`,
     * `'Q3 Sales'!A1:B2` (a doubled quote stands for one). Null when it is not one — a name, say.
     */
    fun parseTarget(text: String): Target? {
        var s = text.trim()
        var sheet: String? = null
        if (s.startsWith("'")) {
            val sb = StringBuilder()
            var i = 1
            while (true) {
                if (i >= s.length) return null
                val ch = s[i]
                if (ch == '\'') {
                    if (i + 1 < s.length && s[i + 1] == '\'') { sb.append('\''); i += 2; continue }
                    i++; break
                }
                sb.append(ch); i++
            }
            if (i >= s.length || s[i] != '!') return null
            sheet = sb.toString(); s = s.substring(i + 1)
        } else {
            val bang = s.lastIndexOf('!')
            if (bang >= 0) { sheet = s.substring(0, bang).trim(); s = s.substring(bang + 1) }
            // An unquoted sheet name holds no operators: `Sales!A2*Sales!B2` is a formula, not a reference.
            if (sheet != null && sheet.any { it in "+-*/&^<>=,()!\"%;{}" }) return null
        }
        if (sheet != null && sheet.isEmpty()) return null
        val parts = s.replace("$", "").split(':').map { it.trim() }
        if (parts.size > 2 || parts.any { it.isEmpty() }) return null
        val a = parts[0]
        val b = parts.getOrElse(1) { a }
        fun rowNumber(p: String) = if (p.all { it in '0'..'9' }) p.toIntOrNull()?.takeIf { it in 1..EXCEL_ROWS } else null
        fun column(p: String) = if (p.length <= 3 && p.all { it in 'A'..'Z' || it in 'a'..'z' }) colIndex(p).takeIf { it < EXCEL_COLS } else null
        if (parts.size == 2) {
            val x = rowNumber(a); val y = rowNumber(b)
            if (x != null && y != null) return Target(sheet, minOf(x, y) - 1, -1, maxOf(x, y) - 1, -1)
            val p = column(a); val q = column(b)
            if (p != null && q != null) return Target(sheet, -1, minOf(p, q), -1, maxOf(p, q))
        }
        val p = cellOrNull(a) ?: return null
        val q = cellOrNull(b) ?: return null
        return Target(sheet, minOf(p.first, q.first), minOf(p.second, q.second), maxOf(p.first, q.first), maxOf(p.second, q.second))
    }

    /** `B3` within Excel's grid (ASCII column letters, `XFD1048576` at most); null otherwise. */
    private fun cellOrNull(p: String): Pair<Int, Int>? {
        val letters = p.takeWhile { it in 'A'..'Z' || it in 'a'..'z' }
        if (letters.isEmpty() || letters.length > 3) return null
        val digits = p.substring(letters.length)
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val row = digits.toIntOrNull()?.takeIf { it in 1..EXCEL_ROWS } ?: return null
        val col = colIndex(letters).takeIf { it < EXCEL_COLS } ?: return null
        return (row - 1) to col
    }

    /** R1C1-style references (`R`, `C`, `R2`, `RC3`, `R1C1`) are never names in Excel either. */
    private val R1C1 = Regex("^(?i)(R\\d*C\\d*|R\\d*|C\\d*)$")

    /**
     * Excel's rules for a defined name: a letter, an underscore or a backslash first, then letters,
     * digits, underscores, periods or backslashes (no spaces), at most 255 characters, and nothing
     * that reads as a reference (`A1`, `R1C1`).
     */
    fun isValidName(name: String): Boolean {
        if (name.isEmpty() || name.length > 255) return false
        val first = name[0]
        if (!(first.isLetter() || first == '_' || first == '\\')) return false
        if (!name.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '\\' }) return false
        return parseTarget(name) == null && !R1C1.matches(name)
    }

    /** `'Sales Q3'!${'$'}B${'$'}2:${'$'}B${'$'}5` — the absolute, sheet-qualified form chart parts and defined names use. */
    fun absoluteRef(sheet: String, r1: Int, c1: Int, r2: Int, c2: Int): String {
        fun cell(r: Int, c: Int) = "$" + Workbook.colLabel(c) + "$" + (r + 1)
        return quoteSheet(sheet) + "!" + cell(r1, c1) + (if (r1 == r2 && c1 == c2) "" else ":" + cell(r2, c2))
    }

    private val CELL_REF = Regex("(?<![A-Za-z0-9_$])(\\$?)([A-Z]{1,3})(\\$?)(\\d{1,7})(?![A-Za-z0-9_(])")

    /**
     * [formula] with its relative references moved by [dRow] rows and [dCol] columns (`$`-anchored
     * parts stay; a reference moved off the sheet is `#REF!`) — a formula written for one cell
     * rewritten for another, as shared formulas and rules' formulas are.
     */
    fun shiftRefs(formula: String, dRow: Int, dCol: Int): String =
        if (dRow == 0 && dCol == 0) formula else StructuredRefs.outsideLiterals(formula) { code -> shiftCode(code, dRow, dCol) }

    private fun shiftCode(code: String, dRow: Int, dCol: Int): String = CELL_REF.replace(code) { m ->
            val g = m.groupValues
            var col = colIndex(g[2])
            var row = g[4].toInt() - 1
            if (g[1].isEmpty()) col += dCol
            if (g[3].isEmpty()) row += dRow
            if (col < 0 || row < 0) "#REF!" else "${g[1]}${Workbook.colLabel(col)}${g[3]}${row + 1}"
        }

    /** A sheet name as references spell it: quoted unless it is a plain word that cannot be read as a number or a reference. */
    fun quoteSheet(sheet: String): String =
        if (sheet.isNotEmpty() && sheet.all { it.isLetterOrDigit() || it == '_' } && !sheet[0].isDigit() && parseTarget(sheet) == null && !R1C1.matches(sheet)) sheet
        else "'" + sheet.replace("'", "''") + "'"
}
