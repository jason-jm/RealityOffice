package com.therealityteam.aeropages.ui

import android.net.Uri
import kotlinx.coroutines.channels.Channel

/** Each live editor receives all of its own system picker results, including simultaneous saves from other windows. */
object SaveRequests {
    private val destinations = mutableMapOf<String, Channel<Uri>>()

    @Synchronized
    fun attach(sessionId: String): Channel<Uri> = Channel<Uri>(Channel.UNLIMITED).also { destinations[sessionId] = it }

    @Synchronized
    fun post(sessionId: String, uri: Uri) { destinations[sessionId]?.trySend(uri) }

    @Synchronized
    fun detach(sessionId: String) { destinations.remove(sessionId)?.close() }
}
