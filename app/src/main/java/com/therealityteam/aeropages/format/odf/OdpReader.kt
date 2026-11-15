package com.therealityteam.aeropages.format.odf

import kotlin.math.roundToInt
import com.therealityteam.aeropages.format.OfficeXml
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.DesignItem
import com.therealityteam.aeropages.model.DesignPicture
import com.therealityteam.aeropages.model.DesignShape
import com.therealityteam.aeropages.model.MasterText
import com.therealityteam.aeropages.model.under
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.ShapeType
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideDesign
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideListStyle
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.SlideTable
import com.therealityteam.aeropages.model.TextAlign

/**
 * OpenDocument Presentation (.odp) reader, the counterpart of [OdpWriter].
 *
 * A `draw:page` gives one slide: the title and outline frames (by `presentation:class`, falling
 * back to reading order) become the slide's boxes with their text formatting, other text frames
 * and `draw:custom-shape`s become positioned shapes, `draw:image`s become pictures with their
 * bytes (a frame's `draw:plugin` a video or sound shown by its poster), `table:table` inside a frame becomes a slide table, and `presentation:notes` becomes the
 * speaker notes. The slide size comes from the master page's layout.
 */
object OdpReader {

    fun read(bytes: ByteArray): Presentation {
        val files = OfficeXml.unzip(bytes)
        val content = OdfNode.parse(files["content.xml"]) ?: return Presentation.blank()
        val stylesPart = OdfNode.parse(files["styles.xml"])
        val styles = OdfStyles.of(content, stylesPart)

        // The slides' size: the page layout of the master page the pages use — not the first one
        // (LibreOffice writes its handouts' A4 page layout first).
        val pageLayoutsByName = stylesPart?.descendants("page-layout").orEmpty().associateBy { it.attr("name") }
        val masters = stylesPart?.descendants("master-page").orEmpty()
        val firstPage = content.child("body")?.child("presentation")?.children("page")?.firstOrNull()
        val master = masters.firstOrNull { it.attr("name") == firstPage?.attr("master-page-name") } ?: masters.firstOrNull()
        val layout = (master?.attr("page-layout-name")?.let { pageLayoutsByName[it] } ?: pageLayoutsByName.values.firstOrNull())?.child("page-layout-properties")
        val pageW = OdfValue.lengthPt(layout?.attr("page-width")) ?: 720f
        val pageH = OdfValue.lengthPt(layout?.attr("page-height")) ?: 405f

        // Impress's page layouts: the placeholders each has (title, subtitle, outline …), by name.
        val pageLayouts = listOfNotNull(stylesPart, content).flatMap { it.descendants("presentation-page-layout") }
            .mapNotNull { l -> l.attr("name")?.let { it to l.children("placeholder").mapNotNull { ph -> ph.attr("object") } } }.toMap()
        val pages = content.child("body")?.child("presentation")?.children("page").orEmpty()
        // A producer that knows placeholders — it defines page layouts (LibreOffice, this app) or
        // classes a frame anywhere — has no unclassed title or body: those frames are text boxes.
        val classed = pageLayouts.isNotEmpty() || pages.any { p -> p.children.any { it.name == "frame" && it.attr("class") != null } }
        // A template's designs: the master pages that draw something — a picture fill, pictures, shapes.
        val masterPages = stylesPart?.descendants("master-page").orEmpty()
        val pres0Chrome = HashMap<com.therealityteam.aeropages.model.SlideChrome.Part, com.therealityteam.aeropages.model.ChromeLook>()
        val designs = masterPages.mapNotNull { m -> design(m, styles, files, pageW, pageH) }
        // The master pages' title and body text (Impress's `<master>-title` / `-outline1` styles): the
        // deck's master's, a design's own where it differs; a page's placeholders inherit them.
        val masterTexts = masterPages.associate { m -> (m.attr("name") ?: "") to masterText(m, styles) }
        // The deck's own: its "Default" master (the app's, Impress's first), else the one its first page is on.
        val deckText = masterTexts["Default"] ?: masterTexts[master?.attr("name")]
        designs.forEach { d ->
            val own = masterTexts[d.name] ?: return@forEach
            if (deckText == null || !same(own.first, deckText.first)) d.titleText = own.first
            if (deckText == null || !same(own.second, deckText.second)) d.bodyText = own.second
        }
        // Where each master puts its date, footer and number and how they read (not where the app does).
        val deckMasterName = if (masterTexts.containsKey("Default")) "Default" else master?.attr("name")
        masterPages.firstOrNull { it.attr("name") == deckMasterName }?.let { pres0Chrome.putAll(chromeOf(it, styles, pageW, pageH)) }
        designs.forEach { d -> masterPages.firstOrNull { it.attr("name") == d.name }?.let { d.chrome.putAll(chromeOf(it, styles, pageW, pageH)) } }
        val deckTitle = deckText?.first ?: MasterText(null, null, null, null)
        val deckBody = deckText?.second ?: MasterText(null, null, null, null)
        // What a master page's pages show behind their text (null: a picture, unknown).
        val masterBackdrop = masterPages.associate { m ->
            val style = m.attr("style-name")
            (m.attr("name") ?: "") to (if (styles.pageFillPicture(style) != null) null else styles.pageFill(style) ?: 0xFFFFFFFF)
        }
        // (The deck's background is its first master page's, as below.)
        val firstMasterName = masterPages.firstOrNull()?.attr("name")
        val masterStyles = masterPages.associate { (it.attr("name") ?: "") to it.attr("style-name") }
        val slides = pages.map { page ->
            val design = designs.firstOrNull { it.name == page.attr("master-page-name") }
            val inherited = deckTitle.under(design?.titleText) to deckBody.under(design?.bodyText)
            slide(page, styles, files, pageW, pageH, pageLayouts, classed, inherited, masterBackdrop[page.attr("master-page-name")]).also { s ->
                // On a master page other than the deck's that only colours its pages (no drawing, so no design): that
                // colour or gradient, unless the page has its own.
                val m = page.attr("master-page-name")
                if (s.background == null && design == null && m != null && m != firstMasterName) masterStyles[m]?.let { st ->
                    styles.pageFill(st)?.let { c -> s.background = c; s.backgroundShade = styles.pageShade(st) }
                }
            }
        }
        val pres = Presentation(
            slides = if (slides.isEmpty()) mutableListOf(Slide()) else slides.toMutableList(),
            slideSize = com.therealityteam.aeropages.model.SlideSize.of(pageW, pageH),
        )
        pres.master.chrome.putAll(pres0Chrome)
        deckText?.let { (t, b) ->
            pres.master.titleFont = t.font; pres.master.titleSizePt = t.sizePt; pres.master.titleColor = t.color; pres.master.titleBold = t.bold
            pres.master.bodyFont = b.font; pres.master.bodySizePt = b.sizePt; pres.master.bodyColor = b.color
        }
        // The master page's drawing-page fill is the deck background; a page fill equal to it inherits.
        val masterStyle = stylesPart?.descendants("master-page")?.firstOrNull()?.attr("style-name")
        val masterBg = masterStyle?.let { styles.pageFill(it) }
        pres.master.background = masterBg
        pres.master.backgroundShade = masterStyle?.let { styles.pageShade(it) }
        if (masterBg != null) pres.slides.forEach {
            if (it.background == masterBg && it.backgroundShade == pres.master.backgroundShade) { it.background = null; it.backgroundShade = null }
        }
        pres.designs.addAll(designs)
        pages.forEachIndexed { i, page ->
            val slide = pres.slides.getOrNull(i) ?: return@forEachIndexed
            val name = page.attr("master-page-name") ?: return@forEachIndexed
            if (pres.designs.any { it.name == name }) slide.design = name
            if (styles.pageHidesObjects(page.attr("style-name"))) slide.designObjects = false
        }
        readHeaderFooter(pres, content, pages, styles)
        // Links to slides name their pages ("#page2", or a slide's own name): now the slides themselves.
        val byName = HashMap<String, Slide>()
        pages.forEachIndexed { i, page -> page.attr("name")?.let { n -> pres.slides.getOrNull(i)?.let { byName.putIfAbsent(n, it) } } }
        com.therealityteam.aeropages.model.SlideLinks.remap(pres) { link ->
            if (!link.startsWith("#") || link in com.therealityteam.aeropages.model.SlideLinks.JUMPS || com.therealityteam.aeropages.model.SlideLinks.slideId(link) != null) return@remap link
            val name = link.substring(1)
            val slide = byName[name]
                // A page named by its number (Impress's "Slide 3" / "page3") that no page is named.
                ?: PAGE_NUMBER.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.let { pres.slides.getOrNull(it - 1) }
            slide?.let { com.therealityteam.aeropages.model.SlideLinks.toSlide(it) } ?: link
        }
        readShow(content.child("body")?.child("presentation")?.child("settings"), pres) { name ->
            byName[name] ?: PAGE_NUMBER.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.let { pres.slides.getOrNull(it - 1) }
        }
        pres.props = com.therealityteam.aeropages.format.OdfMeta.read(files["meta.xml"])
        return pres
    }

