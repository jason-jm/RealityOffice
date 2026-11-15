package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.therealityteam.aeropages.editor.SlideEditor
import com.therealityteam.aeropages.model.Presentation
import com.therealityteam.aeropages.model.Slide
import com.therealityteam.aeropages.model.SlideImage
import com.therealityteam.aeropages.model.SlideLayout
import com.therealityteam.aeropages.model.SlideMedia
import com.therealityteam.aeropages.ui.PictureView
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In the editor, a selected sound or video has PowerPoint's play bar under it: Play plays it (its
 * clock shows its length), Pause pauses it; a picture that is not selected has none.
 *
 *   ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.therealityteam.aeropages.SlideMediaBarTest
 */
@RunWith(AndroidJUnit4::class)
class SlideMediaBarTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun asset(name: String): ByteArray = InstrumentationRegistry.getInstrumentation().context.assets.open("media/$name").use { it.readBytes() }
    private fun str(id: Int, vararg args: Any): String = target.getString(id, *args)
    private fun state(s: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s)

    private val editor = SlideEditor()
    private lateinit var video: SlideImage

    @Before
    fun setUp() {
        TestSupport.installCrashLogger()
        video = SlideImage(asset("poster.png"), "image/png", 640f, 360f, 0.3f, 0.2f, 0.4f, 0.4f,
            media = SlideMedia(SlideMedia.Kind.VIDEO, "video/mp4", asset("clip.mp4"), null, "clip.mp4"))
        editor.load(Presentation(mutableListOf(Slide(layout = SlideLayout.BLANK).also { it.images.add(video) })))
        rule.setContent { PicoTheme { Box(Modifier.size(800.dp, 450.dp)) { PictureView(editor, video, 800.dp, 450.dp) } } }
        // The test's clock moved on by waitFor while the clip plays in real time (see SlideMediaPlaybackTest).
        rule.mainClock.autoAdvance = false
    }

    private fun waitFor(m: SemanticsMatcher) {
        val end = android.os.SystemClock.uptimeMillis() + TIMEOUT
        while (true) {
            // A tenth of a second a look: what a playing clip's clock waits for between its readings.
            rule.mainClock.advanceTimeBy(100)
            if (rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()) return
            if (android.os.SystemClock.uptimeMillis() > end) {
                runCatching { rule.onRoot().printToString(Int.MAX_VALUE).lines().forEach { android.util.Log.i("MediaBarDump", it) } }
                throw AssertionError("not seen in ${TIMEOUT / 1000} s: ${m.description}")
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun aSelectedVideoHasAPlayBarThatPlaysAndPausesIt() {
        val play = hasContentDescription(str(R.string.a11y_media_play))
        rule.mainClock.advanceTimeByFrame()
        assert(rule.onAllNodes(play).fetchSemanticsNodes().isEmpty()) { "no bar while the picture is not selected" }
        rule.runOnUiThread { editor.selectImage(video) }
        waitFor(play)
        rule.onAllNodes(play).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        val pause = hasContentDescription(str(R.string.a11y_media_pause))
        waitFor(pause and state(str(R.string.media_state_playing)))
        waitFor(hasText(" / 0:08", substring = true))
        rule.onAllNodes(pause).onFirst().performSemanticsAction(SemanticsActions.OnClick)
        waitFor(play and state(str(R.string.media_state_paused)))
    }

    private companion object { const val TIMEOUT = 20_000L }
}
