package com.therealityteam.aeropages.platform

import android.content.Intent
import android.os.Bundle
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import com.therealityteam.aeropages.ui.OpenRequests

/** Spatial SDK launch stub; documents opened from other apps (VIEW / SEND) are forwarded to [OpenRequests]. */
class LaunchActivity : SpatialLaunchActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OpenRequests.submit(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        OpenRequests.submit(intent)
    }
}
