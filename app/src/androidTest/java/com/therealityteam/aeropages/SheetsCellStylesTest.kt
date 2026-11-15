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

/** The Cell Styles and Format as Table galleries from the ribbon. */
@RunWith(AndroidJUnit4::class)
class SheetsCellStylesTest {
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

    private fun goTo(ref: String) {
        rule.onAllNodes(nameBox).onFirst().performClick()
        rule.onAllNodes(nameBox).onFirst().performTextReplacement(ref)
        rule.onAllNodes(nameBox).onFirst().performImeAction()
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText(ref)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun type(ref: String, text: String) {
        goTo(ref)
        rule.onNode(hasSetTextAction() and hasAnySibling(hasText("fx")), useUnmergedTree = true).performTextReplacement(text)
        rule.waitUntil(10_000) { rule.onAllNodes(cell(ref) and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun click(description: String) {
        val m = hasContentDescription(description) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(m).onFirst().performClick()
    }

    private fun awaitText(text: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun enter(ref: String, text: String, shows: String) {
        goTo(ref)
        rule.onNode(hasSetTextAction() and hasAnySibling(hasText("fx")), useUnmergedTree = true).performTextReplacement(text)
        rule.waitUntil(10_000) { rule.onAllNodes(cell(ref) and hasText(shows)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clearWith(nameRes: Int) {
        click(context.getString(R.string.clear))
        val pill = hasText(context.getString(nameRes)) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(pill).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(pill).onFirst().performClick()
    }

    @Test
    fun clearContentsKeepsTheFormatClearFormatsDoesNot() {
        goTo("B2")
        click(context.getString(R.string.number_format))
        val percent = hasText(context.getString(R.string.numfmt_percentage)) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(percent).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(percent).onFirst().performClick()
        enter("B2", "0.25", "25.0%")
        clearWith(R.string.clear_contents)
        rule.waitUntil(10_000) { rule.onAllNodes(cell("B2") and hasText("25.0%")).fetchSemanticsNodes().isEmpty() }
        // The percentage format stayed with the cell.
        enter("B2", "0.5", "50.0%")
        clearWith(R.string.clear_formats)
        rule.waitUntil(10_000) { rule.onAllNodes(cell("B2") and hasText("0.5")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aCellStyleAndATableLook() {
        type("A1", "Item"); type("B1", "Cost")
        type("A2", "Rent"); type("B2", "1200")
        // Cell Styles → Good.
        goTo("B2")
        click(context.getString(R.string.cell_styles))
        val good = context.getString(R.string.style_good)
        click(good)
        awaitText(context.getString(R.string.status_cell_style, good))
        // Format as Table from one cell: the data around it becomes the table and the selection.
        goTo("A2")
        click(context.getString(R.string.format_as_table))
        click(context.getString(R.string.table_style_medium) + " 1")
        // Excel's Create Table asks where the data is (guessed: A1:B2, with headers) first.
        val ok = hasText(context.getString(R.string.btn_ok)) and hasClickAction()
        rule.waitUntil(10_000) { rule.onAllNodes(ok).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(ok).onFirst().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(nameBox and hasText("A1:B2")).fetchSemanticsNodes().isNotEmpty() }
        awaitText(context.getString(R.string.status_table_formatted, "A1:B2"))
    }
}
