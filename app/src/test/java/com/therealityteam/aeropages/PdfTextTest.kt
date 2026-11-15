package com.therealityteam.aeropages

import com.therealityteam.aeropages.format.pdf.PdfFaces
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text on a PDF page drops required ligatures (PICO Sans swaps I, i, J, j and l for glyphs no
 * character maps to through them, so copying or searching a PDF lost those letters) — except in
 * scripts whose ligatures are required.
 */
class PdfTextTest {
    @Test
    fun scriptsThatNeedNoRequiredLigatures() {
        listOf(
            "Hanging indent in English: Smith, J. (2026).", "Ïle, łódź, café", "Ελληνικά", "Русский текст",
            "中文段落：首行缩进两个字符。", "日本語のテキスト", "한국어 텍스트", "全角ＡＢＣ　１２３", "emoji 😀 ✓", "→ € ™ …",
        ).forEach { assertTrue(it, PdfFaces.plain(it)) }
    }

    @Test
    fun scriptsWhoseLigaturesAreRequired() {
        listOf("السلام عليكم", "mixed English العربية", "ܫܠܡܐ", "हिन्दी", "ภาษาไทย", "ᠮᠣᠩᠭᠣᠯ", "ﻻ")
            .forEach { assertFalse(it, PdfFaces.plain(it)) }
    }
}
