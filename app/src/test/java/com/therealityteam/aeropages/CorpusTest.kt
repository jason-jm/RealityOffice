package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.DeskFiles
import com.therealityteam.aeropages.format.DeskKind
import com.therealityteam.aeropages.format.DocumentRepository
import com.therealityteam.aeropages.format.UnsupportedContent
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.ui.readDeck
import com.therealityteam.aeropages.ui.readWorkbook
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Every file under `-Daero.corpusDir=<folder>` — real documents from elsewhere (WPS's own templates and
 * samples, a folder of downloads …) — opened as the app opens it, each with what came in: its module,
 * how long it took, what it holds (paragraphs and characters, sheets and cells, slides and shapes), what
 * the scanner finds it holding that the app would drop, or the error it failed with. The report is
 * `aero-corpus.txt` in the temp folder; nothing is asserted (the files are not ours to keep).
 */
class CorpusTest {

    @Test fun openEveryFileInAFolder() {
        val dir = System.getProperty("aero.corpusDir")?.let { File(it) }
        assumeTrue(dir != null && dir.isDirectory)
        val files = dir!!.walkTopDown().filter { it.isFile && DeskFiles.kindOfOrNull(it.path) != null }.sortedBy { it.path }.toList()
        val lines = ArrayList<String>()
        var failed = 0
        for (f in files) {
            val name = f.relativeTo(dir).path
            val started = System.nanoTime()
            val what = runCatching { describe(f) }.getOrElse { e -> failed++; "FAILED ${e.javaClass.simpleName}: ${e.message?.take(160)}" }
            val ms = (System.nanoTime() - started) / 1_000_000
            val held = runCatching { UnsupportedContent.scan(f.path) }.getOrDefault(emptySet())
            lines += "$name [${DeskFiles.kindOf(f.path)}, ${f.length() / 1024} KB, $ms ms] $what" + (if (held.isEmpty()) "" else " | holds $held")
        }
        lines += "${files.size} files, $failed failed"
        File(System.getProperty("java.io.tmpdir"), "aero-corpus.txt").writeText(lines.joinToString("\n"))
    }

    private fun describe(f: File): String = when (DeskFiles.kindOf(f.path)) {
        DeskKind.PAGES -> {
            val d = DocumentRepository.open(f.path)
            val paras = d.blocks.count { it is ParagraphBlock }
            val text = d.blocks.filterIsInstance<ParagraphBlock>().sumOf { it.paragraph.plainText().length }
            "${d.blocks.size} blocks ($paras paragraphs, ${d.blocks.count { it is TableBlock }} tables, ${d.blocks.count { it is ImageBlock }} pictures), " +
                "$text chars, page ${d.page.widthPt.toInt()}×${d.page.heightPt.toInt()}: \"${d.blocks.filterIsInstance<ParagraphBlock>().joinToString(" ¶ ") { it.paragraph.plainText() }.take(60)}\""
        }
        DeskKind.SHEETS -> {
            val wb = readWorkbook(f.path)
            wb.sheets.joinToString("; ") { s -> "${s.name}: ${s.cells.size} cells, ${s.charts.size} charts, ${s.images.size} pictures, ${s.shapes.size} shapes" }
        }
        DeskKind.SLIDES -> {
            val p = readDeck(f.path)
            "${p.slides.size} slides ${p.slideSize.widthPt.toInt()}×${p.slideSize.heightPt.toInt()}, ${p.designs.size} designs, " +
                "${p.slides.sumOf { it.shapes.size }} shapes, ${p.slides.sumOf { it.images.size }} pictures: \"${p.slides.firstOrNull()?.title?.text?.take(40).orEmpty()}\""
        }
    }
}
