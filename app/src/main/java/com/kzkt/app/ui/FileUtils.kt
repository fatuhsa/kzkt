package com.kzkt.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.kzkt.app.util.KLog
import java.io.File
import java.io.FileOutputStream

/**
 * File I/O utilities using Android ContentResolver (Scoped Storage safe).
 */
object FileUtils {
    /**
     * Resolve a batch of picked document URIs into local file paths: archives
     * (ZIP/CBZ/EPUB) are extracted, content-URI files are copied to cache, and
     * plain file paths are returned as-is. Used by the Translate file picker.
     */
    fun resolvePickedUris(
        context: Context,
        uris: List<Uri>,
    ): List<String> {
        val paths = mutableListOf<String>()
        for (uri in uris) {
            val mimeType = context.contentResolver.getType(uri)
            val path = getPathFromUri(context, uri)
            val isZip =
                mimeType?.contains("zip") == true ||
                    mimeType?.contains("cbz") == true ||
                    mimeType?.contains("epub") == true ||
                    path?.lowercase()?.endsWith(".zip") == true ||
                    path?.lowercase()?.endsWith(".cbz") == true ||
                    path?.lowercase()?.endsWith(".epub") == true

            if (isZip) {
                paths.addAll(
                    com.kzkt.app.util.ArchiveExtractor
                        .extractCbz(context, uri),
                )
            } else if (path != null) {
                paths.add(path)
            } else {
                val copied = copyUriToCache(context, uri)
                if (copied != null) paths.add(copied)
            }
        }
        return paths
    }

    fun getPathFromUri(
        context: Context,
        uri: Uri,
    ): String? {
        // Try direct path
        if (uri.scheme == "file") return uri.path

        // Try content provider
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME, "_data")
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Try _data column first
                    val dataIdx = cursor.getColumnIndex("_data")
                    if (dataIdx >= 0) {
                        val path = cursor.getString(dataIdx)
                        if (path != null) return path
                    }
                    // Fallback: use display name
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        val name = cursor.getString(nameIdx)
                        if (name != null) {
                            return copyUriToCache(context, uri, name)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            KLog.w("KZKT", "Failed to resolve URI to a file path ($uri): ${e.message}")
        }

