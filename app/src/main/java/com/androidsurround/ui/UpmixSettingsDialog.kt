package com.androidsurround.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidsurround.model.UpmixConfig
import com.androidsurround.model.UpmixMethod
import kotlin.math.roundToInt

@Composable
fun UpmixSettingsDialog(
    config: UpmixConfig,
    onConfigChanged: (UpmixConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var enabled by remember { mutableStateOf(config.enabled) }
    var method by remember { mutableStateOf(config.method) }
    var mixLfe by remember { mutableStateOf(config.mixLfe) }
    var lfeCutoff by remember { mutableFloatStateOf(config.lfeCutoffHz.toFloat()) }
    var fcCutoff by remember { mutableFloatStateOf(config.fcCutoffHz.toFloat()) }
    var rearDelay by remember { mutableFloatStateOf(config.rearDelayMs) }
    var lfeLevel by remember { mutableFloatStateOf(config.lfeLevel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upmix Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SwitchRow("Enable Upmixing", enabled, { enabled = it })

                if (enabled) {
                    Text("Method", style = MaterialTheme.typography.labelMedium)
                    UpmixMethod.entries.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = method == m,
                                onClick = { method = m },
                            )
                            Column {
                                Text(m.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    m.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    HorizontalDivider()
                    SwitchRow("Mix LFE (Subwoofer)", mixLfe, { mixLfe = it })

                    if (mixLfe) {
                        SliderSetting(
                            label = "LFE Cutoff",
                            value = lfeCutoff,
                            onValueChange = { lfeCutoff = it },
                            valueRange = 50f..250f,
                            displayValue = "${lfeCutoff.roundToInt()} Hz",
                        )
                    }

                    SliderSetting(
                        label = "FC Cutoff",
                        value = fcCutoff,
                        onValueChange = { fcCutoff = it },
                        valueRange = 4000f..20000f,
                        displayValue = "${fcCutoff.roundToInt()} Hz",
                    )

                    SliderSetting(
                        label = "Rear Delay",
                        value = rearDelay,
                        onValueChange = { rearDelay = it },
                        valueRange = 0f..30f,
                        displayValue = "${String.format("%.1f", rearDelay)} ms",
                    )

                    SliderSetting(
                        label = "LFE Level",
                        value = lfeLevel,
                        onValueChange = { lfeLevel = it },
                        valueRange = 0f..2f,
                        displayValue = "${String.format("%.1f", lfeLevel)}x",
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onConfigChanged(
                            UpmixConfig(
                                enabled = enabled,
                                method = method,
                                mixLfe = mixLfe,
                                lfeCutoffHz = lfeCutoff.roundToInt(),
                                fcCutoffHz = fcCutoff.roundToInt(),
                                rearDelayMs = rearDelay,
                                lfeLevel = lfeLevel,
                            )
                        )
                        onDismiss()
                    }
                ) { Text("Apply") }
            }
        },
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(displayValue, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
