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
    fun extractPdfToImages(pdfFile: File, outputDir: File, dpiScale: Float = 1.5f): List<String> {
        val imagePaths = mutableListOf<String>()
        try {
            outputDir.mkdirs()
            outputDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        } catch (_: Exception) {}

        val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val renderer = PdfRenderer(fd)
            try {
                for (i in 0 until renderer.pageCount) {
                    val page = renderer.openPage(i)
                    try {
                        // Cap resolution to 2048px for ultra-fast rendering & zero OOM
                        val width = (page.width * dpiScale).toInt().coerceIn(1, 2048)
                        val height = (page.height * dpiScale).toInt().coerceIn(1, 2048)
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
        } finally {
            fd.close()
        }
        return imagePaths
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
