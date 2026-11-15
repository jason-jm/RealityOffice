package com.therealityteam.aeropages.editor

/** One run of a table's rows on a page; [repeatHeader] puts the table's leading header rows above it. */
data class TableSlice(val rows: IntRange, val repeatHeader: Boolean) {
    val isWhole: Boolean get() = rows.first == 0 && !repeatHeader
}

/**
 * Page composition shared by the editor canvas and the PDF export. Each side measures its blocks
 * in its own unit (pixels on screen, points in the PDF) and hands them here, so both break pages
 * by the same rules — Word's defaults:
 *
 * - a paragraph breaks between lines, with widow/orphan control: never a lone first line at the
 *   bottom of a page or a lone last line at the top of the next (two- and three-line paragraphs
 *   move whole);
 * - "keep with next" (headings) moves a paragraph to the next page when the one after it would
 *   not start on this page;
 * - a table breaks between rows and repeats its header rows on every continuation;
 * - footnotes are reserved at the bottom of the page whose lines anchor them;
 * - a manual page break ends the page; a multi-column page fills its columns left to right;
 * - anything taller than an empty page still gets placed (at least one line / row), so the flow
 *   always advances.
 */
object PageFlow {

    /** A block as the flow sees it. */
    sealed interface Item

    /**
     * Lines of text (a paragraph). [bottoms] are each line's bottom edge measured from the top of the
     * first line; [before] / [after] the paragraph spacing. [notes] (optional) holds, per line, the
     * footnote numbers anchored on it.
     */
    class Lines(
        val before: Float,
        val bottoms: FloatArray,
        val after: Float,
        val keepWithNext: Boolean = false,
        val notes: List<IntArray>? = null,
        /** "Keep lines together": moves whole to the next page rather than break (unless taller than a page). */
        val keepTogether: Boolean = false,
        /** Widow/orphan control; off lets a single line stay at a page edge. */
        val widowControl: Boolean = true,
        /** "Page break before": starts a new page unless the page is still empty. */
        val breakBefore: Boolean = false,
    ) : Item {
        val count: Int get() = bottoms.size

        /** Height of lines [from, to), with the spacing that belongs to that part. */
        fun height(from: Int, to: Int, withAfter: Boolean = true): Float {
            if (to <= from) return 0f
            val top = if (from == 0) 0f else bottoms[from - 1]
            var h = bottoms[to - 1] - top
            if (from == 0) h += before
            if (to == count && withAfter) h += after
            return h
        }
    }

    /** A table: row heights, the leading header rows that repeat, and the space above / below every slice. */
    class Rows(
        val heights: FloatArray,
        val headerCount: Int,
        val padTop: Float = 0f,
        val padBottom: Float = 0f,
        val notes: List<IntArray>? = null,
    ) : Item

    /** Something that never splits (a picture, a text box, one table-of-contents line). */
    class Solid(val height: Float, val notes: IntArray = IntArray(0), val keepWithNext: Boolean = false) : Item

    /** A manual page break. */
    data object Break : Item

    /**
     * Part of item [item] placed in a column: lines [from, to) of a [Lines], a [slice] of a [Rows],
     * or the whole item. [height] is what it takes up in the column.
     */
    data class Piece(val item: Int, val from: Int = 0, val to: Int = 0, val slice: TableSlice? = null, val height: Float = 0f)

    /**
     * A page: its columns' pieces (at least one column) and the footnotes printed at its bottom, in anchor order;
     * [wraps] the paragraphs (by item) starting on it whose lines were laid out around floating objects beside them.
     */
    class Page(val frames: List<List<Piece>>, val notes: List<Int>, val wraps: Map<Int, Wrap> = emptyMap()) {
        val pieces: List<Piece> get() = frames.flatten()
    }

    /**
     * A stretch down a column ([frames]: its index) where a floating object stands that the text goes beside:
     * [top]..[bottom] from the body's top; lines there leave [left] free at the column's left and [right] at its right.
     */
    data class Side(val top: Float, val bottom: Float, val frames: Set<Int>, val left: Float, val right: Float)

