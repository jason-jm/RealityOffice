package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.model.FormatCode
import java.util.Locale

/**
 * OpenDocument number styles read as the Excel format codes they show — a date's pattern and
 * names, elapsed hours, a colour, a fraction, a scaled or scientific number, Chinese numerals, a
 * condition's sections — so a spreadsheet from LibreOffice shows each value as it does there.
 * (The other way is [FormatCode.toOdf].)
 */
internal object OdfNumberStyles {

    /** Excel's named colours, by the RGB ODF gives. */
    private val COLORS = mapOf(
        0xFF000000 to "Black", 0xFF0000FF to "Blue", 0xFF00FFFF to "Cyan", 0xFF00FF00 to "Green",
        0xFFFF00FF to "Magenta", 0xFFFF0000 to "Red", 0xFFFFFFFF to "White", 0xFFFFFF00 to "Yellow",
    )

    /** Characters a code shows without quotes: in a date, and in a number (where , . / would mean something). */
    private const val DATE_SAFE = "$-+/():!^&'~{}<>= ,."
    private const val NUMBER_SAFE = "$-+():!^&'~{}<>= "

    /**
     * The code number style [node] shows — the styles its `style:map`s pick ([byName]) as the code's
     * other sections — or null when some part has no code (an era, a quarter, a week number …).
     */
    fun codeOf(node: OdfNode, byName: Map<String, OdfNode>): String? {
        val main = section(node) ?: return null
        val maps = node.children("map")
        if (maps.isEmpty()) return main
        val subs = maps.map { m ->
            val cond = m.attr("condition")?.replace(" ", "")?.removePrefix("value()") ?: return null
            val op = cond.replace("!=", "<>").replace("==", "=")
            op to (section(byName[m.attr("apply-style-name")] ?: return null) ?: return null)
        }
        val conds = subs.map { it.first }
        val secs = subs.map { it.second }
        val text = node.name == "text-style"
        // A text style's one map for every number (LibreOffice's "value()<=1.7976931348623157E+308").
        val everyNumber = conds.size == 1 && conds[0].startsWith("<=") && (conds[0].drop(2).toDoubleOrNull() ?: 0.0) >= 1e300
        return when {
            // Excel's own sections: positive (and zero), negative; or positive, negative, zero — and text.
            conds == listOf(">=0") && !text -> "${secs[0]};$main"
            conds == listOf(">0", "<0") && !text -> "${secs[0]};${secs[1]};$main"
            text && everyNumber -> "${secs[0]};$main"
            text && conds == listOf(">=0", "<0") -> "${secs[0]};${secs[1]};$main"
            text && conds == listOf(">0", "<0", "=0") -> "${secs[0]};${secs[1]};${secs[2]};$main"
            text || subs.size > 2 -> null
            else -> (subs.map { (c, s) -> "[$c]$s" } + main).joinToString(";")
        }
    }

    /** A code built piece by piece: literal text quoted only where it has to be, code as it is. */
    private class Code(private val safe: String) {
        private val sb = StringBuilder()
        private val pending = StringBuilder()

        fun text(s: String) { pending.append(s) }

        fun code(s: String) { flush(); sb.append(s) }

        private fun flush() {
            var i = 0
            while (i < pending.length) {
                val bare = pending[i] in safe
                var j = i
                while (j < pending.length && (pending[j] in safe) == bare) j++
                val run = pending.substring(i, j)
                // A double quote cannot stand inside quotes: it is escaped on its own.
                sb.append(if (bare) run else run.split('"').joinToString("\\\"") { if (it.isEmpty()) "" else "\"$it\"" })
                i = j
            }
            pending.setLength(0)
        }

        fun done(): String { flush(); return sb.toString() }
    }

