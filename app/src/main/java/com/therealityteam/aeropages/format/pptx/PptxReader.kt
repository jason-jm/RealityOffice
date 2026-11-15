package com.therealityteam.aeropages.format.pptx

import com.therealityteam.aeropages.format.ImageSize
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.format.XmlNode
import com.therealityteam.aeropages.format.XmlPull
import com.therealityteam.aeropages.model.AnimClass
import com.therealityteam.aeropages.model.AnimDir
import com.therealityteam.aeropages.model.AnimEffect
import com.therealityteam.aeropages.model.AnimStart
import com.therealityteam.aeropages.model.AnimTarget
import com.therealityteam.aeropages.model.ElementAnim
import com.therealityteam.aeropages.model.BoxFrame
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.ChartSeries
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.SlideMaster
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideChart
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import com.therealityteam.aeropages.model.SlideTransition
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun

/**
 * PPTX reader built on the XmlPull parser: unzips the package, walks the slides in
 * presentation order (presentation.xml → rels; slideN.xml numbering is NOT the order after
 * reordering in PowerPoint) and extracts each slide's placeholders, shapes, tables, charts,
 * pictures (via the slide's .rels → ppt/media), notes, transition and animation hints from a
 * small DOM per part. Elements are matched by local name, so any namespace prefixes,
 * attribute order or formatting a producer picks are accepted.
 */
object PptxReader {

    fun read(bytes: ByteArray): Presentation {
        val files = OfficeXml.unzip(bytes)
        val slideNames = slideOrder(files)
        // Theme colours and the slide master: scheme colour references, inherited backgrounds and
        // the default title / body text styles all resolve through them.
        val palette = com.therealityteam.aeropages.platform.Trace.section("pptx.palette") { readPalette(files) }
        val master = readMaster(files, palette, slideNames.firstOrNull()?.let { layoutOf(it, files) })
        val layoutBgs = HashMap<String, Pair<Long?, com.therealityteam.aeropages.model.BackgroundShade?>?>()
        // The slide size every position is a fraction of (p:sldSz; PowerPoint's widescreen when missing).
        val presentationRoot = XmlPull.parse(files[PRESENTATION])
        val size = presentationRoot?.descendants("sldSz")?.firstOrNull()
        val dims = Dims(size?.attr("cx")?.toDoubleOrNull()?.takeIf { it > 0 } ?: Dims.WIDESCREEN.cx,
            size?.attr("cy")?.toDoubleOrNull()?.takeIf { it > 0 } ?: Dims.WIDESCREEN.cy)
        val phFrames = PhFrames(files, masterPart(files), dims, palette)
        // Each slide part is parsed once; a malformed slide degrades to an empty one (its position is kept).
        val roots = slideNames.mapNotNull { name -> files[name]?.let { name to (XmlPull.parse(it) ?: XmlNode("", "sld", emptyList())) } }
        val slides = roots.map { (name, root) -> com.therealityteam.aeropages.platform.Trace.section("pptx.slide") { parseSlide(root, name, files, palette, master, layoutBgs, phFrames) } }
        val pres = Presentation(slides.ifEmpty { listOf(Slide()) }.toMutableList(), master = master,
            slideSize = com.therealityteam.aeropages.model.SlideSize.of((dims.cx / 12700.0).toFloat(), (dims.cy / 12700.0).toFloat()),
            firstSlideNumber = presentationRoot?.attr("firstSlideNum")?.toIntOrNull()?.coerceIn(0, 9999) ?: 1)
        // Links to slides named their parts: now the slides themselves (one to a part that is not a slide shown goes).
        val byPart = roots.indices.associate { roots[it].first to slides[it] }
        com.therealityteam.aeropages.model.SlideLinks.remap(pres) { link ->
            if (link.startsWith(SLIDE_PART_LINK)) byPart[link.substring(SLIDE_PART_LINK.length)]?.let { com.therealityteam.aeropages.model.SlideLinks.toSlide(it) } else link
        }
        readFooter(pres, roots.map { it.second })
        readSections(files, pres)
        readShow(files, pres, byPart)
        com.therealityteam.aeropages.platform.Trace.section("pptx.designs") { readDesigns(pres, roots, files, palette, dims) }
        // Comments: PowerPoint's classic ones and its modern ones (each slide's comments part), and the AeroComments custom
        // property earlier versions of this app wrote instead ("slideIdx|text" records, ';'-joined, fields %-escaped).
        roots.forEachIndexed { i, (name, _) -> readComments(files, name, pres.slideSize).let { slides[i].comments.addAll(it) } }
        XmlPull.parse(files["docProps/custom.xml"])?.descendants("property")
            ?.firstOrNull { it.attr("name") == "AeroComments" }?.child("lpwstr")?.text?.split(';')?.forEach { rec ->
                val bar = rec.indexOf('|')
                if (bar <= 0) return@forEach
                val idx = rec.substring(0, bar).toIntOrNull() ?: return@forEach
                pres.slides.getOrNull(idx)?.comments?.add(com.therealityteam.aeropages.model.SlideComment("", PptxWriter.decodeField(rec.substring(bar + 1)), dateMs = 0L))
            }
        pres.props = com.therealityteam.aeropages.format.OoxmlCore.read(files[com.therealityteam.aeropages.format.OoxmlCore.PART])
        return pres
    }

    /**
     * What a slide's master and layout draw under it — as the deck's designs: one per layout the
     * slides use, its master's pictures, shapes, lines and groups (unless the layout hides them) then
     * its own, and a picture background (the layout's, else the master's) as the design's picture;
     * the layouts adding nothing to their master share its design, named as the master is. A slide
     * with a picture background of its own gets its layout's design with that picture. A slide that
     * hides background graphics (`showMasterSp="0"`) shows the picture alone.
     */
    private fun readDesigns(pres: Presentation, roots: List<Pair<String, XmlNode>>, files: Map<String, ByteArray>, palette: Palette, dims: Dims) {
        val byLayout = HashMap<String, com.therealityteam.aeropages.model.SlideDesign?>()
        val byMaster = HashMap<String, com.therealityteam.aeropages.model.SlideDesign>()
        val names = HashSet<String>()
        fun unique(base: String): String {
            var n = base.ifBlank { "Design" }
            var k = 2
            while (!names.add(n)) n = "$base ${k++}"
            return n
        }
        // A colour equal to the deck's own is left to inherit, as a slide's is; so is text its master
        // sets as the deck's does.
        fun differs(t: com.therealityteam.aeropages.model.MasterText?, deck: com.therealityteam.aeropages.model.MasterText) =
            t != null && (t.font != deck.font || t.sizePt != deck.sizePt || t.color != deck.color || t.bold != deck.bold)
        fun register(d: com.therealityteam.aeropages.model.SlideDesign): com.therealityteam.aeropages.model.SlideDesign {
            val inherits = d.background == pres.master.background && d.backgroundShade == pres.master.backgroundShade
            return com.therealityteam.aeropages.model.SlideDesign(unique(d.name), d.background?.takeIf { !inherits }, d.picture, d.items,
                d.titleText?.takeIf { differs(it, pres.master.titleText()) }, d.bodyText?.takeIf { differs(it, pres.master.bodyText()) },
                // Only where it is not the deck master's.
                d.chrome.filter { (part, look) -> pres.master.chrome[part] != look }.toMutableMap(), d.backgroundShade.takeIf { !inherits }).also { pres.designs.add(it) }
        }
        roots.forEachIndexed { i, (name, root) ->
            val slide = pres.slides.getOrNull(i) ?: return@forEachIndexed
            val layoutName = layoutOf(name, files) ?: return@forEachIndexed
            val base = byLayout.getOrPut(layoutName) {
                layoutDesign(layoutName, files, palette, dims)?.let { ld -> ld.master?.let { m -> byMaster.getOrPut(m) { register(ld.design) } } ?: register(ld.design) }
            }
            // A picture background of the slide's own: its layout's drawing over that picture.
            val ownBg = root.child("cSld")?.child("bg")
            val own = ownBg?.let { backgroundPicture(it, name, files) }
            val design = if (own != null) {
                com.therealityteam.aeropages.model.SlideDesign(unique((base?.name ?: "Slide") + " · ${i + 1}"), null, own, base?.items ?: mutableListOf()).also { pres.designs.add(it) }
            } else base
            if (design != null) {
                slide.design = design.name
                // The colour (and gradient) it inherits is its design's.
                if (ownBg == null && slide.background == design.background && slide.backgroundShade == design.backgroundShade) { slide.background = null; slide.backgroundShade = null }
            }
            if (root.attr("showMasterSp") == "0") slide.designObjects = false
        }
    }

    /** A layout's design and — when the layout adds nothing to its master's drawing — the master part it is the design of. */
    private class LayoutDesign(val design: com.therealityteam.aeropages.model.SlideDesign, val master: String?)

    /**
     * A layout part's design: its master's drawing (unless `showMasterSp="0"`) then its own, and the
     * picture its background (else its master's) is filled with — or null when it draws nothing.
     * A layout adding nothing (no drawing, no background of its own) shows its master's design.
     */
    private fun layoutDesign(layoutName: String, files: Map<String, ByteArray>, palette: Palette, dims: Dims): LayoutDesign? {
        val layout = XmlPull.parse(files[layoutName]) ?: return null
        val masterName = XmlPull.rels(files[XmlPull.relsOf(layoutName)]).firstOrNull { it.type.endsWith("/slideMaster") }?.let { XmlPull.partName(files, layoutName, it.target) }
        val master = masterName?.let { XmlPull.parse(files[it]) }
        val items = ArrayList<com.therealityteam.aeropages.model.DesignItem>()
        val showsMaster = layout.attr("showMasterSp") != "0"
        if (showsMaster && master != null) items.addAll(drawnItems(master, masterName, files, palette, dims))
        val own = drawnItems(layout, layoutName, files, palette, dims)
        items.addAll(own)
        val ownBg = layout.child("cSld")?.child("bg")
        val bg = ownBg ?: master?.child("cSld")?.child("bg")
        val picture = bg?.let { backgroundPicture(it, if (ownBg != null) layoutName else masterName ?: layoutName, files) }
        if (picture == null && items.isEmpty()) return null
        val layoutTitle = layout.child("cSld")?.attr("name")?.takeIf { it.isNotBlank() }
        val mastersOwn = showsMaster && own.isEmpty() && ownBg == null && masterName != null
        val name = (if (mastersOwn) masterTitle(master, masterName!!, layoutTitle, files) else null)
            ?: layoutTitle ?: layoutName.substringAfterLast('/').removeSuffix(".xml")
        // Its master's text styles (a design's own where they are not the deck's) — under what the
        // layout's own placeholders set, a design of its own — and where its layout, else its
        // master, puts the date, footer and number.
        val text = master?.let { textStyles(it, palette) }
        val ownText = if (mastersOwn) null to null else placeholderTexts(layout, palette)
        fun over(top: com.therealityteam.aeropages.model.MasterText?, base: com.therealityteam.aeropages.model.MasterText?) =
            if (top == null) base else com.therealityteam.aeropages.model.MasterText(top.font ?: base?.font, top.sizePt ?: base?.sizePt, top.color ?: base?.color, top.bold ?: base?.bold)
        val (bgColor, bgShade) = palette.backgroundFill(bg)
        val design = com.therealityteam.aeropages.model.SlideDesign(name, bgColor, picture, items, over(ownText.first, text?.first), over(ownText.second, text?.second))
        design.backgroundShade = bgShade
        master?.let { design.chrome.putAll(chromeOf(it, palette, dims)) }
        design.chrome.putAll(chromeOf(layout, palette, dims))
        return LayoutDesign(design, if (mastersOwn) masterName else null)
    }

    /**
     * A master's name: its own (`p:cSld@name`); else, a master of one layout, that layout's
     * (LibreOffice names it after its master page); else its theme's — what PowerPoint calls the design.
     */
    private fun masterTitle(master: XmlNode?, masterName: String, layoutTitle: String?, files: Map<String, ByteArray>): String? =
        master?.child("cSld")?.attr("name")?.takeIf { it.isNotBlank() }
            ?: layoutTitle?.takeIf { master?.child("sldLayoutIdLst")?.children("sldLayoutId")?.size == 1 }
            ?: XmlPull.rels(files[XmlPull.relsOf(masterName)]).firstOrNull { it.type.endsWith("/theme") }
                ?.let { r -> XmlPull.partName(files, masterName, r.target)?.let { XmlPull.parse(files[it]) } }?.attr("name")?.takeIf { it.isNotBlank() }

    /** A background's picture fill (`p:bgPr/a:blipFill`) as a picture filling the slide, or null. */
    private fun backgroundPicture(bg: XmlNode, part: String, files: Map<String, ByteArray>): SlideImage? {
        val blipFill = bg.child("bgPr")?.child("blipFill") ?: return null
        val blip = blipFill.child("blip") ?: return null
        val relById = XmlPull.rels(files[XmlPull.relsOf(part)]).associateBy { it.id }
        val img = imageOf(blip, relById, part, files) ?: return null
        img.xPct = 0f; img.yPct = 0f; img.wPct = 1f; img.hPct = 1f
        blipFill.child("srcRect")?.let { r -> com.therealityteam.aeropages.format.PictureCrop.readSrcRect(r::attr, img) }
        return img
    }

    /**
     * What a master or layout part draws of its own, in the order it draws it: pictures, shapes,
     * lines, text boxes and the members of groups — not its placeholders (title, body, date, footer,
     * number, picture …), which are the slides' to fill.
     */
    private fun drawnItems(root: XmlNode, part: String, files: Map<String, ByteArray>, palette: Palette, dims: Dims): List<com.therealityteam.aeropages.model.DesignItem> {
        val tree = root.child("cSld")?.child("spTree") ?: return emptyList()
        val rels = XmlPull.rels(files[XmlPull.relsOf(part)])
        val relById = rels.associateBy { it.id }
        val ctx = SlideCtx(rels.filter { it.type.endsWith("hyperlink") }.associate { it.id to it.target }, palette, dims)
        val out = ArrayList<com.therealityteam.aeropages.model.DesignItem>()
        var groups = 0
        fun walk(node: XmlNode, group: GroupCtx?) {
            for (el in node.children) when (el.name) {
                "grpSp" -> walk(el, GroupCtx(++groups, el.child("grpSpPr")?.child("xfrm"), dims))
                "sp", "cxnSp" -> {
                    if (placeholder(el) != null || (!el.has("prstGeom") && !el.has("custGeom"))) continue
                    parseShape(el, ctx)?.let { s ->
                        group?.let { g -> s.groupId = g.id; g.map(s.xPct, s.yPct, s.wPct, s.hPct)?.let { f -> s.xPct = f[0]; s.yPct = f[1]; s.wPct = f[2]; s.hPct = f[3] } }
                        out.add(com.therealityteam.aeropages.model.DesignShape(s))
                    }
                }
                "pic" -> {
                    if (placeholder(el) != null) continue
                    val blip = el.descendants("blip").firstOrNull() ?: continue
                    val img = imageOf(blip, relById, part, files) ?: continue
                    el.child("blipFill")?.child("srcRect")?.let { r -> com.therealityteam.aeropages.format.PictureCrop.readSrcRect(r::attr, img) }
                    el.child("spPr")?.child("effectLst")?.child("outerShdw")?.let { img.shadow = DrawingShape.shadowOf(it, palette) }
                    turn(el, img)
                    val f = frameOf(el, dims)
                    if (f.x != null && f.y != null) { img.xPct = f.x; img.yPct = f.y }
                    if (f.w != null && f.h != null && f.w > 0f && f.h > 0f) { img.wPct = f.w; img.hPct = f.h }
                    group?.map(img.xPct, img.yPct, img.wPct, img.hPct)?.let { m -> img.xPct = m[0]; img.yPct = m[1]; img.wPct = m[2]; img.hPct = m[3] }
                    out.add(com.therealityteam.aeropages.model.DesignPicture(img))
                }
            }
        }
        walk(tree, null)
        return out
    }

