package com.therealityteam.aeropages.model

/**
 * Word's Layout ▸ Margins and Size galleries: the usual margins (points: top, bottom, left, right) and paper sizes
 * (portrait points), and which a page has.
 */
object PagePresets {
    /** Millimetres in points. */
    fun mm(v: Float) = v * 72f / 25.4f

    enum class MarginKind { NORMAL, NARROW, MODERATE, WIDE }

    /** Margins as Word sets them: its Normal 2.54 cm above and below and — where [cjk], as Chinese and Japanese Word have it — 3.18 cm at the sides, else 2.54 cm. */
    class Margins(val kind: MarginKind, val top: Float, val bottom: Float, val left: Float, val right: Float)

    fun margins(cjk: Boolean): List<Margins> = listOf(
        Margins(MarginKind.NORMAL, 72f, 72f, if (cjk) 90f else 72f, if (cjk) 90f else 72f),
        Margins(MarginKind.NARROW, 36f, 36f, 36f, 36f),
        Margins(MarginKind.MODERATE, 72f, 72f, 54f, 54f),
        Margins(MarginKind.WIDE, 72f, 72f, 144f, 144f),
    )

    /** The preset [pg]'s margins are (within half a point), or null. */
    fun marginsOf(pg: PageSetup, cjk: Boolean): MarginKind? = margins(cjk).firstOrNull {
        near(pg.marginTopPt, it.top) && near(pg.marginBottomPt, it.bottom) && near(pg.marginLeftPt, it.left) && near(pg.marginRightPt, it.right)
    }?.kind

    /** A paper size: its name as Word lists it and its portrait width and height. */
    class Paper(val name: String, val widthPt: Float, val heightPt: Float)

    val PAPERS = listOf(
        Paper("A4", mm(210f), mm(297f)),
        Paper("A3", mm(297f), mm(420f)),
        Paper("A5", mm(148f), mm(210f)),
        Paper("B5 (JIS)", mm(182f), mm(257f)),
        Paper("16K", mm(184f), mm(260f)),
        Paper("32K", mm(130f), mm(184f)),
        Paper("Letter", 612f, 792f),
        Paper("Legal", 612f, 1008f),
        Paper("Executive", 522f, 756f),
    )

    /** The paper [pg] is, either way round (within a point), or null. */
    fun paperOf(pg: PageSetup): Paper? {
        val w = minOf(pg.widthPt, pg.heightPt); val h = maxOf(pg.widthPt, pg.heightPt)
        return PAPERS.firstOrNull { kotlin.math.abs(it.widthPt - w) < 1f && kotlin.math.abs(it.heightPt - h) < 1f }
    }

    /** The line grid's pitch giving [lines] lines in [pg]'s body (Word's 文档网格 ▸ 每页行数). */
    fun pitchFor(pg: PageSetup, lines: Int): Float = (pg.heightPt - pg.marginTopPt - pg.marginBottomPt) / lines.coerceAtLeast(1)

    /** How many lines of its grid [pg]'s body holds. */
    fun linesOf(pg: PageSetup): Int? = pg.linePitchPt?.takeIf { it > 0f }?.let { ((pg.heightPt - pg.marginTopPt - pg.marginBottomPt) / it + 0.01f).toInt().coerceAtLeast(1) }

    private fun near(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.5f

    /** Where Word and Excel start a new file on US Letter: the United States and the American countries that use it, and
     *  the Philippines (or plain English with no country); A4 everywhere else. */
    fun usesLetter(locale: java.util.Locale = java.util.Locale.getDefault()): Boolean =
        locale.country in LETTER_COUNTRIES || (locale.language == "en" && locale.country.isEmpty())

    private val LETTER_COUNTRIES = setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "PR", "GT", "CR", "PA", "DO", "SV")

    /** A new document's page where the reader is: Letter or A4, with Word's Normal margins (wider at the sides for
     *  Chinese and Japanese). */
    fun regionalPage(locale: java.util.Locale = java.util.Locale.getDefault()): PageSetup {
        val cjk = locale.language == "zh" || locale.language == "ja"
        val paper = if (usesLetter(locale)) PAPERS.first { it.name == "Letter" } else PAPERS.first { it.name == "A4" }
        val m = margins(cjk).first()
        return PageSetup(widthPt = paper.widthPt, heightPt = paper.heightPt, marginTopPt = m.top, marginBottomPt = m.bottom,
            marginLeftPt = m.left, marginRightPt = m.right)
    }
}