    private fun section(node: OdfNode): String? {
        val date = node.name == "date-style" || node.name == "time-style"
        val c = Code(if (date) DATE_SAFE else NUMBER_SAFE)
        node.child("text-properties")?.attr("color")?.let { colorOf(it) }?.let { c.code("[$it]") }
        val lang = node.attr("language")
        val country = node.attr("country")
        // Chinese numerals: LibreOffice's ["一" "壹" "１"] digits, with units ("long") or without.
        val dbNum = when (node.attr("transliteration-format")) { "一" -> 1; "壹" -> 2; "１" -> 3; else -> 0 }
        if (dbNum > 0) {
            c.code("[DBNum$dbNum]")
            tag(node.attr("transliteration-language") ?: lang, node.attr("transliteration-country") ?: country)?.let { c.code(it) }
        }
        val zh = lang == "zh"
        val cjkDays = zh || lang == "ja"
        when (node.name) {
            "number-style", "percentage-style", "currency-style" -> {
                val percent = node.name == "percentage-style"
                for (e in node.children) when (e.name) {
                    "text" -> {
                        // A space as wide as a character (`_)`), as LibreOffice marks one.
                        val blank = e.attr("blank-width-char")?.firstOrNull()?.takeIf { e.text.startsWith(" ") }
                        val t = if (blank != null) e.text.drop(1) else e.text
                        if (blank != null) c.code("_$blank")
                        if (percent && '%' in t) t.split('%').forEachIndexed { i, part -> if (i > 0) c.code("%"); c.text(part) } else c.text(t)
                    }
                    "number" -> number(e, c)
                    "scientific-number" -> c.code(scientific(e))
                    "fraction" -> c.code(fraction(e))
                    "currency-symbol" -> c.text(e.text.trim())
                    "fill-character" -> e.text.firstOrNull()?.let { c.code("*$it") }
                    "text-properties", "map" -> Unit
                    else -> return null
                }
            }
            "date-style", "time-style" -> {
                // Month and weekday names in the style's language: a tag says which, unless the
                // names are Chinese weekdays (aaaa), which say so themselves.
                val month = node.children.any { it.name == "month" && it.attr("textual") == "true" }
                val weekday = node.children.any { it.name == "day-of-week" }
                // AM/PM in a language's words, an era: the tag says which language (zh's 上午/下午 say so themselves).
                val amPm = !zh && node.children.any { it.name == "am-pm" }
                val gengou = node.children.any { it.attr("calendar") == "gengou" }
                val tag = tag(lang, country)
                if (dbNum == 0 && tag != null && (month || amPm || gengou || (weekday && tag != "[$-804]"))) c.code(tag)
                // Elapsed time ([h]:mm, [mm]:ss): the first of hours, minutes and seconds runs past its unit.
                val lead = if (node.attr("truncate-on-overflow") == "false") node.children.firstOrNull { it.name in setOf("hours", "minutes", "seconds") } else null
                val chineseText = node.children.any { e -> e.name == "text" && e.text.any { it in '\u4E00'..'\u9FFF' } }
                for (e in node.children) {
                    val long = e.attr("style") == "long"
                    fun elapsed(s: String) = if (e === lead) "[$s]" else s
                    when (e.name) {
                        "year" -> c.code(if (e.attr("calendar") == "gengou") (if (long) "ee" else "e") else if (long) "yyyy" else "yy")
                        "era" -> c.code(if (long) "ggg" else "g")
                        "month" -> c.code(if (e.attr("textual") == "true") (if (long) "mmmm" else "mmm") else (if (long) "mm" else "m"))
                        "day" -> c.code(if (long) "dd" else "d")
                        "day-of-week" -> c.code(if (cjkDays) (if (long) "aaaa" else "aaa") else (if (long) "dddd" else "ddd"))
                        "hours" -> c.code(elapsed(if (long) "hh" else "h"))
                        "minutes" -> c.code(elapsed(if (long) "mm" else "m"))
                        "seconds" -> {
                            val places = e.attr("decimal-places")?.toIntOrNull()?.coerceIn(0, 3) ?: 0
                            c.code(elapsed(if (long) "ss" else "s") + (if (places > 0) "." + "0".repeat(places) else ""))
                        }
                        // 上午/下午 in Chinese — or with no language, beside Chinese text (LibreOffice's own h"时"mm"分").
                        "am-pm" -> c.code(if (zh || (lang == null && chineseText)) "上午/下午" else "AM/PM")
                        "text" -> c.text(e.text)
                        "text-properties", "map" -> Unit
                        else -> return null   // an era, a quarter, a week number: no code shows them
                    }
                }
            }
            "text-style" -> for (e in node.children) when (e.name) {
                "text-content" -> c.code("@")
                // A space as wide as a character (`_)`), as in a number style.
                "text" -> {
                    val blank = e.attr("blank-width-char")?.firstOrNull()?.takeIf { e.text.startsWith(" ") }
                    if (blank != null) { c.code("_$blank"); c.text(e.text.drop(1)) } else c.text(e.text)
                }
                "text-properties", "map" -> Unit
                else -> return null
            }
            else -> return null
        }
        return c.done()
    }

