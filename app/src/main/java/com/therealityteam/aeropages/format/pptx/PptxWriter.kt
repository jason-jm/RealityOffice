package com.therealityteam.aeropages.format.pptx

import com.therealityteam.aeropages.model.LineStyle
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.under
import com.therealityteam.aeropages.model.Slide
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal PPTX writer. Emits a valid PresentationML package with the parts PowerPoint
 * needs to open without repair: content-types, presentation + rels, one slide master,
 * one slide layout, a theme, and one slide per [Slide] (title + body placeholders).
 * Per-run formatting is not persisted (best-effort, edit-and-resave model).
 */
object PptxWriter {

    /** The deck's slide size in EMU (12700 a point): what every fraction of the slide is taken of. */
    private class Emu(size: com.therealityteam.aeropages.model.SlideSize) {
        val cx: Int = Math.round(size.widthPt * 12700.0).toInt()
        val cy: Int = Math.round(size.heightPt * 12700.0).toInt()
        /** PowerPoint's name for the size (`p:sldSz@type`, either way round); widescreen, like any custom size, has none. */
        val type: String? = com.therealityteam.aeropages.model.SlideSizePreset.of(size)?.pptxType
    }

    fun write(p: Presentation, out: OutputStream) {
        val zip = ZipOutputStream(out)
        val n = p.slides.size
        // Global chart numbering: each chart becomes ppt/charts/chartK.xml.
        val chartIndex = HashMap<com.therealityteam.aeropages.model.SlideChart, Int>()
        var chartSeq = 0
        p.slides.forEach { s -> s.charts.forEach { chartIndex[it] = ++chartSeq } }

        val notesIdx = p.slides.mapIndexedNotNull { i, s -> if (s.notes.isNotBlank()) i + 1 else null }
        // Comments as PowerPoint keeps them: their authors in commentAuthors.xml, each slide's in ppt/comments/commentN.xml.
        val comments = SlideComments(p)
        val hasComments = comments.any
        // The designs the slides are drawn on — each one drawing something is a master of its own
        // after the deck's plain one, as PowerPoint keeps a design; a colour alone is the slides'.
        val designs = p.slides.mapNotNull { s -> p.designOf(s)?.takeIf { it.drawsSomething } }.distinct()
        val masters = 1 + designs.size
        val deckMedia = DeckMedia(p)
        put(zip, "[Content_Types].xml", com.therealityteam.aeropages.format.OoxmlCore.types(contentTypes(n, chartSeq, notesIdx, masters, deckMedia.types, comments.slides)))
        put(zip, "_rels/.rels", com.therealityteam.aeropages.format.OoxmlCore.rels(buildString {
            append(RELS_HEAD)
            append(relLine("rId1", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument", "ppt/presentation.xml"))
            append(RELS_TAIL)
        }))
        putBytes(zip, com.therealityteam.aeropages.format.OoxmlCore.PART, com.therealityteam.aeropages.format.OoxmlCore.write(p.props))
        put(zip, "ppt/presentation.xml", presentationXml(n, p, masters))
        put(zip, "ppt/_rels/presentation.xml.rels", presentationRels(n, masters, hasComments))
        put(zip, "ppt/presProps.xml", presPropsXml(p))
        val d = Emu(p.slideSize)
        val designMedia = DesignMedia()
        for (m in 0 until masters) writeMaster(zip, m, designs.getOrNull(m - 1), p, d, designMedia)

        p.slides.forEachIndexed { i, slide ->
            // Allocate relationship ids: rId1=layout, then one per image, then one per chart.
            var relSeq = 2
            // Each picture's part (an SVG's PNG stand-in, with the SVG itself beside it).
            val imageRels = slide.images.mapIndexed { k, img ->
                val pic = OoxmlPicture.of(img.data, img.mime)
                SlidePicture(pic, "rId${relSeq++}", "image${i + 1}_${k + 1}.${pic.mainExt}", pic.svg?.let { "rId${relSeq++}" }, "image${i + 1}_${k + 1}.svg")
            }
            // A picture's video or sound: the copy in the file (p14:media) and the link a:videoFile / a:audioFile names.
            val mediaRels = slide.images.map { img ->
                img.media?.let { m -> SlideMediaRels(m, deckMedia.partOf(m)?.let { "rId${relSeq++}" }, "rId${relSeq++}", deckMedia.partOf(m)) }
            }
            val chartRels = slide.charts.map { it to "rId${relSeq++}" }
            // One relationship per distinct link of the slide's runs, shapes and pictures: an address, or a slide.
            val linkRels = LinkedHashMap<String, String>()
            slideLinks(p, slide).forEach { url -> linkRels[url] = "rId${relSeq++}" }
            val notesRelId = if (slide.notes.isNotBlank()) "rId${relSeq++}" else null
            val commentsRelId = if (slide.comments.isNotEmpty()) "rId${relSeq++}" else null
            put(zip, "ppt/slides/slide${i + 1}.xml", slideXml(p, i, slide, imageRels.map { OoxmlPicture.blip(it.rid, it.svgRid) }, chartRels, linkRels, mediaRels))
            val relsBody = StringBuilder(RELS_HEAD)
            // Its layout among its design's master's (the plain master's first).
            val master = designs.indexOf(p.designOf(slide)) + 1
            relsBody.append(relLine("rId1", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout", "../slideLayouts/slideLayout${master * LAYOUTS.size + layoutNumber(slide)}.xml"))
            imageRels.forEach { r ->
                relsBody.append(relLine(r.rid, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image", "../media/${r.media}"))
                r.svgRid?.let { relsBody.append(relLine(it, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image", "../media/${r.svgMedia}")) }
            }
            mediaRels.filterNotNull().forEach { r ->
                val type = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/" + (if (r.media.kind == com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO) "audio" else "video")
                if (r.part != null) {
                    r.embedRid?.let { relsBody.append(relLine(it, "http://schemas.microsoft.com/office/2007/relationships/media", "../media/${r.part}")) }
                    relsBody.append(relLine(r.linkRid, type, "../media/${r.part}"))
                } else relsBody.append("""<Relationship Id="${r.linkRid}" Type="$type" Target="${esc(r.media.link ?: "")}" TargetMode="External"/>""")
            }
            chartRels.forEach { (chart, rid) ->
                val k = chartIndex[chart]!!
                relsBody.append(relLine(rid, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart", "../charts/chart$k.xml"))
            }
            linkRels.forEach { (url, rid) ->
                val target = com.therealityteam.aeropages.model.SlideLinks.indexIn(p, url)
                if (target != null) relsBody.append(relLine(rid, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide", "slide${target + 1}.xml"))
                else relsBody.append("""<Relationship Id="$rid" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${esc(url)}" TargetMode="External"/>""")
            }
            notesRelId?.let {
                relsBody.append(relLine(it, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/notesSlide", "../notesSlides/notesSlide${i + 1}.xml"))
            }
            commentsRelId?.let {
                relsBody.append(relLine(it, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments", "../comments/comment${i + 1}.xml"))
                put(zip, "ppt/comments/comment${i + 1}.xml", comments.slideXml(slide, p.slideSize))
            }
            relsBody.append(RELS_TAIL)
            put(zip, "ppt/slides/_rels/slide${i + 1}.xml.rels", relsBody.toString())
            if (notesRelId != null) {
                put(zip, "ppt/notesSlides/notesSlide${i + 1}.xml", notesSlideXml(slide.notes))
                put(zip, "ppt/notesSlides/_rels/notesSlide${i + 1}.xml.rels",
                    rel("rId1", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide", "../slides/slide${i + 1}.xml"))
            }
            imageRels.forEach { r ->
                putBytes(zip, "ppt/media/${r.media}", r.pic.main)
                r.pic.svg?.let { putBytes(zip, "ppt/media/${r.svgMedia}", it) }
            }
        }
        // The comments' authors, with the last index each used (known once the slides' comments are written).
        if (hasComments) put(zip, "ppt/commentAuthors.xml", comments.authorsXml())
        // Media parts: each distinct video or sound once, whichever slides show it.
        deckMedia.write(zip)
        // Chart parts.
        chartIndex.forEach { (chart, k) -> put(zip, "ppt/charts/chart$k.xml", chartXml(chart)) }
        zip.finish()
    }

    /**
     * A deck's comments as PowerPoint's classic comments: each author once in commentAuthors.xml (an id, initials, the last
     * index used), each comment `p:cm` with its author, date (UTC), index (counting up per author), position (`p:pos` in
     * eighths of a point) and text; a reply is a comment after its parent with PowerPoint 2013's `p15:threadingInfo`.
     */
    private class SlideComments(p: Presentation) {
        private val authors = LinkedHashMap<String, Int>()
        private val lastIdx = HashMap<Int, Int>()
        val any = p.slides.any { it.comments.isNotEmpty() }
        val slides = p.slides.mapIndexedNotNull { i, s -> if (s.comments.isNotEmpty()) i + 1 else null }

        init { p.slides.forEach { s -> s.comments.forEach { c -> authorId(c.author); c.replies.forEach { authorId(it.author) } } } }

        private fun authorId(name: String) = authors.getOrPut(name.ifBlank { "?" }) { authors.size }
        private fun nextIdx(author: Int) = (lastIdx[author] ?: 0).plus(1).also { lastIdx[author] = it }

        fun slideXml(slide: com.therealityteam.aeropages.model.Slide, size: com.therealityteam.aeropages.model.SlideSize): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<p:cmLst xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
            for (c in slide.comments) {
                val a = authorId(c.author); val idx = nextIdx(a)
                cm(c, a, idx, size, null)
                for (r in c.replies) { val ra = authorId(r.author); cm(r, ra, nextIdx(ra), size, a to idx) }
            }
            append("</p:cmLst>")
        }

        private fun StringBuilder.cm(c: com.therealityteam.aeropages.model.SlideComment, author: Int, idx: Int, size: com.therealityteam.aeropages.model.SlideSize, parent: Pair<Int, Int>?) {
            val dt = java.time.Instant.ofEpochMilli(c.dateMs).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.ROOT))
            append("""<p:cm authorId="$author" dt="$dt" idx="$idx">""")
            append("""<p:pos x="${Math.round(c.x * size.widthPt * 8)}" y="${Math.round(c.y * size.heightPt * 8)}"/>""")
            append("<p:text>").append(esc(c.text)).append("</p:text>")
            parent?.let { (pa, pi) ->
                append("""<p:extLst><p:ext uri="{C676402C-5697-4E1C-873F-D02D1690AC5C}"><p15:threadingInfo xmlns:p15="http://schemas.microsoft.com/office/powerpoint/2012/main" timeZoneBias="0"><p15:parentCm authorId="$pa" idx="$pi"/></p15:threadingInfo></p:ext></p:extLst>""")
            }
            append("</p:cm>")
        }

        fun authorsXml(): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<p:cmAuthorLst xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
            authors.forEach { (name, id) ->
                val initials = name.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)) }.take(4)
                append("""<p:cmAuthor id="$id" name="${esc(name)}" initials="${esc(initials)}" lastIdx="${lastIdx[id] ?: 0}" clrIdx="${id % 8}"/>""")
            }
            append("</p:cmAuthorLst>")
        }
    }

    /** A picture's `a:xfrm` turn (60000ths of a degree clockwise, 0 … 360°) and mirroring. */
    private fun turnAttrs(img: com.therealityteam.aeropages.model.SlideImage): String {
        val rot = Math.round(((img.rotationDeg % 360f) + 360f) % 360f * 60000f) % 21600000
        return (if (rot != 0) """ rot="$rot"""" else "") + (if (img.flipH) """ flipH="1"""" else "") + (if (img.flipV) """ flipV="1"""" else "")
    }

    /** A picture's video or sound on a slide: its part in the file ([part], null for a link), the p14:media relationship ([embedRid]) and the a:videoFile / a:audioFile one ([linkRid]). */
    private class SlideMediaRels(val media: com.therealityteam.aeropages.model.SlideMedia, val embedRid: String?, val linkRid: String, val part: String?)

    /**
     * The deck's videos and sounds held in the file: one part each ("media1.mp4" …), however many slides
     * show it, named before anything is written so the content types can list their extensions.
     */
    private class DeckMedia(p: Presentation) {
        private val parts = java.util.IdentityHashMap<ByteArray, String>()
        val types = LinkedHashMap<String, String>()
        init {
            for (slide in p.slides) for (img in slide.images) {
                val m = img.media ?: continue
                val data = m.data ?: continue
                if (data in parts) continue
                val ext = m.name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && com.therealityteam.aeropages.format.MediaTypes.isMedia(m.name) }
                    ?: com.therealityteam.aeropages.format.MediaTypes.extOf(m.mime)
                parts[data] = "media${parts.size + 1}.$ext"
                types.putIfAbsent(ext, m.mime)
            }
        }
        fun partOf(m: com.therealityteam.aeropages.model.SlideMedia): String? = m.data?.let { parts[it] }
        fun write(zip: ZipOutputStream) = parts.forEach { (data, name) -> putBytes(zip, "ppt/media/$name", data) }
    }

    /** A slide picture's parts: what its blip embeds ([rid], [media]) and, for an SVG, the SVG itself ([svgRid], [svgMedia]). */
    private class SlidePicture(val pic: OoxmlPicture, val rid: String, val media: String, val svgRid: String?, val svgMedia: String)

    /**
     * The media parts the designs' pictures are saved in: one per picture however many designs
     * draw it (a template's layouts share their pictures), an SVG's stand-in drawn once.
     */
    private class DesignMedia {
        private val parts = java.util.IdentityHashMap<ByteArray, Pair<String, String?>>()

        /** [img]'s part and, for an SVG, its original's — written to [zip] the first time. */
        fun of(zip: ZipOutputStream, img: com.therealityteam.aeropages.model.SlideImage): Pair<String, String?> = parts.getOrPut(img.data) {
            val pic = OoxmlPicture.of(img.data, img.mime)
            val k = parts.size + 1
            val main = "design$k.${pic.mainExt}"
            putBytes(zip, "ppt/media/$main", pic.main)
            val svg = pic.svg?.let { s -> "design${k}_svg.svg".also { putBytes(zip, "ppt/media/$it", s) } }
            main to svg
        }
    }

    /** The id of master [m] (0-based); its layouts' ids follow it. Masters and layouts share one id space. */
    private fun masterId(m: Int): Long = 2147483648L + m * (LAYOUTS.size + 1L)

    /**
     * Master [m] (0-based) — the deck's plain master, or one drawing [design] — with its layouts
     * (numbered after the masters before it), its theme and its design's pictures.
     */
    private fun writeMaster(zip: ZipOutputStream, m: Int, design: com.therealityteam.aeropages.model.SlideDesign?, p: Presentation, d: Emu, media: DesignMedia) {
        val k = m + 1
        val img = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
        val rels = StringBuilder(RELS_HEAD)
        LAYOUTS.indices.forEach { j ->
            rels.append(relLine("rId${j + 1}", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout", "../slideLayouts/slideLayout${m * LAYOUTS.size + j + 1}.xml"))
        }
        rels.append(relLine("rId${LAYOUTS.size + 1}", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme", "../theme/theme$k.xml"))
        // The design's pictures — its background first, then those it draws — as blips of the master's.
        var relSeq = LAYOUTS.size + 2
        val pictures = buildList {
            design?.picture?.let { add(it) }
            design?.items?.forEach { if (it is com.therealityteam.aeropages.model.DesignPicture) add(it.image) }
        }
        val blips = pictures.map { pic ->
            val (main, svg) = media.of(zip, pic)
            val rid = "rId${relSeq++}"
            rels.append(relLine(rid, img, "../media/$main"))
            val svgRid = svg?.let { s -> "rId${relSeq++}".also { rels.append(relLine(it, img, "../media/$s")) } }
            OoxmlPicture.blip(rid, svgRid)
        }
        rels.append(RELS_TAIL)
        // A design's master: the deck's text styles with the design's own laid over them.
        val look = if (design == null) p.master else p.master.copyOf().apply {
            design.titleText?.let { t -> titleFont = t.font ?: titleFont; titleSizePt = t.sizePt ?: titleSizePt; titleColor = t.color ?: titleColor; titleBold = t.bold ?: titleBold }
            design.bodyText?.let { t -> bodyFont = t.font ?: bodyFont; bodySizePt = t.sizePt ?: bodySizePt; bodyColor = t.color ?: bodyColor }
        }
        put(zip, "ppt/slideMasters/slideMaster$k.xml", slideMasterXml(look, d, m, design, blips))
        put(zip, "ppt/slideMasters/_rels/slideMaster$k.xml.rels", rels.toString())
        LAYOUTS.forEachIndexed { j, layout ->
            val number = m * LAYOUTS.size + j + 1
            put(zip, "ppt/slideLayouts/slideLayout$number.xml", layoutXml(layout, d, p.master))
            put(zip, "ppt/slideLayouts/_rels/slideLayout$number.xml.rels",
                rel("rId1", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster", "../slideMasters/slideMaster$k.xml"))
        }
        // Each master its own theme, named as its design (PowerPoint names a master by its theme).
        put(zip, "ppt/theme/theme$k.xml", if (design == null) THEME else THEME.replaceFirst("""name="Office Theme"""", """name="${esc(design.name)}""""))
    }

    /** Speaker notes part: a body placeholder with one a:p per notes line. */
    private fun notesSlideXml(notes: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<p:notes xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
        append("""<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""")
        append("""<p:sp><p:nvSpPr><p:cNvPr id="2" name="Notes Placeholder"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph type="body" idx="1"/></p:nvPr></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/>""")
        notes.split('\n').forEach { line ->
            append("""<a:p><a:r><a:t>${esc(line)}</a:t></a:r></a:p>""")
        }
        append("""</p:txBody></p:sp></p:spTree></p:cSld></p:notes>""")
    }

    internal fun encodeField(s: String): String = s.replace("%", "%25").replace("|", "%7C").replace(";", "%3B")
    internal fun decodeField(s: String): String = s.replace("%3B", ";").replace("%7C", "|").replace("%25", "%")

    private fun contentTypes(n: Int, chartCount: Int, notesIdx: List<Int> = emptyList(), masters: Int = 1,
                             media: Map<String, String> = emptyMap(), commentSlides: List<Int> = emptyList()): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        append(OoxmlPicture.CONTENT_TYPES)
        // The videos' and sounds' types, by extension.
        media.forEach { (ext, mime) -> append("""<Default Extension="$ext" ContentType="$mime"/>""") }
        append("""<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>""")
        for (k in 1..masters) append("""<Override PartName="/ppt/slideMasters/slideMaster$k.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>""")
        for (k in 1..masters * LAYOUTS.size) append("""<Override PartName="/ppt/slideLayouts/slideLayout$k.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>""")
        for (k in 1..masters) append("""<Override PartName="/ppt/theme/theme$k.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>""")
        for (i in 1..n) append("""<Override PartName="/ppt/slides/slide$i.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>""")
        for (k in 1..chartCount) append("""<Override PartName="/ppt/charts/chart$k.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>""")
        notesIdx.forEach { append("""<Override PartName="/ppt/notesSlides/notesSlide$it.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.notesSlide+xml"/>""") }
        if (commentSlides.isNotEmpty()) append("""<Override PartName="/ppt/commentAuthors.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.commentAuthors+xml"/>""")
        commentSlides.forEach { append("""<Override PartName="/ppt/comments/comment$it.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.comments+xml"/>""") }
        append("""<Override PartName="/ppt/presProps.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presProps+xml"/>""")
        append("</Types>")
    }

    /** presentation.xml: the [masters] (relationships rId1…), then the slides (after the theme's relationship). */
    private fun presentationXml(n: Int, p: Presentation, masters: Int = 1): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        // Number slides from: the number the first slide shows (1 unless set).
        val first = p.firstSlideNumber.takeIf { it != 1 }?.let { """ firstSlideNum="$it"""" } ?: ""
        append("""<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"$first>""")
        append("<p:sldMasterIdLst>")
        for (m in 0 until masters) append("""<p:sldMasterId id="${masterId(m)}" r:id="rId${m + 1}"/>""")
        append("</p:sldMasterIdLst>")
        append("""<p:sldIdLst>""")
        for (i in 1..n) append("""<p:sldId id="${255 + i}" r:id="rId${masters + 1 + i}"/>""")
        append("""</p:sldIdLst>""")
        val d = Emu(p.slideSize)
        append("""<p:sldSz cx="${d.cx}" cy="${d.cy}"${d.type?.let { """ type="$it"""" } ?: ""}/><p:notesSz cx="6858000" cy="9144000"/>""")
        // Custom shows: each names its slides by their relationships (a slide no longer in the deck is left out).
        if (p.customShows.isNotEmpty()) {
            append("<p:custShowLst>")
            p.customShows.forEachIndexed { k, cs ->
                append("""<p:custShow name="${esc(cs.name)}" id="$k"><p:sldLst>""")
                cs.slides.forEach { id -> p.slides.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { append("""<p:sld r:id="rId${masters + 2 + it}"/>""") } }
                append("</p:sldLst></p:custShow>")
            }
            append("</p:custShowLst>")
        }
        // Text anywhere the slides leave its font unset — shapes, text boxes, tables — in the theme's fonts.
        append("""<p:defaultTextStyle><a:lvl1pPr><a:defRPr><a:latin typeface="+mn-lt"/><a:ea typeface="+mn-ea"/><a:cs typeface="+mn-cs"/></a:defRPr></a:lvl1pPr></p:defaultTextStyle>""")
        appendSections(p)
        append("</p:presentation>")
    }

    /**
     * Sections (节) are PowerPoint 2010's `p14:sectionLst` extension: every slide must belong to a
     * section, so slides before the first named one go into a default section.
     */
    private fun StringBuilder.appendSections(p: Presentation) {
        if (p.slides.none { it.sectionName != null }) return
        val groups = ArrayList<Pair<String, MutableList<Int>>>()
        p.slides.forEachIndexed { i, s ->
            val name = s.sectionName
            if (name != null || groups.isEmpty()) groups.add((name ?: "Default Section") to mutableListOf())
            groups.last().second.add(256 + i)
        }
        append("""<p:extLst><p:ext uri="{521415D9-36F7-43E2-AB2F-B90AF26B5E84}"><p14:sectionLst xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main">""")
        groups.forEach { (name, ids) ->
            append("""<p14:section name="${esc(name)}" id="{${java.util.UUID.randomUUID().toString().uppercase()}}"><p14:sldIdLst>""")
            ids.forEach { append("""<p14:sldId id="$it"/>""") }
            append("</p14:sldIdLst></p14:section>")
        }
        append("</p14:sectionLst></p:ext></p:extLst>")
    }

    private fun presentationRels(n: Int, masters: Int = 1, commentAuthors: Boolean = false): String = buildString {
        append(RELS_HEAD)
        for (m in 1..masters) append(relLine("rId$m", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster", "slideMasters/slideMaster$m.xml"))
        append(relLine("rId${masters + 1}", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme", "theme/theme1.xml"))
        for (i in 1..n) append(relLine("rId${masters + 1 + i}", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide", "slides/slide$i.xml"))
        append(relLine("rId${masters + 2 + n}", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps", "presProps.xml"))
        if (commentAuthors) append(relLine("rId${masters + 3 + n}", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/commentAuthors", "commentAuthors.xml"))
        append(RELS_TAIL)
    }

    /**
     * presProps.xml: Set Up Slide Show (`p:showPr` — loop, narration, animations, timings; presented, browsed or at a kiosk;
     * all the slides, a range of them or a custom show by its position in `p:custShowLst`).
     */
    private fun presPropsXml(p: Presentation): String = buildString {
        fun b(v: Boolean) = if (v) "1" else "0"
        val s = p.show
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<p:presentationPr xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
        append("""<p:showPr loop="${b(p.loopShow || s.type == com.therealityteam.aeropages.model.ShowType.KIOSK)}" showNarration="${b(p.showNarration)}" showAnimation="${b(s.animations)}" useTimings="${b(p.useTimings)}">""")
        append(when (s.type) {
            com.therealityteam.aeropages.model.ShowType.SPEAKER -> "<p:present/>"
            com.therealityteam.aeropages.model.ShowType.WINDOW -> "<p:browse/>"
            com.therealityteam.aeropages.model.ShowType.KIOSK -> "<p:kiosk/>"
        })
        val custom = s.custom?.let { name -> p.customShows.indexOfFirst { it.name == name } }?.takeIf { it >= 0 }
        when {
            custom != null -> append("""<p:custShow id="$custom"/>""")
            s.from > 0 -> append("""<p:sldRg st="${s.from}" end="${if (s.to > 0) maxOf(s.to, s.from) else p.slides.size}"/>""")
            else -> append("<p:sldAll/>")
        }
        append("</p:showPr></p:presentationPr>")
    }

    /** Distinct hyperlink URLs referenced by any run on the slide (title/body/shapes/tables), in first-seen order. */
    /**
     * The links of [slide] that need a relationship — addresses, and slides of [p] (a link to one no
     * longer there is left out) — from its runs and its shapes' and pictures' clicks; the show's own
     * jumps (next, previous …) need none.
     */
    private fun slideLinks(p: Presentation, slide: Slide): List<String> {
        val urls = LinkedHashSet<String>()
        fun add(link: String?) {
            if (link.isNullOrBlank() || link in com.therealityteam.aeropages.model.SlideLinks.JUMPS) return
            if (com.therealityteam.aeropages.model.SlideLinks.slideId(link) != null && com.therealityteam.aeropages.model.SlideLinks.indexIn(p, link) == null) return
            urls.add(link)
        }
        fun scan(box: com.therealityteam.aeropages.model.SlideBox) = box.paragraph.runs.forEach { r -> add(r.format.link) }
        scan(slide.title); scan(slide.body)
        slide.shapes.forEach { scan(it.box); add(it.action); add(it.hoverAction) }
        slide.tables.forEach { t -> t.cells.forEach { row -> row.forEach { scan(it) } } }
        slide.images.forEach { add(it.action); add(it.hoverAction) }
        return urls.toList()
    }

    /**
     * The `a:hlinkClick` for [link]: an address or a slide through its relationship in [linkRels]
     * (`ppaction://hlinksldjump` for a slide), one of the show's jumps by its action
     * (`ppaction://hlinkshowjump?jump=…`); "" for none.
     */
    private fun hlinkClick(link: String?, linkRels: Map<String, String>, element: String = "hlinkClick"): String {
        link ?: return ""
        val ns = """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
        val jump = when (link) {
            com.therealityteam.aeropages.model.SlideLinks.NEXT -> "nextslide"
            com.therealityteam.aeropages.model.SlideLinks.PREVIOUS -> "previousslide"
            com.therealityteam.aeropages.model.SlideLinks.FIRST -> "firstslide"
            com.therealityteam.aeropages.model.SlideLinks.LAST -> "lastslide"
            com.therealityteam.aeropages.model.SlideLinks.END -> "endshow"
            else -> null
        }
        if (jump != null) return """<a:$element $ns r:id="" action="ppaction://hlinkshowjump?jump=$jump"/>"""
        val rid = linkRels[link] ?: return ""
        return if (com.therealityteam.aeropages.model.SlideLinks.slideId(link) != null) """<a:$element $ns r:id="$rid" action="ppaction://hlinksldjump"/>"""
        else """<a:$element $ns r:id="$rid"/>"""
    }

    /**
     * A `p:cNvPr` with [id] and a name — [obj]'s own (the Selection Pane's), else [name] — `hidden` when [obj] is, holding
     * the element's click ([action]) when it has one.
     */
    private fun cNvPr(id: Int, name: String, action: String?, linkRels: Map<String, String>, alt: String = "", decorative: Boolean = false,
                      obj: com.therealityteam.aeropages.model.SlideObject? = null, hover: String? = null): String {
        // Its click, its mouse-over, then its extension list (the decorative mark): CT_NonVisualDrawingProps' order.
        val inner = hlinkClick(action, linkRels) + hlinkClick(hover, linkRels, "hlinkHover") + OoxmlAltText.decorativeExt(decorative)
        val descr = OoxmlAltText.descr(alt)
        val head = """<p:cNvPr id="$id"${nameAttrs(name, obj)}$descr"""
        return if (inner.isEmpty()) "$head/>" else "$head>$inner</p:cNvPr>"
    }

    /** ` name="…"` ([obj]'s own name, else [fallback]) and ` hidden="1"` for a hidden object. */
    private fun nameAttrs(fallback: String, obj: com.therealityteam.aeropages.model.SlideObject?): String =
        """ name="${esc(obj?.objectName ?: fallback)}"""" + (if (obj?.hidden == true) """ hidden="1"""" else "")

    private fun slideXml(p: Presentation, index: Int, slide: Slide, imageBlips: List<String>, chartRels: List<Pair<com.therealityteam.aeropages.model.SlideChart, String>>, linkRels: Map<String, String>,
                         mediaRels: List<SlideMediaRels?> = emptyList()): String = buildString {
        val d = Emu(p.slideSize)
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        // Hidden in the show; its design's graphics hidden (Format Background → Hide background graphics).
        val showAttr = (if (slide.hidden) """ show="0"""" else "") + (if (!slide.designObjects) """ showMasterSp="0"""" else "")
        append("""<p:sld$showAttr xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld>""")
        // Its own background, else a design's that is only a colour (one that draws is a master's).
        val colourDesign = p.designOf(slide)?.takeIf { !it.drawsSomething }
        if (slide.background != null) append(bgXml(slide.background!!, slide.backgroundShade))
        else colourDesign?.background?.let { append(bgXml(it, colourDesign.backgroundShade)) }
        append("""<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""")
        // Every element's effects with the id it is written under; the timing orders them.
        val anims = ArrayList<AnimSpec>()
        fun animated(id: Int, target: com.therealityteam.aeropages.model.AnimTarget, kind: BuildKind) =
            target.anims.forEach { anims.add(AnimSpec(id, target, it, kind)) }
        // Placeholder geometry: the box's own frame, else where the flow layout shows it. A blank
        // slide leaves out what it has nothing in, as PowerPoint's Title Only and Blank do — so it
        // comes back blank, not with an empty body asking for text.
        val blank = slide.layout == com.therealityteam.aeropages.model.SlideLayout.BLANK
        // The placeholders are typed as the slide's layout types them (ctrTitle / subTitle on a title slide …).
        val layout = LAYOUTS[layoutNumber(slide) - 1]
        val tf = slide.title.frame ?: com.therealityteam.aeropages.model.BoxFrame.defaultFor(slide.layout, true)
        val bf = slide.body.frame ?: com.therealityteam.aeropages.model.BoxFrame.defaultFor(slide.layout, false)
        if (!blank || slide.title.text.isNotBlank()) {
            append(withMath(slide.title) { m -> shape(2, "Title", layout.titlePh ?: """type="title"""", "ctr", (tf.xPct * d.cx).toInt(), (tf.yPct * d.cy).toInt(), (tf.wPct * d.cx).toInt(), (tf.hPct * d.cy).toInt(), slide.title, linkRels, m,
                flipped(p.master.titleText().under(p.designOf(slide)?.titleText).color, p.titleTextOn(slide).color)) })
            animated(2, slide.title, BuildKind.TEXT)
        }
        // A content placeholder a table, chart or picture has taken is left out too: that object is written as the
        // placeholder (PowerPoint's p:ph idx="1" on it), so nothing asks for text over it.
        val taker = slide.contentTaker
        if ((!blank || slide.body.text.isNotBlank()) && taker == null) {
            append(withMath(slide.body) { m -> shape(3, "Content", layout.bodyPh ?: """type="body" idx="1"""", "t", (bf.xPct * d.cx).toInt(), (bf.yPct * d.cy).toInt(), (bf.wPct * d.cx).toInt(), (bf.hPct * d.cy).toInt(), slide.body, linkRels, m,
                flipped(p.master.bodyText().under(p.designOf(slide)?.bodyText).color, p.bodyTextOn(slide).color)) })
            animated(3, slide.body, BuildKind.TEXT)
        }
        // One id counter for every element on the slide (2/3 are the title/body placeholders);
        // duplicate ids make PowerPoint offer a "repair" on open.
        var nextId = 4
        imageBlips.forEachIndexed { k, blip ->
            val img = slide.images[k]
            val id = nextId++
            val x = (img.xPct * d.cx).toInt(); val y = (img.yPct * d.cy).toInt()
            val cx = (img.wPct * d.cx).toInt().coerceAtLeast(1); val cy = (img.hPct * d.cy).toInt().coerceAtLeast(1)
            val m = mediaRels.getOrNull(k)
            if (m == null) append("""<p:pic><p:nvPicPr>${cNvPr(id, "Picture ${k + 1}", img.action, linkRels, img.altText, img.decorative, obj = img, hover = img.hoverAction)}<p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr>${nvPr(img === taker)}</p:nvPicPr>""")
            else {
                // A video or sound: a click plays it (ppaction://media); a:videoFile / a:audioFile links it, p14:media holds the file's copy.
                val tag = if (m.media.kind == com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO) "audioFile" else "videoFile"
                val embed = m.embedRid?.let { """<p:extLst><p:ext uri="{DAA4B4D4-6D71-4841-9C94-3DA1D04AE1C2}"><p14:media xmlns:p14="http://schemas.microsoft.com/office/powerpoint/2010/main" r:embed="$it"/></p:ext></p:extLst>""" } ?: ""
                append("""<p:pic><p:nvPicPr><p:cNvPr id="$id" name="${esc(m.media.name)}"${if (img.hidden) """ hidden="1"""" else ""}><a:hlinkClick r:id="" action="ppaction://media"/></p:cNvPr><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr>""")
                append("""<p:nvPr>${if (img === taker) CONTENT_PH else ""}<a:$tag r:link="${m.linkRid}"/>$embed</p:nvPr></p:nvPicPr>""")
            }
            append("""<p:blipFill>$blip${com.therealityteam.aeropages.format.PictureCrop.srcRect(img)}<a:stretch><a:fillRect/></a:stretch></p:blipFill>""")
            append("""<p:spPr><a:xfrm${turnAttrs(img)}><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>${effectXml(img.shadow)}</p:spPr></p:pic>""")
            animated(id, img, BuildKind.PICTURE)
        }
        // Grouped shapes go inside one <p:grpSp> whose child space equals the slide space (chOff/chExt = off/ext),
        // so the children keep their absolute frames and PowerPoint still treats them as a group.
        val written = HashSet<com.therealityteam.aeropages.model.SlideShape>()
        fun emitShape(sh: com.therealityteam.aeropages.model.SlideShape) {
            val id = nextId++; append(withMath(sh.box) { m -> freeShapeXml(id, sh, linkRels, d, m, p.backdropOf(slide)) }); written.add(sh)
            animated(id, sh.box, BuildKind.TEXT)
        }
        slide.shapes.forEach { sh ->
            if (sh in written) return@forEach
            val gid = sh.groupId
            val members = if (gid == null) emptyList() else slide.shapes.filter { it.groupId == gid }
            if (members.size < 2) { emitShape(sh); return@forEach }
            val x = members.minOf { it.xPct }; val y = members.minOf { it.yPct }
            val r = members.maxOf { it.xPct + it.wPct }; val b = members.maxOf { it.yPct + it.hPct }
            val gx = (x * d.cx).toInt(); val gy = (y * d.cy).toInt(); val gw = ((r - x) * d.cx).toInt().coerceAtLeast(1); val gh = ((b - y) * d.cy).toInt().coerceAtLeast(1)
            val gidNode = nextId++
            append("""<p:grpSp><p:nvGrpSpPr><p:cNvPr id="$gidNode" name="Group $gidNode"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""")
            append("""<p:grpSpPr><a:xfrm><a:off x="$gx" y="$gy"/><a:ext cx="$gw" cy="$gh"/><a:chOff x="$gx" y="$gy"/><a:chExt cx="$gw" cy="$gh"/></a:xfrm></p:grpSpPr>""")
            members.forEach { emitShape(it) }
            append("</p:grpSp>")
        }
        slide.tables.forEach { val id = nextId++; append(tableXml(id, it, d, linkRels, it === taker)); animated(id, it, BuildKind.GRAPHIC) }
        chartRels.forEach { (chart, rid) -> val id = nextId++; append(chartFrameXml(id, chart, rid, d, chart === taker)); animated(id, chart, BuildKind.GRAPHIC) }
        // Header & footer: the date, footer and number placeholders, not on a title slide when the deck hides them there.
        // Each where its design (else the master) puts it, in its size and colour (else one reading over the slide).
        com.therealityteam.aeropages.model.SlideChrome.placed(p, slide, index + 1).forEach { c ->
            val color = com.therealityteam.aeropages.model.SlideChrome.lookOf(p, slide, c.part)?.color ?: com.therealityteam.aeropages.model.SlideChrome.solidOver(p.backgroundOf(slide))
            when (c.part) {
                com.therealityteam.aeropages.model.SlideChrome.Part.DATE -> p.date?.let { append(dateShape(nextId++, it, d, color, c)) }
                com.therealityteam.aeropages.model.SlideChrome.Part.FOOTER -> append(footerShape(nextId++, p.footer, d, color, c))
                com.therealityteam.aeropages.model.SlideChrome.Part.NUMBER -> append(slideNumberShape(nextId++, index + 1, d, color, c))
            }
        }
        // A dark slide maps the theme's light colours to text and its dark ones to the background, as
        // PowerPoint's dark background styles do: text setting no colour shows light there, as in the app.
        val dark = p.backdropOf(slide)?.let { com.therealityteam.aeropages.model.TextColors.isDark(it) } == true
        append("</p:spTree></p:cSld>")
        append(if (dark) """<p:clrMapOvr><a:overrideClrMapping bg1="dk1" tx1="lt1" bg2="dk2" tx2="lt2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/></p:clrMapOvr>"""
            else "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>")
        // advTm = rehearsed auto-advance time (排练计时); PowerPoint honours it with 使用计时.
        // The speed always: a transition that names none is "fast" to PowerPoint (the schema's default).
        val spd = when (slide.transitionSpeed) {
            com.therealityteam.aeropages.model.TransitionSpeed.SLOW -> """ spd="slow""""
            com.therealityteam.aeropages.model.TransitionSpeed.FAST -> """ spd="fast""""
            else -> """ spd="med""""
        }
        val adv = if (slide.advanceMs > 0) """ advTm="${slide.advanceMs}"""" else ""
        // Effect Options as PresentationML has them: the direction of the motion (a push "from the right" moves left: dir="l").
        val dir = com.therealityteam.aeropages.model.TransitionDir.of(slide.transition, slide.transitionDir)
        val side = when (dir) {
            com.therealityteam.aeropages.model.TransitionDir.FROM_LEFT -> "r"
            com.therealityteam.aeropages.model.TransitionDir.FROM_TOP -> "d"
            com.therealityteam.aeropages.model.TransitionDir.FROM_BOTTOM -> "u"
            else -> "l"
        }
        val effect = when (slide.transition) {
            com.therealityteam.aeropages.model.SlideTransition.FADE -> "<p:fade/>"
            com.therealityteam.aeropages.model.SlideTransition.PUSH -> """<p:push dir="$side"/>"""
            com.therealityteam.aeropages.model.SlideTransition.ZOOM -> """<p:zoom dir="${if (dir == com.therealityteam.aeropages.model.TransitionDir.OUT) "out" else "in"}"/>"""
            com.therealityteam.aeropages.model.SlideTransition.COVER -> """<p:cover dir="$side"/>"""
            com.therealityteam.aeropages.model.SlideTransition.UNCOVER -> """<p:pull dir="$side"/>"""
            com.therealityteam.aeropages.model.SlideTransition.WIPE -> """<p:wipe dir="$side"/>"""
            com.therealityteam.aeropages.model.SlideTransition.SPLIT -> {
                val vert = dir == com.therealityteam.aeropages.model.TransitionDir.VERTICAL_OUT || dir == com.therealityteam.aeropages.model.TransitionDir.VERTICAL_IN
                val inward = dir == com.therealityteam.aeropages.model.TransitionDir.HORIZONTAL_IN || dir == com.therealityteam.aeropages.model.TransitionDir.VERTICAL_IN
                """<p:split orient="${if (vert) "vert" else "horz"}" dir="${if (inward) "in" else "out"}"/>"""
            }
            com.therealityteam.aeropages.model.SlideTransition.RANDOM_BARS -> """<p:randomBar dir="${if (dir == com.therealityteam.aeropages.model.TransitionDir.VERTICAL_OUT) "vert" else "horz"}"/>"""
            com.therealityteam.aeropages.model.SlideTransition.SHAPE -> "<p:circle/>"
            com.therealityteam.aeropages.model.SlideTransition.DISSOLVE -> "<p:dissolve/>"
            com.therealityteam.aeropages.model.SlideTransition.CUT -> "<p:cut/>"
            com.therealityteam.aeropages.model.SlideTransition.WHEEL -> """<p:wheel spokes="4"/>"""
            com.therealityteam.aeropages.model.SlideTransition.NONE -> null
        }
        if (effect != null) append("""<p:transition$spd$adv>$effect</p:transition>""")
        else if (adv.isNotEmpty()) append("""<p:transition$adv/>""")
        if (anims.isNotEmpty()) append(timingXml(anims))
        append("""</p:sld>""")
    }

    /**
     * A date, footer or number placeholder — PowerPoint's `dt` (idx 10), `ftr` (11) and `sldNum` (12) —
     * where [c] is placed, its text centred there vertically; [paragraph] is its `a:p`.
     */
    private fun chromeShape(id: Int, name: String, ph: String, c: com.therealityteam.aeropages.model.SlideChrome.Placed, d: Emu, paragraph: String): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr>""" +
            """<p:spPr><a:xfrm><a:off x="${(c.xPct * d.cx).toInt()}" y="${(c.yPct * d.cy).toInt()}"/><a:ext cx="${(c.wPct * d.cx).toInt()}" cy="${(c.hPct * d.cy).toInt()}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>""" +
            """<p:txBody><a:bodyPr anchor="ctr"/><a:lstStyle/>$paragraph</p:txBody></p:sp>"""

    /** [shown], the colour a placeholder's text shows in, where it is not the master's [color] (which the slide cannot read). */
    private fun flipped(color: Long?, shown: Long?): Long? = if (color != null && shown != null && shown != color) shown else null

    /** A chrome run's properties: [sizePt] (PowerPoint's 12 pt) in [color]; [lang] its language (a date field's format follows it). */
    private fun chromeRPr(color: Long, sizePt: Float, lang: String = "zh-CN"): String =
        """<a:rPr lang="$lang" sz="${(sizePt * 100).toInt()}" dirty="0"><a:solidFill><a:srgbClr val="${hex(color)}"/></a:solidFill></a:rPr>"""

    /** A chrome paragraph's alignment. */
    private fun chromeAlign(c: com.therealityteam.aeropages.model.SlideChrome.Placed): String = when (c.align) {
        com.therealityteam.aeropages.model.TextAlign.CENTER -> """<a:pPr algn="ctr"/>"""
        com.therealityteam.aeropages.model.TextAlign.RIGHT -> """<a:pPr algn="r"/>"""
        else -> ""
    }

    /** The date placeholder: a date field PowerPoint updates (`datetimeN`, today's date cached), or the fixed text. */
    private fun dateShape(id: Int, date: com.therealityteam.aeropages.model.SlideDate, d: Emu, color: Long, c: com.therealityteam.aeropages.model.SlideChrome.Placed): String {
        val fixed = date.fixed
        val run = if (fixed != null) """<a:r>${chromeRPr(color, c.sizePt)}<a:t>${esc(fixed)}</a:t></a:r>"""
            else DateFields.fieldOf(date.format).let { (type, lang) -> """<a:fld id="{7A1F3C2E-5B8D-4E6A-9C0B-2D4F6A8B1C3E}" type="$type">${chromeRPr(color, c.sizePt, lang)}<a:t>${esc(date.text())}</a:t></a:fld>""" }
        return chromeShape(id, "Date Placeholder", """type="dt" sz="half" idx="10"""", c, d, "<a:p>${chromeAlign(c)}$run</a:p>")
    }

    /** The footer placeholder (PowerPoint's centres its text). */
    private fun footerShape(id: Int, text: String, d: Emu, color: Long, c: com.therealityteam.aeropages.model.SlideChrome.Placed): String =
        chromeShape(id, "Footer Placeholder", """type="ftr" sz="quarter" idx="11"""", c, d,
            """<a:p>${chromeAlign(c)}<a:r>${chromeRPr(color, c.sizePt)}<a:t>${esc(text)}</a:t></a:r></a:p>""")

    /** The slide-number placeholder carrying a live `slidenum` field. */
    private fun slideNumberShape(id: Int, number: Int, d: Emu, color: Long, c: com.therealityteam.aeropages.model.SlideChrome.Placed): String =
        chromeShape(id, "Slide Number Placeholder", """type="sldNum" sz="quarter" idx="12"""", c, d,
            """<a:p>${chromeAlign(c)}<a:fld id="{B6F15528-21DE-4FAA-801E-634DDDAF4B2B}" type="slidenum">${chromeRPr(color, c.sizePt)}<a:t>$number</a:t></a:fld></a:p>""")

    /**
     * The master's and a layout's date, footer and number placeholders — where PowerPoint's own
     * Header & Footer puts a slide's — ids from [firstId] (a layout's without a frame: its master's).
     */
    private fun chromePlaceholders(firstId: Int, d: Emu, framed: Boolean,
                                   looks: Map<com.therealityteam.aeropages.model.SlideChrome.Part, com.therealityteam.aeropages.model.ChromeLook> = emptyMap()): String = buildString {
        val parts = listOf(
            Triple("Date Placeholder", """type="dt" sz="half" idx="10"""", """<a:fld id="{7A1F3C2E-5B8D-4E6A-9C0B-2D4F6A8B1C3E}" type="datetimeFigureOut"><a:rPr lang="zh-CN"/><a:t>${esc(com.therealityteam.aeropages.model.SlideDate().text())}</a:t></a:fld>"""),
            Triple("Footer Placeholder", """type="ftr" sz="quarter" idx="11"""", ""),
            Triple("Slide Number Placeholder", """type="sldNum" sz="quarter" idx="12"""", """<a:fld id="{B6F15528-21DE-4FAA-801E-634DDDAF4B2B}" type="slidenum"><a:rPr lang="zh-CN"/><a:t>‹#›</a:t></a:fld>"""),
        )
        com.therealityteam.aeropages.model.SlideChrome.Part.entries.forEachIndexed { k, part ->
            val (name, ph, field) = parts[k]
            // The master's own look where it has one (a template's), else PowerPoint's.
            val look = looks[part]
            val algn = when (look?.align ?: part.align) { com.therealityteam.aeropages.model.TextAlign.CENTER -> "ctr"; com.therealityteam.aeropages.model.TextAlign.RIGHT -> "r"; else -> "l" }
            val fx = look?.xPct ?: part.xPct; val fy = look?.yPct ?: part.yPct; val fw = look?.wPct ?: part.wPct; val fh = look?.hPct ?: part.hPct
            append("""<p:sp><p:nvSpPr><p:cNvPr id="${firstId + k}" name="$name ${firstId + k}"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr>""")
            if (framed) append("""<p:spPr><a:xfrm><a:off x="${(fx * d.cx).toInt()}" y="${(fy * d.cy).toInt()}"/><a:ext cx="${(fw * d.cx).toInt()}" cy="${(fh * d.cy).toInt()}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>""")
            else append("<p:spPr/>")
            append("""<p:txBody><a:bodyPr anchor="ctr"/><a:lstStyle>""")
            val fill = look?.color?.let { """<a:solidFill><a:srgbClr val="${hex(it)}"/></a:solidFill>""" } ?: """<a:solidFill><a:schemeClr val="tx1"><a:tint val="75000"/></a:schemeClr></a:solidFill>"""
            if (framed) append("""<a:lvl1pPr algn="$algn"><a:defRPr sz="${((look?.sizePt ?: com.therealityteam.aeropages.model.SlideChrome.SIZE_PT) * 100).toInt()}">$fill</a:defRPr></a:lvl1pPr>""")
            append("""</a:lstStyle><a:p>$field<a:endParaRPr lang="zh-CN"/></a:p></p:txBody></p:sp>""")
        }
    }

    /** How PowerPoint's build list records an animated element: text shapes as paragraphs, frames as graphics, pictures not at all. */
    private enum class BuildKind { TEXT, GRAPHIC, PICTURE }

    /** One effect with the element and the shape id it targets. */
    private class AnimSpec(val spid: Int, val target: com.therealityteam.aeropages.model.AnimTarget, val anim: com.therealityteam.aeropages.model.ElementAnim, val kind: BuildKind)

    /** PowerPoint's presetSubtype: the direction of a directional effect, 0 for the others. */
    private fun subtypeOf(a: com.therealityteam.aeropages.model.ElementAnim): Int = if (!a.effect.directional) 0 else when (a.dir) {
        com.therealityteam.aeropages.model.AnimDir.BOTTOM -> 4
        com.therealityteam.aeropages.model.AnimDir.LEFT -> 8
        com.therealityteam.aeropages.model.AnimDir.RIGHT -> 2
        com.therealityteam.aeropages.model.AnimDir.TOP -> 1
    }

    /**
     * The behaviours that make PowerPoint play the effect: fade / wipe / split filters, scales,
     * rotations and position ramps, a lasting opacity for Transparency. Entrances first make the
     * shape visible; exits hide it once they end.
     */
    private fun effectBehaviours(spec: AnimSpec, paragraph: Int, nextId: () -> Int): String = buildString {
        val a = spec.anim
        val ms = a.durationMs
        // A paragraph of an effect built by paragraph is targeted as the shape's text range pRg.
        fun tgt() = if (paragraph < 0) """<p:tgtEl><p:spTgt spid="${spec.spid}"/></p:tgtEl>"""
            else """<p:tgtEl><p:spTgt spid="${spec.spid}"><p:txEl><p:pRg st="$paragraph" end="$paragraph"/></p:txEl></p:spTgt></p:tgtEl>"""
        fun visibility(value: String, at: Int) = append("""<p:set><p:cBhvr><p:cTn id="${nextId()}" dur="1" fill="hold"><p:stCondLst><p:cond delay="$at"/></p:stCondLst></p:cTn>${tgt()}<p:attrNameLst><p:attrName>style.visibility</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="$value"/></p:to></p:set>""")
        fun filter(transition: String, filter: String) = append("""<p:animEffect transition="$transition" filter="$filter"><p:cBhvr><p:cTn id="${nextId()}" dur="$ms"/>${tgt()}</p:cBhvr></p:animEffect>""")
        fun ramp(attr: String, from: String, to: String) = append("""<p:anim calcmode="lin" valueType="num"><p:cBhvr additive="base"><p:cTn id="${nextId()}" dur="$ms" fill="hold"/>${tgt()}<p:attrNameLst><p:attrName>$attr</p:attrName></p:attrNameLst></p:cBhvr><p:tavLst><p:tav tm="0"><p:val><p:strVal val="$from"/></p:val></p:tav><p:tav tm="100000"><p:val><p:strVal val="$to"/></p:val></p:tav></p:tavLst></p:anim>""")
        fun scale(from: Int?, to: Int?, by: Int? = null, dur: Int = ms, autoRev: Boolean = false) = append(
            """<p:animScale><p:cBhvr><p:cTn id="${nextId()}" dur="$dur"${if (autoRev) " autoRev=\"1\"" else ""} fill="hold"/>${tgt()}</p:cBhvr>""" +
                (by?.let { """<p:by x="$it" y="$it"/>""" } ?: "") + (from?.let { """<p:from x="$it" y="$it"/>""" } ?: "") + (to?.let { """<p:to x="$it" y="$it"/>""" } ?: "") + "</p:animScale>")
        fun rotate(by: Int, dur: Int = ms, autoRev: Boolean = false, repeat: Int? = null) = append(
            """<p:animRot by="$by"><p:cBhvr><p:cTn id="${nextId()}" dur="$dur"${if (autoRev) " autoRev=\"1\"" else ""}${repeat?.let { " repeatCount=\"$it\"" } ?: ""} fill="hold"/>${tgt()}<p:attrNameLst><p:attrName>r</p:attrName></p:attrNameLst></p:cBhvr></p:animRot>""")
        /** Where a fly starts (entrances) or ends (exits): just off the slide on the [a.dir] side. */
        fun offSlide(): Pair<String, String> = when (a.dir) {
            com.therealityteam.aeropages.model.AnimDir.BOTTOM -> "ppt_y" to "1+#ppt_h/2"
            com.therealityteam.aeropages.model.AnimDir.TOP -> "ppt_y" to "0-#ppt_h/2"
            com.therealityteam.aeropages.model.AnimDir.LEFT -> "ppt_x" to "0-#ppt_w/2"
            com.therealityteam.aeropages.model.AnimDir.RIGHT -> "ppt_x" to "1+#ppt_w/2"
        }
        /** A short drift on the [a.dir] side (floats). */
        fun nearby(): Pair<String, String> = when (a.dir) {
            com.therealityteam.aeropages.model.AnimDir.BOTTOM -> "ppt_y" to "#ppt_y+.1"
            com.therealityteam.aeropages.model.AnimDir.TOP -> "ppt_y" to "#ppt_y-.1"
            com.therealityteam.aeropages.model.AnimDir.LEFT -> "ppt_x" to "#ppt_x-.1"
            com.therealityteam.aeropages.model.AnimDir.RIGHT -> "ppt_x" to "#ppt_x+.1"
        }
        val wipe = when (a.dir) {
            com.therealityteam.aeropages.model.AnimDir.LEFT -> "left"; com.therealityteam.aeropages.model.AnimDir.RIGHT -> "right"
            com.therealityteam.aeropages.model.AnimDir.TOP -> "down"; com.therealityteam.aeropages.model.AnimDir.BOTTOM -> "up"
        }
        if (a.effect.cls == com.therealityteam.aeropages.model.AnimClass.ENTRANCE) visibility("visible", 0)
        when (a.effect) {
            com.therealityteam.aeropages.model.AnimEffect.APPEAR, com.therealityteam.aeropages.model.AnimEffect.DISAPPEAR -> {}
            com.therealityteam.aeropages.model.AnimEffect.FADE -> filter("in", "fade")
            com.therealityteam.aeropages.model.AnimEffect.FLY_IN -> { val (attr, from) = offSlide(); ramp(attr, from, "#$attr") }
            com.therealityteam.aeropages.model.AnimEffect.FLOAT_IN -> { filter("in", "fade"); val (attr, from) = nearby(); ramp(attr, from, "#$attr") }
            com.therealityteam.aeropages.model.AnimEffect.SPLIT -> filter("in", "barn(inVertical)")
            com.therealityteam.aeropages.model.AnimEffect.WIPE -> filter("in", "wipe($wipe)")
            com.therealityteam.aeropages.model.AnimEffect.ZOOM -> { filter("in", "fade"); scale(from = 0, to = 100000) }
            com.therealityteam.aeropages.model.AnimEffect.GROW -> { filter("in", "fade"); scale(from = 0, to = 100000); rotate(-5400000) }
            com.therealityteam.aeropages.model.AnimEffect.BOUNCE -> { filter("in", "fade"); ramp("ppt_y", "#ppt_y-.25", "#ppt_y") }
            com.therealityteam.aeropages.model.AnimEffect.SPIN -> { filter("in", "fade"); scale(from = 0, to = 100000); rotate(21600000) }
            com.therealityteam.aeropages.model.AnimEffect.PULSE -> scale(from = null, to = null, by = 110000, dur = (ms / 2).coerceAtLeast(1), autoRev = true)
            com.therealityteam.aeropages.model.AnimEffect.TEETER -> rotate(240000, dur = (ms / 8).coerceAtLeast(1), autoRev = true, repeat = 4000)
            com.therealityteam.aeropages.model.AnimEffect.ROTATE -> rotate(21600000)
            com.therealityteam.aeropages.model.AnimEffect.GROW_SHRINK -> scale(from = null, to = null, by = 150000)
            com.therealityteam.aeropages.model.AnimEffect.TRANSPARENCY -> append("""<p:set><p:cBhvr><p:cTn id="${nextId()}" dur="$ms" fill="hold"/>${tgt()}<p:attrNameLst><p:attrName>style.opacity</p:attrName></p:attrNameLst></p:cBhvr><p:to><p:strVal val="0.5"/></p:to></p:set>""")
            com.therealityteam.aeropages.model.AnimEffect.FADE_OUT -> filter("out", "fade")
            com.therealityteam.aeropages.model.AnimEffect.FLY_OUT -> { val (attr, to) = offSlide(); ramp(attr, "#$attr", to) }
            com.therealityteam.aeropages.model.AnimEffect.FLOAT_OUT -> { filter("out", "fade"); val (attr, to) = nearby(); ramp(attr, "#$attr", to) }
            com.therealityteam.aeropages.model.AnimEffect.SPLIT_OUT -> filter("out", "barn(outVertical)")
            com.therealityteam.aeropages.model.AnimEffect.WIPE_OUT -> filter("out", "wipe($wipe)")
            com.therealityteam.aeropages.model.AnimEffect.ZOOM_OUT -> { filter("out", "fade"); scale(from = 100000, to = 0) }
            com.therealityteam.aeropages.model.AnimEffect.SHRINK_TURN -> { filter("out", "fade"); scale(from = 100000, to = 0); rotate(5400000) }
        }
        if (a.effect.cls == com.therealityteam.aeropages.model.AnimClass.EXIT) visibility("hidden", if (a.effect == com.therealityteam.aeropages.model.AnimEffect.DISAPPEAR) 0 else (ms - 1).coerceAtLeast(0))
    }

    /**
     * The slide's `<p:timing>`: PowerPoint's main sequence of click groups. Each On Click effect opens
     * a group (effects before the first one start with the slide, the group's `onBegin` condition);
     * within a group, an After Previous effect opens a new chain that starts when everything before
     * it has finished, With Previous effects join the chain running. Each effect carries its preset
     * (class, id, subtype), how it starts and its own delay; the build list names the text shapes and
     * graphic frames that take part. cTn ids are unique within the block.
     */
    private fun timingXml(specs: List<AnimSpec>): String = buildString {
        var idSeq = 3
        val nextId = { idSeq++ }
        // The units a show plays (an effect built by paragraph is one per paragraph), in click groups of chains.
        val specOf = java.util.IdentityHashMap<com.therealityteam.aeropages.model.ElementAnim, AnimSpec>().apply { specs.forEach { put(it.anim, it) } }
        val units = com.therealityteam.aeropages.model.AnimTimeline.units(specs.sortedBy { it.anim.order }.map { com.therealityteam.aeropages.model.AnimTimeline.Entry(it.target, it.anim) })
        val groups = com.therealityteam.aeropages.model.AnimTimeline.groups(units, { it.start }) { it.anim }
        // One build group per effect: its paragraphs share it.
        val grpIds = HashMap<Int, Int>()
        val grpOf = java.util.IdentityHashMap<com.therealityteam.aeropages.model.ElementAnim, Int>()
        val builds = ArrayList<Triple<AnimSpec, Int, Boolean>>()
        append("""<p:timing><p:tnLst><p:par><p:cTn id="1" dur="indefinite" restart="never" nodeType="tmRoot"><p:childTnLst>""")
        append("""<p:seq concurrent="1" nextAc="seek"><p:cTn id="2" dur="indefinite" nodeType="mainSeq"><p:childTnLst>""")
        for (group in groups) {
            val first = group.first().second.first()
            // A group whose first effect is not On Click starts by itself: with the slide, or after the group before it.
            val trigger = if (first.start == com.therealityteam.aeropages.model.AnimStart.ON_CLICK) """<p:cond delay="indefinite"/>"""
                else """<p:cond delay="indefinite"/><p:cond evt="onBegin" delay="0"><p:tn val="2"/></p:cond>"""
            append("""<p:par><p:cTn id="${nextId()}" fill="hold"><p:stCondLst>$trigger</p:stCondLst><p:childTnLst>""")
            for ((start, effects) in group) {
                append("""<p:par><p:cTn id="${nextId()}" fill="hold"><p:stCondLst><p:cond delay="$start"/></p:stCondLst><p:childTnLst>""")
                for (unit in effects) {
                    val spec = specOf.getValue(unit.anim)
                    val a = spec.anim
                    val grpId = grpOf.getOrPut(a) {
                        grpIds.getOrDefault(spec.spid, 0).also { grpIds[spec.spid] = it + 1; builds.add(Triple(spec, it, unit.paragraph >= 0)) }
                    }
                    val nodeType = when (unit.start) {
                        com.therealityteam.aeropages.model.AnimStart.ON_CLICK -> "clickEffect"
                        com.therealityteam.aeropages.model.AnimStart.WITH_PREVIOUS -> "withEffect"
                        com.therealityteam.aeropages.model.AnimStart.AFTER_PREVIOUS -> "afterEffect"
                    }
                    val cls = when (a.effect.cls) {
                        com.therealityteam.aeropages.model.AnimClass.ENTRANCE -> "entr"
                        com.therealityteam.aeropages.model.AnimClass.EMPHASIS -> "emph"
                        com.therealityteam.aeropages.model.AnimClass.EXIT -> "exit"
                    }
                    append("""<p:par><p:cTn id="${nextId()}" presetID="${a.effect.presetId}" presetClass="$cls" presetSubtype="${subtypeOf(a)}" fill="hold" grpId="$grpId" nodeType="$nodeType"><p:stCondLst><p:cond delay="${a.delayMs}"/></p:stCondLst><p:childTnLst>""")
                    append(effectBehaviours(spec, unit.paragraph, nextId))
                    append("""</p:childTnLst></p:cTn></p:par>""")
                }
                append("""</p:childTnLst></p:cTn></p:par>""")
            }
            append("""</p:childTnLst></p:cTn></p:par>""")
        }
        append("""</p:childTnLst></p:cTn>""")
        append("""<p:prevCondLst><p:cond evt="onPrev" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:prevCondLst>""")
        append("""<p:nextCondLst><p:cond evt="onNext" delay="0"><p:tgtEl><p:sldTgt/></p:tgtEl></p:cond></p:nextCondLst>""")
        append("""</p:seq></p:childTnLst></p:cTn></p:par></p:tnLst>""")
        val listed = builds.filter { it.first.kind != BuildKind.PICTURE }
        if (listed.isNotEmpty()) {
            append("""<p:bldLst>""")
            for ((spec, grpId, byParagraph) in listed) {
                if (spec.kind == BuildKind.TEXT) append(if (byParagraph) """<p:bldP spid="${spec.spid}" grpId="$grpId" build="p"/>""" else """<p:bldP spid="${spec.spid}" grpId="$grpId" animBg="1"/>""")
                else append("""<p:bldGraphic spid="${spec.spid}" grpId="$grpId"><p:bldAsOne/></p:bldGraphic>""")
            }
            append("""</p:bldLst>""")
        }
        append("""</p:timing>""")
    }

    /** The graphicFrame placed on the slide that references the chart part by [relId]. */
    private fun chartFrameXml(id: Int, c: com.therealityteam.aeropages.model.SlideChart, relId: String, d: Emu, inContent: Boolean = false): String = buildString {
        val x = (c.xPct * d.cx).toInt(); val y = (c.yPct * d.cy).toInt()
        val cx = (c.wPct * d.cx).toInt(); val cy = (c.hPct * d.cy).toInt()
        append("""<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="$id"${nameAttrs("Chart$id", c)}/><p:cNvGraphicFramePr/>${nvPr(inContent)}</p:nvGraphicFramePr>""")
        append("""<p:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></p:xfrm>""")
        append("""<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:id="$relId"/></a:graphicData></a:graphic></p:graphicFrame>""")
    }

    /** A shape's geometry: its preset (`a:prstGeom`), or a freeform's own paths (`a:custGeom`). */
    internal fun geometryXml(sh: com.therealityteam.aeropages.model.SlideShape): String {
        // A shape drawn from a preset stays that preset, with its adjust values (PowerPoint works it out again).
        sh.preset?.takeIf { sh.type == com.therealityteam.aeropages.model.ShapeType.FREEFORM && PresetGeometries.has(it) }?.let { p ->
            return """<a:prstGeom prst="$p">${PresetGeometries.avLstXml(sh.presetAdjust)}</a:prstGeom>"""
        }
        val free = sh.path?.takeIf { sh.type == com.therealityteam.aeropages.model.ShapeType.FREEFORM }
        return if (free != null) DrawingGeometry.write(free) else """<a:prstGeom prst="${prstOf(sh.type)}"><a:avLst/></a:prstGeom>"""
    }

    /** DrawingML's preset geometry for a shape [t] (`a:prstGeom@prst`). */
    internal fun prstOf(t: com.therealityteam.aeropages.model.ShapeType): String = when (t) {
        com.therealityteam.aeropages.model.ShapeType.ROUNDED -> "roundRect"
        com.therealityteam.aeropages.model.ShapeType.ELLIPSE -> "ellipse"
        com.therealityteam.aeropages.model.ShapeType.TRIANGLE -> "triangle"
        com.therealityteam.aeropages.model.ShapeType.STAR -> "star5"
        com.therealityteam.aeropages.model.ShapeType.ARROW -> "rightArrow"
        com.therealityteam.aeropages.model.ShapeType.LINE -> "line"
        else -> "rect"
    }

    /** A shape's outer shadow as DrawingML's `a:effectLst` (after its outline in `spPr`); "" for none. */
    internal fun effectXml(sh: com.therealityteam.aeropages.model.SlideShape): String = effectXml(sh.shadow)

    /** A shadow as DrawingML's `a:effectLst`; "" for none. */
    internal fun effectXml(shadow: com.therealityteam.aeropages.model.ShapeShadow?): String {
        val s = shadow ?: return ""
        val dist = kotlin.math.hypot(s.dxPt, s.dyPt)
        var dir = Math.toDegrees(kotlin.math.atan2(s.dyPt.toDouble(), s.dxPt.toDouble()))
        if (dir < 0) dir += 360.0
        val alpha = Math.round(((s.color ushr 24) and 0xFF) / 255f * 100000)
        return """<a:effectLst><a:outerShdw blurRad="${Math.round(s.blurPt * 12700)}" dist="${Math.round(dist * 12700)}" dir="${Math.round(dir * 60000)}" algn="ctr" rotWithShape="0">""" +
            """<a:srgbClr val="${hex(s.color)}"><a:alpha val="$alpha"/></a:srgbClr></a:outerShdw></a:effectLst>"""
    }

    /** A shape's colour as DrawingML writes it: its RGB, and its `a:alpha` when it is see-through. */
    internal fun clrXml(c: Long): String {
        val alpha = ((c ushr 24) and 0xFF).toInt()
        return if (alpha == 0xFF) """<a:srgbClr val="${hex(c)}"/>""" else """<a:srgbClr val="${hex(c)}"><a:alpha val="${Math.round(alpha / 255f * 100000)}"/></a:srgbClr>"""
    }

    /** A shape's fill as DrawingML writes it: none, solid, or its two-colour gradient along its angle. */
    internal fun fillXml(sh: com.therealityteam.aeropages.model.SlideShape): String {
        val fill = sh.fill ?: return "<a:noFill/>"
        val grad = sh.fillGradient ?: return """<a:solidFill>${clrXml(fill)}</a:solidFill>"""
        return gradFillXml(fill, grad, sh.gradientAngle, sh.gradientKind, sh.gradientCx, sh.gradientCy)
    }

    /** A background's fill: its colour, or its gradient from that colour. */
    private fun bgXml(color: Long, shade: com.therealityteam.aeropages.model.BackgroundShade?): String =
        """<p:bg><p:bgPr>${if (shade == null) """<a:solidFill><a:srgbClr val="${hex(color)}"/></a:solidFill>""" else gradFillXml(color, shade.second, shade.angleDeg, shade.kind, shade.cx, shade.cy)}<a:effectLst/></p:bgPr></p:bg>"""

    /** A two-colour gradient as `a:gradFill`: linear along [angleDeg], axial, or a path gradient out from ([cx], [cy]). */
    private fun gradFillXml(fill: Long, grad: Long, angleDeg: Float, kind: com.therealityteam.aeropages.model.GradientKind, cx: Float, cy: Float): String {
        fun gs(pos: Int, c: Long) = """<a:gs pos="$pos">${clrXml(c)}</a:gs>"""
        val lin = """<a:lin ang="${(angleDeg * 60000f).toInt()}" scaled="0"/>"""
        return when (kind) {
            com.therealityteam.aeropages.model.GradientKind.LINEAR -> """<a:gradFill rotWithShape="1"><a:gsLst>${gs(0, fill)}${gs(100000, grad)}</a:gsLst>$lin</a:gradFill>"""
            // Axial: the first colour at both ends, the second in the middle.
            com.therealityteam.aeropages.model.GradientKind.AXIAL -> """<a:gradFill rotWithShape="1"><a:gsLst>${gs(0, fill)}${gs(50000, grad)}${gs(100000, fill)}</a:gsLst>$lin</a:gradFill>"""
            else -> {
                // A path gradient from a point: the fill-to rectangle closed down on the centre.
                val l = Math.round(cx * 100000); val t = Math.round(cy * 100000)
                val path = if (kind == com.therealityteam.aeropages.model.GradientKind.RECTANGULAR) "rect" else "circle"
                """<a:gradFill flip="none" rotWithShape="1"><a:gsLst>${gs(0, fill)}${gs(100000, grad)}</a:gsLst>""" +
                    """<a:path path="$path"><a:fillToRect l="$l" t="$t" r="${100000 - l}" b="${100000 - t}"/></a:path><a:tileRect/></a:gradFill>"""
            }
        }
    }

    /**
     * A shape's outline as DrawingML's `a:ln`: its weight and colour, its dashes, and a line's (or
     * freeform's) arrowheads (`a:headEnd` at its first point, `a:tailEnd` at its last) — [none] when it has none.
     */
    internal fun outlineXml(sh: com.therealityteam.aeropages.model.SlideShape, none: String = ""): String {
        val color = sh.border ?: return none
        return buildString {
            append("""<a:ln w="${Math.round(sh.borderWidthPt * 12700)}"><a:solidFill>${clrXml(color)}</a:solidFill>""")
            if (sh.dash != com.therealityteam.aeropages.model.LineDash.SOLID) append("""<a:prstDash val="${sh.dash.ooxml}"/>""")
            if (sh.type == com.therealityteam.aeropages.model.ShapeType.LINE || sh.type == com.therealityteam.aeropages.model.ShapeType.FREEFORM) {
                listOf("headEnd" to sh.beginArrow, "tailEnd" to sh.endArrow).forEach { (tag, end) ->
                    if (!end.isNone) append("""<a:$tag type="${end.head.ooxml}" w="${com.therealityteam.aeropages.model.LineEnd.sizeName(end.width)}" len="${com.therealityteam.aeropages.model.LineEnd.sizeName(end.length)}"/>""")
                }
            }
            append("</a:ln>")
        }
    }

    /** A free-positioned auto shape / text box (prstGeom + optional fill/outline + text). */
    private fun freeShapeXml(id: Int, sh: com.therealityteam.aeropages.model.SlideShape, linkRels: Map<String, String>, d: Emu, math: Boolean = false,
                             backdrop: Long? = 0xFFFFFFFF): String = buildString {
        val x = (sh.xPct * d.cx).toInt(); val y = (sh.yPct * d.cy).toInt()
        val cx = (sh.wPct * d.cx).toInt(); val cy = (sh.hPct * d.cy).toInt()
        val rotAttr = if (sh.rotationDeg != 0f) " rot=\"${(sh.rotationDeg * 60000).toInt()}\"" else ""
        val flipAttr = (if (sh.flipH) " flipH=\"1\"" else "") + (if (sh.flipV) " flipV=\"1\"" else "")
        // A line without text is a connector, as PowerPoint's Lines gallery makes one.
        if (sh.type == com.therealityteam.aeropages.model.ShapeType.LINE && sh.box.text.isBlank()) {
            append("""<p:cxnSp><p:nvCxnSpPr>${cNvPr(id, "Connector $id", sh.action, linkRels, obj = sh, hover = sh.hoverAction)}<p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>""")
            append("""<p:spPr><a:xfrm$rotAttr$flipAttr><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="line"><a:avLst/></a:prstGeom>""")
            append(outlineXml(sh, "<a:ln><a:noFill/></a:ln>"))
            append(effectXml(sh))
            append("</p:spPr></p:cxnSp>")
            return@buildString
        }
        val txBox = if (sh.type == com.therealityteam.aeropages.model.ShapeType.TEXTBOX) """ txBox="1"""" else ""
        append("""<p:sp><p:nvSpPr>${cNvPr(id, "Shape$id", sh.action, linkRels, obj = sh, hover = sh.hoverAction)}<p:cNvSpPr$txBox/><p:nvPr/></p:nvSpPr>""")
        append("""<p:spPr><a:xfrm$rotAttr$flipAttr><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
        append(geometryXml(sh))
        append(fillXml(sh))
        append(outlineXml(sh))
        append(effectXml(sh))
        val anchor = when (sh.anchor) {
            com.therealityteam.aeropages.model.ShapeAnchor.TOP -> "t"
            com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> "b"
            else -> "ctr"
        }
        // A filled shape's text that sets no colour: the one the app shows over its fill (PowerPoint's
        // default text colour follows the slide, not the fill); an unfilled one's follows the slide there too.
        val textColor = if (sh.fill != null && inheritsColor(sh.box)) com.therealityteam.aeropages.model.TextColors.over(null, sh.textBackdrop(backdrop)) else null
        append("""</p:spPr><p:txBody><a:bodyPr${if (sh.noWrap) " wrap=\"none\"" else ""} anchor="$anchor"/>${listStyle(textColor)}""")
        // The shape's alignment and list ride on each paragraph, which PowerPoint would otherwise take as left and plain.
        paragraphs(sh.box, linkRels, math)
        append("""</p:txBody></p:sp>""")
    }

    /**
     * A box's text as `a:p` paragraphs. With [math], an equation still as it was read goes as Office
     * Math in PowerPoint's `a14:m` — displayed (`m:oMathPara`) when it is its line's only content —
     * and otherwise its runs are text.
     */
    private fun StringBuilder.paragraphs(box: com.therealityteam.aeropages.model.SlideBox, linkRels: Map<String, String>, math: Boolean) {
        for (whole in splitRunsToLines(box.paragraph.runs).ifEmpty { listOf(emptyList()) }) {
            // The tabs a line starts with are its level (a:pPr@lvl), not its text.
            val (level, line) = withoutLevel(whole)
            append("<a:p>")
            append(pPrXml(box, level))
            var i = 0
            while (i < line.size) {
                val eq = if (math) line[i].second.math else null
                if (eq != null) {
                    var end = i
                    val text = StringBuilder()
                    while (end < line.size && line[end].second.math === eq) { text.append(line[end].first); end++ }
                    if (text.toString() == eq.text) {
                        val size = line[i].second.fontSizePt?.let { """ sz="${(it * 100).toInt()}"""" } ?: ""
                        val runProps = """<a:rPr lang="en-US"$size><a:latin typeface="Cambria Math"/></a:rPr>"""
                        val omml = com.therealityteam.aeropages.format.docx.Omml.write(eq.math, runProps = runProps)
                        append("<a14:m>")
                        if (i == 0 && end == line.size) append("""<m:oMathPara><m:oMathParaPr><m:jc m:val="centerGroup"/></m:oMathParaPr><m:oMath>$omml</m:oMath></m:oMathPara>""")
                        else append("<m:oMath>").append(omml).append("</m:oMath>")
                        append("</a14:m>")
                        i = end
                        continue
                    }
                }
                val seg = line[i]
                // A line break inside the line is PowerPoint's a:br (at the text's size).
                seg.first.split(com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK).forEachIndexed { k, piece ->
                    if (k > 0) { append("<a:br>"); append(rPr(seg.second)); append("</a:br>") }
                    if (piece.isNotEmpty()) { append("<a:r>"); append(rPr(seg.second, linkRels)); append("<a:t>${esc(piece)}</a:t></a:r>") }
                }
                i++
            }
            append("</a:p>")
        }
    }

    /** [box]'s text as DrawingML paragraphs (`a:p`…), as a shape's text body holds them — for Excel's shapes. */
    internal fun textParagraphs(box: com.therealityteam.aeropages.model.SlideBox): String = buildString { paragraphs(box, emptyMap(), math = false) }

    /** True when [box] holds an equation still as it was read (its runs spell it). */
    private fun hasMath(box: com.therealityteam.aeropages.model.SlideBox): Boolean {
        val runs = box.paragraph.runs
        var i = 0
        while (i < runs.size) {
            val eq = runs[i].format.math
            if (eq == null) { i++; continue }
            val text = StringBuilder()
            while (i < runs.size && runs[i].format.math === eq) { text.append(runs[i].text); i++ }
            if (text.toString() == eq.text) return true
        }
        return false
    }

    /**
     * A shape as [sp] writes it — or, when its text holds an equation, as PowerPoint writes one:
     * alternate content whose choice has the equation as Office Math (`a14`), and whose fallback,
     * for readers without it (LibreOffice), has it as text.
     */
    private fun withMath(box: com.therealityteam.aeropages.model.SlideBox, sp: (Boolean) -> String): String =
        if (!hasMath(box)) sp(false)
        else """<mc:AlternateContent xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">""" +
            """<mc:Choice xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main" xmlns:m="${com.therealityteam.aeropages.format.docx.Omml.M}" Requires="a14">""" +
            sp(true) + "</mc:Choice><mc:Fallback>" + sp(false) + "</mc:Fallback></mc:AlternateContent>"

    /**
     * A table as a graphicFrame/a:tbl: each cell's fill and half-point borders in its table style's colours (so it
     * draws without a style part, as the app draws it), its text with its own formatting and links, centred in the cell's
     * height; a header or total row's text white and bold where its runs say nothing (the cell's list style), and the
     * six style options on `a:tblPr`.
     */
    private fun tableXml(id: Int, t: com.therealityteam.aeropages.model.SlideTable, d: Emu, linkRels: Map<String, String>, inContent: Boolean = false): String = buildString {
        val look = com.therealityteam.aeropages.model.SlideTableLook
        fun hex(c: Long) = String.format(Locale.ROOT, "%06X", c and 0xFFFFFF)
        val x = (t.xPct * d.cx).toInt(); val y = (t.yPct * d.cy).toInt()
        val cx = (t.wPct * d.cx).toInt(); val cy = (t.hPct * d.cy).toInt()
        fun flag(b: Boolean) = if (b) "1" else "0"
        append("""<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="$id"${nameAttrs("Table$id", t)}/><p:cNvGraphicFramePr/>${nvPr(inContent)}</p:nvGraphicFramePr>""")
        append("""<p:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></p:xfrm>""")
        append("""<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl>""")
        append("""<a:tblPr firstRow="${flag(t.headerRow)}" firstCol="${flag(t.firstColumn)}" lastRow="${flag(t.totalRow)}" lastCol="${flag(t.lastColumn)}" bandRow="${flag(t.bandedRows)}" bandCol="${flag(t.bandedColumns)}"/>""")
        append("<a:tblGrid>")
        for (c in 0 until t.cols) append("""<a:gridCol w="${Math.round(cx * t.colShare(c))}"/>""")
        append("</a:tblGrid>")
        val onHeader = """<a:lstStyle><a:lvl1pPr><a:defRPr b="1"><a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill></a:defRPr></a:lvl1pPr></a:lstStyle>"""
        // Merged cells as PowerPoint writes them: the cell gridSpan / rowSpan, the places it covers hMerge (its row) /
        // vMerge (below it, the first carrying the gridSpan too).
        val cover = t.coverage()
        for (r in 0 until t.rows) {
            append("""<a:tr h="${Math.round(cy * t.rowShare(r))}">""")
            for (c in 0 until t.cols) {
                val box = t.cells.getOrNull(r)?.getOrNull(c)
                val merge = if (t.covered(cover, r, c)) {
                    val ar = cover[r][c] / t.cols; val ac = cover[r][c] % t.cols
                    val (_, acs) = t.spanOf(ar, ac)
                    when {
                        r == ar -> """ hMerge="1""""
                        c == ac -> (if (acs > 1) """ gridSpan="$acs"""" else "") + """ vMerge="1""""
                        else -> """ hMerge="1" vMerge="1""""
                    }
                } else t.spanOf(r, c).let { (rs, cs) -> (if (rs > 1) """ rowSpan="$rs"""" else "") + (if (cs > 1) """ gridSpan="$cs"""" else "") }
                append("<a:tc$merge><a:txBody><a:bodyPr/>")
                append(if (look.emphasized(t, r)) onHeader else "<a:lstStyle/>")
                if (box != null) paragraphs(box, linkRels, math = false) else append("<a:p/>")
                val anchor = when (box?.cellAnchor) { com.therealityteam.aeropages.model.ShapeAnchor.TOP -> "t"; com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> "b"; else -> "ctr" }
                append("""</a:txBody><a:tcPr anchor="$anchor">""")
                // Each side as it draws: the cell's own borders, its neighbour's, or the style's grid (CT_TableCellProperties: lnL, lnR, lnT, lnB).
                val sides = look.sides(t, r, c, cover)
                for ((name, side) in listOf("lnL" to sides.left, "lnR" to sides.right, "lnT" to sides.top, "lnB" to sides.bottom)) append(tableLine(name, side))
                val fill = look.fill(t, r, c)
                append(if (look.isNone(fill)) "<a:noFill/>" else """<a:solidFill><a:srgbClr val="${hex(fill)}"/></a:solidFill>""")
                append("</a:tcPr></a:tc>")
            }
            append("</a:tr>")
        }
        append("""</a:tbl></a:graphicData></a:graphic></p:graphicFrame>""")
    }

    /**
     * A title or body placeholder: [ph] is its `p:ph` attributes (`type="ctrTitle"`, `idx="1"` …),
     * [anchor] where its text sits — as the app draws it, a title in the middle, a body from the top.
     */
    private fun shape(id: Int, name: String, ph: String, anchor: String, x: Int, y: Int, cx: Int, cy: Int, box: com.therealityteam.aeropages.model.SlideBox,
                      linkRels: Map<String, String> = emptyMap(), math: Boolean = false, textColor: Long? = null): String = buildString {
        append("""<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr>""")
        append("""<p:spPr><a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm></p:spPr><p:txBody><a:bodyPr anchor="$anchor"/>${listStyle(textColor)}""")
        paragraphs(box, linkRels, math)
        append("""</p:txBody></p:sp>""")
    }

    /** An object's `p:nvPr`: in the content placeholder ([inContent]) it is that placeholder, as PowerPoint writes a table put in one. */
    private fun nvPr(inContent: Boolean) = if (inContent) "<p:nvPr>$CONTENT_PH</p:nvPr>" else "<p:nvPr/>"
    private const val CONTENT_PH = """<p:ph idx="1"/>"""

    /**
     * A table cell's side [name] (`a:lnL` …): [line]'s width (a double line's whole), colour and dash; none as `a:noFill`.
     */
    private fun tableLine(name: String, line: com.therealityteam.aeropages.model.CellBorder?): String {
        if (line == null) return "<a:$name><a:noFill/></a:$name>"
        val dash = when (line.style) {
            LineStyle.DOTTED -> "sysDot"; LineStyle.DASHED, LineStyle.MEDIUM_DASHED -> "dash"
            LineStyle.DASH_DOT, LineStyle.MEDIUM_DASH_DOT, LineStyle.SLANT_DASH_DOT -> "dashDot"
            LineStyle.DASH_DOT_DOT, LineStyle.MEDIUM_DASH_DOT_DOT -> "sysDashDotDot"
            else -> null
        }
        val cmpd = if (line.style == LineStyle.DOUBLE) """ cmpd="dbl"""" else ""
        return """<a:$name w="${Math.round(line.thicknessPt * 12700)}"$cmpd><a:solidFill><a:srgbClr val="${hex(line.color ?: 0xFF000000)}"/></a:solidFill>""" +
            (dash?.let { """<a:prstDash val="$it"/>""" } ?: "") + "</a:$name>"
    }

    /**
     * A text body's `a:lstStyle`: empty, or — [color] given — every level's text that sets no colour
     * in it (the colour the app shows it in where PowerPoint's default would not read).
     */
    private fun listStyle(color: Long?): String {
        if (color == null) return "<a:lstStyle/>"
        val fill = """<a:defRPr><a:solidFill><a:srgbClr val="${hex(color)}"/></a:solidFill></a:defRPr>"""
        return "<a:lstStyle>" + (1..9).joinToString("") { "<a:lvl${it}pPr>$fill</a:lvl${it}pPr>" } + "</a:lstStyle>"
    }

    /** Whether some run of [box] sets no colour (and so shows the one it inherits). */
    private fun inheritsColor(box: com.therealityteam.aeropages.model.SlideBox): Boolean = box.paragraph.runs.any { it.format.color == null && it.text.isNotEmpty() }

    /** A line's level — the tabs it starts with — and the line without them. */
    private fun withoutLevel(line: List<Pair<String, com.therealityteam.aeropages.model.CharacterFormat>>): Pair<Int, List<Pair<String, com.therealityteam.aeropages.model.CharacterFormat>>> {
        val out = ArrayList(line)
        var level = 0
        while (out.isNotEmpty() && level < com.therealityteam.aeropages.model.SlideLevels.MAX) {
            val (text, format) = out[0]
            val tabs = text.takeWhile { it == '\t' }.length.coerceAtMost(com.therealityteam.aeropages.model.SlideLevels.MAX - level)
            if (tabs == 0) break
            level += tabs
            if (tabs == text.length) out.removeAt(0) else { out[0] = text.substring(tabs) to format; break }
        }
        return level to out
    }

    /** EMUs a level indents by: half an inch, as PowerPoint's outline steps. */
    private const val LEVEL_EMU = 457200

    /**
     * A paragraph's `a:pPr` for [box] at [level]: its alignment, its level, the box's line spacing
     * (`a:lnSpc`, first of its children) and, in a list, the level's bullet (or number) with the
     * hanging indent PowerPoint gives it; nothing when there is nothing to say.
     */
    private fun pPrXml(box: com.therealityteam.aeropages.model.SlideBox, level: Int = 0): String {
        // Left said too: a layout's placeholder may centre what it leaves unsaid (a title slide's title).
        val algn = when (box.align) {
            com.therealityteam.aeropages.model.TextAlign.CENTER -> """ algn="ctr""""
            com.therealityteam.aeropages.model.TextAlign.RIGHT -> """ algn="r""""
            com.therealityteam.aeropages.model.TextAlign.JUSTIFY -> """ algn="just""""
            else -> """ algn="l""""
        }
        val lvl = if (level > 0) """ lvl="$level"""" else ""
        val step = level * LEVEL_EMU
        val spacing = if (box.lineSpacing == 1f) "" else """<a:lnSpc><a:spcPct val="${Math.round(box.lineSpacing * 100000)}"/></a:lnSpc>"""
        val (attrs, bullet) = when (box.listStyle) {
            com.therealityteam.aeropages.model.SlideListStyle.NONE ->
                (if (level > 0) """ marL="$step"$lvl indent="0"$algn""" else algn) to (if (level > 0) "<a:buNone/>" else "")
            com.therealityteam.aeropages.model.SlideListStyle.BULLET ->
                """ marL="${342900 + step}"$lvl indent="-342900"$algn""" to
                    """<a:buFont typeface="Arial"/><a:buChar char="${com.therealityteam.aeropages.model.SlideLevels.bullet(level)}"/>"""
            com.therealityteam.aeropages.model.SlideListStyle.NUMBER ->
                """ marL="${457200 + step}"$lvl indent="-457200"$algn""" to
                    """<a:buFont typeface="+mj-lt"/><a:buAutoNum type="${com.therealityteam.aeropages.model.SlideLevels.autoNumType(level)}"/>"""
        }
        val children = spacing + bullet
        return when {
            attrs.isEmpty() && children.isEmpty() -> ""
            children.isEmpty() -> "<a:pPr$attrs/>"
            else -> "<a:pPr$attrs>$children</a:pPr>"
        }
    }

    /** Split runs into lines (on '\n'); each line is a list of (text, format) segments. */
    private fun splitRunsToLines(runs: List<com.therealityteam.aeropages.model.TextRun>): List<List<Pair<String, com.therealityteam.aeropages.model.CharacterFormat>>> {
        val lines = ArrayList<MutableList<Pair<String, com.therealityteam.aeropages.model.CharacterFormat>>>()
        lines.add(ArrayList())
        for (run in runs) {
            val parts = run.text.split('\n')
            parts.forEachIndexed { idx, part ->
                if (idx > 0) lines.add(ArrayList())
                if (part.isNotEmpty()) lines.last().add(part to run.format)
            }
        }
        return lines
    }

    /** A run-level property element carrying bold/italic/underline/size/colour. */
    private fun rPr(f: com.therealityteam.aeropages.model.CharacterFormat, linkRels: Map<String, String> = emptyMap()): String = buildString {
        append("""<a:rPr lang="zh-CN"""")
        if (f.bold) append(""" b="1"""")
        if (f.italic) append(""" i="1"""")
        if (f.underline) append(""" u="sng"""")
        if (f.strikethrough) append(""" strike="sngStrike"""")
        f.fontSizePt?.let { append(""" sz="${(it * 100).toInt()}"""") }
        f.letterSpacingPt?.let { append(""" spc="${(it * 100).toInt()}"""") }
        if (f.superscript) append(""" baseline="30000"""") else if (f.subscript) append(""" baseline="-25000"""")
        if (f.smallCaps) append(""" cap="small"""")
        append(""" dirty="0"""")
        // Child element order per CT_TextCharacterProperties: solidFill, highlight, latin, hlinkClick.
        val children = buildString {
            f.color?.let { append("""<a:solidFill><a:srgbClr val="${hex(it)}"/></a:solidFill>""") }
            f.highlight?.let { append("""<a:highlight><a:srgbClr val="${hex(it)}"/></a:highlight>""") }
            f.fontFamily?.let { append("""<a:latin typeface="${esc(it)}"/>""") }
            append(hlinkClick(f.link, linkRels))
        }
        if (children.isEmpty()) append("/>") else { append(">"); append(children); append("</a:rPr>") }
    }


    private fun catStrCache(cats: List<String>, f: String? = null): String = buildString {
        append("""<c:strRef><c:f>${f ?: "Sheet1!${'$'}A${'$'}2:${'$'}A${'$'}${cats.size + 1}"}</c:f><c:strCache><c:ptCount val="${cats.size}"/>""")
        cats.forEachIndexed { i, ca -> append("""<c:pt idx="$i"><c:v>${esc(ca)}</c:v></c:pt>""") }
        append("</c:strCache></c:strRef>")
    }

    private fun valNumCache(vals: List<Float>, col: Int, f: String? = null): String = buildString {
        val c = ('B' + col)
        append("""<c:numRef><c:f>${f ?: "Sheet1!${'$'}$c${'$'}2:${'$'}$c${'$'}${vals.size + 1}"}</c:f><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="${vals.size}"/>""")
        vals.forEachIndexed { i, v -> append("""<c:pt idx="$i"><c:v>$v</c:v></c:pt>""") }
        append("</c:numCache></c:numRef>")
    }

    /** A `c:title` holding plain rich text (chart or axis title). */
    private fun titleXml(text: String): String =
        """<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>${esc(text)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>"""

    /**
     * One axis (CT_CatAx / CT_ValAx order: axId, scaling (orientation, max, min), delete, axPos,
     * majorGridlines, title, numFmt, …, crossAx, …, majorUnit). [value] is the chart whose Format
     * Axis settings — fixed bounds, major unit, number format — this value axis carries.
     */
    private fun axisXml(kind: String, id: String, pos: String, cross: String, grid: Boolean, title: String,
                        value: com.therealityteam.aeropages.model.SlideChart? = null): String = buildString {
        append("""<c:$kind><c:axId val="$id"/><c:scaling><c:orientation val="minMax"/>""")
        value?.valMax?.let { append("""<c:max val="${axisNum(it)}"/>""") }
        value?.valMin?.let { append("""<c:min val="${axisNum(it)}"/>""") }
        append("""</c:scaling><c:delete val="0"/><c:axPos val="$pos"/>""")
        if (grid) append("<c:majorGridlines/>")
        if (title.isNotBlank()) append(titleXml(title))
        if (value != null) {
            val general = value.valFormat.equals(com.therealityteam.aeropages.model.SlideChart.GENERAL, ignoreCase = true)
            append("""<c:numFmt formatCode="${esc(value.valFormat)}" sourceLinked="${if (general) 1 else 0}"/>""")
        }
        append("""<c:crossAx val="$cross"/>""")
        value?.valMajor?.let { append("""<c:majorUnit val="${axisNum(it)}"/>""") }
        append("</c:$kind>")
    }

    /** An axis number as OOXML writes it: plain decimal digits, no exponent, no locale. */
    private fun axisNum(v: Double): String = java.math.BigDecimal(v).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()

    /** Data labels showing each point's value (CT_DLbls' required flags in schema order). */
    private const val VALUE_LABELS = """<c:dLbls><c:showLegendKey val="0"/><c:showVal val="1"/><c:showCatName val="0"/><c:showSerName val="0"/><c:showPercent val="0"/><c:showBubbleSize val="0"/></c:dLbls>"""

    /** Sheet-qualified formulas of a chart bound to a worksheet block: the category column, each series' values and (optional) name cell. */
    internal class ChartRefs(val cat: String?, val values: List<String>, val names: List<String?>)

    /**
     * A chart part (chartSpace) carrying cached categories + series so it renders without an
     * embedded workbook; with [refs] the caches point at real worksheet cells, so Excel keeps the chart live.
     */
    internal fun chartXml(c: com.therealityteam.aeropages.model.SlideChart, refs: ChartRefs? = null): String = buildString {
        val pal = com.therealityteam.aeropages.model.ChartStyle.palette(c.colorSet).map { hex(it) }
        val cats = c.categories.toList()
        val type = c.type
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><c:chart>""")
        if (c.title.isNotBlank()) append(titleXml(c.title)).append("""<c:autoTitleDeleted val="0"/>""")
        else append("""<c:autoTitleDeleted val="1"/>""")
        append("<c:plotArea><c:layout/>")
        when (type) {
            ChartType.PIE, ChartType.DOUGHNUT -> {
                append(if (type == ChartType.DOUGHNUT) "<c:doughnutChart>" else "<c:pieChart>")
                append("""<c:varyColors val="1"/>""")
                val ser = c.series.firstOrNull()
                if (ser != null) {
                    append("""<c:ser><c:idx val="0"/><c:order val="0"/>""")
                    append("""<c:tx><c:strRef><c:f>${refs?.names?.getOrNull(0) ?: "Sheet1!${'$'}B${'$'}1"}</c:f><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>${esc(ser.name)}</c:v></c:pt></c:strCache></c:strRef></c:tx>""")
                    cats.indices.forEach { i -> append("""<c:dPt><c:idx val="$i"/><c:bubble3D val="0"/><c:spPr><a:solidFill><a:srgbClr val="${pal[i % pal.size]}"/></a:solidFill></c:spPr></c:dPt>""") }
                    append("""<c:cat>${catStrCache(cats, refs?.cat)}</c:cat>""")
                    append("""<c:val>${valNumCache(ser.values.toList(), 0, refs?.values?.getOrNull(0))}</c:val>""")
                    append("</c:ser>")
                }
                if (c.showValues) append(VALUE_LABELS)
                if (type == ChartType.DOUGHNUT) append("""<c:holeSize val="50"/>""")
                append(if (type == ChartType.DOUGHNUT) "</c:doughnutChart>" else "</c:pieChart>")
            }
            ChartType.SCATTER -> {
                append("""<c:scatterChart><c:scatterStyle val="lineMarker"/><c:varyColors val="0"/>""")
                c.series.forEachIndexed { si, s ->
                    append("""<c:ser><c:idx val="$si"/><c:order val="$si"/>""")
                    append("""<c:tx><c:strRef><c:f>${refs?.names?.getOrNull(si) ?: "Sheet1!${'$'}${('B' + si)}${'$'}1"}</c:f><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>${esc(s.name)}</c:v></c:pt></c:strCache></c:strRef></c:tx>""")
                    append("""<c:spPr><a:ln><a:solidFill><a:srgbClr val="${pal[si % pal.size]}"/></a:solidFill></a:ln></c:spPr>""")
                    append("""<c:xVal>${valNumCache(cats.indices.map { (it + 1).toFloat() }, -1)}</c:xVal>""")
                    append("""<c:yVal>${valNumCache(s.values.toList(), si, refs?.values?.getOrNull(si))}</c:yVal>""")
                    // Straight lines, as drawn here (LibreOffice smooths a series that does not say).
                    append("""<c:smooth val="0"/>""")
                    append("</c:ser>")
                }
                if (c.showValues) append(VALUE_LABELS)
                append("""<c:axId val="111111111"/><c:axId val="222222222"/></c:scatterChart>""")
                append(axisXml("valAx", "111111111", "b", "222222222", grid = false, title = c.catAxisTitle))
                append(axisXml("valAx", "222222222", "l", "111111111", grid = c.showGrid, title = c.valAxisTitle, value = c))
            }
            else -> {
                val open = when (type) {
                    ChartType.LINE -> """<c:lineChart><c:grouping val="standard"/><c:varyColors val="0"/>"""
                    ChartType.AREA -> """<c:areaChart><c:grouping val="standard"/><c:varyColors val="0"/>"""
                    ChartType.RADAR -> """<c:radarChart><c:radarStyle val="marker"/><c:varyColors val="0"/>"""
                    ChartType.BAR -> """<c:barChart><c:barDir val="bar"/><c:grouping val="clustered"/><c:varyColors val="0"/>"""
                    else -> """<c:barChart><c:barDir val="col"/><c:grouping val="clustered"/><c:varyColors val="0"/>"""
                }
                append(open)
                c.series.forEachIndexed { si, s ->
                    append("""<c:ser><c:idx val="$si"/><c:order val="$si"/>""")
                    append("""<c:tx><c:strRef><c:f>${refs?.names?.getOrNull(si) ?: "Sheet1!${'$'}${('B' + si)}${'$'}1"}</c:f><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>${esc(s.name)}</c:v></c:pt></c:strCache></c:strRef></c:tx>""")
                    val fillOrLine = if (type == ChartType.LINE || type == ChartType.RADAR) """<a:ln><a:solidFill><a:srgbClr val="${pal[si % pal.size]}"/></a:solidFill></a:ln>""" else """<a:solidFill><a:srgbClr val="${pal[si % pal.size]}"/></a:solidFill>"""
                    append("""<c:spPr>$fillOrLine</c:spPr>""")
                    append("""<c:cat>${catStrCache(cats, refs?.cat)}</c:cat>""")
                    append("""<c:val>${valNumCache(s.values.toList(), si, refs?.values?.getOrNull(si))}</c:val>""")
                    if (type == ChartType.LINE) append("""<c:smooth val="0"/>""")
                    append("</c:ser>")
                }
                if (c.showValues) append(VALUE_LABELS)
                if (type == ChartType.BAR || type == ChartType.COLUMN) append("""<c:gapWidth val="150"/>""")
                append("""<c:axId val="111111111"/><c:axId val="222222222"/>""")
                append(when (type) { ChartType.LINE -> "</c:lineChart>"; ChartType.AREA -> "</c:areaChart>"; ChartType.RADAR -> "</c:radarChart>"; else -> "</c:barChart>" })
                // A bar chart's category axis is the vertical one (left), its value axis runs along the bottom.
                val bar = type == ChartType.BAR
                append(axisXml("catAx", "111111111", if (bar) "l" else "b", "222222222", grid = false, title = c.catAxisTitle))
                append(axisXml("valAx", "222222222", if (bar) "b" else "l", "111111111", grid = c.showGrid, title = c.valAxisTitle, value = c))
            }
        }
        append("</c:plotArea>")
        if (c.showLegend) {
            val pos = when (c.legendPos) {
                com.therealityteam.aeropages.model.LegendPos.TOP -> "t"
                com.therealityteam.aeropages.model.LegendPos.LEFT -> "l"
                com.therealityteam.aeropages.model.LegendPos.RIGHT -> "r"
                com.therealityteam.aeropages.model.LegendPos.BOTTOM -> "b"
            }
            append("""<c:legend><c:legendPos val="$pos"/><c:overlay val="0"/></c:legend>""")
        }
        append("""<c:plotVisOnly val="1"/></c:chart></c:chartSpace>""")
    }

    // --- fixed minimal master / layout / theme ---------------------------------

    /**
     * A slide master: the deck's master background and title / body text defaults and — master
     * [number] (0-based) drawing a [design] — the design's name, its picture as the background (else
     * its colour) and what it draws, under the placeholders; [blips] embed its pictures, the
     * background's first.
     */
    private fun slideMasterXml(m: com.therealityteam.aeropages.model.SlideMaster, d: Emu, number: Int = 0,
                               design: com.therealityteam.aeropages.model.SlideDesign? = null, blips: List<String> = emptyList()): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">""")
        append(if (design == null) "<p:cSld>" else """<p:cSld name="${esc(design.name)}">""")
        var blip = 0
        val picture = design?.picture
        if (picture != null) {
            append("""<p:bg><p:bgPr><a:blipFill>${blips[blip++]}${com.therealityteam.aeropages.format.PictureCrop.srcRect(picture)}<a:stretch><a:fillRect/></a:stretch></a:blipFill><a:effectLst/></p:bgPr></p:bg>""")
        } else if (design?.background != null) append(bgXml(design.background!!, design.backgroundShade))
        else m.background?.let { append(bgXml(it, m.backgroundShade)) }
        append("""<p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""")
        // What the design draws, in its order (2 and 3 are the placeholders' ids).
        var nextId = 4
        design?.items?.forEach { item ->
            val id = nextId++
            when (item) {
                is com.therealityteam.aeropages.model.DesignShape -> append(freeShapeXml(id, item.shape, emptyMap(), d, backdrop = (design.background ?: m.background ?: 0xFFFFFFFF).takeIf { design.picture == null }))
                is com.therealityteam.aeropages.model.DesignPicture -> {
                    val img = item.image
                    val x = (img.xPct * d.cx).toInt(); val y = (img.yPct * d.cy).toInt()
                    val cx = (img.wPct * d.cx).toInt().coerceAtLeast(1); val cy = (img.hPct * d.cy).toInt().coerceAtLeast(1)
                    append("""<p:pic><p:nvPicPr><p:cNvPr id="$id" name="Picture $id"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr userDrawn="1"/></p:nvPicPr>""")
                    append("""<p:blipFill>${blips[blip++]}${com.therealityteam.aeropages.format.PictureCrop.srcRect(img)}<a:stretch><a:fillRect/></a:stretch></p:blipFill>""")
                    append("""<p:spPr><a:xfrm${turnAttrs(img)}><a:off x="$x" y="$y"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>${effectXml(img.shadow)}</p:spPr></p:pic>""")
                }
            }
        }
        // The master's own title and text placeholders, which the layouts' take their styles from.
        val content = com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT
        append(placeholderXml(2, "Title Placeholder 1", """type="title"""", com.therealityteam.aeropages.model.BoxFrame.defaultFor(content, true), d, "ctr", TITLE_PROMPT))
        append(placeholderXml(3, "Text Placeholder 2", """type="body" idx="1"""", com.therealityteam.aeropages.model.BoxFrame.defaultFor(content, false), d, "t", BODY_PROMPT))
        // Where a slide's date, footer and number go (PowerPoint's Header & Footer adds them there).
        append(chromePlaceholders(nextId, d, framed = true, m.chrome + (design?.chrome ?: emptyMap())))
        append("""</p:spTree></p:cSld>""")
        append("""<p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>""")
        append("<p:sldLayoutIdLst>")
        LAYOUTS.indices.forEach { k -> append("""<p:sldLayoutId id="${masterId(number) + 1 + k}" r:id="rId${k + 1}"/>""") }
        append("</p:sldLayoutIdLst>")
        append("<p:txStyles>")
        append("<p:titleStyle>").append(masterLevel(m.titleSizePt, m.titleBold, m.titleColor, m.titleFont)).append("</p:titleStyle>")
        append("<p:bodyStyle>").append(masterLevel(m.bodySizePt, null, m.bodyColor, m.bodyFont)).append("</p:bodyStyle>")
        // A shape's or text box's text in the theme's body font, as the app shows unset text.
        append("""<p:otherStyle><a:lvl1pPr><a:defRPr><a:solidFill><a:schemeClr val="tx1"/></a:solidFill><a:latin typeface="+mn-lt"/></a:defRPr></a:lvl1pPr></p:otherStyle>""")
        append("</p:txStyles></p:sldMaster>")
    }

    /** One `a:lvl1pPr/a:defRPr` of the master text styles; absent attributes keep PowerPoint's defaults. */
    private fun masterLevel(sizePt: Float?, bold: Boolean?, color: Long?, font: String?): String = buildString {
        append("<a:lvl1pPr><a:defRPr")
        sizePt?.let { append(""" sz="${(it * 100).toInt()}"""") }
        if (bold == true) append(""" b="1"""")
        append(">")
        // No colour of the master's: the colour map's text colour, which a dark slide's map turns light.
        append(color?.let { """<a:solidFill><a:srgbClr val="${hex(it)}"/></a:solidFill>""" } ?: """<a:solidFill><a:schemeClr val="tx1"/></a:solidFill>""")
        font?.let { append("""<a:latin typeface="${esc(it)}"/>""") }
        append("</a:defRPr></a:lvl1pPr>")
    }

    /**
     * A slide layout part: its `type` and name as PowerPoint's default template has them, the
     * app's layout it stands for, and its title and body placeholders' `p:ph` attributes.
     */
    private class LayoutPart(val type: String, val name: String, val layout: com.therealityteam.aeropages.model.SlideLayout, val titlePh: String?, val bodyPh: String?)

    /**
     * The layouts a deck is written with — Title Slide, Title and Content, Section Header, Title
     * Only and Blank — so PowerPoint shows each slide's layout and a slide added (or reset) there
     * gets the placeholders, where the app puts them.
     */
    private val LAYOUTS = listOf(
        LayoutPart("title", "Title Slide", com.therealityteam.aeropages.model.SlideLayout.TITLE, """type="ctrTitle"""", """type="subTitle" idx="1""""),
        LayoutPart("obj", "Title and Content", com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT, """type="title"""", """idx="1""""),
        LayoutPart("secHead", "Section Header", com.therealityteam.aeropages.model.SlideLayout.SECTION, """type="title"""", """type="body" idx="1""""),
        LayoutPart("titleOnly", "Title Only", com.therealityteam.aeropages.model.SlideLayout.BLANK, """type="title"""", null),
        LayoutPart("blank", "Blank", com.therealityteam.aeropages.model.SlideLayout.BLANK, null, null),
    )

    /** The layout part (1-based) [s] is written against: a blank slide with a title is Title Only. */
    private fun layoutNumber(s: Slide): Int = when (s.layout) {
        com.therealityteam.aeropages.model.SlideLayout.TITLE -> 1
        com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT -> 2
        com.therealityteam.aeropages.model.SlideLayout.SECTION -> 3
        com.therealityteam.aeropages.model.SlideLayout.BLANK -> if (s.title.text.isNotBlank()) 4 else 5
    }

    private const val TITLE_PROMPT = "Click to edit Master title style"
    private const val BODY_PROMPT = "Click to edit Master text styles"

    private fun layoutXml(l: LayoutPart, d: Emu, m: com.therealityteam.aeropages.model.SlideMaster): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="${l.type}" preserve="1">""")
        append("""<p:cSld name="${l.name}"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>""")
        // A title slide centres its title and subtitle; a section header's text has no bullets. Text
        // the slide leaves unsized is the size the app shows it (a title 40 pt, other text 22 pt) in
        // the theme's fonts, where the master does not say otherwise — PowerPoint's own would be 18 pt.
        val title = l.layout == com.therealityteam.aeropages.model.SlideLayout.TITLE
        val titleRun = defRPr(if (m.titleSizePt == null) DEFAULT_TITLE_PT else null, if (m.titleFont == null) "+mj-lt" else null)
        val bodyRun = defRPr(if (m.bodySizePt == null) DEFAULT_BODY_PT else null, if (m.bodyFont == null) "+mn-lt" else null)
        l.titlePh?.let { append(placeholderXml(2, "Title 1", it, com.therealityteam.aeropages.model.BoxFrame.defaultFor(l.layout, true), d, "ctr", TITLE_PROMPT,
            """<a:lvl1pPr${if (title) """ algn="ctr"""" else ""}>$titleRun</a:lvl1pPr>""")) }
        l.bodyPh?.let {
            val list = if (l.layout == com.therealityteam.aeropages.model.SlideLayout.TITLE_CONTENT) "<a:lvl1pPr>$bodyRun</a:lvl1pPr>"
                else """<a:lvl1pPr marL="0" indent="0"${if (title) """ algn="ctr"""" else ""}><a:buNone/>$bodyRun</a:lvl1pPr>"""
            append(placeholderXml(3, if (title) "Subtitle 2" else "Text Placeholder 2", it, com.therealityteam.aeropages.model.BoxFrame.defaultFor(l.layout, false), d, "t", BODY_PROMPT, list))
        }
        append(chromePlaceholders(4, d, framed = false))
        append("""</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>""")
    }

    /** The sizes the app gives a title's and a body's text that sets none (as the pptx reader reads them). */
    private const val DEFAULT_TITLE_PT = 40f
    private const val DEFAULT_BODY_PT = 22f

    /** A list level's default run: its [sizePt] and [font] (a theme font as `+mj-lt` / `+mn-lt`) where given. */
    private fun defRPr(sizePt: Float?, font: String?): String =
        "<a:defRPr" + (sizePt?.let { """ sz="${(it * 100).toInt()}"""" } ?: "") + (if (font == null) "/>" else """><a:latin typeface="${esc(font)}"/></a:defRPr>""")

    /** A master or layout placeholder at [f] of the slide, anchored [anchor], showing its [prompt]. */
    private fun placeholderXml(id: Int, name: String, ph: String, f: com.therealityteam.aeropages.model.BoxFrame, d: Emu, anchor: String, prompt: String, list: String = ""): String =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="$name"/><p:cNvSpPr><a:spLocks noGrp="1"/></p:cNvSpPr><p:nvPr><p:ph $ph/></p:nvPr></p:nvSpPr>""" +
            """<p:spPr><a:xfrm><a:off x="${(f.xPct * d.cx).toInt()}" y="${(f.yPct * d.cy).toInt()}"/><a:ext cx="${(f.wPct * d.cx).toInt()}" cy="${(f.hPct * d.cy).toInt()}"/></a:xfrm></p:spPr>""" +
            """<p:txBody><a:bodyPr anchor="$anchor"/><a:lstStyle>$list</a:lstStyle><a:p><a:r><a:rPr lang="en-US"/><a:t>$prompt</a:t></a:r></a:p></p:txBody></p:sp>"""

    /** The Office theme — its colours and its fonts (Calibri Light / Calibri, 等线 for Chinese) — for decks and documents alike. */
    internal val THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
        """<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme"><a:themeElements>""" +
        """<a:clrScheme name="Office"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>""" +
        """<a:dk2><a:srgbClr val="44546A"/></a:dk2><a:lt2><a:srgbClr val="E7E6E6"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2>""" +
        """<a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6>""" +
        """<a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>""" +
        """<a:fontScheme name="Office"><a:majorFont><a:latin typeface="Calibri Light"/><a:ea typeface=""/><a:cs typeface=""/><a:font script="Hans" typeface="等线 Light"/><a:font script="Hant" typeface="新細明體"/><a:font script="Jpan" typeface="游ゴシック Light"/><a:font script="Hang" typeface="맑은 고딕"/><a:font script="Thai" typeface="Leelawadee UI"/><a:font script="Deva" typeface="Nirmala UI"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/><a:font script="Hans" typeface="等线"/><a:font script="Hant" typeface="新細明體"/><a:font script="Jpan" typeface="游ゴシック"/><a:font script="Hang" typeface="맑은 고딕"/><a:font script="Thai" typeface="Leelawadee UI"/><a:font script="Deva" typeface="Nirmala UI"/></a:minorFont></a:fontScheme>""" +
        """<a:fmtScheme name="Office"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst>""" +
        """<a:lnStyleLst><a:ln><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln><a:ln><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst>""" +
        """<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>""" +
        """<a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>""" +
        """</a:themeElements></a:theme>"""

    private const val RELS_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"""
    private const val RELS_TAIL = "</Relationships>"

    private fun relLine(id: String, type: String, target: String) = """<Relationship Id="$id" Type="$type" Target="$target"/>"""
    private fun rel(id: String, type: String, target: String) = RELS_HEAD + relLine(id, type, target) + RELS_TAIL

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putBytes(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun hex(argb: Long): String = String.format(Locale.ROOT, "%06X", (argb and 0xFFFFFF))
    private fun esc(s: String): String = com.therealityteam.aeropages.format.OfficeXml.escape(s)
}
