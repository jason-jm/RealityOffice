package com.therealityteam.aeropages.model

/**
 * PowerPoint's statistics of a deck (its Properties ▸ Statistics): slides and hidden ones, the words and paragraphs on
 * them (titles, text, shapes, tables — counted as Word counts, [WordCounts]), the slides with notes, and the videos and
 * sounds.
 */
data class DeckStats(val slides: Int, val hidden: Int, val words: Int, val paragraphs: Int, val notes: Int, val media: Int) {
    companion object {
        fun of(p: Presentation): DeckStats {
            var words = 0; var paragraphs = 0
            fun count(text: String) {
                if (text.isBlank()) return
                words += WordCounts.of(text).words
                paragraphs += text.split('\n').count { it.isNotBlank() }
            }
            for (s in p.slides) {
                count(s.title.text); count(s.body.text)
                s.shapes.forEach { count(it.box.text) }
                s.tables.forEach { t -> t.cells.forEach { row -> row.forEach { count(it.text) } } }
            }
            return DeckStats(p.slides.size, p.slides.count { it.hidden }, words, paragraphs,
                p.slides.count { it.notes.isNotBlank() }, p.slides.sumOf { s -> s.images.count { it.media != null } })
        }
    }
}

/**
 * Excel's Workbook Statistics for a sheet or the whole book: where its data ends ([end], "D7"; null when it has none),
 * the cells with data, its tables, formulas, charts, pictures and comments.
 */
data class SheetStats(val end: String?, val cells: Int, val tables: Int, val formulas: Int, val charts: Int, val images: Int, val comments: Int) {
    operator fun plus(o: SheetStats) = SheetStats(null, cells + o.cells, tables + o.tables, formulas + o.formulas, charts + o.charts, images + o.images, comments + o.comments)

    companion object {
        fun of(s: Sheet): SheetStats {
            var cells = 0; var formulas = 0; var comments = 0; var lastRow = -1; var lastCol = -1
            for ((key, c) in s.cells) {
                val data = c.text.isNotBlank()
                if (data) { cells++; if (c.text.startsWith("=")) formulas++ }
                if (!c.comment.isNullOrBlank()) comments++
                if (data) { lastRow = maxOf(lastRow, (key / 1000L).toInt()); lastCol = maxOf(lastCol, (key % 1000L).toInt()) }
            }
            val end = if (lastRow < 0) null else "${Workbook.colLabel(lastCol)}${lastRow + 1}"
            return SheetStats(end, cells, s.tables.size, formulas, s.charts.size, s.images.size, comments)
        }

        fun of(wb: Workbook): SheetStats = wb.sheets.fold(SheetStats(null, 0, 0, 0, 0, 0, 0)) { acc, s -> acc + of(s) }
    }
}
