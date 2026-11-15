package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.model.ListDef
import com.therealityteam.aeropages.model.ListLevel
import com.therealityteam.aeropages.model.NumFormat

/**
 * ODF's list styles (`text:list-style`, the headings' `text:outline-style`) and the app's lists
 * ([ListDef]). A level's number is its `style:num-format` between `style:num-prefix` and
 * `style:num-suffix`, showing `text:display-levels` levels ("1.2.3"); LibreOffice also writes the
 * whole label Word's way (`loext:num-list-format="%1%.%2%."`), which is read first and written too.
 */
object OdfLists {

    /** The app's own list styles of plain bullets and numbers (read back as plain lists). */
    const val PLAIN_BULLETS = "Lbul"
    const val PLAIN_NUMBERS = "Lnum"

    /** [node]'s levels (a `text:list-style` or `text:outline-style`); null when it numbers nothing (an outline without numbers). */
    internal fun defOf(node: OdfNode): ListDef? {
        val levels = HashMap<Int, ListLevel>()
        for (c in node.children) {
            val lv = (c.attr("level")?.toIntOrNull() ?: continue) - 1
            if (lv !in 0..8) continue
            levels[lv] = when (c.name) {
                "list-level-style-bullet" -> ListLevel(NumFormat.BULLET, c.attr("bullet-char") ?: "•", font = c.child("text-properties")?.attr("font-name"))
                "list-level-style-number", "outline-level-style" -> numberLevel(c, lv)
                // A picture bullet: a plain one.
                else -> ListLevel(NumFormat.BULLET, "•")
            }
        }
        if (levels.isEmpty() || (node.name == "outline-style" && levels.values.none { it.format.numbered })) return null
        val base = if (levels[0]?.format == NumFormat.BULLET) ListDef.BULLETS else ListDef.NUMBERS
        return ListDef(List(9) { levels[it] ?: base.level(it) }, node.attr("name"))
    }

    private fun numberLevel(c: OdfNode, lv: Int): ListLevel {
        val format = formatOf(c.attr("num-format"))
        val own = c.attr("num-list-format")?.replace(Regex("%(\\d)%"), "%$1")
        val text = own ?: run {
            val shown = (c.attr("display-levels")?.toIntOrNull() ?: 1).coerceIn(1, lv + 1)
            val numbers = if (format == NumFormat.NONE && shown == 1) "" else ((lv + 2 - shown)..(lv + 1)).joinToString(".") { "%$it" }
            c.attr("num-prefix").orEmpty() + numbers + c.attr("num-suffix").orEmpty()
        }
        return ListLevel(format, text, c.attr("start-value")?.toIntOrNull() ?: 1)
    }

    fun formatOf(v: String?): NumFormat = when (v?.trim()) {
        null, "" -> NumFormat.NONE
        "1" -> NumFormat.DECIMAL
        "a" -> NumFormat.LOWER_LETTER
        "A" -> NumFormat.UPPER_LETTER
        "i" -> NumFormat.LOWER_ROMAN
        "I" -> NumFormat.UPPER_ROMAN
        else -> when (v.trim().first()) {
            '一', '〇' -> NumFormat.CHINESE
            '壹' -> NumFormat.CHINESE_LEGAL
            '甲' -> NumFormat.HEAVENLY_STEMS
            '子' -> NumFormat.EARTHLY_BRANCHES
            '①' -> NumFormat.CIRCLED
            '⑴' -> NumFormat.PARENTHESIZED
            '⒈' -> NumFormat.FULL_STOP
            '１' -> NumFormat.FULL_WIDTH
            '0' -> NumFormat.DECIMAL_ZERO
            else -> NumFormat.DECIMAL
        }
    }

