package com.therealityteam.aeropages.model

/**
 * A formula's value as a file keeps it beside the formula — an xlsx cell's `<v>`, an ODS cell's
 * `office:value` — so a reader that does not work formulas out (a preview, a script) still sees it.
 */
sealed interface CachedValue {
    data class Number(val value: Double) : CachedValue
    data class Text(val value: String) : CachedValue
    data class Logical(val value: Boolean) : CachedValue
    /** An error, [code] as Excel names it (`#N/A`, `#DIV/0!`) or this app's own (`#CYCLE`). */
    data class Error(val code: String) : CachedValue

    companion object {
        /** The error values Excel's files know: the ones a cell's cached value may be. */
        val EXCEL_ERRORS = setOf("#NULL!", "#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A")
    }
}
