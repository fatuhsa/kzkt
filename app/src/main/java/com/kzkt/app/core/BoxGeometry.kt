package com.kzkt.app.core

/**
 * Shared box geometry helpers used by both the detection pipeline (ImageProcessor)
 * and the renderer (ResultRenderer). Keeping them in one place means threshold
 * changes cannot drift between the two copies.
 */
object BoxGeometry {
    /** Width of an [x1, y1, x2, y2] box, at least 1. */
    fun boxWidth(box: IntArray): Int = maxOf(1, box[2] - box[0])

    /** Height of an [x1, y1, x2, y2] box, at least 1. */
    fun boxHeight(box: IntArray): Int = maxOf(1, box[3] - box[1])

    /** Area of an [x1, y1, x2, y2] box, clamped to >= 0. */
    fun areaBox(box: IntArray): Int = maxOf(0, box[2] - box[0]) * maxOf(0, box[3] - box[1])

    /** Aspect ratio w/h of an [x1, y1, x2, y2] box (>= 1 dimensions). */
    fun boxRatio(box: IntArray): Double = boxWidth(box).toDouble() / boxHeight(box)

    /** Box area as a fraction of the total image area (>= 1 guard). */
    fun areaRatio(
        box: IntArray,
        totalArea: Int,
    ): Double = (boxWidth(box) * boxHeight(box)).toDouble() / maxOf(1, totalArea)

    /** Intersection area of two [x1, y1, x2, y2] boxes. */
    fun intersectionArea(
        a: IntArray,
        b: IntArray,
    ): Int {
        val ix1 = maxOf(a[0], b[0])
        val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2])
        val iy2 = minOf(a[3], b[3])
        return maxOf(0, ix2 - ix1) * maxOf(0, iy2 - iy1)
    }

    /**
     * Whether two boxes overlap enough to be merged: IoU >= 0.20, or the smaller
     * box is >= 60% covered by the intersection.
     */
    fun shouldMerge(
        a: IntArray,
        b: IntArray,
    ): Boolean {
        val areaA = areaBox(a)
        val areaB = areaBox(b)
        if (areaA == 0 || areaB == 0) return false
        val inter = intersectionArea(a, b)
        if (inter == 0) return false
        val iou = inter.toDouble() / (areaA + areaB - inter)
        val coverSmall = inter.toDouble() / minOf(areaA, areaB)
        return iou >= 0.20 || coverSmall >= 0.60
    }

    /** Overlap length of two intervals [a1, a2] and [b1, b2]. */
    fun overlap1D(
        a1: Int,
        a2: Int,
        b1: Int,
        b2: Int,
    ): Int = maxOf(0, minOf(a2, b2) - maxOf(a1, b1))

    /** Intersection-over-union of two [x1, y1, x2, y2] boxes. */
    fun rectIou(
        a: IntArray,
        b: IntArray,
    ): Double {
        val interW = maxOf(0, minOf(a[2], b[2]) - maxOf(a[0], b[0]))
        val interH = maxOf(0, minOf(a[3], b[3]) - maxOf(a[1], b[1]))
        val inter = interW.toDouble() * interH
        val areaA = maxOf(1, a[2] - a[0]) * maxOf(1, a[3] - a[1])
        val areaB = maxOf(1, b[2] - b[0]) * maxOf(1, b[3] - b[1])
        return inter / (areaA + areaB - inter)
    }

    /** Suspiciously wide box: >= 3.2:1 and >= 35% of the image width. */
    fun isTooWide(
        box: IntArray,
        imgWidth: Int,
    ): Boolean = boxRatio(box) >= 3.2 && boxWidth(box) >= imgWidth * 0.35

    /** Suspiciously flat box: >= 50% of the image width and <= 16% of the height. */
    fun isTooFlat(
        box: IntArray,
        imgWidth: Int,
        imgHeight: Int,
    ): Boolean = boxWidth(box) >= imgWidth * 0.50 && boxHeight(box) <= imgHeight * 0.16

    /** Suspiciously thin box: >= 3.5% of the image area with a >= 2.8:1 ratio. */
    fun isTooThin(
        box: IntArray,
        totalArea: Int,
    ): Boolean = areaRatio(box, totalArea) >= 0.035 && boxRatio(box) >= 2.8

    /**
     * Whether a box is too wide, flat, or thin to be a speech bubble
     * (mirrors the original removeNonsense / buang_kotak_ngawur logic).
     */
    fun isNonsenseBox(
        box: IntArray,
        imgWidth: Int,
        imgHeight: Int,
    ): Boolean {
        val totalArea = maxOf(1, imgWidth * imgHeight)
        return isTooWide(box, imgWidth) || isTooFlat(box, imgWidth, imgHeight) || isTooThin(box, totalArea)
    }
}
