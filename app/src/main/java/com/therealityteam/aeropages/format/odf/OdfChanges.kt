package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.odf.OdfPackage.escape
import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.RevisionMark
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextBoxBlock
import com.therealityteam.aeropages.model.TextRun
import java.util.IdentityHashMap

/**
 * Tracked changes as ODF keeps them (`text:tracked-changes`), as LibreOffice writes them: the text holds what accepting
 * the deletions gives — an insertion between `text:change-start` and `text:change-end`, a format change likewise, a
 * deletion only as a `text:change` mark, its text kept in its changed region (`text:deletion` with a paragraph per
 * piece: a deleted paragraph mark is the break between two of them, the paragraphs either side one in the text).
 * The model keeps deleted text in place and marks a paragraph's mark as inserted or deleted (Word's way); these turn
 * one into the other.
 */
internal object OdfChanges {
    /** A deleted paragraph mark inside a paragraph joined over it (writing) or read from a deletion (reading). */
    const val BREAK = " "

    // --- writing ---------------------------------------------------------------------------------

    /**
     * The document as ODF writes its changes: [doc] with each paragraph whose mark is deleted joined with the one after
     * it (a copy when there are any; [breaks]: each joining break with the paragraphs before and after it, whose styles
     * the deletion's paragraphs take), and [carried]: the paragraphs at whose end an insertion carries over into the
     * paragraph after — an inserted mark (one follows in the same place), or the one before a last inserted paragraph
     * (ODF has no mark after the last paragraph to insert: the break before it is, as Word's reject of it gives).
     */
    class Plan(val doc: Document, val breaks: Map<TextRun, Pair<Paragraph, Paragraph>>, val carried: Map<Paragraph, RevisionMark>)

    fun plan(original: Document): Plan {
        val joins = original.locations().any { joinable(it.paragraph) }
        val doc = if (joins) original.copyOf() else original
        val breaks = IdentityHashMap<TextRun, Pair<Paragraph, Paragraph>>()
        if (joins) {
            joinBlocks(doc, breaks)
            doc.blocks.forEach { b ->
                when (b) {
                    is TableBlock -> b.table.rows.forEach { r -> r.cells.forEach { c -> joinList(c.content, doc, breaks) } }
                    is TextBoxBlock -> joinList(b.paragraphs, doc, breaks)
                    else -> Unit
                }
            }
        }
        val carried = IdentityHashMap<Paragraph, RevisionMark>()
        /** [p], the paragraph after it in its place and the one before (null: none): its inserted mark carried on, or onto the one before. */
        fun carry(p: Paragraph, next: Paragraph?, before: Paragraph?) {
            val m = p.format.revisionMark?.takeIf { it.inserted } ?: return
            if (next != null) carried[p] = m
            else if (before != null && before.format.revisionMark == null && p.runs.all { it.text.isEmpty() || it.format.inserted }) carried.putIfAbsent(before, m)
        }
        fun list(l: List<Paragraph>) = l.forEachIndexed { i, p -> carry(p, l.getOrNull(i + 1), l.getOrNull(i - 1)) }
        doc.blocks.forEachIndexed { i, b ->
            val p = (b as? ParagraphBlock)?.paragraph ?: return@forEachIndexed
            carry(p, (doc.blocks.getOrNull(i + 1) as? ParagraphBlock)?.paragraph, (doc.blocks.getOrNull(i - 1) as? ParagraphBlock)?.paragraph)
        }
        doc.blocks.forEach { b -> (b as? TableBlock)?.table?.rows?.forEach { r -> r.cells.forEach { c -> list(c.content) } } }
        return Plan(doc, breaks, carried)
    }

    /** A paragraph whose deleted mark joins it with the next: not one ending a section, not a page break. */
    private fun joinable(p: Paragraph) = p.format.revisionMark?.inserted == false && p.format.sectionBreak == null && !p.isPageBreak()

