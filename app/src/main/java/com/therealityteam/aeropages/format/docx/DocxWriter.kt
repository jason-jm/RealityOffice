package com.therealityteam.aeropages.format.docx

import com.therealityteam.aeropages.model.Document
import com.therealityteam.aeropages.model.ImageBlock
import com.therealityteam.aeropages.model.ListKind
import com.therealityteam.aeropages.model.PageSetup
import com.therealityteam.aeropages.model.Paragraph
import com.therealityteam.aeropages.model.ParagraphBlock
import com.therealityteam.aeropages.model.ParagraphStyle
import com.therealityteam.aeropages.model.StyleDef
import com.therealityteam.aeropages.model.StyleSheet
import com.therealityteam.aeropages.model.TableBlock
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.TextRun
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [Document] back out as a valid, minimal .docx (OOXML) package.
 *
 * We emit the required package parts ([Content_Types].xml, the relationship
 * files, document.xml, styles.xml, numbering.xml) so Word and other OOXML
 * consumers open the result without repair prompts.
 */
object DocxWriter {

    /** An image to embed, with its assigned relationship id and media filename. */
    /** A picture's parts: what its blip embeds ([rId], [fileName]) and, for an SVG, the SVG itself beside it. */
    private class ImageRef(val block: ImageBlock, val rId: String, val fileName: String, val pic: com.therealityteam.aeropages.format.pptx.OoxmlPicture) {
        val svgRId: String? get() = pic.svg?.let { "${rId}Svg" }
        val svgFile: String get() = fileName.substringBeforeLast('.') + ".svg"
        val blip: String get() = com.therealityteam.aeropages.format.pptx.OoxmlPicture.blip(rId, svgRId)
    }

    /** A header or footer part: `default` / `even` / `first` (OOXML headerReference types). */
    /** A header or footer part: its type (default, even, first), its file, its text (none: an empty line) and its pictures. */
    private class HfPart(val type: String, val file: String, val paragraph: Paragraph?, val rId: String,
                         val pictures: List<ImageBlock> = emptyList())

    private const val REV_DATE = "2026-01-01T00:00:00Z"
    private const val W14_NS = "http://schemas.microsoft.com/office/word/2010/wordml"
    private const val W15_NS = "http://schemas.microsoft.com/office/word/2012/wordml"

    /** Serialise [doc]. Safe to call concurrently (autosave + manual save): all per-write state lives in a [Pass]. */
    fun write(doc: Document, output: OutputStream) = Pass(doc).write(output)

    /** One serialisation pass; every per-document lookup table lives here rather than on the object. */
    private class Pass(private val doc: Document) {
        private var linkIds: Map<String, String> = emptyMap()
        private var commentRefs: Map<Long, List<Int>> = emptyMap()
        /** Each comment's place in comments.xml by its id; the threads with a range (their references go where it ends); each thread's replies. */
        private var commentIndex: Map<Long, Int> = emptyMap()
        private var commentRanged: Set<Long> = emptySet()
        private var commentReplies: Map<Long, List<Int>> = emptyMap()
        private var revId = 0
        private var footnoteCount = 0
        private var endnoteCount = 0
        private var imageRefs: List<ImageRef> = emptyList()
        private var imgOrdinal = 0
        private var chartOrdinal = 0
        private var boxOrdinal = 0
        private var styleSheet: StyleSheet = StyleSheet.defaults()
        /** Bookmark ids by name as they are written (Word pairs a start with its end by id), and the names already ended. */
        private val bookmarkIds = HashMap<String, Int>()
        private val bookmarksEnded = HashSet<String>()
        /** The names whose end follows their start (the others are points: their end goes right after the start). */
        private var bookmarksClosed: Set<String> = emptySet()
        /** The hidden bookmark round each heading the table of contents lists (by paragraph id): one it has, or a new `_Toc` one. */
        private var tocMarks: Map<Long, Pair<String, Boolean>> = emptyMap()
        /** Resolved style of the paragraph being written: runs write explicit "off" toggles where it is "on". */
        private var currentStyle: com.therealityteam.aeropages.model.ResolvedStyle? = null
        /** Each list item's numId, and numbering.xml (see WordLists.Plan). */
        private val listPlan = WordLists.Plan(doc)

        fun write(output: OutputStream) {
            // Relationship ids: rId1=styles, rId2=numbering, then header/footer/comments, links, images.
            var next = 3
            // Header / footer variants: header1 = default, header2 = even pages, header3 = first page (same for footers).
            // A part for each one with text or pictures (a letterhead's logo alone makes one).
            fun part(type: String, file: String, text: Paragraph?, slot: com.therealityteam.aeropages.model.HfSlot, shown: Boolean = true): HfPart? {
                val pics = doc.hfPictures[slot].orEmpty()
                if (!shown || (text == null && pics.isEmpty())) return null
                return HfPart(type, file, text, "rId${next++}", pics)
            }
            val hdrParts = listOfNotNull(
                part("default", "header1.xml", doc.header, com.therealityteam.aeropages.model.HfSlot.HEADER),
                part("even", "header2.xml", doc.evenHeader, com.therealityteam.aeropages.model.HfSlot.EVEN_HEADER, doc.differentOddEven),
                part("first", "header3.xml", doc.firstHeader, com.therealityteam.aeropages.model.HfSlot.FIRST_HEADER, doc.differentFirstPage),
            )
            val ftrParts = listOfNotNull(
                part("default", "footer1.xml", doc.footer, com.therealityteam.aeropages.model.HfSlot.FOOTER),
                part("even", "footer2.xml", doc.evenFooter, com.therealityteam.aeropages.model.HfSlot.EVEN_FOOTER, doc.differentOddEven),
                part("first", "footer3.xml", doc.firstFooter, com.therealityteam.aeropages.model.HfSlot.FIRST_FOOTER, doc.differentFirstPage),
            )
            val settingsId = "rId${next++}"   // settings.xml: the default tab stops (other readers assume their own), odd & even headers
            val commentsId = if (doc.comments.isNotEmpty()) "rId${next++}" else null
            val commentsExId = if (doc.comments.any { it.parentId != null || it.resolved }) "rId${next++}" else null
            val footnotesId = if (doc.footnotes.isNotEmpty()) "rId${next++}" else null
            val endnotesId = if (doc.endnotes.isNotEmpty()) "rId${next++}" else null
            val links = collectLinks(doc, next); next += links.size
            val images = collectImages(doc, next); next += images.size
            // Charts: one part each (word/charts/chartN.xml), shown by an inline drawing.
            val charts = doc.blocks.filterIsInstance<com.therealityteam.aeropages.model.ChartBlock>()
                .mapIndexed { i, block -> ChartRef(block, "rId${next + i}", "chart${i + 1}.xml") }
            chartRefs = charts

            linkIds = links
            footnoteCount = doc.footnotes.size
            endnoteCount = doc.endnotes.size
            styleSheet = doc.styles
            commentRefs = doc.comments.indices.groupBy { doc.comments[it].targetId }
            commentIndex = doc.comments.withIndex().associate { (i, c) -> c.id to i }
            commentRanged = com.therealityteam.aeropages.model.Bookmarks.commentRanges(doc).keys.filterTo(HashSet()) { it in commentIndex }
            commentReplies = doc.comments.withIndex().filter { it.value.parentId != null }.groupBy({ it.value.parentId!! }, { it.index })

            ZipOutputStream(output).use { zip ->
                val hasCustom = doc.watermark != null || doc.sources.isNotEmpty()
                zip.put("[Content_Types].xml", com.therealityteam.aeropages.format.OoxmlCore.types(buildContentTypes(hdrParts.map { it.file }, ftrParts.map { it.file }, commentsId != null, hasCustom, footnotesId != null, commentsExId != null, hasSettings = true, hasEndnotes = endnotesId != null, chartFiles = charts.map { it.fileName })))
                zip.put("_rels/.rels", com.therealityteam.aeropages.format.OoxmlCore.rels(if (hasCustom) ROOT_RELS_WITH_CUSTOM else ROOT_RELS))
                zip.putBytes(com.therealityteam.aeropages.format.OoxmlCore.PART, com.therealityteam.aeropages.format.OoxmlCore.write(doc.props))
                if (hasCustom) zip.put("docProps/custom.xml", buildCustomProps(doc.watermark, doc.sources))
                zip.put("word/_rels/document.xml.rels", buildDocRels(images, hdrParts, ftrParts, commentsId, footnotesId, links, commentsExId, settingsId, endnotesId, charts))
                zip.put("word/styles.xml", buildStyles(doc))
                zip.put("word/theme/theme1.xml", com.therealityteam.aeropages.format.pptx.PptxWriter.THEME)
                zip.put("word/numbering.xml", listPlan.xml())
                (hdrParts.map { "hdr" to it } + ftrParts.map { "ftr" to it }).forEachIndexed { partIndex, (root, part) ->
                    // Its pictures: media of their own, named in the part's relationships.
                    val pics = part.pictures.mapIndexed { k, img ->
                        val pic = com.therealityteam.aeropages.format.pptx.OoxmlPicture.of(img.data, img.mime)
                        val name = "${part.file.removeSuffix(".xml")}_image${k + 1}.${pic.mainExt}"
                        zip.putBytes("word/media/$name", pic.main)
                        Triple(img, "rId${k + 1}", name)
                    }
                    if (pics.isNotEmpty()) zip.put("word/_rels/${part.file}.rels", buildString {
                        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                        pics.forEach { (_, id, name) -> append("""<Relationship Id="$id" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/$name"/>""") }
                        append("</Relationships>")
                    })
                    zip.put("word/${part.file}", buildHdrFtr(root, part.paragraph, doc.page, pics.map { (img, id, _) -> img to id }, 5000 + partIndex * 100))
                }
                zip.put("word/settings.xml", settingsXml(doc))
                if (commentsId != null) zip.put("word/comments.xml", buildComments(doc))
                if (commentsExId != null) zip.put("word/commentsExtended.xml", buildCommentsExtended(doc))
                if (footnotesId != null) zip.put("word/footnotes.xml", buildFootnotes(doc))
                if (endnotesId != null) zip.put("word/endnotes.xml", buildEndnotes(doc))
                zip.put("word/document.xml", buildDocumentXml(doc, images, hdrParts, ftrParts))
                images.forEach { img ->
                    zip.putBytes("word/media/${img.fileName}", img.pic.main)
                    img.pic.svg?.let { zip.putBytes("word/media/${img.svgFile}", it) }
                }
                charts.forEach { c -> zip.put("word/charts/${c.fileName}", com.therealityteam.aeropages.format.pptx.PptxWriter.chartXml(c.block.chart)) }
            }
        }

