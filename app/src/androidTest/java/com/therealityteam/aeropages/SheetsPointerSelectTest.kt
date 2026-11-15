package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.ui.AeroDesk
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Selecting a block with the pointer — the selection's grip, press-and-hold then drag — and going places with the Name Box. */
@RunWith(AndroidJUnit4::class)
class SheetsPointerSelectTest {
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
    private fun centerOf(m: SemanticsMatcher): Offset = rule.onAllNodes(m).onFirst().fetchSemanticsNode().boundsInRoot.center
    private val nameBox get() = hasContentDescription(context.getString(R.string.name_box))

    private fun awaitNameBox(text: String) {
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theGripStretchesTheSelection() {
        rule.onAllNodes(cell("B2")).onFirst().performClick()
        awaitNameBox("B2")
        val grip = hasContentDescription(context.getString(R.string.a11y_selection_end))
        rule.waitUntil(10_000) { rule.onAllNodes(grip).fetchSemanticsNodes().isNotEmpty() }
        val travel = centerOf(cell("D5")) - centerOf(grip)
        rule.onAllNodes(grip).onFirst().performTouchInput {
            down(center)
            for (k in 1..10) { advanceEventTime(30); moveTo(center + travel * (k / 10f)) }
            up()
        }
        awaitNameBox("B2:D5")
    }

    /** Goes to [ref] through the Name Box and types [text] into the formula bar. */
    private fun type(ref: String, text: String) {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement(ref)
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        awaitNameBox(ref)
        rule.onNode(hasSetTextAction() and hasAnySibling(hasText("fx")), useUnmergedTree = true).performTextReplacement(text)
        rule.waitUntil(10_000) { rule.onAllNodes(cell(ref) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theSelectionBarCopiesAndTheRibbonPastes() {
        type("A1", "10")
        type("A2", "20")
        rule.onAllNodes(cell("A1")).onFirst().performClick()
        awaitNameBox("A1")
        val grip = hasContentDescription(context.getString(R.string.a11y_selection_end))
        rule.waitUntil(10_000) { rule.onAllNodes(grip).fetchSemanticsNodes().isNotEmpty() }
        val travel = centerOf(cell("A2")) - centerOf(grip)
        rule.onAllNodes(grip).onFirst().performTouchInput {
            down(center)
            for (k in 1..10) { advanceEventTime(30); moveTo(center + travel * (k / 10f)) }
            up()
        }
        awaitNameBox("A1:A2")
        // A block stretched with the pointer gets the selection's bar; its Copy takes the cells.
        val bar = hasContentDescription(context.getString(R.string.a11y_selection_actions))
        val copy = hasContentDescription(context.getString(R.string.copy)) and hasAnyAncestor(bar)
        rule.waitUntil(10_000) { rule.onAllNodes(copy).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(copy).performClick()
        // The ribbon's Clipboard group pastes them at C1.
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement("C1")
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        awaitNameBox("C1")
        rule.onAllNodes(hasContentDescription(context.getString(R.string.paste)) and hasClickAction()).onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(cell("C1") and hasText("10")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodes(cell("C2") and hasText("20")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun pressAndHoldThenDragSelectsABlock() {
        val travel = centerOf(cell("E10")) - centerOf(cell("C8"))
        rule.onAllNodes(cell("C8")).onFirst().performTouchInput {
            down(center)
            advanceEventTime(1_200)                                     // past the long-press timeout
            for (k in 1..10) { advanceEventTime(30); moveTo(center + travel * (k / 10f)) }
            up()
        }
        awaitNameBox("C8:E10")
    }

    @Test
    fun theNameBoxGoesToBlocksColumnsAndPastTheSheetsEnd() {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement("b3:c4")
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        awaitNameBox("B3:C4")
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement("D:E")
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        awaitNameBox("D:E")
        // A new sheet has 40 rows at first: going to row 300 makes room and brings it into view.
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement("A300")
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        awaitNameBox("A300")
        rule.waitUntil(10_000) { rule.onAllNodes(cell("A300")).fetchSemanticsNodes().isNotEmpty() }
    }
}
