package com.therealityteam.aeropages.model

import java.text.DecimalFormat
import java.util.Currency
import java.util.Locale

/**
 * The currency of the reader's language as Excel's Accounting/Currency button gives it: its symbol (€, ₩, R$, ₽…) and the
 * format code, the symbol where that language writes it (`"€"` after the number in German and French, before it in
 * English, Korean or Brazilian Portuguese). A language without a country takes its usual one (de → Germany).
 */
object LocaleCurrency {
    data class Money(val symbol: String, val code: String)

    private val REGIONS = mapOf(
        "en" to "US", "zh" to "CN", "ja" to "JP", "ko" to "KR", "de" to "DE", "fr" to "FR", "es" to "ES", "it" to "IT", "pt" to "BR",
        "ru" to "RU", "nl" to "NL", "pl" to "PL", "tr" to "TR", "vi" to "VN", "th" to "TH", "in" to "ID", "id" to "ID", "hi" to "IN",
    )

    fun of(locale: Locale = Locale.getDefault()): Money {
        val regional = if (locale.country.isNotEmpty()) locale
            else Locale.Builder().setLocale(locale).setRegion(REGIONS[locale.language] ?: "US").build()
        val currency = runCatching { Currency.getInstance(regional) }.getOrNull() ?: Currency.getInstance("USD")
        val symbol = currency.getSymbol(regional).let { if (it == "CN¥" || it == "JP¥") "¥" else it }
        val pattern = (runCatching { java.text.NumberFormat.getCurrencyInstance(regional) }.getOrNull() as? DecimalFormat)?.toPattern()
            ?.substringBefore(';') ?: "¤#,##0.00"
        val quoted = "\"" + symbol.replace("\"", "") + "\""
        val code = pattern.replace("¤", quoted).replace(' ', ' ').replace(Regex("#,##,##0"), "#,##0")
        return Money(symbol, code)
    }
}
