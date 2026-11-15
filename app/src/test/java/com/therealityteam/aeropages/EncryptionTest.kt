package com.therealityteam.aeropages

import com.therealityteam.aeropages.TestCfb.le16
import com.therealityteam.aeropages.TestCfb.le32
import com.therealityteam.aeropages.format.DocumentRepository
import com.therealityteam.aeropages.format.EncryptedDocumentException
import com.therealityteam.aeropages.format.Encryption
import com.therealityteam.aeropages.format.ppt.PptReader
import com.therealityteam.aeropages.format.xls.XlsReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Password-protected files are refused when they open — never read as a blank document that a save
 * (or autosave) would then write over the real file.
 */
class EncryptionTest {

    private fun file(name: String, bytes: ByteArray): File =
        File(createTempDir("aero-enc"), name).apply { writeBytes(bytes); deleteOnExit() }

    private fun createTempDir(prefix: String): File = java.nio.file.Files.createTempDirectory(prefix).toFile().apply { deleteOnExit() }

    /** What Office writes for a file saved with a password: a compound file of EncryptionInfo and EncryptedPackage. */
    private val encryptedOffice = TestCfb.build(mapOf(
        "EncryptionInfo" to le16(4) + le16(4) + le32(0x40) + "<encryption/>".toByteArray(),
        "EncryptedPackage" to le32(1234) + le32(0) + ByteArray(2048) { (it * 31).toByte() },
    ))

    private fun refused(block: () -> Unit) {
        try { block(); fail("opened an encrypted file") } catch (e: EncryptedDocumentException) { /* refused, as it should be */ }
    }

    @Test fun anEncryptedOfficeFileIsRefusedByEveryModule() {
        for (ext in listOf("xlsx", "xlsm", "pptx", "docx")) {
            val f = file("locked.$ext", encryptedOffice)
            refused { Encryption.check(f.path, f.readBytes()) }
            when (ext) {
                "xlsx", "xlsm" -> refused { com.therealityteam.aeropages.ui.readWorkbook(f.path) }
                "pptx" -> refused { com.therealityteam.aeropages.ui.readDeck(f.path) }
                else -> refused { DocumentRepository.open(f.path) }
            }
        }
    }

    private fun fib(flags: Int) = le16(0xA5EC) + le16(0xC1) + le16(0) + le16(0x0409) + le16(0) + le16(flags) + ByteArray(1024)

    @Test fun aDocSaysItIsEncryptedInItsFib() {
        assertTrue(Encryption.compound(TestCfb.build(mapOf("WordDocument" to fib(0x0100 or 0x0200), "1Table" to ByteArray(16)))))
        assertFalse("fWhichTblStm alone is not encryption", Encryption.compound(TestCfb.build(mapOf("WordDocument" to fib(0x0200), "1Table" to ByteArray(16)))))
    }

    private fun rec(type: Int, body: ByteArray) = le16(type) + le16(body.size) + body
    private val bofGlobals = rec(0x0809, le16(0x0600) + le16(0x0005) + le16(0x0DBB) + le16(0x07CC) + le32(0) + le32(6))

    @Test fun anXlsSaysItIsEncryptedWithAFilePassRecord() {
        val locked = bofGlobals + rec(0x002F, le16(1) + ByteArray(52)) + rec(0x0085, ByteArray(10)) + rec(0x000A, byteArrayOf())
        assertTrue(Encryption.compound(TestCfb.build(mapOf("Workbook" to locked))))
        val open = bofGlobals + rec(0x0085, ByteArray(10)) + rec(0x000A, byteArrayOf()) + rec(0x002F, ByteArray(8))
        assertFalse("only the globals count (a sheet's bytes after EOF are not looked at)", Encryption.compound(TestCfb.build(mapOf("Workbook" to open))))
    }

    private fun currentUser(token: Int) = le16(0) + le16(0x0FF6) + le32(0x14) + le32(0x14) + le32(token) + ByteArray(32)

    @Test fun aPptSaysItIsEncryptedInItsCurrentUserAtom() {
        assertTrue(Encryption.compound(TestCfb.build(mapOf("Current User" to currentUser(0xF3D1C4DF.toInt()), "PowerPoint Document" to ByteArray(64)))))
        assertFalse(Encryption.compound(TestCfb.build(mapOf("Current User" to currentUser(0xE391C05F.toInt()), "PowerPoint Document" to ByteArray(64)))))
    }

    private fun zip(parts: Map<String, String>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    @Test fun anOpenDocumentWithEncryptionDataIsRefused() {
        val manifest = """<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0">""" +
            """<manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml" manifest:size="5120">""" +
            """<manifest:encryption-data manifest:checksum-type="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0#sha256-1k" manifest:checksum="x">""" +
            """<manifest:algorithm manifest:algorithm-name="http://www.w3.org/2001/04/xmlenc#aes256-cbc" manifest:initialisation-vector="y"/>""" +
            """</manifest:encryption-data></manifest:file-entry></manifest:manifest>"""
        val locked = file("locked.ods", zip(mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to "\u0001\u0002", "META-INF/manifest.xml" to manifest)))
        refused { com.therealityteam.aeropages.ui.readWorkbook(locked.path) }
        val plain = file("plain.ods", zip(mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to "<office:document-content/>",
            "META-INF/manifest.xml" to """<manifest:manifest><manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/></manifest:manifest>""")))
        Encryption.check(plain.path, plain.readBytes())
    }

    @Test fun aCompoundFileWithoutItsDocumentStreamIsNoBlankDocument() {
        val other = TestCfb.build(mapOf("Something" to ByteArray(16)))
        assertEquals("not encrypted", false, Encryption.compound(other))
        assertTrue(runCatching { XlsReader.read(other) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { PptReader.read(other) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun noFileTheAppWritesLooksEncrypted() {
        val dir = File(System.getProperty("java.io.tmpdir"), "aero-encryption-samples").apply { deleteRecursively(); mkdirs() }
        SampleFileMakerTest().writeTo(dir)
        val files = dir.listFiles()!!.filter { it.isFile }
        assertTrue(files.size > 50)
        files.forEach { f -> Encryption.check(f.path, f.readBytes()) }
    }
}
