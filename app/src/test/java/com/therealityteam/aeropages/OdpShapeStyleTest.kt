package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.model.ShapeAnchor
import com.therealityteam.aeropages.model.ShapeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shapes in a foreign (Impress-made) ODP take their fill, outline and text anchor from their
 * `graphic` style, resolved through `style:parent-style-name` into styles.xml.
 */
class OdpShapeStyleTest {

    private fun zipOf(parts: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> parts.forEach { (n, s) -> z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    private val ns = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
        """xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
        """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
        """xmlns:presentation="urn:oasis:names:tc:opendocument:xmlns:presentation:1.0""""

    @Test
    fun shapesTakeFillOutlineAndAnchorFromTheirStyleChain() {
        // styles.xml: the common "standard" graphic style (solid blue fill, thin outline, middle anchor).
        val styles = """<office:document-styles $ns><office:styles>
            <style:style style:name="standard" style:family="graphic">
              <style:graphic-properties draw:fill="solid" draw:fill-color="#729fcf" draw:stroke="solid" svg:stroke-color="#3465a4" svg:stroke-width="0.5pt" draw:textarea-vertical-align="middle"/>
            </style:style>
            <style:style style:name="objectwithoutfill" style:family="graphic" style:parent-style-name="standard">
              <style:graphic-properties draw:fill="none"/>
            </style:style>
            </office:styles></office:document-styles>"""
        // content.xml: automatic styles derive from those; three shapes on one page.
        val content = """<office:document-content $ns><office:automatic-styles>
            <style:style style:name="gr1" style:family="graphic" style:parent-style-name="standard">
              <style:graphic-properties draw:fill-color="#ff0000" draw:textarea-vertical-align="bottom" svg:stroke-width="3pt"/>
            </style:style>
            <style:style style:name="gr2" style:family="graphic" style:parent-style-name="objectwithoutfill">
              <style:graphic-properties draw:stroke="none" draw:textarea-vertical-align="top"/>
            </style:style>
            <style:style style:name="gr3" style:family="graphic" style:parent-style-name="standard"><style:graphic-properties/></style:style>
            </office:automatic-styles><office:body><office:presentation>
            <draw:page draw:name="page1">
              <draw:custom-shape draw:style-name="gr1" svg:width="10cm" svg:height="5cm" svg:x="2cm" svg:y="2cm"><text:p>Red</text:p><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>
              <draw:custom-shape draw:style-name="gr2" svg:width="10cm" svg:height="5cm" svg:x="2cm" svg:y="8cm"><text:p>Bare</text:p><draw:enhanced-geometry draw:type="ellipse"/></draw:custom-shape>
              <draw:custom-shape draw:style-name="gr3" svg:width="10cm" svg:height="5cm" svg:x="14cm" svg:y="2cm"><text:p>Standard</text:p><draw:enhanced-geometry draw:type="rectangle"/></draw:custom-shape>
            </draw:page></office:presentation></office:body></office:document-content>"""
        val bytes = zipOf(mapOf("mimetype" to "application/vnd.oasis.opendocument.presentation", "content.xml" to content, "styles.xml" to styles))
        System.getProperty("aero.sampleDir")?.let { java.io.File(it).apply { mkdirs() }.resolve("shapes.odp").writeBytes(bytes) }
        val pres = OdpReader.read(bytes)
        val shapes = pres.slides[0].shapes
        assertEquals(3, shapes.size)
        val red = shapes.first { it.box.text == "Red" }
        assertEquals(ShapeType.RECT, red.type)
        assertEquals("own fill colour over the parent's", 0xFFFF0000, red.fill)
        assertEquals("outline colour inherited from standard", 0xFF3465A4, red.border)
        assertEquals("own outline width", 3f, red.borderWidthPt, 0.01f)
        assertEquals(ShapeAnchor.BOTTOM, red.anchor)
        val bare = shapes.first { it.box.text == "Bare" }
        assertEquals(ShapeType.ELLIPSE, bare.type)
        assertNull("fill none through objectwithoutfill", bare.fill)
        assertNull("stroke none at the leaf", bare.border)
        assertEquals(ShapeAnchor.TOP, bare.anchor)
        val std = shapes.first { it.box.text == "Standard" }
        assertEquals("everything from the named style", 0xFF729FCF, std.fill)
        assertEquals(0xFF3465A4, std.border)
        assertEquals(0.5f, std.borderWidthPt, 0.01f)
        assertEquals(ShapeAnchor.CENTER, std.anchor)
    }
}
