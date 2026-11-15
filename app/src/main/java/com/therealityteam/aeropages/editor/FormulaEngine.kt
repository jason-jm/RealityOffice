package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.CachedValue
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.truncate

/**
 * A small spreadsheet formula evaluator. Cells whose text starts with '=' are
 * formulas; everything else is a literal (number or string). Supports cell refs
 * (A1), ranges (A1:B3) inside functions, + - * / ^, comparisons, parentheses and the
 * common functions (SUM/AVERAGE/MIN/MAX/COUNT/IF/ABS/ROUND/SQRT/POWER/AND/OR/NOT).
 * Evaluation is on-demand per render; cycles are detected and reported as #CYCLE. A
 * [Memo] shared across one render pass makes dependent formulas cost O(n) instead of
 * O(n²); dependency chains deeper than [BIG_STACK_AT] continue on a large-stack thread
 * and give up with #DEPTH at [MAX_DEPTH] rather than overflowing the caller's stack.
 */
object FormulaEngine {

    /**
     * Evaluated formula values plus the precedents each one read. A [SheetEditor] keeps one memo
     * for the lifetime of a workbook: after an edit it calls [invalidate] with the changed
     * rectangle and only the formulas that (transitively) depend on it are dropped, so a
     * keystroke in a large sheet re-evaluates what changed rather than everything visible.
     * Errors are memoised too ([FormulaError]), so a circular reference is not re-walked per render.
     */
    class Memo {
        internal val values = HashMap<Long, Any>()
        private class RectDep(val formula: Long, val ord: Int, val r1: Int, val c1: Int, val r2: Int, val c2: Int)
        private class Deps { val cells = HashSet<Long>(); val rects = ArrayList<RectDep>() }
        private val depsOf = HashMap<Long, Deps>()             // formula → its precedents
        private val byCell = HashMap<Long, HashSet<Long>>()    // precedent cell → dependent formulas
        private val byBand = HashMap<Long, ArrayList<RectDep>>() // (sheet, row band) → range precedents touching the band
        private val wide = ArrayList<RectDep>()                  // range precedents spanning many bands (whole columns)
        private val volatile = HashSet<Long>()                   // formulas using NOW/TODAY/RAND: dropped on every edit

        /** Forget the precedents recorded for [formula] before it is evaluated again. */
        internal fun beginEvaluation(formula: Long) {
            val d = depsOf.remove(formula) ?: return
            d.cells.forEach { byCell[it]?.remove(formula) }
            d.rects.forEach { unindex(it) }
            volatile.remove(formula)
        }

        internal fun noteCell(formula: Long, dep: Long) {
            if (depsOf.getOrPut(formula) { Deps() }.cells.add(dep)) byCell.getOrPut(dep) { HashSet() }.add(formula)
        }

        internal fun noteRect(formula: Long, ord: Int, r1: Int, c1: Int, r2: Int, c2: Int) {
            val dep = RectDep(formula, ord, r1, c1, r2, c2)
            depsOf.getOrPut(formula) { Deps() }.rects.add(dep)
            val b1 = r1 / BAND; val b2 = r2 / BAND
            if (b2 - b1 > WIDE_BANDS) wide.add(dep) else for (b in b1..b2) byBand.getOrPut(bandKey(ord, b)) { ArrayList() }.add(dep)
        }

        internal fun noteVolatile(formula: Long) { volatile.add(formula) }

        /** The cells and ranges [formula] read when it was last worked out, each (sheet ordinal, r1, c1, r2, c2). */
        internal fun precedentsOf(formula: Long): List<IntArray> {
            val d = depsOf[formula] ?: return emptyList()
            return d.cells.map { rectOfKey(it) } + d.rects.map { intArrayOf(it.ord, it.r1, it.c1, it.r2, it.c2) }
        }

