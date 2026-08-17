package com.kzkt.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kzkt.app.ui.component.EmptyMangaPickerView
import com.kzkt.app.ui.component.QuickConfigRow
import com.kzkt.app.ui.component.ResultPreviewCard
import com.kzkt.app.ui.component.SelectedFilesSection
import com.kzkt.app.ui.component.SystemLogsButton
import com.kzkt.app.ui.component.TranslationLogBottomSheet
import com.kzkt.app.ui.component.TranslationProgressBar
import com.kzkt.app.ui.component.YoloStatusCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    val logListState = rememberLazyListState()

    val logList = viewModel.translationLog
    val resultList = viewModel.resultPaths

    var showLogBottomSheet by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // Initialize YOLO on first composition and request notification permission
    LaunchedEffect(Unit) {
        viewModel.initialize(context)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val permissionCheck = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                "android.permission.POST_NOTIFICATIONS"
            )
            if (permissionCheck != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
        }
    }

    val scope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            val allPaths = FileUtils.resolvePickedUris(context, uris)
            if (allPaths.isNotEmpty()) {
                if (viewModel.selectedFiles.isEmpty()) {
                    viewModel.addFiles(allPaths)
                } else {
                    viewModel.appendFiles(allPaths)
                }
            }
        }
    }

    // ── Folder input (SAF tree picker): pick one folder, import all images in it ──
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri != null) {
            val toastContext = context
            scope.launch(Dispatchers.IO) {
                val uris = FileUtils.listImageUrisFromTree(context, treeUri)
                if (uris.isEmpty()) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(toastContext, "No images found in this folder", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val paths = uris.mapNotNull { FileUtils.copyUriToCache(context, it) }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (paths.isNotEmpty()) {
                        if (viewModel.selectedFiles.isEmpty()) {
                            viewModel.addFiles(paths)
                        } else {
                            viewModel.appendFiles(paths)
                        }
                        android.widget.Toast.makeText(toastContext, "Imported ${paths.size} images from folder", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val hasFiles by remember { derivedStateOf { viewModel.selectedFiles.isNotEmpty() } }
    val active by remember { derivedStateOf { viewModel.translationActive.value } }
    val yoloReady by remember { derivedStateOf { viewModel.yoloReady.value } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header: Title + YOLO badge
        Text(
            "Translate",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )

        YoloStatusCard(viewModel)

        Spacer(Modifier.height(14.dp))

        // Quick provider & target language chips
        QuickConfigRow(viewModel)

        Spacer(Modifier.height(14.dp))

        if (!hasFiles && !active) {
            // System logs button (when idle with no files)
            SystemLogsButton(
                logCount = logList.size,
                onClick = { showLogBottomSheet = true },
            )

            // Center Empty State
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                EmptyMangaPickerView(
                    onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                    onPickFolder = { folderPickerLauncher.launch(null) },
                    enabled = !active,
                )
            }
        } else {
            // Action Button: Cancel (if active) / Translate (if idle) / Retry
            if (active) {
                Button(
                    onClick = { viewModel.cancelTranslation() },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Cancel", fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(14.dp))

                // 3-Phase Stepper Progress Bar (Scan -> Translate -> Render)
                TranslationProgressBar(viewModel)
            } else {
                val failedCount by remember {
                    derivedStateOf {
                        viewModel.pageStatus.values.count { it == "failed" }
                    }
                }
                if (failedCount > 0) {
                    OutlinedButton(
                        onClick = { viewModel.retryFailedPages() },
                        enabled = hasFiles && yoloReady,
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry Failed ($failedCount)", fontWeight = FontWeight.Bold)
                    }
                } else {
                    val canRetry by remember { derivedStateOf { viewModel.canRetry.value } }
                    if (canRetry) {
                        Button(
                            onClick = { viewModel.retryTranslation() },
                            enabled = hasFiles && yoloReady,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.startTranslation() },
                            enabled = hasFiles && yoloReady,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Translate", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Selected Files Section with Horizontal Cards & Add Button
            SelectedFilesSection(
                viewModel = viewModel,
                onAddClick = { filePickerLauncher.launch(arrayOf("*/*")) },
            )

            Spacer(Modifier.height(14.dp))

            // System logs button
            SystemLogsButton(
                logCount = logList.size,
                onClick = { showLogBottomSheet = true },
            )

            Spacer(Modifier.weight(1f))

            // Result preview card (if any finished results exist)
            ResultPreviewCard(viewModel, resultList)
        }
    }

    // Modal Bottom Sheet for System Logs
    if (showLogBottomSheet) {
        TranslationLogBottomSheet(
            viewModel = viewModel,
            logList = logList,
            logListState = logListState,
            onDismiss = { showLogBottomSheet = false },
        )
    }
}
