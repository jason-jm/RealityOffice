package com.therealityteam.aeropages.model

/** The cover pages Insert ▸ Cover Page offers. */
enum class CoverDesign { SIMPLE, BAND, SIDELINE, BLOCK }

/**
 * Word's cover pages: a first page of its own — title, subtitle, author and date set out in one of the [CoverDesign]s —
 * ending in a page break. A document's cover ([Document.coverPage]) is its blocks up to that break.
 */
object CoverPages {
    /** What a cover says: the document's title, subtitle, author and date (Word fills them from the file's properties). */
    class Fields(val title: String, val subtitle: String, val author: String, val date: String)

    /** The index of the page break that ends [doc]'s cover page (its blocks are 0 through it), or null without one. */
    fun end(doc: Document): Int? {
        if (!doc.coverPage) return null
        val i = doc.blocks.indexOfFirst { it is ParagraphBlock && (it.paragraph.isPageBreak() || it.paragraph.format.sectionBreak != null) }
        return i.takeIf { it >= 0 }
    }

    /** Word's text placeholder: "[Document title]" and the like, chosen whole so typing replaces it. */
    fun isPlaceholder(text: String): Boolean = text.length > 2 && text.first() == '[' && text.last() == ']'

    /**
     * The blocks of a [design] cover for pages set up as [pg]: its paragraphs (the title first), any shape it draws behind
     * them (floating from the first paragraph, placed on the page), and the page break that ends it. [accent] colours it.
     */
    fun blocks(design: CoverDesign, pg: PageSetup, f: Fields, accent: Long): List<Block> {
        val w = pg.widthPt; val h = pg.heightPt
        val ink = if (design == CoverDesign.BLOCK) WHITE else DARK
        fun para(text: String, size: Float, color: Long, bold: Boolean = false, before: Float = 0f, after: Float = 6f, style: ParagraphStyle = ParagraphStyle.NORMAL) =
            Paragraph(mutableListOf(TextRun(text, CharacterFormat(fontSizePt = size, bold = bold, color = color))),
                ParagraphFormat(style = style, spaceBeforePt = before, spaceAfterPt = after, lineSpacing = 1f))
        val out = ArrayList<Block>()
        when (design) {
            CoverDesign.SIMPLE -> {
                // A large title over a rule in the accent colour, the subtitle under it; author and date low on the page.
                val title = para(f.title, 40f, DARK, bold = true, before = h * 0.26f - pg.marginTopPt, after = 10f, style = ParagraphStyle.TITLE)
                title.format.borders = ParagraphBorders(bottom = CellBorder(LineStyle.THIN, 2.25f, accent), spaceBottom = 8f)
                out += ParagraphBlock(title)
                out += ParagraphBlock(para(f.subtitle, 18f, GREY, before = 6f))
                out += ParagraphBlock(para(f.author, 12f, DARK, bold = true, before = h * 0.30f))
                out += ParagraphBlock(para(f.date, 12f, GREY))
            }
            CoverDesign.BAND -> {
                // A band of the accent colour across the page a quarter of the way down, the title and subtitle in white on it.
                val bandTop = h * 0.22f; val bandH = h * 0.22f
                out += ParagraphBlock(para(f.title, 40f, WHITE, bold = true, before = bandTop - pg.marginTopPt + 40f, after = 8f, style = ParagraphStyle.TITLE))
                out += behind(accent, w, bandH, 0f, bandTop)
                out += ParagraphBlock(para(f.subtitle, 18f, WHITE))
                out += ParagraphBlock(para(f.author, 12f, DARK, bold = true, before = h * 0.30f))
                out += ParagraphBlock(para(f.date, 12f, GREY))
            }
            CoverDesign.SIDELINE -> {
                // A thick line in the accent colour beside the title and subtitle (their shared left border), the date above them.
                val line = CellBorder(LineStyle.THIN, 4.5f, accent)
                out += ParagraphBlock(para(f.date, 12f, GREY, before = h * 0.18f - pg.marginTopPt, after = 18f))
                for ((i, text) in listOf(f.title, f.subtitle).withIndex()) {
                    val p = if (i == 0) para(text, 36f, DARK, bold = true, after = 6f, style = ParagraphStyle.TITLE) else para(text, 18f, accent, after = 0f)
                    p.format.borders = ParagraphBorders(left = line, spaceLeft = 14f)
                    out += ParagraphBlock(p)
                }
                out += ParagraphBlock(para(f.author, 12f, DARK, bold = true, before = h * 0.36f))
            }
            CoverDesign.BLOCK -> {
                // The whole page in the accent colour, everything on it in white.
                out += ParagraphBlock(para(f.title, 44f, ink, bold = true, before = h * 0.30f - pg.marginTopPt, after = 10f, style = ParagraphStyle.TITLE))
                out += behind(accent, w, h, 0f, 0f)
                out += ParagraphBlock(para(f.subtitle, 18f, ink))
                out += ParagraphBlock(para(f.author, 12f, ink, bold = true, before = h * 0.30f))
                out += ParagraphBlock(para(f.date, 12f, ink))
            }
        }
        out += ParagraphBlock(Paragraph(mutableListOf(TextRun(PAGE_BREAK))))
        return out
    }

    /** A rectangle of [fill] behind the text, [w]×[h] points at ([x], [y]) on the page. */
    private fun behind(fill: Long, w: Float, h: Float, x: Float, y: Float): TextBoxBlock {
        val shape = SlideShape(ShapeType.RECT, fill = fill, border = null, borderWidthPt = 0f)
        return TextBoxBlock(mutableListOf(Paragraph.of("")), w, h, shape,
            FloatPosition(x, FloatFrame.PAGE, null, y, FloatFrame.PAGE, null, FloatWrap.BEHIND))
    }

    private const val WHITE = 0xFFFFFFFF
    private const val DARK = 0xFF1F2937
    private const val GREY = 0xFF595959
}
