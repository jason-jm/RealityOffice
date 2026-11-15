package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import com.therealityteam.aeropages.platform.SystemSpelling
import com.therealityteam.aeropages.ui.AeroDesk
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review → Proofing asks the device's spell checker (LatinIME's on the PICO emulator): a misspelled
 * word is listed with suggestions, and taking one puts it in the paragraph.
 *
 *   ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.therealityteam.aeropages.ProofingTest
 */
@RunWith(AndroidJUnit4::class)
class ProofingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    @Before
    fun setUp() {
        TestSupport.installCrashLogger()
        TestSupport.clearDevProps()
        context.getSharedPreferences("aeropages", 0).edit().clear().commit()
        rule.setContent { PicoTheme { CompositionLocalProvider(LocalSpatialNavigator provides RecordingNavigator()) { AeroDesk() } } }
    }

    @Test
    fun aMisspelledWordIsListedAndASuggestionFixesIt() {
        assumeTrue("the device has a spell checker", SystemSpelling.available(context))
        rule.onNodeWithText("Pages").performClick()
        // English beyond doubt (the, of, is), so it is checked in English whatever the app's language.
        paragraphField().performTextInput("Thsi is a tset of the checker")
        waitFor(hasText("Thsi is a tset of the checker", substring = true))
        click(hasText(str(R.string.tab_review)) and hasClickAction())
        click(hasContentDescription(str(R.string.spelling_check)) and hasClickAction())
        waitFor(hasText(str(R.string.txt_proof_misspelled, "tset")))
        click(hasText("test") and hasClickAction())
        waitFor(hasText("Thsi is a test of the checker", substring = true))
    }

    @Test
    fun sentencesSpelledRightRaiseNothing() {
        assumeTrue("the device has a spell checker", SystemSpelling.available(context))
        val typos = kotlinx.coroutines.runBlocking {
            SystemSpelling.check(context, listOf("This sentence ends with a word.", "Commas, full stops; and questions? All fine!", "中文段落也没有拼写问题。"))
        }
        assertEquals(listOf(emptyList<String>(), emptyList(), emptyList()), typos?.map { list -> list.map { "${it.start}+${it.length}" } })
        val wrong = kotlinx.coroutines.runBlocking { SystemSpelling.check(context, listOf("A speling mistake in the text.")) }
        assertEquals("the misspelled word alone, without its full stop", listOf(listOf("2+7")), wrong?.map { list -> list.map { "${it.start}+${it.length}" } })
    }

    private fun paragraphField() = run {
        val field = hasSetTextAction() and TestSupport.notSearch
        val paragraph = hasClickAction() and hasText("") and TestSupport.notSearch
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(paragraph).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(paragraph).onFirst().performClick()
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(field).fetchSemanticsNodes().isNotEmpty() }
        val focused = rule.onAllNodes(field and isFocused()).fetchSemanticsNodes()
        if (focused.isNotEmpty()) rule.onNode(field and isFocused()) else rule.onAllNodes(field).onFirst()
    }

    private fun click(m: SemanticsMatcher) {
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
        val node = rule.onAllNodes(m).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    private fun waitFor(m: SemanticsMatcher) {
        rule.waitUntil(TIMEOUT) { rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() }
    }

    private companion object { const val TIMEOUT = 20_000L }
}
