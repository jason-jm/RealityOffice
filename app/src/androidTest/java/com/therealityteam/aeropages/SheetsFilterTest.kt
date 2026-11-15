package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.ui.AeroDesk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Data → Filter: buttons on the header row, a column's menu hiding a value, and clearing it. */
@RunWith(AndroidJUnit4::class)
class SheetsFilterTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        TestSupport.installCrashLogger()
        TestSupport.clearDevProps()
        context.getSharedPreferences("aeropages", 0).edit().clear().commit()
        rule.setContent {
            PicoTheme { CompositionLocalProvider(LocalSpatialNavigator provides RecordingNavigator()) { AeroDesk() } }
        }
        rule.onAllNodes(hasText("Sheets")).onFirst().performClick()
        rule.waitUntil(20_000) { rule.onAllNodes(cell("B2")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun cell(ref: String) = hasContentDescription(ref)
    private val nameBox get() = hasContentDescription(context.getString(R.string.name_box))

    private fun goTo(ref: String) {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement(ref)
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText(ref)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun type(ref: String, text: String) {
        goTo(ref)
        rule.onNode(hasSetTextAction() and hasAnySibling(hasText("fx")), useUnmergedTree = true).performTextReplacement(text)
        rule.waitUntil(10_000) { rule.onAllNodes(cell(ref) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clickDescription(description: String) {
        val m = hasContentDescription(description) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().performClick()
    }

    private fun clickText(text: String) {
        val m = hasText(text) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().performClick()
    }

    @Test
    fun aHeaderButtonHidesAValueAndClearsIt() {
        type("A1", "Region"); type("A2", "North"); type("A3", "South"); type("A4", "North")
        type("B1", "Sales"); type("B2", "10"); type("B3", "20"); type("B4", "30")
        goTo("A2")
        clickText(context.getString(R.string.tab_data))
        clickDescription(context.getString(R.string.filter))
        // Region's button: untick South.
        clickDescription(context.getString(R.string.a11y_filter_button, "A"))
        // The menu's row for South (a checkbox; the grid's own South cell is not one).
        val south = hasText("South") and isToggleable() and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(south and isOn()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(south).onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(south and isOff()).fetchSemanticsNodes().isNotEmpty() }
        clickText(context.getString(R.string.btn_ok))
        rule.waitUntil(10_000) { rule.onAllNodes(cell("A3")).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodes(cell("A4") and hasText("North")).fetchSemanticsNodes().isNotEmpty() }
        // The menu again: Clear Filter From "Region" brings the row back.
        clickDescription(context.getString(R.string.a11y_filter_button, "A"))
        clickText(context.getString(R.string.clear_filter_from, "Region"))
        rule.waitUntil(10_000) { rule.onAllNodes(cell("A3") and hasText("South")).fetchSemanticsNodes().isNotEmpty() }
    }
}
