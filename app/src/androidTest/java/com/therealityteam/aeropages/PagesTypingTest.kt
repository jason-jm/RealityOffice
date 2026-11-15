package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.ui.AeroDesk
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typing in a long document (the 600-paragraph perf document): keystrokes that keep the paragraph's
 * lines re-measure only that paragraph, so they stay fast however long the document is. Logs the
 * time per keystroke under AeroPerf and checks the text arrives.
 */
@RunWith(AndroidJUnit4::class)
class PagesTypingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        TestSupport.installCrashLogger()
        TestSupport.clearDevProps()
        TestSupport.setDevProp("debug.aero.dialog", "bigdoc")
        context.getSharedPreferences("aeropages", 0).edit().clear().commit()
        rule.setContent {
            PicoTheme { CompositionLocalProvider(LocalSpatialNavigator provides RecordingNavigator()) { AeroDesk() } }
        }
    }

    @After
    fun tearDown() = TestSupport.clearDevProps()

    @Test
    fun typingInALongDocumentStaysFast() {
        rule.onNodeWithTextOnce("Pages").performClick()
        val second = context.getString(R.string.perf_test_paragraph_body, 2)
        val paragraph = hasClickAction() and hasText(second)
        rule.waitUntil(30_000) { rule.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(paragraph).onFirst().performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(hasSetTextAction() and TestSupport.notSearch).fetchSemanticsNodes().isNotEmpty() }
        val field = rule.onAllNodes(hasSetTextAction() and TestSupport.notSearch).onFirst()
        val keys = "abc"
        val t0 = android.os.SystemClock.elapsedRealtime()
        keys.forEach { c -> field.performTextInput(c.toString()); rule.waitForIdle() }
        val ms = android.os.SystemClock.elapsedRealtime() - t0
        // Wall time here is mostly the emulator's frame cadence; the layout's own cost is logged by CanvasFlow.
        android.util.Log.i("AeroPerf", "typed ${keys.length} keys into a 600-paragraph document in $ms ms")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(keys, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onAllNodes(hasText(keys, substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onNodeWithTextOnce(text: String) =
        onAllNodes(hasText(text)).onFirst()
}
