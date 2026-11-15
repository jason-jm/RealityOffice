package com.therealityteam.aeropages.format

import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphFormat
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.Table
import com.therealityteam.aeropages.model.TableBlock
import java.io.InputStream

/**
 * Lightweight importers for the common text-based document formats Word can open
 * besides .doc/.docx: plain text, Markdown, RTF, HTML and CSV. OpenDocument text has its own
 * reader ([com.therealityteam.aeropages.format.odf.OdtReader]).
 * Each produces our block model; formatting fidelity is best-effort (the goal is
 * to open and edit the content, then save as .docx).
 */
object TextImporters {

    fun txt(input: InputStream): Document =
        docOf(readText(input).split('\n').map { line -> ParagraphBlock(Paragraph.of(line.trimEnd('\r'))) })

    fun markdown(input: InputStream): Document {
        val blocks = mutableListOf<Block>()
        readText(input).split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            val t = line.trimStart()
            when {
                t.startsWith("### ") -> blocks += heading(stripMd(t.removePrefix("### ")), ParagraphStyle.HEADING3)
                t.startsWith("## ") -> blocks += heading(stripMd(t.removePrefix("## ")), ParagraphStyle.HEADING2)
                t.startsWith("# ") -> blocks += heading(stripMd(t.removePrefix("# ")), ParagraphStyle.HEADING1)
                t.startsWith("- ") || t.startsWith("* ") -> blocks += ParagraphBlock(Paragraph.of("• " + stripMd(t.drop(2))))
                t.startsWith("> ") -> blocks += ParagraphBlock(Paragraph.of(stripMd(t.removePrefix("> "))))
                else -> blocks += ParagraphBlock(Paragraph.of(stripMd(line)))
            }
        }
        return docOf(blocks)
    }

    /** RTF: see [com.therealityteam.aeropages.format.rtf.RtfReader]. */
    fun rtf(input: InputStream): Document = com.therealityteam.aeropages.format.rtf.RtfReader.read(input.readBytes())

    fun html(input: InputStream): Document = docOf(htmlToParagraphs(readText(input)))

    fun csv(input: InputStream): Document {
        val rows = readText(input).split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            .map { Csv.splitLine(it) }
        if (rows.isEmpty()) return Document.blank()
        val cols = rows.maxOf { it.size }.coerceAtLeast(1)
        val table = Table.of(rows.size, cols)
        rows.forEachIndexed { r, cells ->
            cells.forEachIndexed { c, v ->
                if (c < cols) table.rows[r].cells[c].content[0] = Paragraph.of(v)
            }
        }
        return docOf(listOf(TableBlock(table)))
    }

    // --- helpers ---------------------------------------------------------------

    private fun readText(input: InputStream): String = input.readBytes().toString(Charsets.UTF_8)

    private fun docOf(blocks: List<Block>): Document =
        Document((blocks.ifEmpty { listOf(ParagraphBlock(Paragraph.of(""))) }).toMutableList())

    private fun heading(text: String, style: ParagraphStyle): Block =
        ParagraphBlock(Paragraph.of(text, pFormat = ParagraphFormat(style = style)))

    /** Strip the most common inline Markdown markers, keeping the text. */
    private fun stripMd(s: String): String =
        s.replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("`(.+?)`"), "$1")
            .replace(Regex("\\[(.+?)]\\((.+?)\\)"), "$1")

    private fun htmlToParagraphs(html: String): List<Block> {
        val blocks = mutableListOf<Block>()
        // Turn block-level tags into line breaks (capture heading level first).
        var s = html.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|li|tr|h[1-6])>"), "\n")
        // Mark headings so we can style them after stripping.
        s = s.replace(Regex("(?i)<h1[^>]*>"), "\nH1")
            .replace(Regex("(?i)<h2[^>]*>"), "\nH2")
            .replace(Regex("(?i)<h3[^>]*>"), "\nH3")
        s = s.replace(Regex("<[^>]+>"), "") // drop remaining tags
        s = decodeEntities(s)
        s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            when {
                line.startsWith("H1") -> blocks += heading(line.removePrefix("H1").trim(), ParagraphStyle.HEADING1)
                line.startsWith("H2") -> blocks += heading(line.removePrefix("H2").trim(), ParagraphStyle.HEADING2)
                line.startsWith("H3") -> blocks += heading(line.removePrefix("H3").trim(), ParagraphStyle.HEADING3)
                else -> blocks += ParagraphBlock(Paragraph.of(line))
            }
        }
        return blocks
    }

    private fun decodeEntities(s: String): String = OfficeXml.decode(s.replace("&nbsp;", " "))
}
