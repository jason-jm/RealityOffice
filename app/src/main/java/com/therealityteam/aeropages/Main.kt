package com.therealityteam.aeropages

import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.systemColorScheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.Stage
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.therealityteam.aeropages.ui.AeroDesk
import com.therealityteam.aeropages.ui.OpenRequests
import com.therealityteam.aeropages.ui.ShowSession
import com.therealityteam.aeropages.ui.SPATIAL_SHOW_STAGE
import com.therealityteam.aeropages.ui.SlideShow
import com.therealityteam.aeropages.ui.SpatialShowStage

/**
 * Aero Desk entry point. Hosts the launcher + the Pages/Slides/Sheets modules inside
 * the default planar WindowContainer. [PicoTheme] uses [systemColorScheme], which on
 * PICO OS is a Vibrant color scheme — enabling the system glass/Vibrant rendering.
 *
 * Extra containers make the suite spatial:
 *  - "aux" / "aux2" / "aux3": side windows, each a full Aero Desk, so several documents can be
 *    open at once (Pages + Sheets 并排); "open in new window" picks the first free one.
 *  - "show": a dedicated slideshow window driven by [ShowSession]; the main window
 *    stays interactive as the presenter console (snapshot state syncs both).
 *  - [SPATIAL_SHOW_STAGE]: a Mixed-reality Stage that projects the same show onto a detected
 *    wall (or floats it in front of the user) — see [SpatialShowStage].
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            PicoTheme(colorScheme = systemColorScheme(LocalContext.current)) {
                // Dev hook (debug builds): `debug.aero.dialog glasstest` shows the bare window glass (a design check).
                if (com.therealityteam.aeropages.ui.devDialogProp() == "glasstest") com.therealityteam.aeropages.ui.GlassSwatches()
                else AeroDesk(external = OpenRequests.MAIN)
            }
        }
        OpenRequests.SIDE_WINDOWS.forEach { id ->
            WindowContainer(id = id) {
                PicoTheme(colorScheme = systemColorScheme(LocalContext.current)) { AeroDesk(external = id) }
            }
        }
        WindowContainer(id = "show") {
            PicoTheme(colorScheme = systemColorScheme(LocalContext.current)) {
                val navigator = com.pico.spatial.ui.platform.containers.LocalSpatialNavigator.current
                // Closing the window from the system chrome must not leave the editor pinned in ShowSession.
                androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ShowSession.stop() } }
                val editor = ShowSession.editor
                if (ShowSession.active && editor != null) {
                    SlideShow(editor, fromStart = false, mirror = true, onExit = {
                        ShowSession.stop()
                        navigator.closeWindowContainer()
                    })
                } else {
                    // Session ended from the main window: close this container.
                    androidx.compose.runtime.LaunchedEffect(Unit) { navigator.closeWindowContainer() }
                }
            }
        }
        Stage(id = SPATIAL_SHOW_STAGE) {
            PicoTheme(colorScheme = systemColorScheme(LocalContext.current)) { SpatialShowStage() }
        }
    }
