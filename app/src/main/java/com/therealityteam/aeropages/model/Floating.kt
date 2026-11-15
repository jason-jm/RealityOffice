package com.therealityteam.aeropages.model

/**
 * Where a floating picture or text box stands. It is anchored to a paragraph — the one before it in
 * the document's blocks — but placed on the page, as Word's `wp:anchor`, a .doc FSPA and ODF's frames
 * anchored to a paragraph place theirs: [x] points right of [hFrom]'s left edge (or aligned in it by
 * [hAlign]) and [y] points below [vFrom]'s top (or aligned by [vAlign]). [wrap] says how the text
 * goes by it.
 */
data class FloatPosition(
    var x: Float = 0f,
    var hFrom: FloatFrame = FloatFrame.COLUMN,
    var hAlign: FloatAlign? = null,
    var y: Float = 0f,
    var vFrom: FloatFrame = FloatFrame.PARAGRAPH,
    var vAlign: FloatAlign? = null,
    var wrap: FloatWrap = FloatWrap.SQUARE,
    /**
     * A header's or footer's picture that stands in its line (Word's `wp:inline` there, ODF's `as-char`
     * frame): on the header's line [line] (0-based), side by side with that line's other pictures, the line
     * as tall as the tallest of them (see HeaderFooters.layout) — [hAlign] how a line holding no text
     * places them; [x] and [y] then mean nothing. Null for any other.
     */
    var line: Int? = null,
) {
    /** Over the page, not in the text: behind it or in front of it (Word's "Behind text", "In front of text"). */
    val overlay: Boolean get() = wrap == FloatWrap.BEHIND || wrap == FloatWrap.FRONT

    /**
     * Drawn over its page where it stands, taking no room in the text: behind or in front of the text, placed
     * down the page from anything but its paragraph (a cover's title table, a letterhead's logo), or one the
     * text goes around (square wrapping: the lines beside it narrowed, or — too little room beside it — the
     * text stepping over its height). One the text goes above and below that moves with its paragraph stays
     * in the flow, after the paragraph, keeping its place across.
     */
    val onPage: Boolean get() = overlay || vFrom != FloatFrame.PARAGRAPH || wrap == FloatWrap.SQUARE
}

/** What a floating object's position counts from. */
enum class FloatFrame {
    /** The page's edges. */
    PAGE,
    /** The margins: the box the text is set in. */
    MARGIN,
    /** Its column, across (on a page of one column, the margins' box); down, the margins' box. */
    COLUMN,
    /** Its paragraph: down from its top (across, its column). */
    PARAGRAPH,
    /** The left margin's strip, the right one's, the band above the text and the band below it. */
    LEFT_MARGIN, RIGHT_MARGIN, TOP_MARGIN, BOTTOM_MARGIN,
}

/** Placed in its frame instead of at an offset: at its start (left / top), centred, at its end (right / bottom). */
enum class FloatAlign { START, CENTER, END }

/**
 * How the text goes by a floating object: around its box (Word's square, tight and through wrapping),
 * above and below it only, or not at all — the object drawn behind the text or in front of it.
 */
enum class FloatWrap { SQUARE, TOP_BOTTOM, BEHIND, FRONT }

/** Where floating objects land on a page. Lengths are in one unit throughout — points, or pixels at [unit] per point. */
object FloatPlacement {
    /**
     * The top-left corner of a [w] × [h] object positioned by [pos] on a page [pageW] wide with [pg]'s
     * height and margins (in points, scaled by [unit]); its column starts at [colX] and is [colW] wide,
     * its paragraph's top is at [paraTop] (from the page's top).
     */
    fun place(pos: FloatPosition, w: Float, h: Float, pg: PageSetup, pageW: Float, unit: Float, colX: Float, colW: Float, paraTop: Float): Pair<Float, Float> {
        val (l, r) = across(pos, pg, pageW, unit, colX, colW)
        val (t, b) = down(pos, h, pg, unit, paraTop)
        val x = when (pos.hAlign) {
            null -> l + pos.x * unit
            FloatAlign.START -> l
            FloatAlign.CENTER -> (l + r - w) / 2f
            FloatAlign.END -> r - w
        }
        val y = when (pos.vAlign) {
            null -> t + pos.y * unit
            FloatAlign.START -> t
            FloatAlign.CENTER -> (t + b - h) / 2f
            FloatAlign.END -> b - h
        }
        return x to y
    }

    /** The left and right edges of [pos]'s frame across (see [place]). */
    private fun across(pos: FloatPosition, pg: PageSetup, pageW: Float, unit: Float, colX: Float, colW: Float): Pair<Float, Float> {
        val ml = pg.marginLeftPt * unit; val mr = pg.marginRightPt * unit
        return when (pos.hFrom) {
            FloatFrame.PAGE -> 0f to pageW
            FloatFrame.MARGIN -> ml to pageW - mr
            FloatFrame.COLUMN, FloatFrame.PARAGRAPH -> colX to colX + colW
            FloatFrame.LEFT_MARGIN -> 0f to ml
            FloatFrame.RIGHT_MARGIN -> pageW - mr to pageW
            FloatFrame.TOP_MARGIN, FloatFrame.BOTTOM_MARGIN -> ml to pageW - mr
        }
    }

