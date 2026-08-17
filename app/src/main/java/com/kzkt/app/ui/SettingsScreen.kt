@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kzkt.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BrightnessLow
import androidx.compose.material.icons.outlined.BugReport
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzkt.app.core.Config
import com.kzkt.app.ui.component.AccentColorRow
import com.kzkt.app.ui.component.ActiveProviderConfigCard
import com.kzkt.app.ui.component.AppLogsBottomSheet
import com.kzkt.app.ui.component.AppLogsButton
import com.kzkt.app.ui.component.ChipsRow
import com.kzkt.app.ui.component.Material3SettingsGroup
import com.kzkt.app.ui.component.Material3SettingsItem
import com.kzkt.app.ui.component.SettingsIcon
import com.kzkt.app.ui.component.SfxFilterSection
import com.kzkt.app.ui.component.TweakParamsSection
import com.kzkt.app.ui.theme.DefaultThemeColor
import com.kzkt.app.util.KLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onThemeModeChange: (String) -> Unit = {},
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
    val backupPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
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
                val json =
                    com.kzkt.app.data.BackupManager
                        .buildBackup(toastContext, settingsJson, glossary, history)
                val file =
                    com.kzkt.app.data.BackupManager
                        .writeToCache(toastContext, json)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.kzkt.app.ui.FileUtils
                        .shareAnyFile(toastContext, file.absolutePath)
                }
            } catch (e: Exception) {
                android.util.Log.e("KZKT", "Backup export failed: ${e.message}")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast
                        .makeText(toastContext, "Backup failed: ${e.message}", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    fun restoreBackup(uri: android.net.Uri) {
        val toastContext = context
        isRestoring = true
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val json =
                com.kzkt.app.data.BackupManager
                    .readFromUri(toastContext, uri)
            val result =
                if (json != null) {
                    com.kzkt.app.data.BackupManager.applyBackup(
                        toastContext,
                        json,
                        viewModel.settingsRepo,
                        viewModel.glossaryRepo,
                        viewModel.historyRepo,
                    )
                } else {
                    com.kzkt.app.data.BackupManager
                        .BackupResult(false, "Could not read backup file")
                }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                isRestoring = false
                android.widget.Toast
                    .makeText(
                        toastContext,
                        result.message,
                        if (result.ok) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }

    val fontPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent(),
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
    val enableDevLogs by remember { derivedStateOf { viewModel.settings.value.enableDevLogs } }
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()

    val categories =
        remember {
            listOf(
                "AI & Provider" to 1,
                "Detection & Engine" to 2,
                "Text & Render" to 3,
                "Appearance" to 4,
                "Data & Updates" to 5,
            )
        }

    LazyColumn(
        state = listState,
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 0. Top Header + Quick Jump Bar ──
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // Quick jump pill chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(categories) { (name, targetIndex) ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        scope.launch { listState.animateScrollToItem(targetIndex) }
                                    },
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // ── 1. AI & Provider ──
        item(key = "provider_group") {
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

                val selectedProvider by remember { derivedStateOf { viewModel.settings.value.llmProvider } }
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
                val language by remember { derivedStateOf { viewModel.settings.value.targetLanguage } }
                ChipsRow(
                    chips = languageChips,
                    currentValue = language,
                    onValueUpdate = { lang -> scope.launch { viewModel.settingsRepo.saveLanguage(lang) } },
                )
            }
        }

        // ── 2. Detection & Engine ──
        item(key = "engine_group") {
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

        // ── 3. Text & Rendering ──
        item(key = "render_group") {
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

                val textColor by remember { derivedStateOf { viewModel.settings.value.renderTextColor } }
                val fontScale by remember { derivedStateOf { viewModel.settings.value.renderFontScale } }
                val renderStyle by remember { derivedStateOf { viewModel.settings.value.renderStyle } }
                val customFontPath by remember { derivedStateOf { viewModel.settings.value.customFontPath } }

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
                                            java.io
                                                .File(
                                                    customFontPath,
                                                ).name
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
                                onClick = { showFontDialog = true },
                            ),
                        ),
                )
            }
        }

        // ── 4. Appearance ──
        item(key = "appearance_group") {
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

                val currentThemeMode = viewModel.settings.value.themeMode
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

        // ── 5. Data & Updates ──
        item(key = "data_group") {
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

                val autoCheckUpdates by remember { derivedStateOf { viewModel.settings.value.autoCheckUpdates } }

                Material3SettingsGroup(
                    items =
                        listOf(
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
                                onClick = { exportBackup() },
                            ),
                            Material3SettingsItem(
                                leadingContent = { SettingsIcon(Icons.Outlined.Link) },
                                title = { Text("Restore Backup") },
                                description = { Text("Import a backup file to restore your configuration") },
                                onClick = { backupPickerLauncher.launch("*/*") },
                            ),
                            Material3SettingsItem(
                                leadingContent = { SettingsIcon(Icons.Outlined.DeleteOutline) },
                                title = { Text("Clear Translation Cache") },
                                description = { Text("Clear cached bubble translations memory") },
                                onClick = {
                                    com.kzkt.app.data
                                        .TranslationCacheRepository(context)
                                        .clear()
                                    android.widget.Toast
                                        .makeText(
                                            context,
                                            "Translation cache cleared",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
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
                                description = { Text("Current version: ${com.kzkt.app.BuildConfig.VERSION_NAME}") },
                                onClick = { viewModel.checkForUpdate(manual = true) },
                            ),
                        ),
                )
            }
        }

        // ── 6. Advanced Settings (Expandable) ──
        item(key = "advanced_header") {
            Card(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced },
                shape = RoundedCornerShape(16.dp),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        SettingsIcon(Icons.Outlined.Science)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Advanced Settings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text(
                                if (showAdvanced) {
                                    "Hide developer logs and tweak parameters"
                                } else {
                                    "Show developer logs, tweak parameters, and SFX filter mode"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Icon(
                        imageVector = if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                }
            }
        }

        if (showAdvanced) {
            item(key = "dev_logs") {
                Material3SettingsGroup(
                    items =
                        listOf(
                            Material3SettingsItem(
                                leadingContent = { SettingsIcon(Icons.Outlined.BugReport) },
                                title = { Text("Verbose Developer Logs") },
                                description = {
                                    Text(
                                        if (enableDevLogs) {
                                            "ON: Shows bubble OCR text and raw connection details."
                                        } else {
                                            "OFF: Clean & simple progress logs."
                                        },
                                    )
                                },
                                trailingContent = {
                                    Switch(
                                        checked = enableDevLogs,
                                        onCheckedChange = { enabled -> scope.launch { viewModel.settingsRepo.saveEnableDevLogs(enabled) } },
                                    )
                                },
                                onClick = { scope.launch { viewModel.settingsRepo.saveEnableDevLogs(!enableDevLogs) } },
                            ),
                        ),
                )
            }

            if (enableDevLogs) {
                item(key = "app_logs") {
                    val appLogCount by KLog.entries.collectAsState()
                    var showAppLogs by remember { mutableStateOf(false) }
                    AppLogsButton(
                        logCount = appLogCount.size,
                        onClick = { showAppLogs = true },
                    )
                    if (showAppLogs) {
                        AppLogsBottomSheet(onDismiss = { showAppLogs = false })
                    }
                }
            }

            item(key = "tweak_params") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Tweak Parameters",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    TweakParamsSection(viewModel)
                }
            }

            item(key = "sfx_mode") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "SFX Filter Mode",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    SfxFilterSection(viewModel)
                }
            }

            item(key = "reset_settings") {
                var showResetConfirm by remember { mutableStateOf(false) }

                Button(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(50),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Advanced Settings", fontWeight = FontWeight.SemiBold)
                }

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        title = { Text("Reset Settings") },
                        text = {
                            Text(
                                "Are you sure you want to reset all advanced settings to default? " +
                                    "This will not affect your API keys or models.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    viewModel.settingsRepo.resetToDefault()
                                    android.widget.Toast
                                        .makeText(context, "Settings reset", android.widget.Toast.LENGTH_SHORT)
                                        .show()
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
                        },
                    )
                }
            }
        }

        item(key = "bottom_spacer") {
            Spacer(Modifier.height(32.dp))
        }
    }

    confirmRestoreUri?.let { uri ->
        RestoreBackupDialog(
            isRestoring = isRestoring,
            onConfirm = {
                confirmRestoreUri = null
                restoreBackup(uri)
            },
            onDismiss = { confirmRestoreUri = null },
        )
    }

    if (showFontDialog) {
        FontPickerDialog(
            context = context,
            onPickDefault = {
                scope.launch { viewModel.settingsRepo.saveCustomFontPath("") }
                showFontDialog = false
            },
            onPickFont = { path ->
                scope.launch { viewModel.settingsRepo.saveCustomFontPath(path) }
                showFontDialog = false
            },
            onImport = { fontPickerLauncher.launch("*/*") },
            onDismiss = { showFontDialog = false },
        )
    }
}