    /** Where a paragraph laid out around floating objects started — its first line's [top] — and the [sides] it met. */
    data class Wrap(val top: Float, val sides: List<Side>)

    /**
     * [height] is a column's height on an empty page; [columns] how many columns a page has.
     * [noteHeight] measures one footnote and [noteArea] is the fixed cost of a page's footnote
     * area (its separator), paid once per page that has notes. [room] is what page [index]'s
     * header and footer take from its body (null: nothing).
     */
    class Geometry(
        val height: Float,
        val columns: Int = 1,
        val noteHeight: (Int) -> Float = { 0f },
        val noteArea: Float = 0f,
        val room: (index: Int) -> PageRoom? = { null },
        /**
         * Item [item]'s lines laid out again with its first line's top at [top] (from the body's top) beside the
         * floating objects [sides] — the lines they stand beside narrowed; null: as measured.
         */
        val reflow: ((item: Int, top: Float, sides: List<Side>) -> Lines?)? = null,
    )

    /**
     * What a page's header and footer take from its body, in the flow's unit: [top] from its top (the
     * text starts below — a gap first), [foot] from its foot (its columns, and the footnotes at their
     * foot, end above), and [bands] between, which the text steps over like a floating object's.
     */
    class PageRoom(val top: Float = 0f, val foot: Float = 0f, val bands: List<Band> = emptyList())

    /**
     * A stretch down a page that the text keeps out of — where a floating object the text goes around
     * stands — in the columns [frames]: [top]..[bottom] from the body's top.
     */
    data class Band(val top: Float, val bottom: Float, val frames: Set<Int>)

    /** The item index of a gap: room a column leaves empty where its text steps over a band. */
    const val GAP = -1

    /** [items] in pages; [bands]: each page's (by its index) bands, which the text steps over; [sides] what it goes beside. */
    fun paginate(items: List<Item>, g: Geometry, bands: Map<Int, List<Band>> = emptyMap(), sides: Map<Int, List<Side>> = emptyMap()): List<Page> =
        Packer(items, g, bands, sides).run()

    /**
     * Pages laid out around floating objects' bands, and the page each band — and so its object — is on (by its mark's
     * item); [marks] where each such mark stood in the pass its object's room was taken from.
     */
    class Flowed(val pages: List<Page>, val bandPages: Map<Int, Int>, val marks: Map<Int, At> = emptyMap())

    /** Where a mark stands: page [page], column [frame], [y] from the body's top. */
    data class At(val page: Int, val frame: Int, val y: Float)

    /** The room a floating object takes from the text: what it steps over ([bands]) and what it goes beside ([sides]). */
    class Room(val bands: List<Band> = emptyList(), val sides: List<Side> = emptyList())

    /**
     * Where each of [marks] stands on [pages] — and where it would had it not stepped over the gaps just above it
     * (the band of its own object, which moves its mark down but must not move the object).
     */
    private fun positions(pages: List<Page>, marks: Set<Int>): Map<Int, Pair<At, Float>> {
        val out = HashMap<Int, Pair<At, Float>>()
        pages.forEachIndexed { pi, page ->
            page.frames.forEachIndexed { fi, frame ->
                var y = 0f
                var gapTop = 0f
                var lastGap = false
                frame.forEach { pc ->
                    if (pc.item == GAP) { if (!lastGap) gapTop = y; lastGap = true } else {
                        if (pc.item in marks && pc.item !in out) out[pc.item] = At(pi, fi, y) to (if (lastGap) gapTop else y)
                        if (pc.height > 0f) lastGap = false
                    }
                    y += pc.height
                }
            }
        }
        return out
    }

