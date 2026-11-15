package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.docx.DocxReader
import com.therealityteam.aeropages.format.docx.DocxWriter
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.odf.OdsReader
import com.therealityteam.aeropages.format.odf.OdsWriter
import com.therealityteam.aeropages.format.odf.OdtReader
import com.therealityteam.aeropages.format.odf.OdtWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.xlsx.XlsxReader
import com.therealityteam.aeropages.format.xlsx.XlsxWriter
import com.therealityteam.aeropages.model.Block
import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SlideBox
import com.therealityteam.aeropages.model.SlideShape
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextBoxBlock
import com.therealityteam.aeropages.model.Workbook
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * A drift audit of every reader and writer: each sample file the app's own writers make (the 60 the
 * device checks use — shapes, tables, charts, lists, notes, sections, pictures …) is read, written
 * again in its format and read back, and the two models must hold the same content. Whatever the
 * reader takes in, the writer must put back — a difference is a feature lost (or changed) on save.
 * Paragraph ids are made afresh on each read and are not compared; lengths may move by rounding.
 */
class RoundTripAuditTest {

    @Test
    fun everySampleSurvivesBeingSavedAgain() {
        val dir = File(System.getProperty("java.io.tmpdir"), "aero-roundtrip-audit").apply { deleteRecursively(); mkdirs() }
        SampleFileMakerTest().writeTo(dir)
        val report = ArrayList<String>()
        val files = dir.listFiles()!!.sortedBy { it.name }
        assertTrue("the samples were made", files.size >= 50)
        for (f in files) {
            val bytes = f.readBytes()
            val diffs = runCatching {
                when (f.extension) {
                    "pptx" -> Differ().also { d -> val a = PptxReader.read(bytes); d.deck(a, PptxReader.read(bytesOf { PptxWriter.write(a, it) })) }.found
                    "odp" -> Differ().also { d -> val a = OdpReader.read(bytes); d.deck(a, OdpReader.read(bytesOf { OdpWriter.write(a, it) })) }.found
                    "xlsx" -> Differ().also { d -> val a = XlsxReader.read(bytes); d.book(a, XlsxReader.read(bytesOf { XlsxWriter.write(a, it) })) }.found
                    "ods" -> Differ().also { d -> val a = OdsReader.read(bytes); d.book(a, OdsReader.read(bytesOf { OdsWriter.write(a, it) })) }.found
                    "docx" -> Differ().also { d -> val a = DocxReader.read(ByteArrayInputStream(bytes)); d.doc(a, DocxReader.read(ByteArrayInputStream(bytesOf { DocxWriter.write(a, it) }))) }.found
                    "odt" -> Differ().also { d -> val a = OdtReader.read(bytes); d.doc(a, OdtReader.read(bytesOf { OdtWriter.write(a, it) })) }.found
                    else -> emptyList()
                }
            }.getOrElse { listOf("failed: $it") }
            diffs.take(8).forEach { report.add("${f.name}: $it") }
            if (diffs.size > 8) report.add("${f.name}: … ${diffs.size - 8} more")
        }
        File(System.getProperty("java.io.tmpdir"), "aero-roundtrip-audit.txt").writeText(report.joinToString("\n"))
        assertTrue("content changed by saving again:\n" + report.joinToString("\n"), report.isEmpty())
    }

    /**
     * The same samples through the other format of their kind and back (docx → ODT → docx, pptx → ODP
     * → pptx, xlsx → ODS → xlsx), the differences written to `aero-crossformat-audit.txt` in the temp
     * folder — what each conversion loses, to work through (`-Daero.crossAudit=1`; some losses are the
     * formats' own: ODF has no Excel tables or slide sections).
     */
    @Test
    fun crossFormatExploration() {
        org.junit.Assume.assumeTrue(System.getProperty("aero.crossAudit") != null)
        val dir = File(System.getProperty("java.io.tmpdir"), "aero-roundtrip-audit2").apply { deleteRecursively(); mkdirs() }
        SampleFileMakerTest().writeTo(dir)
        val report = ArrayList<String>()
        for (f in dir.listFiles()!!.sortedBy { it.name }) {
            val bytes = f.readBytes()
            val diffs = runCatching {
                when (f.extension) {
                    "pptx" -> Differ().also { d -> val a = PptxReader.read(bytes); val odp = OdpReader.read(bytesOf { OdpWriter.write(a, it) }); d.deck(a, PptxReader.read(bytesOf { PptxWriter.write(odp, it) })) }.found
                    "xlsx" -> Differ().also { d -> val a = XlsxReader.read(bytes); val ods = OdsReader.read(bytesOf { OdsWriter.write(a, it) }); d.book(a, XlsxReader.read(bytesOf { XlsxWriter.write(ods, it) })) }.found
                    "docx" -> Differ().also { d -> val a = DocxReader.read(ByteArrayInputStream(bytes)); val odt = OdtReader.read(bytesOf { OdtWriter.write(a, it) }); d.doc(a, DocxReader.read(ByteArrayInputStream(bytesOf { DocxWriter.write(odt, it) }))) }.found
                    else -> emptyList()
                }
            }.getOrElse { listOf("failed: $it") }
            diffs.take(12).forEach { report.add("${f.name}: $it") }
            if (diffs.size > 12) report.add("${f.name}: … ${diffs.size - 12} more")
        }
        File(System.getProperty("java.io.tmpdir"), "aero-crossformat-audit.txt").writeText(report.joinToString("\n"))
    }

