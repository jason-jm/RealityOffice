package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
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

/** Paste Values from the ribbon, and Paste Special's Transpose from its dialog. */
@RunWith(AndroidJUnit4::class)
class SheetsPasteSpecialTest {
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

    private fun goTo(ref: String) {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement(ref)
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText(ref)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun type(ref: String, text: String, shows: String = text) {
        goTo(ref)
        rule.onNode(formulaBar, useUnmergedTree = true).performTextReplacement(text)
        rule.waitUntil(10_000) { rule.onAllNodes(cell(ref) and hasText(shows)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ribbon(nameRes: Int) =
        rule.onAllNodes(hasContentDescription(context.getString(nameRes)) and hasClickAction()).onFirst().performClick()

    private fun barShows(text: String) = formulaBar and SemanticsMatcher("edit text $text") { node ->
        node.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == text
    }

    @Test
    fun pasteValuesThenTransposeFromTheDialog() {
        type("A1", "5")
        type("A2", "=A1*2", shows = "10")
        goTo("A1:A2")
        ribbon(R.string.copy)
        // Paste Values: C2 holds 10 itself, not the formula.
        goTo("C1")
        ribbon(R.string.paste_values)
        rule.waitUntil(10_000) { rule.onAllNodes(cell("C2") and hasText("10")).fetchSemanticsNodes().isNotEmpty() }
        goTo("C2")
        rule.waitUntil(10_000) { rule.onAllNodes(barShows("10"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        // Paste Special → Transpose: the column becomes a row, its formula turned with it.
        goTo("D1")
        // Paste ▾ (Paste Options) → Paste Special…, as in Excel.
        ribbon(R.string.paste_options)
        val special = hasText(context.getString(R.string.paste_special)) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(special).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(special).onFirst().performClick()
        val transpose = hasText(context.getString(R.string.paste_transpose)) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(transpose).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(transpose).onFirst().performClick()
        rule.onAllNodes(hasText(context.getString(R.string.btn_ok)) and hasClickAction()).onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(cell("E1") and hasText("10")).fetchSemanticsNodes().isNotEmpty() }
        goTo("E1")
        rule.waitUntil(10_000) { rule.onAllNodes(barShows("=D1*2"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
