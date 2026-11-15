package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdfLists
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ListDef
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.ListLevel
import com.therealityteam.aeropages.model.Lists
import com.therealityteam.aeropages.model.NumFormat
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Lists numbered as Word and LibreOffice number them: each level's own format and label ("1.1.",
 * "（一）", "①"), lists that count on through other paragraphs, restarts, headings numbered by their
 * style or the outline — read from Word's numbering and ODF's list styles and written back.
 */
class ListNumberingTest {

    private fun item(text: String, id: Int, level: Int = 0, kind: ListKind = ListKind.NUMBER) =
        Paragraph.of(text, pFormat = ParagraphFormat(listKind = kind, listLevel = level, listId = id))

    private fun labels(d: Document): List<String> {
        val m = PageMetrics.listLabels(d)
        return d.locations().map { it.paragraph }.mapNotNull { p -> m[p]?.let { "$it ${p.plainText()}" } }
    }

    @Test
    fun numbersInEachFormat() {
        assertEquals("十", Lists.number(10, NumFormat.CHINESE))
        assertEquals("十二", Lists.number(12, NumFormat.CHINESE))
        assertEquals("二十", Lists.number(20, NumFormat.CHINESE))
        assertEquals("一百零五", Lists.number(105, NumFormat.CHINESE))
        assertEquals("壹拾贰", Lists.number(12, NumFormat.CHINESE_LEGAL))
        assertEquals("丙", Lists.number(3, NumFormat.HEAVENLY_STEMS))
        assertEquals("③", Lists.number(3, NumFormat.CIRCLED))
        assertEquals("㉑", Lists.number(21, NumFormat.CIRCLED))
        assertEquals("AA", Lists.number(27, NumFormat.UPPER_LETTER))
        assertEquals("xiv", Lists.number(14, NumFormat.LOWER_ROMAN))
        assertEquals("１２", Lists.number(12, NumFormat.FULL_WIDTH))
        assertEquals("09", Lists.number(9, NumFormat.DECIMAL_ZERO))
        // Symbol's and Wingdings' bullets as the characters they draw.
        assertEquals("•", Lists.shownBullet("", "Symbol"))
        assertEquals("➢", Lists.shownBullet("", "Wingdings"))
        assertEquals("■", Lists.shownBullet("n", "Wingdings"))
    }

