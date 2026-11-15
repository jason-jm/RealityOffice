package com.therealityteam.aeropages.ui

import androidx.core.content.edit
import com.therealityteam.aeropages.R

/**
 * The characters Insert → Symbol offers, by subset as Word's Symbol dialog groups them (and the ones a Chinese or
 * Japanese writer reaches for: full-width punctuation, pinyin tones, enclosed numbers), with each one's Unicode name
 * and code, a search over both, and the recently used list.
 */
internal object Symbols {
    /** A subset: its name and its characters. */
    class Subset(val label: Int, text: String) {
        val chars: List<String> = split(text)
    }

    /** What the Symbol menu shows before anything has been used. */
    val COMMON: List<String> = split("©®™§¶•…–—°±×÷≤≥≠≈∞√←→↑↓★☆✓✗€£¥¢‰※№℃")

    val SUBSETS: List<Subset> = listOf(
        Subset(R.string.sym_common, COMMON.joinToString("")),
        Subset(R.string.sym_cjk_punct, "，。、；：？！“”‘’（）【】《》〈〉「」『』〔〕〖〗…—～·〃々〆〇〒〓"),
        Subset(R.string.sym_punct, "“”‘’«»‹›¡¿…–—‒―·•‣§¶†‡′″‴‰‱※‼⁇⁈⁉¦¨´¸ˆ˜"),
        Subset(R.string.sym_currency, "$¢£¤¥₠₡₢₣₤₥₦₧₨₩₪₫€₭₮₯₰₱₲₳₴₵₸₹₺₼₽₾₿"),
        Subset(R.string.sym_math, "±×÷≈≠≡≤≥≪≫∞√∛∜∑∏∫∬∮∂∆∇∈∉∋∩∪⊂⊃⊆⊇∧∨¬∀∃∄∅∴∵∝∠⊥∥≅∼°′″‰ℵ"),
        Subset(R.string.sym_super, "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾ⁿⁱ₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎ₐₑₒₓ"),
        Subset(R.string.sym_numbers, "½⅓⅔¼¾⅕⅖⅗⅘⅙⅚⅛⅜⅝⅞ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫⅰⅱⅲⅳⅴⅵⅶⅷⅸⅹ"),
        Subset(R.string.sym_enclosed, "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳⑴⑵⑶⑷⑸⑹⑺⑻⑼⑽⒈⒉⒊⒋⒌⒍⒎⒏⒐⒑❶❷❸❹❺❻❼❽❾❿㈠㈡㈢㈣㈤㈥㈦㈧㈨㈩"),
        Subset(R.string.sym_arrows, "←↑→↓↔↕↖↗↘↙↚↛↮⇐⇑⇒⇓⇔⇕⇦⇧⇨⇩➔➜➝➞➟➠➡⟵⟶⟷↩↪↺↻"),
        Subset(R.string.sym_shapes, "■□▢▣▤▥▦▧▨▩▪▫▬▭▮▯▰▱▲△▴▵▶▷▸▹►▻▼▽▾▿◀◁◂◃◄◅◆◇◈◉◊○◌◍◎●◐◑◒◓◔◕◢◣◤◥◦◯★☆"),
        Subset(R.string.sym_misc, "☀☁☂☃☎☏☐☑☒☕☚☛☜☝☞☟☯♀♂♠♡♢♣♤♥♦♧♨♩♪♫♬♭♮♯✁✂✆✈✉✌✍✎✏✐✑✒✓✔✕✖✗✘✙✚✛✜✝✞✟✠✡✢✣✤✥✦✧✩✪✫✬✭✮✯✰✱✲✳✴✵✶✷✸✹✺✻✼✽✾✿❀❁❂❃❄❅❆❇❈❉❊❋"),
        Subset(R.string.sym_greek, "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩαβγδεζηθικλμνξοπρστυφχψωϑϕϖ"),
        Subset(R.string.sym_pinyin, "āáǎàōóǒòēéěèīíǐìūúǔùǖǘǚǜüĀÁǍÀŌÓǑÒĒÉĚÈĪÍǏÌŪÚǓÙǕǗǙǛÜ"),
        Subset(R.string.sym_letterlike, "℃℉№℡™℠℗ℓ℮Ω℧Å℩㎎㎏㎜㎝㎞㎡㏄㏎㏑㏒㏕"),
        Subset(R.string.sym_latin, "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõöøùúûüýþÿĀāĂăĄąĆćČčĎďĒēĘęĚěĞğĪīİıŁłŃńŇňŌōŐőŒœŘřŚśŞşŠšŢţŤťŪūŮůŰűŸŹźŻżŽž"),
    )

    /** [text] as its characters (whole code points). */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            out.add(text.substring(i, i + n)); i += n
        }
        return out
    }

    /** [s]'s Unicode name ("GREEK SMALL LETTER ALPHA"), empty when the platform has none. */
    fun name(s: String): String = runCatching { Character.getName(s.codePointAt(0)) }.getOrNull().orEmpty()

    /** [s]'s code as Word's dialog shows it: `U+03B1`. */
    fun code(s: String): String = "U+" + Integer.toHexString(s.codePointAt(0)).uppercase().padStart(4, '0')

    /**
     * The characters of every subset matching [query]: a word of its name (`arrow`, `alpha`), its code (`2192`,
     * `U+2192`), or the character itself; each once, in subset order.
     */
    fun search(query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hex = q.removePrefix("U+").removePrefix("u+").takeIf { h -> h.length in 2..6 && h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }?.uppercase()
        val words = q.uppercase().split(' ').filter { it.isNotEmpty() }
        return SUBSETS.flatMap { it.chars }.distinct().filter { s ->
            s == q || (hex != null && code(s).removePrefix("U+").trimStart('0') == hex.trimStart('0')) ||
                name(s).let { n -> n.isNotEmpty() && words.all { w -> n.contains(w) } }
        }
    }

    /** The recently used list after [s] is used: it first, at most [max]. */
    fun used(recent: List<String>, s: String, max: Int = 20): List<String> = (listOf(s) + recent.filter { it != s }).take(max)

    /** The recently used list as kept in preferences (code points, spaces between) and back. */
    fun encode(recent: List<String>): String = recent.joinToString(" ") { Integer.toHexString(it.codePointAt(0)) }
    fun decode(text: String?): List<String> =
        text.orEmpty().split(' ').mapNotNull { h -> h.toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } }

    private const val PREFS = "aero_symbols"
    private const val KEY = "recent"

    /** The symbols this device used last (none yet: [COMMON]'s first ones fill the menu). */
    fun recent(context: android.content.Context): List<String> =
        decode(context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY, ""))

    /** Remembers [s] as just used. */
    fun remember(context: android.content.Context, s: String) {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val next = encode(used(decode(prefs.getString(KEY, "")), s))
        prefs.edit { putString(KEY, next) }
    }

    /** What the Symbol menu shows: the recently used, then the common ones, [count] in all. */
    fun menu(recent: List<String>, count: Int = 24): List<String> = (recent + COMMON).distinct().take(count)
}
