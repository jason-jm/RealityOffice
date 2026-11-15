package com.therealityteam.aeropages.format

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

/**
 * XmlPull helpers shared by the OOXML readers: a namespace-aware parser factory, a tiny DOM
 * ([XmlNode]) for the small parts (workbook, rels, styles, slides, charts, comments …), a
 * streaming skeleton for the big ones (worksheet cells, shared strings) and package-part
 * resolution for `.rels` targets.
 *
 * Elements and attributes are matched by LOCAL name, so the readers do not depend on the
 * namespace prefixes a producer chose (`<x:c>`, `<ns0:sld>`), on attribute order or on
 * whitespace. The namespace is only consulted where local names collide, e.g.
 * `<p:sldId id="256" r:id="rId2"/>` ([R_NS]).
 *
 * Parts are parsed strictly first. A part that turns out not to be well-formed is parsed again
 * in the parser's relaxed mode so that whatever can be salvaged is (a missing end tag closes the
 * elements left open, a truncated part keeps what was read); nothing ever throws out of the
 * readers because of a malformed part.
 */
internal object XmlPull {

    /** The relationships namespace of `r:id` / `r:embed` attributes. */
    const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** XmlPull's optional error-recovery feature (supported by kxml2 and Android's parser). */
    private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"

    /**
     * One factory for the process: `XmlPullParserFactory.newInstance()` does a service lookup each
     * time and costs tens of milliseconds on Android, which added up to a second per document open
     * (an Office file is a dozen or more parts). Parser creation itself is cheap.
     */
    private val factory: XmlPullParserFactory by lazy { XmlPullParserFactory.newInstance().also { it.isNamespaceAware = true } }

    /** A namespace-aware pull parser over [bytes]; the encoding is auto-detected (BOM / declaration, UTF-8 default). */
    fun newParser(bytes: ByteArray, relaxed: Boolean = false): XmlPullParser {
        val parser = com.therealityteam.aeropages.platform.Trace.section("xml.newParser") { synchronized(factory) { factory.newPullParser() } }
        return parser.apply {
            if (relaxed) runCatching { setFeature(FEATURE_RELAXED, true) }
            setInput(ByteArrayInputStream(bytes), null)
        }
    }

    /** Parse a whole part into an [XmlNode] tree (strictly, then relaxed); null when [bytes] is null or nothing could be parsed. */
    fun parse(bytes: ByteArray?): XmlNode? {
        if (bytes == null) return null
        return com.therealityteam.aeropages.platform.Trace.section("xml.parse") {
            parseTree(bytes, relaxed = false) ?: parseTree(bytes, relaxed = true)?.also { noteSalvaged() }
        }
    }

    /**
     * Parts this thread has read only by salvaging malformed XML (the relaxed retry), since the last
     * [takeSalvaged]: a document read so is damaged, and some of it may be missing. A file is read on
     * one thread from start to end, so a session asks right after reading it.
     */
    private val salvaged = ThreadLocal.withInitial { 0 }
    fun takeSalvaged(): Int { val n = salvaged.get() ?: 0; salvaged.set(0); return n }
    internal fun noteSalvaged() { salvaged.set((salvaged.get() ?: 0) + 1) }

    private fun parseTree(bytes: ByteArray, relaxed: Boolean): XmlNode? = try {
        val p = newParser(bytes, relaxed)
        var e = p.eventType
        while (e != XmlPullParser.START_TAG && e != XmlPullParser.END_DOCUMENT) e = p.next()
        if (e == XmlPullParser.START_TAG) readSubtree(p) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Streaming skeleton: invokes [onStart] on every START_TAG of [bytes] without building a tree.
     * The handler may consume the element's subtree ([readSubtree] / [readText]); the walk then
     * resumes after it. Returns false when the part was malformed and the walk stopped early
     * (nothing is thrown); see [recover] for the strict-then-relaxed retry.
     */
    fun forEachStart(bytes: ByteArray, relaxed: Boolean = false, onStart: (XmlPullParser) -> Unit): Boolean {
        try {
            val p = newParser(bytes, relaxed)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) onStart(p)
                e = p.next()
            }
            // Elements still open at the end: the part was cut off, which no parser reports as an error.
            if (p.depth > 0) noteSalvaged()
            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Run [build] with a strict walk ([build] returns its result plus whether the walk completed);
     * when the part turned out to be malformed, rebuild it from scratch with a relaxed walk and
     * take that best-effort result as it is.
     */
    fun <T> recover(build: (relaxed: Boolean) -> Pair<T, Boolean>): T {
        val (strict, complete) = build(false)
        if (complete) return strict
        noteSalvaged()
        return build(true).first
    }

    /**
     * Read the element at the current START_TAG and its subtree into an [XmlNode]; leaves the
     * parser on the element's END_TAG. In relaxed mode an end tag that does not match the open
     * element (kxml2 reports the open element and synthesizes the missing closes, Android's
     * parser reports the tag actually read) unwinds to the matching open element, so an unclosed
     * child is simply closed; a stray end tag with no open match is ignored.
     */
    fun readSubtree(p: XmlPullParser): XmlNode {
        val root = nodeOf(p)
        val stack = ArrayList<XmlNode>().apply { add(root) }
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    val node = nodeOf(p)
                    stack[stack.size - 1].children.add(node)
                    stack.add(node)
                }
                XmlPullParser.TEXT -> stack[stack.size - 1].let { it.text += p.text }
                XmlPullParser.END_TAG -> {
                    val local = p.name.substringAfterLast(':')
                    var open = stack.size - 1
                    while (open >= 0 && stack[open].name != local) open--
                    if (open < 0) continue
                    while (stack.size > open) stack.removeAt(stack.size - 1)
                    if (stack.isEmpty()) return root
                }
                // The part ends inside the element: cut off (a parser reads that as the end, strict or not).
                XmlPullParser.END_DOCUMENT -> { noteSalvaged(); return root }
            }
        }
    }

    /**
     * Consume the current element's subtree and return its text: every descendant text node when
     * [tag] is null, otherwise only the text of descendant elements named [tag] (with [skip]
     * subtrees — e.g. phonetic `rPh` runs — left out). Leaves the parser on the element's END_TAG.
     */
    fun readText(p: XmlPullParser, tag: String? = null, skip: String? = null): String {
        val sb = StringBuilder()
        var depth = 0        // relative to the element the parser was on
        var tagDepth = -1    // depth of the [tag] element currently being collected
        var skipDepth = -1   // depth of the [skip] element currently being skipped
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (skipDepth < 0 && skip != null && p.name == skip) skipDepth = depth
                    if (tagDepth < 0 && skipDepth < 0 && tag != null && p.name == tag) tagDepth = depth
                }
                XmlPullParser.TEXT -> if (skipDepth < 0 && (tag == null || tagDepth >= 0)) sb.append(p.text)
                XmlPullParser.END_TAG -> {
                    if (depth == tagDepth) tagDepth = -1
                    if (depth == skipDepth) skipDepth = -1
                    if (depth == 0) return sb.toString()
                    depth--
                }
                XmlPullParser.END_DOCUMENT -> { noteSalvaged(); return sb.toString() }
            }
        }
    }

    private fun nodeOf(p: XmlPullParser): XmlNode {
        val n = p.attributeCount
        val attrs: List<XmlAttr> = if (n <= 0) emptyList() else ArrayList<XmlAttr>(n).apply {
            for (i in 0 until n) add(XmlAttr(p.getAttributeNamespace(i) ?: "", p.getAttributeName(i), p.getAttributeValue(i)))
        }
        return XmlNode(p.namespace ?: "", p.name, attrs)
    }

    /** One `<Relationship>` of a `.rels` part. */
    class Rel(val id: String, val type: String, val target: String)

    /** The relationships of a `.rels` part in document order (empty when the part is absent or unreadable). */
    fun rels(bytes: ByteArray?): List<Rel> =
        parse(bytes)?.descendants("Relationship")?.mapNotNull { r ->
            val id = r.attr("Id") ?: return@mapNotNull null
            val target = r.attr("Target") ?: return@mapNotNull null
            Rel(id, r.attr("Type") ?: "", target)
        } ?: emptyList()

    /** The `.rels` part describing [part]: `xl/worksheets/sheet1.xml` → `xl/worksheets/_rels/sheet1.xml.rels`. */
    fun relsOf(part: String): String {
        val slash = part.lastIndexOf('/')
        return if (slash < 0) "_rels/$part.rels" else part.substring(0, slash + 1) + "_rels/" + part.substring(slash + 1) + ".rels"
    }

    /** Resolve a relationship [target] against the [source] part: relative (`../media/1.png`) or package-absolute (`/xl/styles.xml`). */
    fun resolve(source: String, target: String): String {
        val t = target.replace('\\', '/')
        if (t.startsWith("/")) return t.trimStart('/')
        val segs = ArrayList<String>()
        val dir = source.substringBeforeLast('/', "")
        if (dir.isNotEmpty()) segs.addAll(dir.split('/'))
        for (seg in t.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1)
            else -> segs.add(seg)
        }
        return segs.joinToString("/")
    }

    /**
     * The key in [files] that [target] (taken from [source]'s rels) refers to, or null when that
     * part is absent. Targets that are already package-relative (`xl/worksheets/sheet1.xml`
     * without a leading slash) are tolerated as a fallback.
     */
    fun partName(files: Map<String, ByteArray>, source: String, target: String): String? {
        val resolved = resolve(source, target)
        if (files.containsKey(resolved)) return resolved
        val raw = target.replace('\\', '/').trimStart('/')
        return if (files.containsKey(raw)) raw else null
    }
}

