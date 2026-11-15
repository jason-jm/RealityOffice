package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextBoxBlock
import org.junit.Test
import java.io.File

/**
 * What the app reads from documents and presentations against what LibreOffice reads
 * (`-Daero.soffice=<its soffice>`, `-Daero.textDir=<a folder>`): LibreOffice saves each document as
 * text and each presentation as a PDF (its text taken with pdftotext), and the letters and digits in it
 * are counted against the app's — the body, tables, text boxes, notes, headers and footers it reads
 * (for slides: titles, bodies, shapes, tables, charts' titles, the designs' shapes). What LibreOffice
 * has that the app lacks is content the app loses; the report (`aero-text-oracle.txt` in the temp
 * folder) lists each file's share missing, worst first, with LibreOffice's lines the app has nowhere.
 */
class TextOracleTest {

    private val docExts = setOf("doc", "docx", "docm", "dot", "dotx", "dotm", "wps", "wpt", "odt", "ott", "rtf")
    private val deckExts = setOf("ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "dps", "dpt", "odp", "otp")

    @Test
    fun againstLibreOffice() {
        val soffice = System.getProperty("aero.soffice")
        val folder = System.getProperty("aero.textDir")
        org.junit.Assume.assumeTrue(soffice != null && File(soffice).canExecute() && folder != null)
        val tmp = File(System.getProperty("java.io.tmpdir") ?: ".")
        // LibreOffice's conversions are kept between runs (they take long); -Daero.textFresh=1 starts over.
        val dir = File(tmp, "aero-text-oracle").apply { if (System.getProperty("aero.textFresh") != null) deleteRecursively(); mkdirs() }
        val profile = File(tmp, "aero-lo-profile").apply { mkdirs() }
        val files = File(folder!!).walkTopDown().filter { it.isFile && it.extension.lowercase() in docExts + deckExts }.sortedBy { it.path }.toList()
        val copies = files.mapIndexed { i, f -> File(dir, "f$i.${f.extension.lowercase()}").also { f.copyTo(it, overwrite = true) } to f }
        val docs = copies.filter { it.first.extension in docExts }
        val decks = copies.filter { it.first.extension in deckExts }
        convert(soffice!!, profile, dir, "txt:Text (encoded):UTF8", docs.map { it.first })
        convert(soffice, profile, dir, "pdf", decks.map { it.first })

        class Result(val tag: String, val loChars: Int, val ourChars: Int, val missing: Int, val lines: List<String>)
        val results = ArrayList<Result>()
        val failures = ArrayList<String>()
        for ((copy, original) in copies) {
            val tag = original.path.removePrefix(folder).trimStart('/')
            val theirs = when (copy.extension) {
                in docExts -> File(dir, copy.nameWithoutExtension + ".txt").takeIf { it.exists() }?.readText()
                else -> File(dir, copy.nameWithoutExtension + ".pdf").takeIf { it.exists() }?.let { pdfText(it) }
            }
            if (theirs == null) { failures.add("$tag: LibreOffice did not convert"); continue }
            val read = runCatching {
                if (copy.extension in docExts) documentText(com.therealityteam.aeropages.format.DocumentRepository.open(copy.path))
                else deckText(com.therealityteam.aeropages.ui.readDeck(copy.path))
            }
            if (read.isFailure) { failures.add("$tag: unreadable (${read.exceptionOrNull()})"); continue }
            val ours = read.getOrThrow()
            File(dir, copy.nameWithoutExtension + ".ours.txt").writeText(ours)
            val lo = counts(theirs); val us = counts(ours)
            val missing = lo.entries.sumOf { (c, n) -> maxOf(0, n - (us[c] ?: 0)) }
            // LibreOffice's lines whose letters and digits are nowhere in what the app reads.
            val flat = normal(ours)
            val lost = theirs.lines().map { it.trim() }.filter { line -> normal(line).let { it.length >= 2 && !flat.contains(it) } }
            results.add(Result(tag, lo.values.sum(), us.values.sum(), missing, lost))
        }
        val report = ArrayList<String>()
        val total = results.sumOf { it.loChars }; val lost = results.sumOf { it.missing }
        report.add("files: ${results.size} compared, ${failures.size} not; LibreOffice's letters and digits ${total}, the app lacks $lost (${pct(lost, total)})")
        report.addAll(failures)
        for (r in results.sortedByDescending { if (it.loChars == 0) 0.0 else it.missing.toDouble() / it.loChars }) {
            if (r.missing == 0) continue
            report.add("${r.tag}: LibreOffice ${r.loChars}, the app ${r.ourChars}, lacks ${r.missing} (${pct(r.missing, r.loChars)})")
            r.lines.take(4).forEach { report.add("  « ${it.take(100)} »") }
        }
        File(tmp, "aero-text-oracle.txt").writeText(report.joinToString("\n"))
    }

    private fun pct(n: Int, of: Int) = if (of == 0) "0%" else String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * n / of)

