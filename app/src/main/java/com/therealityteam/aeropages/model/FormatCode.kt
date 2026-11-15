package com.therealityteam.aeropages.model

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor

/**
 * Excel number format codes, applied the way Excel applies them: `#,##0.00;[Red]-#,##0.00`,
 * `0.0,,"M"`, `yyyy"年"m"月"d"日"`, `[h]:mm:ss`, `# ?/?`, `@` and the rest. A code has up to
 * four sections (positive; negative; zero; text) or conditions in brackets (`[>=1000]`), and
 * each section may name a colour (`[Red]`, `[Color10]`), a currency or locale (`[$€-407]`,
 * `[$-804]`) and Chinese numerals (`[DBNum1]` 一千二百, `[DBNum2]` 壹仟贰佰). Numbers use digit
 * placeholders (0 # ?), grouping and scaling commas, percent, exponents and fractions; dates
 * and times use y m d h s, AM/PM, 上午/下午, elapsed [h] [m] [s] and fractions of a second.
 */
object FormatCode {

    /** What a value shows in a code: its text and, when the section names one, a colour (0xAARRGGBB). */
    data class Shown(val text: String, val color: Long? = null)

    /**
     * Whose month and weekday names a date shows (a `[$-804]` tag picks one; otherwise the app's language): the app's
     * languages, each with its locale and Windows locale id ([lcid], written as `[$-407]`). English, Chinese and Japanese
     * names are Excel's own; the others come from the platform's locale data, abbreviations without their final dot as
     * Excel shows them (janv, févr).
     */
    enum class Lang(val locale: Locale, val lcid: Int) {
        EN(Locale.forLanguageTag("en-US"), 0x0409), ZH_CN(Locale.forLanguageTag("zh-CN"), 0x0804), ZH_TW(Locale.forLanguageTag("zh-TW"), 0x0404),
        JA(Locale.forLanguageTag("ja-JP"), 0x0411), KO(Locale.forLanguageTag("ko-KR"), 0x0412), DE(Locale.forLanguageTag("de-DE"), 0x0407),
        FR(Locale.forLanguageTag("fr-FR"), 0x040C), ES(Locale.forLanguageTag("es-ES"), 0x0C0A), IT(Locale.forLanguageTag("it-IT"), 0x0410),
        PT(Locale.forLanguageTag("pt-BR"), 0x0416), RU(Locale.forLanguageTag("ru-RU"), 0x0419), NL(Locale.forLanguageTag("nl-NL"), 0x0413),
        PL(Locale.forLanguageTag("pl-PL"), 0x0415), TR(Locale.forLanguageTag("tr-TR"), 0x041F), VI(Locale.forLanguageTag("vi-VN"), 0x042A),
        TH(Locale.forLanguageTag("th-TH"), 0x041E), ID(Locale.forLanguageTag("id-ID"), 0x0421), HI(Locale.forLanguageTag("hi-IN"), 0x0439);

        /** The language part of [locale] as ODF and BCP 47 write it (`id`, never the old `in`). */
        val language: String get() = locale.language.let { if (it == "in") "id" else it }

        companion object {
            fun of(locale: Locale): Lang {
                val l = locale.language.let { if (it == "in") "id" else it }
                return when {
                    l == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) -> ZH_TW
                    l == "zh" -> ZH_CN
                    else -> entries.firstOrNull { it.language == l } ?: EN
                }
            }

            fun current(): Lang = of(Locale.getDefault())

            /** A Windows locale id (the hex after the dash in `[$-804]`) → its names; null for other languages. */
            fun ofLcid(lcid: Int): Lang? = when (lcid and 0xFFFF) {
                0x0804, 0x1004 -> ZH_CN
                0x0404, 0x0C04, 0x1404 -> ZH_TW
                else -> entries.firstOrNull { it != ZH_CN && it != ZH_TW && (it.lcid and 0x3FF) == (lcid and 0x3FF) }
            }

