package com.therealityteam.aeropages.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList

/** The in-app language choice: follow the system, or pin one of the shipped languages ([label] is its own name). */
internal enum class AppLanguage(val tag: String?, val label: String) {
    SYSTEM(null, ""),
    CHINESE("zh-CN", "简体中文"), TRADITIONAL_CHINESE("zh-TW", "繁體中文"), ENGLISH("en", "English"), JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"), GERMAN("de", "Deutsch"), FRENCH("fr", "Français"), SPANISH("es", "Español"), ITALIAN("it", "Italiano"),
    PORTUGUESE("pt-BR", "Português (Brasil)"), RUSSIAN("ru", "Русский"), DUTCH("nl", "Nederlands"), POLISH("pl", "Polski"),
    TURKISH("tr", "Türkçe"), VIETNAMESE("vi", "Tiếng Việt"), THAI("th", "ไทย"), INDONESIAN("id", "Bahasa Indonesia"), HINDI("hi", "हिन्दी");

    companion object {
        /**
         * The choice a BCP-47 tag stands for: by language (`zh-Hant`/`zh-TW`/`zh-HK`/`zh-MO` → traditional, other `zh` →
         * simplified; any `pt` → Brazilian; `in` is Indonesian's old code); a language the app does not have → English.
         */
        fun fromTag(tag: String?): AppLanguage {
            val t = tag?.lowercase() ?: return SYSTEM
            if (t == "system") return SYSTEM
            val lang = t.substringBefore('-').substringBefore('_')
            if (lang == "zh") return if (t.contains("hant") || t.contains("tw") || t.contains("hk") || t.contains("mo")) TRADITIONAL_CHINESE else CHINESE
            if (lang == "in") return INDONESIAN
            return entries.firstOrNull { it.tag != null && it.tag.substringBefore('-') == lang } ?: ENGLISH
        }
    }
}

/**
 * Per-app locale through the platform [LocaleManager] (API 33+). Setting a locale restarts
 * the activity with the matching `res/values*` resources; both `stringResource` in
 * composables and [Strings]/[AppTexts] lookups then resolve in that language.
 */
internal object AppLocale {
    fun current(context: Context): AppLanguage {
        val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return AppLanguage.SYSTEM
        if (locales.isEmpty) return AppLanguage.SYSTEM
        return AppLanguage.fromTag(locales[0].toLanguageTag())
    }

    fun set(context: Context, language: AppLanguage) {
        if (current(context) == language) return
        val manager = context.getSystemService(LocaleManager::class.java) ?: return
        manager.applicationLocales = language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
    }
}
