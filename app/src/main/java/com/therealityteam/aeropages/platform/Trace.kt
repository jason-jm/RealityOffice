package com.therealityteam.aeropages.platform

import java.util.concurrent.ConcurrentHashMap

/**
 * Cheap wall-clock accounting for the file readers: each [section] adds its duration to a named
 * total, and [summary] prints them largest first ("xml.parse=812ms×14 unzip=40ms×1"). The document
 * session resets it before a read and logs the summary next to the open time (tag `AeroOpen`), so
 * a slow open on the headset can be attributed without a profiler.
 */
object Trace {
    private val totalsNs = ConcurrentHashMap<String, Long>()
    private val counts = ConcurrentHashMap<String, Int>()

    inline fun <T> section(name: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            add(name, System.nanoTime() - t0)
        }
    }

    fun add(name: String, ns: Long) {
        totalsNs.merge(name, ns) { a, b -> a + b }
        counts.merge(name, 1) { a, b -> a + b }
    }

    fun reset() { totalsNs.clear(); counts.clear() }

    fun summary(): String = totalsNs.entries.sortedByDescending { it.value }
        .joinToString(" ") { (k, ns) -> "$k=${ns / 1_000_000}ms×${counts[k] ?: 0}" }
}
