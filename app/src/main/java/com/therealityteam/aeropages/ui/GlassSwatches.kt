package com.therealityteam.aeropages.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

/** Dev check (`debug.aero.dialog glasstest`): the theme's surfaces and labels side by side on the window glass. */
@Composable
internal fun GlassSwatches() {
    val c = PicoTheme.colorScheme
    Column(Modifier.padding(40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("fillPrimary" to c.fillPrimary, "fillSecondary" to c.fillSecondary, "fillTertiary" to c.fillTertiary, "fillLight" to c.fillLight,
                "chrome" to Aero.chrome, "white" to Color.White, "field" to Aero.field).forEach { (n, col) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(140.dp).clip(RoundedCornerShape(16.dp)).background(col))
                    Text(n, color = c.labelPrimary)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf("labelPrimary" to c.labelPrimary, "labelSecondary" to c.labelSecondary, "labelTertiary" to c.labelTertiary, "labelQuaternary" to c.labelQuaternary).forEach { (n, col) ->
                Text(n, color = col)
            }
        }
        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.fillTertiary).padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("on Neutral: fillSecondary" to c.fillSecondary, "fillLight" to c.fillLight, "white" to Color.White).forEach { (n, col) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(120.dp).clip(RoundedCornerShape(12.dp)).background(col))
                        Text(n, color = c.labelSecondary)
                    }
                }
            }
        }
        // The type scale: PICO's roles and the kit's AeroType, each with its size and line height.
        val t = PicoTheme.typography
        val roles = listOf("bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall, "labelMedium" to t.labelMedium,
            "labelSmall" to t.labelSmall, "titleSmall" to t.titleSmall, "titleMedium" to t.titleMedium,
            "Aero.body" to AeroType.body, "Aero.caption" to AeroType.caption, "Aero.heading" to AeroType.heading)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            roles.forEach { (n, st) -> Text("$n ${st.fontSize.value}/${st.lineHeight.value} ${st.fontWeight?.weight ?: "-"}", style = st, color = c.labelPrimary) }
        }
    }
}
