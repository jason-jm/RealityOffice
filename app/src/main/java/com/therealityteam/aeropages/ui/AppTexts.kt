package com.therealityteam.aeropages.ui

import android.content.Context
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.model.TextId
import com.therealityteam.aeropages.model.Texts

/**
 * Routes the model/editor/format layers' [Texts] through Android string resources
 * (the `txt_*` entries of the string resources), so generated content and messages follow
 * the device language like the rest of the UI.
 */
internal object AppTexts {
    fun install(context: Context) {
        val app = context.applicationContext
        Texts.provider = { id, args -> if (args.isEmpty()) app.getString(res(id)) else app.getString(res(id), *args) }
    }

    private fun res(id: TextId): Int = when (id) {
        TextId.COMMENT_AUTHOR_ME -> R.string.txt_comment_author_me
        TextId.NOTE_PRINT_CELL -> R.string.txt_note_print_cell
        TextId.NOTE_PRINT_COMMENT -> R.string.txt_note_print_comment
        TextId.PROOF_REPEATED_WORD -> R.string.txt_proof_repeated_word
        TextId.PROOF_DOUBLE_SPACE -> R.string.txt_proof_double_space
        TextId.ENDNOTES_HEADING -> R.string.txt_endnotes_heading
        TextId.STYLE_NORMAL -> R.string.txt_style_normal
        TextId.STYLE_TITLE -> R.string.txt_style_title
        TextId.STYLE_HEADING1 -> R.string.txt_style_heading1
        TextId.STYLE_HEADING2 -> R.string.txt_style_heading2
        TextId.STYLE_HEADING3 -> R.string.txt_style_heading3
        TextId.STYLE_QUOTE -> R.string.txt_style_quote
        TextId.SMARTART_TEXT -> R.string.txt_smartart_text
        TextId.WORDART_TEXT -> R.string.txt_wordart_text
        TextId.PIVOT_SUM -> R.string.txt_pivot_sum
        TextId.PIVOT_COUNT -> R.string.txt_pivot_count
        TextId.PIVOT_AVERAGE -> R.string.txt_pivot_average
        TextId.PIVOT_MAX -> R.string.txt_pivot_max
        TextId.PIVOT_MIN -> R.string.txt_pivot_min
        TextId.ROW_N -> R.string.txt_row_n
        TextId.CHART_N -> R.string.txt_chart_n
        TextId.PICTURE_N -> R.string.txt_picture_n
        TextId.PIVOT_BLANK -> R.string.txt_pivot_blank
        TextId.PIVOT_SHEET_N -> R.string.txt_pivot_sheet_n
        TextId.SHEET_N -> R.string.txt_sheet_n
        TextId.TOTAL -> R.string.txt_total
        TextId.VALIDATION_INTEGER_REQUIRED -> R.string.txt_validation_integer_required
        TextId.VALIDATION_NUMBER_REQUIRED -> R.string.txt_validation_number_required
        TextId.VALIDATION_DATE_REQUIRED -> R.string.txt_validation_date_required
        TextId.VALIDATION_TIME_REQUIRED -> R.string.txt_validation_time_required
        TextId.VALIDATION_KIND_INTEGER -> R.string.txt_validation_kind_integer
        TextId.VALIDATION_KIND_NUMBER -> R.string.txt_validation_kind_number
        TextId.VALIDATION_KIND_DATE -> R.string.txt_validation_kind_date
        TextId.VALIDATION_KIND_TIME -> R.string.txt_validation_kind_time
        TextId.VALIDATION_KIND_LENGTH -> R.string.txt_validation_kind_length
        TextId.VALIDATION_OP_BETWEEN -> R.string.txt_validation_op_between
        TextId.VALIDATION_OP_NOT_BETWEEN -> R.string.txt_validation_op_not_between
        TextId.VALIDATION_OP_EQUAL -> R.string.txt_validation_op_equal
        TextId.VALIDATION_OP_NOT_EQUAL -> R.string.txt_validation_op_not_equal
        TextId.VALIDATION_OP_GREATER -> R.string.txt_validation_op_greater
        TextId.VALIDATION_OP_LESS -> R.string.txt_validation_op_less
        TextId.VALIDATION_OP_GREATER_OR_EQUAL -> R.string.txt_validation_op_greater_or_equal
        TextId.VALIDATION_OP_LESS_OR_EQUAL -> R.string.txt_validation_op_less_or_equal
        TextId.VALIDATION_CUSTOM -> R.string.txt_validation_custom
        TextId.VALIDATION_LIST_ONLY -> R.string.txt_validation_list_only
        TextId.LIST_JOINER -> R.string.txt_list_joiner
        TextId.TOC_HEADING -> R.string.txt_toc_heading
        TextId.BIBLIOGRAPHY_HEADING -> R.string.txt_bibliography_heading
        TextId.SERIES_N -> R.string.txt_series_n
        TextId.CATEGORY_N -> R.string.txt_category_n
        TextId.CHART_TITLE_DEFAULT -> R.string.txt_chart_title_default
        TextId.SCENARIO_SUMMARY_SHEET -> R.string.scenario_summary_sheet
        TextId.SCENARIO_SUMMARY_CELL -> R.string.scenario_summary_cell
        TextId.SCENARIO_CURRENT -> R.string.scenario_current
        TextId.SUBTOTAL_TOTAL -> R.string.subtotal_total_label
        TextId.SUBTOTAL_GRAND -> R.string.subtotal_grand_label
        TextId.TOC_EMPTY -> R.string.msg_toc_empty
        TextId.FIELD_STYLE_MISSING -> R.string.txt_field_style_missing
        TextId.FIELD_FORMULA_ERROR -> R.string.txt_field_formula_error
        TextId.TOF_EMPTY -> R.string.msg_tof_empty
        TextId.INDEX_EMPTY -> R.string.msg_index_empty
        TextId.COVER_TITLE -> R.string.txt_cover_title
        TextId.COVER_SUBTITLE -> R.string.txt_cover_subtitle
        TextId.COVER_AUTHOR -> R.string.txt_cover_author
        TextId.BIBLIOGRAPHY_EMPTY -> R.string.msg_bibliography_empty
        TextId.UNTITLED_HEADING -> R.string.label_untitled_heading
        TextId.TABLE_TOTAL -> R.string.txt_table_total
        TextId.SHAPE_N -> R.string.txt_shape_n
        TextId.FIELD_REF_MISSING -> R.string.txt_field_ref_missing
        TextId.FIELD_ABOVE -> R.string.txt_field_above
        TextId.FIELD_BELOW -> R.string.txt_field_below
        TextId.FIELD_ON_PAGE -> R.string.txt_field_on_page
    }
}