        // Last resort: copy to cache with a generated name
        return copyUriToCache(context, uri)
    }

    /**
     * Copy a content URI to app cache and return the file path.
     */
    fun copyUriToCache(
        context: Context,
        uri: Uri,
        customName: String? = null,
    ): String? {
        val name = customName ?: getFileName(context, uri) ?: "image_${System.currentTimeMillis()}.png"
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val cacheFile = File(context.cacheDir, "input/$name")
            cacheFile.parentFile?.mkdirs()
            // Collision-safe target: two sources with the same display name (e.g.
            // "001.jpg" from different folders in a recursive folder import, or a
            // multi-file share) must never overwrite each other — dedupe with a
            // numeric suffix ("001_1.jpg", "001_2.jpg", ...).
            val target =
                if (cacheFile.exists()) {
                    val dot = cacheFile.name.lastIndexOf('.')
                    val base = if (dot > 0) cacheFile.name.substring(0, dot) else cacheFile.name
                    val ext = if (dot > 0) cacheFile.name.substring(dot) else ""
                    var i = 1
                    var candidate = File(cacheFile.parentFile, "${base}_$i$ext")
                    while (candidate.exists()) {
                        i++
                        candidate = File(cacheFile.parentFile, "${base}_$i$ext")
                    }
                    candidate
                } else {
                    cacheFile
                }
            FileOutputStream(target).use { out ->
                inputStream.copyTo(out)
            }
            inputStream.close()
            target.absolutePath
        } catch (e: Exception) {
            KLog.w("KZKT", "Failed to copy URI to cache ($uri): ${e.message}")
            null
        }
    }

    private fun getFileName(
        context: Context,
        uri: Uri,
    ): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            KLog.w("KZKT", "Failed to resolve display name for $uri: ${e.message}")
            null
        }
    }

    /**
     * Recursively collect every image file URI inside a SAF tree directory.
     * Uses DocumentFile so no storage permission is required (scoped storage safe).
     */
    fun listImageUrisFromTree(
        context: Context,
        treeUri: Uri,
    ): List<Uri> {
        val root =
            androidx.documentfile.provider.DocumentFile
                .fromTreeUri(context, treeUri)
                ?: return emptyList()
        val results = mutableListOf<Uri>()

        fun walk(dir: androidx.documentfile.provider.DocumentFile) {
            dir.listFiles().forEach { child ->
                when {
                    child.isDirectory -> walk(child)
                    child.isFile && isImageMime(child.type) -> results.add(child.uri)
                    else -> {}
                }
            }
        }
        walk(root)
        return results.sortedBy { it.lastPathSegment }
    }

    private fun isImageMime(mime: String?): Boolean {
        if (mime == null) return false
        return mime.startsWith("image/") ||
            mime == "application/vnd.comicbook+zip"
    }

    /**
     * Open a translated image file using System Gallery / Media Viewer Intent.
     */
    fun openFileInSystemViewer(
        context: Context,
        filePath: String,
    ) {
        try {
            val file = File(filePath)
            if (!file.exists()) return

            val uri: Uri =
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )

            val mime = if (filePath.endsWith(".pdf", ignoreCase = true)) "application/pdf" else "image/*"
            val intent =
                android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            context.startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("KZKT", "Failed to open file in system viewer: ${e.message}")
        }
    }

    /**
     * Share a translated image file to WhatsApp / Social Media.
     */
    fun shareFile(
        context: Context,
        filePath: String,
    ) {
        try {
            val file = File(filePath)
            if (!file.exists()) return

            val uri: Uri =
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )

            val intent =
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/*"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(android.content.Intent.createChooser(intent, "Share Translated Image"))
        } catch (e: Exception) {
            android.util.Log.e("KZKT", "Failed to share file: ${e.message}")
        }
    }

    /**
     * Share any file (e.g. a JSON backup) with an explicit MIME type via a chooser.
     */
    fun shareAnyFile(
        context: Context,
        filePath: String,
        mimeType: String = "application/json",
    ) {
        try {
            val file = File(filePath)
            if (!file.exists()) return

            val uri: Uri =
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )

            val intent =
                android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(android.content.Intent.createChooser(intent, "Share File"))
        } catch (e: Exception) {
            android.util.Log.e("KZKT", "Failed to share file: ${e.message}")
        }
    }

    /**
     * Copy translated file to public MediaStore Download/KZKT directory.
     *
     * @param subDirName optional batch folder under Download/KZKT (e.g. "2026-08-16
     * 14-32 (4 pages)"). When blank the file lands directly in Download/KZKT — used
     * for exports (ZIP/PDF) which stay in the main folder.
     */
    fun saveToMediaStore(
        context: Context,
        filePath: String,
        subDirName: String = "",
    ): String? {
        try {
            val file = File(filePath)
            if (!file.exists()) return null

            val fileName = file.name
            val lowerName = fileName.lowercase()
            val mimeType =
                when {
                    lowerName.endsWith(".pdf") -> "application/pdf"
                    lowerName.endsWith(".zip") || lowerName.endsWith(".cbz") -> "application/zip"
                    lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") -> "image/jpeg"
                    lowerName.endsWith(".webp") -> "image/webp"
                    else -> "image/png"
                }
            val relPath =
                if (subDirName.isBlank()) {
                    "${android.os.Environment.DIRECTORY_DOWNLOADS}/KZKT"
                } else {
                    "${android.os.Environment.DIRECTORY_DOWNLOADS}/KZKT/$subDirName"
                }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values =
                    android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    }

                val targetUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val insertedUri = context.contentResolver.insert(targetUri, values)
                if (insertedUri != null) {
                    context.contentResolver.openOutputStream(insertedUri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    }

                    var actualPath: String? = null
                    val projection = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
                    context.contentResolver.query(insertedUri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                            if (idx != -1) {
                                actualPath = cursor.getString(idx)
                            }
                        }
                    }
                    if (actualPath != null) return actualPath
                    // MediaStore did not expose a real _data path (some devices). The
                    // original cache copy is deleted by the caller right after this, so
                    // returning it would leave History pointing at a missing file.
                    // Park a copy in app-external storage instead — no permission needed
                    // and the path stays valid.
                    val fallbackDir =
                        File(
                            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS),
                            if (subDirName.isBlank()) "KZKT" else "KZKT/$subDirName",
                        )
                    fallbackDir.mkdirs()
                    val fallbackFile = File(fallbackDir, fileName)
                    file.copyTo(fallbackFile, overwrite = true)
                    return fallbackFile.absolutePath
                }
            }

            // Fallback for pre-Android Q or if MediaStore insert fails
            val downloadFolder = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val outputFolder = File(downloadFolder, if (subDirName.isBlank()) "KZKT" else "KZKT/$subDirName")
            outputFolder.mkdirs()
            val destFile = File(outputFolder, fileName)
            file.copyTo(destFile, overwrite = true)
            return destFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.w("KZKT", "Failed to save to MediaStore: ${e.message}")
            return try {
                val file = File(filePath)
                val downloadFolder = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val outputFolder = File(downloadFolder, if (subDirName.isBlank()) "KZKT" else "KZKT/$subDirName")
                outputFolder.mkdirs()
                val destFile = File(outputFolder, file.name)
                file.copyTo(destFile, overwrite = true)
                destFile.absolutePath
            } catch (e: Exception) {
                KLog.w("KZKT", "Failed to save to public Downloads fallback: ${e.message}")
                null
            }
        }
    }

    /**
     * Save bitmap directly to public MediaStore Download/KZKT directory.
     */
    fun saveBitmapToMediaStore(
        context: Context,
        bitmap: android.graphics.Bitmap,
        fileName: String,
        subDirName: String = "KZKT",
    ): Uri? {
        return try {
            val lowerName = fileName.lowercase()
            val mimeType =
                when {
                    lowerName.endsWith(".pdf") -> "application/pdf"
                    lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") -> "image/jpeg"
                    lowerName.endsWith(".webp") -> "image/webp"
                    else -> "image/png"
                }
            val relPath =
                if (subDirName.isNotBlank() && subDirName != "KZKT") {
                    "${android.os.Environment.DIRECTORY_DOWNLOADS}/KZKT/$subDirName"
                } else {
                    "${android.os.Environment.DIRECTORY_DOWNLOADS}/KZKT"
                }

            val values =
                android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                    }
                }

            val targetUri =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

            val uri = context.contentResolver.insert(targetUri, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out ->
                val format =
                    if (mimeType ==
                        "image/jpeg"
                    ) {
                        android.graphics.Bitmap.CompressFormat.JPEG
                    } else {
                        android.graphics.Bitmap.CompressFormat.PNG
                    }
                bitmap.compress(format, 95, out)
            }
            uri
        } catch (e: Exception) {
            android.util.Log.w("KZKT", "Failed to save bitmap to MediaStore: ${e.message}")
            null
        }
    }
}
