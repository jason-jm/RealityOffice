package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondStyle
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.DatePeriod
import com.therealityteam.aeropages.model.IconSetKind
import com.therealityteam.aeropages.model.ScaleKind
import com.therealityteam.aeropages.model.ScalePoint
import com.therealityteam.aeropages.model.Sheet
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * A sheet's conditional formats worked out, Excel's way: a cell's rules apply in their order, the first
 * that sets a fill, a text colour or a font trait giving it, and a rule that stops if true ending the
 * search where it matches; a scale over the cell draws its bar, colour or icon. What a rule needs of its
 * whole range — its numbers in order (ranks, percentiles, scales), their average, which values come more
 * than once — is worked out once, and each cell's result is kept: one instance serves while the sheet's
 * content stays as it is (the editor makes a new one when it changes; the PDF one per sheet).
 */
class CondEval(private val sheet: Sheet, private val values: Values, private val today: LocalDate = LocalDate.now()) {

    /** What the rules read. */
    interface Values {
        /** A cell's value as worked out, unformatted ("" when it is blank). */
        fun raw(row: Int, col: Int): String

        /**
         * [formula] (no `=`) worked out on the sheet for the cell ([row], [col]) — where ROW() and COLUMN()
         * count from — as a cell would show its value unformatted; null for an error.
         */
        fun evaluate(formula: String, row: Int, col: Int): String?
    }

    /** A data bar: how much of the cell's room it takes (0..1, its least length included) and its colour. */
    data class Bar(val fraction: Float, val color: Long)

    /** An icon of [set], [index] 0 the one for the lowest values. */
    data class Icon(val set: IconSetKind, val index: Int)

    /** What the rules do to a cell: its look, a bar, an icon, and whether its value hides (a scale shown alone). */
    data class Look(val style: CondStyle, val bar: Bar?, val icon: Icon?, val hideValue: Boolean)

    /** A rule's range as its rules see it: its numbers in order, and how often each value (any case) comes. */
    private class Stats(val numbers: DoubleArray, val counts: Map<String, Int>) {
        val average: Double? = if (numbers.isEmpty()) null else numbers.sum() / numbers.size
    }

    private val stats = java.util.IdentityHashMap<CondFormat, Stats>()
    private val looks = HashMap<Long, Look?>()

    /** What the sheet's rules do to ([row], [col]); null when none applies. */
    fun look(row: Int, col: Int): Look? {
        if (sheet.condFormats.isEmpty()) return null
        val key = sheet.key(row, col)
        if (looks.containsKey(key)) return looks[key]
        return compute(row, col).also { looks[key] = it }
    }

    private fun compute(row: Int, col: Int): Look? {
        var fill: Long? = null; var font: Long? = null
        var bold: Boolean? = null; var italic: Boolean? = null; var underline: Boolean? = null; var strike: Boolean? = null
        var bar: Bar? = null; var icon: Icon? = null; var hide = false; var any = false
        for (rule in sheet.condFormats) {
            if (!rule.covers(row, col)) continue
            if (rule.type.scale) {
                val v = number(row, col) ?: continue
                when (rule.type) {
                    CondType.COLOR_SCALE -> if (fill == null) scaleColor(rule, v)?.let { fill = it; any = true }
                    CondType.DATA_BAR -> if (bar == null) barOf(rule, v)?.let { bar = it; any = true; if (!rule.showValue) hide = true }
                    else -> if (icon == null) iconOf(rule, v)?.let { icon = it; any = true; if (!rule.showValue) hide = true }
                }
                continue
            }
            if (!matches(rule, row, col)) continue
            val st = rule.look
            if (fill == null) fill = st.fill
            if (font == null) font = st.font
            if (bold == null) bold = st.bold
            if (italic == null) italic = st.italic
            if (underline == null) underline = st.underline
            if (strike == null) strike = st.strike
            any = true
            if (rule.stopIfTrue) break
        }
        return if (any) Look(CondStyle(fill, font, bold, italic, underline, strike), bar, icon, hide) else null
    }

    // --- tests -----------------------------------------------------------------------------------