    /**
     * The samples through LibreOffice itself (`-Daero.soffice=<its soffice>`): it converts each to
     * the other format of its kind and saves it again in its own (docx → odt and docx, xlsx → ods
     * and xlsx, pptx → odp and pptx, and the OpenDocument samples the other way), and what the app
     * reads from LibreOffice's files is compared with what it reads from the sample —
     * `aero-lo-audit.txt` in the temp folder. Where the app reads its own files back but not
     * LibreOffice's, a reader misses how LibreOffice writes something, or a writer writes what
     * LibreOffice reads otherwise (and some differences are LibreOffice's own: what it drops or cannot hold).
     */
    @Test
    fun libreOfficeExploration() {
        val soffice = System.getProperty("aero.soffice")
        org.junit.Assume.assumeTrue(soffice != null && File(soffice).canExecute())
        val tmp = File(System.getProperty("java.io.tmpdir") ?: ".")
        val dir = File(tmp, "aero-lo-audit").apply { deleteRecursively(); mkdirs() }
        SampleFileMakerTest().writeTo(dir)
        val profile = File(tmp, "aero-lo-profile").apply { mkdirs() }
        val targets = mapOf("xlsx" to listOf("ods", "xlsx"), "docx" to listOf("odt", "docx"), "pptx" to listOf("odp", "pptx"),
            "ods" to listOf("xlsx", "ods"), "odt" to listOf("docx", "odt"), "odp" to listOf("pptx", "odp"))
        for ((ext, outs) in targets) {
            val inputs = dir.listFiles { f -> f.extension == ext }!!.sortedBy { it.name }.map { it.path }
            for (out in outs) {
                // One folder per source type: media.pptx and media.odp both become a media.odp.
                val outDir = File(dir, "lo-$ext-$out").apply { mkdirs() }
                val p = ProcessBuilder(listOf(soffice!!, "-env:UserInstallation=${profile.toURI()}", "--headless", "--convert-to", out, "--outdir", outDir.path) + inputs)
                    .redirectErrorStream(true).redirectOutput(File(dir, "soffice-$ext-$out.log")).start()
                p.waitFor(20, java.util.concurrent.TimeUnit.MINUTES)
            }
        }
        val report = ArrayList<String>()
        for (f in dir.listFiles()!!.filter { it.isFile && it.extension in targets }.sortedBy { it.name }) {
            for (out in targets.getValue(f.extension)) {
                val lo = File(File(dir, "lo-${f.extension}-$out"), f.nameWithoutExtension + "." + out)
                val tag = "${f.name} → LibreOffice → $out"
                if (!lo.exists()) { report.add("$tag: not converted"); continue }
                val diffs = runCatching {
                    val b = lo.readBytes()
                    fun book(ext: String, bytes: ByteArray) = if (ext == "ods") OdsReader.read(bytes) else XlsxReader.read(bytes)
                    fun doc(ext: String, bytes: ByteArray) = if (ext == "odt") OdtReader.read(bytes) else DocxReader.read(ByteArrayInputStream(bytes))
                    fun deck(ext: String, bytes: ByteArray) = if (ext == "odp") OdpReader.read(bytes) else PptxReader.read(bytes)
                    when (f.extension) {
                        "xlsx", "ods" -> Differ().also { d -> d.book(book(f.extension, f.readBytes()), book(out, b)) }.found
                        "docx", "odt" -> Differ().also { d -> d.doc(doc(f.extension, f.readBytes()), doc(out, b)) }.found
                        else -> Differ().also { d -> d.deck(deck(f.extension, f.readBytes()), deck(out, b)) }.found
                    }
                }.getOrElse { listOf("failed: $it") }
                diffs.take(15).forEach { report.add("$tag: $it") }
                if (diffs.size > 15) report.add("$tag: … ${diffs.size - 15} more")
            }
        }
        File(tmp, "aero-lo-audit.txt").writeText(report.joinToString("\n"))
    }

