package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.NumberSymbols
import com.therealityteam.aeropages.model.TextAlign
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Renders an evaluated value with a cell's number format (grid display, PDF export and the cell-format preview). */
object NumberFormatting {

    /** 1970-01-01 as an Excel serial day (day 0 = 1899-12-30). */
    private const val UNIX_EPOCH_SERIAL = 25569.0

    /**
     * What [cell] shows for its evaluated value [raw]: its format code when it has one (and that
     * section's colour), else its [NumberFormat] on numbers. Formula errors, TRUE / FALSE and
     * blanks show as they are; a text format (`@`) keeps what was typed, zeros and all. Numbers come out in [symbols];
     * the plain Date and Date-time formats as [dates]' short date shows them (15.10.2026 in German, 10/15/2026 in
     * English — Excel's built-in date follows the system), or ISO (2026-10-15) when null, as files and CSV want them.
     */
    fun shown(raw: String, cell: Cell?, symbols: NumberSymbols = NumberSymbols.CANONICAL, dates: FormatCode.Lang? = null): FormatCode.Shown {
        if (cell == null || raw.isEmpty()) return FormatCode.Shown(raw)
        if (raw == "TRUE" || raw == "FALSE") return FormatCode.Shown(raw)
        val code = cell.formatCode
        if (code != null) {
            if (cell.text.startsWith("=") && raw.startsWith("#")) return FormatCode.Shown(raw)
            // Only the Text format itself (`@`) keeps a number as text; under another text-only code (`@" kg"`) a number shows as General, as in Excel.
            val n = if (code == "@") null else raw.toDoubleOrNull()?.takeIf { it.isFinite() }
            return if (n != null) FormatCode.format(n, code, symbols = symbols) else FormatCode.formatText(raw, code)
        }
        // General: a number in the reader's decimal symbol (1,5); text as it is.
        if (cell.numberFormat == NumberFormat.GENERAL) return FormatCode.Shown(if (raw.toDoubleOrNull() != null) symbols.local(raw) else raw)
        val n = raw.toDoubleOrNull() ?: return FormatCode.Shown(raw)
        return when (cell.numberFormat) {
            // A date's dots and slashes are no decimal symbols: never swapped.
            NumberFormat.DATE, NumberFormat.DATE_TIME ->
                if (dates == null) FormatCode.Shown(format(n, cell.numberFormat, cell.decimals)) else FormatCode.format(n, dateCode(cell.numberFormat, dates), dates, symbols)
            NumberFormat.TIME -> FormatCode.Shown(format(n, cell.numberFormat, cell.decimals))
            else -> FormatCode.Shown(symbols.local(format(n, cell.numberFormat, cell.decimals)))
        }
    }

    /** The Excel code of the plain Date (built-in 14) or Date-time (22) in [lang]: its short date, the time after it. */
    fun dateCode(fmt: NumberFormat, lang: FormatCode.Lang): String =
        com.therealityteam.aeropages.model.FieldDates.shortDate(lang).lowercase(Locale.ROOT) + if (fmt == NumberFormat.DATE_TIME) " h:mm" else ""

    /**
     * A status bar figure [v] (Excel's Average, Min, Max, Sum) as Excel shows it: in [cell]'s number format — the
     * first number of the selection — or General when that has none.
     */
    fun figure(v: Double, cell: Cell?, symbols: NumberSymbols = NumberSymbols.CANONICAL, dates: FormatCode.Lang? = null): String {
        if (cell == null || (cell.formatCode == null && cell.numberFormat == NumberFormat.GENERAL)) return symbols.local(FormatCode.general(v))
        return shown(v.toString(), cell, symbols, dates).text
    }

    /**
     * Where General alignment puts [cell]'s evaluated value [raw]: numbers (dates and times too)
     * right, TRUE / FALSE and formula errors centred, text — and anything in a Text (`@`) cell — left.
     */
    fun generalAlign(raw: String, cell: Cell?): TextAlign {
        if (raw.isEmpty()) return TextAlign.LEFT
        if (cell?.formatCode == "@") return TextAlign.LEFT
        if (raw == "TRUE" || raw == "FALSE") return TextAlign.CENTER
        if (cell != null && cell.text.startsWith("=") && raw.startsWith("#")) return TextAlign.CENTER
        return if (raw.toDoubleOrNull()?.isFinite() == true) TextAlign.RIGHT else TextAlign.LEFT
    }

