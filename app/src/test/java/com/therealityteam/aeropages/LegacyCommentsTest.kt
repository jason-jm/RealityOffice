package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.rtf.RtfReader
import com.therealityteam.aeropages.model.Bookmarks
import com.therealityteam.aeropages.model.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Comments in the legacy formats: Word 97's annotations (.doc: the authors, the texts, the ranges they are on) and RTF's
 * (`\atnauthor`, `\annotation`, `\atrfstart` … `\atrfend`). Both files are LibreOffice's from one ODT: "final" commented by
 * Ann, "plan is final. … Budget" by Bob, a comment on the empty last line.
 */
class LegacyCommentsTest {
    private fun rangeText(d: Document, f: Bookmarks.Found): String {
        val paras = d.locations().map { it.paragraph.plainText() }
        if (f.startPara == f.endPara) return paras[f.startPara].substring(f.start, f.end)
        return (listOf(paras[f.startPara].substring(f.start)) + paras.subList(f.startPara + 1, f.endPara) + paras[f.endPara].substring(0, f.end)).joinToString("|")
    }

    private fun ranges(d: Document) = Bookmarks.commentRanges(d).mapKeys { (id, _) -> d.comments.first { it.id == id }.text }.mapValues { rangeText(d, it.value) }

    private fun paragraphOf(d: Document, text: String) = d.locations().map { it.paragraph }.first { it.plainText() == text }

    private fun checkCommon(d: Document) {
        assertEquals(listOf("The plan is final.", "Budget follows.", ""), d.locations().map { it.paragraph.plainText() })
        assertEquals(listOf("Ann" to "Is it?", "Bob" to "Yes.", "Bob" to "Long one", "Bob" to "On the empty line"), d.comments.map { it.author to it.text })
        assertEquals(mapOf("Is it?" to "final", "Long one" to "plan is final.|Budget"), ranges(d))
        // Each comment on the paragraph its range starts in (or that its mark is in): the empty line's on the empty line.
        val first = paragraphOf(d, "The plan is final.").id
        assertEquals(listOf(first, first, first), d.comments.take(3).map { it.targetId })
        assertEquals(paragraphOf(d, "").id, d.comments[3].targetId)
        // The ranges are no bookmarks.
        assertTrue(Bookmarks.of(d).isEmpty())
    }

    @Test
    fun wordsAnnotationsAreRead() {
        val d = File("src/test/resources/doc/comments.doc").inputStream().use { DocReader.read(it) }
        checkCommon(d)
    }

    @Test
    fun rtfsAnnotationsAreRead() {
        val d = RtfReader.read(File("src/test/resources/rtf/comments.rtf").readBytes())
        checkCommon(d)
        // \atndate is a DTTM.
        val at = LocalDateTime.of(2026, 10, 14, 21, 4).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertTrue(d.comments.all { it.dateMs == at })
    }

    @Test
    fun anRtfCommentWithoutARangeIsOnItsParagraph() {
        val rtf = """{\rtf1\ansi{\*\revtbl{Unknown;}}First {\*\atnid A}{\*\atnauthor Ann}\chatn{\*\annotation{\*\atndate 0}\pard Note one.}line.\par Second line.{\*\atnid B}{\*\atnauthor Bo}\chatn{\*\annotation\pard Two\par paragraphs}\par}"""
        val d = RtfReader.read(rtf.toByteArray())
        assertEquals(listOf("First line.", "Second line."), d.locations().map { it.paragraph.plainText() })
        assertEquals(listOf("Ann" to "Note one.", "Bo" to "Two\nparagraphs"), d.comments.map { it.author to it.text })
        assertEquals(d.locations().map { it.paragraph.id }, d.comments.map { it.targetId })
        assertTrue(Bookmarks.commentRanges(d).isEmpty())
    }
}
