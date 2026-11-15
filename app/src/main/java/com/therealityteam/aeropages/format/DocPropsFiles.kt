package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.model.DocProps
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Dates as the properties parts write them: W3C date-times, in UTC. */
internal object W3cDates {
    fun format(ms: Long): String = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS).toString()

    /** `2026-10-15T08:00:00Z`, with fractions or an offset, or without a zone (ODF's, taken as UTC); null when unreadable. */
    fun parse(s: String?): Long? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}

/**
 * OOXML's core properties (`docProps/core.xml`, the same in docx, xlsx and pptx): the part, its content type and its
 * relationship from the package.
 */
object OoxmlCore {
    const val PART = "docProps/core.xml"

    fun write(p: DocProps): ByteArray = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """)
        append("""xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" """)
        append("""xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""")
        fun el(name: String, v: String) { if (v.isNotEmpty()) append("<$name>").append(OfficeXml.escape(v)).append("</$name>") }
        el("dc:title", p.title); el("dc:subject", p.subject); el("dc:creator", p.author); el("cp:keywords", p.keywords)
        el("dc:description", p.description); el("cp:lastModifiedBy", p.lastModifiedBy)
        p.revision?.let { append("<cp:revision>$it</cp:revision>") }
        p.created?.let { append("""<dcterms:created xsi:type="dcterms:W3CDTF">${W3cDates.format(it)}</dcterms:created>""") }
        p.modified?.let { append("""<dcterms:modified xsi:type="dcterms:W3CDTF">${W3cDates.format(it)}</dcterms:modified>""") }
        append("</cp:coreProperties>")
    }.toByteArray(Charsets.UTF_8)

    fun read(bytes: ByteArray?): DocProps {
        val root = XmlPull.parse(bytes) ?: return DocProps()
        fun t(name: String) = root.child(name)?.text?.trim().orEmpty()
        return DocProps(t("title"), t("subject"), t("creator"), t("keywords"), t("description"), t("lastModifiedBy"),
            W3cDates.parse(t("created")), W3cDates.parse(t("modified")), t("revision").toIntOrNull())
    }

    /** [contentTypes] with the core part's override. */
    fun types(contentTypes: String): String = contentTypes.replace("</Types>",
        """<Override PartName="/$PART" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")

    /** The package's [rels] with the core part's relationship. */
    fun rels(rels: String): String = rels.replace("</Relationships>",
        """<Relationship Id="rIdCore" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="$PART"/></Relationships>""")
}

/**
 * ODF's `meta.xml`: the generator, the properties (`dc:title`, `dc:subject`, `dc:description`, a `meta:keyword` for
 * each keyword, `meta:initial-creator` the author, `dc:creator` who saved it last), its dates and editing cycles.
 */
object OdfMeta {
    fun write(p: DocProps): ByteArray = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        // (The namespaces carry office:version.)
        append("<office:document-meta ${com.therealityteam.aeropages.format.odf.OdfPackage.NS}><office:meta>")
        append("<meta:generator>Reality Office</meta:generator>")
        fun el(name: String, v: String) { if (v.isNotEmpty()) append("<$name>").append(OfficeXml.escape(v)).append("</$name>") }
        el("dc:title", p.title); el("dc:subject", p.subject); el("dc:description", p.description)
        keywordsOf(p.keywords).forEach { el("meta:keyword", it) }
        el("meta:initial-creator", p.author); el("dc:creator", p.lastModifiedBy)
        p.created?.let { append("<meta:creation-date>${W3cDates.format(it).removeSuffix("Z")}</meta:creation-date>") }
        p.modified?.let { append("<dc:date>${W3cDates.format(it).removeSuffix("Z")}</dc:date>") }
        p.revision?.let { append("<meta:editing-cycles>$it</meta:editing-cycles>") }
        append("</office:meta></office:document-meta>")
    }.toByteArray(Charsets.UTF_8)

    fun read(bytes: ByteArray?): DocProps {
        val meta = XmlPull.parse(bytes)?.child("meta") ?: return DocProps()
        fun t(name: String) = meta.child(name)?.text?.trim().orEmpty()
        return DocProps(t("title"), t("subject"), t("initial-creator"), meta.children("keyword").map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString(", "),
            t("description"), t("creator"), W3cDates.parse(t("creation-date")), W3cDates.parse(t("date")), t("editing-cycles").toIntOrNull())
    }

    /** The keywords one by one: Word keeps them as one text, by commas or semicolons as written. */
    fun keywordsOf(s: String): List<String> = s.split(',', ';', '，', '；').map { it.trim() }.filter { it.isNotEmpty() }
}
