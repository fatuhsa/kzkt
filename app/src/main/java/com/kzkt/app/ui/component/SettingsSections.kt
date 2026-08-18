package com.kzkt.app.ui.component

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzkt.app.BuildConfig
import com.kzkt.app.core.Config
import com.kzkt.app.data.TranslationCacheRepository
import com.kzkt.app.ui.MainViewModel
import com.kzkt.app.ui.theme.DefaultThemeColor
import kotlinx.coroutines.launch

@Composable
fun SettingsAiSection(
    viewModel: MainViewModel,
    providerChips: List<Pair<String, String>>,
    languageChips: List<Pair<String, String>>,
) {
    val scope = rememberCoroutineScope()
    val selectedProvider by remember { derivedStateOf { viewModel.settings.value.llmProvider } }
    val language by remember { derivedStateOf { viewModel.settings.value.targetLanguage } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "AI & Provider",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        ChipsRow(
            chips = providerChips,
            currentValue = selectedProvider,
            onValueUpdate = { key ->
                scope.launch {
                    viewModel.settingsRepo.saveProvider(key)
                    viewModel.refreshModelsForProvider(key)
                }
            },
        )
        Config.PROVIDER_REGISTRY[selectedProvider]?.let { meta ->
            Text(
                meta.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        ActiveProviderConfigCard(viewModel)

        Spacer(Modifier.height(4.dp))
        Text(
            "Target Language",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        ChipsRow(
            chips = languageChips,
            currentValue = language,
            onValueUpdate = { lang -> scope.launch { viewModel.settingsRepo.saveLanguage(lang) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsEngineSection(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val useLocalOcr by remember { derivedStateOf { viewModel.settings.value.useLocalOcr } }
    val useImageUpscaler by remember { derivedStateOf { viewModel.settings.value.useImageUpscaler } }
    val translateSfx by remember { derivedStateOf { viewModel.settings.value.translateSfx } }
    val translateFreeText by remember { derivedStateOf { viewModel.settings.value.translateFreeText } }
    val useSse by remember { derivedStateOf { viewModel.settings.value.useSse } }
    val ocrScript by remember { derivedStateOf { viewModel.settings.value.ocrScript } }

    val ocrScriptItem: Material3SettingsItem? =
        if (useLocalOcr || translateFreeText) {
            Material3SettingsItem(
                leadingContent = { SettingsIcon(Icons.Outlined.Language) },
                title = { Text("OCR Script") },
                description = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Which ML Kit model detects text (experimental)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                "en" to "EN",
                                "jp" to "JP",
                                "kr" to "KR",
                                "cn" to "CN",
                                "auto" to "Auto",
                            ).forEach { (key, label) ->
                                FilterChip(
                                    selected = ocrScript == key,
                                    onClick = { scope.launch { viewModel.settingsRepo.saveOcrScript(key) } },
                                    label = { Text(label) },
                                    shape = RoundedCornerShape(50),
                                )
                            }
                        }
                    }
                },
            )
        } else {
            null
        }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Science,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Detection & Engine",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Material3SettingsGroup(
            items =
                listOf(
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.Science) },
                        title = { Text("On-Device Local OCR") },
                        description = {
                            Text(
                                if (useLocalOcr) {
                                    "Google ML Kit extracts text locally before LLM call."
                                } else {
                                    "Default: sends bubble mosaic directly to vision LLM."
                                },
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = useLocalOcr,
                                onCheckedChange = { enabled -> scope.launch { viewModel.settingsRepo.saveUseLocalOcr(enabled) } },
                            )
                        },
                        onClick = { scope.launch { viewModel.settingsRepo.saveUseLocalOcr(!useLocalOcr) } },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.Science) },
                        title = { Text("Smart Image Upscaler") },
                        description = { Text("Enhance low-resolution images for better text recognition.") },
                        trailingContent = {
                            Switch(
                                checked = useImageUpscaler,
                                onCheckedChange = { scope.launch { viewModel.settingsRepo.saveUseImageUpscaler(it) } },
                            )
                        },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.Tune) },
                        title = { Text("Translate Sound Effects (SFX)") },
                        description = {
                            Text(
                                if (translateSfx) {
                                    "ON: onomatopoeia like ドドド / バキ get translated."
                                } else {
                                    "OFF: pure SFX bubbles are skipped."
                                },
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = translateSfx,
                                onCheckedChange = { enabled -> scope.launch { viewModel.settingsRepo.saveTranslateSfx(enabled) } },
                            )
                        },
                        onClick = { scope.launch { viewModel.settingsRepo.saveTranslateSfx(!translateSfx) } },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.TextFields) },
                        title = { Text("Translate Free Text") },
                        description = {
                            Text(
                                if (translateFreeText) "ON: translate text outside speech bubbles" else "OFF: only speech bubbles",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = translateFreeText,
                                onCheckedChange = { enabled ->
                                    scope.launch { viewModel.settingsRepo.saveTranslateFreeText(enabled) }
                                },
                            )
                        },
                        onClick = { scope.launch { viewModel.settingsRepo.saveTranslateFreeText(!translateFreeText) } },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.Sync) },
                        title = { Text("Streaming (SSE)") },
                        description = {
                            Text(
                                if (useSse) "ON: stream responses in real-time" else "OFF: standard request-response",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = useSse,
                                onCheckedChange = { enabled -> scope.launch { viewModel.settingsRepo.saveUseSse(enabled) } },
                            )
                        },
                        onClick = { scope.launch { viewModel.settingsRepo.saveUseSse(!useSse) } },
                    ),
                ) + if (ocrScriptItem != null) listOf(ocrScriptItem) else emptyList(),
        )
    }
}

