package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.rtf.RtfReader
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.RevisionMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Tracked changes in RTF (`\revtbl`, `\deleted`, `\revised`, their authors and DTTMs, `\crauth`), which the reader used to leave out. */
class RtfRevisionsTest {
    private fun texts(d: Document) = d.paragraphs().map { it.plainText() }
    private fun accepted(d: Document) = DocumentEditor().apply { load(d.copyOf()); acceptAllChanges() }.document
    private fun rejected(d: Document) = DocumentEditor().apply { load(d.copyOf()); rejectAllChanges() }.document

    @Test
    fun libreOfficesRtfOfACompareResultKeepsItsChanges() {
        // LibreOffice's RTF of our Compare Result (compare-result.docx).
        val d = RtfReader.read(File("src/test/resources/rtf/revisions.rtf").readBytes())
        val sales = d.paragraphs().first { it.plainText().startsWith("Sales grew") }
        assertEquals("north", sales.runs.filter { it.format.deleted }.joinToString("") { it.text })
        assertEquals("strongly south", sales.runs.filter { it.format.inserted }.joinToString(" ") { it.text.trim() })
        assertTrue(sales.runs.filter { it.format.deleted || it.format.inserted }.all { it.format.revAuthor == "Reviewer" && it.format.revDate != null })
        assertTrue(d.paragraphs().first { it.plainText() == "Costs stayed flat." }.runs.all { it.format.deleted })
        assertEquals(listOf("Quarterly review", "Sales grew strongly in every region this quarter, led by the south.",
            "The new store opened in August and is ahead of plan.", "We expect a stronger next quarter.", "Hiring starts in January."),
            texts(accepted(d)).filter { it.isNotEmpty() })
        assertEquals("Sales grew in every region this quarter, led by the north.", texts(rejected(d))[1])
    }

    @Test
    fun aDeletedParagraphMarkAndAFormatChange() {
        val dttm = 33 or (3 shl 6) or (25 shl 11) or (10 shl 16) or (126 shl 20)
        val rtf = """{\rtf1\ansi{\fonttbl{\f0 Arial;}}{\*\revtbl {Unknown;}{Ann;}{Bo;}}
            |\pard Kept\par
            |\pard{\deleted\revauthdel1\revdttmdel$dttm Gone\par}
            |\pard After {\b\crauth2\crdate$dttm bold}\par}""".trimMargin()
        val d = RtfReader.read(rtf.toByteArray())
        assertEquals(listOf("Kept", "Gone", "After bold"), texts(d))
        val gone = d.paragraphs()[1]
        assertTrue(gone.runs.all { it.format.deleted && it.format.revAuthor == "Ann" })
        assertEquals(RevisionMark(false, "Ann", DocReader.dttm(dttm)), gone.format.revisionMark)
        val bold = d.paragraphs()[2].runs.first { it.text == "bold" }.format
        assertTrue(bold.bold && bold.formatChanged); assertEquals("Bo", bold.revAuthor)
        assertEquals(listOf("Kept", "After bold"), texts(accepted(d)))
        assertEquals(listOf("Kept", "Gone", "After bold"), texts(rejected(d)))
    }
}
