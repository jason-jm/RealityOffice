package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
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

/** Data → Data Validation: a list rule from the dialog, typing into it, its Stop alert, its drop-down, Circle Invalid Data. */
@RunWith(AndroidJUnit4::class)
class SheetsValidationTest {
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
    private val bar = hasSetTextAction() and hasAnySibling(hasText("fx"))

    private fun goTo(ref: String) {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement(ref)
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        awaitNameBox(ref)
    }

    private fun awaitNameBox(ref: String) = rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText(ref)).fetchSemanticsNodes().isNotEmpty() }

    private fun typeInBar(text: String) = rule.onNode(bar, useUnmergedTree = true).performTextReplacement(text)

    private fun awaitCell(ref: String, text: String) =
        rule.waitUntil(10_000) { rule.onAllNodes(cell(ref) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun clickDescription(description: String) {
        val m = hasContentDescription(description) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().performClick()
    }

    private fun clickText(text: String) {
        val m = hasText(text) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().performClick()
    }

    /** Data Validation on the selection: Allow List, its source as the dialog offers it (Yes,No). */
    private fun yesNoRule() {
        // Data ▸ Data Validation ▾ ▸ Data Validation…, then the Allow drop-down (named by its value, Any value) ▸ List.
        clickDescription(context.getString(R.string.title_data_validation))
        clickText(context.getString(R.string.data_validation_more))
        clickDescription(context.getString(R.string.validation_any))
        clickText(context.getString(R.string.validation_list))
        clickText(context.getString(R.string.btn_ok))
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(context.getString(R.string.status_validation_set))).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aListRuleItsAlertItsDropDownAndCircles() {
        val yes = context.getString(R.string.default_yes_no_list).substringBefore(',')
        val no = context.getString(R.string.default_yes_no_list).substringAfter(',')
        goTo("C2:C4")
        clickText(context.getString(R.string.tab_data))
        yesNoRule()
        // Typed a key at a time, the list's value is there as it grows (it is checked once entered).
        goTo("C2")
        typeInBar(yes.take(1))
        awaitCell("C2", yes.take(1))
        typeInBar(yes)
        rule.onNode(bar, useUnmergedTree = true).performImeAction()
        awaitNameBox("C3")
        awaitCell("C2", yes)
        // A value the list does not allow: Stop, and Cancel leaves the cell as it was.
        typeInBar("Maybe")
        rule.onNode(bar, useUnmergedTree = true).performImeAction()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(context.getString(R.string.btn_retry))).fetchSemanticsNodes().isNotEmpty() }
        clickText(context.getString(R.string.cancel))
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(context.getString(R.string.btn_retry))).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(10_000) { rule.onAllNodes(cell("C3") and hasText("Maybe")).fetchSemanticsNodes().isEmpty() }
        // The arrow beside the active cell opens the list; picking a value enters it.
        goTo("C2")
        clickDescription(context.getString(R.string.a11y_list_dropdown, "C2"))
        clickText(no)
        awaitCell("C2", no)
        // A value typed before its cell had a rule: Circle Invalid Data rings it.
        goTo("D2")
        typeInBar("Maybe")
        rule.onNode(bar, useUnmergedTree = true).performImeAction()
        goTo("D2")
        yesNoRule()
        // Circle Invalid Data is in the Data Validation menu, as in Excel.
        clickDescription(context.getString(R.string.title_data_validation))
        clickText(context.getString(R.string.circle_invalid_data))
        rule.waitUntil(10_000) { rule.onAllNodes(hasText(context.getString(R.string.status_circled_invalid, 1))).fetchSemanticsNodes().isNotEmpty() }
    }
}
