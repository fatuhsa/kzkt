package com.kzkt.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.kzkt.app.ui.FileUtils
import com.kzkt.app.ui.KzktApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The manifest advertises ACTION_SEND (image/*) — resolve any shared
        // image into a usable file path and hand it to the Translate tab.
        val sharedFiles = extractSharedFiles(intent)
        setContent {
            KzktApp(initialSharedFiles = sharedFiles)
        }
    }

    /**
     * Resolve a single ACTION_SEND EXTRA_STREAM URI to a real file path.
     * MediaStore/_data paths are returned directly; anything else (DocumentProvider,
     * PhotoPicker) is copied into app cache so the pipeline can read it.
     */
    private fun extractSharedFiles(intent: Intent?): List<String> {
        if (intent?.action != Intent.ACTION_SEND) return emptyList()
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val sharedUri = uri ?: return emptyList()
        return listOfNotNull(
            FileUtils.getPathFromUri(this, sharedUri)
                ?: FileUtils.copyUriToCache(this, sharedUri)
        )
    }
}
