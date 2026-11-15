package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.format.doc.CompoundFileReader
import com.therealityteam.aeropages.format.doc.DocReader
import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.model.DocFormat
import com.therealityteam.aeropages.model.Document
import java.io.File

/**
 * Façade over the format readers/writers. The editor only ever calls these four
 * methods; the underlying .doc/.docx handling is an implementation detail.
 */
object DocumentRepository {

    /** File extensions this app can open (Word and the common interchange formats). */
    val OPENABLE_EXTENSIONS = setOf(
        "docx", "docm", "dotx", "dotm", "dot", "doc", "rtf", "txt", "text", "md", "markdown",
        "html", "htm", "csv", "odt", "ott",
        // WPS Writer's own (a Word 97 file inside) and its template.
        "wps", "wpt",
    )

    /** Open by path, detecting the format from content first, then extension. */
    fun open(path: String): Document {
        val file = File(path)
        val bytes = file.readBytes()
        // A password-protected file is refused, never read as an empty document that a save would write over it.
        Encryption.check(path, bytes)
        val ext = path.substringAfterLast('.', "").lowercase()
        val doc = when {
            CompoundFileReader.isCfb(bytes) -> DocReader.read(bytes.inputStream())          // .doc (OLE2)
            com.therealityteam.aeropages.format.odf.OdfPackage.typeOf(bytes)?.startsWith(com.therealityteam.aeropages.format.odf.OdfPackage.TEXT) == true ||
                (ext == "odt" || ext == "ott") && looksLikeZip(bytes) -> com.therealityteam.aeropages.format.odf.OdtReader.read(bytes)
            looksLikeZip(bytes) -> DocxReader.read(bytes.inputStream())                      // .docx (OOXML zip)
            ext == "txt" || ext == "text" -> TextImporters.txt(bytes.inputStream())
            ext == "md" || ext == "markdown" -> TextImporters.markdown(bytes.inputStream())
            ext == "rtf" -> TextImporters.rtf(bytes.inputStream())
            ext == "html" || ext == "htm" -> TextImporters.html(bytes.inputStream())
            ext == "csv" -> TextImporters.csv(bytes.inputStream())
            DocFormat.fromPath(path) == DocFormat.DOC -> DocReader.read(bytes.inputStream())
            else -> DocxReader.read(bytes.inputStream())
        }
        doc.sourcePath = path
        return doc
    }

    /**
     * Save the document. We always serialise as OOXML (.docx) because the legacy
     * binary writer is out of scope; if the source was a .doc we save alongside
     * it with a .docx extension and report the new path.
     *
     * @return the path the document was written to.
     */
    fun save(doc: Document, targetPath: String? = null): String {
        val basePath = targetPath ?: doc.sourcePath ?: error("No path to save to")
        val outPath = if (basePath.endsWith(".doc", ignoreCase = true)) {
            basePath.dropLast(4) + ".docx"
        } else if (!basePath.endsWith(".docx", ignoreCase = true)) {
            "$basePath.docx"
        } else basePath

        File(outPath).outputStream().use { DocxWriter.write(doc, it) }
        doc.sourcePath = outPath
        doc.sourceFormat = DocFormat.DOCX
        return outPath
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            (bytes[2].toInt() == 0x03 || bytes[2].toInt() == 0x05 || bytes[2].toInt() == 0x07)
}
