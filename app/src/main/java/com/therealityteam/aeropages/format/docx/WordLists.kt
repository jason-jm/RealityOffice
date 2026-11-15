package com.therealityteam.aeropages.format.docx

import com.therealityteam.aeropages.format.XmlPull
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ListDef
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.ListLevel
import com.therealityteam.aeropages.model.NumFormat
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.Paragraph
import org.xmlpull.v1.XmlPullParser

/**
 * Word's list numbering (`word/numbering.xml`) and the app's lists ([ListDef]): each `w:num` is read
 * as a list of the app's — the `w:num`s sharing an abstract definition as one list, counting on
 * together (as Word counts them), one restarting its numbers (`w:startOverride`) or redefining a level
 * as a list of its own. The app's plain bullets and numbers are written as the definitions named
 * [PLAIN_BULLETS] and [PLAIN_NUMBERS], and read back as plain ones.
 */
object WordLists {

    const val PLAIN_BULLETS = "AeroBullets"
    const val PLAIN_NUMBERS = "AeroNumbers"

    /** What numbering.xml holds: each numId's list (its app id, 0 for a plain one) and the styles its levels are linked to. */
    class Parsed(val byNum: Map<String, Pair<Int, ListDef>>, val levelStyles: Map<String, Map<String, Int>>) {
        /** The paragraph kind (bullets or numbers) of [numId]'s [level]. */
        fun kindOf(numId: String?, level: Int): ListKind? {
            val def = byNum[numId]?.second ?: return null
            return if (def.level(level).format == NumFormat.BULLET) ListKind.BULLET else ListKind.NUMBER
        }
    }

    private class Lvl {
        var format: NumFormat? = null; var text: String? = null; var start: Int? = null; var font: String? = null
        var restart: Int? = null; var legal = false; var style: String? = null
        fun into(base: ListLevel) = ListLevel(format ?: base.format, text ?: base.text, start ?: base.start, font ?: base.font, restart ?: base.restartAfter, legal || base.legal)
    }

    private class Abs(val id: String) {
        var name: String? = null
        val levels = HashMap<Int, Lvl>()
    }

    private class Num(val id: String) {
        var abs: String? = null
        val starts = HashMap<Int, Int>()
        val levels = HashMap<Int, Lvl>()
    }

