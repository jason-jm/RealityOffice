package com.therealityteam.aeropages.model

/**
 * Where a link in a slide's text, or a click on a shape or picture, goes in the slide show — besides
 * an address: a slide of the presentation (`#slide:<id>`, by the slide's [Slide.id], so that moving
 * slides keeps it), or the next or previous slide, the first or the last, or the end of the show
 * (PowerPoint's `ppaction://hlinksldjump` and `hlinkshowjump`, Impress's `presentation:action`).
 */
object SlideLinks {
    const val NEXT = "#next"
    const val PREVIOUS = "#previous"
    const val FIRST = "#first"
    const val LAST = "#last"
    const val END = "#end"
    private const val SLIDE = "#slide:"

    /** The show's own steps, in the order a menu offers them. */
    val JUMPS = listOf(NEXT, PREVIOUS, FIRST, LAST, END)

    /** A link to [slide]. */
    fun toSlide(slide: Slide): String = SLIDE + slide.id

    /** The id of the slide [link] goes to, or null when it goes to no one slide. */
    fun slideId(link: String?): Long? = link?.takeIf { it.startsWith(SLIDE) }?.substring(SLIDE.length)?.toLongOrNull()

    /** Whether [link] goes somewhere in the show (a slide, or one of its steps) rather than to an address. */
    fun inShow(link: String?): Boolean = link != null && (link in JUMPS || slideId(link) != null)

    /** The index of the slide [link] goes to in [p] (null: it goes to no one slide, or that slide is gone). */
    fun indexIn(p: Presentation, link: String?): Int? {
        val id = slideId(link) ?: return null
        return p.slides.indexOfFirst { it.id == id }.takeIf { it >= 0 }
    }

    /**
     * Passes every link of [p] through [map] — the links in its slides' text (titles, bodies, shapes,
     * tables) and the click actions of its shapes and pictures —, a null result taking the link off.
     * Readers resolve the slide a link names in the file this way, once every slide is read.
     */
    fun remap(p: Presentation, map: (String) -> String?) {
        fun box(b: SlideBox) {
            if (b.paragraph.runs.none { it.format.link != null }) return
            b.paragraph.runs.forEach { r -> r.format.link?.let { r.format.link = map(it) } }
        }
        p.slides.forEach { s ->
            box(s.title); box(s.body)
            s.shapes.forEach { sh -> box(sh.box); sh.action = sh.action?.let(map); sh.hoverAction = sh.hoverAction?.let(map) }
            s.tables.forEach { t -> t.cells.forEach { row -> row.forEach(::box) } }
            s.images.forEach { img -> img.action = img.action?.let(map); img.hoverAction = img.hoverAction?.let(map) }
        }
    }
}
