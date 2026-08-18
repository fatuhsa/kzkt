package com.kzkt.app.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ArchiveExtractor {
    /**
     * Extracts a CBZ/ZIP file to a cache directory and returns the list of extracted image paths,
     * sorted alphabetically.
     */
    fun extractCbz(
        context: Context,
        uri: Uri,
    ): List<String> {
        val cacheDir = File(context.cacheDir, "cbz_extract_${System.currentTimeMillis()}")
        cacheDir.mkdirs()

        val extractedFiles = mutableListOf<String>()

        try {
            // Copy URI to a temporary local file first
            val tempZip = File(context.cacheDir, "temp_archive_${System.currentTimeMillis()}.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output)
                }
            }

            // Use ZipFile which correctly handles data descriptors and complex zip structures
            java.util.zip.ZipFile(tempZip).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && isImageFile(entry.name)) {
                        // Preserve directory structure in filename to avoid collisions
                        // Do NOT use regex to strip characters, as it destroys Japanese filenames
                        val sanitized = entry.name.replace('/', '_').replace('\\', '_')
                        // Cap filename length to avoid OS limits (usually 255 bytes, leave room for path)
                        val safeName = if (sanitized.length > 150) sanitized.takeLast(150) else sanitized
                        val outputFile = File(cacheDir, safeName)

                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(outputFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        extractedFiles.add(outputFile.absolutePath)
                    }
                }
            }
            tempZip.delete()
        } catch (e: Exception) {
            android.util.Log.e("KZKT", "Failed to extract CBZ: ${e.message}")
            return emptyList()
        }

        return extractedFiles.sorted()
    }

    /**
     * Creates a CBZ (ZIP) archive from a list of image files.
     */
    fun createCbz(
        context: Context,
        imagePaths: List<String>,
        outputFileName: String,
    ): File? {
        if (imagePaths.isEmpty()) return null

        val outputDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "KZKT")
        outputDir.mkdirs()

        val cbzFile = File(outputDir, outputFileName)

        try {
            // Use paths relative to the deepest common ancestor directory so a
            // chapter/folder structure is preserved inside the archive (matching
            // how extractCbz stores entries). When all files share one directory
            // the entry names stay identical to the old flat behaviour.
            val commonRoot = commonParentDir(imagePaths)
            FileOutputStream(cbzFile).use { fos ->
                ZipOutputStream(fos).use { zos ->
                    imagePaths.forEach { path ->
                        val file = File(path)
                        if (file.exists()) {
                            val entryName =
                                if (commonRoot != null && file.absolutePath.startsWith(commonRoot)) {
                                    file.absolutePath.removePrefix(commonRoot).trimStart('/', '\\')
                                } else {
                                    file.name
                                }

                            val zipEntry = ZipEntry(entryName)
                            zos.putNextEntry(zipEntry)
                            file.inputStream().use { fis ->
                                fis.copyTo(zos)
                            }
                            zos.closeEntry()
                        }
                    }
                }
            }
            return cbzFile
        } catch (e: Exception) {
            android.util.Log.e("KZKT", "Failed to create CBZ: ${e.message}")
            return null
        }
    }

    /**
     * Deepest common ancestor directory of all input paths (absolute, no trailing
     * separator), or null when the inputs have no shared ancestor (e.g. one file).
     */
    private fun commonParentDir(paths: List<String>): String? {
        if (paths.size < 2) return null
        val abs = paths.map { File(it).absoluteFile }
        var common = abs[0].parentFile?.absolutePath ?: return null
        for (i in 1 until abs.size) {
            var parent = abs[i].parentFile
            while (parent != null && !common.startsWith(parent.absolutePath + File.separator) && common != parent.absolutePath) {
                parent = parent.parentFile
            }
            if (parent == null) return null
            common = parent.absolutePath
        }
        return common
    }

    /**
     * Creates a PDF from a list of image files (one image per page, aspect ratio
     * preserved, downsampled to at most ~1600px so big scans stay memory-friendly).
     * Written next to [createCbz] outputs in Download/KZKT. Returns null on failure.
     */
    fun createPdf(
        context: Context,
        imagePaths: List<String>,
        outputFileName: String,
    ): File? {
        if (imagePaths.isEmpty()) return null
        val outputDir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "KZKT")
        outputDir.mkdirs()
        val pdfFile = File(outputDir, outputFileName)
        val document = android.graphics.pdf.PdfDocument()
        try {
            imagePaths.forEach { path ->
                val file = File(path)
                if (!file.exists()) return@forEach
                // Downsample so a 4000px scan does not decode at full size.
                val bounds =
                    android.graphics.BitmapFactory
                        .Options()
                        .apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
                val bmp =
                    android.graphics.BitmapFactory.decodeFile(
                        path,
                        android.graphics.BitmapFactory
                            .Options()
                            .apply { inSampleSize = sample },
                    )
                        ?: return@forEach
                // Page sized to the image's aspect ratio, scaled to fit A4-ish bounds.
                val maxW = 595
                val maxH = 842
                val scale = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height)
                val pageW = (bmp.width * scale).toInt().coerceAtLeast(1)
                val pageH = (bmp.height * scale).toInt().coerceAtLeast(1)
                val pageInfo =
                    android.graphics.pdf.PdfDocument.PageInfo
                        .Builder(pageW, pageH, imagePaths.indexOf(path))
                        .create()
                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, null, android.graphics.Rect(0, 0, pageW, pageH), null)
                document.finishPage(page)
                bmp.recycle()
            }
            FileOutputStream(pdfFile).use { document.writeTo(it) }
            return pdfFile
        } catch (e: Exception) {
            android.util.Log.e("KZKT", "Failed to create PDF: ${e.message}")
            return null
        } finally {
            document.close()
        }
    }

    private fun isImageFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp")
    }
}
