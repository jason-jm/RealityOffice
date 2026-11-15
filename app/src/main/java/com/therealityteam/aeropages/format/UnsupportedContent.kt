package com.therealityteam.aeropages.format

import java.io.InputStream
import java.util.zip.ZipFile

/**
 * What an Office or OpenDocument file holds that AeroDesk reads past — macros, embedded objects,
 * controls, SmartArt, sound and video, PivotTables … — and so would not write back. It is found from
 * the package's part names, its relationships and, in ODF, its manifest and a few element names in
 * its content, without reading the document itself. Saving over such a file would drop that content
 * without a word; the session saves a copy instead unless told to replace the original.
 */
object UnsupportedContent {

    enum class Kind {
        MACROS, EMBEDDED_OBJECTS, CONTROLS, SMARTART, MEDIA, PIVOT_TABLES, SLICERS, EXTERNAL_LINKS,
        DATA_CONNECTIONS, DATA_MODEL, CHART_SHEETS, NEW_CHART_TYPES, COMMENT_THREADS, INK, SIGNATURES,
    }

    /** The kinds in the file at [path]: empty for a file that is not a package (.doc, .csv …) or cannot be read. */
    fun scan(path: String): Set<Kind> = runCatching {
        ZipFile(path).use { zip ->
            val names = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()
            scan(names) { name -> zip.getEntry(name)?.let { zip.getInputStream(it) } }
        }
    }.getOrDefault(emptySet())

    /** The kinds in a package of [names]; [open] reads one of them (only small parts are read whole). */
    fun scan(names: Collection<String>, open: (String) -> InputStream?): Set<Kind> {
        val found = LinkedHashSet<Kind>()
        val kept = HashSet<String>()
        if ("content.xml" in names && ("mimetype" in names || "META-INF/manifest.xml" in names)) odf(names, open, found, kept) else ooxml(names, open, found, kept)
        // Sound and video, wherever a package keeps them (ppt/media, ODF's Media/ …), but for a slide's own
        // video or sound, which AeroDesk keeps (a transition's sound, say, it does not).
        if (names.any { MediaTypes.isMedia(it) && it !in kept }) found += Kind.MEDIA
        return Kind.entries.filter { it in found }.toSet()
    }

    private fun ooxml(names: Collection<String>, open: (String) -> InputStream?, found: MutableSet<Kind>, kept: MutableSet<String>) {
        for (n in names.map { it.lowercase() }) {
            when {
                n.endsWith("vbaproject.bin") || "/macrosheets/" in n || "/dialogsheets/" in n -> found += Kind.MACROS
                "/activex/" in n || n.startsWith("xl/ctrlprops/") -> found += Kind.CONTROLS
                "/diagrams/" in n -> found += Kind.SMARTART
                n.startsWith("xl/pivottables/") || n.startsWith("xl/pivotcache/") -> found += Kind.PIVOT_TABLES
                n.startsWith("xl/slicers/") || n.startsWith("xl/slicercaches/") || n.startsWith("xl/timelines/") || n.startsWith("xl/timelinecaches/") -> found += Kind.SLICERS
                n.startsWith("xl/externallinks/") -> found += Kind.EXTERNAL_LINKS
                n == "xl/connections.xml" || n.startsWith("xl/querytables/") -> found += Kind.DATA_CONNECTIONS
                n.startsWith("xl/model/") -> found += Kind.DATA_MODEL
                n.startsWith("xl/chartsheets/") -> found += Kind.CHART_SHEETS
                "/charts/chartex" in n -> found += Kind.NEW_CHART_TYPES
                n.startsWith("xl/threadedcomments/") -> found += Kind.COMMENT_THREADS
                "/ink/" in n -> found += Kind.INK
                n.startsWith("_xmlsignatures/") -> found += Kind.SIGNATURES
            }
        }
        // Objects the document, a slide or a sheet embeds (OLE objects, packages, alternative-format
        // chunks) and controls; a chart's own embedded workbook — its data, which the chart keeps — is not one.
        for (rels in names.filter { it.endsWith(".rels") && "charts/_rels/" !in it.lowercase() }) {
            val text = open(rels)?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: continue
            // A slide's picture that is a video or sound: AeroDesk keeps it (the file's copy, p14:media, or a link elsewhere).
            val slide = rels.startsWith("ppt/slides/_rels/")
            for (rel in RELATIONSHIP.findAll(text)) {
                val attrs = rel.value
                val type = REL_TYPE.find(attrs)?.groupValues?.get(1) ?: continue
                when (type.substringAfterLast('/')) {
                    "oleObject", "package", "aFChunk" -> found += Kind.EMBEDDED_OBJECTS
                    "control" -> found += Kind.CONTROLS
                    // Workbook parts may live anywhere in the package.
                    "chartsheet" -> found += Kind.CHART_SHEETS
                    "pivotTable", "pivotCacheDefinition", "pivotCacheRecords" -> found += Kind.PIVOT_TABLES
                    "slicer", "slicerCache", "timeline", "timelineCache" -> found += Kind.SLICERS
                    "externalLink" -> found += Kind.EXTERNAL_LINKS
                    "connections", "queryTable" -> found += Kind.DATA_CONNECTIONS
                    "threadedComment" -> found += Kind.COMMENT_THREADS
                    "media" -> if (slide) target(rels, attrs)?.let { kept += it } else found += Kind.MEDIA
                    // A slide's link to a video or sound elsewhere is kept as it is; one to a part is counted by its file.
                    "video", "audio" -> if (!slide) found += Kind.MEDIA
                }
            }
        }
    }

