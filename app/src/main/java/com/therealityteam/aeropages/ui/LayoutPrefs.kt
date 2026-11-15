package com.therealityteam.aeropages.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

/**
 * Remembered panel layout per module — which side panes are open, zoom, view mode — so a window
 * reopens the way it was left (Word remembers its navigation and review panes the same way).
 * Keys are `layout.<module>.<pane>` in the app preferences.
 */
object LayoutPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("aeropages", Context.MODE_PRIVATE)
    fun flag(c: Context, key: String, default: Boolean = false): Boolean = prefs(c).getBoolean("layout.$key", default)
    fun setFlag(c: Context, key: String, v: Boolean) = prefs(c).edit { putBoolean("layout.$key", v) }
    fun float(c: Context, key: String, default: Float): Float = prefs(c).getFloat("layout.$key", default)
    fun setFloat(c: Context, key: String, v: Float) = prefs(c).edit { putFloat("layout.$key", v) }
    fun int(c: Context, key: String, default: Int): Int = prefs(c).getInt("layout.$key", default)
    fun setInt(c: Context, key: String, v: Int) = prefs(c).edit { putInt("layout.$key", v) }
}

/** A boolean UI state that starts from and writes back to [LayoutPrefs]. */
@Composable
internal fun rememberLayoutFlag(key: String, default: Boolean = false): MutableState<Boolean> {
    val context = LocalContext.current
    val state = remember(key) { mutableStateOf(LayoutPrefs.flag(context, key, default)) }
    LaunchedEffect(state.value) { LayoutPrefs.setFlag(context, key, state.value) }
    return state
}

@Composable
internal fun rememberLayoutFloat(key: String, default: Float): MutableFloatState {
    val context = LocalContext.current
    val state = remember(key) { mutableFloatStateOf(LayoutPrefs.float(context, key, default)) }
    LaunchedEffect(state.floatValue) { LayoutPrefs.setFloat(context, key, state.floatValue) }
    return state
}

@Composable
internal fun rememberLayoutInt(key: String, default: Int): MutableIntState {
    val context = LocalContext.current
    val state = remember(key) { mutableIntStateOf(LayoutPrefs.int(context, key, default)) }
    LaunchedEffect(state.intValue) { LayoutPrefs.setInt(context, key, state.intValue) }
    return state
}

/**
 * Perf hooks: log "<label> after N ms" (tag AeroPerf) two vsyncs after [since] — the first frame
 * recomposes with the new content, the second is the first one drawn with it.
 */
internal fun logFirstFrame(label: String, since: Long) {
    val choreographer = android.view.Choreographer.getInstance()
    choreographer.postFrameCallback {
        choreographer.postFrameCallback {
            android.util.Log.i("AeroPerf", "$label after ${android.os.SystemClock.elapsedRealtime() - since} ms")
        }
    }
}
