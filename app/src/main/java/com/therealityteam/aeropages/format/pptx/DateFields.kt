package com.therealityteam.aeropages.format.pptx

import com.therealityteam.aeropages.model.SlideDate

/**
 * PowerPoint's date fields (`a:fld type="datetime1"` … `"datetime13"`, each a format of the run's
 * language): the field — type and language — that shows a date format, and the format a field
 * shows, its cached text telling the app's own formats apart.
 */
internal object DateFields {

    /** The field type and run language that show [format]. */
    fun fieldOf(format: String): Pair<String, String> = when (format) {
        "yyyy/m/d" -> "datetime1" to "zh-CN"
        "yyyy\"年\"m\"月\"d\"日\"" -> "datetime4" to "zh-CN"
        "[$-804]yyyy\"年\"m\"月\"d\"日\" aaaa" -> "datetime2" to "zh-CN"
        "yyyy-mm-dd" -> "datetime1" to "sv-SE"
        "m/d/yyyy" -> "datetime1" to "en-US"
        "[$-409]dddd, mmmm d, yyyy" -> "datetime2" to "en-US"
        "[$-409]d mmmm yyyy" -> "datetime3" to "en-US"
        "[$-409]mmmm d, yyyy" -> "datetime4" to "en-US"
        // Any other: the field of its kind (LibreOffice shows datetime1 short, 4 long, 2 with the weekday).
        else -> when (SlideDate.kindOf(format)) {
            com.therealityteam.aeropages.model.DateKind.SHORT -> "datetime1" to "zh-CN"
            com.therealityteam.aeropages.model.DateKind.LONG -> "datetime4" to "zh-CN"
            com.therealityteam.aeropages.model.DateKind.WEEKDAY -> "datetime2" to "zh-CN"
        }
    }

    /** The format a field of [type] shows: the one its [cached] text is in, else PowerPoint's US English one. */
    fun formatOf(type: String, cached: String): String {
        SHAPES.firstOrNull { it.first.matches(cached.trim()) }?.let { return it.second }
        return when (type.removePrefix("datetime").toIntOrNull()) {
            1 -> "m/d/yyyy"
            2 -> "[$-409]dddd, mmmm d, yyyy"
            3 -> "[$-409]d mmmm yyyy"
            4 -> "[$-409]mmmm d, yyyy"
            5 -> "[$-409]d-mmm-yy"
            6 -> "[$-409]mmmm yy"
            7 -> "[$-409]mmm-yy"
            8 -> "m/d/yyyy h:mm AM/PM"
            9 -> "m/d/yyyy h:mm:ss AM/PM"
            10 -> "h:mm"
            11 -> "h:mm:ss"
            12 -> "h:mm AM/PM"
            13 -> "h:mm:ss AM/PM"
            else -> SlideDate.DEFAULT_FORMAT
        }
    }

    /** What a date in each of the app's formats looks like. */
    private val SHAPES = listOf(
        Regex("""\d{4}/\d{1,2}/\d{1,2}""") to "yyyy/m/d",
        Regex("""\d{4}年\d{1,2}月\d{1,2}日""") to "yyyy\"年\"m\"月\"d\"日\"",
        Regex("""\d{4}年\d{1,2}月\d{1,2}日 ?星期[一二三四五六日天]""") to "[$-804]yyyy\"年\"m\"月\"d\"日\" aaaa",
        Regex("""\d{4}-\d{2}-\d{2}""") to "yyyy-mm-dd",
        Regex("""\d{1,2}/\d{1,2}/\d{4}""") to "m/d/yyyy",
        Regex("""[A-Z][a-z]+, [A-Z][a-z]+ \d{1,2}, \d{4}""") to "[$-409]dddd, mmmm d, yyyy",
        Regex("""\d{1,2} [A-Z][a-z]+ \d{4}""") to "[$-409]d mmmm yyyy",
        Regex("""[A-Z][a-z]+ \d{1,2}, \d{4}""") to "[$-409]mmmm d, yyyy",
    )
}
