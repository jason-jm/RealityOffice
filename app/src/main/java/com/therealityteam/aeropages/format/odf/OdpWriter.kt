package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.odf.OdfPackage.HEAD
import com.therealityteam.aeropages.format.odf.OdfPackage.NS
import com.therealityteam.aeropages.format.odf.OdfPackage.color
import com.therealityteam.aeropages.format.odf.OdfPackage.escape
import com.therealityteam.aeropages.format.odf.OdfPackage.pt
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.under
import java.io.OutputStream

/**
 * OpenDocument Presentation (.odp) writer: the counterpart of
 * [com.therealityteam.aeropages.format.pptx.OdpReader].
 *
 * Each slide becomes a `draw:page` carrying a title and an outline text frame (so readers pick up
 * the outline), plus the free elements laid over it: text boxes and shapes as `draw:custom-shape`
 * with the matching enhanced-geometry type, pictures as `draw:frame`/`draw:image` with the bytes
 * packaged under `Pictures/` (a video or sound as the frame's `draw:plugin`, its file under `Media/`,
 * the picture its poster), and tables as a `table:table` inside a frame. Speaker notes go into
 * `presentation:notes`; a hidden slide's page style carries `presentation:visibility="hidden"`; the
 * slide's animations are its `anim:par` timing root (see [OdfAnimations]).
 *
 * Charts are the one element left behind: ODF stores them as a separate embedded object, so a
 * chart is an embedded `Object N` (see [OdfChart]).
 */
object OdpWriter {

    /** Each slide's page name while a presentation is written ("page1" …), for links to slides. */
    private val pageNames = ThreadLocal<Map<Long, String>?>()

    /** A link as ODF keeps it in text: a slide by its page's name (`#page2`); none for one of the show's jumps, which text cannot hold. */
    private fun linkOut(link: String?): String? {
        link ?: return null
        if (link in com.therealityteam.aeropages.model.SlideLinks.JUMPS) return null
        val id = com.therealityteam.aeropages.model.SlideLinks.slideId(link) ?: return link
        return pageNames.get()?.get(id)?.let { "#$it" }
    }

    /**
     * A click's `office:event-listeners` — Impress's `presentation:event-listener` on `dom:click`: the
     * show's next, previous, first or last page, its end (`stop`), or `show` a slide (`#page2`) or an
     * address; "" for none.
     */
    private fun clickXml(action: String?, hover: String? = null): String {
        val listeners = listener("dom:click", action) + listener("dom:mouseover", hover)
        return if (listeners.isEmpty()) "" else "<office:event-listeners>$listeners</office:event-listeners>"
    }

