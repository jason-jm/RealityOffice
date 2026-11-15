package com.therealityteam.aeropages.model

import java.util.Locale

/**
 * User-visible strings produced outside the UI layer — default content ("Click to add
 * title"), generated labels (pivot sheet names, chart series), validation and proofing
 * messages, and the headings the writers/PDF exporter emit. Each carries its English
 * fallback; the app installs an Android-resources provider at startup ([Texts.provider])
 * so the same ids resolve through the `txt_*` string resources in the device language.
 * Arguments use `%1$s`-style positional placeholders.
 */
enum class TextId(val fallback: String) {
    COMMENT_AUTHOR_ME("Me"),
    /** Excel's printed list of notes at the end of a sheet: "Cell: A2", then "Comment: Ann:" and the note. */
    NOTE_PRINT_CELL("Cell: %1\$s"),
    NOTE_PRINT_COMMENT("Comment: %1\$s"),
    PROOF_REPEATED_WORD("Repeated word: \"%1\$s\""),
    PROOF_DOUBLE_SPACE("Double space"),
    ENDNOTES_HEADING("Endnotes"),
    STYLE_NORMAL("Normal"),
    STYLE_TITLE("Title"),
    STYLE_HEADING1("Heading 1"),
    STYLE_HEADING2("Heading 2"),
    STYLE_HEADING3("Heading 3"),
    STYLE_QUOTE("Quote"),
    SMARTART_TEXT("Text"),
    WORDART_TEXT("WordArt"),
    PIVOT_SUM("Sum"),
    PIVOT_COUNT("Count"),
    PIVOT_AVERAGE("Average"),
    PIVOT_MAX("Max"),
    PIVOT_MIN("Min"),
    ROW_N("Row %1\$s"),
    CHART_N("Chart %1\$s"),
    PICTURE_N("Picture %1\$s"),
    PIVOT_BLANK("(blank)"),
    PIVOT_SHEET_N("PivotTable%1\$s"),
    /** A new sheet's name, as the language's Excel names it (Sheet1, Tabelle1, Feuil1, Лист1…). */
    SHEET_N("Sheet%1\$d"),
    TOTAL("Grand Total"),
    VALIDATION_INTEGER_REQUIRED("Enter a whole number"),
    VALIDATION_NUMBER_REQUIRED("Enter a number"),
    VALIDATION_DATE_REQUIRED("Enter a date"),
    VALIDATION_TIME_REQUIRED("Enter a time"),
    VALIDATION_KIND_INTEGER("The whole number"),
    VALIDATION_KIND_NUMBER("The number"),
    VALIDATION_KIND_DATE("The date"),
    VALIDATION_KIND_TIME("The time"),
    VALIDATION_KIND_LENGTH("The text length"),
    VALIDATION_OP_BETWEEN("%1\$s must be between %2\$s and %3\$s"),
    VALIDATION_OP_NOT_BETWEEN("%1\$s must not be between %2\$s and %3\$s"),
    VALIDATION_OP_EQUAL("%1\$s must be %2\$s"),
    VALIDATION_OP_NOT_EQUAL("%1\$s must not be %2\$s"),
    VALIDATION_OP_GREATER("%1\$s must be greater than %2\$s"),
    VALIDATION_OP_LESS("%1\$s must be less than %2\$s"),
    VALIDATION_OP_GREATER_OR_EQUAL("%1\$s must be %2\$s or more"),
    VALIDATION_OP_LESS_OR_EQUAL("%1\$s must be %2\$s or less"),
    VALIDATION_CUSTOM("This value doesn't match the rule for this cell"),
    VALIDATION_LIST_ONLY("Allowed values: %1\$s"),
    LIST_JOINER(", "),
    TOC_HEADING("Table of Contents"),
    BIBLIOGRAPHY_HEADING("Bibliography"),
    SERIES_N("Series %1\$s"),
    CATEGORY_N("Category %1\$s"),
    CHART_TITLE_DEFAULT("Chart Title"),
    SCENARIO_SUMMARY_SHEET("Scenario Summary"),
    SCENARIO_SUMMARY_CELL("Cell"),
    SCENARIO_CURRENT("Current Values"),
    SUBTOTAL_TOTAL("%s Total"),
    SUBTOTAL_GRAND("Grand Total"),
    FIELD_STYLE_MISSING("Error! No text of specified style in document."),
    FIELD_FORMULA_ERROR("!Syntax Error"),
    TOC_EMPTY("(No headings. Apply a heading style to a paragraph to have it appear in the table of contents.)"),
    TOF_EMPTY("(No table of figures entries found. Insert a caption with this label to list it here.)"),
    INDEX_EMPTY("No index entries found."),
    COVER_TITLE("[Document title]"),
    COVER_SUBTITLE("[Document subtitle]"),
    COVER_AUTHOR("[Author name]"),
    BIBLIOGRAPHY_EMPTY("(No sources. Manage sources from the \"References\" tab.)"),
    UNTITLED_HEADING("(Untitled)"),
    TABLE_TOTAL("Total"),
    SHAPE_N("Shape %1\$s"),
    FIELD_REF_MISSING("Error! Reference source not found."),
    FIELD_ABOVE("above"),
    FIELD_BELOW("below"),
    FIELD_ON_PAGE("on page %1\$s"),
}

/** Locale-aware lookup for [TextId]s; see the enum's documentation. */
object Texts {
    /** Resolves an id with its arguments. Defaults to the English fallbacks (JVM tests, early startup). */
    @Volatile
    var provider: (TextId, Array<out Any>) -> String = { id, args ->
        if (args.isEmpty()) id.fallback else String.format(Locale.getDefault(), id.fallback, *args)
    }

    fun get(id: TextId, vararg args: Any): String = provider(id, args)
}
