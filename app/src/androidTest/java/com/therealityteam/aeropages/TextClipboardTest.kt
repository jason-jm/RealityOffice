package com.therealityteam.aeropages

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.therealityteam.aeropages.ui.TextClipboard
import com.therealityteam.aeropages.ui.rememberTextClipboard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The copy / paste path of Pages, Sheets and Slides after moving from the deprecated
 * ClipboardManager to the suspend Clipboard of the Spatial SDK's Compose: text copied through
 * [TextClipboard] comes back from the system clipboard unchanged (CJK and tabs included — a
 * Sheets selection travels as TSV).
 */
@RunWith(AndroidJUnit4::class)
class TextClipboardTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun copiedTextPastesBackUnchanged() {
        lateinit var clipboard: TextClipboard
        rule.setContent { clipboard = rememberTextClipboard() }
        val text = "Region\tSales\n华东\t120"
        rule.runOnIdle { clipboard.copy(text) }
        rule.waitForIdle()
        var pasted: String? = null
        rule.runOnIdle { clipboard.paste { pasted = it } }
        rule.waitUntil(timeoutMillis = 5_000) { pasted != null }
        assertEquals(text, pasted)
    }
}