/** An attribute of an [XmlNode]; [ns] is "" for unprefixed attributes. */
internal class XmlAttr(val ns: String, val name: String, val value: String)

/** A minimal DOM element: namespace + local [name], attributes, child elements and its direct text. */
internal class XmlNode(val ns: String, val name: String, val attrs: List<XmlAttr>) {
    /** Child elements in document order. */
    val children: MutableList<XmlNode> = ArrayList()

    /** Concatenated direct text content (entities already decoded by the parser). */
    var text: String = ""

    /** Namespace-agnostic lookup by local [name]; an unprefixed attribute wins over a prefixed one of the same local name. */
    fun attr(name: String): String? {
        var prefixed: String? = null
        for (a in attrs) if (a.name == name) {
            if (a.ns.isEmpty()) return a.value
            if (prefixed == null) prefixed = a.value
        }
        return prefixed
    }

    /** Namespace-aware lookup, e.g. `attr(XmlPull.R_NS, "id")` for `r:id`. */
    fun attr(ns: String, name: String): String? = attrs.firstOrNull { it.ns == ns && it.name == name }?.value

    /** First child element with local [name]. */
    fun child(name: String): XmlNode? = children.firstOrNull { it.name == name }

    /** Child elements with local [name], in document order. */
    fun children(name: String): List<XmlNode> = children.filter { it.name == name }