    /**
     * [items] in pages around floating objects, each anchored by a mark among [marks]: [roomAt] gives the room an
     * object takes when its mark stands at a place (its position may count from its paragraph's top there). Each
     * object takes the page its mark lands on with the text laid out as if nothing floated; the text is then laid
     * out again around them — and again, while a mark moves down its page (a paragraph pushed down by the lines
     * beside an object above it), a few times at most. (A mark pushed on to another page keeps its object where it
     * was: following it would free the page, bring the mark back and go round.)
     */
    fun paginateAround(items: List<Item>, g: Geometry, marks: Set<Int>, roomAt: (item: Int, at: At) -> Room?): Flowed {
        var pages = paginate(items, g)
        if (marks.isEmpty()) return Flowed(pages, emptyMap())
        var at = positions(pages, marks).mapValues { it.value.first }
        val placed = HashMap<Int, At>()
        for (pass in 0 until MAX_PASSES) {
            val bands = HashMap<Int, MutableList<Band>>()
            val sides = HashMap<Int, MutableList<Side>>()
            placed.clear()
            at.forEach { (item, a) ->
                val room = roomAt(item, a) ?: return@forEach
                if (room.bands.isEmpty() && room.sides.isEmpty()) return@forEach
                placed[item] = a
                if (room.bands.isNotEmpty()) bands.getOrPut(a.page) { ArrayList() }.addAll(room.bands)
                if (room.sides.isNotEmpty()) sides.getOrPut(a.page) { ArrayList() }.addAll(room.sides)
            }
            if (placed.isEmpty()) return Flowed(pages, emptyMap())
            pages = paginate(items, g, bands, sides)
            val now = positions(pages, marks)
            val next = at.mapValues { (i, a) ->
                val (b, above) = now[i] ?: return@mapValues a
                when {
                    b.page != a.page -> a
                    // Moved down only over the gap its own band left: it stays.
                    b.frame == a.frame && kotlin.math.abs(above - a.y) < 0.5f -> a
                    else -> b
                }
            }
            if (next.all { (i, a) -> at[i]?.let { b -> b.frame == a.frame && kotlin.math.abs(b.y - a.y) < 0.5f } == true }) break
            at = next
        }
        return Flowed(pages, placed.mapValues { it.value.page }, placed.toMap())
    }

    /** How many times at most [paginateAround] lays the text out again around its objects. */
    private const val MAX_PASSES = 4

    /**
     * [items] in pages around the bands floating objects make: [bandOf] gives an item's band (a floating
     * object's mark's; null for any other). Each object takes the page its mark lands on with the text laid
     * out as if nothing floated; the text is then laid out again stepping over the bands. (Its own band may
     * push its mark on to the next page — the object stays: following it would free the page, bring the
     * mark back and go round.)
     */
    fun paginateAround(items: List<Item>, g: Geometry, bandOf: (Int) -> Band?): Flowed {
        val marks = items.indices.mapNotNull { i -> bandOf(i)?.let { i to it } }.toMap()
        val first = paginate(items, g)
        if (marks.isEmpty()) return Flowed(first, emptyMap())
        val pageOf = HashMap<Int, Int>()
        val bands = HashMap<Int, MutableList<Band>>()
        first.forEachIndexed { pi, page ->
            page.pieces.forEach { pc -> marks[pc.item]?.let { b -> pageOf[pc.item] = pi; bands.getOrPut(pi) { ArrayList() }.add(b) } }
        }
        return Flowed(paginate(items, g, bands), pageOf)
    }

    private class Packer(val items: List<Item>, val g: Geometry, val bands: Map<Int, List<Band>>, val sides: Map<Int, List<Side>> = emptyMap()) {
        val cols = g.columns.coerceAtLeast(1)
        val pages = ArrayList<Page>()
        var frames = ArrayList<MutableList<Piece>>().apply { add(ArrayList()) }
        val used = FloatArray(cols)
        var f = 0
        val notes = LinkedHashSet<Int>()
        var notesH = 0f
        val rooms = HashMap<Int, PageRoom?>()
        /** The current page's paragraphs laid out around what floats beside them. */
        var wraps = HashMap<Int, Wrap>()

