package com.therealityteam.aeropages.model

/**
 * Office's Check Accessibility: what a person using a screen reader, or reading without colour, would stumble on — a
 * picture with no alternative text, a table with no header row, a slide with no title, merged cells… — each with where
 * it is, as Word, PowerPoint and Excel list them under Errors, Warnings and Tips.
 */
object AccessibilityCheck {
    enum class Severity { ERROR, WARNING, TIP }

    enum class Kind(val severity: Severity) {
        MISSING_ALT_TEXT(Severity.ERROR),
        MISSING_TABLE_HEADER(Severity.ERROR),
        MISSING_SLIDE_TITLE(Severity.ERROR),
        UNCLEAR_LINK(Severity.WARNING),
        DUPLICATE_SLIDE_TITLE(Severity.WARNING),
        MERGED_CELLS(Severity.WARNING),
        SKIPPED_HEADING(Severity.TIP),
        DEFAULT_SHEET_NAME(Severity.TIP),
    }

    /**
     * One finding: its [kind], the object it is about ([target]: a picture, a table, a paragraph, a merge…), where that is
     * ([slide] / [sheet] index, else -1) and its [ordinal] among its kind there (picture 2, table 1) — or a [label] that
     * names it better (a heading's or a link's text, a range, a sheet's name).
     */
    data class Issue(val kind: Kind, val target: Any?, val ordinal: Int = 0, val label: String = "", val slide: Int = -1, val sheet: Int = -1) {
        val severity: Severity get() = kind.severity
    }

    private val HEADINGS = mapOf(ParagraphStyle.HEADING1 to 1, ParagraphStyle.HEADING2 to 2, ParagraphStyle.HEADING3 to 3)

    /** Whether a link's shown text is just an address (Office's "Unclear hyperlink text"). */
    internal fun looksLikeAddress(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.") || t.startsWith("mailto:")
    }

    /** Word's checks: pictures, tables, links in the order they come, and headings that skip a level. */
    fun of(doc: Document): List<Issue> {
        val out = ArrayList<Issue>()
        var pictures = 0; var tables = 0; var prevHeading = 0
        fun picture(img: ImageBlock) {
            pictures++
            if (!img.decorative && img.altText.isBlank()) out.add(Issue(Kind.MISSING_ALT_TEXT, img, pictures))
        }
        fun links(p: Paragraph) {
            val text = LinkedHashMap<String, StringBuilder>()
            for (r in p.runs) r.format.link?.takeIf { !it.startsWith("#") }?.let { text.getOrPut(it) { StringBuilder() }.append(r.text) }
            for ((_, t) in text) if (looksLikeAddress(t.toString())) out.add(Issue(Kind.UNCLEAR_LINK, p, label = t.toString().trim()))
        }
        for (b in doc.blocks) when (b) {
            is ImageBlock -> picture(b)
            is TableBlock -> {
                tables++
                if (b.table.rows.firstOrNull()?.header != true) out.add(Issue(Kind.MISSING_TABLE_HEADER, b, tables))
                b.table.rows.forEach { r -> r.cells.forEach { c -> c.images.forEach(::picture); c.content.forEach(::links) } }
            }
            is ParagraphBlock -> {
                val p = b.paragraph
                HEADINGS[p.format.style]?.let { level ->
                    if (prevHeading > 0 && level > prevHeading + 1) out.add(Issue(Kind.SKIPPED_HEADING, p, label = p.plainText().trim()))
                    prevHeading = level
                }
                links(p)
            }
            else -> Unit
        }
        return out
    }

    /** PowerPoint's checks, slide by slide: pictures, a title (and one no other slide has), tables. */
    fun of(p: Presentation): List<Issue> {
        val out = ArrayList<Issue>()
        val seen = HashSet<String>()
        p.slides.forEachIndexed { i, s ->
            val title = s.title.text.trim()
            if (title.isEmpty()) out.add(Issue(Kind.MISSING_SLIDE_TITLE, s, slide = i))
            else if (!seen.add(title.lowercase())) out.add(Issue(Kind.DUPLICATE_SLIDE_TITLE, s, label = title, slide = i))
            s.images.forEachIndexed { k, img -> if (!img.decorative && img.altText.isBlank()) out.add(Issue(Kind.MISSING_ALT_TEXT, img, k + 1, slide = i)) }
            s.tables.forEachIndexed { k, t -> if (!t.headerRow) out.add(Issue(Kind.MISSING_TABLE_HEADER, t, k + 1, slide = i)) }
        }
        return out
    }

    private val DEFAULT_SHEET = Regex("""^(sheet|工作表|シート)\s*\d+$""", RegexOption.IGNORE_CASE)

    /** Sheet1 and its kin — and the name the reader's language gives a new sheet (Tabelle1, Feuil1, Лист1…). */
    private fun isDefaultSheetName(name: String): Boolean {
        if (DEFAULT_SHEET.matches(name)) return true
        val prefix = Texts.get(TextId.SHEET_N, 1).removeSuffix("1")
        return prefix.isNotEmpty() && name.startsWith(prefix, ignoreCase = true) && name.length > prefix.length && name.substring(prefix.length).trim().all { it.isDigit() }
    }

    /** Excel's checks, sheet by sheet: pictures, merged cells, and sheets still called Sheet1. */
    fun of(wb: Workbook): List<Issue> {
        val out = ArrayList<Issue>()
        wb.sheets.forEachIndexed { i, s ->
            if (s.hidden) return@forEachIndexed
            s.images.forEachIndexed { k, img -> if (!img.decorative && img.altText.isBlank()) out.add(Issue(Kind.MISSING_ALT_TEXT, img, k + 1, label = img.name, sheet = i)) }
            s.merges.forEach { m -> out.add(Issue(Kind.MERGED_CELLS, m, label = "${Workbook.colLabel(m.c1)}${m.r1 + 1}:${Workbook.colLabel(m.c2)}${m.r2 + 1}", sheet = i)) }
            if (isDefaultSheetName(s.name.trim())) out.add(Issue(Kind.DEFAULT_SHEET_NAME, s, label = s.name, sheet = i))
        }
        return out
    }
}
