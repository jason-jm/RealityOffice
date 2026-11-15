package com.therealityteam.aeropages.ui

import android.content.Context
import androidx.core.content.edit
import com.therealityteam.aeropages.R
import com.therealityteam.aeropages.format.DeskFiles
import com.therealityteam.aeropages.model.FieldDates
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The documents opened or saved lately, newest first, each with when: kept in preferences as
 * `path<TAB>millis` lines (a line without a time, as older versions wrote, takes the file's).
 * The home screen adds the app folder's own documents to them and can take any off its list.
 */
object RecentFiles {
    private const val PREFS = "aeropages"
    private const val KEY = "recents"
    private const val DISMISSED = "recents_dismissed"
    private const val PINNED = "recents_pinned"
    private const val KEEP = 20

    data class Recent(val path: String, val at: Long) {
        val file: File get() = File(path)
    }

    private fun read(context: Context): List<Recent> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().split("\n")
            .filter { it.isNotBlank() }
            .map { line ->
                val path = line.substringBefore('\t')
                Recent(path, line.substringAfter('\t', "").toLongOrNull() ?: File(path).lastModified())
            }

    private fun write(context: Context, list: List<Recent>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, list.joinToString("\n") { "${it.path}\t${it.at}" }) }

    /** The recent documents that still exist, newest first. */
    fun load(context: Context): List<Recent> = read(context).filter { File(it.path).exists() }

    /** [path] opened or saved now: to the front of the list (and back on the home screen if it was taken off). */
    fun add(context: Context, path: String, now: Long = System.currentTimeMillis()): List<Recent> {
        // One entry per file, however its path was written (/sdcard/… and /storage/emulated/0/… are the same file).
        val key = same(path)
        val updated = (listOf(Recent(path, now)) + read(context).filter { same(it.path) != key }).take(KEEP)
        write(context, updated)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dismissed = prefs.getStringSet(DISMISSED, emptySet()).orEmpty()
        val remaining = dismissed.filterNot { same(it) == key }.toSet()
        if (remaining.size != dismissed.size) prefs.edit { putStringSet(DISMISSED, remaining) }
        return updated
    }

    /** Takes [path] off the list — and off the home screen, where the app folder's documents would bring it back. */
    fun remove(context: Context, path: String) {
        val key = same(path)
        write(context, read(context).filter { same(it.path) != key })
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putStringSet(DISMISSED, prefs.getStringSet(DISMISSED, emptySet()).orEmpty() + key) }
    }

    /**
     * What the home screen lists: the recent documents and the app folder's own (Welcome.docx, saved
     * documents) by when each was last used, without those taken off the list.
     */
    fun forHome(context: Context, workDir: File, limit: Int = 8): List<Recent> {
        val dismissed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(DISMISSED, emptySet()).orEmpty()
        val own = (workDir.listFiles() ?: emptyArray()).filter { it.isFile && !it.name.startsWith(".") && DeskFiles.kindOfOrNull(it.name) != null }
        return merge(load(context), own, dismissed, limit)
    }

    /**
     * The home list: [recent] and the app folder's [own] documents by when each was last used, each file once — its
     * path in whichever form it was written ([same] makes them comparable) — without the [dismissed] ones.
     */
    internal fun merge(recent: List<Recent>, own: List<File>, dismissed: Set<String>, limit: Int, same: (String) -> String = ::same): List<Recent> {
        val gone = dismissed.map(same).toHashSet()
        val all = recent + own.map { Recent(it.absolutePath, it.lastModified()) }
        // The latest use of each file (a recent entry beats the folder's modified time when it is newer).
        return all.groupBy { same(it.path) }.filterKeys { it !in gone }
            .map { (_, uses) -> uses.maxBy { it.at } }
            .sortedByDescending { it.at }.take(limit)
    }

    /** The documents pinned to the list (Office's Pinned), as their paths were written. */
    fun pinned(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(PINNED, emptySet()).orEmpty()

    /** Whether [path] is pinned, however either was written. */
    fun isPinned(pinned: Set<String>, path: String, same: (String) -> String = ::same): Boolean = pinned.any { same(it) == same(path) }

    /** Pins [path] to the list or unpins it; taking a document off Recent leaves it pinned (as in Office). */
    fun setPinned(context: Context, path: String, on: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val others = prefs.getStringSet(PINNED, emptySet()).orEmpty().filter { same(it) != same(path) }.toSet()
        prefs.edit { putStringSet(PINNED, if (on) others + path else others) }
    }

    /** The Pinned list: the pinned documents that still exist, each with its last use ([known] when listed there), latest first. */
    fun forPinned(context: Context, known: List<Recent>): List<Recent> = pinnedOf(pinned(context), known + load(context))

    internal fun pinnedOf(pinned: Set<String>, known: List<Recent>, exists: (String) -> String? = { p -> p.takeIf { File(it).exists() } },
                          same: (String) -> String = ::same): List<Recent> =
        pinned.mapNotNull { p -> exists(p)?.let { path ->
            known.filter { same(it.path) == same(path) }.maxByOrNull { it.at } ?: Recent(path, File(path).lastModified())
        } }.distinctBy { same(it.path) }.sortedByDescending { it.at }

    /** [path] in one form for comparing: symbolic links (/sdcard) resolved. */
    private fun same(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** When, in a few words: just now, 5 min ago, 3 hr ago, yesterday, Oct 2, or a date with its year. */
    fun whenText(at: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val minutes = (now - at) / 60_000
        val then = LocalDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
        val today = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone).toLocalDate()
        return when {
            minutes < 1 -> Strings.get(R.string.time_just_now)
            minutes < 60 -> Strings.get(R.string.time_minutes_ago, minutes.toInt())
            then.toLocalDate() == today -> Strings.get(R.string.time_hours_ago, (minutes / 60).toInt())
            then.toLocalDate() == today.minusDays(1) -> Strings.get(R.string.time_yesterday)
            then.year == today.year -> FieldDates.format(Strings.get(R.string.time_day_pattern), then)
            else -> FieldDates.format(Strings.get(R.string.tpl_date_pattern), then)
        }
    }
}