@Composable
fun SettingsRenderSection(
    viewModel: MainViewModel,
    onShowFontDialog: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val textColor by remember { derivedStateOf { viewModel.settings.value.renderTextColor } }
    val fontScale by remember { derivedStateOf { viewModel.settings.value.renderFontScale } }
    val renderStyle by remember { derivedStateOf { viewModel.settings.value.renderStyle } }
    val customFontPath by remember { derivedStateOf { viewModel.settings.value.customFontPath } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Text & Rendering",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Material3SettingsGroup(
            items =
                listOf(
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.Tune) },
                        title = { Text("Render style") },
                        description = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                listOf("manga" to "Manga", "clean" to "Clean").forEach { (key, label) ->
                                    FilterChip(
                                        selected = renderStyle == key,
                                        onClick = { scope.launch { viewModel.settingsRepo.saveRenderStyle(key) } },
                                        label = { Text(label) },
                                        shape = RoundedCornerShape(50),
                                    )
                                }
                            }
                        },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.Palette) },
                        title = { Text("Text color") },
                        description = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                listOf("auto" to "Auto", "white" to "White", "black" to "Black").forEach { (key, label) ->
                                    FilterChip(
                                        selected = textColor == key,
                                        onClick = { scope.launch { viewModel.settingsRepo.saveRenderTextColor(key) } },
                                        label = { Text(label) },
                                        shape = RoundedCornerShape(50),
                                    )
                                }
                            }
                        },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.TextFields) },
                        title = {
                            var localScale by remember(fontScale) { mutableFloatStateOf(fontScale) }
                            LaunchedEffect(fontScale) {
                                if (fontScale != localScale) localScale = fontScale
                            }
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Font scale")
                                    Text(
                                        "%.0f%%".format(localScale * 100),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    "Global size multiplier for rendered bubble text",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                                Slider(
                                    value = localScale,
                                    onValueChange = { localScale = it },
                                    onValueChangeFinished = {
                                        scope.launch { viewModel.settingsRepo.saveRenderFontScale(localScale) }
                                    },
                                    valueRange = 0.8f..1.5f,
                                    steps = 6,
                                )
                            }
                        },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.TextFields) },
                        title = { Text("Custom Font (.ttf / .otf)") },
                        description = {
                            Text(
                                if (customFontPath.isNotBlank()) {
                                    java.io.File(customFontPath).name
                                } else {
                                    "Tap to import custom font file"
                                },
                            )
                        },
                        trailingContent =
                            if (customFontPath.isNotBlank()) {
                                {
                                    IconButton(onClick = { scope.launch { viewModel.settingsRepo.saveCustomFontPath("") } }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear Custom Font")
                                    }
                                }
                            } else {
                                null
                            },
                        onClick = onShowFontDialog,
                    ),
                ),
        )
    }
}

