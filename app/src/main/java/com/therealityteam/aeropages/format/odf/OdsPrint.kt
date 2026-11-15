package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.model.Paper
import com.therealityteam.aeropages.model.PrintArea
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.SheetPrint
import com.therealityteam.aeropages.model.Workbook

/**
 * How sheets print, as ODF has it: each print setup a page layout (paper, orientation, margins, scale or fit to pages,
 * whether the grid and the row and column headers print, centring) and a master page holding its header and footer —
 * left, centre and right regions with the sheet's name, the page number and count, the file's name, the date — which a
 * sheet's table style names; the print range on the table. As LibreOffice converts Excel's: with a header, the page's
 * top margin is the header's distance from the edge and the header's area reaches down to the cells.
 */
internal object OdsPrint {
    /** The page layouts (styles.xml's automatic styles), the master pages, and each sheet's master page. */
    class Written(val layouts: String, val masters: String, val masterOf: List<String>)

    fun write(wb: Workbook): Written {
        val names = LinkedHashMap<Triple<Paper, Boolean, SheetPrint>, String>()
        val masterOf = wb.sheets.map { s -> names.getOrPut(Triple(s.paper, s.landscape, s.print)) { if (names.isEmpty()) "Default" else "Mp${names.size + 1}" } }
        val layouts = StringBuilder(); val masters = StringBuilder()
        names.entries.forEachIndexed { k, (key, master) ->
            val (paper, landscape, pr) = key
            val layout = "pmP${k + 1}"
            val hasHeader = pr.header.any { it.isNotEmpty() }; val hasFooter = pr.footer.any { it.isNotEmpty() }
            val w = if (landscape) paper.heightPt else paper.widthPt; val h = if (landscape) paper.widthPt else paper.heightPt
            layouts.append("""<style:page-layout style:name="$layout"><style:page-layout-properties fo:page-width="${pt(w.toFloat())}" fo:page-height="${pt(h.toFloat())}" """)
            layouts.append("""style:print-orientation="${if (landscape) "landscape" else "portrait"}" """)
            layouts.append("""fo:margin-top="${pt(if (hasHeader) pr.marginHeader else pr.marginTop)}" fo:margin-bottom="${pt(if (hasFooter) pr.marginFooter else pr.marginBottom)}" """)
            layouts.append("""fo:margin-left="${pt(pr.marginLeft)}" fo:margin-right="${pt(pr.marginRight)}" """)
            layouts.append("""style:print="charts drawings objects zero-values${if (pr.gridlines) " grid" else ""}${if (pr.headings) " headers" else ""}${if (pr.comments == com.therealityteam.aeropages.model.NotePrint.AT_END) " annotations" else ""}" """)
            if (pr.overThenDown) layouts.append("""style:print-page-order="ltr" """)
            layouts.append("""style:table-centering="${when { pr.centerH && pr.centerV -> "both"; pr.centerH -> "horizontal"; pr.centerV -> "vertical"; else -> "none" }}" """)
            if (pr.fits) layouts.append("""style:scale-to-X="${pr.fitWidth ?: 0}" style:scale-to-Y="${pr.fitHeight ?: 0}"""")
            else layouts.append("""style:scale-to="${pr.scale}%"""")
            layouts.append("/>")
            fun area(element: String, on: Boolean, height: Float, spacing: String) {
                if (!on) { layouts.append("<style:$element/>"); return }
                layouts.append("""<style:$element><style:header-footer-properties fo:min-height="${pt(height.coerceAtLeast(0f))}" fo:margin-left="0pt" fo:margin-right="0pt" fo:$spacing="${pt((height - 14f).coerceAtLeast(0f))}" style:dynamic-spacing="true"/></style:$element>""")
            }
            area("header-style", hasHeader, pr.marginTop - pr.marginHeader, "margin-bottom")
            area("footer-style", hasFooter, pr.marginBottom - pr.marginFooter, "margin-top")
            layouts.append("</style:page-layout>")
            masters.append("""<style:master-page style:name="$master" style:page-layout-name="$layout">""")
            masters.append(if (hasHeader) "<style:header>${regions(pr.header)}</style:header>" else """<style:header style:display="false"/>""")
            masters.append(if (hasFooter) "<style:footer>${regions(pr.footer)}</style:footer>" else """<style:footer style:display="false"/>""")
            masters.append("</style:master-page>")
        }
        return Written(layouts.toString(), masters.toString(), masterOf)
    }

    /** A header's or footer's left, centre and right parts as ODF regions, Excel's field codes as ODF fields. */
    private fun regions(parts: List<String>): String = buildString {
        listOf("region-left", "region-center", "region-right").forEachIndexed { i, region ->
            val part = parts.getOrElse(i) { "" }
            append("<style:$region><text:p>").append(fields(part)).append("</text:p></style:$region>")
        }
    }

    private fun fields(part: String): String = buildString {
        var i = 0
        val text = StringBuilder()
        fun flush() { if (text.isNotEmpty()) { append(OdfPackage.escape(text.toString())); text.clear() } }
        while (i < part.length) {
            val c = part[i]
            if (c != '&' || i + 1 >= part.length) { text.append(c); i++; continue }
            val n = part[i + 1]
            val field = when (n) {
                'P' -> "<text:page-number>1</text:page-number>"
                'N' -> "<text:page-count>1</text:page-count>"
                'A' -> "<text:sheet-name>???</text:sheet-name>"
                'F' -> "<text:file-name>???</text:file-name>"
                'D' -> "<text:date/>"
                'T' -> "<text:time/>"
                else -> null
            }
            when {
                n == '&' -> { text.append('&'); i += 2 }
                field != null -> { flush(); append(field); i += 2 }
                n == '"' -> { val e = part.indexOf('"', i + 2); i = if (e < 0) part.length else e + 1 }
                n.isDigit() -> { i += 2; while (i < part.length && part[i].isDigit()) i++ }
                else -> i += 2
            }
        }
        flush()
    }

    /** [sheet]'s print range as ODF writes it (`Sheet1.A1:Sheet1.D20`), or null. */
    fun printRanges(sheet: Sheet): String? = sheet.printArea?.let { pa ->
        val s = OdfDataRules.odfSheet(sheet.name)
        "$s.${Workbook.colLabel(pa.c1)}${pa.r1 + 1}:$s.${Workbook.colLabel(pa.c2)}${pa.r2 + 1}"
    }

    /** A table's print range (`Sheet1.A1:Sheet1.D20`, `$Sheet1.$A$1:.$D$20`), its first one. */
    fun readPrintRanges(v: String?): PrintArea? {
        // Ranges are space-separated; a quoted sheet name may hold spaces.
        val text = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var quoted = false
        val end = text.indices.firstOrNull { i -> if (text[i] == '\'') quoted = !quoted; text[i] == ' ' && !quoted } ?: text.length
        val first = text.substring(0, end)
        val ends = first.split(':')
        fun at(p: String) = com.therealityteam.aeropages.model.A1.parse(p.substringAfterLast('.').replace("$", ""))
        val (r1, c1) = at(ends[0]) ?: return null
        val (r2, c2) = ends.getOrNull(1)?.let { at(it) } ?: (r1 to c1)
        return PrintArea(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
    }

    /** [sheet]'s print setup from the master page [masterName] names in styles.xml ([styles]) and its page layout. */
    fun read(styles: OdfNode?, masterName: String?, sheet: Sheet) {
        val masterStyles = styles?.child("master-styles") ?: return
        val master = masterStyles.children("master-page").firstOrNull { it.attr("name") == (masterName ?: "Default") }
            ?: masterStyles.children("master-page").firstOrNull() ?: return
        val layout = styles.child("automatic-styles")?.children("page-layout")?.firstOrNull { it.attr("name") == master.attr("page-layout-name") }
        val props = layout?.child("page-layout-properties") ?: return
        val w = OdfValue.twipsPt(props.attr("page-width")); val h = OdfValue.twipsPt(props.attr("page-height"))
        if (w != null && h != null) { Paper.ofSize(w, h)?.let { sheet.paper = it }; sheet.landscape = w > h }
        props.attr("print-orientation")?.let { sheet.landscape = it == "landscape" }
        fun header(element: String): List<String>? {
            val node = master.child(element) ?: return null
            if (node.attr("display") == "false") return null
            val l = node.child("region-left"); val c = node.child("region-center"); val r = node.child("region-right")
            val parts = if (l != null || c != null || r != null) listOf(l, c, r).map { codes(it) } else listOf("", codes(node), "")
            return parts.takeIf { p -> p.any { it.isNotEmpty() } }
        }
        val head = header("header"); val foot = header("footer")
        val top = OdfValue.twipsPt(props.attr("margin-top")) ?: 54f
        val bottom = OdfValue.twipsPt(props.attr("margin-bottom")) ?: 54f
        fun area(style: String) = layout.child(style)?.child("header-footer-properties")?.let { OdfValue.twipsPt(it.attr("min-height")) } ?: 0f
        val print = props.attr("print").orEmpty().split(' ')
        val centering = props.attr("table-centering")
        val pagesX = props.attr("scale-to-X")?.toIntOrNull(); val pagesY = props.attr("scale-to-Y")?.toIntOrNull()
        val pages = props.attr("scale-to-pages")?.toIntOrNull()
        sheet.print = SheetPrint(
            marginTop = if (head != null) top + area("header-style") else top,
            marginBottom = if (foot != null) bottom + area("footer-style") else bottom,
            marginLeft = OdfValue.twipsPt(props.attr("margin-left")) ?: 50.4f, marginRight = OdfValue.twipsPt(props.attr("margin-right")) ?: 50.4f,
            marginHeader = if (head != null) top else 21.6f, marginFooter = if (foot != null) bottom else 21.6f,
            scale = props.attr("scale-to")?.removeSuffix("%")?.toFloatOrNull()?.toInt()?.coerceIn(10, 400) ?: 100,
            // Fit to so many pages one way: the other as many as it takes (LibreOffice writes only the one it sets).
            fitWidth = pagesX ?: pages?.let { 1 } ?: pagesY?.let { 0 }, fitHeight = pagesY ?: pages ?: pagesX?.let { 0 },
            gridlines = "grid" in print, headings = "headers" in print,
            centerH = centering == "horizontal" || centering == "both", centerV = centering == "vertical" || centering == "both",
            header = head ?: SheetPrint.EMPTY_PARTS, footer = foot ?: SheetPrint.EMPTY_PARTS,
            // The print titles are the table's header rows and columns, and the manual breaks its rows' and columns' styles, read with the table.
            titleRows = sheet.print.titleRows, titleCols = sheet.print.titleCols,
            rowBreaks = sheet.print.rowBreaks, colBreaks = sheet.print.colBreaks,
            overThenDown = props.attr("print-page-order") == "ltr",
            comments = if ("annotations" in print) com.therealityteam.aeropages.model.NotePrint.AT_END else com.therealityteam.aeropages.model.NotePrint.NONE,
        )
    }

    /** A region's text with its fields as Excel's codes (&P, &N, &A, &F, &D, &T), && for an ampersand. */
    private fun codes(region: OdfNode?): String {
        region ?: return ""
        val out = StringBuilder()
        fun walk(n: OdfNode) {
            n.parts.forEach { item ->
                when (item) {
                    is String -> out.append(item.replace("&", "&&"))
                    is OdfNode -> when (item.name) {
                        "page-number" -> out.append("&P")
                        "page-count" -> out.append("&N")
                        "sheet-name" -> out.append("&A")
                        "file-name" -> out.append("&F")
                        "date" -> out.append("&D")
                        "time" -> out.append("&T")
                        "s" -> out.append(" ".repeat(item.attr("c")?.toIntOrNull() ?: 1))
                        "p" -> { if (out.isNotEmpty()) out.append('\n'); walk(item) }
                        else -> walk(item)
                    }
                }
            }
        }
        walk(region)
        return out.toString().trim('\n')
    }

    private fun pt(v: Float) = OdfPackage.pt(v)
}
