package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.DeskFiles
import com.therealityteam.aeropages.format.DeskKind
import com.therealityteam.aeropages.format.odf.OdfPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Which module opens a file: by its extension — WPS Office's own and the templates too — or, for a name without one, its MIME type. */
class DeskFilesTest {

    @Test fun wpsOfficesOwnFilesAndTemplatesOpenInTheirModules() {
        assertEquals(DeskKind.PAGES, DeskFiles.kindOfOrNull("/d/report.wps"))
        assertEquals(DeskKind.PAGES, DeskFiles.kindOfOrNull("/d/letter.wpt"))
        assertEquals(DeskKind.PAGES, DeskFiles.kindOfOrNull("/d/form.ott"))
        assertEquals(DeskKind.SHEETS, DeskFiles.kindOfOrNull("/d/budget.ET"))
        assertEquals(DeskKind.SHEETS, DeskFiles.kindOfOrNull("/d/budget.ett"))
        assertEquals(DeskKind.SHEETS, DeskFiles.kindOfOrNull("/d/old.xlt"))
        assertEquals(DeskKind.SHEETS, DeskFiles.kindOfOrNull("/d/plan.ots"))
        assertEquals(DeskKind.SLIDES, DeskFiles.kindOfOrNull("/d/talk.dps"))
        assertEquals(DeskKind.SLIDES, DeskFiles.kindOfOrNull("/d/theme.dpt"))
        assertEquals(DeskKind.SLIDES, DeskFiles.kindOfOrNull("/d/show.pps"))
        assertEquals(DeskKind.SLIDES, DeskFiles.kindOfOrNull("/d/old.pot"))
        assertEquals(DeskKind.SLIDES, DeskFiles.kindOfOrNull("/d/deck.otp"))
        assertNull(DeskFiles.kindOfOrNull("/d/picture.png"))
    }

    @Test fun aNameWithoutAnExtensionTakesItsTypes() {
        assertEquals("xls", DeskFiles.extensionForMime("application/vnd.ms-excel"))
        assertEquals("dps", DeskFiles.extensionForMime("application/wps-office.dps"))
        assertEquals("docx", DeskFiles.extensionForMime("application/vnd.openxmlformats-officedocument.wordprocessingml.document; charset=binary"))
        assertNull(DeskFiles.extensionForMime("image/png"))
        assertNull(DeskFiles.extensionForMime(null))
    }

    @Test fun anOpenDocumentIsKnownByItsMimetypeEntry() {
        val out = ByteArrayOutputStream()
        OdfPackage.write(out, "application/vnd.oasis.opendocument.presentation-template", linkedMapOf("content.xml" to "<x/>".toByteArray()))
        assertEquals("application/vnd.oasis.opendocument.presentation-template", OdfPackage.typeOf(out.toByteArray()))
        assertNull("a zip of another kind", OdfPackage.typeOf(byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(60)))
        assertNull(OdfPackage.typeOf("not a zip".toByteArray()))
    }
}
