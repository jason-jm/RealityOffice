package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.ui.AeroDesk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Excel's keys on a real grid: Ctrl+↓ to the end of the data, Ctrl+; today's date, F4 a reference's $. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SheetsShortcutsTest {
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

    private fun enter(text: String) {
        rule.onNode(formulaBar, useUnmergedTree = true).performTextReplacement(text)
        rule.onNode(formulaBar, useUnmergedTree = true).performImeAction()
    }

    private fun barShows(text: String) = formulaBar and SemanticsMatcher("edit text $text") { node ->
        node.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == text
    }

    @Test
    fun ctrlDownGoesToTheEndOfTheData() {
        goTo("A1")
        listOf("a", "b", "c", "d").forEach { enter(it) }
        awaitNameBox("A5")
        goTo("A1")
        rule.onAllNodes(cell("A1")).onFirst().performClick()
        rule.onAllNodes(cell("A1")).onFirst().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.DirectionDown) } }
        awaitNameBox("A4")
    }

    @Test
    fun ctrlSemicolonEntersTodaysDate() {
        goTo("C3")
        rule.onAllNodes(cell("C3")).onFirst().performClick()
        rule.onAllNodes(cell("C3")).onFirst().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Semicolon) } }
        val day = java.time.LocalDate.now().dayOfMonth.toString()
        rule.waitUntil(10_000) {
            rule.onAllNodes(cell("C3")).fetchSemanticsNodes().any { n ->
                n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString { it.text }?.contains(day) == true
            }
        }
    }

    @Test
    fun f4TurnsAReferenceAbsolute() {
        goTo("D1")
        rule.onNode(formulaBar, useUnmergedTree = true).performClick()
        rule.onNode(formulaBar, useUnmergedTree = true).performTextReplacement("=A1")
        rule.onNode(formulaBar, useUnmergedTree = true).performKeyInput { pressKey(Key.F4) }
        rule.waitUntil(10_000) { rule.onAllNodes(barShows("=\$A\$1"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