    fun parse(bytes: ByteArray): Parsed {
        val parser = XmlPull.newParser(bytes)
        val abstracts = LinkedHashMap<String, Abs>()
        val nums = ArrayList<Num>()
        var abs: Abs? = null
        var num: Num? = null
        var lvl: Lvl? = null
        var overrideLevel = -1
        var inRPr = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == Ooxml.W) {
                val v = parser.getAttributeValue(Ooxml.W, "val")
                when (parser.name) {
                    "abstractNum" -> abs = Abs(parser.getAttributeValue(Ooxml.W, "abstractNumId") ?: "").also { abstracts[it.id] = it }
                    "name" -> if (lvl == null) abs?.name = v
                    "num" -> num = Num(parser.getAttributeValue(Ooxml.W, "numId") ?: "").also { nums.add(it) }
                    "abstractNumId" -> num?.abs = v
                    "lvlOverride" -> overrideLevel = parser.getAttributeValue(Ooxml.W, "ilvl")?.toIntOrNull() ?: -1
                    "startOverride" -> if (num != null && overrideLevel >= 0) v?.toIntOrNull()?.let { num!!.starts[overrideLevel] = it }
                    "lvl" -> {
                        val i = parser.getAttributeValue(Ooxml.W, "ilvl")?.toIntOrNull() ?: overrideLevel.coerceAtLeast(0)
                        lvl = Lvl().also { l -> if (num != null) num!!.levels[i] = l else abs?.levels?.set(i, l) }
                    }
                    "start" -> lvl?.start = v?.toIntOrNull()
                    "numFmt" -> lvl?.format = formatOf(v)
                    "lvlText" -> lvl?.text = v ?: ""
                    "lvlRestart" -> lvl?.restart = v?.toIntOrNull()?.let { if (it == 0) ListLevel.NEVER else it - 1 }
                    "isLgl" -> lvl?.legal = v == null || v == "1" || v == "true" || v == "on"
                    "pStyle" -> lvl?.style = v
                    "rPr" -> inRPr = true
                    "rFonts" -> if (inRPr) lvl?.font = parser.getAttributeValue(Ooxml.W, "ascii") ?: parser.getAttributeValue(Ooxml.W, "hAnsi")
                }
            } else if (event == XmlPullParser.END_TAG && parser.namespace == Ooxml.W) {
                when (parser.name) {
                    "abstractNum" -> abs = null
                    "num" -> num = null
                    "lvl" -> lvl = null
                    "lvlOverride" -> overrideLevel = -1
                    "rPr" -> inRPr = false
                }
            }
            event = parser.next()
        }
        fun defOf(a: Abs?, n: Num?): ListDef {
            val first = n?.levels?.get(0)?.format ?: a?.levels?.get(0)?.format
            val base = if (first == NumFormat.BULLET) ListDef.BULLETS else ListDef.NUMBERS
            return ListDef(List(9) { i ->
                var l = a?.levels?.get(i)?.into(base.level(i)) ?: base.level(i)
                n?.levels?.get(i)?.let { l = it.into(l) }
                n?.starts?.get(i)?.let { l = l.copy(start = it) }
                l
            }, a?.name)
        }
        val byNum = HashMap<String, Pair<Int, ListDef>>()
        val byAbstract = HashMap<String, Int>()
        var next = 1
        for (n in nums) {
            val a = abstracts[n.abs]
            val def = defOf(a, n)
            val id = when {
                a?.name == PLAIN_BULLETS || a?.name == PLAIN_NUMBERS -> 0
                n.starts.isNotEmpty() || n.levels.isNotEmpty() -> next++
                else -> byAbstract.getOrPut(n.abs ?: "") { next++ }
            }
            byNum[n.id] = id to def
        }
        val levelStyles = HashMap<String, Map<String, Int>>()
        for (n in nums) {
            val a = abstracts[n.abs] ?: continue
            val m = a.levels.entries.mapNotNull { (i, l) -> l.style?.let { it to i } }.toMap()
            if (m.isNotEmpty()) levelStyles[n.id] = m
        }
        return Parsed(byNum, levelStyles)
    }

    fun formatOf(v: String?): NumFormat = when (v) {
        null, "decimal", "ordinal", "cardinalText", "ordinalText", "decimalHalfWidth", "arabicAbjad", "hex" -> NumFormat.DECIMAL
        "decimalZero" -> NumFormat.DECIMAL_ZERO
        "lowerLetter" -> NumFormat.LOWER_LETTER
        "upperLetter" -> NumFormat.UPPER_LETTER
        "lowerRoman" -> NumFormat.LOWER_ROMAN
        "upperRoman" -> NumFormat.UPPER_ROMAN
        "chineseCounting", "chineseCountingThousand", "japaneseCounting", "taiwaneseCounting", "taiwaneseCountingThousand",
        "ideographDigital", "japaneseDigitalTenThousand", "koreanCounting", "koreanDigital" -> NumFormat.CHINESE
        "chineseLegalSimplified", "ideographLegalTraditional", "japaneseLegal", "koreanLegal" -> NumFormat.CHINESE_LEGAL
        "ideographTraditional" -> NumFormat.HEAVENLY_STEMS
        "ideographZodiac", "ideographZodiacTraditional" -> NumFormat.EARTHLY_BRANCHES
        "decimalEnclosedCircle", "decimalEnclosedCircleChinese", "ideographEnclosedCircle" -> NumFormat.CIRCLED
        "decimalEnclosedParen" -> NumFormat.PARENTHESIZED
        "decimalEnclosedFullstop" -> NumFormat.FULL_STOP
        "decimalFullWidth", "decimalFullWidth2" -> NumFormat.FULL_WIDTH
        "bullet" -> NumFormat.BULLET
        "none" -> NumFormat.NONE
        else -> NumFormat.DECIMAL
    }

    fun wordFormat(f: NumFormat): String = when (f) {
        NumFormat.DECIMAL -> "decimal"
        NumFormat.DECIMAL_ZERO -> "decimalZero"
        NumFormat.LOWER_LETTER -> "lowerLetter"
        NumFormat.UPPER_LETTER -> "upperLetter"
        NumFormat.LOWER_ROMAN -> "lowerRoman"
        NumFormat.UPPER_ROMAN -> "upperRoman"
        NumFormat.CHINESE -> "chineseCounting"
        NumFormat.CHINESE_LEGAL -> "chineseLegalSimplified"
        NumFormat.HEAVENLY_STEMS -> "ideographTraditional"
        NumFormat.EARTHLY_BRANCHES -> "ideographZodiac"
        NumFormat.CIRCLED -> "decimalEnclosedCircle"
        NumFormat.PARENTHESIZED -> "decimalEnclosedParen"
        NumFormat.FULL_STOP -> "decimalEnclosedFullstop"
        NumFormat.FULL_WIDTH -> "decimalFullWidth"
        NumFormat.BULLET -> "bullet"
        NumFormat.NONE -> "none"
    }

    /**
     * How a document's lists are written: each paragraph's numId, and numbering.xml. A list of the app's
     * is one `w:num`; the plain numbers start over after anything else, so each run of them is a `w:num`
     * of its own restarting at 1 (one numId would count on through the whole document in Word).
     */
    class Plan(doc: Document) {
        private val ids = java.util.IdentityHashMap<Paragraph, Int>()
        private val used = sortedSetOf<Int>()
        private var plainRuns = 0

        init {
            var runKind = ListKind.NONE
            var runId = 0
            for (p in paragraphs(doc)) {
                val f = p?.format
                if (f == null || f.listKind == ListKind.NONE) { runKind = ListKind.NONE; continue }
                if (f.listId != 0 && doc.lists[f.listId] != null) { used.add(f.listId); ids[p] = listNum(f.listId); runKind = ListKind.NONE; continue }
                if (f.listKind == ListKind.BULLET) { ids[p] = 1; runKind = ListKind.BULLET; continue }
                if (runKind != ListKind.NUMBER) { runKind = ListKind.NUMBER; runId = if (plainRuns++ == 0) 2 else RESTART + plainRuns }
                ids[p] = runId
            }
        }

        private val lists = doc.lists

        /** The numId of [p], a list item (one this plan did not see — a header's, a note's — by its kind). */
        fun numIdOf(p: Paragraph): Int = ids[p] ?: (p.format.listId.takeIf { it != 0 && lists[it] != null }?.let { listNum(it) }
            ?: if (p.format.listKind == ListKind.BULLET) 1 else 2)

        private fun listNum(listId: Int) = LIST_BASE + listId

        fun xml(): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
            append("""<w:numbering xmlns:w="${Ooxml.W}">""")
            abstract(0, ListDef.BULLETS.copy(name = PLAIN_BULLETS))
            abstract(1, ListDef.NUMBERS.copy(name = PLAIN_NUMBERS))
            val all = (used + lists.keys.filter { it != 0 }).toSortedSet()
            for (id in all) abstract(1 + id, lists[id]!!)
            append("""<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>""")
            append("""<w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>""")
            for (k in 2..plainRuns) {
                append("""<w:num w:numId="${RESTART + k}"><w:abstractNumId w:val="1"/>""")
                for (i in 0 until 9) append("""<w:lvlOverride w:ilvl="$i"><w:startOverride w:val="1"/></w:lvlOverride>""")
                append("</w:num>")
            }
            for (id in all) append("""<w:num w:numId="${listNum(id)}"><w:abstractNumId w:val="${1 + id}"/></w:num>""")
            append("</w:numbering>")
        }

        private fun StringBuilder.abstract(id: Int, def: ListDef) {
            append("""<w:abstractNum w:abstractNumId="$id"><w:multiLevelType w:val="${if (def.levels.drop(1).any { "%1" in it.text }) "multilevel" else "hybridMultilevel"}"/>""")
            def.name?.let { append("""<w:name w:val="${Ooxml.escape(it)}"/>""") }
            def.levels.take(9).forEachIndexed { i, l ->
                append("""<w:lvl w:ilvl="$i"><w:start w:val="${l.start}"/><w:numFmt w:val="${wordFormat(l.format)}"/>""")
                when (l.restartAfter) {
                    ListLevel.NEVER -> append("""<w:lvlRestart w:val="0"/>""")
                    -1 -> Unit
                    else -> append("""<w:lvlRestart w:val="${l.restartAfter + 1}"/>""")
                }
                if (l.legal) append("<w:isLgl/>")
                append("""<w:lvlText w:val="${Ooxml.escape(l.text)}"/><w:lvlJc w:val="left"/>""")
                l.font?.let { f -> append("""<w:rPr><w:rFonts w:ascii="${Ooxml.escape(f)}" w:hAnsi="${Ooxml.escape(f)}" w:hint="default"/></w:rPr>""") }
                append("</w:lvl>")
            }
            append("</w:abstractNum>")
        }
    }

    private const val RESTART = 1000
    private const val LIST_BASE = 2000

    /** The document's body and tables' paragraphs in reading order, null between (see [PageMetrics.listLabels]). */
    private fun paragraphs(doc: Document): Sequence<Paragraph?> = sequence {
        for (b in doc.blocks) when (b) {
            is com.therealityteam.aeropages.model.ParagraphBlock -> yield(b.paragraph)
            is com.therealityteam.aeropages.model.TableBlock -> {
                yield(null)
                for (row in b.table.rows) for (cell in row.cells) { cell.content.forEach { yield(it) }; yield(null) }
            }
            else -> yield(null)
        }
    }
}
