package com.therealityteam.aeropages.model

/**
 * How a list level counts: Word's `w:numFmt`, ODF's `style:num-format`. [BULLET] shows its
 * level's text as it is; [NONE] shows only the text round the numbers.
 */
enum class NumFormat {
    DECIMAL, DECIMAL_ZERO, LOWER_LETTER, UPPER_LETTER, LOWER_ROMAN, UPPER_ROMAN,
    /** 一, 二 … 十, 十一 (Word's chineseCounting and the Japanese and Taiwanese ones). */
    CHINESE,
    /** 壹, 贰, 叁 (chineseLegalSimplified). */
    CHINESE_LEGAL,
    /** 甲, 乙, 丙 (ideographTraditional). */
    HEAVENLY_STEMS,
    /** 子, 丑, 寅 (ideographZodiac). */
    EARTHLY_BRANCHES,
    /** ①, ②, ③ (decimalEnclosedCircle). */
    CIRCLED,
    /** ⑴, ⑵, ⑶ (decimalEnclosedParen). */
    PARENTHESIZED,
    /** ⒈, ⒉, ⒊ (decimalEnclosedFullstop). */
    FULL_STOP,
    /** １, ２, ３ (decimalFullWidth). */
    FULL_WIDTH,
    BULLET, NONE;

    val numbered: Boolean get() = this != BULLET && this != NONE
}

/**
 * One level of a list. [text] is what the level shows, Word's way (`w:lvlText`): `%1` … `%9` stand for
 * the numbers of levels 1 … 9 (`"%1.%2."` → "2.3."); a bullet level's text is its bullet. [font] is
 * the bullet's font as the file named it (Symbol, Wingdings: their characters are shown as Unicode,
 * see [Lists.shownBullet]). [restartAfter]: the level (0-based) whose items restart this one's count —
 * by default any level above it (-1); [NEVER] never restarts. [legal] shows every level's number in
 * decimal (Word's `w:isLgl`: "1.1.1" under roman chapters).
 */
data class ListLevel(
    val format: NumFormat = NumFormat.DECIMAL,
    val text: String = "%1.",
    val start: Int = 1,
    val font: String? = null,
    val restartAfter: Int = -1,
    val legal: Boolean = false,
) {
    /** It shows nothing: no number and no text round one (LibreOffice puts headings in such a list, its outline without numbers). */
    val blank: Boolean get() = format == NumFormat.NONE && text.replace(Regex("%\\d"), "").isBlank()

    companion object { const val NEVER = -2 }
}

/**
 * A list's numbering, its nine levels. Paragraphs carrying the same [ParagraphFormat.listId] are one
 * list and count on through whatever comes between them (as Word and ODF's continued lists do).
 * [name] is the definition's own name when the file gave one (kept for writing back).
 */
data class ListDef(val levels: List<ListLevel>, val name: String? = null) {
    fun level(i: Int): ListLevel = levels.getOrNull(i.coerceIn(0, 8)) ?: levels.lastOrNull() ?: ListLevel()

    companion object {
        /** Bullets per level: •, ◦, ▪ over and over. */
        val BULLETS = ListDef(List(9) { ListLevel(NumFormat.BULLET, Lists.bulletFor(it)) })

        /** Numbers per level: 1., a., i. over and over, each level showing its own number. */
        val NUMBERS = ListDef(List(9) { i ->
            val f = when (i % 3) { 0 -> NumFormat.DECIMAL; 1 -> NumFormat.LOWER_LETTER; else -> NumFormat.LOWER_ROMAN }
            ListLevel(f, "%${i + 1}.")
        })

        /** Outline numbering: 1., 1.1., 1.1.1. … */
        val OUTLINE = ListDef(List(9) { i -> ListLevel(NumFormat.DECIMAL, (1..i + 1).joinToString(".") { "%$it" } + ".") }, "outline")

        /** The Chinese document numbering: 一、 （一） 1. （1） ① … */
        val CHINESE = ListDef(listOf(
            ListLevel(NumFormat.CHINESE, "%1、"),
            ListLevel(NumFormat.CHINESE, "（%2）"),
            ListLevel(NumFormat.DECIMAL, "%3."),
            ListLevel(NumFormat.DECIMAL, "（%4）"),
            ListLevel(NumFormat.CIRCLED, "%5"),
            ListLevel(NumFormat.LOWER_LETTER, "%6."),
            ListLevel(NumFormat.LOWER_LETTER, "（%7）"),
            ListLevel(NumFormat.LOWER_ROMAN, "%8."),
            ListLevel(NumFormat.LOWER_ROMAN, "（%9）"),
        ), "chinese")
    }
}

