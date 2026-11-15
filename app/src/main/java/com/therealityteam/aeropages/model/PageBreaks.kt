package com.therealityteam.aeropages.model

/**
 * Page breaks inside paragraphs (Word's `<w:br w:type="page"/>` in the middle of a paragraph) made the
 * page-break paragraphs the layout breaks pages at: the text before the break, the break, the text after
 * — the parts keep the paragraph's format (its list marker on the first part only, a section break on the
 * last), its bookmarks go where their text does. Top-level paragraphs only: Word ignores a page break in a
 * table cell.
 */
object PageBreaks {
    fun split(blocks: MutableList<Block>) {
        var i = 0
        while (i < blocks.size) {
            val p = (blocks[i] as? ParagraphBlock)?.paragraph
            if (p == null || p.isPageBreak() || p.runs.none { PAGE_BREAK in it.text }) { i++; continue }
            val parts = parts(p)
            blocks.removeAt(i)
            blocks.addAll(i, parts.map { ParagraphBlock(it) })
            i += parts.size
        }
    }

    private fun parts(p: Paragraph): List<Paragraph> {
        class Part(val runs: MutableList<TextRun> = mutableListOf(), var from: Int = 0, var to: Int = 0)
        val texts = ArrayList<Part>()
        var cur = Part()
        var at = 0
        for (r in p.runs) {
            var start = 0
            for ((k, ch) in r.text.withIndex()) {
                if (ch != '\u000C') continue
                if (k > start) cur.runs.add(TextRun(r.text.substring(start, k), r.format.copyOf()))
                cur.to = at + k; texts.add(cur)
                cur = Part(from = at + k + 1)
                start = k + 1
            }
            if (start < r.text.length) cur.runs.add(TextRun(r.text.substring(start), r.format.copyOf()))
            at += r.text.length
        }
        cur.to = at; texts.add(cur)
        val out = ArrayList<Paragraph>()
        val lastText = texts.indexOfLast { part -> part.runs.any { it.text.isNotEmpty() } }
        texts.forEachIndexed { n, part ->
            if (n > 0) out.add(Paragraph.of(PAGE_BREAK, pFormat = ParagraphFormat(spaceBeforePt = 0f, spaceAfterPt = 0f)))
            val hasText = part.runs.any { it.text.isNotEmpty() }
            // An empty part between breaks shows nothing; the last part stays for the paragraph's mark (its section break).
            if (!hasText && n != texts.lastIndex) return@forEachIndexed
            if (!hasText && lastText >= 0 && p.format.sectionBreak == null) return@forEachIndexed
            val f = p.format.copyOf()
            if (n > 0) { f.listKind = ListKind.NONE; f.pageBreakBefore = false }
            if (n != texts.lastIndex) f.sectionBreak = null
            val q = Paragraph(part.runs.ifEmpty { mutableListOf(TextRun("", p.runs.lastOrNull()?.format?.copyOf() ?: CharacterFormat())) }, f,
                if (out.none { !it.isPageBreak() }) p.id else Paragraph.nextId())
            p.bookmarks.filter { it.offset in part.from..part.to }.forEach { q.bookmarks.add(it.copy(offset = it.offset - part.from)) }
            out.add(q)
        }
        return out
    }
}
