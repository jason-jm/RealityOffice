package com.therealityteam.aeropages.model

/**
 * Excel's structured references: a table's parts by name rather than by address — `Sales` (its
 * data), `Sales[Amount]`, `Sales[[#Headers],[Amount]]`, `Sales[[Qty]:[Price]]`, `Sales[#Totals]`
 * and the row a formula is in, `Sales[@Qty]` (files spell it `Sales[[#This Row],[Qty]]`). A
 * reference without a table name, `[@Qty]`, is to the table the formula is in.
 */
object StructuredRefs {
    /** The special items, in the order Excel writes them. */
    enum class Item(val keyword: String) { ALL("#All"), HEADERS("#Headers"), DATA("#Data"), TOTALS("#Totals"), THIS_ROW("#This Row") }

    /**
     * One reference as written, from [start] to [end] (exclusive) of its formula: the [table] (null
     * when left out; alone, with [bare], for `=SUM(Sales)`), its special [items] (none = the data),
     * its column or span of columns ([col1] … [col2]; none = every column), and whether it used
     * Excel's `@` for this row ([at]).
     */
    class Ref(
        val table: String?, val items: Set<Item>, val col1: String?, val col2: String?,
        val start: Int, val end: Int, val at: Boolean = false, val bare: Boolean = false,
    )

    /** The cells a reference stands for. */
    class Target(val sheet: Sheet, val r1: Int, val c1: Int, val r2: Int, val c2: Int)

    private fun identStart(ch: Char) = ch.isLetter() || ch == '_' || ch == '\\'
    private fun identChar(ch: Char) = ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '\\'

    /** The reference starting at [start] — a table name and then `[`, or `[` alone — or null. */
    fun parseAt(src: String, start: Int): Ref? {
        var i = start
        var table: String? = null
        if (i < src.length && identStart(src[i])) {
            while (i < src.length && identChar(src[i])) i++
            table = src.substring(start, i)
        }
        if (i >= src.length || src[i] != '[') return null
        val body = parseBody(src, i) ?: return null
        // An external workbook's index (`[1]Sheet1!A1`) runs on into a sheet name; a reference does not.
        if (table == null && body.end < src.length && src[body.end].let { identChar(it) || it == '!' || it == '\'' }) return null
        return Ref(table, body.items, body.col1, body.col2, start, body.end, body.at)
    }

    private class Body(val items: Set<Item>, val col1: String?, val col2: String?, val at: Boolean, val end: Int)

    /** What the brackets opening at [open] hold, and where they close. */
    private fun parseBody(src: String, open: Int): Body? {
        var i = open + 1
        fun ws() { while (i < src.length && src[i] == ' ') i++ }
        ws()
        if (i >= src.length) return null
        when (src[i]) {
            // `[]`: the data.
            ']' -> return Body(emptySet(), null, null, false, i + 1)
            // `[#Totals]`
            '#' -> {
                val close = src.indexOf(']', i)
                if (close < 0) return null
                val item = keyword(src.substring(i, close).trim()) ?: return null
                return Body(setOf(item), null, null, false, close + 1)
            }
            // `[@Qty]`, `[@[Unit Price]]`, `[@[Qty]:[Price]]`, `[@]`
            '@' -> {
                i++; ws()
                if (i < src.length && src[i] == ']') return Body(setOf(Item.THIS_ROW), null, null, true, i + 1)
                if (i < src.length && src[i] == '[') {
                    val (a, afterA) = bracketed(src, i) ?: return null
                    i = afterA; ws()
                    var b: String? = null
                    if (i < src.length && src[i] == ':') {
                        i++; ws()
                        val (bb, afterB) = bracketed(src, i) ?: return null
                        b = bb; i = afterB; ws()
                    }
                    if (i >= src.length || src[i] != ']') return null
                    return Body(setOf(Item.THIS_ROW), a, b, true, i + 1)
                }
                val (name, end) = nameTo(src, i) ?: return null
                return Body(setOf(Item.THIS_ROW), name, null, true, end)
            }
            // `[[#Headers],[Qty]:[Price]]`
            '[' -> {
                val items = LinkedHashSet<Item>()
                var c1: String? = null
                var c2: String? = null
                while (true) {
                    ws()
                    // A keyword's `#` is bare; an escaped one ('#) starts a column's name.
                    var k = i + 1
                    while (k < src.length && src[k] == ' ') k++
                    val isKeyword = k < src.length && src[k] == '#'
                    val (text, after) = bracketed(src, i) ?: return null
                    i = after
                    if (isKeyword) items.add(keyword(text.trim()) ?: return null)
                    else {
                        if (c1 != null) return null // one column or one span
                        c1 = text
                        ws()
                        if (i < src.length && src[i] == ':') {
                            i++; ws()
                            val (b, afterB) = bracketed(src, i) ?: return null
                            c2 = b; i = afterB
                        }
                    }
                    ws()
                    if (i >= src.length) return null
                    when (src[i]) {
                        ',' -> i++
                        ']' -> return Body(items, c1, c2, false, i + 1)
                        else -> return null
                    }
                }
            }
            // `[Sales Amount]`
            else -> {
                val (name, end) = nameTo(src, i) ?: return null
                return Body(emptySet(), name, null, false, end)
            }
        }
    }