    /** `[Red]`, or `[ColorN]` for another colour of Excel's palette; null for one a code cannot name. */
    private fun colorOf(value: String): String? {
        val rgb = value.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16) ?: return null
        val argb = 0xFF000000L or rgb
        COLORS[argb]?.let { return it }
        return (1..56).firstOrNull { FormatCode.paletteColor(it) == argb }?.let { "Color$it" }
    }

    /** The locale tag for a language's month and weekday names. */
    private fun tag(lang: String?, country: String?): String? = when {
        lang == null -> null
        lang == "zh" && country?.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO") -> "[$-404]"
        else -> FormatCode.Lang.entries.firstOrNull { it.language == lang && lang != "zh" || (lang == "zh" && it == FormatCode.Lang.ZH_CN) }
            ?.let { "[$-" + Integer.toHexString(it.lcid).uppercase(Locale.ROOT) + "]" }
    }

    private fun number(n: OdfNode, c: Code) {
        val decimals = n.attr("decimal-places")?.toIntOrNull()
        val grouping = n.attr("grouping") == "true"
        val embedded = n.children("embedded-text").mapNotNull { e -> e.attr("position")?.toIntOrNull()?.takeIf { it >= 0 }?.let { it to e.text } }
        if (decimals == null && embedded.isEmpty() && !grouping) { c.code("General"); return }
        val minInt = n.attr("min-integer-digits")?.toIntOrNull()?.coerceIn(0, 30) ?: 1
        // Of those, the last so many show as blanks (`?`), as LibreOffice marks them.
        val blanks = n.attr("max-blank-integer-digits")?.toIntOrNull()?.coerceIn(0, minInt) ?: 0
        val places = (decimals ?: 0).coerceIn(0, 30)
        val minDec = (n.attr("min-decimal-places")?.toIntOrNull() ?: places).coerceIn(0, places)
        val digits = maxOf(minInt, if (grouping) 4 else 1, (embedded.maxOfOrNull { it.first } ?: 0) + 1)
        // Integer placeholders left to right: zeros for the digits always shown, # for the others;
        // an embedded text sits before the digit with [position] digits from it to the point.
        for (k in 0 until digits) {
            val pos = digits - 1 - k
            embedded.filter { it.first == pos + 1 }.forEach { c.text(it.second) }
            c.code(if (pos < blanks) "?" else if (pos < minInt) "0" else "#")
            if (grouping && pos == 3) c.code(",")
        }
        if (places > 0) c.code("." + "0".repeat(minDec) + "#".repeat(places - minDec))
        n.attr("display-factor")?.toDoubleOrNull()?.let { f ->
            var k = 0
            var v = f
            while (v >= 999.5 && k < 4) { v /= 1000.0; k++ }
            if (k > 0) c.code(",".repeat(k))
        }
    }

    private fun scientific(n: OdfNode): String {
        val minInt = (n.attr("min-integer-digits")?.toIntOrNull() ?: 1).coerceIn(1, 30)
        val places = (n.attr("decimal-places")?.toIntOrNull() ?: 2).coerceIn(0, 30)
        val minDec = (n.attr("min-decimal-places")?.toIntOrNull() ?: places).coerceIn(0, places)
        val exp = (n.attr("min-exponent-digits")?.toIntOrNull() ?: 2).coerceIn(1, 5)
        val sign = if (n.attr("forced-exponent-sign") == "false") "E-" else "E+"
        return "0".repeat(minInt) + (if (places > 0) "." + "0".repeat(minDec) + "#".repeat(places - minDec) else "") + sign + "0".repeat(exp)
    }

    private fun fraction(n: OdfNode): String {
        val whole = n.attr("min-integer-digits")?.toIntOrNull()?.coerceIn(0, 30)?.let { k -> (if (k == 0) "#" else "0".repeat(k)) + " " } ?: ""
        val num = "?".repeat((n.attr("min-numerator-digits")?.toIntOrNull() ?: 1).coerceIn(1, 7))
        val den = n.attr("denominator-value")?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: "?".repeat((n.attr("min-denominator-digits")?.toIntOrNull() ?: 1).coerceIn(1, 7))
        return "$whole$num/$den"
    }
}
