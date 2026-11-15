package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.UnsupportedContent
import com.therealityteam.aeropages.format.UnsupportedContent.Kind
import com.therealityteam.aeropages.ui.DocumentSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** What a file holds that saving over it would drop, found from its package alone; and the names saving picks. */
class UnsupportedContentTest {

    private fun scan(parts: Map<String, String>): Set<Kind> =
        UnsupportedContent.scan(parts.keys) { name -> parts[name]?.toByteArray()?.let { ByteArrayInputStream(it) } }

    private fun rels(vararg types: String) = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
        types.mapIndexed { i, t -> """<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/$t" Target="x$i"/>""" }.joinToString("") +
        "</Relationships>"

    private val workbook = mapOf(
        "[Content_Types].xml" to "<Types/>", "xl/workbook.xml" to "<workbook/>", "xl/worksheets/sheet1.xml" to "<worksheet/>",
        "xl/_rels/workbook.xml.rels" to rels("worksheet", "styles", "theme"), "xl/theme/theme1.xml" to "<theme/>", "docProps/core.xml" to "<cp/>",
        "customXml/item1.xml" to "<x/>", "customXml/_rels/item1.xml.rels" to rels("customXmlProps"), "xl/printerSettings/printerSettings1.bin" to "",
    )

    @Test fun anOrdinaryWorkbookHoldsNothingUnsupported() {
        assertEquals(emptySet<Kind>(), scan(workbook))
    }

    @Test fun excelsMacrosPivotTablesSlicersLinksAndConnections() {
        val parts = workbook + mapOf(
            "xl/vbaProject.bin" to "", "xl/pivotTables/pivotTable1.xml" to "", "xl/pivotCache/pivotCacheDefinition1.xml" to "",
            "xl/slicers/slicer1.xml" to "", "xl/timelines/timeline1.xml" to "", "xl/externalLinks/externalLink1.xml" to "",
            "xl/connections.xml" to "", "xl/model/item.data" to "", "xl/chartsheets/sheet1.xml" to "",
            "xl/charts/chartEx1.xml" to "", "xl/threadedComments/threadedComment1.xml" to "", "xl/ctrlProps/ctrlProp1.xml" to "",
            "_xmlsignatures/sig1.xml" to "",
        )
        assertEquals(setOf(Kind.MACROS, Kind.CONTROLS, Kind.PIVOT_TABLES, Kind.SLICERS, Kind.EXTERNAL_LINKS, Kind.DATA_CONNECTIONS,
            Kind.DATA_MODEL, Kind.CHART_SHEETS, Kind.NEW_CHART_TYPES, Kind.COMMENT_THREADS, Kind.SIGNATURES), scan(parts))
    }

    @Test fun aSheetsOleObjectIsEmbeddedButAChartsOwnWorkbookIsNot() {
        val chart = workbook + mapOf(
            "xl/charts/chart1.xml" to "<c:chartSpace/>",
            "xl/charts/_rels/chart1.xml.rels" to rels("package"),
            "xl/embeddings/Microsoft_Excel_Worksheet.xlsx" to "",
        )
        assertEquals("the chart keeps its data: nothing lost", emptySet<Kind>(), scan(chart))
        val ole = workbook + mapOf("xl/worksheets/_rels/sheet1.xml.rels" to rels("drawing", "oleObject", "vmlDrawing"), "xl/embeddings/oleObject1.bin" to "")
        assertEquals(setOf(Kind.EMBEDDED_OBJECTS), scan(ole))
    }

    @Test fun powerPointsSmartArtMediaCommentsAndInk() {
        val deck = mapOf(
            "[Content_Types].xml" to "<Types/>", "ppt/presentation.xml" to "<p/>", "ppt/slides/slide1.xml" to "<sld/>",
            "ppt/slides/_rels/slide1.xml.rels" to rels("slideLayout", "image", "diagramData", "video") +
                """<Relationship Id="m" Type="http://schemas.microsoft.com/office/2007/relationships/media" Target="../media/media1.mp4"/>""",
            "ppt/media/image1.png" to "", "ppt/media/media1.mp4" to "", "ppt/diagrams/data1.xml" to "", "ppt/diagrams/drawing1.xml" to "",
            "ppt/comments/modernComment_100_ABC.xml" to "", "ppt/ink/ink1.xml" to "",
        )
        assertEquals("the slide's own video is kept (its file copy, p14:media)", setOf(Kind.SMARTART, Kind.INK), scan(deck))
        val sound = mapOf("[Content_Types].xml" to "<Types/>", "ppt/presentation.xml" to "<p/>", "ppt/media/media2.m4a" to "")
        assertEquals("a sound no slide picture plays, by its extension alone", setOf(Kind.MEDIA), scan(sound))
    }

