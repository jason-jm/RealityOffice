package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
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

/** Format Cells → Custom: a typed code with its live preview, a preset with a red negative, and Text from the ribbon. */
@RunWith(AndroidJUnit4::class)
class SheetsFormatCodeTest {
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

    /** Goes to [ref] and types [text] into the formula bar; waits for the cell to show [shows]. */
    private fun type(ref: String, text: String, shows: String = text) {
        goTo(ref)
        rule.onNode(hasSetTextAction() and hasAnySibling(hasText("fx")), useUnmergedTree = true).performTextReplacement(text)
        rule.waitUntil(10_000) { rule.onAllNodes(cell(ref) and hasText(shows)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(text) and hasClickAction()).onFirst().performClick()
    }

    private fun openFormatCells() {
        rule.onAllNodes(hasContentDescription(context.getString(R.string.format_cells)) and hasClickAction()).onFirst().performClick()
        click(context.getString(R.string.numfmt_custom))
    }

    @Test
    fun aTypedCodeShowsInThePreviewThenInTheCell() {
        type("B2", "1500000")
        openFormatCells()
        val field = hasContentDescription(context.getString(R.string.format_code)) and hasSetTextAction()
        rule.waitUntil(10_000) { rule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(field).onFirst().performTextReplacement("0.0,,\"M\"")
        // The preview follows the code as it is typed.
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("1.5M")).fetchSemanticsNodes().isNotEmpty() }
        click(context.getString(R.string.apply))
        rule.waitUntil(10_000) { rule.onAllNodes(cell("B2") and hasText("1.5M")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aPresetAndTheTextFormat() {
        type("C3", "-1234.5")
        openFormatCells()
        click("#,##0.00;[Red]-#,##0.00")
        click(context.getString(R.string.apply))
        rule.waitUntil(10_000) { rule.onAllNodes(cell("C3") and hasText("-1,234.50")).fetchSemanticsNodes().isNotEmpty() }
        // Text from the ribbon's number format list: the zeros typed next stay.
        goTo("D4")
        rule.onAllNodes(hasContentDescription(context.getString(R.string.number_format)) and hasClickAction()).onFirst().performClick()
        click(context.getString(R.string.numfmt_text))
        type("D4", "00123")
    }
}
