package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.OfficeXml
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * OpenDocument packaging shared by the .odt / .ods / .odp writers.
 *
 * An ODF file is a zip whose **first** entry is an uncompressed `mimetype` (that is what lets a
 * reader identify the format from the first bytes without unzipping), followed by the XML parts
 * and `META-INF/manifest.xml`, which must list every entry in the package.
 */
object OdfPackage {

    const val TEXT = "application/vnd.oasis.opendocument.text"
    const val SPREADSHEET = "application/vnd.oasis.opendocument.spreadsheet"
    const val PRESENTATION = "application/vnd.oasis.opendocument.presentation"

    /** The namespaces every part declares (a superset; unused ones are harmless). */
    const val NS =
        """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
        """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
        """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
        """xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" """ +
        """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" """ +
        """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
        """xmlns:xlink="http://www.w3.org/1999/xlink" """ +
        """xmlns:dc="http://purl.org/dc/elements/1.1/" """ +
        """xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" """ +
        """xmlns:number="urn:oasis:names:tc:opendocument:xmlns:datastyle:1.0" """ +
        """xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0" """ +
        """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
        """xmlns:of="urn:oasis:names:tc:opendocument:xmlns:of:1.2" """ +
        // The formula grammars LibreOffice names by prefix: Writer's (table formulas, sequences — `ooow:<B2>*<C2>`) and
        // Calc's; undeclared, LibreOffice cannot read the formulas at all (a table formula came to 0).
        """xmlns:ooow="http://openoffice.org/2004/writer" xmlns:oooc="http://openoffice.org/2004/calc" """ +
        """xmlns:calcext="urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0" """ +
        """xmlns:loext="urn:org:documentfoundation:names:experimental:office:xmlns:loext:1.0" """ +
        """xmlns:tableooo="http://openoffice.org/2009/table" """ +
        // CSS3 text properties, as LibreOffice writes a cell's distributed alignment (`css3t:text-justify="distribute"`).
        """xmlns:css3t="http://www.w3.org/TR/css3-text/" """ +
        // LibreOffice's drawing extensions: a hidden object is `drawooo:display="none"`.
        """xmlns:drawooo="http://openoffice.org/2010/draw" """ +
        // LibreOffice's presentation comments (`officeooo:annotation`), and this app's own marks on them (a reply, resolved).
        """xmlns:officeooo="http://openoffice.org/2009/office" xmlns:aero="urn:therealityteam:aerodesk:1.0" """ +
        """xmlns:smil="urn:oasis:names:tc:opendocument:xmlns:smil-compatible:1.0" """ +
        """xmlns:anim="urn:oasis:names:tc:opendocument:xmlns:animation:1.0" """ +
        """xmlns:script="urn:oasis:names:tc:opendocument:xmlns:script:1.0" """ +
        // An event listener's name is a qualified name ("dom:click"): its prefix needs declaring too.
        """xmlns:dom="http://www.w3.org/2001/xml-events" """ +
        """office:version="1.3""""

    const val HEAD = """<?xml version="1.0" encoding="UTF-8"?>"""

    /**
     * Write the package. [parts] maps entry name to content; `mimetype` and the manifest are
     * added here. Insertion order is preserved, so callers can keep content.xml first. [types]
     * gives a part's media type where its name does not tell it (a video's own, say).
     */
    fun write(out: OutputStream, mimetype: String, parts: LinkedHashMap<String, ByteArray>, types: Map<String, String> = emptyMap()) {
        ZipOutputStream(out).use { zip ->
            val mime = mimetype.toByteArray(Charsets.US_ASCII)
            // Stored, first, and with no extra field — the signature readers look for.
            zip.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            val entry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            }
            zip.putNextEntry(entry); zip.write(mime); zip.closeEntry()

            val manifest = manifest(mimetype, parts, types)
            for ((name, bytes) in parts) {
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("META-INF/manifest.xml")); zip.write(manifest); zip.closeEntry()
        }
    }

    private fun manifest(mimetype: String, parts: Map<String, ByteArray>, types: Map<String, String>): ByteArray = buildString {
        val names = parts.keys
        append(HEAD)
        append("""<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3">""")
        append("""<manifest:file-entry manifest:full-path="/" manifest:media-type="$mimetype"/>""")
        // Embedded objects need their directory listed with their media type — a chart's or a
        // formula's — or LibreOffice shows an empty frame where the object should be.
        names.mapNotNull { OBJECT_DIR.find(it)?.groupValues?.get(1) }.distinct().forEach { dir ->
            val type = if (MathMl.isFormula(parts["$dir/content.xml"])) MathMl.MEDIA_TYPE else OdfChart.MEDIA_TYPE
            append("""<manifest:file-entry manifest:full-path="${escape(dir)}/" manifest:media-type="$type"/>""")
        }
        for (n in names) {
            append("""<manifest:file-entry manifest:full-path="${escape(n)}" manifest:media-type="${escape(types[n] ?: mediaType(n))}"/>""")
        }
        append("</manifest:manifest>")
    }.toByteArray(Charsets.UTF_8)

    private val OBJECT_DIR = Regex("""^(Object \d+)/""")

    private fun mediaType(name: String): String = when {
        name.endsWith(".xml") -> "text/xml"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".gif") -> "image/gif"
        name.endsWith(".bmp") -> "image/bmp"
        name.endsWith(".emf") -> "image/x-emf"
        name.endsWith(".wmf") -> "image/x-wmf"
        name.endsWith(".svg") -> "image/svg+xml"
        else -> com.therealityteam.aeropages.format.MediaTypes.mimeOf(name) ?: ""
    }

    /**
     * The part of a package holding [names] that an ODF link [href] points to — `Media/a.mp4`, as
     * `./Media/a.mp4` or LibreOffice's `vnd.sun.star.Package:Media/a.mp4`, escaped (`%20`) or not —
     * or null for a link to a file outside it.
     */
    fun partOf(href: String, names: Collection<String>): String? {
        val path = href.trim().removePrefix("vnd.sun.star.Package:").removePrefix("./")
        if (path in names) return path
        return unescapeUri(path)?.takeIf { it in names }
    }

    /** [s] with its `%XX` escapes undone (as UTF-8); null when it has none. */
    private fun unescapeUri(s: String): String? {
        if ('%' !in s) return null
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val hi = if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) Character.digit(bytes[i + 1].toInt(), 16) else -1
            val lo = if (hi >= 0) Character.digit(bytes[i + 2].toInt(), 16) else -1
            if (lo >= 0) { out.write(hi * 16 + lo); i += 3 } else { out.write(bytes[i].toInt()); i++ }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * The OpenDocument type of a package — its first entry, `mimetype`, stored as the format asks
     * ("application/vnd.oasis.opendocument.text", "…presentation-template" …) — or null for any other
     * file: what a file is, whatever its name says (an .ott, a document saved as .odt.bak …).
     */
    fun typeOf(bytes: ByteArray): String? {
        if (bytes.size < 38 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte() || bytes[2].toInt() != 3 || bytes[3].toInt() != 4) return null
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        val method = u16(8); val size = u16(18) or (u16(20) shl 16); val nameLen = u16(26); val extraLen = u16(28)
        if (method != 0 || nameLen != 8 || String(bytes, 30, 8, Charsets.US_ASCII) != "mimetype") return null
        val from = 30 + nameLen + extraLen
        if (size !in 1..200 || from + size > bytes.size) return null
        return String(bytes, from, size, Charsets.US_ASCII).trim().takeIf { it.startsWith("application/vnd.oasis.opendocument.") }
    }

    fun escape(s: String): String = OfficeXml.escape(s)

    /**
     * A text property for every script: LibreOffice applies `fo:` properties to Latin text only, Chinese
     * and Japanese text taking the `-asian` ones (a heading's size and weight among them), Arabic the `-complex`.
     */
    fun weightAttr(v: String) = """fo:font-weight="$v" style:font-weight-asian="$v" style:font-weight-complex="$v" """
    fun italicAttr(v: String) = """fo:font-style="$v" style:font-style-asian="$v" style:font-style-complex="$v" """
    fun sizeAttr(v: String) = """fo:font-size="$v" style:font-size-asian="$v" style:font-size-complex="$v" """

    /** A length in points, as ODF wants it (`12.5pt`). */
    fun pt(v: Float): String = String.format(Locale.ROOT, "%.2fpt", v)

    /** An 0xAARRGGBB colour as `#RRGGBB`. */
    fun color(argb: Long): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    fun number(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.ROOT, "%s", v)

    /** File extension for an image mime type. */
    fun imageExt(mime: String): String = when {
        mime.contains("png", true) -> "png"
        mime.contains("jpeg", true) || mime.contains("jpg", true) -> "jpg"
        mime.contains("gif", true) -> "gif"
        mime.contains("bmp", true) -> "bmp"
        mime.contains("emf", true) -> "emf"
        mime.contains("wmf", true) -> "wmf"
        mime.contains("svg", true) -> "svg"
        else -> "png"
    }
}