    /** `[text]` at [open] → the text and the index after its `]`. */
    private fun bracketed(src: String, open: Int): Pair<String, Int>? =
        if (open < src.length && src[open] == '[') nameTo(src, open + 1) else null

    /** A column name running to the unescaped `]` (a `'` escapes the character after it) → the name and the index after `]`. */
    private fun nameTo(src: String, from: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = from
        while (i < src.length) {
            val ch = src[i]
            when {
                ch == '\'' && i + 1 < src.length -> { sb.append(src[i + 1]); i += 2 }
                ch == ']' -> return sb.toString() to i + 1
                ch == '[' -> return null
                else -> { sb.append(ch); i++ }
            }
        }
        return null
    }

    private fun keyword(text: String): Item? =
        Item.entries.firstOrNull { it.keyword.equals(text, ignoreCase = true) } ?: if (text.equals("#ThisRow", ignoreCase = true)) Item.THIS_ROW else null

    /**
     * Every reference in [formula], left to right, outside its strings and quoted sheet names; with
     * [tables] (their names), a table's name standing alone (`=SUM(Sales)`) counts too.
     */
    fun find(formula: String, tables: Collection<String> = emptyList()): List<Ref> {
        val out = ArrayList<Ref>()
        val n = formula.length
        var i = 0
        while (i < n) {
            val ch = formula[i]
            when {
                ch == '"' || ch == '\'' -> {
                    // A string or a quoted sheet name; a doubled quote stands for one.
                    i++
                    while (i < n) {
                        if (formula[i] == ch) { if (i + 1 < n && formula[i + 1] == ch) i += 2 else { i++; break } } else i++
                    }
                }
                ch == '[' -> {
                    val ref = parseAt(formula, i)
                    if (ref != null) { out.add(ref); i = ref.end; continue }
                    i = skipBrackets(formula, i)
                }
                identStart(ch) && (i == 0 || !(identChar(formula[i - 1]) || formula[i - 1] == '$')) -> {
                    val ref = parseAt(formula, i)
                    if (ref != null) { out.add(ref); i = ref.end; continue }
                    val s = i
                    while (i < n && identChar(formula[i])) i++
                    if (tables.isNotEmpty() && (s == 0 || formula[s - 1] != '!')) {
                        val word = formula.substring(s, i)
                        var k = i
                        while (k < n && formula[k] == ' ') k++
                        val call = k < n && (formula[k] == '(' || formula[k] == '!')
                        if (!call && tables.any { it.equals(word, ignoreCase = true) }) out.add(Ref(word, emptySet(), null, null, s, i, bare = true))
                    }
                }
                else -> i++
            }
        }
        return out
    }

    /**
     * [formula] with [transform] applied to its code only — not to its strings (`"A1"`) nor to what
     * brackets hold (a table's column names such as `[Q1]`, `[#Totals]`), neither of which holds a
     * reference. Quoted sheet names stay with the code, as references need them.
     */
    fun outsideLiterals(formula: String, transform: (String) -> String): String {
        if (formula.indexOf('"') < 0 && formula.indexOf('[') < 0) return transform(formula)
        val out = StringBuilder()
        val n = formula.length
        var i = 0
        var seg = 0
        while (i < n) {
            when (formula[i]) {
                '\'' -> {
                    i++
                    while (i < n) { if (formula[i] == '\'') { if (i + 1 < n && formula[i + 1] == '\'') i += 2 else { i++; break } } else i++ }
                }
                '"' -> {
                    out.append(transform(formula.substring(seg, i)))
                    val s = i
                    i++
                    while (i < n) { if (formula[i] == '"') { if (i + 1 < n && formula[i + 1] == '"') i += 2 else { i++; break } } else i++ }
                    out.append(formula, s, i)
                    seg = i
                }
                '[' -> {
                    out.append(transform(formula.substring(seg, i)))
                    val s = i
                    i = skipBrackets(formula, i)
                    out.append(formula, s, i)
                    seg = i
                }
                else -> i++
            }
        }
        out.append(transform(formula.substring(seg)))
        return out.toString()
    }

