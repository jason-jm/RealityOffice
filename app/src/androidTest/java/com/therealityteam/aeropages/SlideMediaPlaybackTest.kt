package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideMedia
import com.therealityteam.aeropages.ui.SlideShow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * In the show, a slide's video or sound plays when tapped — from the copy the presentation holds, or
 * a linked file next to it — pauses when tapped again, and a missing one says so; a tap anywhere else
 * still turns the page. The clips are the test APK's assets (tools/make-test-media.sh).
 *
 *   ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.therealityteam.aeropages.SlideMediaPlaybackTest
 */
@RunWith(AndroidJUnit4::class)
class SlideMediaPlaybackTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun asset(name: String): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open("media/$name").use { it.readBytes() }
    private fun str(id: Int, vararg args: Any): String = target.getString(id, *args)

    private val editor = SlideEditor()

    private fun slide(media: SlideMedia, poster: ByteArray, w: Float = 0.6f, h: Float = 0.6f) =
        Slide(layout = SlideLayout.BLANK).also { it.images.add(SlideImage(poster, "image/png", 640f, 360f, 0.2f, 0.2f, w, h, media = media)) }

    @Before
    fun setUp() {
        TestSupport.installCrashLogger()
        TestSupport.clearDevProps()
        val poster = asset("poster.png")
        // A linked clip next to the "presentation" (its folder: the app's files folder).
        val dir = File(target.getExternalFilesDir(null), "playback-test").apply { mkdirs() }
        File(dir, "linked.mp4").writeBytes(asset("clip.mp4"))
        val deck = Presentation(mutableListOf(
            slide(SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", asset("clip.mp4"), null, "clip.mp4"), poster),
            slide(SlideMedia(SlideMedia.Kind.AUDIO, "audio/mp4", asset("tone.m4a"), null, "tone.m4a"), poster, 0.15f, 0.2f),
            slide(SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", null, "C:\\Users\\someone\\Videos\\linked.mp4", "linked.mp4"), poster),
            slide(SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", null, "missing.mp4", "missing.mp4"), poster),
        ))
        deck.sourcePath = File(dir, "deck.pptx").path
        editor.load(deck)
        rule.setContent { PicoTheme { SlideShow(editor, fromStart = true, onExit = {}) } }
        // A playing clip's clock changes every tenth of a second: left to advance by itself, the test's
        // clock would run on until the clip ends before any step could look at it. Moved on in waitFor
        // instead, while the clip plays in real time. (In the PICO shell the test's window, in the
        // background, draws about a frame a second, so what a step sees is checked, not when: the clip's
        // length, the pause, the end. The show's dev hook `showplay` shows the clock running in the app.)
        rule.mainClock.autoAdvance = false
    }

    private fun media(label: String) = hasContentDescription(label)
    private fun state(s: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s)

    private fun waitFor(m: SemanticsMatcher, timeout: Long = TIMEOUT) {
        val end = android.os.SystemClock.uptimeMillis() + timeout
        while (true) {
            // A tenth of a second a look: what a playing clip's clock waits for between its readings.
            rule.mainClock.advanceTimeBy(100)
            if (rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()) return
            if (android.os.SystemClock.uptimeMillis() > end) {
                // What the show held when it gave up, in logcat (tag MediaPlaybackDump).
                runCatching { rule.onRoot().printToString(Int.MAX_VALUE).lines().forEach { android.util.Log.i("MediaPlaybackDump", it) } }
                throw AssertionError("not seen in ${timeout / 1000} s: ${m.description}")
            }
            Thread.sleep(20)
        }
    }

    /** Taps [m] until [expected] shows (three seconds after each tap: well inside the clip's eight; a few taps at most). */
    private fun clickUntil(m: SemanticsMatcher, expected: SemanticsMatcher, taps: Int = 4) {
        repeat(taps) {
            click(m)
            val end = android.os.SystemClock.uptimeMillis() + 3_000
            while (android.os.SystemClock.uptimeMillis() < end) {
                rule.mainClock.advanceTimeBy(100)
                if (rule.onAllNodes(expected).fetchSemanticsNodes().isNotEmpty()) return
                Thread.sleep(20)
            }
        }
        waitFor(expected)
    }

    /** A tap, as its click action (the test's background window takes injected touches slowly, or not at all). */
    private fun click(m: SemanticsMatcher) {
        waitFor(m)
        rule.onAllNodes(m).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeByFrame()
    }

    @Test
    fun aVideoPlaysPausesAndPlaysOnWhenTapped() {
        val video = media(str(R.string.a11y_media_video, "clip.mp4"))
        click(video)
        waitFor(video and state(str(R.string.media_state_playing)))
        // A tap pauses it (in the slow test window a tap may come only after its end, and play it again: tapped till it pauses).
        clickUntil(video, video and state(str(R.string.media_state_paused)))
        // Its clock, of eight seconds, stays while it is paused.
        waitFor(hasText(" / 0:08", substring = true))
        click(video)
        waitFor(video and state(str(R.string.media_state_playing)))
        // To its end: neither playing nor paused, ready to play again.
        waitFor(video and state(""))
    }

    /** The show bar's Next, an icon button named for readers (a tap on the slide's middle could land on its picture). */
    private fun next() = click(hasContentDescription(str(R.string.nav_next)) and hasClickAction())

    @Test
    fun aSoundPlaysAndALinkedClipIsFoundNextToThePresentation() {
        next()
        val sound = media(str(R.string.a11y_media_audio, "tone.m4a"))
        click(sound)
        waitFor(sound and state(str(R.string.media_state_playing)))
        // It plays on to its end: neither playing nor paused, ready to play again.
        waitFor(sound and state(""))
        // A link made on another computer: the file of its name beside the deck plays.
        next()
        val linked = media(str(R.string.a11y_media_video, "linked.mp4"))
        click(linked)
        waitFor(linked and state(str(R.string.media_state_playing)))
    }

    @Test
    fun aMissingFileSaysSoAndATapBesideAPictureTurnsThePage() {
        repeat(3) { next() }
        waitFor(hasText("4 / 4"))
        click(media(str(R.string.a11y_media_video, "missing.mp4")))
        waitFor(hasText(str(R.string.media_not_found, "missing.mp4")))
        // The left third, at its top corner (off the slide's picture): back a page.
        rule.onNode(hasContentDescription(str(R.string.a11y_previous_slide))).performTouchInput { click(Offset(20f, 20f)) }
        waitFor(hasText("3 / 4"))
        // A tap on the picture plays it instead of turning the page.
        val linked = media(str(R.string.a11y_media_video, "linked.mp4"))
        click(linked)
        waitFor(linked and state(str(R.string.media_state_playing)))
        waitFor(hasText("3 / 4"))
    }

    private companion object { const val TIMEOUT = 20_000L }
}
