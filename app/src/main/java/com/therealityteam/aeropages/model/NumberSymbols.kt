package com.therealityteam.aeropages.model

import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * The decimal and grouping symbols numbers are shown and typed with — Excel takes them from the system's settings; Aero
 * Desk from the app's language (1,234.5 in English, 1.234,5 in German, 1 234,5 in French). Files always keep numbers and
 * format codes in the standard form ([CANONICAL]); only what the reader sees and types follows the language.
 */
data class NumberSymbols(val decimal: Char, val group: Char) {
    val canonical: Boolean get() = decimal == '.' && group == ','

    /** [s] — a number written with the standard . and , (General's 1234.5, a plain format's 1,234.50) — in these symbols. */
    fun local(s: String): String = if (canonical) s else buildString(s.length) {
        for (ch in s) append(when (ch) { '.' -> decimal; ',' -> group; else -> ch })
    }

    /** Between the arguments of a function in a formula as typed: `;` where the decimal symbol is a comma (Excel's way). */
    val argument: Char get() = if (decimal == ',') ';' else ','

    companion object {
        val CANONICAL = NumberSymbols('.', ',')

        /** The symbols of [locale] (from the platform's locale data; a narrow no-break space is shown as a no-break space). */
        fun of(locale: Locale): NumberSymbols {
            val s = DecimalFormatSymbols.getInstance(locale)
            val group = s.groupingSeparator.let { if (it == ' ') ' ' else it }
            val decimal = s.decimalSeparator
            return if (decimal == '.' && group == ',') CANONICAL else NumberSymbols(decimal, group)
        }

        fun current(): NumberSymbols = of(Locale.getDefault())
    }
}
