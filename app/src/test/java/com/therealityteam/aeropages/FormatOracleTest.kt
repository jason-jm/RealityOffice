package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.FormulaEngine
import com.therealityteam.aeropages.editor.NumberFormatting
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What cells show — number formats on numbers, dates, times and text — against LibreOffice
 * (`-Daero.soffice=<its soffice>`): a workbook of values under format codes is saved as xlsx, LibreOffice
 * saves it as CSV "as shown", and the two are compared cell by cell. With `-Daero.formatDir`, the
 * spreadsheets in that folder (the samples, a corpus) are compared the same way, every cell of every sheet.
 * LibreOffice names months and days in the computer's language (`-Daero.formatLang`, zh_CN by default), so
 * the engine does too here. The report is `aero-format-oracle.txt` in the temp folder.
 */
class FormatOracleTest {

    private val numbers = listOf(1234.5678, -1234.5678, 0.0, 0.5, 1e-5, 123456789.0)
    private val dates = listOf(45292.75, 45351.0, 45657.999988, 36526.5, 1.0)

    /** Format codes and what to show under each: numbers, dates and times, or a text. */
    private val codes: List<Pair<String, List<Any>>> = listOf(
        // numbers
        "General" to numbers, "0" to numbers, "0.00" to numbers, "#,##0" to numbers, "#,##0.00" to numbers, "0.0#" to numbers, "#.##" to numbers,
        "00000" to numbers, "0%" to numbers, "0.00%" to numbers, "0.00E+00" to numbers, "##0.0E+0" to numbers, "# ?/?" to numbers, "# ??/??" to numbers,
        "?/4" to numbers, "0.0,," to numbers, "#,##0,\"K\"" to numbers, "\"Total: \"0.00" to numbers, "0;-0;\"zero\"" to numbers, "0;(0);-" to numbers,
        "#,##0.00;[Red]-#,##0.00" to numbers, "#,##0_);(#,##0)" to numbers, "\$#,##0.00" to numbers, "¥#,##0.00;¥-#,##0.00" to numbers,
        "_(\$* #,##0.00_);_(\$* (#,##0.00);_(\$* \"-\"??_);_(@_)" to numbers, "[>=1000]#,##0,\"K\";0" to numbers, "[<0]\"neg\";[>0]\"pos\";\"zero\"" to numbers,
        "0.000_ " to numbers, "@" to numbers, ";;;" to numbers, "0 \"pcs\"" to numbers, "+0;-0;0" to numbers, "[DBNum1]0" to numbers, "[DBNum2]0" to numbers,
        "[DBNum1][\$-804]General" to listOf(12.0, 105.0, 2024.0), "0.00\" 元\"" to numbers, "#,##0.00\"元\";\"-\"#,##0.00\"元\"" to numbers,
        "[Blue]#,##0;[Red]-#,##0" to numbers, "000-0000" to listOf(1234567.0), "0.0\" %\"" to numbers,
        // dates and times
        "yyyy-mm-dd" to dates, "yyyy/m/d" to dates, "m/d/yyyy" to dates, "d-mmm-yy" to dates, "dd mmmm yyyy" to dates, "mmm-yy" to dates, "mmmmm" to dates,
        "dddd" to dates, "ddd, mmm d" to dates, "yyyy\"年\"m\"月\"d\"日\"" to dates, "m\"月\"d\"日\"" to dates, "yyyy\"年\"m\"月\"" to dates,
        "h:mm" to dates, "h:mm:ss" to dates, "hh:mm AM/PM" to dates, "h:mm:ss AM/PM" to dates, "[h]:mm:ss" to listOf(1.5, 0.25, 2.75),
        "[mm]:ss" to listOf(0.05, 0.25), "mm:ss.0" to listOf(0.000694, 0.5), "yyyy-mm-dd hh:mm:ss" to dates, "aaaa" to dates, "[\$-409]mmmm d, yyyy" to dates,
        "[\$-409]dddd" to dates, "[\$-804]aaaa" to dates, "[\$-411]ggge\"年\"m\"月\"d\"日\"" to dates, "e" to dates, "yy" to dates, "d" to dates, "[DBNum1]yyyy\"年\"m\"月\"d\"日\"" to dates,
        "h\"时\"mm\"分\"" to dates, "上午/下午h\"时\"mm\"分\"" to dates,
        // text
        "@" to listOf("abc", "007"), "\"Name: \"@" to listOf("Ada"), "0;0;0;\"[\"@\"]\"" to listOf("x"), "@\" kg\"" to listOf("12"),
    )

