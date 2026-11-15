package com.therealityteam.aeropages.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * How a chart looks, shared by the slide canvas, the slideshow, the Sheets chart panel, the PDF
 * export and the file writers: the colour sets ([SlideChart.colorSet]), the label colour that
 * reads on a given background, an Office-style value axis (round ticks that include zero) and
 * number labels.
 */
object ChartStyle {
    /** Series / slice colours per [SlideChart.colorSet]. */
    val PALETTES: List<List<Long>> = listOf(
        listOf(0xFFB81E6A, 0xFFE85A9B, 0xFFF08A3C, 0xFF4BACC6, 0xFF9BBB59, 0xFF8064A2),
        listOf(0xFF2B579A, 0xFF4A90D9, 0xFF8AB4F8, 0xFF17A2A0, 0xFF6AAE6A, 0xFFE8A33D),
        listOf(0xFF333333, 0xFF666666, 0xFF999999, 0xFFB0B0B0, 0xFFC8C8C8, 0xFFE0E0E0),
        listOf(0xFFC0504D, 0xFFE8A33D, 0xFF9BBB59, 0xFF4BACC6, 0xFF8064A2, 0xFF2B579A),
    )

    fun palette(colorSet: Int): List<Long> = PALETTES.getOrElse(colorSet) { PALETTES[0] }

    const val DARK_INK = 0xFF3A3A3AL
    const val LIGHT_INK = 0xFFF2F2F2L

    /** WCAG relative luminance of an 0xAARRGGBB colour (alpha ignored). */
    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio between two colours (1 … 21). */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Label / gridline colour for text drawn on [background] (null = a white slide): whichever ink contrasts more. */
    fun inkFor(background: Long?): Long {
        val bg = background ?: 0xFFFFFFFFL
        return if (contrast(bg, DARK_INK) >= contrast(bg, LIGHT_INK)) DARK_INK else LIGHT_INK
    }

    /**
     * A value axis from [min] to [max] with a tick every [step] from [min] on. An automatic axis has
     * both ends on the step and zero inside; fixed bounds (Format Axis) can put zero outside.
     */
    class Axis(val min: Double, val max: Double, val step: Double) {
        val ticks: List<Double>
            get() {
                val n = floor((max - min) / step + 1e-9).toInt().coerceIn(1, 50)
                return (0..n).map { min + it * step }
            }

        /** Where [v] sits between the axis ends, 0 at [min] and 1 at [max]. */
        fun fraction(v: Double): Float = ((v - min) / (max - min)).toFloat()

        /** Where bars grow from: zero, or the nearer end of the axis when zero is off it (as in Office). */
        val baseline: Double get() = 0.0.coerceIn(min, max)

        /** [v] kept on the axis: a bar or point beyond a fixed bound stops at it. */
        fun clamp(v: Double): Double = v.coerceIn(min, max)
    }

    /** Office-style nice axis for data spanning [minValue]…[maxValue]: a 1 / 2 / 2.5 / 5 × 10ⁿ step, about [targetSteps] of them. */
    fun axis(minValue: Double, maxValue: Double, targetSteps: Int = 4): Axis {
        val lo = minOf(0.0, if (minValue.isFinite()) minValue else 0.0)
        var hi = maxOf(0.0, if (maxValue.isFinite()) maxValue else 0.0)
        if (hi - lo < 1e-12) hi = lo + 1.0
        val step = niceStep((hi - lo) / targetSteps.coerceAtLeast(1))
        return Axis(floor(lo / step + 1e-9) * step, ceil(hi / step - 1e-9) * step, step)
    }

