package com.therealityteam.aeropages

import com.therealityteam.aeropages.ui.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every shipped language has every string of the default (English) resources, with the same placeholders, quotes that
 * aapt accepts, and nothing left empty; every language the app offers has its resources and is in the system's
 * locale list.
 */
class TranslationsTest {
    private val res = File("src/main/res")
    private val line = Regex("""^\s*<string name="([^"]+)"[^>]*>(.*)</string>\s*$""")
    private val placeholder = Regex("""%(?:\d+\$)?[sdf]|%%""")

    private fun strings(dir: String): Map<String, String> =
        File(res, "$dir/strings.xml").readLines().mapNotNull { line.find(it) }.associate { it.groupValues[1] to it.groupValues[2] }

    private fun dirOf(tag: String): String {
        val (lang, region) = tag.split('-').let { it[0] to it.getOrNull(1) }
        val code = if (lang == "id") "in" else lang
        return when {
            tag == "en" -> "values"
            tag == "zh-CN" -> "values-zh"
            region != null -> "values-$code-r$region"
            else -> "values-$code"
        }
    }

    @Test
    fun everyLanguageHasEveryStringWithItsPlaceholders() {
        val base = strings("values")
        val dirs = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!.map { it.name }
        assertTrue("translations found", dirs.size >= 3)
        for (dir in dirs) {
            val got = strings(dir)
            val missing = base.keys - got.keys
            val extra = got.keys - base.keys
            assertTrue("$dir is missing ${missing.take(10)}", missing.isEmpty())
            assertTrue("$dir has strings the default does not: ${extra.take(10)}", extra.isEmpty())
            val wrong = base.filter { (k, v) -> placeholder.findAll(v).map { it.value }.sorted().toList() != placeholder.findAll(got.getValue(k)).map { it.value }.sorted().toList() }.keys
            assertTrue("$dir changes the placeholders of ${wrong.take(10)}", wrong.isEmpty())
            val raw = got.filter { (_, v) -> v.removeSurrounding("\"").contains(Regex("""(?<!\\)['"]""")) }.keys
            assertTrue("$dir has unescaped quotes in ${raw.take(10)}", raw.isEmpty())
            val empty = got.filter { (k, v) -> v.isBlank() && base.getValue(k).isNotBlank() }.keys
            assertTrue("$dir leaves ${empty.take(10)} empty", empty.isEmpty())
        }
    }

    @Test
    fun everyOfferedLanguageShipsAndIsInTheSystemList() {
        val config = File(res, "xml/locales_config.xml").readText()
        for (lang in AppLanguage.entries) {
            val tag = lang.tag ?: continue
            assertTrue("$tag has no resources (${dirOf(tag)})", File(res, "${dirOf(tag)}/strings.xml").exists())
            val listed = if (tag == "id") "in" else tag // Indonesian under its old code, as values-in
            assertTrue("$tag is not in locales_config.xml", config.contains("android:name=\"$listed\""))
        }
        assertEquals(AppLanguage.entries.size - 1, Regex("<locale ").findAll(config).count())
    }

    @Test
    fun launcherNameIsRealityOfficeInEveryShippedLanguage() {
        for (language in AppLanguage.entries) {
            val tag = language.tag ?: continue
            assertEquals(tag, "Reality Office", strings(dirOf(tag)).getValue("app_name"))
        }
    }

    @Test
    fun aTagFindsItsLanguage() {
        assertEquals(AppLanguage.TRADITIONAL_CHINESE, AppLanguage.fromTag("zh-Hant-HK"))
        assertEquals(AppLanguage.CHINESE, AppLanguage.fromTag("zh-Hans-CN"))
        assertEquals(AppLanguage.PORTUGUESE, AppLanguage.fromTag("pt-PT"))
        assertEquals(AppLanguage.INDONESIAN, AppLanguage.fromTag("in-ID"))
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTag("de-AT"))
        assertEquals("a language the app does not have", AppLanguage.ENGLISH, AppLanguage.fromTag("sv-SE"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromTag(null))
    }
}
