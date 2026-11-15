package com.therealityteam.aeropages.model

/**
 * Links from a workbook's cells to places in the workbook — Excel's `#Sheet2!A1`, `#'Q3 Sales'!B2:C5`,
 * `#Total` (a defined name), `#A1` (on the cell's own sheet), what xlsx keeps as a hyperlink's
 * `location` and ODS as `#Sheet2.A1` — and the `=HYPERLINK(location, [name])` formulas that make links.
 */
object CellLinks {

    /** The place a link within the workbook names (`#Sheet2!A1` → `Sheet2!A1`); null for a link elsewhere. */
    fun place(link: String?): String? = link?.takeIf { it.length > 1 && it.startsWith("#") }?.substring(1)

    /** A link to [ref] (`A1`, `B2:C5`) on sheet [sheet] (quoted when its name needs it); `#ref` with no sheet. */
    fun toPlace(sheet: String?, ref: String): String = "#" + (sheet?.let { quoted(it) + "!" } ?: "") + ref

    /** [name] as a reference writes it: quoted, its own quotes doubled, unless it is letters, digits, `_` and `.` not starting with a digit. */
    fun quoted(name: String): String =
        if (name.isNotEmpty() && !name[0].isDigit() && name.all { it.isLetterOrDigit() || it == '_' || it == '.' }) name
        else "'" + name.replace("'", "''") + "'"

    /** Splits [s] at [sep] where it is outside single quotes. */
    private fun split(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        var quoted = false
        var from = 0
        for (i in s.indices) {
            val c = s[i]
            if (c == '\'') quoted = !quoted
            else if (c == sep && !quoted) { out.add(s.substring(from, i)); from = i + 1 }
        }
        out.add(s.substring(from))
        return out
    }

    /** The last [sep] of [s] outside single quotes, or -1. */
    private fun lastOutside(s: String, sep: Char): Int {
        var quoted = false
        var at = -1
        for (i in s.indices) {
            val c = s[i]
            if (c == '\'') quoted = !quoted else if (c == sep && !quoted) at = i
        }
        return at
    }

    /**
     * An ODF link to cells (`#Sheet2.A1`, `#'Q3 Sales'.B2:'Q3 Sales'.C5`, `#$Sheet2.$A$1`, `#Sheet2.A1:.B5`)
     * as Excel writes it (`#Sheet2!A1`, `#'Q3 Sales'!B2:C5`); any other link as it is.
     */
    fun fromOdf(link: String): String {
        val body = place(link) ?: return link
        val parts = split(body, ':')
        val first = parts[0]
        val dot = lastOutside(first, '.')
        if (dot < 0) return link
        val sheet = first.substring(0, dot).removePrefix("$")
        val cell = first.substring(dot + 1).replace("$", "")
        if (!A1_CELL.matches(cell)) return link
        val end = parts.getOrNull(1)?.let { p -> p.substring(lastOutside(p, '.') + 1).replace("$", "") }?.takeIf { A1_CELL.matches(it) }
        val ref = if (end != null && end != cell) "$cell:$end" else cell
        return "#" + (if (sheet.isNotEmpty()) "$sheet!" else "") + ref
    }

    /**
     * An Excel link to cells (`#Sheet2!A1`, `#A1` on [ownSheet]) as LibreOffice writes it in ODF
     * (`#Sheet2.A1`, a range naming its sheet once: `#Sheet2.A1:B5`); others as they are.
     */
    fun toOdf(link: String, ownSheet: String): String {
        val body = place(link) ?: return link
        val bang = lastOutside(body, '!')
        val sheet = if (bang >= 0) body.substring(0, bang) else quoted(ownSheet)
        val ref = body.substring(bang + 1)
        val cells = ref.split(':')
        if (cells.isEmpty() || cells.size > 2 || !cells.all { A1_CELL.matches(it.replace("$", "")) }) return link
        return "#$sheet." + cells.joinToString(":") { it.replace("$", "") }
    }

    /**
     * A hyperlink's `location` as Excel names the place: as written, except LibreOffice's mixed form for
     * a range (`'Q3 Sales'.A1:'Q3 Sales'!C4`, its xlsx after an ODS) — `'Q3 Sales'!A1:C4`.
     */
    fun excelPlace(location: String): String {
        val parts = split(location, ':')
        if (parts.size != 2 || lastOutside(parts[0], '!') >= 0) return location
        val dot = lastOutside(parts[0], '.')
        val bang = lastOutside(parts[1], '!')
        if (dot < 0 || bang < 0) return location
        val cell = parts[0].substring(dot + 1)
        if (!A1_CELL.matches(cell.replace("$", ""))) return location
        return parts[0].substring(0, dot) + "!" + cell + ":" + parts[1].substring(bang + 1)
    }

    private val A1_CELL = Regex("""[A-Za-z]{1,3}[0-9]{1,7}""")

    private val HYPERLINK_CALL = Regex("""^=\s*HYPERLINK\s*\(""", RegexOption.IGNORE_CASE)

    /** Whether [text] (a cell's) is a `=HYPERLINK(…)` formula: the cell is a link. */
    fun isHyperlinkFormula(text: String?): Boolean = text != null && HYPERLINK_CALL.containsMatchIn(text)

    /**
     * The source of a `=HYPERLINK(…)` formula's first argument — where it goes, to work out (`"#Sheet2!A1"`,
     * `"#"&A1&"!A1"`) —, or null when [text] is no such formula.
     */
    fun hyperlinkLocation(text: String): String? {
        val m = HYPERLINK_CALL.find(text) ?: return null
        var depth = 0
        var inString = false
        var i = m.range.last + 1
        val start = i
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> if (c == '"') { if (i + 1 < text.length && text[i + 1] == '"') i++ else inString = false }
                c == '"' -> inString = true
                c == '(' || c == '{' -> depth++
                (c == ')' || c == '}') && depth > 0 -> depth--
                (c == ',' || c == ')') && depth == 0 -> return text.substring(start, i).trim().takeIf { it.isNotEmpty() }
            }
            i++
        }
        return null
    }
}