    /** Whether ([row], [col]) passes [rule]'s test. */
    fun matches(rule: CondFormat, row: Int, col: Int): Boolean {
        val raw = values.raw(row, col)
        return when (rule.type) {
            CondType.GREATER -> compare(raw, operand(rule, 1, row, col) ?: return false) > 0
            CondType.LESS -> compare(raw, operand(rule, 1, row, col) ?: return false) < 0
            CondType.GREATER_EQUAL -> compare(raw, operand(rule, 1, row, col) ?: return false) >= 0
            CondType.LESS_EQUAL -> compare(raw, operand(rule, 1, row, col) ?: return false) <= 0
            CondType.EQUAL -> compare(raw, operand(rule, 1, row, col) ?: return false) == 0
            CondType.NOT_EQUAL -> compare(raw, operand(rule, 1, row, col) ?: return false) != 0
            CondType.BETWEEN, CondType.NOT_BETWEEN -> {
                val a = operand(rule, 1, row, col) ?: return false
                val b = operand(rule, 2, row, col) ?: return false
                val (lo, hi) = if (order(a, b) <= 0) a to b else b to a
                val inside = compare(raw, lo) >= 0 && compare(raw, hi) <= 0
                if (rule.type == CondType.BETWEEN) inside else !inside
            }
            CondType.TEXT_CONTAINS -> raw.contains(rule.text, ignoreCase = true)
            CondType.TEXT_NOT_CONTAINS -> !raw.contains(rule.text, ignoreCase = true)
            CondType.TEXT_BEGINS -> raw.startsWith(rule.text, ignoreCase = true)
            CondType.TEXT_ENDS -> raw.endsWith(rule.text, ignoreCase = true)
            CondType.BLANKS -> raw.isBlank()
            CondType.NO_BLANKS -> raw.isNotBlank()
            CondType.ERRORS -> isError(row, col, raw)
            CondType.NO_ERRORS -> !isError(row, col, raw)
            CondType.DUPLICATES -> raw.isNotBlank() && (statsOf(rule).counts[raw.lowercase()] ?: 0) > 1
            CondType.UNIQUES -> raw.isNotBlank() && (statsOf(rule).counts[raw.lowercase()] ?: 0) == 1
            CondType.TOP, CondType.BOTTOM -> {
                val v = raw.toDoubleOrNull() ?: return false
                val nums = statsOf(rule).numbers
                if (nums.isEmpty()) return false
                val n = (if (rule.percent) (nums.size * rule.v1 / 100.0).toInt() else rule.v1.toInt()).coerceIn(1, nums.size)
                // The N-th highest or lowest: ties with it count too.
                if (rule.type == CondType.TOP) v >= nums[nums.size - n] else v <= nums[n - 1]
            }
            CondType.ABOVE_AVERAGE, CondType.BELOW_AVERAGE -> {
                val v = raw.toDoubleOrNull() ?: return false
                val avg = statsOf(rule).average ?: return false
                if (rule.type == CondType.ABOVE_AVERAGE) (if (rule.orEqual) v >= avg else v > avg) else (if (rule.orEqual) v <= avg else v < avg)
            }
            CondType.DATE -> {
                val serial = raw.toDoubleOrNull() ?: return false
                inPeriod(DATE_EPOCH.plusDays(kotlin.math.floor(serial).toLong()), rule.period ?: return false)
            }
            CondType.FORMULA -> truthy(evaluateAt(rule.formula1 ?: return false, rule, row, col))
            CondType.DATA_BAR, CondType.COLOR_SCALE, CondType.ICON_SET -> false
        }
    }

    /** A cellIs rule's [which] (1 or 2) value at ([row], [col]): its number, or its formula's value there (a number, else text). */
    private fun operand(rule: CondFormat, which: Int, row: Int, col: Int): Any? {
        val f = if (which == 1) rule.formula1 else rule.formula2
        if (f == null) return if (which == 1) rule.v1 else rule.v2
        val v = evaluateAt(f, rule, row, col) ?: return null
        return v.toDoubleOrNull() ?: v
    }

