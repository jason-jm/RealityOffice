package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.NumberSymbols
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.floor

/**
 * Excel's reading of what is typed into a cell, applied when the entry is committed: `15%`,
 * `$1,234.50`, `¥80`, `1,000`, `(42)`, `1.5E3`, dates (`2026-09-29`, `2026/9/29`, `9/29/2026` in
 * English, `2026年9月29日`, `9月29日`, `29-Sep-2026`, `Sep 29, 2026`), times (`9:30`, `9:30:15`,
 * `9:30 PM`, `25:30`, `9时30分`) and a date with a time become numbers — with the number format they
 * were typed in, for a cell that has none — so formulas count them. Plain numbers stay as typed:
 * `00123` keeps its zeros and an 18-digit ID is not rounded into E notation, as Excel would.
 *
 * In a language that writes numbers otherwise ([NumberSymbols]: 1.234,5 in German, 1 234,5 in French) an entry in that
 * writing is read as Excel there reads it — `1,5`, `1.234,5`, `12,5 %`, `1.234,50 €` — and dates go day before month
 * (`8.10.2026`, `08/10/2026`, `1.5` is 1 May); the cell keeps the number in the standard form. (Excel keeps an entry
 * like `3.14` in German as text; a cell here cannot tell such text from a number, so it stays 3.14.)
 */
object EntryParser {

    /** What an entry becomes: the number the cell holds, and the format it takes when the cell has none. */
    data class Entry(val value: String, val format: NumberFormat = NumberFormat.GENERAL, val decimals: Int = -1, val code: String? = null)

    private const val NUM = """(\d{1,3}(?:,\d{3})+|\d*)(?:\.(\d+))?"""
    private val PERCENT = Regex("""^([+-]?)$NUM\s*%$""")
    private val CURRENCY = Regex("""^([+-]?)([$¥￥€£])\s*([+-]?)$NUM$""")
    private val CURRENCY_PAREN = Regex("""^\(([$¥￥€£])\s*$NUM\)$""")
    private val GROUPED = Regex("""^([+-]?)(\d{1,3}(?:,\d{3})+)(?:\.(\d+))?$""")
    private val PAREN = Regex("""^\($NUM\)$""")
    private val SCIENTIFIC = Regex("""^[+-]?\d+(?:\.\d+)?[eE][+-]?\d+$""")

    private val ISO = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val YMD = Regex("""^(\d{4})([/.])(\d{1,2})\2(\d{1,2})$""")
    private val MDY = Regex("""^(\d{1,2})/(\d{1,2})/(\d{2}|\d{4})$""")
    private val MD = Regex("""^(\d{1,2})[/-](\d{1,2})$""")
    private val CJK_YMD = Regex("""^(\d{4})年(\d{1,2})月(\d{1,2})日$""")
    private val CJK_MD = Regex("""^(\d{1,2})月(\d{1,2})日$""")
    private val CJK_YM = Regex("""^(\d{4})年(\d{1,2})月$""")
    private val D_MON_Y = Regex("""^(\d{1,2})[- ]([A-Za-z]{3,9})\.?(?:[- ,]+(\d{2}|\d{4}))?$""")
    private val MON_D_Y = Regex("""^([A-Za-z]{3,9})\.? (\d{1,2})(?:,? (\d{4}))?$""")
    private val TIME = Regex("""^(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\s*([AaPp])[Mm]?)?$""")
    private val CJK_TIME = Regex("""^(\d{1,2})时(\d{1,2})分(?:(\d{1,2})秒)?$""")

    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