        /** What the current page's header and footer take from its body. */
        fun roomHere(): PageRoom? = if (pages.size in rooms) rooms[pages.size] else g.room(pages.size).also { rooms[pages.size] = it }

        /** Where the current page's columns end: above the room its footer takes. */
        fun foot(): Float = g.height - (roomHere()?.foot ?: 0f)

        /** Whether a column holds anything but gaps (the room a header takes counts for nothing). */
        fun holds(frame: List<Piece>): Boolean = frame.any { it.item != GAP }

        fun run(): List<Page> {
            items.forEachIndexed { i, item ->
                when (item) {
                    is Break -> newPage()
                    is Lines -> placeLines(i, item)
                    is Rows -> placeRows(i, item)
                    is Solid -> placeSolid(i, item)
                }
            }
            if (frames.any { holds(it) } || pages.isEmpty()) pages.add(Page(frames, notes.toList(), placedWraps()))
            return pages
        }

        val frameEmpty: Boolean get() = !holds(frames[f])

        /** What the footnotes [ns] would add to this page (those it does not print yet). */
        fun extraFor(ns: Collection<Int>): Float {
            var fresh = 0f
            var any = false
            ns.forEach { n -> if (n !in notes) { fresh += g.noteHeight(n); any = true } }
            if (!any) return 0f
            return fresh + (if (notes.isEmpty()) g.noteArea else 0f)
        }

        /** This page's bands in the current column, top down: its floating objects', its header's and footer's. */
        fun bandsHere(): List<Band> {
            val room = roomHere()
            val own = bands[pages.size]
            if (room == null && own == null) return emptyList()
            val all = ArrayList<Band>()
            own?.let { all.addAll(it) }
            room?.let { r ->
                if (r.top > 0f) all.add(Band(0f, r.top, (0 until cols).toSet()))
                all.addAll(r.bands)
            }
            return all.filter { f in it.frames }.sortedBy { it.top }
        }

        /** Where the current column's next band starts below what it holds (none: past its foot). */
        fun nextBandTop(): Float = bandsHere().firstOrNull { it.bottom > used[f] + EPS }?.top ?: Float.MAX_VALUE

        /** Whether a band, not the column's foot, is what stops more from fitting here. */
        fun bandAhead(): Boolean = nextBandTop() < foot() - notesH - EPS

        /** Steps over any band the column's next line would start in: a gap down to its foot. */
        fun skipBands() {
            for (b in bandsHere()) if (used[f] + EPS >= b.top && used[f] < b.bottom - EPS) {
                frames[f].add(Piece(GAP, height = b.bottom - used[f]))
                used[f] = b.bottom
            }
        }

        /** On past what stops more from fitting: over the band ahead (a gap to its foot), else to the next column. */
        fun advance() {
            val b = bandsHere().firstOrNull { it.bottom > used[f] + EPS && it.top < foot() - notesH - EPS }
            if (b == null) { nextFrame(); return }
            frames[f].add(Piece(GAP, height = b.bottom - used[f]))
            used[f] = b.bottom
            skipBands()
            if (used[f] >= foot() - notesH - EPS) nextFrame()
        }

        /** Whether [h] more fits in the current column when the page also gains [extra] of footnotes. */
        fun fits(h: Float, extra: Float): Boolean {
            val limit = foot() - notesH - extra
            if (used[f] + h > limit + EPS) return false
            if (used[f] + h > nextBandTop() + EPS) return false
            // Footnotes span the page: the columns filled before this one must still fit above them.
            if (extra > 0f) for (i in 0 until f) if (used[i] > limit + EPS) return false
            return true
        }

        fun put(piece: Piece, ns: Collection<Int>) {
            val extra = extraFor(ns)
            frames[f].add(piece)
            used[f] += piece.height
            if (extra > 0f) { notesH += extra; notes.addAll(ns) }
        }

        fun nextFrame() {
            if (f + 1 < cols) { f++; frames.add(ArrayList()) } else newPage()
        }