    /**
     * A cell's value against [operand], as Excel compares them: numbers by size (a blank cell counts as 0
     * against a number), text by its letters in any case, and text always above numbers.
     */
    private fun compare(raw: String, operand: Any): Int {
        val cell: Any = if (raw.isEmpty()) (if (operand is Double) 0.0 else "") else raw.toDoubleOrNull() ?: raw
        return order(cell, operand)
    }

    private fun order(a: Any, b: Any): Int = when {
        a is Double && b is Double -> a.compareTo(b)
        a is Double -> -1
        b is Double -> 1
        else -> a.toString().compareTo(b.toString(), ignoreCase = true)
    }

    /** An error value: a formula's (#DIV/0!, #N/A …), not text that merely starts with #. */
    private fun isError(row: Int, col: Int, raw: String): Boolean =
        FormulaEngine.isError(raw) && sheet.cell(row, col)?.text?.startsWith("=") == true || raw == "#N/A"

    private fun truthy(v: String?): Boolean = v != null && (v.equals("TRUE", ignoreCase = true) || (v.toDoubleOrNull()?.let { it != 0.0 } ?: false))

    /** [formula] at ([row], [col]): its relative references moved there from the rule's top-left cell. */
    private fun evaluateAt(formula: String, rule: CondFormat, row: Int, col: Int): String? =
        values.evaluate(com.therealityteam.aeropages.model.A1.shiftRefs(formula.trim().removePrefix("="), row - rule.r1, col - rule.c1), row, col)

    private fun inPeriod(d: LocalDate, p: DatePeriod): Boolean {
        fun weekOf(x: LocalDate) = x.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        return when (p) {
            DatePeriod.TODAY -> d == today
            DatePeriod.YESTERDAY -> d == today.minusDays(1)
            DatePeriod.TOMORROW -> d == today.plusDays(1)
            DatePeriod.LAST_7_DAYS -> !d.isAfter(today) && !d.isBefore(today.minusDays(6))
            DatePeriod.THIS_WEEK -> weekOf(d) == weekOf(today)
            DatePeriod.LAST_WEEK -> weekOf(d) == weekOf(today).minusWeeks(1)
            DatePeriod.NEXT_WEEK -> weekOf(d) == weekOf(today).plusWeeks(1)
            DatePeriod.THIS_MONTH -> d.year == today.year && d.month == today.month
            DatePeriod.LAST_MONTH -> today.minusMonths(1).let { d.year == it.year && d.month == it.month }
            DatePeriod.NEXT_MONTH -> today.plusMonths(1).let { d.year == it.year && d.month == it.month }
        }
    }

    // --- scales ----------------------------------------------------------------------------------

    private fun number(row: Int, col: Int): Double? = values.raw(row, col).toDoubleOrNull()

    /** Where [p] stands among [rule]'s numbers (null: they have none, or its formula fails). */
    private fun pointValue(rule: CondFormat, p: ScalePoint): Double? {
        val nums = statsOf(rule).numbers
        if (nums.isEmpty()) return null
        val min = nums.first(); val max = nums.last()
        return when (p.kind) {
            ScaleKind.MIN -> min
            ScaleKind.MAX -> max
            ScaleKind.NUMBER -> p.value
            ScaleKind.PERCENT -> min + (max - min) * p.value / 100.0
            ScaleKind.PERCENTILE -> percentile(nums, p.value / 100.0)
            ScaleKind.FORMULA -> p.formula?.let { f -> values.evaluate(f.trim().removePrefix("="), rule.r1, rule.c1)?.toDoubleOrNull() }
        }
    }

    /** A colour scale's colour for [v]: its stops' colours, blended between the two it falls between. */
    private fun scaleColor(rule: CondFormat, v: Double): Long? {
        val stops = rule.stops.ifEmpty { listOf(ScalePoint(ScaleKind.MIN) to rule.color2, ScalePoint(ScaleKind.MAX) to rule.color) }
        val at = stops.map { (p, c) -> (pointValue(rule, p) ?: return null) to c }
        if (v <= at.first().first) return at.first().second
        if (v >= at.last().first) return at.last().second
        for (i in 0 until at.size - 1) {
            val (lo, from) = at[i]; val (hi, to) = at[i + 1]
            if (v <= hi) return if (hi <= lo) to else blend(from, to, ((v - lo) / (hi - lo)).toFloat())
        }
        return at.last().second
    }

