package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.NumberFormat

/**
 * Format Cells → Number as Excel lays it out: a category and its options (decimal places, the
 * thousands separator, how negatives show, the currency symbol, a date, time or fraction type),
 * each choice an Excel format code — and a code read back into the choice that makes it, so the
 * dialog opens on what the cell has.
 */
object FormatChoices {
    enum class Category { GENERAL, NUMBER, CURRENCY, ACCOUNTING, DATE, TIME, PERCENT, FRACTION, SCIENTIFIC, TEXT, CUSTOM }

    /** Currency and accounting symbols, the last none; the reader's own currency first (₩, R$, ₽…), Excel's default there. */
    val SYMBOLS: List<String> = (listOf(com.therealityteam.aeropages.model.LocaleCurrency.of().symbol) + listOf("¥", "$", "€", "£")).distinct() + ""
    val DATE_CODES = listOf(
        "yyyy-mm-dd", "yyyy/m/d", "yyyy\"年\"m\"月\"d\"日\"", "m\"月\"d\"日\"", "yyyy\"年\"m\"月\"", "m/d/yyyy", "d-mmm-yy", "mmmm d, yyyy", "dddd",
    )
    val TIME_CODES = listOf("h:mm", "h:mm:ss", "h:mm AM/PM", "h:mm:ss AM/PM", "h\"时\"mm\"分\"", "yyyy-mm-dd h:mm", "[h]:mm:ss")
    val FRACTION_CODES = listOf("# ?/?", "# ??/??", "# ???/???", "# ?/2", "# ?/4", "# ?/8", "# ??/16", "# ?/10", "# ??/100")
    /** Negative numbers: -1234.10, red 1234.10, (1234.10), red (1234.10). */
    const val NEGATIVE_STYLES = 4
    const val MAX_DECIMALS = 10

    data class Choice(
        val category: Category = Category.GENERAL, val decimals: Int = 2, val thousands: Boolean = false,
        val negative: Int = 0, val symbol: Int = 0, val type: Int = 0, val custom: String = "",
    )

    private fun digits(decimals: Int, thousands: Boolean): String =
        (if (thousands) "#,##0" else "0") + (if (decimals > 0) "." + "0".repeat(decimals) else "")

    /** A positive section and how negatives show, as Excel's four choices write them. */
    private fun signed(base: String, negative: Int): String = when (negative) {
        1 -> "$base;[Red]$base"
        2 -> "${base}_);($base)"
        3 -> "${base}_);[Red]($base)"
        else -> base
    }

    private fun quoted(symbol: String) = if (symbol.isEmpty()) "" else "\"$symbol\""

    /** The code [c] stands for; null for General (no code). */
    fun code(c: Choice): String? {
        val d = c.decimals.coerceIn(0, MAX_DECIMALS)
        return when (c.category) {
            Category.GENERAL -> null
            Category.NUMBER -> signed(digits(d, c.thousands), c.negative)
            Category.CURRENCY -> signed(quoted(SYMBOLS.getOrElse(c.symbol) { "" }) + digits(d, true), c.negative)
            Category.ACCOUNTING -> {
                val s = quoted(SYMBOLS.getOrElse(c.symbol) { "" })
                val n = digits(d, true)
                "_($s* ${n}_);_($s* \\($n\\);_($s* \"-\"${"?".repeat(d)}_);_(@_)"
            }
            Category.DATE -> DATE_CODES.getOrElse(c.type) { DATE_CODES[0] }
            Category.TIME -> TIME_CODES.getOrElse(c.type) { TIME_CODES[0] }
            Category.PERCENT -> digits(d, false) + "%"
            Category.FRACTION -> FRACTION_CODES.getOrElse(c.type) { FRACTION_CODES[0] }
            Category.SCIENTIFIC -> digits(d, false) + "E+00"
            Category.TEXT -> "@"
            Category.CUSTOM -> c.custom
        }
    }

    /** Every choice a category offers (what a code is looked for among). */
    private fun candidates(): Sequence<Choice> = sequence {
        for (d in 0..MAX_DECIMALS) {
            for (t in listOf(false, true)) for (n in 0 until NEGATIVE_STYLES) yield(Choice(Category.NUMBER, d, t, n))
            for (s in SYMBOLS.indices) {
                for (n in 0 until NEGATIVE_STYLES) yield(Choice(Category.CURRENCY, d, negative = n, symbol = s))
                yield(Choice(Category.ACCOUNTING, d, symbol = s))
            }
            yield(Choice(Category.PERCENT, d)); yield(Choice(Category.SCIENTIFIC, d))
        }
        DATE_CODES.indices.forEach { yield(Choice(Category.DATE, type = it)) }
        TIME_CODES.indices.forEach { yield(Choice(Category.TIME, type = it)) }
        FRACTION_CODES.indices.forEach { yield(Choice(Category.FRACTION, type = it)) }
    }

    /** The choice that makes a cell's format: its [code], else its plain [format] with [decimals] (-1: the format's own). */
    fun choiceOf(code: String?, format: NumberFormat, decimals: Int): Choice {
        if (code != null) {
            if (code == "@") return Choice(Category.TEXT)
            return candidates().firstOrNull { code(it) == code } ?: Choice(Category.CUSTOM, custom = code)
        }
        val d = if (decimals >= 0) decimals else 2
        return when (format) {
            NumberFormat.GENERAL -> Choice(Category.GENERAL)
            NumberFormat.NUMBER2 -> Choice(Category.NUMBER, d)
            NumberFormat.THOUSANDS -> Choice(Category.NUMBER, if (decimals >= 0) decimals else 0, thousands = true)
            NumberFormat.CURRENCY_CNY -> Choice(Category.CURRENCY, d, symbol = SYMBOLS.indexOf("¥"))
            NumberFormat.CURRENCY_USD -> Choice(Category.CURRENCY, d, symbol = SYMBOLS.indexOf("$"))
            NumberFormat.PERCENT -> Choice(Category.PERCENT, if (decimals >= 0) decimals else 0)
            NumberFormat.DATE -> Choice(Category.DATE)
            NumberFormat.TIME -> Choice(Category.TIME)
            NumberFormat.DATE_TIME -> Choice(Category.TIME, type = TIME_CODES.indexOf("yyyy-mm-dd h:mm"))
            NumberFormat.SCIENTIFIC -> Choice(Category.SCIENTIFIC, d)
        }
    }
}