    /** One `presentation:event-listener` doing [action] on [event] (a click, or the pointer coming over it); "" for none. */
    private fun listener(event: String, action: String?): String {
        action ?: return ""
        val step = when (action) {
            com.therealityteam.aeropages.model.SlideLinks.NEXT -> "next-page"
            com.therealityteam.aeropages.model.SlideLinks.PREVIOUS -> "previous-page"
            com.therealityteam.aeropages.model.SlideLinks.FIRST -> "first-page"
            com.therealityteam.aeropages.model.SlideLinks.LAST -> "last-page"
            com.therealityteam.aeropages.model.SlideLinks.END -> "stop"
            else -> null
        }
        val attrs = if (step != null) """presentation:action="$step"""" else {
            val href = linkOut(action) ?: return ""
            """presentation:action="show" xlink:href="${escape(href)}" xlink:type="simple" xlink:show="embed" xlink:actuate="onRequest""""
        }
        return """<presentation:event-listener script:event-name="$event" $attrs/>"""
    }

    fun write(p: Presentation, out: OutputStream) {
        pageNames.set(p.slides.mapIndexed { i, s -> s.id to "page${i + 1}" }.toMap())
        try { writePresentation(p, out) } finally { pageNames.remove() }
    }

    private fun writePresentation(p: Presentation, out: OutputStream) {
        // The page is the slide, point for point (its text is set in points).
        val w = p.slideSize.widthPt
        val h = p.slideSize.heightPt
        val styles = SlideStyles()
        val pictures = LinkedHashMap<String, ByteArray>()
        // The template's designs as master pages of their own (their styles live in styles.xml).
        val designStyles = SlideStyles("m")
        val designMasters = StringBuilder()
        val fillImages = StringBuilder()
        val masterNames = HashMap<String, String>()
        // Each master page's title and body text as Impress keeps it: its `<master>-title` … styles.
        val masterTextStyles = StringBuilder(masterText("Default", p.master.titleText(), p.master.bodyText()))
        p.designs.forEachIndexed { k, d ->
            val name = masterName(d.name, k)
            masterNames[d.name] = name
            val color = chromeColor(p.slides.filter { it.design == d.name }.map { p.backgroundOf(it) }.ifEmpty { listOf(d.background ?: p.master.background) })
            designMasters.append(designMaster(d, name, k, w, h, designStyles, pictures, fillImages, color, p.master.bodyFont, p.master.chrome + d.chrome))
            masterTextStyles.append(masterText(name, p.master.titleText().under(d.titleText), p.master.bodyText().under(d.bodyText)))
        }
        // Header & footer: the deck's footer and date declared once and named by every page, each
        // page's style switching them (and the number) on or off; the masters' frames show them.
        val chrome = p.footer.isNotBlank() || p.showSlideNumber || p.date != null
        val decls = StringBuilder()
        val dataStyles = StringBuilder()
        if (p.footer.isNotBlank()) decls.append("""<presentation:footer-decl presentation:name="$FOOTER_DECL">${escape(p.footer)}</presentation:footer-decl>""")
        p.date?.let { d ->
            val fixed = d.fixed
            if (fixed != null) decls.append("""<presentation:date-time-decl presentation:name="$DATE_DECL" presentation:source="fixed">${escape(fixed)}</presentation:date-time-decl>""")
            else {
                dataStyles.append(dateFieldStyle(d.format))
                decls.append("""<presentation:date-time-decl presentation:name="$DATE_DECL" presentation:source="current-date" style:data-style-name="$DATE_STYLE"/>""")
            }
        }
        // xml:id must be unique in the whole document: one sequence for every page.
        val idSeq = intArrayOf(0)
        val pages = buildString {
            p.slides.forEachIndexed { i, s ->
                val display = if (!chrome) null else com.therealityteam.aeropages.model.SlideChrome.shownOn(p, s).let { on ->
                    Display(on && p.date != null, on && p.footer.isNotBlank(), on && p.showSlideNumber)
                }
                val design = p.designOf(s)
                append(page(i, s, w, h, styles, pictures, p.master, idSeq, s.design?.let { masterNames[it] } ?: "Default", display,
                    footerDecl = p.footer.isNotBlank(), dateDecl = p.date != null, backdrop = p.backdropOf(s),
                    titleText = p.master.titleText().under(design?.titleText), bodyText = p.master.bodyText().under(design?.bodyText)))
            }
        }

        val parts = LinkedHashMap<String, ByteArray>()
        parts["content.xml"] = buildString {
            append(HEAD)
            append("<office:document-content $NS>")
            append("<office:automatic-styles>").append(styles.render()).append(dataStyles).append("</office:automatic-styles>")
            append("<office:body><office:presentation>").append(decls).append(pages).append(showSettings(p)).append("</office:presentation></office:body>")
            append("</office:document-content>")
        }.toByteArray(Charsets.UTF_8)
        // The page layouts the pages name, among the named styles.
        val layouts = p.slides.map { PageLayout.of(it) }.distinct().joinToString("") { it.xml(w, h) }
        val onDefault = p.slides.filter { s -> s.design == null || s.design !in masterNames }.map { p.backgroundOf(it) }.ifEmpty { listOf(p.master.background) }
        val defaultChrome = chromeFrames(w, h, designStyles, chromeColor(onDefault), p.master.bodyFont, p.master.chrome)
        // The master page's fill (its gradient among the named styles, so worked out first).
        val masterFill = p.master.background?.let { designStyles.backgroundFill(it, p.master.backgroundShade) } ?: """draw:fill="none""""
        parts["styles.xml"] = styles(masterFill, w, h, styles.renderNamed() + designStyles.renderNamed() + fillImages + layouts + masterTextStyles,
            designStyles.render(), designMasters.toString(), placeholders("Default", w, h) + defaultChrome).toByteArray(Charsets.UTF_8)
        parts["meta.xml"] = com.therealityteam.aeropages.format.OdfMeta.write(p.props)
        pictures.forEach { (name, bytes) -> parts[name] = bytes }
        // The manifest lists a video or a sound with its own type.
        val types = p.slides.flatMap { it.images }.mapNotNull { it.media }.mapNotNull { m -> m.data?.let { d -> pictures.entries.firstOrNull { it.value === d && it.key.startsWith(MEDIA_DIR) }?.key?.to(m.mime) } }.toMap()
        OdfPackage.write(out, OdfPackage.PRESENTATION, parts, types)
    }

    /**
     * Master page [master]'s text styles, as Impress names them: `-title` ([title]'s look), `-subtitle`
     * and `-outline1` ([body]'s), `-outline2` … `-outline9` taking the first level's — each only with
     * what the master sets (a colour it leaves unset is LibreOffice's automatic one).
     */
    private fun masterText(master: String, title: com.therealityteam.aeropages.model.MasterText, body: com.therealityteam.aeropages.model.MasterText): String = buildString {
        fun look(t: com.therealityteam.aeropages.model.MasterText): String = buildString {
            t.sizePt?.let { append(""" fo:font-size="${pt(it)}" style:font-size-asian="${pt(it)}" style:font-size-complex="${pt(it)}"""") }
            t.color?.let { append(""" fo:color="${color(it)}"""") }
            t.font?.let { append(""" fo:font-family="${escape(it)}"""") }
            if (t.bold == true) append(""" fo:font-weight="bold" style:font-weight-asian="bold" style:font-weight-complex="bold"""")
        }
        fun style(suffix: String, t: com.therealityteam.aeropages.model.MasterText, anchor: String) {
            val text = look(t)
            append("""<style:style style:name="${escape("$master-$suffix")}" style:family="presentation">""")
            append("""<style:graphic-properties draw:fill="none" draw:stroke="none" draw:auto-grow-height="false" draw:textarea-vertical-align="$anchor"/>""")
            if (text.isNotEmpty()) append("<style:text-properties$text/>")
            append("</style:style>")
        }
        style("title", title, "middle")
        style("subtitle", body, "middle")
        style("outline1", body, "top")
        for (level in 2..9) append("""<style:style style:name="${escape("$master-outline$level")}" style:family="presentation" style:parent-style-name="${escape("$master-outline${level - 1}")}"/>""")
    }

    /** Master page [master]'s title and outline placeholders — what its pages' take their look from — where the layout puts them. */
    private fun placeholders(master: String, w: Float, h: Float): String = buildString {
        val content = SlideLayout.TITLE_CONTENT
        for ((cls, style, title) in listOf(Triple("title", "$master-title", true), Triple("outline", "$master-outline1", false))) {
            val f = com.therealityteam.aeropages.model.BoxFrame.defaultFor(content, title)
            append("""<draw:frame presentation:style-name="${escape(style)}" draw:layer="backgroundobjects" svg:width="${pt(f.wPct * w)}" svg:height="${pt(f.hPct * h)}" """)
            append("""svg:x="${pt(f.xPct * w)}" svg:y="${pt(f.yPct * h)}" presentation:class="$cls" presentation:placeholder="true"><draw:text-box/></draw:frame>""")
        }
    }

    private const val FOOTER_DECL = "ftr1"
    private const val DATE_DECL = "dtd1"
    private const val DATE_STYLE = "DateField1"

    /**
     * The data style a date that updates is shown in: LibreOffice draws only its own few (in its
     * locale's order) — digits, the month's name, the weekday too — so the one of [format]'s kind, as
     * LibreOffice writes it, with [format] itself as its title for the app to read back.
     */
    private fun dateFieldStyle(format: String): String {
        val body = when (com.therealityteam.aeropages.model.SlideDate.kindOf(format)) {
            com.therealityteam.aeropages.model.DateKind.SHORT ->
                """<number:day number:style="long"/><number:text>.</number:text><number:month number:style="long"/><number:text>.</number:text><number:year number:style="long"/>"""
            com.therealityteam.aeropages.model.DateKind.LONG ->
                """<number:day/><number:text>. </number:text><number:month number:style="long" number:textual="true"/><number:text> </number:text><number:year number:style="long"/>"""
            com.therealityteam.aeropages.model.DateKind.WEEKDAY ->
                """<number:day-of-week number:style="long"/><number:text>, </number:text><number:day/><number:text>. </number:text><number:month number:style="long" number:textual="true"/><number:text> </number:text><number:year number:style="long"/>"""
        }
        val order = if (com.therealityteam.aeropages.model.SlideDate.kindOf(format) == com.therealityteam.aeropages.model.DateKind.WEEKDAY) """ number:automatic-order="true"""" else ""
        return """<number:date-style style:name="$DATE_STYLE" number:title="${escape(format)}"$order>$body</number:date-style>"""
    }

    /** Which of the date, footer and number a page's style displays. */
    private data class Display(val date: Boolean, val footer: Boolean, val number: Boolean)

    /**
     * The colour a master's date, footer and number are written in — one for all its pages, whose
     * [backgrounds] the app reads them over each: what reads over them all, a mid grey when some are
     * light and some dark.
     */
    private fun chromeColor(backgrounds: List<Long?>): Long {
        val tones = backgrounds.map { com.therealityteam.aeropages.model.SlideChrome.colorOver(it) }.distinct()
        return if (tones.size > 1) 0xFF8C8C8C else com.therealityteam.aeropages.model.SlideChrome.solidOver(backgrounds.firstOrNull())
    }

    /**
     * A master page's date, footer and number frames, where [com.therealityteam.aeropages.model.SlideChrome]
     * puts them — the fields the pages fill in — in its text size, aligned as there, in [color] and
     * [font] (the deck's body font, else a sans serif as the app shows them).
     */
    private fun chromeFrames(w: Float, h: Float, styles: SlideStyles, color: Long, font: String?,
                             looks: Map<com.therealityteam.aeropages.model.SlideChrome.Part, com.therealityteam.aeropages.model.ChromeLook> = emptyMap()): String = buildString {
        for (part in com.therealityteam.aeropages.model.SlideChrome.Part.entries) {
            // The master's own look where it has one (a template's), its colour automatic where it sets none.
            val look = looks[part]
            val (cls, field) = when (part) {
                com.therealityteam.aeropages.model.SlideChrome.Part.DATE -> "date-time" to "<presentation:date-time/>"
                com.therealityteam.aeropages.model.SlideChrome.Part.FOOTER -> "footer" to "<presentation:footer/>"
                com.therealityteam.aeropages.model.SlideChrome.Part.NUMBER -> "page-number" to "<text:page-number>&lt;number&gt;</text:page-number>"
            }
            // The frame's own style carries its text's look, as Impress's masters do (a field takes it from there).
            val (frame, text) = styles.chrome(look?.align ?: part.align, look?.sizePt ?: com.therealityteam.aeropages.model.SlideChrome.SIZE_PT,
                if (look != null) look.color else color, font ?: "Liberation Sans")
            val fx = look?.xPct ?: part.xPct; val fy = look?.yPct ?: part.yPct; val fw = look?.wPct ?: part.wPct; val fh = look?.hPct ?: part.hPct
            append("""<draw:frame presentation:style-name="$frame" draw:text-style-name="$text" draw:layer="backgroundobjects" svg:width="${pt(fw * w)}" svg:height="${pt(fh * h)}" """)
            append("""svg:x="${pt(fx * w)}" svg:y="${pt(fy * h)}" presentation:class="$cls"><draw:text-box><text:p>$field</text:p></draw:text-box></draw:frame>""")
        }
    }

    /** A design's master page name: its own when it is a style name as ODF allows, else a made one. */
    private fun masterName(name: String, k: Int): String =
        if (name.isNotEmpty() && name != "Default" && name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' } && !name[0].isDigit()) name else "Design${k + 1}"

    /**
     * Design [d] as a master page named [name]: its fill (a colour, or its picture as a stretched
     * bitmap fill named in [fillImages]) and its pictures and shapes on Impress's background layer.
     */
    private fun designMaster(d: com.therealityteam.aeropages.model.SlideDesign, name: String, k: Int, w: Float, h: Float, styles: SlideStyles,
                             pictures: LinkedHashMap<String, ByteArray>, fillImages: StringBuilder, chromeColor: Long, font: String?,
                             looks: Map<com.therealityteam.aeropages.model.SlideChrome.Part, com.therealityteam.aeropages.model.ChromeLook> = emptyMap()): String = buildString {
        val fill = d.picture?.let { pic ->
            val path = "Pictures/design${k + 1}.${OdfPackage.imageExt(pic.mime)}"
            pictures[path] = pic.data
            fillImages.append("""<draw:fill-image draw:name="DesignFill${k + 1}" xlink:href="$path" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/>""")
            """draw:fill="bitmap" draw:fill-image-name="DesignFill${k + 1}" style:repeat="stretch""""
        } ?: d.background?.let { styles.backgroundFill(it, d.backgroundShade) } ?: """draw:fill="none""""
        val pageStyle = styles.masterPage(fill)
        append("""<style:master-page style:name="$name" style:page-layout-name="PM1" draw:style-name="$pageStyle">""")
        for (item in d.items) when (item) {
            is com.therealityteam.aeropages.model.DesignPicture -> append(image(item.image, w, h, pictures, styles, layer = "backgroundobjects"))
            is com.therealityteam.aeropages.model.DesignShape -> item.shape.let { sh ->
                append(shapeXml(sh, sh.xPct * w, sh.yPct * h, sh.wPct * w, sh.hPct * h, styles, """ draw:layer="backgroundobjects""""))
            }
        }
        append(placeholders(name, w, h))
        append(chromeFrames(w, h, styles, chromeColor, font, looks))
        append("</style:master-page>")
    }

    /**
     * What a page's placeholder text needs beyond what its master's style gives it: the colour, where
     * the master's (inherited, [d]) would not read over [backdrop], that does; else nothing (without
     * one, LibreOffice's automatic colour reads over any page).
     */
    private fun turned(d: com.therealityteam.aeropages.model.MasterText, backdrop: Long?): com.therealityteam.aeropages.model.MasterText {
        val shown = d.color?.let { com.therealityteam.aeropages.model.TextColors.over(it, backdrop) }?.takeIf { it != d.color }
        return com.therealityteam.aeropages.model.MasterText(null, null, shown, null)
    }

    /**
     * Set Up Slide Show as Impress keeps it (`presentation:settings`, after the pages): looping, a kiosk (looping with a pause
     * between rounds), timings ignored, in a window, without animations, the page a range starts at (Impress has no end
     * page) or the custom show to play — and the custom shows, their slides by page name. Nothing for a deck shown as it is.
     */
    private fun showSettings(p: Presentation): String {
        val s = p.show
        val custom = s.custom?.takeIf { name -> p.customShows.any { it.name == name } }
        val attrs = buildString {
            if (custom != null) append(""" presentation:show="${escape(custom)}"""")
            else if (s.from > 1) append(""" presentation:start-page="page${s.from.coerceAtMost(p.slides.size)}"""")
            if (p.loopShow || s.type == com.therealityteam.aeropages.model.ShowType.KIOSK) append(""" presentation:endless="true"""")
            if (s.type == com.therealityteam.aeropages.model.ShowType.KIOSK) append(""" presentation:pause="PT0S"""")
            if (!p.useTimings) append(""" presentation:force-manual="true"""")
            if (s.type == com.therealityteam.aeropages.model.ShowType.WINDOW) append(""" presentation:full-screen="false"""")
            if (!s.animations) append(""" presentation:animations="disabled"""")
        }
        val shows = p.customShows.joinToString("") { cs ->
            val pages = cs.slides.mapNotNull { id -> p.slides.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { "page${it + 1}" } }
            """<presentation:show presentation:name="${escape(cs.name)}" presentation:pages="${pages.joinToString(",")}"/>"""
        }
        return if (attrs.isEmpty() && shows.isEmpty()) "" else "<presentation:settings$attrs>$shows</presentation:settings>"
    }

    private fun page(index: Int, s: Slide, w: Float, h: Float, styles: SlideStyles, pictures: LinkedHashMap<String, ByteArray>, master: com.therealityteam.aeropages.model.SlideMaster, idSeq: IntArray,
                     masterPage: String = "Default", display: Display? = null, footerDecl: Boolean = false, dateDecl: Boolean = false, backdrop: Long? = null,
                     titleText: com.therealityteam.aeropages.model.MasterText = master.titleText(), bodyText: com.therealityteam.aeropages.model.MasterText = master.bodyText()): String = buildString {
        val shows = display?.let { """ presentation:display-footer="${it.footer}" presentation:display-page-number="${it.number}" presentation:display-date-time="${it.date}"""" } ?: ""
        val pageStyle = styles.page(s.background, s.transition, s.transitionSpeed, s.hidden, hideDesign = !s.designObjects, display = shows, shade = s.backgroundShade, dir = s.transitionDir)
        val layout = PageLayout.of(s)
        val uses = (if (footerDecl) """ presentation:use-footer-name="$FOOTER_DECL"""" else "") + (if (dateDecl) """ presentation:use-date-time-name="$DATE_DECL"""" else "")
        append("""<draw:page draw:name="page${index + 1}" draw:style-name="$pageStyle" draw:master-page-name="$masterPage" presentation:presentation-page-layout-name="${layout.styleName}"$uses>""")
        // Animated elements carry an id (xml:id and, for older readers, draw:id) their effects name;
        // the paragraphs a box builds one by one carry their own (xml:id and text:id), as Impress writes them.
        val units = com.therealityteam.aeropages.model.AnimTimeline.units(com.therealityteam.aeropages.model.AnimTimeline.entriesOf(s))
        val ids = java.util.IdentityHashMap<com.therealityteam.aeropages.model.AnimTarget, String>()
        val paragraphIds = java.util.IdentityHashMap<com.therealityteam.aeropages.model.AnimTarget, MutableMap<Int, String>>()
        units.filter { it.paragraph >= 0 }.forEach { u -> paragraphIds.getOrPut(u.target) { HashMap() }.getOrPut(u.paragraph) { "id${++idSeq[0]}" } }
        fun idOf(t: com.therealityteam.aeropages.model.AnimTarget): String =
            if (t.anims.isEmpty()) "" else ids.getOrPut(t) { "id${++idSeq[0]}" }.let { """ xml:id="$it" draw:id="$it"""" }

        // Placeholder geometry: the box's own frame, else where the flow layout shows it. A layout's
        // empty placeholders are written as Impress writes them (presentation:placeholder, no text),
        // so they come back — and LibreOffice asks for their text.
        val tf = s.title.frame ?: com.therealityteam.aeropages.model.BoxFrame.defaultFor(s.layout, true)
        if (s.title.text.isNotEmpty() || layout.title) {
            append(textFrame(s.title, "title", tf.xPct * w, tf.yPct * h, tf.wPct * w, tf.hPct * h, styles, turned(titleText, backdrop), idOf(s.title), paragraphIds[s.title],
                parent = "$masterPage-title"))
        }
        val bf = s.body.frame ?: com.therealityteam.aeropages.model.BoxFrame.defaultFor(s.layout, false)
        if ((s.body.text.isNotEmpty() || layout.body != null) && !s.contentTaken) {
            val cls = layout.body ?: "outline"
            append(textFrame(s.body, cls, bf.xPct * w, bf.yPct * h, bf.wPct * w, bf.hPct * h, styles, turned(bodyText, backdrop), idOf(s.body), paragraphIds[s.body],
                parent = if (cls == "outline") "$masterPage-outline1" else "$masterPage-$cls"))
        }
        s.images.forEach { append(image(it, w, h, pictures, styles, idOf(it))) }
        s.shapes.forEach { append(shape(it, w, h, styles, idOf(it.box) + objAttrs(it), paragraphIds[it.box])) }
        s.tables.forEach { append(table(it, w, h, styles, idOf(it) + objAttrs(it))) }
        // Charts: one embedded chart object each (the extra parts ride in the pictures map).
        s.charts.forEach { c ->
            val n = pictures.keys.count { it.startsWith("Object ") } + 1
            pictures["Object $n/content.xml"] = OdfChart.contentXml(c, c.wPct * w, c.hPct * h).toByteArray(Charsets.UTF_8)
            append(OdfChart.frame("./Object $n", c.xPct * w, c.yPct * h, c.wPct * w, c.hPct * h, ids = idOf(c) + objAttrs(c)))
        }
        // The slide's animations, of the elements (or paragraphs) written above, before its notes (ODF's order).
        append(OdfAnimations.timing(units.mapNotNull { u ->
            if (!ids.containsKey(u.target)) return@mapNotNull null        // the element was not written
            val id = if (u.paragraph >= 0) paragraphIds[u.target]?.get(u.paragraph) else ids[u.target]
            id?.let { OdfAnimations.Unit(it, u.anim, u.start, u.paragraph >= 0) }
        }))
        if (s.notes.isNotBlank()) {
            append("<presentation:notes>")
            append("""<draw:frame presentation:class="notes" svg:width="${pt(w * 0.8f)}" svg:height="${pt(h * 0.5f)}" svg:x="${pt(w * 0.1f)}" svg:y="${pt(h * 0.5f)}">""")
            append("<draw:text-box>")
            s.notes.split('\n').forEach { append("<text:p>${escape(it)}</text:p>") }
            append("</draw:text-box></draw:frame></presentation:notes>")
        }
        // Comments last, as LibreOffice writes them: each an annotation where it is pinned; a reply one of its own after
        // its thread's first (LibreOffice keeps no threads), marked as a reply for this app.
        for (c in s.comments) {
            append(annotation(c, w, h, reply = false))
            c.replies.forEach { append(annotation(it.copy(x = c.x, y = c.y), w, h, reply = true)) }
        }
        append("</draw:page>")
    }

    /**
     * [defaults] (the slide master's text style) fill in what the box's own runs leave unset, since
     * ODP has no master text styles of ours. A classed box with no text is an empty placeholder.
     */
    private fun textFrame(box: SlideBox, cls: String?, x: Float, y: Float, w: Float, h: Float, styles: SlideStyles, defaults: com.therealityteam.aeropages.model.MasterText? = null, ids: String = "", paragraphIds: Map<Int, String>? = null,
                          parent: String? = null): String = buildString {
        val empty = cls != null && box.text.isEmpty()
        // A placeholder: its presentation style (its master's for its class, its parent), placed where it
        // is (not where its layout would put it).
        val style = if (cls == null) """draw:style-name="${styles.graphic(null, null)}""""
            else """presentation:style-name="${styles.placeholder(if (cls == "title") com.therealityteam.aeropages.model.ShapeAnchor.CENTER else com.therealityteam.aeropages.model.ShapeAnchor.TOP, parent)}""""
        val classAttr = if (cls == null) "" else """ presentation:class="$cls" presentation:user-transformed="true""""
        append("""<draw:frame $style$ids draw:layer="layout"$classAttr${if (empty) """ presentation:placeholder="true"""" else ""} """)
        append("""svg:width="${pt(w)}" svg:height="${pt(h)}" svg:x="${pt(x)}" svg:y="${pt(y)}">""")
        if (empty) { append("<draw:text-box/></draw:frame>"); return@buildString }
        append("<draw:text-box>")
        append(boxParagraphs(box, styles, defaults, paragraphIds))
        append("</draw:text-box></draw:frame>")
    }

    /**
     * A page's layout as Impress keeps it (`style:presentation-page-layout`): the placeholders the
     * layout has — a title, a subtitle or an outline — at the flow layout's places. Impress picks
     * its autolayout from them, so LibreOffice shows each page's layout and asks for an empty
     * placeholder's text; [styleName] follows its AL…T<autolayout> names, the section header's is
     * the app's own (Impress has none: it reads as a title slide).
     */
    internal enum class PageLayout(val styleName: String, val layout: SlideLayout, val title: Boolean, val body: String?) {
        TITLE("AL1T0", SlideLayout.TITLE, true, "subtitle"),
        CONTENT("AL2T1", SlideLayout.TITLE_CONTENT, true, "outline"),
        SECTION(SECTION_LAYOUT, SlideLayout.SECTION, true, "subtitle"),
        TITLE_ONLY("AL3T19", SlideLayout.BLANK, true, null),
        BLANK("AL4T20", SlideLayout.BLANK, false, null);

        fun xml(w: Float, h: Float): String = buildString {
            append("""<style:presentation-page-layout style:name="$styleName">""")
            fun ph(obj: String, f: com.therealityteam.aeropages.model.BoxFrame) =
                append("""<presentation:placeholder presentation:object="$obj" svg:x="${pt(f.xPct * w)}" svg:y="${pt(f.yPct * h)}" svg:width="${pt(f.wPct * w)}" svg:height="${pt(f.hPct * h)}"/>""")
            if (title) ph("title", com.therealityteam.aeropages.model.BoxFrame.defaultFor(layout, true))
            body?.let { ph(it, com.therealityteam.aeropages.model.BoxFrame.defaultFor(layout, false)) }
            append("</style:presentation-page-layout>")
        }

        companion object {
            /** [s]'s page layout: a blank slide with a title is Title Only. */
            fun of(s: Slide): PageLayout = when (s.layout) {
                SlideLayout.TITLE -> TITLE
                SlideLayout.TITLE_CONTENT -> CONTENT
                SlideLayout.SECTION -> SECTION
                SlideLayout.BLANK -> if (s.title.text.isNotBlank()) TITLE_ONLY else BLANK
            }
        }
    }

    /** The page layout name a section header is written under. */
    internal const val SECTION_LAYOUT = "AeroSection"

    /** The box's lines as paragraphs; [paragraphIds] gives the ids of those an effect builds one by one. */
    private fun boxParagraphs(box: SlideBox, styles: SlideStyles, defaults: com.therealityteam.aeropages.model.MasterText? = null, paragraphIds: Map<Int, String>? = null): String = buildString {
        val fmt = (box.paragraph.runs.firstOrNull()?.format ?: CharacterFormat()).copyOf()
        if (defaults != null) {
            if (fmt.color == null) fmt.color = defaults.color
            if (fmt.fontFamily == null) fmt.fontFamily = defaults.font
            if (fmt.fontSizePt == null) fmt.fontSizePt = defaults.sizePt
            if (defaults.bold == true) fmt.bold = true
        }
        val style = styles.paragraph(box.align, fmt, spacing = box.lineSpacing)
        // One text:p per line; runs whose look differs from the paragraph's become text:span (+ text:a for links).
        // The tabs a line starts with are its level: a nested list in a list, an indent otherwise.
        val lines = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
        val levels = ArrayList<Int>().apply { add(0) }
        var atLineStart = true
        for (run in box.paragraph.runs) {
            run.text.split('\n').forEachIndexed { i, whole ->
                if (i > 0) { lines.add(StringBuilder()); levels.add(0); atLineStart = true }
                var piece = whole
                if (atLineStart) {
                    val tabs = piece.takeWhile { it == '\t' }.length.coerceAtMost(com.therealityteam.aeropages.model.SlideLevels.MAX - levels.last())
                    levels[levels.lastIndex] += tabs
                    piece = piece.substring(tabs)
                }
                if (piece.isEmpty()) return@forEachIndexed
                atLineStart = false
                val spanStyle = styles.text(run.format, fmt, defaults)
                // A line break inside the line is ODF's text:line-break.
                val text = piece.split(com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK).joinToString("<text:line-break/>") { escape(it) }
                val inner = if (spanStyle == null) text else """<text:span text:style-name="$spanStyle">$text</text:span>"""
                val link = linkOut(run.format.link)
                lines.last().append(if (link.isNullOrBlank()) inner else """<text:a xlink:type="simple" xlink:href="${escape(link)}">$inner</text:a>""")
            }
        }
        fun id(i: Int) = paragraphIds?.get(i)?.let { """ xml:id="$it" text:id="$it"""" } ?: ""
        if (box.listStyle == SlideListStyle.NONE) {
            lines.forEachIndexed { i, it ->
                val own = if (levels[i] > 0) styles.paragraph(box.align, fmt, levels[i], box.lineSpacing) else style
                append("""<text:p${id(i)} text:style-name="$own">$it</text:p>""")
            }
        } else {
            // Lines nest as the lists of their levels, each deeper line inside the item before it.
            fun list(from: Int, level: Int): Int {
                append(if (level == 0) """<text:list text:style-name="${styles.list(box.listStyle)}">""" else "<text:list>")
                var i = from
                while (i < lines.size && levels[i] >= level) {
                    append("<text:list-item>")
                    if (levels[i] == level) { append("""<text:p${id(i)} text:style-name="$style">${lines[i]}</text:p>"""); i++ }
                    if (i < lines.size && levels[i] > level) i = list(i, level + 1)
                    append("</text:list-item>")
                }
                append("</text:list>")
                return i
            }
            list(0, 0)
        }
    }

    /** A slide comment as LibreOffice's annotation: its place and marker size, author, initials, date (UTC, no zone) and lines. */
    private fun annotation(c: com.therealityteam.aeropages.model.SlideComment, w: Float, h: Float, reply: Boolean): String = buildString {
        val initials = c.author.split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)) }.take(4)
        val date = java.time.Instant.ofEpochMilli(c.dateMs).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ROOT))
        append("""<officeooo:annotation svg:x="${pt(c.x * w)}" svg:y="${pt(c.y * h)}" svg:width="${pt(20f)}" svg:height="${pt(14f)}"""")
        if (reply) append(""" aero:reply="true"""")
        if (c.resolved) append(""" aero:resolved="true"""")
        append(">")
        append("<dc:creator>${escape(c.author)}</dc:creator><meta:creator-initials>${escape(initials)}</meta:creator-initials>")
        if (c.dateMs > 0) append("<dc:date>$date</dc:date>")
        c.text.split('\n').forEach { append("<text:p>${escape(it)}</text:p>") }
        append("</officeooo:annotation>")
    }

    private fun image(img: SlideImage, w: Float, h: Float, pictures: LinkedHashMap<String, ByteArray>, styles: SlideStyles, ids: String = "", layer: String = "layout"): String {
        val name = "Pictures/image${pictures.size + 1}.${OdfPackage.imageExt(img.mime)}"
        pictures[name] = img.data
        // A crop is the frame's graphic style's fo:clip, in lengths of the picture's natural size; a shadow
        // and a mirroring (style:mirror, as LibreOffice writes a flipped picture) are the style's too.
        val clip = com.therealityteam.aeropages.format.PictureCrop.foClip(img, img.data)
        // Impress mirrors a picture only left to right: one flipped top to bottom is turned half round and
        // mirrored, as LibreOffice saves it.
        val turn = if (img.flipV) img.rotationDeg + 180f else img.rotationDeg
        val mirror = "horizontal".takeIf { img.flipH != img.flipV }
        val clipStyle = if (clip != null || img.shadow != null || mirror != null || img.decorative)
            """draw:style-name="${styles.picture(clip, img.shadow, mirror, img.decorative)}" """ else ""
        // Placed by svg:x / svg:y, or — turned — by its transform alone, as a turned shape.
        val x = img.xPct * w; val y = img.yPct * h; val fw = img.wPct * w; val fh = img.hPct * h
        val place = transform(((turn % 360f) + 360f) % 360f, x, y, fw, fh).ifEmpty { """ svg:x="${pt(x)}" svg:y="${pt(y)}"""" }
        // A video or sound: the plug-in that plays it, its picture the poster shown in its place (as LibreOffice writes them).
        val media = img.media
        val named = (media?.let { """draw:name="${escape(it.name)}" """ } ?: img.objectName?.let { """draw:name="${escape(it)}" """ } ?: "") +
            (if (img.hidden) """drawooo:display="none" """ else "")
        val plugin = media?.let { m ->
            val href = m.data?.let { mediaPart(m, it, pictures) } ?: m.link ?: return@let ""
            """<draw:plugin xlink:href="${escape(href)}" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad" draw:mime-type="${escape(m.mime)}">""" +
                """<draw:param draw:name="Loop" draw:value="false"/><draw:param draw:name="Mute" draw:value="false"/><draw:param draw:name="VolumeDB" draw:value="0"/></draw:plugin>"""
        } ?: ""
        return """<draw:frame $named${clipStyle}draw:layer="$layer"$ids svg:width="${pt(fw)}" svg:height="${pt(fh)}"$place>""" +
            """$plugin<draw:image xlink:href="$name" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/>${OdfAltText.desc(img.altText)}${clickXml(img.action, img.hoverAction)}</draw:frame>"""
    }

    /** The package part ([pictures]' `Media/…`) holding a video's or sound's [data]: one for a clip however many slides show it. */
    private fun mediaPart(m: com.therealityteam.aeropages.model.SlideMedia, data: ByteArray, pictures: LinkedHashMap<String, ByteArray>): String {
        pictures.entries.firstOrNull { it.value === data && it.key.startsWith(MEDIA_DIR) }?.let { return it.key }
        val ext = m.name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && com.therealityteam.aeropages.format.MediaTypes.isMedia(m.name) }
            ?: com.therealityteam.aeropages.format.MediaTypes.extOf(m.mime)
        val part = "${MEDIA_DIR}media${pictures.keys.count { it.startsWith(MEDIA_DIR) } + 1}.$ext"
        pictures[part] = data
        return part
    }

    private const val MEDIA_DIR = "Media/"

    /** A slide object's ` draw:name` (the Selection Pane's own name) and, hidden, LibreOffice's ` drawooo:display="none"`. */
    private fun objAttrs(o: com.therealityteam.aeropages.model.SlideObject): String =
        (o.objectName?.let { """ draw:name="${escape(it)}"""" } ?: "") + (if (o.hidden) """ drawooo:display="none"""" else "")

    private fun shape(sh: SlideShape, w: Float, h: Float, styles: SlideStyles, ids: String = "", paragraphIds: Map<Int, String>? = null): String =
        shapeXml(sh, sh.xPct * w, sh.yPct * h, sh.wPct * w, sh.hPct * h, styles, """$ids draw:layer="layout"""", paragraphIds)

    /**
     * [sh] at ([x], [y]) [w] × [h] points of its page (or of the cell it sits in): a
     * `draw:custom-shape` with its geometry, flips, turn and text, a text box a `draw:frame`
     * holding a `draw:text-box` (as LibreOffice writes one), a line a `draw:line` from the corner
     * its flips say to the opposite one. [attrs] goes on the element (ids, layer, z-index).
     */
    internal fun shapeXml(sh: SlideShape, x: Float, y: Float, w: Float, h: Float, styles: SlideStyles, attrs: String = "", paragraphIds: Map<Int, String>? = null): String = buildString {
        val style = styles.shape(sh)
        // Placed by svg:x / svg:y, or — turned — by its transform alone (LibreOffice would move it by both).
        val transform = transform(sh.rotationDeg, x, y, w, h)
        val place = transform.ifEmpty { """ svg:x="${pt(x)}" svg:y="${pt(y)}"""" }
        if (sh.type == ShapeType.TEXTBOX) {
            append("""<draw:frame draw:style-name="$style"$attrs svg:width="${pt(w)}" svg:height="${pt(h)}"$place>""")
            append("<draw:text-box>").append(boxParagraphs(sh.box, styles, paragraphIds = paragraphIds)).append("</draw:text-box>")
            append(clickXml(sh.action, sh.hoverAction)).append("</draw:frame>")
            return@buildString
        }
        if (sh.type == ShapeType.LINE) {
            val x1 = if (sh.flipH) x + w else x; val x2 = if (sh.flipH) x else x + w
            val y1 = if (sh.flipV) y + h else y; val y2 = if (sh.flipV) y else y + h
            val click = clickXml(sh.action, sh.hoverAction)
            append("""<draw:line draw:style-name="$style"$attrs svg:x1="${pt(x1)}" svg:y1="${pt(y1)}" svg:x2="${pt(x2)}" svg:y2="${pt(y2)}"""")
            append(if (click.isEmpty()) "/>" else ">$click</draw:line>")
            return@buildString
        }
        append("""<draw:custom-shape draw:style-name="$style"$attrs svg:width="${pt(w)}" svg:height="${pt(h)}"$place>""")
        append(clickXml(sh.action, sh.hoverAction))
        append(boxParagraphs(sh.box, styles, paragraphIds = paragraphIds))
        append(geometryXml(sh, w, h))
        append("</draw:custom-shape>")
    }

    /**
     * A custom shape's `draw:enhanced-geometry`: its preset's type, or — a freeform — its own
     * outline as an enhanced path in a 21600-unit view box (Impress's "non-primitive"); its mirroring.
     * A DrawingML preset's outline is worked out at the shape's size, [wPt] × [hPt] when given (a
     * shape resized since: its arrowheads and corners keep their size, as the screen draws them).
     */
    internal fun geometryXml(sh: SlideShape, wPt: Float? = null, hPt: Float? = null): String {
        val mirror = (if (sh.flipH) """ draw:mirror-horizontal="true"""" else "") + (if (sh.flipV) """ draw:mirror-vertical="true"""" else "")
        val atSize = sh.preset?.takeIf { sh.type == ShapeType.FREEFORM && wPt != null && hPt != null && wPt > 0f && hPt > 0f }
            ?.let { com.therealityteam.aeropages.format.pptx.PresetGeometries.outline(it, sh.presetAdjust, wPt!! * 12700f, hPt!! * 12700f) }
        val free = (atSize ?: sh.path)?.takeIf { sh.type == ShapeType.FREEFORM }
            ?: return """<draw:enhanced-geometry draw:type="${geometry(sh.type)}"$mirror/>"""
        return """<draw:enhanced-geometry svg:viewBox="0 0 21600 21600" draw:type="non-primitive" draw:enhanced-path="${OdfGeometry.enhancedPath(free)}"$mirror/>"""
    }

    /**
     * The `draw:transform` that turns a shape [rotationDeg] clockwise about its centre (the app's and
     * PowerPoint's turn) where its box is ([x], [y]) [w] × [h]: ODF turns a shape about its own
     * corner, anticlockwise, then moves it — the move puts its centre back, as LibreOffice writes it.
     * Empty for an unturned shape, which svg:x / svg:y place; a turned one must have none (Impress
     * moves a shape by them first, then turns it about the page's corner).
     */
    internal fun transform(rotationDeg: Float, x: Float, y: Float, w: Float, h: Float): String {
        if (rotationDeg == 0f) return ""
        val a = Math.toRadians(-rotationDeg.toDouble())
        val cx = w / 2.0; val cy = h / 2.0
        // ODF's rotate(a), y downwards: (x, y) → (x cos a + y sin a, −x sin a + y cos a).
        val tx = x + cx - (cx * kotlin.math.cos(a) + cy * kotlin.math.sin(a))
        val ty = y + cy - (-cx * kotlin.math.sin(a) + cy * kotlin.math.cos(a))
        return """ draw:transform="rotate ($a) translate (${pt(tx.toFloat())} ${pt(ty.toFloat())})""""
    }

    /** ODF's enhanced-geometry type for a shape [t]. */
    internal fun geometry(t: ShapeType): String = when (t) {
        ShapeType.ELLIPSE -> "ellipse"
        ShapeType.ROUNDED -> "round-rectangle"
        ShapeType.TRIANGLE -> "isosceles-triangle"
        ShapeType.STAR -> "star5"
        ShapeType.ARROW -> "right-arrow"
        else -> "rectangle"
    }

    /**
     * A slide table as Impress writes one: a frame holding a `table:table` whose cells each name a `table-cell` style
     * (its fill and half-point border in the table style's colours, the text centred in its height; a header or total
     * row's text white and bold where its runs say nothing), with the six style options on the table.
     */
    private fun table(t: SlideTable, w: Float, h: Float, styles: SlideStyles, ids: String = ""): String = buildString {
        val look = com.therealityteam.aeropages.model.SlideTableLook
        append("""<draw:frame draw:layer="layout"$ids svg:width="${pt(t.wPct * w)}" svg:height="${pt(t.hPct * h)}" """)
        append("""svg:x="${pt(t.xPct * w)}" svg:y="${pt(t.yPct * h)}">""")
        append("""<table:table table:use-first-row-styles="${t.headerRow}" table:use-last-row-styles="${t.totalRow}" table:use-first-column-styles="${t.firstColumn}" """)
        append("""table:use-last-column-styles="${t.lastColumn}" table:use-banding-rows-styles="${t.bandedRows}" table:use-banding-columns-styles="${t.bandedColumns}">""")
        val cols = t.cells.firstOrNull()?.size ?: 0
        // Each column's width and row's height (Table Layout ▸ Cell Size), in the frame's size.
        repeat(cols) { c -> append("""<table:table-column table:style-name="${styles.tableColumn(t.colShare(c) * t.wPct * w)}"/>""") }
        // A merged cell spans number-columns-spanned / number-rows-spanned; the places it covers are covered-table-cells.
        val cover = t.coverage()
        t.cells.forEachIndexed { r, row ->
            append("""<table:table-row table:style-name="${styles.tableRow(t.rowShare(r) * t.hPct * h)}">""")
            row.forEachIndexed { c, cell ->
                val style = styles.tableCell(look.fill(t, r, c), look.sides(t, r, c, cover), look.emphasized(t, r), cell.cellAnchor)
                if (t.covered(cover, r, c)) { append("""<table:covered-table-cell table:style-name="$style"/>"""); return@forEachIndexed }
                val (rs, cs) = t.spanOf(r, c)
                val span = (if (cs > 1) """ table:number-columns-spanned="$cs"""" else "") + (if (rs > 1) """ table:number-rows-spanned="$rs"""" else "")
                append("""<table:table-cell table:style-name="$style"$span office:value-type="string">""")
                append(boxParagraphs(cell, styles))
                append("</table:table-cell>")
            }
            append("</table:table-row>")
        }
        append("</table:table></draw:frame>")
    }

    /** styles.xml: the page layout, the master page's fill, and the [named] drawing styles (gradients, dashes, markers) shapes use. */
    private fun styles(masterFill: String, w: Float, h: Float, named: String, designAutomatic: String = "", designMasters: String = "", defaultChrome: String = ""): String = buildString {
        append(HEAD)
        append("<office:document-styles $NS>")
        // Text in a sans face where a style names none (LibreOffice's own default would be a serif).
        append("<office:styles>")
        append("""<style:default-style style:family="graphic"><style:text-properties fo:font-family="Liberation Sans"/></style:default-style>""")
        append(named).append("</office:styles>")
        append("<office:automatic-styles>").append(designAutomatic)
        append("""<style:page-layout style:name="PM1"><style:page-layout-properties fo:page-width="${pt(w)}" fo:page-height="${pt(h)}" """)
        append("""style:print-orientation="landscape" fo:margin-top="0pt" fo:margin-bottom="0pt" fo:margin-left="0pt" fo:margin-right="0pt"/></style:page-layout>""")
        // The master page's fill is what pages without a fill of their own show.
        append("""<style:style style:name="dpDefault" style:family="drawing-page"><style:drawing-page-properties $masterFill/></style:style>""")
        append("</office:automatic-styles>")
        append("""<office:master-styles><style:master-page style:name="Default" style:page-layout-name="PM1" draw:style-name="dpDefault">$defaultChrome</style:master-page>""")
        append(designMasters).append("</office:master-styles>")
        append("</office:document-styles>")
    }

    /** Points a line deeper indents by: half an inch, as PowerPoint's outline steps. */
    private const val LEVEL_PT = 36f

    /**
     * The automatic styles a deck's frames and shapes name. [prefix] starts every name, so styles
     * written into another document (a spreadsheet's shapes) cannot meet its own.
     */
    internal class SlideStyles(private val prefix: String = "") {
        private val parts = LinkedHashMap<String, String>()
        private val byProps = LinkedHashMap<String, String>()
        /** Named drawing styles the graphic styles use — gradients, dashes, markers — for `office:styles`, by name. */
        private val named = LinkedHashMap<String, String>()
        /** Gradient names by colours and angle. */
        private val gradients = HashMap<String, String>()

        /** A page's fill attributes: its colour, or its gradient from that colour (a `draw:gradient` among the named styles). */
        fun backgroundFill(color: Long, shade: com.therealityteam.aeropages.model.BackgroundShade?): String {
            shade ?: return """draw:fill="solid" draw:fill-color="${color(color)}""""
            val key = "$color|${shade.second}|${shade.angleDeg}|${shade.kind}|${shade.cx}|${shade.cy}"
            val name = named(gradients.getOrPut(key) { "${prefix}Gradient_${gradients.size + 1}" }) { n ->
                OdfLines.gradientXml(n, color, shade.second, shade.angleDeg, shade.kind, shade.cx, shade.cy)
            }
            return """draw:fill="gradient" draw:fill-gradient-name="$name" draw:fill-color="${color(color)}""""
        }

        /** A master page's drawing-page style with its [fill] (the attributes). */
        fun masterPage(fill: String): String = byProps.getOrPut("mdp|$fill") {
            val name = "${prefix}Mdp${byProps.size + 1}"
            parts[name] = """<style:style style:name="$name" style:family="drawing-page"><style:drawing-page-properties draw:background-size="border" $fill/></style:style>"""
            name
        }

        fun page(background: Long?, transition: com.therealityteam.aeropages.model.SlideTransition, speed: com.therealityteam.aeropages.model.TransitionSpeed, hidden: Boolean = false,
                 hideDesign: Boolean = false, display: String = "", shade: com.therealityteam.aeropages.model.BackgroundShade? = null,
                 dir: com.therealityteam.aeropages.model.TransitionDir = com.therealityteam.aeropages.model.TransitionDir.DEFAULT): String {
            // No fill attribute at all = inherit the master page's fill.
            val fill = if (background == null) "" else backgroundFill(background, shade)
            // SMIL transition families, as LibreOffice Impress writes them, their subtype the side the new slide comes from
            // (or the orientation), `smil:direction="reverse"` the other way (a wipe from the right, an uncover, a split closing in).
            val d = com.therealityteam.aeropages.model.TransitionDir.of(transition, dir)
            fun from() = when (d) {
                com.therealityteam.aeropages.model.TransitionDir.FROM_LEFT -> "fromLeft"
                com.therealityteam.aeropages.model.TransitionDir.FROM_TOP -> "fromTop"
                com.therealityteam.aeropages.model.TransitionDir.FROM_BOTTOM -> "fromBottom"
                else -> "fromRight"
            }
            var reverse = false
            val smil = when (transition) {
                com.therealityteam.aeropages.model.SlideTransition.FADE -> "fade" to "crossfade"
                com.therealityteam.aeropages.model.SlideTransition.PUSH -> "pushWipe" to from()
                com.therealityteam.aeropages.model.SlideTransition.ZOOM -> { reverse = d == com.therealityteam.aeropages.model.TransitionDir.OUT; "irisWipe" to "rectangle" }
                com.therealityteam.aeropages.model.SlideTransition.COVER -> "slideWipe" to from()
                com.therealityteam.aeropages.model.SlideTransition.UNCOVER -> { reverse = true; "slideWipe" to from() }
                com.therealityteam.aeropages.model.SlideTransition.WIPE -> {
                    reverse = d == com.therealityteam.aeropages.model.TransitionDir.FROM_RIGHT || d == com.therealityteam.aeropages.model.TransitionDir.FROM_BOTTOM
                    "barWipe" to (if (d == com.therealityteam.aeropages.model.TransitionDir.FROM_TOP || d == com.therealityteam.aeropages.model.TransitionDir.FROM_BOTTOM) "topToBottom" else "leftToRight")
                }
                com.therealityteam.aeropages.model.SlideTransition.SPLIT -> {
                    reverse = d == com.therealityteam.aeropages.model.TransitionDir.HORIZONTAL_IN || d == com.therealityteam.aeropages.model.TransitionDir.VERTICAL_IN
                    "barnDoorWipe" to (if (d == com.therealityteam.aeropages.model.TransitionDir.VERTICAL_OUT || d == com.therealityteam.aeropages.model.TransitionDir.VERTICAL_IN) "vertical" else "horizontal")
                }
                com.therealityteam.aeropages.model.SlideTransition.RANDOM_BARS -> "randomBarWipe" to (if (d == com.therealityteam.aeropages.model.TransitionDir.VERTICAL_OUT) "vertical" else "horizontal")
                com.therealityteam.aeropages.model.SlideTransition.SHAPE -> { reverse = d == com.therealityteam.aeropages.model.TransitionDir.OUT; "ellipseWipe" to "circle" }
                com.therealityteam.aeropages.model.SlideTransition.DISSOLVE -> "dissolve" to "default"
                com.therealityteam.aeropages.model.SlideTransition.WHEEL -> "pinWheelWipe" to "fourBlade"
                com.therealityteam.aeropages.model.SlideTransition.CUT, com.therealityteam.aeropages.model.SlideTransition.NONE -> null
            }
            val speedName = when (speed) {
                com.therealityteam.aeropages.model.TransitionSpeed.SLOW -> "slow"
                com.therealityteam.aeropages.model.TransitionSpeed.FAST -> "fast"
                else -> "medium"
            }
            val props = buildString {
                append(fill)
                if (smil != null) append(""" presentation:transition-type="manual" smil:type="${smil.first}" smil:subtype="${smil.second}"${if (reverse) """ smil:direction="reverse"""" else ""} presentation:transition-speed="$speedName"""")
                // A hidden slide (skipped by the show), as Impress marks it.
                if (hidden) append(""" presentation:visibility="hidden"""")
                // A slide showing its design's background but not its pictures and shapes.
                if (hideDesign) append(""" presentation:background-objects-visible="false"""")
                // Its date, footer and number switched on or off.
                append(display)
            }
            return byProps.getOrPut("dp|$props") {
                val name = "${prefix}dp${byProps.size + 1}"
                parts[name] = """<style:style style:name="$name" style:family="drawing-page"><style:drawing-page-properties $props/></style:style>"""
                name
            }
        }

        fun graphic(fill: Long?, border: Long?, anchor: com.therealityteam.aeropages.model.ShapeAnchor = com.therealityteam.aeropages.model.ShapeAnchor.CENTER, strokeWidthPt: Float = 1f): String {
            val props = buildString {
                if (fill == null) append("""draw:fill="none" """) else append("""draw:fill="solid" draw:fill-color="${color(fill)}" """)
                if (border == null) append("""draw:stroke="none" """) else append("""draw:stroke="solid" svg:stroke-color="${color(border)}" svg:stroke-width="${pt(strokeWidthPt)}" """)
                append("""draw:auto-grow-height="false" draw:textarea-vertical-align="${vAlign(anchor)}"""")
            }
            return graphicStyle(props)
        }

        /**
         * A drawn shape's graphic style: its fill (solid, a two-colour gradient, none), its outline
         * (solid or dashed, weight and colour, none), a line's arrowheads, and where its text sits.
         */
        fun shape(sh: SlideShape, extra: String = ""): String {
            val props = buildString {
                val fill = sh.fill
                val grad = sh.fillGradient
                when {
                    fill == null -> append("""draw:fill="none" """)
                    grad != null -> {
                        // Impress mirrors a custom shape's outline, not its fill: a flipped shape names its gradient's direction as seen.
                        val custom = sh.type != ShapeType.TEXTBOX && sh.type != ShapeType.LINE
                        val angle = if (custom) OdfLines.mirrored(sh.gradientAngle, sh.flipH, sh.flipV) else sh.gradientAngle
                        val cx = if (custom && sh.flipH) 1f - sh.gradientCx else sh.gradientCx
                        val cy = if (custom && sh.flipV) 1f - sh.gradientCy else sh.gradientCy
                        val key = "$fill|$grad|$angle|${sh.gradientKind}|$cx|$cy"
                        val name = named(gradients.getOrPut(key) { "${prefix}Gradient_${gradients.size + 1}" }) { n ->
                            OdfLines.gradientXml(n, fill, grad, angle, sh.gradientKind, cx, cy)
                        }
                        append("""draw:fill="gradient" draw:fill-gradient-name="$name" draw:fill-color="${color(fill)}" """)
                    }
                    else -> append("""draw:fill="solid" draw:fill-color="${color(fill)}" """)
                }
                // A see-through fill: its opacity, or — a gradient whose ends are not as opaque as each other — an opacity gradient run with it.
                if (fill != null) {
                    val a1 = ((fill ushr 24) and 0xFF) / 255f
                    val a2 = (((grad ?: fill) ushr 24) and 0xFF) / 255f
                    if (grad != null && Math.round(a1 * 100) != Math.round(a2 * 100)) {
                        val custom = sh.type != ShapeType.TEXTBOX && sh.type != ShapeType.LINE
                        val angle = if (custom) OdfLines.mirrored(sh.gradientAngle, sh.flipH, sh.flipV) else sh.gradientAngle
                        val cx = if (custom && sh.flipH) 1f - sh.gradientCx else sh.gradientCx
                        val cy = if (custom && sh.flipV) 1f - sh.gradientCy else sh.gradientCy
                        val name = named("${prefix}Transparency_${Math.round(a1 * 100)}_${Math.round(a2 * 100)}_${Math.round(angle)}_${sh.gradientKind}_${Math.round(cx * 100)}_${Math.round(cy * 100)}") { n ->
                            OdfLines.opacityXml(n, a1, a2, angle, sh.gradientKind, cx, cy)
                        }
                        append("""draw:opacity-name="$name" """)
                    } else if (a1 < 1f) append("""draw:opacity="${Math.round(a1 * 100)}%" """)
                }
                sh.shadow?.let { append(shadowAttrs(it).trimStart()).append(' ') }
                val border = sh.border
                if (border == null) append("""draw:stroke="none" """)
                else {
                    if (sh.dash == com.therealityteam.aeropages.model.LineDash.SOLID) append("""draw:stroke="solid" """)
                    else append("""draw:stroke="dash" draw:stroke-dash="${named("${prefix}Dash_${sh.dash.ooxml}") { n -> OdfLines.dashXml(sh.dash, n) }}" """)
                    append("""svg:stroke-color="${color(border)}" svg:stroke-width="${pt(sh.borderWidthPt)}" """)
                    if (sh.type == ShapeType.LINE || sh.type == ShapeType.FREEFORM) {
                        marker("start", sh.beginArrow, sh.borderWidthPt)
                        marker("end", sh.endArrow, sh.borderWidthPt)
                    }
                }
                append("""draw:auto-grow-height="false" draw:textarea-vertical-align="${vAlign(sh.anchor)}"""")
                if (sh.noWrap) append(""" fo:wrap-option="no-wrap"""")
                // Where it stands on a text document's page, when it floats there.
                if (extra.isNotEmpty()) append(' ').append(extra)
            }
            return graphicStyle(props)
        }

        /** A line end's marker attributes: which marker, drawn how wide; a diamond or an oval sits centred on the end. */
        private fun StringBuilder.marker(side: String, end: com.therealityteam.aeropages.model.LineEnd, strokePt: Float) {
            if (end.isNone) return
            val name = named(OdfLines.encoded(OdfLines.markerName(end, strokePt))) { OdfLines.markerXml(end, strokePt) }
            append("""draw:marker-$side="$name" draw:marker-$side-width="${pt(OdfLines.markerWidthPt(end, strokePt))}" """)
            val centred = end.head == com.therealityteam.aeropages.model.ArrowHead.DIAMOND || end.head == com.therealityteam.aeropages.model.ArrowHead.OVAL
            append("""draw:marker-$side-center="$centred" """)
        }

        /** The named style [name], its XML made once by [make]; returns [name]. */
        private fun named(name: String, make: (String) -> String): String {
            if (name !in named) named[name] = make(name)
            return name
        }

        /** The document's named drawing styles, for its `office:styles`. */
        fun renderNamed(): String = named.values.joinToString("")

        /**
         * A title or body placeholder's style: Impress's presentation family, which is what makes a
         * frame a placeholder there (a graphic style gives a plain text box); no fill or line, its
         * text anchored as the app draws it — a title in the middle, a body from the top.
         */
        fun placeholder(anchor: com.therealityteam.aeropages.model.ShapeAnchor, parent: String? = null): String {
            val props = """draw:fill="none" draw:stroke="none" draw:auto-grow-height="false" draw:textarea-vertical-align="${vAlign(anchor)}""""
            // Its text's look from the master's style it names (Impress's `<master>-title` …).
            val parentAttr = parent?.let { """ style:parent-style-name="${escape(it)}"""" } ?: ""
            return byProps.getOrPut("pr|$props|$parent") {
                val name = "${prefix}pr${byProps.size + 1}"
                parts[name] = """<style:style style:name="$name" style:family="presentation"$parentAttr><style:graphic-properties $props/></style:style>"""
                name
            }
        }

        /**
         * A master's date, footer or number frame: no fill or line, its text in the middle, [align]ed,
         * [sizePt] in [color] and [font] — all in the frame's own style, where Impress keeps a field's look.
         */
        fun chrome(align: TextAlign, sizePt: Float, color: Long?, font: String): Pair<String, String> {
            val textAlign = when (align) { TextAlign.CENTER -> "center"; TextAlign.RIGHT -> "end"; else -> "start" }
            // Every script's size, as Impress writes a master's text (a field is drawn in its script's);
            // no colour: LibreOffice's automatic one.
            val tint = if (color == null) """style:use-window-font-color="true"""" else """fo:color="${color(color)}""""
            val look = """<style:text-properties fo:font-size="${pt(sizePt)}" style:font-size-asian="${pt(sizePt)}" style:font-size-complex="${pt(sizePt)}" $tint fo:font-family="${escape(font)}"/>"""
            val frame = byProps.getOrPut("chrome|$sizePt|$color|$font") {
                val name = "${prefix}pr${byProps.size + 1}"
                parts[name] = """<style:style style:name="$name" style:family="presentation"><style:graphic-properties draw:fill="none" draw:stroke="none" draw:auto-grow-height="false" draw:textarea-vertical-align="middle"/>$look</style:style>"""
                name
            }
            val text = byProps.getOrPut("chromeText|$align|$sizePt|$color|$font") {
                val name = "${prefix}MP${byProps.size + 1}"
                parts[name] = """<style:style style:name="$name" style:family="paragraph"><style:paragraph-properties fo:text-align="$textAlign"/>$look</style:style>"""
                name
            }
            return frame to text
        }

        private fun graphicStyle(props: String): String = byProps.getOrPut("gr|$props") {
            val name = "${prefix}gr${byProps.size + 1}"
            parts[name] = """<style:style style:name="$name" style:family="graphic"><style:graphic-properties $props/></style:style>"""
            name
        }

        private fun vAlign(anchor: com.therealityteam.aeropages.model.ShapeAnchor): String = when (anchor) {
            com.therealityteam.aeropages.model.ShapeAnchor.TOP -> "top"
            com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> "bottom"
            else -> "middle"
        }

        /** A picture frame's style with its crop ([clip], `rect(top, right, bottom, left)`) and its [shadow]. */
        fun picture(clip: String?, shadow: com.therealityteam.aeropages.model.ShapeShadow? = null, mirror: String? = null, decorative: Boolean = false): String =
            byProps.getOrPut("pic|$clip|$shadow|$mirror|$decorative") {
            val name = "${prefix}gr${byProps.size + 1}"
            val clipAttr = clip?.let { """ fo:clip="$it"""" } ?: ""
            val mirrorAttr = mirror?.let { """ style:mirror="$it"""" } ?: ""
            parts[name] = """<style:style style:name="$name" style:family="graphic"><style:graphic-properties draw:stroke="none" draw:fill="none"$clipAttr$mirrorAttr${shadowAttrs(shadow)}${OdfAltText.decorativeProp(decorative)}/></style:style>"""
            name
        }

        private fun shadowAttrs(s: com.therealityteam.aeropages.model.ShapeShadow?): String = OdfLines.shadowAttrs(s)

        /**
         * A paragraph style; [level] indents it by as many steps (a line deeper than the first outside
         * a list), [spacing] other than single is its proportional line height.
         */
        fun paragraph(align: TextAlign, f: CharacterFormat, level: Int = 0, spacing: Float = 1f): String {
            val para = """fo:text-align="${when (align) {
                TextAlign.LEFT -> "start"; TextAlign.CENTER -> "center"; TextAlign.RIGHT -> "end"; TextAlign.JUSTIFY -> "justify"
            }}"""" + (if (level > 0) """ fo:margin-left="${pt(level * LEVEL_PT)}"""" else "") +
                (if (spacing != 1f) """ fo:line-height="${Math.round(spacing * 100)}%"""" else "")
            val text = buildString {
                f.fontSizePt?.let { append(OdfPackage.sizeAttr(pt(it))) }
                if (f.bold) append(OdfPackage.weightAttr("bold"))
                if (f.italic) append(OdfPackage.italicAttr("italic"))
                if (f.underline) append("""style:text-underline-style="solid" """)
                f.color?.let { append("""fo:color="${color(it)}" """) }
                f.fontFamily?.let { append("""style:font-name="${escape(it)}" """) }
            }.trim()
            return byProps.getOrPut("p|$para|$text") {
                val name = "${prefix}P${byProps.size + 1}"
                parts[name] = """<style:style style:name="$name" style:family="paragraph"><style:paragraph-properties $para/>""" +
                    (if (text.isEmpty()) "" else """<style:text-properties $text/>""") + "</style:style>"
                name
            }
        }

        /**
         * Automatic text style for a run that differs from its paragraph's [base] look; null when the
         * run needs no span. Differences are written explicitly (`normal`, `none`) so a span can undo
         * a bold or underlined paragraph, and an unset colour / size falls back to the master defaults.
         */
        fun text(f: CharacterFormat, base: CharacterFormat, defaults: com.therealityteam.aeropages.model.MasterText?): String? {
            val props = buildString {
                if (f.bold != base.bold) append(OdfPackage.weightAttr(if (f.bold) "bold" else "normal"))
                if (f.italic != base.italic) append(OdfPackage.italicAttr(if (f.italic) "italic" else "normal"))
                if (f.underline != base.underline) append("""style:text-underline-style="${if (f.underline) "solid" else "none"}" """)
                if (f.strikethrough != base.strikethrough) append("""style:text-line-through-style="${if (f.strikethrough) "solid" else "none"}" """)
                if (f.superscript) append("""style:text-position="super 58%" """)
                if (f.subscript) append("""style:text-position="sub 58%" """)
                if (f.fontSizePt != base.fontSizePt) (f.fontSizePt ?: defaults?.sizePt ?: base.fontSizePt)?.let { append(OdfPackage.sizeAttr(pt(it))) }
                // The colour the run shows (its own, else the defaults'), where the paragraph's is not it.
                val shown = f.color ?: defaults?.color
                if (shown != base.color) append("""fo:color="${color(shown ?: 0xFF000000)}" """)
                if (f.fontFamily != base.fontFamily) (f.fontFamily ?: defaults?.font)?.let { append("""style:font-name="${escape(it)}" """) }
                f.highlight?.let { append("""fo:background-color="${color(it)}" """) }
            }.trim()
            if (props.isEmpty()) return null
            return byProps.getOrPut("t|$props") {
                val name = "${prefix}T${byProps.size + 1}"
                parts[name] = """<style:style style:name="$name" style:family="text"><style:text-properties $props/></style:style>"""
                name
            }
        }

        /** The deck's bulleted or numbered list style: every level's bullet (or number format) and indent. */
        fun list(style: SlideListStyle): String {
            val name = prefix + (if (style == SlideListStyle.NUMBER) "Lnum" else "Lbul")
            parts.getOrPut(name) {
                buildString {
                    append("""<text:list-style style:name="$name">""")
                    for (l in 0..com.therealityteam.aeropages.model.SlideLevels.MAX) {
                        // The label as large as the text (LibreOffice otherwise sets it smaller).
                        val props = """<style:list-level-properties text:space-before="${pt(l * LEVEL_PT)}" text:min-label-width="${pt(if (style == SlideListStyle.NUMBER) 27f else 20f)}"/>""" +
                            """<style:text-properties fo:font-size="100%"/>"""
                        if (style == SlideListStyle.NUMBER)
                            append("""<text:list-level-style-number text:level="${l + 1}" style:num-suffix="." style:num-format="${com.therealityteam.aeropages.model.SlideLevels.odfNumFormat(l)}">$props</text:list-level-style-number>""")
                        else
                            append("""<text:list-level-style-bullet text:level="${l + 1}" text:bullet-char="${com.therealityteam.aeropages.model.SlideLevels.bullet(l)}">$props</text:list-level-style-bullet>""")
                    }
                    append("</text:list-style>")
                }
            }
            return name
        }

        /** A slide table cell's `table-cell` style: its [fill], a half-point [border], text centred in its height ([onHeader]: white and bold). */
        /** A slide table column [widthPt] wide (`style:column-width`). */
        fun tableColumn(widthPt: Float): String = String.format(java.util.Locale.ROOT, "%.2fpt", widthPt).let { wd ->
            byProps.getOrPut("tcol|$wd") {
                val name = "${prefix}Tco${byProps.size + 1}"
                parts[name] = """<style:style style:name="$name" style:family="table-column"><style:table-column-properties style:column-width="$wd"/></style:style>"""
                name
            }
        }

        /** A slide table row [heightPt] high (`style:row-height`). */
        fun tableRow(heightPt: Float): String = String.format(java.util.Locale.ROOT, "%.2fpt", heightPt).let { ht ->
            byProps.getOrPut("trow|$ht") {
                val name = "${prefix}Tro${byProps.size + 1}"
                parts[name] = """<style:style style:name="$name" style:family="table-row"><style:table-row-properties style:row-height="$ht"/></style:style>"""
                name
            }
        }

        fun tableCell(fill: Long, sides: com.therealityteam.aeropages.model.CellBorders, onHeader: Boolean, anchor: com.therealityteam.aeropages.model.ShapeAnchor? = null): String = byProps.getOrPut("tc|$fill|$sides|$onHeader|$anchor") {
            val vAlign = when (anchor) { com.therealityteam.aeropages.model.ShapeAnchor.TOP -> "top"; com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> "bottom"; else -> "middle" }
            val name = "${prefix}Tc${byProps.size + 1}"
            val fillAttrs = if (com.therealityteam.aeropages.model.SlideTableLook.isNone(fill)) """draw:fill="none"""" else """draw:fill="solid" draw:fill-color="${color(fill)}""""
            // Its lines as they draw (its own borders, or the style's grid): one fo:border when all four are alike.
            val v = listOf(sides.top, sides.bottom, sides.left, sides.right).map { OdfBorders.cellValueOf(it) }
            val border = if (v.distinct().size == 1) """fo:border="${v[0]}""""
                else """fo:border-top="${v[0]}" fo:border-bottom="${v[1]}" fo:border-left="${v[2]}" fo:border-right="${v[3]}""""
            parts[name] = """<style:style style:name="$name" style:family="table-cell"><loext:graphic-properties $fillAttrs draw:textarea-vertical-align="$vAlign"/>""" +
                """<style:paragraph-properties $border/>""" +
                (if (onHeader) """<style:text-properties fo:color="#ffffff" fo:font-weight="bold"/>""" else "") + "</style:style>"
            name
        }

        fun render(): String = parts.values.joinToString("")
    }
}
