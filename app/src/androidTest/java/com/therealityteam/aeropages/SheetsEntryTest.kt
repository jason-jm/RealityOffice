package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnySibling
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

/** Typing an entry: Enter in the formula bar reads it as Excel does (15% is 0.15) and moves down. */
@RunWith(AndroidJUnit4::class)
class SheetsEntryTest {
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
    private val formulaBar = hasSetTextAction() and hasAnySibling(hasText("fx"))

    private fun awaitNameBox(ref: String) =
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText(ref)).fetchSemanticsNodes().isNotEmpty() }

    private fun goTo(ref: String) {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement(ref)
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        awaitNameBox(ref)
    }

    /** The formula bar field holding exactly [text]. */
    private fun barShows(text: String) = formulaBar and SemanticsMatcher("edit text $text") { node ->
        node.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == text
    }

    @Test
    fun enterReadsThePercentageAndMovesDown() {
        goTo("B2")
        rule.onNode(formulaBar, useUnmergedTree = true).performTextReplacement("15%")
        rule.onNode(formulaBar, useUnmergedTree = true).performImeAction()
        awaitNameBox("B3")
        // B2 now holds 0.15: a formula on it counts it.
        rule.onNode(formulaBar, useUnmergedTree = true).performTextReplacement("=B2*200")
        rule.waitUntil(10_000) { rule.onAllNodes(cell("B3") and hasText("30")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodes(cell("B2") and hasText("15%")).fetchSemanticsNodes().isNotEmpty() }
        // Back on B2 the bar shows it as typed, not as 0.15.
        goTo("B2")
        rule.waitUntil(10_000) { rule.onAllNodes(barShows("15%"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
