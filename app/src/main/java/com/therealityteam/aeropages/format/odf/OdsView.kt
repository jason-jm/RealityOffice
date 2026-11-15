package com.therealityteam.aeropages.format.odf

import com.therealityteam.aeropages.format.odf.OdfPackage.HEAD
import com.therealityteam.aeropages.format.odf.OdfPackage.escape
import com.therealityteam.aeropages.model.Sheet
import com.therealityteam.aeropages.model.Workbook

/**
 * A spreadsheet's view as LibreOffice Calc keeps it in `settings.xml`: under the view's `Tables`, each sheet's frozen
 * columns and rows (`HorizontalSplitMode` 2 with `HorizontalSplitPosition` columns, `VerticalSplit…` rows), its zoom
 * (`ZoomValue`) and gridlines (`ShowGrid`); the view's own `HasColumnRowHeaders` for the headings (Calc keeps those for
 * the whole view) and, as Calc's view items do, the active sheet's zoom and gridlines.
 */
internal object OdsView {
    private fun item(name: String, type: String, value: Any) =
        """<config:config-item config:name="$name" config:type="$type">$value</config:config-item>"""

    fun write(wb: Workbook): ByteArray = buildString {
        val active = wb.sheets.firstOrNull { !it.hidden } ?: wb.sheets.first()
        append(HEAD)
        append("""<office:document-settings xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """)
        append("""xmlns:config="urn:oasis:names:tc:opendocument:xmlns:config:1.0" office:version="1.3"><office:settings>""")
        append("""<config:config-item-set config:name="ooo:view-settings">""")
        append("""<config:config-item-map-indexed config:name="Views"><config:config-item-map-entry>""")
        append(item("ViewId", "string", "view1"))
        append("""<config:config-item-map-named config:name="Tables">""")
        for (s in wb.sheets) {
            append("""<config:config-item-map-entry config:name="${escape(s.name)}">""")
            append(item("CursorPositionX", "int", 0)).append(item("CursorPositionY", "int", 0))
            append(item("HorizontalSplitMode", "short", if (s.freezeCols > 0) 2 else 0))
            append(item("VerticalSplitMode", "short", if (s.freezeRows > 0) 2 else 0))
            append(item("HorizontalSplitPosition", "int", s.freezeCols)).append(item("VerticalSplitPosition", "int", s.freezeRows))
            // The pane the cursor is in: the bottom-right one past both, else the one past the frozen part.
            append(item("ActiveSplitRange", "short", when { s.freezeRows > 0 && s.freezeCols > 0 -> 3; s.freezeCols > 0 -> 1; else -> 2 }))
            append(item("PositionLeft", "int", 0)).append(item("PositionRight", "int", s.freezeCols))
            append(item("PositionTop", "int", 0)).append(item("PositionBottom", "int", s.freezeRows))
            append(item("ZoomType", "short", 0)).append(item("ZoomValue", "int", s.zoom)).append(item("PageViewZoomValue", "int", s.previewZoom))
            // Calc's page break mode is the view's; each sheet's own is kept here too (Calc passes over what it does not know).
            append(item("ShowPageBreakPreview", "boolean", s.pageBreakPreview))
            append(item("ShowGrid", "boolean", s.showGridlines))
            append("</config:config-item-map-entry>")
        }
        append("</config:config-item-map-named>")
        append(item("ActiveTable", "string", escape(active.name)))
        append(item("ZoomType", "short", 0)).append(item("ZoomValue", "int", active.zoom)).append(item("PageViewZoomValue", "int", active.previewZoom))
        append(item("ShowPageBreakPreview", "boolean", active.pageBreakPreview))
        append(item("ShowGrid", "boolean", active.showGridlines))
        append(item("HasColumnRowHeaders", "boolean", active.showHeadings))
        append(item("HasSheetTabs", "boolean", true))
        append("</config:config-item-map-entry></config:config-item-map-indexed></config:config-item-set>")
        append("""<config:config-item-set config:name="ooo:configuration-settings">""")
        append(item("ShowGrid", "boolean", active.showGridlines)).append(item("HasColumnRowHeaders", "boolean", active.showHeadings))
        append("</config:config-item-set></office:settings></office:document-settings>")
    }.toByteArray(Charsets.UTF_8)

    /** [sheets]' frozen panes, zoom, gridlines and headings from [settings] (a part Calc or this app wrote); nothing when there is none. */
    fun read(settings: ByteArray?, sheets: List<Sheet>) {
        val root = OdfNode.parse(settings) ?: return
        val sets = root.child("settings")?.children("config-item-set").orEmpty()
        val view = sets.firstOrNull { it.attr("name") == "ooo:view-settings" }
            ?.children("config-item-map-indexed")?.firstOrNull { it.attr("name") == "Views" }
            ?.child("config-item-map-entry")
        val config = sets.firstOrNull { it.attr("name") == "ooo:configuration-settings" }
        fun items(n: OdfNode?): Map<String, String> = n?.children("config-item")?.associate { (it.attr("name") ?: "") to it.text.trim() }.orEmpty()
        val viewItems = items(view)
        val configItems = items(config)
        // Calc's headings and gridlines for the whole view: the view's, else the document's.
        val headings = (viewItems["HasColumnRowHeaders"] ?: configItems["HasColumnRowHeaders"])?.let { it == "true" }
        val grid = (viewItems["ShowGrid"] ?: configItems["ShowGrid"])?.let { it == "true" }
        val zoom = viewItems["ZoomValue"]?.toIntOrNull()
        val pageZoom = viewItems["PageViewZoomValue"]?.toIntOrNull()
        val preview = viewItems["ShowPageBreakPreview"]?.let { it == "true" }
        val tables = view?.children("config-item-map-named")?.firstOrNull { it.attr("name") == "Tables" }
            ?.children("config-item-map-entry")?.associateBy { it.attr("name") }.orEmpty()
        for (s in sheets) {
            headings?.let { s.showHeadings = it }
            val t = items(tables[s.name])
            (t["ShowGrid"]?.let { it == "true" } ?: grid)?.let { s.showGridlines = it }
            (t["ZoomValue"]?.toIntOrNull() ?: zoom)?.let { s.zoom = it.coerceIn(Sheet.MIN_ZOOM, Sheet.MAX_ZOOM) }
            (t["PageViewZoomValue"]?.toIntOrNull() ?: pageZoom)?.takeIf { it > 0 }?.let { s.previewZoom = it.coerceIn(Sheet.MIN_ZOOM, Sheet.MAX_ZOOM) }
            // Calc's page break mode is the whole view's: every sheet in it, unless the sheet says otherwise (as this app writes).
            (t["ShowPageBreakPreview"]?.let { it == "true" } ?: preview)?.let { s.pageBreakPreview = it }
            // Frozen (mode 2) — a split (mode 1) is only a view's, not a freeze.
            if (t["HorizontalSplitMode"] == "2") t["HorizontalSplitPosition"]?.toIntOrNull()?.let { if (it > 0) s.freezeCols = it }
            if (t["VerticalSplitMode"] == "2") t["VerticalSplitPosition"]?.toIntOrNull()?.let { if (it > 0) s.freezeRows = it }
        }
    }
}
