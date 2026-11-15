package com.therealityteam.aeropages

import com.therealityteam.aeropages.platform.PickerActivity
import com.therealityteam.aeropages.format.DeskFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** The picker flows: document types map to MIME types (the save-target flow is verified on the device). */
class SaveRequestsTest {
    @Test fun theSystemPickerIncludesDocumentTypesAcceptedByTheEditors() {
        val types = listOf("text/markdown", "text/html", "application/rtf", "text/rtf",
            "application/vnd.oasis.opendocument.spreadsheet-template", "application/wps-office.dpt",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow")
        for (type in types) {
            assertTrue("system picker should accept $type", type in PickerActivity.OPENABLE_TYPES)
            assertTrue("provider names without a suffix need $type", DeskFiles.extensionForMime(type) in DeskFiles.OPENABLE)
        }
        assertTrue("a provider may only know the generic type", "application/octet-stream" in PickerActivity.OPENABLE_TYPES)
    }

    @Test fun androidIntentFiltersAndPickerStayInSyncWithTheFormatRegistry() {
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        val manifest = parser.parse(File("src/main/AndroidManifest.xml"))
        val filters = manifest.getElementsByTagName("intent-filter")
        val ns = "http://schemas.android.com/apk/res/android"
        fun values(filter: Element, tag: String, attribute: String): Set<String> {
            val nodes = filter.getElementsByTagName(tag)
            return (0 until nodes.length).map { (nodes.item(it) as Element).getAttributeNS(ns, attribute) }
                .filter { it.isNotEmpty() }.toSet()
        }
        for (action in listOf("VIEW", "SEND")) {
            val declared = (0 until filters.length).map { filters.item(it) as Element }
                .filter { "android.intent.action.$action" in values(it, "action", "name") }
                .flatMap { values(it, "data", "mimeType") }.toSet() - "*/*"
            assertEquals(action, DeskFiles.documentMimeTypes.keys, declared)
        }
        assertEquals(DeskFiles.documentMimeTypes.keys + "application/octet-stream", PickerActivity.OPENABLE_TYPES.toSet())
        assertTrue(DeskFiles.documentMimeTypes.values.all { it in DeskFiles.OPENABLE })
    }

    @Test
    fun mimeTypesFollowTheExtension() {
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", PickerActivity.mimeFor("docx"))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", PickerActivity.mimeFor("XLSX"))
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", PickerActivity.mimeFor("pptx"))
        assertEquals("application/pdf", PickerActivity.mimeFor("pdf")); assertEquals("application/octet-stream", PickerActivity.mimeFor("bin"))
        // Office (OOXML and 97), OpenDocument, WPS Office's own, CSV and text, anything (a file a provider cannot type).
        for ((ext, name) in listOf("ods" to "spreadsheet", "odt" to "text", "odp" to "presentation")) {
            assertEquals("application/vnd.oasis.opendocument.$name", PickerActivity.mimeFor(ext))
        }
        assertEquals("text/plain", PickerActivity.mimeFor("txt"))
        assertTrue("application/wps-office.dps" in PickerActivity.OPENABLE_TYPES)
    }
}
