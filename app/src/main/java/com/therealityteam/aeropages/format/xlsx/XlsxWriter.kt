package com.therealityteam.aeropages.format.xlsx

import com.therealityteam.aeropages.model.BorderLine
import com.therealityteam.aeropages.model.CachedValue
import com.therealityteam.aeropages.model.Cell
import com.therealityteam.aeropages.model.FormatCode
import com.therealityteam.aeropages.model.NumberFormat
import com.therealityteam.aeropages.model.TextAlign
import com.therealityteam.aeropages.model.Workbook
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal XLSX writer. Emits a valid SpreadsheetML package: content-types, package +
 * workbook relationships, a styles part, the workbook and one worksheet per Sheet.
 * Cell styling (number format, bold/italic/underline, font colour, fill, alignment) is
 * persisted via xl/styles.xml and per-cell `s=` indices. String cells use inlineStr;
 * numbers use <v>; TRUE and FALSE are logical cells (`t="b"`); formulas use <f>, with the
 * value [write]'s `values` gives them beside it.
 */
object XlsxWriter {

    /**
     * Writes [wb] to [out]. [values] gives each formula's value (sheet index, row, column) to keep
     * beside it, as Excel does, so a reader that does not work formulas out — a preview, a script —
     * shows them; an error is left out, for the reader to work out.
     */
    fun write(wb: Workbook, out: OutputStream, values: ((sheetIdx: Int, row: Int, col: Int) -> CachedValue?)? = null) {
        val zip = ZipOutputStream(out)
        val sheets = wb.sheets
        val styles = StyleTable().apply { sheets.forEach { s -> s.cells.values.forEach { indexOf(it) } } }
        // dxf (differential format) per distinct look a conditional format gives, shared across sheets.
        val dxfs = LinkedHashMap<com.therealityteam.aeropages.model.CondStyle, Int>()
        sheets.forEach { s -> s.condFormats.filter { !it.type.scale }.forEach { cf -> dxfs.getOrPut(cf.look) { dxfs.size } } }
        // A filter by colour names its colour by a dxf too: the fill, or the font's colour (none for No Fill / Automatic).
        sheets.forEach { s -> s.autoFilter?.columns?.values?.forEach { f -> f.color?.let { dxfs.getOrPut(filterLook(it)) { dxfs.size } } } }
        // Global chart numbering across all sheets (xl/charts/chartK.xml + drawingK per sheet-with-charts).
        var chartSeq = 0
        val sheetCharts = sheets.map { s -> s.charts.map { ++chartSeq } } // per-sheet chart part numbers
        var drawingSeq = 0
        val sheetDrawing = sheets.map { s -> if (s.charts.isNotEmpty() || s.images.isNotEmpty() || s.shapes.isNotEmpty()) ++drawingSeq else 0 }
        val commentSeq = sheets.map { s -> s.cells.values.any { it.comment != null } }
        // Pictures: one media part each (xl/media/imageN.ext), numbered across the workbook.
        var imageSeq = 0
        // (An SVG goes as a PNG stand-in with the SVG itself beside it, as Excel saves one.)
        val sheetPictures = sheets.map { s -> s.images.map { img -> com.therealityteam.aeropages.format.pptx.OoxmlPicture.of(img.data, img.mime) } }
        val sheetImages = sheetPictures.map { pics -> pics.map { pic -> "image${++imageSeq}.${pic.mainExt}" } }

        // Tables: one part each (xl/tables/tableN.xml), numbered across the workbook as their ids are.
        var tableSeq = 0
        val sheetTables = sheets.map { s -> s.tables.map { ++tableSeq } }

        put(zip, "[Content_Types].xml", com.therealityteam.aeropages.format.OoxmlCore.types(contentTypes(sheets.size, chartSeq, drawingSeq, commentSeq,
            sheetImages.flatten().map { it.substringAfterLast('.') }.toSet() + (if (sheetPictures.flatten().any { it.svg != null }) setOf("svg") else emptySet()), tableSeq)))
        put(zip, "_rels/.rels", com.therealityteam.aeropages.format.OoxmlCore.rels(
            rels("rId1" to ("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" to "xl/workbook.xml"))))
        put(zip, com.therealityteam.aeropages.format.OoxmlCore.PART, String(com.therealityteam.aeropages.format.OoxmlCore.write(wb.props), Charsets.UTF_8))
        val formulas = sheets.any { s -> s.cells.values.any { it.text.startsWith("=") } }
        put(zip, "xl/workbook.xml", workbookXml(sheets.map { it.name }, wb.names, sheets.map { it.printArea }, sheets.map { filterRef(it) }, sheets.map { it.state }, formulas,
            sheets.map { printTitles(it) }, com.therealityteam.aeropages.format.ProtectionXml.xlsxWorkbook(wb)))
        put(zip, "xl/_rels/workbook.xml.rels", workbookRels(sheets.size))
        put(zip, "xl/styles.xml", styles.toXml(dxfs))
        sheets.forEachIndexed { i, sheet ->
            val hasComments = commentSeq[i]
            val drawingId = sheetDrawing[i]
            // Hyperlinks: one external relationship per linked cell (rIdL1..), referenced from <hyperlinks>.
            val links = linkedCells(sheet)
            put(zip, "xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet, styles, dxfs, drawingId != 0, hasComments, links, sheet.tables.size, values?.let { v -> { r: Int, c: Int -> v(i, r, c) } }))
            // Worksheet rels (drawing + comments + hyperlinks + tables).
            if (drawingId != 0 || hasComments || links.isNotEmpty() || sheet.tables.isNotEmpty()) {
                val b = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                if (drawingId != 0) b.append("""<Relationship Id="rIdDrw" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing$drawingId.xml"/>""")
                if (hasComments) {
                    b.append("""<Relationship Id="rIdCmt" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="../comments${i + 1}.xml"/>""")
                    b.append("""<Relationship Id="rIdVml" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/vmlDrawing" Target="../drawings/vmlDrawing${i + 1}.vml"/>""")
                }
                links.forEachIndexed { li, (_, url) ->
                    b.append("""<Relationship Id="rIdL${li + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${esc(url)}" TargetMode="External"/>""")
                }
                sheetTables[i].forEachIndexed { ti, n ->
                    b.append("""<Relationship Id="rIdT${ti + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/table" Target="../tables/table$n.xml"/>""")
                }
                b.append("</Relationships>")
                put(zip, "xl/worksheets/_rels/sheet${i + 1}.xml.rels", b.toString())
            }
            if (hasComments) {
                put(zip, "xl/comments${i + 1}.xml", commentsXml(sheet))
                put(zip, "xl/drawings/vmlDrawing${i + 1}.vml", notesVml(sheet, i + 1))
            }
            sheet.tables.forEachIndexed { ti, t -> put(zip, "xl/tables/table${sheetTables[i][ti]}.xml", tableXml(sheet, t, sheetTables[i][ti], dxfs)) }
            if (drawingId != 0) {
                put(zip, "xl/drawings/drawing$drawingId.xml", drawingXml(sheet.charts.size, sheet.images, sheet.shapes, sheetPictures[i].map { it.svg != null }))
                val rb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                sheet.charts.forEachIndexed { ci, _ ->
                    rb.append("""<Relationship Id="rIdC${ci + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/chart${sheetCharts[i][ci]}.xml"/>""")
                }
                sheetImages[i].forEachIndexed { k, media ->
                    rb.append("""<Relationship Id="rIdI${k + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/$media"/>""")
                    if (sheetPictures[i][k].svg != null) rb.append("""<Relationship Id="rIdS${k + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/${media.substringBeforeLast('.')}.svg"/>""")
                }
                rb.append("</Relationships>")
                sheetPictures[i].forEachIndexed { k, pic ->
                    zip.putNextEntry(ZipEntry("xl/media/${sheetImages[i][k]}")); zip.write(pic.main); zip.closeEntry()
                    pic.svg?.let { svg -> zip.putNextEntry(ZipEntry("xl/media/${sheetImages[i][k].substringBeforeLast('.')}.svg")); zip.write(svg); zip.closeEntry() }
                }
                put(zip, "xl/drawings/_rels/drawing$drawingId.xml.rels", rb.toString())
                sheet.charts.forEachIndexed { ci, chart ->
                    put(zip, "xl/charts/chart${sheetCharts[i][ci]}.xml", com.therealityteam.aeropages.format.pptx.PptxWriter.chartXml(chart, chartRefs(sheet, chart)))
                }
            }
        }
        zip.finish()
    }

    /**
     * A rich run's `<rPr>` as Excel writes one: bold, italic, strike, underline, then always its
     * size and font — the run's own, else the cell's, else Calibri 11 — for a run without them is
     * read in a reader's own default (LibreOffice took them for Cambria and made the cell bold).
     */
    private fun runProps(f: com.therealityteam.aeropages.model.CharacterFormat, cell: com.therealityteam.aeropages.model.CharacterFormat): String = buildString {
        append("<rPr>")
        if (f.bold) append("<b/>"); if (f.italic) append("<i/>"); if (f.strikethrough) append("<strike/>")
        if (f.underline) append("<u/>")
        val size = f.fontSizePt ?: cell.fontSizePt ?: 11f
        append("""<sz val="${if (size == size.toInt().toFloat()) size.toInt().toString() else size.toString()}"/>""")
        (f.color ?: cell.color)?.let { append("""<color rgb="${String.format(java.util.Locale.ROOT, "FF%06X", it and 0xFFFFFF)}"/>""") }
        append("""<rFont val="${esc(f.fontFamily ?: cell.fontFamily ?: "Calibri")}"/>""")
        append("</rPr>")
    }

    /** Formulas binding a chart to its source block (categories, values, names), so Excel keeps the chart live; null for unbound charts. */
    private fun chartRefs(sheet: com.therealityteam.aeropages.model.Sheet, chart: com.therealityteam.aeropages.model.SlideChart): com.therealityteam.aeropages.format.pptx.PptxWriter.ChartRefs? {
        val a1 = com.therealityteam.aeropages.model.A1
        val (r1, c1, r2, c2) = a1.parseRange(chart.sourceRef ?: return null) ?: return null
        val multi = chart.sourceCats && c2 > c1
        val firstData = if (multi) c1 + 1 else c1
        val dataStart = if (chart.sourceHeader) r1 + 1 else r1
        if (dataStart > r2) return null
        val cat = if (multi) a1.absoluteRef(sheet.name, dataStart, c1, r2, c1) else null
        val values = (firstData..c2).map { c -> a1.absoluteRef(sheet.name, dataStart, c, r2, c) }
        val names = (firstData..c2).map { c -> if (chart.sourceHeader) a1.absoluteRef(sheet.name, r1, c, r1, c) else null }
        return com.therealityteam.aeropages.format.pptx.PptxWriter.ChartRefs(cat, values, names)
    }

    /**
     * Functions newer than Excel 2007 carry the `_xlfn.` prefix in the file (FILTER and SORT `_xlfn._xlws.`),
     * or Excel and LibreOffice read them as unknown names (#NAME?). Not NETWORKDAYS.INTL, WORKDAY.INTL or
     * ISO.CEILING: they are in the standard's own list, and LibreOffice knows them only without it.
     */
    private val FUTURE_FUNCTIONS = listOf(
        "UNIQUE", "FILTER", "SORT", "SORTBY", "SEQUENCE", "XLOOKUP", "XMATCH", "IFS", "SWITCH", "MAXIFS", "MINIFS", "TEXTJOIN", "CONCAT", "IFNA", "XOR",
        "STDEV.S", "STDEV.P", "VAR.S", "VAR.P", "MODE.SNGL", "RANK.EQ", "RANK.AVG", "PERCENTILE.INC", "PERCENTILE.EXC", "QUARTILE.INC", "QUARTILE.EXC",
        "COVARIANCE.P", "COVARIANCE.S", "NORM.DIST", "NORM.S.DIST", "NORM.INV", "NORM.S.INV", "FORECAST.LINEAR", "CEILING.MATH", "FLOOR.MATH",
        "CEILING.PRECISE", "FLOOR.PRECISE", "DAYS", "ISOWEEKNUM", "ISFORMULA", "FORMULATEXT",
        "NUMBERVALUE", "UNICHAR", "UNICODE", "COMBINA", "PERMUTATIONA", "TEXTBEFORE", "TEXTAFTER",
    )
    private val XLWS = setOf("FILTER", "SORT")
    private val XLFN = Regex("(?<![A-Za-z0-9_.])(" + FUTURE_FUNCTIONS.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")\\(", RegexOption.IGNORE_CASE)
    private fun xlfn(formula: String): String = XLFN.replace(formula) { m ->
        val f = m.groupValues[1].uppercase()
        (if (f in XLWS) "_xlfn._xlws." else "_xlfn.") + f + "("
    }

    /**
     * The sheet's drawing: anchored graphic frames, one per chart, referencing rIdC1..rIdCn (stacked
     * vertically), then each picture as a one-cell anchor — its cell and offset, its size — showing
     * the media rIdI1..rIdIn.
     */
    private fun drawingXml(chartCount: Int, images: List<com.therealityteam.aeropages.model.SheetImage> = emptyList(),
                           shapes: List<com.therealityteam.aeropages.model.SheetShape> = emptyList(), svgBeside: List<Boolean> = emptyList()): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart">""")
        for (k in 1..chartCount) {
            val rowOff = (k - 1) * 16
            append("""<xdr:twoCellAnchor><xdr:from><xdr:col>5</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$rowOff</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>""")
            append("""<xdr:to><xdr:col>13</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${rowOff + 15}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>""")
            append("""<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="$k" name="Chart $k"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>""")
            append("""<xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm>""")
            append("""<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart r:id="rIdC$k"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>""")
        }
        images.forEachIndexed { k, img ->
            val id = chartCount + k + 1
            val cx = (img.widthPt * EMU_PER_PT).toLong().coerceAtLeast(1); val cy = (img.heightPt * EMU_PER_PT).toLong().coerceAtLeast(1)
            append("""<xdr:oneCellAnchor><xdr:from><xdr:col>${img.col}</xdr:col><xdr:colOff>${(img.dxPt * EMU_PER_PT).toLong()}</xdr:colOff>""")
            append("""<xdr:row>${img.row}</xdr:row><xdr:rowOff>${(img.dyPt * EMU_PER_PT).toLong()}</xdr:rowOff></xdr:from><xdr:ext cx="$cx" cy="$cy"/>""")
            val name = esc(img.name.ifBlank { "Picture $id" })
            val alt = com.therealityteam.aeropages.format.pptx.OoxmlAltText
            val ext = alt.decorativeExt(img.decorative)
            val cNvPr = if (ext.isEmpty()) """<xdr:cNvPr id="$id" name="$name"${alt.descr(img.altText)}/>""" else """<xdr:cNvPr id="$id" name="$name"${alt.descr(img.altText)}>$ext</xdr:cNvPr>"""
            append("""<xdr:pic><xdr:nvPicPr>$cNvPr<xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>""")
            val blip = com.therealityteam.aeropages.format.pptx.OoxmlPicture.blip("rIdI${k + 1}", if (svgBeside.getOrElse(k) { false }) "rIdS${k + 1}" else null)
            append("""<xdr:blipFill>$blip${com.therealityteam.aeropages.format.PictureCrop.srcRect(img)}<a:stretch><a:fillRect/></a:stretch></xdr:blipFill>""")
            append("""<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>${com.therealityteam.aeropages.format.pptx.PptxWriter.effectXml(img.shadow)}</xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>""")
        }
        // Shapes, text boxes and lines, each anchored to its cell like a picture.
        shapes.forEachIndexed { k, sh ->
            val id = chartCount + images.size + k + 1
            append("""<xdr:oneCellAnchor><xdr:from><xdr:col>${sh.col}</xdr:col><xdr:colOff>${(sh.dxPt * EMU_PER_PT).toLong()}</xdr:colOff>""")
            append("""<xdr:row>${sh.row}</xdr:row><xdr:rowOff>${(sh.dyPt * EMU_PER_PT).toLong()}</xdr:rowOff></xdr:from>""")
            append("""<xdr:ext cx="${(sh.widthPt * EMU_PER_PT).toLong().coerceAtLeast(0)}" cy="${(sh.heightPt * EMU_PER_PT).toLong().coerceAtLeast(0)}"/>""")
            append(shapeXml(sh, id))
            append("<xdr:clientData/></xdr:oneCellAnchor>")
        }
        append("</xdr:wsDr>")
    }

    /**
     * A shape as Excel's drawing holds one — `xdr:sp` with its geometry, fill, outline, rotation and
     * flips and its text as DrawingML paragraphs (a text box flagged txBox), a line `xdr:cxnSp`.
     */
    private fun shapeXml(sh: com.therealityteam.aeropages.model.SheetShape, id: Int): String = buildString {
        val s = sh.shape
        val name = esc(sh.name.ifBlank { "Shape $id" })
        val xfrm = buildString {
            append("<a:xfrm")
            if (s.rotationDeg != 0f) append(""" rot="${Math.round(s.rotationDeg * 60000)}"""")
            if (s.flipH) append(""" flipH="1"""")
            if (s.flipV) append(""" flipV="1"""")
            append("""><a:off x="0" y="0"/><a:ext cx="${(sh.widthPt * EMU_PER_PT).toLong()}" cy="${(sh.heightPt * EMU_PER_PT).toLong()}"/></a:xfrm>""")
        }
        val outline = com.therealityteam.aeropages.format.pptx.PptxWriter.outlineXml(s, "<a:ln><a:noFill/></a:ln>")
        if (s.type == com.therealityteam.aeropages.model.ShapeType.LINE) {
            append("""<xdr:cxnSp macro=""><xdr:nvCxnSpPr><xdr:cNvPr id="$id" name="$name"/><xdr:cNvCxnSpPr/></xdr:nvCxnSpPr>""")
            append("""<xdr:spPr>$xfrm<a:prstGeom prst="line"><a:avLst/></a:prstGeom>$outline${com.therealityteam.aeropages.format.pptx.PptxWriter.effectXml(s)}</xdr:spPr></xdr:cxnSp>""")
            return@buildString
        }
        val fillXml = com.therealityteam.aeropages.format.pptx.PptxWriter.fillXml(s)
        val txBox = if (s.type == com.therealityteam.aeropages.model.ShapeType.TEXTBOX) """ txBox="1"""" else ""
        append("""<xdr:sp macro="" textlink=""><xdr:nvSpPr><xdr:cNvPr id="$id" name="$name"/><xdr:cNvSpPr$txBox/></xdr:nvSpPr>""")
        append("""<xdr:spPr>$xfrm${com.therealityteam.aeropages.format.pptx.PptxWriter.geometryXml(s)}$fillXml$outline${com.therealityteam.aeropages.format.pptx.PptxWriter.effectXml(s)}</xdr:spPr>""")
        val anchor = when (s.anchor) {
            com.therealityteam.aeropages.model.ShapeAnchor.TOP -> "t"
            com.therealityteam.aeropages.model.ShapeAnchor.BOTTOM -> "b"
            else -> "ctr"
        }
        append("""<xdr:txBody><a:bodyPr vertOverflow="clip" wrap="square" rtlCol="0" anchor="$anchor"/><a:lstStyle/>""")
        append(com.therealityteam.aeropages.format.pptx.PptxWriter.textParagraphs(s.box))
        append("</xdr:txBody></xdr:sp>")
    }

    /** Cell comments part: each note's author once in `authors` (an unknown one ""), then the notes in cell order. */
    private fun commentsXml(sheet: com.therealityteam.aeropages.model.Sheet): String = buildString {
        val notes = sheet.cells.entries.filter { it.value.comment != null }.sortedBy { it.key }
        val authors = notes.map { it.value.commentAuthor.orEmpty() }.distinct()
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<comments xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><authors>""")
        authors.forEach { append("<author>${esc(it)}</author>") }
        append("</authors><commentList>")
        notes.forEach { (k, cell) ->
            val ref = Workbook.colLabel((k % 1000L).toInt()) + ((k / 1000L).toInt() + 1)
            append("""<comment ref="$ref" authorId="${authors.indexOf(cell.commentAuthor.orEmpty())}"><text><r><t xml:space="preserve">${esc(cell.comment!!)}</t></r></text></comment>""")
        }
        append("</commentList></comments>")
    }

    /**
     * The notes' VML shapes (Excel's legacy drawing for notes): one hidden note box per note, anchored beside its cell
     * (`x:Anchor`: left column and offset, top row and offset, right column and offset, bottom row and offset), as Excel writes them.
     */
    private fun notesVml(sheet: com.therealityteam.aeropages.model.Sheet, sheetNumber: Int): String = buildString {
        append("""<xml xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel">""")
        append("""<o:shapelayout v:ext="edit"><o:idmap v:ext="edit" data="$sheetNumber"/></o:shapelayout>""")
        append("""<v:shapetype id="_x0000_t202" coordsize="21600,21600" o:spt="202" path="m,l,21600r21600,l21600,xe"><v:stroke joinstyle="miter"/><v:path gradientshapeok="t" o:connecttype="rect"/></v:shapetype>""")
        var id = sheetNumber * 1024 + 1
        sheet.cells.entries.filter { it.value.comment != null }.sortedBy { it.key }.forEach { (k, cell) ->
            val r = (k / 1000L).toInt(); val c = (k % 1000L).toInt()
            val shown = if (cell.commentShown) "visible" else "hidden"
            append("""<v:shape id="_x0000_s${id++}" type="#_x0000_t202" style="position:absolute;margin-left:59.25pt;margin-top:1.5pt;width:108pt;height:59.25pt;z-index:1;visibility:$shown" fillcolor="#ffffe1" o:insetmode="auto">""")
            append("""<v:fill color2="#ffffe1"/><v:shadow on="t" color="black" obscured="t"/><v:path o:connecttype="none"/>""")
            append("""<v:textbox style="mso-direction-alt:auto"><div style="text-align:left"></div></v:textbox>""")
            append("""<x:ClientData ObjectType="Note"><x:MoveWithCells/><x:SizeWithCells/>""")
            append("""<x:Anchor>${c + 1}, 15, ${maxOf(0, r - 1)}, 10, ${c + 3}, 15, ${r + 3}, 4</x:Anchor><x:AutoFill>False</x:AutoFill><x:Row>$r</x:Row><x:Column>$c</x:Column>${if (cell.commentShown) "<x:Visible/>" else ""}</x:ClientData>""")
            append("</v:shape>")
        }
        append("</xml>")
    }

    // --- styles -----------------------------------------------------------------

    /** Builds the dedup'd font/fill/border/numFmt/xf tables and assigns a style index per cell. */
    private class StyleTable {
        private data class Font(val bold: Boolean, val italic: Boolean, val underline: Boolean, val color: String?, val size: Float, val family: String? = null, val strike: Boolean = false)
        private data class Border(val top: BorderLine?, val bottom: BorderLine?, val left: BorderLine?, val right: BorderLine?)
        /** A cellXfs entry; [align] null = General (no `horizontal`). */
        private data class Xf(val numFmtId: Int, val fontId: Int, val fillId: Int, val borderId: Int, val align: TextAlign?, val vAlign: com.therealityteam.aeropages.model.VAlign, val wrap: Boolean, val unlocked: Boolean = false,
            val rotation: Int = 0, val stacked: Boolean = false, val indent: Int = 0,
            val spread: com.therealityteam.aeropages.model.CellSpread? = null, val shrink: Boolean = false)

        private val fonts = LinkedHashMap<Font, Int>().apply { put(Font(false, false, false, null, 11f), 0) }
        private val fills = LinkedHashMap<String, Int>() // colourHex -> fillId (fillId 0,1 reserved)
        private val borders = LinkedHashMap<Border, Int>().apply { put(Border(null, null, null, null), 0) }
        private val xfs = LinkedHashMap<Xf, Int>().apply { put(Xf(0, 0, 0, 0, null, com.therealityteam.aeropages.model.VAlign.MIDDLE, false), 0) }
        private val customNumFmts = LinkedHashMap<String, Int>() // code -> id (>=164)

        private fun numFmtId(c: Cell): Int {
            val d = c.decimals
            fun custom(code: String) = customNumFmts.getOrPut(code) { 164 + customNumFmts.size }
            fun frac(n: Int) = if (n > 0) "." + "0".repeat(n) else ""
            c.formatCode?.let { code -> return FormatCode.builtInId(code) ?: custom(code) }
            return when (c.numberFormat) {
                NumberFormat.GENERAL -> 0
                NumberFormat.NUMBER2 -> if (d < 0 || d == 2) 2 else custom("0${frac(d)}")
                // The plain percentage shows one decimal: 9 (0%) and 10 (0.00%) only when those are the places.
                NumberFormat.PERCENT -> when (d) { 0 -> 9; 2 -> 10; else -> custom("0${frac(if (d < 0) 1 else d)}%") }
                NumberFormat.THOUSANDS -> if (d <= 0) 3 else custom("#,##0${frac(d)}")
                NumberFormat.DATE -> 14
                NumberFormat.TIME -> 20
                NumberFormat.DATE_TIME -> 22
                NumberFormat.SCIENTIFIC -> if (d < 0 || d == 2) 11 else custom("0${frac(d)}E+00")
                NumberFormat.CURRENCY_CNY -> custom("\"¥\"#,##0${frac(if (d >= 0) d else 2)}")
                NumberFormat.CURRENCY_USD -> custom("\"$\"#,##0${frac(if (d >= 0) d else 2)}")
            }
        }
        private fun fontId(c: Cell): Int {
            val f = c.format
            val key = Font(f.bold, f.italic, f.underline, f.color?.let { hex(it) }, f.fontSizePt ?: 11f, f.fontFamily, f.strikethrough)
            return fonts.getOrPut(key) { fonts.size }
        }
        private fun fillId(c: Cell): Int {
            val hl = c.format.highlight ?: return 0
            return fills.getOrPut(hex(hl)) { fills.size + 2 } // 0=none,1=gray125
        }
        private fun borderId(c: Cell): Int {
            if (!c.bTop && !c.bBottom && !c.bLeft && !c.bRight) return 0
            return borders.getOrPut(Border(c.borderTop, c.borderBottom, c.borderLeft, c.borderRight)) { borders.size }
        }

        /** Returns the cellXfs index for [c] (0 = plain). */
        fun indexOf(c: Cell): Int {
            val xf = Xf(numFmtId(c), fontId(c), fillId(c), borderId(c), c.align, c.vAlign, c.wrap, c.unlocked, c.rotation, c.stacked, c.indent, c.spread, c.shrink)
            if (xf == Xf(0, 0, 0, 0, null, com.therealityteam.aeropages.model.VAlign.MIDDLE, false)) return 0
            return xfs.getOrPut(xf) { xfs.size }
        }

        fun toXml(dxfs: Map<com.therealityteam.aeropages.model.CondStyle, Int> = emptyMap()): String = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">""")
            if (customNumFmts.isNotEmpty()) {
                append("""<numFmts count="${customNumFmts.size}">""")
                customNumFmts.forEach { (code, id) -> append("""<numFmt numFmtId="$id" formatCode="${esc(code)}"/>""") }
                append("</numFmts>")
            }
            append("""<fonts count="${fonts.size}">""")
            fonts.keys.forEach { f ->
                append("<font>")
                if (f.bold) append("<b/>"); if (f.italic) append("<i/>"); if (f.strike) append("<strike/>"); if (f.underline) append("<u/>")
                append("""<sz val="${if (f.size == f.size.toInt().toFloat()) f.size.toInt().toString() else f.size.toString()}"/>""")
                f.color?.let { append("""<color rgb="FF$it"/>""") }
                append("""<name val="${esc(f.family ?: "Calibri")}"/></font>""")
            }
            append("</fonts>")
            append("""<fills count="${fills.size + 2}"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill>""")
            fills.keys.forEach { c -> append("""<fill><patternFill patternType="solid"><fgColor rgb="FF$c"/></patternFill></fill>""") }
            append("</fills>")
            append("""<borders count="${borders.size}">""")
            borders.keys.forEach { b ->
                append("<border>")
                // CT_Border's order: left, right, top, bottom, diagonal.
                fun side(tag: String, line: BorderLine?) = if (line == null) "<$tag/>"
                    else """<$tag style="${line.style.xlsx}">""" + (line.color?.let { """<color rgb="FF${hex(it)}"/>""" } ?: """<color auto="1"/>""") + "</$tag>"
                append(side("left", b.left)); append(side("right", b.right)); append(side("top", b.top)); append(side("bottom", b.bottom))
                append("<diagonal/></border>")
            }
            append("</borders>")
            // The Normal style sits in the middle as the app's cells do: LibreOffice takes it (not the first
            // cellXfs entry) for the cells that name no style.
            append("""<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" applyAlignment="1"><alignment vertical="center"/></xf></cellStyleXfs>""")
            append("""<cellXfs count="${xfs.size}">""")
            xfs.keys.forEach { xf ->
                val h = when (xf.spread) {
                    com.therealityteam.aeropages.model.CellSpread.FILL -> "fill"
                    com.therealityteam.aeropages.model.CellSpread.CENTER_ACROSS -> "centerContinuous"
                    com.therealityteam.aeropages.model.CellSpread.DISTRIBUTED -> "distributed"
                    null -> when (xf.align) { TextAlign.LEFT -> "left"; TextAlign.CENTER -> "center"; TextAlign.RIGHT -> "right"; TextAlign.JUSTIFY -> "justify"; null -> null }
                }
                // Excel's own default is the bottom: the app's middle (a new cell's) is written out, bottom left unsaid.
                val v = when (xf.vAlign) { com.therealityteam.aeropages.model.VAlign.TOP -> "top"; com.therealityteam.aeropages.model.VAlign.MIDDLE -> "center"; else -> null }
                append("""<xf numFmtId="${xf.numFmtId}" fontId="${xf.fontId}" fillId="${xf.fillId}" borderId="${xf.borderId}" xfId="0"""")
                append(""" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"""")
                // textRotation: counterclockwise 1–90 as it is, clockwise 90 + the degrees, 255 stacked.
                val turn = if (xf.stacked) 255 else if (xf.rotation > 0) xf.rotation else if (xf.rotation < 0) 90 - xf.rotation else 0
                val hasAlign = h != null || v != null || xf.wrap || turn != 0 || xf.indent > 0 || xf.shrink
                if (hasAlign) append(""" applyAlignment="1"""")
                if (xf.unlocked) append(""" applyProtection="1"""")
                if (hasAlign || xf.unlocked) {
                    append(">")
                    if (hasAlign) {
                        append("<alignment")
                        if (h != null) append(""" horizontal="$h"""")
                        if (v != null) append(""" vertical="$v"""")
                        if (turn != 0) append(""" textRotation="$turn"""")
                        if (xf.wrap) append(""" wrapText="1"""")
                        if (xf.indent > 0) append(""" indent="${xf.indent}"""")
                        if (xf.shrink) append(""" shrinkToFit="1"""")
                        append("/>")
                    }
                    // CT_Xf: protection follows alignment. locked="0" is what lets a cell be edited on a protected sheet.
                    if (xf.unlocked) append("""<protection locked="0"/>""")
                    append("</xf>")
                } else append("/>")
            }
            append("</cellXfs>")
            append("""<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>""")
            if (dxfs.isNotEmpty()) {
                append("""<dxfs count="${dxfs.size}">""")
                // CT_Dxf: font, then fill — a solid fill with its colour in fgColor and bgColor both, as Excel and LibreOffice
                // write it (a conditional format reads bgColor, a filter by colour fgColor); CT_Font: b, i, strike, u, color.
                dxfs.keys.forEach { st ->
                    append("<dxf>")
                    if (st.bold != null || st.italic != null || st.strike != null || st.underline != null || st.font != null) {
                        append("<font>")
                        st.bold?.let { append(if (it) "<b/>" else """<b val="0"/>""") }
                        st.italic?.let { append(if (it) "<i/>" else """<i val="0"/>""") }
                        st.strike?.let { append(if (it) "<strike/>" else """<strike val="0"/>""") }
                        st.underline?.let { append(if (it) "<u/>" else """<u val="none"/>""") }
                        st.font?.let { append("""<color rgb="FF${hex(it)}"/>""") }
                        append("</font>")
                    }
                    st.fill?.let { append("""<fill><patternFill patternType="solid"><fgColor rgb="FF${hex(it)}"/><bgColor rgb="FF${hex(it)}"/></patternFill></fill>""") }
                    append("</dxf>")
                }
                append("</dxfs>")
            }
            append("</styleSheet>")
        }
    }

    private fun contentTypes(sheetCount: Int, chartCount: Int = 0, drawingCount: Int = 0, commentSheets: List<Boolean> = emptyList(), imageExts: Set<String> = emptySet(), tableCount: Int = 0): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        if (commentSheets.any { it }) append("""<Default Extension="vml" ContentType="application/vnd.openxmlformats-officedocument.vmlDrawing"/>""")
        for (ext in imageExts) {
            val type = when (ext) { "jpg" -> "image/jpeg"; "emf" -> "image/x-emf"; "wmf" -> "image/x-wmf"; "svg" -> "image/svg+xml"; else -> "image/$ext" }
            append("""<Default Extension="$ext" ContentType="$type"/>""")
        }
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        }
        for (k in 1..chartCount) append("""<Override PartName="/xl/charts/chart$k.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>""")
        for (d in 1..drawingCount) append("""<Override PartName="/xl/drawings/drawing$d.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""")
        commentSheets.forEachIndexed { i, has ->
            if (has) append("""<Override PartName="/xl/comments${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.comments+xml"/>""")
        }
        for (t in 1..tableCount) append("""<Override PartName="/xl/tables/table$t.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.table+xml"/>""")
        append("</Types>")
    }

    /** [sheet]'s print titles as Excel names them, without the sheet: `$1:$2`, `$A:$B`, or both; null without any. */
    private fun printTitles(sheet: com.therealityteam.aeropages.model.Sheet): String? {
        val parts = ArrayList<String>()
        sheet.print.titleCols?.let { parts.add("$${Workbook.colLabel(it.first)}:$${Workbook.colLabel(it.last)}") }
        sheet.print.titleRows?.let { parts.add("$${it.first + 1}:$${it.last + 1}") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun workbookXml(names: List<String>, definedNames: Map<String, String> = emptyMap(), printAreas: List<com.therealityteam.aeropages.model.PrintArea?> = emptyList(), filterRefs: List<String?> = emptyList(),
                            states: List<com.therealityteam.aeropages.model.SheetState> = emptyList(), formulas: Boolean = false,
                            titles: List<String?> = emptyList(), protection: String = ""): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        // A hidden first sheet: Excel opens on the first one shown (it must be a visible sheet).
        val firstShown = states.indexOfFirst { it == com.therealityteam.aeropages.model.SheetState.VISIBLE }
        append(protection)   // CT_Workbook: workbookProtection comes before bookViews
        if (firstShown > 0) append("""<bookViews><workbookView activeTab="$firstShown" firstSheet="$firstShown"/></bookViews>""")
        append("<sheets>")
        names.forEachIndexed { i, n ->
            val state = states.getOrNull(i)?.takeIf { it != com.therealityteam.aeropages.model.SheetState.VISIBLE }?.let { """ state="${it.xlsx}"""" } ?: ""
            append("""<sheet name="${esc(n)}" sheetId="${i + 1}"$state r:id="rId${i + 2}"/>""")
        }
        append("</sheets>")
        if (definedNames.isNotEmpty() || printAreas.any { it != null } || filterRefs.any { it != null } || titles.any { it != null }) {
            append("<definedNames>")
            // Excel pairs every autoFilter with a hidden _FilterDatabase name.
            filterRefs.forEachIndexed { i, ref ->
                if (ref != null) append("""<definedName name="_xlnm._FilterDatabase" localSheetId="$i" hidden="1">${esc(quoteSheetName(names[i]) + "!" + ref.replace(Regex("([A-Z]+)(\\d+)"), "\\$$1\\$$2"))}</definedName>""")
            }
            // Print areas are Excel's built-in `_xlnm.Print_Area`, scoped to their sheet.
            printAreas.forEachIndexed { i, pa ->
                if (pa != null) {
                    val ref = "${quoteSheetName(names[i])}!$${Workbook.colLabel(pa.c1)}$${pa.r1 + 1}:$${Workbook.colLabel(pa.c2)}$${pa.r2 + 1}"
                    append("""<definedName name="_xlnm.Print_Area" localSheetId="$i">${esc(ref)}</definedName>""")
                }
            }
            // Rows and columns repeated on every printed page: `_xlnm.Print_Titles`, scoped to their sheet.
            titles.forEachIndexed { i, t ->
                if (t != null) append("""<definedName name="_xlnm.Print_Titles" localSheetId="$i">${esc(t.split(',').joinToString(",") { quoteSheetName(names[i]) + "!" + it })}</definedName>""")
            }
            definedNames.forEach { (n, ref) -> append("""<definedName name="${esc(n)}">${esc(ref)}</definedName>""") }
            append("</definedNames>")
        }
        // Formulas' values are kept beside them for readers that do not work them out; Excel works them out afresh.
        if (formulas) append("""<calcPr fullCalcOnLoad="1"/>""")
        append("</workbook>")
    }

    /** A formula's number as Excel keeps it: whole numbers plainly, others to the digits that read back the same. */
    private fun cachedNumber(d: Double): String =
        if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    /** Sheet names with anything but letters/digits/underscores must be quoted in references. */
    private fun quoteSheetName(name: String): String =
        if (name.all { it.isLetterOrDigit() || it == '_' } && name.firstOrNull()?.isDigit() != true) name else "'" + name.replace("'", "''") + "'"

    /** The AutoFilter's range — or, for rows a filter hid without one, the used range (`A1:D20`) — else null. */
    private fun filterRef(sheet: com.therealityteam.aeropages.model.Sheet): String? {
        // A table's filter is written in its part; the sheet's may not reach into a table.
        sheet.autoFilter?.let { af ->
            if (sheet.tables.any { it.intersects(af.r1, af.c1, af.r2, af.c2) }) return null
            return com.therealityteam.aeropages.model.A1.rangeRef(af.r1, af.c1, af.r2, af.c2)
        }
        if (sheet.hiddenRows.isEmpty() || sheet.cells.isEmpty()) return null
        val maxRow = sheet.cells.keys.maxOf { (it / 1000L).toInt() }
        val maxCol = sheet.cells.keys.maxOf { (it % 1000L).toInt() }
        if (sheet.tables.any { it.intersects(0, 0, maxRow, maxCol) }) return null
        return "A1:${Workbook.colLabel(maxCol)}${maxRow + 1}"
    }

    /**
     * A table part: its name, range, header and total rows; each column's name and what the total
     * row shows under it — a SUBTOTAL of the column from Excel's list, a label, or a formula of its
     * own; the filter (with the sheet's criteria when the sheet's filter is the table's); the style.
     */
    private fun tableXml(sheet: com.therealityteam.aeropages.model.Sheet, t: com.therealityteam.aeropages.model.SheetTable, id: Int,
                         dxfs: Map<com.therealityteam.aeropages.model.CondStyle, Int> = emptyMap()): String = buildString {
        val a1 = com.therealityteam.aeropages.model.A1
        fun b(v: Boolean) = if (v) "1" else "0"
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<table xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" id="$id" name="${esc(t.name)}" displayName="${esc(t.name)}" ref="${a1.ref(t.r1, t.c1)}:${a1.ref(t.r2, t.c2)}"""")
        if (!t.headerRow) append(""" headerRowCount="0"""")
        append(if (t.totalsRow) """ totalsRowCount="1">""" else """ totalsRowShown="0">""")
        if (t.filterButton && t.headerRow) {
            val af = sheet.autoFilter?.takeIf { it.r1 == t.r1 && it.c1 == t.c1 && it.r2 == t.dataR2 && it.c2 == t.c2 }
            append(autoFilterXml("${a1.ref(t.r1, t.c1)}:${a1.ref(t.dataR2, t.c2)}", af, dxfs))
        }
        val names = t.columnNames(sheet)
        append("""<tableColumns count="${names.size}">""")
        names.forEachIndexed { i, n ->
            append("""<tableColumn id="${i + 1}" name="${esc(n)}"""")
            val total = if (t.totalsRow) sheet.cell(t.r2, t.c1 + i)?.text?.takeIf { it.isNotEmpty() } else null
            when {
                total == null -> append("/>")
                !total.startsWith("=") -> append(""" totalsRowLabel="${esc(total)}"/>""")
                else -> {
                    val fn = totalFunction(total, n, t)
                    if (fn != null) append(""" totalsRowFunction="$fn"/>""")
                    else append(""" totalsRowFunction="custom"><totalsRowFormula>${esc(xlfn(com.therealityteam.aeropages.model.StructuredRefs.toFileForm(total.substring(1), sheet, t.r2, t.c1 + i)))}</totalsRowFormula></tableColumn>""")
                }
            }
        }
        append("</tableColumns>")
        append("<tableStyleInfo")
        t.style?.let { append(""" name="${esc(it)}"""") }
        append(""" showFirstColumn="${b(t.firstColumn)}" showLastColumn="${b(t.lastColumn)}" showRowStripes="${b(t.bandedRows)}" showColumnStripes="${b(t.bandedCols)}"/>""")
        append("</table>")
    }

    private val SUBTOTAL = Regex("""^=SUBTOTAL\((\d+),\s*(.+?)\s*\)$""", RegexOption.IGNORE_CASE)

    /** Excel's name for a total row's `=SUBTOTAL(109,[Sales])` under column [column] of [t] (sum, average …); null for any other formula. */
    private fun totalFunction(formula: String, column: String, t: com.therealityteam.aeropages.model.SheetTable): String? {
        val m = SUBTOTAL.matchEntire(formula.trim()) ?: return null
        val arg = m.groupValues[2]
        val ref = com.therealityteam.aeropages.model.StructuredRefs.find(arg).singleOrNull()?.takeIf { it.start == 0 && it.end == arg.length } ?: return null
        if (ref.table != null && !ref.table.equals(t.name, ignoreCase = true)) return null
        if (ref.items.isNotEmpty() && ref.items != setOf(com.therealityteam.aeropages.model.StructuredRefs.Item.DATA)) return null
        if (ref.col2 != null || ref.col1?.trim()?.equals(column, ignoreCase = true) != true) return null
        return when (m.groupValues[1].toInt()) {
            101 -> "average"; 102 -> "countNums"; 103 -> "count"; 104 -> "max"; 105 -> "min"; 107 -> "stdDev"; 109 -> "sum"; 110 -> "var"
            else -> null
        }
    }

    /** The dxf a filter by colour points at: the fill, or the font's colour; empty for No Fill / Automatic. */
    private fun filterLook(c: com.therealityteam.aeropages.model.ColorFilter) =
        if (c.font) com.therealityteam.aeropages.model.CondStyle(font = c.color) else com.therealityteam.aeropages.model.CondStyle(fill = c.color)

    /**
     * `<autoFilter ref>` with a `<filterColumn>` per filtered column (colId counts from the range's first column): the
     * values it shows (`blank="1"` when blanks show too); or one or two custom filters (`and="1"` unless joined by Or) —
     * Excel's comparison operators, and `*text*` / `text*` / `*text` for contains / begins / ends; or `top10`, a
     * `dynamicFilter`, or a `colorFilter` naming its colour's dxf ([dxfs]).
     */
    private fun autoFilterXml(ref: String, af: com.therealityteam.aeropages.model.AutoFilter?,
                              dxfs: Map<com.therealityteam.aeropages.model.CondStyle, Int> = emptyMap()): String = buildString {
        if (af == null || af.columns.isEmpty()) { append("""<autoFilter ref="$ref"/>"""); return@buildString }
        append("""<autoFilter ref="$ref">""")
        fun custom(cond: com.therealityteam.aeropages.model.FilterCondition) {
            val v = cond.value
            val (op, value) = when (cond.op) {
                com.therealityteam.aeropages.model.FilterCondition.Op.EQUALS -> null to v
                com.therealityteam.aeropages.model.FilterCondition.Op.NOT_EQUALS -> "notEqual" to v
                com.therealityteam.aeropages.model.FilterCondition.Op.CONTAINS -> null to "*$v*"
                com.therealityteam.aeropages.model.FilterCondition.Op.NOT_CONTAINS -> "notEqual" to "*$v*"
                com.therealityteam.aeropages.model.FilterCondition.Op.BEGINS_WITH -> null to "$v*"
                com.therealityteam.aeropages.model.FilterCondition.Op.ENDS_WITH -> null to "*$v"
                com.therealityteam.aeropages.model.FilterCondition.Op.NOT_BEGINS_WITH -> "notEqual" to "$v*"
                com.therealityteam.aeropages.model.FilterCondition.Op.NOT_ENDS_WITH -> "notEqual" to "*$v"
                com.therealityteam.aeropages.model.FilterCondition.Op.GREATER -> "greaterThan" to v
                com.therealityteam.aeropages.model.FilterCondition.Op.GREATER_OR_EQUAL -> "greaterThanOrEqual" to v
                com.therealityteam.aeropages.model.FilterCondition.Op.LESS -> "lessThan" to v
                com.therealityteam.aeropages.model.FilterCondition.Op.LESS_OR_EQUAL -> "lessThanOrEqual" to v
            }
            append("<customFilter")
            if (op != null) append(""" operator="$op"""")
            append(""" val="${esc(value)}"/>""")
        }
        for ((col, f) in af.columns.toSortedMap()) {
            append("""<filterColumn colId="${col - af.c1}">""")
            val cond = f.condition
            val color = f.color
            val top = f.top
            val dynamic = f.dynamic
            when {
                color != null -> {
                    append("""<colorFilter dxfId="${dxfs[filterLook(color)] ?: 0}"""")
                    if (color.font) append(""" cellColor="0"""")
                    append("/>")
                }
                top != null -> {
                    append("<top10")
                    if (!top.top) append(""" top="0"""")
                    if (top.percent) append(""" percent="1"""")
                    val count = if (top.count % 1.0 == 0.0) top.count.toLong().toString() else top.count.toString()
                    append(""" val="$count"/>""")
                }
                dynamic != null -> append("""<dynamicFilter type="${dynamic.excel}"/>""")
                cond != null -> {
                    val second = f.condition2
                    append(if (second != null && !f.or) """<customFilters and="1">""" else "<customFilters>")
                    custom(cond)
                    second?.let { custom(it) }
                    append("</customFilters>")
                }
                else -> {
                    append(if (f.blanks) """<filters blank="1">""" else "<filters>")
                    f.values?.sorted()?.forEach { append("""<filter val="${esc(it)}"/>""") }
                    append("</filters>")
                }
            }
            append("</filterColumn>")
        }
        append("</autoFilter>")
    }

    /** (cell key, url) of every linked cell, in sheet order. */
    /** The cells linking to an address — each one relationship (a link to a place in this workbook needs none). */
    private fun linkedCells(sheet: com.therealityteam.aeropages.model.Sheet): List<Pair<Long, String>> =
        sheet.cells.entries.filter { it.value.link != null && com.therealityteam.aeropages.model.CellLinks.place(it.value.link) == null }
            .sortedBy { it.key }.map { it.key to it.value.link!! }

    /** The cells linking to a place in this workbook, and that place (Excel's hyperlink `location`). */
    private fun placeLinks(sheet: com.therealityteam.aeropages.model.Sheet): List<Pair<Long, String>> =
        sheet.cells.entries.mapNotNull { e -> com.therealityteam.aeropages.model.CellLinks.place(e.value.link)?.let { e.key to it } }.sortedBy { it.first }

    private fun workbookRels(sheetCount: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        append("""<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
        for (i in 1..sheetCount) {
            append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>""")
        }
        append("</Relationships>")
    }

    /** Excel's default column width (in the file's units, [XLSX_DEFAULT_COL_WIDTH]) and row height (points) — factors scale these. */
    private const val DEF_COL_W = XLSX_DEFAULT_COL_WIDTH
    private const val DEF_ROW_H = 15.0

    private fun sheetXml(sheet: com.therealityteam.aeropages.model.Sheet, styles: StyleTable, dxfs: Map<com.therealityteam.aeropages.model.CondStyle, Int> = emptyMap(), hasDrawing: Boolean = false, hasComments: Boolean = false, links: List<Pair<Long, String>> = emptyList(), tableCount: Int = 0,
                         values: ((row: Int, col: Int) -> CachedValue?)? = null): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        val filter = filterRef(sheet)
        // CT_SheetPr: the filter mode, then the tab's colour, then fitting the print to pages.
        val fit = sheet.print.fits
        if (filter != null || sheet.tabColor != null || fit) {
            append("<sheetPr")
            if (filter != null) append(""" filterMode="1"""")
            if (sheet.tabColor == null && !fit) append("/>") else {
                append(">")
                sheet.tabColor?.let { append("""<tabColor rgb="${String.format(java.util.Locale.ROOT, "%08X", it and 0xFFFFFFFFL)}"/>""") }
                if (fit) append("""<pageSetUpPr fitToPage="1"/>""")
                append("</sheetPr>")
            }
        }
        // Sheet view: hidden gridlines or headings, a zoom, freeze panes (sheetViews must precede cols/sheetData in the schema).
        val frozen = sheet.freezeRows > 0 || sheet.freezeCols > 0
        if (frozen || !sheet.showHeadings || !sheet.showGridlines || sheet.zoom != 100 || sheet.pageBreakPreview || sheet.previewZoom != 60) {
            append("""<sheetViews><sheetView workbookViewId="0"""")
            if (sheet.pageBreakPreview) append(""" view="pageBreakPreview"""")
            if (!sheet.showGridlines) append(""" showGridLines="0"""")
            if (!sheet.showHeadings) append(""" showRowColHeaders="0"""")
            // The zoom shown, then each view's own: Normal's and Page Break Preview's (Excel's 60 % unwritten).
            if (sheet.viewZoom != 100) append(""" zoomScale="${sheet.viewZoom}"""")
            if (sheet.zoom != 100) append(""" zoomScaleNormal="${sheet.zoom}"""")
            if (sheet.previewZoom != 60) append(""" zoomScaleSheetLayoutView="${sheet.previewZoom}"""")
            if (frozen) {
                val topLeft = Workbook.colLabel(sheet.freezeCols) + (sheet.freezeRows + 1)
                append("""><pane""")
                if (sheet.freezeCols > 0) append(""" xSplit="${sheet.freezeCols}"""")
                if (sheet.freezeRows > 0) append(""" ySplit="${sheet.freezeRows}"""")
                append(""" topLeftCell="$topLeft" state="frozen"/></sheetView></sheetViews>""")
            } else append("""/></sheetViews>""")
        }
        // Row outline: the deepest level sizes Excel's outline gutter (sheetFormatPr follows sheetViews).
        val outlineLevels = HashMap<Int, Int>()
        sheet.rowGroups.forEach { g -> for (r in g.r1..g.r2) outlineLevels[r] = (outlineLevels[r] ?: 0) + 1 }
        val folded = sheet.foldedRows()
        val collapsedSummary = sheet.rowGroups.filter { it.collapsed }.map { it.r2 + 1 }.toSet()
        // Column outline, hidden columns and widths all live on <col>.
        val colLevels = HashMap<Int, Int>()
        sheet.colGroups.forEach { g -> for (c in g.r1..g.r2) colLevels[c] = (colLevels[c] ?: 0) + 1 }
        val foldedCols = sheet.foldedCols()
        val collapsedSummaryCols = sheet.colGroups.filter { it.collapsed }.map { it.r2 + 1 }.toSet()
        if (outlineLevels.isNotEmpty() || colLevels.isNotEmpty()) {
            append("""<sheetFormatPr defaultRowHeight="${String.format(Locale.ROOT, "%.2f", DEF_ROW_H)}"""")
            if (outlineLevels.isNotEmpty()) append(""" outlineLevelRow="${outlineLevels.values.max().coerceAtMost(7)}"""")
            if (colLevels.isNotEmpty()) append(""" outlineLevelCol="${colLevels.values.max().coerceAtMost(7)}"""")
            append("/>")
        }
        val colKeys = java.util.TreeSet<Int>().apply {
            addAll(sheet.colWidths.keys); addAll(sheet.hiddenCols); addAll(foldedCols); addAll(colLevels.keys); addAll(collapsedSummaryCols)
        }
        if (colKeys.isNotEmpty()) {
            // One <col> per run of neighbouring columns that look the same (Excel writes ranges too).
            fun attrs(c: Int): String = buildString {
                val f = sheet.colWidths[c]
                append(""" width="${String.format(Locale.ROOT, "%.4f", DEF_COL_W * (f ?: 1f))}"""")
                if (f != null) append(""" customWidth="1"""")
                if (c in sheet.hiddenCols || c in foldedCols) append(""" hidden="1"""")
                colLevels[c]?.let { append(""" outlineLevel="${it.coerceAtMost(7)}"""") }
                if (c in collapsedSummaryCols) append(""" collapsed="1"""")
            }
            append("<cols>")
            var start = -1; var prev = -2; var prevAttrs = ""
            fun flush() { if (start >= 0) append("""<col min="${start + 1}" max="${prev + 1}"$prevAttrs/>""") }
            for (c in colKeys) {
                val a = attrs(c)
                if (c != prev + 1 || a != prevAttrs) { flush(); start = c; prevAttrs = a }
                prev = c
            }
            flush()
            append("</cols>")
        }
        append("<sheetData>")
        // Group once by row (a per-row filter over all cells was O(rows × cells) on big sheets).
        val byRow = java.util.TreeMap<Int, MutableList<Pair<Int, Cell>>>()
        sheet.cells.forEach { (k, cell) -> byRow.getOrPut((k / 1000L).toInt()) { ArrayList() }.add((k % 1000L).toInt() to cell) }
        // Hidden, outlined and collapsed-summary rows are written even when empty.
        sheet.hiddenRows.forEach { byRow.getOrPut(it) { ArrayList() } }
        sheet.manualHiddenRows.forEach { byRow.getOrPut(it) { ArrayList() } }
        outlineLevels.keys.forEach { byRow.getOrPut(it) { ArrayList() } }
        collapsedSummary.forEach { byRow.getOrPut(it) { ArrayList() } }
        // A row given a height keeps it even when empty.
        sheet.rowHeights.keys.forEach { byRow.getOrPut(it) { ArrayList() } }
        // A table's header cells hold its columns' names as text, as Excel requires — blank ones too.
        val headerNames = HashMap<Long, String>()
        for (t in sheet.tables) if (t.headerRow) t.columnNames(sheet).forEachIndexed { i, n ->
            val key = sheet.key(t.r1, t.c1 + i)
            headerNames[key] = n
            if (sheet.cells[key] == null) byRow.getOrPut(t.r1) { ArrayList() }.add(t.c1 + i to Cell())
        }
        for ((r, cellsInRow) in byRow) {
            // A height the row was given is custom; one its content needs is written as Excel caches a
            // fitted height (no customHeight), so the row still follows its content.
            val custom = sheet.rowHeights[r]
            val fitted = sheet.autoRowHeights[r]
            val htAttr = when {
                custom != null -> """ ht="${String.format(Locale.ROOT, "%.2f", DEF_ROW_H * custom)}" customHeight="1""""
                fitted != null -> """ ht="${String.format(Locale.ROOT, "%.2f", DEF_ROW_H * fitted)}""""
                else -> ""
            }
            val hiddenAttr = if (r in sheet.hiddenRows || r in sheet.manualHiddenRows || r in folded) """ hidden="1"""" else ""
            val levelAttr = outlineLevels[r]?.let { """ outlineLevel="${it.coerceAtMost(7)}"""" } ?: ""
            val collapsedAttr = if (r in collapsedSummary) """ collapsed="1"""" else ""
            append("""<row r="${r + 1}"$htAttr$hiddenAttr$levelAttr$collapsedAttr>""")
            cellsInRow.sortBy { it.first }
            for ((c, cell) in cellsInRow) {
                val header = if (headerNames.isEmpty()) null else headerNames[sheet.key(r, c)]
                val ref = Workbook.colLabel(c) + (r + 1)
                if (cell.text.isEmpty() && header == null) {
                    // An empty cell with formats of its own (a fill, borders, Center Across Selection …) is written with
                    // just its style, as Excel writes one; a plain empty cell not at all.
                    val s = styles.indexOf(cell)
                    if (s != 0) append("""<c r="$ref" s="$s"/>""")
                    continue
                }
                val s = styles.indexOf(cell)
                val sAttr = if (s != 0) """ s="$s"""" else ""
                // A number only where the text is one as Excel stores it: `00123` or a 24-digit code stays text.
                val num = cell.storedNumber()
                when {
                    header != null -> append("""<c r="$ref"$sAttr t="inlineStr"><is><t xml:space="preserve">${esc(header)}</t></is></c>""")
                    cell.text.startsWith("=") -> {
                        val f = "<f>" + esc(xlfn(com.therealityteam.aeropages.model.StructuredRefs.toFileForm(cell.text.substring(1), sheet, r, c))) + "</f>"
                        when (val v = values?.invoke(r, c)) {
                            is CachedValue.Number -> append("""<c r="$ref"$sAttr>$f<v>${cachedNumber(v.value)}</v></c>""")
                            is CachedValue.Logical -> append("""<c r="$ref"$sAttr t="b">$f<v>${if (v.value) 1 else 0}</v></c>""")
                            is CachedValue.Text -> append("""<c r="$ref"$sAttr t="str">$f<v>${esc(v.value)}</v></c>""")
                            else -> append("""<c r="$ref"$sAttr>$f</c>""")
                        }
                    }
                    cell.storedLogical() != null -> append("""<c r="$ref"$sAttr t="b"><v>${if (cell.storedLogical() == true) 1 else 0}</v></c>""")
                    num != null -> append("""<c r="$ref"$sAttr><v>${cell.text}</v></c>""")
                    else -> {
                        val rich = cell.richRuns()
                        if (rich == null) append("""<c r="$ref"$sAttr t="inlineStr"><is><t xml:space="preserve">${esc(cell.text)}</t></is></c>""")
                        else {
                            append("""<c r="$ref"$sAttr t="inlineStr"><is>""")
                            rich.forEach { run -> append("<r>").append(runProps(run.format, cell.format)).append("""<t xml:space="preserve">${esc(run.text)}</t></r>""") }
                            append("</is></c>")
                        }
                    }
                }
            }
            append("</row>")
        }
        append("</sheetData>")
        // CT_Worksheet: sheetProtection comes right after sheetData.
        append(com.therealityteam.aeropages.format.ProtectionXml.xlsxSheet(sheet))
        if (sheet.scenarios.isNotEmpty()) {
            append("""<scenarios count="${sheet.scenarios.size}">""")
            sheet.scenarios.forEach { sc ->
                append("""<scenario name="${esc(sc.name)}" count="${sc.values.size}">""")
                sc.values.forEach { (k, v) -> append("""<inputCells r="${com.therealityteam.aeropages.model.A1.ref((k / 1000L).toInt(), (k % 1000L).toInt())}" val="${esc(v)}"/>""") }
                append("</scenario>")
            }
            append("</scenarios>")
        }
        if (filter != null) append(autoFilterXml(filter, sheet.autoFilter, dxfs))
        if (sheet.merges.isNotEmpty()) {
            append("""<mergeCells count="${sheet.merges.size}">""")
            sheet.merges.forEach { m ->
                val ref = Workbook.colLabel(m.c1) + (m.r1 + 1) + ":" + Workbook.colLabel(m.c2) + (m.r2 + 1)
                append("""<mergeCell ref="$ref"/>""")
            }
            append("</mergeCells>")
        }
        sheet.condFormats.forEachIndexed { pri, cf -> append(condFormatXml(cf, pri + 1, dxfs)) }
        // A list with nothing in it is left out: Excel reads an empty list as a damaged file.
        val rules = sheet.validations.filter { it.type != com.therealityteam.aeropages.model.ValidationType.LIST || it.formula1 != null || it.items.isNotEmpty() }
        if (rules.isNotEmpty()) {
            append("""<dataValidations count="${rules.size}">""")
            rules.forEach { append(validationXml(it)) }
            append("</dataValidations>")
        }
        val places = placeLinks(sheet)
        if (links.isNotEmpty() || places.isNotEmpty()) {
            fun ref(key: Long) = "${Workbook.colLabel((key % 1000L).toInt())}${(key / 1000L).toInt() + 1}"
            append("<hyperlinks>")
            links.forEachIndexed { li, (key, _) -> append("""<hyperlink ref="${ref(key)}" r:id="rIdL${li + 1}"/>""") }
            // A place in this workbook: its location, with the text it shows (Excel writes both).
            places.forEach { (key, place) ->
                val shown = sheet.cells[key]?.text?.takeIf { it.isNotEmpty() && !it.startsWith("=") }
                append("""<hyperlink ref="${ref(key)}" location="${esc(place)}"${shown?.let { """ display="${esc(it)}"""" } ?: ""}/>""")
            }
            append("</hyperlinks>")
        }
        // How it prints (CT_Worksheet: printOptions, pageMargins, pageSetup, headerFooter); a sheet as Excel's defaults stays minimal.
        val pr = sheet.print
        if (pr.gridlines || pr.headings || pr.centerH || pr.centerV) {
            append("<printOptions")
            if (pr.centerH) append(""" horizontalCentered="1"""")
            if (pr.centerV) append(""" verticalCentered="1"""")
            if (pr.headings) append(""" headings="1"""")
            if (pr.gridlines) append(""" gridLines="1"""")
            append("/>")
        }
        // The margins on every sheet, as Excel writes them (a reader would otherwise take its own).
        fun inches(pt: Float) = (Math.round(pt / 72f * 10000f) / 10000f).toString()
        append("""<pageMargins left="${inches(pr.marginLeft)}" right="${inches(pr.marginRight)}" top="${inches(pr.marginTop)}" bottom="${inches(pr.marginBottom)}" header="${inches(pr.marginHeader)}" footer="${inches(pr.marginFooter)}"/>""")
        if (sheet.landscape || sheet.paper != com.therealityteam.aeropages.model.Paper.A4 || pr != com.therealityteam.aeropages.model.SheetPrint()) {
            append("""<pageSetup paperSize="${sheet.paper.xlsxId}"""")
            if (pr.scale != 100) append(""" scale="${pr.scale}"""")
            if (pr.fits) append(""" fitToWidth="${pr.fitWidth ?: 0}" fitToHeight="${pr.fitHeight ?: 0}"""")
            if (pr.overThenDown) append(""" pageOrder="overThenDown"""")
            if (pr.comments != com.therealityteam.aeropages.model.NotePrint.NONE) append(""" cellComments="${pr.comments.xlsx}"""")
            if (pr.blackAndWhite) append(""" blackAndWhite="1"""")
            if (pr.draft) append(""" draft="1"""")
            if (pr.errors != com.therealityteam.aeropages.model.ErrorPrint.DISPLAYED) append(""" errors="${pr.errors.xlsx}"""")
            append(""" orientation="${if (sheet.landscape) "landscape" else "portrait"}"/>""")
            val head = com.therealityteam.aeropages.model.SheetPrint.toExcel(pr.header)
            val foot = com.therealityteam.aeropages.model.SheetPrint.toExcel(pr.footer)
            if (head.isNotEmpty() || foot.isNotEmpty()) {
                append("<headerFooter>")
                if (head.isNotEmpty()) append("<oddHeader>${esc(head)}</oddHeader>")
                if (foot.isNotEmpty()) append("<oddFooter>${esc(foot)}</oddFooter>")
                append("</headerFooter>")
            }
        }
        // Manual page breaks (CT_Worksheet: rowBreaks, colBreaks after headerFooter): each above its row / left of its column.
        if (pr.rowBreaks.isNotEmpty()) {
            append("""<rowBreaks count="${pr.rowBreaks.size}" manualBreakCount="${pr.rowBreaks.size}">""")
            pr.rowBreaks.forEach { append("""<brk id="$it" max="16383" man="1"/>""") }
            append("</rowBreaks>")
        }
        if (pr.colBreaks.isNotEmpty()) {
            append("""<colBreaks count="${pr.colBreaks.size}" manualBreakCount="${pr.colBreaks.size}">""")
            pr.colBreaks.forEach { append("""<brk id="$it" max="1048575" man="1"/>""") }
            append("</colBreaks>")
        }
        if (hasDrawing) append("""<drawing r:id="rIdDrw"/>""")
        // The notes' shapes (CT_Worksheet: legacyDrawing after drawing, before tableParts): Excel shows notes through them.
        if (hasComments) append("""<legacyDrawing r:id="rIdVml"/>""")
        if (tableCount > 0) {
            append("""<tableParts count="$tableCount">""")
            for (k in 1..tableCount) append("""<tablePart r:id="rIdT$k"/>""")
            append("</tableParts>")
        }
        appendSparklines(sheet)
        append("</worksheet>")
    }

    /**
     * Sparklines live in Excel's 2009 extension (`x14:sparklineGroups` inside `extLst`), one group
     * per type + colour; each sparkline names its source range (`xm:f`) and its cell (`xm:sqref`).
     */
    private fun StringBuilder.appendSparklines(sheet: com.therealityteam.aeropages.model.Sheet) {
        if (sheet.sparklines.isEmpty()) return
        val quotedName = "'" + sheet.name.replace("'", "''") + "'"
        append("""<extLst><ext uri="{05C60535-1F16-4fd2-B633-F4F36F0B64E0}" xmlns:x14="http://schemas.microsoft.com/office/spreadsheetml/2009/9/main">""")
        append("""<x14:sparklineGroups xmlns:xm="http://schemas.microsoft.com/office/excel/2006/main">""")
        sheet.sparklines.groupBy { Triple(it.type, it.color, it.markers) }.forEach { (key, group) ->
            val (type, color, markers) = key
            val typeAttr = when (type) {
                com.therealityteam.aeropages.model.SparklineType.COLUMN -> """ type="column""""
                com.therealityteam.aeropages.model.SparklineType.WIN_LOSS -> """ type="stacked""""
                else -> ""
            }
            append("""<x14:sparklineGroup displayEmptyCellsAs="gap"$typeAttr${if (markers) """ markers="1"""" else ""}>""")
            append("""<x14:colorSeries rgb="FF${hex(color)}"/><x14:colorNegative rgb="FFD00000"/><x14:colorMarkers rgb="FF${hex(color)}"/>""")
            append("<x14:sparklines>")
            group.forEach { s ->
                val range = "${Workbook.colLabel(s.c1)}${s.r1 + 1}:${Workbook.colLabel(s.c2)}${s.r2 + 1}"
                append("""<x14:sparkline><xm:f>${esc(quotedName)}!$range</xm:f><xm:sqref>${Workbook.colLabel(s.col)}${s.row + 1}</xm:sqref></x14:sparkline>""")
            }
            append("</x14:sparklines></x14:sparklineGroup>")
        }
        append("</x14:sparklineGroups></ext></extLst>")
    }

    /**
     * One rule as a `<conditionalFormatting>` block of its own (priority [priority]): a test with its look
     * (dxf) — the text, blank, error and date tests with the formulas Excel writes beside them — or a scale.
     */
    private fun condFormatXml(cf: com.therealityteam.aeropages.model.CondFormat, priority: Int, dxfs: Map<com.therealityteam.aeropages.model.CondStyle, Int>): String = buildString {
        val sqref = Workbook.colLabel(cf.c1) + (cf.r1 + 1) + ":" + Workbook.colLabel(cf.c2) + (cf.r2 + 1)
        val at = Workbook.colLabel(cf.c1) + (cf.r1 + 1)
        val stop = if (cf.stopIfTrue) """ stopIfTrue="1"""" else ""
        val dxf = """dxfId="${dxfs[cf.look] ?: 0}""""
        fun formula(f: String) = "<formula>${esc(f)}</formula>"
        fun quoted(t: String) = "\"" + t.replace("\"", "\"\"") + "\""
        fun cfvo(p: com.therealityteam.aeropages.model.ScalePoint, gte: Boolean = false): String {
            val type = when (p.kind) {
                com.therealityteam.aeropages.model.ScaleKind.MIN -> "min"
                com.therealityteam.aeropages.model.ScaleKind.MAX -> "max"
                com.therealityteam.aeropages.model.ScaleKind.NUMBER -> "num"
                com.therealityteam.aeropages.model.ScaleKind.PERCENT -> "percent"
                com.therealityteam.aeropages.model.ScaleKind.PERCENTILE -> "percentile"
                com.therealityteam.aeropages.model.ScaleKind.FORMULA -> "formula"
            }
            val v = when (p.kind) {
                com.therealityteam.aeropages.model.ScaleKind.MIN, com.therealityteam.aeropages.model.ScaleKind.MAX -> ""
                com.therealityteam.aeropages.model.ScaleKind.FORMULA -> """ val="${esc(p.formula ?: "0")}""""
                else -> """ val="${fmtNum(p.value)}""""
            }
            return """<cfvo type="$type"$v${if (gte && !p.gte) """ gte="0"""" else ""}/>"""
        }
        append("""<conditionalFormatting sqref="$sqref">""")
        when (cf.type) {
            com.therealityteam.aeropages.model.CondType.DATA_BAR -> {
                val ends = cf.barEnds.takeIf { it.size == 2 } ?: listOf(com.therealityteam.aeropages.model.ScalePoint(com.therealityteam.aeropages.model.ScaleKind.MIN), com.therealityteam.aeropages.model.ScalePoint(com.therealityteam.aeropages.model.ScaleKind.MAX))
                val lengths = (if (cf.barMin != 10) """ minLength="${cf.barMin}"""" else "") + (if (cf.barMax != 90) """ maxLength="${cf.barMax}"""" else "")
                append("""<cfRule type="dataBar" priority="$priority"><dataBar$lengths${if (!cf.showValue) """ showValue="0"""" else ""}>""")
                ends.forEach { append(cfvo(it)) }
                append("""<color rgb="FF${hex(cf.color)}"/></dataBar></cfRule>""")
            }
            com.therealityteam.aeropages.model.CondType.COLOR_SCALE -> {
                val stops = cf.stops.ifEmpty { listOf(com.therealityteam.aeropages.model.ScalePoint(com.therealityteam.aeropages.model.ScaleKind.MIN) to cf.color2, com.therealityteam.aeropages.model.ScalePoint(com.therealityteam.aeropages.model.ScaleKind.MAX) to cf.color) }
                append("""<cfRule type="colorScale" priority="$priority"><colorScale>""")
                stops.forEach { append(cfvo(it.first)) }
                stops.forEach { append("""<color rgb="FF${hex(it.second)}"/>""") }
                append("</colorScale></cfRule>")
            }
            com.therealityteam.aeropages.model.CondType.ICON_SET -> {
                val set = cf.icons ?: com.therealityteam.aeropages.model.IconSetKind.TRAFFIC_LIGHTS_3
                val points = cf.iconPoints.takeIf { it.size == set.count }
                    ?: (0 until set.count).map { com.therealityteam.aeropages.model.ScalePoint(com.therealityteam.aeropages.model.ScaleKind.PERCENT, Math.round(100.0 * it / set.count).toDouble()) }
                append("""<cfRule type="iconSet" priority="$priority"><iconSet iconSet="${set.xlsx}"${if (!cf.showValue) """ showValue="0"""" else ""}${if (cf.reverse) """ reverse="1"""" else ""}>""")
                points.forEach { append(cfvo(it, gte = true)) }
                append("</iconSet></cfRule>")
            }
            com.therealityteam.aeropages.model.CondType.GREATER, com.therealityteam.aeropages.model.CondType.LESS, com.therealityteam.aeropages.model.CondType.EQUAL,
            com.therealityteam.aeropages.model.CondType.BETWEEN, com.therealityteam.aeropages.model.CondType.GREATER_EQUAL, com.therealityteam.aeropages.model.CondType.LESS_EQUAL,
            com.therealityteam.aeropages.model.CondType.NOT_EQUAL, com.therealityteam.aeropages.model.CondType.NOT_BETWEEN -> {
                val op = when (cf.type) {
                    com.therealityteam.aeropages.model.CondType.GREATER -> "greaterThan"
                    com.therealityteam.aeropages.model.CondType.LESS -> "lessThan"
                    com.therealityteam.aeropages.model.CondType.GREATER_EQUAL -> "greaterThanOrEqual"
                    com.therealityteam.aeropages.model.CondType.LESS_EQUAL -> "lessThanOrEqual"
                    com.therealityteam.aeropages.model.CondType.EQUAL -> "equal"
                    com.therealityteam.aeropages.model.CondType.NOT_EQUAL -> "notEqual"
                    com.therealityteam.aeropages.model.CondType.BETWEEN -> "between"
                    else -> "notBetween"
                }
                append("""<cfRule type="cellIs" $dxf priority="$priority"$stop operator="$op">""")
                append(formula(cf.formula1 ?: fmtNum(cf.v1)))
                if (cf.type == com.therealityteam.aeropages.model.CondType.BETWEEN || cf.type == com.therealityteam.aeropages.model.CondType.NOT_BETWEEN) append(formula(cf.formula2 ?: fmtNum(cf.v2)))
                append("</cfRule>")
            }
            com.therealityteam.aeropages.model.CondType.TEXT_CONTAINS -> append("""<cfRule type="containsText" $dxf priority="$priority"$stop operator="containsText" text="${esc(cf.text)}">${formula("NOT(ISERROR(SEARCH(${quoted(cf.text)},$at)))")}</cfRule>""")
            com.therealityteam.aeropages.model.CondType.TEXT_NOT_CONTAINS -> append("""<cfRule type="notContainsText" $dxf priority="$priority"$stop operator="notContains" text="${esc(cf.text)}">${formula("ISERROR(SEARCH(${quoted(cf.text)},$at))")}</cfRule>""")
            com.therealityteam.aeropages.model.CondType.TEXT_BEGINS -> append("""<cfRule type="beginsWith" $dxf priority="$priority"$stop operator="beginsWith" text="${esc(cf.text)}">${formula("LEFT($at,LEN(${quoted(cf.text)}))=${quoted(cf.text)}")}</cfRule>""")
            com.therealityteam.aeropages.model.CondType.TEXT_ENDS -> append("""<cfRule type="endsWith" $dxf priority="$priority"$stop operator="endsWith" text="${esc(cf.text)}">${formula("RIGHT($at,LEN(${quoted(cf.text)}))=${quoted(cf.text)}")}</cfRule>""")
            com.therealityteam.aeropages.model.CondType.BLANKS -> append("""<cfRule type="containsBlanks" $dxf priority="$priority"$stop>${formula("LEN(TRIM($at))=0")}</cfRule>""")
            com.therealityteam.aeropages.model.CondType.NO_BLANKS -> append("""<cfRule type="notContainsBlanks" $dxf priority="$priority"$stop>${formula("LEN(TRIM($at))>0")}</cfRule>""")
            com.therealityteam.aeropages.model.CondType.ERRORS -> append("""<cfRule type="containsErrors" $dxf priority="$priority"$stop>${formula("ISERROR($at)")}</cfRule>""")
            com.therealityteam.aeropages.model.CondType.NO_ERRORS -> append("""<cfRule type="notContainsErrors" $dxf priority="$priority"$stop>${formula("NOT(ISERROR($at))")}</cfRule>""")
            com.therealityteam.aeropages.model.CondType.DUPLICATES -> append("""<cfRule type="duplicateValues" $dxf priority="$priority"$stop/>""")
            com.therealityteam.aeropages.model.CondType.UNIQUES -> append("""<cfRule type="uniqueValues" $dxf priority="$priority"$stop/>""")
            com.therealityteam.aeropages.model.CondType.TOP, com.therealityteam.aeropages.model.CondType.BOTTOM -> {
                val bottom = if (cf.type == com.therealityteam.aeropages.model.CondType.BOTTOM) """ bottom="1"""" else ""
                append("""<cfRule type="top10" $dxf priority="$priority"$stop${if (cf.percent) """ percent="1"""" else ""}$bottom rank="${cf.v1.toInt().coerceAtLeast(1)}"/>""")
            }
            com.therealityteam.aeropages.model.CondType.ABOVE_AVERAGE, com.therealityteam.aeropages.model.CondType.BELOW_AVERAGE -> {
                val below = if (cf.type == com.therealityteam.aeropages.model.CondType.BELOW_AVERAGE) """ aboveAverage="0"""" else ""
                append("""<cfRule type="aboveAverage" $dxf priority="$priority"$stop$below${if (cf.orEqual) """ equalAverage="1"""" else ""}/>""")
            }
            com.therealityteam.aeropages.model.CondType.DATE -> {
                val (name, f) = when (cf.period ?: com.therealityteam.aeropages.model.DatePeriod.TODAY) {
                    com.therealityteam.aeropages.model.DatePeriod.TODAY -> "today" to "FLOOR($at,1)=TODAY()"
                    com.therealityteam.aeropages.model.DatePeriod.YESTERDAY -> "yesterday" to "FLOOR($at,1)=TODAY()-1"
                    com.therealityteam.aeropages.model.DatePeriod.TOMORROW -> "tomorrow" to "FLOOR($at,1)=TODAY()+1"
                    com.therealityteam.aeropages.model.DatePeriod.LAST_7_DAYS -> "last7Days" to "AND(TODAY()-FLOOR($at,1)<=6,FLOOR($at,1)<=TODAY())"
                    com.therealityteam.aeropages.model.DatePeriod.THIS_WEEK -> "thisWeek" to "AND(TODAY()-ROUNDDOWN($at,0)<=WEEKDAY(TODAY())-1,ROUNDDOWN($at,0)-TODAY()<=7-WEEKDAY(TODAY()))"
                    com.therealityteam.aeropages.model.DatePeriod.LAST_WEEK -> "lastWeek" to "AND(TODAY()-ROUNDDOWN($at,0)>=(WEEKDAY(TODAY())),TODAY()-ROUNDDOWN($at,0)<(WEEKDAY(TODAY())+7))"
                    com.therealityteam.aeropages.model.DatePeriod.NEXT_WEEK -> "nextWeek" to "AND(ROUNDDOWN($at,0)-TODAY()>(7-WEEKDAY(TODAY())),ROUNDDOWN($at,0)-TODAY()<(15-WEEKDAY(TODAY())))"
                    com.therealityteam.aeropages.model.DatePeriod.THIS_MONTH -> "thisMonth" to "AND(MONTH($at)=MONTH(TODAY()),YEAR($at)=YEAR(TODAY()))"
                    com.therealityteam.aeropages.model.DatePeriod.LAST_MONTH -> "lastMonth" to "AND(MONTH($at)=MONTH(EDATE(TODAY(),0-1)),YEAR($at)=YEAR(EDATE(TODAY(),0-1)))"
                    com.therealityteam.aeropages.model.DatePeriod.NEXT_MONTH -> "nextMonth" to "AND(MONTH($at)=MONTH(EDATE(TODAY(),0+1)),YEAR($at)=YEAR(EDATE(TODAY(),0+1)))"
                }
                append("""<cfRule type="timePeriod" $dxf priority="$priority"$stop timePeriod="$name">${formula(f)}</cfRule>""")
            }
            com.therealityteam.aeropages.model.CondType.FORMULA -> append("""<cfRule type="expression" $dxf priority="$priority"$stop>${formula(cf.formula1 ?: "FALSE")}</cfRule>""")
        }
        append("</conditionalFormatting>")
    }

    private fun fmtNum(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /**
     * One validation rule as Excel writes it: what it allows and how it compares, its input
     * message and error alert (`showDropDown="1"` hides a list's arrow — OOXML's name for it is
     * backwards), then its formulas: bounds as numbers or formulas, a list as a quoted literal or its
     * source reference, a Custom rule's formula.
     */
    private fun validationXml(dv: com.therealityteam.aeropages.model.DataValidation): String = buildString {
        val type = dv.type
        append("""<dataValidation type="${type.xlsx}"""")
        if (dv.errorStyle != com.therealityteam.aeropages.model.ValidationAlertStyle.STOP) append(""" errorStyle="${dv.errorStyle.xlsx}"""")
        if (type.compares && dv.operator != com.therealityteam.aeropages.model.ValidationOperator.BETWEEN) append(""" operator="${dv.operator.xlsx}"""")
        if (dv.allowBlank) append(""" allowBlank="1"""")
        if (type == com.therealityteam.aeropages.model.ValidationType.LIST && !dv.dropdown) append(""" showDropDown="1"""")
        if (dv.showInput) append(""" showInputMessage="1"""")
        if (dv.showError) append(""" showErrorMessage="1"""")
        if (dv.errorTitle.isNotEmpty()) append(""" errorTitle="${attrText(dv.errorTitle)}"""")
        if (dv.errorMessage.isNotEmpty()) append(""" error="${attrText(dv.errorMessage)}"""")
        if (dv.inputTitle.isNotEmpty()) append(""" promptTitle="${attrText(dv.inputTitle)}"""")
        if (dv.inputMessage.isNotEmpty()) append(""" prompt="${attrText(dv.inputMessage)}"""")
        append(""" sqref="${com.therealityteam.aeropages.model.A1.rangeRef(dv.r1, dv.c1, dv.r2, dv.c2)}">""")
        val f1 = dv.formula1 ?: when {
            type == com.therealityteam.aeropages.model.ValidationType.LIST -> "\"" + dv.items.joinToString(",") { it.replace("\"", "\"\"") } + "\""
            type.compares -> fmtNum(dv.min)
            else -> null
        }
        if (f1 != null) append("<formula1>${esc(f1)}</formula1>")
        if (type.compares && dv.operator.twoBounds) append("<formula2>${esc(dv.formula2 ?: fmtNum(dv.max))}</formula2>")
        append("</dataValidation>")
    }

    /** Text for an attribute value: escaped, line breaks kept as character references (a literal one would read back as a space). */
    private fun attrText(s: String): String = esc(s).replace("\r\n", "\n").replace("\n", "&#10;")

    private fun rels(vararg r: Pair<String, Pair<String, String>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        r.forEach { (id, tt) -> append("""<Relationship Id="$id" Type="${tt.first}" Target="${tt.second}"/>""") }
        append("</Relationships>")
    }

    /** EMUs (DrawingML's unit) per point. */
    private const val EMU_PER_PT = 12700f

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
    }

    private fun hex(argb: Long): String = String.format(Locale.ROOT, "%06X", argb and 0xFFFFFF)
    private fun esc(s: String): String = com.therealityteam.aeropages.format.OfficeXml.escape(s)
}
