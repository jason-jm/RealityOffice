package com.therealityteam.aeropages.model

import java.util.Locale

/**
 * The locale Chinese characters in a paragraph are drawn in. The same character has a Japanese, a Simplified and a
 * Traditional Chinese and a Korean form (直, 骨, 写…), and the font picks one by the text's locale: Word picks by
 * each run's language; with no language kept, the text tells — kana make it Japanese, hangul Korean — and Han
 * alone takes the reader's language (Japanese forms in a Japanese app, Traditional in a Traditional Chinese one),
 * Simplified Chinese in any other. Null when the text has neither Han nor kana (nothing to choose).
 */
object HanLocale {
    fun of(paragraph: Paragraph, app: Locale = Locale.getDefault()): Locale? = decide(paragraph.runs.map { it.text }, app)

    /** The same for a piece of text (a spreadsheet cell, a run). */
    fun of(text: CharSequence, app: Locale = Locale.getDefault()): Locale? = decide(listOf(text), app)

    /**
     * Only what the text itself says beyond doubt — Japanese where kana appear, Korean where hangul stands beside Han
     * — or null: for one run of a paragraph, whose Han alone follows the paragraph's choice.
     */
    fun strong(text: CharSequence): Locale? = when (val l = decide(listOf(text), Locale.ROOT)) {
        Locale.JAPANESE, Locale.KOREAN -> l
        else -> null
    }

    private fun decide(texts: List<CharSequence>, app: Locale): Locale? {
        var han = false; var hangul = false
        for (t in texts) for (ch in t) {
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.HAN -> han = true
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return Locale.JAPANESE
                Character.UnicodeScript.HANGUL -> hangul = true
                else -> {}
            }
        }
        return when {
            !han -> null
            hangul -> Locale.KOREAN
            app.language == "zh" || app.language == "ja" || app.language == "ko" -> app
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }
}
