package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Word's Lock Tracking (`w:documentProtection w:edit="trackedChanges"`): Track Changes stays on and the changes can't be
 * accepted or rejected until it's unlocked; a lock with a password set in Word is kept as it is. And Accept / Reject on a
 * change's shortcut menu: the part of the changes the selection covers, or the change at the caret.
 */
class TrackingLockTest {
    private val ins = CharacterFormat(inserted = true, revAuthor = "Ann", revDate = 1_760_000_000_000L)
    private val del = CharacterFormat(deleted = true, revAuthor = "Bo", revDate = 1_760_000_000_000L)
    private fun changed() = Document(mutableListOf()).also { d ->
        d.addParagraph(Paragraph(mutableListOf(TextRun("Keep "), TextRun("new", ins.copyOf()), TextRun(" text "), TextRun("old", del.copyOf()), TextRun("."))))
        d.addParagraph(Paragraph(mutableListOf(TextRun("Next "), TextRun("added", ins.copyOf()), TextRun(" line."))))
    }
    private fun editor(d: Document) = DocumentEditor().apply { load(d) }
    private fun texts(d: Document) = d.paragraphs().map { it.plainText() }
    private fun docx(d: Document) = ByteArrayOutputStream().also { DocxWriter.write(d, it) }.toByteArray()
    private fun part(zip: ByteArray, name: String): String {
        ZipInputStream(zip.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; if (e.name == name) return z.readBytes().toString(Charsets.UTF_8) } }
        error("no $name")
    }
    /** [zip] with its part [name] replaced by [text]. */
    private fun withPart(zip: ByteArray, name: String, text: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { o ->
            ZipInputStream(zip.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    o.putNextEntry(ZipEntry(e.name))
                    o.write(if (e.name == name) text.toByteArray() else z.readBytes())
                    o.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    @Test
    fun lockedTrackingStaysOnAndTheChangesStay() {
        val e = editor(changed())
        e.lockTracking()
        assertTrue(e.trackingLocked && e.trackChanges && e.document.trackRevisions)
        e.toggleTrackChanges()
        assertTrue("it can't be turned off", e.trackChanges)
        e.acceptAllChanges(); e.rejectChangesBy("Ann"); e.resolveShownChanges(accept = true); e.resolveAllAndStopTracking(accept = false)
        e.acceptRevision(e.revisions().first())
        assertEquals(3, e.revisions().size)
        e.setSelection(0, 6, 6)
        assertFalse(e.resolveSelection(accept = true))
        // Unlocked (no password), all works again.
        assertTrue(e.unlockTracking())
        assertFalse(e.trackingLocked)
        e.toggleTrackChanges()
        assertFalse(e.trackChanges)
        e.acceptAllChanges()
        assertEquals(listOf("Keep new text .", "Next added line."), texts(e.document))
    }

    @Test
    fun docxKeepsTheLockAndAPasswordItCantCheck() {
        val d = changed(); d.trackRevisions = true; d.trackLock = emptyMap()
        val bytes = docx(d)
        val settings = part(bytes, "word/settings.xml")
        // CT_Settings: trackRevisions, documentProtection, defaultTabStop.
        assertTrue(settings, Regex("""<w:trackRevisions/><w:documentProtection w:edit="trackedChanges" w:enforcement="1"/><w:defaultTabStop""").containsMatchIn(settings))
        val back = DocxReader.read(ByteArrayInputStream(bytes))
        assertEquals(emptyMap<String, String>(), back.trackLock)
        assertTrue(editor(back).trackingLocked)
        // Word's own lock with a password: kept as written, and not unlocked here.
        val word = settings.replace("""<w:documentProtection w:edit="trackedChanges" w:enforcement="1"/>""",
            """<w:documentProtection w:edit="trackedChanges" w:enforcement="1" w:cryptProviderType="rsaAES" w:cryptAlgorithmClass="hash" w:cryptAlgorithmType="typeAny" w:cryptAlgorithmSid="14" w:cryptSpinCount="100000" w:hash="abc+/=" w:salt="xyz=="/>""")
        val locked = DocxReader.read(ByteArrayInputStream(withPart(bytes, "word/settings.xml", word)))
        assertEquals("14", locked.trackLock?.get("w:cryptAlgorithmSid"))
        val e = editor(locked)
        assertTrue(e.trackingLockHasPassword)
        assertFalse(e.unlockTracking())
        assertTrue(e.trackingLocked)
        val again = part(docx(e.document), "word/settings.xml")
        assertTrue(again, again.contains("""w:cryptAlgorithmSid="14""""))
        assertTrue(again, again.contains("""w:hash="abc+/=""""))
        // LibreOffice's re-saved lock carries empty hash attributes: still no password.
        val lo = settings.replace("""<w:documentProtection w:edit="trackedChanges" w:enforcement="1"/>""",
            """<w:documentProtection w:edit="trackedChanges" w:enforcement="1" w:cryptProviderType="rsaAES" w:cryptSpinCount="0" w:hash="" w:salt=""/>""")
        val fromLo = editor(DocxReader.read(ByteArrayInputStream(withPart(bytes, "word/settings.xml", lo))))
        assertTrue(fromLo.trackingLocked && !fromLo.trackingLockHasPassword)
        assertTrue(fromLo.unlockTracking())
        // Other protections (read-only, forms) are not a tracking lock.
        val readOnly = settings.replace("""w:edit="trackedChanges"""", """w:edit="readOnly"""")
        assertNull(DocxReader.read(ByteArrayInputStream(withPart(bytes, "word/settings.xml", readOnly))).trackLock)
        // Unlocked documents write no protection.
        assertFalse(part(docx(changed()), "word/settings.xml").contains("documentProtection"))
    }

    @Test
    fun odtKeepsTheLockAsLibreOfficeDoes() {
        fun odt(d: Document) = ByteArrayOutputStream().also { com.therealityteam.aeropages.format.odf.OdtWriter.write(d, it) }.toByteArray()
        val d = changed(); d.trackRevisions = true; d.trackLock = emptyMap()
        val bytes = odt(d)
        assertTrue(part(bytes, "settings.xml").contains("""config:name="RedlineProtectionKey" config:type="base64Binary">AQ==<"""))
        assertTrue(part(bytes, "META-INF/manifest.xml").contains("settings.xml"))
        val back = com.therealityteam.aeropages.format.odf.OdtReader.read(bytes)
        assertEquals(emptyMap<String, String>(), back.trackLock)
        assertFalse(back.trackLockHasPassword)
        // A key LibreOffice made from a password: kept, not unlocked here; a Word password becomes LibreOffice's no-password key.
        val keyed = changed().also { it.trackLock = mapOf(com.therealityteam.aeropages.model.ODF_TRACK_KEY to "q83vEjRWeJA=") }
        val k = com.therealityteam.aeropages.format.odf.OdtReader.read(odt(keyed))
        assertTrue(k.trackLockHasPassword)
        assertFalse(editor(k).unlockTracking())
        val fromWord = changed().also { it.trackLock = mapOf("w:hash" to "abc=", "w:salt" to "xyz=") }
        assertTrue(part(odt(fromWord), "settings.xml").contains(">AQ==<"))
        assertFalse(part(docx(keyed), "word/settings.xml").contains("odf:"))
        // Unlocked: no settings at all.
        assertFalse(ZipInputStream(odt(changed()).inputStream()).use { z -> generateSequence { z.nextEntry }.any { it.name == "settings.xml" } })
    }

    @Test
    fun acceptAndRejectOnASelectionTakeThePartItCovers() {
        // The caret in a change: the whole change, by its kind.
        val e = editor(changed())
        e.setSelection(0, 6, 6)
        assertEquals(listOf(DocumentEditor.RevKind.INSERT), e.selectionChanges().map { it.kind })
        assertTrue(e.resolveSelection(accept = true))
        assertTrue(e.document.paragraphs()[0].runs.none { it.format.inserted })
        assertEquals(1, e.document.paragraphs()[0].runs.count { it.format.deleted })
        e.undo()
        assertEquals(3, e.revisions().size)
        // A selection over an insertion and a deletion: both kinds; rejected, the insertion goes and the deletion stays as text.
        e.setSelection(0, 0, 18)
        assertEquals(listOf(DocumentEditor.RevKind.INSERT, DocumentEditor.RevKind.DELETE), e.selectionChanges().map { it.kind })
        e.resolveSelection(accept = false)
        assertEquals("Keep  text old.", e.document.paragraphs()[0].plainText())
        assertTrue(e.document.paragraphs()[0].runs.none { it.format.inserted || it.format.deleted })
        assertEquals("Next added line.", e.document.paragraphs()[1].plainText())
        assertEquals(1, e.revisions().size)
        e.undo()
        // Part of a change selected: only that part.
        e.setSelection(0, 5, 7)
        e.resolveSelection(accept = false)
        assertEquals("Keep w text old.", e.document.paragraphs()[0].plainText().replace("\u0000", ""))
        e.undo()
        // A selection across paragraphs: the changes in both.
        e.selectRange(DocumentEditor.DocPos(0, 13), DocumentEditor.DocPos(1, 11))
        assertEquals(listOf(DocumentEditor.RevKind.DELETE, DocumentEditor.RevKind.INSERT), e.selectionChanges().map { it.kind })
        e.resolveSelection(accept = true)
        assertEquals(listOf("Keep new text .", "Next added line."), texts(e.document))
        assertEquals(1, e.revisions().size)   // the first insertion was outside the selection
        // No change there: nothing.
        e.setSelection(0, 1, 1)
        assertTrue(e.selectionChanges().isEmpty())
        assertFalse(e.resolveSelection(accept = true))
    }

    /** Samples (with `-Daero.sampleDir`): a document with tracked changes and Lock Tracking on, as docx and ODT. */
    @Test
    fun writeSample() {
        val dir = System.getProperty("aero.sampleDir")?.let { java.io.File(it).also { d -> d.mkdirs() } } ?: return
        val d = changed(); d.trackRevisions = true; d.trackLock = emptyMap()
        java.io.File(dir, "lock-tracking.docx").outputStream().use { DocxWriter.write(d, it) }
        java.io.File(dir, "lock-tracking.odt").outputStream().use { com.therealityteam.aeropages.format.odf.OdtWriter.write(d, it) }
    }
}
