package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.UnsupportedContent
import com.therealityteam.aeropages.format.odf.OdfPackage
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Videos and sounds on slides: kept from a PowerPoint or OpenDocument file to the one AeroDesk writes, as PowerPoint and LibreOffice keep them. */
class SlideMediaTest {

    private val poster = TestPng.header(16, 9)
    private val clip = "clip bytes".toByteArray()

    private fun picture(media: SlideMedia) = SlideImage(poster, "image/png", 160f, 90f, 0.2f, 0.2f, 0.4f, 0.4f, media = media)

    private fun parts(bytes: ByteArray): Map<String, ByteArray> = HashMap<String, ByteArray>().also { m ->
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.forEach { e -> m[e.name] = z.readBytes() } }
    }

    private fun deck(vararg slides: Slide) = Presentation(slides.toMutableList())
    private fun write(p: Presentation) = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()

    @Test fun anEmbeddedVideoIsWrittenAsPowerPointKeepsIt() {
        // On a blank slide: in a content placeholder PowerPoint writes the video as the placeholder (ContentPlaceholderTest).
        val bytes = write(deck(Slide(layout = com.therealityteam.aeropages.model.SlideLayout.BLANK).also { it.images.add(picture(SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", clip, null, "clip.mp4"))) }))
        val parts = parts(bytes)
        val slide = parts.getValue("ppt/slides/slide1.xml").decodeToString()
        assertTrue(slide, Regex("""<p:cNvPr id="\d+" name="clip.mp4"><a:hlinkClick r:id="" action="ppaction://media"/></p:cNvPr>""").containsMatchIn(slide))
        assertTrue(slide, Regex("""<p:nvPr><a:videoFile r:link="(rId\d+)"/><p:extLst><p:ext uri="\{DAA4B4D4-6D71-4841-9C94-3DA1D04AE1C2}"><p14:media [^>]*r:embed="(rId\d+)"/>""").containsMatchIn(slide))
        val rels = parts.getValue("ppt/slides/_rels/slide1.xml.rels").decodeToString()
        assertTrue(rels, rels.contains("""Type="http://schemas.microsoft.com/office/2007/relationships/media" Target="../media/media1.mp4""""))
        assertTrue(rels, rels.contains("""Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/video" Target="../media/media1.mp4""""))
        assertTrue(parts.getValue("[Content_Types].xml").decodeToString().contains("""<Default Extension="mp4" ContentType="video/mp4"/>"""))
        assertArrayEquals(clip, parts.getValue("ppt/media/media1.mp4"))
        val back = PptxReader.read(bytes).slides[0].images[0]
        val m = back.media!!
        assertEquals(SlideMedia.Kind.VIDEO, m.kind); assertEquals("video/mp4", m.mime); assertEquals("clip.mp4", m.name)
        assertArrayEquals(clip, m.data); assertNull(m.link)
        assertEquals("the poster frame is the picture", "image/png", back.mime)
    }

    @Test fun aSoundAndALinkedVideoRoundTrip() {
        val sound = SlideMedia(SlideMedia.Kind.AUDIO, "audio/mpeg", "sound".toByteArray(), null, "chime.mp3")
        val linked = SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", null, "https://example.com/talk.mp4", "talk.mp4")
        val bytes = write(deck(Slide().also { it.images.add(picture(sound)) }, Slide().also { it.images.add(picture(linked)) }))
        val parts = parts(bytes)
        assertTrue(parts.getValue("ppt/slides/slide1.xml").decodeToString().contains("<a:audioFile r:link="))
        val rels2 = parts.getValue("ppt/slides/_rels/slide2.xml.rels").decodeToString()
        assertTrue(rels2, rels2.contains("""Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/video" Target="https://example.com/talk.mp4" TargetMode="External""""))
        assertTrue("a link has no copy in the file", !parts.getValue("ppt/slides/slide2.xml").decodeToString().contains("p14:media"))
        val back = PptxReader.read(bytes)
        val s = back.slides[0].images[0].media!!
        assertEquals(SlideMedia.Kind.AUDIO, s.kind); assertEquals("chime.mp3", s.name); assertEquals("audio/mpeg", s.mime)
        val l = back.slides[1].images[0].media!!
        assertEquals("https://example.com/talk.mp4", l.link); assertNull(l.data); assertEquals("talk.mp4", l.name)
    }

    @Test fun aClipShownOnTwoSlidesIsOnePart() {
        val m = SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", clip, null, "clip.mp4")
        val parts = parts(write(deck(Slide().also { it.images.add(picture(m)) }, Slide().also { it.images.add(picture(m)) })))
        assertEquals(listOf("ppt/media/media1.mp4"), parts.keys.filter { it.endsWith(".mp4") })
    }

    @Test fun aDeckWithItsVideosKeptIsNotFlaggedButATransitionSoundIs() {
        // On a blank slide: in a content placeholder PowerPoint writes the video as the placeholder (ContentPlaceholderTest).
        val bytes = write(deck(Slide(layout = com.therealityteam.aeropages.model.SlideLayout.BLANK).also { it.images.add(picture(SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", clip, null, "clip.mp4"))) }))
        val dir = java.nio.file.Files.createTempDirectory("aero-media").toFile().apply { deleteOnExit() }
        val f = File(dir, "kept.pptx").apply { writeBytes(bytes) }
        assertEquals(emptySet<UnsupportedContent.Kind>(), UnsupportedContent.scan(f.path))
        // The same deck with a transition's sound — slide relationship of type audio to a part no picture plays.
        val withSound = ByteArrayOutputStream()
        ZipOutputStream(withSound).use { z ->
            parts(bytes).forEach { (name, data) ->
                val out = if (name == "ppt/slides/_rels/slide1.xml.rels") data.decodeToString().replace("</Relationships>",
                    """<Relationship Id="rId90" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/audio" Target="../media/sound1.wav"/></Relationships>""").toByteArray() else data
                z.putNextEntry(ZipEntry(name)); z.write(out); z.closeEntry()
            }
            z.putNextEntry(ZipEntry("ppt/media/sound1.wav")); z.write(ByteArray(8)); z.closeEntry()
        }
        val g = File(dir, "sound.pptx").apply { writeBytes(withSound.toByteArray()) }
        assertEquals(setOf(UnsupportedContent.Kind.MEDIA), UnsupportedContent.scan(g.path))
    }

    private fun writeOdp(p: Presentation) = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
    private fun describe(m: SlideMedia?) = m?.let { listOf(it.kind, it.mime, it.name, it.data?.decodeToString(), it.link) }

    @Test fun anOpenDocumentPresentationKeepsThemAsLibreOfficeDoes() {
        val video = SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", clip, null, "clip.mp4")
        val sound = SlideMedia(SlideMedia.Kind.AUDIO, "audio/mpeg", "sound".toByteArray(), null, "chime.mp3")
        val linked = SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", null, "https://example.com/talk.mp4", "talk.mp4")
        val bytes = writeOdp(deck(Slide().also { it.images.add(picture(video)) }, Slide().also { it.images.add(picture(sound)); it.images.add(picture(video)) },
            Slide().also { it.images.add(picture(linked)) }))
        val parts = parts(bytes)
        val content = parts.getValue("content.xml").decodeToString()
        assertTrue(content, Regex("""<draw:frame draw:name="clip.mp4" [^>]*><draw:plugin xlink:href="Media/media1.mp4" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad" draw:mime-type="video/mp4">""" +
            """(<draw:param [^>]*/>)+</draw:plugin><draw:image xlink:href="Pictures/image\d+.png"""").containsMatchIn(content))
        assertTrue(content, content.contains("""<draw:plugin xlink:href="https://example.com/talk.mp4" """))
        assertEquals("a clip on two slides is one part", listOf("Media/media1.mp4", "Media/media2.mp3"), parts.keys.filter { it.startsWith("Media/") }.sorted())
        assertArrayEquals(clip, parts.getValue("Media/media1.mp4"))
        val manifest = parts.getValue("META-INF/manifest.xml").decodeToString()
        assertTrue(manifest, manifest.contains("""manifest:full-path="Media/media1.mp4" manifest:media-type="video/mp4""""))
        assertTrue(manifest, manifest.contains("""manifest:full-path="Media/media2.mp3" manifest:media-type="audio/mpeg""""))
        val back = OdpReader.read(bytes)
        assertEquals(listOf(listOf(describe(video)), listOf(describe(sound), describe(video)), listOf(describe(linked))), back.slides.map { s -> s.images.map { describe(it.media) } })
        assertEquals("the poster is the picture", "image/png", back.slides[0].images[0].mime)
        val f = File.createTempFile("aero-media", ".odp").apply { writeBytes(bytes); deleteOnExit() }
        assertEquals("nothing in it that AeroDesk would drop", emptySet<UnsupportedContent.Kind>(), UnsupportedContent.scan(f.path))
    }

    private fun odp(content: String, vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            (listOf("mimetype" to "application/vnd.oasis.opendocument.presentation".toByteArray(), "content.xml" to content.toByteArray()) + files).forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry()
            }
        }
    }.toByteArray()

    private fun page(vararg frames: String) = """<office:document-content ${OdfPackage.NS}><office:body><office:presentation><draw:page draw:name="p1">""" +
        frames.joinToString("") + "</draw:page></office:presentation></office:body></office:document-content>"

    @Test fun libreOfficesVideosAndSoundsAreRead() {
        val bytes = odp(page(
            // As LibreOffice saves a deck it converted from PowerPoint: the file where the conversion left it, a poster of its own, the frame named for the clip.
            """<draw:frame draw:name="clip.mp4" draw:layer="layout" svg:width="20cm" svg:height="11.25cm" svg:x="2cm" svg:y="3cm" presentation:class="object">""" +
                """<draw:plugin xlink:href="ppt/media/media1.mp4" xlink:type="simple" xlink:show="embed" xlink:actuate="onLoad" draw:mime-type="video/mp4">""" +
                """<draw:param draw:name="Loop" draw:value="false"/></draw:plugin><draw:image xlink:href="Pictures/MediaPreview1.png"/></draw:frame>""",
            // Older LibreOffice: its catch-all type, a package URL to Media/ (escaped), no poster.
            """<draw:frame draw:layer="layout" svg:width="2cm" svg:height="2cm" svg:x="1cm" svg:y="1cm">""" +
                """<draw:plugin xlink:href="vnd.sun.star.Package:Media/my%20chime.mp3" draw:mime-type="application/vnd.sun.star.media"/></draw:frame>""",
            // A plug-in that plays no sound or video: its picture alone.
            """<draw:frame draw:layer="layout" svg:width="2cm" svg:height="2cm" svg:x="5cm" svg:y="1cm">""" +
                """<draw:plugin xlink:href="Plugins/game.swf" draw:mime-type="application/x-shockwave-flash"/><draw:image xlink:href="Pictures/MediaPreview1.png"/></draw:frame>""",
        ), "ppt/media/media1.mp4" to clip, "Media/my chime.mp3" to "sound".toByteArray(), "Pictures/MediaPreview1.png" to poster, "Plugins/game.swf" to ByteArray(4))
        val deck = OdpReader.read(bytes)
        val images = deck.slides.single().images
        assertEquals(listOf(
            listOf(SlideMedia.Kind.VIDEO, "video/mp4", "clip.mp4", "clip bytes", null),
            listOf(SlideMedia.Kind.AUDIO, "audio/mpeg", "my chime.mp3", "sound", null),
            null,
        ), images.map { describe(it.media) })
        assertEquals("a sound without a poster shows a black frame", "image/png", images[1].mime)
        assertEquals("in its frame's place", listOf(28.35f, 28.35f, 56.69f, 56.69f), images[1].let { listOf(it.xPct, it.yPct, it.wPct, it.hPct) }
            .mapIndexed { i, v -> Math.round(v * (if (i % 2 == 0) deck.slideSize.widthPt else deck.slideSize.heightPt) * 100) / 100f })
        val dir = java.nio.file.Files.createTempDirectory("aero-media").toFile().apply { deleteOnExit() }
        val f = File(dir, "lo.odp").apply { writeBytes(bytes) }
        assertEquals("its videos and sounds are kept; the Flash game is not", setOf(UnsupportedContent.Kind.EMBEDDED_OBJECTS), UnsupportedContent.scan(f.path))
    }

    @Test fun aSlideTransitionsSoundIsNotKept() {
        val styles = """<office:automatic-styles><style:style style:name="dp1" style:family="drawing-page"><style:drawing-page-properties presentation:transition-type="automatic">""" +
            """<presentation:sound xlink:href="../../share/gallery/sounds/apert.wav" xlink:type="simple" xlink:show="new" xlink:actuate="onRequest"/></style:drawing-page-properties></style:style></office:automatic-styles>"""
        val bytes = odp(page("").replace("<office:body>", "$styles<office:body>"))
        val f = File.createTempFile("aero-sound", ".odp").apply { writeBytes(bytes); deleteOnExit() }
        assertEquals(setOf(UnsupportedContent.Kind.MEDIA), UnsupportedContent.scan(f.path))
    }

    @Test fun aCopyOfThePictureKeepsItsMedia() {
        val m = SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", clip, null, "clip.mp4")
        assertTrue(picture(m).copyOf().media === m)
    }
}