    /**
     * Impress's slide show settings (`presentation:settings`): looping (`endless`; with a `pause` between rounds, a kiosk),
     * timings ignored (`force-manual`), in a window (`full-screen="false"`), without animations, the page it starts at,
     * the custom show it plays — and the custom shows (`presentation:show`, their pages by name), slides by [slideNamed].
     */
    private fun readShow(settings: OdfNode?, pres: Presentation, slideNamed: (String) -> Slide?) {
        settings ?: return
        pres.customShows = settings.children("show").mapNotNull { show ->
            val name = show.attr("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            com.therealityteam.aeropages.model.CustomShow(name, show.attr("pages").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { slideNamed(it)?.id })
        }.distinctBy { it.name }
        val endless = settings.attr("endless") == "true"
        pres.loopShow = endless
        pres.useTimings = settings.attr("force-manual") != "true"
        val start = settings.attr("start-page")?.let(slideNamed)?.let { pres.slides.indexOf(it) + 1 } ?: 0
        pres.show = com.therealityteam.aeropages.model.ShowSetup(
            type = when {
                endless && settings.attr("pause") != null -> com.therealityteam.aeropages.model.ShowType.KIOSK
                settings.attr("full-screen") == "false" -> com.therealityteam.aeropages.model.ShowType.WINDOW
                else -> com.therealityteam.aeropages.model.ShowType.SPEAKER
            },
            animations = settings.attr("animations") != "disabled",
            from = if (start > 1) start else 0,
            custom = settings.attr("show")?.takeIf { name -> pres.customShows.any { it.name == name } },
        )
    }

    private val PAGE_NUMBER = Regex("""(?i)(?:page|slide)\s*(\d+)""")

    /**
     * A shape's or picture's click (Impress's `presentation:event-listener` on `dom:click`): the show's
     * next, previous, first or last page, its end (`stop`), or a slide or address it shows (`show`,
     * `xlink:href`: "#page2" until the slides are known); null for what the show does not do (a sound, a
     * program, a macro, hiding the shape).
     */
    private fun clickOf(node: OdfNode, event: String = "dom:click"): String? {
        val listener = node.child("event-listeners")?.children("event-listener")?.firstOrNull { it.attr("event-name") == event } ?: return null
        return when (listener.attr("action")) {
            "next-page" -> com.therealityteam.aeropages.model.SlideLinks.NEXT
            "previous-page" -> com.therealityteam.aeropages.model.SlideLinks.PREVIOUS
            "first-page" -> com.therealityteam.aeropages.model.SlideLinks.FIRST
            "last-page" -> com.therealityteam.aeropages.model.SlideLinks.LAST
            "stop" -> com.therealityteam.aeropages.model.SlideLinks.END
            "show" -> listener.attr("href")?.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /**
     * The format a date that updates is shown in: the app's own, kept as its data style's title;
     * else the app's of the kind the style is (LibreOffice's own few: digits, the month's name, the
     * weekday — which it draws in its locale's order).
     */
    private fun dateFormatOf(style: String?, styles: OdfStyles): String {
        styles.dataTitle(style)?.takeIf { com.therealityteam.aeropages.model.FormatCode.isValid(it) }?.let { return it }
        val code = styles.dataCode(style) ?: return com.therealityteam.aeropages.model.SlideDate.DEFAULT_FORMAT
        return com.therealityteam.aeropages.model.SlideDate.formatOf(com.therealityteam.aeropages.model.SlideDate.kindOf(code))
    }

    /**
     * Header & footer: the footer texts and dates the presentation declares (`presentation:footer-decl`,
     * `presentation:date-time-decl` — today's date in a data style, or fixed text), shown on the
     * pages that name them where their styles display them (as Impress does, a footer and a date
     * unless switched off, a number only when switched on). The deck's footer and date are the first
     * a page shows, its number shown when any page shows one; title pages showing none of them while
     * others do are "don't show on title slide".
     */
    private fun readHeaderFooter(pres: Presentation, content: OdfNode, pages: List<OdfNode>, styles: OdfStyles) {
        val body = content.child("body")?.child("presentation") ?: return
        val footers = body.children("footer-decl").associate { (it.attr("name") ?: "") to it.text.trim() }
        val dates = body.children("date-time-decl").associate { d ->
            (d.attr("name") ?: "") to if (d.attr("source") == "current-date") com.therealityteam.aeropages.model.SlideDate(dateFormatOf(d.attr("data-style-name"), styles))
                else com.therealityteam.aeropages.model.SlideDate(fixed = d.text.trim())
        }
        var titleShows = false; var titleHides = false; var othersShow = false
        pages.forEachIndexed { i, page ->
            val slide = pres.slides.getOrNull(i) ?: return@forEachIndexed
            val style = page.attr("style-name")
            val footer = page.attr("use-footer-name")?.let { footers[it] }?.takeIf { it.isNotBlank() && styles.pageDisplays(style, "display-footer") != false }
            val date = page.attr("use-date-time-name")?.let { dates[it] }
                ?.takeIf { (it.fixed == null || it.fixed.isNotBlank()) && styles.pageDisplays(style, "display-date-time") != false }
            val number = styles.pageDisplays(style, "display-page-number") == true
            if (footer != null && pres.footer.isBlank()) pres.footer = footer
            if (date != null && pres.date == null) pres.date = date
            if (number) pres.showSlideNumber = true
            val shows = footer != null || date != null || number
            if (slide.layout == com.therealityteam.aeropages.model.SlideLayout.TITLE) { if (shows) titleShows = true else titleHides = true }
            else if (shows) othersShow = true
        }
        pres.hideFooterOnTitle = titleHides && !titleShows && othersShow
    }

    /**
     * A master page's title and body text defaults: what the styles of its title and outline
     * placeholders (Impress's `<master>-title`, `-outline1`, their parents resolved) give text.
     */
    private fun masterText(m: OdfNode, styles: OdfStyles): Pair<MasterText, MasterText> {
        fun of(cls: String): MasterText {
            val t = m.children("frame").firstOrNull { it.attr("class") == cls }?.attr("style-name")?.let { styles.resolvedParagraph(it)?.text }
            return MasterText(t?.fontFamily, t?.fontSizePt, t?.color, if (t?.bold == true) true else null)
        }
        return of("title") to of("outline")
    }

    private fun same(a: MasterText, b: MasterText): Boolean = a.font == b.font && a.sizePt == b.sizePt && a.color == b.color && a.bold == b.bold

    /**
     * Where master page [m] puts the date, footer and number (its `date-time`, `footer` and
     * `page-number` frames, not its notes page's) and how their text reads — its text style's (the
     * frame's `draw:text-style-name`), else its presentation style's alignment, size and colour — each
     * one not where the app puts it (a file of its own).
     */
    private fun chromeOf(m: OdfNode, styles: OdfStyles, pageW: Float, pageH: Float): Map<com.therealityteam.aeropages.model.SlideChrome.Part, com.therealityteam.aeropages.model.ChromeLook> {
        val out = HashMap<com.therealityteam.aeropages.model.SlideChrome.Part, com.therealityteam.aeropages.model.ChromeLook>()
        for (frame in m.children("frame")) {
            val part = when (frame.attr("class")) {
                "date-time" -> com.therealityteam.aeropages.model.SlideChrome.Part.DATE
                "footer" -> com.therealityteam.aeropages.model.SlideChrome.Part.FOOTER
                "page-number" -> com.therealityteam.aeropages.model.SlideChrome.Part.NUMBER
                else -> continue
            }
            val f = boxFrame(frame, pageW, pageH) ?: continue
            val text = styles.resolvedParagraph(frame.attr("text-style-name"))
            val look = styles.resolvedParagraph(frame.attr("style-name"))
            val inner = frame.descendants("p").firstOrNull()?.attr("style-name")?.let { styles.resolvedParagraph(it) }
            val align = inner?.align ?: text?.align ?: look?.align ?: part.align
            val size = inner?.text?.fontSizePt ?: text?.text?.fontSizePt ?: look?.text?.fontSizePt ?: com.therealityteam.aeropages.model.SlideChrome.SIZE_PT
            val color = inner?.text?.color ?: text?.text?.color ?: look?.text?.color
            val chrome = com.therealityteam.aeropages.model.ChromeLook(f.xPct, f.yPct, f.wPct, f.hPct, align, size, color)
            if (!com.therealityteam.aeropages.model.SlideChrome.isDefault(part, chrome)) out[part] = chrome
        }
        return out
    }

    /**
     * A master page as a [SlideDesign] — its fill (a colour or a picture) and what it draws: pictures,
     * shapes, lines, text boxes, the members of its groups — or null when it draws nothing but a
     * colour. Its placeholders (title, outline …) and header / footer / date / number fields are not
     * drawings of its own.
     */
    private fun design(master: OdfNode, styles: OdfStyles, files: Map<String, ByteArray>, pageW: Float, pageH: Float): SlideDesign? {
        val name = master.attr("name") ?: return null
        val style = master.attr("style-name")
        val picture = styles.pageFillPicture(style)?.let { path -> files[path]?.let { bytes ->
            val natural = com.therealityteam.aeropages.format.ImageSize.naturalPt(bytes) ?: (pageW to pageH)
            SlideImage(bytes, com.therealityteam.aeropages.format.PictureType.mimeOf(bytes, path), natural.first, natural.second, 0f, 0f, 1f, 1f)
        } }
        val items = ArrayList<DesignItem>()
        fun collect(node: OdfNode) {
            for (el in node.children) {
                val cls = el.attr("class")
                when {
                    el.name == "g" -> collect(el)
                    cls != null && el.name == "frame" -> Unit   // a placeholder, or a header / footer / date / number field
                    el.name == "frame" && el.child("image") != null -> image(el, files, pageW, pageH, styles)?.let { items.add(DesignPicture(it)) }
                    el.name == "frame" && el.child("text-box") != null -> items.add(DesignShape(shape(el, styles, pageW, pageH)))
                    el.name in DRAWN_ELEMENTS -> items.add(DesignShape(shape(el, styles, pageW, pageH)))
                }
            }
        }
        collect(master)
        if (picture == null && items.isEmpty()) return null
        return SlideDesign(name, styles.pageFill(style), picture, items).also { it.backgroundShade = styles.pageShade(style) }
    }

    private fun slide(page: OdfNode, styles: OdfStyles, files: Map<String, ByteArray>, pageW: Float, pageH: Float, pageLayouts: Map<String, List<String>> = emptyMap(), classed: Boolean = true,
                      inherited: Pair<MasterText, MasterText>? = null, masterBackdrop: Long? = 0xFFFFFFFF): Slide {
        val slide = Slide()
        slide.background = styles.pageFill(page.attr("style-name"))
        slide.backgroundShade = styles.pageShade(page.attr("style-name"))
        // What the page's text sits on: its own fill, else its master's (null: a picture).
        val backdrop = slide.background ?: masterBackdrop
        slide.hidden = styles.pageHidden(page.attr("style-name"))
        styles.pageTransition(page.attr("style-name"))?.let { (type, speed) ->
            val (subtype, reverse) = styles.pageTransitionWay(page.attr("style-name")) ?: ("" to false)
            slide.transition = when (type) {
                "fade" -> com.therealityteam.aeropages.model.SlideTransition.FADE
                "pushWipe" -> com.therealityteam.aeropages.model.SlideTransition.PUSH
                "irisWipe", "zoom" -> com.therealityteam.aeropages.model.SlideTransition.ZOOM
                "slideWipe" -> if (reverse) com.therealityteam.aeropages.model.SlideTransition.UNCOVER else com.therealityteam.aeropages.model.SlideTransition.COVER
                "barWipe", "blindsWipe", "waterfallWipe" -> com.therealityteam.aeropages.model.SlideTransition.WIPE
                "barnDoorWipe" -> com.therealityteam.aeropages.model.SlideTransition.SPLIT
                "randomBarWipe" -> com.therealityteam.aeropages.model.SlideTransition.RANDOM_BARS
                "ellipseWipe" -> com.therealityteam.aeropages.model.SlideTransition.SHAPE
                "dissolve", "checkerBoardWipe" -> com.therealityteam.aeropages.model.SlideTransition.DISSOLVE
                "pinWheelWipe", "fanWipe" -> com.therealityteam.aeropages.model.SlideTransition.WHEEL
                else -> com.therealityteam.aeropages.model.SlideTransition.FADE
            }
            // Its Effect Options from the subtype and direction, as the writer puts them.
            fun side() = when (subtype) {
                "fromLeft" -> com.therealityteam.aeropages.model.TransitionDir.FROM_LEFT
                "fromTop" -> com.therealityteam.aeropages.model.TransitionDir.FROM_TOP
                "fromBottom" -> com.therealityteam.aeropages.model.TransitionDir.FROM_BOTTOM
                else -> com.therealityteam.aeropages.model.TransitionDir.FROM_RIGHT
            }
            slide.transitionDir = when (slide.transition) {
                com.therealityteam.aeropages.model.SlideTransition.PUSH, com.therealityteam.aeropages.model.SlideTransition.COVER, com.therealityteam.aeropages.model.SlideTransition.UNCOVER -> side()
                com.therealityteam.aeropages.model.SlideTransition.WIPE -> if (subtype == "topToBottom") (if (reverse) com.therealityteam.aeropages.model.TransitionDir.FROM_BOTTOM else com.therealityteam.aeropages.model.TransitionDir.FROM_TOP)
                    else if (reverse) com.therealityteam.aeropages.model.TransitionDir.FROM_RIGHT else com.therealityteam.aeropages.model.TransitionDir.FROM_LEFT
                com.therealityteam.aeropages.model.SlideTransition.SPLIT -> if (subtype == "vertical") (if (reverse) com.therealityteam.aeropages.model.TransitionDir.VERTICAL_IN else com.therealityteam.aeropages.model.TransitionDir.VERTICAL_OUT)
                    else if (reverse) com.therealityteam.aeropages.model.TransitionDir.HORIZONTAL_IN else com.therealityteam.aeropages.model.TransitionDir.HORIZONTAL_OUT
                com.therealityteam.aeropages.model.SlideTransition.RANDOM_BARS -> if (subtype == "vertical") com.therealityteam.aeropages.model.TransitionDir.VERTICAL_OUT else com.therealityteam.aeropages.model.TransitionDir.HORIZONTAL_OUT
                com.therealityteam.aeropages.model.SlideTransition.ZOOM, com.therealityteam.aeropages.model.SlideTransition.SHAPE -> if (reverse) com.therealityteam.aeropages.model.TransitionDir.OUT else com.therealityteam.aeropages.model.TransitionDir.IN
                else -> com.therealityteam.aeropages.model.TransitionDir.DEFAULT
            }
            // The page's own timing gives the real duration (LibreOffice's style keeps a nominal "fast").
            val dur = page.descendants("transitionFilter").firstNotNullOfOrNull { it.attr("dur") }?.let { d ->
                d.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toInt() }
            }
            slide.transitionSpeed = if (dur != null) com.therealityteam.aeropages.format.pptx.PptxReader.speedOf(dur) else when (speed) {
                "slow" -> com.therealityteam.aeropages.model.TransitionSpeed.SLOW
                "fast" -> com.therealityteam.aeropages.model.TransitionSpeed.FAST
                else -> com.therealityteam.aeropages.model.TransitionSpeed.MEDIUM
            }
        }

        val textFrames = ArrayList<OdfNode>()
        // Element ids (xml:id / draw:id) → what the page's animations animate.
        val animTargets = HashMap<String, com.therealityteam.aeropages.model.AnimTarget>()
        // Paragraph ids (a box built by paragraph animates its text:p one by one) → the box.
        val paragraphTargets = HashMap<String, com.therealityteam.aeropages.model.AnimTarget>()
        fun target(frame: OdfNode, t: com.therealityteam.aeropages.model.AnimTarget) {
            frame.attr("id")?.let { animTargets[it] = t }
            if (t is SlideBox) frame.descendants("p").forEach { p -> p.attr("id")?.let { paragraphTargets[it] = t } }
        }
        // A group's members (a group in a group joins the outer one) share its id: they select and move together.
        val groupOf = java.util.IdentityHashMap<OdfNode, Int>()
        // Where each text frame stood among the drawn shapes: a free text box keeps its place in the stacking order.
        val slotOf = java.util.IdentityHashMap<OdfNode, Int>()
        var groups = 0
        fun element(frame: OdfNode, group: Int?) {
            // A chart first: LibreOffice gives its frame a picture of it too (draw:image beside draw:object).
            val chart = if (frame.name == "frame" && frame.child("object") != null) chart(frame, files, pageW, pageH) else null
            when {
                chart != null -> { named(frame, chart); slide.charts.add(chart); target(frame, chart) }
                frame.name == "g" -> { val id = group ?: ++groups; frame.children.forEach { element(it, id) } }
                // A video or sound before a picture: its frame's draw:image is the poster it shows.
                frame.name == "frame" && frame.child("plugin") != null ->
                    (media(frame, files, pageW, pageH, styles) ?: image(frame, files, pageW, pageH, styles))?.let { it.hidden = hiddenOf(frame); slide.images.add(it); target(frame, it) }
                // A table first: Impress puts a preview picture of it in the same frame (`draw:image` of an .svm), only for readers without tables.
                frame.name == "frame" && frame.child("table") != null -> table(frame, styles, pageW, pageH)?.let { named(frame, it); slide.tables.add(it); target(frame, it) }
                frame.name == "frame" && frame.child("image") != null -> image(frame, files, pageW, pageH, styles)?.let { named(frame, it); slide.images.add(it); target(frame, it) }
                frame.name == "frame" && frame.descendants("table").isNotEmpty() -> table(frame, styles, pageW, pageH)?.let { named(frame, it); slide.tables.add(it); target(frame, it) }
                frame.name == "frame" && frame.child("object") != null -> Unit   // an object that is no chart (a formula …)
                frame.name == "frame" -> { textFrames.add(frame); slotOf[frame] = slide.shapes.size; group?.let { groupOf[frame] = it } }
                frame.name in DRAWN_ELEMENTS -> shape(frame, styles, pageW, pageH).let { it.groupId = group; named(frame, it); slide.shapes.add(it); target(frame, it.box) }
                // Older AeroDesk files marked a hidden slide with an empty classed notes element.
                frame.name == "notes" && frame.attr("class") == "hidden" -> slide.hidden = true
                frame.name == "notes" -> slide.notes = frame.descendants("p").joinToString("\n") { it.text }.trim()
                // LibreOffice's comments: a reply (this app marks them) goes under the comment before it.
                frame.name == "annotation" -> annotation(frame, pageW, pageH).let { c ->
                    val top = slide.comments.lastOrNull()
                    if (frame.attr("reply") == "true" && top != null) slide.comments[slide.comments.lastIndex] = top.copy(replies = top.replies + c)
                    else slide.comments.add(c)
                }
            }
        }
        for (frame in page.children) element(frame, null)

        // Placeholders by their class; in a file that classes none, the first frames are the title and body.
        val title = textFrames.firstOrNull { it.attr("class") == "title" }
            ?: if (classed) null else textFrames.firstOrNull { it.attr("class") == null }
        val outline = textFrames.firstOrNull { it.attr("class") == "outline" || it.attr("class") == "subtitle" }
            ?: if (classed) null else textFrames.firstOrNull { it !== title }

        title?.let { fill(slide.title, it, styles, inherited?.first, backdrop); slide.title.frame = boxFrame(it, pageW, pageH); target(it, slide.title) }
        outline?.let { fill(slide.body, it, styles, inherited?.second, backdrop); slide.body.frame = boxFrame(it, pageW, pageH); target(it, slide.body) }
        // Anything left over is a free text box rather than a placeholder — with its look, turn and
        // place — but not the page's header, footer, date or number, which the master draws.
        var inserted = 0
        textFrames.filter { it !== title && it !== outline && it.attr("class") !in CHROME_CLASSES }.forEach { frame ->
            val shape = shape(frame, styles, pageW, pageH)
            shape.groupId = groupOf[frame]
            named(frame, shape)
            slide.shapes.add(((slotOf[frame] ?: slide.shapes.size) + inserted).coerceAtMost(slide.shapes.size), shape)
            inserted++
            target(frame, shape.box)
        }
        OdfAnimations.read(page, animTargets, paragraphTargets)
        slide.layout = if (page.attr("master-page-name")?.replace("_20_", " ") == "Section Header") SlideLayout.SECTION
            else layoutOf(page.attr("presentation-page-layout-name"), pageLayouts, textFrames.map { it.attr("class") }, outline != null)
        return slide
    }

    /**
     * A page's layout: the one its page layout's placeholders make — a subtitle a title slide,
     * an outline (or any content) a content slide, a title alone or nothing a blank one (the app's
     * Title Only) — else the one its frames make; the app's section header by its layout's name
     * (and, above, PowerPoint's by the master page LibreOffice makes of its layout).
     */
    private fun layoutOf(name: String?, pageLayouts: Map<String, List<String>>, classes: List<String?>, body: Boolean): SlideLayout {
        if (name == OdpWriter.SECTION_LAYOUT) return SlideLayout.SECTION
        val objects = name?.let { pageLayouts[it] }
        return when {
            objects != null && "subtitle" in objects -> SlideLayout.TITLE
            objects != null -> if (objects.any { it != "title" }) SlideLayout.TITLE_CONTENT else SlideLayout.BLANK
            "subtitle" in classes -> SlideLayout.TITLE
            body -> SlideLayout.TITLE_CONTENT
            else -> SlideLayout.BLANK
        }
    }

    /** Copy a frame's paragraphs (and their formatting) into a slide box. */
    /**
     * Fill a box from a frame's paragraphs: one run per differently styled stretch (text:span,
     * text:a links), "\n" runs between paragraphs and at line breaks, so rich text round-trips.
     */
    private fun fill(box: SlideBox, frame: OdfNode, styles: OdfStyles, inherited: MasterText? = null, backdrop: Long? = null) {
        val leveled = ArrayList<Pair<OdfNode, Int>>()
        paragraphsWithLevels(frame, 0, leveled)
        val listed = frame.descendants("list").isNotEmpty()
        val firstStyle = styles.resolvedParagraph(leveled.firstOrNull()?.first?.attr("style-name"))
        // What the placeholder's own style (and the master's it names) gives its text where a paragraph
        // says nothing — kept on the runs only where it is not what the box inherits anyway (the
        // master's, or its design's). Sizes are the runs' own.
        val frameText = styles.resolvedParagraph(frame.attr("style-name"))?.text
        val defaultSize = frameText?.fontSizePt ?: inherited?.sizePt ?: box.defaultSizePt
        // A colour the app wrote for a dark page, where the master's would not read: the inherited one, turned.
        fun turned(c: Long) = inherited?.color != null && c != inherited.color && backdrop != null && c == com.therealityteam.aeropages.model.TextColors.over(inherited.color, backdrop)
        val runs = ArrayList<com.therealityteam.aeropages.model.TextRun>()
        leveled.forEachIndexed { i, (p, depth) ->
            val resolved = styles.resolvedParagraph(p.attr("style-name"))
            val t = resolved?.text
            val base = CharacterFormat(
                bold = t?.bold == true || (frameText?.bold == true && inherited?.bold != true), italic = t?.italic == true || frameText?.italic == true,
                underline = t?.underline == true, strikethrough = t?.strikethrough == true,
                fontSizePt = t?.fontSizePt ?: defaultSize,
                fontFamily = t?.fontFamily ?: frameText?.fontFamily?.takeIf { it != inherited?.font },
                color = (t?.color ?: frameText?.color?.takeIf { it != inherited?.color })?.takeUnless { turned(it) },
            )
            // The line's own runs first: the break before it ends the line above, in that line's look, and its
            // level's tabs take its text's — not the bare paragraph style's, where LibreOffice has moved the
            // look onto spans (a size in the paragraph style becomes each span's).
            val own = ArrayList<com.therealityteam.aeropages.model.TextRun>()
            collectRuns(p, base, null, own, styles, lineBreak = com.therealityteam.aeropages.model.SlideLevels.LINE_BREAK.toString())
            if (i > 0) runs.add(com.therealityteam.aeropages.model.TextRun("\n", (runs.lastOrNull()?.format ?: base).copyOf().also { it.link = null }))
            // A line's level: how deep in its lists it sits, or — outside a list — its indent in half-inch steps.
            val level = (if (listed) depth - 1 else ((resolved?.indentLeftPt ?: 0f) / LEVEL_PT).roundToInt())
                .coerceIn(0, com.therealityteam.aeropages.model.SlideLevels.MAX)
            if (level > 0) {
                val lead = (own.firstOrNull()?.format ?: base).copyOf().also { it.link = null }
                val first = own.firstOrNull()
                if (first != null && first.format == lead) own[0] = com.therealityteam.aeropages.model.TextRun("\t".repeat(level) + first.text, first.format)
                else runs.add(com.therealityteam.aeropages.model.TextRun("\t".repeat(level), lead))
            }
            runs.addAll(own)
        }
        if (runs.isEmpty()) runs.add(com.therealityteam.aeropages.model.TextRun("", CharacterFormat(fontSizePt = defaultSize)))
        box.paragraph.runs.clear()
        box.paragraph.runs.addAll(inheriting(runs, inherited))
        box.paragraph.format.align = firstStyle?.align ?: TextAlign.LEFT
        // A proportional line height is the box's line spacing (100% = single).
        box.lineSpacing = firstStyle?.lineSpacing?.takeIf { it in 0.3f..5f } ?: 1f
        if (frame.descendants("list").isNotEmpty()) {
            box.listStyle = if (styles.listKind(frame.descendants("list").first().attr("style-name")) == com.therealityteam.aeropages.model.ListKind.NUMBER)
                SlideListStyle.NUMBER else SlideListStyle.BULLET
        }
    }

    /**
     * [runs] with the face and colour they have from the master ([inherited]) left to it, as PowerPoint
     * keeps a placeholder's text: LibreOffice writes the master's look onto every span of text it saves,
     * which would otherwise pin each run to it (a later change to the master passing them by). Runs
     * that come to look alike are joined.
     */
    private fun inheriting(runs: List<com.therealityteam.aeropages.model.TextRun>, inherited: MasterText?): List<com.therealityteam.aeropages.model.TextRun> {
        if (inherited == null || (inherited.font == null && inherited.color == null)) return runs
        val out = ArrayList<com.therealityteam.aeropages.model.TextRun>(runs.size)
        for (r in runs) {
            val f = r.format
            val format = if ((inherited.font != null && f.fontFamily == inherited.font) || (inherited.color != null && f.color == inherited.color))
                f.copyOf().also { c -> if (c.fontFamily == inherited.font) c.fontFamily = null; if (c.color == inherited.color) c.color = null } else f
            val last = out.lastOrNull()
            if (last != null && last.text != "\n" && r.text != "\n" && last.format == format) out[out.lastIndex] = com.therealityteam.aeropages.model.TextRun(last.text + r.text, format)
            else out.add(if (format === f) r else com.therealityteam.aeropages.model.TextRun(r.text, format))
        }
        return out
    }

    /** Points a level indents by outside a list (as the writer indents). */
    private const val LEVEL_PT = 36f

    /** A frame's paragraphs in order, each with how many lists hold it. */
    private fun paragraphsWithLevels(node: OdfNode, depth: Int, out: MutableList<Pair<OdfNode, Int>>) {
        for (part in node.parts) if (part is OdfNode) when (part.name) {
            "p", "h" -> out.add(part to depth)
            "list" -> paragraphsWithLevels(part, depth + 1, out)
            else -> paragraphsWithLevels(part, depth, out)
        }
    }

    /**
     * Runs of a text container: spans layer their text style over [base]; links, spaces, tabs and
     * line breaks ([lineBreak]: a slide's is a line inside its item, a cell's a newline) are kept.
     * Shared with the ODS reader.
     */
    internal fun collectRuns(node: OdfNode, base: CharacterFormat, link: String?, out: MutableList<com.therealityteam.aeropages.model.TextRun>, styles: OdfStyles, lineBreak: String = "\n") {
        fun emit(text: String) {
            if (text.isEmpty()) return
            val format = base.copyOf().also { it.link = link }
            val last = out.lastOrNull()
            if (last != null && last.text != "\n" && last.format == format) out[out.lastIndex] = com.therealityteam.aeropages.model.TextRun(last.text + text, format)
            else out.add(com.therealityteam.aeropages.model.TextRun(text, format))
        }
        for (part in node.parts) {
            when (part) {
                is String -> emit(part)
                is OdfNode -> when (part.name) {
                    "span" -> collectRuns(part, merge(base, styles.text(part.attr("style-name")), styles.textTurnsOff(part.attr("style-name"))), link, out, styles, lineBreak)
                    "a" -> collectRuns(part, base, part.attr("href") ?: link, out, styles, lineBreak)
                    "s" -> emit(" ".repeat((part.attr("c")?.toIntOrNull() ?: 1).coerceIn(1, 200)))
                    "tab" -> emit("\t")
                    "line-break" -> emit(lineBreak)
                    else -> collectRuns(part, base, link, out, styles, lineBreak)
                }
            }
        }
    }

    /** A span's text style layered over its paragraph's: set values win, booleans add unless the span turns them [off]. */
    private fun merge(base: CharacterFormat, add: CharacterFormat?, off: Set<String> = emptySet()): CharacterFormat {
        if (add == null) return base
        return CharacterFormat(
            bold = (base.bold || add.bold) && "bold" !in off, italic = (base.italic || add.italic) && "italic" !in off,
            underline = (base.underline || add.underline) && "underline" !in off,
            strikethrough = (base.strikethrough || add.strikethrough) && "strikethrough" !in off,
            superscript = base.superscript || add.superscript, subscript = base.subscript || add.subscript,
            fontSizePt = add.fontSizePt ?: base.fontSizePt, fontFamily = add.fontFamily ?: base.fontFamily,
            color = add.color ?: base.color, highlight = add.highlight ?: base.highlight,
        )
    }

    private fun shape(node: OdfNode, styles: OdfStyles, pageW: Float, pageH: Float): SlideShape {
        val (shape, box) = readShape(node, styles)
        shape.xPct = (box[0] / pageW).coerceIn(-1f, 2f); shape.yPct = (box[1] / pageH).coerceIn(-1f, 2f)
        // A line or a freeform may lie flat; a shape keeps a point or so.
        val min = if (shape.type == ShapeType.LINE || shape.type == ShapeType.FREEFORM) 0f else SlideShape.MIN_FRACTION
        shape.wPct = (box[2] / pageW).coerceIn(min, 2f); shape.hPct = (box[3] / pageH).coerceIn(min, 2f)
        return shape
    }

    /**
     * The drawing elements that are shapes: custom shapes, rectangles, ellipses, lines and
     * connectors, and the freeform polygons, polylines and paths. Shared with the ODS and ODT readers.
     */
    internal val DRAWN_ELEMENTS = setOf("custom-shape", "rect", "ellipse", "line", "connector", "polygon", "polyline", "path")

    /**
     * The app's preset a custom shape's [geometry] is — its type, with the modifiers that type has
     * by default (a round rectangle's corner, a triangle's apex …) — or null when the app has none
     * of its look: then it is drawn from its own enhanced path. Impress's own names, and the OOXML
     * presets it keeps as "ooxml-…" for a shape from PowerPoint or Excel.
     */
    private fun presetOf(geometry: OdfNode?): ShapeType? {
        val type = geometry?.attr("type") ?: return ShapeType.RECT
        val mods = geometry.attr("modifiers")?.split(' ', ',')?.mapNotNull { it.toDoubleOrNull() }.orEmpty()
        fun default(vararg d: Double) = mods.zip(d.toList()).all { (a, b) -> kotlin.math.abs(a - b) < 0.5 }
        return when (type.removePrefix("ooxml-")) {
            "rectangle", "rect" -> ShapeType.RECT
            "ellipse", "circle" -> ShapeType.ELLIPSE
            "round-rectangle" -> if (default(3600.0)) ShapeType.ROUNDED else null
            "roundRect" -> if (default(16667.0)) ShapeType.ROUNDED else null
            "isosceles-triangle" -> if (default(10800.0)) ShapeType.TRIANGLE else null
            "triangle" -> if (default(50000.0)) ShapeType.TRIANGLE else null
            "star5", "star" -> ShapeType.STAR
            "right-arrow" -> if (default(16200.0, 5400.0)) ShapeType.ARROW else null
            "rightArrow" -> if (default(50000.0, 50000.0)) ShapeType.ARROW else null
            "arrow" -> ShapeType.ARROW
            // A text box of old Office files.
            "mso-spt202" -> ShapeType.TEXTBOX
            else -> null
        }
    }

    /**
     * A drawn shape (`draw:custom-shape`, `draw:rect`, `draw:ellipse`, `draw:line`, a
     * `draw:connector` as a straight line between its ends, a `draw:polygon`, `draw:polyline` or
     * `draw:path` as its freeform outline) or a text box (`draw:frame` holding a `draw:text-box`)
     * and its box in points (x, y, width, height) from what it is placed in: its geometry — a
     * preset the app draws, else the custom shape's own enhanced path worked out — and mirroring,
     * its text, and its graphic style's fill, stroke and text anchor. A line's box runs between
     * its ends, flipped as it runs.
     */
    internal fun readShape(node: OdfNode, styles: OdfStyles): Pair<SlideShape, FloatArray> {
        val geometry = node.child("enhanced-geometry")
        val line = node.name == "line" || node.name == "connector"
        val x1 = OdfValue.lengthPt(node.attr("x1")) ?: 0f; val y1 = OdfValue.lengthPt(node.attr("y1")) ?: 0f
        val x2 = OdfValue.lengthPt(node.attr("x2")) ?: 0f; val y2 = OdfValue.lengthPt(node.attr("y2")) ?: 0f
        val box = if (line) floatArrayOf(minOf(x1, x2), minOf(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
        else floatArrayOf(OdfValue.lengthPt(node.attr("x")) ?: 0f, OdfValue.lengthPt(node.attr("y")) ?: 0f,
            OdfValue.lengthPt(node.attr("width")) ?: 72f, OdfValue.lengthPt(node.attr("height")) ?: 36f)
        var path: com.therealityteam.aeropages.model.Freeform? = null
        val type = when (node.name) {
            "ellipse" -> ShapeType.ELLIPSE
            "line", "connector" -> ShapeType.LINE
            "frame" -> ShapeType.TEXTBOX
            "polygon", "polyline", "path" -> { path = OdfGeometry.drawing(node); if (path != null) ShapeType.FREEFORM else ShapeType.RECT }
            "custom-shape" -> presetOf(geometry) ?: run {
                path = geometry?.let { OdfGeometry.freeform(it, box[2], box[3]) }
                if (path != null) ShapeType.FREEFORM else ShapeType.RECT
            }
            else -> ShapeType.RECT
        }
        val shape = SlideShape(type)
        shape.path = path
        shape.action = clickOf(node)
        shape.hoverAction = clickOf(node, "dom:mouseover")
        if (type == ShapeType.TEXTBOX) { shape.fill = null; shape.border = null; shape.anchor = com.therealityteam.aeropages.model.ShapeAnchor.TOP }
        if (line) { shape.flipH = x2 < x1; shape.flipV = y2 < y1 }
        if (geometry?.attr("mirror-horizontal") == "true") shape.flipH = true
        if (geometry?.attr("mirror-vertical") == "true") shape.flipV = true
        if (type != ShapeType.LINE) node.attr("transform")?.let { t -> turn(t, box)?.let { shape.rotationDeg = it } }
        fill(shape.box, node, styles)
        // Fill / outline / text anchor from the shape's graphic style (parent chain resolved). A
        // style that says nothing keeps the type's defaults, as for a pptx shape without spPr.
        styles.resolvedGraphic(node.attr("style-name"))?.let { g ->
            if (g.filled == false) shape.fill = null else if (g.fillColor != null && g.filled != false) shape.fill = g.fillColor
            if (g.stroked == false) shape.border = null else if (g.strokeColor != null) shape.border = g.strokeColor
            g.strokeWidthPt?.let { if (it > 0f) shape.borderWidthPt = it.coerceIn(0.25f, 12f) }
            if (g.wrapOption == "no-wrap") shape.noWrap = true
            when (g.vAlign) {
                "top" -> shape.anchor = com.therealityteam.aeropages.model.ShapeAnchor.TOP
                "bottom" -> shape.anchor = com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM
                "middle" -> shape.anchor = com.therealityteam.aeropages.model.ShapeAnchor.CENTER
            }
            // A gradient: its two colours along its angle; any other kind (axial, radial …) as a linear one.
            styles.gradient(g.activeGradient)?.let { gr ->
                val start = gr.start ?: return@let
                val end = gr.end ?: start
                // Impress's radial kinds have their start colour at the edge and their end colour at the centre,
                // axial ones their start colour on the centre line and their end colour at the edges: the other
                // way round from the app's (first colour at the centre, or at the edges).
                val kind = OdfLines.gradientKind(gr.style)
                val swap = kind != com.therealityteam.aeropages.model.GradientKind.LINEAR
                shape.gradientKind = kind
                shape.fill = if (swap) end else start; shape.fillGradient = if (swap) start else end
                shape.gradientCx = gr.cx; shape.gradientCy = gr.cy
                // A radial gradient has no direction (LibreOffice writes it no angle): none, as PowerPoint's.
                shape.gradientAngle = if (kind == com.therealityteam.aeropages.model.GradientKind.RADIAL) 0f else OdfLines.odfAngle(gr.angleDeg)
                // Impress mirrors a custom shape's outline, not its fill; the app mirrors both.
                if (node.name == "custom-shape") {
                    shape.gradientAngle = OdfLines.mirrored(shape.gradientAngle, shape.flipH, shape.flipV)
                    if (shape.flipH) shape.gradientCx = 1f - shape.gradientCx
                    if (shape.flipV) shape.gradientCy = 1f - shape.gradientCy
                }
            }
            // A see-through fill: its colours' alpha — one opacity (`draw:opacity`), or an opacity gradient
            // (`draw:opacity-name`) from the first colour to the second (a solid fill fading as a gradient of its colour).
            fun alpha(c: Long, o: Float) = (c and 0xFFFFFFL) or (Math.round(o.coerceIn(0f, 1f) * 255).toLong() shl 24)
            val fade = styles.opacity(g.opacityName)
            val fill = shape.fill
            if (fade != null && fill != null && shape.type != ShapeType.LINE) {
                val kind = OdfLines.gradientKind(fade.style)
                val swap = kind != com.therealityteam.aeropages.model.GradientKind.LINEAR
                val first = if (swap) fade.end else fade.start
                val second = if (swap) fade.start else fade.end
                if (shape.fillGradient == null) {
                    shape.gradientKind = kind
                    shape.gradientAngle = if (kind == com.therealityteam.aeropages.model.GradientKind.RADIAL) 0f else OdfLines.odfAngle(fade.angleDeg)
                    shape.gradientCx = fade.cx; shape.gradientCy = fade.cy
                    if (node.name == "custom-shape") {
                        shape.gradientAngle = OdfLines.mirrored(shape.gradientAngle, shape.flipH, shape.flipV)
                        if (shape.flipH) shape.gradientCx = 1f - shape.gradientCx
                        if (shape.flipV) shape.gradientCy = 1f - shape.gradientCy
                    }
                }
                shape.fillGradient = alpha(shape.fillGradient ?: fill, second)
                shape.fill = alpha(fill, first)
            } else g.opacity?.takeIf { it < 1f }?.let { o ->
                shape.fill = shape.fill?.let { alpha(it, o) }
                shape.fillGradient = shape.fillGradient?.let { alpha(it, o) }
            }
            shape.shadow = shadowOf(g)
            // Dashes as the nearest preset; a line's markers as its arrowheads.
            styles.strokeDash(g.activeDash)?.let { shape.dash = OdfLines.dashOf(it, g.strokeWidthPt ?: 0f) }
            if (type == ShapeType.LINE || type == ShapeType.FREEFORM) {
                g.markerStart?.let { shape.beginArrow = OdfLines.endOf(it, styles.marker(it), g.markerStartWidthPt, g.strokeWidthPt ?: 0f) }
                g.markerEnd?.let { shape.endArrow = OdfLines.endOf(it, styles.marker(it), g.markerEndWidthPt, g.strokeWidthPt ?: 0f) }
            }
        }
        return shape to box
    }

    /**
     * A graphic style's shadow: its offset, colour and opacity (ODF's defaults: grey, opaque, 0.2 cm
     * down and right) and LibreOffice's blur — or none. Shared with the pictures of every module.
     */
    internal fun shadowOf(g: OdfStyles.GraphicStyle?): com.therealityteam.aeropages.model.ShapeShadow? {
        if (g?.shadow != true) return null
        val opacity = (g.shadowOpacity ?: 1f).coerceIn(0f, 1f)
        val rgb = (g.shadowColor ?: 0xFF808080) and 0xFFFFFF
        return com.therealityteam.aeropages.model.ShapeShadow(((Math.round(opacity * 255).toLong()) shl 24) or rgb,
            g.shadowDxPt ?: 5.67f, g.shadowDyPt ?: 5.67f, g.shadowBlurPt ?: 0f)
    }

    private val ROTATE = Regex("""rotate\s*\(\s*([-+]?[0-9.]+(?:[eE][-+]?[0-9]+)?)\s*\)""")
    private val TRANSLATE = Regex("""translate\s*\(\s*([^\s,)]+)[\s,]+([^\s,)]+)\s*\)""")

    /**
     * A `draw:transform`'s turn, clockwise degrees about the centre as the app turns a shape (null
     * for none), with [box]'s x and y moved to the unturned box. As Impress applies it: the shape
     * placed at svg:x / svg:y (LibreOffice gives none with a turn), turned anticlockwise about the
     * page's corner, then moved.
     */
    private fun turn(t: String, box: FloatArray): Float? {
        val a = ROTATE.find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val m = TRANSLATE.find(t)
        val tx = m?.let { OdfValue.lengthPt(it.groupValues[1]) } ?: 0f
        val ty = m?.let { OdfValue.lengthPt(it.groupValues[2]) } ?: 0f
        val cx = box[2] / 2.0; val cy = box[3] / 2.0
        // Where the centre lands: ODF's rotate(a), y downwards, then the move.
        val px = box[0] + cx; val py = box[1] + cy
        val wx = px * kotlin.math.cos(a) + py * kotlin.math.sin(a) + tx
        val wy = -px * kotlin.math.sin(a) + py * kotlin.math.cos(a) + ty
        box[0] = (wx - cx).toFloat(); box[1] = (wy - cy).toFloat()
        if (a == 0.0) return null
        // Within ±180°, a turn back anticlockwise negative (as the app and a spreadsheet keep one).
        var deg = ((-Math.toDegrees(a)) % 360.0 + 360.0) % 360.0
        if (deg > 180.0) deg -= 360.0
        return (Math.round(deg * 100.0) / 100.0).toFloat().takeIf { it != 0f }
    }

    /** [obj]'s name (`draw:name`, not one a program numbers) and whether it is hidden ([hiddenOf]), for the Selection Pane. */
    private fun named(frame: OdfNode, obj: com.therealityteam.aeropages.model.SlideObject) {
        obj.objectName = frame.attr("name")?.trim()?.takeIf { it.isNotEmpty() && !com.therealityteam.aeropages.format.pptx.PptxReader.AUTO_NAME.matches(it) }
        obj.hidden = hiddenOf(frame)
    }

    /** LibreOffice's hidden object: `drawooo:display="none"` (or ODF's `draw:display`). */
    private fun hiddenOf(frame: OdfNode): Boolean = frame.attr("display") == "none"

    /** An embedded chart object (`draw:object` → `Object N/content.xml`) at the frame's position. */
    private fun chart(frame: OdfNode, files: Map<String, ByteArray>, pageW: Float, pageH: Float): com.therealityteam.aeropages.model.SlideChart? {
        val href = frame.child("object")?.attr("href")?.removePrefix("./")?.trimEnd('/') ?: return null
        val chart = OdfChart.read(files["$href/content.xml"]) ?: return null
        OdfValue.lengthPt(frame.attr("x"))?.let { chart.xPct = (it / pageW).coerceIn(0f, 1f) }
        OdfValue.lengthPt(frame.attr("y"))?.let { chart.yPct = (it / pageH).coerceIn(0f, 1f) }
        OdfValue.lengthPt(frame.attr("width"))?.let { chart.wPct = (it / pageW).coerceIn(0.05f, 1f) }
        OdfValue.lengthPt(frame.attr("height"))?.let { chart.hPct = (it / pageH).coerceIn(0.05f, 1f) }
        return chart
    }

    /** A placeholder frame's geometry as slide fractions (ODF frames always carry it). */
    private fun boxFrame(node: OdfNode, pageW: Float, pageH: Float): com.therealityteam.aeropages.model.BoxFrame? {
        val x = OdfValue.lengthPt(node.attr("x")) ?: return null
        val y = OdfValue.lengthPt(node.attr("y")) ?: return null
        val w = OdfValue.lengthPt(node.attr("width")) ?: return null
        val h = OdfValue.lengthPt(node.attr("height")) ?: return null
        if (w <= 0f || h <= 0f) return null
        return com.therealityteam.aeropages.model.BoxFrame(
            (x / pageW).coerceIn(0f, 1f), (y / pageH).coerceIn(0f, 1f), (w / pageW).coerceIn(0.02f, 1f), (h / pageH).coerceIn(0.02f, 1f))
    }

    /** Presentation classes of a page's chrome: fields the master shows, not text of the slide's own. */
    /** A LibreOffice annotation as a slide comment: its author, date (without a zone, UTC), lines and place. */
    private fun annotation(node: OdfNode, pageW: Float, pageH: Float): com.therealityteam.aeropages.model.SlideComment {
        val date = node.child("date")?.text?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
            runCatching { java.time.OffsetDateTime.parse(d).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { java.time.LocalDateTime.parse(d.substringBefore('Z')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        } ?: 0L
        return com.therealityteam.aeropages.model.SlideComment(
            node.child("creator")?.text?.trim().orEmpty(), node.children("p").joinToString("\n") { it.text }.trim(), date,
            ((OdfValue.lengthPt(node.attr("x")) ?: 0f) / pageW).coerceIn(0f, 1f), ((OdfValue.lengthPt(node.attr("y")) ?: 0f) / pageH).coerceIn(0f, 1f),
            resolved = node.attr("resolved") == "true",
        )
    }

    private val CHROME_CLASSES = setOf("header", "footer", "date-time", "page-number", "notes", "page", "handout")

    /**
     * A video or sound — a frame's `draw:plugin`, its file in the package (LibreOffice's `Media/…`, or
     * where a conversion left it) or a link elsewhere — as the picture showing it: the frame's
     * `draw:image` (LibreOffice's poster), else a black frame. Null for a plug-in that plays neither.
     */
    private fun media(frame: OdfNode, files: Map<String, ByteArray>, pageW: Float, pageH: Float, styles: OdfStyles): SlideImage? {
        val plugin = frame.child("plugin") ?: return null
        val href = plugin.attr("href")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mime = com.therealityteam.aeropages.format.MediaTypes.ofPlugin(href, plugin.attr("mime-type")) ?: return null
        val part = OdfPackage.partOf(href, files.keys)
        val data = part?.let { files[it] }
        // Its name as the slide shows it (LibreOffice names the frame for the file it was inserted from), else the file's.
        val name = frame.attr("name")?.takeIf { com.therealityteam.aeropages.format.MediaTypes.isMedia(it) }
            ?: (part ?: href.substringBefore('?')).substringAfterLast('/').substringAfterLast('\\')
        val kind = if (mime.startsWith("audio/")) com.therealityteam.aeropages.model.SlideMedia.Kind.AUDIO else com.therealityteam.aeropages.model.SlideMedia.Kind.VIDEO
        val img = image(frame, files, pageW, pageH, styles, poster = BLACK_PNG) ?: return null
        img.media = com.therealityteam.aeropages.model.SlideMedia(kind, mime, data, if (data == null) href else null, name)
        return img
    }

    /** A 1×1 black PNG: the poster of a video without one. */
    private val BLACK_PNG = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0, -112, 119, 83, -34, 0, 0, 0, 12,
        73, 68, 65, 84, 120, -100, 99, 96, 96, 96, 0, 0, 0, 4, 0, 1, -10, 23, 56, 85, 0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126,
    )

    /** A frame's picture ([poster] when it has none); null when it has none and no [poster] is given. */
    private fun image(frame: OdfNode, files: Map<String, ByteArray>, pageW: Float, pageH: Float, styles: OdfStyles, poster: ByteArray? = null): SlideImage? {
        val href = frame.child("image")?.attr("href")?.removePrefix("./")
        val data = href?.let { files[it] } ?: poster ?: return null
        val w = OdfValue.lengthPt(frame.attr("width")) ?: (pageW * 0.5f)
        val h = OdfValue.lengthPt(frame.attr("height")) ?: (pageH * 0.4f)
        val mime = com.therealityteam.aeropages.format.PictureType.mimeOf(data, href ?: "poster.png")
        // The bitmap's own pixel size (its aspect), not the frame's.
        val (px, py) = com.therealityteam.aeropages.format.ImageSize.of(data) ?: (w to h)
        // Placed by svg:x / svg:y — or, turned, by its transform (as a turned shape is).
        val box = floatArrayOf(OdfValue.lengthPt(frame.attr("x")) ?: 0f, OdfValue.lengthPt(frame.attr("y")) ?: 0f, w, h)
        val turned = frame.attr("transform")?.let { turn(it, box) }
        val img = SlideImage(
            data, mime, px, py,
            xPct = box[0] / pageW,
            yPct = box[1] / pageH,
            wPct = (w / pageW).coerceIn(SlideShape.MIN_FRACTION, 1.5f),
            hPct = (h / pageH).coerceIn(SlideShape.MIN_FRACTION, 1.5f),
        )
        turned?.let { img.rotationDeg = it }
        img.action = clickOf(frame)
        img.hoverAction = clickOf(frame, "dom:mouseover")
        val look = styles.resolvedGraphic(frame.attr("style-name"))
        com.therealityteam.aeropages.format.PictureCrop.readFoClip(look?.clip, data, img)
        img.shadow = shadowOf(look)
        OdfAltText.read(frame, look).let { (alt, decorative) -> img.altText = alt; img.decorative = decorative }
        // Mirrored as its style says (LibreOffice writes a flipped picture's style:mirror) — mirrored
        // and turned past a quarter, flipped top to bottom (how Impress keeps that flip).
        look?.mirror?.split(' ')?.let { m -> img.flipH = "horizontal" in m; img.flipV = "vertical" in m }
        if (img.flipH && !img.flipV && kotlin.math.abs(img.rotationDeg) >= 89.99f) {
            var d = img.rotationDeg - 180f
            if (d < -180f) d += 360f
            img.rotationDeg = if (kotlin.math.abs(d) < 0.01f) 0f else d
            img.flipH = false; img.flipV = true
        }
        return img
    }

    /** [sizes] as a table's column or row weights: null unless there is one per column (row), all above 0, and they differ. */
    private fun sizesToWeights(sizes: List<Float>, n: Int): List<Float>? {
        if (sizes.size != n || n < 2 || sizes.any { it <= 0f }) return null
        val mean = sizes.average().toFloat()
        return sizes.takeIf { s -> s.any { kotlin.math.abs(it - mean) > mean * 0.005f } }
    }

    private fun table(frame: OdfNode, styles: OdfStyles, pageW: Float, pageH: Float): SlideTable? {
        val tableNode = frame.descendants("table").firstOrNull() ?: return null
        val rows = tableNode.children("table-row")
        if (rows.isEmpty()) return null
        // Each cell's text with its own look and links, as a text box's; its fill by its `table-cell` style (or its row's default).
        // Every grid place: its cell, or the covered cell a merged one spans over (kept, hidden, so the grid stays whole).
        fun placesOf(r: OdfNode) = r.children.filter { it.name == "table-cell" || it.name == "covered-table-cell" }
        val names = rows.map { r -> placesOf(r).map { c -> c.attr("style-name") ?: r.attr("default-cell-style-name") } }
        val fills = names.map { row -> row.map { styles.tableCellFill(it) } }
        val cells = rows.map { r ->
            placesOf(r).map { c -> SlideBox("").also { box ->
                fill(box, c, styles)
                if (c.name == "table-cell") {
                    box.colSpan = (c.attr("number-columns-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    box.rowSpan = (c.attr("number-rows-spanned")?.toIntOrNull() ?: 1).coerceAtLeast(1)
                }
            } }.toMutableList()
        }.toMutableList()
        // Each cell's text from the top, middle or bottom (middle, the app's own, as none).
        cells.forEachIndexed { r, row -> row.forEachIndexed { c, box ->
            box.cellAnchor = when (styles.tableCellAnchor(names.getOrNull(r)?.getOrNull(c))) {
                "top" -> com.therealityteam.aeropages.model.ShapeAnchor.TOP
                "bottom" -> com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM
                else -> null
            }
        } }
        val table = SlideTable(cells)
        // Its style options as written; else, from LibreOffice's (which writes none), what its fills show.
        fun opt(name: String) = tableNode.attr(name)?.let { it == "true" }
        val look = com.therealityteam.aeropages.model.SlideTableLook
        val firstFill = fills.firstOrNull()?.firstOrNull()
        val style = look.STYLES.indexOfFirst { it.header == firstFill }.takeIf { it >= 0 }
            ?: look.STYLES.indexOfFirst { it.band == firstFill || it.plain == firstFill }.takeIf { it >= 0 }
        style?.let { table.styleIndex = it }
        val header = look.STYLES.getOrNull(table.styleIndex)?.header
        table.headerRow = opt("use-first-row-styles") ?: (firstFill != null && firstFill == header)
        table.totalRow = opt("use-last-row-styles") ?: (rows.size > 1 && fills.last().firstOrNull()?.let { it == header } == true)
        table.firstColumn = opt("use-first-column-styles") ?: false
        table.lastColumn = opt("use-last-column-styles") ?: false
        table.bandedRows = opt("use-banding-rows-styles") ?: (fills.size > 2 && fills[if (table.headerRow) 1 else 0].firstOrNull() == look.STYLES.getOrNull(table.styleIndex)?.band)
        table.bandedColumns = opt("use-banding-columns-styles") ?: false
        // The style most cells' fills are; a cell filled otherwise has its own shading (Table Design ▸ Shading).
        // Its columns' widths and rows' heights (repeated columns count each), when they are not all alike.
        val colW = tableNode.children("table-column").flatMap { col ->
            List((col.attr("number-columns-repeated")?.toIntOrNull() ?: 1).coerceIn(1, 64)) { styles.columnWidth(col.attr("style-name")) ?: 0f }
        }
        sizesToWeights(colW, table.cols)?.let { table.colWeights.addAll(it) }
        sizesToWeights(rows.map { r -> r.attr("style-name").let { styles.rowHeight(it) ?: styles.rowMinHeight(it) ?: 0f } }, table.rows)?.let { table.rowWeights.addAll(it) }
        look.adopt(table) { r, c -> fills.getOrNull(r)?.getOrNull(c) }
        // A cell whose lines are not the style's grid has its own borders (Table Design ▸ Borders); a side left unsaid is the grid.
        look.adoptBorders(table) { r, c, side ->
            val v = styles.tableCellSide(names.getOrNull(r)?.getOrNull(c), side.ordinal) ?: return@adoptBorders look.styleLine(table)
            OdfBorders.cellLineOf(v)?.let { if (it.color == null) it.copy(color = 0xFF000000) else it }   // black, which ODF's reader takes as automatic
        }
        look.tidyCovered(table)
        OdfValue.lengthPt(frame.attr("x"))?.let { table.xPct = it / pageW }
        OdfValue.lengthPt(frame.attr("y"))?.let { table.yPct = it / pageH }
        OdfValue.lengthPt(frame.attr("width"))?.let { table.wPct = (it / pageW).coerceIn(0.05f, 1.5f) }
        OdfValue.lengthPt(frame.attr("height"))?.let { table.hPct = (it / pageH).coerceIn(0.05f, 1.5f) }
        return table
    }
}
