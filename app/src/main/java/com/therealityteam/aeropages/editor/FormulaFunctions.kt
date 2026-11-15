package com.therealityteam.aeropages.editor

/**
 * The formula functions [FormulaEngine] understands, with Excel-style signatures for the
 * formula bar's autocomplete and argument hints. Optional arguments are in brackets.
 */
object FormulaFunctions {
    class Signature(val name: String, val params: List<String>) {
        /** `SUM(number1, [number2], …)` */
        val text: String get() = "$name(${params.joinToString(", ")})"
    }

    private val TABLE = """
        SUM(number1, [number2], …) | AVERAGE(number1, [number2], …) | MIN(number1, [number2], …) | MAX(number1, [number2], …) | SUBTOTAL(function_num, ref1, [ref2], …)
        COUNT(value1, [value2], …) | COUNTA(value1, [value2], …) | COUNTBLANK(range) | PRODUCT(number1, [number2], …) | SUMSQ(number1, [number2], …)
        MEDIAN(number1, [number2], …) | MODE(number1, [number2], …) | STDEV(number1, [number2], …) | STDEVP(number1, [number2], …) | VAR(number1, [number2], …) | VARP(number1, [number2], …)
        LARGE(array, k) | SMALL(array, k) | RANK(number, ref, [order]) | RANK.EQ(number, ref, [order])
        SUMIF(range, criteria, [sum_range]) | AVERAGEIF(range, criteria, [average_range]) | COUNTIF(range, criteria)
        SUMIFS(sum_range, criteria_range1, criteria1, …) | AVERAGEIFS(average_range, criteria_range1, criteria1, …) | COUNTIFS(criteria_range1, criteria1, …)
        MAXIFS(max_range, criteria_range1, criteria1, …) | MINIFS(min_range, criteria_range1, criteria1, …) | SUMPRODUCT(array1, [array2], …)
        IF(logical_test, value_if_true, [value_if_false]) | IFS(logical_test1, value1, …) | IFERROR(value, value_if_error) | IFNA(value, value_if_na)
        SWITCH(expression, value1, result1, …, [default]) | AND(logical1, [logical2], …) | OR(logical1, [logical2], …) | NOT(logical) | XOR(logical1, [logical2], …) | TRUE() | FALSE()
        ISERROR(value) | ISERR(value) | ISNA(value) | ISBLANK(value) | ISNUMBER(value) | ISTEXT(value) | ISNONTEXT(value) | ISLOGICAL(value) | ISFORMULA(reference)
        ISEVEN(number) | ISODD(number) | N(value) | T(value) | NA() | ERROR.TYPE(error_val)
        VLOOKUP(lookup_value, table_array, col_index, [range_lookup]) | HLOOKUP(lookup_value, table_array, row_index, [range_lookup])
        XLOOKUP(lookup_value, lookup_array, return_array, [if_not_found]) | MATCH(lookup_value, lookup_array, [match_type]) | INDEX(array, row_num, [column_num])
        OFFSET(reference, rows, cols, [height], [width]) | INDIRECT(ref_text) | CHOOSE(index_num, value1, [value2], …)
        UNIQUE(array) | FILTER(array, include, [if_empty]) | SORT(array, [sort_index], [sort_order])
        ROW([reference]) | COLUMN([reference]) | ROWS(array) | COLUMNS(array) | HYPERLINK(link_location, [friendly_name])
        ABS(number) | SQRT(number) | POWER(number, power) | EXP(number) | LN(number) | LOG(number, [base]) | LOG10(number) | PI()
        ROUND(number, num_digits) | ROUNDUP(number, num_digits) | ROUNDDOWN(number, num_digits) | TRUNC(number, [num_digits]) | INT(number)
        MOD(number, divisor) | CEILING(number, significance) | FLOOR(number, significance) | SIGN(number) | RAND() | RANDBETWEEN(bottom, top)
        SIN(number) | COS(number) | TAN(number) | ASIN(number) | ACOS(number) | ATAN(number) | ATAN2(x_num, y_num) | DEGREES(angle) | RADIANS(angle)
        LEN(text) | LEFT(text, [num_chars]) | RIGHT(text, [num_chars]) | MID(text, start_num, num_chars) | UPPER(text) | LOWER(text) | PROPER(text) | TRIM(text)
        CONCAT(text1, [text2], …) | CONCATENATE(text1, [text2], …) | TEXTJOIN(delimiter, ignore_empty, text1, …) | TEXT(value, format_text) | REPT(text, number_times)
        FIND(find_text, within_text, [start_num]) | SEARCH(find_text, within_text, [start_num]) | SUBSTITUTE(text, old_text, new_text) | REPLACE(old_text, start_num, num_chars, new_text)
        EXACT(text1, text2) | VALUE(text) | NUMBERVALUE(text) | CHAR(number) | CODE(text)
        DATE(year, month, day) | YEAR(serial) | MONTH(serial) | DAY(serial) | WEEKDAY(serial, [return_type]) | DAYS(end_date, start_date) | DATEDIF(start_date, end_date, unit)
        EDATE(start_date, months) | EOMONTH(start_date, months) | NETWORKDAYS(start_date, end_date, [holidays]) | WORKDAY(start_date, days, [holidays]) | TODAY() | NOW()
        NETWORKDAYS.INTL(start_date, end_date, [weekend], [holidays]) | WORKDAY.INTL(start_date, days, [weekend], [holidays]) | DAYS360(start_date, end_date, [method])
        TIME(hour, minute, second) | HOUR(serial) | MINUTE(serial) | SECOND(serial) | WEEKNUM(serial, [return_type]) | ISOWEEKNUM(date) | YEARFRAC(start_date, end_date, [basis])
        DATEVALUE(date_text) | TIMEVALUE(time_text)
        PMT(rate, nper, pv, [fv], [type]) | IPMT(rate, per, nper, pv, [fv], [type]) | PPMT(rate, per, nper, pv, [fv], [type]) | PV(rate, nper, pmt, [fv], [type])
        FV(rate, nper, pmt, [pv], [type]) | NPER(rate, pmt, pv, [fv], [type]) | RATE(nper, pmt, pv, [fv], [type], [guess]) | NPV(rate, value1, [value2], …)
        IRR(values, [guess]) | XNPV(rate, values, dates) | XIRR(values, dates, [guess]) | CUMIPMT(rate, nper, pv, start_period, end_period, type)
        CUMPRINC(rate, nper, pv, start_period, end_period, type) | SLN(cost, salvage, life) | SYD(cost, salvage, life, per) | DDB(cost, salvage, life, period, [factor])
        DB(cost, salvage, life, period, [month]) | EFFECT(nominal_rate, npery) | NOMINAL(effect_rate, npery)
        QUOTIENT(numerator, denominator) | MROUND(number, multiple) | GCD(number1, [number2], …) | LCM(number1, [number2], …) | FACT(number) | COMBIN(number, number_chosen)
        COMBINA(number, number_chosen) | PERMUT(number, number_chosen) | PERMUTATIONA(number, number_chosen) | EVEN(number) | ODD(number) | SQRTPI(number)
        CEILING.MATH(number, [significance], [mode]) | FLOOR.MATH(number, [significance], [mode]) | CEILING.PRECISE(number, [significance]) | FLOOR.PRECISE(number, [significance]) | ISO.CEILING(number, [significance])
        STDEV.S(number1, [number2], …) | STDEV.P(number1, [number2], …) | VAR.S(number1, [number2], …) | VAR.P(number1, [number2], …) | MODE.SNGL(number1, [number2], …)
        PERCENTILE(array, k) | PERCENTILE.INC(array, k) | PERCENTILE.EXC(array, k) | QUARTILE(array, quart) | QUARTILE.INC(array, quart) | QUARTILE.EXC(array, quart)
        AVERAGEA(value1, [value2], …) | MAXA(value1, [value2], …) | MINA(value1, [value2], …) | GEOMEAN(number1, [number2], …) | HARMEAN(number1, [number2], …)
        AVEDEV(number1, [number2], …) | DEVSQ(number1, [number2], …) | RANK.AVG(number, ref, [order]) | CORREL(array1, array2) | PEARSON(array1, array2) | RSQ(known_ys, known_xs)
        SLOPE(known_ys, known_xs) | INTERCEPT(known_ys, known_xs) | FORECAST(x, known_ys, known_xs) | FORECAST.LINEAR(x, known_ys, known_xs) | COVAR(array1, array2)
        COVARIANCE.P(array1, array2) | COVARIANCE.S(array1, array2) | STANDARDIZE(x, mean, standard_dev) | NORM.DIST(x, mean, standard_dev, cumulative)
        NORM.S.DIST(z, cumulative) | NORM.INV(probability, mean, standard_dev) | NORM.S.INV(probability) | NORMDIST(x, mean, standard_dev, cumulative) | NORMSDIST(z)
        NORMINV(probability, mean, standard_dev) | NORMSINV(probability)
        CLEAN(text) | FIXED(number, [decimals], [no_commas]) | DOLLAR(number, [decimals]) | UNICHAR(number) | UNICODE(text) | TEXTBEFORE(text, delimiter, [instance_num], [match_mode], [match_end], [if_not_found])
        TEXTAFTER(text, delimiter, [instance_num], [match_mode], [match_end], [if_not_found]) | LOOKUP(lookup_value, lookup_vector, [result_vector]) | ADDRESS(row_num, column_num, [abs_num], [a1], [sheet_text])
        XMATCH(lookup_value, lookup_array, [match_mode], [search_mode]) | TYPE(value) | ISREF(value) | FORMULATEXT(reference)
    """.trimIndent()

