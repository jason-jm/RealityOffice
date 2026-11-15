package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.BookmarkMark
import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.PAGE_BREAK
import com.therealityteam.aeropages.model.PageBreaks
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Word's page breaks inside a paragraph become the page-break paragraphs layout breaks pages at. */
class PageBreaksTest {
    private fun texts(blocks: List<Block>) = blocks.map { (it as ParagraphBlock).paragraph.plainText().replace(PAGE_BREAK, "⏎") }

    @Test
    fun aParagraphSplitsAtItsBreaks() {
        val p = Paragraph(mutableListOf(TextRun("Cover"), TextRun("${PAGE_BREAK}Chapter one"), TextRun(" goes on")), ParagraphFormat(listKind = ListKind.BULLET))
        p.bookmarks.add(BookmarkMark("ch1", 6, false))
        val blocks = mutableListOf<Block>(ParagraphBlock(p), ParagraphBlock(Paragraph.of("after")))
        PageBreaks.split(blocks)
        assertEquals(listOf("Cover", "⏎", "Chapter one goes on", "after"), texts(blocks))
        val second = (blocks[2] as ParagraphBlock).paragraph
        assertEquals("the list marker stays on the first part", ListKind.NONE, second.format.listKind)
        assertEquals(listOf("ch1" to 0), second.bookmarks.map { it.name to it.offset })
        assertTrue((blocks[1] as ParagraphBlock).paragraph.isPageBreak())
        // A paragraph holding only a break (Word's Ctrl+Enter at a line's start) is just the break and what follows.
        val only = mutableListOf<Block>(ParagraphBlock(Paragraph.of("${PAGE_BREAK}Next page")))
        PageBreaks.split(only)
        assertEquals(listOf("⏎", "Next page"), texts(only))
    }

    /** ODT keeps a page break as LibreOffice does: a break before the next paragraph (it used to be lost — written as an empty paragraph). */
    @Test
    fun odtKeepsThePageBreak() {
        val doc = com.therealityteam.aeropages.model.Document().apply {
            listOf("Before", PAGE_BREAK, "After", PAGE_BREAK).forEach { blocks.add(ParagraphBlock(Paragraph.of(it))) }
        }
        val bytes = java.io.ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdtWriter.write(doc, it) }.toByteArray()
        val content = java.util.zip.ZipInputStream(bytes.inputStream()).use { z ->
            generateSequence { z.nextEntry }.first { it.name == "content.xml" }.let { z.readBytes().decodeToString() }
        }
        assertTrue(content.contains("""fo:break-before="page""""))
        assertEquals("no empty paragraph left where the first break was", 3, Regex("<text:p ").findAll(content).count())
        val back = com.therealityteam.aeropages.format.odf.OdtReader.read(bytes).paragraphs()
        assertEquals(listOf("Before", "After", ""), back.map { it.plainText() })
        assertTrue("After starts a page", back[1].format.pageBreakBefore)
        assertTrue("so does the empty paragraph after the last break", back[2].format.pageBreakBefore)
    }
}
