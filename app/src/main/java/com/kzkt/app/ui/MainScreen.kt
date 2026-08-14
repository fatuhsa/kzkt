package com.kzkt.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kzkt.app.ui.component.QuickSettingsCard
import com.kzkt.app.ui.component.ResultPreviewCard
import com.kzkt.app.ui.component.TranslateActionButtons
import com.kzkt.app.ui.component.TranslationLogCard
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

    // Stable references to snapshot state lists — reading the list object itself
    // does not trigger recomposition (F4: no per-tick copy).
    val logList = viewModel.translationLog
    val resultList = viewModel.resultPaths

    // Auto-scroll log — non-animated jump, triggered only when the log grows (F2).
    val logSize by remember { derivedStateOf { logList.size } }
    LaunchedEffect(logSize) {
        if (logSize > 0) logListState.scrollToItem(logSize - 1)
    }

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
                viewModel.addFiles(allPaths)
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
                        viewModel.addFiles(paths)
                        android.widget.Toast.makeText(toastContext, "Imported ${paths.size} images from folder", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header — matches History / Settings screens
        Text(
            "Translate",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        YoloStatusCard(viewModel)

        Spacer(Modifier.height(8.dp))

        QuickSettingsCard(viewModel)

        Spacer(Modifier.height(12.dp))

        TranslateActionButtons(viewModel, filePickerLauncher, folderPickerLauncher)

        // ── Progress ──
        if (viewModel.translationActive.value) {
            Spacer(Modifier.height(8.dp))
            TranslationProgressBar(viewModel)
        }

        Spacer(Modifier.height(12.dp))

        // ── Log output ──
        TranslationLogCard(
            viewModel = viewModel,
            logList = logList,
            logListState = logListState,
            modifier = Modifier.weight(1f),
        )

        ResultPreviewCard(viewModel, resultList)
    }
}
