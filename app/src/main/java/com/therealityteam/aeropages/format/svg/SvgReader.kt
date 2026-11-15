package com.therealityteam.aeropages.format.svg

import com.therealityteam.aeropages.format.SvgPath
import com.therealityteam.aeropages.format.odf.OdfNode
import com.therealityteam.aeropages.model.Freeform
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.math.sqrt

/**
 * Reads SVG — as Inkscape, Illustrator, LibreOffice and the icon sets write it — into an
 * [SvgPicture]: shapes (`path`, `rect` with round corners, `circle`, `ellipse`, `line`,
 * `polyline`, `polygon`), groups and `use`, nested `svg`, text and `tspan`, embedded pictures;
 * styles from presentation attributes, `style` attributes and `<style>` sheets (type, class and id
 * selectors), inherited as CSS inherits them; colours in every CSS form, linear and radial
 * gradients (their `href` chains, units, transforms and spreads) and clip paths. Masks, filters,
 * patterns and markers are left out: what they apply to is drawn plain.
 */
internal object SvgReader {

    /** Whether [data] is an SVG picture (plain or gzipped): an `<svg` root after the prolog. */
    fun isSvg(data: ByteArray): Boolean {
        val bytes = unzipped(data, limit = 4096) ?: return false
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        return SVG_ROOT.containsMatchIn(head)
    }

    private val SVG_ROOT = Regex("""<(?:\w+:)?svg[\s>/]""")

    /** Its declared size in CSS pixels (its view box's when it declares none, 300 × 150 at worst), or null when it is no SVG. */
    fun size(data: ByteArray): Pair<Float, Float>? {
        if (!isSvg(data)) return null
        val root = root(data) ?: return null
        val (w, h) = rootSize(root)
        return w to h
    }

    /** [data] read, or null when it is no SVG (or nothing of it could be parsed). */
    fun read(data: ByteArray): SvgPicture? = runCatching {
        val root = root(data) ?: return null
        Reader(root).picture()
    }.getOrNull()

    private fun unzipped(data: ByteArray, limit: Int = Int.MAX_VALUE): ByteArray? {
        if (data.size >= 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) {
            return runCatching { GZIPInputStream(ByteArrayInputStream(data)).use { z -> if (limit == Int.MAX_VALUE) z.readBytes() else z.readNBytes(limit) } }.getOrNull()
        }
        return data
    }

    private fun root(data: ByteArray): OdfNode? {
        val doc = OdfNode.parse(unzipped(data) ?: return null) ?: return null
        return if (doc.name == "svg") doc else doc.descendants("svg").firstOrNull()
    }

    private fun rootSize(root: OdfNode): Pair<Float, Float> {
        val box = viewBoxOf(root)
        fun len(v: String?, whole: Float?): Float? {
            val t = v?.trim() ?: return null
            if (t.endsWith("%")) return whole?.let { w -> t.dropLast(1).toFloatOrNull()?.let { it / 100f * w } }
            return Units.length(t, 16f, 0f)
        }
        val w = len(root.attr("width"), box?.get(2)) ?: box?.get(2) ?: 300f
        val h = len(root.attr("height"), box?.get(3)) ?: box?.get(3) ?: (if (box != null && box[2] > 0f) w * box[3] / box[2] else 150f)
        return w.coerceAtLeast(1f) to h.coerceAtLeast(1f)
    }