    private val RELATIONSHIP = Regex("""<Relationship\b[^>]*>""")
    private val REL_TYPE = Regex("""Type="([^"]+)"""")
    private val REL_TARGET = Regex("""Target="([^"]+)"""")

    /** The part a relationship in [rels] (`ppt/slides/_rels/slide1.xml.rels`) points to: `../media/media1.mp4` → `ppt/media/media1.mp4`. */
    private fun target(rels: String, attrs: String): String? {
        val t = REL_TARGET.find(attrs)?.groupValues?.get(1) ?: return null
        if ("TargetMode=\"External\"" in attrs) return null
        val base = rels.substringBefore("_rels/").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (seg in t.split('/')) when (seg) { "", "." -> {}; ".." -> if (base.isNotEmpty()) base.removeAt(base.size - 1); else -> base += seg }
        return base.joinToString("/")
    }

    private fun odf(names: Collection<String>, open: (String) -> InputStream?, found: MutableSet<Kind>, kept: MutableSet<String>) {
        // Basic modules (an empty library writes only its script-lb / script-lc index) and other script languages.
        if (names.any { it.startsWith("Basic/") && it.count { c -> c == '/' } >= 2 && it.substringAfterLast('/') !in BASIC_INDEX } ||
            names.any { it.startsWith("Scripts/") }) found += Kind.MACROS
        if ("META-INF/documentsignatures.xml" in names || "META-INF/macrosignatures.xml" in names) found += Kind.SIGNATURES
        // Embedded objects: a chart or a formula is read; anything else (a sheet in a text, an OLE object …) is not.
        var docType = open("mimetype")?.use { it.readBytes() }?.toString(Charsets.US_ASCII)?.trim()
        open("META-INF/manifest.xml")?.use { it.readBytes() }?.toString(Charsets.UTF_8)?.let { manifest ->
            for (e in FILE_ENTRY.findAll(manifest)) {
                val path = FULL_PATH.find(e.value)?.groupValues?.get(1) ?: continue
                val type = MEDIA_TYPE.find(e.value)?.groupValues?.get(1) ?: ""
                if (path == "/" && docType.isNullOrEmpty()) docType = type
                if (!OBJECT_PATH.matches(path)) continue
                if (type != "application/vnd.oasis.opendocument.chart" && type != "application/vnd.oasis.opendocument.formula") found += Kind.EMBEDDED_OBJECTS
            }
        }
        // A few elements in the content: forms and controls, plug-ins (sound, video), DataPilot tables, OLE objects, linked sheets.
        // A presentation's slides keep their videos and sounds (a transition's or an effect's sound they do not).
        val presentation = docType?.startsWith("application/vnd.oasis.opendocument.presentation") == true
        val markers = if (presentation) PRESENTATION_MARKERS else CONTENT_MARKERS
        open("content.xml")?.use { input ->
            for (marker in markers(input, markers.keys)) found += markers.getValue(marker)
        }
        if (presentation) open("content.xml")?.use { input ->
            for (tag in startTags(input, "<draw:plugin ")) {
                val href = HREF.find(tag)?.groupValues?.get(1)?.let { OfficeXml.decode(it) } ?: continue
                val mime = PLUGIN_MIME.find(tag)?.groupValues?.get(1)?.let { OfficeXml.decode(it) }
                if (MediaTypes.ofPlugin(href, mime) == null) { found += Kind.EMBEDDED_OBJECTS; continue }   // Flash, an applet …
                com.therealityteam.aeropages.format.odf.OdfPackage.partOf(href, names)?.let { kept += it }
            }
        }
    }

    private val BASIC_INDEX = setOf("script-lb.xml", "script-lc.xml", "dialog-lb.xml", "dialog-lc.xml")
    private val FILE_ENTRY = Regex("""<manifest:file-entry\b[^>]*>""")
    private val FULL_PATH = Regex("""manifest:full-path="([^"]*)"""")
    private val MEDIA_TYPE = Regex("""manifest:media-type="([^"]*)"""")
    private val OBJECT_PATH = Regex("""Object \d+/?""")

    private val CONTENT_MARKERS = mapOf(
        "<form:form " to Kind.CONTROLS, "<draw:control " to Kind.CONTROLS,
        "<draw:plugin " to Kind.MEDIA,
        "<table:data-pilot-table " to Kind.PIVOT_TABLES,
        "<draw:object-ole " to Kind.EMBEDDED_OBJECTS,
        "<table:table-source " to Kind.EXTERNAL_LINKS, "<table:dde-source " to Kind.EXTERNAL_LINKS,
    )

    /** A presentation's: its plug-ins are read one by one ([startTags]); a transition's and an effect's sounds are not kept. */
    private val PRESENTATION_MARKERS = CONTENT_MARKERS - "<draw:plugin " + mapOf("<presentation:sound " to Kind.MEDIA, "<anim:audio " to Kind.MEDIA)

    private val HREF = Regex("""xlink:href="([^"]*)"""")
    private val PLUGIN_MIME = Regex("""draw:mime-type="([^"]*)"""")

    /**
     * The start tags beginning with [start] (`<draw:plugin `) in [input], attributes and all, read in
     * pieces as [markers] reads; a tag left unfinished at the end of a piece is finished from the next.
     */
    internal fun startTags(input: InputStream, start: String): List<String> {
        val out = ArrayList<String>()
        val buf = ByteArray(64 * 1024)
        var text = ""
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            text += String(buf, 0, n, Charsets.ISO_8859_1)
            var from = 0
            var open = -1
            while (true) {
                val i = text.indexOf(start, from)
                if (i < 0) break
                val end = text.indexOf('>', i)
                if (end < 0) { open = i; break }
                // Latin-1 back to its bytes, read as the UTF-8 they are (a file name in Chinese, say).
                out += String(text.substring(i, end + 1).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                from = end + 1
            }
            // Too short to hold [start] whole, the tail can only begin one; an unfinished tag goes on whole (but not for ever).
            text = if (open >= 0 && text.length - open < MAX_TAG) text.substring(open) else text.takeLast(start.length - 1)
        }
        return out
    }

    private const val MAX_TAG = 64 * 1024

    /**
     * Which of [markers] (plain ASCII) occur in [input], read in pieces so a large content part is
     * never held whole; stops early once all are found.
     */
    internal fun markers(input: InputStream, markers: Collection<String>): Set<String> {
        val left = markers.toMutableSet()
        val seen = LinkedHashSet<String>()
        val keep = (markers.maxOfOrNull { it.length } ?: 1) - 1
        val buf = ByteArray(64 * 1024)
        var tail = ""
        while (left.isNotEmpty()) {
            val n = input.read(buf)
            if (n < 0) break
            // Latin-1 keeps one char per byte, so markers split across reads are found in tail + piece.
            val text = tail + String(buf, 0, n, Charsets.ISO_8859_1)
            val it = left.iterator()
            while (it.hasNext()) { val m = it.next(); if (text.contains(m)) { seen += m; it.remove() } }
            tail = if (text.length > keep) text.substring(text.length - keep) else text
        }
        return seen
    }
}
