package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.format.doc.CompoundFileReader
import java.util.zip.ZipFile

/**
 * Opening failed because the file is locked with a password. Read on, it would give nothing or
 * garbage — and a blank document that a save, or autosave, then writes over the real file.
 */
class EncryptedDocumentException : java.io.IOException("the file is protected with a password")

/**
 * Whether a document is encrypted with a password, which AeroDesk cannot read: an Office 2007+ file
 * saved with a password (or under rights management) is a compound file holding EncryptionInfo and
 * EncryptedPackage streams; a .doc says so in its FIB (fEncrypted), a .xls with a FILEPASS record
 * among its globals, a .ppt in its Current User atom's header token; an OpenDocument package gives
 * its parts encryption data in the manifest.
 */
object Encryption {

    /** Throws [EncryptedDocumentException] when the file at [path], whose [bytes] are already read, is encrypted. */
    fun check(path: String, bytes: ByteArray) {
        if (if (CompoundFileReader.isCfb(bytes)) compound(bytes) else isZip(bytes) && packageEncrypted(path)) throw EncryptedDocumentException()
    }

    /** Whether a compound file (.doc / .xls / .ppt, or an encrypted Office 2007+ file) is encrypted. */
    fun compound(bytes: ByteArray): Boolean = runCatching {
        val cfb = CompoundFileReader(bytes)
        val names = cfb.streamNames()
        when {
            "EncryptedPackage" in names || "EncryptionInfo" in names -> true
            else -> cfb.readStream("WordDocument")?.let { fib -> fib.size > 0x0B && (u16(fib, 0x0A) and F_ENCRYPTED) != 0 } == true ||
                (cfb.readStream("Workbook") ?: cfb.readStream("Book"))?.let { filePass(it) } == true ||
                cfb.readStream("Current User")?.let { cu -> cu.size >= 16 && u32(cu, 12) == ENCRYPTED_PPT } == true
        }
    }.getOrDefault(false)

    /** Whether the package at [path] is an OpenDocument one whose manifest gives a part encryption data. */
    fun packageEncrypted(path: String): Boolean = runCatching {
        ZipFile(path).use { zip ->
            val manifest = zip.getEntry("META-INF/manifest.xml") ?: return@use false
            manifestEncrypted(zip.getInputStream(manifest).use { it.readBytes() }.toString(Charsets.UTF_8))
        }
    }.getOrDefault(false)

    /** An ODF manifest listing encryption data (`manifest:encryption-data`) for a part. */
    fun manifestEncrypted(manifest: String): Boolean = "encryption-data" in manifest

    /** FibBase.fEncrypted. */
    private const val F_ENCRYPTED = 0x0100
    /** CurrentUserAtom.headerToken of an encrypted presentation (0xE391C05F when it is not). */
    private const val ENCRYPTED_PPT = 0xF3D1C4DF.toInt()

    /** A FILEPASS record (0x002F) among the workbook globals' records, before their EOF (0x000A). */
    private fun filePass(stream: ByteArray): Boolean {
        var off = 0
        while (off + 4 <= stream.size) {
            when (u16(stream, off)) { 0x002F -> return true; 0x000A -> return false }
            off += 4 + u16(stream, off + 2)
        }
        return false
    }

    private fun isZip(b: ByteArray) = b.size >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
