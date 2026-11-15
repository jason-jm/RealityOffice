package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.rtf.RtfReader
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.PageMetrics
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rich Text Format as Word, WPS and WordPad write it: code pages, Unicode, formatting, lists, tables, pictures, fields, notes. */
class RtfReaderTest {

    private fun read(rtf: String): Document = RtfReader.read(rtf.toByteArray(Charsets.ISO_8859_1))
    private fun paras(d: Document) = d.blocks.filterIsInstance<ParagraphBlock>().map { it.paragraph }

    /** The header of a Chinese document as WPS writes it: GBK (code page 936), a Chinese font named in its own code page. */
    private val head = """{\rtf1\ansi\ansicpg936\deff0{\fonttbl{\f0\froman\fcharset0 Times New Roman;}{\f1\fnil\fcharset134 \'cb\'ce\'cc\'e5;}{\f2\fnil\fcharset2 Wingdings;}}""" +
        """{\colortbl;\red255\green0\blue0;\red0\green0\blue255;}{\stylesheet{\s0 Normal;}{\s1\outlinelevel0 heading 1;}}""" +
        """{\*\generator WPS Office;}{\info{\title Secret}{\author Someone}}"""

    @Test
    fun textInItsCodePageAndInUnicode() {
        val d = read(head + """\pard\plain\f1\'d0\'ad\'d2\'e9\par\pard\uc1\u21327?\u35758?\par\pard\plain Caf\'e9 caf\'e9\par}""")
        val p = paras(d)
        assertEquals(listOf("协议", "协议", "Café café"), p.map { it.plainText() }.take(2) + p[2].plainText().let { listOf(it) })
        // The font table, the style sheet, the generator and the document information are not text.
        val all = p.joinToString(" ") { it.plainText() }
        assertFalse(all, all.contains("Times") || all.contains("Normal") || all.contains("WPS") || all.contains("Secret"))
        assertEquals("宋体", p[0].runs[0].format.fontFamily)
    }

    @Test
    fun characterAndParagraphFormatting() {
        val d = read(head + """\pard\qc\li720\fi-360\sb120\sa240\sl360\slmult1\plain\fs32\b Bold \i both\b0\i0  \cf1 red\cf0 {\ul under}{\strike gone}{\super 2}{\v hidden}\line next\tab tab\par""" +
            """\pard\s1\outlinelevel0 Title one\par}""")
        val p = paras(d)
        val first = p[0]
        assertEquals("Bold both redundergone2\nnext\ttab", first.plainText())
        assertEquals(TextAlign.CENTER, first.format.align)
        assertEquals(36f, first.format.indentLeftPt); assertEquals(-18f, first.format.firstLineIndentPt)
        assertEquals(6f, first.format.spaceBeforePt); assertEquals(12f, first.format.spaceAfterPt); assertEquals(1.5f, first.format.lineSpacing)
        val runs = first.runs
        assertTrue(runs[0].format.bold && runs[0].format.fontSizePt == 16f)
        assertTrue(runs.first { it.text.contains("both") }.format.let { it.bold && it.italic })
        assertEquals(0xFFFF0000, runs.first { it.text == "red" }.format.color)
        assertTrue(runs.first { it.text == "under" }.format.underline)
        assertTrue(runs.first { it.text == "gone" }.format.strikethrough)
        assertTrue(runs.first { it.text == "2" }.format.superscript)
        assertEquals(ParagraphStyle.HEADING1, p[1].format.style)
    }

    @Test
    fun listsTablesPicturesLinksNotesAndHeaders() {
        val png = "89504e470d0a1a0a0000000d4948445200000002000000010802000000"
        val rtf = head +
            """{\*\listtable{\list\listtemplateid1{\listlevel\levelnfc37\levelstartat1{\leveltext\'02\'00\'a1\'a2;}{\levelnumbers\'01;}}""" +
            """{\listlevel\levelnfc0\levelstartat1{\leveltext\'04\'00.\'01.;}{\levelnumbers\'01\'03;}}{\listname ;}\listid100}""" +
            """{\list\listtemplateid2{\listlevel\levelnfc23{\leveltext\'01\u-3880 ?;}{\levelnumbers;}\f2}{\listname ;}\listid200}}""" +
            """{\*\listoverridetable{\listoverride\listid100\listoverridecount0\ls1}{\listoverride\listid200\listoverridecount0\ls2}}""" +
            """{\header \pard\plain Company\par}{\footer \pard\plain Page\par}""" +
            """\pard\ls1\ilvl0{\listtext 一、\tab}First\par\pard\ls1\ilvl1 Sub\par\pard\ls1\ilvl0 Second\par""" +
            """\pard\ls2 Point\par""" +
            """\pard\plain See {\field{\*\fldinst HYPERLINK "https://example.com/"}{\fldrslt example}} here{\super\chftn}{\footnote\pard\plain{\super\chftn} The note.}\par""" +
            """\trowd\clvmgf\cellx2000\cellx4000\pard\intbl A\cell B\cell\row""" +
            """\trowd\clvmrg\cellx2000\clbrdrt\brdrs\cellx4000\pard\intbl\cell D\cell\row""" +
            """\pard After\par""" +
            """{\pict\pngblip\picw2\pich1\picwgoal1440\pichgoal720 $png}\par}"""
        val d = read(rtf)
        val m = PageMetrics.listLabels(d)
        val p = paras(d)
        assertEquals(listOf("一、 First", "一.1. Sub", "二、 Second"), p.take(3).map { "${m[it]} ${it.plainText()}" })
        assertEquals(ListKind.BULLET, p[3].format.listKind)
        assertEquals("➢", m[p[3]])
        val link = p[4]
        assertEquals("See example here[1]", link.plainText())
        assertEquals("https://example.com/", link.runs.first { it.text == "example" }.format.link)
        assertNull(link.runs.first { it.text.startsWith(" here") }.format.link)
        assertEquals(listOf("The note."), d.footnotes.map { it.text })
        assertEquals("Company", d.header?.plainText()); assertEquals("Page", d.footer?.plainText())
        val t = d.blocks.filterIsInstance<TableBlock>().single().table
        assertEquals(listOf("A", "B"), t.rows[0].cells.map { c -> c.content.joinToString { it.plainText() } })
        assertEquals("D", t.rows[1].cells[1].content.joinToString { it.plainText() })
        assertEquals(2, t.rows[0].cells[0].rowSpan); assertTrue(t.rows[1].cells[0].vMerged)
        assertTrue(t.borders)
        assertEquals(listOf(1f, 1f), t.columnWidths.map { it / 2000f })
        assertEquals("After", paras(d).last().plainText().ifEmpty { paras(d).dropLast(1).last().plainText() })
        val img = d.blocks.filterIsInstance<ImageBlock>().single()
        assertEquals("image/png", img.mime); assertEquals(72f, img.widthPt); assertEquals(36f, img.heightPt)
    }
}