    /** Every signature, sorted by name. */
    val all: List<Signature> = TABLE.split('|', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
        val name = entry.substringBefore('(')
        val inner = entry.substringAfter('(').substringBeforeLast(')')
        Signature(name, if (inner.isBlank()) emptyList() else inner.split(',').map { it.trim() })
    }.sortedBy { it.name }

    private val byName = all.associateBy { it.name }

    fun find(name: String): Signature? = byName[name.uppercase()]

    /** Functions whose name starts with [prefix] (case-insensitive), for autocomplete. */
    fun matching(prefix: String, limit: Int = 8): List<Signature> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.uppercase()
        return all.filter { it.name.startsWith(p) }.take(limit)
    }

    /** The identifier being typed at the end of [formula] (after `=`), or null. */
    fun trailingIdentifier(formula: String): String? {
        if (!formula.startsWith("=")) return null
        val m = Regex("(?<![A-Za-z0-9_.!\$])([A-Za-z][A-Za-z0-9.]*)$").find(formula) ?: return null
        val ident = m.groupValues[1]
        // A cell reference (A1) is not a function prefix.
        return if (Regex("^[A-Za-z]{1,3}[0-9]+$").matches(ident)) null else ident
    }

    /** Innermost unclosed function call at the end of [formula] and the 0-based argument being edited, or null. */
    fun openCall(formula: String): Pair<Signature, Int>? {
        if (!formula.startsWith("=")) return null
        var depth = 0
        var inStr = false
        var commas = 0
        // Walk backwards: count commas at depth 0 until the unmatched '(' that opens the current call.
        var k = formula.length - 1
        while (k > 0) {
            val ch = formula[k]
            if (ch == '"') inStr = !inStr
            else if (!inStr) when (ch) {
                ')' -> depth++
                '(' -> if (depth == 0) {
                    val nameEnd = k
                    var s = k - 1
                    while (s >= 1 && (formula[s].isLetterOrDigit() || formula[s] == '.')) s--
                    val name = formula.substring(s + 1, nameEnd)
                    val sig = find(name) ?: return null
                    return sig to commas
                } else depth--
                ',' -> if (depth == 0) commas++
            }
            k--
        }
        return null
    }
}
