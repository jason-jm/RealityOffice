package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberSymbols
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableCell
import com.therealityteam.aeropages.model.TableFormulas
import com.therealityteam.aeropages.model.TableRow
import com.therealityteam.aeropages.model.TextRun
import java.util.Locale

/** What separates a table's cells as text (Word's Convert Text to Table / Table to Text): tabs, commas, spaces, paragraphs, or a character. */
enum class TableSeparator { TAB, COMMA, SPACE, PARAGRAPH, OTHER }

/** How Sort orders a column (Word's Type): as text in the language's order (Chinese by Pinyin), as numbers, as dates. */
enum class SortKind { TEXT, NUMBER, DATE }

/** One key of Word's Sort Text: a paragraph's whole text ([field] -1) or its [field]th field (from 0), compared as [kind]. */
data class TextSortKey(val field: Int, val kind: SortKind = SortKind.TEXT, val descending: Boolean = false)

/**
 * Text and tables, one into the other, and a table's rows sorted — Word's Table Tools ▸ Data. Each cell keeps its
 * text's runs and their look.
 */
object TableText {
    /** Where [text]'s fields are, split at [sep] ([other] for [TableSeparator.OTHER]); a comma is either width's. */
    fun fields(text: String, sep: TableSeparator, other: Char? = null): List<IntRange> {
        val isSep: (Char) -> Boolean = when (sep) {
            TableSeparator.TAB -> { c -> c == '\t' }
            TableSeparator.COMMA -> { c -> c == ',' || c == '，' }
            TableSeparator.SPACE -> { c -> c == ' ' || c == '　' }
            TableSeparator.OTHER -> { c -> c == other }
            TableSeparator.PARAGRAPH -> { _ -> false }
        }
        val out = ArrayList<IntRange>()
        var start = 0
        for (i in text.indices) if (isSep(text[i])) { out.add(start until i); start = i + 1 }
        out.add(start until text.length)
        return out
    }

    /** How many columns [paragraphs] make: their most fields (paragraph separators: a cell each, [columns] across). */
    fun columnsFor(paragraphs: List<Paragraph>, sep: TableSeparator, other: Char? = null): Int =
        if (sep == TableSeparator.PARAGRAPH) 1 else paragraphs.maxOfOrNull { fields(it.plainText(), sep, other).size } ?: 1

    /** A copy of [p] holding only its characters [range] (its runs' looks with them). */
    fun slice(p: Paragraph, range: IntRange): Paragraph {
        val copy = p.copyOf()
        val len = ParagraphOps.length(copy)
        val a = range.first.coerceIn(0, len)
        val b = (range.last + 1).coerceIn(a, len)
        if (b < len) ParagraphOps.replace(copy, b, len, "", ParagraphOps.formatAt(copy, b))
        if (a > 0) ParagraphOps.replace(copy, 0, a, "", ParagraphOps.formatAt(copy, 0))
        copy.bookmarks.clear()
        copy.format.sectionBreak = null
        return copy
    }

    /**
     * [paragraphs] as a table [columns] wide: each paragraph a row of its fields (more fields than columns run into the
     * last cell's text), or — paragraph separators — each paragraph a cell, row after row.
     */
    fun toTable(paragraphs: List<Paragraph>, sep: TableSeparator, other: Char?, columns: Int): Table {
        val cols = columns.coerceAtLeast(1)
        val rows = ArrayList<TableRow>()
        if (sep == TableSeparator.PARAGRAPH) {
            paragraphs.chunked(cols).forEach { chunk ->
                rows.add(TableRow(MutableList(cols) { i -> TableCell(mutableListOf(chunk.getOrNull(i)?.let { slice(it, 0 until ParagraphOps.length(it)) } ?: Paragraph.of(""))) }))
            }
        } else {
            for (p in paragraphs) {
                val text = p.plainText()
                val f = fields(text, sep, other)
                // Fields past the last column stay in it, separators and all.
                val ranges = if (f.size <= cols) f else f.take(cols - 1) + listOf(f[cols - 1].first until text.length)
                rows.add(TableRow(MutableList(cols) { i -> TableCell(mutableListOf(ranges.getOrNull(i)?.let { slice(p, it) } ?: Paragraph.of(""))) }))
            }
        }
        if (rows.isEmpty()) rows.add(TableRow(MutableList(cols) { TableCell() }))
        return Table(rows, columnWidths = MutableList(cols) { 1f })
    }

