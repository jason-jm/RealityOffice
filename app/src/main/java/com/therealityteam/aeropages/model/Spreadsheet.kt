package com.therealityteam.aeropages.model

/**
 * Excel (Sheets) model. A workbook of sheets; each sheet is a sparse grid of cells
 * addressed by (row, col). Cells carry text plus a reused [CharacterFormat] so the
 * Word formatting stack (bold/colour/size) applies directly.
 */
/** Display number format for a cell (applied to numeric values / formula results). */
enum class NumberFormat { GENERAL, NUMBER2, CURRENCY_CNY, CURRENCY_USD, PERCENT, THOUSANDS, DATE, TIME, DATE_TIME, SCIENTIFIC }

/** Vertical alignment within a cell. */
enum class VAlign { TOP, MIDDLE, BOTTOM }

/**
 * Excel's border line styles (its `ST_BorderStyle`), thinnest first within each family, with how
 * they are drawn: [width] in dp (points on paper) and a [dash] pattern (null = solid); a double
 * line is two thin lines apart.
 */
enum class LineStyle(val xlsx: String, val width: Float, val dash: FloatArray? = null) {
    HAIR("hair", 0.5f), THIN("thin", 1f), DOTTED("dotted", 1f, floatArrayOf(1f, 2f)), DASHED("dashed", 1f, floatArrayOf(4f, 2f)),
    DASH_DOT("dashDot", 1f, floatArrayOf(4f, 2f, 1f, 2f)), DASH_DOT_DOT("dashDotDot", 1f, floatArrayOf(4f, 2f, 1f, 2f, 1f, 2f)),
    MEDIUM("medium", 2f), MEDIUM_DASHED("mediumDashed", 2f, floatArrayOf(6f, 3f)), MEDIUM_DASH_DOT("mediumDashDot", 2f, floatArrayOf(6f, 3f, 2f, 3f)),
    MEDIUM_DASH_DOT_DOT("mediumDashDotDot", 2f, floatArrayOf(6f, 3f, 2f, 3f, 2f, 3f)), SLANT_DASH_DOT("slantDashDot", 2f, floatArrayOf(6f, 2f, 2f, 2f)),
    THICK("thick", 3f), DOUBLE("double", 1f);

    companion object {
        fun ofXlsx(name: String?): LineStyle? = entries.firstOrNull { it.xlsx == name }
    }
}

/** One side's border: its line style and colour (0xAARRGGBB; null = automatic, black). */
data class BorderLine(val style: LineStyle = LineStyle.THIN, val color: Long? = null) {
    companion object {
        val THIN = BorderLine()
        val THICK = BorderLine(LineStyle.THICK)
        val DOUBLE = BorderLine(LineStyle.DOUBLE)
    }
}

