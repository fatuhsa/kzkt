package com.kzkt.app.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ArchiveExtractor {

    /**
     * Extracts a CBZ/ZIP file to a cache directory and returns the list of extracted image paths,
     * sorted alphabetically.
     */
    fun extractCbz(context: Context, uri: Uri): List<String> {
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
    fun createCbz(context: Context, imagePaths: List<String>, outputFileName: String): File? {
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
                            val entryName = if (commonRoot != null && file.absolutePath.startsWith(commonRoot)) {
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
            var parent = abs[i].parentFile ?: return null
            while (parent != null && !common.startsWith(parent.absolutePath + File.separator) && common != parent.absolutePath) {
                parent = parent.parentFile
            }
            if (parent == null) return null
            common = parent.absolutePath
        }
        return common
    }

    private fun isImageFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || 
               lower.endsWith(".png") || lower.endsWith(".webp")
    }
}
