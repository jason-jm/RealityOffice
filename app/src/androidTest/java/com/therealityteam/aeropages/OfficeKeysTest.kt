package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
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

/**
 * Word's and PowerPoint's keys on the real apps: Ctrl+Enter a page break in Pages, Ctrl+M a new slide in Slides — and
 * Word's AutoCorrect as a word is typed, with its AutoCorrect Options button undoing it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OfficeKeysTest {
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
    }

    private fun waitForText(text: String) =
        rule.waitUntil(20_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun ctrlEnterBreaksThePageInPages() {
        rule.onAllNodes(hasText("Pages")).onFirst().performClick()
        // A paragraph is plain text until tapped (a blank document has one empty paragraph); the tap arms its editor.
        val paragraph = hasClickAction() and hasText("") and TestSupport.notSearch
        rule.waitUntil(20_000) { rule.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(paragraph).onFirst().performClick()
        val field = hasSetTextAction() and TestSupport.notSearch
        rule.waitUntil(20_000) { rule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(field).onFirst().performTextInput("Hello")
        waitForText(context.getString(R.string.status_page_of, "1", "1"))
        rule.onAllNodes(field).onFirst().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Enter) } }
        rule.waitUntil(20_000) {
            rule.onAllNodes(hasText(context.getString(R.string.status_page_of, "1", "2")) or hasText(context.getString(R.string.status_page_of, "2", "2")))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun ctrlMAddsASlideInSlides() {
        rule.onAllNodes(hasText("Slides")).onFirst().performClick()
        waitForText(context.getString(R.string.label_slide_of, 1, 1))
        // Keys go to what has the focus: the new deck's title box.
        val field = hasSetTextAction() and TestSupport.notSearch
        rule.waitUntil(20_000) { rule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(field).onFirst().performClick()
        rule.onAllNodes(field).onFirst().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.M) } }
        waitForText(context.getString(R.string.label_slide_of, 2, 2))
    }

    @Test
    fun aWordTypedIsAutoCorrectedAndTheButtonUndoesIt() {
        rule.onAllNodes(hasText("Pages")).onFirst().performClick()
        val paragraph = hasClickAction() and hasText("") and TestSupport.notSearch
        rule.waitUntil(20_000) { rule.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(paragraph).onFirst().performClick()
        val field = hasSetTextAction() and TestSupport.notSearch
        rule.waitUntil(20_000) { rule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        fun shows(text: String) = field and SemanticsMatcher("edit text $text") { n ->
            n.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == text
        }
        // A word, then the space that ends it — one key, as typed.
        rule.onAllNodes(field).onFirst().performTextInput("teh")
        rule.onAllNodes(field).onFirst().performTextInput(" ")
        rule.waitUntil(10_000) { rule.onAllNodes(shows("The ")).fetchSemanticsNodes().isNotEmpty() }
        val button = hasContentDescription(context.getString(R.string.ac_options)) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(button).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(button).onFirst().performClick()
        val back = context.getString(R.string.ac_change_back, "teh")
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(back)).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText(back)).onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(shows("teh ")).fetchSemanticsNodes().isNotEmpty() }
    }
}
