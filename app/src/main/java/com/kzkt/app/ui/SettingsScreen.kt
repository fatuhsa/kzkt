@file:OptIn(ExperimentalMaterial3Api::class)

package com.kzkt.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzkt.app.core.Config
import com.kzkt.app.ui.component.AccentColorRow
import com.kzkt.app.ui.component.ActiveProviderConfigCard
import com.kzkt.app.ui.component.ChipsRow
import com.kzkt.app.ui.component.Material3SettingsGroup
import com.kzkt.app.ui.component.Material3SettingsItem
import com.kzkt.app.ui.component.SettingsIcon
import com.kzkt.app.ui.component.SfxFilterSection
import com.kzkt.app.ui.component.TweakParamsSection
import com.kzkt.app.ui.theme.DefaultThemeColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    pureBlack: Boolean,
    onPureBlackChange: (Boolean) -> Unit,
    themeColor: Color,
    onThemeColorChange: (Color) -> Unit,
    onNavigateToGlossary: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showFontDialog by remember { mutableStateOf(false) }

    var confirmRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var isRestoring by remember { mutableStateOf(false) }

    // Backup restore file picker
    val backupPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) confirmRestoreUri = uri
    }

    fun exportBackup() {
        val toastContext = context
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val settingsJson = viewModel.settingsRepo.exportAllJson()
                val glossary = viewModel.glossaryRepo.glossary.value
                val history = viewModel.historyRepo.entriesFlow.first()
                val json = com.kzkt.app.data.BackupManager.buildBackup(toastContext, settingsJson, glossary, history)
                val file = com.kzkt.app.data.BackupManager.writeToCache(toastContext, json)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.kzkt.app.ui.FileUtils.shareAnyFile(toastContext, file.absolutePath)
                }
            } catch (e: Exception) {
                android.util.Log.e("KZKT", "Backup export failed: ${e.message}")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(toastContext, "Backup failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun restoreBackup(uri: android.net.Uri) {
        val toastContext = context
        isRestoring = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val json = com.kzkt.app.data.BackupManager.readFromUri(toastContext, uri)
            val result = if (json != null) {
                com.kzkt.app.data.BackupManager.applyBackup(
                    toastContext, json,
                    viewModel.settingsRepo, viewModel.glossaryRepo, viewModel.historyRepo
                )
            } else {
                com.kzkt.app.data.BackupManager.BackupResult(false, "Could not read backup file")
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                isRestoring = false
                android.widget.Toast.makeText(
                    toastContext, result.message,
                    if (result.ok) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val fontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                var originalName = "custom_font_${System.currentTimeMillis()}.ttf"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) originalName = cursor.getString(idx)
                    }
                }

                val fontsDir = java.io.File(context.filesDir, "custom_fonts")
                fontsDir.mkdirs()
                val destFile = java.io.File(fontsDir, originalName)

                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                scope.launch { viewModel.settingsRepo.saveCustomFontPath(destFile.absolutePath) }
                showFontDialog = false
            } catch (e: Exception) {
                android.util.Log.e("KZKT", "Font import error: ${e.message}")
            }
        }
    }

    val providerChips = remember { Config.PROVIDER_REGISTRY.values.map { it.key to it.displayName } }
    val languageChips = remember { Config.LANGUAGE_CHOICES.map { it to it } }
    var showAdvanced by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // ── Appearance ──
        item(key = "appearance") {
            Column {
                Text(
                    "Appearance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
                )
                Material3SettingsGroup(
                    items = listOf(
                    Material3SettingsItem(
                        leadingContent = { SettingsIcon(Icons.Outlined.DarkMode) },
                        title = { Text("Dark mode") },
                        description = { Text("Use the dark color scheme") },
                        trailingContent = {
                            Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
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
                            Text(if (themeColor == DefaultThemeColor) "System / Material You" else "Custom seed color")
                        },
                        trailingContent = { AccentColorRow(themeColor, onThemeColorChange) },
                    ),
                    ),
                )
            }
        }

        // ── Provider & Configuration ──
        item(key = "provider") {
            Column {
                Text(
                    "Provider",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
                )
                val selectedProvider by remember { derivedStateOf { viewModel.settings.value.llmProvider } }
                ChipsRow(
                    chips = providerChips,
                    currentValue = selectedProvider,
                    onValueUpdate = { key -> scope.launch { viewModel.settingsRepo.saveProvider(key) } },
                )
                Config.PROVIDER_REGISTRY[selectedProvider]?.let { meta ->
                    Text(
                        meta.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 8.dp),
                    )
                }
                Spacer(Modifier.height(4.dp))
                ActiveProviderConfigCard(viewModel)
            }
        }

        // ── Target Language ──
        item(key = "language") {
            Column {
                Text(
                    "Target Language",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
                )
                val language by remember { derivedStateOf { viewModel.settings.value.targetLanguage } }
                ChipsRow(
                    chips = languageChips,
                    currentValue = language,
                    onValueUpdate = { lang -> scope.launch { viewModel.settingsRepo.saveLanguage(lang) } },
                )
            }
        }

        // ── Translation Engine ──
        item(key = "engine") {
            Column {
                Text(
                    "Translation Engine",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
                )
                // Read only the fields this group needs, each via its own
                // derivedStateOf, so changing one setting recomposes just this
                // group — not the whole Settings screen (recomposition storm).
                val useLocalOcr by remember { derivedStateOf { viewModel.settings.value.useLocalOcr } }
                val useImageUpscaler by remember { derivedStateOf { viewModel.settings.value.useImageUpscaler } }
                val translateSfx by remember { derivedStateOf { viewModel.settings.value.translateSfx } }
                val translateFreeText by remember { derivedStateOf { viewModel.settings.value.translateFreeText } }
                Material3SettingsGroup(
                    items = listOf(
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.Science) },
                            title = { Text("Use On-Device Local OCR") },
                            description = { Text(if (useLocalOcr) "Google ML Kit extracts text locally before the LLM — works with any text-only model." else "Default: sends the bubble mosaic to a vision LLM.") },
                            trailingContent = {
                                Switch(
                                    checked = useLocalOcr,
                                    onCheckedChange = { enabled ->
                                        scope.launch { viewModel.settingsRepo.saveUseLocalOcr(enabled) }
                                    }
                                )
                            },
                            onClick = {
                                scope.launch { viewModel.settingsRepo.saveUseLocalOcr(!useLocalOcr) }
                            }
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.Science) },
                            title = { Text("Smart Image Upscaler") },
                            description = { Text("Enhance low-resolution images for better AI text detection. May increase RAM usage.") },
                            trailingContent = {
                                Switch(
                                    checked = useImageUpscaler,
                                    onCheckedChange = { scope.launch { viewModel.settingsRepo.saveUseImageUpscaler(it) } }
                                )
                            }
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.Tune) },
                            title = { Text("Translate Sound Effects (SFX)") },
                            description = { Text(if (translateSfx) "ON: onomatopoeia like ドドド / バキ get translated too." else "OFF: pure SFX bubbles are skipped (default).") },
                            trailingContent = {
                                Switch(
                                    checked = translateSfx,
                                    onCheckedChange = { enabled ->
                                        scope.launch { viewModel.settingsRepo.saveTranslateSfx(enabled) }
                                    }
                                )
                            },
                            onClick = {
                                scope.launch { viewModel.settingsRepo.saveTranslateSfx(!translateSfx) }
                            }
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.TextFields) },
                            title = { Text("Translate Free Text (outside bubbles)") },
                            description = {
                                Text(
                                    if (translateFreeText) "ON: also translate text outside bubbles"
                                    else "OFF: only speech bubbles"
                                )
                            },
                            trailingContent = {
                                Switch(
                                    checked = translateFreeText,
                                    onCheckedChange = { enabled -> scope.launch { viewModel.settingsRepo.saveTranslateFreeText(enabled) } },
                                )
                            },
                            onClick = {
                                scope.launch { viewModel.settingsRepo.saveTranslateFreeText(!translateFreeText) }
                            },
                        ),
                    )
                )
                // OCR script is auto-detected: the single bundled ML Kit model
                // (Japanese + Latin) reads both scripts, so no selector is needed.
                if (useLocalOcr) {
                    Spacer(Modifier.height(4.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SettingsIcon(Icons.Outlined.Tune)
                            Text(
                                "Script auto-detected: Japanese + Latin (ML Kit)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ── Rendered Text (appearance of the translated bubbles) ──
        item(key = "render_text") {
            Column {
                Text(
                    "Rendered Text",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
                )
                val textColor by remember { derivedStateOf { viewModel.settings.value.renderTextColor } }
                val fontScale by remember { derivedStateOf { viewModel.settings.value.renderFontScale } }
                val renderStyle by remember { derivedStateOf { viewModel.settings.value.renderStyle } }
                val customFontPath by remember { derivedStateOf { viewModel.settings.value.customFontPath } }
                Material3SettingsGroup(
                    items = listOf(
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.Tune) },
                            title = { Text("Render style") },
                            description = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("manga" to "Manga", "clean" to "Clean").forEach { (key, label) ->
                                        FilterChip(
                                            selected = renderStyle == key,
                                            onClick = { scope.launch { viewModel.settingsRepo.saveRenderStyle(key) } },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                            },
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.Palette) },
                            title = { Text("Text color") },
                            description = {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("auto" to "Auto", "white" to "White", "black" to "Black").forEach { (key, label) ->
                                        FilterChip(
                                            selected = textColor == key,
                                            onClick = { scope.launch { viewModel.settingsRepo.saveRenderTextColor(key) } },
                                            label = { Text(label) },
                                        )
                                    }
                                }
                            },
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.TextFields) },
                            title = {
                                // Local slider state; DataStore write happens only on
                                // onValueChangeFinished (same pattern as TweakSlider) so
                                // dragging does not hammer the disk on every tick.
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
                                Text(if (customFontPath.isNotBlank()) java.io.File(customFontPath).name else "Tap to import custom font file")
                            },
                            trailingContent = if (customFontPath.isNotBlank()) {
                                {
                                    IconButton(onClick = { scope.launch { viewModel.settingsRepo.saveCustomFontPath("") } }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear Custom Font")
                                    }
                                }
                            } else null,
                            onClick = { showFontDialog = true },
                        ),
                    )
                )
            }
        }

        // ── Data Management (Backup & Restore) ──
        item(key = "data") {
            Column {
                Text(
                    "Data & Memory",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
                )
                Material3SettingsGroup(
                    items = listOf(
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.CloudDownload) },
                            title = { Text("Export Backup") },
                            description = { Text("Save settings, glossary, history and translation memory to one file") },
                            onClick = { exportBackup() },
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.Link) },
                            title = { Text("Restore Backup") },
                            description = { Text("Import a backup file — overwrites current settings, glossary and history") },
                            onClick = { backupPickerLauncher.launch("*/*") },
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.DeleteOutline) },
                            title = { Text("Clear Translation Cache") },
                            description = { Text("Forces re-translation of identical speech bubbles instead of using memory") },
                            onClick = {
                                com.kzkt.app.data.TranslationCacheRepository(context).clear()
                                android.widget.Toast.makeText(context, "Translation cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.TextFields) },
                            title = { Text("Custom Dictionary / Glossary") },
                            description = { Text("Define how specific names or terms should be translated") },
                            onClick = onNavigateToGlossary
                        ),
                    )
                )
            }
        }

        // ── Updates (self-update via GitHub Releases) ──
        item(key = "updates") {
            Column {
                Text(
                    "Updates",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
                )
                val autoCheckUpdates by remember { derivedStateOf { viewModel.settings.value.autoCheckUpdates } }
                Material3SettingsGroup(
                    items = listOf(
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.SystemUpdate) },
                            title = { Text("Check for Updates Automatically") },
                            description = { Text("Check GitHub Releases for a new version when the app opens") },
                            trailingContent = {
                                Switch(
                                    checked = autoCheckUpdates,
                                    onCheckedChange = { enabled ->
                                        scope.launch { viewModel.settingsRepo.saveAutoCheckUpdates(enabled) }
                                    }
                                )
                            },
                            onClick = {
                                scope.launch { viewModel.settingsRepo.saveAutoCheckUpdates(!autoCheckUpdates) }
                            }
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.CloudDownload) },
                            title = { Text("Check for Updates") },
                            description = {
                                Text("Current version: ${com.kzkt.app.BuildConfig.VERSION_NAME}")
                            },
                            onClick = { viewModel.checkForUpdate(manual = true) },
                        ),
                    )
                )
            }
        }

        // ── Advanced Options Header ──
        item(key = "advanced_header") {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvanced = !showAdvanced },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        SettingsIcon(Icons.Outlined.Science)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text("Advanced settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (showAdvanced) "Hide sliders and custom configurations" else "Show bubble count, OCR padding, and API timeout tweaks",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        imageVector = if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
            }
        }

        if (showAdvanced) {
            // ── Verbose Developer Logs ──
            item(key = "dev_logs") {
                val enableDevLogs by remember { derivedStateOf { viewModel.settings.value.enableDevLogs } }
                Material3SettingsGroup(
                    items = listOf(
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(Icons.Outlined.BugReport) },
                            title = { Text("Verbose Developer Logs") },
                            description = { Text(if (enableDevLogs) "ON: Shows bubble-by-bubble OCR text and connection details." else "OFF: Clean & simple progress logs.") },
                            trailingContent = {
                                Switch(
                                    checked = enableDevLogs,
                                    onCheckedChange = { enabled ->
                                        scope.launch { viewModel.settingsRepo.saveEnableDevLogs(enabled) }
                                    }
                                )
                            },
                            onClick = {
                                scope.launch { viewModel.settingsRepo.saveEnableDevLogs(!enableDevLogs) }
                            }
                        )
                    )
                )
            }

            // ── Tweak Parameters ──
            item(key = "tweak_params") {
                Column {
                    Text(
                        "Tweak Parameters",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp, top = 8.dp),
                    )
                    TweakParamsSection(viewModel)
                }
            }

            // ── SFX Filter Mode ──
            item(key = "sfx_mode") {
                Column {
                    Text(
                        "SFX Filter Mode",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp, top = 8.dp),
                    )
                    SfxFilterSection(viewModel)
                }
            }

            item(key = "reset_settings") {
                var showResetConfirm by remember { mutableStateOf(false) }

                Button(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Advanced Settings")
                }

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("Reset Settings") },
                        text = { Text("Are you sure you want to reset all advanced settings to their default values? This will not affect your API keys or models.") },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    viewModel.settingsRepo.resetToDefault()
                                    android.widget.Toast.makeText(context, "Settings reset", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                showResetConfirm = false
                            }) {
                                Text("Reset", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(Modifier.height(32.dp))
        }
    }

    confirmRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!isRestoring) confirmRestoreUri = null },
            title = { Text("Restore backup?") },
            text = { Text("This will overwrite your current settings, glossary, history and translation memory with the contents of the backup file.") },
            confirmButton = {
                TextButton(
                    enabled = !isRestoring,
                    onClick = {
                        confirmRestoreUri = null
                        restoreBackup(uri)
                    }
                ) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    enabled = !isRestoring,
                    onClick = { confirmRestoreUri = null }
                ) { Text("Cancel") }
            },
        )
    }

    if (showFontDialog) {
        AlertDialog(
            onDismissRequest = { showFontDialog = false },
            title = { Text("Select Custom Font") },
            text = {
                val fontsDir = java.io.File(context.filesDir, "custom_fonts")
                val fonts = fontsDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".ttf") || it.name.endsWith(".otf")) } ?: emptyList()

                LazyColumn {
                    item {
                        TextButton(
                            onClick = {
                                scope.launch { viewModel.settingsRepo.saveCustomFontPath("") }
                                showFontDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Default Font", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        }
                    }
                    items(fonts) { font ->
                        TextButton(
                            onClick = {
                                scope.launch { viewModel.settingsRepo.saveCustomFontPath(font.absolutePath) }
                                showFontDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(font.name, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { fontPickerLauncher.launch("*/*") }) {
                    Text("Import New Font")
                }
            },
            dismissButton = {
                TextButton(onClick = { showFontDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}