    @Test
    fun levelsCountAndRestartUnderTheirParent() {
        val d = Document(mutableListOf())
        d.lists[1] = ListDef.CHINESE
        listOf(item("总则", 1), item("目的", 1, 1), item("范围", 1, 1), item("细则", 1, 2), item("职责", 1),
            item("部门", 1, 1)).forEach { d.blocks.add(ParagraphBlock(it)) }
        // Another paragraph and a plain list between do not stop the list counting on.
        d.blocks.add(1, ParagraphBlock(Paragraph.of("正文")))
        assertEquals(listOf("一、 总则", "（一） 目的", "（二） 范围", "1. 细则", "二、 职责", "（一） 部门"), labels(d))
        // Outline numbering: the levels above in each label; legal numbering shows them in decimal.
        val o = Document(mutableListOf())
        o.lists[1] = ListDef.OUTLINE
        listOf(item("A", 1), item("B", 1, 1), item("C", 1, 2), item("D", 1, 1), item("E", 1)).forEach { o.blocks.add(ParagraphBlock(it)) }
        assertEquals(listOf("1. A", "1.1. B", "1.1.1. C", "1.2. D", "2. E"), labels(o))
        o.lists[1] = ListDef(listOf(ListLevel(NumFormat.UPPER_ROMAN, "%1."), ListLevel(NumFormat.LOWER_LETTER, "%1.%2", legal = true)) + List(7) { ListLevel() })
        assertEquals("1.1 B", labels(o)[1])
        // A level that never restarts counts on under the next parent.
        o.lists[1] = ListDef(listOf(ListLevel(), ListLevel(NumFormat.DECIMAL, "(%2)", restartAfter = ListLevel.NEVER)) + List(7) { ListLevel() })
        o.blocks.add(ParagraphBlock(item("F", 1, 1)))
        assertEquals("(3) F", labels(o)[5])
        // The marker column grows to hold a long label.
        val f = ParagraphFormat(listKind = ListKind.NUMBER, indentLeftPt = 18f, firstLineIndentPt = -18f)
        assertEquals(18f, PageMetrics.markerWidth(f, "1.", 12f))
        assertTrue(PageMetrics.markerWidth(f, "1.1.1.", 12f) > 18f)
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name, body) -> z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() } }
        return out.toByteArray()
    }

    private val w = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""

    private fun p(text: String, num: String? = null, ilvl: Int? = null, style: String? = null): String {
        val numPr = if (num != null || ilvl != null) "<w:numPr>${ilvl?.let { """<w:ilvl w:val="$it"/>""" }.orEmpty()}${num?.let { """<w:numId w:val="$it"/>""" }.orEmpty()}</w:numPr>" else ""
        val st = style?.let { """<w:pStyle w:val="$it"/>""" }.orEmpty()
        return """<w:p><w:pPr>$st$numPr</w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"""
    }

    /**
     * Word's numbering: two w:nums of one definition (counting on together), a third restarting it, a
     * Chinese list, and headings numbered by their styles (the levels linked to the styles).
     */
    private fun wordFile(): ByteArray {
        val numbering = """<w:numbering $w>""" +
            """<w:abstractNum w:abstractNumId="10"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1)"/></w:lvl>""" +
            """<w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/></w:lvl></w:abstractNum>""" +
            """<w:abstractNum w:abstractNumId="20"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="chineseCounting"/><w:lvlText w:val="%1、"/></w:lvl>""" +
            """<w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="chineseCounting"/><w:lvlText w:val="（%2）"/></w:lvl></w:abstractNum>""" +
            """<w:abstractNum w:abstractNumId="30"><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:pStyle w:val="Heading1"/><w:lvlText w:val="第%1章"/></w:lvl>""" +
            """<w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:pStyle w:val="Heading2"/><w:lvlText w:val="%1.%2"/></w:lvl></w:abstractNum>""" +
            """<w:abstractNum w:abstractNumId="40"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="&#xF0D8;"/><w:rPr><w:rFonts w:ascii="Wingdings" w:hAnsi="Wingdings"/></w:rPr></w:lvl></w:abstractNum>""" +
            """<w:num w:numId="1"><w:abstractNumId w:val="10"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="10"/></w:num>""" +
            """<w:num w:numId="3"><w:abstractNumId w:val="10"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/></w:lvlOverride></w:num>""" +
            """<w:num w:numId="4"><w:abstractNumId w:val="20"/></w:num><w:num w:numId="5"><w:abstractNumId w:val="30"/></w:num><w:num w:numId="6"><w:abstractNumId w:val="40"/></w:num>""" +
            """</w:numbering>"""
        val styles = """<w:styles $w>""" +
            """<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:pPr><w:numPr><w:numId w:val="5"/></w:numPr><w:outlineLvl w:val="0"/></w:pPr></w:style>""" +
            """<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Heading1"/><w:pPr><w:numPr><w:ilvl w:val="1"/></w:numPr><w:outlineLvl w:val="1"/></w:pPr></w:style>""" +
            """</w:styles>"""
        val body = listOf(
            p("总论", style = "Heading1"), p("背景", style = "Heading2"), p("目标", style = "Heading2"),
            p("one", "1"), p("sub", "1", 1), p("text"), p("two", "2"), p("again", "3"),
            p("甲", "4"), p("乙", "4", 1), p("丙", "4"),
            p("方法", style = "Heading1"), p("arrow", "6"), p("unnumbered", "0", style = "Heading1"),
        ).joinToString("")
        return zip(mapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?><w:document $w><w:body>$body</w:body></w:document>""",
            "word/numbering.xml" to numbering, "word/styles.xml" to styles,
        ))
    }

    private val wordLabels = listOf("第1章 总论", "1.1 背景", "1.2 目标", "1) one", "a) sub", "2) two", "1) again", "一、 甲", "（一） 乙", "二、 丙", "第2章 方法", "➢ arrow")

    @Test
    fun wordsNumberingIsRead() {
        val d = DocxReader.read(ByteArrayInputStream(wordFile()))
        assertEquals(wordLabels, labels(d))
        val p = d.locations().map { it.paragraph }
        assertEquals(ListKind.NONE, p.last().format.listKind)
        assertEquals(ListKind.BULLET, p[12].format.listKind)
    }

    /**
     * With `-Daero.listsOut=<folder>`: the Word file as the app writes it (lists.docx) and the ODF one (lists.odt), for
     * LibreOffice to show its labels and to make the .doc fixture (`resources/doc/lists.doc`) from.
     */
    @Test
    fun writesTheFilesForLibreOffice() {
        val dir = System.getProperty("aero.listsOut")?.let { java.io.File(it) } ?: return
        val d = DocxReader.read(ByteArrayInputStream(wordFile()))
        java.io.File(dir, "lists.docx").outputStream().use { DocxWriter.write(d, it) }
        java.io.File(dir, "lists.odt").outputStream().use { OdtWriter.write(OdtReader.read(odfFile()), it) }
    }

    @Test
    fun wordsNumberingIsWrittenBack() {
        val d = DocxReader.read(ByteArrayInputStream(wordFile()))
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
        assertEquals(wordLabels, labels(DocxReader.read(ByteArrayInputStream(bytes))))
        // Through ODF: list styles, the parts of one list continuing one another.
        val odt = ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()
        val fromOdt = OdtReader.read(odt)
        assertEquals(wordLabels, labels(fromOdt))
    }

    /** The same lists in Word 97's binary format (LibreOffice's .doc of [wordFile] as the app writes it): LST/LVL, LFO, the styles' lists. */
    @Test
    fun word97ListsAreRead() {
        val d = com.therealityteam.aeropages.format.doc.DocReader.read(javaClass.getResourceAsStream("/doc/lists.doc")!!)
        assertEquals(wordLabels, labels(d))
    }

    @Test
    fun plainNumberedListsRestartInWord() {
        val d = Document(mutableListOf())
        listOf(item("a", 0), item("b", 0), item("c", 0, 1)).forEach { d.blocks.add(ParagraphBlock(it)) }
        d.blocks.add(ParagraphBlock(Paragraph.of("between")))
        listOf(item("x", 0), item("y", 0)).forEach { d.blocks.add(ParagraphBlock(it)) }
        assertEquals(listOf("1. a", "2. b", "a. c", "1. x", "2. y"), labels(d))
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
        val numbering = String(OfficeXml.unzip(bytes)["word/numbering.xml"]!!)
        assertTrue(numbering, numbering.contains("<w:startOverride w:val=\"1\"/>"))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(labels(d), labels(back))
        // Read back as the app's plain lists.
        assertTrue(back.locations().all { it.paragraph.format.listId == 0 })
        val odt = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray())
        assertEquals(labels(d), labels(odt))
        assertTrue(odt.locations().all { it.paragraph.format.listId == 0 })
    }

    private val odfNs = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
        """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
        """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:loext="urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace""""

    /** LibreOffice's way: nested lists, a list continued after a paragraph, chapter numbers from the outline style, a list in a table. */
    private fun odfFile(): ByteArray {
        val styles = """<office:document-styles $odfNs office:version="1.3"><office:styles>""" +
            """<text:outline-style style:name="Outline"><text:outline-level-style text:level="1" style:num-format="1" style:num-suffix="."/>""" +
            """<text:outline-level-style text:level="2" style:num-format="1" text:display-levels="2"/></text:outline-style>""" +
            """<text:list-style style:name="WWNum1"><text:list-level-style-number text:level="1" style:num-prefix="（" style:num-suffix="）" style:num-format="一, 二, 三, ..."/>""" +
            """<text:list-level-style-number text:level="2" style:num-format="①, ②, ③, ..." loext:num-list-format="%2%"/>""" +
            """<text:list-level-style-bullet text:level="3" text:bullet-char="▪"/></text:list-style>""" +
            """</office:styles></office:document-styles>"""
        val content = """<office:document-content $odfNs office:version="1.3"><office:body><office:text>""" +
            """<text:h text:outline-level="1">Intro</text:h><text:h text:outline-level="2">Scope</text:h>""" +
            """<text:list xml:id="l1" text:style-name="WWNum1"><text:list-item><text:p>甲</text:p>""" +
            """<text:list><text:list-item><text:p>子</text:p><text:list><text:list-item><text:p>点</text:p></text:list-item></text:list></text:list-item></text:list></text:list-item>""" +
            """<text:list-item><text:p>乙</text:p></text:list-item></text:list>""" +
            """<text:p>正文</text:p>""" +
            """<text:list text:continue-list="l1" text:style-name="WWNum1"><text:list-item><text:p>丙</text:p></text:list-item></text:list>""" +
            """<text:h text:outline-level="1">Next</text:h>""" +
            """<table:table><table:table-column/><table:table-row><table:table-cell><text:list text:style-name="WWNum1"><text:list-item><text:p>格</text:p></text:list-item></text:list></table:table-cell></table:table-row></table:table>""" +
            """</office:text></office:body></office:document-content>"""
        return zip(mapOf("mimetype" to "application/vnd.oasis.opendocument.text", "content.xml" to content, "styles.xml" to styles))
    }

    private val odfLabels = listOf("1. Intro", "1.1 Scope", "（一） 甲", "① 子", "▪ 点", "（二） 乙", "（三） 丙", "2. Next", "（一） 格")

    @Test
    fun odfListsAreReadNestedAndContinued() {
        val d = OdtReader.read(odfFile())
        assertEquals(odfLabels, labels(d))
        val back = OdtReader.read(ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray())
        assertEquals(odfLabels, labels(back))
        assertEquals(ParagraphStyle.HEADING1, back.locations().first().paragraph.format.style)
        val docx = DocxReader.read(ByteArrayInputStream(ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()))
        assertEquals(odfLabels, labels(docx))
    }

    /** LibreOffice gives Chinese text the `-asian` properties: a heading's size and weight are written for every script. */
    @Test
    fun odfHeadingsLookTheSameInChinese() {
        val d = Document(mutableListOf(ParagraphBlock(Paragraph.of("总则", pFormat = ParagraphFormat(style = ParagraphStyle.HEADING1)))))
        val styles = String(OfficeXml.unzip(ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray())["styles.xml"]!!)
        val heading = Regex("<style:style style:name=\"Heading_20_1\".*?</style:style>").find(styles)!!.value
        assertTrue(heading, heading.contains("style:font-size-asian=") && heading.contains("style:font-weight-asian=\"bold\""))
    }

    @Test
    fun odfLabelsSplitIntoPrefixLevelsAndSuffix() {
        assertEquals(Triple("（", 1, "）"), OdfLists.split("（%2）", 1))
        assertEquals(Triple("", 3, "."), OdfLists.split("%1.%2.%3.", 2))
        assertEquals(Triple("第", 1, "章"), OdfLists.split("第%1章", 0))
    }
}