    private fun joinBlocks(doc: Document, breaks: IdentityHashMap<TextRun, Pair<Paragraph, Paragraph>>) {
        val blocks = doc.blocks
        if (blocks.none { (it as? ParagraphBlock)?.paragraph?.let(::joinable) == true }) return
        val out = ArrayList<Block>(blocks.size)
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            if (b !is ParagraphBlock) { out.add(b); i++; continue }
            var p = b.paragraph
            while (joinable(p)) {
                val q = (blocks.getOrNull(i + 1) as? ParagraphBlock)?.paragraph?.takeIf { !it.isPageBreak() } ?: break
                p = joined(p, q, doc, breaks)
                i++
            }
            out.add(if (p === b.paragraph) b else ParagraphBlock(p))
            i++
        }
        blocks.clear(); blocks.addAll(out)
    }

    private fun joinList(list: MutableList<Paragraph>, doc: Document, breaks: IdentityHashMap<TextRun, Pair<Paragraph, Paragraph>>) {
        if (list.none(::joinable)) return
        val out = ArrayList<Paragraph>(list.size)
        var i = 0
        while (i < list.size) {
            var p = list[i]
            while (joinable(p) && i + 1 < list.size) { p = joined(p, list[i + 1], doc, breaks); i++ }
            out.add(p)
            i++
        }
        list.clear(); list.addAll(out)
    }

    /** [p] and [q] as one paragraph, [q]'s (its mark the one left): [p]'s text, the break, [q]'s; their bookmarks; [p]'s comments. */
    private fun joined(p: Paragraph, q: Paragraph, doc: Document, breaks: IdentityHashMap<TextRun, Pair<Paragraph, Paragraph>>): Paragraph {
        val m = p.format.revisionMark
        val br = TextRun(BREAK, CharacterFormat(deleted = true, revAuthor = m?.author, revDate = m?.date))
        val shift = p.plainText().length + 1
        val runs = ArrayList<TextRun>(p.runs.size + q.runs.size + 1).apply { addAll(p.runs); add(br); addAll(q.runs) }
        val marks = ArrayList(p.bookmarks).apply { q.bookmarks.forEach { add(it.copy(offset = it.offset + shift)) } }
        val merged = Paragraph(runs, q.format, q.id, marks)
        breaks[br] = p to merged
        doc.comments.forEach { if (it.targetId == p.id) it.targetId = q.id }
        return merged
    }

    /**
     * Writes the changes while the text is written: what goes into the text before each run, at each paragraph's start
     * and end, and the changed regions they name ([xml], written at the text's start once the text is done).
     */
    class Recorder(private val plan: Plan) {
        private class Region(val id: String, val kind: String, val author: String?, val date: Long?) {
            /** A deletion's paragraphs: the paragraph each was part of and its text. */
            val paras = ArrayList<Pair<Paragraph, StringBuilder>>()
        }
        private val regions = ArrayList<Region>()
        private var open: Region? = null
        private var deleting: Region? = null
        /** The paragraph the runs being written were part of (before a joined paragraph's breaks). */
        private var origin: Paragraph? = null

        val any: Boolean get() = regions.isNotEmpty()

        private fun region(kind: String, author: String?, date: Long?) = Region("ct${regions.size + 1}", kind, author, date).also { regions.add(it) }

        /** At [p]'s start: its first text was part of the paragraph before its first break (a joined paragraph's), else [p]. */
        fun begin(p: Paragraph) {
            deleting = null
            origin = p.runs.firstOrNull { it in plan.breaks }?.let { plan.breaks[it]?.first } ?: p
        }

        /** Before a run's content in the text (not a deleted one's): ends what it does not continue, starts its own change. */
        fun toward(f: CharacterFormat): String {
            deleting = null
            val kind = when { f.inserted -> "insertion"; f.formatChanged -> "format-change"; else -> null }
            val o = open
            if (o != null && kind == o.kind && o.author == f.revAuthor && o.date == f.revDate) return ""
            val sb = StringBuilder(close())
            if (kind != null) region(kind, f.revAuthor, f.revDate).also { open = it; sb.append("""<text:change-start text:change-id="${it.id}"/>""") }
            return sb.toString()
        }

        /**
         * A deleted run: its text ([text], as the deletion's paragraph writes it) into the deletion it continues, or a new
         * one marked here; a break (a deleted paragraph mark) starts the deletion's next paragraph.
         */
        fun deleted(run: TextRun, text: (String) -> String): String {
            val f = run.format
            val sb = StringBuilder(close())
            var r = deleting
            if (r == null || r.author != f.revAuthor || r.date != f.revDate) {
                r = region("deletion", f.revAuthor, f.revDate).also { deleting = it }
                sb.append("""<text:change text:change-id="${r.id}"/>""")
                r.paras.add((origin ?: plan.breaks[run]?.first ?: Paragraph()) to StringBuilder())
            }
            val br = plan.breaks[run]
            if (br != null) {
                r.paras.add(br.second to StringBuilder())
                origin = br.second
            } else r.paras.last().second.append(text(run.text))
            return sb.toString()
        }

        /** At [p]'s end: an insertion carried over to the next paragraph (see [Plan.carried]), else what is open ended. */
        fun end(p: Paragraph): String {
            deleting = null
            val m = plan.carried[p]
            if (m != null) {
                val o = open
                if (o != null && o.kind == "insertion" && o.author == m.author && o.date == m.date) return ""
                val sb = StringBuilder(close())
                region("insertion", m.author, m.date).also { open = it; sb.append("""<text:change-start text:change-id="${it.id}"/>""") }
                return sb.toString()
            }
            return close()
        }

        private fun close(): String {
            val o = open ?: return ""
            open = null
            return """<text:change-end text:change-id="${o.id}"/>"""
        }

        /** Writes [body] (a frame's paragraphs inside a paragraph) with nothing open across it. */
        fun aside(body: () -> String): String {
            val o = open; val d = deleting; val g = origin
            open = null; deleting = null
            return try { body() } finally { open = o; deleting = d; origin = g }
        }

        /**
         * `text:tracked-changes`, each deletion's paragraph as [paragraph] writes it (its style, whether a heading); [recording]:
         * Track Changes is on (the element written for that alone when there are no changes).
         */
        fun xml(recording: Boolean = false, paragraph: (Paragraph, String) -> String): String = buildString {
            if (regions.isEmpty()) { if (recording) append("""<text:tracked-changes text:track-changes="true"/>"""); return@buildString }
            append("""<text:tracked-changes text:track-changes="$recording">""")
            for (r in regions) {
                append("""<text:changed-region xml:id="${r.id}" text:id="${r.id}"><text:${r.kind}><office:change-info>""")
                append("<dc:creator>").append(escape(r.author ?: "Reality Office")).append("</dc:creator>")
                append("<dc:date>").append(dateOf(r.date)).append("</dc:date></office:change-info>")
                r.paras.forEach { (p, text) -> append(paragraph(p, text.toString())) }
                append("</text:${r.kind}></text:changed-region>")
            }
            append("</text:tracked-changes>")
        }

        private fun dateOf(ms: Long?) =
            java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms ?: REV_DATE), java.time.ZoneOffset.UTC).withNano(0).toString()
    }

    /** The date a change with none is given (the docx writer's: 2026-01-01T00:00:00Z). */
    private const val REV_DATE = 1_767_225_600_000L

    // --- reading ---------------------------------------------------------------------------------

    enum class Kind { INSERTION, DELETION, FORMAT }

    /** A changed region: what it is, who made it and when, and (a deletion) its node, whose paragraphs hold the text. */
    class Region(val kind: Kind, val author: String?, val date: Long?, val node: OdfNode)

    /** The changed regions of a text's `text:tracked-changes`, by id. */
    fun regions(tracked: OdfNode?): Map<String, Region> {
        if (tracked == null) return emptyMap()
        val out = HashMap<String, Region>()
        for (cr in tracked.children("changed-region")) {
            val id = cr.attr("id") ?: continue
            val k = cr.children.firstOrNull { it.name == "insertion" || it.name == "deletion" || it.name == "format-change" } ?: continue
            val info = k.child("change-info")
            val kind = when (k.name) { "insertion" -> Kind.INSERTION; "deletion" -> Kind.DELETION; else -> Kind.FORMAT }
            out[id] = Region(kind, info?.child("creator")?.text?.trim()?.takeIf { it.isNotEmpty() }, info?.child("date")?.text?.trim()?.let(::parseDate), k)
        }
        return out
    }

    fun parseDate(s: String): Long? = runCatching { java.time.LocalDateTime.parse(s).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(s).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()

    /** Marks [f] as the open change makes it: inserted, or its format changed, by its author at its date. */
    fun mark(f: CharacterFormat, open: Region?) {
        open ?: return
        when (open.kind) {
            Kind.INSERTION -> f.inserted = true
            Kind.FORMAT -> f.formatChanged = true
            Kind.DELETION -> return
        }
        f.revAuthor = open.author; f.revDate = open.date
    }

    /**
     * A paragraph read with deleted paragraph marks in it ([breaks]: each with the format of the paragraph it ended, from
     * its deletion's paragraph) as the paragraphs it was: each before a break with that format and its mark deleted, the
     * last [p] itself (its mark the one left, its comments with it); bookmarks go with their text.
     */
    fun split(p: Paragraph, breaks: Map<TextRun, ParagraphFormat>): List<Paragraph> {
        if (p.runs.none { it in breaks }) return listOf(p)
        val out = ArrayList<Paragraph>()
        var runs = ArrayList<TextRun>()
        var start = 0
        var pos = 0
        val all = ArrayList(p.runs)
        val marks = ArrayList(p.bookmarks)
        for (r in all) {
            val f = breaks[r]
            if (f == null) { runs.add(r); pos += r.text.length; continue }
            val own = marks.filter { it.offset in start..pos }.map { it.copy(offset = it.offset - start) }
            marks.removeAll { it.offset in start..pos }
            if (runs.isEmpty()) runs.add(TextRun("", r.format.copyOf().also { it.deleted = false; it.revAuthor = null; it.revDate = null }))
            out.add(Paragraph(runs, f.copyOf().also { it.revisionMark = RevisionMark(false, r.format.revAuthor, r.format.revDate) }, bookmarks = own.toMutableList()))
            runs = ArrayList()
            pos += r.text.length
            start = pos
        }
        p.runs.clear()
        p.runs.addAll(runs.ifEmpty { listOf(TextRun("", all.last().format.copyOf().also { it.deleted = false; it.revAuthor = null; it.revDate = null })) })
        p.bookmarks.clear()
        marks.forEach { p.bookmarks.add(it.copy(offset = it.offset - start)) }
        out.add(p)
        return out
    }

    /** Every paragraph of [doc] with breaks in it split ([split]): in the body, table cells and text boxes. */
    fun splitAll(doc: Document, breaks: Map<TextRun, ParagraphFormat>) {
        if (breaks.isEmpty()) return
        fun list(l: MutableList<Paragraph>) {
            if (l.none { p -> p.runs.any { it in breaks } }) return
            val out = l.flatMap { split(it, breaks) }
            l.clear(); l.addAll(out)
        }
        val out = ArrayList<Block>(doc.blocks.size)
        for (b in doc.blocks) {
            when (b) {
                is ParagraphBlock -> split(b.paragraph, breaks).let { parts -> if (parts.size == 1) out.add(b) else parts.forEach { out.add(ParagraphBlock(it)) } }
                is TableBlock -> { b.table.rows.forEach { r -> r.cells.forEach { c -> list(c.content) } }; out.add(b) }
                is TextBoxBlock -> { list(b.paragraphs); out.add(b) }
                else -> out.add(b)
            }
        }
        doc.blocks.clear(); doc.blocks.addAll(out)
    }
}