        /** A chart block's relationship id and part name. */
        private class ChartRef(val block: com.therealityteam.aeropages.model.ChartBlock, val rId: String, val fileName: String)
        private var chartRefs: List<ChartRef> = emptyList()

        /**
         * A chart: a centred paragraph holding an inline drawing of it, [ordinal] numbering its
         * drawing id past the pictures'.
         */
        private fun StringBuilder.appendChart(block: com.therealityteam.aeropages.model.ChartBlock, ref: ChartRef, ordinal: Int) {
            val cx = (block.widthPt * 12700).toLong(); val cy = (block.heightPt * 12700).toLong()
            val id = 1000 + ordinal
            append("""<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0">""")
            append("""<wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$id" name="Chart $ordinal"/>""")
            append("""<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart">""")
            append("""<c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="${ref.rId}"/></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""")
        }

        /** Distinct hyperlink URLs across body, tables, header and footer, mapped to rIds. */
        private fun collectLinks(doc: Document, startId: Int): Map<String, String> {
            val urls = LinkedHashSet<String>()
            // Links within the document go to a bookmark (w:anchor), not to a relationship.
            fun scan(p: Paragraph) = p.runs.forEach { it.format.link?.takeIf { u -> com.therealityteam.aeropages.model.Bookmarks.targetOf(u) == null }?.let { u -> urls.add(u) } }
            doc.paragraphs().forEach(::scan)
            doc.header?.let(::scan); doc.footer?.let(::scan)
            return urls.mapIndexed { i, u -> u to "rId${startId + i}" }.toMap()
        }

        /** 8-hex `w14:paraId` of a comment's paragraph — what commentsExtended threads replies by. */
        private fun paraIdOf(c: com.therealityteam.aeropages.model.Comment): String = String.format(Locale.ROOT, "%08X", (c.id and 0x7FFFFFFFL).toInt())

        private fun buildComments(doc: Document): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
            append("""<w:comments xmlns:w="${Ooxml.W}" xmlns:w14="$W14_NS">""")
            doc.comments.forEachIndexed { i, c ->
                append("""<w:comment w:id="$i" w:author="${Ooxml.escape(c.author)}" w:date="${Ooxml.isoDate(c.dateMs)}" w:initials="">""")
                append("""<w:p w14:paraId="${paraIdOf(c)}"><w:r><w:t xml:space="preserve">${Ooxml.escape(c.text)}</w:t></w:r></w:p>""")
                append("</w:comment>")
            }
            append("</w:comments>")
        }

        /** word/commentsExtended.xml: reply threading (paraIdParent) and the resolved state (done). */
        private fun buildCommentsExtended(doc: Document): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
            append("""<w15:commentsEx xmlns:w15="$W15_NS">""")
            val byId = doc.comments.associateBy { it.id }
            doc.comments.forEach { c ->
                append("""<w15:commentEx w15:paraId="${paraIdOf(c)}"""")
                c.parentId?.let { pid -> byId[pid]?.let { append(""" w15:paraIdParent="${paraIdOf(it)}"""") } }
                append(""" w15:done="${if (c.resolved) 1 else 0}"/>""")
            }
            append("</w15:commentsEx>")
        }

        /** `w:author` / `w:date` of a revision element from the run that carries it. */
        private fun revAttrs(f: com.therealityteam.aeropages.model.CharacterFormat): String =
            """ w:author="${Ooxml.escape(f.revAuthor ?: "Reality Office")}" w:date="${f.revDate?.let { Ooxml.isoDate(it) } ?: REV_DATE}""""

        /** word/endnotes.xml: same shape as footnotes.xml, `w:endnote` elements. */
        private fun buildEndnotes(doc: Document): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:endnotes xmlns:w="${Ooxml.W}">""")
            append("""<w:endnote w:type="separator" w:id="-1"><w:p><w:r><w:separator/></w:r></w:p></w:endnote>""")
            append("""<w:endnote w:type="continuationSeparator" w:id="0"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:endnote>""")
            doc.endnotes.forEachIndexed { i, en ->
                append("""<w:endnote w:id="${i + 1}"><w:p><w:r><w:t xml:space="preserve">${Ooxml.escape(en.text)}</w:t></w:r></w:p></w:endnote>""")
            }
            append("</w:endnotes>")
        }

        /** word/footnotes.xml: the two Word-required separator notes + one note per Footnote (ids 1..n). */
        private fun buildFootnotes(doc: Document): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
            append("""<w:footnotes xmlns:w="${Ooxml.W}">""")
            append("""<w:footnote w:type="separator" w:id="-1"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>""")
            append("""<w:footnote w:type="continuationSeparator" w:id="0"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:footnote>""")
            doc.footnotes.forEachIndexed { i, fn ->
                append("""<w:footnote w:id="${i + 1}"><w:p><w:r><w:t xml:space="preserve">${Ooxml.escape(fn.text)}</w:t></w:r></w:p></w:footnote>""")
            }
            append("</w:footnotes>")
        }

        /** Every picture in the document (top-level blocks and table cells), each with a relationship id and media name. */
        private fun collectImages(doc: Document, startId: Int): List<ImageRef> {
            val out = ArrayList<ImageRef>()
            fun add(block: ImageBlock) {
                val pic = com.therealityteam.aeropages.format.pptx.OoxmlPicture.of(block.data, block.mime)
                val i = out.size
                out.add(ImageRef(block, "rId${startId + i}", "image${i + 1}.${pic.mainExt}", pic))
            }
            doc.blocks.forEach { block ->
                when (block) {
                    is ImageBlock -> add(block)
                    is TableBlock -> block.table.rows.forEach { r -> r.cells.forEach { c -> c.images.forEach(::add) } }
                    else -> {}
                }
            }
            return out
        }

        /**
         * A header or footer part: its lines, each a paragraph with the centre and right tab stops Word's
         * Header and Footer styles have (at the middle and the right edge of [page]'s text), no spacing,
         * its page number and page count as PAGE / NUMPAGES fields; its pictures in line in their line's
         * paragraph (a line of pictures alone aligned as they were), the floating ones anchored to the first.
         */
        private fun buildHdrFtr(root: String, paragraph: Paragraph?, page: PageSetup, pictures: List<Pair<ImageBlock, String>> = emptyList(), firstDrawingId: Int = 5000): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
            append("""<w:$root xmlns:w="${Ooxml.W}" xmlns:m="${Omml.M}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">""")
            val width = Ooxml.ptToTwips(page.widthPt - page.marginLeftPt - page.marginRightPt).coerceAtLeast(720)
            val align = paragraph?.format?.align ?: com.therealityteam.aeropages.model.TextAlign.LEFT
            currentStyle = null
            val fields = Regex(Regex.escape(com.therealityteam.aeropages.model.HeaderFooters.PAGE) + "|" + Regex.escape(com.therealityteam.aeropages.model.HeaderFooters.PAGES))
            val lines = com.therealityteam.aeropages.model.HeaderFooters.runLines(paragraph?.runs.orEmpty())
            val count = maxOf(lines.size, (pictures.mapNotNull { it.first.floating?.line }.maxOrNull() ?: -1) + 1)
            var drawing = firstDrawingId
            for (li in 0 until count) {
                val runs = lines.getOrElse(li) { emptyList() }
                val inLine = pictures.filter { it.first.floating?.line == li }
                val lineAlign = com.therealityteam.aeropages.model.HeaderFooters.lineAlign(runs, inLine.map { it.first }, align)
                append("<w:p><w:pPr>")
                append("""<w:tabs><w:tab w:val="center" w:pos="${width / 2}"/><w:tab w:val="right" w:pos="$width"/></w:tabs>""")
                append("""<w:spacing w:before="0" w:after="0" w:line="240" w:lineRule="auto"/><w:jc w:val="${Ooxml.jcValue(lineAlign)}"/></w:pPr>""")
                // The floating pictures, anchored to the first paragraph where they stand on the page; those in line in theirs.
                if (li == 0) pictures.filter { it.first.floating?.line == null }.forEach { (img, id) ->
                    appendImageRun(img, com.therealityteam.aeropages.format.pptx.OoxmlPicture.blip(id, null), drawing++)
                }
                inLine.forEach { (img, id) -> appendImageRun(img, com.therealityteam.aeropages.format.pptx.OoxmlPicture.blip(id, null), drawing++) }
                for (run in runs) {
                    var pos = 0
                    for (m in fields.findAll(run.text)) {
                        if (m.range.first > pos) appendRun(TextRun(run.text.substring(pos, m.range.first), run.format))
                        appendField(if (m.value == com.therealityteam.aeropages.model.HeaderFooters.PAGE) "PAGE" else "NUMPAGES", run)
                        pos = m.range.last + 1
                    }
                    if (pos < run.text.length) appendRun(TextRun(run.text.substring(pos), run.format))
                }
                append("</w:p>")
            }
            append("</w:$root>")
        }

        /** A field Word works out as it lays the page out ([code]: PAGE, NUMPAGES), in [run]'s look, showing 1 until it does. */
        private fun StringBuilder.appendField(code: String, run: TextRun) {
            fun piece(inner: String) { append("<w:r>"); appendRunProps(run); append(inner).append("</w:r>") }
            piece("""<w:fldChar w:fldCharType="begin"/>""")
            piece("""<w:instrText xml:space="preserve"> $code </w:instrText>""")
            piece("""<w:fldChar w:fldCharType="separate"/>""")
            piece("<w:t>1</w:t>")
            piece("""<w:fldChar w:fldCharType="end"/>""")
        }

        private fun buildDocRels(images: List<ImageRef>, hdrParts: List<HfPart>, ftrParts: List<HfPart>, commentsId: String?, footnotesId: String?, links: Map<String, String>, commentsExId: String? = null, settingsId: String? = null, endnotesId: String? = null,
                                 charts: List<ChartRef> = emptyList()): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            append("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
            append("""<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>""")
            append("""<Relationship Id="rIdTheme" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>""")
            hdrParts.forEach { append("""<Relationship Id="${it.rId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="${it.file}"/>""") }
            ftrParts.forEach { append("""<Relationship Id="${it.rId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="${it.file}"/>""") }
            settingsId?.let { append("""<Relationship Id="$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>""") }
            commentsId?.let { append("""<Relationship Id="$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/>""") }
            commentsExId?.let { append("""<Relationship Id="$it" Type="http://schemas.microsoft.com/office/2011/relationships/commentsExtended" Target="commentsExtended.xml"/>""") }
            footnotesId?.let { append("""<Relationship Id="$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes" Target="footnotes.xml"/>""") }
            endnotesId?.let { append("""<Relationship Id="$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/endnotes" Target="endnotes.xml"/>""") }
            links.forEach { (url, rid) ->
                append("""<Relationship Id="$rid" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${Ooxml.escape(url)}" TargetMode="External"/>""")
            }
            images.forEach { img ->
                append("""<Relationship Id="${img.rId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${img.fileName}"/>""")
                img.svgRId?.let { append("""<Relationship Id="$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${img.svgFile}"/>""") }
            }
            charts.forEach { c ->
                append("""<Relationship Id="${c.rId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="charts/${c.fileName}"/>""")
            }
            append("</Relationships>")
        }

