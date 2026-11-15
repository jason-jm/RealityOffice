package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasAnyAncestor
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

/** General alignment, Align Left toggling, long text running on over empty cells, and numbers fitted rather than cut. */
@RunWith(AndroidJUnit4::class)
class SheetsAlignmentTest {
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

    private fun type(ref: String, text: String) {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement(ref)
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText(ref)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasSetTextAction() and hasAnySibling(hasText("fx")), useUnmergedTree = true).performTextReplacement(text)
        rule.waitUntil(10_000) { rule.onAllNodes(cell(ref) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun cellBounds(ref: String): Rect = rule.onAllNodes(cell(ref)).onFirst().fetchSemanticsNode().boundsInRoot

    /** The bounds of the text [text] drawn inside cell [ref]. */
    private fun textBounds(ref: String, text: String): Rect =
        rule.onAllNodes(hasText(text) and hasAnyAncestor(cell(ref)), useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot

    private fun sitsRight(ref: String, text: String) = textBounds(ref, text).center.x > cellBounds(ref).center.x
    private fun sitsLeft(ref: String, text: String) = textBounds(ref, text).center.x < cellBounds(ref).center.x

    @Test
    fun longTextRunsOnUntilTheNextValueAndNumbersNeverShowCutOff() {
        val long = "A long title that runs on and on"
        type("B2", long)
        // The text reaches past B2 over the empty cells to its right …
        rule.waitUntil(10_000) { textBounds("B2", long).right > cellBounds("B2").right + 10f }
        // … and stops where C2 has a value.
        type("C2", "x")
        rule.waitUntil(10_000) { textBounds("B2", long).right <= cellBounds("B2").right + 1f }
        // A number too wide for its column goes to E notation instead of being cut off.
        goTo("B4")
        rule.onNode(hasSetTextAction() and hasAnySibling(hasText("fx")), useUnmergedTree = true).performTextReplacement("123456789012345678901234")
        rule.waitUntil(10_000) { rule.onAllNodes(cell("B4") and hasText("1.23457E+23")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun goTo(ref: String) {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement(ref)
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText(ref)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun numbersSitRightTextLeftAndAlignLeftToggles() {
        type("B2", "123")
        type("C2", "abc")
        rule.waitUntil(10_000) { sitsRight("B2", "123") }
        rule.waitUntil(10_000) { sitsLeft("C2", "abc") }
        // Align Left on the number: it moves left; pressed again, General puts it back on the right.
        type("B2", "123")
        val alignLeft = hasContentDescription(context.getString(R.string.align_left)) and hasClickAction()
        rule.onAllNodes(alignLeft).onFirst().performClick()
        rule.waitUntil(10_000) { sitsLeft("B2", "123") }
        rule.onAllNodes(alignLeft).onFirst().performClick()
        rule.waitUntil(10_000) { sitsRight("B2", "123") }
    }
}