        /**
         * Drop every memoised formula inside the rectangle (its text may have changed) and every
         * one that depends — directly, through a range, or through other formulas — on a cell
         * inside it, plus the volatile ones. Returns the dropped formula keys so callers can evict
         * their own caches; null when the rectangle was too large to walk cell by cell and
         * everything was cleared instead.
         */
        fun invalidate(ord: Int, r1: Int, c1: Int, r2: Int, c2: Int): Set<Long>? {
            if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1).toLong() > MAX_WALK) { clear(); return null }
            val removed = HashSet<Long>()
            val queue = ArrayDeque<IntArray>()
            fun schedule(f: Long) { if (removed.add(f)) queue.add(rectOfKey(f)) }
            queue.add(intArrayOf(ord, r1, c1, r2, c2))
            for (r in r1..r2) for (c in c1..c2) compositeKey(ord, r, c).let { k -> if (k in values || k in depsOf) schedule(k) }
            volatile.toList().forEach { schedule(it) }
            while (queue.isNotEmpty()) {
                val q = queue.removeFirst()
                for (r in q[1]..q[3]) for (c in q[2]..q[4]) byCell[compositeKey(q[0], r, c)]?.toList()?.forEach { schedule(it) }
                for (b in q[1] / BAND..q[3] / BAND) byBand[bandKey(q[0], b)]?.toList()?.forEach { if (it.ord == q[0] && intersects(it, q)) schedule(it.formula) }
                wide.toList().forEach { if (it.ord == q[0] && intersects(it, q)) schedule(it.formula) }
            }
            removed.forEach { values.remove(it); beginEvaluation(it) }
            return removed
        }

        fun clear() { values.clear(); depsOf.clear(); byCell.clear(); byBand.clear(); wide.clear(); volatile.clear() }

        private fun unindex(dep: RectDep) {
            if (!wide.remove(dep)) for (b in dep.r1 / BAND..dep.r2 / BAND) byBand[bandKey(dep.ord, b)]?.remove(dep)
        }

        private fun intersects(d: RectDep, q: IntArray) = d.r1 <= q[3] && d.r2 >= q[1] && d.c1 <= q[4] && d.c2 >= q[2]
        private fun bandKey(ord: Int, band: Int): Long = ord.toLong() shl 32 or band.toLong()

        private companion object {
            const val BAND = 256
            const val WIDE_BANDS = 32
            const val MAX_WALK = 20_000L
        }
    }

    /** A memoised evaluation failure: reading the cell re-raises it so dependents fail the same way. */
    class FormulaError(val text: String) : RuntimeException(text)

    /**
     * The error values a formula can come to: Excel's — `#N/A` a lookup that finds nothing, `#NAME?` a
     * function or name not known, `#VALUE!` a value of the wrong kind, `#REF!` a cell that is not there,
     * `#NUM!` a number out of reach, `#DIV/0!`, `#NULL!`, `#SPILL!`, `#CALC!` — and this engine's own:
     * `#CYCLE` a circular reference, `#DEPTH` a chain of formulas too deep to follow, `#ERROR!` a formula
     * it cannot read (as Google Sheets names one).
     */
    val ERRORS = setOf("#NULL!", "#DIV/0!", "#VALUE!", "#REF!", "#NAME?", "#NUM!", "#N/A", "#SPILL!", "#CALC!", "#GETTING_DATA", "#CYCLE", "#DEPTH", "#ERROR!")

    /** Whether [shown] — a formula's value as its cell shows it — is an error. */
    fun isError(shown: String): Boolean = shown in ERRORS

    /** Excel's ERROR.TYPE numbers. */
    private val ERROR_TYPES = mapOf("#NULL!" to 1, "#DIV/0!" to 2, "#VALUE!" to 3, "#REF!" to 4, "#NAME?" to 5, "#NUM!" to 6, "#N/A" to 7, "#GETTING_DATA" to 8, "#SPILL!" to 9, "#CALC!" to 14)

    /** The error value a failed evaluation comes to: its own code, or — a formula that cannot be read — `#ERROR!`. */
    private fun codeOf(e: Throwable): String = when (e) {
        is FormulaError -> e.text
        is CycleException -> "#CYCLE"
        is DepthException, is StackOverflowError -> "#DEPTH"
        else -> "#ERROR!"
    }

    /**
     * Display string for a cell: evaluated value for formulas, raw text otherwise.
     * [allSheets] enables cross-sheet references (Sheet2!A1); defaults to just [sheet].
     */
    fun display(sheet: Sheet, row: Int, col: Int, allSheets: List<Sheet> = listOf(sheet), names: Map<String, String> = emptyMap(), memo: Memo? = null): String {
        val raw = sheet.cell(row, col)?.text ?: ""
        if (!raw.startsWith("=")) return raw
        return render(valueOf(sheet, row, col, raw, allSheets, names, memo))
    }

    /**
     * What the formula in [row], [col] comes to as a file keeps it beside the formula ([CachedValue]): a
     * number, text, TRUE or FALSE, or its error; null when the cell holds no formula. A list (UNIQUE,
     * FILTER …), which cannot spill here, is the text the cell shows.
     */
    fun cached(sheet: Sheet, row: Int, col: Int, allSheets: List<Sheet> = listOf(sheet), names: Map<String, String> = emptyMap(), memo: Memo? = null): CachedValue? {
        val raw = sheet.cell(row, col)?.text ?: ""
        if (!raw.startsWith("=")) return null
        return when (val v = valueOf(sheet, row, col, raw, allSheets, names, memo)) {
            is Double -> if (v.isFinite()) CachedValue.Number(v) else CachedValue.Error("#NUM!")
            Blank -> CachedValue.Number(0.0)
            is Boolean -> CachedValue.Logical(v)
            is FormulaError -> CachedValue.Error(v.text)
            else -> CachedValue.Text(render(v))
        }
    }

    /** The formula [raw] in [row], [col] worked out — memoised, an error as a [FormulaError] value. */
    private fun valueOf(sheet: Sheet, row: Int, col: Int, raw: String, allSheets: List<Sheet>, names: Map<String, String>, memo: Memo?): Any {
        val key = compositeKey(allSheets.indexOf(sheet).coerceAtLeast(0), row, col)
        memo?.values?.get(key)?.let { return it }
        val byName = allSheets.associateBy { it.name.lowercase() }
        val ordinals = allSheets.withIndex().associate { (i, s) -> s to i }
        memo?.beginEvaluation(key)
        val result: Any = try {
            Parser(sheet, raw.substring(1), hashSetOf(key), byName, ordinals, names, memo, selfKey = key).parseAll()
        } catch (e: Exception) {
            FormulaError(codeOf(e))
        } catch (e: StackOverflowError) {
            FormulaError("#DEPTH")
        }
        memo?.values?.put(key, result)
        return result
    }

    /**
     * [formula] (without its `=`) evaluated on [sheet] outside any cell — for what rules hold as
     * formulas (a data validation's bounds and Custom formula, a conditional format's test). Its value
     * as a cell would show it unformatted, or null when it is an error. [at] (row, column) is the cell
     * it is worked out for: where ROW() and COLUMN() without a reference count from.
     */
    fun evaluateText(sheet: Sheet, formula: String, allSheets: List<Sheet> = listOf(sheet), names: Map<String, String> = emptyMap(), at: Pair<Int, Int>? = null): String? {
        val byName = allSheets.associateBy { it.name.lowercase() }
        val ordinals = allSheets.withIndex().associate { (i, s) -> s to i }
        val self = at?.let { (r, c) -> compositeKey(ordinals[sheet] ?: 0, r, c) } ?: -1L
        val result: Any = try {
            Parser(sheet, formula, hashSetOf(-1L), byName, ordinals, names, selfKey = self).parseAll()
        } catch (e: Exception) {
            return null
        } catch (e: StackOverflowError) {
            return null
        }
        return if (result is FormulaError) null else render(result)
    }

    /**
     * [formula] (without its "=") worked out at ([at]) and written back as a formula literal — a number, "text" (quotes doubled), TRUE /
     * FALSE, an error, {an,array} — for Evaluate Formula to put in place of the part it worked out.
     */
    fun literalOf(sheet: Sheet, formula: String, allSheets: List<Sheet> = listOf(sheet), names: Map<String, String> = emptyMap(), at: Pair<Int, Int>? = null): String {
        val byName = allSheets.associateBy { it.name.lowercase() }
        val ordinals = allSheets.withIndex().associate { (i, s) -> s to i }
        val self = at?.let { (r, c) -> compositeKey(ordinals[sheet] ?: 0, r, c) } ?: -1L
        val result: Any = try {
            Parser(sheet, formula, hashSetOf(-1L), byName, ordinals, names, selfKey = self).parseAll()
        } catch (e: FormulaError) {
            e
        } catch (e: Exception) {
            FormulaError(codeOf(e))
        } catch (e: StackOverflowError) {
            FormulaError("#NUM!")
        }
        return literal(result)
    }

    private fun literal(v: Any?): String = when (v) {
        is String -> "\"" + v.replace("\"", "\"\"") + "\""
        Blank -> "0"
        is Double -> if (v.isFinite()) formatNum(v) else "#NUM!"
        is Boolean -> if (v) "TRUE" else "FALSE"
        is FormulaError -> v.text
        is List<*> -> v.joinToString(",", "{", "}") { literal(it) }
        else -> "#VALUE!"
    }

    private fun render(v: Any): String = when (v) {
        is String -> v
        Blank -> "0"
        is Double -> formatNum(v)
        is Boolean -> if (v) "TRUE" else "FALSE"
        is FormulaError -> v.text
        is List<*> -> str(v) // a dynamic-array result cannot spill: show its items comma-separated
        else -> ""
    }

    /** Sheet-qualified cell key shared by the memo, cycle detection and the editor's display cache. */
    internal fun compositeKey(sheetOrdinal: Int, row: Int, col: Int): Long =
        sheetOrdinal.toLong() * 1_000_000_000L + row.toLong() * 1000L + col

    /** Inverse of [compositeKey] as a 1×1 rectangle (ord, r, c, r, c). */
    internal fun rectOfKey(key: Long): IntArray {
        val ord = (key / 1_000_000_000L).toInt(); val rem = key % 1_000_000_000L
        val r = (rem / 1000L).toInt(); val c = (rem % 1000L).toInt()
        return intArrayOf(ord, r, c, r, c)
    }

    /**
     * An empty cell's value: 0 to arithmetic, "" to text, FALSE to a condition, equal to all three — unlike a
     * formula's empty text "", which is text (`=""+1` is #VALUE!, ISBLANK of it FALSE). A formula that is just
     * a blank cell (`=A1`) shows 0, as Excel's does.
     */
    private object Blank { override fun toString() = "" }

    /*
     * Values are a Double, a String, a Boolean (TRUE / FALSE), [Blank] or a List (an array result).
     * [num] and [str] coerce leniently, for a function's arguments; [operand] is an operator's (`+`, `*` …)
     * coercion, strict as Excel's.
     */
    private fun num(v: Any): Double = when (v) {
        is Double -> v; is Boolean -> if (v) 1.0 else 0.0; is String -> textNumber(v) ?: 0.0
        is List<*> -> num(v.firstOrNull() ?: 0.0); else -> 0.0
    }
    private fun str(v: Any): String = when (v) {
        is Double -> formatNum(v); is String -> v; is Boolean -> if (v) "TRUE" else "FALSE"
        is List<*> -> v.joinToString(", ") { str(it ?: "") }; is FormulaError -> v.text; else -> ""
    }

    /** An argument's value as its first number, cheaply (no reading of `1,234` or dates): what most arguments never need. */
    private fun quickNum(v: Any): Double = when (v) { is Double -> v; is Boolean -> if (v) 1.0 else 0.0; is String -> v.trim().toDoubleOrNull() ?: 0.0; else -> 0.0 }

    /** An item of a range or an array where it is read by position (SUMPRODUCT): a number, else 0 — TRUE and FALSE too, as Excel's. */
    private fun positional(v: Any): Double = when (v) { is Double -> v; is String -> v.trim().toDoubleOrNull() ?: 0.0; else -> 0.0 }

    /**
     * The number text reads as, as Excel turns text into a number for arithmetic and VALUE: a plain one, or
     * one as it would be typed into a cell — `1,234`, `50%`, `$10`, `2024-01-01` ([EntryParser]); else null.
     */
    private fun textNumber(t: String): Double? {
        val s = t.trim()
        if (s.isEmpty()) return null
        return s.toDoubleOrNull()?.takeIf { it.isFinite() } ?: EntryParser.parse(s)?.value?.toDoubleOrNull()
    }

    /** A value as an operator takes it: a blank is 0, TRUE and FALSE 1 and 0, text that reads as a number that number — other text (even "") #VALUE!. */
    private fun operand(v: Any): Double = when (v) {
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        is String -> textNumber(v) ?: throw FormulaError("#VALUE!")
        is List<*> -> operand(v.firstOrNull() ?: "")
        else -> 0.0
    }

    /** [d] when it is a number a cell can hold, else #NUM! (an overflow, the root of a negative number …). */
    private fun finite(d: Double): Double = if (d.isNaN() || d.isInfinite()) throw FormulaError("#NUM!") else d

    /**
     * A value as a condition takes it (IF, NOT, AND …): TRUE / FALSE, a number not 0, text reading TRUE,
     * FALSE or a number; a blank is FALSE, other text (even "") #VALUE!.
     */
    private fun truth(v: Any): Boolean = when (v) {
        is Boolean -> v
        is Double -> v != 0.0
        is String -> {
            val t = v.trim()
            when {
                t.equals("TRUE", ignoreCase = true) -> true
                t.equals("FALSE", ignoreCase = true) -> false
                else -> t.toDoubleOrNull()?.let { it != 0.0 } ?: throw FormulaError("#VALUE!")
            }
        }
        is List<*> -> truth(v.firstOrNull() ?: "")
        else -> false
    }

    /**
     * Compares two values as Excel's `=` `<` `>` do: numbers come before text, text before FALSE and TRUE;
     * a blank stands for 0, "" or FALSE, whichever the other side is; text compares without case; numbers
     * equal to fifteen significant digits are equal (0.1 + 0.2 = 0.3).
     */
    private fun cmp(a: Any, b: Any): Int {
        val x = if (a === Blank) blankLike(b) else a
        val y = if (b === Blank) blankLike(x) else b
        val kx = kindOf(x)
        val ky = kindOf(y)
        if (kx != ky) return kx.compareTo(ky)
        return when (x) {
            is Double -> numCmp(x, y as Double)
            is Boolean -> x.compareTo(y as Boolean)
            else -> str(x).compareTo(str(y), ignoreCase = true).sign
        }
    }

    private fun blankLike(other: Any): Any = when (other) { is Double -> 0.0; is Boolean -> false; Blank -> ""; else -> "" }
    private fun kindOf(v: Any): Int = when (v) { is Double -> 0; is Boolean -> 2; else -> 1 }

    /** Numbers compared as Excel holds them: to fifteen significant digits. */
    private fun numCmp(a: Double, b: Double): Int {
        if (a == b) return 0
        val m = maxOf(abs(a), abs(b))
        if (abs(a - b) > m * 1e-14) return a.compareTo(b)
        val ra = java.math.BigDecimal(a).round(java.math.MathContext(15))
        val rb = java.math.BigDecimal(b).round(java.math.MathContext(15))
        return ra.compareTo(rb)
    }

    /** A number as a cell holds it once computed: whole numbers plainly, others to 15 significant digits (0.1 + 0.2 → 0.3). */
    fun numberText(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "#NUM!"
        if (abs(d - d.roundToLong()) < 1e-9 && abs(d) < 1e15) return d.roundToLong().toString()
        return java.math.BigDecimal(d).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()
    }

    private fun formatNum(d: Double): String = when {
        d.isNaN() || d.isInfinite() -> "#NUM!"
        abs(d - d.roundToLong()) < 1e-9 && abs(d) < 1e15 -> d.roundToLong().toString()
        else -> {
            val r = Math.round(d * 1e6) / 1e6
            r.toString()
        }
    }

    private class CycleException : RuntimeException()
    private class DepthException : RuntimeException()

    /** Dependency depth at which evaluation continues on a thread with a large stack. */
    private const val BIG_STACK_AT = 200
    /** Hard limit on dependency depth (→ #DEPTH); protects the big-stack thread too. */
    private const val MAX_DEPTH = 10_000

    /** Runs a deep evaluation on a throwaway thread with a 64 MB stack, propagating its result or error. */
    private object BigStack {
        fun <T> run(block: () -> T): T {
            var result: Any? = null
            var error: Throwable? = null
            val t = Thread(null, { try { result = block() } catch (e: Throwable) { error = e } }, "formula-deep", 64L * 1024 * 1024)
            t.start()
            t.join()
            error?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }
    }

    // Excel date serials: day 0 = 1899-12-30 (so 1900-01-01 = 1), matching Excel's epoch.
    private val DATE_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private fun toSerial(d: LocalDate): Double = ChronoUnit.DAYS.between(DATE_EPOCH, d).toDouble()
    private fun fromSerial(s: Double): LocalDate = DATE_EPOCH.plusDays(s.toLong())

    /** A rectangular cell range (zero-based, inclusive bounds) in a specific sheet. */
    private class RangeRef(val sheet: Sheet, val r1: Int, val c1: Int, val r2: Int, val c2: Int)

    /** Functions whose arguments must be evaluated lazily (short-circuit / error-catching). */
    private val LAZY_FUNCS = setOf("IF", "IFERROR", "IFNA", "IFS", "SWITCH", "ISERROR", "ISERR", "ISNA", "ERROR.TYPE", "TYPE", "ISREF")

    /** A reference as a formula writes one: a cell or a range, on a sheet or not (what ISREF says yes to). */
    private val REFERENCE_TEXT = Regex("""^(?:(?:'(?:[^']|'')+'|[A-Za-z_][A-Za-z0-9_.]*)!)?\$?[A-Za-z]{1,3}\$?[0-9]+(?::\$?[A-Za-z]{1,3}\$?[0-9]+)?$""")

    /** DOLLAR's currency symbol by the region of the device's language (the dollar elsewhere). */
    private val CURRENCY_SYMBOLS = mapOf("CN" to "¥", "JP" to "¥", "TW" to "NT$", "HK" to "HK$", "GB" to "£", "DE" to "€", "FR" to "€", "IT" to "€", "ES" to "€", "NL" to "€")

    /** The comparison operators, the two-character ones first. */
    private val COMPARE_OPS = listOf("<=", ">=", "<>", "=", "<", ">")

    /**
     * How a function's argument that is a reference is read: worked out (an error in it is the function's
     * error), [TOLERANT] (an error is one of the values — COUNTA counts it, COUNTIF compares it), or not at all
     * ([REFERENCE]: ROW, ROWS, INDEX, a lookup's table — read cell by cell as needed, if at all).
     */
    private enum class ArgMode { EVALUATE, TOLERANT, REFERENCE }

    /** Functions whose references read errors as values, as Excel's do. */
    private val TOLERANT_FUNCS = setOf("COUNT", "COUNTA", "COUNTBLANK", "COUNTIF", "COUNTIFS", "SUMIF", "SUMIFS", "AVERAGEIF", "AVERAGEIFS", "MAXIFS", "MINIFS")

    /** Functions' arguments (by position) that stand for where cells are, not for their values. */
    private val REFERENCE_ARGS = mapOf(
        "ROW" to 0..0, "COLUMN" to 0..0, "ROWS" to 0..0, "COLUMNS" to 0..0, "ISFORMULA" to 0..0, "FORMULATEXT" to 0..0, "INDEX" to 0..0,
        "VLOOKUP" to 1..1, "HLOOKUP" to 1..1, "MATCH" to 1..1, "XMATCH" to 1..1, "LOOKUP" to 1..2, "XLOOKUP" to 1..2,
    )

    private fun argMode(fn: String, k: Int): ArgMode = when {
        REFERENCE_ARGS[fn]?.contains(k) == true -> ArgMode.REFERENCE
        fn in TOLERANT_FUNCS -> ArgMode.TOLERANT
        else -> ArgMode.EVALUATE
    }

    /** Excel's `TEXT`: the value in a number format code, through the engine the grid uses ([FormatCode]). */
    private fun textFormat(v: Any, fmt: String): String {
        if (fmt.isEmpty()) return ""
        if (!FormatCode.isValid(fmt)) throw FormulaError("#VALUE!")
        val n = when (v) { is Double -> v; is String -> v.trim().toDoubleOrNull(); else -> null }
        return if (n != null && n.isFinite()) FormatCode.format(n, fmt).text else FormatCode.formatText(str(v), fmt).text
    }

    /** Excel's wildcards as a pattern, without case: `?` one character, `*` any run, `~` before either (or `~`) that character itself. */
    private fun wildcardRegex(pattern: String): Regex = Regex(buildString {
        var k = 0
        while (k < pattern.length) {
            val ch = pattern[k]
            when {
                ch == '~' && k + 1 < pattern.length && pattern[k + 1] in "*?~" -> { append(Regex.escape(pattern[k + 1].toString())); k++ }
                ch == '*' -> append(".*")
                ch == '?' -> append(".")
                else -> append(Regex.escape(ch.toString()))
            }
            k++
        }
    }, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * An Excel criteria: a comparison, an equality, `*` / `?` wildcards. A number to compare with —
     * written as one, or as a date, a percentage, an amount (`">=2024-01-01"`) — matches numbers alone
     * (and, for `=`, text reading as that number): never a blank, TRUE or FALSE. `<` and `>` with text
     * compare text, without case; `<>` matches what `=` does not.
     */
    private class Criteria(criteria: String) {
        private val op: String?
        private val rest: String
        private val restNum: Double?
        private val ordering: Boolean
        private val pattern: Regex?

        init {
            val c = criteria.trim().removeSurrounding("\"").trim()
            op = COMPARE_OPS.firstOrNull { c.startsWith(it) }
            rest = if (op != null) c.substring(op.length).trim() else c
            restNum = rest.toDoubleOrNull() ?: EntryParser.parse(rest)?.value?.toDoubleOrNull()
            ordering = op == "<" || op == ">" || op == "<=" || op == ">="
            pattern = if (restNum == null && !ordering) wildcardRegex(rest) else null
        }

        fun matches(value: Any): Boolean {
            if (restNum != null) {
                val v = when (value) { is Double -> value; is String -> if (ordering) null else value.trim().toDoubleOrNull(); else -> null }
                    ?: return op == "<>"
                val d = numCmp(v, restNum)
                return when (op) { ">=" -> d >= 0; "<=" -> d <= 0; "<>" -> d != 0; "<" -> d < 0; ">" -> d > 0; else -> d == 0 }
            }
            if (ordering) {
                if (value !is String || value.isEmpty()) return false
                val d = value.compareTo(rest, ignoreCase = true)
                return when (op) { ">=" -> d >= 0; "<=" -> d <= 0; "<" -> d < 0; else -> d > 0 }
            }
            val matches = pattern!!.matches(str(value))
            return if (op == "<>") !matches else matches
        }
    }

    /** Recursive-descent evaluator over the formula text (without the leading '='). */
    private class Parser(
        val sheet: Sheet,
        val src: String,
        val visiting: MutableSet<Long>,
        val sheetsByName: Map<String, Sheet>,
        val ordinals: Map<Sheet, Int>,
        val names: Map<String, String> = emptyMap(),
        val memo: Memo? = null,
        val depth: Int = 0,
        val onBigStack: Boolean = false,
        /** Key of the formula cell being evaluated; its precedents are recorded against it. */
        val selfKey: Long = -1L,
        /** How many defined names deep this parser evaluates (a name's formula naming another): bounds a name naming itself. */
        val nameDepth: Int = 0,
    ) {
        private var i = 0
        /** > 0 while a whole range has been recorded as a precedent, so its cells are not recorded one by one. */
        private var suppressDeps = 0

        private fun ordinalOf(s: Sheet): Int = ordinals[s] ?: 0

        /** Row and column of the formula's own cell (-1 outside a cell), for `[@Qty]` and references without a table name. */
        private val selfRow: Int get() = if (selfKey < 0) -1 else ((selfKey % 1_000_000_000L) / 1000L).toInt()
        private val selfCol: Int get() = if (selfKey < 0) -1 else (selfKey % 1000L).toInt()

        /**
         * A structured reference at the cursor — a table's name and brackets, brackets alone, or a
         * table's bare name — read and resolved to its cells; null, with nothing read, when there
         * is none. One naming nothing (no such table, column or row) is #REF!.
         */
        private fun structured(): RangeRef? {
            val ch = peek()
            if (ch != '[' && !(ch.isLetter() || ch == '_' || ch == '\\')) return null
            val ref = com.therealityteam.aeropages.model.StructuredRefs.parseAt(src, i) ?: bareTable() ?: return null
            i = ref.end
            val t = com.therealityteam.aeropages.model.StructuredRefs.resolve(ref, sheet, selfRow, selfCol, sheetsByName.values)
                ?: throw FormulaError("#REF!")
            return RangeRef(t.sheet, t.r1, t.c1, t.r2, t.c2)
        }

        /** A table's name standing alone (its data), not a function's or a sheet's. */
        private fun bareTable(): com.therealityteam.aeropages.model.StructuredRefs.Ref? {
            var j = i
            while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_' || src[j] == '.' || src[j] == '\\')) j++
            if (j == i) return null
            var k = j
            while (k < src.length && src[k] == ' ') k++
            if (k < src.length && (src[k] == '(' || src[k] == '!')) return null
            val word = src.substring(i, j)
            if (sheetsByName.values.none { s -> s.tables.any { it.name.equals(word, ignoreCase = true) } }) return null
            return com.therealityteam.aeropages.model.StructuredRefs.Ref(word, emptySet(), null, null, i, j, bare = true)
        }

        /**
         * A reference's value where one value is wanted: its one cell, or — from a column or a row
         * of cells — the one in line with the formula (Excel's implicit intersection); #VALUE! else.
         */
        private fun scalarOf(r: RangeRef): Any = when {
            r.r1 == r.r2 && r.c1 == r.c2 -> cellAt(r.sheet, r.r1, r.c1)
            r.c1 == r.c2 && selfRow in r.r1..r.r2 -> cellAt(r.sheet, selfRow, r.c1)
            r.r1 == r.r2 && selfCol in r.c1..r.c2 -> cellAt(r.sheet, r.r1, selfCol)
            else -> throw FormulaError("#VALUE!")
        }
        private fun noteRange(ref: RangeRef) { memo?.noteRect(selfKey, ordinalOf(ref.sheet), ref.r1, ref.c1, ref.r2, ref.c2) }
        private inline fun <T> overRange(ref: RangeRef, block: () -> T): T { noteRange(ref); suppressDeps++; try { return block() } finally { suppressDeps-- } }

        /** What the defined name [ident] (any case) stands for, as the file keeps it — a reference, a constant, a formula —, or null. */
        private fun nameText(ident: String): String? = names.entries.firstOrNull { it.key.equals(ident, ignoreCase = true) }?.value

        /**
         * Resolve a defined name that refers to cells ("Sheet1!A1:B5" or "A1:B5") to (sheet, ref-without-sheet); null for no
         * such name and for one standing for a constant (`0.08`) or a formula (`Sheet1!A1*2`), which [nameValue] evaluates.
         */
        private fun resolveName(ident: String): Pair<Sheet, String>? {
            val target = nameText(ident) ?: return null
            // Only a reference — `Sheet1!$A$1:$B$5`, not `Sheet1!A1*2` — names cells.
            val t = A1.parseTarget(target.trim().removePrefix("=")) ?: return null
            if (t.wholeRows || t.wholeColumns) return null
            val clean = target.trim().removePrefix("=").replace("$", "")
            val bang = clean.lastIndexOf('!')
            val (sh, ref) = if (bang >= 0) {
                // 'Bob''s'!A1: the quotes go, a doubled one stands for one.
                val name = clean.substring(0, bang).trim().removeSurrounding("'").replace("''", "'")
                (sheetsByName[name.lowercase()] ?: sheet) to clean.substring(bang + 1).trim()
            } else sheet to clean.trim()
            val parts = ref.split(':')
            if (parts.size > 2 || parts.any { A1.parse(it.trim()) == null }) return null
            return sh to ref
        }

        /** A defined name standing for a constant or a formula: its value, worked out where it is used (a name naming itself: #NAME?). */
        private fun nameValue(text: String): Any {
            if (nameDepth >= 16) throw FormulaError("#NAME?")
            return Parser(sheet, text.trim().removePrefix("="), visiting, sheetsByName, ordinals, names, memo, depth, onBigStack, selfKey, nameDepth + 1).parseAll()
        }

        /**
         * One parsed function argument. [values] is positional (blank cells → 0, for index-aligned
         * functions such as SUMPRODUCT); [numbers] holds only genuinely numeric cells — Excel's rule
         * for AVERAGE/COUNT/MIN/…; [nonBlank] counts cells with any content (COUNTA).
         */
        private inner class Arg(val values: DoubleArray, val numbers: DoubleArray, val nonBlank: Int, val value: Any, val text: String, val range: RangeRef?)
        private class ParsedArg(val values: DoubleArray, val numbers: DoubleArray, val nonBlank: Int, val value: Any, val range: RangeRef?)

        fun parseAll(): Any {
            val v = parseCompare()
            skipWs()
            if (i < src.length) error("trailing")
            return v
        }

        // comparison (=,<,>,<=,>=,<>) → TRUE / FALSE, as Excel compares ([cmp]); a chain works left to right.
        private fun parseCompare(): Any {
            var left = parseConcat()
            while (true) {
                skipWs()
                val op = COMPARE_OPS.firstOrNull { src.startsWith(it, i) } ?: return left
                i += op.length
                left = lift(left, parseConcat()) { x, y -> compare(x, op, y) }
            }
        }

        /**
         * [op] on two values — or, where either is an array, on each pair of items (a single value, or a one-item
         * array, against every item), as Excel works an array formula; an item past the shorter array is #N/A, and
         * an item's error is that item's value.
         */
        private inline fun lift(a: Any, b: Any, op: (Any, Any) -> Any): Any {
            if (a !is List<*> && b !is List<*>) return op(a, b)
            val la = a as? List<*>; val lb = b as? List<*>
            val n = maxOf(la?.size ?: 1, lb?.size ?: 1)
            return List(n) { k ->
                val x = if (la == null) a else (if (la.size == 1) la[0] else la.getOrNull(k)) ?: return@List FormulaError("#N/A")
                val y = if (lb == null) b else (if (lb.size == 1) lb[0] else lb.getOrNull(k)) ?: return@List FormulaError("#N/A")
                when {
                    x is FormulaError -> x
                    y is FormulaError -> y
                    else -> try { op(x, y) } catch (e: FormulaError) { e }
                }
            }
        }

        // string concatenation with '&' (Excel) → String; otherwise passes the value through.
        private fun parseConcat(): Any {
            var v = parseAdd()
            skipWs()
            while (peek() == '&') { i++; val r = parseAdd(); v = lift(v, r) { x, y -> str(x) + str(y) }; skipWs() }
            return v
        }

        // Arithmetic levels return Any: a lone operand passes through (so a String survives for
        // concatenation/text functions); applying an operator coerces operands to numbers.
        private fun parseAdd(): Any {
            var v = parseMul()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { i++; val l = scalarOperand(v); v = lift(l, parseMul()) { x, y -> finite(operand(x) + operand(y)) } }
                    '-' -> { i++; val l = scalarOperand(v); v = lift(l, parseMul()) { x, y -> finite(operand(x) - operand(y)) } }
                    else -> return v
                }
            }
        }

        private fun parseMul(): Any {
            var v = parsePow()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { i++; val l = scalarOperand(v); v = lift(l, parsePow()) { x, y -> finite(operand(x) * operand(y)) } }
                    '/' -> { i++; val l = scalarOperand(v); v = lift(l, parsePow()) { x, y -> val d = operand(y); if (d == 0.0) throw FormulaError("#DIV/0!"); finite(operand(x) / d) } }
                    else -> return v
                }
            }
        }

        private fun parsePow(): Any {
            val base = parseUnary()
            skipWs()
            if (peek() == '^') { i++; val b = scalarOperand(base); return lift(b, parsePow()) { x, y -> power(operand(x), operand(y)) } }
            return base
        }

        private fun parseUnary(): Any {
            skipWs()
            return when (peek()) {
                '-' -> { i++; lift(parseUnary(), 0.0) { x, _ -> -operand(x) } }
                '+' -> { i++; parseUnary() }
                else -> {
                    // `50%` is 0.5: Excel's percent operator, after what it divides.
                    var v = parsePrimary()
                    while (peek() == '%') { i++; v = lift(v, 0.0) { x, _ -> operand(x) / 100.0 } }
                    v
                }
            }
        }

        /** An operator's left side, worked out before its right side is read (its error comes first, as in Excel); an array as it is. */
        private fun scalarOperand(v: Any): Any = if (v is List<*>) v else operand(v)

        /** [b] to the power [e], as Excel's `^` and POWER: 0 to a negative power is #DIV/0!, an impossible one #NUM!. */
        private fun power(b: Double, e: Double): Double = if (b == 0.0 && e < 0) throw FormulaError("#DIV/0!") else finite(b.pow(e))

        private fun parsePrimary(): Any {
            skipWs()
            val c = peek()
            if (c == '(') { i++; val v = parseCompare(); skipWs(); expect(')'); return v }
            // String literal (e.g. "abc" or a COUNTIF criteria ">2") → its text content; "" inside is one ".
            if (c == '"') return parseString()
            // An array constant: {0.03,0.1,0.2} (`;` between rows).
            if (c == '{') return parseArrayConstant()
            if (c.isDigit() || c == '.') return parseNumber()
            // An error value written into the formula (`=#REF!+1`, what a deleted reference leaves): the formula is that error.
            if (c == '#') {
                val code = ERRORS.firstOrNull { src.startsWith(it, i, ignoreCase = true) } ?: error("unexpected '#'")
                throw FormulaError(code)
            }
            // A table's parts by name: Sales[Amount], [@Qty], Sales[#Totals].
            structured()?.let { return scalarOf(it) }
            // Quoted sheet name: 'Sheet Name'!A1
            if (c == '\'') {
                i++; val sb = StringBuilder(); while (i < src.length && src[i] != '\'') { sb.append(src[i]); i++ }; if (i < src.length) i++
                skipWs(); expect('!')
                val cellRef = parseIdent()
                val target = sheetsByName[sb.toString().replace("''", "'").lowercase()] ?: sheet
                return referenceValue(target, cellRef)
            }
            if (c.isLetter() || c == '$') {
                val ident = parseIdent()
                skipWs()
                if (peek() == '(') {  // function
                    i++
                    val up = ident.uppercase()
                    if (up in LAZY_FUNCS) {
                        val raw = parseRawArgs()
                        expect(')')
                        return applyLazy(up, raw)
                    }
                    val argList = parseArgs(up)
                    expect(')')
                    return applyFunc(up, argList)
                }
                if (peek() == '!') {  // sheet-qualified ref: Sheet2!A1
                    i++
                    val cellRef = parseIdent()
                    val target = sheetsByName[ident.lowercase()] ?: sheet
                    return referenceValue(target, cellRef)
                }
                // The logical constants (`=IF(A1>5,TRUE,FALSE)`): never a name in Excel, they are TRUE() and FALSE().
                when (ident.uppercase()) { "TRUE" -> return true; "FALSE" -> return false }
                // A defined name: its cell, or — naming a block — the block's values (as `A1:B3` in an expression, so
                // `SUMPRODUCT(Qty*Price)` works on names); a constant's or a formula's value.
                resolveName(ident)?.let { (sh, ref) ->
                    val parts = ref.split(':')
                    val (ra, ca) = refToRC(parts[0])
                    if (parts.size == 1) return cellAt(sh, ra, ca)
                    val (rb, cb) = refToRC(parts[1])
                    return rangeCells(RangeRef(sh, minOf(ra, rb), minOf(ca, cb), maxOf(ra, rb), maxOf(ca, cb)))
                }
                nameText(ident)?.let { return nameValue(it) }
                // else a cell ref like A1 in the current sheet
                return referenceValue(sheet, ident)
            }
            error("unexpected '$c'")
        }

        /** A cell's value — or, `A1:B3` read on, a range's values as an array (an array formula's operand: `A1:A9*B1:B9`). */
        private fun referenceValue(target: Sheet, first: String): Any {
            val (r, cc) = refToRC(first)
            if (peek() != ':') return cellAt(target, r, cc)
            val save = i
            i++
            val second = parseIdent()
            val end = A1.parse(second) ?: run { i = save; return cellAt(target, r, cc) }
            return rangeCells(RangeRef(target, minOf(r, end.first), minOf(cc, end.second), maxOf(r, end.first), maxOf(cc, end.second)))
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < src.length) {
                if (src[i] == '"') { if (i + 1 < src.length && src[i + 1] == '"') { sb.append('"'); i += 2; continue }; i++; return sb.toString() }
                sb.append(src[i]); i++
            }
            return sb.toString()
        }

        /** `{1,2,3;4,5,6}`: an array constant, its rows one after another — numbers, text, TRUE / FALSE and errors. */
        private fun parseArrayConstant(): List<Any> {
            expect('{')
            val out = ArrayList<Any>()
            while (true) {
                skipWs()
                val c = peek()
                out.add(when {
                    c == '"' -> parseString()
                    c == '-' || c == '+' -> { i++; skipWs(); parseNumber().let { if (c == '-') -it else it } }
                    c.isDigit() || c == '.' -> parseNumber()
                    c == '#' -> ERRORS.firstOrNull { src.startsWith(it, i, ignoreCase = true) }?.let { i += it.length; FormulaError(it) } ?: error("bad array")
                    c.isLetter() -> when (parseIdent().uppercase()) { "TRUE" -> true; "FALSE" -> false; else -> error("bad array") }
                    else -> error("bad array")
                })
                skipWs()
                when (peek()) { ',', ';' -> i++; '}' -> { i++; return out }; else -> error("expected }") }
            }
        }

        /** Parse comma-separated function arguments of function [fn] (its name tells how references are read). */
        private fun parseArgs(fn: String): List<Arg> {
            val out = ArrayList<Arg>()
            skipWs()
            if (peek() == ')') return out
            while (true) {
                val start = i
                val p = parseArg(argMode(fn, out.size))
                out.add(Arg(p.values, p.numbers, p.nonBlank, p.value, src.substring(start, i).trim(), p.range))
                skipWs()
                if (peek() == ',') { i++; continue } else break
            }
            return out
        }

        /** Split function args into raw source substrings WITHOUT evaluating (for lazy funcs). Leaves i at ')'. */
        private fun parseRawArgs(): List<String> {
            val out = ArrayList<String>()
            skipWs()
            if (peek() == ')') return out
            var depth = 0; var start = i; var inStr = false
            while (i < src.length) {
                val ch = src[i]
                if (inStr) { if (ch == '"') inStr = false; i++; continue }
                when (ch) {
                    '"' -> { inStr = true; i++ }
                    '(', '{' -> { depth++; i++ }
                    '}' -> { depth--; i++ }
                    ')' -> { if (depth == 0) { out.add(src.substring(start, i).trim()); return out }; depth--; i++ }
                    ',' -> { if (depth == 0) { out.add(src.substring(start, i).trim()); i++; start = i } else i++ }
                    else -> i++
                }
            }
            out.add(src.substring(start, i).trim())
            return out
        }

        /** Evaluate a raw argument expression in this parser's context (shares cycle-detection set). */
        private fun evalSub(text: String): Any = Parser(sheet, text, visiting, sheetsByName, ordinals, names, memo, depth, onBigStack, selfKey).parseAll()

        private fun applyLazy(name: String, raw: List<String>): Any {
            // An argument left empty (`=IF(A1>5,"big",)`) is 0, as in Excel.
            fun ev(idx: Int): Any = if (idx < raw.size && raw[idx].isNotBlank()) evalSub(raw[idx]) else 0.0
            /** The error argument [idx] comes to, or null when it has a value. A circular reference is never caught. */
            fun errorOf(idx: Int): String? = try { ev(idx); null } catch (c: CycleException) { throw c } catch (e: Exception) { codeOf(e) }
            return when (name) {
                // No value for FALSE: the result is FALSE, as Excel's.
                "IF" -> if (truth(ev(0))) ev(1) else (if (raw.size > 2) ev(2) else false)
                "IFERROR" -> try { ev(0) } catch (c: CycleException) { throw c } catch (e: Exception) { if (raw.size > 1) ev(1) else "" }
                // IFNA catches #N/A alone; any other error goes through.
                "IFNA" -> try { ev(0) } catch (c: CycleException) { throw c } catch (e: Exception) { if (codeOf(e) == "#N/A") (if (raw.size > 1) ev(1) else "") else throw e }
                "ISERROR" -> errorOf(0) != null
                "ISERR" -> errorOf(0).let { it != null && it != "#N/A" }
                "ISNA" -> errorOf(0) == "#N/A"
                "ERROR.TYPE" -> errorOf(0)?.let { ERROR_TYPES[it]?.toDouble() } ?: throw FormulaError("#N/A")
                // TYPE: 1 a number (or a blank), 2 text, 4 TRUE / FALSE, 16 an error, 64 an array.
                "TYPE" -> try {
                    when (val v = ev(0)) {
                        is Boolean -> 4.0
                        is List<*> -> 64.0
                        // A blank cell is a number to TYPE; text, even "", is text.
                        is String -> 2.0
                        else -> 1.0
                    }
                } catch (c: CycleException) { throw c } catch (e: Exception) { 16.0 }
                "ISREF" -> raw.getOrNull(0)?.trim()?.let { r -> REFERENCE_TEXT.matches(r) || resolveName(r) != null } ?: false
                "IFS" -> { var k = 0; while (k + 1 < raw.size) { if (truth(evalSub(raw[k]))) return evalSub(raw[k + 1]); k += 2 }; throw FormulaError("#N/A") }
                "SWITCH" -> {
                    val expr = evalSub(raw[0]); var k = 1
                    while (k + 1 < raw.size) { if (valEq(evalSub(raw[k]), expr)) return evalSub(raw[k + 1]); k += 2 }
                    if (raw.size % 2 == 0) evalSub(raw[raw.size - 1]) else throw FormulaError("#N/A")
                }
                else -> error("lazy $name")
            }
        }

        /** Parse one argument: a range (possibly sheet-qualified or a defined name) or a scalar expression; a reference read by [mode]. */
        private fun parseArg(mode: ArgMode = ArgMode.EVALUATE): ParsedArg {
            val save = i
            skipWs()
            // OFFSET(ref, rows, cols, [height], [width]) used as an argument denotes a range, e.g. SUM(OFFSET(A1,1,0,5,1)).
            if (src.regionMatches(i, "OFFSET(", 0, 7, ignoreCase = true)) {
                i += 7
                val raw = parseRawArgs()
                expect(')')
                skipWs()
                if (peek() == ',' || peek() == ')') return rangeArg(offsetRange(raw), mode)
                i = save
            }
            // A table's parts (Sales[Amount], [@Qty]) as an argument are its cells; inside a larger expression they are read as a value.
            run {
                val at = i
                val ref = structured() ?: return@run
                skipWs()
                val one = ref.r1 == ref.r2 && ref.c1 == ref.c2
                if (peek() == ',' || peek() == ')') return if (one) cellArg(ref.sheet, ref.r1, ref.c1, mode) else rangeArg(ref, mode)
                if (!one) arrayCompare(ref)?.let { return it }
                i = at
            }
            if (peek().isLetter() || peek() == '$') {
                var first = parseIdent()
                var target = sheet
                if (peek() == '!') {  // sheet-qualified: Sheet2!A1 or Sheet2!A1:B3
                    i++
                    target = sheetsByName[first.lowercase()] ?: sheet
                    first = parseIdent()
                }
                if (peek() == ':') {
                    i++
                    val b = parseIdent()
                    val (ra, ca) = refToRC(first); val (rb, cb) = refToRC(b)
                    val ref = RangeRef(target, minOf(ra, rb), minOf(ca, cb), maxOf(ra, rb), maxOf(ca, cb))
                    skipWs()
                    if (peek() == ',' || peek() == ')') return rangeArg(ref, mode)
                    // `A1:A9*B1:B9`, `B1:B6>1000`: an array worked out item by item.
                    i = save
                    return scalarArg(parseCompare(), mode)
                }
                if (target !== sheet) {  // sheet-qualified single cell
                    val (r, cc) = refToRC(first)
                    return cellArg(target, r, cc, mode)
                }
                // A bare cell reference keeps its position (ROW / COLUMN / OFFSET need the reference, not the value).
                skipWs()
                if ((peek() == ',' || peek() == ')') && A1.parse(first) != null && resolveName(first) == null) {
                    val (r, cc) = refToRC(first)
                    return cellArg(target, r, cc, mode)
                }
                // Defined name used as a function argument → expand to its range.
                if (peek() == ',' || peek() == ')' ) {
                    resolveName(first)?.let { (sh, refText) ->
                        val parts = refText.split(":")
                        val (ra, ca) = refToRC(parts[0])
                        val (rb, cb) = if (parts.size > 1) refToRC(parts[1]) else (ra to ca)
                        return rangeArg(RangeRef(sh, minOf(ra, rb), minOf(ca, cb), maxOf(ra, rb), maxOf(ca, cb)), mode)
                    }
                }
            }
            i = save
            return scalarArg(parseCompare(), mode)
        }

        /** `range op scalar` used as an argument (FILTER's include): one TRUE / FALSE per cell of the range, in range order. */
        private fun arrayCompare(ref: RangeRef): ParsedArg? {
            val op = COMPARE_OPS.firstOrNull { src.startsWith(it, i) } ?: return null
            i += op.length
            val right = parseConcat()
            return scalarArg(rangeCells(ref).map { compare(it, op, right) })
        }

        private fun compare(left: Any, op: String, right: Any): Boolean {
            val c = cmp(left, right)
            return when (op) { "=" -> c == 0; "<>" -> c != 0; "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 }
        }

        /** The items an argument stands for: a range's cells, an array result's elements, or the lone scalar. */
        private fun itemsOf(a: List<Arg>, idx: Int, tolerant: Boolean = false): List<Any> {
            val arg = a.getOrNull(idx) ?: return emptyList()
            arg.range?.let { return rangeCells(it, tolerant) }
            val v = arg.value
            return if (v is List<*>) v.map { it ?: "" } else listOf(v)
        }

        /** Distinct items in first-seen order (case-insensitive for text), Excel's UNIQUE. */
        private fun uniqueOf(items: List<Any>): List<Any> {
            val out = ArrayList<Any>()
            for (v in items) if (out.none { valEq(it, v) }) out.add(v)
            return out
        }

        /**
         * A single referenced cell: its value plus a 1×1 range so reference functions can see where it is.
         * The aggregates read it as a range: only a number counts (SUM(A1) passes TRUE and text by).
         */
        private fun cellArg(target: Sheet, r: Int, c: Int, mode: ArgMode = ArgMode.EVALUATE): ParsedArg {
            if (mode == ArgMode.REFERENCE) return referenceArg(RangeRef(target, r, c, r, c))
            val v = if (mode == ArgMode.TOLERANT) cellOrError(target, r, c) else cellAt(target, r, c)
            return ParsedArg(doubleArrayOf(quickNum(v)), if (v is Double) doubleArrayOf(v) else DoubleArray(0), if (v !== Blank) 1 else 0, v, RangeRef(target, r, c, r, c))
        }

        /** "A1", "A1:B3", "Sheet2!A1" or "'My Sheet'!A1:B3" → its range. */
        private fun refRange(text: String): RangeRef {
            var t = text.trim()
            var target = sheet
            val bang = t.lastIndexOf('!')
            if (bang >= 0) { target = sheetsByName[t.substring(0, bang).trim().removeSurrounding("'").replace("''", "'").lowercase()] ?: sheet; t = t.substring(bang + 1) }
            resolveName(t)?.let { (sh, ref) -> target = sh; t = ref }
            val parts = t.split(':')
            val (ra, ca) = refToRC(parts[0].trim())
            val (rb, cb) = if (parts.size > 1) refToRC(parts[1].trim()) else (ra to ca)
            return RangeRef(target, minOf(ra, rb), minOf(ca, cb), maxOf(ra, rb), maxOf(ca, cb))
        }

        /** The range OFFSET(ref, rows, cols, [height], [width]) denotes. */
        private fun offsetRange(raw: List<String>): RangeRef {
            if (raw.size < 3) throw FormulaError("#VALUE!")
            val base = refRange(raw[0])
            val dr = num(evalSub(raw[1])).toInt(); val dc = num(evalSub(raw[2])).toInt()
            val h = if (raw.size > 3 && raw[3].isNotBlank()) num(evalSub(raw[3])).toInt() else base.r2 - base.r1 + 1
            val w = if (raw.size > 4 && raw[4].isNotBlank()) num(evalSub(raw[4])).toInt() else base.c2 - base.c1 + 1
            val r1 = base.r1 + dr; val c1 = base.c1 + dc
            if (r1 < 0 || c1 < 0 || h < 1 || w < 1) throw FormulaError("#REF!")
            return RangeRef(base.sheet, r1, c1, r1 + h - 1, c1 + w - 1)
        }

        /** A range: its cells by position (SUMPRODUCT), its numbers alone (SUM, COUNT … pass TRUE, FALSE and text by), its first cell as its value. */
        private fun rangeArg(ref: RangeRef, mode: ArgMode = ArgMode.EVALUATE): ParsedArg {
            if (mode == ArgMode.REFERENCE) return referenceArg(ref)
            val cells = rangeCells(ref, tolerant = mode == ArgMode.TOLERANT)
            val values = DoubleArray(cells.size) { positional(cells[it]) }
            val numbers = cells.filterIsInstance<Double>().toDoubleArray()
            val nonBlank = cells.count { it !== Blank }
            return ParsedArg(values, numbers, nonBlank, cells.firstOrNull() ?: Blank, ref)
        }

        /** A reference as itself, its cells not read (the function reads what it needs); its precedents noted as a range's. */
        private fun referenceArg(ref: RangeRef): ParsedArg { noteRange(ref); return ParsedArg(DoubleArray(0), DoubleArray(0), 0, Blank, ref) }

        private fun scalarArg(v: Any, mode: ArgMode = ArgMode.EVALUATE): ParsedArg {
            // An array result (UNIQUE / FILTER / SORT / a range comparison) feeds aggregates like a range would; an error in it is theirs.
            if (v is List<*>) {
                val items = v.map { it ?: "" }
                if (mode != ArgMode.TOLERANT) items.firstOrNull { it is FormulaError }?.let { throw it as FormulaError }
                return ParsedArg(DoubleArray(items.size) { positional(items[it]) }, items.filterIsInstance<Double>().toDoubleArray(), items.count { it !== Blank }, v, null)
            }
            // A value typed into the call counts as a number when it is one, TRUE / FALSE or text reading as one (SUM(TRUE, "2") is 3).
            val n = when (v) { is Double -> v; is Boolean -> if (v) 1.0 else 0.0; is String -> v.trim().toDoubleOrNull(); else -> null }
            return ParsedArg(doubleArrayOf(quickNum(v)), if (n != null) doubleArrayOf(n) else DoubleArray(0), if (v !== Blank) 1 else 0, v, null)
        }

        private fun applyFunc(name: String, a: List<Arg>): Any {
            val args = a.map { it.values }
            // Statistical aggregates follow Excel and skip blank/text cells; SUMPRODUCT stays positional.
            val flat = a.flatMap { it.numbers.asList() }
            val nonBlank = a.sumOf { it.nonBlank }
            val argTexts = a.map { it.text }
            val argVals = a.map { it.value }
            val ranges = a.map { it.range }
            fun sv(i: Int): Any = argVals.getOrNull(i) ?: 0.0
            fun st(i: Int): String = str(sv(i))
            fun nv(i: Int): Double = num(sv(i))
            /** A lookup's key: a blank cell looks up 0, as in Excel. */
            fun key(i: Int): Any = sv(i).let { if (it === Blank) 0.0 else it }
            /** Argument [i] as a number, or [default] when it is left out or empty. */
            fun opt(i: Int, default: Double): Double = if (i < argVals.size && argVals[i] !== Blank && !(argVals[i] is String && st(i).isEmpty())) nv(i) else default
            // Text functions return strings; handle before the numeric block.
            when (name) {
                "LEN" -> return st(0).length.toDouble()
                "LEFT" -> return st(0).take(if (argVals.size > 1) nv(1).toInt() else 1)
                "RIGHT" -> return st(0).takeLast(if (argVals.size > 1) nv(1).toInt() else 1)
                "MID" -> { val s = st(0); val start = (nv(1).toInt() - 1).coerceIn(0, s.length); val len = nv(2).toInt().coerceAtLeast(0); return s.substring(start, (start + len).coerceAtMost(s.length)) }
                "UPPER" -> return st(0).uppercase()
                "LOWER" -> return st(0).lowercase()
                // PROPER: a letter after a letter in lower case, any other in upper case ("o'neil-smith 2nd" → "O'Neil-Smith 2Nd").
                "PROPER" -> {
                    val sb = StringBuilder(); var afterLetter = false
                    for (ch in st(0)) { sb.append(if (!ch.isLetter()) ch else if (afterLetter) ch.lowercaseChar() else ch.uppercaseChar()); afterLetter = ch.isLetter() }
                    return sb.toString()
                }
                "TRIM" -> return st(0).trim().replace(Regex(" +"), " ")
                // CONCAT and TEXTJOIN take a range's every cell; TEXTJOIN leaves the empty ones out when told to.
                "CONCAT", "CONCATENATE" -> return a.indices.joinToString("") { k -> itemsOf(a, k).joinToString("") { str(it) } }
                "TEXTJOIN" -> {
                    val skipEmpty = argVals.size > 1 && truth(sv(1))
                    return (2 until a.size).flatMap { itemsOf(a, it) }.map { str(it) }.filter { !skipEmpty || it.isNotEmpty() }.joinToString(st(0))
                }
                "REPT" -> { val n = nv(1).toInt(); if (n < 0 || st(0).length.toLong() * n > 32_767) throw FormulaError("#VALUE!"); return st(0).repeat(n) }
                "FIND" -> { val idx = st(1).indexOf(st(0), (if (argVals.size > 2) nv(2).toInt() - 1 else 0).coerceAtLeast(0)); if (idx < 0) throw FormulaError("#VALUE!"); return (idx + 1).toDouble() }
                // SEARCH: without case, `?` any one character, `*` any run of them, `~` the next one as it is.
                "SEARCH" -> {
                    val from = (if (argVals.size > 2) nv(2).toInt() else 1)
                    if (from < 1 || from > st(1).length + 1) throw FormulaError("#VALUE!")
                    val m = wildcard(st(0), whole = false).find(st(1), from - 1) ?: throw FormulaError("#VALUE!")
                    return (m.range.first + 1).toDouble()
                }
                // SUBSTITUTE: every occurrence, or only the [instance]-th; nothing to find leaves the text as it is.
                "SUBSTITUTE" -> {
                    val text = st(0); val old = st(1)
                    if (old.isEmpty()) return text
                    if (argVals.size < 4) return text.replace(old, st(2))
                    val n = nv(3).toInt()
                    if (n < 1) throw FormulaError("#VALUE!")
                    var at = -1
                    repeat(n) { at = text.indexOf(old, at + 1); if (at < 0) return text }
                    return text.substring(0, at) + st(2) + text.substring(at + old.length)
                }
                "REPLACE" -> { val old = st(0); val start = (nv(1).toInt() - 1).coerceIn(0, old.length); val len = nv(2).toInt().coerceAtLeast(0); return old.substring(0, start) + st(3) + old.substring((start + len).coerceAtMost(old.length)) }
                "EXACT" -> return st(0) == st(1)
                "VALUE", "NUMBERVALUE" -> return (sv(0) as? Double) ?: textNumber(st(0)) ?: throw FormulaError("#VALUE!")
                "CHAR" -> { val n = nv(0).toInt(); if (n < 1 || n > 255) throw FormulaError("#VALUE!"); return n.toChar().toString() }
                "UNICHAR" -> { val n = nv(0).toInt(); if (n < 1 || n > 0x10FFFF || n in 0xD800..0xDFFF) throw FormulaError("#VALUE!"); return String(Character.toChars(n)) }
                "UNICODE" -> return st(0).takeIf { it.isNotEmpty() }?.codePointAt(0)?.toDouble() ?: throw FormulaError("#VALUE!")
                // CLEAN: without the first 32 control characters.
                "CLEAN" -> return st(0).filter { it.code >= 32 }
                // FIXED: rounded to [decimals] (2), grouped by thousands unless told not to.
                "FIXED" -> {
                    val d = if (argVals.size > 1) nv(1).toInt() else 2
                    val v = FormulaMath.round(nv(0), d, java.math.RoundingMode.HALF_UP)
                    val grouped = !(argVals.size > 2 && truth(sv(2)))
                    return java.text.DecimalFormat((if (grouped) "#,##0" else "0") + (if (d > 0) "." + "0".repeat(d) else ""),
                        java.text.DecimalFormatSymbols(java.util.Locale.ROOT)).apply { roundingMode = java.math.RoundingMode.HALF_UP }.format(v)
                }
                // DOLLAR: an amount in the currency of the language's region, [decimals] (2) places.
                "DOLLAR" -> {
                    val d = if (argVals.size > 1) nv(1).toInt() else 2
                    val v = FormulaMath.round(nv(0), d, java.math.RoundingMode.HALF_UP)
                    val symbol = CURRENCY_SYMBOLS[java.util.Locale.getDefault().country] ?: "$"
                    val body = java.text.DecimalFormat("#,##0" + (if (d > 0) "." + "0".repeat(d) else ""), java.text.DecimalFormatSymbols(java.util.Locale.ROOT))
                        .apply { roundingMode = java.math.RoundingMode.HALF_UP }.format(kotlin.math.abs(v))
                    return if (v < 0) "-$symbol$body" else "$symbol$body"
                }
                // TEXTBEFORE / TEXTAFTER: the text before / after the [instance]-th delimiter (counting from the end when negative).
                "TEXTBEFORE", "TEXTAFTER" -> {
                    val text = st(0); val delim = st(1)
                    val n = if (argVals.size > 2 && st(2).isNotEmpty()) nv(2).toInt() else 1
                    val noCase = argVals.size > 3 && nv(3) != 0.0
                    if (n == 0) throw FormulaError("#VALUE!")
                    if (delim.isEmpty()) return if ((name == "TEXTBEFORE") == (n > 0)) "" else text
                    val hay = if (noCase) text.lowercase() else text; val needle = if (noCase) delim.lowercase() else delim
                    var at = if (n > 0) -1 else hay.length + 1
                    repeat(kotlin.math.abs(n)) {
                        at = if (n > 0) hay.indexOf(needle, at + 1) else hay.lastIndexOf(needle, at - 1)
                        if (at < 0) return if (argVals.size > 5) sv(5) else throw FormulaError("#N/A")
                    }
                    return if (name == "TEXTBEFORE") text.substring(0, at) else text.substring(at + delim.length)
                }
                "CODE" -> return st(0).firstOrNull()?.code?.toDouble() ?: throw FormulaError("#VALUE!")
                "T" -> return if (sv(0) is String) sv(0) else ""
                "TEXT" -> return textFormat(sv(0), st(1))
                "N" -> return when (val v = sv(0)) { is Double -> v; is Boolean -> if (v) 1.0 else 0.0; else -> 0.0 }
                "ISBLANK" -> return sv(0) === Blank
                "ISNUMBER" -> return sv(0) is Double
                "ISTEXT" -> return sv(0) is String
                "ISNONTEXT" -> return sv(0) !is String
                "ISLOGICAL" -> return sv(0) is Boolean
                "ISFORMULA" -> return ranges.getOrNull(0)?.let { it.sheet.cell(it.r1, it.c1)?.text?.startsWith("=") == true } ?: throw FormulaError("#VALUE!")
                "ISEVEN" -> return nv(0).toLong() % 2 == 0L
                "ISODD" -> return nv(0).toLong() % 2 != 0L
                "NA" -> throw FormulaError("#N/A")
                "HYPERLINK" -> return if (argVals.size > 1) st(1) else st(0)
                "FORMULATEXT" -> return ranges.getOrNull(0)?.let { it.sheet.cell(it.r1, it.c1)?.text }?.takeIf { it.startsWith("=") } ?: throw FormulaError("#N/A")
                "OFFSET" -> { val r = offsetRange(argTexts); return cellAt(r.sheet, r.r1, r.c1) }
                "ROW" -> return ((ranges.getOrNull(0)?.r1 ?: (if (selfKey >= 0) rectOfKey(selfKey)[1] else 0)) + 1).toDouble()
                "COLUMN" -> return ((ranges.getOrNull(0)?.c1 ?: (if (selfKey >= 0) rectOfKey(selfKey)[2] else 0)) + 1).toDouble()
                "ROWS" -> return (ranges.getOrNull(0)?.let { it.r2 - it.r1 + 1 } ?: 1).toDouble()
                "COLUMNS" -> return (ranges.getOrNull(0)?.let { it.c2 - it.c1 + 1 } ?: 1).toDouble()
                // Dynamic-array functions: the result is a list (shown comma-separated in a cell, fed to aggregates / INDEX / MATCH as items).
                "UNIQUE" -> return uniqueOf(itemsOf(a, 0))
                "FILTER" -> {
                    val items = itemsOf(a, 0); val include = itemsOf(a, 1)
                    val cols = ranges.getOrNull(0)?.let { it.c2 - it.c1 + 1 } ?: 1
                    val out = ArrayList<Any>()
                    if (cols > 1 && items.size == include.size * cols) {
                        // Whole rows of a multi-column block, matched by a one-column include.
                        for (r in include.indices) if (num(include[r]) != 0.0) for (c in 0 until cols) out.add(items[r * cols + c])
                    } else {
                        for (k in 0 until minOf(items.size, include.size)) if (num(include[k]) != 0.0) out.add(items[k])
                    }
                    if (out.isEmpty()) return if (argVals.size > 2) sv(2) else throw FormulaError("#CALC!")
                    return out
                }
                "SORT" -> {
                    val desc = argVals.size > 2 && nv(2) < 0
                    // Excel's order: numbers, text, FALSE and TRUE, blanks last.
                    val sorted = itemsOf(a, 0).sortedWith { x, y -> if (x === Blank || y === Blank) (if (x === Blank) 1 else 0) - (if (y === Blank) 1 else 0) else cmp(x, y) }
                    return if (desc) sorted.reversed() else sorted
                }
                "XLOOKUP" -> {
                    val lr = ranges.getOrNull(1) ?: throw FormulaError("#VALUE!"); val rr = ranges.getOrNull(2) ?: throw FormulaError("#VALUE!")
                    val idx = rangeCells(lr, tolerant = true).indexOfFirst { valEq(it, key(0)) }
                    if (idx < 0) return if (argVals.size > 3) sv(3) else throw FormulaError("#N/A")
                    // The returned cell in line with the found one, across or down as the ranges run.
                    val across = lr.r1 == lr.r2 && lr.c1 != lr.c2
                    val (r, c) = if (across) rr.r1 to rr.c1 + idx else rr.r1 + idx to rr.c1
                    if (r > rr.r2 || c > rr.c2) throw FormulaError("#VALUE!")
                    return cellAt(rr.sheet, r, c)
                }
                "DATEDIF" -> {
                    val a = fromSerial(nv(0)); val b = fromSerial(nv(1))
                    if (b.isBefore(a)) throw FormulaError("#NUM!")
                    val p = java.time.Period.between(a, b)
                    return when (st(2).uppercase()) {
                        "Y" -> p.years.toDouble()
                        "M" -> (p.years * 12 + p.months).toDouble()
                        "D" -> ChronoUnit.DAYS.between(a, b).toDouble()
                        "YM" -> p.months.toDouble()
                        "MD" -> p.days.toDouble()
                        "YD" -> ChronoUnit.DAYS.between(a.plusYears(p.years.toLong()), b).toDouble()
                        else -> throw FormulaError("#NUM!")
                    }
                }
                // Working days between two dates (or a date so many working days on), weekends as given (.INTL) and holidays aside.
                "NETWORKDAYS", "NETWORKDAYS.INTL", "WORKDAY", "WORKDAY.INTL" -> {
                    val intl = name.endsWith(".INTL")
                    val weekend = FormulaMath.weekendOf(if (intl && argVals.size > 2 && sv(2) !== Blank && !(sv(2) is String && st(2).isEmpty())) sv(2) else null)
                    val holidays = itemsOf(a, if (intl) 3 else 2).filterIsInstance<Double>().map { FormulaMath.date(it) }.toSet()
                    val start = FormulaMath.date(nv(0))
                    return if (name.startsWith("NETWORKDAYS")) FormulaMath.networkDays(start, FormulaMath.date(nv(1)), weekend, holidays)
                    else FormulaMath.serial(FormulaMath.workday(start, nv(1).toInt(), weekend, holidays))
                }
            }
            // A criteria is its argument's value — "apple", ">"&B1, a cell, TODAY() — not the argument as written.
            fun crit(i: Int): String = argVals.getOrNull(i)?.let { str(it) } ?: ""
            return when (name) {
                "COUNTIF" -> { val c = crit(1); rangeCellsOrNums(ranges.getOrNull(0), args.getOrNull(0), tolerant = true).count { matchCriteriaAny(it, c) }.toDouble() }
                "SUMIF" -> conditionalAny(ranges, args, crit(1), average = false)
                "AVERAGEIF" -> conditionalAny(ranges, args, crit(1), average = true)
                // multi-criteria: COUNTIFS(r1,c1,r2,c2,…); SUM/AVERAGEIFS(sumRange,r1,c1,…)
                "COUNTIFS" -> {
                    val pairs = (0 until argTexts.size step 2).mapNotNull { j -> ranges.getOrNull(j)?.let { rangeCells(it, tolerant = true) to crit(j + 1) } }
                    val n = pairs.minOfOrNull { it.first.size } ?: 0
                    ifsMatches(pairs, n).size.toDouble()
                }
                "SUMIFS", "AVERAGEIFS", "MAXIFS", "MINIFS" -> {
                    val sumCells = rangeCells(ranges.getOrNull(0) ?: throw FormulaError("#VALUE!"), tolerant = true)
                    val pairs = (1 until argTexts.size step 2).mapNotNull { j -> ranges.getOrNull(j)?.let { rangeCells(it, tolerant = true) to crit(j + 1) } }
                    val n = (listOf(sumCells.size) + pairs.map { it.first.size }).min()
                    val hits = ifsMatches(pairs, n)
                    // An error among the cells picked is the result, as in Excel; the others' errors do not count.
                    val picked = hits.map { sumCells[it].also { v -> if (v is FormulaError) throw v } }.filterIsInstance<Double>()
                    when (name) {
                        "MAXIFS" -> picked.maxOrNull() ?: 0.0
                        "MINIFS" -> picked.minOrNull() ?: 0.0
                        "AVERAGEIFS" -> FormulaMath.average(picked)
                        else -> picked.sum()
                    }
                }
                "RANK", "RANK.EQ" -> {
                    val v = nv(0); val vals = a.getOrNull(1)?.numbers?.toList() ?: emptyList()
                    val desc = if (argVals.size > 2) nv(2) == 0.0 else true
                    val sorted = if (desc) vals.sortedDescending() else vals.sorted()
                    val pos = sorted.indexOfFirst { abs(it - v) < 1e-9 }
                    if (pos < 0) throw FormulaError("#N/A") else (pos + 1).toDouble()
                }
                "VLOOKUP" -> vlookup(ranges.getOrNull(1) ?: throw FormulaError("#N/A"), key(0), nv(2).toInt(), if (argVals.size > 3) truth(sv(3)) else true)
                "HLOOKUP" -> hlookup(ranges.getOrNull(1) ?: throw FormulaError("#N/A"), key(0), nv(2).toInt(), if (argVals.size > 3) truth(sv(3)) else true)
                "MATCH" -> matchPos(itemsOf(a, 1, tolerant = true), key(0), if (argVals.size > 2) nv(2).toInt() else 1)
                // XMATCH(lookup, array, [match_mode 0 exact / -1 or smaller / 1 or larger / 2 wildcards], [search_mode 1 first / -1 last]).
                "XMATCH" -> {
                    val items = itemsOf(a, 1, tolerant = true)
                    val mode = if (argVals.size > 2) nv(2).toInt() else 0
                    val fromEnd = argVals.size > 3 && nv(3) < 0
                    val order = if (fromEnd) items.indices.reversed() else items.indices
                    val key = key(0)
                    val pick = when (mode) {
                        0 -> order.firstOrNull { valEq(items[it], key) }
                        2 -> order.firstOrNull { lookupMatches(items[it], key, wildcards = true) }
                        -1, 1 -> order.firstOrNull { valEq(items[it], key) } ?: run {
                            // The nearest value on the side asked for, of the same kind.
                            order.filter { items[it] !is FormulaError && items[it] !== Blank && kindOf(items[it]) == kindOf(key) && (if (mode < 0) cmp(items[it], key) < 0 else cmp(items[it], key) > 0) }
                                .let { c -> if (mode < 0) c.maxWithOrNull { x, y -> cmp(items[x], items[y]) } else c.minWithOrNull { x, y -> cmp(items[x], items[y]) } }
                        }
                        else -> throw FormulaError("#VALUE!")
                    } ?: throw FormulaError("#N/A")
                    (pick + 1).toDouble()
                }
                // LOOKUP: the largest value not above the lookup in a sorted vector, and what is in line with it in the result vector
                // (or, with one 2-D range, its first row or column searched and its last one returned).
                "LOOKUP" -> {
                    val lr = ranges.getOrNull(1)
                    val result = ranges.getOrNull(2)
                    // A 2-D range alone: its first row or column searched (the longer way), its last one returned.
                    val twoD = lr != null && result == null && a.size < 3 && lr.r1 != lr.r2 && lr.c1 != lr.c2
                    val wide = lr != null && lr.c2 - lr.c1 > lr.r2 - lr.r1
                    val items = when {
                        lr == null -> itemsOf(a, 1, tolerant = true)
                        twoD -> rangeCells(if (wide) RangeRef(lr.sheet, lr.r1, lr.c1, lr.r1, lr.c2) else RangeRef(lr.sheet, lr.r1, lr.c1, lr.r2, lr.c1), tolerant = true)
                        else -> rangeCells(lr, tolerant = true)
                    }
                    var best = -1
                    for (k in items.indices) {
                        val v = items[k]
                        if (v is FormulaError || v === Blank || kindOf(v) != kindOf(key(0))) continue
                        if (cmp(v, key(0)) <= 0) best = k else break
                    }
                    if (best < 0) throw FormulaError("#N/A")
                    when {
                        twoD -> if (wide) cellAt(lr!!.sheet, lr.r2, lr.c1 + best) else cellAt(lr!!.sheet, lr.r1 + best, lr.c2)
                        result != null -> if (result.r1 == result.r2 && result.c1 != result.c2) cellAt(result.sheet, result.r1, result.c1 + best) else cellAt(result.sheet, result.r1 + best, result.c1)
                        a.size > 2 -> itemsOf(a, 2).getOrElse(best) { throw FormulaError("#N/A") }
                        else -> items[best]
                    }
                }
                // ADDRESS(row, column, [abs_num 1 $C$2 / 2 C$2 / 3 $C2 / 4 C2], [a1], [sheet]).
                "ADDRESS" -> {
                    val r = nv(0).toInt(); val c = nv(1).toInt()
                    if (r < 1 || c < 1 || c > 16_384) throw FormulaError("#VALUE!")
                    val abs = if (argVals.size > 2) nv(2).toInt() else 1
                    if (abs !in 1..4) throw FormulaError("#VALUE!")
                    val a1 = !(argVals.size > 3 && !truth(sv(3)))
                    val ref = if (a1) (if (abs == 1 || abs == 3) "$" else "") + com.therealityteam.aeropages.model.Workbook.colLabel(c - 1) + (if (abs <= 2) "$" else "") + r
                        else "R" + (if (abs <= 2) "$r" else "[$r]") + "C" + (if (abs == 1 || abs == 3) "$c" else "[$c]")
                    val sheetName = if (argVals.size > 4) st(4) else ""
                    if (sheetName.isEmpty()) ref else com.therealityteam.aeropages.model.A1.quoteSheet(sheetName) + "!" + ref
                }
                "INDEX" -> ranges.getOrNull(0)?.let { indexAt(it, nv(1).toInt(), if (argVals.size > 2) nv(2).toInt() else null) }
                    ?: itemsOf(a, 0).getOrElse(nv(1).toInt() - 1) { throw FormulaError("#REF!") }
                "INDIRECT" -> {
                    val t = st(0).trim()
                    val bang = t.lastIndexOf('!')
                    val target = if (bang >= 0) (sheetsByName[t.substring(0, bang).trim().removeSurrounding("'").replace("''", "'").lowercase()] ?: sheet) else sheet
                    val (r, c) = refToRC(if (bang >= 0) t.substring(bang + 1) else t)
                    cellAt(target, r, c)
                }
                // CHOOSE(idx, v1, v2, …): 1-based → argVals[idx]; past the values, #VALUE!.
                "CHOOSE" -> nv(0).toInt().let { k -> if (k < 1 || k >= argVals.size) throw FormulaError("#VALUE!") else sv(k) }
                "SUMPRODUCT" -> {
                    if (args.isEmpty()) 0.0 else {
                        val n = args.minOf { it.size }
                        var total = 0.0
                        for (idx in 0 until n) { var p = 1.0; for (arr in args) p *= arr[idx]; total += p }
                        total
                    }
                }
                "LARGE" -> { val k = nv(1).toInt(); (a.getOrNull(0)?.numbers?.toList()?.sortedDescending() ?: emptyList()).getOrElse(k - 1) { throw FormulaError("#NUM!") } }
                "SMALL" -> { val k = nv(1).toInt(); (a.getOrNull(0)?.numbers?.toList()?.sorted() ?: emptyList()).getOrElse(k - 1) { throw FormulaError("#NUM!") } }
                "COUNTBLANK" -> { val ref = ranges.getOrNull(0); (if (ref != null) rangeCells(ref, tolerant = true) else emptyList()).count { str(it).isEmpty() }.toDouble() }
                // SUBTOTAL(fn, ref…): 1 AVERAGE 2 COUNT 3 COUNTA 4 MAX 5 MIN 6 PRODUCT 7 STDEV 8 STDEVP 9 SUM 10 VAR 11 VARP
                // (101–111 alike). Cells holding a SUBTOTAL formula of their own are skipped, so a grand
                // total over a block with group subtotals does not double count — Excel's rule.
                "SUBTOTAL" -> {
                    val fn = nv(0).toInt().let { if (it > 100) it - 100 else it }
                    val vals = ArrayList<Double>()
                    var nonBlankCount = 0
                    for (i in 1 until a.size) {
                        val ref = ranges.getOrNull(i)
                        if (ref == null) { a[i].numbers.forEach { vals.add(it) }; nonBlankCount += a[i].nonBlank; continue }
                        for (r in ref.r1..ref.r2) for (c in ref.c1..ref.c2) {
                            val raw = ref.sheet.cell(r, c)?.text ?: ""
                            if (raw.trimStart().uppercase().startsWith("=SUBTOTAL(")) continue
                            val v = cellAt(ref.sheet, r, c)
                            if (v is Double) vals.add(v)
                            if (v !== Blank) nonBlankCount++
                        }
                    }
                    fun variance(sample: Boolean): Double = FormulaMath.variance(vals, sample)
                    when (fn) {
                        1 -> FormulaMath.average(vals)
                        2 -> vals.size.toDouble()
                        3 -> nonBlankCount.toDouble()
                        4 -> vals.maxOrNull() ?: 0.0
                        5 -> vals.minOrNull() ?: 0.0
                        6 -> vals.fold(1.0) { x, y -> x * y }
                        7 -> sqrt(variance(true))
                        8 -> sqrt(variance(false))
                        9 -> vals.sum()
                        10 -> variance(true)
                        11 -> variance(false)
                        else -> throw FormulaError("#VALUE!")
                    }
                }
                "SUM" -> flat.sum()
                "AVERAGE", "AVG" -> FormulaMath.average(flat)
                "MIN" -> flat.minOrNull() ?: 0.0
                "MAX" -> flat.maxOrNull() ?: 0.0
                "COUNT" -> flat.size.toDouble()
                "COUNTA" -> nonBlank.toDouble()
                // The "A" functions: TRUE as 1, FALSE and text as 0 in ranges too.
                "AVERAGEA" -> FormulaMath.average(aValues(a))
                "MAXA" -> aValues(a).maxOrNull() ?: 0.0
                "MINA" -> aValues(a).minOrNull() ?: 0.0
                "ABS" -> abs(nv(0))
                "SQRT" -> nv(0).let { if (it < 0) throw FormulaError("#NUM!") else sqrt(it) }
                // Rounding on the number's fifteen-digit value, halves away from 0, as Excel's (ROUND(1.005,2) is 1.01, ROUND(-2.5,0) is -3).
                "ROUND" -> FormulaMath.round(nv(0), nv(1).toInt(), java.math.RoundingMode.HALF_UP)
                "ROUNDUP" -> FormulaMath.round(nv(0), nv(1).toInt(), java.math.RoundingMode.UP)
                "ROUNDDOWN" -> FormulaMath.round(nv(0), nv(1).toInt(), java.math.RoundingMode.DOWN)
                "TRUNC" -> FormulaMath.round(nv(0), if (argVals.size > 1) nv(1).toInt() else 0, java.math.RoundingMode.DOWN)
                "INT" -> FormulaMath.round(nv(0), 0, java.math.RoundingMode.FLOOR)
                "MROUND" -> FormulaMath.mround(nv(0), nv(1))
                "CEILING" -> FormulaMath.ceiling(nv(0), if (argVals.size > 1) nv(1) else 1.0)
                "FLOOR" -> FormulaMath.floor(nv(0), if (argVals.size > 1) nv(1) else 1.0)
                "CEILING.MATH" -> FormulaMath.ceilingMath(nv(0), if (argVals.size > 1 && st(1).isNotEmpty()) nv(1) else 1.0, if (argVals.size > 2) nv(2) else 0.0)
                "FLOOR.MATH" -> FormulaMath.floorMath(nv(0), if (argVals.size > 1 && st(1).isNotEmpty()) nv(1) else 1.0, if (argVals.size > 2) nv(2) else 0.0)
                "CEILING.PRECISE", "ISO.CEILING" -> FormulaMath.ceilingMath(nv(0), if (argVals.size > 1) nv(1) else 1.0, 0.0)
                "FLOOR.PRECISE" -> FormulaMath.floorMath(nv(0), if (argVals.size > 1) nv(1) else 1.0, 0.0)
                "EVEN" -> FormulaMath.even(nv(0))
                "ODD" -> FormulaMath.odd(nv(0))
                "QUOTIENT" -> nv(1).let { d -> if (d == 0.0) throw FormulaError("#DIV/0!") else truncate(nv(0) / d) }
                "GCD" -> FormulaMath.gcd(flat)
                "LCM" -> FormulaMath.lcm(flat)
                "FACT" -> FormulaMath.fact(nv(0))
                "COMBIN" -> FormulaMath.combin(nv(0), nv(1))
                "COMBINA" -> FormulaMath.combin(nv(0) + nv(1) - 1, nv(1))
                "PERMUT" -> FormulaMath.permut(nv(0), nv(1))
                "PERMUTATIONA" -> nv(0).toLong().toDouble().pow(nv(1).toLong().toDouble())
                "SQRTPI" -> nv(0).let { if (it < 0) throw FormulaError("#NUM!") else sqrt(it * Math.PI) }
                "POWER" -> power(nv(0), nv(1))
                "AND" -> logicals(a).all { it }
                "OR" -> logicals(a).any { it }
                "NOT" -> !truth(sv(0))
                "XOR" -> logicals(a).count { it } % 2 == 1
                "TRUE" -> true
                "FALSE" -> false
                // aggregate
                "PRODUCT" -> flat.fold(1.0) { a, b -> a * b }
                "SUMSQ" -> flat.sumOf { it * it }
                "MEDIAN" -> FormulaMath.median(flat)
                "MODE", "MODE.SNGL" -> FormulaMath.mode(flat)
                "STDEV", "STDEV.S" -> sqrt(FormulaMath.variance(flat, sample = true))
                "STDEVP", "STDEV.P" -> sqrt(FormulaMath.variance(flat, sample = false))
                "VAR", "VAR.S" -> FormulaMath.variance(flat, sample = true)
                "VARP", "VAR.P" -> FormulaMath.variance(flat, sample = false)
                "DEVSQ" -> FormulaMath.devsq(flat)
                "AVEDEV" -> FormulaMath.avedev(flat)
                "GEOMEAN" -> FormulaMath.geomean(flat)
                "HARMEAN" -> FormulaMath.harmean(flat)
                "PERCENTILE", "PERCENTILE.INC" -> FormulaMath.percentileInc(a.getOrNull(0)?.numbers?.toList().orEmpty(), nv(1))
                "PERCENTILE.EXC" -> FormulaMath.percentileExc(a.getOrNull(0)?.numbers?.toList().orEmpty(), nv(1))
                "QUARTILE", "QUARTILE.INC" -> nv(1).toInt().let { q -> if (q !in 0..4) throw FormulaError("#NUM!"); FormulaMath.percentileInc(a.getOrNull(0)?.numbers?.toList().orEmpty(), q / 4.0) }
                "QUARTILE.EXC" -> nv(1).toInt().let { q -> if (q !in 1..3) throw FormulaError("#NUM!"); FormulaMath.percentileExc(a.getOrNull(0)?.numbers?.toList().orEmpty(), q / 4.0) }
                "RANK.AVG" -> FormulaMath.rankAvg(nv(0), a.getOrNull(1)?.numbers?.toList().orEmpty(), argVals.size > 2 && nv(2) != 0.0)
                "CORREL", "PEARSON" -> FormulaMath.correl(FormulaMath.pairs(itemsOf(a, 0), itemsOf(a, 1)))
                "RSQ" -> FormulaMath.correl(FormulaMath.pairs(itemsOf(a, 0), itemsOf(a, 1))).let { it * it }
                "SLOPE" -> FormulaMath.slope(FormulaMath.pairs(itemsOf(a, 0), itemsOf(a, 1)))
                "INTERCEPT" -> FormulaMath.intercept(FormulaMath.pairs(itemsOf(a, 0), itemsOf(a, 1)))
                "FORECAST", "FORECAST.LINEAR" -> FormulaMath.forecast(nv(0), FormulaMath.pairs(itemsOf(a, 1), itemsOf(a, 2)))
                "COVAR", "COVARIANCE.P" -> FormulaMath.covariance(FormulaMath.pairs(itemsOf(a, 0), itemsOf(a, 1)), sample = false)
                "COVARIANCE.S" -> FormulaMath.covariance(FormulaMath.pairs(itemsOf(a, 0), itemsOf(a, 1)), sample = true)
                "STANDARDIZE" -> nv(2).let { sd -> if (sd <= 0) throw FormulaError("#NUM!") else (nv(0) - nv(1)) / sd }
                "NORM.S.DIST" -> FormulaMath.normS(nv(0), truth(sv(1)))
                "NORMSDIST" -> FormulaMath.normS(nv(0), true)
                "NORM.DIST", "NORMDIST" -> nv(2).let { sd -> if (sd <= 0) throw FormulaError("#NUM!"); val z = (nv(0) - nv(1)) / sd; if (truth(sv(3))) FormulaMath.normS(z, true) else FormulaMath.normS(z, false) / sd }
                "NORM.S.INV", "NORMSINV" -> FormulaMath.normSInv(nv(0))
                "NORM.INV", "NORMINV" -> nv(2).let { sd -> if (sd <= 0) throw FormulaError("#NUM!") else nv(1) + sd * FormulaMath.normSInv(nv(0)) }
                // math
                "MOD" -> { val a = nv(0); val b = nv(1); if (b == 0.0) throw FormulaError("#DIV/0!") else a - b * floor(a / b) }
                "SIGN" -> sign(nv(0))
                "EXP" -> exp(nv(0))
                "LN" -> nv(0).let { if (it <= 0) throw FormulaError("#NUM!") else ln(it) }
                "LOG10" -> nv(0).let { if (it <= 0) throw FormulaError("#NUM!") else log10(it) }
                "LOG" -> { val x = nv(0); val base = if (argVals.size > 1) nv(1) else 10.0; if (x <= 0 || base <= 0) throw FormulaError("#NUM!"); if (base == 1.0) throw FormulaError("#DIV/0!"); ln(x) / ln(base) }
                "PI" -> Math.PI
                // trig (radians)
                "SIN" -> sin(nv(0)); "COS" -> cos(nv(0)); "TAN" -> tan(nv(0))
                "ASIN" -> asin(nv(0)); "ACOS" -> acos(nv(0)); "ATAN" -> atan(nv(0))
                "ATAN2" -> { val x = nv(0); val y = nv(1); if (x == 0.0 && y == 0.0) throw FormulaError("#DIV/0!"); atan2(y, x) } // Excel ATAN2(x, y) = kotlin atan2(y, x)
                "DEGREES" -> Math.toDegrees(nv(0))
                "RADIANS" -> Math.toRadians(nv(0))
                // dates (serials: 1900-01-01 = 1); a year 0–1899 is counted from 1900, as Excel's.
                "DATE" -> {
                    var y = nv(0).toInt()
                    if (y in 0..1899) y += 1900
                    if (y < 0 || y > 9999) throw FormulaError("#NUM!")
                    val d = toSerial(LocalDate.of(y, 1, 1).plusMonths(nv(1).toLong() - 1).plusDays(nv(2).toLong() - 1))
                    if (d < 0) throw FormulaError("#NUM!") else d
                }
                "YEAR" -> FormulaMath.date(nv(0)).year.toDouble()
                "MONTH" -> FormulaMath.date(nv(0)).monthValue.toDouble()
                "DAY" -> FormulaMath.date(nv(0)).dayOfMonth.toDouble()
                "WEEKDAY" -> FormulaMath.weekday(FormulaMath.date(nv(0)), if (argVals.size > 1) nv(1).toInt() else 1)
                "WEEKNUM" -> FormulaMath.weeknum(FormulaMath.date(nv(0)), if (argVals.size > 1) nv(1).toInt() else 1)
                "ISOWEEKNUM" -> FormulaMath.weeknum(FormulaMath.date(nv(0)), 21)
                "DAYS" -> floor(nv(0)) - floor(nv(1))
                "DAYS360" -> FormulaMath.days360(FormulaMath.date(nv(0)), FormulaMath.date(nv(1)), european = argVals.size > 2 && truth(sv(2)))
                "YEARFRAC" -> FormulaMath.yearFrac(FormulaMath.date(nv(0)), FormulaMath.date(nv(1)), if (argVals.size > 2) nv(2).toInt() else 0)
                "EDATE" -> toSerial(FormulaMath.date(nv(0)).plusMonths(nv(1).toLong()))
                "EOMONTH" -> { val d = FormulaMath.date(nv(0)).plusMonths(nv(1).toLong()); toSerial(d.withDayOfMonth(d.lengthOfMonth())) }
                // TIME wraps past a day (TIME(25,0,0) is 1:00); HOUR / MINUTE / SECOND of a date-time, to the nearest second.
                "TIME" -> { val sec = nv(0).toLong() * 3600 + nv(1).toLong() * 60 + nv(2).toLong(); if (sec < 0) throw FormulaError("#NUM!"); (sec % 86_400) / 86_400.0 }
                "HOUR" -> (secondsOfDay(nv(0)) / 3600).toDouble()
                "MINUTE" -> (secondsOfDay(nv(0)) / 60 % 60).toDouble()
                "SECOND" -> (secondsOfDay(nv(0)) % 60).toDouble()
                // DATEVALUE / TIMEVALUE: text read as a date or a time is, as typed into a cell.
                "DATEVALUE" -> datedEntry(st(0), NumberFormat.DATE)?.let { floor(it) } ?: throw FormulaError("#VALUE!")
                "TIMEVALUE" -> datedEntry(st(0), NumberFormat.TIME)?.let { it - floor(it) } ?: throw FormulaError("#VALUE!")
                // loans and investments: rate per period, number of periods, payment, present and future value, type 1 = paid at the start.
                "PMT" -> FormulaMath.pmt(nv(0), nv(1), nv(2), opt(3, 0.0), opt(4, 0.0))
                "IPMT" -> FormulaMath.ipmt(nv(0), nv(1), nv(2), nv(3), opt(4, 0.0), opt(5, 0.0))
                "PPMT" -> FormulaMath.ppmt(nv(0), nv(1), nv(2), nv(3), opt(4, 0.0), opt(5, 0.0))
                "PV" -> FormulaMath.pv(nv(0), nv(1), nv(2), opt(3, 0.0), opt(4, 0.0))
                "FV" -> FormulaMath.fv(nv(0), nv(1), nv(2), opt(3, 0.0), opt(4, 0.0))
                "NPER" -> FormulaMath.nper(nv(0), nv(1), nv(2), opt(3, 0.0), opt(4, 0.0))
                "RATE" -> FormulaMath.rate(nv(0), nv(1), nv(2), opt(3, 0.0), opt(4, 0.0), opt(5, 0.1))
                "NPV" -> FormulaMath.npv(nv(0), a.drop(1).flatMap { it.numbers.asList() })
                "IRR" -> FormulaMath.irr(a.getOrNull(0)?.numbers?.toList().orEmpty(), opt(1, 0.1))
                "XNPV" -> FormulaMath.xnpv(nv(0), itemsOf(a, 1).map { num(it) }, itemsOf(a, 2).map { num(it) })
                "XIRR" -> FormulaMath.xirr(itemsOf(a, 0).map { num(it) }, itemsOf(a, 1).map { num(it) }, opt(2, 0.1))
                "CUMIPMT", "CUMPRINC" -> {
                    val rate = nv(0); val nper = nv(1); val pv = nv(2); val from = nv(3).toInt(); val to = nv(4).toInt(); val type = nv(5)
                    if (rate <= 0 || nper <= 0 || pv <= 0 || from < 1 || to < from || to > nper || (type != 0.0 && type != 1.0)) throw FormulaError("#NUM!")
                    (from..to).sumOf { per -> if (name == "CUMIPMT") FormulaMath.ipmt(rate, per.toDouble(), nper, pv, 0.0, type) else FormulaMath.ppmt(rate, per.toDouble(), nper, pv, 0.0, type) }
                }
                "SLN" -> FormulaMath.sln(nv(0), nv(1), nv(2))
                "SYD" -> FormulaMath.syd(nv(0), nv(1), nv(2), nv(3))
                "DDB" -> FormulaMath.ddb(nv(0), nv(1), nv(2), nv(3), opt(4, 2.0))
                "DB" -> FormulaMath.db(nv(0), nv(1), nv(2), nv(3), opt(4, 12.0))
                "EFFECT" -> FormulaMath.effect(nv(0), nv(1))
                "NOMINAL" -> FormulaMath.nominal(nv(0), nv(1))
                "TODAY" -> { memo?.noteVolatile(selfKey); toSerial(LocalDate.now()) }
                "NOW" -> { memo?.noteVolatile(selfKey); toSerial(LocalDate.now()) + java.time.LocalTime.now().toSecondOfDay() / 86_400.0 }
                "RAND" -> { memo?.noteVolatile(selfKey); Math.random() }
                "RANDBETWEEN" -> { memo?.noteVolatile(selfKey); val lo = scalar(args, 0); val hi = scalar(args, 1); floor(lo + Math.random() * (hi - lo + 1)) }
                else -> throw FormulaError("#NAME?")
            }.let { if (it is Double) finite(it) else it }
        }

        /**
         * The logical values AND, OR and XOR weigh: TRUE / FALSE and numbers (not 0 is TRUE). In a range or
         * an array, text and blanks are passed over; typed into the call, text must read TRUE or FALSE. With
         * none at all the result is #VALUE!, as Excel's.
         */
        private fun logicals(a: List<Arg>): List<Boolean> {
            val out = ArrayList<Boolean>()
            a.forEachIndexed { k, arg ->
                val direct = arg.range == null && arg.value !is List<*>
                for (v in itemsOf(a, k)) when (v) {
                    is Boolean -> out.add(v)
                    is Double -> out.add(v != 0.0)
                    is String -> if (direct && v.isNotEmpty()) out.add(truth(v))
                }
            }
            if (out.isEmpty()) throw FormulaError("#VALUE!")
            return out
        }

        /** The values AVERAGEA, MAXA and MINA weigh: in ranges numbers, TRUE as 1, FALSE and text as 0 (blanks passed by); typed in, as numbers. */
        private fun aValues(a: List<Arg>): List<Double> = a.indices.flatMap { k ->
            val arg = a[k]
            if (arg.range != null || arg.value is List<*>) itemsOf(a, k).mapNotNull { v ->
                when (v) { is Double -> v; is Boolean -> if (v) 1.0 else 0.0; is String -> 0.0; else -> null }
            } else listOf(operand(arg.value))
        }

        /** [text] read as a date (or time, as [kind] asks) is typed into a cell: its serial, or null when it reads as neither that nor a date and time. */
        private fun datedEntry(text: String, kind: NumberFormat): Double? {
            val e = EntryParser.parse(text.trim()) ?: return null
            val k = e.code?.let { FormatCode.category(it) } ?: e.format
            return if (k == kind || k == NumberFormat.DATE_TIME) e.value.toDoubleOrNull() else null
        }

        /** The time of day in [v] (a date-time serial) in whole seconds, to the nearest one. */
        private fun secondsOfDay(v: Double): Long {
            if (v < 0) throw FormulaError("#NUM!")
            return Math.round((v - floor(v)) * 86_400) % 86_400
        }

        /** Whether a lookup finds [key] in [cell]: as `=` compares, or — text with `*`, `?` or `~` when [wildcards] — by pattern, text cells only. */
        private fun lookupMatches(cell: Any, key: Any, wildcards: Boolean): Boolean {
            if (cell is FormulaError) return false
            if (wildcards && key is String && key.any { it == '*' || it == '?' || it == '~' }) return cell is String && wildcard(key, whole = true).matches(cell)
            return valEq(cell, key)
        }

        private val wildcards = HashMap<String, Regex>()
        private fun wildcard(pattern: String, whole: Boolean): Regex = wildcards.getOrPut(pattern + whole) { wildcardRegex(pattern) }

        /** A range's values in order; [tolerant]: an error is one of the values ([FormulaError]) rather than the range's failure. */
        private fun rangeCells(ref: RangeRef, tolerant: Boolean = false): List<Any> = overRange(ref) {
            val out = ArrayList<Any>()
            for (r in ref.r1..ref.r2) for (c in ref.c1..ref.c2) out.add(if (tolerant) cellOrError(ref.sheet, r, c) else cellAt(ref.sheet, r, c))
            out
        }

        /** A cell's value, or its error as a value (a circular reference still fails the formula). */
        private fun cellOrError(s: Sheet, r: Int, c: Int): Any =
            try { cellAt(s, r, c) } catch (e: CycleException) { throw e } catch (e: FormulaError) { e } catch (e: Exception) { FormulaError(codeOf(e)) }

        private fun rangeCellsOrNums(ref: RangeRef?, nums: DoubleArray?, tolerant: Boolean = false): List<Any> =
            if (ref != null) rangeCells(ref, tolerant) else (nums?.toList() ?: emptyList())

        /** SUMIF / AVERAGEIF over ranges: range=arg0, [crit] (arg1's value), optional sumRange=arg2 (index-aligned). */
        private fun conditionalAny(ranges: List<RangeRef?>, args: List<DoubleArray>, crit: String, average: Boolean): Double {
            val rangeCellsL = rangeCellsOrNums(ranges.getOrNull(0), args.getOrNull(0), tolerant = true)
            val sumCellsL = when {
                ranges.getOrNull(2) != null -> rangeCells(ranges[2]!!, tolerant = true)
                args.getOrNull(2) != null -> args[2].toList()
                else -> rangeCellsL
            }
            var sum = 0.0; var n = 0
            // Only numbers are summed and averaged: text, TRUE and FALSE in the sum range count nothing, as Excel's.
            for (idx in rangeCellsL.indices) if (matchCriteriaAny(rangeCellsL[idx], crit)) {
                val v = sumCellsL.getOrElse(idx) { "" }
                if (v is FormulaError) throw v
                if (v is Double) { sum += v; n++ }
            }
            return if (average) (if (n == 0) throw FormulaError("#DIV/0!") else sum / n) else sum
        }

        /** Indices [0,n) where every (cells, criteria) pair matches — for COUNTIFS/SUMIFS/AVERAGEIFS. */
        private fun ifsMatches(pairs: List<Pair<List<Any>, String>>, n: Int): List<Int> {
            val out = ArrayList<Int>()
            for (idx in 0 until n) if (pairs.all { (cells, crit) -> idx < cells.size && matchCriteriaAny(cells[idx], crit) }) out.add(idx)
            return out
        }

        /** Match a value against an Excel criteria (COUNTIF, SUMIFS …) — each criteria read once per formula. */
        private fun matchCriteriaAny(value: Any, criteria: String): Boolean = criteriaCache.getOrPut(criteria) { Criteria(criteria) }.matches(value)

        private val criteriaCache = HashMap<String, Criteria>()

        private fun scalar(args: List<DoubleArray>, idx: Int): Double = args.getOrNull(idx)?.firstOrNull() ?: 0.0

        /** Cell value at absolute (row,col) in sheet [s] as Any, recursing into formulas (cycle-checked across sheets, memoised). */
        private fun cellAt(s: Sheet, r: Int, c: Int): Any {
            val key = compositeKey(ordinalOf(s), r, c)
            if (suppressDeps == 0) memo?.noteCell(selfKey, key)
            memo?.values?.get(key)?.let { if (it is FormulaError) throw it else return it }
            val cell = s.cell(r, c)
            val raw = cell?.text ?: ""
            if (raw.isBlank()) return Blank
            if (raw.startsWith("=")) {
                if (key in visiting) throw CycleException()
                visiting.add(key)
                memo?.beginEvaluation(key)
                try {
                    val v = evaluateNested(s, raw.substring(1), key)
                    memo?.values?.put(key, v)
                    return v
                } finally { visiting.remove(key) }
            }
            // A cell formatted as Text holds text, digits and all: SUM passes it by, as Excel's does.
            if (cell?.formatCode == "@") return raw
            raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
            // TRUE and FALSE typed in (or read from a file's logical cells) are those values.
            return when (raw) { "TRUE" -> true; "FALSE" -> false; else -> raw }
        }

        /**
         * Evaluate a referenced formula one level deeper. Long dependency chains (a running
         * total filled down thousands of rows) would overflow the UI thread's stack, so past
         * [BIG_STACK_AT] the remainder of the chain runs on a thread with a large stack.
         */
        private fun evaluateNested(s: Sheet, src: String, key: Long): Any {
            val next = depth + 1
            if (next >= MAX_DEPTH) throw DepthException()
            if (next == BIG_STACK_AT && !onBigStack) {
                return BigStack.run { Parser(s, src, visiting, sheetsByName, ordinals, names, memo, next, onBigStack = true, selfKey = key).parseAll() }
            }
            return Parser(s, src, visiting, sheetsByName, ordinals, names, memo, next, onBigStack, key).parseAll()
        }

        // --- lookups (VLOOKUP/HLOOKUP/MATCH/INDEX) ---
        /** Whether a lookup finds [a] where [b] is: as `=` compares ([cmp]: 5 is not the text "5"), but a blank matches only a blank. */
        private fun valEq(a: Any, b: Any): Boolean = if (a === Blank || b === Blank) a === b else cmp(a, b) == 0
        private fun valLe(a: Any, b: Any): Boolean = cmp(a, b) <= 0
        private fun valGe(a: Any, b: Any): Boolean = cmp(a, b) >= 0

        /** A lookup's column (or row) number within [size]: below 1 is #VALUE!, past the range #REF!, as Excel's. */
        private fun lookupIndex(index: Int, size: Int): Int = when {
            index < 1 -> throw FormulaError("#VALUE!")
            index > size -> throw FormulaError("#REF!")
            else -> index
        }

        private fun vlookup(ref: RangeRef, lookup: Any, colIdx: Int, approx: Boolean): Any = overRange(ref) {
            val rN = ref.r2 - ref.r1
            val col = ref.c1 + lookupIndex(colIdx, ref.c2 - ref.c1 + 1) - 1
            if (!approx) {
                for (rr in 0..rN) if (lookupMatches(cellOrError(ref.sheet, ref.r1 + rr, ref.c1), lookup, wildcards = true)) return@overRange cellAt(ref.sheet, ref.r1 + rr, col)
                throw FormulaError("#N/A")
            }
            var best = -1
            for (rr in 0..rN) { val v = cellOrError(ref.sheet, ref.r1 + rr, ref.c1); if (v is FormulaError) continue; if (valLe(v, lookup)) best = rr else break }
            if (best < 0) throw FormulaError("#N/A")
            cellAt(ref.sheet, ref.r1 + best, col)
        }

        private fun hlookup(ref: RangeRef, lookup: Any, rowIdx: Int, approx: Boolean): Any = overRange(ref) {
            val cN = ref.c2 - ref.c1
            val row = ref.r1 + lookupIndex(rowIdx, ref.r2 - ref.r1 + 1) - 1
            if (!approx) {
                for (cc in 0..cN) if (lookupMatches(cellOrError(ref.sheet, ref.r1, ref.c1 + cc), lookup, wildcards = true)) return@overRange cellAt(ref.sheet, row, ref.c1 + cc)
                throw FormulaError("#N/A")
            }
            var best = -1
            for (cc in 0..cN) { val v = cellOrError(ref.sheet, ref.r1, ref.c1 + cc); if (v is FormulaError) continue; if (valLe(v, lookup)) best = cc else break }
            if (best < 0) throw FormulaError("#N/A")
            cellAt(ref.sheet, row, ref.c1 + best)
        }

        /** MATCH over a 1-D range; type 0 exact, 1 largest≤ (ascending), -1 smallest≥ (descending). 1-based. */
        private fun matchPos(cells: List<Any>, lookup: Any, type: Int): Double {
            when (type) {
                0 -> { val idx = cells.indexOfFirst { lookupMatches(it, lookup, wildcards = true) }; if (idx < 0) throw FormulaError("#N/A"); return (idx + 1).toDouble() }
                -1 -> { var best = -1; for (i in cells.indices) if (valGe(cells[i], lookup)) best = i else break; if (best < 0) throw FormulaError("#N/A"); return (best + 1).toDouble() }
                else -> { var best = -1; for (i in cells.indices) if (valLe(cells[i], lookup)) best = i else break; if (best < 0) throw FormulaError("#N/A"); return (best + 1).toDouble() }
            }
        }

        /**
         * INDEX on a range: the cell at [rowNum] and [colNum] (one number into a single row or column is its position
         * along it); 0 for either is the whole column or row, as a list; outside the range #REF!.
         */
        private fun indexAt(ref: RangeRef, rowNum: Int, colNum: Int?): Any = overRange(ref) {
            val rows = ref.r2 - ref.r1 + 1; val cols = ref.c2 - ref.c1 + 1
            val (r, c) = when {
                colNum == null && rows == 1 -> 1 to rowNum
                colNum == null -> rowNum to 1
                else -> rowNum to colNum
            }
            if (r < 0 || c < 0 || r > rows || c > cols) throw FormulaError("#REF!")
            when {
                r == 0 && c == 0 -> rangeCells(ref)
                r == 0 -> (ref.r1..ref.r2).map { cellAt(ref.sheet, it, ref.c1 + c - 1) }
                c == 0 -> (ref.c1..ref.c2).map { cellAt(ref.sheet, ref.r1 + r - 1, it) }
                else -> cellAt(ref.sheet, ref.r1 + r - 1, ref.c1 + c - 1)
            }
        }

        /** "B3" / "$B$3" → (row=2, col=1) zero-based. Absolute markers ($) are ignored. */
        /** A name that is neither a cell nor a defined name is #NAME?, as Excel's. */
        private fun refToRC(ref: String): Pair<Int, Int> = A1.parse(ref) ?: throw FormulaError("#NAME?")

        private fun parseNumber(): Double {
            val start = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
            // An exponent: 1E308, 2.5e-3.
            if (i < src.length && (src[i] == 'E' || src[i] == 'e')) {
                var j = i + 1
                if (j < src.length && (src[j] == '+' || src[j] == '-')) j++
                if (j < src.length && src[j].isDigit()) { i = j; while (i < src.length && src[i].isDigit()) i++ }
            }
            return src.substring(start, i).toDoubleOrNull() ?: error("bad number")
        }

        private fun parseIdent(): String {
            val start = i
            // Names and dotted functions (Tax_Rate, STDEV.S) read whole; a dot never starts one.
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '$' || (i > start && (src[i] == '_' || src[i] == '.')))) i++
            return src.substring(start, i)
        }

        private fun peek(): Char = if (i < src.length) src[i] else '\u0000'
        private fun expect(c: Char) { if (peek() != c) error("expected $c"); i++ }
        private fun skipWs() { while (i < src.length && src[i] == ' ') i++ }
    }
}