        private fun buildContentTypes(headerFiles: List<String>, footerFiles: List<String>, hasComments: Boolean, hasCustomProps: Boolean = false, hasFootnotes: Boolean = false, hasCommentsEx: Boolean = false, hasSettings: Boolean = false, hasEndnotes: Boolean = false,
                                      chartFiles: List<String> = emptyList()): String = buildString {
            append(CONTENT_TYPES_HEAD)
            chartFiles.forEach { append("""<Override PartName="/word/charts/$it" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>""") }
            headerFiles.forEach { append("""<Override PartName="/word/$it" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>""") }
            footerFiles.forEach { append("""<Override PartName="/word/$it" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>""") }
            if (hasSettings) append("""<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>""")
            if (hasComments) append("""<Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/>""")
            if (hasCommentsEx) append("""<Override PartName="/word/commentsExtended.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.commentsExtended+xml"/>""")
            if (hasCustomProps) append("""<Override PartName="/docProps/custom.xml" ContentType="application/vnd.openxmlformats-officedocument.custom-properties+xml"/>""")
            if (hasFootnotes) append("""<Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/>""")
            if (hasEndnotes) append("""<Override PartName="/word/endnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.endnotes+xml"/>""")
            append("</Types>")
        }

        private fun ZipOutputStream.put(name: String, content: String) {
            putNextEntry(ZipEntry(name))
            write(content.toByteArray(Charsets.UTF_8))
            closeEntry()
        }

