package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.NumberFormat
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.YearMonth

/**
 * Excel's AutoFill: how a line of seed cells goes on when a block is filled past them (the fill
 * handle, Fill Series). Positions count along the line from the first seed (0): filling down or
 * right asks for n, n+1, …, filling up or left for -1, -2, ….
 *
 *  - Numbers: one repeats, as Excel's AutoFill does, unless it is a series (then it counts up by
 *    1); two or more follow their straight-line trend (1, 3 → 5, 7).
 *  - Dates — date-formatted numbers, or text such as 2026-09-28, 2026/9/28 or 2026年9月28日 — go
 *    by the day, or by the month / year when the seeds are whole months apart; one counts up a day.
 *    A lone time (a time-formatted number, or text such as 9:00) counts up an hour.
 *  - Text with a number counts on from its last one: "Item 1" → "Item 2", "第1季度" → "第2季度",
 *    "Room 009" → "Room 010", "1st" → "2nd"; two or more seeds give the step.
 *  - Built-in lists cycle — weekdays, months (12月 → 1月), quarters (Q4 → Q1), the heavenly stems
 *    and earthly branches — in English (short and long, keeping the seed's capitals), Chinese and
 *    Japanese.
 *  - Numbers written with a currency sign, thousands commas or a percent sign are numbers
 *    ($1,250.50, 15%) and keep that dress.
 *  - Anything else (blanks, formulas, plain text, mixed seeds) repeats the seeds.
 */
object AutoFill {
    /** A seed cell: its text and its number format (dates and times count differently). */
    class Seed(val text: String, val format: NumberFormat = NumberFormat.GENERAL)

    /**
     * What goes at one position: [text], or null to copy the seed [copyOf] as it is (a formula
     * with its relative references moved [shift] positions along). Either way the cell takes the
     * format of seed [copyOf].
     */
    class Step(val copyOf: Int, val text: String?, val shift: Int)

    /** The cells at [positions] after (or before) [seeds]; [series] makes a lone number count up (Fill Series). */
    fun fill(seeds: List<Seed>, positions: List<Int>, series: Boolean = false): List<Step> {
        require(seeds.isNotEmpty())
        val next = generatorFor(seeds, series)
        return positions.map { p ->
            val s = Math.floorMod(p, seeds.size)
            Step(s, next?.invoke(p), p - s)
        }
    }

    private fun generatorFor(seeds: List<Seed>, series: Boolean): ((Int) -> String)? {
        val texts = seeds.map { it.text.trim() }
        if (texts.any { it.isEmpty() || it.startsWith("=") }) return null
        // Numbers — plain or written with a currency sign, thousands commas or a percent sign — are
        // settled by the number rules alone: a lone one repeats rather than counting as text would.
        if (texts.all { it.toDoubleOrNull()?.isFinite() == true }) return numbers(seeds, texts, series)
        decorated(texts)?.let { d -> return if (texts.size == 1 && !series) null else d }
        return dateTexts(texts) ?: times(texts) ?: lists(texts) ?: textNumbers(texts)
    }

    private val DECORATED = Regex("^([$¥€£]?)(-?)(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?(%?)$")

    /** `$1,250.50`, `¥10`, `15%`: numbers as Excel reads them, continued in the same dress (sign, grouping, decimals). */
    private fun decorated(texts: List<String>): ((Int) -> String)? {
        val parts = texts.map { DECORATED.matchEntire(it) ?: return null }
        val sign = parts[0].groupValues[1]
        val percent = parts[0].groupValues[5]
        if (sign.isEmpty() && percent.isEmpty() && parts.none { ',' in it.groupValues[3] }) return null
        if (parts.any { it.groupValues[1] != sign || it.groupValues[5] != percent }) return null
        val grouped = parts.any { ',' in it.groupValues[3] }
        val decimals = parts.maxOf { it.groupValues[4].length.let { n -> if (n > 0) n - 1 else 0 } }
        val values = parts.map { m -> (m.groupValues[2] + m.groupValues[3].replace(",", "") + m.groupValues[4]).toDouble() }
        val (a, b) = if (values.size == 1) values[0] to 1.0 else trend(values)
        return { p ->
            val v = a + b * p
            val body = BigDecimal(kotlin.math.abs(v)).setScale(decimals, java.math.RoundingMode.HALF_UP).toPlainString()
            val whole = body.substringBefore('.')
            val frac = if ('.' in body) "." + body.substringAfter('.') else ""
            val digits = if (grouped) whole.reversed().chunked(3).joinToString(",").reversed() else whole
            sign + (if (v < 0 && body.any { it in '1'..'9' }) "-" else "") + digits + frac + percent
        }
    }

    // --- numbers, date and time serials ---------------------------------------------------