    /** Where LibreOffice shows a value otherwise than Excel (or the app chooses otherwise), by the case's code. */
    private val excelDiffers = mapOf(
        "#.##" to "Excel keeps the point when no digit follows it (\"123.\", \".\")",
        "[>=1000]#,##0,\"K\";0" to "Excel puts a minus before a negative number its section does not cover by condition",
        "[DBNum1]0" to "LibreOffice writes a full-width minus", "[DBNum2]0" to "LibreOffice writes 负 for the minus",
        "[DBNum1][\$-804]General" to "LibreOffice writes 一十二 and 〇 (the app: 十二, 零 between digits)",
        "ddd, mmm d" to "Excel's short weekday in Chinese is 周一", "e" to "Excel shows the four-digit year outside Japanese",
        "上午/下午h\"时\"mm\"分\"" to "LibreOffice cannot read Excel's Chinese time code",
        "# ?/?" to "LibreOffice pads ? with figure spaces", "# ??/??" to "LibreOffice pads ? with figure spaces",
        "_(\$* #,##0.00_);_(\$* (#,##0.00);_(\$* \"-\"??_);_(@_)" to "LibreOffice pads ? with figure spaces",
        "NUMBER2/0" to "Excel shows -0 for a negative number rounding to 0", "THOUSANDS/-1" to "Excel shows -0", "THOUSANDS/0" to "Excel shows -0",
    )

    /** Why a case may differ: its code's entry, Excel's rounding to the second (LibreOffice cuts), or the app's own date look. */
    private fun knownReason(code: String, value: String): String? = excelDiffers[code]
        ?: "Excel rounds to the nearest second (LibreOffice cuts)".takeIf { value == "45657.999988" || (code == "mm:ss.0" && value == "0.000694") }
        ?: "the app shows its Date format as yyyy-mm-dd (the file keeps the system's short date)".takeIf { code.startsWith("DATE") }

    @Test
    fun againstLibreOffice() {
        val soffice = System.getProperty("aero.soffice")
        org.junit.Assume.assumeTrue(soffice != null && File(soffice).canExecute())
        val lang = System.getProperty("aero.formatLang", "zh_CN").split('_')
        val saved = Locale.getDefault()
        Locale.setDefault(Locale(lang[0], lang.getOrElse(1) { "" }))
        try { run(soffice!!) } finally { Locale.setDefault(saved) }
    }