    /**
     * [axis] with the user's fixed bounds and major unit where they are set (Office's Format
     * Axis); the automatic parts stay round, and an automatic bound still takes zero in unless the
     * other one was fixed past it. Bounds that contradict each other are ignored, as is a unit that
     * would give more than 50 ticks.
     */
    fun axis(minValue: Double, maxValue: Double, fixedMin: Double?, fixedMax: Double?, fixedStep: Double?, targetSteps: Int = 4): Axis {
        val fMin = fixedMin?.takeIf { it.isFinite() }
        var fMax = fixedMax?.takeIf { it.isFinite() }
        if (fMin != null && fMax != null && fMax <= fMin) fMax = null
        val fStep = fixedStep?.takeIf { it.isFinite() && it > 0 }
        if (fMin == null && fMax == null && fStep == null) return axis(minValue, maxValue, targetSteps)
        var lo = fMin ?: minOf(if (fMax != null && fMax <= 0.0) minValue else 0.0, if (minValue.isFinite()) minValue else 0.0)
        var hi = fMax ?: maxOf(if (fMin != null && fMin >= 0.0) maxValue else 0.0, if (maxValue.isFinite()) maxValue else 0.0)
        if (hi - lo < 1e-12) { if (fMax == null) hi = lo + 1.0 else lo = hi - 1.0 }
        val step = fStep?.takeIf { (hi - lo) / it <= 50.0 } ?: niceStep((hi - lo) / targetSteps.coerceAtLeast(1))
        // With one end fixed, the other lands a whole number of steps from it (ticks start at the minimum).
        val min = fMin ?: if (fMax != null) fMax - ceil((fMax - lo) / step - 1e-9) * step else floor(lo / step + 1e-9) * step
        val max = fMax ?: if (fMin != null) fMin + ceil((hi - fMin) / step - 1e-9) * step else ceil(hi / step - 1e-9) * step
        return Axis(min, if (max - min < 1e-12) min + step else max, step)
    }

    /** A 1 / 2 / 2.5 / 5 × 10ⁿ step near [raw]. */
    private fun niceStep(raw: Double): Double {
        val magnitude = 10.0.pow(floor(log10(raw)))
        val nice = when (raw / magnitude) {
            in 0.0..1.0 -> 1.0
            in 1.0..2.0 -> 2.0
            in 2.0..2.5 -> 2.5
            in 2.5..5.0 -> 5.0
            else -> 10.0
        }
        return nice * magnitude
    }

    /** A chart's value axis: its data's round range, with the chart's own fixed bounds and major unit where set. */
    fun valueAxis(chart: SlideChart): Axis {
        val all = chart.series.flatMap { it.values }
        return axis((all.minOrNull() ?: 0f).toDouble(), (all.maxOrNull() ?: 0f).toDouble(), chart.valMin, chart.valMax, chart.valMajor)
    }

    /** A value as [chart] shows it on its axis and in its data labels: in its number format, else the automatic label. */
    fun valueLabel(chart: SlideChart, v: Double, compact: Boolean = false): String = format(v, chart.valFormat, compact)

    /**
     * The parts of an Excel number format the charts use: text (or a currency sign) before and
     * after the number, [decimals] of which [required] always show (0.0# → 2, 1), thousands
     * [grouping], [percent] (×100) and [scale] (each trailing comma divides by 1 000).
     */
    class NumberFormatSpec(
        val prefix: String, val suffix: String, val decimals: Int, val required: Int,
        val grouping: Boolean, val percent: Boolean, val scale: Int,
    ) {
        /** Back to an Excel code (literal text quoted), the way the file writers store it. */
        fun code(): String = buildString {
            if (prefix.isNotEmpty()) append('"').append(prefix.replace("\"", "")).append('"')
            append(if (grouping) "#,##0" else "0")
            if (decimals > 0) append('.').append("0".repeat(required)).append("#".repeat(decimals - required))
            repeat(scale) { append(',') }
            val text = suffix.removeSuffix("%")
            if (text.isNotEmpty()) append('"').append(text.replace("\"", "")).append('"')
            if (percent) append('%')
        }
    }