    /** Letters and digits, lower case, by how often each comes. */
    private fun counts(s: String): Map<Char, Int> {
        val m = HashMap<Char, Int>()
        for (c in s.lowercase()) if (c.isLetterOrDigit()) m[c] = (m[c] ?: 0) + 1
        return m
    }

    private fun normal(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    private fun para(p: Paragraph?) = p?.plainText().orEmpty()

    /** All the text the app shows for a document: its paragraphs with their list labels, tables, boxes, the contents' entries, notes, headers and footers. */
    private fun documentText(d: Document): String = buildString {
        val labels = com.therealityteam.aeropages.model.PageMetrics.listLabels(d)
        fun line(p: Paragraph) = appendLine((labels[p]?.let { "$it " } ?: "") + p.plainText())
        fun blocks(list: List<com.therealityteam.aeropages.model.Block>) {
            for (b in list) when (b) {
                is ParagraphBlock -> line(b.paragraph)
                is TableBlock -> b.table.rows.forEach { row -> row.cells.forEach { cell -> cell.content.forEach { line(it) } } }
                is TextBoxBlock -> b.paragraphs.forEach { appendLine(it.plainText()) }
                is com.therealityteam.aeropages.model.TocBlock -> d.tocEntries().forEach { appendLine(it.text) }
                is ImageBlock -> Unit
                else -> Unit
            }
        }
        blocks(d.blocks)
        d.footnotes.forEach { appendLine(it.text) }
        d.endnotes.forEach { appendLine(it.text) }
        listOf(d.header, d.footer, d.evenHeader, d.evenFooter, d.firstHeader, d.firstFooter).forEach { appendLine(para(it)) }
        d.comments.forEach { appendLine(it.text) }
    }

    /** All the text the app holds for a presentation. */
    private fun deckText(p: Presentation): String = buildString {
        for (s in p.slides) {
            appendLine(s.title.paragraph.plainText()); appendLine(s.body.paragraph.plainText())
            s.shapes.forEach { appendLine(it.box.paragraph.plainText()) }
            s.tables.forEach { t -> t.cells.forEach { row -> row.forEach { appendLine(it.paragraph.plainText()) } } }
            s.charts.forEach { c -> appendLine(c.title); c.categories.forEach { appendLine(it) }; c.series.forEach { appendLine(it.name) } }
            appendLine(s.notes)
        }
        p.designs.forEach { d -> d.items.forEach { if (it is com.therealityteam.aeropages.model.DesignShape) appendLine(it.shape.box.paragraph.plainText()) } }
    }

    private fun pdfText(pdf: File): String? {
        val out = File(pdf.parentFile, pdf.nameWithoutExtension + ".pdf.txt")
        val p = ProcessBuilder("/opt/homebrew/bin/pdftotext", "-enc", "UTF-8", pdf.path, out.path).redirectErrorStream(true).start()
        p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES)
        return out.takeIf { it.exists() }?.readText()
    }

    /** One file at a time: LibreOffice hangs on some files, and a hung batch takes the rest with it. */
    private fun convert(soffice: String, profile: File, out: File, filter: String, inputs: List<File>) {
        val log = File(out, "soffice-${filter.substringBefore(':')}.log")
        for (input in inputs) {
            if (File(out, input.nameWithoutExtension + "." + filter.substringBefore(':')).exists() || File(out, input.nameWithoutExtension + ".timeout").exists()) continue
            val p = ProcessBuilder(soffice, "-env:UserInstallation=${profile.toURI()}", "--headless", "--convert-to", filter, "--outdir", out.path, input.path)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.MINUTES)) {
                p.destroyForcibly()
                ProcessBuilder("pkill", "-9", "-f", profile.path).start().waitFor()
                log.appendText("timed out: ${input.name}\n")
                File(out, input.nameWithoutExtension + ".timeout").writeText("")
            }
        }
    }
}
