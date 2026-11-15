package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
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

/** Selecting text with the pointer in Pages: press and hold a word, drag across paragraphs, move an end with its handle. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PagesPointerSelectTest {
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
        val empty = hasClickAction() and hasText("") and TestSupport.notSearch
        waitFor(empty)
        rule.onAllNodes(empty).onFirst().performClick()
        waitFor(hasSetTextAction() and isFocused())
        rule.onNode(hasSetTextAction() and isFocused()).performTextInput("Alpha one\nBravo two\nCharlie three")
        waitFor(hasSetTextAction() and isFocused() and hasText("Charlie three"))
    }

    private fun waitFor(m: SemanticsMatcher) {
        try { rule.waitUntil(15_000) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() } } catch (e: Throwable) { dump(); throw e }
    }

    /** Logs the text fields (text, selection, focus) and paragraph texts when a wait fails. */
    private fun dump() {
        rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().forEach { n ->
            val t = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)
            val sel = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange)
            val f = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Focused)
            android.util.Log.i("PagesPointerDump", "field '$t' sel=$sel focused=$f")
        }
        rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.let { android.util.Log.i("PagesPointerDump", "text '${it.joinToString("|")}'") }
        }
    }
    private fun bounds(m: SemanticsMatcher) = rule.onAllNodes(m).onFirst().fetchSemanticsNode().boundsInRoot

    /** Presses and holds at [from] (inside [node]), then drags to [to] (root px) and lets go. */
    private fun holdAndDrag(node: SemanticsMatcher, from: Offset, to: Offset) {
        val box = bounds(node)
        rule.onAllNodes(node).onFirst().performTouchInput {
            val start = from - box.topLeft
            down(start)
            advanceEventTime(1_200)
            for (k in 1..12) { advanceEventTime(30); moveTo(start + (to - from) * (k / 12f)) }
            up()
        }
    }

    @Test
    fun pressAndHoldSelectsAWord() {
        val bravo = hasText("Bravo two")
        val box = bounds(bravo)
        // Hold on the first word, near the left edge of the line.
        holdAndDrag(bravo, Offset(box.left + 12f, box.center.y), Offset(box.left + 12f, box.center.y))
        // The selection is the word: Delete takes just it.
        waitFor(hasSetTextAction() and isFocused() and hasText("Bravo two"))
        rule.onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Delete) }
        waitFor(hasText(" two"))
    }

    @Test
    fun theSelectionBarCutsAndTheRibbonPastesBack() {
        val bravo = hasText("Bravo two")
        val box = bounds(bravo)
        holdAndDrag(bravo, Offset(box.left + 12f, box.center.y), Offset(box.left + 12f, box.center.y))
        // A held word gets the selection's bar; its Cut takes the word.
        val bar = hasContentDescription(context.getString(R.string.a11y_selection_actions))
        val cut = hasContentDescription(context.getString(R.string.cut)) and hasAnyAncestor(bar)
        waitFor(cut)
        rule.onNode(cut).performClick()
        waitFor(hasText(" two"))
        // The ribbon's Clipboard group pastes it back at the caret.
        rule.onAllNodes(hasContentDescription(context.getString(R.string.paste)) and hasClickAction()).onFirst().performClick()
        waitFor(hasText("Bravo two"))
    }

    @Test
    fun draggingAcrossParagraphsSelectsThemAndTypingReplacesTheSelection() {
        val alpha = hasText("Alpha one")
        val a = bounds(alpha)
        val c = bounds(hasText("Charlie three"))
        holdAndDrag(alpha, Offset(a.left + 12f, a.center.y), Offset(c.left + 2f, c.center.y))
        // Both handles show for a selection across paragraphs.
        waitFor(hasContentDescription(context.getString(R.string.a11y_selection_start)))
        waitFor(hasContentDescription(context.getString(R.string.a11y_selection_end)))
        waitFor(hasSetTextAction() and isFocused())
        rule.onNode(hasSetTextAction() and isFocused()).performTextInput("Z")
        waitFor(hasText("ZCharlie three"))
        assertTrue(rule.onAllNodes(hasText("Bravo two")).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theEndHandleMovesThatEndIntoTheNextParagraph() {
        val alpha = hasText("Alpha one")
        val a = bounds(alpha)
        holdAndDrag(alpha, Offset(a.left + 12f, a.center.y), Offset(a.right - 4f, a.center.y))   // most of Alpha's line
        val endHandle = hasContentDescription(context.getString(R.string.a11y_selection_end))
        waitFor(endHandle)
        val handle = bounds(endHandle)
        val b = bounds(hasText("Bravo two"))
        // Drag the end handle down to the start of Bravo: the selection now runs into it.
        rule.onAllNodes(endHandle).onFirst().performTouchInput {
            down(center)
            val target = Offset(b.left + 2f, b.center.y + (handle.height / 2f + 8f)) - handle.topLeft
            for (k in 1..10) { advanceEventTime(30); moveTo(center + (target - center) * (k / 10f)) }
            up()
        }
        waitFor(hasSetTextAction() and isFocused())
        rule.onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Delete) }
        waitFor(hasText("Bravo two"))
        try { rule.waitUntil(15_000) { rule.onAllNodes(hasText("Alpha one")).fetchSemanticsNodes().isEmpty() } } catch (e: Throwable) { dump(); throw e }
    }
}