/** Numbering a document's lists: each list item's label ("3.", "1.2.", "（二）", "•"). */
object Lists {

    fun bulletFor(level: Int): String = when (level % 3) { 0 -> "•"; 1 -> "◦"; else -> "▪" }

    /** The definition paragraphs of [f] count by: their list's, or the plain bullets / numbers. */
    fun defOf(f: ParagraphFormat, lists: Map<Int, ListDef>): ListDef =
        lists[f.listId].takeIf { f.listId != 0 } ?: if (f.listKind == ListKind.BULLET) ListDef.BULLETS else ListDef.NUMBERS

    /**
     * The labels of the list items among [paragraphs], in reading order (null: something that is not a
     * paragraph — a table, a picture). A list with an id ([ParagraphFormat.listId]) counts on through
     * anything between its items; the plain lists (id 0) restart after anything that is not one of their items.
     */
    fun labels(paragraphs: Sequence<Paragraph?>, lists: Map<Int, ListDef>): Map<Paragraph, String> {
        val out = java.util.IdentityHashMap<Paragraph, String>()
        val counters = HashMap<Int, IntArray>()
        val seen = HashMap<Int, BooleanArray>()
        // The plain lists' counts, by kind: restarted by anything else.
        var plainKind = ListKind.NONE
        for (p in paragraphs) {
            val f = p?.format
            if (f == null || f.listKind == ListKind.NONE) { if (plainKind != ListKind.NONE) { counters.remove(PLAIN); seen.remove(PLAIN) }; plainKind = ListKind.NONE; continue }
            val def = defOf(f, lists)
            val key = if (f.listId == 0 || lists[f.listId] == null) {
                if (f.listKind != plainKind) { counters.remove(PLAIN); seen.remove(PLAIN); plainKind = f.listKind }
                PLAIN
            } else {
                if (plainKind != ListKind.NONE) { counters.remove(PLAIN); seen.remove(PLAIN); plainKind = ListKind.NONE }
                f.listId
            }
            val c = counters.getOrPut(key) { IntArray(9) }
            val s = seen.getOrPut(key) { BooleanArray(9) }
            val lv = f.listLevel.coerceIn(0, 8)
            c[lv] = if (s[lv]) c[lv] + 1 else def.level(lv).start
            s[lv] = true
            // Deeper levels start over when this one moves on (unless they say otherwise).
            for (d in lv + 1 until 9) {
                val r = def.level(d).restartAfter
                if (r == ListLevel.NEVER) continue
                if (r == -1 || r >= lv) s[d] = false
            }
            out[p] = label(def, lv, c, s)
        }
        return out
    }

    private const val PLAIN = Int.MIN_VALUE

    /** Level [lv]'s text with each `%n` replaced by level n's count (its start when it has not counted yet). */
    fun label(def: ListDef, lv: Int, counts: IntArray, seen: BooleanArray): String {
        val level = def.level(lv)
        if (level.format == NumFormat.BULLET) return shownBullet(level.text, level.font)
        val sb = StringBuilder()
        var i = 0
        val t = level.text
        while (i < t.length) {
            val ch = t[i]
            if (ch == '%' && i + 1 < t.length && t[i + 1] in '1'..'9') {
                val n = t[i + 1] - '1'
                if (n <= lv) {
                    val of = def.level(n)
                    val v = if (seen[n]) counts[n] else of.start
                    val fmt = if (level.legal && of.format.numbered) NumFormat.DECIMAL else of.format
                    sb.append(number(v, fmt))
                }
                i += 2
            } else { sb.append(ch); i++ }
        }
        return sb.toString()
    }

