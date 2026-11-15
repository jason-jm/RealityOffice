package com.therealityteam.aeropages

import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.pico.spatial.ui.platform.ability.UpperLimbRenderMode
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import com.pico.spatial.ui.platform.containers.StageStyle

/** The spatial shell as the UI sees it outside a WindowContainer: records what the app asks to open. */
internal class RecordingNavigator : SpatialNavigator {
    val opened = mutableListOf<String>()
    val stages = mutableListOf<String>()
    override fun openWindowContainer(id: String, tag: String?, bundle: Bundle?) { opened += id }
    override fun closeWindowContainer(id: String, tag: String?) {}
    override fun closeWindowContainer() {}
    override fun minimizeWindowContainer(id: String, tag: String?): Boolean = false
    override fun minimizeWindowContainer(): Boolean = false
    override fun restoreWindowContainer(id: String, tag: String?): Boolean = false
    override fun restoreWindowContainer(): Boolean = false
    override suspend fun openStage(id: String, style: StageStyle?, bundle: Bundle?, upperLimbRenderMode: UpperLimbRenderMode): OpenStageResult {
        stages += id
        return OpenStageResult.NotAllowed
    }
    override suspend fun closeStage() {}
}

internal object TestSupport {
    private var crashLoggerInstalled = false

    /** Not the title bar's search box — an empty text field too, named Search — when a test looks for a document's field. */
    val notSearch: androidx.compose.ui.test.SemanticsMatcher get() = !androidx.compose.ui.test.hasContentDescription(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.search))

    /**
     * Clears the debug-build dev hooks (`debug.aero.page` scrolls the canvas, `debug.aero.export`
     * seeds sample content and exports, …). They are set by hand for screenshots and survive until
     * the emulator reboots; left set, they scroll the tested paragraph off screen or add content.
     */
    fun clearDevProps() {
        listOf("debug.aero.page", "debug.aero.export", "debug.aero.module", "debug.aero.dialog").forEach { setDevProp(it, "''") }
    }

    /** Sets a debug-build dev hook property (the shell reads `''` literally; the app treats it as unset). */
    fun setDevProp(name: String, value: String) {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        runCatching {
            automation.executeShellCommand("setprop $name $value").use { fd ->
                java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() }
            }
        }
    }

    /** A crash on any app thread ends the instrumentation with only "Process crashed": keep the stack in logcat. */
    fun installCrashLogger() {
        if (crashLoggerInstalled) return
        crashLoggerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            android.util.Log.e("DeskFlowsTest", "Uncaught exception on thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** True when a screen reader would have something to say for this (merged) node. */
    fun SemanticsNode.isLabelled(): Boolean {
        val cfg = config
        val text = cfg.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true
        val description = cfg.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true
        val editable = cfg.getOrNull(SemanticsProperties.EditableText)?.text?.isNotBlank() == true || cfg.contains(SemanticsActions.SetText)
        val state = cfg.getOrNull(SemanticsProperties.StateDescription)?.isNotBlank() == true
        return text || description || editable || state
    }

    /** A short, log-friendly description of a node for failure messages. */
    fun SemanticsNode.describe(): String {
        val cfg = config
        val role = cfg.getOrNull(SemanticsProperties.Role)?.toString() ?: "-"
        val b = boundsInRoot
        return "role=$role bounds=(${b.left.toInt()},${b.top.toInt()})-(${b.right.toInt()},${b.bottom.toInt()}) " +
            "text=${cfg.getOrNull(SemanticsProperties.Text)} desc=${cfg.getOrNull(SemanticsProperties.ContentDescription)}"
    }
}
