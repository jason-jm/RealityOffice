package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook

/** Minimal RFC 4180-style CSV parsing shared by the Pages importer and the Sheets module. */
object Csv {

    /** Split one line on commas, honouring double-quoted fields with `""` escapes. */
    fun splitLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    /** Parse CSV text into a one-sheet workbook (the Sheets module's `.csv` open path). */
    fun toWorkbook(bytes: ByteArray): Workbook {
        val sheet = Sheet()
        val lines = bytes.toString(Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
        var maxC = 0
        lines.forEachIndexed { r, line ->
            if (line.isNotEmpty()) {
                val cells = splitLine(line)
                cells.forEachIndexed { c, v -> if (v.isNotEmpty() && c < Sheet.MAX_COLS) sheet.ensure(r, c).text = v }
                if (cells.size > maxC) maxC = cells.size
            }
        }
        sheet.rowCount = maxOf(20, lines.size + 4)
        sheet.colCount = maxOf(8, maxC + 2).coerceAtMost(Sheet.MAX_COLS)
        return Workbook(mutableListOf(sheet))
    }
}