    /** [n] written in [format]. */
    fun number(n: Int, format: NumFormat): String = when (format) {
        NumFormat.DECIMAL -> n.toString()
        NumFormat.DECIMAL_ZERO -> if (n in 0..9) "0$n" else n.toString()
        NumFormat.LOWER_LETTER -> letters(n).lowercase()
        NumFormat.UPPER_LETTER -> letters(n)
        NumFormat.LOWER_ROMAN -> roman(n).lowercase()
        NumFormat.UPPER_ROMAN -> roman(n)
        NumFormat.CHINESE -> chinese(n, "零一二三四五六七八九", "十百千")
        NumFormat.CHINESE_LEGAL -> chinese(n, "零壹贰叁肆伍陆柒捌玖", "拾佰仟", legal = true)
        NumFormat.HEAVENLY_STEMS -> "甲乙丙丁戊己庚辛壬癸"[(n - 1).mod(10)].toString()
        NumFormat.EARTHLY_BRANCHES -> "子丑寅卯辰巳午未申酉戌亥"[(n - 1).mod(12)].toString()
        NumFormat.CIRCLED -> if (n in 1..20) ('①' + (n - 1)).toString() else if (n in 21..35) ('㉑' + (n - 21)).toString() else if (n in 36..50) ('㊱' + (n - 36)).toString() else n.toString()
        NumFormat.PARENTHESIZED -> if (n in 1..20) ('⑴' + (n - 1)).toString() else "($n)"
        NumFormat.FULL_STOP -> if (n in 1..20) ('⒈' + (n - 1)).toString() else "$n."
        NumFormat.FULL_WIDTH -> n.toString().map { if (it in '0'..'9') '０' + (it - '0') else it }.joinToString("")
        NumFormat.BULLET, NumFormat.NONE -> ""
    }

    /** A, B … Z, AA, BB … (Word's letters: past Z the letter doubles). */
    private fun letters(n: Int): String {
        if (n <= 0) return n.toString()
        val ch = 'A' + (n - 1) % 26
        return ch.toString().repeat((n - 1) / 26 + 1)
    }

    private fun roman(n: Int): String {
        if (n !in 1..3999) return n.toString()
        var k = n
        val sb = StringBuilder()
        for ((v, s) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC", 50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
            while (k >= v) { sb.append(s); k -= v }
        }
        return sb.toString()
    }

    /** Chinese counting: 十 for ten, 十一 for eleven (二十一, 一百零五); the legal form says 壹拾壹. */
    private fun chinese(n: Int, digits: String, units: String, legal: Boolean = false): String {
        if (n <= 0 || n > 9999) return n.toString()
        if (n == 0) return digits[0].toString()
        val parts = intArrayOf(n / 1000, n / 100 % 10, n / 10 % 10, n % 10)
        val sb = StringBuilder()
        var zero = false
        for (i in 0..3) {
            val d = parts[i]
            if (d == 0) { if (sb.isNotEmpty()) zero = true; continue }
            if (zero) { sb.append(digits[0]); zero = false }
            // 十一 rather than 一十一 when ten comes first.
            if (!(i == 2 && d == 1 && sb.isEmpty() && !legal)) sb.append(digits[d])
            if (i < 3) sb.append(units[2 - i])
        }
        return sb.toString()
    }

    /**
     * A bullet as it shows: Symbol's and Wingdings' characters (written as U+F0xx, or plainly in the
     * font's own code) as the Unicode characters they draw.
     */
    fun shownBullet(text: String, font: String?): String {
        val f = font?.lowercase().orEmpty()
        return text.map { ch ->
            val code = if (ch.code in 0xF000..0xF0FF) ch.code - 0xF000 else if (f.startsWith("wingdings") || f == "symbol") ch.code else -1
            when {
                code < 0 -> ch
                f.startsWith("wingdings") -> WINGDINGS[code] ?: '•'
                else -> SYMBOL[code] ?: if (ch.code >= 0xF000) '•' else ch
            }
        }.joinToString("")
    }

    private val SYMBOL = mapOf(0xB7 to '•', 0xA8 to '♦', 0xA7 to '♣', 0xA9 to '♥', 0xAA to '♠', 0x2D to '−', 0x6F to 'ο', 0xD8 to '¬', 0xDE to '⇒', 0xAE to '→')
    private val WINGDINGS = mapOf(
        0x6C to '●', 0x6E to '■', 0x6F to '□', 0x71 to '❑', 0x75 to '◆', 0x76 to '❖', 0xA7 to '▪', 0xA8 to '◻', 0x9F to '•',
        0xD8 to '➢', 0xD7 to '➢', 0xE8 to '➔', 0xFC to '✓', 0xFB to '✗', 0xFE to '☑', 0x4A to '☺', 0x77 to '⬥', 0xA1 to '○', 0x70 to '◼', 0x73 to '◆', 0x74 to '⧫',
    )
}
