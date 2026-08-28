@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kzkt.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
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
import com.kzkt.app.ui.component.AppLogsBottomSheet
import com.kzkt.app.ui.component.AppLogsButton
import com.kzkt.app.ui.component.FontPickerDialog
import com.kzkt.app.ui.component.Material3SettingsGroup
import com.kzkt.app.ui.component.Material3SettingsItem
import com.kzkt.app.ui.component.RestoreBackupDialog
import com.kzkt.app.ui.component.SettingsAiSection
import com.kzkt.app.ui.component.SettingsAppearanceSection
import com.kzkt.app.ui.component.SettingsDataSection
import com.kzkt.app.ui.component.SettingsEngineSection
import com.kzkt.app.ui.component.SettingsIcon
import com.kzkt.app.ui.component.SettingsRenderSection
import com.kzkt.app.ui.component.SfxFilterSection
import com.kzkt.app.ui.component.TweakParamsSection
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
    onShowOnboarding: () -> Unit = {},
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
            SettingsAiSection(
                viewModel = viewModel,
                providerChips = providerChips,
                languageChips = languageChips,
            )
        }

        // ── 2. Detection & Engine ──
        item(key = "engine_group") {
            SettingsEngineSection(viewModel = viewModel)
        }

        // ── 3. Text & Rendering ──
        item(key = "render_group") {
            SettingsRenderSection(
                viewModel = viewModel,
                onShowFontDialog = { showFontDialog = true },
            )
        }

        // ── 4. Appearance ──
        item(key = "appearance_group") {
            SettingsAppearanceSection(
                currentThemeMode = viewModel.settings.value.themeMode,
                darkTheme = darkTheme,
                pureBlack = pureBlack,
                themeColor = themeColor,
                onThemeModeChange = onThemeModeChange,
                onPureBlackChange = onPureBlackChange,
                onThemeColorChange = onThemeColorChange,
            )
        }

        // ── 5. Data & Updates ──
        item(key = "data_group") {
            SettingsDataSection(
                context = context,
                viewModel = viewModel,
                onNavigateToGlossary = onNavigateToGlossary,
                onExportBackup = { exportBackup() },
                onRestoreBackup = { backupPickerLauncher.launch("*/*") },
                onShowOnboarding = onShowOnboarding,
            )
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

        item(key = "app_footer") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "KZKT v1.38.0",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Native Manga & Comic Translation",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
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