    /** The top and bottom of [pos]'s frame down (see [place]); a paragraph's is the object's [h] from its top. */
    private fun down(pos: FloatPosition, h: Float, pg: PageSetup, unit: Float, paraTop: Float): Pair<Float, Float> {
        val pageH = pg.heightPt * unit
        val mt = pg.marginTopPt * unit; val mb = pg.marginBottomPt * unit
        return when (pos.vFrom) {
            FloatFrame.PAGE -> 0f to pageH
            FloatFrame.PARAGRAPH -> paraTop to paraTop + h
            FloatFrame.TOP_MARGIN -> 0f to mt
            FloatFrame.BOTTOM_MARGIN -> pageH - mb to pageH
            else -> mt to pageH - mb
        }
    }

    /**
     * [pos] moved so its object's top-left is at [x], [y] (same units and frames as [place]): offsets from
     * the same frames, no longer aligned.
     */
    fun movedTo(pos: FloatPosition, x: Float, y: Float, pg: PageSetup, pageW: Float, unit: Float, colX: Float, colW: Float, paraTop: Float): FloatPosition {
        val l = across(pos, pg, pageW, unit, colX, colW).first
        val t = down(pos, 0f, pg, unit, paraTop).first
        return pos.copy(x = (x - l) / unit, hAlign = null, y = (y - t) / unit, vAlign = null)
    }

    /**
     * Whether the text keeps clear of a floating object placed on the page that stands [overlapW] across a
     * column [colW] wide: the text goes above and below it, or around it with too little room beside it
     * for lines of its own (the text is not set beside it: it steps over its height). Behind or in front of
     * the text, the text pays it no mind; beside a narrower one the lines are set shorter ([textBeside]). ([onPage]: whether
     * it stands on the page rather than with its paragraph — a header's or footer's picture always does.)
     */
    fun textKeepsClear(pos: FloatPosition, overlapW: Float, colW: Float, onPage: Boolean = pos.onPage): Boolean =
        onPage && !pos.overlay && (pos.wrap == FloatWrap.TOP_BOTTOM || colW - overlapW < colW * 0.4f)

    /** Whether the text goes beside a floating object standing [overlapW] across a column [colW] wide: square wrapping with room for lines beside it. */
    fun textBeside(pos: FloatPosition, overlapW: Float, colW: Float): Boolean =
        pos.onPage && pos.wrap == FloatWrap.SQUARE && overlapW > 0f && !textKeepsClear(pos, overlapW, colW)

    /**
     * What a floating object at [x], [w] wide leaves the lines of a column at [colX], [colW] wide beside it: the
     * room it takes from the column's left and from its right, [gap] (the text's distance from it) included. The
     * text goes on its wider side, as Word's "largest only" wrapping sets it.
     */
    fun beside(x: Float, w: Float, colX: Float, colW: Float, gap: Float): Pair<Float, Float> {
        val freeLeft = x - gap - colX
        val freeRight = colX + colW - (x + w + gap)
        return if (freeRight >= freeLeft) (colW - freeRight).coerceIn(0f, colW) to 0f else 0f to (colW - freeLeft).coerceIn(0f, colW)
    }

    /** Word's distance between square-wrapped text and the object beside it, left and right (0.13 in), in points. */
    const val WRAP_GAP_PT = 9f

    /**
     * A header's or footer's picture standing in its paragraph's line [line] (0-based among the header's
     * lines), that paragraph aligning it [align] when the line holds no text.
     */
    fun inHeaderLine(line: Int, align: TextAlign): FloatPosition =
        FloatPosition(0f, FloatFrame.MARGIN, when (align) { TextAlign.CENTER -> FloatAlign.CENTER; TextAlign.RIGHT -> FloatAlign.END; else -> FloatAlign.START },
            0f, FloatFrame.PAGE, null, FloatWrap.TOP_BOTTOM, line)

    /**
     * The top-left (points from the page's corner) of a header's or footer's floating picture on a page of
     * [pg] drawn [pageW] points wide: from the page, its margins, or — moving with its paragraph — the
     * header's or footer's top ([paraTop]; by default the header distance down, a line above the footer
     * distance up). Null for one in its line (HeaderFooters.layout places those).
     */
    fun placeInHeader(img: ImageBlock, header: Boolean, pg: PageSetup, pageW: Float = pg.widthPt,
                      paraTop: Float = if (header) pg.headerPt else pg.heightPt - pg.footerPt - 14f): Pair<Float, Float>? {
        val pos = img.floating ?: return null
        if (pos.line != null) return null
        return place(pos, img.widthPt, img.heightPt, pg, pageW, 1f, pg.marginLeftPt, pageW - pg.marginLeftPt - pg.marginRightPt, paraTop)
    }

    /**
     * Where across its column ([colX], [colW] wide) an object [w] wide that the text goes around stands
     * — the text is not set beside it, so it keeps its place across and takes its own lines: its
     * position's left edge, kept within the column; centred when it has none.
     */
    fun acrossColumn(pos: FloatPosition?, w: Float, pg: PageSetup, pageW: Float, unit: Float, colX: Float, colW: Float): Float {
        if (pos == null) return (colW - w) / 2f
        val (x, _) = place(pos, w, 0f, pg, pageW, unit, colX, colW, 0f)
        return (x - colX).coerceIn(0f, (colW - w).coerceAtLeast(0f))
    }
}
