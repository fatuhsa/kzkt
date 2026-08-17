package com.kzkt.app.core

import com.kzkt.app.core.Config.TweakParams
import com.kzkt.app.util.KLog
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Region analysis (background color sampling) and crop/mask helpers, extracted
 * from ImageProcessor so box filtering / I/O stays lean.
 */
object ImageRegion {
    /**
     * Detect bubble background color (light/white vs dark/black).
     * Returns android.graphics.Color (Color.WHITE or Color.BLACK).
     */
    fun detectBubbleBackgroundColor(
        mat: Mat,
        box: IntArray,
    ): Int {
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
        } catch (e: Exception) {
            KLog.w("KZKT", "Failed to detect bubble background color: ${e.message}")
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

        val sub = mat.submat(Rect(ex1, ey1, ew, eh))
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
        } catch (e: Exception) {
            KLog.w("KZKT", "Failed to sample region background color: ${e.message}")
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

        val sub = mat.submat(Rect(ex1, ey1, ew, eh))
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
        } catch (e: Exception) {
            KLog.w("KZKT", "Failed to sample median region background color: ${e.message}")
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

            val overlapX = BoxGeometry.overlap1D(x1, x2, ox1, ox2).toDouble() / minOf(boxW, otherW)
            val overlapY = BoxGeometry.overlap1D(y1, y2, oy1, oy2).toDouble() / minOf(boxH, otherH)

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
        cropX1: Int,
        cropY1: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
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
}
