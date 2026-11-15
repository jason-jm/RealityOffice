package com.therealityteam.aeropages.ui

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes

/**
 * String-resource lookup for code that runs outside composition — event handlers, the
 * document session's status text, sample content and lookup tables. Composables read
 * `stringResource(...)` directly; both resolve through the app's resources, so they follow
 * the device language and the in-app language choice ([AppLocale]).
 */
// Holds the *application* context, which lives as long as the process — not an Activity leak.
@SuppressLint("StaticFieldLeak")
object Strings {
    @Volatile
    private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val c = checkNotNull(app) { "Strings.init(context) must run before the UI" }
        return if (args.isEmpty()) c.getString(id) else c.getString(id, *args)
    }
}
