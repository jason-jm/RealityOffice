package com.therealityteam.aeropages.platform

import com.therealityteam.aeropages.model.ChineseConversion

/** Chinese conversion with the device's ICU transliterators ("Hans-Hant", "Hant-Hans"); null where the device has none. */
internal object IcuChinese {
    fun engine(): ChineseConversion.Engine? {
        val toT = runCatching { android.icu.text.Transliterator.getInstance("Hans-Hant") }.getOrNull() ?: return null
        val toS = runCatching { android.icu.text.Transliterator.getInstance("Hant-Hans") }.getOrNull() ?: return null
        return object : ChineseConversion.Engine {
            override fun toTraditional(text: String): String = toT.transliterate(text)
            override fun toSimplified(text: String): String = toS.transliterate(text)
        }
    }
}
