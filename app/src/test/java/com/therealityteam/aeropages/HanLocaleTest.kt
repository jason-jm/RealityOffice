package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.HanLocale
import com.therealityteam.aeropages.model.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/** Which forms Chinese characters take: Japanese text in Japanese glyphs whatever the app's language, Han alone in the reader's. */
class HanLocaleTest {
    private fun of(text: String, app: Locale) = HanLocale.of(Paragraph.of(text), app)

    @Test
    fun kanaHangulAndTheAppsLanguageChoose() {
        assertEquals("kana make it Japanese", Locale.JAPANESE, of("四半期報告書を確認しました。", Locale.SIMPLIFIED_CHINESE))
        assertEquals("hangul beside Han make it Korean", Locale.KOREAN, of("分期 보고서", Locale.SIMPLIFIED_CHINESE))
        assertEquals("Han alone: the reader's Japanese", Locale.JAPANESE, of("四半期報告", Locale.JAPANESE))
        assertEquals("Han alone: the reader's Traditional Chinese", Locale.TRADITIONAL_CHINESE, of("季度報告", Locale.TRADITIONAL_CHINESE))
        assertEquals("Han alone in a German app: Simplified Chinese", Locale.SIMPLIFIED_CHINESE, of("季度报告", Locale.GERMANY))
        assertNull("no Han, nothing to choose", of("Quarterly report", Locale.SIMPLIFIED_CHINESE))
        assertNull("hangul alone has one form", of("분기별 보고서", Locale.SIMPLIFIED_CHINESE))
        assertEquals("a cell's text", Locale.JAPANESE, HanLocale.of("確認しました" as CharSequence, Locale.SIMPLIFIED_CHINESE))
    }

    @Test
    fun aRunAloneSaysOnlyWhatItsLettersProve() {
        assertEquals(Locale.JAPANESE, HanLocale.strong("報告書を確認"))
        assertEquals(Locale.KOREAN, HanLocale.strong("分期 보고서"))
        assertNull("Han alone follows the paragraph", HanLocale.strong("直 骨"))
        assertNull(HanLocale.strong("Report"))
    }
}
