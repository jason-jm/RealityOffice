package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.DocumentEditor
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.model.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tracked changes in .doc: the revision marks of the runs (sprmCFRMarkDel / sprmCFRMarkIns, their authors from SttbfRMark
 * and their dates) and of the paragraph marks, which the reader used to leave out — a deletion read as kept text.
 */
class DocRevisionsTest {
    private fun texts(d: Document) = d.paragraphs().map { it.plainText() }
    private fun accepted(d: Document) = DocumentEditor().apply { load(d.copyOf()); acceptAllChanges() }.document
    private fun rejected(d: Document) = DocumentEditor().apply { load(d.copyOf()); rejectAllChanges() }.document

    @Test
    fun libreOfficesDocOfACompareResultKeepsItsChanges() {
        // LibreOffice's .doc of our Compare Result (compare-result.docx).
        val d = File("src/test/resources/doc/revisions.doc").inputStream().use { DocReader.read(it) }
        val sales = d.paragraphs().first { it.plainText().startsWith("Sales grew") }
        assertEquals("north", sales.runs.filter { it.format.deleted }.joinToString("") { it.text })
        assertEquals("strongly south", sales.runs.filter { it.format.inserted }.joinToString(" ") { it.text.trim() })
        val changed = sales.runs.filter { it.format.deleted || it.format.inserted }
        assertTrue(changed.joinToString { "${it.text}:${it.format.revAuthor}" }, changed.all { it.format.revAuthor == "Reviewer" })
        assertTrue(changed.all { it.format.revDate != null })
        val costs = d.paragraphs().first { it.plainText() == "Costs stayed flat." }
        assertTrue(costs.runs.all { it.format.deleted })
        // LibreOffice's .doc keeps the deleted paragraph's text, not its mark's deletion (it reads its own file the same way).
        assertEquals(listOf("Quarterly review", "Sales grew strongly in every region this quarter, led by the south.",
            "The new store opened in August and is ahead of plan.", "We expect a stronger next quarter.", "Hiring starts in January."),
            texts(accepted(d)).filter { it.isNotEmpty() })
        val back = texts(rejected(d))
        assertEquals("Sales grew in every region this quarter, led by the north.", back[1])
        assertTrue("Costs stayed flat." in back)
        assertTrue("We expect the same next quarter." in back)
    }

    private fun ByteArray.put16(off: Int, v: Int) { this[off] = (v and 0xFF).toByte(); this[off + 1] = ((v ushr 8) and 0xFF).toByte() }
    private fun ByteArray.put32(off: Int, v: Int) { for (k in 0 until 4) this[off + k] = ((v ushr (8 * k)) and 0xFF).toByte() }

    /** "Kept", "Gone", "After": "Gone" and its paragraph mark deleted by Ann (sprmCFRMarkDel, sprmCIbstRMarkDel, sprmCDttmRMarkDel). */
    private fun deletedParagraphDoc(dttm: Int): ByteArray {
        val text = "Kept\rGone\rAfter\r"
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val wd = ByteArray(4608)
        wd.put16(0, 0xA5EC); wd.put16(2, 0x00C1)
        val fcMin = 1024; val fcMac = fcMin + textBytes.size
        wd.put32(0x18, fcMin); wd.put32(0x1C, fcMac)
        wd.put16(0x20, 14); wd.put16(0x3E, 22); wd.put32(0x40 + 12, text.length); wd.put16(0x98, 93)
        System.arraycopy(textBytes, 0, wd, fcMin, textBytes.size)
        // CHPX FKP: plain | "Gone\r" deleted | plain
        val page = 2048
        val goneFc = fcMin + text.indexOf("Gone"); val goneEnd = goneFc + 5
        intArrayOf(fcMin, goneFc, goneEnd, fcMac).forEachIndexed { i, fc -> wd.put32(page + i * 4, fc) }
        val chpx = byteArrayOf(0x00, 0x08, 1, 0x63, 0x48, 1, 0, 0x64, 0x68, 0, 0, 0, 0)
        for (k in 0 until 4) chpx[9 + k] = ((dttm ushr (8 * k)) and 0xFF).toByte()
        wd[page + 400] = chpx.size.toByte(); System.arraycopy(chpx, 0, wd, page + 401, chpx.size)
        wd[page + 16] = 0; wd[page + 17] = 200.toByte(); wd[page + 18] = 0
        wd[page + 511] = 3
        // 0Table: the CLX, the character bin table, the revision authors ("Unknown", "Ann").
        val tbl = ByteArray(1024)
        var pos = 0
        tbl[pos] = 2; tbl.put32(pos + 1, 16); pos += 5
        tbl.put32(pos, 0); tbl.put32(pos + 4, text.length); pos += 8
        tbl.put16(pos, 0); tbl.put32(pos + 2, (fcMin * 2) or 0x40000000); tbl.put16(pos + 6, 0); pos += 8
        val clxLen = pos
        val bte = pos; tbl.put32(pos, fcMin); tbl.put32(pos + 4, fcMac); tbl.put32(pos + 8, 4); pos += 12
        val sttb = pos; tbl.put16(pos, 0xFFFF); tbl.put16(pos + 2, 2); tbl.put16(pos + 4, 0); pos += 6
        for (name in listOf("Unknown", "Ann")) { tbl.put16(pos, name.length); pos += 2; name.forEach { tbl.put16(pos, it.code); pos += 2 } }
        wd.put32(0x1A2, 0); wd.put32(0x1A6, clxLen)
        wd.put32(0xFA, bte); wd.put32(0xFE, 12)
        wd.put32(0x232, sttb); wd.put32(0x236, pos - sttb)
        return TestCfb.build(linkedMapOf("WordDocument" to wd, "0Table" to tbl))
    }

    @Test
    fun aDeletedParagraphMarkIsTheParagraphsAndItsAuthorIsNamed() {
        val v = 33 or (3 shl 6) or (25 shl 11) or (10 shl 16) or (126 shl 20)
        val d = DocReader.read(java.io.ByteArrayInputStream(deletedParagraphDoc(v)))
        assertEquals(listOf("Kept", "Gone", "After"), texts(d))
        val gone = d.paragraphs()[1]
        assertTrue(gone.runs.all { it.format.deleted && it.format.revAuthor == "Ann" && it.format.revDate == DocReader.dttm(v) })
        assertEquals(com.therealityteam.aeropages.model.RevisionMark(false, "Ann", DocReader.dttm(v)), gone.format.revisionMark)
        assertEquals(null, d.paragraphs()[0].format.revisionMark)
        assertEquals(listOf("Kept", "After"), texts(accepted(d)))
        assertEquals(listOf("Kept", "Gone", "After"), texts(rejected(d)))
    }

    @Test
    fun aDttmIsItsMinuteOfItsDay() {
        // 2026-10-25 03:33: minutes 33, hours 3, day 25, month 10, 126 years after 1900.
        val v = 33 or (3 shl 6) or (25 shl 11) or (10 shl 16) or (126 shl 20)
        assertEquals(java.time.LocalDateTime.of(2026, 10, 25, 3, 33).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(), DocReader.dttm(v))
        assertEquals(null, DocReader.dttm(0))
    }
}