    /** The text [sep] puts between cells. */
    private fun separatorText(sep: TableSeparator, other: Char?) = when (sep) {
        TableSeparator.TAB -> "\t"; TableSeparator.COMMA -> ","; TableSeparator.SPACE -> " "; TableSeparator.OTHER -> (other ?: '-').toString(); TableSeparator.PARAGRAPH -> ""
    }

    /**
     * [table] as paragraphs: a row each, its cells joined by [sep] (a cell's own further paragraphs go on as paragraphs),
     * or — paragraph separators — every cell's paragraphs one after another; covered cells of a merge give nothing.
     */
    fun toText(table: Table, sep: TableSeparator, other: Char?): List<Paragraph> {
        val out = ArrayList<Paragraph>()
        val between = separatorText(sep, other)
        for (row in table.rows) {
            val cells = row.cells.filterNot { it.vMerged }
            if (sep == TableSeparator.PARAGRAPH) { cells.forEach { c -> c.content.forEach { out.add(slice(it, 0 until ParagraphOps.length(it))) } }; continue }
            var line: Paragraph? = null
            cells.forEachIndexed { ci, c ->
                c.content.forEachIndexed { pi, para ->
                    val piece = slice(para, 0 until ParagraphOps.length(para))
                    val cur = line
                    if (cur == null || pi > 0) {
                        if (cur != null) out.add(cur)
                        line = piece
                        if (pi == 0 && ci > 0) line!!.runs.add(0, TextRun(between, ParagraphOps.formatAt(piece, 0)))
                    } else {
                        cur.runs.add(TextRun(between, ParagraphOps.formatAt(cur, (ParagraphOps.length(cur) - 1).coerceAtLeast(0))))
                        cur.runs.addAll(piece.runs)
                    }
                }
            }
            line?.let { out.add(it) }
        }
        out.forEach { p -> p.runs.removeAll { it.text.isEmpty() && p.runs.size > 1 } }
        return out
    }

    /** Whether [table] can be sorted: no cell spans rows (Word will not sort those either). */
    fun sortable(table: Table): Boolean = table.rows.none { r -> r.cells.any { it.rowSpan > 1 || it.vMerged } }

    /**
     * [table]'s rows — after its header rows ([header]: at least the first) — in order of grid column [column]'s text
     * as [kind] says, [descending] or not; ties keep their order, empty cells go last. False when it cannot be sorted.
     */
    fun sort(table: Table, column: Int, kind: SortKind, descending: Boolean, header: Boolean, collator: java.text.Collator = java.text.Collator.getInstance()): Boolean {
        if (!sortable(table)) return false
        val lead = maxOf(if (header) 1 else 0, table.rows.takeWhile { it.header }.size).coerceAtMost(table.rows.size)
        val body = table.rows.subList(lead, table.rows.size)
        fun keyText(r: TableRow): String {
            var g = 0
            for (c in r.cells) { val span = c.gridSpan.coerceAtLeast(1); if (column < g + span) return c.content.joinToString(" ") { it.plainText() }.trim(); g += span }
            return ""
        }
        val keyed = body.map { r -> r to keyText(r) }
        val (empty, filled) = keyed.partition { it.second.isEmpty() }
        val cmp: Comparator<Pair<TableRow, String>> = when (kind) {
            SortKind.TEXT -> Comparator { a, b -> collator.compare(a.second, b.second) }
            SortKind.NUMBER -> compareBy(nullsLast()) { number(it.second) }
            SortKind.DATE -> compareBy(nullsLast()) { date(it.second) }
        }
        val sorted = filled.sortedWith(if (descending) cmp.reversed() else cmp) + empty
        val rows = sorted.map { it.first }
        body.clear(); body.addAll(rows)
        return true
    }