    /** Past the brackets opening at [open] (nested ones included), or past the formula when they never close. */
    private fun skipBrackets(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '\'' -> i++
                '[' -> depth++
                ']' -> if (--depth == 0) return i + 1
            }
            i++
        }
        return s.length
    }

    /** [formula] with each reference [replace] gives text for put in its place (null keeps it as written). */
    fun rewrite(formula: String, tables: Collection<String> = emptyList(), replace: (Ref) -> String?): String {
        val refs = find(formula, tables)
        if (refs.isEmpty()) return formula
        val sb = StringBuilder()
        var at = 0
        for (r in refs) {
            val t = replace(r) ?: continue
            sb.append(formula, at, r.start).append(t)
            at = r.end
        }
        if (at == 0) return formula
        sb.append(formula, at, formula.length)
        return sb.toString()
    }

    /** Every table's name in [sheets]. */
    fun tableNames(sheets: Collection<Sheet>): List<String> = sheets.flatMap { s -> s.tables.map { it.name } }

    /** The table a reference names, or — naming none — the one the formula's cell ([row], [col] of [sheet]) is in. */
    fun tableOf(ref: Ref, sheet: Sheet, row: Int, col: Int, sheets: Collection<Sheet>): Pair<Sheet, SheetTable>? {
        val name = ref.table ?: return sheet.tableAt(row, col)?.let { sheet to it }
        for (s in sheets) for (t in s.tables) if (t.name.equals(name, ignoreCase = true)) return s to t
        return null
    }

    /**
     * The cells [ref] stands for, from a formula at ([row], [col]) of [sheet] (-1 outside a cell);
     * null when it names nothing there — no such table or column, no header or total row, or this
     * row outside the table — which Excel shows as #REF!.
     */
    fun resolve(ref: Ref, sheet: Sheet, row: Int, col: Int, sheets: Collection<Sheet>): Target? {
        val (s, t) = tableOf(ref, sheet, row, col, sheets) ?: return null
        val rows = rowsOf(t, ref.items, row) ?: return null
        var x1 = t.c1
        var x2 = t.c2
        if (ref.col1 != null) {
            val names = t.columnNames(s)
            fun indexOf(name: String) = names.indexOfFirst { it.equals(name.trim(), ignoreCase = true) }.takeIf { it >= 0 }
            val a = indexOf(ref.col1) ?: return null
            val b = ref.col2?.let { indexOf(it) ?: return null } ?: a
            x1 = t.c1 + minOf(a, b); x2 = t.c1 + maxOf(a, b)
        }
        return Target(s, rows.first, x1, rows.last, x2)
    }

    private fun rowsOf(t: SheetTable, items: Set<Item>, row: Int): IntRange? {
        val data = (t.dataR1..t.dataR2).takeUnless { it.isEmpty() }
        return when {
            items.isEmpty() || items == setOf(Item.DATA) -> data
            Item.THIS_ROW in items -> if (items.size == 1 && row in t.r1..t.r2) row..row else null
            Item.ALL in items -> t.r1..t.r2
            items == setOf(Item.HEADERS) -> if (t.headerRow) t.r1..t.r1 else null
            items == setOf(Item.TOTALS) -> if (t.totalsRow) t.r2..t.r2 else null
            items == setOf(Item.HEADERS, Item.DATA) -> (if (t.headerRow) t.r1 else t.dataR1)..t.dataR2
            items == setOf(Item.DATA, Item.TOTALS) -> t.dataR1..t.r2
            else -> null
        }
    }

    /** How [target] reads as an A1 reference in a formula on [from]: `B2:B9`, `'Other sheet'!B2`. */
    fun a1Of(target: Target, from: Sheet): String {
        val ref = A1.rangeRef(target.r1, target.c1, target.r2, target.c2)
        return if (target.sheet === from) ref else A1.quoteSheet(target.sheet.name) + "!" + ref
    }

    /**
     * [formula] (of the cell at [row], [col] of [sheet]) with its references as the cells they
     * stand for — for OpenDocument, which has none, and Convert to Range. One naming nothing is #REF!.
     */
    fun toA1(formula: String, sheet: Sheet, row: Int, col: Int, sheets: Collection<Sheet>): String {
        if (sheets.none { it.tables.isNotEmpty() }) return formula
        return rewrite(formula, tableNames(sheets)) { r -> resolve(r, sheet, row, col, sheets)?.let { a1Of(it, sheet) } ?: "#REF!" }
    }

    /** [formula] as Excel's files spell its references: each names its table, and `@` is `[#This Row]`. */
    fun toFileForm(formula: String, sheet: Sheet, row: Int, col: Int): String {
        if (formula.indexOf('[') < 0) return formula
        return rewrite(formula) { r ->
            if (r.bare || (r.table != null && !r.at)) return@rewrite null
            val name = r.table ?: sheet.tableAt(row, col)?.name ?: return@rewrite null
            render(name, r.items, r.col1, r.col2, ui = false)
        }
    }

    /** [formula] as Excel shows it: `[@Qty]` for a column of this row, without the name inside its own table. */
    fun toUiForm(formula: String, sheet: Sheet, row: Int, col: Int): String {
        if (formula.indexOf('[') < 0) return formula
        val own = sheet.tableAt(row, col)
        return rewrite(formula) { r ->
            if (r.items != setOf(Item.THIS_ROW) || r.at) return@rewrite null
            val mine = r.table == null || (own != null && r.table.equals(own.name, ignoreCase = true))
            render(if (mine) null else r.table, r.items, r.col1, r.col2, ui = true)
        }
    }

    /** A column name as a reference spells it: `'` before `[`, `]`, `#` and `'`. */
    fun escape(name: String): String = buildString {
        for (ch in name) { if (ch == '[' || ch == ']' || ch == '#' || ch == '\'') append('\''); append(ch) }
    }

    /** A name that needs no brackets of its own after `@` or in a file's simple form. */
    private fun plain(name: String) = name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' || it == '.' }

    /**
     * A reference written out: [table] (null leaves it out), the special [items], the column span.
     * [ui] writes this row as `@`, as Excel's formula bar shows it; otherwise it is `[#This Row]`.
     */
    fun render(table: String?, items: Set<Item>, col1: String?, col2: String?, ui: Boolean): String {
        val t = table ?: ""
        val cols = when {
            col1 == null -> null
            col2 == null -> "[" + escape(col1) + "]"
            else -> "[" + escape(col1) + "]:[" + escape(col2) + "]"
        }
        if (ui && items == setOf(Item.THIS_ROW)) return when {
            col1 == null -> "$t[@]"
            col2 == null && plain(col1) -> "$t[@" + escape(col1) + "]"
            else -> "$t[@$cols]"
        }
        val keys = items.sortedBy { it.ordinal }.map { "[" + it.keyword + "]" }
        return when {
            keys.isEmpty() && cols == null -> "$t[]"
            keys.isEmpty() -> if (col2 == null && plain(col1!!)) "$t[" + escape(col1) + "]" else "$t[$cols]"
            cols == null -> if (keys.size == 1) t + keys[0] else "$t[" + keys.joinToString(",") + "]"
            else -> "$t[" + keys.joinToString(",") + "," + cols + "]"
        }
    }

    /** [formula] with each reference that names no table naming [table] — a copy leaving the table it was written in. */
    fun qualify(formula: String, table: String): String =
        if (formula.indexOf('[') < 0) formula
        else rewrite(formula) { r -> if (r.table != null || r.bare) null else table + formula.substring(r.start, r.end) }

    /** [formula] with the table [old] called [new] wherever a reference names it. */
    fun renameTable(formula: String, old: String, new: String, tables: Collection<String>): String =
        rewrite(formula, tables) { r ->
            if (r.table == null || !r.table.equals(old, ignoreCase = true)) null else new + formula.substring(r.start + r.table.length, r.end)
        }

    /**
     * [formula] (of the cell at [row], [col] of [sheet]) with column [old] of [table] called [new]
     * wherever a reference to that table names it.
     */
    fun renameColumn(formula: String, sheet: Sheet, row: Int, col: Int, table: SheetTable, old: String, new: String): String {
        if (formula.indexOf('[') < 0) return formula
        val own = sheet.tableAt(row, col)
        return rewrite(formula) { r ->
            val ofTable = if (r.table == null) own === table else r.table.equals(table.name, ignoreCase = true)
            if (!ofTable) return@rewrite null
            val a = r.col1?.takeIf { it.trim().equals(old, ignoreCase = true) }
            val b = r.col2?.takeIf { it.trim().equals(old, ignoreCase = true) }
            if (a == null && b == null) return@rewrite null
            render(r.table, r.items, if (a != null) new else r.col1, if (b != null) new else r.col2, ui = r.at)
        }
    }
}
