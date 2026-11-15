package com.therealityteam.aeropages.model

import java.time.LocalDateTime

/**
 * Word's DATE and TIME fields, which show the date and time now (Word updates them as a document
 * opens and prints — a letter template's date is the day it is written); CREATEDATE, SAVEDATE and
 * PRINTDATE keep what the file says. The picture switch (`\@ "MMMM d, yyyy"`) says how: y / yy /
 * yyyy, M … MMMM, d … dddd, h / hh (12-hour), H / HH, m / mm, s / ss, AM/PM or am/pm, text in
 * single quotes, Japanese Word's aaa / aaaa (木 / 木曜日); a picture with Chinese or Japanese text
 * names months and days so; others in the app's language (Oktober, octobre, октября) — English for an
 * English, Chinese or Japanese app. Without one, Word's short date of the language (2026/10/8 in a
 * Chinese document, 10/8/2026 in English, 08.10.2026 in German…) or time (14:05).
 */
object FieldDates {

    /** What a field of [code] shows now, or null when it is not a date or time field that updates. */
    fun shown(code: String, now: LocalDateTime = LocalDateTime.now(), cjk: Boolean = false): String? {
        val trimmed = code.trim()
        val name = trimmed.substringBefore(' ').uppercase()
        if (name != "DATE" && name != "TIME") return null
        val picture = Regex("""\\@\s*(?:"([^"]*)"|(\S+))""").find(trimmed)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        if (picture != null) return format(picture, now)
        return if (name == "TIME") "${now.hour}:${two(now.minute)}"
        else if (cjk) "${now.year}/${now.monthValue}/${now.dayOfMonth}"
        // Not a Chinese or Japanese document: the app's language's short date — English's for a Chinese or Japanese app.
        else format(shortDate(FormatCode.Lang.current().takeUnless { it == FormatCode.Lang.ZH_CN || it == FormatCode.Lang.ZH_TW || it == FormatCode.Lang.JA } ?: FormatCode.Lang.EN), now)
    }

    /** Word's default short date picture in [lang] (a DATE field without `\@`). */
    fun shortDate(lang: FormatCode.Lang): String = when (lang) {
        FormatCode.Lang.ZH_CN, FormatCode.Lang.ZH_TW, FormatCode.Lang.JA -> "yyyy/M/d"
        FormatCode.Lang.KO -> "yyyy-MM-dd"
        FormatCode.Lang.DE, FormatCode.Lang.RU, FormatCode.Lang.PL, FormatCode.Lang.TR -> "dd.MM.yyyy"
        FormatCode.Lang.NL -> "d-M-yyyy"
        FormatCode.Lang.EN -> "M/d/yyyy"
        else -> "dd/MM/yyyy"
    }

    /** Month and weekday names from the platform for a language whose Word writes its own (not English, Chinese, Japanese). */
    private fun local(lang: FormatCode.Lang): java.text.DateFormatSymbols? = when (lang) {
        FormatCode.Lang.EN, FormatCode.Lang.ZH_CN, FormatCode.Lang.ZH_TW, FormatCode.Lang.JA -> null
        else -> FormatCode.Lang.symbols(lang)
    }

    private val EN_MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val EN_DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val CJK_DAYS = "一二三四五六日"
    private val JA_DAYS = "月火水木金土日"

    private fun two(n: Int) = if (n in 0..9) "0$n" else "$n"

    /** [now] as the picture [p] has it. */
    fun format(p: String, now: LocalDateTime, lang: FormatCode.Lang = FormatCode.Lang.current()): String {
        // Chinese or Japanese text (CJK ideographs, kana, full-width forms) — not Korean's hangul, which has names of its own.
        val cjk = p.any { it.code in 0x2E80..0x9FFF || it.code in 0xF900..0xFAFF || it.code in 0xFF00..0xFFEF }
        val names = if (cjk) null else local(lang)
        val ja = p.any { it in '\u3040'..'\u30FF' } || p.contains("aaa") || p.contains('曜')
        val out = StringBuilder()
        var i = 0
        fun run(ch: Char): Int { var n = 0; while (i + n < p.length && p[i + n] == ch) n++; return n }
        val hasAmPm = p.contains("AM/PM", ignoreCase = true)
        while (i < p.length) {
            val c = p[i]
            when {
                c == '\'' -> { val end = p.indexOf('\'', i + 1).let { if (it < 0) p.length else it }; out.append(p, i + 1, end); i = end + 1 }
                p.startsWith("AM/PM", i) -> {
                    out.append(names?.amPmStrings?.get(if (now.hour < 12) 0 else 1) ?: if (now.hour < 12) (if (cjk) "上午" else "AM") else (if (cjk) "下午" else "PM")); i += 5
                }
                p.startsWith("am/pm", i) -> { out.append(if (now.hour < 12) "am" else "pm"); i += 5 }
                c == 'y' || c == 'Y' -> { val n = run(c); out.append(if (n <= 2) two(now.year % 100) else now.year.toString()); i += n }
                c == 'M' -> {
                    val n = run(c)
                    val m = now.monthValue
                    out.append(when (n) {
                        1 -> "$m"; 2 -> two(m)
                        3 -> if (cjk) "${m}月" else names?.shortMonths?.get(m - 1)?.removeSuffix(".") ?: EN_MONTHS[m - 1].take(3)
                        else -> if (cjk) "${m}月" else names?.months?.get(m - 1) ?: EN_MONTHS[m - 1]
                    })
                    i += n
                }
                c == 'd' || c == 'D' -> {
                    val n = run(c)
                    val dow = now.dayOfWeek.value - 1
                    out.append(when (n) {
                        1 -> "${now.dayOfMonth}"; 2 -> two(now.dayOfMonth)
                        // DateFormatSymbols' weekdays: index 1 = Sunday (dow here is 0 = Monday).
                        3 -> if (ja) JA_DAYS[dow].toString() else if (cjk) "周${CJK_DAYS[dow]}" else names?.shortWeekdays?.get((dow + 1) % 7 + 1)?.removeSuffix(".") ?: EN_DAYS[dow].take(3)
                        else -> if (ja) "${JA_DAYS[dow]}曜日" else if (cjk) "星期${CJK_DAYS[dow]}" else names?.weekdays?.get((dow + 1) % 7 + 1) ?: EN_DAYS[dow]
                    })
                    i += n
                }
                c == 'a' && p.startsWith("aaa", i) -> { val n = run(c); val dow = now.dayOfWeek.value - 1; out.append(if (n >= 4) "${JA_DAYS[dow]}曜日" else JA_DAYS[dow].toString()); i += n }
                c == 'h' -> { val n = run(c); val h = if (hasAmPm) ((now.hour + 11) % 12 + 1) else now.hour; out.append(if (n >= 2) two(h) else "$h"); i += n }
                c == 'H' -> { val n = run(c); out.append(if (n >= 2) two(now.hour) else "${now.hour}"); i += n }
                c == 'm' -> { val n = run(c); out.append(if (n >= 2) two(now.minute) else "${now.minute}"); i += n }
                c == 's' || c == 'S' -> { val n = run(c); out.append(if (n >= 2) two(now.second) else "${now.second}"); i += n }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }
}