    /** Day 0 of Excel's serial dates. */
    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    /** [text] as Excel reads an entry; null when it stays as typed (text, a plain number, a formula). */
    fun parse(
        text: String, lang: FormatCode.Lang = FormatCode.Lang.current(), today: LocalDate = LocalDate.now(),
        symbols: NumberSymbols = NumberSymbols.CANONICAL,
    ): Entry? {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("=")) return null
        if (t.equals("TRUE", ignoreCase = true) || t.equals("FALSE", ignoreCase = true)) {
            val upper = t.uppercase()
            return if (upper == text) null else Entry(upper)
        }
        if (!symbols.canonical) {
            // A date the language's way first where its dot is no decimal point (1.5 is 1 May in German), then a number
            // in its own writing (1,5 · 1.234,5 · 12,5 % · 1.234,50 €), read as the standard form would be.
            if (symbols.decimal != '.') localDate(t, lang, today)?.let { return it }
            canonicalNumber(t, symbols)?.let { c -> return number(c) ?: Entry(plain(BigDecimal(c.replace(",", "")))) }
        }
        return number(t) ?: dateTime(t, lang, today)
    }

    /** [t] in the standard writing as a number entry (percent, currency, grouping, parentheses, E notation); null otherwise. */
    private fun number(t: String): Entry? {
        PERCENT.matchEntire(t)?.let { m ->
            val n = number(m.groupValues[2], m.groupValues[3]) ?: return null
            val v = n.movePointLeft(2).let { if (m.groupValues[1] == "-") it.negate() else it }
            return Entry(plain(v), NumberFormat.PERCENT, m.groupValues[3].length)
        }
        CURRENCY.matchEntire(t)?.let { m ->
            if (m.groupValues[1].isNotEmpty() && m.groupValues[3].isNotEmpty()) return null
            val n = number(m.groupValues[4], m.groupValues[5]) ?: return null
            val negative = m.groupValues[1] == "-" || m.groupValues[3] == "-"
            return currency(m.groupValues[2], if (negative) n.negate() else n, m.groupValues[5].isNotEmpty())
        }
        CURRENCY_PAREN.matchEntire(t)?.let { m ->
            val n = number(m.groupValues[2], m.groupValues[3]) ?: return null
            return currency(m.groupValues[1], n.negate(), m.groupValues[3].isNotEmpty())
        }
        GROUPED.matchEntire(t)?.let { m ->
            val n = number(m.groupValues[2], m.groupValues[3]) ?: return null
            val v = if (m.groupValues[1] == "-") n.negate() else n
            return Entry(plain(v), NumberFormat.THOUSANDS, if (m.groupValues[3].isEmpty()) -1 else 2)
        }
        PAREN.matchEntire(t)?.let { m ->
            val n = number(m.groupValues[1], m.groupValues[2]) ?: return null
            return Entry(plain(n.negate()))
        }
        if (SCIENTIFIC.matches(t)) return Entry(t, NumberFormat.SCIENTIFIC)
        return null
    }

    private val CURRENCIES = listOf("R$", "Rp", "zł", "€", "£", "$", "¥", "￥", "₩", "₽", "₺", "₫", "฿", "₹")
    private val EXPONENT = Regex("""^[eE][+-]?\d+$""")

    /**
     * [t] — a number written with [sym]'s symbols, with what may stand round a number (a sign, a currency before or after
     * it, parentheses, a percent, an exponent) — in the standard writing (1.234,50 € → €1,234.50); null when [t] is not
     * such a number or has none of [sym]'s symbols (then it reads as it is).
     */
    private fun canonicalNumber(t: String, sym: NumberSymbols): String? {
        var s = t
        CURRENCIES.firstOrNull { s.endsWith(it) && s.length > it.length }?.let { c -> s = c + s.removeSuffix(c).trimEnd() }
        fun isGroup(ch: Char) = ch == sym.group || (sym.group == '\u00A0' && (ch == ' ' || ch == '\u202F'))
        val start = s.indexOfFirst { it.isDigit() || it == sym.decimal }
        if (start < 0) return null
        var end = start
        while (end < s.length && (s[end].isDigit() || s[end] == sym.decimal || isGroup(s[end]))) end++
        while (end > start && isGroup(s[end - 1])) end--
        val run = s.substring(start, end)
        if (run.none { it == sym.decimal || isGroup(it) }) return null
        val head = CURRENCIES.fold(s.substring(0, start)) { h, c -> h.replace(c, "") }
        if (head.any { it !in "+-( \u00A0" }) return null
        val tail = s.substring(end).trim()
        if (tail.isNotEmpty() && tail != "%" && tail != ")" && !EXPONENT.matches(tail)) return null
        val parts = run.split(sym.decimal)
        if (parts.size > 2 || parts.getOrElse(1) { "" }.any { !it.isDigit() }) return null
        val groups = parts[0].split { isGroup(it) }
        if (groups.any { g -> g.any { !it.isDigit() } }) return null
        if (groups.size > 1 && (groups[0].length !in 1..3 || groups.drop(1).any { it.length != 3 })) return null
        return s.substring(0, start) + groups.joinToString(",") + (if (parts.size == 2) "." + parts[1] else "") + s.substring(end)
    }

    private fun String.split(isSep: (Char) -> Boolean): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder()
        for (ch in this) if (isSep(ch)) { out.add(sb.toString()); sb.clear() } else sb.append(ch)
        out.add(sb.toString())
        return out
    }

    // --- dates the language's way: day before month --------------------------------------------

    private val DMY = Regex("""^(\d{1,2})([./-])(\d{1,2})\2(\d{2}|\d{4})$""")
    private val DM = Regex("""^(\d{1,2})([./-])(\d{1,2})\.?$""")

    /** The languages whose dates go day, month, year (8.10.2026, 08/10/2026, 8-10-2026). */
    private val DMY_LANGS = setOf(FormatCode.Lang.DE, FormatCode.Lang.FR, FormatCode.Lang.ES, FormatCode.Lang.IT, FormatCode.Lang.PT,
        FormatCode.Lang.RU, FormatCode.Lang.NL, FormatCode.Lang.PL, FormatCode.Lang.TR, FormatCode.Lang.VI, FormatCode.Lang.TH,
        FormatCode.Lang.ID, FormatCode.Lang.HI)

    /** A typed date the way [lang] writes one (day first), with a time after it too; null when not such a date. */
    /** Whether [lang] writes the day before the month (8.10.2026, 08/10/2026), as its Excel and Word read dates. */
    fun dayFirst(lang: FormatCode.Lang): Boolean = lang in DMY_LANGS

    private fun localDate(t: String, lang: FormatCode.Lang, today: LocalDate): Entry? {
        if (lang !in DMY_LANGS) return null
        DMY.matchEntire(t)?.let { m ->
            val sep = m.groupValues[2]
            return valid(year(m.groupValues[4]), m.groupValues[3].toInt(), m.groupValues[1].toInt())?.let { entry(it, 0.0, "dd${sep}mm${sep}yyyy") }
        }
        DM.matchEntire(t)?.let { m ->
            val sep = m.groupValues[2]
            return valid(today.year, m.groupValues[3].toInt(), m.groupValues[1].toInt())?.let { entry(it, 0.0, if (sep == ".") "dd. mmm" else "d-mmm") }
        }
        return null
    }

    private fun currency(symbol: String, v: BigDecimal, decimals: Boolean): Entry = when (symbol) {
        "$" -> Entry(plain(v), NumberFormat.CURRENCY_USD, if (decimals) 2 else 0)
        "¥", "￥" -> Entry(plain(v), NumberFormat.CURRENCY_CNY, if (decimals) 2 else 0)
        else -> Entry(plain(v), NumberFormat.GENERAL, -1, "\"$symbol\"#,##0" + if (decimals) ".00" else "")
    }

    /** A typed number: its whole part (commas in threes, or none) and decimals; null without a digit. */
    private fun number(whole: String, frac: String): BigDecimal? {
        if (whole.isEmpty() && frac.isEmpty()) return null
        return BigDecimal((whole.replace(",", "").ifEmpty { "0" }) + (if (frac.isEmpty()) "" else ".$frac"))
    }

    /** A number as a cell holds it: no exponent, no trailing zeros. */
    private fun plain(v: BigDecimal): String = v.stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }

    // --- dates and times ----------------------------------------------------------------------

    private fun dateTime(t: String, lang: FormatCode.Lang, today: LocalDate): Entry? {
        if (lang in DMY_LANGS) (DMY.matchEntire(t) ?: DM.matchEntire(t)?.takeIf { it.groupValues[2] != "." })?.let { return localDate(t, lang, today) }
        date(t, lang, today)?.let { (d, code) -> return entry(d, 0.0, code) }
        time(t)?.let { (f, code) -> return Entry(plain(BigDecimal(f).round(java.math.MathContext(15))), NumberFormat.GENERAL, -1, code) }
        // A date and a time: `2026-09-29 9:30`, `2026/9/29 9:30:15`, `Sep 29, 2026 9:30 PM`.
        for (space in t.indices) {
            if (t[space] != ' ') continue
            val (d, dateCode) = date(t.substring(0, space).trim(), lang, today) ?: continue
            val (f, timeCode) = time(t.substring(space + 1).trim()) ?: continue
            if (f >= 1.0) return null
            return entry(d, f, if (dateCode == null && timeCode == "h:mm") null else "${dateCode ?: "yyyy-mm-dd"} $timeCode", dateTime = true)
        }
        return null
    }

    private fun entry(d: LocalDate, fraction: Double, code: String?, dateTime: Boolean = false): Entry {
        val serial = ChronoUnit.DAYS.between(EPOCH, d).toDouble() + fraction
        val value = plain(BigDecimal(serial).round(java.math.MathContext(15)))
        return when {
            code != null -> Entry(value, NumberFormat.GENERAL, -1, code)
            dateTime -> Entry(value, NumberFormat.DATE_TIME)
            else -> Entry(value, NumberFormat.DATE)
        }
    }

    /** A typed date and the code it shows in (null = the plain yyyy-mm-dd date); null when not a date. */
    private fun date(t: String, lang: FormatCode.Lang, today: LocalDate): Pair<LocalDate, String?>? {
        ISO.matchEntire(t)?.let { m -> return valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { it to null } }
        YMD.matchEntire(t)?.let { m ->
            val sep = m.groupValues[2]
            return valid(m.groupValues[1].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())?.let { it to "yyyy${sep}m${sep}d" }
        }
        CJK_YMD.matchEntire(t)?.let { m -> return valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let { it to "yyyy\"年\"m\"月\"d\"日\"" } }
        CJK_MD.matchEntire(t)?.let { m -> return valid(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { it to "m\"月\"d\"日\"" } }
        CJK_YM.matchEntire(t)?.let { m -> return valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 1)?.let { it to "yyyy\"年\"m\"月\"" } }
        if (lang == FormatCode.Lang.EN) MDY.matchEntire(t)?.let { m ->
            return valid(year(m.groupValues[3]), m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { it to "m/d/yyyy" }
        }
        MD.matchEntire(t)?.let { m ->
            // Excel reads 9/29 (and, yes, 1/2) as a date this year.
            return valid(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { it to if (lang == FormatCode.Lang.EN) "d-mmm" else "m\"月\"d\"日\"" }
        }
        D_MON_Y.matchEntire(t)?.let { m ->
            val month = month(m.groupValues[2]) ?: return null
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { year(it) }
            return valid(y ?: today.year, month, m.groupValues[1].toInt())?.let { it to if (y == null) "d-mmm" else "d-mmm-yy" }
        }
        MON_D_Y.matchEntire(t)?.let { m ->
            val month = month(m.groupValues[1]) ?: return null
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            return valid(y ?: today.year, month, m.groupValues[2].toInt())?.let { it to if (y == null) "d-mmm" else "d-mmm-yy" }
        }
        return null
    }

    /** Two-digit years as Excel reads them: 00–29 in the 2000s, 30–99 in the 1900s. */
    private fun year(s: String): Int = s.toInt().let { if (s.length == 2) (if (it < 30) 2000 + it else 1900 + it) else it }

    private fun month(name: String): Int? {
        val n = name.lowercase()
        val i = MONTHS.indexOfFirst { it == n || (n.length >= 3 && it.startsWith(n)) }
        return if (i < 0) null else i + 1
    }

    private fun valid(y: Int, m: Int, d: Int): LocalDate? =
        if (y !in 1900..9999) null else runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** A typed time as a fraction of a day (more for elapsed `25:30`) and the code it shows in; null when not a time. */
    private fun time(t: String): Pair<Double, String>? {
        TIME.matchEntire(t)?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val s = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            if (min > 59 || s > 59) return null
            val ampm = m.groupValues[4].takeIf { it.isNotEmpty() }?.uppercase()
            val seconds = m.groupValues[3].isNotEmpty()
            val code = when {
                ampm != null -> {
                    if (h !in 1..12) return null
                    h = h % 12 + if (ampm == "P") 12 else 0
                    if (seconds) "h:mm:ss AM/PM" else "h:mm AM/PM"
                }
                h >= 24 -> if (seconds) "[h]:mm:ss" else "[h]:mm"
                else -> if (seconds) "h:mm:ss" else "h:mm"
            }
            return (h * 3600 + min * 60 + s) / 86_400.0 to code
        }
        CJK_TIME.matchEntire(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            val s = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
            if (h > 23 || min > 59 || (s ?: 0) > 59) return null
            return (h * 3600 + min * 60 + (s ?: 0)) / 86_400.0 to (if (s != null) "h\"时\"mm\"分\"ss\"秒\"" else "h\"时\"mm\"分\"")
        }
        return null
    }

    // --- the other way: what the formula bar shows ---------------------------------------------

    /**
     * What the formula bar shows for [cell], as Excel's does: its formula or text as typed, and for
     * a percentage, a date or a time the number as it would be typed (`15%`, `2026-09-29`, `9:30`,
     * `2026-09-29 9:30`) rather than the fraction or serial the cell holds. Committing it reads back
     * the same number.
     */
    fun editText(cell: Cell, symbols: NumberSymbols = NumberSymbols.CANONICAL): String {
        val t = cell.text
        // A formula as the language writes it (=ROUND(A1;2) in German).
        if (t.startsWith("=")) return FormulaLocale.toLocal(t, symbols)
        if (cell.formatCode == "@") return t
        val n = t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return t
        return when (cell.formatCode?.let { FormatCode.category(it) } ?: cell.numberFormat) {
            NumberFormat.PERCENT -> symbols.local(plain(BigDecimal(t).movePointRight(2))) + "%"
            NumberFormat.DATE -> if (n < 0) t else if (n != floor(n)) dateText(n) + " " + timeText(n - floor(n), false) else dateText(n)
            NumberFormat.DATE_TIME -> if (n < 0) t else dateText(n) + " " + timeText(n - floor(n), false)
            NumberFormat.TIME -> if (n < 0) t else timeText(n, true)
            // A number in the reader's decimal symbol (1,5), as it would be typed; dates and times above are the same everywhere.
            else -> if (t.any { it.isLetter() }) t else symbols.local(t)
        }
    }

    private fun dateText(n: Double): String {
        val d = EPOCH.plusDays(floor(n).toLong())
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", d.year, d.monthValue, d.dayOfMonth)
    }

    /** [fraction] of a day as h:mm (with :ss when there are seconds); [elapsed] lets hours pass 24. */
    private fun timeText(fraction: Double, elapsed: Boolean): String {
        val total = Math.round(abs(fraction) * 86_400)
        val h = if (elapsed) total / 3600 else (total / 3600) % 24
        val m = (total / 60) % 60
        val s = total % 60
        return if (s == 0L) String.format(java.util.Locale.ROOT, "$h:%02d", m) else String.format(java.util.Locale.ROOT, "$h:%02d:%02d", m, s)
    }
}
