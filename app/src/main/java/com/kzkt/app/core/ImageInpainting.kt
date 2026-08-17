package com.kzkt.app.core

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Inpainting and upscaling helpers, extracted from ImageProcessor so the heavy
 * OpenCV image-transformation code lives apart from the box filtering / I/O.
 */
object ImageInpainting {
    /**
     * Smart Image Upscaler using OpenCV
     * Doubles the resolution using Bicubic interpolation and applies Unsharp Masking
     * to enhance text edges for better OCR detection on low-res scans.
     */
    fun enhanceImage(src: Mat): Mat {
        val dest = Mat()
        val blurred = Mat()
        try {
            // 1. Upscale 2x using bicubic interpolation
            Imgproc.resize(src, dest, Size(), 2.0, 2.0, Imgproc.INTER_CUBIC)

            // 2. Unsharp Masking for crisp edges
            Imgproc.GaussianBlur(dest, blurred, Size(0.0, 0.0), 3.0)

            val sharpened = Mat()
            // sharpened = dest * 1.5 - blurred * 0.5
            Core.addWeighted(dest, 1.5, blurred, -0.5, 0.0, sharpened)
            return sharpened
        } finally {
            // dest (4x piksel dari resize 2x) harus di-release — kebocoran native
            // ini bisa menumpuk ratusan MB per batch saat upscaler aktif.
            dest.release()
            blurred.release()
        }
    }

    /**
     * Doubles the resolution of [bitmap] via the smart upscaler. Returns a new
     * bitmap; the caller must recycle the input afterwards.
     */
    fun upscaleBitmap(bitmap: Bitmap): Bitmap {
        val mat = ImageProcessor.bitmapToMat(bitmap)
        val enhanced = enhanceImage(mat)
        val result = ImageProcessor.matToBitmap(enhanced)
        mat.release()
        enhanced.release()
        return result
    }

    /**
     * Pre-filters [translations] (dropping SKIP / blank values), then erases the
     * original text strokes inside the matching bubbles of [mat] in parallel.
     * Shared by the single-image and batch paths so the parallel inpainting
     * boilerplate lives in exactly one place.
     */
    suspend fun inpaintTranslated(
        mat: Mat,
        translations: Map<String, String>,
        coordinateMap: Map<String, IntArray>,
    ) {
        val targets =
            coordinateMap.mapNotNull { (id, box) ->
                val text = translations[id]
                if (text != null && text.uppercase() != "SKIP" && text.isNotBlank()) box else null
            }
        if (targets.isEmpty()) return
        coroutineScope {
            targets
                .map { box ->
                    async(Dispatchers.Default) {
                        synchronized(mat) {
                            inpaintBubbleText(mat, box)
                        }
                    }
                }.awaitAll()
        }
    }

    /**
     * Inpaint original text inside a speech bubble using OpenCV Photo.inpaint.
     * Erases dark text strokes seamlessly matching background screentone/texture.
     */
    fun inpaintBubbleText(
        mat: Mat,
        box: IntArray,
    ): Mat {
        if (mat.empty() || box.size < 4) return mat
        val cols = mat.cols()
        val rows = mat.rows()

        val x1 = box[0].coerceIn(0, maxOf(0, cols - 1))
        val y1 = box[1].coerceIn(0, maxOf(0, rows - 1))
        val w = (box[2] - x1).coerceIn(1, maxOf(1, cols - x1))
        val h = (box[3] - y1).coerceIn(1, maxOf(1, rows - y1))

        val rect = Rect(x1, y1, w, h)
        val crop = mat.submat(rect)
        if (crop.empty()) {
            crop.release()
            return mat
        }

        val gray = Mat()
        val textMask = Mat()
        val inpainted = Mat()
        try {
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGBA2GRAY)
            val meanBrightness = Core.mean(gray).`val`[0]
            if (meanBrightness > 128) {
                // White/light background: threshold dark text strokes (inclusive of anti-aliasing)
                Imgproc.threshold(gray, textMask, 190.0, 255.0, Imgproc.THRESH_BINARY_INV)
            } else {
                // Dark background: threshold light text strokes
                Imgproc.threshold(gray, textMask, 90.0, 255.0, Imgproc.THRESH_BINARY)
            }

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.dilate(textMask, textMask, kernel)
            kernel.release()

            val bgrCrop = Mat()
            Imgproc.cvtColor(crop, bgrCrop, Imgproc.COLOR_RGBA2BGR)
            org.opencv.photo.Photo
                .inpaint(bgrCrop, textMask, inpainted, 3.0, org.opencv.photo.Photo.INPAINT_TELEA)
            bgrCrop.release()

            val rgbaResult = Mat()
            Imgproc.cvtColor(inpainted, rgbaResult, Imgproc.COLOR_BGR2RGBA)
            val targetSubmat = mat.submat(rect)
            rgbaResult.copyTo(targetSubmat)
            targetSubmat.release()
            rgbaResult.release()
        } catch (e: Exception) {
            Log.w("KZKT", "Inpainting failed: ${e.message}")
        } finally {
            inpainted.release()
            textMask.release()
            gray.release()
            crop.release()
        }
        return mat
    }
}