            private val symbols = ConcurrentHashMap<Lang, java.text.DateFormatSymbols>()
            /** The platform's month, weekday and AM/PM names of [lang]. */
            internal fun symbols(lang: Lang): java.text.DateFormatSymbols = symbols.getOrPut(lang) { java.text.DateFormatSymbols.getInstance(lang.locale) }
        }
    }

    // --- tokens ------------------------------------------------------------------------

    private sealed class Tok
    /** Literal text; a `_x` space as wide as [blank], or (empty) the `*x` repeat-to-fill [fill] character — kept for ODF. */
    private class Lit(val s: String, val blank: Char? = null, val fill: Char? = null) : Tok()
    private class Ph(val ch: Char) : Tok()
    private object Dot : Tok()
    private object Comma : Tok()
    private object Pct : Tok()
    private class Exp(val sign: Char, val e: Char) : Tok()
    private object Slash : Tok()
    private object At : Tok()
    private object Gen : Tok()
    /** y · M (month) · m (minute) · d · h · s · a (CJK weekday) · g (Japanese era) · e (era year), with its letter count. */
    private class DT(val kind: Char, val n: Int) : Tok()
    private class Elapsed(val kind: Char, val n: Int) : Tok()
    private class AmPm(val am: String, val pm: String, val cjk: Boolean) : Tok()
    private class SubSec(val n: Int) : Tok()
    // A number section's placeholders once their roles are known.
    private class IntPh(val ch: Char) : Tok()
    private class FracPh(val ch: Char) : Tok()
    private class ExpPh(val ch: Char) : Tok()
    private class NumPh(val ch: Char) : Tok()
    private class DenPh(val ch: Char) : Tok()
    private class DenFix(val d: Long) : Tok()
    private object Point : Tok()
    private object Bar : Tok()

    private enum class Kind { NUMBER, SCIENTIFIC, FRACTION, GENERAL, DATE, TEXT, LITERAL }

    private class Cond(val op: String, val limit: Double) {
        fun test(v: Double) = when (op) {
            "<" -> v < limit; "<=" -> v <= limit; ">" -> v > limit; ">=" -> v >= limit; "=" -> v == limit; else -> v != limit
        }
        /** `[<0]` / `[<=0]`: the section shows negatives without their sign. */
        val negative get() = limit == 0.0 && (op == "<" || op == "<=")
        /** `[>=0]` / `[>0]`: a plain section after it is the one for negatives. */
        val positive get() = limit == 0.0 && (op == ">" || op == ">=")
    }

    private class Section(
        val toks: List<Tok>, val kind: Kind,
        val color: Long?, val cond: Cond?, val lang: Lang?, val dbNum: Int,
        /** `[$-F800]` / `[$-F400]`: the system's long date / time, which is the CJK pattern outside English. */
        val cjkToks: List<Tok>? = null,
        val grouping: Boolean = false, val scale: Int = 0, val percent: Int = 0,
    )

    private class Parsed(val numeric: List<Section>, val text: Section?)

    /** Bracket contents and other per-section settings gathered while reading it. */
    private class Raw {
        var color: Long? = null
        var cond: Cond? = null
        var lang: Lang? = null
        var dbNum = 0
        var system: Char? = null
    }

    // --- reading a code ------------------------------------------------------------------

    private val cache = ConcurrentHashMap<String, Any>()
    private val INVALID = Any()

    private fun parse(code: String): Parsed? {
        cache[code]?.let { return it as? Parsed }
        val p = read(code)
        if (cache.size > 512) cache.clear()
        cache[code] = p ?: INVALID
        return p
    }

    /** Whether [code] is a number format this engine (and so Excel) can use. */
    fun isValid(code: String): Boolean = code.isNotBlank() && parse(code) != null

    private fun read(code: String): Parsed? {
        val parts = split(code) ?: return null
        val sections = parts.map { section(it) ?: return null }
        // A last section holding @ (and no number) is for text: the 4th always, or `yyyy"年"m"月"d"日";@`.
        val textSection = when {
            sections.size == 4 -> sections[3]
            sections.size >= 2 && sections.last().kind == Kind.TEXT -> sections.last()
            sections.size == 1 && sections[0].kind == Kind.TEXT -> sections[0]
            else -> null
        }
        val numeric = when {
            sections.size == 4 -> sections.take(3)
            sections.size >= 2 && sections.last().kind == Kind.TEXT -> sections.dropLast(1)
            else -> sections
        }
        return Parsed(numeric, textSection)
    }

    /** The code's sections, split at semicolons outside quotes, escapes and brackets; null when unbalanced. */
    private fun split(code: String): List<String>? {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < code.length) {
            val ch = code[i]
            when (ch) {
                '"' -> {
                    val end = code.indexOf('"', i + 1)
                    if (end < 0) return null
                    cur.append(code, i, end + 1); i = end + 1
                }
                '\\', '_', '*' -> { cur.append(code, i, minOf(i + 2, code.length)); i += 2 }
                '[' -> {
                    val end = code.indexOf(']', i + 1)
                    if (end < 0) return null
                    cur.append(code, i, end + 1); i = end + 1
                }
                ';' -> { out.add(cur.toString()); cur.setLength(0); i++ }
                else -> { cur.append(ch); i++ }
            }
        }
        out.add(cur.toString())
        return if (out.size > 4) null else out
    }

    private fun section(s: String): Section? {
        val raw = Raw()
        val toks = tokenize(s, raw) ?: return null
        val cjk = if (raw.system != null) tokenize(if (raw.system == 'D') "yyyy\"年\"m\"月\"d\"日\"" else "h:mm:ss", Raw()) else null
        return build(toks, raw, cjk)
    }

    private fun tokenize(s: String, raw: Raw): List<Tok>? {
        val toks = ArrayList<Tok>()
        var i = 0
        fun run(ch: Char): Int { var j = i; while (j < s.length && s[j].equals(ch, ignoreCase = true)) j++; return j - i }
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '"' -> { val end = s.indexOf('"', i + 1); toks.add(Lit(s.substring(i + 1, end))); i = end + 1 }
                ch == '\\' -> { if (i + 1 < s.length) toks.add(Lit(s[i + 1].toString())); i += 2 }
                ch == '_' -> { toks.add(Lit(" ", blank = s.getOrNull(i + 1))); i += 2 }
                // Repeat-to-fill: the grid has no width to fill here; the character is kept for ODF.
                ch == '*' -> { s.getOrNull(i + 1)?.let { toks.add(Lit("", fill = it)) }; i += 2 }
                ch == '[' -> { val end = s.indexOf(']', i + 1); if (!bracket(s.substring(i + 1, end), raw, toks)) return null; i = end + 1 }
                s.regionMatches(i, "General", 0, 7, ignoreCase = true) -> { toks.add(Gen); i += 7 }
                s.regionMatches(i, "AM/PM", 0, 5, ignoreCase = true) -> { toks.add(AmPm(s.substring(i, i + 2), s.substring(i + 3, i + 5), false)); i += 5 }
                s.regionMatches(i, "A/P", 0, 3, ignoreCase = true) -> { toks.add(AmPm(s.substring(i, i + 1), s.substring(i + 2, i + 3), false)); i += 3 }
                s.startsWith("上午/下午", i) -> { toks.add(AmPm("上午", "下午", true)); i += 5 }
                (ch == 'E' || ch == 'e') && i + 1 < s.length && (s[i + 1] == '+' || s[i + 1] == '-') -> { toks.add(Exp(s[i + 1], ch)); i += 2 }
                ch in "yYmMdDhHsS" -> {
                    val n = run(ch)
                    val kind = ch.lowercaseChar().let { if (it == 'm') 'M' else it }
                    toks.add(DT(kind, n)); i += n
                }
                // The Japanese calendar: g / gg / ggg the era (H, 平, 平成), e / ee its year — Gregorian years elsewhere.
                ch == 'e' -> { val n = run('e'); toks.add(DT('e', n)); i += n }
                ch == 'g' || ch == 'G' -> { val n = run('g'); toks.add(DT('g', n)); i += n }
                ch == 'a' || ch == 'A' -> {
                    val n = run('a')
                    if (n >= 3) toks.add(DT('a', n)) else toks.add(Lit(s.substring(i, i + n)))
                    i += n
                }
                ch == '0' || ch == '#' || ch == '?' -> { toks.add(Ph(ch)); i++ }
                ch == '.' -> { toks.add(Dot); i++ }
                ch == ',' -> { toks.add(Comma); i++ }
                ch == '%' -> { toks.add(Pct); i++ }
                ch == '/' -> { toks.add(Slash); i++ }
                ch == '@' -> { toks.add(At); i++ }
                else -> { toks.add(Lit(ch.toString())); i++ }
            }
        }
        return toks
    }

    private val CONDITION = Regex("""^(<=|>=|<>|<|>|=)\s*(-?\d*\.?\d+(?:[eE][+-]?\d+)?)$""")
    private val ELAPSED = Regex("h+|m+|s+", RegexOption.IGNORE_CASE)

    /** One `[...]`: a colour, a condition, a currency / locale tag, Chinese numerals or elapsed time. */
    private fun bracket(inner: String, raw: Raw, toks: MutableList<Tok>): Boolean {
        val t = inner.trim()
        val lower = t.lowercase(Locale.ROOT)
        COLORS[lower]?.let { raw.color = it; return true }
        if (lower.startsWith("color")) {
            val n = lower.removePrefix("color").toIntOrNull() ?: return false
            raw.color = PALETTE.getOrNull(n - 1) ?: return false
            return true
        }
        CONDITION.find(t)?.let { m -> raw.cond = Cond(m.groupValues[1], m.groupValues[2].toDouble()); return true }
        if (t.startsWith("$")) {
            val body = t.substring(1)
            when {
                "x-sysdate" in lower -> raw.system = 'D'
                "x-systime" in lower -> raw.system = 'T'
                else -> {
                    val dash = body.lastIndexOf('-')
                    val symbol = if (dash >= 0) body.substring(0, dash) else body
                    if (symbol.isNotEmpty()) toks.add(Lit(symbol))
                    val lcid = if (dash >= 0) body.substring(dash + 1).toIntOrNull(16) else null
                    when (lcid?.and(0xFFFF)) {
                        null -> {}
                        0xF800 -> raw.system = 'D'
                        0xF400 -> raw.system = 'T'
                        else -> Lang.ofLcid(lcid)?.let { raw.lang = it }
                    }
                }
            }
            return true
        }
        if (lower.startsWith("dbnum")) { raw.dbNum = lower.removePrefix("dbnum").toIntOrNull()?.coerceIn(1, 3) ?: 1; return true }
        if (ELAPSED.matches(t)) { toks.add(Elapsed(lower[0], lower.length)); return true }
        return true   // other tags ([NatNum1], [~buddhist] …) change nothing here
    }

    private fun literal(t: Tok): Tok = when (t) {
        is Lit -> t
        is Ph -> Lit(t.ch.toString())
        Dot -> Lit(".")
        Comma -> Lit(",")
        Pct -> Lit("%")
        is Exp -> Lit("${t.e}${t.sign}")
        Slash -> Lit("/")
        At -> Lit("")
        Gen -> Lit("")
        else -> t
    }

    private fun build(toks: List<Tok>, raw: Raw, cjk: List<Tok>?): Section {
        fun make(list: List<Tok>, kind: Kind, cjkList: List<Tok>? = null) =
            Section(list, kind, raw.color, raw.cond, raw.lang, raw.dbNum, cjkList)
        if (toks.any { it is DT || it is Elapsed || it is AmPm }) return make(dateTokens(toks), Kind.DATE, cjk?.let { dateTokens(it) })
        if (toks.any { it === Gen }) return make(toks.map { if (it === Gen) it else literal(it) }, Kind.GENERAL)
        if (toks.any { it is Ph }) return numberSection(toks, raw)
        if (toks.any { it === At }) return make(toks.map { if (it === At) it else literal(it) }, Kind.TEXT)
        return make(toks.map { literal(it) }, Kind.LITERAL)
    }

    /** m / mm right after hours or right before seconds are minutes; `.0` after seconds is a fraction of one. */
    private fun dateTokens(toks: List<Tok>): List<Tok> {
        val list = toks.toMutableList()
        val timeIdx = list.indices.filter { list[it] is DT || list[it] is Elapsed }
        for ((k, i) in timeIdx.withIndex()) {
            val t = list[i] as? DT ?: continue
            if (t.kind != 'M' || t.n > 2) continue
            val prev = timeIdx.getOrNull(k - 1)?.let { list[it] }
            val next = timeIdx.getOrNull(k + 1)?.let { list[it] }
            val afterHour = (prev is DT && prev.kind == 'h') || (prev is Elapsed && prev.kind == 'h')
            val beforeSecond = (next is DT && next.kind == 's') || (next is Elapsed && next.kind == 's')
            if (afterHour || beforeSecond) list[i] = DT('m', t.n)
        }
        val out = ArrayList<Tok>()
        var i = 0
        while (i < list.size) {
            val t = list[i]
            val last = out.lastOrNull()
            val afterSeconds = (last is DT && last.kind == 's') || (last is Elapsed && last.kind == 's')
            if (t === Dot && afterSeconds && list.getOrNull(i + 1).let { it is Ph && it.ch == '0' }) {
                var n = 0
                var j = i + 1
                while (j < list.size && list[j].let { it is Ph && it.ch == '0' }) { n++; j++ }
                out.add(SubSec(n.coerceAtMost(3))); i = j; continue
            }
            out.add(if (t is DT || t is Elapsed || t is AmPm) t else literal(t))
            i++
        }
        return out
    }

    /** A number section: which placeholders are the integer, the decimals, the exponent or a fraction's parts. */
    private fun numberSection(toks: List<Tok>, raw: Raw): Section {
        val phIdx = toks.indices.filter { toks[it] is Ph }
        // A fraction: a slash with placeholders before it and placeholders or digits after it.
        val slash = toks.indices.firstOrNull { i ->
            toks[i] === Slash && phIdx.any { it < i } && toks.getOrNull(i + 1).let { it is Ph || (it is Lit && it.s.length == 1 && it.s[0] in '1'..'9') }
        }
        if (slash != null) return fractionSection(toks, slash, raw)

        val exp = toks.indexOfFirst { it is Exp }.takeIf { it >= 0 }
        val end = exp ?: toks.size
        val dot = (0 until end).firstOrNull { toks[it] === Dot }
        val intEnd = dot ?: end
        val lastIntPh = (0 until intEnd).lastOrNull { toks[it] is Ph }
        val lastFracPh = if (dot != null) (dot + 1 until end).lastOrNull { toks[it] is Ph } else null
        var grouping = false
        var scale = 0
        var percent = 0
        val out = ArrayList<Tok>()
        for ((i, t) in toks.withIndex()) {
            when {
                t is Ph && i < intEnd -> out.add(IntPh(t.ch))
                t is Ph && i < end -> out.add(FracPh(t.ch))
                t is Ph -> out.add(ExpPh(t.ch))
                t === Dot && i == dot -> {
                    if (lastIntPh == null) out.add(IntPh('#'))   // ".00" still shows the whole part of 12.5
                    out.add(Point)
                }
                t === Comma -> {
                    val between = i < intEnd && lastIntPh != null && i < lastIntPh && phIdx.any { it < i }
                    // Commas right after the last digit (before the point, or ending the number) divide by 1000 each.
                    val trailing = (lastIntPh != null && i > lastIntPh && i < intEnd && (lastIntPh + 1 until i).all { toks[it] === Comma }) ||
                        (lastFracPh != null && i > lastFracPh && i < end && (lastFracPh + 1 until i).all { toks[it] === Comma })
                    when {
                        between -> grouping = true
                        trailing -> scale++
                        else -> out.add(Lit(","))
                    }
                }
                t === Pct -> { percent++; out.add(Lit("%")) }
                t is Exp -> out.add(t)
                else -> out.add(literal(t))
            }
        }
        return Section(out, if (exp != null) Kind.SCIENTIFIC else Kind.NUMBER, raw.color, raw.cond, raw.lang, raw.dbNum,
            grouping = grouping, scale = scale, percent = percent)
    }

    private fun fractionSection(toks: List<Tok>, slash: Int, raw: Raw): Section {
        // Placeholder groups before the slash: the last is the numerator, an earlier one the whole number.
        val before = (0 until slash).filter { toks[it] is Ph }
        val numStart = before.last().let { last -> var k = last; while (k - 1 >= 0 && toks[k - 1] is Ph) k--; k }
        val out = ArrayList<Tok>()
        var percent = 0
        for (i in 0 until slash) {
            val t = toks[i]
            out.add(when {
                t is Ph && i >= numStart -> NumPh(t.ch)
                t is Ph -> IntPh(t.ch)
                t === Pct -> { percent++; Lit("%") }
                else -> literal(t)
            })
        }
        out.add(Bar)
        var i = slash + 1
        val first = toks.getOrNull(i)
        if (first is Lit) {
            // A fixed denominator: `# ?/8`, `?/100`.
            val digits = StringBuilder()
            while (i < toks.size) {
                val t = toks[i]
                val d = when { t is Lit && t.s.length == 1 && t.s[0].isDigit() -> t.s; t is Ph && t.ch == '0' -> "0"; else -> null } ?: break
                digits.append(d); i++
            }
            out.add(DenFix(digits.toString().toLongOrNull()?.coerceAtMost(1_000_000L) ?: 1_000_000L))
        } else {
            while (i < toks.size && toks[i] is Ph) { out.add(DenPh((toks[i] as Ph).ch)); i++ }
        }
        while (i < toks.size) {
            val t = toks[i]
            out.add(if (t === Pct) { percent++; Lit("%") } else literal(t))
            i++
        }
        return Section(out, Kind.FRACTION, raw.color, raw.cond, raw.lang, raw.dbNum, percent = percent)
    }

    // --- applying a code -------------------------------------------------------------------

    // While a code is laid out the decimal point and the grouping separator are these marks; [format] turns them into
    // the reader's symbols at the end, so a literal . or , in the code (\. or quoted text, a date's dd.mm) stays as it is.
    private const val DEC = '\uE000'
    private const val GRP = '\uE001'

    private fun marked(general: String) = general.replace('.', DEC)

    /**
     * [v] shown in [code]; a code it cannot read shows General. The decimal point and grouping separators are the
     * reader's [symbols] (the standard . and , by default — what files and formulas use).
     */
    fun format(v: Double, code: String, lang: Lang = Lang.current(), symbols: NumberSymbols = NumberSymbols.CANONICAL): Shown {
        val shown = laidOut(v, code, lang)
        return Shown(shown.text.replace(DEC, symbols.decimal).replace(GRP, symbols.group), shown.color)
    }

    private fun laidOut(v: Double, code: String, lang: Lang): Shown {
        if (v.isNaN() || v.isInfinite()) return Shown("#NUM!")
        if (code.isBlank() || code.equals("General", ignoreCase = true)) return Shown(marked(general(v)))
        val p = parse(code) ?: return Shown(marked(general(v)))
        val (sec, dropSign) = pick(p, v) ?: return Shown(marked(general(v)))
        val x = if (dropSign) abs(v) else v
        val tw = (sec.lang ?: lang) == Lang.ZH_TW
        val text = when (sec.kind) {
            Kind.DATE -> date(x, sec, lang) ?: "#####"
            Kind.TEXT -> marked(general(x))
            else -> {
                val (body, zero) = when (sec.kind) {
                    Kind.NUMBER -> decimal(abs(x), sec, tw)
                    Kind.SCIENTIFIC -> scientific(abs(x), sec, tw)
                    Kind.FRACTION -> fraction(abs(x), sec, tw)
                    Kind.GENERAL -> generalSection(abs(x), sec, tw)
                    else -> sec.toks.joinToString("") { (it as Lit).s }.let { it to it.isEmpty() }
                }
                if (x < 0 && !zero) "-$body" else body
            }
        }
        return Shown(text, sec.color)
    }

    /** Text [s] shown in [code]: its text section with @ standing for the text; without one, the text itself. */
    fun formatText(s: String, code: String): Shown {
        val sec = parse(code)?.text ?: return Shown(s)
        val sb = StringBuilder()
        for (t in sec.toks) when (t) {
            At -> sb.append(s)
            is Lit -> sb.append(t.s)
            else -> {}
        }
        return Shown(sb.toString(), sec.color)
    }

    /** The section [v] falls in, and whether it shows the value without its minus sign. */
    private fun pick(p: Parsed, v: Double): Pair<Section, Boolean>? {
        val s = p.numeric
        val c0 = s[0].cond
        val c1 = s.getOrNull(1)?.cond
        if (c0 == null && c1 == null) return when {
            s.size == 1 -> s[0] to false
            s.size == 2 -> if (v >= 0) s[0] to false else s[1] to true
            else -> if (v > 0) s[0] to false else if (v < 0) s[1] to true else s[2] to false
        }
        for (k in 0 until minOf(2, s.size)) {
            val c = s[k].cond
            if (c == null) return s[k] to (k == 1 && c0?.positive == true && v < 0)
            if (c.test(v)) return s[k] to c.negative
        }
        return s.getOrNull(2)?.let { it to false }
    }

    /** Excel's General: whole numbers plainly, up to ten significant digits, very large or small ones in E notation. */
    fun general(v: Double): String {
        if (v == 0.0) return "0"
        if (v.isNaN() || v.isInfinite()) return "#NUM!"
        val a = abs(v)
        if (a >= 1e11 || a < 1e-9) {
            val bd = BigDecimal(v).round(MathContext(6))
            val e = bd.precision() - bd.scale() - 1
            val mant = bd.movePointLeft(e).stripTrailingZeros().toPlainString()
            return mant + "E" + (if (e < 0) "-" else "+") + abs(e).toString().padStart(2, '0')
        }
        if (a == floor(a)) return v.toLong().toString()
        return BigDecimal(v).round(MathContext(10)).stripTrailingZeros().toPlainString()
    }

    /** [x] as Excel holds it: fifteen significant digits, so 2.675 rounds to 2.68 as it does there. */
    private fun decimalOf(x: Double): BigDecimal = if (x == 0.0) BigDecimal.ZERO else BigDecimal(x).round(MathContext(15))

    private fun decimal(x: Double, sec: Section, tw: Boolean): Pair<String, Boolean> {
        val nFrac = sec.toks.count { it is FracPh }
        val scaled = decimalOf(x).movePointRight(2 * sec.percent).movePointLeft(3 * sec.scale).setScale(nFrac, RoundingMode.HALF_UP)
        val zero = scaled.signum() == 0
        val plain = scaled.toPlainString()
        val whole = plain.substringBefore('.').let { if (it == "0") "" else it }
        val frac = if (nFrac > 0) plain.substringAfter('.') else ""
        return lay(sec, whole, frac, null, tw) to zero
    }

    private fun scientific(x: Double, sec: Section, tw: Boolean): Pair<String, Boolean> {
        val ints = sec.toks.filterIsInstance<IntPh>()
        val nInt = ints.size.coerceAtLeast(1)
        val nFrac = sec.toks.count { it is FracPh }
        val engineering = nInt > 1 && ints.any { it.ch == '#' }
        var e = 0
        var mant = BigDecimal.ZERO.setScale(nFrac)
        if (x > 0) {
            val d = decimalOf(x)
            val mag = d.precision() - d.scale() - 1
            e = if (engineering) Math.floorDiv(mag, nInt) * nInt else mag - (nInt - 1)
            mant = d.movePointLeft(e).setScale(nFrac, RoundingMode.HALF_UP)
            if (mant >= BigDecimal.TEN.pow(nInt)) {   // 9.999 → 10.00: one digit too many
                e += if (engineering) nInt else 1
                mant = d.movePointLeft(e).setScale(nFrac, RoundingMode.HALF_UP)
            }
        }
        val plain = mant.toPlainString()
        val whole = plain.substringBefore('.').let { if (it == "0") "" else it }
        val frac = if (nFrac > 0) plain.substringAfter('.') else ""
        return lay(sec, whole, frac, e, tw) to (mant.signum() == 0)
    }

    private fun fraction(x: Double, sec: Section, tw: Boolean): Pair<String, Boolean> {
        val y = decimalOf(x).movePointRight(2 * sec.percent).toDouble()
        val hasWhole = sec.toks.any { it is IntPh }
        var whole = if (hasWhole) floor(y).toLong() else 0L
        val f = if (hasWhole) y - whole else y
        val fixed = sec.toks.firstNotNullOfOrNull { (it as? DenFix)?.d }
        var num: Long
        val den: Long
        if (fixed != null && fixed > 0) {
            den = fixed
            num = Math.round(f * den)
        } else {
            val digits = sec.toks.count { it is DenPh }.coerceIn(1, 4)
            var maxDen = 1L
            repeat(digits) { maxDen *= 10 }
            val (n, d) = closest(f, maxDen - 1)
            num = n; den = d
        }
        if (hasWhole && num == den) { whole++; num = 0 }
        val zero = whole == 0L && num == 0L
        val showFraction = !hasWhole || num != 0L
        val ints = if (whole == 0L) (if (zero && hasWhole) "0" else "") else whole.toString()
        val numDigits = if (num == 0L && hasWhole) "" else num.toString()
        val intPieces = pieces(sec.toks.filterIsInstance<IntPh>().map { it.ch }, ints, false)
        val numPieces = pieces(sec.toks.filterIsInstance<NumPh>().map { it.ch }, numDigits, false)
        val denPh = sec.toks.filterIsInstance<DenPh>().map { it.ch }
        val denDigits = den.toString()
        var ii = 0
        var ni = 0
        var di = 0
        val sb = StringBuilder()
        for (t in sec.toks) when (t) {
            is Lit -> sb.append(t.s)
            is IntPh -> sb.append(digits(intPieces[ii++], sec.dbNum, tw))
            is NumPh -> sb.append(if (showFraction) digits(numPieces[ni++], sec.dbNum, tw) else " ")
            Bar -> sb.append(if (showFraction) "/" else " ")
            is DenFix -> sb.append(if (showFraction) digits(denDigits, sec.dbNum, tw) else " ".repeat(denDigits.length))
            is DenPh -> {
                val j = di++
                val s = when {
                    !showFraction -> " "
                    j < denDigits.length -> if (j == denPh.lastIndex) denDigits.drop(j) else denDigits[j].toString()
                    else -> when (t.ch) { '0' -> "0"; '?' -> " "; else -> "" }
                }
                sb.append(digits(s, sec.dbNum, tw))
            }
            else -> {}
        }
        return sb.toString() to zero
    }

    /** The fraction n/d closest to [f] with d at most [maxDen] (the smallest such d on a tie). */
    private fun closest(f: Double, maxDen: Long): Pair<Long, Long> {
        var bestN = Math.round(f)
        var bestD = 1L
        var bestErr = abs(f - bestN)
        var d = 2L
        while (d <= maxDen && bestErr > 1e-12) {
            val n = Math.round(f * d)
            val err = abs(f - n.toDouble() / d)
            if (err < bestErr - 1e-15) { bestN = n; bestD = d; bestErr = err }
            d++
        }
        return bestN to bestD
    }

    private fun generalSection(x: Double, sec: Section, tw: Boolean): Pair<String, Boolean> {
        val g = general(x)
        val shown = if (sec.dbNum == 0) marked(g) else chineseNumber(g, sec.dbNum, tw)
        val sb = StringBuilder()
        for (t in sec.toks) when (t) {
            Gen -> sb.append(shown)
            is Lit -> sb.append(t.s)
            else -> {}
        }
        return sb.toString() to (g == "0")
    }

    /**
     * What each integer placeholder shows for [digits] (the number's whole part, "" for zero): digits
     * fill them from the right, the leftmost takes any extra; empty ones show 0, a space (?) or
     * nothing (#). With [grouping] a comma follows every third digit from the right.
     */
    private fun pieces(ph: List<Char>, digits: String, grouping: Boolean): List<String> {
        val n = ph.size
        if (n == 0) return emptyList()
        val len = digits.length
        val out = MutableList(n) { j ->
            val fromRight = n - 1 - j
            when {
                j == 0 && len > n -> digits.substring(0, len - n + 1)
                fromRight < len -> digits[len - 1 - fromRight].toString()
                else -> when (ph[j]) { '0' -> "0"; '?' -> " "; else -> "" }
            }
        }
        if (grouping) {
            var count = 0
            for (j in n - 1 downTo 0) {
                val sb = StringBuilder()
                for (ch in out[j].reversed()) {
                    if (ch.isDigit()) {
                        if (count > 0 && count % 3 == 0) sb.append(GRP)
                        count++
                    }
                    sb.append(ch)
                }
                out[j] = sb.reverse().toString()
            }
        }
        return out
    }

    /** A decimal or scientific section with its whole digits, decimals and (for E notation) exponent filled in. */
    private fun lay(sec: Section, whole: String, frac: String, exponent: Int?, tw: Boolean): String {
        val intPieces = pieces(sec.toks.filterIsInstance<IntPh>().map { it.ch }, whole, sec.grouping)
        // [DBNum1] / [DBNum2] write the whole part in words with units (四万六千二百九十五), as for
        // General — Excel's TEXT(n, "[DBNum1]0"); grouped or split digits stay digit by digit.
        val intAt = sec.toks.indices.filter { sec.toks[it] is IntPh }
        val words = if (sec.dbNum in 1..2 && exponent == null && !sec.grouping && intAt.isNotEmpty() &&
            (intAt.first()..intAt.last()).all { sec.toks[it] is IntPh }) {
            when {
                whole.isNotEmpty() -> chineseNumber(whole, sec.dbNum, tw)
                sec.toks.any { it is IntPh && it.ch == '0' } -> chineseNumber("0", sec.dbNum, tw)
                else -> ""
            }
        } else null
        val lastSig = frac.indexOfLast { it != '0' }
        val expPh = sec.toks.filterIsInstance<ExpPh>().map { it.ch }
        val expPieces = if (exponent != null) pieces(expPh, abs(exponent).toString().let { if (it == "0") "" else it }, false) else emptyList()
        var ii = 0
        var fi = 0
        var ei = 0
        val sb = StringBuilder()
        for (t in sec.toks) when (t) {
            is Lit -> sb.append(t.s)
            is IntPh -> { if (words == null) sb.append(digits(intPieces[ii], sec.dbNum, tw)) else if (ii == 0) sb.append(words); ii++ }
            Point -> sb.append(DEC)
            is FracPh -> {
                val j = fi++
                val d = frac[j]
                sb.append(when {
                    j <= lastSig || t.ch == '0' -> digits(d.toString(), sec.dbNum, tw)
                    t.ch == '?' -> " "
                    else -> ""
                })
            }
            is Exp -> sb.append(t.e.uppercaseChar()).append(if ((exponent ?: 0) < 0) "-" else if (t.sign == '+') "+" else "")
            is ExpPh -> sb.append(digits(expPieces[ei++], sec.dbNum, tw))
            else -> {}
        }
        return sb.toString()
    }

    // --- dates and times -------------------------------------------------------------------

    /** Day 0 of Excel's serial dates (so 1900-03-01 onwards match Excel). */
    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)

    private fun date(v: Double, sec: Section, lang: Lang): String? {
        if (v < 0 || v >= 2_958_466.0) return null
        val names = sec.lang ?: lang
        val toks = if (sec.cjkToks != null && names != Lang.EN) sec.cjkToks else sec.toks
        val fs = toks.maxOfOrNull { (it as? SubSec)?.n ?: 0 } ?: 0
        var unit = 1L
        repeat(fs) { unit *= 10 }
        val perDay = 86_400L * unit
        val total = Math.round(v * perDay)
        val days = total / perDay
        val rem = total % perDay
        val secOfDay = rem / unit
        val sub = rem % unit
        val date = EPOCH.plusDays(days)
        val h = (secOfDay / 3600).toInt()
        val mi = ((secOfDay / 60) % 60).toInt()
        val s = (secOfDay % 60).toInt()
        val twelve = toks.any { it is AmPm }
        val db = sec.dbNum
        val tw = names == Lang.ZH_TW
        fun n(value: Long, pad: Int): String = if (db == 0) value.toString().padStart(pad, '0') else chineseNumber(value.toString(), db, tw)
        val sb = StringBuilder()
        for (t in toks) when (t) {
            is Lit -> sb.append(t.s)
            is DT -> when (t.kind) {
                'y' -> {
                    val y = if (t.n <= 2) (date.year % 100).toString().padStart(2, '0') else date.year.toString()
                    sb.append(if (db == 0) y else digits(y, db, tw))
                }
                'M' -> sb.append(when (t.n) {
                    1, 2 -> n(date.monthValue.toLong(), t.n)
                    3 -> monthName(names, date.monthValue, false)
                    4 -> monthName(names, date.monthValue, true)
                    else -> monthName(names, date.monthValue, true).take(1)
                })
                'm' -> sb.append(n(mi.toLong(), t.n.coerceAtMost(2)))
                'd' -> sb.append(when (t.n) {
                    1, 2 -> n(date.dayOfMonth.toLong(), t.n)
                    3 -> dayName(names, date.dayOfWeek.value % 7, false)
                    else -> dayName(names, date.dayOfWeek.value % 7, true)
                })
                'h' -> sb.append(n((if (twelve) (h % 12).let { if (it == 0) 12 else it } else h).toLong(), t.n.coerceAtMost(2)))
                's' -> sb.append(n(s.toLong(), t.n.coerceAtMost(2)))
                'a' -> sb.append(cjkDay(names, date.dayOfWeek.value % 7, t.n >= 4))
                'g' -> if (names == Lang.JA) eraOf(date)?.let { e -> sb.append(when (t.n) { 1 -> e.letter; 2 -> e.name.take(1); else -> e.name }) }
                'e' -> sb.append(if (names == Lang.JA) eraOf(date)?.let { e -> (date.year - e.start.year + 1).toString().padStart(t.n.coerceAtMost(2), '0') } ?: date.year.toString()
                    else date.year.toString())
            }
            is Elapsed -> {
                val value = when (t.kind) {
                    'h' -> days * 24 + h
                    'm' -> days * 1440 + h * 60 + mi
                    else -> days * 86_400 + secOfDay
                }
                sb.append(n(value, t.n))
            }
            is SubSec -> sb.append(DEC).append(sub.toString().padStart(fs, '0').take(t.n))
            // AM/PM in the language's own words, as Excel shows them (上午/下午, 午前/午後) — [$-409] keeps them English; A/P stays.
            is AmPm -> sb.append(when {
                t.cjk -> if (h < 12) t.am else t.pm
                t.am.length == 1 -> if (h < 12) t.am else t.pm
                names == Lang.ZH_CN || names == Lang.ZH_TW -> if (h < 12) "上午" else "下午"
                names == Lang.JA -> if (h < 12) "午前" else "午後"
                names == Lang.EN -> if (h < 12) t.am else t.pm
                // Another language's own designators (오전/오후, ก่อนเที่ยง/หลังเที่ยง); "AM"/"PM" where it has none of its own.
                else -> Lang.symbols(names).amPmStrings.let { if (h < 12) it[0] else it[1] }
            })
            else -> {}
        }
        return sb.toString()
    }

    /** A Japanese era: its letter, its name, its first day. */
    private class Era(val letter: String, val name: String, val start: LocalDate)
    private val ERAS = listOf(
        Era("R", "令和", LocalDate.of(2019, 5, 1)), Era("H", "平成", LocalDate.of(1989, 1, 8)), Era("S", "昭和", LocalDate.of(1926, 12, 25)),
        Era("T", "大正", LocalDate.of(1912, 7, 30)), Era("M", "明治", LocalDate.of(1868, 1, 1)),
    )
    private fun eraOf(d: LocalDate): Era? = ERAS.firstOrNull { !d.isBefore(it.start) }

    private val EN_MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val EN_DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val CN_MONTHS = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二")
    private val CN_DAYS = arrayOf("日", "一", "二", "三", "四", "五", "六")
    private val JA_DAYS = arrayOf("日", "月", "火", "水", "木", "金", "土")

    private fun monthName(lang: Lang, m: Int, long: Boolean): String = when (lang) {
        Lang.EN -> if (long) EN_MONTHS[m - 1] else EN_MONTHS[m - 1].take(3)
        Lang.ZH_CN, Lang.ZH_TW -> if (long) CN_MONTHS[m - 1] + "月" else "${m}月"
        Lang.JA -> "${m}月"
        else -> Lang.symbols(lang).let { if (long) it.months[m - 1] else it.shortMonths[m - 1].removeSuffix(".") }
    }

    /** [dow]: 0 = Sunday. */
    private fun dayName(lang: Lang, dow: Int, long: Boolean): String = when (lang) {
        Lang.EN -> if (long) EN_DAYS[dow] else EN_DAYS[dow].take(3)
        Lang.ZH_CN -> if (long) "星期" + CN_DAYS[dow] else "周" + CN_DAYS[dow]
        Lang.ZH_TW -> if (long) "星期" + CN_DAYS[dow] else "週" + CN_DAYS[dow]
        Lang.JA -> if (long) JA_DAYS[dow] + "曜日" else JA_DAYS[dow]
        // DateFormatSymbols' weekdays start at index 1 = Sunday.
        else -> Lang.symbols(lang).let { if (long) it.weekdays[dow + 1] else it.shortWeekdays[dow + 1].removeSuffix(".") }
    }

    /** `aaa` / `aaaa`: the weekday as Chinese and Japanese Excel write it (一 / 星期一, 月 / 月曜日). */
    private fun cjkDay(lang: Lang, dow: Int, long: Boolean): String =
        if (lang == Lang.JA) (if (long) JA_DAYS[dow] + "曜日" else JA_DAYS[dow])
        else (if (long) "星期" + CN_DAYS[dow] else CN_DAYS[dow])

    // --- Chinese numerals ([DBNum1] [DBNum2] [DBNum3]) ------------------------------------------

    private fun digitSet(db: Int, tw: Boolean): String = when (db) {
        1 -> "〇一二三四五六七八九"
        2 -> if (tw) "零壹貳參肆伍陸柒捌玖" else "零壹贰叁肆伍陆柒捌玖"
        else -> "０１２３４５６７８９"
    }

    /** Each digit of [s] as a Chinese (or full-width) digit; other characters unchanged. */
    private fun digits(s: String, db: Int, tw: Boolean): String {
        if (db == 0 || s.none { it.isDigit() }) return s
        val set = digitSet(db, tw)
        return buildString { for (ch in s) append(if (ch in '0'..'9') set[ch - '0'] else ch) }
    }

    /** A plain decimal number string in Chinese numerals: the whole part with units (一千二百), decimals digit by digit. */
    private fun chineseNumber(s: String, db: Int, tw: Boolean): String {
        if (db == 3 || 'E' in s) return digits(s, db, tw)
        val whole = s.substringBefore('.')
        val frac = if ('.' in s) s.substringAfter('.') else ""
        val n = whole.toLongOrNull() ?: return digits(s, db, tw)
        val head = withUnits(n, db, tw)
        return if (frac.isEmpty()) head else head + "." + digits(frac, db, tw)
    }

    private fun withUnits(n: Long, db: Int, tw: Boolean): String {
        val set = digitSet(db, tw)
        if (n == 0L) return set[0].toString()
        val small = if (db == 2) "拾佰仟" else "十百千"
        val big = if (tw) arrayOf("", "萬", "億", "兆") else arrayOf("", "万", "亿", "万亿")
        val zero = '零'
        val groups = ArrayList<Int>()
        var m = n
        while (m > 0) { groups.add((m % 10_000).toInt()); m /= 10_000 }
        val sb = StringBuilder()
        var pendingZero = false
        for (gi in groups.indices.reversed()) {
            val g = groups[gi]
            if (g == 0) { if (sb.isNotEmpty()) pendingZero = true; continue }
            if (sb.isNotEmpty() && (pendingZero || g < 1000)) sb.append(zero)
            pendingZero = false
            var started = false
            var gap = false
            for (pos in 3 downTo 0) {
                val d = (g / POW10[pos]) % 10
                if (d == 0) { if (started) gap = true; continue }
                if (gap) { sb.append(zero); gap = false }
                sb.append(set[d])
                if (pos > 0) sb.append(small[pos - 1])
                started = true
            }
            sb.append(big.getOrElse(gi) { "" })
        }
        val out = sb.toString()
        return if (db == 1 && out.startsWith("一十")) out.substring(1) else out   // 十二, not 一十二
    }

    private val POW10 = intArrayOf(1, 10, 100, 1000)

    // --- what a code is ------------------------------------------------------------------------

    /** Whether [code] formats numbers as dates or times (its first section does). */
    fun isDate(code: String): Boolean = parse(code)?.numeric?.firstOrNull()?.kind == Kind.DATE

    /** Whether [code] is a text format (`@`): what is typed stays as typed. */
    fun isText(code: String): Boolean = parse(code)?.let { it.numeric.size == 1 && it.numeric[0].kind == Kind.TEXT } == true

    /** The simple format a code is closest to — for what reads a cell's kind (dates for AutoFill, value types in files). */
    fun category(code: String): NumberFormat {
        val s = parse(code)?.numeric?.firstOrNull() ?: return NumberFormat.GENERAL
        return when (s.kind) {
            Kind.DATE -> {
                val date = s.toks.any { it is DT && it.kind in "yMda" }
                val time = s.toks.any { (it is DT && it.kind in "hms") || it is Elapsed || it is AmPm }
                if (date && time) NumberFormat.DATE_TIME else if (date) NumberFormat.DATE else NumberFormat.TIME
            }
            Kind.SCIENTIFIC -> NumberFormat.SCIENTIFIC
            Kind.NUMBER, Kind.FRACTION -> {
                val lits = s.toks.filterIsInstance<Lit>().joinToString("") { it.s }
                when {
                    s.percent > 0 -> NumberFormat.PERCENT
                    '¥' in lits || '￥' in lits || '元' in lits -> NumberFormat.CURRENCY_CNY
                    '$' in lits -> NumberFormat.CURRENCY_USD
                    s.grouping -> NumberFormat.THOUSANDS
                    else -> NumberFormat.NUMBER2
                }
            }
            else -> NumberFormat.GENERAL
        }
    }

    /**
     * [code] with one decimal place more ([delta] > 0) or fewer in each number section — Excel's
     * Increase / Decrease Decimal on a custom format (`0.0,,"M"` → `0.00,,"M"`). Dates, text and
     * General sections stay as they are; so does a code with nothing to change.
     */
    fun withDecimals(code: String, delta: Int): String {
        if (delta == 0) return code
        val parts = split(code) ?: return code
        return parts.joinToString(";") { part -> adjustSection(part, delta) }
    }

    private fun adjustSection(part: String, delta: Int): String {
        val sec = section(part) ?: return part
        if (sec.kind != Kind.NUMBER && sec.kind != Kind.SCIENTIFIC) return part
        // Positions of placeholders, the point and the exponent outside quotes, escapes and brackets.
        val ph = ArrayList<Int>()
        var dot = -1
        var exp = part.length
        var i = 0
        while (i < part.length) {
            val ch = part[i]
            when {
                ch == '"' -> { i = part.indexOf('"', i + 1).let { if (it < 0) part.length else it + 1 }; continue }
                ch == '\\' || ch == '_' || ch == '*' -> { i += 2; continue }
                ch == '[' -> { i = part.indexOf(']', i + 1).let { if (it < 0) part.length else it + 1 }; continue }
                (ch == 'E' || ch == 'e') && i + 1 < part.length && (part[i + 1] == '+' || part[i + 1] == '-') -> { if (exp == part.length) exp = i }
                ch == '0' || ch == '#' || ch == '?' -> if (i < exp) ph.add(i)
                ch == '.' -> if (dot < 0 && i < exp) dot = i
            }
            i++
        }
        if (ph.isEmpty()) return part
        val fracPh = if (dot >= 0) ph.filter { it > dot } else emptyList()
        return if (delta > 0) {
            when {
                dot >= 0 -> { val at = (fracPh.lastOrNull() ?: dot) + 1; part.substring(0, at) + "0".repeat(delta) + part.substring(at) }
                else -> { val at = ph.last() + 1; part.substring(0, at) + "." + "0".repeat(delta) + part.substring(at) }
            }
        } else {
            if (fracPh.isEmpty()) return if (dot >= 0) part.removeRange(dot, dot + 1) else part
            val drop = fracPh.takeLast(-delta)
            var out = part
            for (p in drop.reversed()) out = out.removeRange(p, p + 1)
            if (drop.size == fracPh.size) out = out.removeRange(dot, dot + 1)
            out
        }
    }

    // --- plain codes ---------------------------------------------------------------------------

    private val PLAIN_NUMBER = Regex("""^(#,##)?0(?:\.(0+))?(%)?$""")
    private val PLAIN_CURRENCY = Regex("""^"([¥$])"#,##0(?:\.(0+))?$""")

    /**
     * The plain format (and its decimal places) a code is — General, `0.00`, `#,##0`, `0%`,
     * `"¥"#,##0.00` — as the app keeps those without a code; null for any other code.
     */
    fun plain(code: String): Pair<NumberFormat, Int>? {
        if (code.equals("General", ignoreCase = true)) return NumberFormat.GENERAL to -1
        PLAIN_NUMBER.matchEntire(code)?.let { m ->
            val fmt = when {
                m.groupValues[3].isNotEmpty() -> if (m.groupValues[1].isEmpty()) NumberFormat.PERCENT else return null
                m.groupValues[1].isNotEmpty() -> NumberFormat.THOUSANDS
                else -> NumberFormat.NUMBER2
            }
            return fmt to m.groupValues[2].length
        }
        PLAIN_CURRENCY.matchEntire(code)?.let { m ->
            return (if (m.groupValues[1] == "¥") NumberFormat.CURRENCY_CNY else NumberFormat.CURRENCY_USD) to m.groupValues[2].length
        }
        return null
    }

    // --- OpenDocument number styles ---------------------------------------------------------------

    /**
     * [code] as OpenDocument number styles, as LibreOffice writes one: the style named [name] shows
     * its last section, the others are styles of their own (`[name]P0`, `P1` …) its `style:map`s
     * pick — for two sections the first when `value()>=0`, for three `>0` and `<0`, else by each
     * section's own condition. A text section is [name] itself, the number sections all maps (one
     * for every number, `>=0` `<0`, or `>0` `<0` `=0`). Each element is one style's XML, [name]'s
     * last; null when some part has no ODF form.
     */
    fun toOdf(code: String, name: String): List<String>? {
        val p = parse(code) ?: return null
        val secs = p.numeric
        if (secs.isEmpty() || (secs.size == 1 && secs[0].kind == Kind.TEXT)) {
            val t = p.text ?: secs.firstOrNull() ?: return null
            return odfText(t, name, "")?.let { listOf(it) }
        }
        // A text section is the style itself, its number sections picked by maps as LibreOffice does.
        val text = p.text?.takeIf { secs.none { it.cond != null } }
        val conditions: List<String> = when {
            text != null -> when (secs.size) {
                1 -> listOf("value()<=1.7976931348623157E+308")
                2 -> listOf("value()>=0", "value()<0")
                else -> listOf("value()>0", "value()<0", "value()=0")
            }
            secs.any { it.cond != null } -> secs.dropLast(1).map { s ->
                val c = s.cond ?: return null
                "value()" + (if (c.op == "<>") "!=" else c.op) + odfNumber(c.limit)
            }
            secs.size == 2 -> listOf("value()>=0")
            secs.size == 3 -> listOf("value()>0", "value()<0")
            else -> emptyList()
        }
        val out = ArrayList<String>()
        val maps = StringBuilder()
        (if (text != null) secs else secs.dropLast(1)).forEachIndexed { i, s ->
            val sub = "${name}P$i"
            out.add(odfSection(s, sub, "", volatile = true) ?: return null)
            maps.append("""<style:map style:condition="${xml(conditions[i])}" style:apply-style-name="$sub"/>""")
        }
        out.add((if (text != null) odfText(text, name, maps.toString()) else odfSection(secs.last(), name, maps.toString(), volatile = false)) ?: return null)
        return out
    }

    private fun odfNumber(v: Double): String = if (v == floor(v) && abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun xml(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun odfTexts(sb: StringBuilder, pending: StringBuilder) {
        if (pending.isNotEmpty()) { sb.append("<number:text>").append(xml(pending.toString())).append("</number:text>"); pending.setLength(0) }
    }

    /** A literal into an ODF number style: a `_x` space as LibreOffice's blank-width text, a `*x` fill as its fill character ([fill] where one may go). */
    private fun odfLit(t: Lit, body: StringBuilder, pending: StringBuilder, fill: Boolean = true) {
        when {
            t.fill != null -> if (fill) { odfTexts(body, pending); body.append("<number:fill-character>").append(xml(t.fill.toString())).append("</number:fill-character>") }
            t.blank != null -> { odfTexts(body, pending); body.append("""<number:text loext:blank-width-char="${xml(t.blank.toString())}"> </number:text>""") }
            else -> pending.append(t.s)
        }
    }

    /** A section's language (its tag's, or Chinese for 上午/下午 and 星期 names) and Chinese numerals, as ODF attributes. */
    private fun odfAttrs(s: Section): String {
        val cjk = s.toks.any { (it is AmPm && it.cjk) || (it is DT && it.kind == 'a') }
        val lang = (s.lang ?: if (cjk) Lang.ZH_CN else null)?.let { """ number:language="${it.language}" number:country="${it.locale.country}"""" } ?: ""
        // Chinese numerals as LibreOffice keeps Excel's: numbers with units (一千二百, "long"), dates
        // and full-width digits without ("short") — its NatNum4/5 and NatNum1/2/3.
        val style = if (s.kind == Kind.DATE || s.dbNum == 3) "short" else "long"
        val country = if (s.lang == Lang.ZH_TW) "TW" else "CN"
        val trans = when (s.dbNum) { 1 -> "一"; 2 -> "壹"; 3 -> "１"; else -> null }
            ?.let { """ number:transliteration-format="$it" number:transliteration-language="zh" number:transliteration-country="$country" number:transliteration-style="$style"""" } ?: ""
        return lang + trans
    }

    private fun odfSection(s: Section, name: String, maps: String, volatile: Boolean): String? {
        val body = StringBuilder()
        s.color?.let { body.append("""<style:text-properties fo:color="#${String.format(Locale.ROOT, "%06X", it and 0xFFFFFF)}"/>""") }
        val pending = StringBuilder()
        var element = "number:number-style"
        var extra = ""
        when (s.kind) {
            Kind.DATE -> {
                element = if (s.toks.none { it is DT && it.kind in "yMda" }) "number:time-style" else "number:date-style"
                if (s.toks.any { it is Elapsed }) extra = """ number:truncate-on-overflow="false""""
                var i = 0
                while (i < s.toks.size) {
                    val t = s.toks[i]
                    if (t is Lit) { pending.append(t.s); i++; continue }
                    odfTexts(body, pending)
                    val long = if ((t is DT && t.n >= 2) || (t is Elapsed && t.n >= 2)) """ number:style="long"""" else ""
                    when {
                        t is DT && t.kind == 'y' -> body.append(if (t.n >= 3) """<number:year number:style="long"/>""" else "<number:year/>")
                        // The Japanese calendar: its era and the era's year, as LibreOffice writes them.
                        t is DT && t.kind == 'g' -> body.append("""<number:era number:calendar="gengou"${if (t.n >= 3) """ number:style="long"""" else ""}/>""")
                        t is DT && t.kind == 'e' -> body.append(if (s.lang == Lang.JA) """<number:year number:calendar="gengou"${if (t.n >= 2) """ number:style="long"""" else ""}/>"""
                            else """<number:year number:style="long"/>""")
                        t is DT && t.kind == 'M' -> body.append(when {
                            t.n >= 4 -> """<number:month number:textual="true" number:style="long"/>"""
                            t.n == 3 || t.n == 5 -> """<number:month number:textual="true"/>"""
                            else -> "<number:month$long/>"
                        })
                        t is DT && t.kind == 'd' -> body.append(when {
                            t.n >= 4 -> """<number:day-of-week number:style="long"/>"""
                            t.n == 3 -> "<number:day-of-week/>"
                            else -> "<number:day$long/>"
                        })
                        t is DT && t.kind == 'a' -> body.append(if (t.n >= 4) """<number:day-of-week number:style="long"/>""" else "<number:day-of-week/>")
                        (t is DT && t.kind == 'h') || (t is Elapsed && t.kind == 'h') -> body.append("<number:hours$long/>")
                        (t is DT && t.kind == 'm') || (t is Elapsed && t.kind == 'm') -> body.append("<number:minutes$long/>")
                        (t is DT && t.kind == 's') || (t is Elapsed && t.kind == 's') -> {
                            val sub = s.toks.getOrNull(i + 1) as? SubSec
                            body.append("<number:seconds$long" + (sub?.let { """ number:decimal-places="${it.n}"""" } ?: "") + "/>")
                            if (sub != null) i++
                        }
                        t is AmPm -> body.append("<number:am-pm/>")
                        else -> return null
                    }
                    i++
                }
            }
            Kind.TEXT -> return odfText(s, name, maps)
            Kind.GENERAL, Kind.LITERAL -> for (t in s.toks) {
                if (t is Lit) odfLit(t, body, pending)
                else { odfTexts(body, pending); body.append("""<number:number number:min-integer-digits="1"/>""") }
            }
            Kind.NUMBER, Kind.SCIENTIFIC -> {
                if (s.percent > 0) element = "number:percentage-style"
                val ints = s.toks.filterIsInstance<IntPh>()
                val fracs = s.toks.filterIsInstance<FracPh>()
                val first = s.toks.indexOfFirst { it is IntPh || it === Point || it is FracPh }
                val last = s.toks.indexOfLast { it is IntPh || it === Point || it is FracPh || it is Exp || it is ExpPh }
                val embedded = StringBuilder()
                for ((i, t) in s.toks.withIndex()) {
                    when {
                        i < first || i > last -> if (t is Lit) odfLit(t, body, pending) else return null
                        i == first -> {
                            odfTexts(body, pending)
                            val dec = """ number:decimal-places="${fracs.size}" number:min-decimal-places="${fracs.count { it.ch == '0' }}""""
                            // `?` integer places are digits shown as blanks: LibreOffice's max-blank-integer-digits.
                            val blanks = ints.count { it.ch == '?' }
                            val minInt = """ number:min-integer-digits="${ints.count { it.ch == '0' } + blanks}"""" +
                                (if (blanks > 0) """ loext:max-blank-integer-digits="$blanks"""" else "")
                            if (s.kind == Kind.SCIENTIFIC) {
                                val exp = s.toks.filterIsInstance<Exp>().first()
                                val expDigits = s.toks.count { it is ExpPh }
                                val sign = if (exp.sign == '-') """ number:forced-exponent-sign="false"""" else ""
                                body.append("""<number:scientific-number$dec$minInt number:min-exponent-digits="$expDigits"$sign/>""")
                            } else {
                                val group = if (s.grouping) """ number:grouping="true"""" else ""
                                val factor = if (s.scale > 0) """ number:display-factor="${"1" + "000".repeat(s.scale)}"""" else ""
                                body.append("""<number:number$dec$minInt$group$factor>""")
                                body.append("\u0000")   // where embedded texts go
                                body.append("</number:number>")
                            }
                        }
                        t is Lit -> {
                            // Text between the digits: an embedded text, counted by the integer digits after it.
                            val after = s.toks.subList(i, s.toks.size).count { it is IntPh }
                            if (s.kind != Kind.NUMBER || after == 0) return null
                            embedded.append("""<number:embedded-text number:position="$after">${xml(t.s)}</number:embedded-text>""")
                        }
                    }
                }
                val at = body.indexOf("\u0000")
                if (at >= 0) body.replace(at, at + 1, embedded.toString())
            }
            Kind.FRACTION -> {
                val ints = s.toks.filterIsInstance<IntPh>()
                val nums = s.toks.count { it is NumPh }
                val dens = s.toks.count { it is DenPh }
                val fixed = s.toks.filterIsInstance<DenFix>().firstOrNull()
                val first = s.toks.indexOfFirst { it is IntPh || it is NumPh }
                val last = s.toks.indexOfLast { it is DenPh || it is DenFix }
                if (s.percent > 0) return null
                for ((i, t) in s.toks.withIndex()) {
                    when {
                        i < first || i > last -> if (t is Lit) odfLit(t, body, pending) else return null
                        i == first -> {
                            odfTexts(body, pending)
                            val whole = if (ints.isNotEmpty()) """ number:min-integer-digits="${ints.count { it.ch == '0' }}"""" else ""
                            val den = fixed?.let { """ number:denominator-value="${it.d}"""" } ?: """ number:min-denominator-digits="$dens""""
                            body.append("""<number:fraction$whole number:min-numerator-digits="$nums"$den/>""")
                        }
                    }
                }
            }
        }
        odfTexts(body, pending)
        val vol = if (volatile) """ style:volatile="true"""" else ""
        return """<$element style:name="$name"$vol${odfAttrs(s)}$extra>$body$maps</$element>"""
    }

    private fun odfText(s: Section, name: String, maps: String): String? {
        val body = StringBuilder()
        s.color?.let { body.append("""<style:text-properties fo:color="#${String.format(Locale.ROOT, "%06X", it and 0xFFFFFF)}"/>""") }
        val pending = StringBuilder()
        for (t in s.toks) when {
            t is Lit -> odfLit(t, body, pending, fill = false)
            t === At -> { odfTexts(body, pending); body.append("<number:text-content/>") }
            else -> return null
        }
        odfTexts(body, pending)
        return """<number:text-style style:name="$name">$body$maps</number:text-style>"""
    }

    // --- Excel's built-in formats -------------------------------------------------------------

    /** Built-in `numFmtId`s and their codes (with the Chinese-locale ones 27–36 and 50–58). */
    private val BUILT_IN: Map<Int, String> = mapOf(
        0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
        // Currency with brackets and the accounting formats, as US Excel defines them (the locale's own in each Excel).
        5 to "\"$\"#,##0_);\\(\"$\"#,##0\\)", 6 to "\"$\"#,##0_);[Red]\\(\"$\"#,##0\\)",
        7 to "\"$\"#,##0.00_);\\(\"$\"#,##0.00\\)", 8 to "\"$\"#,##0.00_);[Red]\\(\"$\"#,##0.00\\)",
        41 to "_(* #,##0_);_(* \\(#,##0\\);_(* \"-\"_);_(@_)", 42 to "_(\"$\"* #,##0_);_(\"$\"* \\(#,##0\\);_(\"$\"* \"-\"_);_(@_)",
        43 to "_(* #,##0.00_);_(* \\(#,##0.00\\);_(* \"-\"??_);_(@_)", 44 to "_(\"$\"* #,##0.00_);_(\"$\"* \\(#,##0.00\\);_(\"$\"* \"-\"??_);_(@_)",
        9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
        14 to "yyyy-mm-dd", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
        18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss", 22 to "yyyy-mm-dd h:mm",
        37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)", 39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
        45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mm:ss.0", 48 to "##0.0E+0", 49 to "@",
        27 to "yyyy\"年\"m\"月\"", 28 to "m\"月\"d\"日\"", 29 to "m\"月\"d\"日\"", 30 to "m-d-yy", 31 to "yyyy\"年\"m\"月\"d\"日\"",
        32 to "h\"时\"mm\"分\"", 33 to "h\"时\"mm\"分\"ss\"秒\"", 34 to "上午/下午h\"时\"mm\"分\"", 35 to "上午/下午h\"时\"mm\"分\"ss\"秒\"",
        36 to "yyyy\"年\"m\"月\"", 50 to "yyyy\"年\"m\"月\"", 51 to "m\"月\"d\"日\"", 52 to "yyyy\"年\"m\"月\"", 53 to "m\"月\"d\"日\"",
        54 to "m\"月\"d\"日\"", 55 to "上午/下午h\"时\"mm\"分\"", 56 to "上午/下午h\"时\"mm\"分\"ss\"秒\"", 57 to "yyyy\"年\"m\"月\"", 58 to "m\"月\"d\"日\"",
    )

    /** The code of built-in number format [id]; null for ids that are not built in. */
    fun builtIn(id: Int): String? = BUILT_IN[id]

    /** The id every Excel knows [code] by without a definition (the locale-neutral ones only); null otherwise. */
    fun builtInId(code: String): Int? {
        if (code.equals("General", ignoreCase = true)) return 0
        // Not the ones each Excel shows in its own way: 5–8 and 37–44 (currency, brackets, accounting — a Chinese
        // Excel shows negatives -1,234 and its ¥), 14–22 (dates and times in the locale's order and words).
        return BUILT_IN.entries.firstOrNull { (id, c) -> c == code && (id in 1..4 || id in 9..13 || id in 20..21 || id in 45..49) }?.key
    }

    // --- colours -----------------------------------------------------------------------------

    private val COLORS: Map<String, Long> = mapOf(
        "black" to 0xFF000000, "blue" to 0xFF0000FF, "cyan" to 0xFF00FFFF, "green" to 0xFF00FF00,
        "magenta" to 0xFFFF00FF, "red" to 0xFFFF0000, "white" to 0xFFFFFFFF, "yellow" to 0xFFFFFF00,
        "黑色" to 0xFF000000, "蓝色" to 0xFF0000FF, "藍色" to 0xFF0000FF, "青色" to 0xFF00FFFF, "绿色" to 0xFF00FF00, "綠色" to 0xFF00FF00,
        "洋红" to 0xFFFF00FF, "洋紅" to 0xFFFF00FF, "红色" to 0xFFFF0000, "紅色" to 0xFFFF0000, "白色" to 0xFFFFFFFF, "黄色" to 0xFFFFFF00, "黃色" to 0xFFFFFF00,
    )

    /** Colour [n] (1–56) of Excel's default palette — `[ColorN]`, and indexed colours 8–63 in files. */
    fun paletteColor(n: Int): Long? = PALETTE.getOrNull(n - 1)

    /** Excel's default 56-colour palette, for `[Color1]` … `[Color56]`. */
    private val PALETTE: List<Long> = listOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    ).map { 0xFF000000L or it.toLong() }
}
