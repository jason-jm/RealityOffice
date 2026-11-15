package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.odf.OdfPackage.escape
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.AutoFilter
import com.therealityteam.aeropages.model.ColumnFilter
import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.FilterCondition
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.ValidationAlertStyle
import com.therealityteam.aeropages.model.ValidationOperator
import com.therealityteam.aeropages.model.ValidationType
import com.therealityteam.aeropages.model.Workbook
import java.util.Locale

/**
 * A spreadsheet's data rules in OpenDocument: validation rules (`table:content-validations`,
 * named by the cells they cover) and AutoFilters (`table:database-ranges`, one anonymous database
 * range per sheet with its filter buttons and criteria), written and read as LibreOffice Calc does.
 */
internal object OdfDataRules {

    // --- validation -----------------------------------------------------------------------------

    /**
     * A rule's ODF condition (`of:cell-content-is-whole-number() and cell-content-is-between(1,10)` —
     * a comma between the bounds, as the ODF grammar and LibreOffice have it, while a list's values
     * and the expressions inside are `;`-separated —
     * `of:cell-content-is-in-list("Yes";"No")`, `of:is-true-formula(…)`), or null for Any value.
     */
    fun condition(dv: DataValidation): String? {
        fun expr(formula: String?, literal: Double): String = formula?.let { odfExpr(it) } ?: number(literal)
        val a = expr(dv.formula1, dv.min)
        val b = expr(dv.formula2, dv.max)
        val body = when (dv.type) {
            ValidationType.ANY -> return null
            ValidationType.LIST -> "cell-content-is-in-list(" +
                (dv.formula1?.let { odfExpr(it) } ?: dv.items.joinToString(";") { "\"" + it.replace("\"", "\"\"") + "\"" }) + ")"
            ValidationType.CUSTOM -> "is-true-formula(${odfExpr(dv.formula1 ?: "TRUE()")})"
            ValidationType.TEXT_LENGTH -> when (dv.operator) {
                ValidationOperator.BETWEEN -> "cell-content-text-length-is-between($a,$b)"
                ValidationOperator.NOT_BETWEEN -> "cell-content-text-length-is-not-between($a,$b)"
                else -> "cell-content-text-length()${op(dv.operator)}$a"
            }
            else -> {
                val kind = when (dv.type) {
                    ValidationType.INTEGER -> "cell-content-is-whole-number()"
                    ValidationType.DATE -> "cell-content-is-date()"
                    ValidationType.TIME -> "cell-content-is-time()"
                    else -> "cell-content-is-decimal-number()"
                }
                kind + " and " + when (dv.operator) {
                    ValidationOperator.BETWEEN -> "cell-content-is-between($a,$b)"
                    ValidationOperator.NOT_BETWEEN -> "cell-content-is-not-between($a,$b)"
                    else -> "cell-content()${op(dv.operator)}$a"
                }
            }
        }
        return "of:$body"
    }

    private fun op(o: ValidationOperator): String = when (o) {
        ValidationOperator.EQUAL -> "="
        ValidationOperator.NOT_EQUAL -> "!="
        ValidationOperator.GREATER -> ">"
        ValidationOperator.LESS -> "<"
        ValidationOperator.GREATER_OR_EQUAL -> ">="
        else -> "<="
    }

    private fun number(v: Double): String =
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else String.format(Locale.ROOT, "%s", v)

    /** An editor formula (A1, no `=`) as an ODF expression: `$A$1:$A$5` → `[.$A$1:.$A$5]`, `,` → `;`. */
    private fun odfExpr(formula: String): String = OdsWriter.toOdfFormula("=$formula").removePrefix("of:=")

    /** `Sheet 1.A1` as ODF spells a cell address (the sheet quoted when it has to be). */
    fun address(sheet: String, r: Int, c: Int): String = odfSheet(sheet) + "." + A1.ref(r, c)