    /** A picture's turn and mirroring (`a:xfrm@rot`, 60000ths of a degree clockwise; `@flipH` / `@flipV`), the turn within ±180°. */
    private fun turn(pic: XmlNode, img: SlideImage) {
        val xfrm = pic.child("spPr")?.child("xfrm") ?: return
        xfrm.attr("rot")?.toFloatOrNull()?.let { r ->
            var d = ((r / 60000f) % 360f + 360f) % 360f
            if (d > 180f) d -= 360f
            img.rotationDeg = if (kotlin.math.abs(d) < 0.01f) 0f else d
        }
        img.flipH = xfrm.attr("flipH").let { it == "1" || it == "true" }
        img.flipV = xfrm.attr("flipV").let { it == "1" || it == "true" }
    }

    /** A deck's slide size in EMU (`p:sldSz`): every frame's fractions are taken of it. */
    private class Dims(val cx: Double, val cy: Double) {
        companion object {
            val WIDESCREEN = Dims(12192000.0, 6858000.0)
        }
    }
    private const val PRESENTATION = "ppt/presentation.xml"
    private val SLIDE_PART = Regex("ppt/slides/slide\\d+\\.xml")
    private val SLIDE_NUMBER = Regex("slide(\\d+)\\.xml")
    /** Placeholder types that are slide chrome rather than content. */
    private val CHROME_PH = setOf("ftr", "sldNum", "dt")
    /** First colour of each chart palette (the writer's series 1 colour) for colorSet recovery. */
    private val CHART_FIRST_COLORS = com.therealityteam.aeropages.model.ChartStyle.PALETTES.map { String.format(java.util.Locale.ROOT, "%06X", it[0] and 0xFFFFFF) }

