package com.kzkt.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.kzkt.app.core.Config.TweakParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

/**
 * Image processing: box filtering, crop/mask, SFX detection, image I/O.
 * Ported from the original Python image service
 */
object ImageProcessor {

    init {
        try {
            System.loadLibrary("opencv_java4")
        } catch (_: Throwable) {}
        try {
            OpenCVLoader.initLocal()
        } catch (_: Throwable) {}
    }


    // ── Image I/O ──────────────────────────────────────────────────

    fun bitmapToBase64(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG, quality: Int = 85): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(format, quality, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun bitmapToBase64DataUri(bitmap: Bitmap): String {
        return "data:image/jpeg;base64,${bitmapToBase64(bitmap, Bitmap.CompressFormat.JPEG, 85)}"
    }

    /**
     * Downscale [bitmap] when its longest side exceeds [maxDimension] so provider
     * image-size limits are respected. Returns the same bitmap instance when it is
     * already small enough — the caller must not recycle the original in that case.
     */
    fun prepareImageForProvider(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        val newW = maxOf(1, (bitmap.width * scale).toInt())
        val newH = maxOf(1, (bitmap.height * scale).toInt())
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bitmap)
        return bitmap
    }

    fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return mat
    }

    fun loadBitmap(path: String): Bitmap? {
        return BitmapFactory.decodeFile(path)
    }

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

    // ── Box Geometry ───────────────────────────────────────────────

    private fun areaBox(box: IntArray): Int {
        return maxOf(0, box[2] - box[0]) * maxOf(0, box[3] - box[1])
    }

    private fun intersectionArea(a: IntArray, b: IntArray): Int {
        val ix1 = maxOf(a[0], b[0])
        val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2])
        val iy2 = minOf(a[3], b[3])
        return maxOf(0, ix2 - ix1) * maxOf(0, iy2 - iy1)
    }

    private fun shouldMerge(a: IntArray, b: IntArray): Boolean {
        val areaA = areaBox(a)
        val areaB = areaBox(b)
        if (areaA == 0 || areaB == 0) return false
        val inter = intersectionArea(a, b)
        if (inter == 0) return false
        val iou = inter.toDouble() / (areaA + areaB - inter)
        val coverSmall = inter.toDouble() / minOf(areaA, areaB)
        return iou >= 0.20 || coverSmall >= 0.60
    }

    private fun overlap1D(a1: Int, a2: Int, b1: Int, b2: Int): Int {
        return maxOf(0, minOf(a2, b2) - maxOf(a1, b1))
    }

    // ── Box Filtering ──────────────────────────────────────────────

    /**
     * Remove false giant boxes: if a big box overlaps a small box by >= 80% of the small one's area
     * and is > 6.0x the area, keep the small one.
     * buang_kotak_raksasa_palsu()
     */
    fun removeFalseGiants(boxes: List<IntArray>): List<IntArray> {
        if (boxes.isEmpty()) return boxes

        val withArea = boxes.sortedByDescending { areaBox(it) }
        val keep = BooleanArray(withArea.size) { true }

        for (i in withArea.indices) {
            if (!keep[i]) continue
            val (boxI, areaI) = withArea[i] to areaBox(withArea[i])
            for (j in i + 1 until withArea.size) {
                if (!keep[j]) continue
                val (boxJ, areaJ) = withArea[j] to areaBox(withArea[j])
                if (areaI > 6.0 * areaJ) {
                    val inter = intersectionArea(boxI, boxJ)
                    if (inter >= 0.8 * areaJ) {
                        keep[i] = false
                        break
                    }
                }
            }
        }
        return withArea.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Merge overlapping boxes iteratively.
     * gabung_kotak_tumpang_tindih()
     */
    fun mergeOverlapping(boxes: List<IntArray>): List<IntArray> {
        if (boxes.isEmpty()) return boxes
        var result = boxes.sortedBy { it[0] }

        var changed = true
        while (changed) {
            changed = false
            val newBoxes = mutableListOf<IntArray>()
            val used = BooleanArray(result.size) { false }

            for (i in result.indices) {
                if (used[i]) continue
                var (x1, y1, x2, y2) = result[i]

                for (j in i + 1 until result.size) {
                    if (used[j]) continue
                    if (result[j][0] > x2) break
                    if (shouldMerge(intArrayOf(x1, y1, x2, y2), result[j])) {
                        x1 = minOf(x1, result[j][0])
                        y1 = minOf(y1, result[j][1])
                        x2 = maxOf(x2, result[j][2])
                        y2 = maxOf(y2, result[j][3])
                        used[j] = true
                        changed = true
                    }
                }
                newBoxes.add(intArrayOf(x1, y1, x2, y2))
                used[i] = true
            }
            result = newBoxes
        }

        return result.sortedBy { it[1] * 10000 + it[0] }
    }

    /**
     * Remove boxes that are too wide, flat, or thin.
     * buang_kotak_ngawur()
     */
    fun removeNonsense(boxes: List<IntArray>, imgWidth: Int, imgHeight: Int): List<IntArray> {
        val totalArea = maxOf(1, imgWidth * imgHeight)
        return boxes.filter { box ->
            val (x1, y1, x2, y2) = box
            val w = maxOf(1, x2 - x1)
            val h = maxOf(1, y2 - y1)
            val ratio = w.toDouble() / h
            val areaRatio = (w * h).toDouble() / totalArea

            val tooWide = ratio >= 3.2 && w >= imgWidth * 0.35
            val tooFlat = w >= imgWidth * 0.50 && h <= imgHeight * 0.16
            val tooThin = areaRatio >= 0.035 && ratio >= 2.8

            !(tooWide || tooFlat || tooThin)
        }
    }

    /**
     * SFX / noise removal filter based on pixel analysis.
     * buang_kotak_sfx_dan_gambar()
     */
    fun removeSfxAndImages(
        mat: Mat,
        boxes: List<IntArray>,
        params: TweakParams,
    ): List<IntArray> {
        if (!params.detection.filterSfxAktif) return boxes

        val imgHeight = mat.rows()
        val imgWidth = mat.cols()
        val totalArea = maxOf(1, imgHeight * imgWidth)

        val (blackThr, edgeThr, whiteSafe) = when (params.detection.filterSfxMode.lowercase()) {
            "relaxed", "longgar" -> Triple(0.20, 0.14, 0.58)
            "strict", "ketat" -> Triple(0.13, 0.09, 0.68)
            else -> Triple(0.16, 0.11, 0.62) // balanced
        }

        return boxes.filter { box ->
            val (x1, y1, x2, y2) = box
            val w = maxOf(1, x2 - x1)
            val h = maxOf(1, y2 - y1)
            val areaRatio = (w * h).toDouble() / totalArea
            val ratio = w.toDouble() / h

            // Small boxes: always keep
            val isSmall = w < imgWidth * 0.18 && h < imgHeight * 0.18 && areaRatio < 0.020
            if (isSmall) return@filter true

            val crop = mat.submat(Rect(x1, y1, w, h))
            if (crop.empty()) {
                crop.release()
                return@filter false
            }

            try {
                val gray = Mat()
                val blackMask = Mat()
                val whiteMask = Mat()
                val edges = Mat()
                try {
                    Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGBA2GRAY)

                    // Black threshold
                    Imgproc.threshold(gray, blackMask, 79.0, 255.0, Imgproc.THRESH_BINARY_INV)
                    val blackRatio = Core.countNonZero(blackMask).toDouble() / gray.total()

                    // White threshold
                    Imgproc.threshold(gray, whiteMask, 220.0, 255.0, Imgproc.THRESH_BINARY)
                    val whiteRatio = Core.countNonZero(whiteMask).toDouble() / gray.total()

                    // Edge detection
                    Imgproc.Canny(gray, edges, 80.0, 160.0)
                    val edgeRatio = Core.countNonZero(edges).toDouble() / gray.total()

                    // Safe: mostly white = text bubble
                    if (whiteRatio >= whiteSafe) return@filter true

                    val isSfxOrImage = areaRatio > 0.018 && blackRatio > blackThr && edgeRatio > edgeThr
                    val isFlatSuspicious = ratio > 2.2 && w > imgWidth * 0.30 &&
                        edgeRatio > maxOf(0.07, edgeThr - 0.03) && whiteRatio < whiteSafe
                    val isLargeSuspicious = areaRatio > 0.045 && whiteRatio < 0.55 && edgeRatio > 0.075

                    !(isSfxOrImage || isFlatSuspicious || isLargeSuspicious)
                } finally {
                    edges.release()
                    whiteMask.release()
                    blackMask.release()
                    gray.release()
                }
            } finally {
                crop.release()
            }
        }
    }

    /**
     * Detect bubble background color (light/white vs dark/black).
     * Returns android.graphics.Color (Color.WHITE or Color.BLACK).
     */
    fun detectBubbleBackgroundColor(mat: Mat, box: IntArray): Int {
        val (x1, y1, x2, y2) = box
        val w = maxOf(1, x2 - x1)
        val h = maxOf(1, y2 - y1)

        val crop = mat.submat(Rect(x1, y1, w, h))
        if (crop.empty()) {
            crop.release()
            return android.graphics.Color.WHITE
        }

        val gray = Mat()
        try {
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_RGBA2GRAY)
            val meanVal = Core.mean(gray).`val`[0]
            return if (meanVal < 128.0) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        } catch (_: Exception) {
            return android.graphics.Color.WHITE
        } finally {
            gray.release()
            crop.release()
        }
    }

    /**
     * Average color of the ring of pixels around [box] (the page background the
     * text sits on), used to erase free-text regions with a solid fill before
     * rendering the translation. Samples the border strip only — never the text
     * strokes inside the box. Returns an ARGB int (alpha=255).
     */
    fun sampleRegionBackgroundColor(
        mat: Mat,
        box: IntArray,
        thickness: Int = 6,
    ): Int {
        val cols = mat.cols()
        val rows = mat.rows()
        val x1 = box[0].coerceIn(0, cols - 1)
        val y1 = box[1].coerceIn(0, rows - 1)
        val x2 = box[2].coerceIn(x1 + 1, cols)
        val y2 = box[3].coerceIn(y1 + 1, rows)

        val ex1 = maxOf(0, x1 - thickness)
        val ey1 = maxOf(0, y1 - thickness)
        val ex2 = minOf(cols, x2 + thickness)
        val ey2 = minOf(rows, y2 + thickness)
        val ew = ex2 - ex1
        val eh = ey2 - ey1
        if (ew <= 0 || eh <= 0) return android.graphics.Color.WHITE

        val sub = mat.submat(org.opencv.core.Rect(ex1, ey1, ew, eh))
        val buf = ByteArray(ew * eh * 4)
        var r = 0L
        var g = 0L
        var b = 0L
        var count = 0L
        try {
            sub.get(0, 0, buf)
            for (row in 0 until eh) {
                for (col in 0 until ew) {
                    val px = col + ex1
                    val py = row + ey1
                    val inBox = px >= x1 && px < x2 && py >= y1 && py < y2
                    if (inBox) continue
                    val idx = (row * ew + col) * 4
                    b += buf[idx].toInt() and 0xFF
                    g += buf[idx + 1].toInt() and 0xFF
                    r += buf[idx + 2].toInt() and 0xFF
                    count++
                }
            }
        } catch (_: Exception) {
            return android.graphics.Color.WHITE
        } finally {
            sub.release()
        }
        if (count == 0L) return android.graphics.Color.WHITE
        return android.graphics.Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }

    /**
     * Median color of the ring of pixels around [box]. More robust than the mean
     * when stray text strokes, specular highlights or panel borders touch the
     * sample strip — the median ignores those outliers. Used by the clean render
     * style so the erased free-text fill blends with the real page background.
     */
    fun sampleRegionBackgroundColorMedian(
        mat: Mat,
        box: IntArray,
        thickness: Int = 6,
    ): Int {
        val cols = mat.cols()
        val rows = mat.rows()
        val x1 = box[0].coerceIn(0, cols - 1)
        val y1 = box[1].coerceIn(0, rows - 1)
        val x2 = box[2].coerceIn(x1 + 1, cols)
        val y2 = box[3].coerceIn(y1 + 1, rows)

        val ex1 = maxOf(0, x1 - thickness)
        val ey1 = maxOf(0, y1 - thickness)
        val ex2 = minOf(cols, x2 + thickness)
        val ey2 = minOf(rows, y2 + thickness)
        val ew = ex2 - ex1
        val eh = ey2 - ey1
        if (ew <= 0 || eh <= 0) return android.graphics.Color.WHITE

        val ringCapacity = ew * eh - (x2 - x1) * (y2 - y1)
        if (ringCapacity <= 0) return android.graphics.Color.WHITE

        val sub = mat.submat(org.opencv.core.Rect(ex1, ey1, ew, eh))
        val buf = ByteArray(ew * eh * 4)
        val rs = IntArray(ringCapacity)
        val gs = IntArray(ringCapacity)
        val bs = IntArray(ringCapacity)
        var count = 0
        try {
            sub.get(0, 0, buf)
            for (row in 0 until eh) {
                for (col in 0 until ew) {
                    val px = col + ex1
                    val py = row + ey1
                    val inBox = px >= x1 && px < x2 && py >= y1 && py < y2
                    if (inBox) continue
                    val idx = (row * ew + col) * 4
                    bs[count] = buf[idx].toInt() and 0xFF
                    gs[count] = buf[idx + 1].toInt() and 0xFF
                    rs[count] = buf[idx + 2].toInt() and 0xFF
                    count++
                }
            }
        } catch (_: Exception) {
            return android.graphics.Color.WHITE
        } finally {
            sub.release()
        }
        if (count == 0) return android.graphics.Color.WHITE
        val rArr = rs.copyOfRange(0, count)
        val gArr = gs.copyOfRange(0, count)
        val bArr = bs.copyOfRange(0, count)
        java.util.Arrays.sort(rArr)
        java.util.Arrays.sort(gArr)
        java.util.Arrays.sort(bArr)
        val mid = count / 2
        return android.graphics.Color.rgb(rArr[mid], gArr[mid], bArr[mid])
    }

    /**
     * Merge nearby free-text boxes into larger regions: a caption box's lines,
     * adjacent SFX, or words split across ML Kit blocks become ONE box instead
     * of many small ones. Iterative greedy — repeats until no two boxes can
     * merge. Boxes merge when they sit on the same row (vertical overlap +
     * small horizontal gap), are stacked closely (horizontal overlap + small
     * vertical gap), or simply overlap.
     */
    fun mergeNearbyTextBoxes(
        boxes: List<IntArray>,
        gapRatio: Double = 0.7,
        overlapRatio: Double = 0.25,
    ): List<IntArray> {
        if (boxes.size <= 1) return boxes
        val work = boxes.map { it.copyOf() }.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in work.indices) {
                val a = work[i]
                for (j in i + 1 until work.size) {
                    val b = work[j]
                    if (shouldMergeTextRegions(a, b, gapRatio, overlapRatio)) {
                        work[i] =
                            intArrayOf(
                                minOf(a[0], b[0]),
                                minOf(a[1], b[1]),
                                maxOf(a[2], b[2]),
                                maxOf(a[3], b[3]),
                            )
                        work.removeAt(j)
                        changed = true
                        break@outer
                    }
                }
            }
        }
        return work
    }

    private fun shouldMergeTextRegions(
        a: IntArray,
        b: IntArray,
        gapRatio: Double,
        overlapRatio: Double,
    ): Boolean {
        val wA = maxOf(1, a[2] - a[0])
        val hA = maxOf(1, a[3] - a[1])
        val wB = maxOf(1, b[2] - b[0])
        val hB = maxOf(1, b[3] - b[1])
        val minH = minOf(hA, hB)
        val minW = minOf(wA, wB)

        val overlapX = overlap1D(a[0], a[2], b[0], b[2])
        val overlapY = overlap1D(a[1], a[3], b[1], b[3])
        val gapX = maxOf(0, maxOf(a[0], b[0]) - minOf(a[2], b[2]))
        val gapY = maxOf(0, maxOf(a[1], b[1]) - minOf(a[3], b[3]))

        val maxGap = maxOf(12, (gapRatio * minH).toInt())

        // Same row: vertically overlapping + small horizontal gap → one wide box.
        val sameRow = overlapY.toDouble() / minH >= overlapRatio && gapX <= maxGap
        // Stacked: horizontally overlapping + small vertical gap → one tall box.
        val stacked = overlapX.toDouble() / minW >= overlapRatio && gapY <= maxGap
        // Overlapping boxes are part of the same text region.
        val touching = overlapX > 0 && overlapY > 0
        return sameRow || stacked || touching
    }

    /** Intersection-over-union of two [x1, y1, x2, y2] boxes. */
    fun rectIou(
        a: IntArray,
        b: IntArray,
    ): Double {
        val ix1 = maxOf(a[0], b[0])
        val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2])
        val iy2 = minOf(a[3], b[3])
        val interW = maxOf(0, ix2 - ix1)
        val interH = maxOf(0, iy2 - iy1)
        val inter = interW.toDouble() * interH
        val areaA = maxOf(1, a[2] - a[0]) * maxOf(1, a[3] - a[1])
        val areaB = maxOf(1, b[2] - b[0]) * maxOf(1, b[3] - b[1])
        return inter / (areaA + areaB - inter)
    }

    // ── Crop & Mask ────────────────────────────────────────────────

    /**
     * Expand crop region with padding but avoid overlapping other boxes.
     * buat_crop_lega_tapi_tidak_nyamber()
     */
    fun smartCropBounds(
        box: IntArray,
        allBoxes: List<IntArray>,
        imgWidth: Int,
        imgHeight: Int,
        padX: Int,
        padY: Int,
        params: TweakParams,
    ): IntArray {
        val (x1, y1, x2, y2) = box
        var cropX1 = maxOf(0, x1 - padX)
        var cropY1 = maxOf(0, y1 - padY)
        var cropX2 = minOf(imgWidth, x2 + padX)
        var cropY2 = minOf(imgHeight, y2 + padY)

        val boxW = maxOf(1, x2 - x1)
        val boxH = maxOf(1, y2 - y1)

        for (other in allBoxes) {
            if (other.contentEquals(box)) continue
            val (ox1, oy1, ox2, oy2) = other
            val otherW = maxOf(1, ox2 - ox1)
            val otherH = maxOf(1, oy2 - oy1)

            val overlapX = overlap1D(x1, x2, ox1, ox2).toDouble() / minOf(boxW, otherW)
            val overlapY = overlap1D(y1, y2, oy1, oy2).toDouble() / minOf(boxH, otherH)

            if (overlapX >= params.detection.overlapBatasCrop) {
                if (oy1 >= y2) { // other is below
                    val batas = (y2 + oy1) / 2
                    cropY2 = minOf(cropY2, maxOf(y2, batas))
                } else if (oy2 <= y1) { // other is above
                    val batas = (oy2 + y1) / 2
                    cropY1 = maxOf(cropY1, minOf(y1, batas))
                }
            }

            if (overlapY >= params.detection.overlapBatasCrop) {
                if (ox1 >= x2) { // other is to the right
                    val batas = (x2 + ox1) / 2
                    cropX2 = minOf(cropX2, maxOf(x2, batas))
                } else if (ox2 <= x1) { // other is to the left
                    val batas = (ox2 + x1) / 2
                    cropX1 = maxOf(cropX1, minOf(x1, batas))
                }
            }
        }

        return intArrayOf(cropX1, cropY1, cropX2, cropY2)
    }

    /**
     * Mask outside the bubble area with white.
     * mask_luar_box_utama()
     */
    fun maskOutsideBubble(
        crop: Mat,
        cropX1: Int, cropY1: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
        params: TweakParams,
    ): Mat {
        if (!params.detection.maskAreaLuarBox) return crop

        val localX1 = x1 - cropX1
        val localY1 = y1 - cropY1
        val localX2 = x2 - cropX1
        val localY2 = y2 - cropY1

        val maskX1 = maxOf(0, localX1 - params.detection.maskMargin)
        val maskY1 = maxOf(0, localY1 - params.detection.maskMargin)
        val maskX2 = minOf(crop.cols(), localX2 + params.detection.maskMargin)
        val maskY2 = minOf(crop.rows(), localY2 + params.detection.maskMargin)

        val result = Mat.ones(crop.size(), crop.type())
        val region = result.submat(Rect(maskX1, maskY1, maskX2 - maskX1, maskY2 - maskY1))
        val srcRegion = crop.submat(Rect(maskX1, maskY1, maskX2 - maskX1, maskY2 - maskY1))
        try {
            Core.multiply(result, Scalar.all(255.0), result)
            srcRegion.copyTo(region)
        } finally {
            srcRegion.release()
            region.release()
        }
        return result
    }

    /**
     * Doubles the resolution of [bitmap] via the smart upscaler. Returns a new
     * bitmap; the caller must recycle the input afterwards.
     */
    fun upscaleBitmap(bitmap: Bitmap): Bitmap {
        val mat = bitmapToMat(bitmap)
        val enhanced = enhanceImage(mat)
        val result = matToBitmap(enhanced)
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
        val targets = coordinateMap.mapNotNull { (id, box) ->
            val text = translations[id]
            if (text != null && text.uppercase() != "SKIP" && text.isNotBlank()) box else null
        }
        if (targets.isEmpty()) return
        coroutineScope {
            targets.map { box ->
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
    fun inpaintBubbleText(mat: Mat, box: IntArray): Mat {
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
            org.opencv.photo.Photo.inpaint(bgrCrop, textMask, inpainted, 3.0, org.opencv.photo.Photo.INPAINT_TELEA)
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

    // ── Landscape Auto-Split ───────────────────────────────────────

    /**
     * Auto-split wide images (ratio > 1.2) into pages, processing right-to-left.
     * Returns number of splits.
     */
    fun shouldAutoSplit(bitmap: Bitmap): Int {
        val ratio = bitmap.width.toDouble() / bitmap.height
        if (ratio <= 1.2) return 1
        return maxOf(2, (ratio / 0.71).toInt())
    }
}
