package com.therealityteam.aeropages.platform

import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Cold-start milestones as milliseconds since the process started, logged under `AeroStart`
 * (`adb logcat -s AeroStart:I`). Each name logs once per process so recompositions stay quiet.
 */
object StartupClock {
    private val seen = HashSet<String>()

    fun mark(name: String) {
        if (!seen.add(name)) return
        val ms = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        Log.i("AeroStart", "$name +${ms}ms")
    }
}
