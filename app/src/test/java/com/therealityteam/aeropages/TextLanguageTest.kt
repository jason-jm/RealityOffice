package com.therealityteam.aeropages

import com.therealityteam.aeropages.model.TextLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Proofing checks each paragraph in its own language: the language told from the text, in each of the app's languages. */
class TextLanguageTest {
    private fun lang(text: String) = TextLanguage.guess(text)?.language

    @Test
    fun eachLanguageIsToldFromItsText() {
        val samples = mapOf(
            "en" to "We reviewed the quarterly report and the results are in line with the plan.",
            "de" to "Wir haben den Quartalsbericht geprüft und die Ergebnisse sind gut.",
            "fr" to "Nous avons examiné le rapport trimestriel et les résultats sont bons.",
            "es" to "Hemos revisado el informe trimestral y los resultados son buenos.",
            "it" to "Abbiamo esaminato il rapporto trimestrale e i risultati sono buoni per la società.",
            "pt" to "Nós revisamos o relatório trimestral e os resultados são bons.",
            "nl" to "Wij hebben het kwartaalverslag bekeken en de resultaten zijn goed.",
            "pl" to "Sprawdziliśmy raport kwartalny i wyniki są dobre, ale nie wszystkie.",
            "tr" to "Üç aylık raporu inceledik ve sonuçlar çok iyi.",
            "vi" to "Chúng tôi đã xem xét báo cáo hằng quý và kết quả rất tốt.",
            "ru" to "Мы проверили квартальный отчёт, и результаты хорошие.",
            "ko" to "분기별 보고서를 검토했습니다.",
            "ja" to "四半期報告書を確認しました。",
            "zh" to "我们审阅了季度报告。",
            "th" to "เราได้ตรวจสอบรายงานประจำไตรมาสแล้ว",
            "hi" to "हमने तिमाही रिपोर्ट की समीक्षा की।",
        )
        for ((code, text) in samples) assertEquals(text, code, lang(text))
        val indonesian = lang("Kami telah meninjau laporan triwulan dan hasilnya adalah baik untuk tim.")
        assertEquals("Indonesian, under either code", true, indonesian == "id" || indonesian == "in")
    }

    @Test
    fun tooLittleTextOrNoClearLeaderTellsNothing() {
        assertNull(lang("OK"))
        assertNull("one word", lang("Quartalsbericht"))
        assertNull("numbers only", lang("12 345,67"))
        assertNull("names and figures, no common words", lang("Aero Desk 2026"))
        assertEquals("a little kana beside much Han is Japanese", "ja", lang("株式会社四半期決算報告の概要について"))
        assertEquals("English words in a Chinese sentence: still Chinese", "zh", lang("我们用 Aero Desk 审阅了季度报告"))
        assertEquals("a short English sentence", "en", lang("Commas, full stops; and questions? All fine!"))
        assertEquals("en", lang("Thsi is a tset of the checker"))
    }
}