        /** The current page's wraps of paragraphs that start on it (one laid out here, then moved on, does not). */
        fun placedWraps(): Map<Int, Wrap> = wraps.filterKeys { k -> frames.any { fr -> fr.any { it.item == k && it.from == 0 } } }

        fun newPage() {
            pages.add(Page(frames, notes.toList(), placedWraps()))
            wraps = HashMap()
            frames = ArrayList<MutableList<Piece>>().apply { add(ArrayList()) }
            used.fill(0f)
            f = 0
            notes.clear()
            notesH = 0f
        }

        fun notesOf(list: List<IntArray>?, from: Int, to: Int): List<Int> {
            if (list == null) return emptyList()
            val out = ArrayList<Int>()
            for (i in from until to.coerceAtMost(list.size)) list[i].forEach { out.add(it) }
            return out
        }

        /** The least of [index] that has to start on the same page as a keep-with-next item before it. */
        fun minStart(index: Int, depth: Int = 0): Float {
            val item = items.getOrNull(index) ?: return 0f
            return when (item) {
                is Break -> 0f
                // A mark of no height (where a floating object is anchored) keeps nothing apart.
                is Solid -> if (item.height == 0f) minStart(index + 1, depth)
                    else item.height + (if (item.keepWithNext && depth < 3) minStart(index + 1, depth + 1) else 0f)
                is Rows -> if (item.heights.isEmpty()) 0f else item.padTop + item.heights[0]
                is Lines -> {
                    // Widow/orphan control: a paragraph of up to three lines only moves whole (as does one kept together).
                    val whole = item.keepTogether || (item.widowControl && item.count <= 3)
                    val h = if (whole) item.height(0, item.count, withAfter = false) else item.height(0, if (item.widowControl) 2 else 1)
                    h + (if (item.keepWithNext && whole && depth < 3) item.after + minStart(index + 1, depth + 1) else 0f)
                }
            }
        }

        fun placeSolid(index: Int, item: Solid) {
            val ns = item.notes.toList()
            // A mark of no height (where a floating object is anchored) goes with what follows it: on to the next
            // column when that does not start here.
            if (item.height == 0f && item.keepWithNext && !frameEmpty && !fits(minStart(index + 1), 0f) && !bandAhead()) nextFrame()
            if (item.height > 0f) {
                skipBands()
                val need = item.height + (if (item.keepWithNext) minStart(index + 1) else 0f)
                while (bandAhead() && !fits(item.height, extraFor(ns))) advance()
                if (!frameEmpty && !fits(need, extraFor(ns))) nextFrame()
                skipBands()
            } else skipBands()   // a mark stands where what follows it starts: below the room a header takes
            put(Piece(index, height = item.height), ns)
        }

        /** The current column's floating objects that the text goes beside, which a paragraph from [top] down to [bottom] meets. */
        fun sidesHere(top: Float, bottom: Float): List<Side> =
            sides[pages.size]?.filter { f in it.frames && it.bottom > top + EPS && it.top < bottom - EPS }.orEmpty()