    fun odfSheet(sheet: String): String =
        if (sheet.isNotEmpty() && sheet.all { it.isLetterOrDigit() || it == '_' }) sheet else "'" + sheet.replace("'", "''") + "'"

    /** `<table:content-validations>` for [rules]: each rule's sheet, the rule, its name. */
    fun contentValidations(wb: Workbook, rules: List<Triple<Int, DataValidation, String>>): String {
        if (rules.isEmpty()) return ""
        return buildString {
            append("<table:content-validations>")
            rules.forEach { (si, dv, name) ->
                append("""<table:content-validation table:name="$name"""")
                condition(dv)?.let { append(""" table:condition="${escape(it)}"""") }
                append(""" table:allow-empty-cell="${dv.allowBlank}" table:base-cell-address="${escape(address(wb.sheets[si].name, dv.r1, dv.c1))}"""")
                if (dv.type == ValidationType.LIST) append(""" table:display-list="${if (dv.dropdown) "unsorted" else "none"}"""")
                append(">")
                append("""<table:help-message table:title="${escape(dv.inputTitle)}" table:display="${dv.showInput}">""")
                paragraphs(dv.inputMessage)
                append("</table:help-message>")
                append("""<table:error-message table:message-type="${dv.errorStyle.xlsx}" table:title="${escape(dv.errorTitle)}" table:display="${dv.showError}">""")
                paragraphs(dv.errorMessage)
                append("</table:error-message>")
                append("</table:content-validation>")
            }
            append("</table:content-validations>")
        }
    }

    private fun StringBuilder.paragraphs(text: String) {
        if (text.isEmpty()) return
        text.split('\n').forEach { append("<text:p>${escape(it)}</text:p>") }
    }

    /** A rule as a `table:content-validation` holds it, on no cells yet, and the cell its relative references are written for. */
    class Template(val rule: DataValidation, val baseRow: Int, val baseCol: Int)

    /** The document's validation rules by name, in the order it declares them. */
    fun templates(spreadsheet: OdfNode?): Map<String, Template> {
        val out = LinkedHashMap<String, Template>()
        spreadsheet?.child("content-validations")?.children("content-validation")?.forEach { cv ->
            val name = cv.attr("name") ?: return@forEach
            ruleOf(cv)?.let { out[name] = it }
        }
        return out
    }

    private fun ruleOf(cv: OdfNode): Template? {
        val cond = cv.attr("condition")?.trim()?.let { c -> listOf("of:", "oooc:", "ooow:").firstOrNull { c.startsWith(it) }?.let { c.removePrefix(it) } ?: c }
        var type = ValidationType.ANY
        var operator = ValidationOperator.BETWEEN
        var bounds = emptyList<String>()
        var items = emptyList<String>()
        var formula1: String? = null
        if (cond != null && cond.isNotEmpty()) {
            fun inner(s: String, head: String): String? = s.takeIf { it.startsWith(head) && it.endsWith(")") }?.substring(head.length, s.length - 1)
            val list = inner(cond, "cell-content-is-in-list(")
            val custom = inner(cond, "is-true-formula(")
            when {
                list != null -> {
                    type = ValidationType.LIST
                    val args = splitArgs(list)
                    if (args.all { it.startsWith("\"") }) items = args.map { it.removeSurrounding("\"").replace("\"\"", "\"") }.filter { it.isNotEmpty() }
                    else formula1 = a1Expr(list)
                }
                custom != null -> { type = ValidationType.CUSTOM; formula1 = a1Expr(custom) }
                else -> {
                    var rest: String = cond
                    listOf(
                        "cell-content-is-whole-number()" to ValidationType.INTEGER, "cell-content-is-decimal-number()" to ValidationType.DECIMAL,
                        "cell-content-is-date()" to ValidationType.DATE, "cell-content-is-time()" to ValidationType.TIME,
                    ).firstOrNull { rest.startsWith(it.first) }?.let { (head, t) ->
                        type = t
                        rest = rest.removePrefix(head).trim().removePrefix("and").trim()
                    }
                    val between = inner(rest, "cell-content-is-between(") ?: inner(rest, "cell-content-text-length-is-between(")
                    val notBetween = inner(rest, "cell-content-is-not-between(") ?: inner(rest, "cell-content-text-length-is-not-between(")
                    if (rest.startsWith("cell-content-text-length")) type = ValidationType.TEXT_LENGTH
                    when {
                        between != null -> { operator = ValidationOperator.BETWEEN; bounds = splitArgs(between, bounds = true) }
                        notBetween != null -> { operator = ValidationOperator.NOT_BETWEEN; bounds = splitArgs(notBetween, bounds = true) }
                        else -> {
                            val m = Regex("""^cell-content(?:-text-length)?\(\)\s*(<=|>=|!=|<>|=|<|>)(.*)$""").find(rest) ?: return null
                            operator = when (m.groupValues[1]) {
                                "=" -> ValidationOperator.EQUAL; "!=", "<>" -> ValidationOperator.NOT_EQUAL
                                ">" -> ValidationOperator.GREATER; "<" -> ValidationOperator.LESS
                                ">=" -> ValidationOperator.GREATER_OR_EQUAL; else -> ValidationOperator.LESS_OR_EQUAL
                            }
                            bounds = listOf(m.groupValues[2].trim())
                        }
                    }
                    if (type == ValidationType.ANY) type = ValidationType.DECIMAL   // a bare comparison: any number
                }
            }
        }
        fun bound(i: Int): Pair<Double, String?> {
            val s = bounds.getOrNull(i)?.trim() ?: return 0.0 to null
            return s.toDoubleOrNull()?.let { it to null } ?: (0.0 to a1Expr(s))
        }
        val (min, f1) = bound(0)
        val (max, f2) = bound(1)
        val help = cv.child("help-message")
        val error = cv.child("error-message")
        val (baseRow, baseCol) = cv.attr("base-cell-address")?.let { A1.parse(it.substringAfterLast('.').replace("$", "")) } ?: (0 to 0)
        val rule = DataValidation(
            0, 0, 0, 0, type, min, max, items, operator,
            formula1 = formula1 ?: f1.takeIf { type.compares }, formula2 = f2.takeIf { type.compares && operator.twoBounds },
            allowBlank = cv.attr("allow-empty-cell") != "false", dropdown = cv.attr("display-list") != "none",
            showInput = help?.attr("display") == "true", inputTitle = help?.attr("title") ?: "", inputMessage = text(help),
            showError = error?.attr("display") == "true", errorStyle = ValidationAlertStyle.ofXlsx(error?.attr("message-type")),
            errorTitle = error?.attr("title") ?: "", errorMessage = text(error),
        )
        return Template(rule, baseRow, baseCol)
    }

    private fun text(node: OdfNode?): String = node?.children("p")?.joinToString("\n") { it.text } ?: ""

    /** An ODF expression as an editor formula without its `=`. */
    private fun a1Expr(expr: String): String = OdsReader.fromOdfFormula(expr).removePrefix("=")

    /**
     * Arguments at the top level of an ODF call — outside quotes, brackets and parentheses —
     * separated by `;`, or for a between's [bounds] by `,` (older writers used `;` there too).
     */
    private fun splitArgs(s: String, bounds: Boolean = false): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var quoted = false
        val cur = StringBuilder()
        for (ch in s) {
            when {
                ch == '"' -> { quoted = !quoted; cur.append(ch) }
                quoted -> cur.append(ch)
                ch == '(' || ch == '[' -> { depth++; cur.append(ch) }
                ch == ')' || ch == ']' -> { depth--; cur.append(ch) }
                (ch == ';' || (bounds && ch == ',')) && depth == 0 -> { out.add(cur.toString().trim()); cur.clear() }
                else -> cur.append(ch)
            }
        }
        out.add(cur.toString().trim())
        return out.filter { it.isNotEmpty() }
    }

    /**
     * The blocks each named rule covers — gathered cell run by cell run while the rows are read —
     * as rules on [sheet]: runs merged into rectangles, formulas moved from the rule's base cell to
     * each rectangle's top-left one.
     */
    fun place(sheet: Sheet, runs: Map<String, List<IntArray>>, templates: Map<String, Template>) {
        // In the order the document declares the rules — the first that covers a cell is its rule —
        // not the order their cells come in (which reversed rules declared right to left on every save).
        templates.forEach { (name, t) ->
            val blocks = runs[name] ?: return@forEach
            merge(blocks).forEach { (r1, c1, r2, c2) ->
                val dr = r1 - t.baseRow; val dc = c1 - t.baseCol
                fun moved(f: String?) = f?.let { A1.shiftRefs(it, dr, dc) }
                sheet.validations.add(t.rule.copy(r1 = r1, c1 = c1, r2 = r2, c2 = c2, formula1 = moved(t.rule.formula1), formula2 = moved(t.rule.formula2)))
            }
        }
    }

    /** Blocks [r1, c1, r2, c2] joined where they line up: first down the columns, then across the rows. */
    private fun merge(blocks: List<IntArray>): List<IntArray> {
        fun pass(list: List<IntArray>, across: Boolean): List<IntArray> {
            val sorted = if (across) list.sortedWith(compareBy({ it[0] }, { it[2] }, { it[1] })) else list.sortedWith(compareBy({ it[1] }, { it[3] }, { it[0] }))
            val out = ArrayList<IntArray>()
            for (b in sorted) {
                val last = out.lastOrNull()
                if (last != null && !across && last[1] == b[1] && last[3] == b[3] && b[0] <= last[2] + 1) last[2] = maxOf(last[2], b[2])
                else if (last != null && across && last[0] == b[0] && last[2] == b[2] && b[1] <= last[3] + 1) last[3] = maxOf(last[3], b[3])
                else out.add(b.copyOf())
            }
            return out
        }
        return pass(pass(blocks, across = false), across = true)
    }

    // --- AutoFilter -----------------------------------------------------------------------------

    /** `<table:database-ranges>`: each sheet's AutoFilter as LibreOffice's anonymous sheet database range. */
    fun databaseRanges(wb: Workbook): String {
        // A table's filter buttons are the sheet's filter when it has no other.
        fun filterOf(sheet: com.therealityteam.aeropages.model.Sheet) = sheet.autoFilter
            ?: sheet.tables.firstOrNull { it.filterButton && it.headerRow }?.let { com.therealityteam.aeropages.model.AutoFilter(it.r1, it.c1, it.dataR2, it.c2) }
        val filtered = wb.sheets.withIndex().filter { filterOf(it.value) != null }
        if (filtered.isEmpty()) return ""
        return buildString {
            append("<table:database-ranges>")
            filtered.forEach { (si, sheet) ->
                val af = filterOf(sheet)!!
                val target = address(sheet.name, af.r1, af.c1) + ":" + address(sheet.name, af.r2, af.c2)
                append("""<table:database-range table:name="__Anonymous_Sheet_DB__$si" table:target-range-address="${escape(target)}" table:display-filter-buttons="true">""")
                // ODF nests an Or inside the And of the columns, but never an And inside an And: two conditions joined by
                // And on one column are two more of the outer And's.
                val conditions = af.columns.entries.sortedBy { it.key }.flatMap { (col, f) -> filterConditions(col - af.c1, f) }
                if (conditions.isNotEmpty()) {
                    append("<table:filter>")
                    if (conditions.size > 1) append("<table:filter-and>")
                    conditions.forEach { append(it) }
                    if (conditions.size > 1) append("</table:filter-and>")
                    append("</table:filter>")
                }
                append("</table:database-range>")
            }
            append("</table:database-ranges>")
        }
    }

    /** [f] as ODF filter conditions to And with the other columns' (two joined by Or come as one `table:filter-or`). */
    private fun filterConditions(field: Int, f: ColumnFilter): List<String> {
        f.color?.let { c ->
            // LibreOffice's colour filters; No Fill and Automatic as it writes them.
            val value = c.color?.let { "#%06x".format(java.util.Locale.ROOT, it and 0xFFFFFF) } ?: if (c.font) "window-font-color" else "transparent"
            return listOf("""<table:filter-condition table:field-number="$field" table:value="$value" table:operator="=" loext:data-type="${if (c.font) "text-color" else "background-color"}"/>""")
        }
        f.top?.let { t ->
            val op = (if (t.top) "top" else "bottom") + (if (t.percent) " percent" else " values")
            val count = if (t.count % 1.0 == 0.0) t.count.toLong().toString() else t.count.toString()
            return listOf("""<table:filter-condition table:field-number="$field" table:value="$count" table:operator="$op" table:data-type="number"/>""")
        }
        f.condition?.let { c1 ->
            val first = conditionXml(field, c1)
            val second = f.condition2?.let { conditionXml(field, it) } ?: return listOf(first)
            return if (f.or) listOf("<table:filter-or>$first$second</table:filter-or>") else listOf(first, second)
        }
        val values = f.values ?: return emptyList()
        if (values.isEmpty()) return if (f.blanks) listOf("""<table:filter-condition table:field-number="$field" table:value="" table:operator="empty"/>""") else emptyList()
        val shown = values.sorted() + (if (f.blanks) listOf("") else emptyList())
        return listOf(buildString {
            append("""<table:filter-condition table:field-number="$field" table:value="${escape(shown.first())}" table:operator="=">""")
            if (shown.size > 1) shown.forEach { append("""<table:filter-set-item table:value="${escape(it)}"/>""") }
            append("</table:filter-condition>")
        })
    }

    private fun conditionXml(field: Int, c: FilterCondition): String {
        val operator = when (c.op) {
            FilterCondition.Op.EQUALS -> "="; FilterCondition.Op.NOT_EQUALS -> "!="
            FilterCondition.Op.CONTAINS -> "contains"; FilterCondition.Op.NOT_CONTAINS -> "!contains"
            FilterCondition.Op.BEGINS_WITH -> "begins"; FilterCondition.Op.ENDS_WITH -> "ends"
            FilterCondition.Op.NOT_BEGINS_WITH -> "!begins"; FilterCondition.Op.NOT_ENDS_WITH -> "!ends"
            FilterCondition.Op.GREATER -> ">"; FilterCondition.Op.GREATER_OR_EQUAL -> ">="
            FilterCondition.Op.LESS -> "<"; FilterCondition.Op.LESS_OR_EQUAL -> "<="
        }
        val type = if (c.value.trim().toDoubleOrNull() != null) "number" else "text"
        return """<table:filter-condition table:field-number="$field" table:value="${escape(c.value)}" table:operator="${escape(operator)}" table:data-type="$type"/>"""
    }

    /** Each sheet's AutoFilter from the document's database ranges (those showing filter buttons). */
    fun readFilters(spreadsheet: OdfNode?, sheets: List<Sheet>) {
        spreadsheet?.child("database-ranges")?.children("database-range")?.forEach { db ->
            if (db.attr("display-filter-buttons") != "true") return@forEach
            val target = db.attr("target-range-address") ?: return@forEach
            val parts = target.split(':')
            val from = parts[0]
            val to = parts.getOrElse(1) { parts[0] }
            val sheetName = unquote(from.substringBeforeLast('.'))
            val sheet = sheets.firstOrNull { it.name == sheetName } ?: return@forEach
            val (r1, c1) = A1.parse(from.substringAfterLast('.').replace("$", "")) ?: return@forEach
            val (r2, c2) = A1.parse(to.substringAfterLast('.').replace("$", "")) ?: return@forEach
            val af = AutoFilter(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
            // The conditions, an Or's two on one column joined as Excel's custom filter joins them (an And's likewise).
            fun visit(node: OdfNode, or: Boolean) {
                for (child in node.children) when (child.name) {
                    "filter-condition" -> {
                        val field = child.attr("field-number")?.toIntOrNull() ?: continue
                        val f = columnFilter(child) ?: continue
                        val col = af.c1 + field
                        val had = af.columns[col]
                        af.columns[col] = if (had?.condition != null && had.condition2 == null && f.condition != null)
                            had.copy(condition2 = f.condition, or = or) else f
                    }
                    "filter-or" -> visit(child, true)
                    "filter-and" -> visit(child, false)
                }
            }
            db.child("filter")?.let { visit(it, false) }
            sheet.autoFilter = af
        }
    }

    private fun columnFilter(fc: OdfNode): ColumnFilter? {
        val value = fc.attr("value") ?: ""
        val items = fc.children("filter-set-item").map { it.attr("value") ?: "" }
        fun cond(op: FilterCondition.Op) = ColumnFilter(blanks = false, condition = FilterCondition(op, value))
        fun top(top: Boolean, percent: Boolean) = ColumnFilter(blanks = false,
            top = com.therealityteam.aeropages.model.TopFilter(top, value.toDoubleOrNull() ?: 10.0, percent))
        // LibreOffice's colour filters (loext:data-type, also met as table:data-type).
        val colorType = fc.attrs.entries.firstOrNull { it.key.substringAfter(':') == "data-type" && it.value.endsWith("-color") }?.value
            ?: fc.attr("data-type")?.takeIf { it.endsWith("-color") }
        if (colorType != null) {
            val font = colorType == "text-color"
            val color = value.removePrefix("#").takeIf { value.startsWith("#") }?.toLongOrNull(16)?.let { 0xFF000000L or it }
            return ColumnFilter(blanks = false, color = com.therealityteam.aeropages.model.ColorFilter(color, font))
        }
        return when (fc.attr("operator") ?: "=") {
            "=" -> {
                val all = items.ifEmpty { listOf(value) }
                ColumnFilter(values = all.filter { it.isNotEmpty() }.toSet(), blanks = all.any { it.isEmpty() })
            }
            "empty" -> ColumnFilter(values = emptySet(), blanks = true)
            "!=" -> cond(FilterCondition.Op.NOT_EQUALS)
            "contains" -> cond(FilterCondition.Op.CONTAINS)
            "!contains" -> cond(FilterCondition.Op.NOT_CONTAINS)
            "begins" -> cond(FilterCondition.Op.BEGINS_WITH)
            "ends" -> cond(FilterCondition.Op.ENDS_WITH)
            ">" -> cond(FilterCondition.Op.GREATER)
            ">=" -> cond(FilterCondition.Op.GREATER_OR_EQUAL)
            "<" -> cond(FilterCondition.Op.LESS)
            "<=" -> cond(FilterCondition.Op.LESS_OR_EQUAL)
            "!begins" -> cond(FilterCondition.Op.NOT_BEGINS_WITH)
            "!ends" -> cond(FilterCondition.Op.NOT_ENDS_WITH)
            "top values" -> top(true, false)
            "bottom values" -> top(false, false)
            "top percent" -> top(true, true)
            "bottom percent" -> top(false, true)
            else -> null
        }
    }

    /** `'Q3 Sales'` → `Q3 Sales` (a doubled quote stands for one); a plain name stays. */
    fun unquote(s: String): String = s.trim().removePrefix("$").let { if (it.startsWith("'") && it.endsWith("'") && it.length >= 2) it.substring(1, it.length - 1).replace("''", "'") else it }
}