    private const val EXCEL_EPOCH_DAY = 25569L   // serial of 1970-01-01

    private fun numbers(seeds: List<Seed>, texts: List<String>, series: Boolean): ((Int) -> String)? {
        val values = texts.map { t -> t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null }
        val dates = seeds.all { it.format == NumberFormat.DATE || it.format == NumberFormat.DATE_TIME }
        val times = seeds.all { it.format == NumberFormat.TIME }
        if (dates && values.all { it == Math.rint(it) }) {
            val days = values.map { LocalDate.ofEpochDay(it.toLong() - EXCEL_EPOCH_DAY) }
            val at = datesAt(days)
            return { p -> (at(p).toEpochDay() + EXCEL_EPOCH_DAY).toString() }
        }
        if (values.size == 1) {
            val step = when {
                dates -> 1.0
                times -> 1.0 / 24
                series -> 1.0
                else -> return null   // a lone number repeats
            }
            return { p -> number(values[0] + step * p) }
        }
        val (a, b) = trend(values)
        return { p -> number(a + b * p) }
    }

    /** Least-squares line through (i, v[i]): (value at 0, step). */
    private fun trend(v: List<Double>): Pair<Double, Double> {
        val xm = (v.size - 1) / 2.0
        val ym = v.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in v.indices) { sxy += (i - xm) * (v[i] - ym); sxx += (i - xm) * (i - xm) }
        val b = if (sxx == 0.0) 0.0 else sxy / sxx
        return (ym - b * xm) to b
    }

    /** A number as a cell holds it: integers plainly, fractions without binary noise (0.1 + 0.2 → 0.3). */
    private fun number(v: Double): String {
        val r = Math.rint(v)
        if (kotlin.math.abs(v - r) < 1e-9 && kotlin.math.abs(r) < 1e15) return r.toLong().toString()
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString()
    }

    /**
     * Dates along a line: whole months apart (the same day of the month, or every one a month's
     * last day) go on by months, keeping the month end or clamping the day to short months; other
     * dates by their day step (a lone one by a day).
     */
    private fun datesAt(d: List<LocalDate>): (Int) -> LocalDate {
        val monthEnds = d.all { it.dayOfMonth == it.lengthOfMonth() }
        val months = d.map { it.year * 12L + it.monthValue - 1 }
        val k = if (d.size >= 2) months[1] - months[0] else 0L
        val monthly = d.size >= 2 && k != 0L && (monthEnds || d.all { it.dayOfMonth == d[0].dayOfMonth }) &&
            (1 until d.size).all { months[it] - months[it - 1] == k }
        if (monthly) {
            val start = YearMonth.from(d[0])
            return { p ->
                val ym = start.plusMonths(k * p)
                if (monthEnds) ym.atEndOfMonth() else ym.atDay(minOf(d[0].dayOfMonth, ym.lengthOfMonth()))
            }
        }
        if (d.size == 1) return { p -> d[0].plusDays(p.toLong()) }
        val (a, b) = trend(d.map { it.toEpochDay().toDouble() })
        return { p -> LocalDate.ofEpochDay(Math.round(a + b * p)) }
    }

    // --- dates and times written as text ----------------------------------------------------

    private val DATE_DASHED = Regex("^(\\d{4})([-/.])(\\d{1,2})\\2(\\d{1,2})$")
    private val DATE_CJK = Regex("^(\\d{4})年(\\d{1,2})月(\\d{1,2})日$")

    /** Text dates in one shape (`2026-09-28`, `2026/9/28`, `2026.09.28`, `2026年9月28日`), written back in the first seed's shape. */
    private fun dateTexts(texts: List<String>): ((Int) -> String)? {
        val dashed = DATE_DASHED.matchEntire(texts[0])
        val cjk = if (dashed == null) DATE_CJK.matchEntire(texts[0]) else null
        val first = dashed ?: cjk ?: return null
        val sep = dashed?.groupValues?.get(2)
        // Two-digit months and days throughout (2026-09-28) stay padded; any single digit (2026/9/30) means none.
        var padded = true
        val days = texts.map { t ->
            val m = (if (dashed != null) DATE_DASHED.matchEntire(t)?.takeIf { it.groupValues[2] == sep } else DATE_CJK.matchEntire(t)) ?: return null
            val g = m.groupValues
            val (y, mo, d) = if (dashed != null) Triple(g[1], g[3], g[4]) else Triple(g[1], g[2], g[3])
            if (mo.length < 2 || d.length < 2) padded = false
            runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: return null
        }
        val at = datesAt(days)
        fun two(n: Int) = if (padded) n.toString().padStart(2, '0') else n.toString()
        return { p ->
            val d = at(p)
            if (sep != null) "${d.year}$sep${two(d.monthValue)}$sep${two(d.dayOfMonth)}"
            else "${d.year}年${two(d.monthValue)}月${two(d.dayOfMonth)}日"
        }
    }

    private val TIME = Regex("^(\\d{1,2}):(\\d{2})$")

    /** `9:00`-style times: a lone one counts up an hour, several by their own step, round the clock. */
    private fun times(texts: List<String>): ((Int) -> String)? {
        val minutes = texts.map { t ->
            val m = TIME.matchEntire(t) ?: return null
            val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
            if (h > 23 || mi > 59) return null
            (h * 60 + mi).toDouble()
        }
        val pad = TIME.matchEntire(texts[0])!!.groupValues[1].length == 2
        val (a, b) = if (minutes.size == 1) minutes[0] to 60.0 else trend(minutes)
        return { p ->
            val t = Math.floorMod(Math.round(a + b * p), 24L * 60).toInt()
            val h = (t / 60).toString().let { if (pad) it.padStart(2, '0') else it }
            "$h:${(t % 60).toString().padStart(2, '0')}"
        }
    }

    // --- built-in lists ----------------------------------------------------------------------

    private fun lists(texts: List<String>): ((Int) -> String)? {
        for (list in CustomLists.all()) {
            val idx = texts.map { t -> list.indexOfFirst { it.equals(t, ignoreCase = true) } }
            if (idx.any { it < 0 }) continue
            val size = list.size
            val step = if (idx.size == 1) 1 else Math.floorMod(idx[1] - idx[0], size)
            if ((1 until idx.size).any { Math.floorMod(idx[it] - idx[it - 1], size) != step }) return null
            // Capitals as the first seed has them, in any script: MON → TUE, mon → tue, Mon → Tue, ЯНВАРЬ → ФЕВРАЛЬ.
            val seed = texts[0]
            val cased = seed.any { it.isLetter() && it.uppercaseChar() != it.lowercaseChar() }
            val case: (String) -> String = when {
                cased && seed == seed.uppercase() -> { s -> s.uppercase() }
                cased && seed == seed.lowercase() -> { s -> s.lowercase() }
                cased && seed.first().isUpperCase() && seed.drop(1) == seed.drop(1).lowercase() -> { s -> s.lowercase().replaceFirstChar { it.titlecase() } }
                else -> { s -> s }
            }
            return { p -> case(list[Math.floorMod(idx[0] + step * p, size)]) }
        }
        return null
    }

    // --- text with a number ------------------------------------------------------------------

    /** The last number in a text, wherever it sits (Item 1, 第1季度, 3 apples, Room 009). */
    private val LAST_NUMBER = Regex("^(.*?)(\\d+)(\\D*)$")
    private val ORDINAL = Regex("^(st|nd|rd|th)(?![A-Za-z])(.*)$", RegexOption.IGNORE_CASE)

    /** A text split round its counting number; [ordinal] when an English ordinal suffix follows it (1st, 2nd …). */
    private class Counted(val prefix: String, val digits: String, val suffix: String, val ordinal: String?)

    private fun counted(t: String): Counted? {
        val m = LAST_NUMBER.matchEntire(t) ?: return null
        val (prefix, digits, rest) = m.destructured
        if (prefix.isEmpty() && rest.isEmpty()) return null
        val ord = ORDINAL.matchEntire(rest)
        return if (ord != null) Counted(prefix, digits, ord.groupValues[2], ord.groupValues[1]) else Counted(prefix, digits, rest, null)
    }

    private fun textNumbers(texts: List<String>): ((Int) -> String)? {
        val parts = texts.map { counted(it) ?: return null }
        val first = parts[0]
        if (parts.any { it.prefix != first.prefix || it.suffix != first.suffix || (it.ordinal == null) != (first.ordinal == null) }) return null
        val values = parts.map { it.digits.toLongOrNull()?.toDouble() ?: return null }
        val (a, b) = if (values.size == 1) values[0] to 1.0 else trend(values)
        // Zero-padded numbers keep their width (Room 009 → Room 010).
        val width = if (first.digits.length > 1 && first.digits.startsWith('0')) first.digits.length else 0
        val upper = first.ordinal?.let { it == it.uppercase() } ?: false
        return { p ->
            val n = kotlin.math.abs(Math.round(a + b * p))
            val digits = n.toString().padStart(width, '0')
            val ord = if (first.ordinal == null) "" else ordinalSuffix(n).let { if (upper) it.uppercase() else it }
            first.prefix + digits + ord + first.suffix
        }
    }

    private fun ordinalSuffix(n: Long): String = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1L -> "st"
        n % 10 == 2L -> "nd"
        n % 10 == 3L -> "rd"
        else -> "th"
    }
}
