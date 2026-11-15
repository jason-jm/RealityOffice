package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.odf.OdfPackage.escape
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.CondFormat
import com.therealityteam.aeropages.model.CondStyle
import com.therealityteam.aeropages.model.CondType
import com.therealityteam.aeropages.model.DatePeriod
import com.therealityteam.aeropages.model.IconSetKind
import com.therealityteam.aeropages.model.ScaleKind
import com.therealityteam.aeropages.model.ScalePoint
import com.therealityteam.aeropages.model.Sheet

/**
 * Conditional formats in ODS as LibreOffice keeps them: a table's `calcext:conditional-formats`, each
 * `calcext:conditional-format` over its target ranges holding its rules in order — conditions
 * (`calcext:condition`: a value compared, `between(…)`, `contains-text(…)`, `duplicate`,
 * `top-elements(…)`, `above-average`, `is-error`, `formula-is(…)` …, relative to its base cell), dates
 * (`calcext:date-is`), colour scales, data bars and icon sets — the looks named cell styles (LibreOffice
 * names them by their display names, its `_` written `_5f_`).
 */
internal object OdfCondFormats {

    /** The looks a workbook's rules give, each a named cell style written into styles.xml. */
    class Looks {
        private val names = LinkedHashMap<CondStyle, String>()

        fun nameOf(st: CondStyle): String = names.getOrPut(st) { "AeroCond${names.size + 1}" }

