package com.therealityteam.aeropages.model

import java.util.Locale

/**
 * The language a piece of text is written in, guessed the way Word's "detect language automatically" does
 * for proofing: by its letters first — Hangul is Korean, kana Japanese, Han alone Chinese, Thai, Devanagari
 * (Hindi), Cyrillic (Russian) — and for text in Latin letters by its commonest words and the letters only
 * one language uses (ß, ğ, ł, ơ…). Null when the text is too short or no language clearly leads.
 */
object TextLanguage {
    private const val MIN_LETTERS = 3

    // A language's commonest short words, lowercase.
    private val WORDS: Map<String, Set<String>> = mapOf(
        "en" to setOf("the", "and", "of", "to", "is", "in", "that", "it", "with", "for", "was", "are", "this", "be", "on", "not", "you", "have", "we", "our", "will", "from",
            "all", "but", "or", "by", "an", "if", "can", "has", "more", "there", "their", "which", "would", "about", "what", "when", "your", "they", "been", "were", "also", "than", "these", "into"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "mit", "den", "von", "auf", "für", "sich", "auch", "dem", "des", "im", "wir", "ich", "wird", "sind"),
        "fr" to setOf("le", "la", "les", "et", "est", "des", "une", "un", "du", "que", "pour", "dans", "ce", "qui", "pas", "au", "sur", "avec", "nous", "je", "sont", "aux"),
        "es" to setOf("el", "los", "las", "y", "que", "es", "una", "por", "con", "para", "se", "del", "al", "como", "más", "este", "esta", "hemos", "son", "su"),
        "it" to setOf("il", "di", "che", "è", "per", "non", "della", "sono", "gli", "nel", "alla", "delle", "questo", "abbiamo", "anche", "lo", "ha"),
        "pt" to setOf("o", "os", "as", "não", "um", "uma", "com", "em", "do", "da", "dos", "das", "são", "mais", "foi", "nós", "ao", "este", "esta"),
        "nl" to setOf("de", "het", "een", "en", "van", "is", "dat", "niet", "op", "te", "met", "voor", "zijn", "die", "ook", "wij", "ik", "wordt", "hebben"),
        "pl" to setOf("i", "w", "z", "na", "nie", "się", "jest", "to", "że", "do", "jak", "ale", "przez", "dla", "są", "od", "po", "tak"),
        "tr" to setOf("ve", "bir", "bu", "için", "ile", "da", "çok", "olarak", "ne", "daha", "gibi", "ama", "değil", "olan", "biz", "ben"),
        "vi" to setOf("và", "của", "là", "các", "có", "không", "những", "được", "trong", "cho", "một", "với", "này", "đã", "người", "chúng", "tôi"),
        "id" to setOf("dan", "yang", "di", "ini", "itu", "dengan", "untuk", "tidak", "dari", "dalam", "ada", "akan", "kami", "saya", "adalah", "telah"),
    )

    // Letters one language (of these) uses and the others rarely do.
    private val LETTERS: Map<String, String> = mapOf(
        "de" to "ßäöü",
        "fr" to "œèêëîïûù",
        "es" to "ñ¿¡",
        "it" to "ìò",
        "pt" to "ãõ",
        "pl" to "ąęłńśźż",
        "tr" to "ğış",
        "vi" to "ăđơưạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ",
    )

    /** The language of [text], or null when it cannot tell. */
    fun guess(text: String): Locale? {
        var latin = 0; var han = 0; var kana = 0; var hangul = 0; var thai = 0; var deva = 0; var cyrillic = 0
        for (ch in text) {
            if (!Character.isLetter(ch) && Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) continue
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.LATIN -> latin++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.THAI -> thai++
                Character.UnicodeScript.DEVANAGARI -> deva++
                Character.UnicodeScript.CYRILLIC -> cyrillic++
                else -> {}
            }
        }
        // Japanese mixes Han with kana; Chinese is Han alone; a little kana beside much Han is still Japanese.
        val scripts = listOf(
            "ko" to hangul, "ja" to if (kana > 0) kana + han else 0, "zh" to if (kana == 0) han else 0,
            "th" to thai, "hi" to deva, "ru" to cyrillic, "latin" to latin,
        )
        val (script, count) = scripts.maxBy { it.second }
        if (count < MIN_LETTERS) return null
        if (script != "latin") return Locale.forLanguageTag(script)
        return latinLanguage(text)?.let { Locale.forLanguageTag(it) }
    }

    private fun latinLanguage(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        val words = lower.split(Regex("[^\\p{L}]+")).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val score = HashMap<String, Int>()
        for (w in words) for ((lang, common) in WORDS) if (w in common) score[lang] = (score[lang] ?: 0) + 1
        for (ch in lower) for ((lang, own) in LETTERS) if (ch in own) score[lang] = (score[lang] ?: 0) + 2
        val ranked = score.entries.sortedByDescending { it.value }
        val top = ranked.firstOrNull() ?: return null
        if (top.value < 2) return null
        val second = ranked.getOrNull(1)?.value ?: 0
        return top.key.takeIf { top.value > second }
    }
}
