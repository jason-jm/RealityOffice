package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.docx.Ooxml
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Review: revisions attributed to reviewers (accept/reject per author or per change) and comment threads, through docx. */
class ReviewTest {

    private fun editorWith(text: String): DocumentEditor {
        val ed = DocumentEditor()
        ed.load(Document.blank())
        ed.setSelection(0, 0, 0)
        ed.replaceText(0, "", text)
        return ed
    }

    @Test
    fun trackedEditsCarryTheReviewerAndCanBeResolvedPerAuthor() {
        val ed = editorWith("hello world")
        ed.changeReviewer("Ann")
        ed.toggleTrackChanges()
        ed.replaceText(0, "hello world", "hello brave world")
        val ins = ed.activeParagraph.runs.first { it.format.inserted }
        assertEquals("brave ", ins.text); assertEquals("Ann", ins.format.revAuthor); assertTrue(ins.format.revDate!! > 0)
        ed.changeReviewer("Bob")
        ed.replaceText(0, "hello brave world", "brave world")
        val del = ed.activeParagraph.runs.first { it.format.deleted }
        assertEquals("hello ", del.text); assertEquals("Bob", del.format.revAuthor)
        assertEquals(listOf("Bob", "Ann"), ed.revisionAuthors())
        val revs = ed.revisions()
        assertEquals(2, revs.size)
        assertEquals(DocumentEditor.RevKind.DELETE, revs[0].kind); assertEquals("hello ", revs[0].text); assertEquals(0, revs[0].start)
        assertEquals(DocumentEditor.RevKind.INSERT, revs[1].kind); assertEquals("brave ", revs[1].text); assertEquals("Ann", revs[1].author)
        // Bob's deletion is accepted; Ann's insertion still pending.
        ed.acceptChangesBy("Bob")
        assertEquals("brave world", ed.activeParagraph.plainText())
        assertEquals(1, ed.revisions().size); assertEquals("Ann", ed.revisions()[0].author)
        ed.rejectChangesBy("Ann")
        assertEquals("world", ed.activeParagraph.plainText())
        assertTrue(ed.revisions().isEmpty())
        assertTrue(ed.activeParagraph.runs.none { it.format.revAuthor != null })
        ed.undo(); ed.undo()
        assertEquals("hello brave world", ed.activeParagraph.plainText()); assertEquals(2, ed.revisions().size)
    }

    @Test
    fun singleRevisionsAcceptAndRejectIndependently() {
        val ed = editorWith("one two three")
        ed.toggleTrackChanges()
        ed.changeReviewer("Ann"); ed.replaceText(0, "one two three", "one 2 two three")
        ed.changeReviewer("Bob"); ed.replaceText(0, "one 2 two three", "one 2 two three!")
        val revs = ed.revisions()
        assertEquals(2, revs.size)
        ed.acceptRevision(revs[0])
        assertEquals("one 2 two three!", ed.activeParagraph.plainText())
        assertEquals(1, ed.revisions().size); assertEquals("!", ed.revisions()[0].text)
        ed.rejectRevision(ed.revisions()[0])
        assertEquals("one 2 two three", ed.activeParagraph.plainText()); assertTrue(ed.revisions().isEmpty())
        // Formatting under track changes is a revision by the current reviewer too.
        ed.setSelection(0, 0, 3); ed.toggleBold()
        val f = ed.revisions().single()
        assertEquals(DocumentEditor.RevKind.FORMAT, f.kind); assertEquals("Bob", f.author); assertEquals("one", f.text)
        ed.acceptRevision(f)
        assertTrue(ed.revisions().isEmpty()); assertTrue(ed.activeParagraph.runs[0].format.bold)
    }