    /** Slide parts in presentation order (presentation.xml sldIdLst → rels targets); falls back to slideN numbering. */
    private fun slideOrder(files: Map<String, ByteArray>): List<String> {
        val byNumber = files.keys.filter { SLIDE_PART.matches(it) }
            .sortedBy { SLIDE_NUMBER.find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        val pres = XmlPull.parse(files[PRESENTATION]) ?: return byNumber
        val rels = XmlPull.rels(files[XmlPull.relsOf(PRESENTATION)] ?: return byNumber).associateBy { it.id }
        val ordered = pres.descendants("sldId")
            .mapNotNull { s -> s.attr(XmlPull.R_NS, "id")?.let { rels[it] }?.let { XmlPull.partName(files, PRESENTATION, it.target) } }
            .distinct()
        return if (ordered.isEmpty()) byNumber else ordered + byNumber.filter { it !in ordered }
    }

    /** The `p:ph` of a shape, or null when the shape is not a placeholder. */
    private fun placeholder(sp: XmlNode): XmlNode? = sp.descendants("ph").firstOrNull()

    /** Placeholder kind: title (`title` / `ctrTitle`), body (`body` / `subTitle` / `obj` / untyped), else the raw type. */
    private fun phKind(type: String?): String = when (type) {
        "title", "ctrTitle" -> "title"
        null, "body", "subTitle", "obj" -> "body"
        else -> type
    }
    private fun kindOf(sp: XmlNode): String? = placeholder(sp)?.let { phKind(it.attr("type")) }

    /**
     * A placeholder's frame (and its list's bullet and first level's text, when its lstStyle says) on a
     * layout / master part, matched from a slide by idx, else by kind.
     */
    private class PhFrame(val kind: String, val idx: String?, val frame: BoxFrame?, val bullet: SlideListStyle? = null,
        val text: com.therealityteam.aeropages.model.MasterText? = null, val align: TextAlign? = null)

    /** Layout / master placeholder geometry, each part read once (the inheritance chain for slide placeholders). */
    private class PhFrames(private val files: Map<String, ByteArray>, masterName: String?, val dims: Dims, private val palette: Palette) {
        private val masterRoot = masterName?.let { XmlPull.parse(files[it]) }
        val master: List<PhFrame> = framesIn(masterRoot)
        /** The master's text styles' bullets: a body's lists come from here in most decks. */
        val masterTitleBullet: SlideListStyle? = bulletOf(masterRoot?.child("txStyles")?.child("titleStyle")?.child("lvl1pPr"))
        val masterBodyBullet: SlideListStyle? = bulletOf(masterRoot?.child("txStyles")?.child("bodyStyle")?.child("lvl1pPr"))
        /** The master's title and body alignment (lvl1pPr's `algn`), where it gives one. */
        val masterTitleAlign: TextAlign? = alignOf(masterRoot?.child("txStyles")?.child("titleStyle")?.child("lvl1pPr"))
        val masterBodyAlign: TextAlign? = alignOf(masterRoot?.child("txStyles")?.child("bodyStyle")?.child("lvl1pPr"))
        /** The master's title and body line spacing (lvl1pPr's `a:lnSpc`), where it gives one. */
        val masterTitleSpacing: Float? = spacingOf(masterRoot?.child("txStyles")?.child("titleStyle")?.child("lvl1pPr"), null)
        val masterBodySpacing: Float? = spacingOf(masterRoot?.child("txStyles")?.child("bodyStyle")?.child("lvl1pPr"), null)
        /** The master's body text size at each level (lvl1pPr … lvl9pPr), where it gives one. */
        val masterBodyLevelSizes: List<Float?> = (1..9).map { n ->
            masterRoot?.child("txStyles")?.child("bodyStyle")?.child("lvl${n}pPr")?.child("defRPr")?.attr("sz")?.toIntOrNull()?.let { it / 100f }
        }
        private val layouts = HashMap<String, List<PhFrame>>()
        private val layoutTypes = HashMap<String, String>()
        fun layout(name: String?): List<PhFrame> = if (name == null) emptyList() else layouts.getOrPut(name) { framesIn(XmlPull.parse(files[name])) }
        /** A layout part's `type` (title, obj, secHead, titleOnly, blank …); "" when it names none. */
        fun layoutType(name: String?): String = if (name == null) "" else layoutTypes.getOrPut(name) { XmlPull.parse(files[name])?.attr("type").orEmpty() }
        private fun framesIn(root: XmlNode?): List<PhFrame> {
            root ?: return emptyList()
            return root.descendants("sp").mapNotNull { sp ->
                val ph = placeholder(sp) ?: return@mapNotNull null
                val lvl1 = sp.child("txBody")?.child("lstStyle")?.child("lvl1pPr")
                PhFrame(phKind(ph.attr("type")), ph.attr("idx"), frameOf(sp, dims).toBoxFrame(), bulletOf(lvl1), listText(lvl1, palette), alignOf(lvl1))
            }
        }
    }

    /**
     * A paragraph's (or list level's) line spacing as a multiple of single spacing: `a:lnSpc`'s
     * percentage, or its exact points over single spacing (1.2 × [sizePt], when known); null when
     * it does not say.
     */
    private fun spacingOf(pPr: XmlNode?, sizePt: Float?): Float? {
        val ln = pPr?.child("lnSpc") ?: return null
        ln.child("spcPct")?.attr("val")?.let { v -> v.removeSuffix("%").toFloatOrNull()?.let { return (if (v.endsWith("%")) it / 100f else it / 100000f).takeIf { m -> m > 0f } } }
        val pts = ln.child("spcPts")?.attr("val")?.toFloatOrNull()?.div(100f) ?: return null
        return if (sizePt != null && sizePt > 0f) (pts / (sizePt * 1.2f)).takeIf { it > 0f } else null
    }

    /** A paragraph (or list level)'s bullet: buChar / buBlip a bullet, buAutoNum a number, buNone none; null when it does not say. */
    private fun bulletOf(pPr: XmlNode?): SlideListStyle? = when {
        pPr == null -> null
        pPr.child("buNone") != null -> SlideListStyle.NONE
        pPr.child("buAutoNum") != null -> SlideListStyle.NUMBER
        pPr.child("buChar") != null || pPr.child("buBlip") != null -> SlideListStyle.BULLET
        else -> null
    }

    /**
     * A text box's list: what its first non-blank paragraph says, else its own list style; for a
     * placeholder of [kind], then the layout placeholder's (by idx, then kind), the master's, and the
     * master's title / body text style — where PowerPoint keeps a deck's bullets.
     */
    private fun listStyleOf(sp: XmlNode?, kind: String?, layout: List<PhFrame>, frames: PhFrames?): SlideListStyle {
        val txBody = sp?.child("txBody")
        val first = txBody?.children("p")?.firstOrNull { p -> p.descendants("t").any { it.text.isNotBlank() } }
        bulletOf(first?.child("pPr"))?.let { return it }
        bulletOf(txBody?.child("lstStyle")?.child("lvl1pPr"))?.let { return it }
        if (kind == null || frames == null) return SlideListStyle.NONE
        val idx = sp?.let { placeholder(it)?.attr("idx") }
        ((if (idx != null) layout.firstOrNull { it.idx == idx } else null) ?: layout.firstOrNull { it.kind == kind })?.bullet?.let { return it }
        frames.master.firstOrNull { it.kind == kind }?.bullet?.let { return it }
        return (if (kind == "title") frames.masterTitleBullet else frames.masterBodyBullet) ?: SlideListStyle.NONE
    }

    /**
     * Where a slide placeholder of [kind] sits: its own `a:xfrm`, else the layout placeholder with
     * the same idx (then the same kind), else the master's. A missing placeholder inherits only
     * when its layout has no placeholders of its own (a "Title Only" layout shows no body).
     */
    private fun placeholderFrame(sp: XmlNode?, kind: String, layout: List<PhFrame>, master: List<PhFrame>, dims: Dims): BoxFrame? {
        sp?.let { frameOf(it, dims).toBoxFrame() }?.let { return it }
        if (sp == null && layout.isNotEmpty() && layout.none { it.kind == kind }) return null
        val idx = sp?.let { placeholder(it)?.attr("idx") }
        // The layout's placeholder with its idx, where there is one, else one of its kind: one placing
        // nothing itself sits where the master's does (not where another of its kind does).
        val byIdx = if (idx != null) layout.firstOrNull { it.idx == idx } else null
        val fromLayout = if (byIdx != null) byIdx.frame else layout.firstOrNull { it.kind == kind && it.frame != null }?.frame
        return fromLayout?.copyOf() ?: master.firstOrNull { it.kind == kind && it.frame != null }?.frame?.copyOf()
    }

    /** Footer text / slide-number placeholders (presentation-wide in our model; the first slide carrying them wins). */
    /**
     * Header & footer from the slides' date, footer and number placeholders: the first footer text,
     * the first date (a `datetimeN` field — today's date in its format — or fixed text), a number when
     * any slide has one; title slides without any while others have them are "don't show on title slide".
     */
    private fun readFooter(pres: Presentation, roots: List<XmlNode>) {
        var titleShows = false; var titleHides = false; var othersShow = false
        roots.forEachIndexed { i, root ->
            var shows = false
            for (sp in root.descendants("sp")) when (placeholder(sp)?.attr("type")) {
                "ftr" -> sp.textOf("t").trim().takeIf { it.isNotEmpty() }?.let { text -> shows = true; if (pres.footer.isBlank()) pres.footer = text }
                "sldNum" -> { shows = true; pres.showSlideNumber = true }
                "dt" -> {
                    val field = sp.descendants("fld").firstOrNull { it.attr("type")?.startsWith("datetime") == true }
                    val date = if (field != null) com.therealityteam.aeropages.model.SlideDate(DateFields.formatOf(field.attr("type") ?: "", field.textOf("t")))
                        else sp.textOf("t").trim().takeIf { it.isNotEmpty() }?.let { com.therealityteam.aeropages.model.SlideDate(fixed = it) }
                    if (date != null) { shows = true; if (pres.date == null) pres.date = date }
                }
            }
            if (pres.slides.getOrNull(i)?.layout == com.therealityteam.aeropages.model.SlideLayout.TITLE) { if (shows) titleShows = true else titleHides = true }
            else if (shows) othersShow = true
        }
        pres.hideFooterOnTitle = titleHides && !titleShows && othersShow
    }

    /**
     * The first `p:transition`'s effect and its options — the side a push, cover, pull or wipe moves to (`dir`: l, r, u, d),
     * a split's `orient` and `dir`, random bars' `dir`, a zoom in or out — as the nearest SlideTransition and its direction.
     */
    internal fun transitionOf(root: XmlNode): Pair<SlideTransition, com.therealityteam.aeropages.model.TransitionDir> {
        val t = root.descendants("transition").firstOrNull() ?: return SlideTransition.NONE to com.therealityteam.aeropages.model.TransitionDir.DEFAULT
        fun side(c: XmlNode) = when (c.attr("dir")) {
            "r" -> com.therealityteam.aeropages.model.TransitionDir.FROM_LEFT
            "u" -> com.therealityteam.aeropages.model.TransitionDir.FROM_BOTTOM
            "d" -> com.therealityteam.aeropages.model.TransitionDir.FROM_TOP
            else -> com.therealityteam.aeropages.model.TransitionDir.FROM_RIGHT
        }
        val none = com.therealityteam.aeropages.model.TransitionDir.DEFAULT
        var nearest = SlideTransition.NONE
        for (c in t.children) when (c.name) {
            "fade" -> return SlideTransition.FADE to none
            "push" -> return SlideTransition.PUSH to side(c)
            "pull" -> return SlideTransition.UNCOVER to side(c)
            "zoom" -> return SlideTransition.ZOOM to (if (c.attr("dir") == "out") com.therealityteam.aeropages.model.TransitionDir.OUT else com.therealityteam.aeropages.model.TransitionDir.IN)
            "circle", "diamond", "plus" -> return SlideTransition.SHAPE to com.therealityteam.aeropages.model.TransitionDir.IN
            "cover", "comb" -> return SlideTransition.COVER to side(c)
            "wipe" -> return SlideTransition.WIPE to side(c)
            "strips", "blinds" -> return SlideTransition.WIPE to com.therealityteam.aeropages.model.TransitionDir.FROM_LEFT
            "randomBar" -> return SlideTransition.RANDOM_BARS to (if (c.attr("dir") == "vert") com.therealityteam.aeropages.model.TransitionDir.VERTICAL_OUT else com.therealityteam.aeropages.model.TransitionDir.HORIZONTAL_OUT)
            "split" -> {
                val vert = c.attr("orient") == "vert"; val inward = c.attr("dir") == "in"
                return SlideTransition.SPLIT to when {
                    vert && inward -> com.therealityteam.aeropages.model.TransitionDir.VERTICAL_IN
                    vert -> com.therealityteam.aeropages.model.TransitionDir.VERTICAL_OUT
                    inward -> com.therealityteam.aeropages.model.TransitionDir.HORIZONTAL_IN
                    else -> com.therealityteam.aeropages.model.TransitionDir.HORIZONTAL_OUT
                }
            }
            "dissolve", "checker", "random", "newsflash" -> return SlideTransition.DISSOLVE to none
            "cut" -> return SlideTransition.CUT to none
            "wheel", "wedge" -> return SlideTransition.WHEEL to none
            "sndAc", "extLst" -> {}
            else -> nearest = SlideTransition.FADE // any other PowerPoint transition → nearest
        }
        return nearest to none
    }

    /**
     * A transition's speed: PowerPoint 2010's exact duration (`p14:dur`, ms) where it gives one, else
     * `spd` — slow / med / fast, and fast when it names none, as the schema has it.
     */
    private fun parseTransitionSpeed(root: XmlNode): com.therealityteam.aeropages.model.TransitionSpeed {
        val t = root.descendants("transition").firstOrNull()
        t?.attr("dur")?.toIntOrNull()?.let { ms -> return speedOf(ms) }
        return when (t?.attr("spd")) {
            "slow" -> com.therealityteam.aeropages.model.TransitionSpeed.SLOW
            "med" -> com.therealityteam.aeropages.model.TransitionSpeed.MEDIUM
            else -> com.therealityteam.aeropages.model.TransitionSpeed.FAST
        }
    }

    /** A transition [ms] long as the nearest speed (fast 0.5 s, medium 0.75 s, slow 1 s). */
    internal fun speedOf(ms: Int): com.therealityteam.aeropages.model.TransitionSpeed = when {
        ms <= 600 -> com.therealityteam.aeropages.model.TransitionSpeed.FAST
        ms <= 900 -> com.therealityteam.aeropages.model.TransitionSpeed.MEDIUM
        else -> com.therealityteam.aeropages.model.TransitionSpeed.SLOW
    }

    /**
     * Sections from presentation.xml's `p14:sectionLst`: each names the slides it holds by their
     * `sldId`, which we map back to positions through the ordinary `p:sldIdLst`.
     */
    private fun readSections(files: Map<String, ByteArray>, pres: Presentation) {
        val root = XmlPull.parse(files[PRESENTATION]) ?: return
        val order = root.child("sldIdLst")?.children("sldId")?.mapNotNull { it.attr("id") } ?: return
        val sectionLst = root.descendants("sectionLst").firstOrNull() ?: return
        sectionLst.children("section").forEach { section ->
            val name = section.attr("name") ?: return@forEach
            val firstId = section.descendants("sldId").firstNotNullOfOrNull { it.attr("id") } ?: return@forEach
            val index = order.indexOf(firstId)
            if (index in pres.slides.indices) pres.slides[index].sectionName = name
        }
    }

    /**
     * Set Up Slide Show and the custom shows: presProps.xml's `p:showPr` (loop, narration, animations, timings; present,
     * browse or kiosk; all the slides, a range of them or a custom show by its id) and presentation.xml's `p:custShowLst`
     * (each show's slides by their relationships), the slides by the parts [byPart] read them from.
     */
    private fun readShow(files: Map<String, ByteArray>, pres: Presentation, byPart: Map<String, Slide>) {
        val rels = XmlPull.rels(files[XmlPull.relsOf(PRESENTATION)])
        val byId = rels.associateBy { it.id }
        val shows = XmlPull.parse(files[PRESENTATION])?.child("custShowLst")?.children("custShow").orEmpty().mapNotNull { cs ->
            val name = cs.attr("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ids = cs.child("sldLst")?.children("sld").orEmpty().mapNotNull { sld ->
                sld.attr(XmlPull.R_NS, "id")?.let { byId[it] }?.let { XmlPull.partName(files, PRESENTATION, it.target) }?.let { byPart[it]?.id }
            }
            cs.attr("id").orEmpty() to com.therealityteam.aeropages.model.CustomShow(name, ids)
        }.distinctBy { it.second.name }
        pres.customShows = shows.map { it.second }
        val props = rels.firstOrNull { it.type.endsWith("/presProps") }?.let { XmlPull.partName(files, PRESENTATION, it.target) } ?: "ppt/presProps.xml"
        val showPr = XmlPull.parse(files[props])?.child("showPr") ?: return
        fun flag(name: String, default: Boolean) = showPr.attr(name)?.let { it == "1" || it == "true" } ?: default
        pres.loopShow = flag("loop", false)
        pres.showNarration = flag("showNarration", false)
        pres.useTimings = flag("useTimings", true)
        val range = showPr.child("sldRg")
        pres.show = com.therealityteam.aeropages.model.ShowSetup(
            type = when {
                showPr.child("kiosk") != null -> com.therealityteam.aeropages.model.ShowType.KIOSK
                showPr.child("browse") != null -> com.therealityteam.aeropages.model.ShowType.WINDOW
                else -> com.therealityteam.aeropages.model.ShowType.SPEAKER
            },
            animations = flag("showAnimation", true),
            from = range?.attr("st")?.toIntOrNull()?.coerceAtLeast(0) ?: 0, to = range?.attr("end")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            custom = showPr.child("custShow")?.attr("id")?.let { id -> shows.firstOrNull { it.first == id }?.second?.name },
        )
    }

    /**
     * A shape PowerPoint writes with an equation (`a14:m`) comes twice, in `mc:AlternateContent`: with
     * the formula for PowerPoint, and as a picture of it for older readers. The formula is read, so
     * the picture copy goes.
     */
    private fun dropMathFallbacks(node: XmlNode) {
        for (c in node.children) {
            if (c.name == "AlternateContent" && c.child("Choice")?.let { it.has("oMath") || it.has("oMathPara") } == true) c.children.removeAll { it.name == "Fallback" }
            dropMathFallbacks(c)
        }
    }

    private fun parseSlide(root: XmlNode, name: String, files: Map<String, ByteArray>, deckPalette: Palette, master: SlideMaster,
                           layoutBgs: MutableMap<String, Pair<Long?, com.therealityteam.aeropages.model.BackgroundShade?>?>, phFrames: PhFrames): Slide {
        // A slide's own colour map (a dark one's text light): its scheme colours resolve through it.
        val palette = deckPalette.overriddenBy(root.child("clrMapOvr")?.child("overrideClrMapping"))
        dropMathFallbacks(root)
        val rels = XmlPull.rels(files[XmlPull.relsOf(name)])
        val links = rels.filter { it.type.endsWith("hyperlink") }.associate { it.id to it.target }
        val slideParts = rels.filter { it.type.endsWith("/slide") }.mapNotNull { r -> XmlPull.partName(files, name, r.target)?.let { r.id to it } }.toMap()
        val ctx = SlideCtx(links, palette, phFrames.dims, slideParts)
        val titleSize = master.titleSizePt ?: 40f
        val bodySize = master.bodySizePt ?: 22f
        // A body's deeper levels are set smaller, as the master's levels are (28 → 24 → 20 pt …).
        fun levelSizeFrom(top: Float): (Int) -> Float = { level ->
            val first = phFrames.masterBodyLevelSizes[0]
            val at = phFrames.masterBodyLevelSizes.getOrNull(level)
            if (first != null && at != null && first > 0f) top * at / first else top
        }
        val layoutName = layoutOf(name, files)
        val layoutPhs = phFrames.layout(layoutName)
        // Pull out drawn shapes, tables and charts first, excluding them from the plain text scan
        // so their text is not folded into the title/body.
        val excluded = HashSet<XmlNode>()
        val shapes = ArrayList<SlideShape>()
        // Shape id (cNvPr@id, what <p:spTgt> names) → the element its <p:timing> effects animate.
        val animTargets = HashMap<Int, AnimTarget>()
        // Shapes and lines (connectors), in the order they are drawn: the later on top.
        val sps = root.descendants(setOf("sp", "cxnSp"))
        // Groups (<p:grpSp>): children keep the group's id and are mapped through its transform
        // (chOff/chExt → off/ext), so a moved or scaled group lands where PowerPoint shows it.
        val groupOf = java.util.IdentityHashMap<XmlNode, GroupCtx>()
        root.descendants("grpSp").forEachIndexed { gi, grp ->
            val ctx = GroupCtx(gi + 1, grp.child("grpSpPr")?.child("xfrm"), phFrames.dims)
            (grp.descendants("sp") + grp.descendants("cxnSp") + grp.descendants("pic")).forEach { groupOf[it] = ctx }
        }
        for (sp in sps) {
            val ph = placeholder(sp)
            if (ph != null) {
                // Footer / slide-number / date placeholders are chrome, not slide text.
                val type = ph.attr("type")
                if (type != null && type in CHROME_PH) excluded.add(sp)
                continue                                      // title/body placeholders handled below
            }
            if (!sp.has("prstGeom") && !sp.has("custGeom")) continue   // not a drawn shape
            parseShape(sp, ctx)?.let { s ->
                groupOf[sp]?.let { g -> s.groupId = g.id; g.map(s.xPct, s.yPct, s.wPct, s.hPct)?.let { f -> s.xPct = f[0]; s.yPct = f[1]; s.wPct = f[2]; s.hPct = f[3] } }
                named(sp, s)
                shapes.add(s); excluded.add(sp)
                shapeId(sp)?.let { id -> animTargets[id] = s.box }
            }
        }
        val tables = ArrayList<SlideTable>()
        val charts = ArrayList<SlideChart>()
        for (frame in root.descendants("graphicFrame")) {
            val tbl = frame.descendants("tbl").firstOrNull()
            when {
                tbl != null -> parseTable(frame, tbl, ctx)?.let { t ->
                    named(frame, t)
                    tables.add(t); excluded.add(frame)
                    shapeId(frame)?.let { id -> animTargets[id] = t }
                }
                frame.has("chart") -> parseChart(frame, name, files, rels, ctx.dims)?.let { c ->
                    named(frame, c)
                    charts.add(c); excluded.add(frame)
                    shapeId(frame)?.let { id -> animTargets[id] = c }
                }
            }
        }

        // Pictures (<p:pic>): media via the blip's r:embed → slide rels; frame from the pic's own a:xfrm.
        val relById = rels.associateBy { it.id }
        val images = ArrayList<SlideImage>()
        val picBlips = HashSet<XmlNode>()
        for (pic in root.descendants("pic")) {
            val blip = pic.descendants("blip").firstOrNull() ?: continue
            picBlips.add(blip)
            val img = imageOf(blip, relById, name, files) ?: continue
            pic.child("blipFill")?.child("srcRect")?.let { r -> com.therealityteam.aeropages.format.PictureCrop.readSrcRect(r::attr, img) }
            pic.child("spPr")?.child("effectLst")?.child("outerShdw")?.let { img.shadow = DrawingShape.shadowOf(it, ctx.palette) }
            img.media = mediaOf(pic, relById, name, files)
            img.action = clickOf(pic, ctx)
            img.hoverAction = clickOf(pic, ctx, "hlinkHover")
            OoxmlAltText.read(pic.child("nvPicPr")?.child("cNvPr")).let { (alt, decorative) -> img.altText = alt; img.decorative = decorative }
            if (img.media == null) named(pic, img) else img.hidden = pic.child("nvPicPr")?.child("cNvPr")?.attr("hidden").let { it == "1" || it == "true" }
            turn(pic, img)
            val f = frameOf(pic, phFrames.dims)
            if (f.x != null && f.y != null) { img.xPct = f.x; img.yPct = f.y }
            if (f.w != null && f.h != null && f.w > 0f && f.h > 0f) { img.wPct = f.w.coerceAtMost(1f); img.hPct = f.h.coerceAtMost(1f) }
            groupOf[pic]?.map(img.xPct, img.yPct, img.wPct, img.hPct)?.let { m -> img.xPct = m[0]; img.yPct = m[1]; img.wPct = m[2]; img.hPct = m[3] }
            shapeId(pic)?.let { id -> animTargets[id] = img }
            images.add(img)
        }
        // Pictures that are not <p:pic> (a shape's blipFill, foreign containers): keep the media at the default frame.
        for (blip in root.descendants("blip")) if (blip !in picBlips) imageOf(blip, relById, name, files)?.let { images.add(it) }

        // Title / body: prefer the placeholder shapes (rich, preserves run formatting);
        // fall back to a plain paragraph scan for files that don't use ph placeholders.
        // A content placeholder is usually typed only by its idx (`<p:ph idx="1"/>`, type obj): body.
        val titleSp = sps.firstOrNull { kindOf(it) == "title" }
        val bodySp = sps.firstOrNull { kindOf(it) == "body" }
        val slide: Slide
        if (titleSp != null || bodySp != null) {
            // Text the slide leaves unset is its layout placeholder's, else the master's.
            val titleLook = layoutLook(titleSp, "title", layoutPhs, master, deckPalette)
            val bodyLook = layoutLook(bodySp, "body", layoutPhs, master, deckPalette)
            val titleBox = titleSp?.let { richBox(it.child("txBody"), titleLook?.sizePt ?: titleSize, ctx, spacing = phFrames.masterTitleSpacing, look = titleLook,
                align = placeholderAlign(it, "title", layoutPhs, phFrames)) }
                ?: SlideBox("", CharacterFormat(fontSizePt = titleLook?.sizePt ?: titleSize, bold = true), TextAlign.CENTER)
            val bodyBox = bodySp?.let { sp -> richBox(sp.child("txBody"), bodyLook?.sizePt ?: bodySize, ctx, levelSizeFrom(bodyLook?.sizePt ?: bodySize), phFrames.masterBodySpacing, bodyLook,
                placeholderAlign(sp, "body", layoutPhs, phFrames)) }
                ?: SlideBox("", CharacterFormat(fontSizePt = bodyLook?.sizePt ?: bodySize))
            // Bullets and numbering: the slide's own, else the layout's, else the master's (a deck's usual source).
            titleSp?.let { titleBox.listStyle = listStyleOf(it, "title", layoutPhs, phFrames) }
            bodySp?.let { bodyBox.listStyle = listStyleOf(it, "body", layoutPhs, phFrames) }
            // Placeholders show where the deck puts them: the slide's own xfrm, else the layout's, else the master's.
            titleBox.frame = placeholderFrame(titleSp, "title", layoutPhs, phFrames.master, phFrames.dims)
            bodyBox.frame = placeholderFrame(bodySp, "body", layoutPhs, phFrames.master, phFrames.dims)
            slide = Slide(title = titleBox, body = bodyBox)
            // The slide's layout: a title slide (its layout says so, or its placeholders are a centred
            // title and a subtitle), a section header, or — with no body placeholder, as PowerPoint's
            // Title Only — a blank slide showing its title.
            val phTypes = listOfNotNull(titleSp, bodySp).mapNotNull { placeholder(it)?.attr("type") }
            val layoutType = phFrames.layoutType(layoutName)
            slide.layout = when {
                layoutType == "title" || "ctrTitle" in phTypes || "subTitle" in phTypes -> com.therealityteam.aeropages.model.SlideLayout.TITLE
                layoutType == "secHead" -> com.therealityteam.aeropages.model.SlideLayout.SECTION
                // A table, chart or picture put in the content placeholder (PowerPoint writes p:ph on it; LibreOffice keeps
                // only the Title and Content layout — checked below once its objects are read): still Title and Content.
                bodySp == null && layoutType != "obj" && (sequenceOf("graphicFrame", "pic").flatMap { root.descendants(it) }
                    .none { n -> n.children.firstOrNull { it.name.startsWith("nv") }?.child("nvPr")?.child("ph") != null }) ->
                    com.therealityteam.aeropages.model.SlideLayout.BLANK
                else -> com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT
            }
            // Further text placeholders (a two-content layout's second column, a subtitle next to a
            // body) have no slot in the model: keep them as text boxes at their frames.
            val extras = ArrayList<SlideShape>()
            for (sp in sps) {
                if (sp === titleSp || sp === bodySp) continue
                val kind = kindOf(sp) ?: continue
                if (kind != "title" && kind != "body") continue
                val look = layoutLook(sp, kind, layoutPhs, master, deckPalette)
                val size = look?.sizePt ?: if (kind == "title") titleSize else bodySize
                val box = richBox(sp.child("txBody"), size, ctx, if (kind == "title") null else levelSizeFrom(size),
                    if (kind == "title") phFrames.masterTitleSpacing else phFrames.masterBodySpacing, look, placeholderAlign(sp, kind, layoutPhs, phFrames))
                box.listStyle = listStyleOf(sp, kind, layoutPhs, phFrames)
                if (box.text.isBlank()) continue
                val f = placeholderFrame(sp, kind, layoutPhs, phFrames.master, phFrames.dims) ?: continue
                val sh = SlideShape(ShapeType.TEXTBOX, box, f.xPct, f.yPct, f.wPct, f.hPct, fill = null, border = null)
                sh.anchor = anchorOf(sp, if (kind == "title") ShapeAnchor.CENTER else ShapeAnchor.TOP)
                shapeId(sp)?.let { id -> animTargets[id] = sh.box }
                extras.add(sh); excluded.add(sp)
            }
            shapes.addAll(0, extras)
        } else {
            val paras = ArrayList<String>()
            collectParagraphs(root, excluded, paras)
            val lines = paras.map { it.trim() }.filter { it.isNotEmpty() }
            slide = Slide(
                title = SlideBox(lines.firstOrNull() ?: "", CharacterFormat(fontSizePt = titleSize, bold = true), TextAlign.CENTER),
                body = SlideBox(if (lines.size > 1) lines.drop(1).joinToString("\n") else "", CharacterFormat(fontSizePt = bodySize)),
            )
            // No placeholders and no loose text (PowerPoint's Blank): nothing to ask for.
            if (lines.isEmpty()) slide.layout = com.therealityteam.aeropages.model.SlideLayout.BLANK
        }
        // Background: the slide's own, else its layout's, else its layout's master's (a deck may have
        // several); one equal to the (first) master's is left to inherit.
        val ownBg = root.child("cSld")?.child("bg")?.let { palette.backgroundFill(it) }?.takeIf { it.first != null }
        val layoutBg = layoutName?.let { ln ->
            if (!layoutBgs.containsKey(ln)) layoutBgs[ln] = XmlPull.parse(files[ln])?.let { layout ->
                val bg = layout.child("cSld")?.child("bg")
                    ?: XmlPull.rels(files[XmlPull.relsOf(ln)]).firstOrNull { it.type.endsWith("/slideMaster") }
                        ?.let { r -> XmlPull.partName(files, ln, r.target)?.let { XmlPull.parse(files[it]) } }?.child("cSld")?.child("bg")
                // The deck's palette: the cache serves every slide on the layout, whatever its own map.
                bg?.let { deckPalette.backgroundFill(it) }?.takeIf { it.first != null }
            }
            layoutBgs[ln]
        }
        // (Its colour and gradient both the master's: inherited.)
        (ownBg ?: layoutBg)?.takeIf { (c, shade) -> c != master.background || shade != master.backgroundShade }?.let { (c, shade) ->
            slide.background = c; slide.backgroundShade = shade
        }
        slide.images.addAll(images)
        slide.shapes.addAll(shapes)
        slide.tables.addAll(tables)
        slide.charts.addAll(charts)
        // A Title and Content slide without its body is one whose placeholder an object took; none did: it was deleted (Title Only).
        if (slide.layout == com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT && slide.body.text.isBlank() && !slide.contentTaken &&
            root.descendants("sp").none { kindOf(it) == "body" }) slide.layout = com.therealityteam.aeropages.model.SlideLayout.BLANK
        // The title and body placeholders the text came from animate the slide's title and body.
        titleSp?.let { sp -> shapeId(sp)?.let { animTargets[it] = slide.title } }
        bodySp?.let { sp -> shapeId(sp)?.let { animTargets[it] = slide.body } }
        readTimingAnimations(root, animTargets)
        // Hidden flag (<p:sld show="0">) + transition element (incl. rehearsed advTm).
        slide.hidden = root.attr("show") == "0"
        transitionOf(root).let { (t, dir) -> slide.transition = t; slide.transitionDir = dir }
        // A speed without an effect (LibreOffice writes an empty transition on every slide) says nothing.
        slide.transitionSpeed = if (slide.transition == SlideTransition.NONE) com.therealityteam.aeropages.model.TransitionSpeed.MEDIUM else parseTransitionSpeed(root)
        slide.advanceMs = root.descendants("transition").firstNotNullOfOrNull { it.attr("advTm")?.toIntOrNull() } ?: 0
        // Speaker notes: slide rels → notesSlide part → body-placeholder text (one line per a:p).
        rels.firstOrNull { it.type.endsWith("notesSlide") }?.let { rel ->
            val part = XmlPull.partName(files, name, rel.target) ?: return@let
            val notes = XmlPull.parse(files[part]) ?: return@let
            // Prefer the body placeholder (foreign files also carry slide-number/date shapes).
            val src = notes.descendants("sp").firstOrNull { placeholder(it)?.attr("type") == "body" } ?: notes
            val lines = src.descendants("p").map { it.textOf("t") }
            if (lines.any { it.isNotBlank() }) slide.notes = lines.joinToString("\n").trim('\n')
        }
        return slide
    }

    /**
     * A slide's comments: its comments part — PowerPoint's classic `p:cmLst` (authors in commentAuthors.xml, `p:pos` in eighths
     * of a point, a reply's parent in `p15:threadingInfo`) or its modern `p188:cmLst` (authors in authors.xml by id, the text a
     * DrawingML body, replies in `p188:replyLst`, resolved as its status).
     */
    private fun readComments(files: Map<String, ByteArray>, slide: String, size: com.therealityteam.aeropages.model.SlideSize): List<com.therealityteam.aeropages.model.SlideComment> {
        val out = ArrayList<com.therealityteam.aeropages.model.SlideComment>()
        val rels = XmlPull.rels(files[XmlPull.relsOf(slide)]).filter { it.type.endsWith("/comments") }
        for (rel in rels) {
            val part = XmlPull.partName(files, slide, rel.target) ?: continue
            val root = XmlPull.parse(files[part]) ?: continue
            val cms = root.children("cm")
            if (cms.isEmpty()) continue
            val modern = cms.any { it.child("txBody") != null || it.attr("created") != null }
            if (modern) {
                val authors = XmlPull.parse(files["ppt/authors.xml"])?.descendants("author")?.associate { (it.attr("id") ?: "") to (it.attr("name") ?: "") }.orEmpty()
                fun body(n: XmlNode) = n.child("txBody")?.children("p")?.joinToString("\n") { it.textOf("t") }?.trim().orEmpty()
                for (cm in cms) {
                    val replies = cm.child("replyLst")?.children("reply").orEmpty().map { r ->
                        com.therealityteam.aeropages.model.SlideComment(authors[r.attr("authorId")].orEmpty(), body(r), dateOf(r.attr("created")))
                    }
                    out.add(com.therealityteam.aeropages.model.SlideComment(authors[cm.attr("authorId")].orEmpty(), body(cm), dateOf(cm.attr("created")),
                        replies = replies, resolved = cm.attr("status") == "resolved"))
                }
            } else {
                val authors = XmlPull.parse(files["ppt/commentAuthors.xml"])?.descendants("cmAuthor")?.associate { (it.attr("id") ?: "") to (it.attr("name") ?: "") }.orEmpty()
                val byKey = LinkedHashMap<String, com.therealityteam.aeropages.model.SlideComment>()
                val parents = HashMap<String, String>()
                for (cm in cms) {
                    val key = "${cm.attr("authorId")}/${cm.attr("idx")}"
                    val pos = cm.child("pos")
                    val x = ((pos?.attr("x")?.toFloatOrNull() ?: 0f) / 8f / size.widthPt).coerceIn(0f, 1f)
                    val y = ((pos?.attr("y")?.toFloatOrNull() ?: 0f) / 8f / size.heightPt).coerceIn(0f, 1f)
                    byKey[key] = com.therealityteam.aeropages.model.SlideComment(authors[cm.attr("authorId")].orEmpty(), cm.child("text")?.text.orEmpty(), dateOf(cm.attr("dt")), x, y)
                    cm.descendants("parentCm").firstOrNull()?.let { pc -> parents[key] = "${pc.attr("authorId")}/${pc.attr("idx")}" }
                }
                // Replies under their thread's first comment, in order.
                val tops = LinkedHashMap<String, com.therealityteam.aeropages.model.SlideComment>()
                val topOf = HashMap<String, String>()
                for ((key, c) in byKey) {
                    val t = parents[key]?.let { topOf[it] }
                    val top = t?.let { tops[it] }
                    if (t != null && top != null) { tops[t] = top.copy(replies = top.replies + c); topOf[key] = t }
                    else { tops[key] = c; topOf[key] = key }
                }
                out.addAll(tops.values)
            }
        }
        return out
    }

    /** A comment's date: an ISO date and time (with or without a zone, without it UTC); 0 when there is none. */
    private fun dateOf(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(s.substringBefore('Z')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull() ?: 0L
    }

    /** Text of every `a:p` under [node] in document order, skipping the [excluded] subtrees. */
    private fun collectParagraphs(node: XmlNode, excluded: Set<XmlNode>, out: MutableList<String>) {
        if (node in excluded) return
        if (node.name == "p") {
            out.add(node.textOf("t"))
            return
        }
        for (c in node.children) collectParagraphs(c, excluded, out)
    }

    /** "RRGGBB" → opaque 0xAARRGGBB; null for anything else. */
    private fun hex6(hex: String?): Long? = hex?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000L or it }

    /** Per-slide parse context: the slide's hyperlink rels and the deck's theme palette. */
    private class SlideCtx(val links: Map<String, String>, val palette: Palette, val dims: Dims = Dims.WIDESCREEN, val slideParts: Map<String, String> = emptyMap())

    /** A link to a slide as the reader keeps it until every slide is read: the slide part it names. */
    private const val SLIDE_PART_LINK = "#slidepart:"

    /**
     * Where an `a:hlinkClick` goes: its address (the relationship's target, a file's too), a slide
     * (`ppaction://hlinksldjump`: the relationship's slide part), the show's next, previous, first or
     * last slide or its end (`ppaction://hlinkshowjump?jump=…`, the last slide viewed taken as the
     * previous); null for what the show does not do (a program, a macro, a custom show, a media's play).
     */
    private fun linkOf(h: XmlNode?, ctx: SlideCtx): String? {
        h ?: return null
        val action = h.attr("action").orEmpty()
        val rid = h.attr(XmlPull.R_NS, "id")?.takeIf { it.isNotEmpty() }
        return when {
            action.isEmpty() || action.startsWith("ppaction://hlinkfile") || action.startsWith("ppaction://hlinkpres") -> rid?.let { ctx.links[it] }
            action.startsWith("ppaction://hlinksldjump") -> rid?.let { ctx.slideParts[it] }?.let { SLIDE_PART_LINK + it }
            action.startsWith("ppaction://hlinkshowjump") -> when (action.substringAfter("jump=", "").substringBefore('&')) {
                "nextslide" -> com.therealityteam.aeropages.model.SlideLinks.NEXT
                "previousslide", "lastslideviewed" -> com.therealityteam.aeropages.model.SlideLinks.PREVIOUS
                "firstslide" -> com.therealityteam.aeropages.model.SlideLinks.FIRST
                "lastslide" -> com.therealityteam.aeropages.model.SlideLinks.LAST
                "endshow" -> com.therealityteam.aeropages.model.SlideLinks.END
                else -> null
            }
            else -> null
        }
    }

    /** A shape's or picture's own click (its `cNvPr`'s `a:hlinkClick`). */
    private fun clickOf(node: XmlNode, ctx: SlideCtx, element: String = "hlinkClick"): String? =
        node.children.firstOrNull { it.name.startsWith("nv") }?.child("cNvPr")?.child(element)?.let { linkOf(it, ctx) }

    /**
     * Theme colours (`a:clrScheme`), the master's colour map (bg1 → lt1 …) and the major / minor
     * fonts, so `a:schemeClr` and `+mj-lt` / `+mn-lt` references resolve to real values.
     */
    internal class Palette(
        private val scheme: Map<String, Long>, private val clrMap: Map<String, String>, val majorFont: String?, val minorFont: String?,
        /** The theme's effect styles (`a:effectStyleLst`), what a shape's `a:effectRef` idx 1, 2, 3 names. */
        val effects: List<XmlNode> = emptyList(),
        /** The theme's background fills (`a:bgFillStyleLst`), what a `p:bgRef` idx 1001, 1002, 1003 names. */
        val backgroundFills: List<XmlNode> = emptyList(),
    ) {

        /**
         * Colour of a fill element (`a:solidFill`, a gradient stop, `p:bgRef`, a scheme entry…): its
         * srgbClr / schemeClr / sysClr / prstClr child, with lumMod, lumOff, tint and shade applied.
         */
        fun colorOf(fill: XmlNode?): Long? {
            val clr = fill?.children?.firstOrNull { it.name in COLOR_TAGS } ?: return null
            val base = when (clr.name) {
                "srgbClr" -> hex6(clr.attr("val"))
                "sysClr" -> hex6(clr.attr("lastClr")) ?: if (clr.attr("val") == "window") 0xFFFFFFFF else 0xFF000000
                "schemeClr" -> clr.attr("val")?.let { v -> scheme[clrMap[v] ?: v] }
                "prstClr" -> PRESET_COLORS[clr.attr("val")]
                else -> null
            } ?: return null
            return modify(base, clr)
        }

        /** A shape's fill or outline colour ([colorOf]) as see-through as its `a:alpha` says. */
        fun fillColorOf(fill: XmlNode?): Long? {
            val c = colorOf(fill) ?: return null
            val alpha = fill?.children?.firstOrNull { it.name in COLOR_TAGS }?.child("alpha")?.attr("val")?.toFloatOrNull() ?: return c
            return (c and 0xFFFFFFL) or (Math.round((alpha / 100000f).coerceIn(0f, 1f) * 255).toLong() shl 24)
        }

        /** A colour of the theme's scheme by its name (`lt1`, `dk1`, `accent1` … `folHlink`), or null. */
        fun themeColor(name: String): Long? = scheme[name]

        /** This palette as a slide's `p:clrMapOvr/a:overrideClrMapping` [override] maps the theme (itself without one). */
        fun overriddenBy(override: XmlNode?): Palette {
            if (override == null || MAP_KEYS.none { override.attr(it) != null }) return this
            return Palette(scheme, MAP_KEYS.associateWith { override.attr(it) ?: clrMap[it] ?: it }, majorFont, minorFont, effects, backgroundFills)
        }

        /**
         * A master text style's colour: null (the app's automatic colour, which reads on dark slides too)
         * for the colour map's plain dark text colour (`tx1`, unmodified), which is what PowerPoint
         * turns light on a dark slide; else the colour.
         */
        fun textColorOf(fill: XmlNode?): Long? {
            val c = colorOf(fill) ?: return null
            val clr = fill?.children?.firstOrNull { it.name in COLOR_TAGS }
            val plainTx1 = clr?.name == "schemeClr" && clr.attr("val") == "tx1" && clr.children.isEmpty()
            return if (plainTx1 && com.therealityteam.aeropages.model.TextColors.luminance(c) < 0.1) null else c
        }

        /** Background colour of a `p:bg` (see [backgroundFill]). */
        fun background(bg: XmlNode?): Long? = backgroundFill(bg).first

        /**
         * A `p:bg` as its colour and, for a gradient, the shade from it: bgPr's solid fill or gradient, or
         * (`p:bgRef`) the theme's background fill it names in the reference's colour — a gradient there
         * shading in that colour's tints and shades ("phClr").
         */
        fun backgroundFill(bg: XmlNode?): Pair<Long?, com.therealityteam.aeropages.model.BackgroundShade?> {
            val pr = bg?.child("bgPr")
            if (pr != null) {
                pr.child("gradFill")?.let { g -> shadeOf(g)?.let { return it.first to it.second } }
                return colorOf(pr.child("solidFill")) to null
            }
            val ref = bg?.child("bgRef") ?: return null to null
            val color = colorOf(ref) ?: return null to null
            val style = backgroundFills.getOrNull((ref.attr("idx")?.toIntOrNull() ?: 0) - 1001)
            if (style?.name == "gradFill") {
                Palette(scheme + ("phClr" to color), clrMap, majorFont, minorFont).shadeOf(style)?.let { return it.first to it.second }
            }
            return color to null
        }

        /**
         * A gradient fill (`a:gradFill`) as its first colour and the shade from it to its last: along its
         * `a:lin` angle; a path gradient (`a:path` circle / rect) from its fill-to rectangle's centre; one
         * colour at both ends and another between, axial — as a shape's gradient reads.
         */
        fun shadeOf(grad: XmlNode): Pair<Long, com.therealityteam.aeropages.model.BackgroundShade>? {
            val stops = grad.child("gsLst")?.children("gs")?.sortedBy { it.attr("pos")?.toIntOrNull() ?: 0 } ?: return null
            val first = stops.firstOrNull()?.let { colorOf(it) } ?: return null
            val last = stops.lastOrNull()?.let { colorOf(it) } ?: first
            val angle = grad.child("lin")?.attr("ang")?.toFloatOrNull()?.div(60000f) ?: 90f
            val path = grad.child("path")
            val shade = when {
                path != null -> {
                    val r = path.child("fillToRect")
                    fun v(k: String) = (r?.attr(k)?.toFloatOrNull() ?: 0f) / 100000f
                    val kind = if (path.attr("path") == "rect") com.therealityteam.aeropages.model.GradientKind.RECTANGULAR else com.therealityteam.aeropages.model.GradientKind.RADIAL
                    com.therealityteam.aeropages.model.BackgroundShade(last, 0f, kind, ((v("l") + 1f - v("r")) / 2f).coerceIn(0f, 1f), ((v("t") + 1f - v("b")) / 2f).coerceIn(0f, 1f))
                }
                stops.size == 3 && first == last && (stops[1].attr("pos")?.toIntOrNull() ?: 0) in 40000..60000 ->
                    com.therealityteam.aeropages.model.BackgroundShade(colorOf(stops[1]) ?: first, angle, com.therealityteam.aeropages.model.GradientKind.AXIAL)
                else -> com.therealityteam.aeropages.model.BackgroundShade(last, angle)
            }
            return first to shade
        }

        /** A run's typeface: theme references (+mj-lt / +mn-lt) become the theme's fonts. */
        fun typeface(name: String?): String? = when {
            name.isNullOrEmpty() -> null
            name.startsWith("+mj") -> majorFont
            name.startsWith("+mn") -> minorFont
            else -> name
        }

        /** Apply the colour transforms nested in a colour element (in document order). */
        private fun modify(color: Long, clr: XmlNode): Long {
            var c = color
            for (m in clr.children) {
                val v = (m.attr("val")?.toFloatOrNull() ?: continue) / 100000f
                c = when (m.name) {
                    "lumMod" -> lum(c) { it * v }
                    "lumOff" -> lum(c) { it + v }
                    "tint" -> mix(c, 0xFFFFFFFF, 1f - v)     // a 40% tint is 40% colour, 60% white
                    "shade" -> mix(c, 0xFF000000, 1f - v)
                    else -> c
                }
            }
            return c
        }

        companion object {
            private val COLOR_TAGS = setOf("srgbClr", "schemeClr", "sysClr", "prstClr")
            private val PRESET_COLORS = mapOf(
                "white" to 0xFFFFFFFF, "black" to 0xFF000000, "red" to 0xFFFF0000, "green" to 0xFF008000, "blue" to 0xFF0000FF,
                "yellow" to 0xFFFFFF00, "gray" to 0xFF808080, "grey" to 0xFF808080, "orange" to 0xFFFFA500, "purple" to 0xFF800080,
            )
            /** Office's default colour scheme: what a deck without a theme part falls back to. */
            val DEFAULT_SCHEME: Map<String, Long> = mapOf(
                "dk1" to 0xFF000000, "lt1" to 0xFFFFFFFF, "dk2" to 0xFF44546A, "lt2" to 0xFFE7E6E6,
                "accent1" to 0xFF4472C4, "accent2" to 0xFFED7D31, "accent3" to 0xFFA5A5A5, "accent4" to 0xFFFFC000,
                "accent5" to 0xFF5B9BD5, "accent6" to 0xFF70AD47, "hlink" to 0xFF0563C1, "folHlink" to 0xFF954F72,
            )
            val DEFAULT_MAP: Map<String, String> = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
            val MAP_KEYS = listOf("bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
            val DEFAULT = Palette(DEFAULT_SCHEME, DEFAULT_MAP, "Calibri Light", "Calibri")

            /** Luminance transform in HSL space (what lumMod / lumOff mean). */
            fun lum(c: Long, f: (Float) -> Float): Long {
                val r = ((c shr 16) and 0xFF) / 255f; val g = ((c shr 8) and 0xFF) / 255f; val b = (c and 0xFF) / 255f
                val max = maxOf(r, g, b); val min = minOf(r, g, b)
                val l = (max + min) / 2f
                val d = max - min
                val s = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * l - 1f))
                var h = when {
                    d == 0f -> 0f
                    max == r -> 60f * (((g - b) / d) % 6f)
                    max == g -> 60f * ((b - r) / d + 2f)
                    else -> 60f * ((r - g) / d + 4f)
                }
                if (h < 0f) h += 360f
                val nl = f(l).coerceIn(0f, 1f)
                val cc = (1f - kotlin.math.abs(2f * nl - 1f)) * s
                val x = cc * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
                val m = nl - cc / 2f
                val (r1, g1, b1) = when {
                    h < 60f -> Triple(cc, x, 0f)
                    h < 120f -> Triple(x, cc, 0f)
                    h < 180f -> Triple(0f, cc, x)
                    h < 240f -> Triple(0f, x, cc)
                    h < 300f -> Triple(x, 0f, cc)
                    else -> Triple(cc, 0f, x)
                }
                return rgb(r1 + m, g1 + m, b1 + m)
            }

            /** [a] moved [t] (0..1) of the way to [b], per channel. */
            fun mix(a: Long, b: Long, t: Float): Long {
                fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1f - t) + ((b shr shift) and 0xFF) * t) / 255f
                return rgb(ch(16), ch(8), ch(0))
            }

