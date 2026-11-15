package com.therealityteam.aeropages.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.windows.AlertDialog
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.therealityteam.aeropages.R

/** Whether Review shows Chinese Conversion: the app speaks Chinese (Word shows the group with a Chinese editing language). */
internal val showChineseConversion: Boolean get() = java.util.Locale.getDefault().language == "zh"

/**
 * Review ▸ Chinese Conversion as Word's three small buttons — 繁转简, 简转繁, 简繁转换… (the dialog: which way, and whether
 * to convert the words the mainland and Taiwan say differently, remembered) — converting what [onConvert] converts
 * (the selection, else everything; it says how many characters changed, -1 for a device that cannot), the outcome
 * told through [onStatus].
 */
@Composable
internal fun ChineseConversionButtons(onConvert: (toTraditional: Boolean, vocabulary: Boolean) -> Int, onStatus: (String) -> Unit) {
    var vocabulary by rememberLayoutFlag("zh.vocabulary", true)
    var dialog by remember { mutableStateOf(devDialogProp() == "zhconvert") }
    fun run(toTraditional: Boolean) {
        val n = onConvert(toTraditional, vocabulary)
        onStatus(when {
            n < 0 -> Strings.get(R.string.zh_unavailable)
            n == 0 -> Strings.get(R.string.zh_nothing)
            else -> Strings.get(R.string.zh_converted, n)
        })
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RibbonSmallButton("简", stringResource(R.string.zh_to_simplified), { run(false) })
        RibbonSmallButton("繁", stringResource(R.string.zh_to_traditional), { run(true) })
        RibbonSmallButton("⇄", stringResource(R.string.zh_dialog), { dialog = true })
    }
    if (dialog) {
        var toTraditional by remember { mutableStateOf(true) }
        AeroDialog(title = stringResource(R.string.zh_title), onDismiss = { dialog = false }, width = 400.dp, buttons = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton(stringResource(R.string.cancel), { dialog = false })
                    DialogButton(stringResource(R.string.btn_ok), { dialog = false; run(toTraditional) }, primary = true)
                }
            }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(400.dp)) {
                    // Word's Chinese Conversion: the direction as option buttons.
                    DialogLabel(stringResource(R.string.zh_direction))
                    RadioOption(stringResource(R.string.zh_dir_to_simplified), !toTraditional, { toTraditional = false })
                    RadioOption(stringResource(R.string.zh_dir_to_traditional), toTraditional, { toTraditional = true })
                    CheckRow(stringResource(R.string.zh_vocabulary), vocabulary) { vocabulary = !vocabulary }
                    Text(stringResource(R.string.zh_vocabulary_hint), style = AeroType.caption, color = PicoTheme.colorScheme.labelSecondary)
                }
            }
    }
}
