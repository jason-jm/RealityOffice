package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.TestSupport.describe
import com.therealityteam.aeropages.TestSupport.isLabelled
import com.therealityteam.aeropages.ui.AeroDesk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Accessibility sweep: on the launcher and on every ribbon tab of the three modules, every control
 * with a click action must carry a name — text, a content description, an editable value or a
 * state description — so a screen reader never lands on a nameless button. Offenders are listed
 * with their bounds in the failure message.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int): String = context.getString(id)

    @Before
    fun setUp() {
        TestSupport.installCrashLogger()
        TestSupport.clearDevProps()
        context.getSharedPreferences("aeropages", 0).edit().clear().commit()
        rule.setContent {
            PicoTheme { CompositionLocalProvider(LocalSpatialNavigator provides RecordingNavigator()) { AeroDesk() } }
        }
    }

    @Test
    fun launcherControlsAreNamed() = sweep("launcher")

    @Test
    fun pagesRibbonControlsAreNamed() {
        rule.onNodeWithText("Pages").performClick()
        tabs(R.string.tab_home, R.string.tab_insert, R.string.tab_design, R.string.tab_layout, R.string.tab_references, R.string.tab_review, R.string.tab_view)
    }

    @Test
    fun sheetsRibbonControlsAreNamed() {
        rule.onNodeWithText("Sheets").performClick()
        tabs(R.string.tab_home, R.string.tab_insert, R.string.tab_page_layout, R.string.formulas, R.string.tab_data, R.string.tab_review, R.string.tab_view)
    }

    @Test
    fun slidesRibbonControlsAreNamed() {
        rule.onNodeWithText("Slides").performClick()
        tabs(R.string.tab_home, R.string.tab_insert, R.string.tab_design, R.string.tab_transitions, R.string.tab_animations, R.string.tab_slide_show, R.string.tab_review, R.string.tab_view)
    }

    private fun tabs(vararg ids: Int) {
        ids.forEach { id ->
            val tab = hasText(str(id)) and hasClickAction()
            rule.waitUntil(TIMEOUT) { rule.onAllNodes(tab).fetchSemanticsNodes().isNotEmpty() }
            rule.onAllNodes(tab).onFirst().performClick()
            rule.waitForIdle()
            sweep(str(id))
        }
    }

    private fun sweep(where: String) {
        rule.waitForIdle()
        val offenders = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().filter { !it.isLabelled() }.map { it.describe() }
        offenders.forEach { android.util.Log.w("AccessibilityTest", "[$where] unnamed clickable: $it") }
        assertTrue("Unnamed clickables on $where:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    private companion object { const val TIMEOUT = 20_000L }
}
