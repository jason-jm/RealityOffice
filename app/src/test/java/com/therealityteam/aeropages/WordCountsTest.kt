package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.WordCounts
import org.junit.Assert.assertEquals
import org.junit.Test

/** Word's 字数: each East Asian character a word, other words between spaces. */
class WordCountsTest {
    @Test
    fun chineseCharactersAndPunctuationCountOneEachOtherWordsBetweenSpaces() {
        assertEquals(WordCounts.Counts(0, 4), WordCounts.of("The quick brown fox"))
        // 本季度收入 5 chars + full-width comma + 增长 2 + full-width full stop = 9; "Q3" and "12%" are two words.
        assertEquals(WordCounts.Counts(9, 2), WordCounts.of("本季度收入，Q3 增长 12%。"))
        // Japanese kana count by character; an ideographic space separates but is not counted.
        assertEquals(WordCounts.Counts(5, 0), WordCounts.of("ありがとう　"))
        // A word against Chinese text ends there; hyphenated and apostrophised words stay one.
        assertEquals(WordCounts.Counts(4, 2), WordCounts.of("使用AeroDesk编辑 don't"))
        assertEquals(WordCounts.Counts(0, 0), WordCounts.of("   \u000C  "))
    }

    @Test
    fun theEditorCountsTheDocumentAndTheSelection() {
        val d = DocumentEditor()
        d.load(Document(mutableListOf(ParagraphBlock(Paragraph.of("季度报告 for 2026")), ParagraphBlock(Paragraph.of("Second line")))))
        assertEquals("4 characters, \"for\", \"2026\", \"Second\", \"line\"", 8, d.wordCount())
        assertEquals(4, d.cjkCount())
        d.setSelection(0, 0, 2)
        assertEquals(2, d.selectedWordCount())
    }
}