    /** Descendant elements with local [name], in document order (depth first). */
    fun descendants(name: String): List<XmlNode> = ArrayList<XmlNode>().also { collect(setOf(name), it) }

    /** Descendant elements with any of the local [names], in document order (depth first) — shapes and lines as they are drawn. */
    fun descendants(names: Set<String>): List<XmlNode> = ArrayList<XmlNode>().also { collect(names, it) }

    private fun collect(names: Set<String>, out: MutableList<XmlNode>) {
        for (c in children) {
            if (c.name in names) out.add(c)
            c.collect(names, out)
        }
    }

    /** True when any descendant has local [name]. */
    fun has(name: String): Boolean = children.any { it.name == name || it.has(name) }

    /** Concatenated [text] of every descendant named [name], in document order (e.g. all `a:t` runs). */
    fun textOf(name: String): String = buildString { descendants(name).forEach { append(it.text) } }
}

/** Namespace-agnostic attribute lookup on a parser positioned at a START_TAG (unprefixed attribute preferred). */
internal fun XmlPullParser.attr(name: String): String? {
    var prefixed: String? = null
    for (i in 0 until attributeCount) if (getAttributeName(i) == name) {
        if (getAttributeNamespace(i).isNullOrEmpty()) return getAttributeValue(i)
        if (prefixed == null) prefixed = getAttributeValue(i)
    }
    return prefixed
}

/** Namespace-aware attribute lookup on a parser positioned at a START_TAG, e.g. `attr(XmlPull.R_NS, "id")`. */
internal fun XmlPullParser.attr(ns: String, name: String): String? = getAttributeValue(ns, name)
