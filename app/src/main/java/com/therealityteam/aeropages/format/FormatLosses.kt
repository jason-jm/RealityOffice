package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.model.CharacterFormat
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.VAlign
import com.therealityteam.aeropages.model.Workbook

/**
 * What a document holds that a format cannot keep — CSV for a workbook, plain text for a document —
 * so that saving in it would drop that without a word. A file opened from CSV or text holds none of
 * it until it is edited (number formats count for nothing: CSV keeps what they show).
 */
object FormatLosses {

    enum class Loss { OTHER_SHEETS, FORMULAS, FORMATTING, OBJECTS, CELL_EXTRAS, NOTES }

    /** The sheet a CSV holds: the first one shown. */
    fun csvSheet(wb: Workbook): Sheet = wb.sheets.firstOrNull { !it.hidden } ?: wb.sheets.first()

    /** What a CSV of [wb] — [csvSheet]'s values as they show — leaves out. */
    fun csv(wb: Workbook): Set<Loss> {
        if (wb.sheets.isEmpty()) return emptySet()
        val out = HashSet<Loss>()
        if (wb.sheets.size > 1) out += Loss.OTHER_SHEETS
        val sheet = csvSheet(wb)
        for (cell in sheet.cells.values) {
            if (cell.text.startsWith("=")) out += Loss.FORMULAS
            if (cell.format != CharacterFormat() || cell.richRuns() != null || cell.align != null || cell.wrap || cell.vAlign != VAlign.MIDDLE ||
                cell.borderTop != null || cell.borderBottom != null || cell.borderLeft != null || cell.borderRight != null) out += Loss.FORMATTING
            if (cell.comment != null || cell.link != null) out += Loss.CELL_EXTRAS
        }
        if (sheet.charts.isNotEmpty() || sheet.images.isNotEmpty() || sheet.shapes.isNotEmpty() || sheet.sparklines.isNotEmpty()) out += Loss.OBJECTS
        if (sheet.merges.isNotEmpty() || sheet.validations.isNotEmpty() || sheet.condFormats.isNotEmpty() || sheet.tables.isNotEmpty() ||
            sheet.autoFilter != null || wb.names.isNotEmpty()) out += Loss.CELL_EXTRAS
        return Loss.entries.filter { it in out }.toSet()
    }

    /** What plain text of [doc] — its paragraphs' words, one per line — leaves out. */
    fun text(doc: Document): Set<Loss> {
        val out = HashSet<Loss>()
        for (b in doc.blocks) {
            if (b !is ParagraphBlock) { out += Loss.OBJECTS; continue }
            val p = b.paragraph
            if (p.format != ParagraphFormat() || p.runs.any { it.format != CharacterFormat() }) out += Loss.FORMATTING
        }
        if (listOf(doc.header, doc.footer, doc.evenHeader, doc.evenFooter, doc.firstHeader, doc.firstFooter).any { it != null && !it.isEmpty() } ||
            doc.comments.isNotEmpty() || doc.footnotes.isNotEmpty() || doc.endnotes.isNotEmpty()) out += Loss.NOTES
        return Loss.entries.filter { it in out }.toSet()
    }
}
