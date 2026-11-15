package com.therealityteam.aeropages.model

/**
 * Bookmarks — named places in a document's text ([BookmarkMark]s in its paragraphs) — and links
 * within the document: a run whose [CharacterFormat.link] is `#name` goes to bookmark `name`
 * (Word's `w:hyperlink w:anchor`, ODF's `xlink:href="#name"`).
 */
object Bookmarks {

    /**
     * A bookmark of a document: its [name], where it starts ([startPara] — an index into
     * [Document.locations] — and character [start]) and where it ends; a point ends where it starts.
     */
    data class Found(val name: String, val startPara: Int, val start: Int, val endPara: Int, val end: Int, val closed: Boolean = true) {
        val hidden: Boolean get() = isHidden(name)
    }

    /**
     * Every bookmark of [doc] in text order. A name's first start counts (with the first end after
     * it); a start with no end after it is a point, an end with no start before it is no bookmark.
     * With [comments], the comments' ranges instead (their marks: see [commentMark]).
     */
    fun of(doc: Document, comments: Boolean = false): List<Found> {
        val open = LinkedHashMap<String, Pair<Int, Int>>()
        val done = HashSet<String>()
        val out = ArrayList<Found>()
        doc.locations().forEachIndexed { i, loc ->
            val p = loc.paragraph
            if (p.bookmarks.isEmpty()) return@forEachIndexed
            val len = p.runs.sumOf { it.text.length }
            p.bookmarks.forEach { m ->
                if (isComment(m.name) != comments) return@forEach
                val at = m.offset.coerceIn(0, len)
                if (!m.end) {
                    if (m.name !in open && m.name !in done) open[m.name] = i to at
                } else open.remove(m.name)?.let { (sp, so) -> out.add(Found(m.name, sp, so, i, at)); done.add(m.name) }
            }
        }
        open.forEach { (name, at) -> out.add(Found(name, at.first, at.second, at.first, at.second, closed = false)) }
        return out.sortedWith(compareBy({ it.startPara }, { it.start }))
    }

    /**
     * The marks comment [id]'s range rides on: the edits keep them with their text as they keep bookmarks', but they
     * are no bookmark — left out of [of] and written as the comment's range (Word's `commentRangeStart`, ODF's
     * `office:annotation` … `office:annotation-end`).
     */
    fun commentMark(id: Long): String = "\u0001c$id"
    fun isComment(name: String): Boolean = name.startsWith('\u0001')
    fun commentIdOf(name: String): Long? = if (name.startsWith("\u0001c")) name.substring(2).toLongOrNull() else null

    /** Each commented range of [doc] that has both its ends, by comment id. */
    fun commentRanges(doc: Document): Map<Long, Found> =
        of(doc, comments = true).filter { it.closed }.mapNotNull { f -> commentIdOf(f.name)?.let { it to f } }.toMap()

    /** Takes the range marks of the comments [ids] out of [doc]. */
    fun dropCommentMarks(doc: Document, ids: Set<Long>) {
        doc.locations().forEach { l -> l.paragraph.bookmarks.removeAll { m -> commentIdOf(m.name)?.let { it in ids } == true } }
    }

    /** Word's rule for a bookmark's name: a letter first, then letters, digits and underscores — 40 at most. */
    fun isValidName(name: String): Boolean =
        name.length in 1..40 && name[0].isLetter() && name.all { it.isLetterOrDigit() || it == '_' }

    /** Word's hidden bookmarks (`_Toc…` round headings, `_Ref…`, `_GoBack`) start with an underscore. */
    fun isHidden(name: String): Boolean = name.startsWith("_")

    /** The bookmark a link within the document goes to ("#name" → name); null for a link elsewhere. */
    fun targetOf(link: String?): String? =
        link?.takeIf { it.length > 1 && it.startsWith("#") }?.substring(1)

    /**
     * The heading text a LibreOffice link to a heading names (`#1.2.Heading|outline` gives
     * `1.2.Heading`, its number and text as LibreOffice shows them), or null for another target.
     */
    fun outlineTarget(name: String): String? = name.takeIf { it.endsWith("|outline") }?.removeSuffix("|outline")

    /**
     * A hidden name for a bookmark round [heading] (where a link to the heading goes): Word's way,
     * "_" and the heading's first words joined by underscores, unique among [taken].
     */
    fun headingName(heading: String, taken: Set<String>): String {
        val words = heading.trim().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        val stem = ("_" + words.joinToString("_")).take(32).trimEnd('_').ifEmpty { "_Heading" }
        if (stem !in taken) return stem
        var n = 1
        while ("${stem}_$n" in taken) n++
        return "${stem}_$n"
    }

    /**
     * The bookmark each heading a table of contents lists goes through (by the heading's paragraph id):
     * a `_Toc` one starting where its text does (Word's), else a new `_Toc` one — marked true: a writer
     * puts it round the heading's text. Empty when the document has no table of contents.
     */
    fun tocMarks(doc: Document): Map<Long, Pair<String, Boolean>> {
        if (doc.blocks.none { it is TocBlock }) return emptyMap()
        val taken = of(doc).mapTo(HashSet()) { it.name }
        var next = 100000001
        // Each entry of every contents block: headings, and the captions a table of figures lists.
        return doc.blocks.filterIsInstance<TocBlock>().flatMap { doc.tocEntries(it) }.distinctBy { it.paragraph.id }.associate { e ->
            val own = e.paragraph.bookmarks.firstOrNull { !it.end && it.offset == 0 && it.name.startsWith("_Toc") }?.name
            e.paragraph.id to (own?.let { it to false } ?: run {
                while ("_Toc$next" in taken) next++
                ("_Toc$next").also { taken.add(it); next++ } to true
            })
        }
    }

    /** Puts [mark] into [p]'s marks in text order: after the marks already at its offset. */
    fun place(p: Paragraph, mark: BookmarkMark) {
        val at = p.bookmarks.indexOfFirst { it.offset > mark.offset }
        if (at < 0) p.bookmarks.add(mark) else p.bookmarks.add(at, mark)
    }

    /**
     * Walks a paragraph's bookmark marks alongside its text as a writer writes it: the marks up to
     * a place ([upTo]), where the next one cuts into a run ([cutIn]), and what is left after the
     * text ([rest]).
     */
    class Cursor(p: Paragraph) {
        private val marks = p.bookmarks.sortedBy { it.offset }
        private var next = 0

        /** The marks not yet written that stand at or before [pos]. */
        fun upTo(pos: Int): List<BookmarkMark> {
            if (next >= marks.size || marks[next].offset > pos) return emptyList()
            val from = next
            while (next < marks.size && marks[next].offset <= pos) next++
            return marks.subList(from, next)
        }

        /** The offset of the next mark strictly inside ([from], [to]) — where a run must be cut — or null. */
        fun cutIn(from: Int, to: Int): Int? = marks.getOrNull(next)?.offset?.takeIf { it in (from + 1) until to }

        /** The marks not yet written (those after the text). */
        fun rest(): List<BookmarkMark> = marks.subList(next.coerceAtMost(marks.size), marks.size).also { next = marks.size }
    }
}
