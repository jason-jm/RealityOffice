package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.odf.OdfPackage.HEAD
import com.therealityteam.aeropages.format.odf.OdfPackage.NS
import com.therealityteam.aeropages.format.odf.OdfPackage.color
import com.therealityteam.aeropages.format.odf.OdfPackage.escape
import com.therealityteam.aeropages.format.odf.OdfPackage.pt
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.model.A1
import com.therealityteam.aeropages.model.ChartSeries
import com.therealityteam.aeropages.model.ChartType
import com.therealityteam.aeropages.model.SlideChart
import java.util.Locale

/**
 * OpenDocument charts. A chart is an embedded object — `Object N/content.xml` holding a
 * `chart:chart` — referenced by a `draw:frame`/`draw:object` on a slide (odp) or in a sheet's
 * `table:shapes` (ods). The object carries its data twice: a local table (`local-table`, the
 * cache every producer writes and what we read) and, for a spreadsheet chart, cell-range
 * addresses into the sheet (`xlink:href=".."`), which is what keeps the chart live in Calc.
 */
internal object OdfChart {
    const val MEDIA_TYPE = "application/vnd.oasis.opendocument.chart"
    private const val CHART_NS = """xmlns:chart="urn:oasis:names:tc:opendocument:xmlns:chart:1.0""""

    /** The `draw:frame` wrapping an embedded object at [href] (`./Object 1`); slides put it on the layout layer, sheets have no layers. */
    fun frame(href: String, xPt: Float, yPt: Float, wPt: Float, hPt: Float, layer: Boolean = true, ids: String = ""): String =
        """<draw:frame${if (layer) """ draw:layer="layout"""" else ""}$ids svg:width="${pt(wPt)}" svg:height="${pt(hPt)}" svg:x="${pt(xPt)}" svg:y="${pt(yPt)}">""" +
            """<draw:object xlink:href="$href" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad"/></draw:frame>"""

