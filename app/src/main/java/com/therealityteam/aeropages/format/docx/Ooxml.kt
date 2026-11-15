package com.therealityteam.aeropages.format.docx

/** Shared OOXML constants and unit conversions used by the DOCX reader/writer. */
internal object Ooxml {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    /** Markup compatibility: `mc:AlternateContent` holds a `mc:Choice` (modern markup) and a `mc:Fallback` (the same content again, for old readers). */
    const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"

    // DrawingML uses English Metric Units: 1 point = 12700 EMU.
    fun emuToPt(emu: Long): Float = emu / 12700f

    // Word stores most measurements in twips: 1 point = 20 twips, 1 inch = 1440 twips.
    fun twipsToPt(twips: Int): Float = twips / 20f
    fun ptToTwips(pt: Float): Int = kotlin.math.round(pt * 20f).toInt()

    /** A tab's leader (`w:leader`): dots, hyphens, a rule (thin or heavy), middle dots, or none. */
    fun parseLeader(v: String?): com.therealityteam.aeropages.model.TabLeader = when (v) {
        "dot" -> com.therealityteam.aeropages.model.TabLeader.DOT
        "hyphen" -> com.therealityteam.aeropages.model.TabLeader.HYPHEN
        "underscore", "heavy" -> com.therealityteam.aeropages.model.TabLeader.UNDERSCORE
        "middleDot" -> com.therealityteam.aeropages.model.TabLeader.MIDDLE_DOT
        else -> com.therealityteam.aeropages.model.TabLeader.NONE
    }

    /** `w:tabs` for [stops]. */
    fun tabsXml(stops: List<com.therealityteam.aeropages.model.TabStop>): String = buildString {
        append("<w:tabs>")
        for (t in stops) {
            val v = when (t.align) {
                com.therealityteam.aeropages.model.TabAlign.LEFT -> "left"
                com.therealityteam.aeropages.model.TabAlign.CENTER -> "center"
                com.therealityteam.aeropages.model.TabAlign.RIGHT -> "right"
                com.therealityteam.aeropages.model.TabAlign.DECIMAL -> "decimal"
            }
            append("""<w:tab w:val="$v" w:pos="${ptToTwips(t.posPt)}"""")
            when (t.leader) {
                com.therealityteam.aeropages.model.TabLeader.NONE -> Unit
                com.therealityteam.aeropages.model.TabLeader.DOT -> append(""" w:leader="dot"""")
                com.therealityteam.aeropages.model.TabLeader.HYPHEN -> append(""" w:leader="hyphen"""")
                com.therealityteam.aeropages.model.TabLeader.UNDERSCORE -> append(""" w:leader="underscore"""")
                com.therealityteam.aeropages.model.TabLeader.MIDDLE_DOT -> append(""" w:leader="middleDot"""")
            }
            append("/>")
        }
        append("</w:tabs>")
    }

    /** A `w:ind`: [left], [right] and the first line's indent ([first], negative: hanging) in points, null sides left out. */
    fun indXml(left: Float?, first: Float?, right: Float?): String = buildString {
        append("<w:ind")
        left?.let { append(""" w:left="${ptToTwips(it)}"""") }
        right?.let { append(""" w:right="${ptToTwips(it)}"""") }
        first?.let { append(if (it < 0f) """ w:hanging="${ptToTwips(-it)}"""" else """ w:firstLine="${ptToTwips(it)}"""") }
        append("/>")
    }

    // Font sizes (w:sz) are in half-points.
    fun halfPtToPt(halfPt: Int): Float = halfPt / 2f
    fun ptToHalfPt(pt: Float): Int = (pt * 2f).toInt()

    /** Parse an OOXML hex colour "RRGGBB" (or "auto") into 0xAARRGGBB. */
    fun parseColor(value: String?): Long? {
        if (value == null || value.equals("auto", ignoreCase = true)) return null
        val hex = value.removePrefix("#")
        if (hex.length != 6) return null
        return try {
            0xFF000000L or hex.toLong(16)
        } catch (e: NumberFormatException) {
            null
        }
    }

    /** Format 0xAARRGGBB back to "RRGGBB" for writing. */
    fun colorToHex(color: Long): String {
        val rgb = (color and 0xFFFFFF).toInt()
        return String.format(java.util.Locale.ROOT, "%06X", rgb)
    }

    /** Named highlight colours used by `w:highlight`. */
    private val HIGHLIGHTS: Map<String, Long> = mapOf(
        "yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF,
        "magenta" to 0xFFFF00FF, "red" to 0xFFFF0000, "blue" to 0xFF0000FF,
        "darkGray" to 0xFFA9A9A9, "lightGray" to 0xFFD3D3D3, "black" to 0xFF000000,
        "darkBlue" to 0xFF00008B, "darkGreen" to 0xFF006400, "darkRed" to 0xFF8B0000,
        "darkYellow" to 0xFF808000, "white" to 0xFFFFFFFF,
    )

    fun parseHighlight(name: String?): Long? =
        name?.let { HIGHLIGHTS[it] }

    fun highlightName(color: Long): String =
        HIGHLIGHTS.entries.firstOrNull { it.value == color }?.key ?: "yellow"

    /** Map `w:jc` value <-> our alignment. */
    fun parseJc(value: String?): com.therealityteam.aeropages.model.TextAlign = when (value) {
        "center" -> com.therealityteam.aeropages.model.TextAlign.CENTER
        "right", "end" -> com.therealityteam.aeropages.model.TextAlign.RIGHT
        "both", "distribute", "justify" -> com.therealityteam.aeropages.model.TextAlign.JUSTIFY
        else -> com.therealityteam.aeropages.model.TextAlign.LEFT
    }

    fun jcValue(align: com.therealityteam.aeropages.model.TextAlign): String = when (align) {
        com.therealityteam.aeropages.model.TextAlign.CENTER -> "center"
        com.therealityteam.aeropages.model.TextAlign.RIGHT -> "right"
        com.therealityteam.aeropages.model.TextAlign.JUSTIFY -> "both"
        com.therealityteam.aeropages.model.TextAlign.LEFT -> "left"
    }

    /** Escape text for inclusion in XML element content / attributes (drops characters XML forbids). */
    fun escape(s: String): String = com.therealityteam.aeropages.format.OfficeXml.escape(s)

    /** OOXML `w:date` (xsd:dateTime, UTC, whole seconds) from epoch ms. */
    fun isoDate(ms: Long): String = java.time.Instant.ofEpochMilli(ms).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()

    /** Epoch ms from an OOXML `w:date` (with or without a zone); null when absent or unparseable. */
    fun parseDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }
}
