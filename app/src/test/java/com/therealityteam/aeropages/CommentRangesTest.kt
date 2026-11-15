package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.editor.DocumentEditor.DocPos
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.Bookmarks
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Comments on the text they are about (Word's ranges), kept as the text changes and through docx and ODT. */
class CommentRangesTest {

    private fun editor(vararg lines: String): DocumentEditor {
        val doc = Document(mutableListOf())
        lines.forEach { doc.addParagraph(Paragraph.of(it)) }
        return DocumentEditor().apply { load(doc) }
    }

    /** The text a comment is about, paragraphs joined by "|". */
    private fun about(e: DocumentEditor, c: com.therealityteam.aeropages.model.Comment): String? {
        val (a, b) = e.commentRange(c) ?: return null
        val locs = e.document.locations()
        return (a.index..b.index).joinToString("|") { i ->
            val t = locs[i].paragraph.plainText()
            t.substring(if (i == a.index) a.offset else 0, if (i == b.index) b.offset else t.length)
        }
    }

    @Test
    fun aCommentIsOnTheSelectionOrTheWordAtTheCaret() {
        val e = editor("hello brave world", "second line here")
        e.setSelection(0, 6, 11)
        e.addComment("Too bold?")
        val c = e.document.comments.single()
        assertEquals("brave", about(e, c))
        // The range keeps to its text as the text changes before it and inside it.
        e.setSelection(0, 0, 0); e.typeOver("Oh, ")
        assertEquals("brave", about(e, c))
        e.setSelection(0, 10, 10); e.typeOver("very ")
        assertEquals("very brave", about(e, c))
        // A comment's marks are no bookmarks.
        assertTrue(Bookmarks.of(e.document).isEmpty())
        // No selection: the word at the caret.
        e.setSelection(1, 9, 9)
        e.addComment("Which line?")
        assertEquals("line", about(e, e.document.comments[1]))
        // Across paragraphs.
        e.selectRange(DocPos(0, 21), DocPos(1, 6))
        e.addComment("Both")
        assertEquals("world|second", about(e, e.document.comments[2]))
        assertEquals(e.document.locations()[0].paragraph.id, e.document.comments[2].targetId)
        // Deleting a comment takes its range away; undo brings both back.
        e.deleteComment(c)
        assertEquals(2, e.document.comments.size)
        assertEquals(2, Bookmarks.commentRanges(e.document).size)
        e.undo()
        assertEquals("very brave", about(e, e.document.comments.first { it.text == "Too bold?" }))
    }

    @Test
    fun goingToACommentSelectsItsText() {
        val e = editor("alpha beta gamma")
        e.setSelection(0, 6, 10); e.addComment("b")
        e.setSelection(0, 0, 0)
        e.currentComment = null
        assertTrue(e.goToComment())
        assertEquals(DocPos(0, 6) to DocPos(0, 10), e.selectionEnds())
        // An empty paragraph has no word: the comment is on the paragraph.
        val f = editor("")
        f.addComment("blank")
        assertNull(f.commentRange(f.document.comments.single()))
    }

    private fun threaded(): DocumentEditor {
        val e = editor("The plan is final.", "Budget follows.", "")
        e.changeReviewer("Ann")
        e.setSelection(0, 12, 17); e.addComment("Is it?")
        e.changeReviewer("Bob")
        e.replyComment(e.document.comments[0], "Yes.")
        e.toggleResolved(e.document.comments[0])
        e.selectRange(DocPos(0, 4), DocPos(1, 6)); e.addComment("Long one")
        e.setSelection(2, 0, 0); e.addComment("On the empty line")
        return e
    }

    private fun assertThreaded(back: Document) {
        val e = DocumentEditor().apply { load(back) }
        val root = back.comments.first { it.text == "Is it?" }
        assertEquals("final", about(e, root))
        assertTrue(root.resolved)
        val reply = back.comments.single { it.parentId == root.id }
        assertEquals("Yes.", reply.text); assertEquals("Bob", reply.author)
        assertNull("a reply's range is its thread's", Bookmarks.commentRanges(back)[reply.id])
        assertEquals("plan is final.|Budget", about(e, back.comments.first { it.text == "Long one" }))
        val blank = back.comments.first { it.text == "On the empty line" }
        assertNull(e.commentRange(blank))
        assertEquals(back.locations()[2].paragraph.id, blank.targetId)
    }

    @Test
    fun wordKeepsTheRanges() {
        val bytes = ByteArrayOutputStream().also { DocxWriter.write(threaded().document, it) }.toByteArray()
        val xml = String(OfficeXml.unzip(bytes)["word/document.xml"]!!)
        // The range, then where it ends the reference and the reply's.
        assertTrue(xml, Regex("""<w:commentRangeStart w:id="0"/>.*final.*<w:commentRangeEnd w:id="0"/><w:r><w:commentReference w:id="0"/></w:r><w:r><w:commentReference w:id="1"/></w:r>""").containsMatchIn(xml))
        assertTrue(xml.contains("""<w:commentRangeStart w:id="2"/>"""))
        assertThreaded(DocxReader.read(ByteArrayInputStream(bytes)))
    }

    @Test
    fun odfKeepsThemAsAnnotations() {
        val bytes = ByteArrayOutputStream().also { OdtWriter.write(threaded().document, it) }.toByteArray()
        val content = String(OfficeXml.unzip(bytes)["content.xml"]!!)
        val root = threaded().document.comments[0]
        assertTrue(content, content.contains("""<office:annotation office:name="__Annotation__"""))
        assertTrue(content.contains("""loext:parent-name="__Annotation__"""))
        assertTrue(content.contains("""<office:annotation-end office:name="__Annotation__"""))
        assertTrue(content.contains("<dc:creator>Ann</dc:creator>"))
        assertTrue(root.text.isNotEmpty())
        assertThreaded(OdtReader.read(bytes))
    }

    /** With `-Daero.sampleDir`: comments on words, across paragraphs, a reply, for LibreOffice. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        val e = threaded()
        File(dir, "comment-ranges.docx").outputStream().use { DocxWriter.write(e.document, it) }
        File(dir, "comment-ranges.odt").outputStream().use { OdtWriter.write(e.document, it) }
    }
}