class Cell(
    var text: String = "",
    val format: CharacterFormat = CharacterFormat(),
    /** Horizontal alignment; null = Excel's General (numbers right, text left, TRUE / FALSE and errors centred). */
    var align: TextAlign? = null,
    var numberFormat: NumberFormat = NumberFormat.GENERAL,
    /** Cell borders per side, with Excel's line styles and colours; null = none. */
    var borderTop: BorderLine? = null,
    var borderBottom: BorderLine? = null,
    var borderLeft: BorderLine? = null,
    var borderRight: BorderLine? = null,
    /** Wrap text within the cell. */
    var wrap: Boolean = false,
    /** Vertical alignment within the cell. */
    var vAlign: VAlign = VAlign.MIDDLE,
    /** Cell comment / note text. Null = none. */
    var comment: String? = null,
    /** Decimal places override for numeric formats; -1 = the format's default. */
    var decimals: Int = -1,
    /** Hyperlink target (external URL), or null. Rendered blue/underlined; round-trips through xlsx `<hyperlinks>`. */
    var link: String? = null,
    /** Editable even while the sheet is protected (Excel's "Locked" off; OOXML `protection@locked=0`). */
    var unlocked: Boolean = false,
    /**
     * Mixed formatting inside the cell (Excel's rich text runs, Calc's `text:span`s): the runs
     * concatenate to [text]. Null = the whole cell uses [format]. Editing the text drops them.
     */
    var runs: MutableList<TextRun>? = null,
    /**
     * An Excel number format code (`yyyy"年"m"月"d"日"`, `#,##0.00;[Red]-#,##0.00`, `@` …) that
     * decides what the value shows, see [FormatCode]; [numberFormat] then only names its kind
     * (a date, a percentage …) for what reads that. Null = [numberFormat] with [decimals].
     */
    var formatCode: String? = null,
    /** Orientation: the text turned [rotation] degrees counterclockwise (−90…90; xlsx `textRotation`, ODS `style:rotation-angle`). */
    var rotation: Int = 0,
    /** Orientation → Vertical Text: one character under another (xlsx `textRotation="255"`, ODS `style:direction="ttb"`). */
    var stacked: Boolean = false,
    /** Increase / Decrease Indent: levels of [INDENT_PT] from the aligned edge (xlsx `indent`, ODS `fo:margin-left`). */
    var indent: Int = 0,
    /** Excel's other horizontal alignments — Fill, Center Across Selection, Distributed; [align] then holds the nearest plain one. */
    var spread: CellSpread? = null,
    /** Format Cells → Shrink to fit: the type made smaller until the text fits the cell's width (xlsx `shrinkToFit`, ODS `style:shrink-to-fit`). */
    var shrink: Boolean = false,
) {
    /** Who wrote the note (Excel's comment author, ODF's `dc:creator`; null: not known) and when (epoch ms; 0: not known). */
    var commentAuthor: String? = null
    var commentDate: Long = 0L
    /** The note stays shown beside its cell (Excel's Show/Hide Note; VML `x:Visible`, ODF `office:display`). */
    var commentShown: Boolean = false

    fun copyOf(): Cell = Cell(text, format.copyOf(), align, numberFormat, borderTop, borderBottom, borderLeft, borderRight, wrap, vAlign, comment, decimals, link, unlocked,
        runs?.map { TextRun(it.text, it.format.copyOf()) }?.toMutableList(), formatCode, rotation, stacked, indent, spread, shrink)
        .also { it.commentAuthor = commentAuthor; it.commentDate = commentDate; it.commentShown = commentShown }

    /** Whether its text is turned or stacked (it then neither spills into its neighbours nor wraps). */
    val turned: Boolean get() = rotation != 0 || stacked

    // Whether a side has a border; setting one keeps its line (or draws a thin one), clearing removes it.
    var bTop: Boolean get() = borderTop != null; set(v) { borderTop = if (v) borderTop ?: BorderLine.THIN else null }
    var bBottom: Boolean get() = borderBottom != null; set(v) { borderBottom = if (v) borderBottom ?: BorderLine.THIN else null }
    var bLeft: Boolean get() = borderLeft != null; set(v) { borderLeft = if (v) borderLeft ?: BorderLine.THIN else null }
    var bRight: Boolean get() = borderRight != null; set(v) { borderRight = if (v) borderRight ?: BorderLine.THIN else null }

    /** The rich runs when they still describe [text] (a bulk edit may have changed the text underneath), else null. */
    fun richRuns(): List<TextRun>? = runs?.takeIf { r -> r.isNotEmpty() && r.joinToString("") { it.text } == text }

    /**
     * The number this cell holds as a file stores numbers, else null — its text is kept as text:
     * a plain decimal or scientific literal without leading zeros (`00123` is a code, not 123), at
     * most 15 significant digits (a longer run of digits would come back rounded), in a cell not
     * formatted as text (`@`).
     */
    fun storedNumber(): Double? {
        if (formatCode == "@") return null
        if (!NUMBER_LITERAL.matches(text)) return null
        val mantissa = text.substringBefore('e').substringBefore('E').filter { it.isDigit() }.trimStart('0')
        if (mantissa.trimEnd('0').length > 15) return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** The logical value this cell holds — TRUE or FALSE, as typed or read from a file — else null (text, a number, a formula). */
    fun storedLogical(): Boolean? = if (formatCode == "@") null else when (text) { "TRUE" -> true; "FALSE" -> false; else -> null }

    companion object {
        private val NUMBER_LITERAL = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
        /** One indent level: three spaces of the standard font, as Excel and LibreOffice (0.265 cm) take it. */
        const val INDENT_PT = 7.5f
        /** The most indent levels Increase Indent goes to (Excel's Format Cells box goes to 15 in practice). */
        const val MAX_INDENT = 15

        /** [text]'s letters one under another as Vertical Text stacks them: whole characters (a Thai vowel mark stays on its letter), no line breaks. */
        fun stackedLetters(text: String): List<String> {
            val flat = text.replace("\n", "")
            val it = java.text.BreakIterator.getCharacterInstance().apply { setText(flat) }
            val out = ArrayList<String>()
            var start = it.first(); var end = it.next()
            while (end != java.text.BreakIterator.DONE) { out.add(flat.substring(start, end)); start = end; end = it.next() }
            return out
        }
    }
}

/**
 * Excel's horizontal alignments beyond left, centre, right and justify: [FILL] repeats the text across the cell
 * (xlsx `fill`, ODS `style:repeat-content`), [CENTER_ACROSS] centres it over the empty cells after it that are set the
 * same way (`centerContinuous`; ODS has none, so it is written centred), [DISTRIBUTED] spreads its letters edge to edge
 * (`distributed`, ODS `css3t:text-justify="distribute"`).
 */
enum class CellSpread(val plain: TextAlign) { FILL(TextAlign.LEFT), CENTER_ACROSS(TextAlign.CENTER), DISTRIBUTED(TextAlign.JUSTIFY) }

/** Paper sizes for printing / PDF export (points; [xlsxId] is OOXML `pageSetup@paperSize`), with their names as Excel lists them. */
enum class Paper(val widthPt: Int, val heightPt: Int, val xlsxId: Int, val label: String) {
    A4(595, 842, 9, "A4"), LETTER(612, 792, 1, "Letter"), A3(842, 1191, 8, "A3"), A5(420, 595, 11, "A5"),
    B5(516, 729, 13, "B5 (JIS)"), LEGAL(612, 1008, 5, "Legal"), EXECUTIVE(522, 756, 7, "Executive");

    companion object {
        fun fromXlsxId(id: Int?): Paper = entries.firstOrNull { it.xlsxId == id } ?: A4
        /** The paper [widthPt] × [heightPt] is, either way round (within two points). */
        fun ofSize(widthPt: Float, heightPt: Float): Paper? {
            val w = minOf(widthPt, heightPt); val h = maxOf(widthPt, heightPt)
            return entries.firstOrNull { kotlin.math.abs(it.widthPt - w) < 2f && kotlin.math.abs(it.heightPt - h) < 2f }
        }
    }
}

/**
 * How a sheet prints, past its paper and orientation (Excel's Page Setup; OOXML `pageMargins`, `pageSetup` scale and
 * fit, `printOptions`, `headerFooter` and the `_xlnm.Print_Titles` name; ODF's page layout). Margins in points — Excel's
 * Normal: 0.75 in above and below, 0.7 in at the sides, the header and footer 0.3 in from the edge. [scale] percent of
 * normal size, or — [fitWidth] / [fitHeight] not null — shrunk to so many pages wide / tall (0: as many as it takes).
 * [gridlines] and [headings]: printed or not (Excel prints neither unless asked); [centerH] / [centerV]: the cells
 * centred on the page. [titleRows] / [titleCols]: rows repeated at the top of every page, columns at its left (zero-based,
 * inclusive). [header] / [footer]: left, centre and right parts in Excel's codes (&P page, &N pages, &A sheet, &F file,
 * &D date, &T time).
 */
/** How a sheet's notes print (xlsx `pageSetup@cellComments`: none, atEnd, asDisplayed; ODS's `style:print` "annotations" = at end). */
enum class NotePrint(val xlsx: String) { NONE("none"), AT_END("atEnd"), AS_DISPLAYED("asDisplayed");
    companion object { fun of(xlsx: String?): NotePrint = entries.firstOrNull { it.xlsx == xlsx } ?: NONE }
}

/** How cells showing an error print (xlsx `pageSetup@errors`): as they show, blank, "--", or "#N/A". */
enum class ErrorPrint(val xlsx: String, val text: String?) { DISPLAYED("displayed", null), BLANK("blank", ""), DASH("dash", "--"), NA("NA", "#N/A");
    companion object { fun of(xlsx: String?): ErrorPrint = entries.firstOrNull { it.xlsx == xlsx } ?: DISPLAYED }
}

data class SheetPrint(
    val marginTop: Float = 54f, val marginBottom: Float = 54f, val marginLeft: Float = 50.4f, val marginRight: Float = 50.4f,
    val marginHeader: Float = 21.6f, val marginFooter: Float = 21.6f,
    val scale: Int = 100,
    val fitWidth: Int? = null, val fitHeight: Int? = null,
    val gridlines: Boolean = false, val headings: Boolean = false,
    val centerH: Boolean = false, val centerV: Boolean = false,
    val titleRows: IntRange? = null, val titleCols: IntRange? = null,
    val header: List<String> = EMPTY_PARTS, val footer: List<String> = EMPTY_PARTS,
    /**
     * Excel's manual page breaks (Page Layout ▸ Breaks; xlsx `rowBreaks` / `colBreaks`, ODS `fo:break-before` on a row's
     * or column's style): the rows and columns that start a new page, zero-based, ascending. Fitting to pages ignores them.
     */
    val rowBreaks: List<Int> = emptyList(), val colBreaks: List<Int> = emptyList(),
    /** Excel's Page order: across the columns and then down (true), or Excel's default — down the rows, then over. */
    val overThenDown: Boolean = false,
    /** Excel's Sheet ▸ Comments: notes not printed, listed at the end of the sheet's pages, or printed as shown beside their cells. */
    val comments: NotePrint = NotePrint.NONE,
    /** Excel's Sheet ▸ Black and white: no cell fills, text and borders in black, pictures in greys. */
    val blackAndWhite: Boolean = false,
    /** Excel's Sheet ▸ Draft quality: no gridlines and no pictures, shapes or charts. */
    val draft: Boolean = false,
    /** Excel's Sheet ▸ Cell errors as: shown, blank, "--" or "#N/A". */
    val errors: ErrorPrint = ErrorPrint.DISPLAYED,
) {
    val fits: Boolean get() = fitWidth != null || fitHeight != null

    companion object {
        val EMPTY_PARTS = listOf("", "", "")

        /** Excel's Margins gallery: Normal, Wide, Narrow (top/bottom, left/right, header/footer). */
        val NORMAL = SheetPrint()
        val WIDE = SheetPrint(72f, 72f, 72f, 72f, 36f, 36f)
        val NARROW = SheetPrint(54f, 54f, 18f, 18f, 21.6f, 21.6f)

        /** [p]'s header or footer as one of Excel's `&L…&C…&R…` strings, its parts' codes kept. */
        fun toExcel(parts: List<String>): String = buildString {
            if (parts.getOrElse(0) { "" }.isNotEmpty()) append("&L").append(parts[0])
            if (parts.getOrElse(1) { "" }.isNotEmpty()) append("&C").append(parts[1])
            if (parts.getOrElse(2) { "" }.isNotEmpty()) append("&R").append(parts[2])
        }

        /** An Excel header or footer (`&L…&C…&R…`) as its left, centre and right parts; text before any section code is the centre's. */
        fun fromExcel(code: String?): List<String> {
            if (code.isNullOrEmpty()) return EMPTY_PARTS
            val parts = arrayOf(StringBuilder(), StringBuilder(), StringBuilder())
            var cur = 1
            var i = 0
            while (i < code.length) {
                val c = code[i]
                if (c == '&' && i + 1 < code.length) {
                    when (code[i + 1]) {
                        'L' -> { cur = 0; i += 2; continue }
                        'C' -> { cur = 1; i += 2; continue }
                        'R' -> { cur = 2; i += 2; continue }
                    }
                }
                parts[cur].append(c); i++
            }
            return parts.map { it.toString() }
        }

        /**
         * A header part as it prints on page [page] of [pages] of sheet [sheet] in file [file]: its fields filled in, its
         * look codes (&"font,style", &12, &B, &I, &U …) taken out, && an ampersand.
         */
        fun fill(part: String, page: Int, pages: Int, sheet: String, file: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
            if ('&' !in part) return part
            val out = StringBuilder()
            var i = 0
            while (i < part.length) {
                val c = part[i]
                if (c != '&' || i + 1 >= part.length) { out.append(c); i++; continue }
                val n = part[i + 1]
                when {
                    n == '&' -> { out.append('&'); i += 2 }
                    n == 'P' -> { out.append(page); i += 2 }
                    n == 'N' -> { out.append(pages); i += 2 }
                    n == 'A' -> { out.append(sheet); i += 2 }
                    n == 'F' -> { out.append(file); i += 2 }
                    n == 'D' -> { out.append(now.toLocalDate().toString()); i += 2 }
                    n == 'T' -> { out.append(String.format(java.util.Locale.US, "%d:%02d", now.hour, now.minute)); i += 2 }
                    n == '"' -> { val e = part.indexOf('"', i + 2); i = if (e < 0) part.length else e + 1 }
                    n.isDigit() -> { i += 2; while (i < part.length && part[i].isDigit()) i++ }
                    else -> i += 2   // &B &I &U &S &X &Y &K…: looks, not text
                }
            }
            return out.toString()
        }
    }
}

/** The rectangle a sheet prints / exports (zero-based inclusive); null = the used range. */
class PrintArea(val r1: Int, val c1: Int, val r2: Int, val c2: Int) {
    fun copyOf(): PrintArea = PrintArea(r1, c1, r2, c2)
}

/** What a validation rule allows (Excel's Data Validation → Allow), with its OOXML name. */
enum class ValidationType(val xlsx: String) {
    ANY("none"), INTEGER("whole"), DECIMAL("decimal"), LIST("list"), DATE("date"), TIME("time"), TEXT_LENGTH("textLength"), CUSTOM("custom");

    /** The kinds that compare a value with bounds through an operator. */
    val compares: Boolean get() = this == INTEGER || this == DECIMAL || this == DATE || this == TIME || this == TEXT_LENGTH

    companion object {
        fun ofXlsx(name: String?): ValidationType? = entries.firstOrNull { it.xlsx == name }
    }
}

/** How a whole number, decimal, date, time or text length compares with a rule's bounds (Excel's Data list). */
enum class ValidationOperator(val xlsx: String) {
    BETWEEN("between"), NOT_BETWEEN("notBetween"), EQUAL("equal"), NOT_EQUAL("notEqual"),
    GREATER("greaterThan"), LESS("lessThan"), GREATER_OR_EQUAL("greaterThanOrEqual"), LESS_OR_EQUAL("lessThanOrEqual");

    /** Between and not between take a minimum and a maximum; the others one value. */
    val twoBounds: Boolean get() = this == BETWEEN || this == NOT_BETWEEN

    fun test(v: Double, a: Double, b: Double): Boolean = when (this) {
        BETWEEN -> v >= minOf(a, b) && v <= maxOf(a, b)
        NOT_BETWEEN -> v < minOf(a, b) || v > maxOf(a, b)
        EQUAL -> v == a
        NOT_EQUAL -> v != a
        GREATER -> v > a
        LESS -> v < a
        GREATER_OR_EQUAL -> v >= a
        LESS_OR_EQUAL -> v <= a
    }

    companion object {
        fun ofXlsx(name: String?): ValidationOperator = entries.firstOrNull { it.xlsx == name } ?: BETWEEN
    }
}

/** Excel's error alert styles: Stop refuses an invalid entry, Warning asks whether to keep it, Information keeps it after saying so. */
enum class ValidationAlertStyle(val xlsx: String) {
    STOP("stop"), WARNING("warning"), INFORMATION("information");

    companion object {
        fun ofXlsx(name: String?): ValidationAlertStyle = entries.firstOrNull { it.xlsx == name } ?: STOP
    }
}

/**
 * What a rule's formulas mean at a cell. The editor supplies one that reads entries as Excel does
 * and evaluates formulas against the workbook; [None] knows plain numbers only.
 */
interface ValidationScope {
    /** The number an entry stands for (`15%`, `2026-09-29`, `9:30`, `1,000`), or null when it is none. */
    fun number(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
    /** A bound's [formula] as a number at ([row], [col]) of [rule] — relative references move from its top-left cell. */
    fun evaluate(formula: String, rule: DataValidation, row: Int, col: Int): Double? = number(formula)
    /** The values a list's [source] (a range, a name, INDIRECT of one) holds at ([row], [col]). */
    fun values(source: String, rule: DataValidation, row: Int, col: Int): List<String> = emptyList()
    /** Whether a Custom rule's [formula] is TRUE at ([row], [col]). */
    fun isTrue(formula: String, rule: DataValidation, row: Int, col: Int): Boolean = true

    object None : ValidationScope
}

/**
 * Excel's Data Validation on the cells [r1],[c1]..[r2],[c2]: what they allow — a [type] compared by
 * [operator] with its bounds, one of a list, or a Custom formula that must be TRUE — the input
 * message selecting one shows, and the alert an invalid entry raises.
 *
 * A bound is the number [min] / [max] (a date serial for dates, a fraction of a day for times)
 * unless [formula1] / [formula2] give it as a formula (`TODAY()`, `$B$1`). A list is [items] unless
 * [formula1] names its source (`$A$1:$A$5`, `Sheet2!A1:A9`, a defined name, `INDIRECT(A2)`). A Custom
 * rule is [formula1], written for the top-left cell. Formulas are kept without their `=`, as OOXML
 * keeps them.
 */
data class DataValidation(
    val r1: Int, val c1: Int, val r2: Int, val c2: Int,
    val type: ValidationType, val min: Double = 0.0, val max: Double = 0.0,
    val items: List<String> = emptyList(),
    val operator: ValidationOperator = ValidationOperator.BETWEEN,
    val formula1: String? = null,
    val formula2: String? = null,
    /** Excel's Ignore blank. Blank entries always pass here, as clearing a cell does in Excel; it is kept for the file. */
    val allowBlank: Boolean = true,
    /** A list offers its values in a drop-down on the cell (Excel's In-cell dropdown). */
    val dropdown: Boolean = true,
    val showInput: Boolean = true,
    val inputTitle: String = "",
    val inputMessage: String = "",
    /** Off, any entry is kept without an alert (Excel's Show error alert after invalid data is entered). */
    val showError: Boolean = true,
    val errorStyle: ValidationAlertStyle = ValidationAlertStyle.STOP,
    val errorTitle: String = "",
    val errorMessage: String = "",
) {
    fun covers(row: Int, col: Int): Boolean = row in r1..r2 && col in c1..c2
    fun copyOf(): DataValidation = copy()
    /** The same rule on other cells. */
    fun moved(r1: Int, c1: Int, r2: Int, c2: Int): DataValidation = copy(r1 = r1, c1 = c1, r2 = r2, c2 = c2)
    /** Selecting one of the cells shows a message. */
    val hasInputMessage: Boolean get() = showInput && (inputTitle.isNotBlank() || inputMessage.isNotBlank())

    /**
     * Null when [text] entered at ([row], [col]) passes the rule; otherwise why not, in words — the
     * alert's message when the rule has none of its own. Blank entries and formulas pass, as does
     * anything when a bound cannot be worked out. A list matches regardless of case, as Excel's does.
     */
    fun validate(text: String, row: Int = r1, col: Int = c1, scope: ValidationScope = ValidationScope.None): String? {
        if (text.isBlank() || text.startsWith("=")) return null
        return when (type) {
            ValidationType.ANY -> null
            ValidationType.LIST -> {
                // A list with nothing in it (its source all blank, or not to be found) restricts nothing, as in Excel.
                val allowed = listValues(row, col, scope).takeIf { it.isNotEmpty() } ?: return null
                val t = text.trim()
                if (allowed.any { it.trim().equals(t, ignoreCase = true) }) null
                else Texts.get(TextId.VALIDATION_LIST_ONLY, allowed.joinToString(Texts.get(TextId.LIST_JOINER)))
            }
            ValidationType.CUSTOM -> if (scope.isTrue(formula1 ?: return null, this, row, col)) null else Texts.get(TextId.VALIDATION_CUSTOM)
            else -> {
                val v = if (type == ValidationType.TEXT_LENGTH) text.length.toDouble()
                    else scope.number(text)?.takeIf { type != ValidationType.INTEGER || it == kotlin.math.floor(it) } ?: return Texts.get(requiredId())
                val a = bound(formula1, min, row, col, scope) ?: return null
                val b = if (operator.twoBounds) bound(formula2, max, row, col, scope) ?: return null else a
                if (operator.test(v, a, b)) null else rangeMessage(a, b)
            }
        }
    }

    /** What a list offers at ([row], [col]): its own items, or its source's non-blank values. */
    fun listValues(row: Int = r1, col: Int = c1, scope: ValidationScope = ValidationScope.None): List<String> =
        formula1?.let { scope.values(it, this, row, col) } ?: items

    private fun bound(formula: String?, literal: Double, row: Int, col: Int, scope: ValidationScope): Double? =
        if (formula == null) literal else scope.evaluate(formula, this, row, col)

    private fun requiredId(): TextId = when (type) {
        ValidationType.INTEGER -> TextId.VALIDATION_INTEGER_REQUIRED
        ValidationType.DATE -> TextId.VALIDATION_DATE_REQUIRED
        ValidationType.TIME -> TextId.VALIDATION_TIME_REQUIRED
        else -> TextId.VALIDATION_NUMBER_REQUIRED
    }

    /** "The whole number must be between 1 and 10", with dates and times written as such. */
    private fun rangeMessage(a: Double, b: Double): String {
        val kind = Texts.get(when (type) {
            ValidationType.INTEGER -> TextId.VALIDATION_KIND_INTEGER
            ValidationType.DATE -> TextId.VALIDATION_KIND_DATE
            ValidationType.TIME -> TextId.VALIDATION_KIND_TIME
            ValidationType.TEXT_LENGTH -> TextId.VALIDATION_KIND_LENGTH
            else -> TextId.VALIDATION_KIND_NUMBER
        })
        val lo = boundText(if (operator.twoBounds) minOf(a, b) else a)
        val hi = boundText(maxOf(a, b))
        return when (operator) {
            ValidationOperator.BETWEEN -> Texts.get(TextId.VALIDATION_OP_BETWEEN, kind, lo, hi)
            ValidationOperator.NOT_BETWEEN -> Texts.get(TextId.VALIDATION_OP_NOT_BETWEEN, kind, lo, hi)
            ValidationOperator.EQUAL -> Texts.get(TextId.VALIDATION_OP_EQUAL, kind, lo)
            ValidationOperator.NOT_EQUAL -> Texts.get(TextId.VALIDATION_OP_NOT_EQUAL, kind, lo)
            ValidationOperator.GREATER -> Texts.get(TextId.VALIDATION_OP_GREATER, kind, lo)
            ValidationOperator.LESS -> Texts.get(TextId.VALIDATION_OP_LESS, kind, lo)
            ValidationOperator.GREATER_OR_EQUAL -> Texts.get(TextId.VALIDATION_OP_GREATER_OR_EQUAL, kind, lo)
            ValidationOperator.LESS_OR_EQUAL -> Texts.get(TextId.VALIDATION_OP_LESS_OR_EQUAL, kind, lo)
        }
    }

    /** A bound as the rule reads it: a date, a time of day, or a number. */
    fun boundText(v: Double): String = when (type) {
        ValidationType.DATE -> FormatCode.format(v, if (v != kotlin.math.floor(v)) "yyyy-mm-dd h:mm" else "yyyy-mm-dd").text
        ValidationType.TIME -> FormatCode.format(v, if (kotlin.math.round(v * 86400.0) % 60.0 != 0.0) "h:mm:ss" else "h:mm").text
        else -> FormatCode.general(v)
    }
}

/**
 * Something floating over a sheet's cells — a picture or a shape — anchored to one: its top-left
 * corner is the cell ([row], [col]) plus an offset of [dxPt] × [dyPt], it is [widthPt] ×
 * [heightPt], and it moves with that cell when rows or columns come and go.
 */
interface SheetFloating {
    var row: Int
    var col: Int
    var dxPt: Float
    var dyPt: Float
    var widthPt: Float
    var heightPt: Float
    /** The name Excel lists it under (cNvPr@name). */
    var name: String
}

/**
 * A drawn shape or text box floating over a sheet (Insert → Shapes, Text Box; Excel's `xdr:sp`,
 * a line's `xdr:cxnSp`), anchored to a cell as a picture is. Its look and its text are a slide
 * shape's: the type, fill, outline, rotation, flips, the rich text and where it sits in the shape
 * ([SlideShape]'s slide position is not used).
 */
class SheetShape(
    val shape: SlideShape,
    override var row: Int,
    override var col: Int,
    override var dxPt: Float = 0f,
    override var dyPt: Float = 0f,
    override var widthPt: Float,
    override var heightPt: Float,
    override var name: String = "",
) : SheetFloating {
    fun copyOf(): SheetShape = SheetShape(shape.copyOf(), row, col, dxPt, dyPt, widthPt, heightPt, name)
}

/**
 * A picture floating over a sheet, as Excel's Insert → Pictures places one: its top-left corner is
 * the cell ([row], [col]) plus an offset of [dxPt] × [dyPt], it is [widthPt] × [heightPt] (the part a
 * crop leaves), and it moves with that cell when rows or columns come and go. [data] is the image
 * file as stored.
 */
class SheetImage(
    val data: ByteArray,
    val mime: String,
    override var row: Int,
    override var col: Int,
    override var dxPt: Float = 0f,
    override var dyPt: Float = 0f,
    override var widthPt: Float,
    override var heightPt: Float,
    override var name: String = "",
) : Croppable, SheetFloating {
    override var cropL = 0f
    override var cropT = 0f
    override var cropR = 0f
    override var cropB = 0f

    /** Its outer shadow (Excel's picture styles cast one), or none. */
    var shadow: ShapeShadow? = null

    /** Its alternative text (Excel's Alt Text): what a screen reader says for it; or [decorative]: passed by. */
    var altText: String = ""
    var decorative: Boolean = false

    fun copyOf(): SheetImage = SheetImage(data, mime, row, col, dxPt, dyPt, widthPt, heightPt, name).also {
        it.setCrop(cropL, cropT, cropR, cropB); it.shadow = shadow; it.altText = altText; it.decorative = decorative
    }
    fun sameAs(o: SheetImage): Boolean = mime == o.mime && row == o.row && col == o.col && dxPt == o.dxPt && dyPt == o.dyPt &&
        widthPt == o.widthPt && heightPt == o.heightPt && name == o.name && cropL == o.cropL && cropT == o.cropT && cropR == o.cropR && cropB == o.cropB &&
        shadow == o.shadow && altText == o.altText && decorative == o.decorative && (data === o.data || data.contentEquals(o.data))

    companion object {
        /** Excel's default row height and column width, in points: 15 pt × 64 px (48 pt). */
        const val ROW_PT = 15f
        const val COL_PT = 48f
    }
}

/** A merged cell region (zero-based inclusive). The top-left cell ([r1],[c1]) is the anchor. */
/** In-cell mini chart shape (Excel's sparkline types; "stacked" is win/loss). */
enum class SparklineType { LINE, COLUMN, WIN_LOSS }

/**
 * A sparkline: a tiny chart drawn inside cell ([row], [col]) from the values of the range
 * (r1,c1)-(r2,c2), read in row-major order. Stored per sheet; written as Excel's `x14:sparklineGroups`
 * and LibreOffice's `calcext:sparkline-groups`.
 */
class Sparkline(
    var row: Int, var col: Int,
    var r1: Int, var c1: Int, var r2: Int, var c2: Int,
    var type: SparklineType = SparklineType.LINE,
    var color: Long = 0xFF376092,
    var markers: Boolean = false,
) {
    fun copyOf(): Sparkline = Sparkline(row, col, r1, c1, r2, c2, type, color, markers)
}

class MergeRegion(val r1: Int, val c1: Int, val r2: Int, val c2: Int) {
    fun covers(row: Int, col: Int): Boolean = row in r1..r2 && col in c1..c2
    fun isAnchor(row: Int, col: Int): Boolean = row == r1 && col == c1
    fun intersects(rr1: Int, cc1: Int, rr2: Int, cc2: Int): Boolean = r1 <= rr2 && r2 >= rr1 && c1 <= cc2 && c2 >= cc1
    fun copyOf(): MergeRegion = MergeRegion(r1, c1, r2, c2)
}

/** What a conditional format's rule tests, or — a scale — draws (Excel's rule types, LibreOffice's conditions). */
enum class CondType {
    // The cell's value against one value or two (Excel's "Cell Value"): numbers by size, text by its letters.
    GREATER, LESS, EQUAL, BETWEEN, GREATER_EQUAL, LESS_EQUAL, NOT_EQUAL, NOT_BETWEEN,
    // Its value's text containing the rule's text, not containing it, beginning or ending with it (any case).
    TEXT_CONTAINS, TEXT_NOT_CONTAINS, TEXT_BEGINS, TEXT_ENDS,
    // Blank (or only spaces) or not; an error or not.
    BLANKS, NO_BLANKS, ERRORS, NO_ERRORS,
    // A value the range holds more than once, or just once.
    DUPLICATES, UNIQUES,
    // Among the range's highest or lowest N numbers (or N percent of them).
    TOP, BOTTOM,
    // Above or below the average of the range's numbers.
    ABOVE_AVERAGE, BELOW_AVERAGE,
    // A date in a period around today.
    DATE,
    // A formula true at the cell (its relative references from the range's top-left cell).
    FORMULA,
    // Scales: a bar as long as the value, a colour between two or three, an icon of a set.
    DATA_BAR, COLOR_SCALE, ICON_SET;

    /** A scale (every number in the range gets its bar, colour or icon), not a test. */
    val scale: Boolean get() = this == DATA_BAR || this == COLOR_SCALE || this == ICON_SET
}

/** A period a DATE rule's dates fall in, around today (weeks from Sunday, as Excel counts them). */
enum class DatePeriod { TODAY, YESTERDAY, TOMORROW, LAST_7_DAYS, THIS_WEEK, LAST_WEEK, NEXT_WEEK, THIS_MONTH, LAST_MONTH, NEXT_MONTH }

/** How a rule's matching cells look (Excel's dxf): a fill, a text colour, bold / italic / underline / strikethrough — null: as they are. */
data class CondStyle(
    val fill: Long? = null, val font: Long? = null,
    val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null, val strike: Boolean? = null,
)

/** Where a point of a scale stands (Excel's cfvo types). */
enum class ScaleKind { MIN, MAX, NUMBER, PERCENT, PERCENTILE, FORMULA }

/** A point of a scale: the range's lowest or highest number, a number, a percent of the way between them, a percentile, or a formula's value. */
data class ScalePoint(val kind: ScaleKind, val value: Double = 0.0, val formula: String? = null, /** An icon's threshold: at least the point (false: above it). */ val gte: Boolean = true)

/** Excel's icon sets: how many icons, and the name SpreadsheetML and LibreOffice give the set. */
enum class IconSetKind(val count: Int, val xlsx: String) {
    ARROWS_3(3, "3Arrows"), ARROWS_GRAY_3(3, "3ArrowsGray"), FLAGS_3(3, "3Flags"), TRAFFIC_LIGHTS_3(3, "3TrafficLights1"),
    TRAFFIC_LIGHTS_RIMMED_3(3, "3TrafficLights2"), SIGNS_3(3, "3Signs"), SYMBOLS_3(3, "3Symbols"), SYMBOLS_UNCIRCLED_3(3, "3Symbols2"),
    STARS_3(3, "3Stars"), TRIANGLES_3(3, "3Triangles"),
    ARROWS_4(4, "4Arrows"), ARROWS_GRAY_4(4, "4ArrowsGray"), RED_TO_BLACK_4(4, "4RedToBlack"), RATING_4(4, "4Rating"), TRAFFIC_LIGHTS_4(4, "4TrafficLights"),
    ARROWS_5(5, "5Arrows"), ARROWS_GRAY_5(5, "5ArrowsGray"), RATING_5(5, "5Rating"), QUARTERS_5(5, "5Quarters"), BOXES_5(5, "5Boxes");

    companion object {
        fun of(name: String?): IconSetKind? = entries.firstOrNull { it.xlsx.equals(name, ignoreCase = true) }
    }
}

/**
 * A conditional format: a rule over the cells [r1]..[r2] × [c1]..[c2] — a test whose matching cells take
 * a look, or a scale drawn over the range's numbers. A sheet's rules apply in their order (the first
 * one's look wins where two set the same thing; [stopIfTrue] ends the search at a match).
 */
data class CondFormat(
    val r1: Int, val c1: Int, val r2: Int, val c2: Int,
    val type: CondType,
    /** The values compared with (GREATER … NOT_BETWEEN), or TOP / BOTTOM's N. */
    val v1: Double = 0.0, val v2: Double = 0.0,
    /** A highlight's fill (when it has no [style]); a data bar's colour; a two-colour scale's high colour. */
    val color: Long = 0xFFFFF2CC,
    /** A two-colour scale's low colour. */
    val color2: Long = 0xFFFFFFFF,
    /** Formulas in place of [v1] / [v2] — references, functions — worked out at each cell; FORMULA's test. */
    val formula1: String? = null, val formula2: String? = null,
    /** TEXT_ rules' text. */
    val text: String = "",
    /** How matching cells look (null: [color] as their fill). */
    val style: CondStyle? = null,
    /** TOP / BOTTOM: N percent of the range's numbers rather than N of them. */
    val percent: Boolean = false,
    /** ABOVE / BELOW_AVERAGE: the average itself counts too. */
    val orEqual: Boolean = false,
    val period: DatePeriod? = null,
    /** A colour scale's stops low to high, each a point and its colour (two or three; none: [color2] at the lowest, [color] at the highest). */
    val stops: List<Pair<ScalePoint, Long>> = emptyList(),
    /** A data bar's ends (none: the range's lowest and highest) and how much of the cell its shortest and longest bars take, in percent. */
    val barEnds: List<ScalePoint> = emptyList(), val barMin: Int = 10, val barMax: Int = 90,
    val icons: IconSetKind? = null,
    /** An icon set's thresholds, one per icon from the lowest (the first is where the lowest icon starts). */
    val iconPoints: List<ScalePoint> = emptyList(),
    /** An icon set's icons the other way round (the highest icon for the lowest values). */
    val reverse: Boolean = false,
    /** A scale shows the cell's value beside its bar or icon (false: the bar or icon alone). */
    val showValue: Boolean = true,
    val stopIfTrue: Boolean = false,
) {
    fun covers(row: Int, col: Int): Boolean = row in r1..r2 && col in c1..c2
    fun copyOf(): CondFormat = copy()

    /** The look of a matching cell: its [style], or [color] as its fill. */
    val look: CondStyle get() = style ?: CondStyle(fill = color)

    /** The same rule over other cells. */
    fun over(r1: Int, c1: Int, r2: Int, c2: Int): CondFormat = copy(r1 = r1, c1 = c1, r2 = r2, c2 = c2)
}

/**
 * An outline group (Excel's Data → Group): rows — or, in [Sheet.colGroups], columns — [r1]..[r2].
 * [collapsed] folds them away behind the summary row just below / summary column just right of
 * them (Excel's defaults "summary rows below detail", "summary columns right of detail").
 */
class RowGroup(var r1: Int, var r2: Int, var collapsed: Boolean = false) {
    fun copyOf(): RowGroup = RowGroup(r1, r2, collapsed)
    fun contains(o: RowGroup): Boolean = o.r1 >= r1 && o.r2 <= r2
    /** True when the two ranges intersect without one containing the other (not allowed in an outline). */
    fun overlapsPartly(o: RowGroup): Boolean = !(o.r2 < r1 || o.r1 > r2) && !contains(o) && !o.contains(this)
}

/** Outline rules shared by row and column groups: levels, folding, what may be added, merging. */
object Outline {
    /** How many groups contain index [i] (0 = not grouped). */
    fun level(groups: List<RowGroup>, i: Int): Int = groups.count { i in it.r1..it.r2 }

    /** Level of [g]: 1 for an outermost group, 2 inside one, … */
    fun levelOf(groups: List<RowGroup>, g: RowGroup): Int = groups.count { it.contains(g) }

    /** Deepest level in use (0 = no groups). */
    fun maxLevel(groups: List<RowGroup>): Int = groups.maxOfOrNull { levelOf(groups, it) } ?: 0

    /** Indexes folded away inside collapsed groups. */
    fun folded(groups: List<RowGroup>): Set<Int> {
        if (groups.none { it.collapsed }) return emptySet()
        val out = HashSet<Int>()
        groups.forEach { g -> if (g.collapsed) for (i in g.r1..g.r2) out.add(i) }
        return out
    }

    /** Whether [g] can join: not a duplicate, no partial overlap, at most eight levels deep (Excel's limit). */
    fun canAdd(groups: List<RowGroup>, g: RowGroup): Boolean {
        if (groups.any { (it.r1 == g.r1 && it.r2 == g.r2) || it.overlapsPartly(g) }) return false
        val depth = 1 + groups.count { it.contains(g) } +
            (groups.filter { g.contains(it) }.maxOfOrNull { inner -> groups.count { o -> g.contains(o) && o.contains(inner) } } ?: 0)
        return depth <= 8
    }

    /**
     * Joins adjacent groups at the same level, as Excel does — the file formats store only a level
     * per row / column, so two touching level-n blocks could not stay apart after a save anyway.
     */
    fun mergeAdjacent(groups: MutableList<RowGroup>) {
        while (true) {
            val byStart = groups.groupBy { it.r1 }
            val pair = groups.firstNotNullOfOrNull { a ->
                byStart[a.r2 + 1]?.firstOrNull { b ->
                    val merged = RowGroup(a.r1, b.r2)
                    levelOf(groups, a) == levelOf(groups, b) && groups.none { it !== a && it !== b && it.overlapsPartly(merged) }
                }?.let { a to it }
            } ?: break
            val (a, b) = pair
            groups.remove(a); groups.remove(b)
            groups.add(RowGroup(a.r1, b.r2, a.collapsed && b.collapsed))
        }
        groups.sortWith(compareBy({ it.r1 }, { -it.r2 }))
    }

    /**
     * Rebuilds groups from a level per index (how xlsx stores an outline): each run of indexes at
     * level ≥ n is one level-n group; it is collapsed when [collapsedSummary] holds the index right
     * after it (Excel marks the summary row / column) or [hidden] covers all of it.
     */
    fun fromLevels(levels: Map<Int, Int>, collapsedSummary: Set<Int>, hidden: Set<Int>): List<RowGroup> {
        if (levels.isEmpty()) return emptyList()
        val out = ArrayList<RowGroup>()
        val maxLevel = levels.values.max().coerceAtMost(8)
        for (n in 1..maxLevel) {
            val idx = levels.filterValues { it >= n }.keys.sorted()
            var start = -1; var prev = -2
            fun flush() { if (start >= 0) out.add(RowGroup(start, prev)) }
            for (i in idx) { if (i != prev + 1) { flush(); start = i }; prev = i }
            flush()
        }
        out.forEach { g -> g.collapsed = (g.r2 + 1) in collapsedSummary || (g.r1..g.r2).all { it in hidden } }
        return out.sortedWith(compareBy({ it.r1 }, { -it.r2 }))
    }
}

/** A What-If scenario: the text of each input cell (keyed like [Sheet.cells]) under a name. */
class Scenario(var name: String, val values: MutableMap<Long, String>) {
    fun copyOf(): Scenario = Scenario(name, values.toMutableMap())
}

/**
 * A text or number condition on a filtered column (Excel's Text / Number / Date Filters); [value] compares as a number when
 * both sides are (a date's value is its serial). Equals and Does Not Equal take Excel's wildcards: `?` any one character,
 * `*` any run of them, `~` before either for itself.
 */
data class FilterCondition(val op: Op, val value: String) {
    enum class Op { EQUALS, NOT_EQUALS, CONTAINS, NOT_CONTAINS, BEGINS_WITH, ENDS_WITH, GREATER, GREATER_OR_EQUAL, LESS, LESS_OR_EQUAL, NOT_BEGINS_WITH, NOT_ENDS_WITH }

    private val pattern: Regex? by lazy {
        if ((op != Op.EQUALS && op != Op.NOT_EQUALS) || (!value.contains('*') && !value.contains('?'))) null
        else Regex(buildString {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '~' && i + 1 < value.length && value[i + 1] in "*?~" -> { append(Regex.escape(value[i + 1].toString())); i++ }
                    c == '*' -> append(".*")
                    c == '?' -> append(".")
                    else -> append(Regex.escape(c.toString()))
                }
                i++
            }
        }, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    /** Whether a cell showing [shown] (holding the number [number], if any) passes. */
    fun test(shown: String, number: Double?): Boolean {
        val n = value.trim().toDoubleOrNull()
        fun cmp(): Int? = if (number != null && n != null) number.compareTo(n) else null
        return when (op) {
            Op.EQUALS -> pattern?.matches(shown) ?: cmp()?.let { it == 0 } ?: shown.equals(value, ignoreCase = true)
            Op.NOT_EQUALS -> pattern?.let { !it.matches(shown) } ?: cmp()?.let { it != 0 } ?: !shown.equals(value, ignoreCase = true)
            Op.CONTAINS -> shown.contains(value, ignoreCase = true)
            Op.NOT_CONTAINS -> !shown.contains(value, ignoreCase = true)
            Op.BEGINS_WITH -> shown.startsWith(value, ignoreCase = true)
            Op.ENDS_WITH -> shown.endsWith(value, ignoreCase = true)
            Op.NOT_BEGINS_WITH -> !shown.startsWith(value, ignoreCase = true)
            Op.NOT_ENDS_WITH -> !shown.endsWith(value, ignoreCase = true)
            Op.GREATER -> (cmp() ?: shown.compareTo(value, ignoreCase = true)) > 0
            Op.GREATER_OR_EQUAL -> (cmp() ?: shown.compareTo(value, ignoreCase = true)) >= 0
            Op.LESS -> (cmp() ?: shown.compareTo(value, ignoreCase = true)) < 0
            Op.LESS_OR_EQUAL -> (cmp() ?: shown.compareTo(value, ignoreCase = true)) <= 0
        }
    }
}

/** Excel's Top 10 filter: the [count] largest numbers of the column (smallest when ![top]), or that percent of them; ties all stay. */
data class TopFilter(val top: Boolean = true, val count: Double = 10.0, val percent: Boolean = false)

/** Excel's dynamic filters (xlsx `dynamicFilter@type`): above or below the column's average, and dates against today. */
enum class DynamicFilter(val excel: String) {
    ABOVE_AVERAGE("aboveAverage"), BELOW_AVERAGE("belowAverage"),
    TOMORROW("tomorrow"), TODAY("today"), YESTERDAY("yesterday"),
    NEXT_WEEK("nextWeek"), THIS_WEEK("thisWeek"), LAST_WEEK("lastWeek"),
    NEXT_MONTH("nextMonth"), THIS_MONTH("thisMonth"), LAST_MONTH("lastMonth"),
    NEXT_QUARTER("nextQuarter"), THIS_QUARTER("thisQuarter"), LAST_QUARTER("lastQuarter"),
    NEXT_YEAR("nextYear"), THIS_YEAR("thisYear"), LAST_YEAR("lastYear"), YEAR_TO_DATE("yearToDate");

    /** Whether this compares dates (else the average). */
    val dated: Boolean get() = this != ABOVE_AVERAGE && this != BELOW_AVERAGE

    /** Whether [day] falls in it, today being [today] (weeks from Sunday, as Excel's). */
    fun holds(day: java.time.LocalDate, today: java.time.LocalDate): Boolean {
        fun weekStart(d: java.time.LocalDate) = d.minusDays((d.dayOfWeek.value % 7).toLong())
        fun quarter(d: java.time.LocalDate) = d.year * 4 + (d.monthValue - 1) / 3
        fun month(d: java.time.LocalDate) = d.year * 12 + d.monthValue
        return when (this) {
            TOMORROW -> day == today.plusDays(1)
            TODAY -> day == today
            YESTERDAY -> day == today.minusDays(1)
            NEXT_WEEK -> weekStart(day) == weekStart(today).plusWeeks(1)
            THIS_WEEK -> weekStart(day) == weekStart(today)
            LAST_WEEK -> weekStart(day) == weekStart(today).minusWeeks(1)
            NEXT_MONTH -> month(day) == month(today) + 1
            THIS_MONTH -> month(day) == month(today)
            LAST_MONTH -> month(day) == month(today) - 1
            NEXT_QUARTER -> quarter(day) == quarter(today) + 1
            THIS_QUARTER -> quarter(day) == quarter(today)
            LAST_QUARTER -> quarter(day) == quarter(today) - 1
            NEXT_YEAR -> day.year == today.year + 1
            THIS_YEAR -> day.year == today.year
            LAST_YEAR -> day.year == today.year - 1
            YEAR_TO_DATE -> day.year == today.year && !day.isAfter(today)
            ABOVE_AVERAGE, BELOW_AVERAGE -> false
        }
    }

    companion object {
        fun ofExcel(type: String?): DynamicFilter? = entries.firstOrNull { it.excel == type }
    }
}

/** Excel's Filter by Color: the cells whose fill (or font colour when [font]) is [color] — null for No Fill / Automatic. */
data class ColorFilter(val color: Long?, val font: Boolean = false)

/**
 * What the whole filtered column holds, for the filters that weigh a cell against the rest — Top 10, above or below the
 * average — and today, for the date filters.
 */
class FilterStats(numbers: List<Double>, val today: java.time.LocalDate = java.time.LocalDate.now()) {
    /** The column's numbers, smallest first. */
    val numbers: DoubleArray = numbers.toDoubleArray().also { it.sort() }
    val average: Double? = if (this.numbers.isEmpty()) null else this.numbers.average()

    /** The least number a Top 10 keeps (or the greatest a Bottom 10 does); null when the column has none. */
    fun cutoff(f: TopFilter): Double? {
        if (numbers.isEmpty()) return null
        val n = (if (f.percent) kotlin.math.floor(numbers.size * f.count / 100.0) else f.count).toInt().coerceIn(1, numbers.size)
        return if (f.top) numbers[numbers.size - n] else numbers[n - 1]
    }

    companion object {
        /** Day 0 of the serials dates are stored as. */
        val EPOCH: java.time.LocalDate = java.time.LocalDate.of(1899, 12, 30)
    }
}

/**
 * One column's AutoFilter criteria: the values it shows (as displayed; null = any value) and whether blank cells show (by
 * default only when no values are listed); or a [condition] (with a second, [condition2], joined by Or when [or], else
 * And), which decides blanks itself; or a Top 10 ([top]), a [dynamic] filter, or a colour ([color]). A row stays when every
 * filtered column passes.
 */
data class ColumnFilter(
    val values: Set<String>? = null, val blanks: Boolean = values == null, val condition: FilterCondition? = null,
    val condition2: FilterCondition? = null, val or: Boolean = false,
    val top: TopFilter? = null, val dynamic: DynamicFilter? = null, val color: ColorFilter? = null,
) {
    /** Whether it weighs a cell against the whole column (and so needs [FilterStats]). */
    val needsStats: Boolean get() = top != null || dynamic != null

    /** [values] without case: Excel's filter lists "Paris" and "PARIS" as one and shows both. */
    private val folded: Set<String>? by lazy { values?.mapTo(HashSet()) { it.lowercase() } }

    /** The filter's kind as Excel's menu marks it: a condition, Top 10 or dynamic filter (not a list of values, nor a colour). */
    val isCriteria: Boolean get() = condition != null || top != null || dynamic != null

    /**
     * Whether a cell showing [shown] (holding [number], if any; filled [fill], its text [font]) passes, [stats] being the
     * column's (Top 10, average and dates pass every cell without them).
     */
    fun passes(shown: String, number: Double?, stats: FilterStats? = null, fill: Long? = null, font: Long? = null): Boolean {
        color?.let { return (if (it.font) font else fill) == it.color }
        top?.let { t ->
            val cut = stats?.cutoff(t) ?: return stats == null
            return number != null && (if (t.top) number >= cut else number <= cut)
        }
        dynamic?.let { d ->
            stats ?: return true
            if (number == null) return false
            if (!d.dated) {
                val avg = stats.average ?: return false
                return if (d == DynamicFilter.ABOVE_AVERAGE) number > avg else number < avg
            }
            return d.holds(FilterStats.EPOCH.plusDays(kotlin.math.floor(number).toLong()), stats.today)
        }
        condition?.let { c1 ->
            val first = c1.test(shown, number)
            val second = condition2 ?: return first
            return if (or) first || second.test(shown, number) else first && second.test(shown, number)
        }
        if (shown.isEmpty()) return blanks
        return folded?.contains(shown.lowercase()) ?: true
    }
}

/** Excel's AutoFilter on a sheet: the range (its first row holds the filter buttons) and each filtered column's criteria, by column index. */
class AutoFilter(var r1: Int, var c1: Int, var r2: Int, var c2: Int, val columns: MutableMap<Int, ColumnFilter> = mutableMapOf()) {
    fun copyOf(): AutoFilter = AutoFilter(r1, c1, r2, c2, columns.toMutableMap())
    override fun equals(other: Any?): Boolean =
        other is AutoFilter && r1 == other.r1 && c1 == other.c1 && r2 == other.r2 && c2 == other.c2 && columns == other.columns
    override fun hashCode(): Int = ((r1 * 31 + c1) * 31 + r2) * 31 + c2 + columns.hashCode()
}

/**
 * An Excel table (Insert → Table, Format as Table; a ListObject in `xl/tables/tableN.xml`): a named
 * block whose first row, when it has one, names its columns and whose last row may total them,
 * drawn in a table style ([style], Excel's name for it) and reached from formulas by structured
 * references (`Sales[Amount]`, `[@Qty]`; see [StructuredRefs]). Names are unique in the workbook.
 */
class SheetTable(
    var name: String,
    var r1: Int, var c1: Int, var r2: Int, var c2: Int,
    var headerRow: Boolean = true,
    var totalsRow: Boolean = false,
    /** Excel's table style (`TableStyleMedium2` …; see [TableLooks]); null draws none. */
    var style: String? = DEFAULT_STYLE,
    var bandedRows: Boolean = true,
    var bandedCols: Boolean = false,
    var firstColumn: Boolean = false,
    var lastColumn: Boolean = false,
    /** Filter buttons on the header row. */
    var filterButton: Boolean = true,
    /**
     * The columns' names as the file gave them. With a header row its cells name the columns and
     * these only stand in for blank ones; without one they are the names' only record.
     */
    val columns: MutableList<String> = mutableListOf(),
) {
    /** First and last row of the data, between the header and the total row. */
    val dataR1: Int get() = if (headerRow) r1 + 1 else r1
    val dataR2: Int get() = if (totalsRow) r2 - 1 else r2
    val width: Int get() = c2 - c1 + 1

    fun contains(row: Int, col: Int): Boolean = row in r1..r2 && col in c1..c2
    fun intersects(ar1: Int, ac1: Int, ar2: Int, ac2: Int): Boolean = ar1 <= r2 && ar2 >= r1 && ac1 <= c2 && ac2 >= c1

    /**
     * The columns' names, left to right: the header cells' text (a formula's or a blank one's from
     * [columns], else `Column1` …), made unique as Excel makes them — a repeated name gets a number.
     * [override] gives one cell's text in place of what it holds.
     */
    fun columnNames(sheet: Sheet, override: Pair<Long, String>? = null): List<String> {
        val out = ArrayList<String>(width)
        val seen = HashSet<String>()
        for (i in 0 until width) {
            val key = sheet.key(r1, c1 + i)
            val own = if (!headerRow) null
                else (if (override?.first == key) override.second else sheet.cells[key]?.text)?.takeIf { it.isNotBlank() && !it.startsWith("=") }?.trim()
            var name = own ?: columns.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "Column${i + 1}"
            if (name.lowercase() in seen) {
                var n = 2
                while ((name + n).lowercase() in seen) n++
                name += n
            }
            seen.add(name.lowercase())
            out.add(name)
        }
        return out
    }

    fun copyOf(): SheetTable = SheetTable(name, r1, c1, r2, c2, headerRow, totalsRow, style, bandedRows, bandedCols, firstColumn, lastColumn, filterButton, columns.toMutableList())
    fun sameAs(o: SheetTable): Boolean = name == o.name && r1 == o.r1 && c1 == o.c1 && r2 == o.r2 && c2 == o.c2 && headerRow == o.headerRow &&
        totalsRow == o.totalsRow && style == o.style && bandedRows == o.bandedRows && bandedCols == o.bandedCols &&
        firstColumn == o.firstColumn && lastColumn == o.lastColumn && filterButton == o.filterButton && columns == o.columns

    companion object {
        /** Excel's default look for a new table: blue header, banded rows. */
        const val DEFAULT_STYLE = "TableStyleMedium2"
    }
}

/**
 * Whether a sheet shows in the tabs: shown, hidden (Home → Format → Hide & Unhide; Unhide lists it),
 * or "very hidden" (only a macro shows it again in Excel; kept so, and not offered by Unhide).
 */
enum class SheetState(val xlsx: String) {
    VISIBLE("visible"), HIDDEN("hidden"), VERY_HIDDEN("veryHidden");

    companion object {
        fun ofXlsx(state: String?): SheetState = entries.firstOrNull { it.xlsx == state } ?: VISIBLE
    }
}

class Sheet(
    var name: String = "Sheet1",
    var rowCount: Int = 40,
    var colCount: Int = 16,
    /** Sparse cell storage keyed by row * 1000 + col (see [MAX_COLS]). */
    val cells: MutableMap<Long, Cell> = mutableMapOf(),
    /** Freeze panes: this many leading rows / columns stay pinned while scrolling. */
    var freezeRows: Int = 0,
    var freezeCols: Int = 0,
    /** Conditional-formatting rules (evaluated per render). */
    val condFormats: MutableList<CondFormat> = mutableListOf(),
    /** Merged cell regions. */
    val merges: MutableList<MergeRegion> = mutableListOf(),
    /** Per-column width factor (1f = default width); sparse, default 1f. */
    val colWidths: MutableMap<Int, Float> = mutableMapOf(),
    /** Per-row height factor (1f = default height); sparse, default 1f. */
    val rowHeights: MutableMap<Int, Float> = mutableMapOf(),
    /** Charts built from cell ranges (rendered in the chart panel; reuses the Slides chart model). */
    val charts: MutableList<SlideChart> = mutableListOf(),
    /** Data-validation rules. */
    val validations: MutableList<DataValidation> = mutableListOf(),
    /** Page setup for printing / PDF export (OOXML `pageSetup`). */
    var landscape: Boolean = false,
    var paper: Paper = Paper.A4,
    /** Print area (`_xlnm.Print_Area`); null = the used range. */
    var printArea: PrintArea? = null,
    /** Show the row numbers / column letters (OOXML `sheetView@showRowColHeaders`). */
    var showHeadings: Boolean = true,
    /** Rows hidden by a filter (OOXML `row@hidden`); persisted and undoable like Excel's AutoFilter. */
    val hiddenRows: MutableSet<Int> = mutableSetOf(),
    /** In-cell mini charts. */
    val sparklines: MutableList<Sparkline> = mutableListOf(),
    /** Sheet protection: only [Cell.unlocked] cells can be edited and the structure is frozen. */
    var protected: Boolean = false,
    /** What-If scenarios: named value sets for input cells (Excel's 方案管理器, xlsx `<scenarios>`). */
    val scenarios: MutableList<Scenario> = mutableListOf(),
    /** Row outline groups (Data → Group, 分级显示); nested, never partly overlapping. */
    val rowGroups: MutableList<RowGroup> = mutableListOf(),
    /** Columns hidden by hand (Home → Format → Hide Columns; OOXML `col@hidden`). */
    val hiddenCols: MutableSet<Int> = mutableSetOf(),
    /** Column outline groups; summary columns sit right of their detail, as in Excel. */
    val colGroups: MutableList<RowGroup> = mutableListOf(),
    /** Rows hidden by hand (Home → Format → Hide Rows), kept apart from [hiddenRows], which a filter owns. */
    val manualHiddenRows: MutableSet<Int> = mutableSetOf(),
    /** Excel's AutoFilter: the range with filter buttons on its first row and each column's criteria; it owns [hiddenRows]. */
    var autoFilter: AutoFilter? = null,
    /** Pictures floating over the cells (Insert → Pictures), each anchored to a cell. */
    val images: MutableList<SheetImage> = mutableListOf(),
    /** Excel tables (ListObjects) on the sheet; they never overlap. */
    val tables: MutableList<SheetTable> = mutableListOf(),
    /** Shapes and text boxes floating over the cells (Insert → Shapes), each anchored to a cell. */
    val shapes: MutableList<SheetShape> = mutableListOf(),
    /** Shown in the sheet tabs, or hidden (Excel's Hide; xlsx `sheet@state`, ODF `table:display`). */
    var state: SheetState = SheetState.VISIBLE,
    /** The colour of the sheet's tab (Tab Color; xlsx `sheetPr/tabColor`, ODF 1.3 `table:tab-color`), or none. */
    var tabColor: Long? = null,
    /** How it prints: margins, scale or fit to pages, gridlines and headings, print titles, header and footer. */
    var print: SheetPrint = SheetPrint(),
    /** Gridlines shown on screen (View → Gridlines; xlsx `sheetView@showGridLines`, Calc's `ShowGrid`) — printing has its own. */
    var showGridlines: Boolean = true,
    /** The sheet's zoom in percent, [MIN_ZOOM]–[MAX_ZOOM] (xlsx `sheetView@zoomScale`, Calc's `ZoomValue`): a view setting, not an edit. */
    var zoom: Int = 100,
    /** Excel's Page Break Preview (View ▸ Page Break Preview; xlsx `sheetView@view`): a view setting, not an edit. */
    var pageBreakPreview: Boolean = false,
    /** Page Break Preview's own zoom, Excel's 60 % at first (xlsx `zoomScaleSheetLayoutView`, Calc's `PageViewZoomValue`). */
    var previewZoom: Int = 60,
) {
    /** The zoom of the view the sheet is in: [previewZoom] in Page Break Preview, else [zoom]. */
    var viewZoom: Int
        get() = if (pageBreakPreview) previewZoom else zoom
        set(v) { if (pageBreakPreview) previewZoom = v else zoom = v }

    /** Excel's "show page breaks" in Normal view: on once the sheet's breaks were inserted or previewed (this session). */
    var showPageBreaks: Boolean = false

    /** Whether the sheet is left out of the tabs. */
    val hidden: Boolean get() = state != SheetState.VISIBLE

    /** Outline level of row [r]: how many groups contain it (0 = not grouped). */
    fun rowLevel(r: Int): Int = Outline.level(rowGroups, r)

    /** Level of [g] among the row groups: 1 for an outermost group, 2 inside one, … */
    fun levelOf(g: RowGroup): Int = Outline.levelOf(rowGroups, g)

    /** Rows folded away inside collapsed groups (hidden, but not by a filter). */
    fun foldedRows(): Set<Int> = Outline.folded(rowGroups)

    /** Outline level of column [c]. */
    fun colLevel(c: Int): Int = Outline.level(colGroups, c)

    /** Level of [g] among the column groups. */
    fun levelOfCol(g: RowGroup): Int = Outline.levelOf(colGroups, g)

    /** Columns folded away inside collapsed column groups. */
    fun foldedCols(): Set<Int> = Outline.folded(colGroups)

    /** Columns the grid, the PDF and Excel do not show: hidden by hand or folded in a group. */
    fun invisibleCols(): Set<Int> = if (colGroups.none { it.collapsed }) hiddenCols else hiddenCols + foldedCols()

    /** Rows the grid, the PDF and Excel do not show: filtered out, hidden by hand or folded in a group. */
    fun invisibleRows(): Set<Int> {
        val folded = if (rowGroups.none { it.collapsed }) emptySet() else foldedRows()
        if (manualHiddenRows.isEmpty() && folded.isEmpty()) return hiddenRows
        return HashSet<Int>(hiddenRows).apply { addAll(manualHiddenRows); addAll(folded) }
    }

    fun key(row: Int, col: Int): Long = row.toLong() * 1000L + col
    fun cell(row: Int, col: Int): Cell? = cells[key(row, col)]
    fun ensure(row: Int, col: Int): Cell = cells.getOrPut(key(row, col)) { Cell() }
    fun mergeAt(row: Int, col: Int): MergeRegion? = merges.firstOrNull { it.covers(row, col) }
    /** True when the cell is inside a merge but is not its anchor (should not render). */
    fun isMergeCovered(row: Int, col: Int): Boolean = mergeAt(row, col)?.let { !it.isAnchor(row, col) } ?: false
    fun colWidthFactor(col: Int): Float = colWidths[col] ?: 1f
    /** A row's height as a factor of the default: its own ([rowHeights]), else what its content needs ([autoRowHeights]). */
    fun rowHeightFactor(row: Int): Float = rowHeights[row] ?: autoRowHeights[row] ?: 1f

    /**
     * Rows without a height of their own that their content makes taller than the default (larger
     * type, wrapping text), as [RowFit] works them out: derived, never saved or copied.
     */
    val autoRowHeights: HashMap<Int, Float> = HashMap()
    /** [autoRowHeights] has been worked out for the cells as they are. */
    var autoRowsReady = false
    fun copyOf(): Sheet = Sheet(name, rowCount, colCount, cells.mapValues { it.value.copyOf() }.toMutableMap(), freezeRows, freezeCols, condFormats.map { it.copyOf() }.toMutableList(), merges.map { it.copyOf() }.toMutableList(), colWidths.toMutableMap(), rowHeights.toMutableMap(), charts.map { it.copyOf() }.toMutableList(), validations.map { it.copyOf() }.toMutableList(),
        landscape, paper, printArea?.copyOf(), showHeadings, hiddenRows.toMutableSet(), sparklines.map { it.copyOf() }.toMutableList(), protected, scenarios.map { it.copyOf() }.toMutableList(), rowGroups.map { it.copyOf() }.toMutableList(),
        hiddenCols.toMutableSet(), colGroups.map { it.copyOf() }.toMutableList(), manualHiddenRows.toMutableSet(), autoFilter?.copyOf(),
        images.map { it.copyOf() }.toMutableList(), tables.map { it.copyOf() }.toMutableList(), shapes.map { it.copyOf() }.toMutableList(), state, tabColor, print, showGridlines, zoom,
        pageBreakPreview, previewZoom).also { it.protection = protection; it.watched.addAll(watched) }

    /**
     * The cells Excel's Watch Window watches on this sheet, in the order added ([WatchMark.seq]): kept with the sheet
     * so they move with inserted and deleted rows and columns and come back with it on undo. Never saved or compared.
     */
    val watched: MutableList<WatchMark> = mutableListOf()

    /** What Protect Sheet allows and its password, kept while [protected] (and remembered after, as Excel's dialog does). */
    var protection: SheetProtection = SheetProtection()

    /** The table the cell is in, if any. */
    fun tableAt(row: Int, col: Int): SheetTable? = if (tables.isEmpty()) null else tables.firstOrNull { it.contains(row, col) }

    fun sparklineAt(row: Int, col: Int): Sparkline? = sparklines.firstOrNull { it.row == row && it.col == col }

    companion object {
        /** Columns per sheet: the cell key packs `row * 1000 + col`, so a wider grid would alias. Readers and the editor clamp to this. */
        const val MAX_COLS = 1000
        /** The zoom limits, in percent: Excel goes down to 10, but the grid composes each cell it shows, and at 10 % a window shows tens of thousands. */
        const val MIN_ZOOM = 25
        const val MAX_ZOOM = 400
    }
}

/** A watched cell ([Sheet.watched]): at ([row], [col]), [seq] the order it was added in. */
data class WatchMark(val seq: Long, val row: Int, val col: Int)

class Workbook(
    val sheets: MutableList<Sheet> = mutableListOf(Sheet()),
    var sourcePath: String? = null,
    /** Named ranges: name → reference like "Sheet1!A1:B5" or "A1:B5" (定义名称). */
    val names: MutableMap<String, String> = mutableMapOf(),
) : HasProps {
    fun copyOf(): Workbook = Workbook(sheets.map { it.copyOf() }.toMutableList(), sourcePath, names.toMutableMap()).also {
        it.props = props; it.structureLocked = structureLocked; it.structurePasswords = structurePasswords
    }

    /** Excel's Protect Workbook (structure): sheets cannot be added, deleted, renamed, moved, copied, hidden or shown. */
    var structureLocked: Boolean = false
    /** Its password, kept for each format (see [ProtectionHash]); empty: none. */
    var structurePasswords: List<ProtectionHash> = emptyList()

    /** The file's properties (File → Info). */
    override var props: DocProps = DocProps()

    /** The table named [name] (in any case) and its sheet. */
    fun table(name: String): Pair<Sheet, SheetTable>? {
        for (s in sheets) for (t in s.tables) if (t.name.equals(name, ignoreCase = true)) return s to t
        return null
    }

    companion object {
        fun blank(): Workbook = Workbook(mutableListOf(Sheet()))

        /** A new blank workbook as Excel starts one where the reader is (prints on Letter or A4). */
        /** A new workbook as the reader's Excel starts one: its paper, its first sheet named in its language (Tabelle1, Feuil1…). */
        fun newBlank(): Workbook = blank().also { wb ->
            wb.sheets.forEach { it.paper = if (PagePresets.usesLetter()) Paper.LETTER else Paper.A4 }
            wb.sheets[0].name = Texts.get(TextId.SHEET_N, 1)
        }

        /** Spreadsheet column label: 0 -> A, 25 -> Z, 26 -> AA … */
        fun colLabel(col: Int): String {
            var c = col
            val sb = StringBuilder()
            do {
                sb.insert(0, ('A' + c % 26))
                c = c / 26 - 1
            } while (c >= 0)
            return sb.toString()
        }
    }
}
