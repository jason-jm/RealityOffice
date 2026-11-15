package com.therealityteam.aeropages.model

/**
 * Word's word count: every East Asian character (Chinese, Japanese kana, full-width punctuation) is a word of its own,
 * and the rest is counted in runs between spaces — Word's 字数 = 中文字符和朝鲜语单词 + 非中文单词. Korean (Hangul)
 * counts in words, as Word counts it.
 */
object WordCounts {
    data class Counts(val asian: Int, val other: Int) {
        val words: Int get() = asian + other
        operator fun plus(o: Counts) = Counts(asian + o.asian, other + o.other)
    }

    val NONE = Counts(0, 0)

    /** Whether [cp] counts as a word by itself. */
    fun isAsian(cp: Int): Boolean = when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.BOPOMOFO -> true
        // Full-width punctuation and forms (，。、「」！？ …), the CJK symbols block, the iteration and prolonged-sound marks.
        else -> cp in 0x3000..0x303F || cp in 0xFF01..0xFF60 || cp in 0xFFE0..0xFFEE || cp == 0x30FC
    }

    fun of(text: String): Counts {
        var asian = 0; var other = 0; var inWord = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x0C -> inWord = false
                isAsian(cp) -> { asian++; inWord = false }
                !inWord -> { other++; inWord = true }
            }
        }
        return Counts(asian, other)
    }
}
