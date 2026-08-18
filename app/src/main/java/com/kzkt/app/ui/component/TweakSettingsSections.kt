package com.kzkt.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzkt.app.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** OpenCV inpainting toggle + all tweak-parameter sliders (advanced settings). */
@Composable
fun TweakParamsSection(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()

    // Per-field derived read so the section doesn't recompose on unrelated settings.
    val useInpainting by remember { derivedStateOf { viewModel.settings.value.useInpainting } }
    Material3SettingsGroup(
        items =
            listOf(
                Material3SettingsItem(
                    leadingContent = { SettingsIcon(Icons.Outlined.AutoFixHigh) },
                    title = { Text("OpenCV Text Inpainting") },
                    description = { Text("Seamlessly erase original text matching background textures") },
                    trailingContent = {
                        Switch(
                            checked = useInpainting,
                            onCheckedChange = { scope.launch { viewModel.settingsRepo.saveUseInpainting(it) } },
                        )
                    },
                ),
            ),
    )
    Spacer(Modifier.height(8.dp))

    TweakSlider(
        viewModel,
        "custom_timeout",
        "Custom Request Timeout",
        30f..600f,
    )
    TweakSlider(
        viewModel,
        "max_bubbles",
        "Bubbles Per Request",
        5f..50f,
    )
    TweakSlider(
        viewModel,
        "request_delay",
        "Min Request Delay",
        0.5f..10f,
    )
    TweakSlider(
        viewModel,
        "pad_x",
        "Pad X Ratio",
        0.1f..1.0f,
    )
    TweakSlider(
        viewModel,
        "pad_y",
        "Pad Y Ratio",
        0.1f..1.0f,
    )
    TweakSlider(
        viewModel,
        "min_pad",
        "Min Padding (px)",
        5f..100f,
    )
    TweakSlider(
        viewModel,
        "jpeg_quality",
        "JPEG Output Quality",
        70f..100f,
    )
}

private fun saveTweakSliderValue(
    scope: CoroutineScope,
    viewModel: MainViewModel,
    keyField: String,
    value: Float,
) {
    scope.launch {
        when (keyField) {
            "custom_timeout" -> viewModel.settingsRepo.saveCustomTimeoutSec(value.toInt())
            "jpeg_quality" -> viewModel.settingsRepo.saveJpegQuality(value.toInt())
            else ->
                viewModel.settingsRepo.saveTweakParam(
                    keyField,
                    when (keyField) {
                        "max_bubbles", "min_pad" -> value.toInt()
                        else -> value
                    },
                )
        }
    }
}

/**
 * One tweak-parameter slider (advanced settings).
 */
@Composable
private fun TweakSlider(
    viewModel: MainViewModel,
    keyField: String,
    label: String,
    range: ClosedFloatingPointRange<Float>,
) {
    val scope = rememberCoroutineScope()

    val value by remember {
        derivedStateOf {
            when (keyField) {
                "max_bubbles" ->
                    viewModel.settings.value.maxBubblesPerRequest
                        .toFloat()
                "request_delay" -> viewModel.settings.value.minRequestDelay
                "pad_x" -> viewModel.settings.value.padXRatio
                "pad_y" -> viewModel.settings.value.padYRatio
                "min_pad" ->
                    viewModel.settings.value.minPad
                        .toFloat()
                "custom_timeout" ->
                    viewModel.settings.value.customTimeoutSec
                        .toFloat()
                "jpeg_quality" ->
                    viewModel.settings.value.jpegQuality
                        .toFloat()
                else -> 0f
            }
        }
    }
    var sliderValue by remember(value) { mutableFloatStateOf(value) }

    Material3SettingsGroup(
        items =
            listOf(
                Material3SettingsItem(
                    title = {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                val fmt =
                                    when (keyField) {
                                        "request_delay" -> "%.1fs".format(sliderValue)
                                        "min_pad", "max_bubbles", "jpeg_quality" -> "${sliderValue.toInt()}"
                                        "custom_timeout" -> "${sliderValue.toInt()}s"
                                        else -> "%.2f".format(sliderValue)
                                    }
                                Text(fmt, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    saveTweakSliderValue(scope, viewModel, keyField, sliderValue)
                                },
                                valueRange = range,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    },
                ),
            ),
    )
}

/** SFX filter mode radio group (balanced / relaxed / strict). */
@Composable
fun SfxFilterSection(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val current by remember { derivedStateOf { viewModel.settings.value.filterSfxMode } }
    val modes = listOf("balanced", "relaxed", "strict")
    Material3SettingsGroup(
        items =
            modes.map { mode ->
                Material3SettingsItem(
                    leadingContent = { SettingsIcon(Icons.Outlined.Tune) },
                    title = { Text(mode.uppercase().replaceFirstChar { it }) },
                    isHighlighted = current == mode,
                    trailingContent = {
                        RadioButton(
                            selected = current == mode,
                            onClick = { scope.launch { viewModel.settingsRepo.saveTweakParam("sfx_mode", mode) } },
                        )
                    },
                )
            },
    )
}