    /**
     * Word's Sort Text: [paragraphs] in the order [keys] give — each key a paragraph's whole text ([TextSortKey.field] -1)
     * or one of its fields split at [sep], compared as text (in the language's order, with or without [caseSensitive]),
     * as numbers or as dates, ascending or descending; ties keep their order. A paragraph with nothing to compare (empty,
     * or no number, no date) comes first ascending and last descending, as Word sorts blank paragraphs; with [header]
     * the first stays where it is.
     */
    fun sortParagraphs(paragraphs: List<Paragraph>, keys: List<TextSortKey>, sep: TableSeparator = TableSeparator.TAB, other: Char? = null,
                       header: Boolean = false, caseSensitive: Boolean = false, collator: java.text.Collator = java.text.Collator.getInstance()): List<Paragraph> {
        if (paragraphs.size < 2 || keys.isEmpty()) return paragraphs
        collator.strength = if (caseSensitive) java.text.Collator.TERTIARY else java.text.Collator.SECONDARY
        val lead = if (header) 1 else 0
        val body = paragraphs.drop(lead)
        fun keyText(p: Paragraph, k: TextSortKey): String {
            val t = p.plainText()
            if (k.field < 0) return t.trim()
            return fields(t, sep, other).getOrNull(k.field)?.let { t.substring(it.first, it.last + 1) }?.trim().orEmpty()
        }
        fun compare(a: Paragraph, b: Paragraph, k: TextSortKey): Int {
            val x = keyText(a, k); val y = keyText(b, k)
            val c = when (k.kind) {
                SortKind.TEXT -> if (x.isEmpty() || y.isEmpty()) compareValues(x.isNotEmpty(), y.isNotEmpty()) else collator.compare(x, y)
                SortKind.NUMBER -> compareValues(number(x), number(y))
                SortKind.DATE -> compareValues(date(x), date(y))
            }
            return if (k.descending) -c else c
        }
        val sorted = body.sortedWith { a, b -> keys.asSequence().map { compare(a, b, it) }.firstOrNull { it != 0 } ?: 0 }
        return paragraphs.take(lead) + sorted
    }

    /**
     * The number [s] starts with — its sign, digits, grouping and decimal symbols as the language writes them (¥1,234.5,
     * -3, 12% in English; 1.234,5 € in German) — or null.
     */
    fun number(s: String, symbols: NumberSymbols = NumberSymbols.current()): Double? = TableFormulas.numberIn(s, symbols)

    /**
     * The date [s] reads as — 2026-10-16, 2026/10/16, 2026.10.16, 2026年10月16日, and 10/16/2026 or, where the language
     * puts the day first ([dayFirst]), 16.10.2026 and 16/10/2026 — as yyyymmdd, or null.
     */
    fun date(s: String, dayFirst: Boolean = EntryParser.dayFirst(FormatCode.Lang.of(Locale.getDefault()))): Long? {
        Regex("""(\d{4})\s*[-/.年]\s*(\d{1,2})\s*[-/.月]\s*(\d{1,2})""").find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return y.toLong() * 10_000 + mo.toLong() * 100 + d.toLong()
        }
        Regex("""(\d{1,2})([/.-])(\d{1,2})\2(\d{4})""").find(s)?.let { m ->
            val (a, _, b, y) = m.destructured
            val (mo, d) = if (dayFirst) b to a else a to b
            return y.toLong() * 10_000 + mo.toLong() * 100 + d.toLong()
        }
        return null
    }
}