    @Test fun wordsEmbeddedChunksAndControls() {
        val doc = mapOf(
            "[Content_Types].xml" to "<Types/>", "word/document.xml" to "<w:document/>",
            "word/_rels/document.xml.rels" to rels("styles", "aFChunk", "control", "image"),
            "word/activeX/activeX1.xml" to "", "word/fontTable.xml" to "", "word/glossary/document.xml" to "",
        )
        assertEquals(setOf(Kind.EMBEDDED_OBJECTS, Kind.CONTROLS), scan(doc))
    }

    private fun manifest(vararg entries: Pair<String, String>) =
        """<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3">""" +
            """<manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>""" +
            entries.joinToString("") { (p, t) -> """<manifest:file-entry manifest:full-path="$p" manifest:version="1.3" manifest:media-type="$t"/>""" } +
            "</manifest:manifest>"

    private fun ods(content: String = "<office:document-content/>", vararg extra: Pair<String, String>) =
        mapOf("mimetype" to "application/vnd.oasis.opendocument.spreadsheet", "content.xml" to content, "styles.xml" to "<s/>") + extra.toMap()

    @Test fun anOpenDocumentWithAChartAndAFormulaHoldsNothingUnsupported() {
        val parts = ods("<office:document-content/>",
            "META-INF/manifest.xml" to manifest("Object 1/" to "application/vnd.oasis.opendocument.chart", "Object 2/" to "application/vnd.oasis.opendocument.formula",
                "Configurations2/" to "application/vnd.sun.xml.ui.configuration"),
            "Object 1/content.xml" to "<chart/>", "Object 2/content.xml" to "<math/>", "Thumbnails/thumbnail.png" to "",
            "Basic/script-lc.xml" to "", "Basic/Standard/script-lb.xml" to "")
        assertEquals("an empty Basic library is no macro", emptySet<Kind>(), scan(parts))
    }

    @Test fun openDocumentMacrosObjectsControlsMediaDataPilotsAndSignatures() {
        val content = """<office:document-content><office:body><office:spreadsheet><table:table table:name="S">""" +
            """<form:form form:name="F"/><draw:plugin xlink:href="Media/clip.mp4"/><draw:object-ole xlink:href="./Object 3"/>""" +
            """</table:table><table:data-pilot-tables><table:data-pilot-table table:name="P"/></table:data-pilot-tables>""" +
            """<table:table table:name="L"><table:table-source xlink:href="other.ods"/></table:table></office:spreadsheet></office:body></office:document-content>"""
        val parts = ods(content,
            "META-INF/manifest.xml" to manifest("Object 1/" to "application/vnd.oasis.opendocument.text", "Object 3" to "application/vnd.sun.star.oleobject"),
            "Basic/Standard/Module1.xml" to "<script/>", "META-INF/documentsignatures.xml" to "<sig/>")
        assertEquals(setOf(Kind.MACROS, Kind.EMBEDDED_OBJECTS, Kind.CONTROLS, Kind.MEDIA, Kind.PIVOT_TABLES, Kind.EXTERNAL_LINKS, Kind.SIGNATURES), scan(parts))
    }

    @Test fun markersAreFoundAcrossTheReadsOfALargeContentPart() {
        val pad = "x".repeat(64 * 1024 - 5)
        val text = pad + "<draw:plugin " + pad + "<table:data-pilot-table "
        val found = UnsupportedContent.markers(ByteArrayInputStream(text.toByteArray()), listOf("<draw:plugin ", "<table:data-pilot-table ", "<form:form "))
        assertEquals(setOf("<draw:plugin ", "<table:data-pilot-table "), found)
    }

    @Test fun startTagsAreReadWholeAcrossTheReadsOfALargeContentPart() {
        val split = """<draw:plugin xlink:href="Media/视频.mp4" draw:mime-type="video/mp4"/>"""
        val text = "x".repeat(64 * 1024 - 20) + split + "y".repeat(100) + """<draw:plugin xlink:href="b.swf"/>"""
        assertEquals(listOf(split, """<draw:plugin xlink:href="b.swf"/>"""), UnsupportedContent.startTags(ByteArrayInputStream(text.toByteArray()), "<draw:plugin "))
    }

