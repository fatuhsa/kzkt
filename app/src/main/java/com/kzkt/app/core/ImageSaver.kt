package com.kzkt.app.core

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import com.kzkt.app.ui.FileUtils
import java.io.File
import java.io.FileOutputStream

/**
 * Saves translated pages with extension-matched encoding. Extracted from
 * TranslationPipeline.
 */
class ImageSaver(
    private val context: Context?,
    private val jpegQuality: Int,
) {

    fun save(bitmap: Bitmap, path: String) {
        val file = File(path)
        file.parentFile?.mkdirs()
        // Encode benchmark (JVM ImageIO, representative ratio): JPEG encodes ~4x
        // faster and yields ~4x smaller files than PNG on scan-like content. JPEG is
        // used ONLY for .jpg/.jpeg outputs so the extension ↔ MIME type stays
        // consistent (MediaStore infers MIME from the filename — writing JPEG into a
        // .png/.gif/extensionless name would mislabel the file in the gallery). The
        // quality is user-configurable (default 92).
        val lowerName = file.name.lowercase()
        val format = if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            Bitmap.CompressFormat.JPEG
        } else {
            Bitmap.CompressFormat.PNG
        }
        val quality = if (format == Bitmap.CompressFormat.JPEG) jpegQuality else 100
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(format, quality, out)
            }
        } catch (e: Exception) {
            val ctx = context
            if (ctx != null) {
                val subDir = file.parentFile?.name ?: "KZKT"
                val uri = FileUtils.saveBitmapToMediaStore(ctx, bitmap, file.name, subDir)
                if (uri == null) {
                    val fallbackFile = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "KZKT/${file.name}")
                    fallbackFile.parentFile?.mkdirs()
                    FileOutputStream(fallbackFile).use { out ->
                        bitmap.compress(format, quality, out)
                    }
                }
            }
        }
    }
}
