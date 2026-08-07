@file:OptIn(ExperimentalMaterial3Api::class)

package com.kzkt.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kzkt.app.core.Config
import com.kzkt.app.ui.component.ChipsRow
import com.kzkt.app.ui.component.Material3SettingsGroup
import com.kzkt.app.ui.component.Material3SettingsItem
import com.kzkt.app.ui.theme.DefaultThemeColor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.AutoFixHigh

// Seed-color presets for the accent picker (Material You keeps the default crimson).
private val ACCENT_PRESETS = listOf(
    DefaultThemeColor,
    Color(0xFF6D5DF6),
    Color(0xFF00897B),
    Color(0xFF2E7D32),
    Color(0xFF1565C0),
    Color(0xFFF9A825),
)

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
            val customFontPath by remember { derivedStateOf { viewModel.settings.value.customFontPath } }
            Material3SettingsGroup(
                title = "Appearance",
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
                            Text(if (themeColor == DefaultThemeColor) "System / Material You (default)" else "Custom seed color")
                        },
                        trailingContent = { AccentColorRow(themeColor, onThemeColorChange) },
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
                ),
            )
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
                val translateSfx by remember { derivedStateOf { viewModel.settings.value.translateSfx } }
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
                            leadingContent = { SettingsIcon(androidx.compose.material.icons.Icons.Outlined.TextFields) },
                            title = { Text("Custom Dictionary / Glossary") },
                            description = { Text("Define how specific names or terms should be translated") },
                            onClick = onNavigateToGlossary
                        ),
                        Material3SettingsItem(
                            leadingContent = { SettingsIcon(androidx.compose.material.icons.Icons.Outlined.DeleteOutline) },
                            title = { Text("Clear Translation Cache") },
                            description = { Text("Forces re-translation of identical speech bubbles instead of using memory") },
                            onClick = {
                                com.kzkt.app.data.TranslationCacheRepository(context).clear()
                                android.widget.Toast.makeText(context, "Translation cache cleared", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
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

        // ── Data Management (Backup & Restore) ──
        item(key = "data") {
            Column {
                Text(
                    "Data",
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
                    )
                )
            }
        }

        // ── Developer ──
        item(key = "developer") {
            Column {
                Text(
                    "Developer",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
                )
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


            item(key = "image_upscaler") {
                val useImageUpscaler by remember { derivedStateOf { viewModel.settings.value.useImageUpscaler } }
                Material3SettingsGroup(
                    items = listOf(
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

@Composable
private fun SettingsIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(24.dp),
    )
}

@Composable
private fun AccentColorRow(selected: Color, onSelect: (Color) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        ACCENT_PRESETS.forEach { color ->
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(color = color, shape = CircleShape)
                    .border(
                        width = 2.dp,
                        color = if (color == selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
private fun ActiveProviderConfigCard(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    // Read only the fields this card needs, each via its own derivedStateOf, so a
    // change to any single setting recomposes just this card — not the whole
    // Settings screen (recomposition storm).
    val providerKey by remember { derivedStateOf { viewModel.settings.value.llmProvider } }
    val meta = Config.PROVIDER_REGISTRY[providerKey] ?: return

    val apiKey by remember(providerKey) { derivedStateOf {
        when (providerKey) {
            "gemini" -> viewModel.settings.value.geminiApiKey
            "openai" -> viewModel.settings.value.openaiApiKey
            "openrouter" -> viewModel.settings.value.openrouterApiKey
            "zen" -> viewModel.settings.value.zenApiKey
            "opencodego" -> viewModel.settings.value.opencodegoApiKey
            "custom" -> viewModel.settings.value.customApiKey
            else -> ""
        }
    } }

    val baseUrl by remember(providerKey) { derivedStateOf { viewModel.settings.value.getBaseUrl(providerKey) } }
    val defaultBaseUrl = meta.defaultBaseUrl

    val currentModel by remember(providerKey) { derivedStateOf {
        when (providerKey) {
            "gemini" -> viewModel.settings.value.modelGemini
            "openai" -> viewModel.settings.value.modelOpenai
            "openrouter" -> viewModel.settings.value.modelOpenrouter
            "zen" -> viewModel.settings.value.modelZen
            "opencodego" -> viewModel.settings.value.modelOpencodego
            "custom" -> viewModel.settings.value.modelCustom
            else -> meta.defaultModel
        }
    } }

    // Live API-key / base-URL field states (hoisted so the "Test API Key" button
    // validates what the user is actually typing, not the debounced saved value).
    var apiKeyText by remember(providerKey) { mutableStateOf(apiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    LaunchedEffect(apiKeyText) {
        if (apiKeyText != apiKey) {
            kotlinx.coroutines.delay(350)
            viewModel.settingsRepo.saveApiKey(providerKey, apiKeyText)
        }
    }
    var baseUrlText by remember(providerKey) { mutableStateOf(baseUrl) }
    LaunchedEffect(baseUrlText) {
        if (baseUrlText != baseUrl) {
            kotlinx.coroutines.delay(350)
            viewModel.settingsRepo.saveBaseUrl(providerKey, baseUrlText)
        }
    }

    val detected: List<String> = viewModel.providerModels[providerKey] ?: emptyList()
    val presetList: List<String> = Config.PRESET_MODELS[providerKey] ?: emptyList()
    val allModels = remember(presetList, detected) { (presetList + detected).distinct().sorted() }
    val isLoading = viewModel.modelsLoading.value

    Material3SettingsGroup(
        title = "${meta.displayName} Configuration",
        items = listOf(
            Material3SettingsItem(
                leadingContent = { SettingsIcon(Icons.Outlined.GppGood) },
                title = {
                    // Hoisted state: see apiKeyText / apiKeyVisible above. The single
                    // debounced LaunchedEffect is the only write path.
                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = { apiKeyText = it },
                        label = { Text(if (meta.requiresKey) "${meta.displayName} API Key" else "${meta.displayName} API Key (Optional)") },
                        placeholder = { Text(if (meta.requiresKey) "Enter API Key" else "Optional API Key") },
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                                Icon(
                                    if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (apiKeyVisible) "Hide" else "Show",
                                )
                            }
                        },
                    )
                },
            ),
            Material3SettingsItem(
                leadingContent = { SettingsIcon(Icons.Outlined.Link) },
                title = {
                    OutlinedTextField(
                        value = baseUrlText,
                        onValueChange = { baseUrlText = it },
                        label = { Text("Base URL") },
                        placeholder = { Text(if (defaultBaseUrl.isNotBlank()) defaultBaseUrl else "https://api.example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (defaultBaseUrl.isNotBlank() && baseUrlText != defaultBaseUrl) {
                                TextButton(onClick = {
                                    baseUrlText = defaultBaseUrl
                                    scope.launch { viewModel.settingsRepo.saveBaseUrl(providerKey, defaultBaseUrl) }
                                }) {
                                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    )
                },
            ),
            Material3SettingsItem(
                leadingContent = { SettingsIcon(Icons.Outlined.ModelTraining) },
                title = {
                    ModelDropdownInput(
                        label = meta.displayName,
                        value = currentModel,
                        presets = if (allModels.isNotEmpty()) allModels else listOf(meta.defaultModel),
                        onValue = { scope.launch { viewModel.settingsRepo.saveModel(providerKey, it) } },
                    )
                },
            ),
        )
    )

    // Model auto-detection performs a network call, so it is a real button — not
    // a plain settings row that looks like a passive toggle.
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { viewModel.fetchModelsForProvider(providerKey, baseUrl, apiKey) },
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text("Fetching models…")
        } else {
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Detect Models from API")
        }
    }

    // ── Health check: validate key + model + base URL with one tiny request ──
    val testState = viewModel.providerTestState.value
    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { viewModel.testProviderConnection(providerKey, baseUrlText, apiKeyText, currentModel) },
        enabled = testState?.loading != true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        if (testState?.loading == true) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text("Testing…")
        } else {
            Icon(
                imageVector = Icons.Outlined.GppGood,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Test API Key & Connection")
        }
    }
    testState?.let { state ->
        if (!state.loading && state.message.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            val resultColor = when (state.ok) {
                true -> MaterialTheme.colorScheme.primary
                false -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                state.message,
                style = MaterialTheme.typography.bodySmall,
                color = resultColor,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun TweakParamsSection(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()

    // Per-field derived read so the section doesn't recompose on unrelated settings.
    val useInpainting by remember { derivedStateOf { viewModel.settings.value.useInpainting } }
    Material3SettingsGroup(
        items = listOf(
            Material3SettingsItem(
                leadingContent = { SettingsIcon(Icons.Outlined.AutoFixHigh) },
                title = { Text("OpenCV Text Inpainting") },
                description = { Text("Seamlessly erase original text matching background textures") },
                trailingContent = {
                    Switch(
                        checked = useInpainting,
                        onCheckedChange = { scope.launch { viewModel.settingsRepo.saveUseInpainting(it) } }
                    )
                }
            )
        )
    )
    Spacer(Modifier.height(8.dp))

    TweakSlider(
        viewModel,
        "custom_timeout",
        "Custom Request Timeout",
        "Maximum HTTP network timeout per LLM API request (30s – 600s)",
        30f..600f
    )
    TweakSlider(
        viewModel,
        "max_bubbles",
        "Bubbles Per Request",
        "Maximum text speech bubbles processed in a single LLM batch",
        5f..50f
    )
    TweakSlider(
        viewModel,
        "request_delay",
        "Min Request Delay",
        "Minimum delay interval between API calls to prevent rate limits",
        0.5f..10f
    )
    TweakSlider(
        viewModel,
        "pad_x",
        "Pad X Ratio",
        "Horizontal padding multiplier added to text bubble bounding boxes",
        0.1f..1.0f
    )
    TweakSlider(
        viewModel,
        "pad_y",
        "Pad Y Ratio",
        "Vertical padding multiplier added to text bubble bounding boxes",
        0.1f..1.0f
    )
    TweakSlider(
        viewModel,
        "min_pad",
        "Min Padding (px)",
        "Minimum absolute pixel padding added around detected text bubbles",
        5f..100f
    )
}

private fun saveTweakSliderValue(
    scope: kotlinx.coroutines.CoroutineScope,
    viewModel: MainViewModel,
    keyField: String,
    value: Float
) {
    scope.launch {
        if (keyField == "custom_timeout") {
            viewModel.settingsRepo.saveCustomTimeoutSec(value.toInt())
        } else {
            viewModel.settingsRepo.saveTweakParam(
                keyField,
                when (keyField) {
                    "max_bubbles", "min_pad" -> value.toInt()
                    else -> value
                }
            )
        }
    }
}

/**
 * One tweak-parameter slider with descriptions and +/- stepper buttons for precise adjustment.
 */
@Composable
private fun TweakSlider(
    viewModel: MainViewModel,
    keyField: String,
    label: String,
    description: String,
    range: ClosedFloatingPointRange<Float>,
) {
    val scope = rememberCoroutineScope()

    val value by remember { derivedStateOf {
        when (keyField) {
            "max_bubbles" -> viewModel.settings.value.maxBubblesPerRequest.toFloat()
            "request_delay" -> viewModel.settings.value.minRequestDelay
            "pad_x" -> viewModel.settings.value.padXRatio
            "pad_y" -> viewModel.settings.value.padYRatio
            "min_pad" -> viewModel.settings.value.minPad.toFloat()
            "custom_timeout" -> viewModel.settings.value.customTimeoutSec.toFloat()
            else -> 0f
        }
    } }
    var sliderValue by remember(value) { mutableFloatStateOf(value) }

    val step = when (keyField) {
        "custom_timeout" -> 5f
        "max_bubbles", "min_pad" -> 1f
        "request_delay" -> 0.5f
        else -> 0.05f
    }

    Material3SettingsGroup(
        items = listOf(
            Material3SettingsItem(
                title = {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            val fmt = when (keyField) {
                                "request_delay" -> "%.1fs".format(sliderValue)
                                "min_pad", "max_bubbles" -> "${sliderValue.toInt()}"
                                "custom_timeout" -> "${sliderValue.toInt()}s"
                                else -> "%.2f".format(sliderValue)
                            }
                            Text(fmt, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(
                                onClick = {
                                    val newValue = (sliderValue - step).coerceIn(range.start, range.endInclusive)
                                    sliderValue = newValue
                                    saveTweakSliderValue(scope, viewModel, keyField, newValue)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Remove,
                                    contentDescription = "Decrease",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = {
                                    saveTweakSliderValue(scope, viewModel, keyField, sliderValue)
                                },
                                valueRange = range,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    val newValue = (sliderValue + step).coerceIn(range.start, range.endInclusive)
                                    sliderValue = newValue
                                    saveTweakSliderValue(scope, viewModel, keyField, newValue)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Increase",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
            ),
        ),
    )
}

@Composable
private fun SfxFilterSection(viewModel: MainViewModel) {
    val scope = rememberCoroutineScope()
    val current by remember { derivedStateOf { viewModel.settings.value.filterSfxMode } }
    val modes = listOf("balanced", "relaxed", "strict")
    Material3SettingsGroup(
        items = modes.map { mode ->
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

@Composable
private fun ModelDropdownInput(
    label: String,
    value: String,
    presets: List<String>,
    onValue: (String) -> Unit,
) {
    var textState by remember(value) { mutableStateOf(value) }
    var expanded by remember { mutableStateOf(false) }

    // Debounce writes: typing a long model name would otherwise hit DataStore on
    // every keystroke. Saving happens 350 ms after typing pauses (or immediately
    // when picking from the dropdown).
    LaunchedEffect(textState) {
        if (textState != value) {
            kotlinx.coroutines.delay(350)
            onValue(textState)
        }
    }

    val options = remember(presets, value) {
        val list = presets.toMutableList()
        if (value.isNotBlank() && value !in list) list.add(0, value)
        list
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = textState,
            onValueChange = { newText ->
                textState = newText
            },
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        textState = model
                        onValue(model)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

