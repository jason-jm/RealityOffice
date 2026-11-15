package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.SlideMedia
import com.therealityteam.aeropages.ui.MediaSources
import com.therealityteam.aeropages.ui.MediaTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Where the show plays a slide's video or sound from: its copy, written out once, or the file its link names. */
class MediaSourcesTest {

    private val deck = File("/sdcard/Documents/Talks")
    private fun resolve(link: String, vararg files: String) = MediaSources.resolveLink(link, deck) { it.path in files }
    private fun local(path: String) = MediaTarget.Local(File(path))

    @Test fun aWebLinkIsForTheBrowser() {
        assertEquals(MediaTarget.Web("https://example.com/talk.mp4"), resolve("https://example.com/talk.mp4"))
        assertEquals(MediaTarget.Web("HTTP://example.com/a.mp3"), resolve(" HTTP://example.com/a.mp3 "))
    }

    @Test fun aFileUrlAnAbsolutePathAndOneRelativeToTheDeck() {
        assertEquals(local("/sdcard/Movies/my clip.mp4"), resolve("file:///sdcard/Movies/my%20clip.mp4", "/sdcard/Movies/my clip.mp4"))
        assertEquals(local("/sdcard/Movies/a.mp4"), resolve("/sdcard/Movies/a.mp4", "/sdcard/Movies/a.mp4"))
        assertEquals(local("/sdcard/Documents/Videos/a.mp4"), resolve("../Videos/a.mp4", "/sdcard/Documents/Videos/a.mp4"))
        assertEquals("a Windows-style relative path", local("/sdcard/Documents/Talks/media/a.mp4"), resolve("media\\a.mp4", "/sdcard/Documents/Talks/media/a.mp4"))
    }

    @Test fun aLinkMadeOnAnotherComputerIsLookedForNextToTheDeck() {
        val beside = "/sdcard/Documents/Talks/talk.mp4"
        assertEquals(local(beside), resolve("C:\\Users\\me\\Videos\\talk.mp4", beside))
        assertEquals(local(beside), resolve("\\\\server\\share\\talk.mp4", beside))
        assertEquals(local(beside), resolve("../elsewhere/talk.mp4", beside))
        assertEquals(local(beside), resolve("file:///C:/Users/me/talk.mp4", beside))
        assertEquals(MediaTarget.Missing("talk.mp4"), resolve("C:\\Users\\me\\Videos\\talk.mp4"))
        assertEquals("a new deck has no folder to look in", MediaTarget.Missing("talk.mp4"), MediaSources.resolveLink("talk.mp4", null) { true })
    }

    @Test fun fileNamesOfPathsAndUrls() {
        assertEquals("talk.mp4", MediaSources.fileName("C:\\Users\\me\\talk.mp4"))
        assertEquals("my clip.mp4", MediaSources.fileName("https://example.com/v/my%20clip.mp4?t=10#start"))
        assertEquals("a+b.mp3", MediaSources.fileName("/sdcard/a+b.mp3"))
    }

    @Test fun theCopiesPlayedLongestAgoGoPastTheLimit() {
        val dir = java.nio.file.Files.createTempDirectory("aero-slide-media").toFile().apply { deleteOnExit() }
        val files = (1..4).map { i -> File(dir, "c$i.mp4").apply { writeBytes(ByteArray(100)); setLastModified(1_000_000L * i) } }
        // The newest is just written; the oldest is the one kept (it is being played).
        MediaSources.prune(dir, keep = files[0], limit = 250)
        assertEquals(listOf("c1.mp4", "c4.mp4"), dir.listFiles()!!.map { it.name }.sorted())
        MediaSources.prune(dir, keep = files[3], limit = 1000)
        assertEquals("under the limit nothing goes", 2, dir.listFiles()!!.size)
    }

    @Test fun aCopyIsPlayedFromAFileNamedForItsBytes() {
        val data = byteArrayOf(1, 2, 3)
        val name = MediaSources.cacheName(SlideMedia(SlideMedia.Kind.VIDEO, "video/quicktime", data, null, "Clip.MOV"), data)
        assertTrue(name, Regex("[0-9a-f]{24}\\.mov").matches(name))
        assertEquals("the same bytes, the same file", name, MediaSources.cacheName(SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", data.copyOf(), null, "other.mov"), data.copyOf()))
        assertNotEquals(name, MediaSources.cacheName(SlideMedia(SlideMedia.Kind.VIDEO, "video/quicktime", byteArrayOf(4), null, "Clip.MOV"), byteArrayOf(4)))
        assertTrue("no extension of its own: its type's", MediaSources.cacheName(SlideMedia(SlideMedia.Kind.AUDIO, "audio/mpeg", data, null, "sound"), data).endsWith(".mp3"))
    }
}
