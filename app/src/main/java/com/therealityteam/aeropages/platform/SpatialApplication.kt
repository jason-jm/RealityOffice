package com.therealityteam.aeropages.platform

import android.app.Application
import android.content.Context
import com.pico.spatial.ui.foundation.dsl.launch
import com.therealityteam.aeropages.mainApp
import com.therealityteam.aeropages.ui.AppTexts
import com.therealityteam.aeropages.ui.Strings

/** App / device facts written into every crash report and the diagnostics summary. */
fun Context.appInfo(): String = buildString {
    append("Reality Office ${com.therealityteam.aeropages.BuildConfig.VERSION_NAME} (${com.therealityteam.aeropages.BuildConfig.VERSION_CODE})")
    if (com.therealityteam.aeropages.BuildConfig.DEBUG) append(" debug")
    append(" · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.SDK_INT})")
    append(" · locale ${java.util.Locale.getDefault()}")
}

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)      // string resources for non-composable UI code
        AppTexts.install(this)  // localised defaults/messages for the model + format layers
        Diagnostics.install(this, appInfo())  // crash reports + session log for the diagnostics bundle
        com.therealityteam.aeropages.ui.AutoCorrectPrefs.load(this)  // Word's AutoCorrect options, as last set
        com.therealityteam.aeropages.ui.CustomListPrefs.load(this)  // Excel's custom lists the user made
        // Review ▸ Chinese Conversion: ICU's transliterators, made on a worker thread (their data loads on first use).
        Thread({ com.therealityteam.aeropages.model.ChineseConversion.engine = IcuChinese.engine() }, "icu-chinese").start()
        StartupClock.mark("application")
        launch(::mainApp)
    }
}
