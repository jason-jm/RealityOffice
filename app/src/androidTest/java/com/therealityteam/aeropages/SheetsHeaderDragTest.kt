package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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

/** Dragging a column letter's right edge widens the column; dragging a row number's bottom edge makes the row taller. */
@RunWith(AndroidJUnit4::class)
class SheetsHeaderDragTest {
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

    @Test
    fun draggingHeaderEdgesResizesColumnsAndRows() {
        rule.onAllNodes(hasText("Sheets")).onFirst().performClick()
        val colB = hasContentDescription(context.getString(R.string.a11y_select_column, "B"))
        rule.waitUntil(20_000) { rule.onAllNodes(colB).fetchSemanticsNodes().isNotEmpty() }
        val before = rule.onAllNodes(colB).onFirst().fetchSemanticsNode().boundsInRoot
        rule.onAllNodes(colB).onFirst().performTouchInput {
            swipe(Offset(width - 3f, centerY), Offset(width - 3f + 160f, centerY), durationMillis = 500)
        }
        rule.waitForIdle()
        val after = rule.onAllNodes(colB).onFirst().fetchSemanticsNode().boundsInRoot
        // The edge follows the pointer from where it was pressed: a 160 px swipe widens the column by 160 px.
        assertTrue("column B widened: ${before.width} → ${after.width}", after.width > before.width + 150f)

        val row3 = hasContentDescription(context.getString(R.string.a11y_select_row, 3))
        val rowBefore = rule.onAllNodes(row3).onFirst().fetchSemanticsNode().boundsInRoot
        rule.onAllNodes(row3).onFirst().performTouchInput {
            swipe(Offset(centerX, height - 2f), Offset(centerX, height - 2f + 80f), durationMillis = 500)
        }
        rule.waitForIdle()
        val rowAfter = rule.onAllNodes(row3).onFirst().fetchSemanticsNode().boundsInRoot
        assertTrue("row 3 grew: ${rowBefore.height} → ${rowAfter.height}", rowAfter.height > rowBefore.height + 70f)
    }
}