    fun odfFormat(f: NumFormat): String = when (f) {
        NumFormat.DECIMAL -> "1"
        NumFormat.DECIMAL_ZERO -> "01, 02, 03, ..."
        NumFormat.LOWER_LETTER -> "a"
        NumFormat.UPPER_LETTER -> "A"
        NumFormat.LOWER_ROMAN -> "i"
        NumFormat.UPPER_ROMAN -> "I"
        NumFormat.CHINESE -> "一, 二, 三, ..."
        NumFormat.CHINESE_LEGAL -> "壹, 贰, 叁, ..."
        NumFormat.HEAVENLY_STEMS -> "甲, 乙, 丙, ..."
        NumFormat.EARTHLY_BRANCHES -> "子, 丑, 寅, ..."
        NumFormat.CIRCLED -> "①, ②, ③, ..."
        NumFormat.PARENTHESIZED -> "⑴, ⑵, ⑶, ..."
        NumFormat.FULL_STOP -> "⒈, ⒉, ⒊, ..."
        NumFormat.FULL_WIDTH -> "１, ２, ３, ..."
        NumFormat.BULLET, NumFormat.NONE -> ""
    }

    /**
     * [def] as a `text:list-style` named [name] (or, [outline], the headings' `text:outline-style`), its
     * levels indenting nothing themselves: a list item's indents are its paragraph's, as in Word.
     */
    fun listStyle(name: String, def: ListDef, outline: Boolean = false): String = buildString {
        append(if (outline) """<text:outline-style style:name="Outline">""" else """<text:list-style style:name="${OdfPackage.escape(name)}">""")
        def.levels.take(9).forEachIndexed { i, l ->
            // The label where the paragraph's own first line starts, its text after a tab (Word's way): the indents are the paragraph's.
            val props = """<style:list-level-properties text:list-level-position-and-space-mode="label-alignment">""" +
                """<style:list-level-label-alignment text:label-followed-by="listtab" fo:text-indent="0pt" fo:margin-left="0pt"/></style:list-level-properties>"""
            if (l.format == NumFormat.BULLET && !outline) {
                append("""<text:list-level-style-bullet text:level="${i + 1}" text:bullet-char="${OdfPackage.escape(l.text.ifEmpty { "•" })}">""").append(props)
                l.font?.let { append("""<style:text-properties style:font-name="${OdfPackage.escape(it)}"/>""") }
                append("</text:list-level-style-bullet>")
            } else {
                val (prefix, shown, suffix) = split(l.text, i)
                val element = if (outline) "text:outline-level-style" else "text:list-level-style-number"
                append("""<$element text:level="${i + 1}" """)
                if (prefix.isNotEmpty()) append("""style:num-prefix="${OdfPackage.escape(prefix)}" """)
                if (suffix.isNotEmpty()) append("""style:num-suffix="${OdfPackage.escape(suffix)}" """)
                append("""style:num-format="${OdfPackage.escape(odfFormat(l.format))}" """)
                if (shown > 1) append("""text:display-levels="$shown" """)
                if (l.start != 1) append("""text:start-value="${l.start}" """)
                append("""loext:num-list-format="${OdfPackage.escape(l.text.replace(Regex("%(\\d)"), "%$1%"))}">""").append(props).append("</$element>")
            }
        }
        append(if (outline) "</text:outline-style>" else "</text:list-style>")
    }

    /**
     * Word's label [text] of level [lv] as ODF's prefix, number of levels shown and suffix: "（%2）" →
     * ("（", 1, "）"), "%1.%2." → ("", 2, "."). A label ODF cannot say so ("%1-%3") shows the level's own number.
     */
    fun split(text: String, lv: Int): Triple<String, Int, String> {
        val refs = Regex("%(\\d)").findAll(text).toList()
        if (refs.isEmpty()) return Triple(text, 0, "")
        val prefix = text.substring(0, refs.first().range.first)
        val suffix = text.substring(refs.last().range.last + 1)
        val levels = refs.map { it.groupValues[1].toInt() }
        val between = (0 until refs.size - 1).all { text.substring(refs[it].range.last + 1, refs[it + 1].range.first) == "." }
        val consecutive = levels.zipWithNext().all { (a, b) -> b == a + 1 } && levels.last() == lv + 1
        return if (between && consecutive) Triple(prefix, levels.size, suffix) else Triple(prefix, 1, suffix)
    }
}