    /**
     * A General number [v] shortened until [fits] accepts it, as Excel's General shows a number in a
     * narrow column: fewer decimals while the whole part still shows, then E notation with fewer
     * digits; null when not even that fits (the cell then shows ####).
     */
    fun fitGeneral(v: Double, symbols: NumberSymbols = NumberSymbols.CANONICAL, fitsIt: (String) -> Boolean): String? {
        val fits = { s: String -> fitsIt(symbols.local(s)) }
        return fitCanonical(v, fits)?.let { symbols.local(it) }
    }

    private fun fitCanonical(v: Double, fits: (String) -> Boolean): String? {
        val g = FormatCode.general(v)
        if (fits(g)) return g
        val a = kotlin.math.abs(v)
        if (a >= 1e-4 && a < 1e11) {
            val whole = if (a >= 1) kotlin.math.floor(kotlin.math.log10(a)).toInt() + 1 else 1
            for (sig in 10 downTo whole) {
                val s = java.math.BigDecimal(v).round(java.math.MathContext(sig)).stripTrailingZeros().toPlainString()
                if (fits(s)) return s
            }
        }
        for (digits in 5 downTo 0) {
            val s = String.format(Locale.ROOT, "%.${digits}E", v)
            if (fits(s)) return s
        }
        return null
    }

    /** The Excel code a plain format stands for, with its decimals — where Format Cells → Custom starts from. */
    fun codeOf(fmt: NumberFormat, decimals: Int = -1): String {
        fun frac(default: Int): String { val d = if (decimals >= 0) decimals else default; return if (d > 0) "." + "0".repeat(d) else "" }
        return when (fmt) {
            NumberFormat.GENERAL -> "General"
            NumberFormat.NUMBER2 -> "0" + frac(2)
            NumberFormat.CURRENCY_CNY -> "\"¥\"#,##0" + frac(2)
            NumberFormat.CURRENCY_USD -> "\"$\"#,##0" + frac(2)
            NumberFormat.PERCENT -> "0" + frac(1) + "%"
            NumberFormat.THOUSANDS -> "#,##0" + frac(0)
            NumberFormat.SCIENTIFIC -> "0" + frac(2) + "E+00"
            NumberFormat.DATE -> "yyyy-mm-dd"
            NumberFormat.TIME -> "hh:mm"
            NumberFormat.DATE_TIME -> "yyyy-mm-dd hh:mm"
        }
    }

    private fun money(symbol: String, n: Double, places: Int): String {
        val body = String.format(Locale.ROOT, "%,.${places}f", kotlin.math.abs(n))
        return (if (n < 0 && body.any { it in '1'..'9' }) "-" else "") + symbol + body
    }

    fun format(n: Double, fmt: NumberFormat, decimals: Int = -1): String {
        fun d(def: Int) = if (decimals >= 0) decimals else def
        fun serialMillis() = ((n - UNIX_EPOCH_SERIAL) * 86_400_000.0).toLong()
        fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault())
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(serialMillis()))
        return when (fmt) {
            NumberFormat.GENERAL -> n.toString()
            NumberFormat.NUMBER2 -> String.format(Locale.ROOT, "%.${d(2)}f", n)
            // The minus goes before the symbol, as Excel writes -$5 (not $-5).
            NumberFormat.CURRENCY_CNY -> money("¥", n, d(2))
            NumberFormat.CURRENCY_USD -> money("$", n, d(2))
            NumberFormat.PERCENT -> String.format(Locale.ROOT, "%.${d(1)}f%%", n * 100)
            NumberFormat.THOUSANDS -> String.format(Locale.ROOT, "%,.${d(0)}f", n)
            NumberFormat.SCIENTIFIC -> String.format(Locale.ROOT, "%.${d(2)}E", n)
            NumberFormat.DATE -> utc("yyyy-MM-dd")
            // h:mm, as Excel's built-in time (id 20) shows it: 9:30, 0:00 — to the nearest second, the seconds then dropped.
            NumberFormat.TIME -> {
                val secs = Math.round(((n % 1.0) + 1.0) % 1.0 * 86_400).toInt() % 86_400
                String.format(Locale.ROOT, "%d:%02d", secs / 3600, secs / 60 % 60)
            }
            NumberFormat.DATE_TIME -> utc("yyyy-MM-dd HH:mm")
        }
    }
}
