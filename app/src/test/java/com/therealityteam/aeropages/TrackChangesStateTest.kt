package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** Track Changes on is the document's setting (Word's `w:trackRevisions`, ODF's `text:track-changes`): a document opens recording as it was saved. */
class TrackChangesStateTest {
    private fun doc(on: Boolean, changed: Boolean = false) = Document(mutableListOf()).also { d ->
        d.addParagraph(if (changed) Paragraph(mutableListOf(TextRun("Kept "), TextRun("new", CharacterFormat(inserted = true, revAuthor = "Ann", revDate = 1L))))
        else Paragraph.of("Plain"))
        d.trackRevisions = on
    }
    private fun part(zip: ByteArray, name: String): String {
        ZipInputStream(zip.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().toString(Charsets.UTF_8) } }
        error("no $name")
    }
    private fun docx(d: Document) = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
    private fun odt(d: Document) = ByteArrayOutputStream().also { OdtWriter.write(d, it) }.toByteArray()

    @Test
    fun docxKeepsTrackChangesOn() {
        val on = docx(doc(true))
        val settings = part(on, "word/settings.xml")
        assertTrue("CT_Settings: trackRevisions before defaultTabStop", settings.indexOf("<w:trackRevisions/>") in 0 until settings.indexOf("<w:defaultTabStop"))
        assertTrue(DocxReader.read(ByteArrayInputStream(on)).trackRevisions)
        val off = docx(doc(false))
        assertFalse(part(off, "word/settings.xml").contains("trackRevisions"))
        assertFalse(DocxReader.read(ByteArrayInputStream(off)).trackRevisions)
    }

    @Test
    fun odtKeepsTrackChangesOnWithOrWithoutChanges() {
        val empty = odt(doc(true))
        assertTrue(part(empty, "content.xml").contains("""<text:tracked-changes text:track-changes="true"/>"""))
        assertTrue(OdtReader.read(empty).trackRevisions)
        val withChanges = odt(doc(true, changed = true))
        assertTrue(part(withChanges, "content.xml").contains("""<text:tracked-changes text:track-changes="true">"""))
        assertTrue(OdtReader.read(withChanges).trackRevisions)
        val off = odt(doc(false, changed = true))
        assertTrue(part(off, "content.xml").contains("""text:track-changes="false""""))
        assertFalse(OdtReader.read(off).trackRevisions)
        assertFalse(part(odt(doc(false)), "content.xml").contains("tracked-changes"))
        // LibreOffice's ODT of a Compare Result: changes there, recording off.
        assertFalse(OdtReader.read(File("src/test/resources/odt/tracked-changes.odt").readBytes()).trackRevisions)
    }

    @Test
    fun theEditorOpensRecordingAndStopsWithTheChangesTaken() {
        val e = DocumentEditor().apply { load(doc(true, changed = true)) }
        assertTrue(e.trackChanges)
        e.resolveAllAndStopTracking(accept = true)
        assertFalse(e.trackChanges); assertFalse(e.document.trackRevisions)
        assertEquals("Kept new", e.document.paragraphs()[0].plainText())
        assertFalse(e.document.paragraphs()[0].runs.any { it.format.inserted })
        e.toggleTrackChanges()
        assertTrue(e.document.trackRevisions)
        // A document saved with it on opens with it on; one without, off.
        val back = DocxReader.read(ByteArrayInputStream(docx(e.document)))
        assertTrue(DocumentEditor().apply { load(back) }.trackChanges)
        assertFalse(DocumentEditor().apply { load(doc(false)) }.trackChanges)
    }

    /** With `-Daero.sampleDir`: track-on.docx / .odt — a change, Track Changes on. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { File(it).also { d -> d.mkdirs() } } ?: return
        File(dir, "track-on.docx").writeBytes(docx(doc(true, changed = true)))
        File(dir, "track-on.odt").writeBytes(odt(doc(true, changed = true)))
    }
}
