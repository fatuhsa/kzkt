package com.kzkt.app.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.kzkt.app.core.Config.TweakParams
import com.kzkt.app.util.KLog
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

/**
 * Image processing: box filtering, crop/mask, SFX detection, image I/O.
 */
object ImageProcessor {
    init {
        try {
            System.loadLibrary("opencv_java4")
        } catch (e: Throwable) {
            KLog.e("KZKT", "CRITICAL: System.loadLibrary(\"opencv_java4\") failed: ${e.message}")
        }
        try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            KLog.e("KZKT", "CRITICAL: OpenCVLoader.initLocal() failed: ${e.message}")
        }
    }

    // ── Image I/O ──────────────────────────────────────────────────

    fun bitmapToBase64(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 85,
    ): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(format, quality, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun bitmapToBase64DataUri(bitmap: Bitmap): String = "data:image/jpeg;base64,${bitmapToBase64(bitmap, Bitmap.CompressFormat.JPEG, 85)}"

    /**
     * Downscale [bitmap] when its longest side exceeds [maxDimension] so provider
     * image-size limits are respected. Returns the same bitmap instance when it is
     * already small enough — the caller must not recycle the original in that case.
     */
    fun prepareImageForProvider(
        bitmap: Bitmap,
        maxDimension: Int,
    ): Bitmap {
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

    fun loadBitmap(path: String): Bitmap? = BitmapFactory.decodeFile(path)

    // ── Box Filtering ──────────────────────────────────────────────

    /**
     * Remove false giant boxes: if a big box overlaps a small box by >= 80% of the small one's area
     * and is > 6.0x the area, keep the small one.
     * buang_kotak_raksasa_palsu()
     */
    fun removeFalseGiants(boxes: List<IntArray>): List<IntArray> {
        if (boxes.isEmpty()) return boxes

        val withArea = boxes.sortedByDescending { BoxGeometry.areaBox(it) }
        val keep = BooleanArray(withArea.size) { true }

        for (i in withArea.indices) {
            if (!keep[i]) continue
            val (boxI, areaI) = withArea[i] to BoxGeometry.areaBox(withArea[i])
            for (j in i + 1 until withArea.size) {
                if (!keep[j]) continue
                val (boxJ, areaJ) = withArea[j] to BoxGeometry.areaBox(withArea[j])
                if (areaI > 6.0 * areaJ) {
                    val inter = BoxGeometry.intersectionArea(boxI, boxJ)
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
                    if (BoxGeometry.shouldMerge(intArrayOf(x1, y1, x2, y2), result[j])) {
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
    fun removeNonsense(
        boxes: List<IntArray>,
        imgWidth: Int,
        imgHeight: Int,
    ): List<IntArray> = boxes.filterNot { BoxGeometry.isNonsenseBox(it, imgWidth, imgHeight) }

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

        val (blackThr, edgeThr, whiteSafe) =
            when (params.detection.filterSfxMode.lowercase()) {
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
                    val isFlatSuspicious =
                        ratio > 2.2 &&
                            w > imgWidth * 0.30 &&
                            edgeRatio > maxOf(0.07, edgeThr - 0.03) &&
                            whiteRatio < whiteSafe
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

        val overlapX = BoxGeometry.overlap1D(a[0], a[2], b[0], b[2])
        val overlapY = BoxGeometry.overlap1D(a[1], a[3], b[1], b[3])
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
    ): Double = BoxGeometry.rectIou(a, b)

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