    /** A data bar for [v]: its share of the way between the bar's ends, within its shortest and longest lengths. */
    private fun barOf(rule: CondFormat, v: Double): Bar? {
        val ends = rule.barEnds.takeIf { it.size == 2 } ?: listOf(ScalePoint(ScaleKind.MIN), ScalePoint(ScaleKind.MAX))
        val lo = pointValue(rule, ends[0]) ?: return null
        val hi = pointValue(rule, ends[1]) ?: return null
        val f = if (hi <= lo) 1.0 else ((v - lo) / (hi - lo)).coerceIn(0.0, 1.0)
        val least = rule.barMin.coerceIn(0, 100) / 100.0
        val most = rule.barMax.coerceIn(0, 100) / 100.0
        return Bar((least + (most - least).coerceAtLeast(0.0) * f).toFloat(), rule.color)
    }

    /** An icon for [v]: the highest threshold it reaches (thresholds by default equal shares of the way). */
    private fun iconOf(rule: CondFormat, v: Double): Icon? {
        val set = rule.icons ?: return null
        val points = rule.iconPoints.takeIf { it.size == set.count }
            ?: (0 until set.count).map { ScalePoint(ScaleKind.PERCENT, Math.round(100.0 * it / set.count).toDouble()) }
        var index = 0
        for (i in 1 until points.size) {
            val t = pointValue(rule, points[i]) ?: return null
            if (if (points[i].gte) v >= t else v > t) index = i
        }
        return Icon(set, if (rule.reverse) set.count - 1 - index else index)
    }

    /** Excel's PERCENTILE.INC of sorted [nums] at [k] (0..1). */
    private fun percentile(nums: DoubleArray, k: Double): Double {
        val pos = k.coerceIn(0.0, 1.0) * (nums.size - 1)
        val i = pos.toInt()
        return if (i >= nums.size - 1) nums.last() else nums[i] + (nums[i + 1] - nums[i]) * (pos - i)
    }

    /** The rule's range read once: its numbers in order and how often each value comes (blanks left out). */
    private fun statsOf(rule: CondFormat): Stats = stats.getOrPut(rule) {
        val nums = ArrayList<Double>()
        val counts = HashMap<String, Int>()
        fun take(r: Int, c: Int) {
            val raw = values.raw(r, c)
            if (raw.isBlank()) return
            raw.toDoubleOrNull()?.let { nums.add(it) }
            counts.merge(raw.lowercase(), 1, Int::plus)
        }
        val area = (rule.r2.toLong() - rule.r1 + 1) * (rule.c2.toLong() - rule.c1 + 1)
        // A block is read cell by cell; whole columns or rows by what the sparse sheet holds.
        if (area <= 50_000L) for (r in rule.r1..rule.r2) for (c in rule.c1..rule.c2) take(r, c)
        else sheet.cells.keys.forEach { k -> val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt(); if (rule.covers(r, c)) take(r, c) }
        Stats(nums.toDoubleArray().also { it.sort() }, counts)
    }

    companion object {
        /** Excel's day 0: serial 1 is 1900-01-01. */
        private val DATE_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

        /** [from] blended [f] of the way to [to] (opaque). */
        fun blend(from: Long, to: Long, f: Float): Long {
            fun ch(shift: Int): Long {
                val a = (from shr shift) and 0xFF; val b = (to shr shift) and 0xFF
                return (a + ((b - a) * f).toLong()).coerceIn(0, 255)
            }
            return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** Excel's highlight presets: light red fill with dark red text, yellow with dark yellow, green with dark green, light red fill, red text. */
        val PRESETS: List<CondStyle> = listOf(
            CondStyle(fill = 0xFFFFC7CE, font = 0xFF9C0006),
            CondStyle(fill = 0xFFFFEB9C, font = 0xFF9C5700),
            CondStyle(fill = 0xFFC6EFCE, font = 0xFF006100),
            CondStyle(fill = 0xFFFFC7CE),
            CondStyle(font = 0xFF9C0006),
        )
    }
}