    /**
     * Reads an Excel number format [code] — decimals (`0.00`, optional `#`), thousands separators
     * (`#,##0`), percent (`0%`), scaling by trailing commas (`0.0,,"M"`), and text or a currency
     * sign around the number (`"¥"#,##0`, `$#,##0.00`, `[$€-407]#,##0`, `0" 元"`); only the first
     * (positive) section counts. Null for General or a code it cannot read.
     */
    fun parseFormat(code: String): NumberFormatSpec? {
        val section = code.substringBefore(';').trim()
        if (section.isEmpty() || section.equals(SlideChart.GENERAL, ignoreCase = true)) return null
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        val pattern = StringBuilder()
        var state = 0 // 0 before the number, 1 in it, 2 after it
        fun literal(text: String) {
            if (state == 1) state = 2
            (if (state == 0) prefix else suffix).append(text)
        }
        var i = 0
        while (i < section.length) {
            val ch = section[i]
            when {
                ch == '"' -> {
                    val end = section.indexOf('"', i + 1).let { if (it < 0) section.length else it }
                    literal(section.substring(i + 1, end)); i = end + 1
                }
                ch == '\\' && i + 1 < section.length -> { literal(section[i + 1].toString()); i += 2 }
                ch == '[' -> {
                    val end = section.indexOf(']', i).let { if (it < 0) section.length else it }
                    val inner = section.substring(i + 1, end)
                    if (inner.startsWith("$")) literal(inner.substring(1).substringBefore('-'))
                    i = end + 1
                }
                ch == '_' || ch == '*' -> i += 2
                state != 2 && (ch == '0' || ch == '#' || ch == '?' || ch == ',' || ch == '.') -> { state = 1; pattern.append(ch); i++ }
                else -> { literal(ch.toString()); i++ }
            }
        }
        if (pattern.none { it == '0' || it == '#' || it == '?' }) return null
        val p = pattern.toString()
        var intPart = p.substringBefore('.')
        var decPart = if ('.' in p) p.substringAfter('.') else ""
        var scale = 0
        while (decPart.endsWith(',')) { scale++; decPart = decPart.dropLast(1) }
        if (decPart.isEmpty()) while (intPart.endsWith(',')) { scale++; intPart = intPart.dropLast(1) }
        return NumberFormatSpec(
            prefix = prefix.toString(), suffix = suffix.toString(),
            decimals = decPart.count { it == '0' || it == '#' || it == '?' }, required = decPart.count { it == '0' },
            grouping = ',' in intPart, percent = '%' in suffix || '%' in prefix, scale = scale,
        )
    }

    /** [v] in the Excel number format [code] (through [FormatCode]); General — or a code with no number in it — gives the automatic [label]. */
    fun format(v: Double, code: String, compact: Boolean = false): String {
        if (!v.isFinite()) return ""
        // Drawn for the reader: in their decimal and grouping symbols (charts never write these into files).
        val symbols = NumberSymbols.current()
        if (parseFormat(code) == null && !FormatCode.isDate(code)) return symbols.local(label(v, compact))
        return FormatCode.format(v, code, symbols = symbols).text
    }

    /** A data or tick label: up to two decimals, trailing zeros dropped; [compact] shortens 10 000+ to K / M / B. */
    fun label(v: Double, compact: Boolean = false): String {
        if (!v.isFinite()) return ""
        val a = abs(v)
        if (compact && a >= 1e4) {
            val (div, suffix) = when {
                a >= 1e9 -> 1e9 to "B"
                a >= 1e6 -> 1e6 to "M"
                else -> 1e3 to "K"
            }
            return plain(v / div) + suffix
        }
        return plain(v)
    }

    private fun plain(v: Double): String {
        if (abs(v) >= 1e13) return String.format(Locale.ROOT, "%.0f", v)
        val r = Math.round(v * 100.0) / 100.0
        if (r == Math.rint(r)) return r.toLong().toString()
        return String.format(Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.')
    }
}
