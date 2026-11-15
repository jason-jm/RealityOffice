package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.ui.AeroDesk
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pages across paragraphs: typed lines become paragraphs, arrows cross them, Shift+arrows and Select All make selections that span them. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PagesCrossParagraphTest {
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
        rule.onAllNodes(hasText("Pages")).onFirst().performClick()
    }

    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(15_000) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }

    /** The paragraph being edited: the focused text field (after [waitFor] one showing [text]). */
    private fun editing(text: String): SemanticsNodeInteraction {
        val m = hasSetTextAction() and isFocused() and hasText(text)
        try { waitFor(m) } catch (e: Throwable) { dump("waiting for a focused field showing '$text'"); throw e }
        return rule.onNode(m)
    }

    /** Logs every text field (its text and focus) and every text node — what the canvas holds when a wait fails. */
    private fun dump(why: String) {
        android.util.Log.i("PagesSpanDump", why)
        rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().forEach { n ->
            val t = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)
            val f = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused)
            android.util.Log.i("PagesSpanDump", "field text='$t' focused=$f")
        }
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { n ->
            val t = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("|")
            if (t != null) android.util.Log.i("PagesSpanDump", "text '$t'")
        }
    }

    /** Taps the blank document's empty paragraph and types [text] into it. */
    private fun type(text: String) {
        val empty = hasClickAction() and hasText("") and TestSupport.notSearch
        waitFor(empty)
        rule.onAllNodes(empty).onFirst().performClick()
        waitFor(hasSetTextAction() and isFocused())
        rule.onNode(hasSetTextAction() and isFocused()).performTextInput(text)
    }

    @Test
    fun typedLinesBecomeParagraphsAndArrowsCrossThem() {
        type("Alpha\nBravo\nCharlie")
        editing("Charlie")
        waitFor(hasText("Alpha")); waitFor(hasText("Bravo"))
        editing("Charlie").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
        editing("Alpha").performKeyInput { pressKey(Key.DirectionDown) }
        editing("Bravo").performTextInput("X")
        waitFor(hasText("XBravo"))
    }

    @Test
    fun shiftArrowsSelectAcrossParagraphsAndTypingReplacesTheSpan() {
        type("Alpha\nBravo\nCharlie")
        editing("Charlie").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
        editing("Alpha").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) } }
        editing("Bravo").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) } }
        // The selection now runs from the start of Alpha to the start of Charlie: typing replaces it.
        editing("Charlie").performTextInput("Z")
        waitFor(hasText("ZCharlie"))
        assertTrue("Alpha went with the selection", rule.onAllNodes(hasText("Alpha")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasText("Bravo")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun selectAllFromTheRibbonThenDeleteEmptiesTheDocument() {
        type("Alpha\nBravo")
        editing("Bravo")
        rule.onAllNodes(hasText(context.getString(R.string.select_all))).onFirst().performClick()
        val field = rule.onNode(hasSetTextAction() and hasText("Bravo"))
        field.performSemanticsAction(SemanticsActions.RequestFocus)
        field.performKeyInput { pressKey(Key.Delete) }
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasText("Alpha")).fetchSemanticsNodes().isEmpty() && rule.onAllNodes(hasText("Bravo")).fetchSemanticsNodes().isEmpty()
        }
    }
}