@Composable
fun SettingsAppearanceSection(
    currentThemeMode: String,
    darkTheme: Boolean,
    pureBlack: Boolean,
    themeColor: Color,
    onThemeModeChange: (String) -> Unit,
    onPureBlackChange: (Boolean) -> Unit,
    onThemeColorChange: (Color) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Palette,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Appearance",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Material3SettingsGroup(
            items =
                listOf(
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.DarkMode) },
                        title = { Text("Theme mode") },
                        description = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Text(
                                    when (currentThemeMode) {
                                        "dark" -> "Dark theme enabled"
                                        "light" -> "Light theme enabled"
                                        else -> "Follow system default"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = currentThemeMode == "system",
                                        onClick = { onThemeModeChange("system") },
                                        label = { Text("Auto") },
                                        shape = RoundedCornerShape(50),
                                    )
                                    FilterChip(
                                        selected = currentThemeMode == "light",
                                        onClick = { onThemeModeChange("light") },
                                        label = { Text("Light") },
                                        shape = RoundedCornerShape(50),
                                    )
                                    FilterChip(
                                        selected = currentThemeMode == "dark",
                                        onClick = { onThemeModeChange("dark") },
                                        label = { Text("Dark") },
                                        shape = RoundedCornerShape(50),
                                    )
                                }
                            }
                        },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.BrightnessLow) },
                        title = { Text("Pure black") },
                        description = { Text("True black background in dark mode") },
                        enabled = darkTheme,
                        trailingContent = {
                            Switch(
                                checked = pureBlack,
                                onCheckedChange = onPureBlackChange,
                                enabled = darkTheme,
                            )
                        },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.Palette) },
                        title = { Text("Accent color") },
                        description = {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(top = 2.dp),
                            ) {
                                Text(
                                    if (themeColor == DefaultThemeColor) "System / Material You" else "Custom seed color",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                AccentColorRow(themeColor, onThemeColorChange)
                            }
                        },
                    ),
                ),
        )
    }
}

@Composable
fun SettingsDataSection(
    context: Context,
    viewModel: MainViewModel,
    onNavigateToGlossary: () -> Unit,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onShowOnboarding: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val autoCheckUpdates by remember { derivedStateOf { viewModel.settings.value.autoCheckUpdates } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Data & Updates",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Material3SettingsGroup(
            items =
                listOf(
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.AutoMirrored.Outlined.HelpOutline) },
                        title = { Text("App Tutorial & Quick Guide") },
                        description = { Text("View introductory guide and crucial API setup tips") },
                        onClick = onShowOnboarding,
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.TextFields) },
                        title = { Text("Custom Dictionary / Glossary") },
                        description = { Text("Define translation rules for specific names or terms") },
                        onClick = onNavigateToGlossary,
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.CloudDownload) },
                        title = { Text("Export Backup") },
                        description = { Text("Save settings, glossary, history, and translation memory") },
                        onClick = onExportBackup,
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.Link) },
                        title = { Text("Restore Backup") },
                        description = { Text("Import a backup file to restore your configuration") },
                        onClick = onRestoreBackup,
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.DeleteOutline) },
                        title = { Text("Clear Translation Cache") },
                        description = { Text("Clear cached bubble translations memory") },
                        onClick = {
                            TranslationCacheRepository(context).clear()
                            android.widget.Toast
                                .makeText(context, "Translation cache cleared", android.widget.Toast.LENGTH_SHORT)
                                .show()
                        },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.SystemUpdate) },
                        title = { Text("Check Updates Automatically") },
                        description = { Text("Check GitHub Releases when app opens") },
                        trailingContent = {
                            Switch(
                                checked = autoCheckUpdates,
                                onCheckedChange = { enabled ->
                                    scope.launch { viewModel.settingsRepo.saveAutoCheckUpdates(enabled) }
                                },
                            )
                        },
                        onClick = { scope.launch { viewModel.settingsRepo.saveAutoCheckUpdates(!autoCheckUpdates) } },
                    ),
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.CloudDownload) },
                        title = { Text("Check for Updates") },
                        description = { Text("Current version: ${BuildConfig.VERSION_NAME}") },
                        onClick = { viewModel.checkForUpdate(manual = true) },
                    ),
                ),
        )
    }
}