        /** The named cell styles (in office:styles). */
        fun render(): String = buildString {
            for ((st, name) in names) {
                append("""<style:style style:name="$name" style:family="table-cell">""")
                st.fill?.let { append("""<style:table-cell-properties fo:background-color="${OdfPackage.color(it)}"/>""") }
                val text = buildString {
                    st.font?.let { append(""" fo:color="${OdfPackage.color(it)}"""") }
                    st.bold?.let { val w = if (it) "bold" else "normal"; append(""" fo:font-weight="$w" style:font-weight-asian="$w" style:font-weight-complex="$w"""") }
                    st.italic?.let { val s = if (it) "italic" else "normal"; append(""" fo:font-style="$s" style:font-style-asian="$s" style:font-style-complex="$s"""") }
                    st.underline?.let { append(if (it) """ style:text-underline-style="solid" style:text-underline-width="auto" style:text-underline-color="font-color"""" else """ style:text-underline-style="none"""") }
                    st.strike?.let { append(if (it) """ style:text-line-through-style="solid"""" else """ style:text-line-through-style="none"""") }
                }
                if (text.isNotEmpty()) append("<style:text-properties$text/>")
                append("</style:style>")
            }
        }
    }

    // --- writing ---------------------------------------------------------------------------------

    /** [sheet]'s rules (named [sheetName] in addresses) as `calcext:conditional-formats`, their looks through [looks]; "" with none. */
    fun write(sheet: Sheet, sheetName: String, looks: Looks): String {
        if (sheet.condFormats.isEmpty()) return ""
        return buildString {
            append("<calcext:conditional-formats>")
            for (cf in sheet.condFormats) {
                val target = OdfDataRules.address(sheetName, cf.r1, cf.c1) + ":" + OdfDataRules.address(sheetName, cf.r2, cf.c2)
                append("""<calcext:conditional-format calcext:target-range-address="${escape(target)}">""")
                append(ruleXml(cf, OdfDataRules.address(sheetName, cf.r1, cf.c1), looks))
                append("</calcext:conditional-format>")
            }
            append("</calcext:conditional-formats>")
        }
    }

    private fun ruleXml(cf: CondFormat, base: String, looks: Looks): String {
        fun entry(element: String, p: ScalePoint, color: Long? = null): String {
            val type = when (p.kind) {
                ScaleKind.MIN -> "minimum"; ScaleKind.MAX -> "maximum"; ScaleKind.NUMBER -> "number"
                ScaleKind.PERCENT -> "percent"; ScaleKind.PERCENTILE -> "percentile"; ScaleKind.FORMULA -> "formula"
            }
            val value = if (p.kind == ScaleKind.FORMULA) escape(odfExpr(p.formula ?: "0")) else number(p.value)
            return """<calcext:$element calcext:value="$value" calcext:type="$type"${color?.let { """ calcext:color="${OdfPackage.color(it)}"""" } ?: ""}/>"""
        }
        return when (cf.type) {
            CondType.COLOR_SCALE -> buildString {
                append("<calcext:color-scale>")
                cf.stops.ifEmpty { listOf(ScalePoint(ScaleKind.MIN) to cf.color2, ScalePoint(ScaleKind.MAX) to cf.color) }
                    .forEach { (p, c) -> append(entry("color-scale-entry", p, c)) }
                append("</calcext:color-scale>")
            }
            CondType.DATA_BAR -> buildString {
                val c = OdfPackage.color(cf.color)
                append("""<calcext:data-bar calcext:min-length="${cf.barMin}" calcext:max-length="${cf.barMax}" calcext:positive-color="$c" calcext:negative-color="#ff0000" calcext:axis-position="none"""")
                if (!cf.showValue) append(""" calcext:show-value="false"""")
                append(">")
                (cf.barEnds.takeIf { it.size == 2 } ?: listOf(ScalePoint(ScaleKind.MIN), ScalePoint(ScaleKind.MAX))).forEach { append(entry("formatting-entry", it)) }
                append("</calcext:data-bar>")
            }
            CondType.ICON_SET -> buildString {
                val set = cf.icons ?: IconSetKind.TRAFFIC_LIGHTS_3
                append("""<calcext:icon-set calcext:icon-set-type="${set.xlsx}"""")
                if (!cf.showValue) append(""" calcext:show-value="false"""")
                append(">")
                (cf.iconPoints.takeIf { it.size == set.count } ?: (0 until set.count).map { ScalePoint(ScaleKind.PERCENT, Math.round(100.0 * it / set.count).toDouble()) })
                    .forEach { append(entry("formatting-entry", it)) }
                append("</calcext:icon-set>")
            }
            CondType.DATE -> {
                val date = when (cf.period ?: DatePeriod.TODAY) {
                    DatePeriod.TODAY -> "today"; DatePeriod.YESTERDAY -> "yesterday"; DatePeriod.TOMORROW -> "tomorrow"
                    DatePeriod.LAST_7_DAYS -> "last-7-days"; DatePeriod.THIS_WEEK -> "this-week"; DatePeriod.LAST_WEEK -> "last-week"
                    DatePeriod.NEXT_WEEK -> "next-week"; DatePeriod.THIS_MONTH -> "this-month"; DatePeriod.LAST_MONTH -> "last-month"
                    DatePeriod.NEXT_MONTH -> "next-month"
                }
                """<calcext:date-is calcext:style="${looks.nameOf(cf.look)}" calcext:date="$date"/>"""
            }
            else -> {
                fun operand(i: Int) = (if (i == 1) cf.formula1 else cf.formula2)?.let { odfExpr(it) } ?: number(if (i == 1) cf.v1 else cf.v2)
                val at = "[." + A1.ref(cf.r1, cf.c1) + "]"
                val value = when (cf.type) {
                    CondType.GREATER -> ">" + operand(1)
                    CondType.LESS -> "<" + operand(1)
                    CondType.GREATER_EQUAL -> ">=" + operand(1)
                    CondType.LESS_EQUAL -> "<=" + operand(1)
                    CondType.EQUAL -> "=" + operand(1)
                    CondType.NOT_EQUAL -> "!=" + operand(1)
                    CondType.BETWEEN -> "between(${operand(1)};${operand(2)})"
                    CondType.NOT_BETWEEN -> "not-between(${operand(1)};${operand(2)})"
                    CondType.TEXT_CONTAINS -> "contains-text(${quoted(cf.text)})"
                    CondType.TEXT_NOT_CONTAINS -> "not-contains-text(${quoted(cf.text)})"
                    CondType.TEXT_BEGINS -> "begins-with(${quoted(cf.text)})"
                    CondType.TEXT_ENDS -> "ends-with(${quoted(cf.text)})"
                    // LibreOffice has no blank test of its own: Excel's formula for one.
                    CondType.BLANKS -> "formula-is(LEN(TRIM($at))=0)"
                    CondType.NO_BLANKS -> "formula-is(LEN(TRIM($at))>0)"
                    CondType.ERRORS -> "is-error"
                    CondType.NO_ERRORS -> "is-no-error"
                    CondType.DUPLICATES -> "duplicate"
                    CondType.UNIQUES -> "unique"
                    CondType.TOP -> (if (cf.percent) "top-percent" else "top-elements") + "(${cf.v1.toInt().coerceAtLeast(1)})"
                    CondType.BOTTOM -> (if (cf.percent) "bottom-percent" else "bottom-elements") + "(${cf.v1.toInt().coerceAtLeast(1)})"
                    CondType.ABOVE_AVERAGE -> if (cf.orEqual) "above-equal-average" else "above-average"
                    CondType.BELOW_AVERAGE -> if (cf.orEqual) "below-equal-average" else "below-average"
                    else -> "formula-is(${odfExpr(cf.formula1 ?: "FALSE")})"
                }
                """<calcext:condition calcext:apply-style-name="${looks.nameOf(cf.look)}" calcext:value="${escape(value)}" calcext:base-cell-address="${escape(base)}"/>"""
            }
        }
    }

    private fun quoted(t: String) = "\"" + t.replace("\"", "\"\"") + "\""

    private fun number(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun odfExpr(formula: String): String = OdsWriter.toOdfFormula("=" + formula.removePrefix("=")).removePrefix("of:=")

    // --- reading ---------------------------------------------------------------------------------

    /** [table]'s conditional formats in their order, their looks the cell styles [styles] names. */
    fun read(table: OdfNode, styles: OdfStyles): List<CondFormat> {
        val out = ArrayList<CondFormat>()
        val block = table.child("conditional-formats") ?: return out
        for (format in block.children("conditional-format")) {
            val ranges = addresses(format.attr("target-range-address") ?: continue).mapNotNull { rangeOf(it) }
            if (ranges.isEmpty()) continue
            for (rule in format.children) {
                val base = ruleOf(rule, styles) ?: continue
                // Formulas are relative to the base cell: moved to each range's top-left (where this app counts them from).
                val (baseRow, baseCol) = rule.attr("base-cell-address")?.let { cellOf(it) } ?: (ranges[0][0] to ranges[0][1])
                for (r in ranges) {
                    fun moved(f: String?) = f?.let { A1.shiftRefs(it, r[0] - baseRow, r[1] - baseCol) }
                    out.add(base.copy(r1 = r[0], c1 = r[1], r2 = r[2], c2 = r[3], formula1 = moved(base.formula1), formula2 = moved(base.formula2)))
                }
            }
        }
        return out
    }

    private fun ruleOf(rule: OdfNode, styles: OdfStyles): CondFormat? {
        fun point(e: OdfNode): ScalePoint {
            val v = e.attr("value") ?: "0"
            val kind = when (e.attr("type")) {
                "minimum", "auto-minimum" -> ScaleKind.MIN
                "maximum", "auto-maximum" -> ScaleKind.MAX
                "percent" -> ScaleKind.PERCENT
                "percentile" -> ScaleKind.PERCENTILE
                "formula" -> ScaleKind.FORMULA
                else -> ScaleKind.NUMBER
            }
            return ScalePoint(kind, v.toDoubleOrNull() ?: 0.0, if (kind == ScaleKind.FORMULA) a1Expr(v) else null)
        }
        return when (rule.name) {
            "color-scale" -> {
                val entries = rule.children("color-scale-entry")
                val stops = entries.map { point(it) to (OdfValue.color(it.attr("color")) ?: 0xFFFFFFFF) }
                if (stops.size < 2) return null
                val plain = stops.size == 2 && stops[0].first.kind == ScaleKind.MIN && stops[1].first.kind == ScaleKind.MAX
                CondFormat(0, 0, 0, 0, CondType.COLOR_SCALE, color = stops.last().second, color2 = stops.first().second, stops = if (plain) emptyList() else stops)
            }
            "data-bar" -> {
                val ends = rule.children("formatting-entry").map { point(it) }
                CondFormat(0, 0, 0, 0, CondType.DATA_BAR, color = OdfValue.color(rule.attr("positive-color")) ?: 0xFF638EC6,
                    barEnds = ends.takeIf { it.size == 2 && !(it[0].kind == ScaleKind.MIN && it[1].kind == ScaleKind.MAX) } ?: emptyList(),
                    // LibreOffice's bars run from no length to the whole cell unless it says otherwise.
                    barMin = rule.attr("min-length")?.toDoubleOrNull()?.toInt() ?: 0, barMax = rule.attr("max-length")?.toDoubleOrNull()?.toInt() ?: 100,
                    showValue = rule.attr("show-value") != "false")
            }
            "icon-set" -> {
                val set = IconSetKind.of(rule.attr("icon-set-type")) ?: return null
                val defaults = (0 until set.count).map { ScalePoint(ScaleKind.PERCENT, Math.round(100.0 * it / set.count).toDouble()) }
                CondFormat(0, 0, 0, 0, CondType.ICON_SET, icons = set,
                    iconPoints = rule.children("formatting-entry").map { point(it) }.takeIf { it.size == set.count && it != defaults } ?: emptyList(),
                    showValue = rule.attr("show-value") != "false")
            }
            "date-is" -> {
                val period = when (rule.attr("date")) {
                    "today" -> DatePeriod.TODAY; "yesterday" -> DatePeriod.YESTERDAY; "tomorrow" -> DatePeriod.TOMORROW
                    "last-7-days" -> DatePeriod.LAST_7_DAYS; "this-week" -> DatePeriod.THIS_WEEK; "last-week" -> DatePeriod.LAST_WEEK
                    "next-week" -> DatePeriod.NEXT_WEEK; "this-month" -> DatePeriod.THIS_MONTH; "last-month" -> DatePeriod.LAST_MONTH
                    "next-month" -> DatePeriod.NEXT_MONTH
                    else -> return null
                }
                CondFormat(0, 0, 0, 0, CondType.DATE, period = period, style = styles.condStyle(rule.attr("style")) ?: CondStyle())
            }
            "condition" -> condition(rule.attr("value") ?: return null)?.copy(style = styles.condStyle(rule.attr("apply-style-name")) ?: CondStyle())
            else -> null
        }
    }

    /** A condition's value as a rule (its look set by the caller); null for one this app does not know. */
    private fun condition(value: String): CondFormat? {
        val v = value.trim()
        fun rule(type: CondType) = CondFormat(0, 0, 0, 0, type)
        fun inner(head: String): String? = v.takeIf { it.startsWith("$head(") && it.endsWith(")") }?.substring(head.length + 1, v.length - 1)
        fun operand(expr: String): Pair<Double, String?> = expr.trim().toDoubleOrNull()?.let { it to null } ?: (0.0 to a1Expr(expr.trim()))
        fun text(arg: String): String = arg.trim().let { if (it.length >= 2 && it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1).replace("\"\"", "\"") else it }
        when (v) {
            "duplicate" -> return rule(CondType.DUPLICATES)
            "unique" -> return rule(CondType.UNIQUES)
            "above-average" -> return rule(CondType.ABOVE_AVERAGE)
            "below-average" -> return rule(CondType.BELOW_AVERAGE)
            "above-equal-average" -> return rule(CondType.ABOVE_AVERAGE).copy(orEqual = true)
            "below-equal-average" -> return rule(CondType.BELOW_AVERAGE).copy(orEqual = true)
            "is-error" -> return rule(CondType.ERRORS)
            "is-no-error" -> return rule(CondType.NO_ERRORS)
        }
        inner("top-elements")?.let { return rule(CondType.TOP).copy(v1 = it.trim().toDoubleOrNull() ?: 10.0) }
        inner("bottom-elements")?.let { return rule(CondType.BOTTOM).copy(v1 = it.trim().toDoubleOrNull() ?: 10.0) }
        inner("top-percent")?.let { return rule(CondType.TOP).copy(v1 = it.trim().toDoubleOrNull() ?: 10.0, percent = true) }
        inner("bottom-percent")?.let { return rule(CondType.BOTTOM).copy(v1 = it.trim().toDoubleOrNull() ?: 10.0, percent = true) }
        inner("contains-text")?.let { return rule(CondType.TEXT_CONTAINS).copy(text = text(it)) }
        inner("not-contains-text")?.let { return rule(CondType.TEXT_NOT_CONTAINS).copy(text = text(it)) }
        inner("begins-with")?.let { return rule(CondType.TEXT_BEGINS).copy(text = text(it)) }
        inner("ends-with")?.let { return rule(CondType.TEXT_ENDS).copy(text = text(it)) }
        inner("formula-is")?.let { return rule(CondType.FORMULA).copy(formula1 = a1Expr(it)) }
        for ((head, type) in listOf("between" to CondType.BETWEEN, "not-between" to CondType.NOT_BETWEEN)) {
            inner(head)?.let { args ->
                val parts = topLevelSplit(args)
                if (parts.size != 2) return null
                val (a, fa) = operand(parts[0]); val (b, fb) = operand(parts[1])
                return rule(type).copy(v1 = a, v2 = b, formula1 = fa, formula2 = fb)
            }
        }
        val m = Regex("""^(<=|>=|!=|<>|<|>|=)(.*)$""", RegexOption.DOT_MATCHES_ALL).find(v) ?: return null
        val type = when (m.groupValues[1]) {
            "<" -> CondType.LESS; ">" -> CondType.GREATER; "<=" -> CondType.LESS_EQUAL; ">=" -> CondType.GREATER_EQUAL
            "!=", "<>" -> CondType.NOT_EQUAL; else -> CondType.EQUAL
        }
        val (n, f) = operand(m.groupValues[2])
        return rule(type).copy(v1 = n, formula1 = f)
    }

    /** [args] split at the `;` between them (not inside brackets, parentheses or quotes). */
    private fun topLevelSplit(args: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0; var quoted = false; var start = 0
        for (i in args.indices) {
            when (args[i]) {
                '"' -> quoted = !quoted
                '(', '[' -> if (!quoted) depth++
                ')', ']' -> if (!quoted) depth--
                ';' -> if (!quoted && depth == 0) { out.add(args.substring(start, i)); start = i + 1 }
                else -> {}
            }
        }
        out.add(args.substring(start))
        return out
    }

    private fun a1Expr(expr: String): String = OdsReader.fromOdfFormula(expr).removePrefix("=")

    /** A list of addresses split at the spaces between them (not those in a quoted sheet name). */
    private fun addresses(list: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (c in list) {
            if (c == '\'') quoted = !quoted
            if (c == ' ' && !quoted) { if (cur.isNotEmpty()) out.add(cur.toString()); cur.clear() } else cur.append(c)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    /** "Sheet.A2:Sheet.B5" (or one cell) → [r1, c1, r2, c2]. */
    private fun rangeOf(address: String): IntArray? {
        val parts = address.split(':')
        val (r1, c1) = cellOf(parts[0]) ?: return null
        val (r2, c2) = parts.getOrNull(1)?.let { cellOf(it) } ?: (r1 to c1)
        return intArrayOf(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
    }

    /** "Sheet.A2" (the sheet quoted or not, anchored or not) → (row, column). */
    private fun cellOf(address: String): Pair<Int, Int>? = A1.parse(address.substringAfterLast('.').replace("$", ""))
}
