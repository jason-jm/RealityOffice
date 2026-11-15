package com.therealityteam.aeropages.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** Open [url] in the system browser (a bare host gets `https://`); false when no app can handle it. */
internal fun openExternalUrl(context: Context, url: String): Boolean = runCatching {
    val target = if (url.contains("://")) url else "https://$url"
    context.startActivity(Intent(Intent.ACTION_VIEW, target.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}.isSuccess