        fun placeLines(index: Int, original: Lines) {
            var item = original
            var n = item.count
            /** Lines laid out for where the paragraph starts now: around what floats beside them there, else as measured. */
            fun refit() {
                val reflow = g.reflow ?: return
                val top = used[f] + original.before
                val met = sidesHere(top, top + original.height(0, original.count, withAfter = false) * 2f + 1f)
                val lines = if (met.isEmpty()) null else reflow(index, top, met)
                if (lines != null) { item = lines; wraps[index] = Wrap(top, met) } else { item = original; wraps.remove(index) }
                n = item.count
            }
            // Page break before: a new page, unless nothing is on this one yet.
            if (item.breakBefore && frames.any { holds(it) }) newPage()
            if (n == 0) { put(Piece(index, 0, 0, height = item.before + item.after), emptyList()); return }
            // Keep lines together: a paragraph that does not fit here starts on the next page (as in Word, one
            // taller than a page too — it then breaks from the top of that page).
            if (item.keepTogether && !frameEmpty) {
                if (!fits(item.height(0, n, withAfter = false), extraFor(notesOf(item.notes, 0, n)))) nextFrame()
            }
            // Keep with next: a paragraph that fits here moves on when the one after it would not start here
            // too (one that has to break anyway just breaks: its last lines go on with the next paragraph).
            if (item.keepWithNext && !frameEmpty) {
                val extra = extraFor(notesOf(item.notes, 0, n))
                val need = item.height(0, n) + minStart(index + 1)
                if (fits(item.height(0, n, withAfter = false), extra) && !fits(need, extra) && fitsOnFreshColumn(need)) nextFrame()
            }
            var from = 0
            while (from < n) {
                skipBands()
                if (from == 0) refit()
                // Usually the rest fits whole (one check instead of one per line).
                val rest = notesOf(item.notes, from, n)
                if (fits(item.height(from, n, withAfter = false), extraFor(rest))) {
                    put(Piece(index, from, n, height = item.height(from, n)), rest)
                    return
                }
                // The most lines from `from` that fit (the space after the last line may hang past the bottom).
                var k = from
                while (k < n && fits(item.height(from, k + 1, withAfter = false), extraFor(notesOf(item.notes, from, k + 1)))) k++
                if (k == n) {
                    put(Piece(index, from, n, height = item.height(from, n)), notesOf(item.notes, from, n))
                    return
                }
                // A band stops the lines (the text goes on below it, on this page): no widow or orphan rules there.
                val atBand = bandAhead()
                if (item.widowControl && !atBand) {
                    // Widow control: the next page must get at least two lines.
                    if (n - k == 1) {
                        if (k - from >= 2) k -= 1 else if (!frameEmpty) k = from
                    }
                    // Orphan control: the first line never stays alone at the bottom.
                    if (from == 0 && k == 1 && !frameEmpty) k = 0
                }
                if (k == from) {
                    if (atBand) { advance(); continue }
                    if (frameEmpty) k = from + 1 // not even one line fits an empty column: place it anyway
                    else { nextFrame(); continue }
                }
                put(Piece(index, from, k, height = item.height(from, k, withAfter = false)), notesOf(item.notes, from, k))
                from = k
                advance()
            }
        }

        fun placeRows(index: Int, item: Rows) {
            val n = item.heights.size
            if (n == 0) return
            val hc = item.headerCount.coerceIn(0, n)
            val headerH = (0 until hc).sumOf { item.heights[it].toDouble() }.toFloat()
            // Header rows repeat on continuations only while they leave room for the table's own rows.
            val repeatable = hc > 0 && headerH <= g.height / 2f
            var i = 0
            while (i < n) {
                skipBands()
                // Header rows repeat where the table goes on in another column or page — not below a band on the same one.
                val repeat = repeatable && i > 0 && hc in 1..i && frames[f].none { it.item == index }
                var h = item.padTop + item.padBottom + (if (repeat) headerH else 0f)
                val ns = ArrayList<Int>()
                val start = i
                while (i < n) {
                    val rowNotes = item.notes?.getOrNull(i)?.toList() ?: emptyList()
                    if (fits(h + item.heights[i], extraFor(ns + rowNotes))) { h += item.heights[i]; ns += rowNotes; i++ } else break
                }
                // Never strand the header rows alone at the bottom of a page the table starts on.
                if (start == 0 && i in 1..hc && i < n && !frameEmpty) i = 0
                if (i == start) {
                    if (bandAhead()) { advance(); continue }
                    if (frameEmpty) { // a row taller than the page: it gets the page to itself
                        h += item.heights[i]; item.notes?.getOrNull(i)?.forEach { ns += it }; i++
                    } else { nextFrame(); continue }
                }
                put(Piece(index, slice = TableSlice(start until i, repeat), height = h), ns)
                if (i < n) advance()
            }
        }

        fun fitsOnFreshColumn(h: Float): Boolean = h <= foot() - (roomHere()?.top ?: 0f) + EPS
    }

    private const val EPS = 0.01f
}