            private fun rgb(r: Float, g: Float, b: Float): Long {
                fun q(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
                return 0xFF000000L or (q(r) shl 16) or (q(g) shl 8) or q(b)
            }
        }
    }

    private val MASTER_PART = Regex("ppt/slideMasters/slideMaster(\\d+)\\.xml")

    /** The first slide master part (decks normally have one; the lowest number wins). */
    private fun masterPart(files: Map<String, ByteArray>): String? =
        files.keys.mapNotNull { k -> MASTER_PART.matchEntire(k)?.let { k to it.groupValues[1].toInt() } }.minByOrNull { it.second }?.first

    /** The slide's layout part (its slideLayout relationship), or null. */
    private fun layoutOf(slideName: String, files: Map<String, ByteArray>): String? =
        XmlPull.rels(files[XmlPull.relsOf(slideName)]).firstOrNull { it.type.endsWith("/slideLayout") }?.let { XmlPull.partName(files, slideName, it.target) }

    /** Theme colour scheme + fonts (from the master's theme part) and the master's colour map. */
    private fun readPalette(files: Map<String, ByteArray>): Palette {
        val masterName = masterPart(files)
        val masterRoot = masterName?.let { XmlPull.parse(files[it]) }
        val clrMap = masterRoot?.child("clrMap")?.let { node -> Palette.MAP_KEYS.associateWith { node.attr(it) ?: Palette.DEFAULT_MAP[it] ?: it } } ?: Palette.DEFAULT_MAP
        val themeName = masterName?.let { mn ->
            XmlPull.rels(files[XmlPull.relsOf(mn)]).firstOrNull { it.type.endsWith("/theme") }?.let { XmlPull.partName(files, mn, it.target) }
        } ?: files.keys.filter { it.startsWith("ppt/theme/theme") }.minOrNull()
        val theme = themeName?.let { XmlPull.parse(files[it]) } ?: return Palette(Palette.DEFAULT_SCHEME, clrMap, "Calibri Light", "Calibri")
        val raw = Palette(emptyMap(), emptyMap(), null, null)
        val scheme = HashMap(Palette.DEFAULT_SCHEME)
        theme.descendants("clrScheme").firstOrNull()?.children?.forEach { n -> raw.colorOf(n)?.let { scheme[n.name] = it } }
        val fonts = theme.descendants("fontScheme").firstOrNull()
        val major = fonts?.child("majorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotEmpty() }
        val minor = fonts?.child("minorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotEmpty() }
        return Palette(scheme, clrMap, major, minor, effectStyles(theme), backgroundFills(theme))
    }

    /** A theme's effect styles, in order. */
    private fun effectStyles(theme: XmlNode): List<XmlNode> = theme.descendants("effectStyleLst").firstOrNull()?.children("effectStyle").orEmpty()

    /** A theme's background fills (solid, gradient, picture …), in order. */
    private fun backgroundFills(theme: XmlNode): List<XmlNode> = theme.descendants("bgFillStyleLst").firstOrNull()?.children.orEmpty()

    /**
     * The slide master's background and its title / body text defaults (`p:txStyles`). A master that
     * sets none of them, nor places anything (LibreOffice 26 keeps each master page's look on a layout
     * of a bare master), has the first slide's layout's: its background, its title and body
     * placeholders' first levels, where it puts the date, footer and number.
     */
    private fun readMaster(files: Map<String, ByteArray>, palette: Palette, firstLayout: String?): SlideMaster {
        val m = SlideMaster()
        val root = masterPart(files)?.let { XmlPull.parse(files[it]) } ?: return m
        val bare = root.child("txStyles") == null && root.child("cSld")?.child("bg") == null && root.descendants("ph").isEmpty()
        val layout = if (bare) firstLayout?.let { XmlPull.parse(files[it]) } else null
        if (layout != null) {
            palette.backgroundFill(layout.child("cSld")?.child("bg")).let { (c, shade) -> m.background = c; m.backgroundShade = shade }
            m.chrome.putAll(chromeOf(layout, palette, masterDims(files)))
            val (title, body) = placeholderTexts(layout, palette)
            title?.let { t -> m.titleSizePt = t.sizePt; m.titleBold = t.bold?.takeIf { it }; m.titleColor = t.color; m.titleFont = t.font }
            body?.let { b -> m.bodySizePt = b.sizePt; m.bodyColor = b.color; m.bodyFont = b.font }
            return m
        }
        palette.backgroundFill(root.child("cSld")?.child("bg")).let { (c, shade) -> m.background = c; m.backgroundShade = shade }
        m.chrome.putAll(chromeOf(root, palette, masterDims(files)))
        val (title, body) = textStyles(root, palette) ?: return m
        m.titleSizePt = title.sizePt; m.titleBold = title.bold; m.titleColor = title.color; m.titleFont = title.font
        m.bodySizePt = body.sizePt; m.bodyColor = body.color; m.bodyFont = body.font
        return m
    }

    /** The slide size (`p:sldSz`) as the master's frames are measured against. */
    private fun masterDims(files: Map<String, ByteArray>): Dims {
        val size = XmlPull.parse(files[PRESENTATION])?.descendants("sldSz")?.firstOrNull()
        return Dims(size?.attr("cx")?.toDoubleOrNull()?.takeIf { it > 0 } ?: Dims.WIDESCREEN.cx, size?.attr("cy")?.toDoubleOrNull()?.takeIf { it > 0 } ?: Dims.WIDESCREEN.cy)
    }

    /**
     * Where a master or layout part puts the date, footer and number (its framed `dt` / `ftr` /
     * `sldNum` placeholders) and how their text reads — its list style's first level (alignment,
     * size, colour), else its first run's — each one not where the app puts it.
     */
    private fun chromeOf(root: XmlNode, palette: Palette, dims: Dims): Map<com.therealityteam.aeropages.model.SlideChrome.Part, com.therealityteam.aeropages.model.ChromeLook> {
        val out = HashMap<com.therealityteam.aeropages.model.SlideChrome.Part, com.therealityteam.aeropages.model.ChromeLook>()
        val tree = root.child("cSld")?.child("spTree") ?: return out
        for (sp in tree.children("sp")) {
            val part = when (placeholder(sp)?.attr("type")) {
                "dt" -> com.therealityteam.aeropages.model.SlideChrome.Part.DATE
                "ftr" -> com.therealityteam.aeropages.model.SlideChrome.Part.FOOTER
                "sldNum" -> com.therealityteam.aeropages.model.SlideChrome.Part.NUMBER
                else -> continue
            }
            val f = frameOf(sp.child("spPr") ?: continue, dims).toBoxFrame() ?: continue
            val lvl = sp.child("txBody")?.child("lstStyle")?.child("lvl1pPr")
            val rPr = lvl?.child("defRPr") ?: sp.descendants("rPr").firstOrNull()
            val align = when (lvl?.attr("algn") ?: sp.descendants("pPr").firstOrNull()?.attr("algn")) {
                "ctr" -> com.therealityteam.aeropages.model.TextAlign.CENTER
                "r" -> com.therealityteam.aeropages.model.TextAlign.RIGHT
                "l" -> com.therealityteam.aeropages.model.TextAlign.LEFT
                else -> part.align
            }
            val size = rPr?.attr("sz")?.toIntOrNull()?.let { it / 100f } ?: com.therealityteam.aeropages.model.SlideChrome.SIZE_PT
            val look = com.therealityteam.aeropages.model.ChromeLook(f.xPct, f.yPct, f.wPct, f.hPct, align, size, palette.textColorOf(rPr?.child("solidFill")))
            if (!com.therealityteam.aeropages.model.SlideChrome.isDefault(part, look)) out[part] = look
        }
        return out
    }

    /** A master part's title and body text defaults (its `p:txStyles` first levels), or null without any. */
    private fun textStyles(master: XmlNode, palette: Palette): Pair<com.therealityteam.aeropages.model.MasterText, com.therealityteam.aeropages.model.MasterText>? {
        val tx = master.child("txStyles") ?: return null
        fun of(style: String, bold: Boolean): com.therealityteam.aeropages.model.MasterText {
            val d = tx.child(style)?.child("lvl1pPr")?.child("defRPr") ?: return com.therealityteam.aeropages.model.MasterText(null, null, null, null)
            return com.therealityteam.aeropages.model.MasterText(palette.typeface(d.child("latin")?.attr("typeface")), d.attr("sz")?.toIntOrNull()?.let { it / 100f },
                palette.textColorOf(d.child("solidFill")), if (bold) d.attr("b")?.let { it == "1" || it == "true" }?.takeIf { it } else null)
        }
        return of("titleStyle", true) to of("bodyStyle", false)
    }

    /** A layout's title and body placeholders' own text (their list styles' first levels), where they set any. */
    private fun placeholderTexts(layout: XmlNode, palette: Palette): Pair<com.therealityteam.aeropages.model.MasterText?, com.therealityteam.aeropages.model.MasterText?> {
        val phs = layout.child("cSld")?.child("spTree")?.children("sp").orEmpty()
        fun of(kind: String) = phs.firstOrNull { kindOf(it) == kind }?.let { listText(it.child("txBody")?.child("lstStyle")?.child("lvl1pPr"), palette) }
        return of("title") to of("body")
    }

    /** A paragraph's (or list level's) alignment (`algn`), or null when it does not say. */
    private fun alignOf(pPr: XmlNode?): TextAlign? = when (pPr?.attr("algn")) {
        "l" -> TextAlign.LEFT
        "ctr" -> TextAlign.CENTER
        "r" -> TextAlign.RIGHT
        "just", "dist" -> TextAlign.JUSTIFY
        else -> null
    }

    /**
     * How a slide placeholder's text aligns where its paragraphs do not say: its own list style's
     * first level, else its layout placeholder's (PowerPoint's title slide centres its title and
     * subtitle there), else the master's text style's; left when none does.
     */
    private fun placeholderAlign(sp: XmlNode?, kind: String, layout: List<PhFrame>, frames: PhFrames): TextAlign {
        alignOf(sp?.child("txBody")?.child("lstStyle")?.child("lvl1pPr"))?.let { return it }
        val idx = sp?.let { placeholder(it)?.attr("idx") }
        ((if (idx != null) layout.firstOrNull { it.idx == idx } else null) ?: layout.firstOrNull { it.kind == kind })?.align?.let { return it }
        return (if (kind == "title") frames.masterTitleAlign else frames.masterBodyAlign) ?: TextAlign.LEFT
    }

    /** What a list level's default run (`a:defRPr`) sets — face, size, colour, bold — or null when it sets none of them. */
    private fun listText(lvl: XmlNode?, palette: Palette): com.therealityteam.aeropages.model.MasterText? {
        val d = lvl?.child("defRPr") ?: return null
        val t = com.therealityteam.aeropages.model.MasterText(palette.typeface(d.child("latin")?.attr("typeface")), d.attr("sz")?.toIntOrNull()?.let { it / 100f },
            palette.textColorOf(d.child("solidFill")), d.attr("b")?.let { it == "1" || it == "true" })
        return t.takeIf { it.font != null || it.sizePt != null || it.color != null || it.bold != null }
    }

    /**
     * The text a slide placeholder takes from its layout's (the one with its idx, else of its kind):
     * PowerPoint's title slide sets its title 60 pt there and its subtitle 24 pt, a section header its
     * text grey. Its face and colour only where they are not the master's (the slide inherits those;
     * a master naming no face shows the theme's), its bold only where the master's is not.
     */
    private fun layoutLook(sp: XmlNode?, kind: String, layout: List<PhFrame>, master: SlideMaster, palette: Palette): com.therealityteam.aeropages.model.MasterText? {
        val idx = sp?.let { placeholder(it)?.attr("idx") }
        val t = ((if (idx != null) layout.firstOrNull { it.idx == idx } else null) ?: layout.firstOrNull { it.kind == kind })?.text ?: return null
        val title = kind == "title"
        val face = if (title) master.titleFont ?: palette.majorFont else master.bodyFont ?: palette.minorFont
        return com.therealityteam.aeropages.model.MasterText(t.font?.takeIf { it != face }, t.sizePt,
            t.color?.takeIf { it != (if (title) master.titleColor else master.bodyColor) }, t.bold?.takeIf { it && (!title || master.titleBold != true) })
    }

    /** Build a CharacterFormat from a run's `a:rPr` (null = no properties). */
    private fun fmtFromRPr(rPr: XmlNode?, defaultSize: Float, ctx: SlideCtx, look: com.therealityteam.aeropages.model.MasterText? = null): CharacterFormat {
        val f = CharacterFormat()
        if (rPr != null) {
            if (rPr.attr("b").let { it == "1" || it == "true" }) f.bold = true
            if (rPr.attr("i").let { it == "1" || it == "true" }) f.italic = true
            rPr.attr("u")?.let { if (it.isNotEmpty() && it != "none") f.underline = true }
            rPr.attr("strike")?.let { if (it == "sngStrike" || it == "dblStrike") f.strikethrough = true }
            rPr.attr("sz")?.toIntOrNull()?.let { f.fontSizePt = it / 100f }
            rPr.attr("spc")?.toIntOrNull()?.let { f.letterSpacingPt = it / 100f }
            rPr.attr("baseline")?.toIntOrNull()?.let { if (it > 0) f.superscript = true else if (it < 0) f.subscript = true }
            if (rPr.attr("cap") == "small") f.smallCaps = true
            ctx.palette.colorOf(rPr.child("solidFill"))?.let { f.color = it }
            ctx.palette.colorOf(rPr.child("highlight"))?.let { f.highlight = it }
            ctx.palette.typeface(rPr.child("latin")?.attr("typeface"))?.let { f.fontFamily = it }
            linkOf(rPr.child("hlinkClick"), ctx)?.let { f.link = it }
        }
        if (f.fontSizePt == null) f.fontSizePt = defaultSize
        // What its placeholder's layout sets, where the run does not.
        if (look != null) {
            if (look.bold == true && rPr?.attr("b") == null) f.bold = true
            if (f.color == null) f.color = look.color
            if (f.fontFamily == null) f.fontFamily = look.font
        }
        return f
    }

    /**
     * Parse a txBody (or any block holding a:p paragraphs) into rich runs + the first paragraph's
     * alignment. A paragraph's level (`a:pPr@lvl`) becomes the tabs its line starts with; a run
     * without a size of its own takes [levelSize] of its level, when given, else [defaultSize].
     */
    private fun parseRich(block: XmlNode?, defaultSize: Float, ctx: SlideCtx, levelSize: ((Int) -> Float)? = null,
        look: com.therealityteam.aeropages.model.MasterText? = null, defaultAlign: TextAlign = TextAlign.LEFT): Pair<MutableList<TextRun>, TextAlign> {
        val runs = ArrayList<TextRun>()
        var align = defaultAlign
        var displayed = false
        block?.descendants("p")?.forEachIndexed { pi, p ->
            if (pi == 0) p.child("pPr")?.attr("algn")?.let {
                align = when (it) { "ctr" -> TextAlign.CENTER; "r" -> TextAlign.RIGHT; "just" -> TextAlign.JUSTIFY; else -> TextAlign.LEFT }
            }
            val level = (p.child("pPr")?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, com.therealityteam.aeropages.model.SlideLevels.MAX)
            val size = if (level > 0 && levelSize != null) levelSize(level) else defaultSize
            if (pi > 0) runs.add(TextRun("\n", CharacterFormat(fontSizePt = defaultSize)))
            if (level > 0) runs.add(TextRun("\t".repeat(level), CharacterFormat(fontSizePt = size)))
            for (child in p.children) when (child.name) {
                "r", "fld" -> {
                    val t = child.child("t")?.text ?: ""
                    if (t.isNotEmpty()) runs.add(TextRun(t, fmtFromRPr(child.child("rPr"), size, ctx, look)))
                }
                // A line break inside a paragraph: a new line under the same bullet.
                "br" -> runs.add(TextRun(com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK.toString(), fmtFromRPr(child.child("rPr"), size, ctx, look)))
                // An equation (`a14:m`, displayed or in the text): runs that spell it, tied to it.
                "m" -> for (om in child.descendants("oMath")) {
                    val size = om.descendants("rPr").firstNotNullOfOrNull { it.attr("sz")?.toIntOrNull() }?.let { it / 100f } ?: defaultSize
                    val color = om.descendants("rPr").firstNotNullOfOrNull { ctx.palette.colorOf(it.child("solidFill")) }
                    val math = com.therealityteam.aeropages.model.InlineMath(com.therealityteam.aeropages.format.docx.Omml.read(om))
                    runs.addAll(com.therealityteam.aeropages.model.MathText.runs(math, CharacterFormat(fontSizePt = size, color = color)))
                    if (child.has("oMathPara")) displayed = true
                }
            }
        }
        // A box holding only a displayed equation is centred, as PowerPoint centres its group (a box
        // with other text keeps its own alignment: the box has one).
        if (displayed && runs.all { it.format.math != null || it.text.isBlank() }) align = TextAlign.CENTER
        if (runs.isEmpty()) runs.add(TextRun("", CharacterFormat(fontSizePt = defaultSize)))
        return runs to align
    }

    /**
     * Build a SlideBox from a txBody element, preserving per-run formatting + alignment (and levels;
     * [levelSize] sizes a body's deeper ones). Its line spacing is its first paragraph's, else the
     * box's list style's, else [spacing] (the master's for a placeholder), else single.
     */
    private fun richBox(txBody: XmlNode?, defaultSize: Float, ctx: SlideCtx, levelSize: ((Int) -> Float)? = null, spacing: Float? = null,
        look: com.therealityteam.aeropages.model.MasterText? = null, align: TextAlign = TextAlign.LEFT): SlideBox {
        val (runs, align) = parseRich(txBody, defaultSize, ctx, levelSize, look, align)
        // Exact spacing is a multiple of the box's largest text, as the canvas draws it.
        val largest = runs.maxOfOrNull { it.format.fontSizePt ?: defaultSize } ?: defaultSize
        val lineSpacing = spacingOf(txBody?.child("p")?.child("pPr"), largest)
            ?: spacingOf(txBody?.child("lstStyle")?.child("lvl1pPr"), largest) ?: spacing ?: 1f
        return SlideBox(Paragraph(runs, ParagraphFormat(align = align)), lineSpacing = lineSpacing)
    }

    /**
     * [obj]'s name and visibility from [node]'s `cNvPr` (the Selection Pane's): a name of its own, not one a program
     * numbers ("Shape4", "Picture 2", "Table7" — the pane gives those its own), and `hidden`.
     */
    private fun named(node: XmlNode, obj: com.therealityteam.aeropages.model.SlideObject) {
        val c = node.children.firstOrNull { it.name.startsWith("nv") }?.child("cNvPr") ?: return
        obj.objectName = c.attr("name")?.trim()?.takeIf { it.isNotEmpty() && !AUTO_NAME.matches(it) }
        obj.hidden = c.attr("hidden").let { it == "1" || it == "true" }
    }

    /** A name a program gives a shape by number (PowerPoint's "Picture 2", LibreOffice's "Shape4", this app's "Table7"). */
    internal val AUTO_NAME = Regex("(Shape|Table|Chart|Connector|Picture|Image|Graphic|Object|Group|PlaceHolder) ?\\d+")

    /** `cNvPr@id` of a shape / graphic frame (the id `<p:spTgt>` animations refer to). */
    private fun shapeId(node: XmlNode): Int? = node.descendants("cNvPr").firstOrNull()?.attr("id")?.toIntOrNull()

    /**
     * A slide's animations from its `<p:timing>` main sequence: click groups hold chains of effects
     * that start together, each effect a `cTn` naming its class (entr / emph / exit), PowerPoint's
     * preset, a direction (`presetSubtype`), how it starts (clickEffect / withEffect / afterEffect)
     * and its own delay; its longest behaviour is its duration. Effects land on the elements their
     * `spTgt` names, numbered in list order. Motion paths, media and the like are skipped, and a
     * text build that repeats an effect per paragraph counts once, as an effect built by paragraph.
     */
    private fun readTimingAnimations(root: XmlNode, targets: Map<Int, AnimTarget>) {
        if (targets.isEmpty()) return
        val main = root.child("timing")?.descendants("cTn")?.firstOrNull { it.attr("nodeType") == "mainSeq" } ?: return
        var order = 0
        // A text build repeats its effect per paragraph (spTgt → txEl → pRg), all in one build group:
        // the first paragraph's becomes the element's effect, built by paragraph; the others are its own.
        val perParagraph = HashSet<Pair<Int, String>>()
        for (group in main.child("childTnLst")?.children("par").orEmpty()) {
            for (chain in group.child("cTn")?.child("childTnLst")?.children("par").orEmpty()) {
                for (effect in chain.child("cTn")?.child("childTnLst")?.children("par").orEmpty()) {
                    val ctn = effect.child("cTn") ?: continue
                    val cls = when (ctn.attr("presetClass")) { "entr" -> AnimClass.ENTRANCE; "emph" -> AnimClass.EMPHASIS; "exit" -> AnimClass.EXIT; else -> continue }
                    val tgt = ctn.descendants("spTgt").firstOrNull() ?: continue
                    val spid = tgt.attr("spid")?.toIntOrNull() ?: continue
                    val target = targets[spid] ?: continue
                    val paragraphTarget = tgt.child("txEl") != null
                    if (paragraphTarget && !perParagraph.add(spid to (ctn.attr("grpId") ?: cls.name))) continue
                    val start = when (ctn.attr("nodeType")) {
                        "withEffect" -> AnimStart.WITH_PREVIOUS
                        "afterEffect" -> AnimStart.AFTER_PREVIOUS
                        else -> AnimStart.ON_CLICK
                    }
                    val delay = ctn.child("stCondLst")?.child("cond")?.attr("delay")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    // Behaviours run for `dur`, twice when auto-reversing, times `repeatCount` (in thousandths);
                    // the exit's closing `set` lasts 1 ms.
                    val dur = ctn.descendants("cTn").mapNotNull { c ->
                        c.attr("dur")?.toIntOrNull()?.let { d ->
                            val rev = if (c.attr("autoRev") == "1") 2 else 1
                            val times = c.attr("repeatCount")?.toIntOrNull()?.takeIf { it > 0 } ?: 1000
                            (d.toLong() * rev * times / 1000).toInt()
                        }
                    }.filter { it > 1 }.maxOrNull() ?: 500
                    val dir = when (ctn.attr("presetSubtype")?.toIntOrNull()) { 4 -> AnimDir.BOTTOM; 2 -> AnimDir.RIGHT; 1 -> AnimDir.TOP; else -> AnimDir.LEFT }
                    target.anims.add(ElementAnim(AnimEffect.of(cls, ctn.attr("presetID")?.toIntOrNull() ?: 0), start,
                        dur.coerceIn(ElementAnim.MIN_MS, ElementAnim.MAX_MS), delay.coerceAtMost(ElementAnim.MAX_MS), dir, order++, byParagraph = paragraphTarget))
                }
            }
        }
    }

    /** The media behind an `a:blip` (r:embed → slide rels → media part), with the bitmap's pixel size from its header. */
    private fun imageOf(blip: XmlNode, relById: Map<String, XmlPull.Rel>, name: String, files: Map<String, ByteArray>): SlideImage? {
        val target = blip.attr(XmlPull.R_NS, "embed")?.let { relById[it] }?.target ?: return null
        if (!target.contains("media/")) return null // charts etc. also use r:embed-like rels
        val mediaPath = XmlPull.partName(files, name, target) ?: return null
        // PowerPoint's SVG picture: a PNG stand-in on r:embed, the SVG itself on the blip's asvg:svgBlip — drawn crisp at any size.
        val svg = blip.descendants("svgBlip").firstOrNull()?.attr(XmlPull.R_NS, "embed")?.let { relById[it] }?.target
            ?.let { XmlPull.partName(files, name, it) }?.let { files[it] }?.takeIf { com.therealityteam.aeropages.format.svg.SvgReader.isSvg(it) }
        val data = svg ?: files[mediaPath] ?: return null
        val mime = com.therealityteam.aeropages.format.PictureType.mimeOf(data, mediaPath)
        val (w, h) = ImageSize.of(data) ?: (480f to 320f)
        return SlideImage(data, mime, w, h)
    }

    /**
     * The video or sound a `p:pic` stands for — `p:nvPr`'s `a:videoFile` / `a:audioFile` (a link, to the
     * file's own copy or somewhere else) and the copy PowerPoint 2010+ embeds (`p14:media`) — or null
     * for a plain picture.
     */
    private fun mediaOf(pic: XmlNode, relById: Map<String, XmlPull.Rel>, name: String, files: Map<String, ByteArray>): com.therealityteam.aeropages.model.SlideMedia? {
        val nvPr = pic.child("nvPicPr")?.child("nvPr") ?: return null
        val file = nvPr.child("videoFile") ?: nvPr.child("audioFile") ?: nvPr.child("quickTimeFile") ?: return null
        val kind = if (file.name == "audioFile") com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO else com.therealityteam.aeropages.model.SlideMedia.Kind.VIDEO
        fun part(target: String?) = target?.let { XmlPull.partName(files, name, it) }?.takeIf { it in files }
        val embedded = part(nvPr.descendants("media").firstOrNull()?.attr(XmlPull.R_NS, "embed")?.let { relById[it] }?.target)
        val link = file.attr(XmlPull.R_NS, "link")?.let { relById[it] }?.target
        val inFile = embedded ?: part(link)
        // Its name as the slide shows it (PowerPoint keeps the file it was inserted from: "clip.mp4"), else its part's.
        val fileName = pic.child("nvPicPr")?.child("cNvPr")?.attr("name")?.takeIf { com.therealityteam.aeropages.format.MediaTypes.isMedia(it) }
            ?: (inFile ?: link ?: return null).substringAfterLast('/').substringAfterLast('\\')
        val mime = com.therealityteam.aeropages.format.MediaTypes.mimeOf(fileName)
            ?: if (kind == com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO) "audio/mpeg" else "video/mp4"
        return if (inFile != null) com.therealityteam.aeropages.model.SlideMedia(kind, mime, files[inFile], null, fileName)
            else com.therealityteam.aeropages.model.SlideMedia(kind, mime, null, link, fileName)
    }

    /** A `p:grpSp`: its id for the shapes inside and the child→slide mapping of its transform. */
    private class GroupCtx(val id: Int, xfrm: XmlNode?, private val dims: Dims) {
        private val off = xfrm?.child("off"); private val ext = xfrm?.child("ext"); private val chOff = xfrm?.child("chOff"); private val chExt = xfrm?.child("chExt")
        private fun attr(n: XmlNode?, a: String) = n?.attr(a)?.toDoubleOrNull()
        /** Slide fractions of a child frame given in the group's child space; null when the group has no usable transform. */
        fun map(x: Float, y: Float, w: Float, h: Float): FloatArray? {
            val ox = attr(off, "x") ?: return null; val oy = attr(off, "y") ?: return null
            val ex = attr(ext, "cx") ?: return null; val ey = attr(ext, "cy") ?: return null
            val cx = attr(chOff, "x") ?: 0.0; val cy = attr(chOff, "y") ?: 0.0
            val cex = attr(chExt, "cx")?.takeIf { it > 0 } ?: ex; val cey = attr(chExt, "cy")?.takeIf { it > 0 } ?: ey
            val sx = ex / cex; val sy = ey / cey
            val nx = (ox + (x * dims.cx - cx) * sx) / dims.cx; val ny = (oy + (y * dims.cy - cy) * sy) / dims.cy
            // A member keeps its own size however small (an icon's dots), and a line its flatness.
            return floatArrayOf(nx.toFloat().coerceIn(-1f, 2f), ny.toFloat().coerceIn(-1f, 2f), (w * sx).toFloat().coerceIn(0f, 2f), (h * sy).toFloat().coerceIn(0f, 2f))
        }
    }

    /** Fractional slide position/size from the first `a:off` / `a:ext` (EMU over the deck's slide size); null parts when absent. */
    private class Frame(val x: Float?, val y: Float?, val w: Float?, val h: Float?) {
        fun toBoxFrame(): BoxFrame? =
            if (x != null && y != null && w != null && h != null && w > 0f && h > 0f) BoxFrame(x, y, w.coerceAtMost(1f), h.coerceAtMost(1f)) else null
    }

    private fun frameOf(node: XmlNode, dims: Dims): Frame {
        val off = node.descendants("off").firstOrNull()
        val ext = node.descendants("ext").firstOrNull { it.attr("cx") != null } // not extLst/ext
        val x = off?.attr("x")?.toDoubleOrNull()
        val y = off?.attr("y")?.toDoubleOrNull()
        val cx = ext?.attr("cx")?.toDoubleOrNull()
        val cy = ext?.attr("cy")?.toDoubleOrNull()
        val hasOff = x != null && y != null
        val hasExt = cx != null && cy != null
        return Frame(
            if (hasOff) (x!! / dims.cx).toFloat().coerceIn(0f, 1f) else null,
            if (hasOff) (y!! / dims.cy).toFloat().coerceIn(0f, 1f) else null,
            if (hasExt) (cx!! / dims.cx).toFloat() else null,
            if (hasExt) (cy!! / dims.cy).toFloat() else null,
        )
    }

    private fun parseShape(sp: XmlNode, ctx: SlideCtx): SlideShape? {
        val sh = DrawingShape.read(sp, ctx.palette) { txBody -> richBox(txBody, 18f, ctx) }
        sh.box.listStyle = listStyleOf(sp, null, emptyList(), null)
        val fr = frameOf(sp, ctx.dims)
        fr.x?.let { sh.xPct = it }; fr.y?.let { sh.yPct = it }
        // A line or a freeform may lie flat (PowerPoint's level lines have no height); a shape keeps a point or so.
        val min = if (sh.type == ShapeType.LINE || sh.type == ShapeType.FREEFORM) 0f else SlideShape.MIN_FRACTION
        fr.w?.let { sh.wPct = it.coerceIn(min, 1f) }; fr.h?.let { sh.hPct = it.coerceIn(min, 1f) }
        // Vertical text: the box turned (written back as that turn, which draws the same).
        when (sp.child("txBody")?.child("bodyPr")?.attr("vert")) {
            "vert", "eaVert", "mongolianVert", "wordArtVert" -> sh.turnText(90f, (ctx.dims.cx / ctx.dims.cy).toFloat())
            "vert270" -> sh.turnText(270f, (ctx.dims.cx / ctx.dims.cy).toFloat())
        }
        sh.action = clickOf(sp, ctx)
        sh.hoverAction = clickOf(sp, ctx, "hlinkHover")
        return sh
    }

    /**
     * A DrawingML text body read as a slide box's text — for Excel's shapes: runs, levels, breaks
     * and alignment, the first paragraph's bullet as the box's list, [defaultSize] for runs that set none.
     */
    internal fun textBoxOf(txBody: XmlNode?, defaultSize: Float, palette: Palette): SlideBox {
        val box = richBox(txBody, defaultSize, SlideCtx(emptyMap(), palette))
        val first = txBody?.children("p")?.firstOrNull { p -> p.descendants("t").any { it.text.isNotBlank() } }
        box.listStyle = bulletOf(first?.child("pPr")) ?: bulletOf(txBody?.child("lstStyle")?.child("lvl1pPr")) ?: SlideListStyle.NONE
        return box
    }

    /** A theme part's colours and fonts as a palette, with Office's default colour map (what an Excel workbook's shapes use). */
    internal fun paletteOf(theme: XmlNode?): Palette {
        theme ?: return Palette.DEFAULT
        val raw = Palette(emptyMap(), emptyMap(), null, null)
        val scheme = HashMap(Palette.DEFAULT_SCHEME)
        theme.descendants("clrScheme").firstOrNull()?.children?.forEach { n -> raw.colorOf(n)?.let { scheme[n.name] = it } }
        val fonts = theme.descendants("fontScheme").firstOrNull()
        val major = fonts?.child("majorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotEmpty() }
        val minor = fonts?.child("minorFont")?.child("latin")?.attr("typeface")?.takeIf { it.isNotEmpty() }
        return Palette(scheme, Palette.DEFAULT_MAP, major, minor, effectStyles(theme), backgroundFills(theme))
    }

    /** `a:bodyPr@anchor` of a shape's text body, else [default]. */
    private fun anchorOf(sp: XmlNode, default: ShapeAnchor): ShapeAnchor =
        when (sp.child("txBody")?.child("bodyPr")?.attr("anchor")) {
            "t" -> ShapeAnchor.TOP
            "ctr" -> ShapeAnchor.CENTER
            "b" -> ShapeAnchor.BOTTOM
            else -> default
        }

    private fun parseTable(frame: XmlNode, tbl: XmlNode, ctx: SlideCtx): SlideTable? {
        // Each cell as its txBody block (rich), preserving per-run formatting.
        val rows = tbl.children("tr").map { it.children("tc") }
        if (rows.isEmpty()) return null
        val nCols = rows.maxOf { it.size }.coerceAtLeast(1)
        val table = SlideTable.create(rows.size, nCols)
        rows.forEachIndexed { r, cells ->
            cells.forEachIndexed { c, tc ->
                if (c < nCols) {
                    val (runs, align) = parseRich(tc, 16f, ctx)
                    val box = table.cells[r][c]
                    box.paragraph.runs.clear()
                    box.paragraph.runs.addAll(runs)
                    box.paragraph.format.align = align
                    // A merged cell spans gridSpan columns and rowSpan rows; the places it covers (hMerge / vMerge) stay as cells.
                    box.colSpan = (tc.attr("gridSpan")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    box.rowSpan = (tc.attr("rowSpan")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    if (tc.attr("hMerge").let { it == "1" || it == "true" } || tc.attr("vMerge").let { it == "1" || it == "true" }) { box.colSpan = 1; box.rowSpan = 1 }
                    // a:tcPr anchor: top unless it says (ECMA-376's default), the middle (the app's own) as null.
                    box.cellAnchor = when (tc.child("tcPr")?.attr("anchor")) {
                        "ctr", "just", "dist" -> null
                        "b" -> com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM
                        else -> com.therealityteam.aeropages.model.ShapeAnchor.TOP
                    }
                }
            }
        }
        val tblPr = tbl.child("tblPr")
        fun on(name: String) = tblPr?.attr(name).let { it == "1" || it == "true" }
        table.headerRow = on("firstRow")
        table.bandedRows = on("bandRow")
        table.totalRow = on("lastRow")
        table.firstColumn = on("firstCol")
        table.lastColumn = on("lastCol")
        table.bandedColumns = on("bandCol")
        // Which of the app's table styles its fills are (a table it, or LibreOffice, wrote fills every cell so).
        val look = com.therealityteam.aeropages.model.SlideTableLook
        fun fillOf(r: Int, c: Int): Long? {
            val pr = rows.getOrNull(r)?.getOrNull(c)?.child("tcPr") ?: return null
            if (pr.child("noFill") != null) return 0L
            return pr.child("solidFill")?.child("srgbClr")?.attr("val")?.toLongOrNull(16)?.let { 0xFF000000 or it }
        }
        val first = fillOf(0, 0)
        (look.STYLES.indexOfFirst { it.header == first }.takeIf { it >= 0 } ?: look.STYLES.indexOfFirst { it.band == first || it.plain == first }.takeIf { it >= 0 })
            ?.let { table.styleIndex = it }
        // No options written (LibreOffice writes an empty a:tblPr): what the fills show.
        if (tblPr?.attr("firstRow") == null && first != null) {
            val st = look.STYLES[table.styleIndex]
            table.headerRow = first == st.header
            table.totalRow = rows.size > 1 && fillOf(rows.size - 1, 0) == st.header
            table.bandedRows = rows.size > 2 && fillOf(if (table.headerRow) 1 else 0, 0) == st.band
        }
        // The style most cells' fills are; a cell filled otherwise has its own shading (Table Design ▸ Shading).
        look.adopt(table, ::fillOf)
        // A cell whose lines are not the style's grid has its own borders (Table Design ▸ Borders); a side left out is the grid.
        look.adoptBorders(table) { r, c, side ->
            val name = when (side) { com.therealityteam.aeropages.model.SlideTableLook.Side.TOP -> "lnT"; com.therealityteam.aeropages.model.SlideTableLook.Side.BOTTOM -> "lnB"
                com.therealityteam.aeropages.model.SlideTableLook.Side.LEFT -> "lnL"; com.therealityteam.aeropages.model.SlideTableLook.Side.RIGHT -> "lnR" }
            val ln = rows.getOrNull(r)?.getOrNull(c)?.child("tcPr")?.child(name) ?: return@adoptBorders look.styleLine(table)
            tableLineOf(ln) ?: if (ln.child("noFill") != null) null else look.styleLine(table)
        }
        // Its columns' widths and rows' heights, when they are not all alike.
        weights(tbl.child("tblGrid")?.children("gridCol")?.map { it.attr("w")?.toFloatOrNull() ?: 0f }, nCols)?.let { table.colWeights.addAll(it) }
        weights(rows.indices.map { tbl.children("tr")[it].attr("h")?.toFloatOrNull() ?: 0f }, rows.size)?.let { table.rowWeights.addAll(it) }
        look.tidyCovered(table)
        val fr = frameOf(frame, ctx.dims)
        fr.x?.let { table.xPct = it }; fr.y?.let { table.yPct = it }
        fr.w?.let { table.wPct = it.coerceIn(0.1f, 1f) }; fr.h?.let { table.hPct = it.coerceIn(0.08f, 1f) }
        return table
    }

    /** [sizes] as a table's column or row weights: null unless there is one per column (row), all above 0, and they differ. */
    private fun weights(sizes: List<Float>?, n: Int): List<Float>? {
        if (sizes == null || sizes.size != n || n < 2 || sizes.any { it <= 0f }) return null
        val mean = sizes.average().toFloat()
        return sizes.takeIf { s -> s.any { kotlin.math.abs(it - mean) > mean * 0.005f } }
    }

    /**
     * A table cell's side (`a:lnL` …) as a line: its width (a double line's whole width over three), its colour and
     * dash; null when it has no colour of its own (none, or left to the style).
     */
    private fun tableLineOf(ln: XmlNode): com.therealityteam.aeropages.model.CellBorder? {
        val rgb = ln.child("solidFill")?.child("srgbClr")?.attr("val")?.toLongOrNull(16) ?: return null
        val ls = when (ln.child("prstDash")?.attr("val")) {
            "dot", "sysDot" -> com.therealityteam.aeropages.model.LineStyle.DOTTED
            "dash", "sysDash", "lgDash" -> com.therealityteam.aeropages.model.LineStyle.DASHED
            "dashDot", "sysDashDot", "lgDashDot" -> com.therealityteam.aeropages.model.LineStyle.DASH_DOT
            "lgDashDotDot", "sysDashDotDot" -> com.therealityteam.aeropages.model.LineStyle.DASH_DOT_DOT
            else -> if (ln.attr("cmpd") == "dbl") com.therealityteam.aeropages.model.LineStyle.DOUBLE else com.therealityteam.aeropages.model.LineStyle.THIN
        }
        val w = (ln.attr("w")?.toLongOrNull() ?: 12700L) / 12700f
        val each = if (ls == com.therealityteam.aeropages.model.LineStyle.DOUBLE) w / 3f else w
        return com.therealityteam.aeropages.model.CellBorder(ls, Math.round(each * 100f) / 100f, 0xFF000000 or rgb)
    }

    /** Resolve the slide's chart graphicFrame to its chart part and parse it. */
    private fun parseChart(frame: XmlNode, slideName: String, files: Map<String, ByteArray>, rels: List<XmlPull.Rel>, dims: Dims): SlideChart? {
        val rid = frame.descendants("chart").firstOrNull()?.attr(XmlPull.R_NS, "id") ?: return null
        val target = rels.firstOrNull { it.id == rid }?.target ?: return null
        val part = XmlPull.partName(files, slideName, target) ?: return null
        val chart = parseChartPart(files[part] ?: return null) ?: return null
        val fr = frameOf(frame, dims)
        fr.x?.let { chart.xPct = it }; fr.y?.let { chart.yPct = it }
        fr.w?.let { chart.wPct = it.coerceIn(0.1f, 1f) }; fr.h?.let { chart.hPct = it.coerceIn(0.08f, 1f) }
        return chart
    }

    /**
     * Parse a chart part (`c:chartSpace`) into a SlideChart: type (incl. barDir), title, the first
     * series' categories, every series' cached points, colorSet recovered from the first colour and
     * the legend flag. Null when the part is malformed or has no series. Shared with [XlsxReader].
     */
    internal fun parseChartPart(bytes: ByteArray, bindRanges: Boolean = false): SlideChart? {
        val root = XmlPull.parse(bytes) ?: return null
        val type = when {
            root.has("doughnutChart") -> ChartType.DOUGHNUT
            root.has("pieChart") || root.has("pie3DChart") || root.has("ofPieChart") -> ChartType.PIE
            root.has("lineChart") || root.has("line3DChart") -> ChartType.LINE
            root.has("areaChart") || root.has("area3DChart") -> ChartType.AREA
            root.has("radarChart") -> ChartType.RADAR
            root.has("scatterChart") || root.has("bubbleChart") -> ChartType.SCATTER
            root.has("barChart") || root.has("bar3DChart") ->
                if (root.descendants("barDir").any { it.attr("val") == "bar" }) ChartType.BAR else ChartType.COLUMN
            else -> ChartType.COLUMN
        }
        // Chart title: rich text runs, else the cached cell text of a referenced title.
        val title = root.child("chart")?.child("title")?.let { t -> t.textOf("t").ifEmpty { t.descendants("v").firstOrNull()?.text ?: "" } } ?: ""
        val sers = root.descendants("ser")
        if (sers.isEmpty()) return null
        val categories = ArrayList<String>()
        sers[0].child("cat")?.let { cat -> categories.addAll(cat.descendants("pt").mapNotNull { it.child("v")?.text?.trim() }) }
        val series = ArrayList<ChartSeries>()
        sers.forEachIndexed { si, ser ->
            val name = ser.child("tx")?.descendants("v")?.firstOrNull()?.text ?: com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.SERIES_N, si + 1)
            val values = (ser.child("val") ?: ser.child("yVal"))?.descendants("pt")
                ?.mapNotNull { it.child("v")?.text?.trim()?.toFloatOrNull() } ?: emptyList()
            series.add(ChartSeries(name, values.toMutableList()))
        }
        if (categories.isEmpty()) {
            val n = series.maxOfOrNull { it.values.size } ?: 0
            for (i in 1..n) categories.add(com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.CATEGORY_N, i))
        }
        // Recover the palette (colorSet) from the first series'/point's colour.
        val firstColor = sers[0].descendants("srgbClr").firstNotNullOfOrNull { c -> c.attr("val")?.takeIf { it.length == 6 } }?.uppercase()
        val colorSet = CHART_FIRST_COLORS.indexOf(firstColor).coerceAtLeast(0)
        // A worksheet-bound chart: when the references form one column block, remember it so Sheets keeps the chart live.
        val source = if (bindRanges) sourceOf(
            sers[0].child("cat")?.descendants("f")?.firstOrNull()?.text?.trim(),
            sers.map { s -> (s.child("val") ?: s.child("yVal"))?.descendants("f")?.firstOrNull()?.text?.trim() },
            sers.map { s -> s.child("tx")?.descendants("f")?.firstOrNull()?.text?.trim() },
        ) else null
        // Legend position (OOXML's default is the right), gridlines on the value axis, value data labels.
        val legend = root.child("chart")?.child("legend")
        val legendPos = when (legend?.child("legendPos")?.attr("val")) {
            "b" -> com.therealityteam.aeropages.model.LegendPos.BOTTOM
            "t" -> com.therealityteam.aeropages.model.LegendPos.TOP
            "l" -> com.therealityteam.aeropages.model.LegendPos.LEFT
            else -> com.therealityteam.aeropages.model.LegendPos.RIGHT
        }
        val plot = root.child("chart")?.child("plotArea")
        val catAx = plot?.child("catAx") ?: plot?.child("dateAx")
        val valAxes = plot?.children("valAx") ?: emptyList()
        // Scatter charts have two value axes: the one along the bottom is the x (category) axis.
        val xAx = catAx ?: valAxes.firstOrNull { it.child("axPos")?.attr("val") == "b" } ?: valAxes.getOrNull(0)
        val yAx = if (catAx != null) valAxes.firstOrNull() else valAxes.firstOrNull { it !== xAx }
        fun axisTitle(ax: XmlNode?): String = ax?.child("title")?.let { t -> t.textOf("t").ifEmpty { t.descendants("v").firstOrNull()?.text ?: "" } } ?: ""
        // A chart without axes (pie, doughnut) keeps the default, so it shows gridlines if its type changes later.
        val showGrid = (catAx == null && valAxes.isEmpty()) ||
            valAxes.any { it.child("majorGridlines") != null } || catAx?.child("majorGridlines") != null
        val showValues = root.descendants("dLbls").any { d -> d.child("showVal")?.attr("val").let { it == "1" || it == "true" } }
        // Format Axis on the value axis: fixed bounds (c:scaling), major unit, number format.
        val scaling = yAx?.child("scaling")
        fun num(node: XmlNode?) = node?.attr("val")?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val valFormat = yAx?.child("numFmt")?.attr("formatCode")?.takeIf { it.isNotBlank() } ?: SlideChart.GENERAL
        return SlideChart(type = type, title = title, categories = categories, series = series,
            colorSet = colorSet, showLegend = legend != null, showGrid = showGrid, showValues = showValues,
            sourceRef = source?.ref, sourceHeader = source?.header ?: true, sourceCats = source?.cats ?: true,
            legendPos = legendPos, catAxisTitle = axisTitle(xAx), valAxisTitle = axisTitle(yAx),
            valMin = num(scaling?.child("min")), valMax = num(scaling?.child("max")), valMajor = num(yAx?.child("majorUnit"))?.takeIf { it > 0 },
            valFormat = valFormat)
    }

    internal class ChartSource(val ref: String, val header: Boolean, val cats: Boolean)

    /** The block behind chart formulas when they describe one: a category column, value columns over the same rows, names in the row above. */
    /**
     * The worksheet block a chart's ranges describe (`Sheet!A1:B4` formulas: one column per
     * series over the same rows, an optional category column to their left and an optional
     * name cell above each series), or null when the ranges do not form one block.
     */
    internal fun sourceOf(cat: String?, vals: List<String?>, names: List<String?>): ChartSource? {
        if (vals.isEmpty() || vals.any { it.isNullOrBlank() }) return null
        val a1 = com.therealityteam.aeropages.model.A1
        fun sheetOf(f: String) = if (f.contains('!')) f.substringBeforeLast('!') else ""
        val sheetName = sheetOf(vals[0]!!)
        val cols = ArrayList<Int>()
        var rows: Pair<Int, Int>? = null
        for (f in vals) {
            if (sheetOf(f!!) != sheetName) return null
            val r = a1.parseRange(f) ?: return null
            if (r[1] != r[3]) return null // one column per series
            val span = r[0] to r[2]
            if (rows == null) rows = span else if (rows != span) return null
            cols.add(r[1])
        }
        val (top, bottom) = rows ?: return null
        var left = cols.min(); val right = cols.max()
        var hasCats = false
        if (!cat.isNullOrBlank()) {
            val r = a1.parseRange(cat) ?: return null
            if (sheetOf(cat) != sheetName || r[1] != r[3] || r[0] != top || r[2] != bottom || r[1] >= left) return null
            left = r[1]; hasCats = true
        }
        var header = false
        if (names.any { !it.isNullOrBlank() }) {
            for ((i, n) in names.withIndex()) {
                if (n.isNullOrBlank()) continue
                val r = a1.parseRange(n) ?: return null
                if (sheetOf(n) != sheetName || r[0] != top - 1 || r[2] != top - 1 || r[1] != cols[i]) return null
            }
            header = true
        }
        return ChartSource(a1.rangeRef(if (header) top - 1 else top, left, bottom, right), header, hasCats)
    }
}
