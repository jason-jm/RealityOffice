package com.therealityteam.aeropages.format.odf

/**
 * Function names in ODF formulas (OpenFormula) as LibreOffice writes them, against Excel's: Excel's newer
 * functions — and CEILING and FLOOR, whose OpenFormula namesakes work otherwise — take the `COM.MICROSOFT.`
 * prefix; a few have names of their own (FORMULATEXT is FORMULA, NORMSDIST is LEGACY.NORMSDIST).
 */
object OdfFunctions {

    private val MICROSOFT = setOf(
        "CEILING", "CEILING.MATH", "CEILING.PRECISE", "CONCAT", "COVARIANCE.P", "COVARIANCE.S", "FILTER", "FLOOR", "FLOOR.MATH",
        "FLOOR.PRECISE", "FORECAST.LINEAR", "IFS", "ISO.CEILING", "MAXIFS", "MINIFS", "MODE.SNGL", "NETWORKDAYS.INTL", "NORM.DIST",
        "NORM.INV", "NORM.S.DIST", "NORM.S.INV", "PERCENTILE.EXC", "PERCENTILE.INC", "QUARTILE.EXC", "QUARTILE.INC", "RANK.AVG",
        "RANK.EQ", "SORT", "SORTBY", "SEQUENCE", "STDEV.P", "STDEV.S", "SWITCH", "TEXTAFTER", "TEXTBEFORE", "TEXTJOIN", "UNIQUE",
        "VAR.P", "VAR.S", "WORKDAY.INTL", "XLOOKUP", "XMATCH",
    )

    private val RENAMED = mapOf("FORMULATEXT" to "FORMULA", "NORMSDIST" to "LEGACY.NORMSDIST", "NORMSINV" to "LEGACY.NORMSINV")
    private val RENAMED_BACK = RENAMED.entries.associate { (k, v) -> v to k }

    /** Excel's function [name] as ODF writes it. */
    fun toOdf(name: String): String {
        val up = name.uppercase()
        return RENAMED[up] ?: if (up in MICROSOFT) "COM.MICROSOFT.$up" else name
    }

    /** An ODF function [name] as Excel names it. */
    fun fromOdf(name: String): String {
        val up = name.uppercase()
        RENAMED_BACK[up]?.let { return it }
        return if (up.startsWith("COM.MICROSOFT.")) up.removePrefix("COM.MICROSOFT.") else name
    }

    /** A function's name where a call starts: letters, digits, `.` and `_`, then `(`. */
    val CALL = Regex("""([A-Za-z][A-Za-z0-9._]*)(?=\s*\()""")
}