    private fun odp(content: String, vararg extra: Pair<String, String>) =
        mapOf("mimetype" to "application/vnd.oasis.opendocument.presentation", "content.xml" to content, "styles.xml" to "<s/>") + extra.toMap()

    private fun slide(frame: String) = """<office:document-content><office:body><office:presentation><draw:page>$frame</draw:page></office:presentation></office:body></office:document-content>"""

    @Test fun aPresentationKeepsTheVideosItsSlidesShow() {
        val video = slide("""<draw:frame><draw:plugin xlink:href="Media/clip.mp4" draw:mime-type="video/mp4"/><draw:image xlink:href="Pictures/p.png"/></draw:frame>""")
        assertEquals(emptySet<Kind>(), scan(odp(video, "Media/clip.mp4" to "", "Pictures/p.png" to "")))
        assertEquals("a sound no slide plays", setOf(Kind.MEDIA), scan(odp(video, "Media/clip.mp4" to "", "Media/other.wav" to "")))
        assertEquals("an effect's sound", setOf(Kind.MEDIA), scan(odp(video.replace("</draw:page>", """<anim:par><anim:audio xlink:href="Media/clip.mp4"/></anim:par></draw:page>"""), "Media/clip.mp4" to "")))
        val flash = slide("""<draw:frame><draw:plugin xlink:href="Plugins/game.swf" draw:mime-type="application/x-shockwave-flash"/></draw:frame>""")
        assertEquals(setOf(Kind.EMBEDDED_OBJECTS), scan(odp(flash, "Plugins/game.swf" to "")))
        // No mimetype: the manifest's root entry tells a presentation.
        val manifest = manifest().replace("opendocument.spreadsheet", "opendocument.presentation")
        assertEquals(emptySet<Kind>(), scan(odp(video, "Media/clip.mp4" to "", "META-INF/manifest.xml" to manifest) - "mimetype"))
        assertEquals("a text's plug-in is not kept", setOf(Kind.MEDIA), scan(odp(video, "Media/clip.mp4" to "") + ("mimetype" to "application/vnd.oasis.opendocument.text")))
    }

    @Test fun aFileThatIsNotAPackageHoldsNothingToFind() {
        val f = File.createTempFile("aero", ".doc").apply { writeBytes(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())); deleteOnExit() }
        assertEquals(emptySet<Kind>(), UnsupportedContent.scan(f.path))
    }

    // --- names saving picks -------------------------------------------------------------

    @Test fun aFreeNameBesideAnExistingFile() {
        val taken = setOf("/d/report.xlsx", "/d/report (2).xlsx")
        assertEquals("/d/new.xlsx", DocumentSession.freeName("/d/new.xlsx") { it in taken })
        assertEquals("/d/report (3).xlsx", DocumentSession.freeName("/d/report.xlsx") { it in taken })
        assertEquals("/d/notes (2)", DocumentSession.freeName("/d/notes") { it == "/d/notes" })
    }

    /**
     * Every Office / OpenDocument file under `-Daero.scanDir=<folder>` (LibreOffice's conversions, a
     * corpus of real files …) and what it holds, written to `aero-unsupported-scan.txt` in the temp folder.
     */
    @Test fun scanAFolder() {
        val dir = System.getProperty("aero.scanDir")?.let { File(it) }
        org.junit.Assume.assumeTrue(dir != null && dir.isDirectory)
        val exts = setOf("docx", "docm", "xlsx", "xlsm", "xltx", "pptx", "pptm", "potx", "ppsx", "odt", "ods", "odp", "ott", "ots", "otp")
        val lines = dir!!.walkTopDown().filter { it.isFile && it.extension.lowercase() in exts }.sortedBy { it.path }
            .map { f -> "${f.relativeTo(dir).path}: ${UnsupportedContent.scan(f.path).ifEmpty { "-" }}" }.toList()
        File(System.getProperty("java.io.tmpdir"), "aero-unsupported-scan.txt").writeText(lines.joinToString("\n"))
    }

    @Test fun everySampleTheAppWritesScansClean() {
        val dir = File(System.getProperty("java.io.tmpdir"), "aero-unsupported-samples").apply { deleteRecursively(); mkdirs() }
        SampleFileMakerTest().writeTo(dir)
        val flagged = dir.listFiles()!!.sortedBy { it.name }.mapNotNull { f -> UnsupportedContent.scan(f.path).takeIf { it.isNotEmpty() }?.let { "${f.name}: $it" } }
        assertTrue("the app's own files must never look like they hold content it would drop:\n" + flagged.joinToString("\n"), flagged.isEmpty())
    }
}
