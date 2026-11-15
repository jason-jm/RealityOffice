package com.therealityteam.aeropages

import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.format.odf.OdpReader
import com.therealityteam.aeropages.format.odf.OdpWriter
import com.therealityteam.aeropages.format.pptx.PptxReader
import com.therealityteam.aeropages.format.pptx.PptxWriter
import com.therealityteam.aeropages.format.MediaTypes
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.SlideMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Insert → Video / Audio: the media on the slide as the picture standing for it, kept in pptx and ODP. */
class SlideMediaInsertTest {

    /** A tiny PNG (1 × 1, opaque white): the poster. */
    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52, 0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0,
        0x90.toByte(), 0x77, 0x53, 0xDE.toByte(), 0, 0, 0, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xFF.toByte(),
        0xFF.toByte(), 0x3F, 0, 0x05, 0xFE.toByte(), 0x02, 0xFE.toByte(), 0xA7.toByte(), 0x35, 0x81.toByte(), 0x84.toByte(), 0, 0, 0, 0,
        0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )
    private val video = ByteArray(4096) { (it * 31).toByte() }
    private val sound = ByteArray(2048) { (it * 7).toByte() }

    private fun deck(): SlideEditor {
        val e = SlideEditor().apply { load(Presentation.blank()) }
        e.insertMedia(SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", video, null, "clip.mp4"), png, "image/png", 480f, 270f)
        e.insertMedia(SlideMedia(SlideMedia.Kind.AUDIO, "audio/mp4", sound, null, "tone.m4a"), png, "image/png", 192f, 192f)
        return e
    }

    @Test
    fun theMediaTypesInsertTakes() {
        assertEquals(SlideMedia.Kind.VIDEO to "video/mp4", MediaTypes.of("Clip.MP4"))
        assertEquals(SlideMedia.Kind.VIDEO to "video/quicktime", MediaTypes.of("a.mov"))
        assertEquals(SlideMedia.Kind.AUDIO to "audio/mpeg", MediaTypes.of("song.mp3"))
        assertNull(MediaTypes.of("notes.txt"))
    }

    @Test
    fun aVideoGoesInLargeASoundSmallBothInTheMiddle() {
        val e = deck()
        val (v, a) = e.slide.images
        assertEquals(SlideMedia.Kind.VIDEO, v.media!!.kind)
        assertTrue(v.wPct > 0.4f && a.wPct < 0.1f)
        assertEquals(0.5f, v.xPct + v.wPct / 2f, 0.001f)
        assertEquals(0.5f, a.yPct + a.hPct / 2f, 0.001f)
        assertTrue(e.selectedImage === a)
        e.undo()
        assertEquals(1, e.slide.images.size)
    }

    private fun assertKept(p: Presentation) {
        val media = p.slides[0].images.mapNotNull { it.media }
        assertEquals(listOf(SlideMedia.Kind.VIDEO, SlideMedia.Kind.AUDIO), media.map { it.kind })
        assertArrayEquals(video, media[0].data); assertArrayEquals(sound, media[1].data)
        assertEquals(listOf("video/mp4", "audio/mp4"), media.map { it.mime })
    }

    @Test
    fun powerPointAndOdpKeepIt() {
        val p = deck().presentation
        val pptx = ByteArrayOutputStream().also { PptxWriter.write(p, it) }.toByteArray()
        assertKept(PptxReader.read(pptx))
        val odp = ByteArrayOutputStream().also { OdpWriter.write(p, it) }.toByteArray()
        assertKept(OdpReader.read(odp))
    }
}
