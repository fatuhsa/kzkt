package com.kzkt.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * PDF I/O using only built-in Android APIs — zero third-party dependencies.
 * Input:  PdfRenderer (rasterize each page to a Bitmap)
 * Output: PdfDocument (assemble translated page images back into a PDF)
 */
object PdfImporter {

    /**
     * Render every page of a PDF to PNG files in [outputDir].
     * Returns the list of generated image paths (empty if the PDF could not be read).
     *
     * [dpiScale] 1.5–2.0 keeps bubble text sharp enough for YOLO detection.
     */
    fun extractPdfToImages(pdfFile: File, outputDir: File, dpiScale: Float = 1.5f, context: android.content.Context? = null): List<String> {
        val imagePaths = mutableListOf<String>()
        try {
            outputDir.mkdirs()
            outputDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        } catch (_: Exception) {}

        val fd = openPdfFileDescriptor(context, pdfFile) ?: run {
            Log.e("KZKT/PDF", "Unable to open ParcelFileDescriptor for PDF: ${pdfFile.absolutePath}")
            return emptyList()
        }

        try {
            val renderer = PdfRenderer(fd)
            try {
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    try {
                        // Cap resolution to 2048px for ultra-fast rendering & zero OOM.
                        // IMPORTANT: the cap must scale BOTH dimensions by the same uniform
                        // factor. Capping width and height independently breaks the page's
                        // aspect ratio once one axis exceeds 2048 (e.g. translated PDFs whose
                        // pages are 2x when the Smart Upscaler is ON) — the rendered page then
                        // looks squished / stretched. A uniform fit keeps the page intact.
                        val maxDim = 2048
                        val rawW = page.width * dpiScale
                        val rawH = page.height * dpiScale
                        val fit = minOf(1.0f, maxDim / maxOf(rawW, rawH))
                        val width = (rawW * fit).toInt().coerceAtLeast(1)
                        val height = (rawH * fit).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val imageFile = File(outputDir, "${pdfFile.nameWithoutExtension}_page_${i + 1}.jpg")
                            imageFile.outputStream().use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                            }
                            imagePaths.add(imageFile.absolutePath)
                        } finally {
                            bitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.w("KZKT/PDF", "Failed to render page ${i + 1}: ${e.message}")
                    } finally {
                        page.close()
                    }
                }
            } finally {
                renderer.close()
            }
        } catch (e: Exception) {
            Log.e("KZKT/PDF", "PdfRenderer error: ${e.message}")
        } finally {
            try { fd.close() } catch (_: Exception) {}
        }
        return imagePaths
    }

    /**
     * Count a PDF's pages without rendering them (cheap: opens the renderer and
     * reads pageCount). Returns 0 when the PDF cannot be read. Used to name the
     * per-batch output folder ("… (N pages)") before translation starts.
     */
    fun pdfPageCount(pdfFile: File, context: android.content.Context? = null): Int {
        val fd = openPdfFileDescriptor(context, pdfFile) ?: return 0
        try {
            val renderer = PdfRenderer(fd)
            try {
                return renderer.pageCount
            } finally {
                renderer.close()
            }
        } catch (e: Exception) {
            Log.w("KZKT/PDF", "Failed to count pages of ${pdfFile.absolutePath}: ${e.message}")
            return 0
        } finally {
            try { fd.close() } catch (_: Exception) {}
        }
    }

    fun openPdfFileDescriptor(context: android.content.Context?, pdfFile: File): ParcelFileDescriptor? {
        if (pdfFile.exists() && pdfFile.canRead()) {
            try {
                return ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                Log.w("KZKT/PDF", "Direct file open failed for ${pdfFile.absolutePath}: ${e.message}")
            }
        }

        if (context != null) {
            // MediaStore.Downloads only exists on Android 10+ (API 29) — skip the
            // fallback entirely on older devices (accessing the field would crash).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // 1. Try querying MediaStore Downloads by DATA path
            try {
                val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
                val selection = "${android.provider.MediaStore.MediaColumns.DATA} = ?"
                val selectionArgs = arrayOf(pdfFile.absolutePath)
                val contentUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI

                context.contentResolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                        val itemUri = android.content.ContentUris.withAppendedId(contentUri, id)
                        return context.contentResolver.openFileDescriptor(itemUri, "r")
                    }
                }
            } catch (_: Exception) {}

            // 2. Try querying MediaStore Downloads by DISPLAY_NAME
            try {
                val projection = arrayOf(android.provider.MediaStore.MediaColumns._ID)
                val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(pdfFile.name)
                val contentUri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI

                context.contentResolver.query(contentUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                        val itemUri = android.content.ContentUris.withAppendedId(contentUri, id)
                        return context.contentResolver.openFileDescriptor(itemUri, "r")
                    }
                }
            } catch (_: Exception) {}
            }
        }

        return null
    }
}

object PdfExporter {

    /**
     * Assemble a list of page images into a single PDF file.
     * Each page gets its own PDF page sized to the image dimensions.
     */
    fun createPdfFromImages(imagePaths: List<String>, outputPdfFile: File) {
        val pdf = PdfDocument()
        try {
            for ((index, imagePath) in imagePaths.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(imagePath) ?: continue
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                    val page = pdf.startPage(pageInfo)
                    page.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    pdf.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }

            outputPdfFile.parentFile?.mkdirs()
            FileOutputStream(outputPdfFile).use { out ->
                pdf.writeTo(out)
            }
        } finally {
            pdf.close()
        }
    }
}
