package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.XmlPull
import org.xmlpull.v1.XmlPullParser
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * A tiny ordered DOM for OpenDocument parts.
 *
 * The OOXML readers use [com.therealityteam.aeropages.format.XmlNode], which keeps a node's text
 * separately from its children. ODF paragraphs are mixed content — `<text:p>A<text:span>B</text:span>C</text:p>` —
 * so the order of text and elements is the content, and it has to be preserved: [parts] holds
 * both, in document order.
 *
 * Elements and attributes are matched by local name (the parser is namespace-aware), so a
 * producer's choice of prefixes does not matter. Parsing is strict first, then relaxed, like the
 * other readers: a malformed part yields whatever could be read rather than an exception.
 */
internal class OdfNode(val name: String, val attrs: Map<String, String>) {

    /** Text and child elements in document order (`String` or [OdfNode]). */
    val parts: MutableList<Any> = ArrayList()

    val children: List<OdfNode> get() = parts.filterIsInstance<OdfNode>()

    fun attr(name: String): String? = attrs[name]

    fun child(name: String): OdfNode? = children.firstOrNull { it.name == name }

    fun children(name: String): List<OdfNode> = children.filter { it.name == name }

    fun descendants(name: String): List<OdfNode> = ArrayList<OdfNode>().also { collect(name, it) }

    private fun collect(name: String, out: MutableList<OdfNode>) {
        for (c in children) {
            if (c.name == name) out.add(c)
            c.collect(name, out)
        }
    }

    /** All text under this node, in order. */
    val text: String
        get() = buildString {
            for (p in parts) when (p) {
                is String -> append(p)
                is OdfNode -> append(p.text)
            }
        }

    companion object {
        /** Parse a part; null when it is missing or nothing could be salvaged. */
        fun parse(bytes: ByteArray?): OdfNode? {
            if (bytes == null) return null
            return parse(bytes, relaxed = false) ?: parse(bytes, relaxed = true)?.also { XmlPull.noteSalvaged() }
        }

        private fun parse(bytes: ByteArray, relaxed: Boolean): OdfNode? = try {
            val p = XmlPull.newParser(bytes, relaxed)
            var e = p.eventType
            while (e != XmlPullParser.START_TAG && e != XmlPullParser.END_DOCUMENT) e = p.next()
            if (e == XmlPullParser.START_TAG) read(p) else null
        } catch (e: Exception) {
            null
        }

        /** Read the element the parser sits on, with its subtree. */
        private fun read(p: XmlPullParser): OdfNode {
            val attrs = HashMap<String, String>(p.attributeCount)
            for (i in 0 until p.attributeCount) attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
            val node = OdfNode(p.name ?: "", attrs)
            var depth = 1
            while (depth > 0) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> node.parts.add(read(p))
                    XmlPullParser.TEXT -> p.text?.let { if (it.isNotEmpty()) node.parts.add(it) }
                    XmlPullParser.END_TAG -> depth--
                    // The part ends inside the element: cut off.
                    XmlPullParser.END_DOCUMENT -> { XmlPull.noteSalvaged(); return node }
                }
            }
            return node
        }
    }
}

/** Lengths, colours and the other value forms ODF attributes use. */
internal object OdfValue {

    /** `12.5pt`, `0.5in`, `2.54cm`, `10mm`, `1pc`, `16px` → points; null when absent or unparsable. */
    fun lengthPt(v: String?): Float? {
        val s = v?.trim()?.lowercase() ?: return null
        val unit = when {
            s.endsWith("pt") -> 1f
            s.endsWith("in") -> 72f
            s.endsWith("cm") -> 72f / 2.54f
            s.endsWith("mm") -> 72f / 25.4f
            s.endsWith("pc") -> 12f
            s.endsWith("px") -> 0.75f
            else -> return s.toFloatOrNull()
        }
        return s.dropLast(2).trim().toFloatOrNull()?.times(unit)
    }

    /**
     * [lengthPt] to the nearest twentieth of a point (Word's step), for a page's or a paragraph's
     * measures: LibreOffice keeps lengths in hundredths of a millimetre, so the 8 pt it was given
     * comes back as 7.9937 pt and an A4 page as 594.99 pt.
     */
    fun twipsPt(v: String?): Float? = lengthPt(v)?.let { Math.round(it * 20f) / 20f }

    /** `#RRGGBB` → 0xFFRRGGBB; null for "transparent" / absent. */
    fun color(v: String?): Long? {
        val s = v?.trim() ?: return null
        if (!s.startsWith("#") || s.length < 7) return null
        return s.substring(1, 7).toLongOrNull(16)?.or(0xFF000000L)
    }

    /**
     * An ODF angle in degrees: "30deg", "0.52rad", "33grad", or a bare number — tenths of a degree,
     * as LibreOffice has always written gradients' angles.
     */
    fun angleDeg(v: String?): Float? {
        val s = v?.trim()?.lowercase() ?: return null
        return when {
            s.endsWith("deg") -> s.dropLast(3).toFloatOrNull()
            s.endsWith("grad") -> s.dropLast(4).toFloatOrNull()?.times(0.9f)
            s.endsWith("rad") -> s.dropLast(3).toFloatOrNull()?.let { Math.toDegrees(it.toDouble()).toFloat() }
            else -> s.toFloatOrNull()?.div(10f)
        }
    }

    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private val DURATION = Regex("""^(-)?P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$""")

    /**
     * An `office:date-value` (`2026-09-29`, `2026-09-29T09:30:00`) as a spreadsheet serial — days
     * since 1899-12-30, the time of day as the fraction — to 15 significant digits; null when unreadable.
     */
    fun dateSerial(v: String): String? {
        val s = v.trim()
        val date = runCatching { LocalDate.parse(s.take(10)) }.getOrNull() ?: return null
        val days = ChronoUnit.DAYS.between(EPOCH, date).toDouble()
        val time = s.substringAfter('T', "").takeIf { it.isNotEmpty() }?.let { t ->
            val p = t.trimEnd('Z').substringBefore('+').split(':')
            val h = p.getOrNull(0)?.toDoubleOrNull() ?: return null
            val m = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            val sec = p.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            (h * 3600 + m * 60 + sec) / 86_400.0
        } ?: 0.0
        return serial(days + time)
    }

    /** An `office:time-value` (an ISO duration: `PT09H30M00S`, `PT36H`, `P1DT2H`) as a fraction of days. */
    fun timeSerial(v: String): String? {
        val m = DURATION.matchEntire(v.trim()) ?: return null
        val g = m.groupValues
        val seconds = (g[2].toDoubleOrNull() ?: 0.0) * 86_400 + (g[3].toDoubleOrNull() ?: 0.0) * 3600 +
            (g[4].toDoubleOrNull() ?: 0.0) * 60 + (g[5].toDoubleOrNull() ?: 0.0)
        return serial((if (g[1] == "-") -seconds else seconds) / 86_400.0)
    }

    private fun serial(v: Double): String =
        BigDecimal(v).round(MathContext(15)).stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()

    fun lineHeight(v: String?, base: Float = 1f): Float? {
        val s = v?.trim() ?: return null
        if (s.endsWith("%")) return s.dropLast(1).toFloatOrNull()?.div(100f)
        val pt = lengthPt(s) ?: return null
        return if (base > 0f) pt / base else null
    }

    fun bold(v: String?): Boolean = v != null && (v == "bold" || (v.toIntOrNull() ?: 0) >= 600)

    fun on(v: String?): Boolean = v != null && v != "none"
}
