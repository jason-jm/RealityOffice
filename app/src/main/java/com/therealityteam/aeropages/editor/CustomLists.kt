package com.therealityteam.aeropages.editor

import java.time.DayOfWeek
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Excel's custom lists (File ▸ Options ▸ Advanced ▸ Edit Custom Lists): the built-in ones — weekdays and months, short
 * and long, in English and in every language the app speaks, quarters, the heavenly stems and earthly branches — and the
 * user's own, kept on the device. AutoFill continues them (Mon → Tue), and Sort orders by them (Order ▸ Custom List).
 */
object CustomLists {
    /** The languages whose weekdays and months Excel lists, as the app speaks them. */
    private val LANGUAGES = listOf("de", "fr", "es", "it", "pt-BR", "ru", "nl", "pl", "tr", "vi", "th", "id", "hi", "ko")

    /** The built-in lists, English first (so "May" continues the short months, as in Excel). */
    val builtIn: List<List<String>> by lazy {
        val out = ArrayList<List<String>>()
        out += listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        out += listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        out += listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        out += listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        out += listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        out += listOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        out += listOf("週日", "週一", "週二", "週三", "週四", "週五", "週六")
        out += listOf("一月", "二月", "三月", "四月", "五月", "六月", "七月", "八月", "九月", "十月", "十一月", "十二月")
        out += listOf("1月", "2月", "3月", "4月", "5月", "6月", "7月", "8月", "9月", "10月", "11月", "12月")
        out += listOf("Q1", "Q2", "Q3", "Q4")
        out += listOf("Qtr1", "Qtr2", "Qtr3", "Qtr4")
        out += listOf("第一季", "第二季", "第三季", "第四季")
        out += listOf("第一季度", "第二季度", "第三季度", "第四季度")
        out += listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
        out += listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
        out += listOf("日曜日", "月曜日", "火曜日", "水曜日", "木曜日", "金曜日", "土曜日")
        // Every other language's weekdays (Sunday first, as Excel lists them) and months, short and long, as they stand alone.
        val days = listOf(DayOfWeek.SUNDAY) + DayOfWeek.entries.filter { it != DayOfWeek.SUNDAY }
        for (tag in LANGUAGES) {
            val locale = Locale.forLanguageTag(tag)
            for (style in listOf(TextStyle.SHORT_STANDALONE, TextStyle.FULL_STANDALONE)) {
                out += days.map { capital(it.getDisplayName(style, locale), locale) }
                out += Month.entries.map { capital(it.getDisplayName(style, locale), locale) }
            }
        }
        // A list once (two languages may name a month alike), only lists of names (not "1", "2" where data is missing).
        out.filter { l -> l.distinctBy { it.lowercase() }.size == l.size && l.none { e -> e.isBlank() || e.all { it.isDigit() } } }
            .distinctBy { l -> l.joinToString("\u0001") { it.lowercase() } }
    }

    /** The lists the user made, in the order made; set from the device's preferences. */
    @Volatile var user: List<List<String>> = emptyList()

    /** Every list AutoFill and Sort know: the built-in ones, then the user's. */
    fun all(): List<List<String>> = builtIn + user

    /** [text] as Excel lists it: its first letter a capital (Excel's lists are; the languages' own data mostly are not). */
    private fun capital(text: String, locale: Locale): String = text.trim().trimEnd('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    /**
     * A list's entries from what the user typed or imported: one per line (Excel's dialog) or separated by commas, each
     * trimmed, empty ones dropped, and each once.
     */
    fun parse(text: String): List<String> =
        text.split('\n', '\r', ',', '，', '、').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** How a list shows in the dialog: its entries joined by commas. */
    fun label(list: List<String>): String = list.joinToString(", ")
}