    /** A sheet name as it appears in a cell-range address: quoted when it holds anything beyond letters, digits and `_`. */
    private fun sheetRef(name: String): String =
        if (name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '_' }) escape(name) else "'" + escape(name.replace("'", "''")) + "'"

    /**
     * The object's content.xml. [sheetName] (with a bound [SlideChart.sourceRef]) makes the
     * ranges point into that sheet; otherwise every range points into the local table.
     */
    fun contentXml(c: SlideChart, wPt: Float, hPt: Float, sheetName: String? = null): String = buildString {
        val cats = c.categories
        val nRows = cats.size.coerceAtLeast(c.series.maxOfOrNull { it.values.size } ?: 0)
        val vertical = c.type == ChartType.BAR
        val cls = when (c.type) {
            ChartType.COLUMN, ChartType.BAR -> "chart:bar"
            ChartType.LINE -> "chart:line"
            ChartType.PIE -> "chart:circle"
            ChartType.DOUGHNUT -> "chart:ring"
            ChartType.AREA -> "chart:area"
            ChartType.SCATTER -> "chart:scatter"
            ChartType.RADAR -> "chart:radar"
        }
        // Where the data lives: the sheet block for a bound spreadsheet chart, else the local table.
        // (sheet name, block) — null for a slide chart or an unbound sheet chart.
        val bound = sheetName?.let { s -> c.sourceRef?.let { ref -> A1.parseRange(ref) }?.let { s to it } }
        val block = bound?.second
        fun addr(sheet: String, r1: Int, c1: Int, r2: Int, c2: Int) =
            "${sheetRef(sheet)}.${A1.ref(r1, c1)}:${sheetRef(sheet)}.${A1.ref(r2, c2)}"
        val catAddr: String
        val valAddr: (Int) -> String
        val labelAddr: (Int) -> String?
        if (bound != null) {
            val (sheet, b) = bound
            val top = b[0] + (if (c.sourceHeader) 1 else 0)
            val left = b[1] + (if (c.sourceCats) 1 else 0)
            catAddr = if (c.sourceCats) addr(sheet, top, b[1], b[2], b[1]) else ""
            valAddr = { i -> addr(sheet, top, left + i, b[2], left + i) }
            labelAddr = { i -> if (c.sourceHeader) "${sheetRef(sheet)}.${A1.ref(b[0], left + i)}" else null }
        } else {
            catAddr = "local-table.A2:local-table.A${nRows + 1}"
            valAddr = { i -> "local-table.${A1.ref(1, i + 1)}:local-table.${A1.ref(nRows, i + 1)}" }
            labelAddr = { i -> "local-table.${A1.ref(0, i + 1)}" }
        }
        val pal = com.therealityteam.aeropages.model.ChartStyle.palette(c.colorSet)
        append(HEAD)
        append("<office:document-content $NS $CHART_NS>")
        append("<office:automatic-styles>")
        append("""<style:style style:name="plot" style:family="chart"><style:chart-properties chart:vertical="$vertical"/></style:style>""")
        append("""<style:style style:name="axis" style:family="chart"><style:chart-properties chart:display-label="true"/></style:style>""")
        append(valueAxisStyles(c))
        c.series.forEachIndexed { i, _ ->
            val fill = color(pal[i % pal.size])
            append("""<style:style style:name="series$i" style:family="chart">""")
            append("""<style:chart-properties chart:data-label-number="${if (c.showValues) "value" else "none"}" chart:symbol-type="none"/>""")
            append("""<style:graphic-properties draw:fill-color="$fill" svg:stroke-color="$fill"/></style:style>""")
        }
        append("</office:automatic-styles>")
        append("<office:body><office:chart>")
        val href = if (block != null) """ xlink:href=".." xlink:type="simple"""" else ""
        append("""<chart:chart chart:class="$cls" svg:width="${pt(wPt)}" svg:height="${pt(hPt)}"$href>""")
        if (c.title.isNotBlank()) append("""<chart:title><text:p>${escape(c.title)}</text:p></chart:title>""")
        if (c.showLegend) {
            val pos = when (c.legendPos) {
                com.therealityteam.aeropages.model.LegendPos.BOTTOM -> "bottom"
                com.therealityteam.aeropages.model.LegendPos.TOP -> "top"
                com.therealityteam.aeropages.model.LegendPos.LEFT -> "start"
                com.therealityteam.aeropages.model.LegendPos.RIGHT -> "end"
            }
            append("""<chart:legend chart:legend-position="$pos"/>""")
        }
        val rangeAttr = bound?.let { (sheet, b) -> """ table:cell-range-address="${addr(sheet, b[0], b[1], b[2], b[3])}"""" } ?: ""
        val labels = if (block != null) (if (c.sourceHeader && c.sourceCats) "both" else if (c.sourceHeader) "row" else if (c.sourceCats) "column" else "none") else "both"
        append("""<chart:plot-area chart:style-name="plot"$rangeAttr chart:data-source-has-labels="$labels">""")
        // chart:axis holds its title first, then categories / grid (ODF 1.2 order).
        append("""<chart:axis chart:dimension="x" chart:name="primary-x" chart:style-name="axis">""")
        if (c.catAxisTitle.isNotBlank()) append("""<chart:title><text:p>${escape(c.catAxisTitle)}</text:p></chart:title>""")
        if (catAddr.isNotEmpty()) append("""<chart:categories table:cell-range-address="$catAddr"/>""")
        append("</chart:axis>")
        append("""<chart:axis chart:dimension="y" chart:name="primary-y" chart:style-name="axis-y">""")
        if (c.valAxisTitle.isNotBlank()) append("""<chart:title><text:p>${escape(c.valAxisTitle)}</text:p></chart:title>""")
        if (c.showGrid) append("""<chart:grid chart:class="major"/>""")
        append("</chart:axis>")
        c.series.forEachIndexed { i, s ->
            val label = labelAddr(i)?.let { """ chart:label-cell-range-address="$it"""" } ?: ""
            append("""<chart:series chart:style-name="series$i" chart:values-cell-range-address="${valAddr(i)}"$label chart:class="$cls">""")
            append("""<chart:data-point chart:repeated="${s.values.size.coerceAtLeast(1)}"/></chart:series>""")
        }
        append("</chart:plot-area>")
        // Local table: header row of series names, then one row per category.
        append("""<table:table table:name="local-table"><table:table-header-columns><table:table-column/></table:table-header-columns>""")
        append("""<table:table-columns><table:table-column table:number-columns-repeated="${c.series.size.coerceAtLeast(1)}"/></table:table-columns>""")
        append("""<table:table-header-rows><table:table-row><table:table-cell><text:p/></table:table-cell>""")
        c.series.forEach { s -> append("""<table:table-cell office:value-type="string"><text:p>${escape(s.name)}</text:p></table:table-cell>""") }
        append("</table:table-row></table:table-header-rows><table:table-rows>")
        for (r in 0 until nRows) {
            append("""<table:table-row><table:table-cell office:value-type="string"><text:p>${escape(cats.getOrNull(r) ?: "")}</text:p></table:table-cell>""")
            c.series.forEach { s ->
                val v = s.values.getOrNull(r)
                if (v == null) append("<table:table-cell/>")
                else append("""<table:table-cell office:value-type="float" office:value="${num(v)}"><text:p>${num(v)}</text:p></table:table-cell>""")
            }
            append("</table:table-row>")
        }
        append("</table:table-rows></table:table></chart:chart></office:chart></office:body></office:document-content>")
    }

    /**
     * The value axis' style — Format Axis bounds and major interval — plus its number style when
     * the chart has a number format (ODF's data style; a percent format is a percentage style,
     * scaling a display factor).
     */
    private fun valueAxisStyles(c: SlideChart): String = buildString {
        val spec = com.therealityteam.aeropages.model.ChartStyle.parseFormat(c.valFormat)
        if (spec != null) {
            val element = if (spec.percent) "number:percentage-style" else "number:number-style"
            append("""<$element style:name="Nval">""")
            if (spec.prefix.isNotEmpty()) append("<number:text>${escape(spec.prefix)}</number:text>")
            append("""<number:number number:decimal-places="${spec.decimals}" number:min-decimal-places="${spec.required}" number:min-integer-digits="1"""")
            if (spec.grouping) append(""" number:grouping="true"""")
            if (spec.scale > 0) append(""" number:display-factor="${Math.round(Math.pow(1000.0, spec.scale.toDouble()))}"""")
            append("/>")
            if (spec.suffix.isNotEmpty()) append("<number:text>${escape(spec.suffix)}</number:text>")
            append("</$element>")
        }
        append("""<style:style style:name="axis-y" style:family="chart"${if (spec != null) " style:data-style-name=\"Nval\"" else ""}>""")
        append("""<style:chart-properties chart:display-label="true"""")
        c.valMin?.let { append(""" chart:minimum="${dnum(it)}"""") }
        c.valMax?.let { append(""" chart:maximum="${dnum(it)}"""") }
        c.valMajor?.let { append(""" chart:interval-major="${dnum(it)}"""") }
        if (spec != null) append(""" chart:link-data-style-to-source="false"""")
        append("/></style:style>")
    }

    private fun dnum(v: Double): String = java.math.BigDecimal(v).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()

    private fun num(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')

    /**
     * Reads an object's content.xml. Data comes from the local table (series in column order,
     * names from its header row, categories from its first column); a spreadsheet chart whose
     * ranges form one block on [sheetName] is bound to it ([SlideChart.sourceRef]).
     */
    /** An ODF number / percentage / currency style as the Excel format code the chart model keeps. */
    private fun formatCodeOf(style: OdfNode): String {
        val prefix = StringBuilder()
        val suffix = StringBuilder()
        var number: OdfNode? = null
        for (child in style.children) when (child.name) {
            "text", "currency-symbol" -> (if (number == null) prefix else suffix).append(child.text)
            "number" -> number = child
        }
        val n = number ?: return SlideChart.GENERAL
        val decimals = n.attr("decimal-places")?.toIntOrNull() ?: 0
        val required = (n.attr("min-decimal-places")?.toIntOrNull() ?: decimals).coerceIn(0, decimals)
        var factor = n.attr("display-factor")?.toDoubleOrNull() ?: 1.0
        var scale = 0
        while (factor >= 1000.0 - 1e-9 && scale < 4) { factor /= 1000.0; scale++ }
        return com.therealityteam.aeropages.model.ChartStyle.NumberFormatSpec(
            prefix.toString(), suffix.toString(), decimals, required, n.attr("grouping") == "true", style.name == "percentage-style", scale,
        ).code()
    }

    fun read(bytes: ByteArray?, sheetName: String? = null): SlideChart? {
        val root = OdfNode.parse(bytes) ?: return null
        val chart = root.descendants("chart").firstOrNull { it.attr("class") != null } ?: return null
        val styles = HashMap<String, OdfNode>()
        root.descendants("style").forEach { st -> st.attr("name")?.let { styles[it] = st } }
        fun chartProp(styleName: String?, attr: String): String? {
            val st = styles[styleName ?: return null] ?: return null
            return st.child("chart-properties")?.attr(attr)
        }
        val plot = chart.child("plot-area")
        val vertical = chartProp(plot?.attr("style-name"), "vertical") == "true"
        val cls = chart.attr("class")?.substringAfter(':') ?: "bar"
        val type = when (cls) {
            "bar" -> if (vertical) ChartType.BAR else ChartType.COLUMN
            "line", "stock" -> ChartType.LINE
            "circle" -> ChartType.PIE
            "ring" -> ChartType.DOUGHNUT
            "area" -> ChartType.AREA
            "scatter", "bubble" -> ChartType.SCATTER
            "radar", "filled-radar" -> ChartType.RADAR
            else -> ChartType.COLUMN
        }
        val out = SlideChart(type = type)
        chart.child("title")?.descendants("p")?.joinToString(" ") { it.text }?.trim()?.let { out.title = it }
        out.showLegend = chart.child("legend") != null
        out.legendPos = when (chart.child("legend")?.attr("legend-position")) {
            "bottom", "bottom-start", "bottom-end" -> com.therealityteam.aeropages.model.LegendPos.BOTTOM
            "top", "top-start", "top-end" -> com.therealityteam.aeropages.model.LegendPos.TOP
            "start" -> com.therealityteam.aeropages.model.LegendPos.LEFT
            else -> com.therealityteam.aeropages.model.LegendPos.RIGHT
        }
        fun axisTitle(dim: String) = plot?.children("axis")?.firstOrNull { it.attr("dimension") == dim }
            ?.child("title")?.descendants("p")?.joinToString(" ") { it.text }?.trim() ?: ""
        out.catAxisTitle = axisTitle("x")
        out.valAxisTitle = axisTitle("y")
        out.showGrid = plot?.children("axis")?.any { it.attr("dimension") == "y" && it.child("grid") != null } == true
        // Format Axis on the value axis: fixed bounds and interval in its style, the number format in its data style.
        val yStyle = plot?.children("axis")?.firstOrNull { it.attr("dimension") == "y" }?.attr("style-name")
        fun bound(attr: String) = chartProp(yStyle, attr)?.toDoubleOrNull()?.takeIf { it.isFinite() }
        out.valMin = bound("minimum")
        out.valMax = bound("maximum")
        out.valMajor = bound("interval-major")?.takeIf { it > 0 }
        styles[yStyle]?.attr("data-style-name")?.let { name ->
            root.descendants("number-style").plus(root.descendants("percentage-style")).plus(root.descendants("currency-style"))
                .firstOrNull { it.attr("name") == name }
        }?.let { out.valFormat = formatCodeOf(it) }
        val seriesNodes = plot?.children("series").orEmpty()
        out.showValues = seriesNodes.any { s -> chartProp(s.attr("style-name"), "data-label-number").let { it != null && it != "none" } }

        // Local table → grid of (text, number?).
        val table = chart.children("table").firstOrNull()
        val rows = ArrayList<List<Pair<String, Float?>>>()
        table?.descendants("table-row")?.forEach { r ->
            rows.add(r.children.filter { it.name == "table-cell" }.map { cell ->
                val text = cell.descendants("p").joinToString("\n") { it.text }.trim()
                text to (cell.attr("value")?.toFloatOrNull() ?: text.toFloatOrNull())
            })
        }
        val labels = plot?.attr("data-source-has-labels") ?: "both"
        val hasHeader = labels == "both" || labels == "row"
        val hasCats = labels == "both" || labels == "column"
        val header = if (hasHeader) rows.firstOrNull() else null
        val body = if (hasHeader) rows.drop(1) else rows
        val firstCol = if (hasCats) 1 else 0
        if (hasCats) body.forEach { r -> out.categories.add(r.getOrNull(0)?.first ?: "") }
        else body.forEachIndexed { i, _ -> out.categories.add((i + 1).toString()) }
        val nSeries = (seriesNodes.size.takeIf { it > 0 } ?: ((body.maxOfOrNull { it.size } ?: firstCol) - firstCol)).coerceAtLeast(0)
        for (i in 0 until nSeries) {
            val col = firstCol + i
            val name = header?.getOrNull(col)?.first?.takeIf { it.isNotBlank() }
                ?: "Series ${i + 1}"
            out.series.add(ChartSeries(name, body.map { it.getOrNull(col)?.second ?: 0f }.toMutableList()))
        }
        // A spreadsheet chart: bind to the block its ranges describe on this sheet.
        if (sheetName != null && seriesNodes.isNotEmpty()) {
            fun formula(addr: String?): String? {
                if (addr.isNullOrBlank()) return null
                // `Sheet.$A$1:.$B$4`: a second end without a sheet name means the first end's sheet.
                var last = sheetName
                val ends = addr.split(':').map { part ->
                    // `'My.Sheet'.A1`: the sheet may be quoted (and contain dots) — the separator is the dot after the quotes.
                    val dot = if (part.startsWith("'") || part.startsWith("$'")) part.indexOf("'.", part.indexOf('\'') + 1).let { if (it < 0) part.lastIndexOf('.') else it + 1 } else part.lastIndexOf('.')
                    val named = if (dot >= 0) part.substring(0, dot).trim('$').removeSurrounding("'").replace("''", "'") else ""
                    val sheet = named.ifEmpty { last }
                    last = sheet
                    sheet to part.substring(dot + 1).replace("$", "")
                }
                if (ends.any { it.first != sheetName }) return "!!other"
                return "$sheetName!" + ends.joinToString(":") { it.second }
            }
            val cat = formula(plot?.children("axis")?.firstNotNullOfOrNull { it.child("categories")?.attr("cell-range-address") })
            val vals = seriesNodes.map { formula(it.attr("values-cell-range-address")) }
            val names = seriesNodes.map { formula(it.attr("label-cell-range-address")) }
            if (cat != "!!other" && vals.none { it == "!!other" } && names.none { it == "!!other" }) {
                PptxReader.sourceOf(cat, vals, names)?.let { src ->
                    out.sourceRef = src.ref; out.sourceHeader = src.header; out.sourceCats = src.cats
                }
            }
        }
        return out
    }
}