        private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray) {
            putNextEntry(ZipEntry(name))
            write(bytes)
            closeEntry()
        }

        private fun buildDocumentXml(doc: Document, images: List<ImageRef>, hdrParts: List<HfPart>, ftrParts: List<HfPart>): String = buildString {
            sectionHeaders = hdrParts; sectionFooters = ftrParts   // section breaks repeat the same references
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
            append(
                """<w:document xmlns:w="${Ooxml.W}" xmlns:m="${Omml.M}" """ +
                    """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
                    """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing">"""
            )
            doc.pageColor?.let { append("""<w:background w:color="${Ooxml.colorToHex(it)}"/>""") }
            append("<w:body>")
            imageRefs = images; imgOrdinal = 0; boxOrdinal = 0; chartOrdinal = 0
            bookmarkIds.clear(); bookmarksEnded.clear()
            val found = com.therealityteam.aeropages.model.Bookmarks.of(doc)
            bookmarksClosed = found.filter { it.closed }.mapTo(HashSet()) { it.name }
            // A table of contents links its entries to their headings: through the heading's own `_Toc` bookmark, or a new one.
            tocMarks = com.therealityteam.aeropages.model.Bookmarks.tocMarks(doc)
            // A floating picture or text box goes into the paragraph it is anchored to (the one before it), as Word keeps it.
            val anchoredTo = java.util.IdentityHashMap<com.therealityteam.aeropages.model.Block, MutableList<com.therealityteam.aeropages.model.Block>>()
            val anchored = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<com.therealityteam.aeropages.model.Block, Boolean>())
            var host: ParagraphBlock? = null
            doc.blocks.forEach { b ->
                if (b is ParagraphBlock) host = b
                else if (floatOf(b) != null && host != null && (b !is ImageBlock || images.any { it.block === b })) {
                    anchoredTo.getOrPut(host!!) { ArrayList() }.add(b); anchored.add(b)
                } else host = null
            }
            // Word's cover page: its blocks (through the page break that ends it) in a "Cover Pages" content control.
            val coverEnd = com.therealityteam.aeropages.model.CoverPages.end(doc) ?: -1
            if (coverEnd >= 0) append("""<w:sdt><w:sdtPr><w:id w:val="1952188736"/><w:docPartObj><w:docPartGallery w:val="Cover Pages"/><w:docPartUnique/></w:docPartObj></w:sdtPr><w:sdtContent>""")
            doc.blocks.forEachIndexed { i, block ->
                if (block in anchored) { if (i == coverEnd) append("</w:sdtContent></w:sdt>"); return@forEachIndexed }
                when (block) {
                    is ParagraphBlock -> appendParagraph(block.paragraph, anchoredTo[block].orEmpty())
                    is com.therealityteam.aeropages.model.TextBoxBlock -> appendTextBox(block, ++boxOrdinal)
                    is TableBlock -> appendTable(block.table)
                    is ImageBlock -> {
                        val ref = images.firstOrNull { it.block === block }
                        if (ref != null) appendImage(block, ref.blip, ++imgOrdinal)
                    }
                    is com.therealityteam.aeropages.model.TocBlock -> appendToc(doc, block)
                    is com.therealityteam.aeropages.model.IndexBlock -> appendIndex(doc, block)
                    is com.therealityteam.aeropages.model.EquationBlock -> appendEquation(block)
                    is com.therealityteam.aeropages.model.ChartBlock -> chartRefs.firstOrNull { it.block === block }?.let { ref -> appendChart(block, ref, ++chartOrdinal) }
                    is com.therealityteam.aeropages.model.BibliographyBlock -> appendGenerated(com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.BIBLIOGRAPHY_HEADING), bibEntries(doc))
                }
                if (i == coverEnd) append("</w:sdtContent></w:sdt>")
            }
            // A bookmark whose end the document did not reach after its start ends here.
            bookmarkIds.forEach { (name, id) -> if (name !in bookmarksEnded) append("""<w:bookmarkEnd w:id="$id"/>""") }
            appendSectPr(doc, hdrParts, ftrParts)
            append("</w:body></w:document>")
        }

        /**
         * Bookmark starts and ends where they stand in the text: a name's first start (with its end right
         * after when the bookmark is a point), the end of a bookmark already started; others are left out.
         */
        private fun StringBuilder.appendBookmarks(marks: List<com.therealityteam.aeropages.model.BookmarkMark>) {
            marks.forEach { m ->
                // An index entry: Word's XE field where it is marked.
                if (com.therealityteam.aeropages.model.IndexEntries.isEntry(m.name)) {
                    com.therealityteam.aeropages.model.IndexEntries.entryOf(m.name)?.let { e ->
                        append("""<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve">${Ooxml.escape(com.therealityteam.aeropages.model.IndexEntries.fieldCode(e))}</w:instrText></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>""")
                    }
                    return@forEach
                }
                // A comment's range: its start, or its end followed by the comment's reference and its replies' (Word's order).
                if (com.therealityteam.aeropages.model.Bookmarks.isComment(m.name)) {
                    val cid = com.therealityteam.aeropages.model.Bookmarks.commentIdOf(m.name)?.takeIf { it in commentRanged } ?: return@forEach
                    val idx = commentIndex[cid] ?: return@forEach
                    if (!m.end) append("""<w:commentRangeStart w:id="$idx"/>""")
                    else {
                        append("""<w:commentRangeEnd w:id="$idx"/><w:r><w:commentReference w:id="$idx"/></w:r>""")
                        commentReplies[cid]?.forEach { r -> append("""<w:r><w:commentReference w:id="$r"/></w:r>""") }
                    }
                    return@forEach
                }
                if (!m.end) {
                    if (m.name in bookmarkIds) return@forEach
                    val id = bookmarkIds.size
                    bookmarkIds[m.name] = id
                    append("""<w:bookmarkStart w:id="$id" w:name="${Ooxml.escape(m.name)}"/>""")
                    if (m.name !in bookmarksClosed) { bookmarksEnded.add(m.name); append("""<w:bookmarkEnd w:id="$id"/>""") }
                } else {
                    val id = bookmarkIds[m.name] ?: return@forEach
                    if (bookmarksEnded.add(m.name)) append("""<w:bookmarkEnd w:id="$id"/>""")
                }
            }
        }

        private fun StringBuilder.appendImage(image: ImageBlock, blip: String, ordinal: Int, maxWidthPt: Float? = null) {
            append("""<w:p><w:pPr><w:jc w:val="center"/></w:pPr>""")
            appendImageRun(image, blip, ordinal, maxWidthPt)
            append("</w:p>")
        }

        /**
         * The opening of a drawing at [cx] × [cy] EMU: in line with the text, or — [pos] — anchored and
         * placed on the page (its frames, offset or alignment, wrapping, behind the text), its extent,
         * its wrapping and its docPr ([id], [name]).
         */
        private fun StringBuilder.appendDrawingStart(pos: com.therealityteam.aeropages.model.FloatPosition?, cx: Long, cy: Long, id: Int, name: String,
                                                     alt: String = "", decorative: Boolean = false) {
            val docPr = docPr(id, name, alt, decorative)
            if (pos == null) {
                append("""<wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/>$docPr""")
                return
            }
            append(DrawingAnchors.anchorStart(pos, cx, cy, id))
            append("""$docPr<wp:cNvGraphicFramePr/>""")
        }

        /** A drawing's `wp:docPr`: its id and name, its alternative text, and Office 2019's decorative mark. */
        private fun docPr(id: Int, name: String, alt: String, decorative: Boolean): String {
            val descr = if (alt.isNotEmpty()) """ descr="${Ooxml.escape(alt)}"""" else ""
            if (!decorative) return """<wp:docPr id="$id" name="$name"$descr/>"""
            return """<wp:docPr id="$id" name="$name"$descr><a:extLst xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""" +
                """<a:ext uri="{C183D7F6-B498-43B3-948B-1728B52AA6E4}"><adec:decorative xmlns:adec="${DocxReader.ADEC_NS}" val="1"/></a:ext></a:extLst></wp:docPr>"""
        }

        private fun drawingEnd(pos: com.therealityteam.aeropages.model.FloatPosition?) = if (pos == null) "</wp:inline>" else "</wp:anchor>"

        /** A picture's run: its drawing in line or anchored (inside a cell always in line, scaled down to the cell's width — Word would otherwise overflow it). */
        private fun StringBuilder.appendImageRun(image: ImageBlock, blip: String, ordinal: Int, maxWidthPt: Float? = null) {
            val scale = if (maxWidthPt != null && image.widthPt > maxWidthPt && image.widthPt > 0f) maxWidthPt / image.widthPt else 1f
            val cx = (image.widthPt * scale * 12700f).toLong()
            val cy = (image.heightPt * scale * 12700f).toLong()
            // A header's picture standing in its line goes in line.
            val pos = image.floating.takeIf { maxWidthPt == null && it?.line == null }
            append("""<w:r><w:drawing>""")
            appendDrawingStart(pos, cx, cy, ordinal, "Image$ordinal", image.altText, image.decorative)
            append("""<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""")
            append("""<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""")
            append("""<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture">""")
            append("""<pic:nvPicPr><pic:cNvPr id="$ordinal" name="Image$ordinal"/><pic:cNvPicPr/></pic:nvPicPr>""")
            append("""<pic:blipFill>$blip${com.therealityteam.aeropages.format.PictureCrop.srcRect(image)}<a:stretch><a:fillRect/></a:stretch></pic:blipFill>""")
            val flips = (if (image.flipH) """ flipH="1"""" else "") + (if (image.flipV) """ flipV="1"""" else "")
            append("""<pic:spPr><a:xfrm$flips><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
            append("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>${com.therealityteam.aeropages.format.pptx.PptxWriter.effectXml(image.shadow)}</pic:spPr>""")
            append("""</pic:pic></a:graphicData></a:graphic>${drawingEnd(pos)}</w:drawing></w:r>""")
        }

        /**
         * A text box as an inline `wps` shape in a paragraph of its own (Word 2010+ and LibreOffice
         * read wps directly): Word's plain box — white, a thin black rule — or, a drawn shape, its
         * own geometry, fill, outline, turn and flips (a line as a connector); its text, if any, in
         * a `wps:txbx`, placed top, middle or bottom as the shape says.
         */
        private fun StringBuilder.appendTextBox(box: com.therealityteam.aeropages.model.TextBoxBlock, ordinal: Int) {
            append("<w:p>")
            appendTextBoxRun(box, ordinal)
            append("</w:p>")
        }

        /** A text box's run: its `wps` shape in line or, floating, anchored. */
        private fun StringBuilder.appendTextBoxRun(box: com.therealityteam.aeropages.model.TextBoxBlock, ordinal: Int) {
            val sh = box.shape
            val w = box.widthPt ?: 320f
            val h = box.heightPt ?: (box.paragraphs.size * 18f + 16f)
            val cx = (w * 12700).toLong(); val cy = (h * 12700).toLong()
            val line = sh?.type == com.therealityteam.aeropages.model.ShapeType.LINE
            append("<w:r><w:drawing>")
            appendDrawingStart(box.floating, cx, cy, 4000 + ordinal, "${if (sh == null || sh.type == com.therealityteam.aeropages.model.ShapeType.TEXTBOX) "Text Box" else "Shape"} $ordinal")
            append("""<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">""")
            append("""<a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">""")
            append("""<wps:wsp xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">""")
            append(when {
                line -> "<wps:cNvCnPr/>"
                sh == null || sh.type == com.therealityteam.aeropages.model.ShapeType.TEXTBOX -> """<wps:cNvSpPr txBox="1"/>"""
                else -> "<wps:cNvSpPr/>"
            })
            val rot = sh?.rotationDeg?.takeIf { it != 0f }?.let { """ rot="${(it * 60000).toInt()}"""" } ?: ""
            val flips = (if (sh?.flipH == true) """ flipH="1"""" else "") + (if (sh?.flipV == true) """ flipV="1"""" else "")
            append("""<wps:spPr><a:xfrm$rot$flips><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>""")
            if (sh == null) {
                append("""<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>""")
                append("""<a:solidFill><a:srgbClr val="FFFFFF"/></a:solidFill><a:ln w="6350"><a:solidFill><a:srgbClr val="000000"/></a:solidFill></a:ln>""")
            } else {
                append(com.therealityteam.aeropages.format.pptx.PptxWriter.geometryXml(sh))
                if (!line) append(com.therealityteam.aeropages.format.pptx.PptxWriter.fillXml(sh))
                append(com.therealityteam.aeropages.format.pptx.PptxWriter.outlineXml(sh, "<a:ln><a:noFill/></a:ln>"))
                append(com.therealityteam.aeropages.format.pptx.PptxWriter.effectXml(sh))
            }
            append("</wps:spPr>")
            if (box.paragraphs.isNotEmpty() && !line) {
                append("<wps:txbx><w:txbxContent>")
                box.paragraphs.forEach { appendParagraph(it) }
                append("</w:txbxContent></wps:txbx>")
            }
            val anchor = when (sh?.anchor) {
                com.therealityteam.aeropages.model.ShapeAnchor.CENTER -> "ctr"
                com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> "b"
                else -> "t"
            }
            append("""<wps:bodyPr rot="0" vert="horz" wrap="${if (sh?.noWrap == true) "none" else "square"}" lIns="91440" tIns="45720" rIns="91440" bIns="45720" anchor="$anchor" anchorCtr="0"><a:noAutofit/></wps:bodyPr>""")
            append("</wps:wsp></a:graphicData></a:graphic>${drawingEnd(box.floating)}</w:drawing></w:r>")
        }

        private fun StringBuilder.appendTable(table: com.therealityteam.aeropages.model.Table) {
            append("<w:tbl>")
            // CT_TblPr: its position (a floating table's), the width before the borders.
            append("<w:tblPr>")
            table.floating?.let { append(DrawingAnchors.tablePositionXml(it)) }
            val w = table.widthPt
            append(if (w != null) """<w:tblW w:w="${Math.round(w * 20)}" w:type="dxa"/>""" else """<w:tblW w:w="0" w:type="auto"/>""")
            // In the text: where it stands across the column (CT_TblPr: jc, then tblInd, after the width).
            if (table.floating == null) {
                if (table.align != com.therealityteam.aeropages.model.TextAlign.LEFT) append("""<w:jc w:val="${Ooxml.jcValue(table.align)}"/>""")
                if (table.indentPt != 0f && table.align == com.therealityteam.aeropages.model.TextAlign.LEFT) append("""<w:tblInd w:w="${Math.round(table.indentPt * 20)}" w:type="dxa"/>""")
            }
            // Its grid's line on every side, or none — then the cells' own borders, each in its tcBorders.
            append(WordBorders.tableXml(table))
            append("</w:tblPr>")

            // Grid columns = the widest row by total gridSpan (so merges are consistent).
            val cols = (table.rows.maxOfOrNull { r -> r.cells.sumOf { it.gridSpan } } ?: 1).coerceAtLeast(1)
            val totalWeight = (0 until cols).sumOf { table.weightOf(it).toDouble() }.coerceAtLeast(1.0)
            // A table is as wide as it says; one that does not, the text's width on a Letter page.
            val totalTwips = table.widthPt?.let { it * 20.0 } ?: 9360.0
            val colTwips = IntArray(cols) { (totalTwips * table.weightOf(it) / totalWeight).toInt() }
            append("<w:tblGrid>")
            colTwips.forEach { append("""<w:gridCol w:w="$it"/>""") }
            append("</w:tblGrid>")

            table.rows.forEach { row ->
                append("<w:tr>")
                val height = row.heightPt?.let { """<w:trHeight w:val="${Math.round(it * 20)}"${if (row.exactHeight) """ w:hRule="exact"""" else ""}/>""" }.orEmpty()
                if (row.header || height.isNotEmpty()) append("<w:trPr>$height${if (row.header) "<w:tblHeader/>" else ""}</w:trPr>")
                var col = 0
                row.cells.forEach { cell ->
                    val span = cell.gridSpan.coerceAtLeast(1)
                    val w = (0 until span).sumOf { colTwips.getOrElse(col + it) { 0 } }
                    append("<w:tc><w:tcPr>")
                    append("""<w:tcW w:w="$w" w:type="dxa"/>""")
                    if (span > 1) append("""<w:gridSpan w:val="$span"/>""")
                    if (cell.rowSpan > 1) append("""<w:vMerge w:val="restart"/>""")
                    else if (cell.vMerged) append("""<w:vMerge/>""")
                    append(WordBorders.cellXml(cell.borders))
                    cell.shade?.let { append("""<w:shd w:val="clear" w:color="auto" w:fill="${Ooxml.colorToHex(it)}"/>""") }
                    append("</w:tcPr>")
                    if (cell.content.isEmpty()) append("<w:p/>")
                    else cell.content.forEach { appendParagraph(it) }
                    cell.images.forEach { img -> imageRefs.firstOrNull { it.block === img }?.let { appendImage(img, it.blip, ++imgOrdinal, maxWidthPt = w / 20f - 12f) } }
                    append("</w:tc>")
                    col += span
                }
                append("</w:tr>")
            }
            append("</w:tbl>")
        }

        /** Heading text entries for a generated TOC (from the document's headings). */
        private fun bibEntries(doc: Document): List<String> =
            doc.sources.map { "${it.author}. ${it.title}. ${it.year}." }

        /** Write a generated section (TOC / bibliography) as a heading + plain paragraphs. */
        /**
         * Word's table of contents: a content control marked as the TOC gallery holding the heading
         * ("TOC Heading") and a `TOC \o "1-3" \h \z \u` field whose cached result lists the Heading
         * 1–3 entries (TOC 1–3 styles, dot-leader tab at the right margin). The field is marked dirty,
         * so Word offers to update it — with page numbers — when the file opens.
         */
        /**
         * A contents block as Word's: a content control in the Table of Contents gallery holding its heading and a
         * `TOC \o "1-3"` field — or, for a table of figures, a `TOC \c "Figure"` field (no heading) in a control of
         * its own gallery, so it reads back as one. Word updates either (`w:dirty`).
         */
        private fun StringBuilder.appendToc(doc: Document, block: com.therealityteam.aeropages.model.TocBlock) {
            val title = com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.TOC_HEADING)
            val pg = doc.page
            val tabPos = Ooxml.ptToTwips(pg.widthPt - pg.marginLeftPt - pg.marginRightPt)
            val figures = block.caption
            val gallery = if (figures != null) "Table of Figures" else "Table of Contents"
            append("""<w:sdt><w:sdtPr><w:docPartObj><w:docPartGallery w:val="$gallery"/>${if (figures == null) "<w:docPartUnique/>" else ""}</w:docPartObj></w:sdtPr><w:sdtContent>""")
            if (figures == null) append("""<w:p><w:pPr><w:pStyle w:val="TOCHeading"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr><w:t xml:space="preserve">${Ooxml.escape(title)}</w:t></w:r></w:p>""")
            val code = if (figures != null) """ TOC \h \z \c "${Ooxml.escape(figures)}" """ else """ TOC \o "1-3" \h \z \u """
            val begin = """<w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/></w:r><w:r><w:instrText xml:space="preserve">$code</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>"""
            val end = """<w:r><w:fldChar w:fldCharType="end"/></w:r>"""
            val entries = doc.tocEntries(block)
            if (entries.isEmpty()) append("<w:p>$begin$end</w:p>")
            entries.forEachIndexed { i, e ->
                val style = if (e.figure) "TableofFigures" else "TOC${e.level + 1}"
                append("""<w:p><w:pPr><w:pStyle w:val="$style"/><w:tabs><w:tab w:val="right" w:leader="dot" w:pos="$tabPos"/></w:tabs>""")
                if (e.level > 0) append("""<w:ind w:left="${e.level * 220}"/>""")
                append("</w:pPr>")
                if (i == 0) append(begin)
                val mark = tocMarks[e.paragraph.id]?.first
                if (mark != null) append("""<w:hyperlink w:anchor="$mark" w:history="1">""")
                append("""<w:r><w:t xml:space="preserve">${Ooxml.escape(e.text)}</w:t></w:r><w:r><w:tab/></w:r>""")
                // Its page as last laid out (the field's result until Word updates it).
                doc.pageCache[e.paragraph.id]?.let { append("""<w:r><w:t>${Ooxml.escape(it)}</w:t></w:r>""") }
                if (mark != null) append("</w:hyperlink>")
                if (i == entries.lastIndex) append(end)
                append("</w:p>")
            }
            append("</w:sdtContent></w:sdt>")
        }

        /**
         * Word's index: an `INDEX` field (dirty, so Word updates it) whose result is the entries as last laid out — "index 1"
         * and "index 2" paragraphs, a letter's "index heading" — each with its pages after a comma, or after a dotted tab.
         */
        private fun StringBuilder.appendIndex(doc: Document, block: com.therealityteam.aeropages.model.IndexBlock) {
            val pg = doc.page
            val tabPos = Ooxml.ptToTwips(pg.widthPt - pg.marginLeftPt - pg.marginRightPt)
            val code = Ooxml.escape(com.therealityteam.aeropages.model.IndexEntries.indexCode(block))
            val begin = """<w:r><w:fldChar w:fldCharType="begin" w:dirty="true"/></w:r><w:r><w:instrText xml:space="preserve">$code</w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>"""
            val end = """<w:r><w:fldChar w:fldCharType="end"/></w:r>"""
            val lines = com.therealityteam.aeropages.model.IndexEntries.lines(doc, block.headings)
            if (lines.isEmpty()) {
                val none = com.therealityteam.aeropages.model.Texts.get(com.therealityteam.aeropages.model.TextId.INDEX_EMPTY)
                append("""<w:p>$begin<w:r><w:t xml:space="preserve">${Ooxml.escape(none)}</w:t></w:r>$end</w:p>""")
                return
            }
            lines.forEachIndexed { i, l ->
                val style = when { l.heading -> "IndexHeading"; l.level == 0 -> "Index1"; else -> "Index2" }
                append("""<w:p><w:pPr><w:pStyle w:val="$style"/>""")
                if (block.rightAlign && !l.heading) append("""<w:tabs><w:tab w:val="right" w:leader="dot" w:pos="$tabPos"/></w:tabs>""")
                append("</w:pPr>")
                if (i == 0) append(begin)
                append("""<w:r><w:t xml:space="preserve">${Ooxml.escape(l.text)}</w:t></w:r>""")
                val pages = l.pages { doc.pageCache[it.id] }
                if (pages.isNotEmpty()) append(if (block.rightAlign) """<w:r><w:tab/></w:r><w:r><w:t>${Ooxml.escape(pages)}</w:t></w:r>""" else """<w:r><w:t xml:space="preserve">, ${Ooxml.escape(pages)}</w:t></w:r>""")
                if (i == lines.lastIndex) append(end)
                append("</w:p>")
            }
        }

        private fun StringBuilder.appendGenerated(title: String, entries: List<String>) {
            append("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/><w:sz w:val="40"/></w:rPr>""")
            append("""<w:t xml:space="preserve">${Ooxml.escape(title)}</w:t></w:r></w:p>""")
            entries.forEach { e ->
                append("""<w:p><w:r><w:t xml:space="preserve">${Ooxml.escape(e)}</w:t></w:r></w:p>""")
            }
        }

        /** A displayed equation: a paragraph holding an `m:oMathPara`, justified as the block is. */
        private fun StringBuilder.appendEquation(block: com.therealityteam.aeropages.model.EquationBlock) {
            val jc = when (block.align) { TextAlign.LEFT -> "left"; TextAlign.RIGHT -> "right"; else -> "center" }
            append("""<w:p><w:pPr><w:jc w:val="${Ooxml.jcValue(block.align)}"/></w:pPr><m:oMathPara><m:oMathParaPr><m:jc m:val="$jc"/></m:oMathParaPr><m:oMath>""")
            append(Omml.write(block.math, block.sizePt.takeIf { it != com.therealityteam.aeropages.model.EquationBlock.DEFAULT_SIZE }))
            append("</m:oMath></m:oMathPara></w:p>")
        }

        /**
         * Runs [from] on that spell an equation still as it was read (all tied to one [InlineMath] and
         * reading its text) as the `m:oMath` it came from; returns how many runs that took, 0 when
         * they no longer do (an edit made them text).
         */
        private fun StringBuilder.appendInlineMath(runs: List<TextRun>, from: Int): Int {
            val math = runs[from].format.math ?: return 0
            var end = from
            val text = StringBuilder()
            while (end < runs.size && runs[end].format.math === math) { text.append(runs[end].text); end++ }
            if (text.toString() != math.text) return 0
            append("<m:oMath>").append(Omml.write(math.math, runs[from].format.fontSizePt)).append("</m:oMath>")
            return end - from
        }

        /** A floating picture's or text box's position, if it has one. */
        private fun floatOf(b: com.therealityteam.aeropages.model.Block) = when (b) {
            is ImageBlock -> b.floating
            is com.therealityteam.aeropages.model.TextBoxBlock -> b.floating
            else -> null
        }

        /**
         * Word's drop cap: [p]'s first letters in a frame paragraph of their own before it (`w:framePr w:dropCap`), set
         * at their size on an exact line as tall as the lines they drop (see DropCaps); [p] then goes on without them.
         */
        private fun StringBuilder.appendDropCapFrame(p: Paragraph) {
            val d = p.format.dropCap ?: return
            val text = com.therealityteam.aeropages.model.DropCaps.text(p)
            if (text.isEmpty()) return
            val pitch = com.therealityteam.aeropages.model.PageMetrics.pitchPt(p)
            val size = com.therealityteam.aeropages.model.DropCaps.size(text, d.lines, pitch, com.therealityteam.aeropages.model.PageMetrics.maxSizePt(p))
            val styleId = p.format.rawStyleId ?: styleSheet.idOf(p.format.style)
            append("<w:p><w:pPr>")
            append("""<w:pStyle w:val="${Ooxml.escape(styleId)}"/><w:keepNext/>""")
            // CT_PPrBase: framePr after pageBreakBefore, before widowControl; spacing later, textAlignment near the end.
            append("""<w:framePr w:dropCap="${if (d.inMargin) "margin" else "drop"}" w:lines="${d.lines}" w:hSpace="${Ooxml.ptToTwips(d.distancePt)}" w:wrap="around" w:vAnchor="text" w:hAnchor="${if (d.inMargin) "page" else "text"}"/>""")
            append("""<w:spacing w:before="0" w:after="0" w:line="${Ooxml.ptToTwips(d.lines * pitch)}" w:lineRule="exact"/><w:textAlignment w:val="baseline"/>""")
            append("</w:pPr>")
            appendRun(TextRun(text, com.therealityteam.aeropages.model.DropCaps.format(p).copy(fontSizePt = size)))
            append("</w:p>")
        }

        private fun StringBuilder.appendParagraph(p: Paragraph, anchored: List<com.therealityteam.aeropages.model.Block> = emptyList()) {
            currentStyle = styleSheet.resolve(p.format.rawStyleId ?: styleSheet.idOf(p.format.style))
            // A drop cap's letters go first, in their frame; the paragraph's runs then leave them out.
            var capSkip = com.therealityteam.aeropages.model.DropCaps.length(p)
            if (capSkip > 0) appendDropCapFrame(p)
            append("<w:p>")
            appendParagraphProps(p)
            // A heading the table of contents links to without a bookmark of its own: a new `_Toc` one round its text.
            val tocMark = tocMarks[p.id]?.takeIf { it.second }?.first?.let { name ->
                val id = bookmarkIds.size
                bookmarkIds[name] = id; bookmarksEnded.add(name)
                append("""<w:bookmarkStart w:id="$id" w:name="$name"/>""")
                id
            }
            // Bookmarks stand between runs: a run with one starting or ending inside it is cut there.
            val marks = com.therealityteam.aeropages.model.Bookmarks.Cursor(p)
            var pos = 0
            var skip = 0
            // A live field's result (a caption's number, a cross-reference): Word's complex field round its runs.
            var openField: com.therealityteam.aeropages.model.DocField? = null
            fun fieldTo(f: com.therealityteam.aeropages.model.DocField?) {
                if (f === openField) return
                if (openField != null) append("""<w:r><w:fldChar w:fldCharType="end"/></w:r>""")
                if (f != null) append("""<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> ${Ooxml.escape(f.code)} </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>""")
                openField = f
            }
            p.runs.forEachIndexed { i, whole ->
                if (skip > 0) { skip--; pos += whole.text.length; return@forEachIndexed }
                val run = if (capSkip == 0) whole else {
                    val k = minOf(capSkip, whole.text.length)
                    capSkip -= k; pos += k
                    if (k == whole.text.length) return@forEachIndexed
                    TextRun(whole.text.substring(k), whole.format)
                }
                fieldTo(run.format.field)
                appendBookmarks(marks.upTo(pos))
                val took = appendInlineMath(p.runs, i)
                if (took > 0) { skip = took - 1; pos += run.text.length; return@forEachIndexed }
                var from = 0
                while (true) {
                    val cut = marks.cutIn(pos + from, pos + run.text.length) ?: break
                    appendLinkedRun(TextRun(run.text.substring(from, cut - pos), run.format))
                    from = cut - pos
                    appendBookmarks(marks.upTo(cut))
                }
                appendLinkedRun(if (from == 0) run else TextRun(run.text.substring(from), run.format))
                pos += run.text.length
            }
            fieldTo(null)
            appendBookmarks(marks.rest())
            tocMark?.let { append("""<w:bookmarkEnd w:id="$it"/>""") }
            // Comments on the paragraph (no range of their own): their references at its end.
            commentRefs[p.id]?.forEach { idx ->
                val c = doc.comments[idx]
                if ((c.parentId ?: c.id) !in commentRanged) append("""<w:r><w:commentReference w:id="$idx"/></w:r>""")
            }
            // What floats from this paragraph: anchored drawings at its end.
            anchored.forEach { b ->
                when (b) {
                    is ImageBlock -> imageRefs.firstOrNull { it.block === b }?.let { ref -> appendImageRun(b, ref.blip, ++imgOrdinal) }
                    is com.therealityteam.aeropages.model.TextBoxBlock -> appendTextBoxRun(b, ++boxOrdinal)
                    else -> Unit
                }
            }
            append("</w:p>")
        }

        /**
         * A run of a paragraph as Word keeps it: footnote markers as references, inside its link — to an
         * address (a relationship) or a bookmark (`w:anchor`) — and its revision (`w:ins` / `w:del`).
         */
        private fun StringBuilder.appendLinkedRun(run: TextRun) {
            // Superscript "[n]" markers are footnote anchors → real <w:footnoteReference>.
            // Adjacent markers coalesce into one run ("[1][2]"), so emit per-marker.
            if (run.format.superscript && (footnoteCount > 0 || endnoteCount > 0) && appendFootnoteAnchors(run)) return
            // Track-changes persist as OOXML revisions: insertions in <w:ins>, deletions
            // in <w:del> with <w:delText>, so Word/reopen shows them pending review.
            // A field's result links through its own \h switch, not a hyperlink round it.
            val link = run.format.link.takeIf { run.format.field == null }
            val place = com.therealityteam.aeropages.model.Bookmarks.targetOf(link)
            val rid = link?.let { linkIds[it] }
            when {
                place != null -> append("""<w:hyperlink w:anchor="${Ooxml.escape(place)}" w:history="1">""")
                rid != null -> append("""<w:hyperlink r:id="$rid">""")
            }
            when {
                run.format.inserted -> {
                    append("""<w:ins w:id="${++revId}"${revAttrs(run.format)}>""")
                    appendRun(run)
                    append("</w:ins>")
                }
                run.format.deleted -> {
                    append("""<w:del w:id="${++revId}"${revAttrs(run.format)}>""")
                    appendRun(run, del = true)
                    append("</w:del>")
                }
                else -> appendRun(run)
            }
            if (place != null || rid != null) append("</w:hyperlink>")
        }

        /**
         * Emits a superscript run as interleaved <w:footnoteReference> anchors (for each
         * "[n]" with a real footnote id) and plain text segments. Returns false — and
         * emits nothing — when the run contains no valid marker.
         */
        private fun StringBuilder.appendFootnoteAnchors(run: TextRun): Boolean {
            // "[n]" is a footnote anchor, "[iv]" (a roman numeral) an endnote anchor.
            val matches = Regex("""\[([0-9]+|[ivxlcdm]+)]""").findAll(run.text).mapNotNull { m ->
                val body = m.groupValues[1]
                val digits = body.toIntOrNull()
                when {
                    digits != null -> if (digits in 1..footnoteCount) Triple(m, "footnoteReference", digits) else null
                    else -> com.therealityteam.aeropages.model.Roman.parse(body)?.takeIf { it in 1..endnoteCount }?.let { Triple(m, "endnoteReference", it) }
                }
            }.toList()
            if (matches.isEmpty()) return false
            var pos = 0
            matches.forEach { (m, element, id) ->
                if (m.range.first > pos) appendRun(TextRun(run.text.substring(pos, m.range.first), run.format))
                append("""<w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:$element w:id="$id"/></w:r>""")
                pos = m.range.last + 1
            }
            if (pos < run.text.length) appendRun(TextRun(run.text.substring(pos), run.format))
            return true
        }

        private fun StringBuilder.appendParagraphProps(p: Paragraph) {
            val f = p.format
            append("<w:pPr>")
            val styleId = f.rawStyleId ?: styleSheet.idOf(f.style)
            append("""<w:pStyle w:val="${Ooxml.escape(styleId)}"/>""")
            // Line and page breaks, in CT_PPrBase order (keepNext, keepLines, pageBreakBefore, …, widowControl, numPr).
            when (f.keepWithNext) {
                true -> append("<w:keepNext/>")
                false -> append("""<w:keepNext w:val="0"/>""")
                null -> Unit
            }
            if (f.keepTogether) append("<w:keepLines/>")
            if (f.pageBreakBefore) append("<w:pageBreakBefore/>")
            if (!f.widowControl) append("""<w:widowControl w:val="0"/>""")
            if (f.listKind != ListKind.NONE) {
                val numId = listPlan.numIdOf(p)
                append("""<w:numPr><w:ilvl w:val="${f.listLevel}"/><w:numId w:val="$numId"/></w:numPr>""")
            }
            // Its lines not numbered (CT_PPrBase: suppressLineNumbers after numPr, before pBdr).
            if (f.suppressLineNumbers) append("<w:suppressLineNumbers/>")
            // Borders and shading (CT_PPrBase: pBdr, shd after numPr, before tabs): the paragraph's own, or none where its style has some.
            val look = styleSheet.resolve(styleId)
            if (f.borders?.none == false) append(WordBorders.paragraphXml(f.borders)) else if (look.borders != null) append(WordBorders.paragraphXml(null))
            if (f.shading != null || look.shading != null) append(WordBorders.shadingXml(f.shading))
            // CT_PPrBase: tabs after numPr, before spacing.
            if (f.tabStops.isNotEmpty()) append(Ooxml.tabsXml(f.tabStops))
            // Off the document grid (CT_PPrBase: snapToGrid just before spacing).
            if (!f.snapToGrid) append("""<w:snapToGrid w:val="0"/>""")
            // A multiple of single lines in 240ths, or an exact or least height in twips.
            val height = f.lineHeightPt
            val line = if (height != null) """w:line="${Ooxml.ptToTwips(height)}" w:lineRule="${if (f.lineExact) "exact" else "atLeast"}""""
                else """w:line="${(f.lineSpacing * 240f).toInt()}" w:lineRule="auto""""
            append(
                """<w:spacing w:before="${Ooxml.ptToTwips(f.spaceBeforePt)}" """ +
                    """w:after="${Ooxml.ptToTwips(f.spaceAfterPt)}" """ +
                    """$line/>"""
            )
            // Indents unless they are the style's (then explicit zeros too, where the style indents).
            val st = styleSheet.resolve(styleId)
            if (f.indentLeftPt != (st.indentLeftPt ?: 0f) || f.firstLineIndentPt != (st.firstLineIndentPt ?: 0f) || f.indentRightPt != (st.indentRightPt ?: 0f)) {
                append(Ooxml.indXml(f.indentLeftPt, f.firstLineIndentPt, f.indentRightPt))
            }
            // CT_PPr requires <w:jc> after <w:spacing>/<w:ind>.
            append("""<w:jc w:val="${Ooxml.jcValue(f.align)}"/>""")
            // An empty paragraph's look is its mark's (its size makes the line's height): CT_PPr's rPr, before sectPr; a
            // tracked change of the mark itself leads it (CT_ParaRPr: ins / del first).
            val mark = f.revisionMark?.let { m -> """<w:${if (m.inserted) "ins" else "del"} w:id="${++revId}" w:author="${Ooxml.escape(m.author ?: "Reality Office")}" w:date="${m.date?.let { Ooxml.isoDate(it) } ?: REV_DATE}"/>""" } ?: ""
            if (p.runs.all { it.text.isEmpty() }) p.runs.firstOrNull()?.let { appendRunProps(it, mark) }
            else if (mark.isNotEmpty()) append("<w:rPr>$mark</w:rPr>")
            // A section break rides on the paragraph that ends the section (CT_PPr: sectPr last).
            f.sectionBreak?.let { appendSectPr(it, sectionHeaders, sectionFooters) }
            append("</w:pPr>")
        }

        private fun StringBuilder.appendRun(r: TextRun, del: Boolean = false) {
            // Inside <w:del>, run text must use <w:delText> instead of <w:t>.
            val tag = if (del) "w:delText" else "w:t"
            append("<w:r>")
            appendRunProps(r)
            // Split on newlines/tabs so we emit <w:br/> and <w:tab/> rather than raw chars.
            val text = r.text
            append("<$tag xml:space=\"preserve\">")
            for (c in text) when (c) {
                '\n' -> append("</$tag><w:br/><$tag xml:space=\"preserve\">")
                '' -> append("</$tag><w:br w:type=\"page\"/><$tag xml:space=\"preserve\">")
                '\t' -> append("</$tag><w:tab/><$tag xml:space=\"preserve\">")
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> if (com.therealityteam.aeropages.format.OfficeXml.isXmlChar(c)) append(c) // control chars would make Word refuse the file
            }
            append("</$tag></w:r>")
        }

        private fun StringBuilder.appendRunProps(r: TextRun, lead: String = "") {
            val f = r.format
            append("<w:rPr>").append(lead)
            appendLook(f)
            // A tracked format change: the properties before it (CT_RPrOriginal), which rejecting it in Word brings back.
            if (f.formatChanged) {
                val prior = StringBuilder().also { sb -> f.before?.let { sb.appendLook(it) } }
                append("""<w:rPrChange w:id="${++revId}"${revAttrs(f)}>""")
                append(if (prior.isEmpty()) "<w:rPr/>" else "<w:rPr>$prior</w:rPr>").append("</w:rPrChange>")
            }
            append("</w:rPr>")
        }

        /** How [f] looks, as CT_RPr's elements in their schema order. */
        private fun StringBuilder.appendLook(f: com.therealityteam.aeropages.model.CharacterFormat) {
            f.fontFamily?.let { append("""<w:rFonts w:ascii="${Ooxml.escape(it)}" w:hAnsi="${Ooxml.escape(it)}"/>""") }
            // Toggles inherit from the paragraph style, so a run that is not bold inside a bold style needs an explicit off.
            if (f.bold) append("<w:b/>") else if (currentStyle?.bold == true) append("""<w:b w:val="0"/>""")
            if (f.italic) append("<w:i/>") else if (currentStyle?.italic == true) append("""<w:i w:val="0"/>""")
            if (f.smallCaps) append("<w:smallCaps/>")
            if (f.strikethrough) append("<w:strike/>")
            f.color?.let { append("""<w:color w:val="${Ooxml.colorToHex(it)}"/>""") }
            f.letterSpacingPt?.let { append("""<w:spacing w:val="${Ooxml.ptToTwips(it)}"/>""") }
            f.fontSizePt?.let {
                val hp = Ooxml.ptToHalfPt(it)
                append("""<w:sz w:val="$hp"/><w:szCs w:val="$hp"/>""")
            }
            f.highlight?.let { append("""<w:highlight w:val="${Ooxml.highlightName(it)}"/>""") }
            if (f.underline) append("""<w:u w:val="single"/>""")
            if (f.superscript) append("""<w:vertAlign w:val="superscript"/>""")
            if (f.subscript) append("""<w:vertAlign w:val="subscript"/>""")
        }

        /** Header/footer references every section repeats, so headers continue across breaks. */
        private var sectionHeaders: List<HfPart> = emptyList()
        private var sectionFooters: List<HfPart> = emptyList()

        private fun StringBuilder.appendSectPr(doc: Document, hdrParts: List<HfPart>, ftrParts: List<HfPart>) =
            appendSectPr(doc.page, hdrParts, ftrParts, doc)

        private fun StringBuilder.appendSectPr(pg: PageSetup, hdrParts: List<HfPart>, ftrParts: List<HfPart>, doc: Document? = null) {
            append("<w:sectPr>")
            hdrParts.forEach { append("""<w:headerReference w:type="${it.type}" r:id="${it.rId}"/>""") }
            ftrParts.forEach { append("""<w:footerReference w:type="${it.type}" r:id="${it.rId}"/>""") }
            append("""<w:pgSz w:w="${Ooxml.ptToTwips(pg.widthPt)}" w:h="${Ooxml.ptToTwips(pg.heightPt)}"/>""")
            append(
                """<w:pgMar w:top="${Ooxml.ptToTwips(pg.marginTopPt)}" """ +
                    """w:right="${Ooxml.ptToTwips(pg.marginRightPt)}" """ +
                    """w:bottom="${Ooxml.ptToTwips(pg.marginBottomPt)}" """ +
                    """w:left="${Ooxml.ptToTwips(pg.marginLeftPt)}" """ +
                    """w:header="${Ooxml.ptToTwips(pg.headerPt)}" w:footer="${Ooxml.ptToTwips(pg.footerPt)}" w:gutter="0"/>"""
            )
            // CT_SectPr requires <w:pgBorders> after <w:pgSz>/<w:pgMar>.
            doc?.pageBorders?.takeIf { !it.none }?.let { append(WordBorders.pageXml(it)) }
            // Line numbers (CT_SectPr: lnNumType after pgBorders, before pgNumType); Word writes the value before the first.
            pg.lineNumbers?.let { n ->
                append("""<w:lnNumType w:countBy="${n.countBy.coerceAtLeast(1)}"""")
                if (n.start != 1) append(""" w:start="${n.start - 1}"""")
                n.distancePt?.let { append(""" w:distance="${Ooxml.ptToTwips(it)}"""") }
                append(""" w:restart="${when (n.restart) { com.therealityteam.aeropages.model.LineNumberRestart.PAGE -> "newPage"; com.therealityteam.aeropages.model.LineNumberRestart.SECTION -> "newSection"; else -> "continuous" }}"/>""")
            }
            // Page numbering (页码格式): pgNumType follows pgBorders, before cols.
            if (pg.pageNumberStart != null || pg.pageNumberFormat != com.therealityteam.aeropages.model.NumFormat.DECIMAL) {
                append("<w:pgNumType")
                if (pg.pageNumberFormat != com.therealityteam.aeropages.model.NumFormat.DECIMAL) append(""" w:fmt="${WordLists.wordFormat(pg.pageNumberFormat)}"""")
                pg.pageNumberStart?.let { append(""" w:start="$it"""") }
                append("/>")
            }
            // Multi-column layout (分栏); w:cols follows pgBorders in CT_SectPr.
            if (pg.columns > 1) append("""<w:cols w:num="${pg.columns}" w:space="${Ooxml.ptToTwips(pg.columnGapPt)}"${if (pg.columnLine) " w:sep=\"1\"" else ""}/>""")
            // Different first page (首页不同); CT_SectPr puts titlePg after cols.
            if (doc?.differentFirstPage == true) append("<w:titlePg/>")
            // The line grid; CT_SectPr puts docGrid after titlePg.
            pg.linePitchPt?.let { append("""<w:docGrid w:type="lines" w:linePitch="${Ooxml.ptToTwips(it)}"/>""") }
            append("</w:sectPr>")
        }

    }

    // --- Static package parts --------------------------------------------------

    /**
     * word/settings.xml (CT_Settings order): Track Changes on (`w:trackRevisions`) and locked, the default tab stops' distance
     * (LibreOffice assumes 1.25 cm without it) and different odd & even page headers.
     */
    private fun settingsXml(doc: Document): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        append("""<w:settings xmlns:w="${Ooxml.W}">""")
        if (doc.trackRevisions) append("<w:trackRevisions/>")
        // Lock Tracking (after trackRevisions, before defaultTabStop), with the password attributes the file came with.
        doc.trackLock?.let { lock ->
            append("""<w:documentProtection w:edit="trackedChanges" w:enforcement="1"""")
            lock.forEach { (k, v) -> if (Regex("""w:\w+""").matches(k)) append(" $k=\"${Ooxml.escape(v)}\"") }
            append("/>")
        }
        append("""<w:defaultTabStop w:val="${Ooxml.ptToTwips(doc.defaultTabPt)}"/>""")
        if (doc.differentOddEven) append("<w:evenAndOddHeaders/>")
        append("</w:settings>")
    }

    private val CONTENT_TYPES_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
${com.therealityteam.aeropages.format.pptx.OoxmlPicture.CONTENT_TYPES}
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
<Override PartName="/word/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
"""

    private val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    private val ROOT_RELS_WITH_CUSTOM = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId99" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/custom-properties" Target="docProps/custom.xml"/>
</Relationships>"""

    /** Custom document properties: carries the watermark text (Word preserves custom props). */
    /** Field-level escaping for the AeroSources encoding (fields '|'-joined, records ';'-joined). */
    internal fun encodeSourceField(s: String): String = s.replace("%", "%25").replace("|", "%7C").replace(";", "%3B")
    internal fun decodeSourceField(s: String): String = s.replace("%3B", ";").replace("%7C", "|").replace("%25", "%")

    private fun buildCustomProps(watermark: String?, sources: List<com.therealityteam.aeropages.model.Source>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        append("""<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">""")
        var pid = 2
        watermark?.let {
            append("""<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="${pid++}" name="AeroWatermark"><vt:lpwstr>${Ooxml.escape(it)}</vt:lpwstr></property>""")
        }
        if (sources.isNotEmpty()) {
            val enc = sources.joinToString(";") { s ->
                "${encodeSourceField(s.author)}|${encodeSourceField(s.title)}|${encodeSourceField(s.year)}"
            }
            append("""<property fmtid="{D5CDD505-2E9C-101B-9397-08002B2CF9AE}" pid="${pid++}" name="AeroSources"><vt:lpwstr>${Ooxml.escape(enc)}</vt:lpwstr></property>""")
        }
        append("</Properties>")
    }

    private val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
</Relationships>"""

    /** styles.xml from the document's style sheet; built-in styles the sheet lacks get their defaults. */
    private fun buildStyles(doc: Document): String = buildString {
        val sheet = doc.styles
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append("\n")
        append("""<w:styles xmlns:w="${Ooxml.W}">""")
        append("<w:docDefaults><w:rPrDefault><w:rPr>")
        // The document's font, else the theme's body font (Calibri; 等线 for Chinese), as Word's own
        // documents have it — with no font at all Word and LibreOffice fall back to Times New Roman.
        val family = sheet.defaultFontFamily
        if (family != null) append("""<w:rFonts w:ascii="${Ooxml.escape(family)}" w:hAnsi="${Ooxml.escape(family)}"/>""")
        else append("""<w:rFonts w:asciiTheme="minorHAnsi" w:eastAsiaTheme="minorEastAsia" w:hAnsiTheme="minorHAnsi" w:cstheme="minorBidi"/>""")
        val dhp = Ooxml.ptToHalfPt(sheet.defaultSizePt)
        append("""<w:sz w:val="$dhp"/><w:szCs w:val="$dhp"/></w:rPr></w:rPrDefault></w:docDefaults>""")
        val defs = LinkedHashMap<String, StyleDef>()
        sheet.styles.values.forEach { defs[it.id] = it }
        ParagraphStyle.entries.forEach { s -> val id = sheet.idOf(s); if (defs.keys.none { it.equals(id, ignoreCase = true) }) defs[id] = StyleSheet.builtin(s) }
        val normalId = sheet.idOf(ParagraphStyle.NORMAL)
        defs.values.forEach { d ->
            val default = if (d.id.equals(normalId, ignoreCase = true)) """ w:default="1"""" else ""
            append("""<w:style w:type="paragraph"$default w:styleId="${Ooxml.escape(d.id)}"><w:name w:val="${Ooxml.escape(d.name)}"/>""")
            d.basedOn?.let { append("""<w:basedOn w:val="${Ooxml.escape(it)}"/>""") }
            append("<w:qFormat/>")
            val pPr = StringBuilder()
            // Headings keep with the paragraph after them and never break, as Word's own heading styles do.
            if (d.outlineLevel != null) pPr.append("<w:keepNext/><w:keepLines/>")
            // Its borders and shading (CT_PPrBase: pBdr, shd before spacing).
            d.borders?.takeIf { !it.none }?.let { pPr.append(WordBorders.paragraphXml(it)) }
            d.shading?.let { pPr.append(WordBorders.shadingXml(it)) }
            if (d.spaceBeforePt != null || d.spaceAfterPt != null || d.lineSpacing != null || d.lineHeightPt != null) {
                pPr.append("<w:spacing")
                d.spaceBeforePt?.let { pPr.append(""" w:before="${Ooxml.ptToTwips(it)}"""") }
                d.spaceAfterPt?.let { pPr.append(""" w:after="${Ooxml.ptToTwips(it)}"""") }
                val height = d.lineHeightPt
                if (height != null) pPr.append(""" w:line="${Ooxml.ptToTwips(height)}" w:lineRule="${if (d.lineExact) "exact" else "atLeast"}"""")
                else d.lineSpacing?.let { pPr.append(""" w:line="${(it * 240f).toInt()}" w:lineRule="auto"""") }
                pPr.append("/>")
            }
            if (d.indentLeftPt != null || d.firstLineIndentPt != null || d.indentRightPt != null) {
                pPr.append(Ooxml.indXml(d.indentLeftPt, d.firstLineIndentPt, d.indentRightPt))
            }
            d.align?.let { pPr.append("""<w:jc w:val="${Ooxml.jcValue(it)}"/>""") }
            d.outlineLevel?.let { pPr.append("""<w:outlineLvl w:val="$it"/>""") }
            if (pPr.isNotEmpty()) append("<w:pPr>").append(pPr).append("</w:pPr>")
            val rPr = StringBuilder()
            d.fontFamily?.let { rPr.append("""<w:rFonts w:ascii="${Ooxml.escape(it)}" w:hAnsi="${Ooxml.escape(it)}" w:eastAsia="${Ooxml.escape(it)}"/>""") }
            d.bold?.let { rPr.append(if (it) "<w:b/><w:bCs/>" else """<w:b w:val="0"/><w:bCs w:val="0"/>""") }
            d.italic?.let { rPr.append(if (it) "<w:i/><w:iCs/>" else """<w:i w:val="0"/><w:iCs w:val="0"/>""") }
            d.color?.let { rPr.append("""<w:color w:val="${Ooxml.colorToHex(it)}"/>""") }
            d.sizePt?.let { val hp = Ooxml.ptToHalfPt(it); rPr.append("""<w:sz w:val="$hp"/><w:szCs w:val="$hp"/>""") }
            if (rPr.isNotEmpty()) append("<w:rPr>").append(rPr).append("</w:rPr>")
            append("</w:style>")
        }
        // A table of figures' entries take Word's "table of figures" style: one LibreOffice does not know runs the
        // entries of its illustration index into one paragraph.
        if (doc.blocks.any { it is com.therealityteam.aeropages.model.TocBlock && it.caption != null } && defs.keys.none { it.equals("TableofFigures", ignoreCase = true) })
            append("""<w:style w:type="paragraph" w:styleId="TableofFigures"><w:name w:val="table of figures"/><w:basedOn w:val="${Ooxml.escape(normalId)}"/><w:next w:val="${Ooxml.escape(normalId)}"/><w:pPr><w:spacing w:after="0"/></w:pPr></w:style>""")
        // An index's entries take Word's index styles (a subentry indented, both hanging; a letter's heading bold).
        if (doc.blocks.any { it is com.therealityteam.aeropages.model.IndexBlock }) {
            fun def(id: String, name: String, body: String) {
                if (defs.keys.none { it.equals(id, ignoreCase = true) })
                    append("""<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="${Ooxml.escape(normalId)}"/><w:next w:val="${Ooxml.escape(normalId)}"/>$body</w:style>""")
            }
            def("Index1", "index 1", """<w:pPr><w:spacing w:after="0"/><w:ind w:left="220" w:hanging="220"/></w:pPr>""")
            def("Index2", "index 2", """<w:pPr><w:spacing w:after="0"/><w:ind w:left="440" w:hanging="220"/></w:pPr>""")
            def("IndexHeading", "index heading", """<w:pPr><w:spacing w:before="120" w:after="0"/></w:pPr><w:rPr><w:b/><w:bCs/></w:rPr>""")
        }
        append("</w:styles>")
    }

}