/**
 * Confirmation dialog before restoring a backup. Blocks dismissal while the
 * restore is running so the user cannot cancel mid-restore.
 */
@Composable
private fun RestoreBackupDialog(
    isRestoring: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isRestoring) onDismiss() },
        title = { Text("Restore backup?") },
        text = {
            Text(
                "This will overwrite your current settings, glossary, history and translation " +
                    "memory with the contents of the backup file.",
            )
        },
        confirmButton = {
            TextButton(
                enabled = !isRestoring,
                onClick = onConfirm,
            ) { Text("Restore", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(
                enabled = !isRestoring,
                onClick = onDismiss,
            ) { Text("Cancel") }
        },
    )
}

/**
 * Font picker: list fonts bundled in the app's custom_fonts dir, with an option
 * to reset to the default font or import a new one via the system picker.
 */
@Composable
private fun FontPickerDialog(
    context: android.content.Context,
    onPickDefault: () -> Unit,
    onPickFont: (String) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Custom Font") },
        text = {
            val fontsDir = java.io.File(context.filesDir, "custom_fonts")
            val fonts =
                fontsDir.listFiles()?.filter { it.isFile && (it.name.endsWith(".ttf") || it.name.endsWith(".otf")) } ?: emptyList()

            LazyColumn {
                item {
                    TextButton(
                        onClick = onPickDefault,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Default Font",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                        )
                    }
                }
                items(fonts) { font ->
                    TextButton(
                        onClick = { onPickFont(font.absolutePath) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(font.name, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) {
                Text("Import New Font")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}
