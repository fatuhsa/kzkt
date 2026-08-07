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
        // The manifest advertises ACTION_SEND / ACTION_SEND_MULTIPLE (image/*) —
        // resolve any shared images into usable file paths and hand them to the
        // Translate tab.
        val sharedFiles = extractSharedFiles(intent)
        setContent {
            KzktApp(initialSharedFiles = sharedFiles)
        }
    }

    /**
     * Resolve shared image URIs (ACTION_SEND single, or ACTION_SEND_MULTIPLE /
     * ClipData for multiple) to real file paths. MediaStore/_data paths are
     * returned directly; anything else (DocumentProvider, PhotoPicker) is copied
     * into app cache so the pipeline can read it.
     */
    private fun extractSharedFiles(intent: Intent?): List<String> {
        val action = intent?.action ?: return emptyList()

        val uris = mutableListOf<Uri>()
        when (action) {
            Intent.ACTION_SEND -> {
                val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { uris.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!streams.isNullOrEmpty()) {
                    uris.addAll(streams)
                } else {
                    // Fallback: read from ClipData (some launchers omit EXTRA_STREAM)
                    intent.clipData?.let { clip ->
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i).uri?.let { uris.add(it) }
                        }
                    }
                }
            }
            else -> return emptyList()
        }

        if (uris.isEmpty()) return emptyList()
        return uris.mapNotNull { uri ->
            FileUtils.getPathFromUri(this, uri)
                ?: FileUtils.copyUriToCache(this, uri)
        }
    }
}
