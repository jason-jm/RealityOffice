package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasAnySibling
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

/** Excel's fill handle: dragging it continues a series; a double tap fills down beside the adjacent data. */
@RunWith(AndroidJUnit4::class)
class SheetsFillHandleTest {
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
        rule.waitUntil(20_000) { rule.onAllNodes(hasContentDescription("B2")).fetchSemanticsNodes().isNotEmpty() }
    }

    private val nameBox get() = hasContentDescription(context.getString(R.string.name_box))
    private val fillHandle get() = hasContentDescription(context.getString(R.string.a11y_fill_handle))
    private fun centerOf(m: SemanticsMatcher): Offset = rule.onAllNodes(m).onFirst().fetchSemanticsNode().boundsInRoot.center

    /** Goes to [ref] through the Name Box and types [text] into its formula bar. */
    private fun type(ref: String, text: String) {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement(ref)
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText(ref)).fetchSemanticsNodes().isNotEmpty() }
        val fx = hasSetTextAction() and hasAnySibling(hasText("fx"))
        rule.onNode(fx, useUnmergedTree = true).performTextReplacement(text)
    }

    private fun awaitCell(ref: String, text: String) {
        rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription(ref) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun draggingTheFillHandleContinuesTheSeries() {
        type("B2", "Item 1")
        awaitCell("B2", "Item 1")
        rule.waitUntil(10_000) { rule.onAllNodes(fillHandle).fetchSemanticsNodes().isNotEmpty() }
        val travel = centerOf(hasContentDescription("B5")) - centerOf(fillHandle)
        rule.onAllNodes(fillHandle).onFirst().performTouchInput {
            down(center)
            for (k in 1..10) { advanceEventTime(30); moveTo(center + travel * (k / 10f)) }
            up()
        }
        awaitCell("B3", "Item 2")
        awaitCell("B5", "Item 4")
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText("B2:B5")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun doubleTappingTheFillHandleFillsDownBesideTheData() {
        for (r in 1..6) type("A$r", "Row $r")
        type("B1", "Mon")
        awaitCell("B1", "Mon")
        rule.waitUntil(10_000) { rule.onAllNodes(fillHandle).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(fillHandle).onFirst().performTouchInput { doubleClick() }
        awaitCell("B2", "Tue")
        awaitCell("B6", "Sat")
    }
}
