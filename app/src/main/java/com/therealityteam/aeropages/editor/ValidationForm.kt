package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.DataValidation
import com.therealityteam.aeropages.model.NumberSymbols
import com.therealityteam.aeropages.model.ValidationAlertStyle
import com.therealityteam.aeropages.model.ValidationOperator
import com.therealityteam.aeropages.model.ValidationType

/**
 * The Data Validation dialog's fields and the rule they make. Bounds are typed as a person would
 * type them into a cell — `10`, `2026-01-31`, `9:30`, `1,000` — or as a formula (`=TODAY()`,
 * `=$B$1`); a list's source is its values separated by commas or a reference (`=$A$1:$A$5`, a name). In a language
 * whose decimal symbol is a comma ([NumberSymbols]) all of it is the language's way, as in its Excel: 1,5, formulas
 * with `;`, and a list's values separated by `;` — the rule itself keeps the standard form.
 */
object ValidationForm {

    data class Fields(
        val type: ValidationType = ValidationType.ANY,
        val operator: ValidationOperator = ValidationOperator.BETWEEN,
        val minimum: String = "",
        val maximum: String = "",
        val source: String = "",
        val formula: String = "",
        val dropdown: Boolean = true,
        val ignoreBlank: Boolean = true,
        val showInput: Boolean = true,
        val inputTitle: String = "",
        val inputMessage: String = "",
        val showError: Boolean = true,
        val errorStyle: ValidationAlertStyle = ValidationAlertStyle.STOP,
        val errorTitle: String = "",
        val errorMessage: String = "",
    )

    /** What keeps the fields from making a rule. */
    enum class Problem { BOUND, MIN_OVER_MAX, LIST, LIST_TOO_LONG, FORMULA }

    /** Excel keeps a typed list in a formula of at most 255 characters (a longer one makes the file unreadable to it). */
    const val MAX_LIST_CHARS = 255

    /** The dialog's fields for [rule] (null: a cell without one, [defaultList] offered as a list's source). */
    fun fieldsOf(rule: DataValidation?, defaultList: String = "", symbols: NumberSymbols = NumberSymbols.CANONICAL): Fields {
        if (rule == null) return Fields(source = defaultList)
        fun formula(f: String) = FormulaLocale.toLocal("=$f", symbols)
        return Fields(
            type = rule.type, operator = rule.operator,
            minimum = if (rule.type.compares) boundText(rule, rule.formula1, rule.min, symbols) else "",
            maximum = if (rule.type.compares && rule.operator.twoBounds) boundText(rule, rule.formula2, rule.max, symbols) else "",
            source = if (rule.type == ValidationType.LIST) rule.formula1?.let { formula(it) } ?: rule.items.joinToString(listSeparator(symbols)) else defaultList,
            formula = if (rule.type == ValidationType.CUSTOM) rule.formula1?.let { formula(it) } ?: "" else "",
            dropdown = rule.dropdown, ignoreBlank = rule.allowBlank,
            showInput = rule.showInput, inputTitle = rule.inputTitle, inputMessage = rule.inputMessage,
            showError = rule.showError, errorStyle = rule.errorStyle, errorTitle = rule.errorTitle, errorMessage = rule.errorMessage,
        )
    }

    private fun boundText(rule: DataValidation, formula: String?, v: Double, symbols: NumberSymbols): String =
        formula?.let { FormulaLocale.toLocal("=$it", symbols) }
            ?: rule.boundText(v).let { if (rule.type == ValidationType.DATE || rule.type == ValidationType.TIME) it else symbols.local(it) }

    /** Between a typed list's values: `,` — or `;` where the comma is the decimal symbol (Excel there). */
    private fun listSeparator(symbols: NumberSymbols) = if (symbols.argument == ';') ";" else ","

    /** The rule [f] makes (on no cells yet — the editor puts it on the selection), or why it cannot. */
    fun ruleOf(f: Fields, symbols: NumberSymbols = NumberSymbols.CANONICAL): Pair<DataValidation?, Problem?> {
        var min = 0.0; var max = 0.0
        var formula1: String? = null; var formula2: String? = null
        var items = emptyList<String>()
        when {
            f.type.compares -> {
                val a = bound(f.minimum, f.type, symbols) ?: return null to Problem.BOUND
                min = a.first; formula1 = a.second
                if (f.operator.twoBounds) {
                    val b = bound(f.maximum, f.type, symbols) ?: return null to Problem.BOUND
                    max = b.first; formula2 = b.second
                    if (formula1 == null && formula2 == null && max < min) return null to Problem.MIN_OVER_MAX
                }
            }
            f.type == ValidationType.LIST -> {
                val s = f.source.trim()
                if (s.startsWith("=")) formula1 = FormulaLocale.toCanonical(s, symbols).substring(1).trim().takeIf { it.isNotEmpty() } ?: return null to Problem.LIST
                else items = (if (symbols.argument == ';') s.split(';', '；') else s.split(',', '，', '、')).map { it.trim() }.filter { it.isNotEmpty() }
                if (formula1 == null && items.isEmpty()) return null to Problem.LIST
                if (formula1 == null && items.joinToString(",").length + 2 > MAX_LIST_CHARS) return null to Problem.LIST_TOO_LONG
            }
            f.type == ValidationType.CUSTOM -> formula1 = FormulaLocale.toCanonical(f.formula.trim(), symbols).removePrefix("=").trim().takeIf { it.isNotEmpty() } ?: return null to Problem.FORMULA
        }
        return DataValidation(
            0, 0, 0, 0, f.type, min, max, items, if (f.type.compares) f.operator else ValidationOperator.BETWEEN, formula1, formula2,
            allowBlank = f.ignoreBlank, dropdown = f.dropdown,
            showInput = f.showInput, inputTitle = f.inputTitle.trim(), inputMessage = f.inputMessage.trim(),
            showError = f.showError, errorStyle = f.errorStyle, errorTitle = f.errorTitle.trim(), errorMessage = f.errorMessage.trim(),
        ) to null
    }

    /** A bound as typed: a number (for dates the serial, for times the fraction of a day) and no formula, or a formula; null when it is neither. */
    fun bound(text: String, type: ValidationType, symbols: NumberSymbols = NumberSymbols.CANONICAL): Pair<Double, String?>? {
        val t = text.trim()
        if (t.startsWith("=")) return FormulaLocale.toCanonical(t, symbols).substring(1).trim().takeIf { it.isNotEmpty() }?.let { 0.0 to it }
        if (symbols.canonical) t.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it to null }
        val entry = EntryParser.parse(t, symbols = symbols) ?: return t.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { it to null }
        val v = entry.value.toDoubleOrNull() ?: return null
        // A time rule's bound is a time of day: a date typed with it keeps only the time.
        return (if (type == ValidationType.TIME && v >= 1.0) v - kotlin.math.floor(v) else v) to null
    }
}