    private fun run(soffice: String) {
        val tmp = File(System.getProperty("java.io.tmpdir") ?: ".")
        val dir = File(tmp, "aero-format-oracle").apply { deleteRecursively(); mkdirs() }
        val profile = File(tmp, "aero-lo-profile").apply { mkdirs() }
        val report = ArrayList<String>()

        // The cases: one row per value under its code, the code beside it.
        val s = Sheet(name = "Formats")
        var r = 0
        for ((code, values) in codes) for (v in values) {
            s.ensure(r, 0).apply { text = if (v is Double) FormulaEngine.numberText(v) else v.toString(); formatCode = code.takeIf { it != "General" } }
            s.ensure(r, 1).apply { text = code; formatCode = "@" }
            r++
        }
        // The app's own formats, its plain ones.
        for (f in NumberFormat.entries) for (d in listOf(-1, 0, 3)) for (v in listOf(1234.5678, -0.25, 45292.75)) {
            s.ensure(r, 0).apply { text = FormulaEngine.numberText(v); numberFormat = f; decimals = d }
            s.ensure(r, 1).apply { text = "${f.name}/$d"; formatCode = "@" }
            r++
        }
        val src = File(dir, "formats.xlsx").apply { outputStream().use { XlsxWriter.write(Workbook(mutableListOf(s)), it) } }
        convert(soffice, profile, dir, listOf(src))
        val theirs = File(dir, "formats-Formats.csv").takeIf { it.exists() }?.let { csv(it.readText()) }
        if (theirs == null) report.add("formats: LibreOffice did not convert")
        else {
            var same = 0
            val diffs = ArrayList<String>()
            val known = ArrayList<String>()
            for (row in 0 until r) {
                val ours = shown(s, row, 0, listOf(s))
                val lo = theirs.getOrNull(row)?.getOrNull(0).orEmpty()
                val code = s.cell(row, 1)?.text.orEmpty(); val value = s.cell(row, 0)?.text.orEmpty()
                val line = "$code on $value: ours \"$ours\" | LibreOffice \"$lo\""
                when {
                    ours == lo -> same++
                    knownReason(code, value) != null -> known.add("$line — ${knownReason(code, value)}")
                    else -> diffs.add(line)
                }
            }
            report.add("formats: $r cells, $same alike, ${diffs.size} differ, ${known.size} where LibreOffice departs from Excel")
            report.addAll(diffs)
            if (known.isNotEmpty()) { report.add("— LibreOffice departs from Excel (the app follows Excel):"); report.addAll(known) }
        }

        // Spreadsheets from a folder: every cell of every sheet.
        System.getProperty("aero.formatDir")?.let { folder ->
            val files = File(folder).walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("xlsx", "xlsm", "xltx", "xltm") }.toList()
            val cdir = File(dir, "corpus").apply { mkdirs() }
            val copies = files.mapIndexed { i, f -> File(cdir, "f$i.${f.extension}").also { f.copyTo(it, overwrite = true) } to f }
            convert(soffice, profile, cdir, copies.map { it.first })
            for ((copy, f) in copies) {
                val tag = f.path.removePrefix(folder).trimStart('/')
                val read = runCatching { XlsxReader.read(f.readBytes()) }
                if (read.isFailure) { report.add("$tag: unreadable"); continue }
                val book = read.getOrThrow()
                var n = 0; var same = 0
                val diffs = ArrayList<String>()
                for (sheet in book.sheets) {
                    val out = File(cdir, "${copy.nameWithoutExtension}-${sheet.name}.csv")
                    if (!out.exists()) continue
                    val rows = csv(out.readText())
                    for ((key, cell) in sheet.cells.entries.sortedBy { it.key }) {
                        if (cell.text.isEmpty()) continue
                        val row = (key / 1000L).toInt(); val col = (key % 1000L).toInt()
                        val lo = rows.getOrNull(row)?.getOrNull(col) ?: continue
                        val ours = shown(sheet, row, col, book.sheets, book.names)
                        n++
                        if (ours == lo || numerically(ours, lo, cell)) same++
                        else diffs.add("${sheet.name}!${Workbook.colLabel(col)}${row + 1} ${cell.text.take(40)} [${cell.formatCode ?: cell.numberFormat}]: ours \"$ours\" | LibreOffice \"$lo\"")
                    }
                }
                report.add("$tag: $n cells, $same alike")
                diffs.take(15).forEach { report.add("  $it") }
                if (diffs.size > 15) report.add("  … ${diffs.size - 15} more")
            }
        }
        File(tmp, "aero-format-oracle.txt").writeText(report.joinToString("\n"))
    }

    /** What the cell shows (its value under its format), as the grid draws it before fitting the column. */
    private fun shown(s: Sheet, row: Int, col: Int, sheets: List<Sheet>, names: Map<String, String> = emptyMap()): String {
        val raw = FormulaEngine.display(s, row, col, sheets, names)
        return NumberFormatting.shown(raw, s.cell(row, col)).text
    }

    /** A General number LibreOffice writes to fifteen digits and the grid to fewer (it fits a column): alike to six. */
    private fun numerically(ours: String, lo: String, cell: com.therealityteam.aeropages.model.Cell): Boolean {
        if (cell.formatCode != null || cell.numberFormat != NumberFormat.GENERAL) return false
        val a = ours.toDoubleOrNull() ?: return false
        val b = lo.toDoubleOrNull() ?: return false
        return kotlin.math.abs(a - b) <= 1e-6 * maxOf(1.0, kotlin.math.abs(b))
    }

    private fun convert(soffice: String, profile: File, out: File, inputs: List<File>) {
        if (inputs.isEmpty()) return
        val filter = "csv:Text - txt - csv (StarCalc):44,34,76,1,,1033,false,true,true,false,false,-1"
        val p = ProcessBuilder(listOf(soffice, "-env:UserInstallation=${profile.toURI()}", "--headless", "--convert-to", filter, "--outdir", out.path) + inputs.map { it.path })
            .redirectErrorStream(true).redirectOutput(File(out, "soffice.log")).start()
        p.waitFor(20, java.util.concurrent.TimeUnit.MINUTES)
    }

    /** CSV rows and fields: commas, double quotes around fields that need them, "" inside them. */
    private fun csv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(field.toString()); field.setLength(0) }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(field.toString()); field.setLength(0); rows.add(row); row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }
}
