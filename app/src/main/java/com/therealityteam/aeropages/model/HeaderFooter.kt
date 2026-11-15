package com.therealityteam.aeropages.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Header & Footer → Date and time: the day the slides are shown, formatted anew each time in
 * [format] (a spreadsheet date code — `yyyy/m/d`, `[$-409]mmmm d, yyyy` …) — or, [fixed], that text
 * as it is.
 */
data class SlideDate(val format: String = DEFAULT_FORMAT, val fixed: String? = null) {

    /** What it shows at [now]. */
    fun text(now: LocalDateTime = LocalDateTime.now()): String = fixed ?: FormatCode.format(serialOf(now), format).text

    companion object {
        const val DEFAULT_FORMAT = "yyyy/m/d"

        /** The formats Header & Footer offers — the dates PowerPoint and Impress offer. */
        val FORMATS = listOf(
            "yyyy/m/d", "yyyy\"年\"m\"月\"d\"日\"", "[$-804]yyyy\"年\"m\"月\"d\"日\" aaaa", "yyyy-mm-dd", "m/d/yyyy",
            "[$-409]mmmm d, yyyy", "[$-409]d mmmm yyyy", "[$-409]dddd, mmmm d, yyyy",
        )

        /** What [format] reads as: digits, the month's name, or the weekday too (what LibreOffice keeps of a date). */
        fun kindOf(format: String): DateKind {
            val f = format.lowercase().replace(Regex("\"[^\"]*\""), "")
            return when {
                "ddd" in f || "aaa" in f -> DateKind.WEEKDAY
                "mmm" in f || "年" in format -> DateKind.LONG
                else -> DateKind.SHORT
            }
        }

        /** The app's format of [kind] for readers of [lang]. */
        fun formatOf(kind: DateKind, lang: FormatCode.Lang = FormatCode.Lang.current()): String {
            val cjk = lang != FormatCode.Lang.EN
            return when (kind) {
                DateKind.SHORT -> if (cjk) "yyyy/m/d" else "m/d/yyyy"
                DateKind.LONG -> if (cjk) "yyyy\"年\"m\"月\"d\"日\"" else "[$-409]mmmm d, yyyy"
                DateKind.WEEKDAY -> if (cjk) "[$-804]yyyy\"年\"m\"月\"d\"日\" aaaa" else "[$-409]dddd, mmmm d, yyyy"
            }
        }

        /** [t] as a spreadsheet serial: days from 1899-12-30, the time of day its fraction. */
        fun serialOf(t: LocalDateTime): Double =
            ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), t.toLocalDate()) + t.toLocalTime().toSecondOfDay() / 86400.0
    }
}

/**
 * A template's own date, footer or slide number: where its master puts it (fractions of the slide),
 * its text's alignment and size, and its colour (null: one that reads over the slide).
 */
data class ChromeLook(val xPct: Float, val yPct: Float, val wPct: Float, val hPct: Float, val align: TextAlign, val sizePt: Float, val color: Long?)

/** How a date reads: its day, month and year in digits, with the month's name, or with the weekday too. */
enum class DateKind { SHORT, LONG, WEEKDAY }

/**
 * A slide's date, footer and number — where they sit as PowerPoint's Office theme and Impress's
 * default master have them (the date at the left of the slide's foot, the footer centred, the
 * number at the right), what each shows and in what colour. The editor, the show, thumbnails, PDF
 * and both file formats place them alike.
 */
object SlideChrome {

    /** One of them: its frame (fractions of the slide) and its text's alignment there. */
    enum class Part(val xPct: Float, val yPct: Float, val wPct: Float, val hPct: Float, val align: TextAlign) {
        DATE(0.06875f, 0.92685f, 0.225f, 0.05324f, TextAlign.LEFT),
        FOOTER(0.33125f, 0.92685f, 0.3375f, 0.05324f, TextAlign.CENTER),
        NUMBER(0.70625f, 0.92685f, 0.225f, 0.05324f, TextAlign.RIGHT),
    }

    /** Their text size, in points (PowerPoint's). */
    const val SIZE_PT = 12f

    /**
     * Where a part sits and how it reads on a slide: a template's own look ([ChromeLook], its design's
     * else its master's), else PowerPoint's; its colour, the template's or one reading over the slide.
     */
    class Placed(val part: Part, val text: String, val xPct: Float, val yPct: Float, val wPct: Float, val hPct: Float,
                 val align: TextAlign, val sizePt: Float, val color: Long)

    /** The look [slide]'s [part] takes: its design's, else the deck master's, else null (PowerPoint's). */
    fun lookOf(p: Presentation, slide: Slide, part: Part): ChromeLook? = p.designOf(slide)?.chrome?.get(part) ?: p.master.chrome[part]

    /** What [slide] (number [number]) shows of them, placed and coloured, at [now]. */
    fun placed(p: Presentation, slide: Slide, number: Int, now: LocalDateTime = LocalDateTime.now()): List<Placed> {
        val parts = partsOf(p, slide, number, now)
        if (parts.isEmpty()) return emptyList()
        val auto = colorOver(p.backgroundOf(slide))
        return parts.map { (part, text) ->
            val look = lookOf(p, slide, part)
            Placed(part, text, look?.xPct ?: part.xPct, look?.yPct ?: part.yPct, look?.wPct ?: part.wPct, look?.hPct ?: part.hPct,
                look?.align ?: part.align, look?.sizePt ?: SIZE_PT, look?.color ?: auto)
        }
    }

    /** Whether [look] is [part] where the app puts it (a file of the app's own), within [tolerance] of the slide. */
    fun isDefault(part: Part, look: ChromeLook, tolerance: Float = 0.01f): Boolean =
        kotlin.math.abs(look.xPct - part.xPct) < tolerance && kotlin.math.abs(look.yPct - part.yPct) < tolerance &&
            kotlin.math.abs(look.wPct - part.wPct) < tolerance && kotlin.math.abs(look.hPct - part.hPct) < tolerance &&
            kotlin.math.abs(look.sizePt - SIZE_PT) < 0.5f

    /** Whether [slide] of [p] shows them: not a title slide when the deck hides them there. */
    fun shownOn(p: Presentation, slide: Slide): Boolean = !(p.hideFooterOnTitle && slide.layout == SlideLayout.TITLE)

    /** What [slide] (number [number]) shows of them, each with its text, at [now]. */
    fun partsOf(p: Presentation, slide: Slide, number: Int, now: LocalDateTime = LocalDateTime.now()): List<Pair<Part, String>> {
        if (!shownOn(p, slide)) return emptyList()
        return buildList {
            p.date?.text(now)?.takeIf { it.isNotBlank() }?.let { add(Part.DATE to it) }
            if (p.footer.isNotBlank()) add(Part.FOOTER to p.footer)
            if (p.showSlideNumber) add(Part.NUMBER to (number + p.firstSlideNumber - 1).toString())
        }
    }

    /** [colorOver] laid on [background]: the solid colour it shows as, for formats without transparent text. */
    fun solidOver(background: Long?): Long {
        val bg = background ?: 0xFFFFFFFF
        val fg = colorOver(background)
        val a = ((fg ushr 24) and 0xFF) / 255.0
        fun mix(shift: Int) = Math.round(((fg shr shift) and 0xFF) * a + ((bg shr shift) and 0xFF) * (1 - a)).toLong()
        return 0xFF000000 or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Their colour over a slide [background] (null = white): a soft black on light, a soft white on dark. */
    fun colorOver(background: Long?): Long = if (TextColors.isDark(background ?: 0xFFFFFFFF)) 0xB3FFFFFF else 0x99000000
}
