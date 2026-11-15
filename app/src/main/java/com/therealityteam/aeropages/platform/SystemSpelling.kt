package com.therealityteam.aeropages.platform

import android.content.Context
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import com.therealityteam.aeropages.model.TextLanguage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * The device's spell checker (Settings → Spell checker: LatinIME's on PICO's emulator), asked through a
 * [SpellCheckerSession]: which words of a text it takes for typos, and what it would put instead. Not
 * every device has one turned on; then [check] answers null and only AeroDesk's own checks run.
 */
object SystemSpelling {

    /** A word the spell checker does not know: [start] and [length] in its text, and what it suggests instead. */
    class Typo(val start: Int, val length: Int, val suggestions: List<String>)

    /** At most this many texts to one request (a long document is asked in turns). */
    private const val CHUNK = 40
    private const val SUGGESTIONS = 5

    /** Whether the device has a spell checker turned on (it may still have no dictionary for a language). */
    fun available(context: Context): Boolean {
        val tsm = context.getSystemService(TextServicesManager::class.java) ?: return false
        return tsm.isSpellCheckerEnabled && tsm.currentSpellCheckerInfo != null
    }

    /**
     * Each of [texts]' typos, in order — or null when the device has no spell checker turned on, none
     * for any language tried, or it did not answer within [timeoutMs] for a request. Each text is checked
     * in its own language ([TextLanguage], as Word detects it): a German paragraph in German — or not at
     * all when the spell checker has no German, rather than in English, where every word would be a typo.
     * Text whose language cannot be told goes by the spell checker's own settings (the user's keyboard
     * languages), [locale], then English. Text in another script with no dictionary (Chinese, Thai…) is
     * checked in English for its words in Latin letters only: those are English far more often than not.
     */
    suspend fun check(context: Context, texts: List<String>, locale: Locale = Locale.getDefault(), timeoutMs: Long = 10_000): List<List<Typo>>? {
        if (texts.isEmpty()) return emptyList()
        val tsm = context.getSystemService(TextServicesManager::class.java) ?: return null
        if (!tsm.isSpellCheckerEnabled) return null
        val out = MutableList<List<Typo>>(texts.size) { emptyList() }
        var checked = false
        for ((language, indices) in texts.indices.groupBy { TextLanguage.guess(texts[it])?.language ?: locale.language }) {
            val own = if (language == locale.language) open(tsm, locale, true) ?: open(tsm, locale, false)
                else openLanguage(tsm, language)
            val latinOnly = own == null
            val asker = own ?: if (language == locale.language || language !in LATIN) open(tsm, Locale.US, false) else null
            asker ?: continue
            val found = asker.use { it.ask(indices.map { i -> texts[i] }, latinOnly, timeoutMs) } ?: return null
            indices.forEachIndexed { k, i -> out[i] = found[k] }
            checked = true
        }
        return out.takeIf { checked }
    }

    // The country a language's dictionary is usually listed under (LatinIME has en_US, de_DE…, not a bare "en").
    private val REGIONS = mapOf("en" to "US", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "BR", "ru" to "RU",
        "nl" to "NL", "pl" to "PL", "tr" to "TR", "vi" to "VN", "id" to "ID", "in" to "ID", "ko" to "KR", "th" to "TH", "hi" to "IN")

    /** A session for text in [language]: the language alone, else with its usual country (null: no dictionary for it). */
    private fun openLanguage(tsm: TextServicesManager, language: String): Asker? =
        open(tsm, Locale.forLanguageTag(language), false)
            ?: REGIONS[language]?.let { open(tsm, Locale.Builder().setLanguage(language).setRegion(it).build(), false) }

    // The languages of the app written in Latin letters: with no dictionary for one, its text is not checked.
    private val LATIN = setOf("en", "de", "fr", "es", "it", "pt", "nl", "pl", "tr", "vi", "id", "in")

    /** A spell-checker session with its answers; one exists only for a language the spell checker has (null otherwise). */
    private class Asker(val session: SpellCheckerSession, val answers: Channel<Array<out SentenceSuggestionsInfo>?>) : AutoCloseable {
        suspend fun ask(texts: List<String>, latinOnly: Boolean, timeoutMs: Long): List<List<Typo>>? {
            val out = ArrayList<List<Typo>>(texts.size)
            for (chunk in texts.chunked(CHUNK)) {
                // Each text with a space after it: LatinIME takes a last word's full stop for part of the word without one ("word." flagged).
                session.getSentenceSuggestions(chunk.mapIndexed { i, t -> TextInfo("$t ", 0, t.length + 1, i, i) }.toTypedArray(), SUGGESTIONS)
                val results = withTimeoutOrNull(timeoutMs) { answers.receive() } ?: return null
                for (i in chunk.indices) out.add(typos(results.getOrNull(i), chunk[i], latinOnly))
            }
            return out
        }

        override fun close() = session.close()
    }

    private fun open(tsm: TextServicesManager, l: Locale, refer: Boolean): Asker? = runCatching {
        val answers = Channel<Array<out SentenceSuggestionsInfo>?>(Channel.UNLIMITED)
        val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {}
            override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) { answers.trySend(results) }
        }
        tsm.newSpellCheckerSession(SpellCheckerSession.SpellCheckerSessionParams.Builder()
            .setLocale(l).setShouldReferToSpellCheckerLanguageSettings(refer)
            .setSupportedAttributes(SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY)
            .build(), { it.run() }, listener)?.let { Asker(it, answers) }
    }.getOrNull()

    /** The words of [text] that look like typos, with their suggestions; with [latinOnly], only words in Latin letters. */
    private fun typos(info: SentenceSuggestionsInfo?, text: String, latinOnly: Boolean): List<Typo> {
        info ?: return emptyList()
        val out = ArrayList<Typo>()
        for (k in 0 until info.suggestionsCount) {
            val si = info.getSuggestionsInfoAt(k) ?: continue
            if (si.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO == 0) continue
            val start = info.getOffsetAt(k)
            val length = info.getLengthAt(k)
            if (length <= 0 || start < 0 || start + length > text.length) continue
            if (latinOnly && text.substring(start, start + length).none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }) continue
            out.add(Typo(start, length, (0 until si.suggestionsCount.coerceAtLeast(0)).mapNotNull { si.getSuggestionAt(it) }))
        }
        return out
    }
}
