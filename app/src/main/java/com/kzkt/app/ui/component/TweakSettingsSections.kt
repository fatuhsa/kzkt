package com.kzkt.app.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Engine Parameters",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(
            onClick = {
                scope.launch {
                    viewModel.settingsRepo.saveCustomTimeoutSec(120)
                    viewModel.settingsRepo.saveTweakParam("max_bubbles", 20)
                    viewModel.settingsRepo.saveTweakParam("request_delay", 2.0f)
                    viewModel.settingsRepo.saveTweakParam("pad_x", 0.40f)
                    viewModel.settingsRepo.saveTweakParam("pad_y", 0.25f)
                    viewModel.settingsRepo.saveTweakParam("min_pad", 35)
                    viewModel.settingsRepo.saveJpegQuality(95)
                }
            },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Reset Defaults", style = MaterialTheme.typography.labelMedium)
        }
    }

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
                                        "min_pad", "max_bubbles" -> "${sliderValue.toInt()}"
                                        "jpeg_quality" -> "${sliderValue.toInt()}%"
                                        "custom_timeout" -> "${sliderValue.toInt()}s"
                                        else -> "%.2f".format(sliderValue)
                                    }
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Text(
                                        text = fmt,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
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

/** SFX filter mode segmented row (balanced / relaxed / strict). */
@Composable
fun SfxFilterSection(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val current by remember { derivedStateOf { viewModel.settings.value.filterSfxMode } }
    val modes = listOf("balanced" to "Balanced", "relaxed" to "Relaxed", "strict" to "Strict")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            modes.forEach { (modeKey, modeTitle) ->
                val isSelected = current.lowercase() == modeKey
                Surface(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                scope.launch { viewModel.settingsRepo.saveTweakParam("sfx_mode", modeKey) }
                            },
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = modeTitle,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color =
                                if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                }
            }
        }
    }
}
