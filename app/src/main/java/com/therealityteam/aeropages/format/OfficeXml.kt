package com.therealityteam.aeropages.format

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Helpers shared by the Office Open XML readers/writers: zip package access and XML text
 * escaping/unescaping (dropping the control characters XML 1.0 forbids — Word and Excel
 * refuse to open a file that contains one). The OOXML readers themselves parse with
 * [XmlPull]; the ODF importers still read `content.xml` textually and use [decode].
 */
object OfficeXml {

    /** Every non-directory zip entry, keyed by its `/`-normalised path. */
    fun unzip(input: InputStream): Map<String, ByteArray> = com.therealityteam.aeropages.platform.Trace.section("unzip") {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) out[e.name.replace('\\', '/')] = zip.readBytes()
                zip.closeEntry()
                e = zip.nextEntry
            }
        }
        out
    }

    fun unzip(bytes: ByteArray): Map<String, ByteArray> = unzip(bytes.inputStream())

    /** One named entry (e.g. `content.xml`), or null when absent. */
    fun entry(bytes: ByteArray, name: String): ByteArray? {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name == name) return zip.readBytes()
                e = zip.nextEntry
            }
        }
        return null
    }

    /** True for characters XML 1.0 allows in text; everything else is dropped on write. */
    fun isXmlChar(c: Char): Boolean = c == '\t' || c == '\n' || c == '\r' || (c >= ' ' && c != '\uFFFE' && c != '\uFFFF')

    /** Escape for element content or a double-quoted attribute, dropping forbidden control characters. */
    fun escape(s: String): String {
        var clean = true
        for (c in s) if (c == '&' || c == '<' || c == '>' || c == '"' || !isXmlChar(c)) { clean = false; break }
        if (clean) return s
        return buildString(s.length + 8) {
            for (c in s) when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                isXmlChar(c) -> append(c)
            }
        }
    }

    private val NUMERIC_ENTITY = Regex("&#(x[0-9A-Fa-f]+|[0-9]+);")

    /** Undo [escape] (plus `&apos;` and numeric character references). */
    fun decode(s: String): String {
        if ('&' !in s) return s
        var t = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        if (t.contains("&#")) t = NUMERIC_ENTITY.replace(t) { m ->
            val v = m.groupValues[1]
            val code = if (v.startsWith("x")) v.substring(1).toIntOrNull(16) else v.toIntOrNull()
            if (code != null && code in 1..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return t.replace("&amp;", "&")
    }
}