    private fun bytesOf(write: (ByteArrayOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also(write).toByteArray()

    /** Walks two models side by side and notes where they differ. */
    private class Differ {
        val found = ArrayList<String>()

        private fun note(path: String, a: Any?, b: Any?) { found.add("$path: $a → $b") }
        private fun eq(path: String, a: Any?, b: Any?) { if (a != b) note(path, a, b) }
        private fun near(path: String, a: Float?, b: Float?, tol: Float = 0.001f) {
            if (a == null || b == null) { if (a != b) note(path, a, b); return }
            if (abs(a - b) > tol * maxOf(1f, abs(a))) note(path, a, b)
        }
        private fun <T> list(path: String, a: List<T>, b: List<T>, each: (String, T, T) -> Unit) {
            if (a.size != b.size) { note("$path.size", a.size, b.size); return }
            a.indices.forEach { each("$path[$it]", a[it], b[it]) }
        }

        /** A run by its content: an equation's by its structure (the wrapper has no equality of its own). */
        private fun key(r: com.therealityteam.aeropages.model.TextRun) = Triple(r.text, r.format.copy(math = null), r.format.math?.math)

        private fun runs(path: String, a: List<com.therealityteam.aeropages.model.TextRun>, b: List<com.therealityteam.aeropages.model.TextRun>) {
            if (a.map(::key) == b.map(::key)) return
            if (a.joinToString("") { it.text } != b.joinToString("") { it.text }) note("$path.text", a.joinToString("") { it.text }, b.joinToString("") { it.text })
            else list("$path.runs", a, b) { p, x, y -> if (key(x) != key(y)) note(p, x, y) }
        }

        // --- Pages ---
        fun para(path: String, a: Paragraph?, b: Paragraph?) {
            if (a == null || b == null) { if ((a == null) != (b == null)) note(path, a?.plainText(), b?.plainText()); return }
            // Lists by what they show (labels, below), not by their ids: each reader numbers its lists in reading order.
            eq("$path.format", a.format.copy(listId = 0), b.format.copy(listId = 0))
            runs(path, a.runs, b.runs)
            // The bookmarks it had, where they were (a writer or LibreOffice may add hidden ones, `_Toc…` round headings;
            // marks at one place may come in another order).
            val isComment = { m: com.therealityteam.aeropages.model.BookmarkMark -> com.therealityteam.aeropages.model.Bookmarks.isComment(m.name) }
            val names = a.bookmarks.filterNot(isComment).mapTo(HashSet()) { it.name }
            val order = compareBy<com.therealityteam.aeropages.model.BookmarkMark>({ it.offset }, { it.end }, { it.name })
            eq("$path.bookmarks", a.bookmarks.filterNot(isComment).sortedWith(order), b.bookmarks.filter { it.name in names }.sortedWith(order))
            // Where comments' ranges start and end (their marks name the comments, numbered afresh by each reader).
            eq("$path.commentRanges", a.bookmarks.filter(isComment).map { it.offset to it.end }.sortedWith(compareBy({ it.first }, { it.second })),
                b.bookmarks.filter(isComment).map { it.offset to it.end }.sortedWith(compareBy({ it.first }, { it.second })))
        }

        fun doc(a: Document, b: Document) {
            // Every list item's label ("1.2", "（三）", "•"), in reading order — first, so a long report does not cut it off.
            fun labels(d: Document) = com.therealityteam.aeropages.model.PageMetrics.listLabels(d).let { m -> d.locations().mapNotNull { m[it.paragraph]?.let { l -> "«$l»" } } }
            eq("listLabels", labels(a), labels(b))
            eq("page", a.page, b.page)
            para("header", a.header, b.header); para("footer", a.footer, b.footer)
            para("evenHeader", a.evenHeader, b.evenHeader); para("evenFooter", a.evenFooter, b.evenFooter)
            para("firstHeader", a.firstHeader, b.firstHeader); para("firstFooter", a.firstFooter, b.firstFooter)
            eq("differentOddEven", a.differentOddEven, b.differentOddEven); eq("differentFirstPage", a.differentFirstPage, b.differentFirstPage)
            // Word draws a header's objects under the body's text whatever their wrapping (LibreOffice reads "in front" there as behind).
            fun underText(img: ImageBlock) = (img.copyOf() as ImageBlock).also { c ->
                c.floating?.let { if (it.wrap == com.therealityteam.aeropages.model.FloatWrap.FRONT) it.wrap = com.therealityteam.aeropages.model.FloatWrap.BEHIND }
            }
            com.therealityteam.aeropages.model.HfSlot.entries.forEach { slot ->
                list("hfPictures.$slot", a.hfPictures[slot].orEmpty(), b.hfPictures[slot].orEmpty()) { p, x, y -> block(p, underText(x), underText(y)) }
            }
            eq("pageColor", a.pageColor, b.pageColor); eq("watermark", a.watermark, b.watermark); eq("pageBorders", a.pageBorders, b.pageBorders)
            if (!a.styles.sameAs(b.styles)) note("styles", "…", "differ")
            list("comments", a.comments, b.comments) { p, x, y -> eq("$p.text", x.text, y.text); eq("$p.author", x.author, y.author); eq("$p.resolved", x.resolved, y.resolved) }
            // File → Info's properties (who saved it last and when are the writer's own).
            eq("props", a.props.let { it.title to listOf(it.subject, it.author, it.keywords, it.description) }, b.props.let { it.title to listOf(it.subject, it.author, it.keywords, it.description) })
            list("footnotes", a.footnotes, b.footnotes) { p, x, y -> eq(p, x.text, y.text) }
            list("endnotes", a.endnotes, b.endnotes) { p, x, y -> eq(p, x.text, y.text) }
            list("sources", a.sources, b.sources) { p, x, y -> eq(p, "${x.author}|${x.title}|${x.year}", "${y.author}|${y.title}|${y.year}") }
            list("blocks", a.blocks, b.blocks) { p, x, y -> block(p, x, y) }
        }

        private fun kind(b: Block) = b::class.simpleName

        /** Where a floating object stands: its wrapping, frames and alignments, its offsets where it has no alignment. */
        private fun floating(path: String, a: com.therealityteam.aeropages.model.FloatPosition?, b: com.therealityteam.aeropages.model.FloatPosition?) {
            if (a == null || b == null) { if ((a == null) != (b == null)) note("$path.floating", a, b); return }
            eq("$path.floating.wrap", a.wrap, b.wrap)
            eq("$path.floating.hFrom", a.hFrom, b.hFrom); eq("$path.floating.hAlign", a.hAlign, b.hAlign)
            eq("$path.floating.vFrom", a.vFrom, b.vFrom); eq("$path.floating.vAlign", a.vAlign, b.vAlign)
            eq("$path.floating.line", a.line, b.line)
            if (a.hAlign == null) near("$path.floating.x", a.x, b.x, 0.1f)
            if (a.vAlign == null) near("$path.floating.y", a.y, b.y, 0.1f)
        }

        private fun block(path: String, a: Block, b: Block) {
            if (kind(a) != kind(b)) { note("$path.kind", kind(a), kind(b)); return }
            when (a) {
                is ParagraphBlock -> para(path, a.paragraph, (b as ParagraphBlock).paragraph)
                is TableBlock -> {
                    val t = (b as TableBlock).table
                    eq("$path.borders", a.table.borders, t.borders)
                    if (a.table.borders && t.borders) eq("$path.gridLine", a.table.gridLine, t.gridLine)
                    floating(path, a.table.floating, t.floating)
                    if (a.table.floating != null) near("$path.widthPt", a.table.widthPt, t.widthPt, 0.1f)
                    list("$path.columnWidths", a.table.columnWidths, t.columnWidths) { p, x, y -> near(p, x, y) }
                    list("$path.rows", a.table.rows, t.rows) { p, x, y ->
                        eq("$p.header", x.header, y.header)
                        list("$p.cells", x.cells, y.cells) { q, c, d ->
                            eq("$q.shade", c.shade, d.shade); eq("$q.gridSpan", c.gridSpan, d.gridSpan); eq("$q.rowSpan", c.rowSpan, d.rowSpan); eq("$q.vMerged", c.vMerged, d.vMerged)
                            if (!c.vMerged) eq("$q.drawn", a.table.bordersOf(c), t.bordersOf(d))
                            list("$q.content", c.content, d.content) { r, u, v -> para(r, u, v) }
                            list("$q.images", c.images, d.images) { r, u, v -> block(r, u, v) }
                        }
                    }
                }
                is ImageBlock -> {
                    b as ImageBlock
                    eq("$path.mime", a.mime, b.mime); near("$path.width", a.widthPt, b.widthPt, 0.01f); near("$path.height", a.heightPt, b.heightPt, 0.01f)
                    near("$path.crop", a.cropL + a.cropT * 10 + a.cropR * 100 + a.cropB * 1000, b.cropL + b.cropT * 10 + b.cropR * 100 + b.cropB * 1000, 0.01f)
                    if (!a.data.contentEquals(b.data)) note("$path.data", a.data.size, b.data.size)
                    floating(path, a.floating, b.floating)
                    eq("$path.flipH", a.flipH, b.flipH); eq("$path.flipV", a.flipV, b.flipV)
                }
                is TextBoxBlock -> {
                    b as TextBoxBlock
                    floating(path, a.floating, b.floating)
                    near("$path.width", a.widthPt, b.widthPt, 0.01f); near("$path.height", a.heightPt, b.heightPt, 0.01f)
                    list("$path.paragraphs", a.paragraphs, b.paragraphs) { p, x, y -> para(p, x, y) }
                    val s = a.shape; val t = b.shape
                    if (s == null || t == null) { if ((s == null) != (t == null)) note("$path.shape", s?.type, t?.type) } else shape("$path.shape", s, t, frame = false)
                }
                is com.therealityteam.aeropages.model.ChartBlock -> {
                    b as com.therealityteam.aeropages.model.ChartBlock
                    near("$path.width", a.widthPt, b.widthPt, 0.01f); near("$path.height", a.heightPt, b.heightPt, 0.01f)
                    chart(path, a.chart, b.chart, frame = false)
                }
                is com.therealityteam.aeropages.model.EquationBlock -> {
                    b as com.therealityteam.aeropages.model.EquationBlock
                    eq("$path.math", a.math, b.math); eq("$path.align", a.align, b.align); near("$path.size", a.sizePt, b.sizePt)
                }
                else -> Unit
            }
        }

        // --- Slides ---
        fun deck(a: Presentation, b: Presentation) {
            // A link to a slide names it by an id each reading gives anew: compared by where that slide stands.
            fun byPlace(p: Presentation) = com.therealityteam.aeropages.model.SlideLinks.remap(p) { l ->
                com.therealityteam.aeropages.model.SlideLinks.indexIn(p, l)?.let { "#slide@$it" } ?: l
            }
            byPlace(a); byPlace(b)
            eq("slideSize", a.slideSize, b.slideSize); eq("theme", a.themeIndex, b.themeIndex); eq("loopShow", a.loopShow, b.loopShow)
            eq("useTimings", a.useTimings, b.useTimings); eq("footer", a.footer, b.footer); eq("showSlideNumber", a.showSlideNumber, b.showSlideNumber)
            if (!a.master.sameAs(b.master)) {
                val m = a.master; val n = b.master
                eq("master.background", m.background, n.background)
                eq("master.backgroundShade", shade(m.backgroundShade), shade(n.backgroundShade))
                eq("master.title", listOf(m.titleFont, m.titleSizePt, m.titleColor, m.titleBold), listOf(n.titleFont, n.titleSizePt, n.titleColor, n.titleBold))
                eq("master.body", listOf(m.bodyFont, m.bodySizePt, m.bodyColor), listOf(n.bodyFont, n.bodySizePt, n.bodyColor))
                eq("master.chrome", m.chrome, n.chrome)
            }
eq("props", a.props.let { it.title to listOf(it.subject, it.author, it.keywords, it.description) }, b.props.let { it.title to listOf(it.subject, it.author, it.keywords, it.description) })
                        list("slides", a.slides, b.slides) { p, x, y ->
                box("$p.title", x.title, y.title); box("$p.body", x.body, y.body)
                eq("$p.background", x.background, y.background); eq("$p.layout", x.layout, y.layout); eq("$p.notes", x.notes, y.notes)
                eq("$p.backgroundShade", shade(x.backgroundShade), shade(y.backgroundShade))
                eq("$p.transition", x.transition, y.transition); eq("$p.speed", x.transitionSpeed, y.transitionSpeed)
                eq("$p.section", x.sectionName, y.sectionName); eq("$p.hidden", x.hidden, y.hidden); eq("$p.advanceMs", x.advanceMs, y.advanceMs)
                eq("$p.comments", x.comments, y.comments)
                list("$p.images", x.images, y.images) { q, u, v ->
                    eq("$q.mime", u.mime, v.mime); frame(q, u.xPct, u.yPct, u.wPct, u.hPct, v.xPct, v.yPct, v.wPct, v.hPct)
                    eq("$q.action", u.action, v.action)
                    // A video's or sound's: its kind, type, name and bytes (or link).
                    eq("$q.media", u.media?.let { listOf(it.kind, it.mime, it.name, it.data?.contentHashCode(), it.link) }, v.media?.let { listOf(it.kind, it.mime, it.name, it.data?.contentHashCode(), it.link) })
                    near("$q.crop", u.cropL + u.cropT * 10 + u.cropR * 100 + u.cropB * 1000, v.cropL + v.cropT * 10 + v.cropR * 100 + v.cropB * 1000, 0.01f)
                    anims(q, u, v)
                }
                list("$p.shapes", x.shapes, y.shapes) { q, u, v -> shape(q, u, v, frame = true) }
                list("$p.tables", x.tables, y.tables) { q, u, v ->
                    eq("$q.style", listOf(u.styleIndex, u.headerRow, u.bandedRows, u.firstColumn, u.lastColumn, u.totalRow, u.bandedColumns),
                        listOf(v.styleIndex, v.headerRow, v.bandedRows, v.firstColumn, v.lastColumn, v.totalRow, v.bandedColumns))
                    frame(q, u.xPct, u.yPct, u.wPct, u.hPct, v.xPct, v.yPct, v.wPct, v.hPct)
                    list("$q.cells", u.cells, v.cells) { r, c, d -> list(r, c, d) { s, e, f -> box(s, e, f); eq("$s.shading", e.fill?.let(java.lang.Long::toHexString), f.fill?.let(java.lang.Long::toHexString)); eq("$s.anchor", e.cellAnchor, f.cellAnchor); eq("$s.span", e.colSpan to e.rowSpan, f.colSpan to f.rowSpan) } }
                    // Its columns' widths and rows' heights, to half a percent of the table.
                    eq("$q.colShares", (0 until u.cols).map { Math.round(u.colShare(it) * 200) }, (0 until v.cols).map { Math.round(v.colShare(it) * 200) })
                    eq("$q.rowShares", (0 until u.rows).map { Math.round(u.rowShare(it) * 200) }, (0 until v.rows).map { Math.round(v.rowShare(it) * 200) })
                    // Each edge as it draws (a cell's own borders, its neighbour's or the style's grid).
                    val look = com.therealityteam.aeropages.model.SlideTableLook
                    for (r in 0 until minOf(u.rows, v.rows)) for (c in 0 until minOf(u.cols, v.cols)) eq("$q.lines[$r][$c]", look.sides(u, r, c), look.sides(v, r, c))
                    anims(q, u, v)
                }
                list("$p.charts", x.charts, y.charts) { q, u, v -> chart(q, u, v, frame = true) }
            }
        }

        /** A background's gradient as compared: its second colour, kind, and its angle and centre to a degree and a percent. */
        private fun shade(s: com.therealityteam.aeropages.model.BackgroundShade?) =
            s?.let { listOf(java.lang.Long.toHexString(it.second), it.kind, Math.round(it.angleDeg), Math.round(it.cx * 100), Math.round(it.cy * 100)) }

        private fun anims(path: String, a: com.therealityteam.aeropages.model.AnimTarget, b: com.therealityteam.aeropages.model.AnimTarget) =
            list("$path.anims", a.anims, b.anims) { p, x, y -> if (!x.sameAs(y)) note(p, x, y) }

        private fun frame(path: String, x: Float, y: Float, w: Float, h: Float, x2: Float, y2: Float, w2: Float, h2: Float) {
            near("$path.x", x, x2, 0.0005f); near("$path.y", y, y2, 0.0005f); near("$path.w", w, w2, 0.0005f); near("$path.h", h, h2, 0.0005f)
        }

        private fun box(path: String, a: SlideBox, b: SlideBox) {
            eq("$path.listStyle", a.listStyle, b.listStyle); near("$path.lineSpacing", a.lineSpacing, b.lineSpacing)
            val f = a.frame; val g = b.frame
            if (f == null || g == null) { if ((f == null) != (g == null)) note("$path.frame", f, g) } else frame("$path.frame", f.xPct, f.yPct, f.wPct, f.hPct, g.xPct, g.yPct, g.wPct, g.hPct)
            eq("$path.align", a.paragraph.format, b.paragraph.format)
            runs(path, a.paragraph.runs, b.paragraph.runs)
            anims(path, a, b)
        }

        fun shape(path: String, a: SlideShape, b: SlideShape, frame: Boolean) {
            eq("$path.type", a.type, b.type); eq("$path.fill", a.fill, b.fill); eq("$path.border", a.border, b.border)
            near("$path.borderWidth", a.borderWidthPt, b.borderWidthPt, 0.01f); eq("$path.anchor", a.anchor, b.anchor)
            near("$path.rotation", a.rotationDeg, b.rotationDeg, 0.001f); eq("$path.flips", a.flipH to a.flipV, b.flipH to b.flipV)
            eq("$path.gradient", a.fillGradient, b.fillGradient); if (a.fillGradient != null) near("$path.angle", a.gradientAngle, b.gradientAngle, 0.01f)
            eq("$path.dash", a.dash, b.dash); eq("$path.beginArrow", a.beginArrow, b.beginArrow); eq("$path.endArrow", a.endArrow, b.endArrow)
            outline("$path.path", a.path, b.path)
            eq("$path.action", a.action, b.action); eq("$path.noWrap", a.noWrap, b.noWrap)
            if (frame) {
                this.frame(path, a.xPct, a.yPct, a.wPct, a.hPct, b.xPct, b.yPct, b.wPct, b.hPct)
                eq("$path.group", a.groupId != null, b.groupId != null)
                box("$path.box", a.box, b.box)
            }
        }

        /** Two freeform outlines: their parts, how each is filled and outlined, and every point where its stretches end. */
        fun outline(path: String, a: com.therealityteam.aeropages.model.Freeform?, b: com.therealityteam.aeropages.model.Freeform?) {
            if (a == null || b == null) { eq("$path.present", a != null, b != null); return }
            eq("$path.parts", a.parts.size, b.parts.size)
            a.parts.zip(b.parts).forEachIndexed { i, (x, y) ->
                eq("$path[$i].look", x.filled to x.stroked, y.filled to y.stroked)
                fun ends(p: com.therealityteam.aeropages.model.Freeform.Part) = p.ops.mapNotNull { op ->
                    when (op) {
                        is com.therealityteam.aeropages.model.Freeform.Move -> op.x to op.y
                        is com.therealityteam.aeropages.model.Freeform.Line -> op.x to op.y
                        is com.therealityteam.aeropages.model.Freeform.Cubic -> op.x to op.y
                        else -> null
                    }
                }
                val ex = ends(x); val ey = ends(y)
                if (ex.size != ey.size) { note("$path[$i].points", ex.size, ey.size); return@forEachIndexed }
                ex.zip(ey).forEachIndexed { k, (p, q) ->
                    if (kotlin.math.abs(p.first - q.first) > 0.005f || kotlin.math.abs(p.second - q.second) > 0.005f) { note("$path[$i].point$k", p, q); return@forEachIndexed }
                }
            }
        }

        fun chart(path: String, a: com.therealityteam.aeropages.model.SlideChart, b: com.therealityteam.aeropages.model.SlideChart, frame: Boolean) {
            eq("$path.type", a.type, b.type); eq("$path.title", a.title, b.title); eq("$path.colorSet", a.colorSet, b.colorSet)
            eq("$path.legend", listOf(a.showLegend, a.legendPos), listOf(b.showLegend, b.legendPos))
            eq("$path.gridValues", listOf(a.showGrid, a.showValues), listOf(b.showGrid, b.showValues))
            eq("$path.axisTitles", listOf(a.catAxisTitle, a.valAxisTitle), listOf(b.catAxisTitle, b.valAxisTitle))
            eq("$path.scale", listOf(a.valMin, a.valMax, a.valMajor, a.valFormat), listOf(b.valMin, b.valMax, b.valMajor, b.valFormat))
            eq("$path.source", listOf(a.sourceRef, a.sourceHeader, a.sourceCats), listOf(b.sourceRef, b.sourceHeader, b.sourceCats))
            eq("$path.categories", a.categories, b.categories)
            list("$path.series", a.series, b.series) { p, x, y -> eq("$p.name", x.name, y.name); eq("$p.values", x.values, y.values) }
            if (frame) this.frame(path, a.xPct, a.yPct, a.wPct, a.hPct, b.xPct, b.yPct, b.wPct, b.hPct)
            anims(path, a, b)
        }

        // --- Sheets ---
        fun book(a: Workbook, b: Workbook) {
            eq("names", a.names, b.names)
eq("props", a.props.let { it.title to listOf(it.subject, it.author, it.keywords, it.description) }, b.props.let { it.title to listOf(it.subject, it.author, it.keywords, it.description) })
                        list("sheets", a.sheets, b.sheets) { p, x, y -> sheet(p, x, y) }
        }

        private fun sheet(path: String, a: Sheet, b: Sheet) {
            eq("$path.name", a.name, b.name)
            eq("$path.state", a.state, b.state); eq("$path.tabColor", a.tabColor, b.tabColor)
            eq("$path.freeze", a.freezeRows to a.freezeCols, b.freezeRows to b.freezeCols)
            eq("$path.print", listOf(a.landscape, a.paper, a.showHeadings), listOf(b.landscape, b.paper, b.showHeadings))
            eq("$path.hiddenRows", a.hiddenRows, b.hiddenRows); eq("$path.hiddenCols", a.hiddenCols, b.hiddenCols)
            eq("$path.manualHiddenRows", a.manualHiddenRows, b.manualHiddenRows); eq("$path.protected", a.protected, b.protected)
            eq("$path.printArea", a.printArea?.let { listOf(it.r1, it.c1, it.r2, it.c2) }, b.printArea?.let { listOf(it.r1, it.c1, it.r2, it.c2) })
            (a.colWidths.keys + b.colWidths.keys).sorted().forEach { c -> near("$path.colWidth[$c]", a.colWidths[c], b.colWidths[c], 0.01f) }
            (a.rowHeights.keys + b.rowHeights.keys).sorted().forEach { r -> near("$path.rowHeight[$r]", a.rowHeights[r], b.rowHeights[r], 0.01f) }
            list("$path.merges", a.merges, b.merges) { p, x, y -> eq(p, listOf(x.r1, x.c1, x.r2, x.c2), listOf(y.r1, y.c1, y.r2, y.c2)) }
            list("$path.condFormats", a.condFormats, b.condFormats) { p, x, y -> eq(p, x, y) }
            eq("$path.validations", a.validations, b.validations)
            list("$path.charts", a.charts, b.charts) { p, x, y -> chart(p, x, y, frame = false) }
            list("$path.rowGroups", a.rowGroups, b.rowGroups) { p, x, y -> eq(p, listOf(x.r1, x.r2, x.collapsed), listOf(y.r1, y.r2, y.collapsed)) }
            list("$path.colGroups", a.colGroups, b.colGroups) { p, x, y -> eq(p, listOf(x.r1, x.r2, x.collapsed), listOf(y.r1, y.r2, y.collapsed)) }
            eq("$path.autoFilter", a.autoFilter, b.autoFilter)
            list("$path.sparklines", a.sparklines, b.sparklines) { p, x, y ->
                eq(p, listOf(x.row, x.col, x.r1, x.c1, x.r2, x.c2, x.type, x.color, x.markers), listOf(y.row, y.col, y.r1, y.c1, y.r2, y.c2, y.type, y.color, y.markers))
            }
            list("$path.images", a.images, b.images) { p, x, y -> if (!x.sameAs(y)) note(p, "${x.name} ${x.row},${x.col} ${x.widthPt}×${x.heightPt}", "${y.name} ${y.row},${y.col} ${y.widthPt}×${y.heightPt}") }
            list("$path.tables", a.tables, b.tables) { p, x, y -> if (!x.sameAs(y)) note(p, x.name, y.name) }
            list("$path.shapes", a.shapes, b.shapes) { p, x, y ->
                eq("$p.anchor", listOf(x.row, x.col, x.name), listOf(y.row, y.col, y.name))
                near("$p.dx", x.dxPt, y.dxPt, 0.01f); near("$p.dy", x.dyPt, y.dyPt, 0.01f); near("$p.w", x.widthPt, y.widthPt, 0.01f); near("$p.h", x.heightPt, y.heightPt, 0.01f)
                shape(p, x.shape, y.shape, frame = false); box("$p.box", x.shape.box, y.shape.box)
            }
            (a.cells.keys + b.cells.keys).sorted().forEach { k ->
                val x = a.cells[k]; val y = b.cells[k]
                val at = "$path.${Workbook.colLabel((k % 1000).toInt())}${k / 1000 + 1}"
                if (x == null || y == null) {
                    // An empty, plain cell may come and go.
                    val c = x ?: y!!
                    if (c.text.isNotEmpty() || c.comment != null) note(at, x?.text, y?.text)
                    return@forEach
                }
                eq("$at.text", x.text, y.text); eq("$at.format", x.format, y.format); eq("$at.align", x.align, y.align)
                eq("$at.numberFormat", x.numberFormat to x.decimals, y.numberFormat to y.decimals); eq("$at.formatCode", x.formatCode, y.formatCode)
                eq("$at.borders", listOf(x.borderTop, x.borderBottom, x.borderLeft, x.borderRight), listOf(y.borderTop, y.borderBottom, y.borderLeft, y.borderRight))
                eq("$at.wrap", x.wrap to x.vAlign, y.wrap to y.vAlign); eq("$at.comment", x.comment, y.comment)
                eq("$at.link", x.link, y.link); eq("$at.unlocked", x.unlocked, y.unlocked); eq("$at.runs", x.runs, y.runs)
            }
        }
    }
}
