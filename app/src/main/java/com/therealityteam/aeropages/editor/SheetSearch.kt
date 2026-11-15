package com.therealityteam.aeropages.editor

/** Where Excel's Find looks in a cell: what is typed (formulas), what it shows (values) or its comment. */
enum class LookIn { FORMULAS, VALUES, COMMENTS }

/** Excel's Find and Replace options: this sheet or the whole workbook, by rows or by columns, where to look, case, whole cells. */
data class SheetFindOptions(
    val workbook: Boolean = false,
    val byColumns: Boolean = false,
    val lookIn: LookIn = LookIn.FORMULAS,
    val matchCase: Boolean = false,
    val wholeCell: Boolean = false,
)

/** A cell Find found: sheet [sheet] (its index), row and column. */
data class CellHit(val sheet: Int, val row: Int, val col: Int)

/**
 * Excel's Find: its query's wildcards — `?` one character, `*` any run of them, `~` before either (or itself) for that
 * character — matched anywhere in a cell's text, or, matching entire cell contents, against the whole of it.
 */
object SheetSearch {
    fun pattern(query: String, o: SheetFindOptions): Regex? {
        if (query.isEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        while (i < query.length) {
            val c = query[i]
            when {
                c == '~' && i + 1 < query.length && query[i + 1] in "*?~" -> { sb.append(Regex.escape(query[i + 1].toString())); i += 2; continue }
                c == '*' -> sb.append("(?s:.*?)")
                c == '?' -> sb.append("(?s:.)")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        val body = if (o.wholeCell) "^(?:$sb)$" else sb.toString()
        return if (o.matchCase) Regex(body) else Regex(body, RegexOption.IGNORE_CASE)
    }

    /** Whether [text] holds a match ([o]'s whole cell: is one). A `*` alone matches any text that is there. */
    fun matches(regex: Regex, text: String): Boolean = text.isNotEmpty() && regex.containsMatchIn(text)
}