    @Test
    fun docxRoundTripKeepsRevisionAuthorsDatesAndFormatChanges() {
        val doc = Document()
        val p = Paragraph(mutableListOf(
            TextRun("keep ", CharacterFormat()),
            TextRun("added", CharacterFormat(inserted = true, revAuthor = "Ann", revDate = 1_700_000_000_000L)),
            TextRun(" gone", CharacterFormat(deleted = true, revAuthor = "Bob", revDate = 1_700_000_060_000L)),
            TextRun(" bolded", CharacterFormat(bold = true, formatChanged = true, revAuthor = "Cy", revDate = 1_700_000_120_000L)),
        ))
        doc.blocks.add(ParagraphBlock(p))
        val bos = ByteArrayOutputStream()
        DocxWriter.write(doc, bos)
        val bytes = bos.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["word/document.xml"]!!, Charsets.UTF_8)
        assertTrue(xml.contains("""<w:ins w:id="1" w:author="Ann" w:date="2023-11-14T22:13:20Z">"""))
        assertTrue(xml.contains("""<w:del w:id="2" w:author="Bob" w:date="2023-11-14T22:14:20Z">"""))
        assertTrue(xml.contains("""<w:rPrChange w:id="3" w:author="Cy" w:date="2023-11-14T22:15:20Z"><w:rPr/></w:rPrChange>"""))
        val back = DocxReader.read(ByteArrayInputStream(bytes)).blocks.filterIsInstance<ParagraphBlock>().single().paragraph
        val runs = back.runs
        assertEquals("keep added gone bolded", back.plainText())
        assertNull(runs[0].format.revAuthor)
        assertTrue(runs[1].format.inserted); assertEquals("Ann", runs[1].format.revAuthor); assertEquals(1_700_000_000_000L, runs[1].format.revDate)
        assertTrue(runs[2].format.deleted); assertEquals("Bob", runs[2].format.revAuthor); assertEquals(1_700_000_060_000L, runs[2].format.revDate)
        assertTrue(runs[3].format.formatChanged); assertTrue(runs[3].format.bold); assertEquals("Cy", runs[3].format.revAuthor)
        assertFalse("the old properties inside rPrChange must not leak into the run", runs[3].format.italic)
        assertEquals(1_700_000_000_000L, Ooxml.parseDate("2023-11-14T22:13:20Z")); assertEquals(0L, Ooxml.parseDate("1970-01-01T00:00:00")); assertNull(Ooxml.parseDate("soon"))
    }

    @Test
    fun commentThreadsAndResolvedStateRoundTrip() {
        val ed = editorWith("Reviewed paragraph")
        ed.changeReviewer("Ann")
        ed.addComment("Is this right?")
        val parent = ed.document.comments.single()
        assertEquals("Ann", parent.author); assertTrue(parent.dateMs > 0)
        ed.changeReviewer("Bob")
        ed.replyComment(parent, "Yes, checked.")
        val reply = ed.repliesOf(parent).single()
        assertEquals("Bob", reply.author); assertEquals(parent.id, reply.parentId); assertEquals(parent.targetId, reply.targetId)
        ed.replyComment(reply, "Thanks") // replying to a reply stays in the same thread
        assertEquals(2, ed.repliesOf(parent).size)
        ed.toggleResolved(parent)
        assertTrue(parent.resolved)
        val bos = ByteArrayOutputStream()
        DocxWriter.write(ed.document, bos)
        val bytes = bos.toByteArray()
        val parts = OfficeXml.unzip(bytes)
        val ext = String(parts["word/commentsExtended.xml"]!!, Charsets.UTF_8)
        assertTrue(ext.contains("""w15:paraIdParent="""")); assertTrue(ext.contains("""w15:done="1""""))
        assertTrue(String(parts["[Content_Types].xml"]!!, Charsets.UTF_8).contains("commentsExtended+xml"))
        assertTrue(String(parts["word/_rels/document.xml.rels"]!!, Charsets.UTF_8).contains("relationships/commentsExtended"))
        assertTrue(String(parts["word/comments.xml"]!!, Charsets.UTF_8).contains("""w:author="Bob""""))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(3, back.comments.size)
        val root = back.comments.single { it.parentId == null }
        assertEquals("Is this right?", root.text); assertTrue(root.resolved); assertEquals(parent.dateMs / 1000, root.dateMs / 1000)
        val replies = back.comments.filter { it.parentId == root.id }
        assertEquals(listOf("Yes, checked.", "Thanks"), replies.map { it.text }); assertEquals(listOf("Bob", "Bob"), replies.map { it.author })
        assertFalse(replies[0].resolved)
        assertEquals(root.targetId, replies[0].targetId)
        // Deleting the thread root removes its replies; undo brings the whole thread back.
        val ed2 = DocumentEditor(); ed2.load(back)
        ed2.deleteComment(root)
        assertTrue(ed2.document.comments.isEmpty())
        ed2.undo()
        assertEquals(3, ed2.document.comments.size)
        assertNotNull(ed2.document.comments.firstOrNull { it.parentId != null })
    }
}