    private fun viewBoxOf(n: OdfNode): FloatArray? =
        n.attr("viewBox")?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 && it[2] > 0f && it[3] > 0f }?.toFloatArray()

    /** Lengths in CSS units. */
    object Units {
        private val LENGTH = Regex("""^([-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?)\s*(px|pt|pc|mm|cm|in|em|ex|%)?$""")

        /** [v] in pixels: [fontSize] for em / ex, [whole] for percentages. Null when it is no length. */
        fun length(v: String?, fontSize: Float, whole: Float): Float? {
            val m = LENGTH.matchEntire(v?.trim() ?: return null) ?: return null
            val n = m.groupValues[1].toFloatOrNull() ?: return null
            return when (m.groupValues[2]) {
                "", "px" -> n
                "pt" -> n * 96f / 72f
                "pc" -> n * 16f
                "mm" -> n * 96f / 25.4f
                "cm" -> n * 96f / 2.54f
                "in" -> n * 96f
                "em" -> n * fontSize
                "ex" -> n * fontSize / 2f
                "%" -> n / 100f * whole
                else -> n
            }
        }
    }

    /** Properties an element passes to what it holds (CSS inheritance). */
    private val INHERITED = setOf(
        "fill", "fill-opacity", "fill-rule", "stroke", "stroke-width", "stroke-opacity", "stroke-linecap", "stroke-linejoin",
        "stroke-miterlimit", "stroke-dasharray", "stroke-dashoffset", "visibility", "clip-rule", "color",
        "font-family", "font-size", "font-weight", "font-style", "text-anchor",
    )

    /** Properties an element sets for itself alone. */
    private val OWN = setOf("opacity", "display", "clip-path", "mask", "stop-color", "stop-opacity", "filter")

    private val PROPERTIES = INHERITED + OWN

    /** A `<style>` sheet's rule: a simple selector — element, classes, id — and its declarations. */
    private class Rule(val type: String?, val classes: List<String>, val id: String?, val decls: Map<String, String>, val order: Int) {
        val specificity: Int get() = (if (id != null) 10000 else 0) + classes.size * 100 + (if (type != null) 1 else 0)
        fun matches(n: OdfNode, classSet: Set<String>): Boolean =
            (type == null || type == n.name) && (id == null || id == n.attr("id")) && classes.all { it in classSet }
    }

    private class Reader(val root: OdfNode) {
        val ids = HashMap<String, OdfNode>()
        val rules = ArrayList<Rule>()
        val viewport: FloatArray
        val gradients = HashMap<String, SvgGradient?>()
        val clips = HashMap<String, SvgClip?>()
        /** Elements being drawn through `use` (a `use` of its own ancestor would loop). */
        val using = HashSet<OdfNode>()

        init {
            fun index(n: OdfNode) {
                n.attr("id")?.let { ids.putIfAbsent(it, n) }
                if (n.name == "style") css(n.text)
                n.children.forEach { index(it) }
            }
            index(root)
            val box = viewBoxOf(root)
            val (w, h) = rootSize(root)
            viewport = box ?: floatArrayOf(0f, 0f, w, h)
        }

        fun picture(): SvgPicture {
            val (w, h) = rootSize(root)
            val style = computed(root, emptyMap())
            val items = children(root, style)
            return SvgPicture(w, h, viewport, SvgAspect.parse(root.attr("preserveAspectRatio")), items)
        }

        // --- styles ------------------------------------------------------------------------------

        private fun css(text: String) {
            val clean = text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            // At-rules' own blocks (fonts, keyframes) are skipped; @media's rules are read as they are.
            var i = 0
            while (i < clean.length) {
                val open = clean.indexOf('{', i)
                if (open < 0) break
                val selector = clean.substring(i, open).trim()
                if (selector.startsWith("@media")) { i = open + 1; continue }
                val close = clean.indexOf('}', open)
                if (close < 0) break
                if (!selector.startsWith("@")) {
                    val decls = declarations(clean.substring(open + 1, close))
                    for (s in selector.split(',')) simpleSelector(s.trim())?.let { (t, cls, id) -> rules.add(Rule(t, cls, id, decls, rules.size)) }
                }
                i = close + 1
            }
        }

        /** An element / class / id selector (`rect.cls-1`, `.st0`, `#logo`, `*`), or null for one with combinators. */
        private fun simpleSelector(s: String): Triple<String?, List<String>, String?>? {
            if (s.isEmpty() || s.any { it.isWhitespace() || it in ">+~[:" }) return null
            val m = Regex("""^(\*|[A-Za-z][\w-]*)?((?:[.#][\w-]+)*)$""").matchEntire(s) ?: return null
            val type = m.groupValues[1].takeIf { it.isNotEmpty() && it != "*" }
            val parts = Regex("""[.#][\w-]+""").findAll(m.groupValues[2]).map { it.value }.toList()
            return Triple(type, parts.filter { it.startsWith(".") }.map { it.drop(1) }, parts.firstOrNull { it.startsWith("#") }?.drop(1))
        }

        private fun declarations(s: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (d in s.split(';')) {
                val colon = d.indexOf(':')
                if (colon <= 0) continue
                val k = d.substring(0, colon).trim().lowercase()
                val v = d.substring(colon + 1).trim().removeSuffix("!important").trim()
                if (k.isNotEmpty() && v.isNotEmpty()) out[k] = v
            }
            return out
        }

        /** [n]'s style: what its parent passes on, its presentation attributes, the sheet's rules, then its `style`. */
        fun computed(n: OdfNode, parent: Map<String, String>): Map<String, String> {
            val out = HashMap<String, String>()
            for ((k, v) in parent) if (k in INHERITED) out[k] = v
            for ((k, v) in n.attrs) if (k in PROPERTIES) out[k] = v.trim()
            if (rules.isNotEmpty()) {
                val classes = n.attr("class")?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet().orEmpty()
                rules.filter { it.matches(n, classes) }.sortedWith(compareBy({ it.specificity }, { it.order })).forEach { r -> out.putAll(r.decls.filterKeys { it in PROPERTIES || it == "font" }) }
            }
            n.attr("style")?.let { st -> out.putAll(declarations(st)) }
            out.remove("font")?.let { font(it, out) }
            for ((k, v) in out.entries.toList()) if (v == "inherit") { val p = parent[k]; if (p != null) out[k] = p else out.remove(k) }
            return out
        }

        /** The `font` shorthand's style, weight, size and family. */
        private fun font(v: String, out: MutableMap<String, String>) {
            val m = Regex("""(?:(italic|oblique)\s+)?(?:(bold|bolder|[1-9]00|normal)\s+)*([\d.]+(?:px|pt|em|%)?)(?:/\S+)?\s+(.+)""").find(v) ?: return
            if (m.groupValues[1].isNotEmpty()) out["font-style"] = "italic"
            if (m.groupValues[2].isNotEmpty()) out["font-weight"] = m.groupValues[2]
            out["font-size"] = m.groupValues[3]
            out["font-family"] = m.groupValues[4]
        }

        private fun fontSize(style: Map<String, String>): Float = Units.length(style["font-size"], 16f, 16f) ?: when (style["font-size"]) {
            "xx-small" -> 9f; "x-small" -> 10f; "small" -> 13f; "large" -> 18f; "x-large" -> 24f; "xx-large" -> 32f
            else -> 16f
        }

        private fun number(v: String?, default: Float): Float = v?.trim()?.let { t ->
            if (t.endsWith("%")) t.dropLast(1).toFloatOrNull()?.div(100f) else t.toFloatOrNull()
        } ?: default

        /** A horizontal length (percentages of the viewport's width). */
        private fun x(v: String?, style: Map<String, String>, default: Float = 0f) = Units.length(v, fontSize(style), viewport[2]) ?: default
        private fun y(v: String?, style: Map<String, String>, default: Float = 0f) = Units.length(v, fontSize(style), viewport[3]) ?: default
        /** Any other length (percentages of the viewport's normalised diagonal). */
        private fun len(v: String?, style: Map<String, String>, default: Float = 0f) =
            Units.length(v, fontSize(style), sqrt((viewport[2] * viewport[2] + viewport[3] * viewport[3]) / 2f)) ?: default

        // --- elements ----------------------------------------------------------------------------

        private val SKIPPED = setOf("defs", "symbol", "clipPath", "mask", "pattern", "marker", "linearGradient", "radialGradient",
            "style", "title", "desc", "metadata", "filter", "script", "font", "font-face", "foreignObject", "cursor", "view")

        fun children(n: OdfNode, style: Map<String, String>): List<SvgItem> {
            if (n.name == "switch") return n.children.firstNotNullOfOrNull { item(it, style) }?.let { listOf(it) } ?: emptyList()
            return n.children.mapNotNull { item(it, style) }
        }

        fun item(n: OdfNode, parent: Map<String, String>): SvgItem? {
            if (n.name in SKIPPED) return null
            val style = computed(n, parent)
            if (style["display"] == "none") return null
            val own = SvgMatrix.parse(n.attr("transform"))
            val opacity = number(style["opacity"], 1f).coerceIn(0f, 1f)
            val clip = style["clip-path"]?.let { urlId(it) }?.let { clipPath(it) }
            val hidden = style["visibility"].let { it == "hidden" || it == "collapse" }
            return when (n.name) {
                "g", "a" -> SvgGroup(children(n, style), own, opacity, clip).takeIf { it.items.isNotEmpty() }
                "switch" -> SvgGroup(children(n, style), own, opacity, clip).takeIf { it.items.isNotEmpty() }
                "svg" -> nested(n, style, own, opacity, clip)
                "use" -> use(n, style, own, opacity, clip)
                "text" -> if (hidden) null else text(n, style, own, opacity, clip)
                "image" -> if (hidden) null else image(n, style, own, opacity, clip)
                else -> if (hidden) null else outline(n, style)?.let { ops -> shape(ops, style, own, opacity, clip, closedOnly = n.name == "line") }
            }
        }

        /** A nested `svg`: its view box fitted into its own box at its x, y. */
        private fun nested(n: OdfNode, style: Map<String, String>, own: FloatArray?, opacity: Float, clip: SvgClip?): SvgItem? {
            val box = viewBoxOf(n)
            val w = x(n.attr("width"), style, box?.get(2) ?: viewport[2])
            val h = y(n.attr("height"), style, box?.get(3) ?: viewport[3])
            var m = SvgMatrix.translate(x(n.attr("x"), style), y(n.attr("y"), style))
            if (box != null) {
                val f = SvgAspect.parse(n.attr("preserveAspectRatio")).fit(box[0], box[1], box[2], box[3], w, h)
                m = SvgMatrix.times(m, floatArrayOf(f[0], 0f, 0f, f[1], f[2], f[3]))
            }
            val items = children(n, style)
            if (items.isEmpty()) return null
            return SvgGroup(items, own?.let { SvgMatrix.times(it, m) } ?: m, opacity, clip)
        }

        /** A `use`: what it names, drawn at its x, y with its style passed on (a `symbol` fitted to its size). */
        private fun use(n: OdfNode, style: Map<String, String>, own: FloatArray?, opacity: Float, clip: SvgClip?): SvgItem? {
            val target = n.attr("href")?.trim()?.removePrefix("#")?.let { ids[it] } ?: return null
            if (!using.add(target)) return null
            try {
                var m = SvgMatrix.translate(x(n.attr("x"), style), y(n.attr("y"), style))
                own?.let { m = SvgMatrix.times(it, m) }
                val drawn: List<SvgItem> = if (target.name == "symbol" || target.name == "svg") {
                    val box = viewBoxOf(target)
                    val tStyle = computed(target, style)
                    if (box != null) {
                        val w = x(n.attr("width") ?: target.attr("width"), style, box[2])
                        val h = y(n.attr("height") ?: target.attr("height"), style, box[3])
                        val f = SvgAspect.parse(target.attr("preserveAspectRatio")).fit(box[0], box[1], box[2], box[3], w, h)
                        m = SvgMatrix.times(m, floatArrayOf(f[0], 0f, 0f, f[1], f[2], f[3]))
                    }
                    children(target, tStyle)
                } else listOfNotNull(item(target, style))
                if (drawn.isEmpty()) return null
                return SvgGroup(drawn, m, opacity, clip)
            } finally {
                using.remove(target)
            }
        }

        /** A basic shape's or a path's outline in its own units, or null when it draws nothing. */
        fun outline(n: OdfNode, style: Map<String, String>): List<Freeform.Op>? {
            val k = 0.5522847498f
            fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float): List<Freeform.Op>? {
                if (rx <= 0f || ry <= 0f) return null
                return listOf(Freeform.Move(cx + rx, cy),
                    Freeform.Cubic(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry),
                    Freeform.Cubic(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy),
                    Freeform.Cubic(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry),
                    Freeform.Cubic(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy), Freeform.Close)
            }
            fun points(): List<Float> = n.attr("points")?.split(Regex("[\\s,]+"))?.mapNotNull { it.toFloatOrNull() }.orEmpty()
            return when (n.name) {
                "path" -> n.attr("d")?.let { SvgPath.parse(it) }?.takeIf { ops -> ops.any { it !is Freeform.Move } }
                "rect" -> {
                    val x0 = x(n.attr("x"), style); val y0 = y(n.attr("y"), style)
                    val w = x(n.attr("width"), style); val h = y(n.attr("height"), style)
                    if (w <= 0f || h <= 0f) return null
                    var rx = n.attr("rx")?.let { x(it, style) }; var ry = n.attr("ry")?.let { y(it, style) }
                    if (rx == null) rx = ry; if (ry == null) ry = rx
                    val cx = (rx ?: 0f).coerceIn(0f, w / 2f); val cy = (ry ?: 0f).coerceIn(0f, h / 2f)
                    if (cx <= 0f || cy <= 0f) listOf(Freeform.Move(x0, y0), Freeform.Line(x0 + w, y0), Freeform.Line(x0 + w, y0 + h), Freeform.Line(x0, y0 + h), Freeform.Close)
                    else listOf(Freeform.Move(x0 + cx, y0), Freeform.Line(x0 + w - cx, y0),
                        Freeform.Cubic(x0 + w - cx + k * cx, y0, x0 + w, y0 + cy - k * cy, x0 + w, y0 + cy), Freeform.Line(x0 + w, y0 + h - cy),
                        Freeform.Cubic(x0 + w, y0 + h - cy + k * cy, x0 + w - cx + k * cx, y0 + h, x0 + w - cx, y0 + h), Freeform.Line(x0 + cx, y0 + h),
                        Freeform.Cubic(x0 + cx - k * cx, y0 + h, x0, y0 + h - cy + k * cy, x0, y0 + h - cy), Freeform.Line(x0, y0 + cy),
                        Freeform.Cubic(x0, y0 + cy - k * cy, x0 + cx - k * cx, y0, x0 + cx, y0), Freeform.Close)
                }
                "circle" -> len(n.attr("r"), style).let { r -> ellipse(x(n.attr("cx"), style), y(n.attr("cy"), style), r, r) }
                "ellipse" -> {
                    val rx = n.attr("rx")?.let { x(it, style) }; val ry = n.attr("ry")?.let { y(it, style) }
                    ellipse(x(n.attr("cx"), style), y(n.attr("cy"), style), rx ?: ry ?: 0f, ry ?: rx ?: 0f)
                }
                "line" -> listOf(Freeform.Move(x(n.attr("x1"), style), y(n.attr("y1"), style)), Freeform.Line(x(n.attr("x2"), style), y(n.attr("y2"), style)))
                "polyline", "polygon" -> points().takeIf { it.size >= 4 }?.let { p ->
                    buildList {
                        add(Freeform.Move(p[0], p[1]))
                        for (i in 2 until p.size - 1 step 2) add(Freeform.Line(p[i], p[i + 1]))
                        if (n.name == "polygon") add(Freeform.Close)
                    }
                }
                else -> null
            }
        }

        /** A shape as its style paints it; [closedOnly]: a `line` is never filled. */
        fun shape(ops: List<Freeform.Op>, style: Map<String, String>, own: FloatArray?, opacity: Float, clip: SvgClip?, closedOnly: Boolean = false): SvgShape? {
            val fill = if (closedOnly) null else paint(style["fill"] ?: "black", style)
            val stroke = paint(style["stroke"] ?: "none", style)
            val width = len(style["stroke-width"], style, 1f)
            if (fill == null && (stroke == null || width <= 0f)) return null
            val dashes = style["stroke-dasharray"]?.takeIf { it != "none" }?.split(Regex("[\\s,]+"))?.mapNotNull { v -> len(v, style, -1f).takeIf { it >= 0f } }
                ?.takeIf { d -> d.isNotEmpty() && d.any { it > 0f } }?.let { d -> (if (d.size % 2 == 1) d + d else d).toFloatArray() }
            return SvgShape(
                ops, own,
                fill, number(style["fill-opacity"], 1f).coerceIn(0f, 1f), style["fill-rule"] == "evenodd",
                stroke?.takeIf { width > 0f }, number(style["stroke-opacity"], 1f).coerceIn(0f, 1f), width,
                when (style["stroke-linecap"]) { "round" -> 1; "square" -> 2; else -> 0 },
                when (style["stroke-linejoin"]) { "round" -> 1; "bevel" -> 2; else -> 0 },
                number(style["stroke-miterlimit"], 4f).coerceAtLeast(1f),
                dashes, len(style["stroke-dashoffset"], style, 0f),
                opacity, clip,
            )
        }

        /** Text and its `tspan`s as runs, their white space collapsed as SVG collapses it. */
        private fun text(n: OdfNode, style: Map<String, String>, own: FloatArray?, opacity: Float, clip: SvgClip?): SvgItem? {
            val spans = ArrayList<SvgSpan>()
            fun collect(node: OdfNode, st: Map<String, String>, first: Boolean) {
                var placeX: Float? = node.attr("x")?.split(Regex("[\\s,]+"))?.firstOrNull()?.let { x(it, st) }
                var placeY: Float? = node.attr("y")?.split(Regex("[\\s,]+"))?.firstOrNull()?.let { y(it, st) }
                var dx = node.attr("dx")?.split(Regex("[\\s,]+"))?.firstOrNull()?.let { x(it, st) } ?: 0f
                var dy = node.attr("dy")?.split(Regex("[\\s,]+"))?.firstOrNull()?.let { y(it, st) } ?: 0f
                if (first) { if (placeX == null) placeX = 0f; if (placeY == null) placeY = 0f }
                for (part in node.parts) {
                    when (part) {
                        is String -> {
                            val t = part.replace(Regex("[\\r\\n\\t]"), " ").replace(Regex(" {2,}"), " ")
                            if (t.isEmpty()) continue
                            spans.add(SvgSpan(t, placeX, placeY, dx, dy, fontSize(st), st["font-family"]?.split(',')?.firstOrNull()?.trim()?.trim('\'', '"'),
                                st["font-weight"].let { it == "bold" || it == "bolder" || (it?.toIntOrNull() ?: 400) >= 600 },
                                st["font-style"] == "italic" || st["font-style"] == "oblique",
                                when (st["text-anchor"]) { "middle" -> 1; "end" -> 2; else -> 0 },
                                paint(st["fill"] ?: "black", st), number(st["fill-opacity"], 1f)))
                            placeX = null; placeY = null; dx = 0f; dy = 0f
                        }
                        is OdfNode -> if (part.name == "tspan" || part.name == "a" || part.name == "textPath") {
                            val s = computed(part, st)
                            if (s["display"] != "none") collect(part, s, false)
                        }
                    }
                }
            }
            collect(n, style, true)
            if (spans.isEmpty()) return null
            // Leading space of the first run and trailing of the last go, as SVG drops them.
            val first = spans.first()
            spans[0] = SvgSpan(first.text.trimStart(), first.x, first.y, first.dx, first.dy, first.size, first.family, first.bold, first.italic, first.anchor, first.fill, first.fillOpacity)
            val l = spans.last()
            spans[spans.lastIndex] = SvgSpan(l.text.trimEnd(), l.x, l.y, l.dx, l.dy, l.size, l.family, l.bold, l.italic, l.anchor, l.fill, l.fillOpacity)
            return SvgText(spans.filter { it.text.isNotEmpty() }, own, opacity, clip).takeIf { it.spans.isNotEmpty() }
        }

        /** An embedded picture: a `data:` URI's bytes (base64 or percent-encoded); a file it names is not in hand. */
        private fun image(n: OdfNode, style: Map<String, String>, own: FloatArray?, opacity: Float, clip: SvgClip?): SvgItem? {
            val href = n.attr("href")?.trim() ?: return null
            if (!href.startsWith("data:")) return null
            val comma = href.indexOf(',')
            if (comma < 0) return null
            val meta = href.substring(5, comma)
            val payload = href.substring(comma + 1)
            val bytes = runCatching {
                if (meta.endsWith(";base64")) java.util.Base64.getMimeDecoder().decode(payload.filterNot { it.isWhitespace() })
                else java.net.URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8").toByteArray()
            }.getOrNull() ?: return null
            val w = x(n.attr("width"), style, -1f); val h = y(n.attr("height"), style, -1f)
            // Without a size: the picture's own (PNG / JPEG / GIF headers, or an SVG's).
            val natural = com.therealityteam.aeropages.format.ImageSize.of(bytes)
            val iw = if (w > 0f) w else natural?.first ?: return null
            val ih = if (h > 0f) h else natural?.second ?: return null
            return SvgImage(bytes, x(n.attr("x"), style), y(n.attr("y"), style), iw, ih, SvgAspect.parse(n.attr("preserveAspectRatio")), own, opacity, clip)
        }

        // --- paint -------------------------------------------------------------------------------

        private fun urlId(v: String): String? = Regex("""url\(\s*['"]?#([^'")\s]+)['"]?\s*\)""").find(v)?.groupValues?.get(1)

        /** A fill or stroke: none, a colour, `currentColor`, or a gradient by `url(#id)` (its fallback colour when the id names none). */
        fun paint(v: String, style: Map<String, String>): SvgPaint? {
            val t = v.trim()
            if (t == "none" || t.isEmpty()) return null
            if (t.startsWith("url(")) {
                val id = urlId(t)
                val server = id?.let { gradient(it) }
                if (server != null) {
                    // A gradient of one stop is its colour.
                    return if (server.stops.size == 1) SvgColor(server.stops[0].second) else server
                }
                val fallback = t.substringAfter(')', "").trim()
                return if (fallback.isNotEmpty()) paint(fallback, style) else null
            }
            if (t == "currentColor") return Colors.parse(style["color"] ?: "black")?.let { SvgColor(it) }
            return Colors.parse(t)?.let { SvgColor(it) }
        }

        /** The gradient [id] names, its `href` chain resolved: attributes from the nearest that sets them, stops from the first that has any. */
        fun gradient(id: String): SvgGradient? {
            if (gradients.containsKey(id)) return gradients[id]
            gradients[id] = null
            val chain = ArrayList<OdfNode>()
            var n = ids[id]
            while (n != null && (n.name == "linearGradient" || n.name == "radialGradient") && n !in chain) {
                chain.add(n)
                n = n.attr("href")?.trim()?.removePrefix("#")?.let { ids[it] }
            }
            val g = chain.firstOrNull() ?: return null
            fun attr(k: String) = chain.firstNotNullOfOrNull { it.attr(k) }
            val bbox = attr("gradientUnits") != "userSpaceOnUse"
            fun coord(k: String, default: String, horizontal: Boolean?): Float {
                val v = attr(k) ?: default
                if (bbox) return number(v, 0f)
                val whole = when (horizontal) {
                    true -> viewport[2]; false -> viewport[3]
                    null -> sqrt((viewport[2] * viewport[2] + viewport[3] * viewport[3]) / 2f)
                }
                return Units.length(v, 16f, whole) ?: 0f
            }
            val stopsNode = chain.firstOrNull { c -> c.children.any { it.name == "stop" } }
            var last = 0f
            val stops = stopsNode?.children("stop")?.map { s ->
                val st = computed(s, emptyMap())
                val offset = number(s.attr("offset") ?: st["offset"], 0f).coerceIn(0f, 1f).coerceAtLeast(last)
                last = offset
                val color = Colors.parse(st["stop-color"] ?: "black") ?: 0xFF000000.toInt()
                val a = (((color ushr 24) and 0xFF) * number(st["stop-opacity"], 1f).coerceIn(0f, 1f)).toInt()
                offset to ((a shl 24) or (color and 0xFFFFFF))
            }.orEmpty()
            if (stops.isEmpty()) return null
            val radial = g.name == "radialGradient"
            val spread = when (attr("spreadMethod")) { "reflect" -> 1; "repeat" -> 2; else -> 0 }
            val matrix = SvgMatrix.parse(attr("gradientTransform"))
            val out = if (radial) {
                val cx = coord("cx", "50%", true); val cy = coord("cy", "50%", false)
                SvgGradient(true, cx, cy, attr("fx")?.let { coord("fx", it, true) } ?: cx, attr("fy")?.let { coord("fy", it, false) } ?: cy,
                    coord("r", "50%", null), stops, bbox, matrix, spread)
            } else SvgGradient(false, coord("x1", "0%", true), coord("y1", "0%", false), coord("x2", "100%", true), coord("y2", "0%", false), 0f, stops, bbox, matrix, spread)
            gradients[id] = out
            return out
        }

        /** The clip path [id] names: its shapes (and the shapes its `use`s draw) with their transforms. */
        fun clipPath(id: String): SvgClip? {
            if (clips.containsKey(id)) return clips[id]
            clips[id] = null
            val n = ids[id]?.takeIf { it.name == "clipPath" } ?: return null
            val style = computed(n, emptyMap())
            val parts = ArrayList<SvgClip.Part>()
            fun add(el: OdfNode, st: Map<String, String>, m: FloatArray?) {
                val s = computed(el, st)
                if (s["display"] == "none") return
                val own = SvgMatrix.parse(el.attr("transform"))
                val mm = if (m == null) own else if (own == null) m else SvgMatrix.times(m, own)
                when (el.name) {
                    "use" -> el.attr("href")?.trim()?.removePrefix("#")?.let { ids[it] }?.let { t ->
                        add(t, s, SvgMatrix.times(mm ?: SvgMatrix.IDENTITY, SvgMatrix.translate(x(el.attr("x"), s), y(el.attr("y"), s))))
                    }
                    "g" -> el.children.forEach { add(it, s, mm) }
                    else -> outline(el, s)?.let { parts.add(SvgClip.Part(it, mm, s["clip-rule"] == "evenodd")) }
                }
            }
            n.children.forEach { add(it, style, null) }
            val out = SvgClip(parts, n.attr("clipPathUnits") == "objectBoundingBox", SvgMatrix.parse(n.attr("transform")))
            clips[id] = out
            return out
        }
    }

    /** CSS colours: #rgb, #rgba, #rrggbb, #rrggbbaa, rgb() / rgba() (numbers or percentages), hsl() / hsla(), names, transparent. */
    object Colors {
        fun parse(v: String): Int? {
            val t = v.trim().lowercase()
            if (t.startsWith("#")) {
                val h = t.drop(1)
                fun hex(s: String) = s.toLongOrNull(16)?.toInt()
                return when (h.length) {
                    3 -> hex(h.map { "$it$it" }.joinToString(""))?.let { it or 0xFF000000.toInt() }
                    4 -> hex(h.map { "$it$it" }.joinToString(""))?.let { rgba -> ((rgba and 0xFF) shl 24) or (rgba ushr 8) }
                    6 -> hex(h)?.let { it or 0xFF000000.toInt() }
                    8 -> hex(h)?.let { rgba -> ((rgba and 0xFF) shl 24) or (rgba ushr 8) }
                    else -> null
                }
            }
            val fn = Regex("""^(rgba?|hsla?)\(([^)]*)\)$""").matchEntire(t)
            if (fn != null) {
                val a = fn.groupValues[2].split(Regex("[\\s,/]+")).filter { it.isNotEmpty() }
                if (a.size < 3) return null
                fun channel(s: String) = if (s.endsWith("%")) (s.dropLast(1).toFloatOrNull() ?: 0f) * 2.55f else s.toFloatOrNull() ?: 0f
                val alpha = a.getOrNull(3)?.let { s -> if (s.endsWith("%")) (s.dropLast(1).toFloatOrNull() ?: 100f) / 100f else s.toFloatOrNull() ?: 1f } ?: 1f
                val (r, g, b) = if (fn.groupValues[1].startsWith("rgb")) Triple(channel(a[0]), channel(a[1]), channel(a[2])) else {
                    val hue = (a[0].removeSuffix("deg").toFloatOrNull() ?: 0f) / 360f
                    val s = (a[1].removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
                    val l = (a[2].removeSuffix("%").toFloatOrNull() ?: 0f) / 100f
                    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
                    val p = 2 * l - q
                    fun hueTo(tt: Float): Float {
                        var x = tt; if (x < 0) x += 1f; if (x > 1) x -= 1f
                        return when { x < 1f / 6 -> p + (q - p) * 6 * x; x < 0.5f -> q; x < 2f / 3 -> p + (q - p) * (2f / 3 - x) * 6; else -> p }
                    }
                    Triple(hueTo(hue + 1f / 3) * 255f, hueTo(hue) * 255f, hueTo(hue - 1f / 3) * 255f)
                }
                fun c(f: Float) = Math.round(f).coerceIn(0, 255)
                return (c(alpha * 255f) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
            }
            if (t == "transparent") return 0
            return NAMES[t]?.let { it or 0xFF000000.toInt() }
        }

        private val NAMES: Map<String, Int> by lazy {
            ("aliceblue f0f8ff antiquewhite faebd7 aqua 00ffff aquamarine 7fffd4 azure f0ffff beige f5f5dc bisque ffe4c4 black 000000 " +
                "blanchedalmond ffebcd blue 0000ff blueviolet 8a2be2 brown a52a2a burlywood deb887 cadetblue 5f9ea0 chartreuse 7fff00 " +
                "chocolate d2691e coral ff7f50 cornflowerblue 6495ed cornsilk fff8dc crimson dc143c cyan 00ffff darkblue 00008b darkcyan 008b8b " +
                "darkgoldenrod b8860b darkgray a9a9a9 darkgreen 006400 darkgrey a9a9a9 darkkhaki bdb76b darkmagenta 8b008b darkolivegreen 556b2f " +
                "darkorange ff8c00 darkorchid 9932cc darkred 8b0000 darksalmon e9967a darkseagreen 8fbc8f darkslateblue 483d8b darkslategray 2f4f4f " +
                "darkslategrey 2f4f4f darkturquoise 00ced1 darkviolet 9400d3 deeppink ff1493 deepskyblue 00bfff dimgray 696969 dimgrey 696969 " +
                "dodgerblue 1e90ff firebrick b22222 floralwhite fffaf0 forestgreen 228b22 fuchsia ff00ff gainsboro dcdcdc ghostwhite f8f8ff " +
                "gold ffd700 goldenrod daa520 gray 808080 green 008000 greenyellow adff2f grey 808080 honeydew f0fff0 hotpink ff69b4 " +
                "indianred cd5c5c indigo 4b0082 ivory fffff0 khaki f0e68c lavender e6e6fa lavenderblush fff0f5 lawngreen 7cfc00 " +
                "lemonchiffon fffacd lightblue add8e6 lightcoral f08080 lightcyan e0ffff lightgoldenrodyellow fafad2 lightgray d3d3d3 " +
                "lightgreen 90ee90 lightgrey d3d3d3 lightpink ffb6c1 lightsalmon ffa07a lightseagreen 20b2aa lightskyblue 87cefa " +
                "lightslategray 778899 lightslategrey 778899 lightsteelblue b0c4de lightyellow ffffe0 lime 00ff00 limegreen 32cd32 linen faf0e6 " +
                "magenta ff00ff maroon 800000 mediumaquamarine 66cdaa mediumblue 0000cd mediumorchid ba55d3 mediumpurple 9370db " +
                "mediumseagreen 3cb371 mediumslateblue 7b68ee mediumspringgreen 00fa9a mediumturquoise 48d1cc mediumvioletred c71585 " +
                "midnightblue 191970 mintcream f5fffa mistyrose ffe4e1 moccasin ffe4b5 navajowhite ffdead navy 000080 oldlace fdf5e6 " +
                "olive 808000 olivedrab 6b8e23 orange ffa500 orangered ff4500 orchid da70d6 palegoldenrod eee8aa palegreen 98fb98 " +
                "paleturquoise afeeee palevioletred db7093 papayawhip ffefd5 peachpuff ffdab9 peru cd853f pink ffc0cb plum dda0dd " +
                "powderblue b0e0e6 purple 800080 rebeccapurple 663399 red ff0000 rosybrown bc8f8f royalblue 4169e1 saddlebrown 8b4513 " +
                "salmon fa8072 sandybrown f4a460 seagreen 2e8b57 seashell fff5ee sienna a0522d silver c0c0c0 skyblue 87ceeb slateblue 6a5acd " +
                "slategray 708090 slategrey 708090 snow fffafa springgreen 00ff7f steelblue 4682b4 tan d2b48c teal 008080 thistle d8bfd8 " +
                "tomato ff6347 turquoise 40e0d0 violet ee82ee wheat f5deb3 white ffffff whitesmoke f5f5f5 yellow ffff00 yellowgreen 9acd32")
                .split(' ').chunked(2).associate { (n, h) -> n to h.toInt(16) }
        }
    }
}
