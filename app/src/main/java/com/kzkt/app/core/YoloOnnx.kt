package com.kzkt.app.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kzkt.app.util.KLog
import java.io.File
import java.nio.FloatBuffer
import java.util.UUID

/**
 * YOLOv8 ONNX inference via ONNX Runtime Android.
 */
class YoloOnnx(
    private val context: Context,
    private val modelFilename: String = "kzkt.dat",
    private val confThreshold: Double = 0.25,
    private val iouThreshold: Double = 0.45,
) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    data class Detection(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
    )

    fun initialize(): Boolean {
        try {
            Log.d("KZKT/YOLO", "=== INIT START ===")
            Log.d("KZKT/YOLO", "confThreshold=$confThreshold iouThreshold=$iouThreshold")

            var onnxFile = decryptModel()
            if (onnxFile == null || !onnxFile.exists()) {
                Log.e("KZKT/YOLO", "decryptModel returned null or file not found")
                return false
            }
            Log.d("KZKT/YOLO", "ONNX file ready: ${onnxFile.absolutePath} (${onnxFile.length()} bytes)")

            Log.d("KZKT/YOLO", "Getting OrtEnvironment...")
            ortEnv = OrtEnvironment.getEnvironment()
            Log.d("KZKT/YOLO", "OrtEnvironment OK")

            Log.d("KZKT/YOLO", "Creating SessionOptions...")
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            val env =
                ortEnv ?: run {
                    Log.e("KZKT/YOLO", "OrtEnvironment was not initialized")
                    return false
                }
            Log.d("KZKT/YOLO", "Creating session from ${onnxFile.absolutePath}...")
            try {
                ortSession = env.createSession(onnxFile.absolutePath, opts)
            } catch (e: Exception) {
                // The cached file can pass the cheap 0x08 header check yet still be
                // truncated/corrupt (e.g. an interrupted copy), and ONNX Runtime may
                // surface that as any exception type. Nuke the cache and re-decrypt
                // from assets once, then retry session creation.
                Log.w("KZKT/YOLO", "Session creation failed (${e.message}) — deleting cache & re-decrypting once")
                File(context.cacheDir, "kzkt.onnx").delete()
                onnxFile = decryptModel()
                if (onnxFile == null || !onnxFile.exists()) {
                    Log.e("KZKT/YOLO", "Re-decrypt after cache failure returned no usable file")
                    return false
                }
                ortSession = env.createSession(onnxFile.absolutePath, opts)
            }
            Log.d("KZKT/YOLO", "Session created OK")

            // Log input/output info
            val session =
                ortSession ?: run {
                    Log.e("KZKT/YOLO", "Session was not created")
                    return false
                }
            val inputInfo = session.inputNames
            val outputInfo = session.outputNames
            Log.d("KZKT/YOLO", "Inputs: $inputInfo")
            Log.d("KZKT/YOLO", "Outputs: $outputInfo")

            Log.d("KZKT/YOLO", "=== INIT SUCCESS ===")
            return true
        } catch (e: UnsatisfiedLinkError) {
            Log.e("KZKT/YOLO", "Native library not loaded: ${e.message}", e)
            return false
        } catch (e: ai.onnxruntime.OrtException) {
            Log.e("KZKT/YOLO", "ONNX Runtime error: ${e.message}", e)
            return false
        } catch (e: Exception) {
            Log.e("KZKT/YOLO", "Failed: ${e.message}", e)
            return false
        }
    }

    private fun decryptModel(): File? {
        val onnxDirect = File(context.cacheDir, "kzkt.onnx")

        // Check cached file validity — delete if corrupt. Only the first byte is
        // read here instead of the whole ~100 MB file.
        if (onnxDirect.exists()) {
            if (looksLikeOnnx(onnxDirect)) {
                Log.d("KZKT/YOLO", "Using cached ONNX: ${onnxDirect.length()} bytes (header OK)")
                return onnxDirect
            } else {
                Log.w("KZKT/YOLO", "Cached file is not a valid ONNX protobuf — deleting corrupt cache")
                onnxDirect.delete()
            }
        }

        return try {
            Log.d("KZKT/YOLO", "Decrypting assets/models/$modelFilename → cache (streamed)...")
            val key = Constants.MODEL_DECRYPT_KEY
            val tag = UUID.randomUUID().toString().take(8)
            val modelFile = File(context.cacheDir, "kzkt_$tag.onnx")

            // Stream XOR decryption chunk-by-chunk instead of loading the whole model
            // into memory at once — avoids a large RAM spike during startup.
            val inputStream = context.assets.open("models/$modelFilename")
            try {
                java.io.FileOutputStream(modelFile).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = inputStream.read(buffer)
                        if (read <= 0) break
                        for (i in 0 until read) {
                            buffer[i] = (buffer[i].toInt() xor key).toByte()
                        }
                        out.write(buffer, 0, read)
                        total += read
                    }
                    Log.d("KZKT/YOLO", "Decrypted $total bytes")
                }
            } finally {
                inputStream.close()
            }

            // Verify it looks like an ONNX protobuf (first byte 0x08 = field 1
            // ir_version). Standard ONNX models never start with the string "ONNX".
            if (!looksLikeOnnx(modelFile)) {
                Log.w("KZKT/YOLO", "Decrypted file is not a valid ONNX protobuf — key may be wrong")
                return null
            }
            Log.d("KZKT/YOLO", "Decrypted file passes ONNX protobuf check")

            modelFile.copyTo(onnxDirect, overwrite = true)
            Log.d("KZKT/YOLO", "Written to ${modelFile.absolutePath}")

            // Clean up stale temp files from interrupted previous runs (keep the cached one)
            try {
                context.cacheDir.listFiles()?.forEach { f ->
                    val name = f.name
                    if (name.startsWith("kzkt_") && name.endsWith(".onnx") && name != modelFile.name) {
                        f.delete()
                    }
                }
            } catch (e: Exception) {
                KLog.w("KZKT/YOLO", "Failed to clean stale temp ONNX files: ${e.message}")
            }

            modelFile
        } catch (e: Exception) {
            Log.e("KZKT/YOLO", "Decryption failed: ${e.message}", e)
            null
        }
    }

    /**
     * True if the file starts with byte 0x08 — the protobuf wire marker for
     * ONNX ModelProto field 1 (ir_version, varint). Standard ONNX models begin
     * with this byte; they never begin with the literal string "ONNX".
     */
    private fun looksLikeOnnx(file: File): Boolean {
        val bytes = ByteArray(1)
        return try {
            file.inputStream().use { ins ->
                ins.read(bytes) > 0
            }
            (bytes[0].toInt() and 0xFF) == 0x08
        } catch (e: Exception) {
            KLog.w("KZKT/YOLO", "Failed to read model header byte: ${e.message}")
            false
        }
    }

    /**
     * Preprocessed model input (resized + padded 640×640 tensor with letterbox
     * ratios/paddings). Computed ONCE per page and reused across the 3-stage
     * confidence cascade — the input tensor is identical for every stage, only
     * the confidence/IOU thresholds change.
     */
    data class PreparedInput(
        val buffer: FloatBuffer,
        val ratios: DoubleArray,
        val paddings: DoubleArray,
    )

    fun prepareInput(bitmap: Bitmap): PreparedInput {
        val h = bitmap.height.toDouble()
        val w = bitmap.width.toDouble()
        val targetSize = Constants.YOLO_INPUT_SIZE.toDouble()

        val scale = minOf(targetSize / h, targetSize / w)
        val newW = (w * scale).toInt()
        val newH = (h * scale).toInt()

        val dw = ((targetSize - newW) / 2.0).toInt()
        val dh = ((targetSize - newH) / 2.0).toInt()

        val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        val padded = Bitmap.createBitmap(Constants.YOLO_INPUT_SIZE, Constants.YOLO_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(padded)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))
        canvas.drawBitmap(resized, dw.toFloat(), dh.toFloat(), null)

        val pixels = IntArray(Constants.YOLO_INPUT_SIZE * Constants.YOLO_INPUT_SIZE)
        padded.getPixels(pixels, 0, Constants.YOLO_INPUT_SIZE, 0, 0, Constants.YOLO_INPUT_SIZE, Constants.YOLO_INPUT_SIZE)
        padded.recycle()
        if (resized != bitmap) resized.recycle()

        val area = Constants.YOLO_INPUT_SIZE * Constants.YOLO_INPUT_SIZE
        val inputBuffer =
            java.nio.ByteBuffer
                .allocateDirect(area * 3 * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer()
        for (i in pixels.indices) {
            val pixel = pixels[i]
            inputBuffer.put(i, ((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.put(area + i, ((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.put(2 * area + i, (pixel and 0xFF) / 255.0f)
        }

        return PreparedInput(inputBuffer, doubleArrayOf(scale, scale), doubleArrayOf(dw.toDouble(), dh.toDouble()))
    }

    fun predict(
        bitmap: Bitmap,
        confThreshold: Double = this.confThreshold,
        iouThreshold: Double = this.iouThreshold,
        prepared: PreparedInput? = null,
    ): List<Detection> {
        val env = ortEnv ?: throw IllegalStateException("ONNX Runtime not initialized")
        val session = ortSession ?: throw IllegalStateException("Model not loaded")

        val preparedInput = prepared ?: prepareInput(bitmap)
        val (buffer, ratios, paddings) = preparedInput
        val (dw, dh) = paddings[0] to paddings[1]
        val (ratioW, ratioH) = ratios[0] to ratios[1]

        val inputShape = longArrayOf(1, 3, Constants.YOLO_INPUT_SIZE.toLong(), Constants.YOLO_INPUT_SIZE.toLong())
        val inputName = session.inputNames.iterator().next()

        val outputData: FloatArray
        OnnxTensor.createTensor(env, buffer, inputShape).use { inputTensor ->
            session.run(mapOf(inputName to inputTensor)).use { result ->
                val onnxVal = result.get(0) as OnnxTensor
                val fb = onnxVal.floatBuffer
                outputData = FloatArray(fb.capacity())
                fb.get(outputData)
            }
        }
        val bufSize = outputData.size
        Log.d("KZKT/YOLO", "Output buffer size: $bufSize floats")

        // Determine grid dimensions from buffer size
        // Expected: 84 * 8400 = 705600. Could also be 8400 * 5 = 42000 (old model) or 8400 * 6 = 50400
        val grid: Int
        val channels: Int
        when {
            bufSize % 8400 == 0 -> {
                grid = 8400
                channels = bufSize / 8400
            }
            bufSize % 840 == 0 -> {
                grid = 840
                channels = bufSize / 840
            }
            else -> {
                Log.e("KZKT/YOLO", "Unknown output shape - buffer size $bufSize not divisible by 8400 or 840")
                return emptyList()
            }
        }
        Log.d("KZKT/YOLO", "Output shape: $channels x $grid (expected 84 x 8400)")

        // If it's transposed (grid, channels), transpose index access
        // Normal ONNX output is (1, 84, 8400) = data[c * grid + g]
        // Transposed would be (1, 8400, 84) = data[g * channels + c]
        // Detect by looking at conf value at position [4 * grid + 0] vs [0 * channels + 4]
        val sampleConfDirect = if (bufSize > 4 * grid) outputData[4 * grid] else -1f
        val sampleConfTransposed = if (bufSize > 4) outputData[4] else -1f
        val confIsValid = sampleConfDirect in 0.01f..1.0f
        val transposedConfIsValid = sampleConfTransposed in 0.01f..1.0f

        val transposed: Boolean
        if (confIsValid && !transposedConfIsValid) {
            transposed = false
        } else if (!confIsValid && transposedConfIsValid) {
            transposed = true
        } else if (confIsValid && transposedConfIsValid) {
            // Both look plausible — check if channel 5+ has reasonable values when accessed transposed
            transposed = outputData[5] in 0.0f..1.0f && outputData[4 * grid + 5] !in 0.0f..1.0f
        } else {
            transposed = false
        }

        if (transposed) {
            Log.d("KZKT/YOLO", "Detected transposed output (grid x channels). Adapting lookup.")
        }

        val detections = mutableListOf<Detection>()
        val boxes = mutableListOf<IntArray>()
        val confidences = mutableListOf<Float>()

        val outH = bitmap.height.toDouble()
        val outW = bitmap.width.toDouble()

        for (g in 0 until grid) {
            val conf: Float
            val xc: Double
            val yc: Double
            val boxW: Double
            val boxH: Double

            if (transposed) {
                conf = outputData[g * channels + 4]
                xc = outputData[g * channels + 0].toDouble()
                yc = outputData[g * channels + 1].toDouble()
                boxW = outputData[g * channels + 2].toDouble()
                boxH = outputData[g * channels + 3].toDouble()
            } else {
                conf = outputData[4 * grid + g]
                xc = outputData[0 * grid + g].toDouble()
                yc = outputData[1 * grid + g].toDouble()
                boxW = outputData[2 * grid + g].toDouble()
                boxH = outputData[3 * grid + g].toDouble()
            }

            if (conf < confThreshold) continue

            var x1 = (xc - boxW / 2.0 - dw) / ratioW
            var y1 = (yc - boxH / 2.0 - dh) / ratioH
            var x2 = (xc + boxW / 2.0 - dw) / ratioW
            var y2 = (yc + boxH / 2.0 - dh) / ratioH

            x1 = x1.coerceIn(0.0, outW)
            y1 = y1.coerceIn(0.0, outH)
            x2 = x2.coerceIn(0.0, outW)
            y2 = y2.coerceIn(0.0, outH)

            val bw = (x2 - x1).toInt()
            val bh = (y2 - y1).toInt()
            if (bw <= 0 || bh <= 0) continue

            boxes.add(intArrayOf(x1.toInt(), y1.toInt(), bw, bh))
            confidences.add(conf)
        }

        Log.d("KZKT/YOLO", "Pre-NMS: ${boxes.size} boxes, top conf=${confidences.maxOrNull()}")

        if (boxes.isNotEmpty()) {
            val order = confidences.indices.sortedByDescending { confidences[it] }
            val keep = mutableListOf<Int>()

            for (i in order) {
                val keepFlag =
                    keep.all { k ->
                        val a = boxes[i]
                        val b = boxes[k]
                        val ix1 = maxOf(a[0], b[0])
                        val iy1 = maxOf(a[1], b[1])
                        val ix2 = minOf(a[0] + a[2], b[0] + b[2])
                        val iy2 = minOf(a[1] + a[3], b[1] + b[3])
                        val inter = maxOf(0, ix2 - ix1) * maxOf(0, iy2 - iy1)
                        val iou = inter.toDouble() / (a[2] * a[3] + b[2] * b[3] - inter)
                        iou <= iouThreshold
                    }
                if (keepFlag) keep.add(i)
            }

            for (i in keep) {
                val b = boxes[i]
                detections.add(
                    Detection(
                        b[0],
                        b[1],
                        (b[0] + b[2]).coerceAtMost(bitmap.width),
                        (b[1] + b[3]).coerceAtMost(bitmap.height),
                    ),
                )
            }
        }

        Log.d("KZKT/YOLO", "Post-NMS: ${detections.size} detections")
        return detections
    }

    /**
     * Intentionally NOT called from the app lifecycle: the session is a process-wide
     * singleton (KzktApplication.yolo) also used by the background TranslationWorker.
     * Closing it while a worker runs would kill every subsequent predict() call, and a
     * closed session would never be re-created (KzktApplication.yolo is non-null). The
     * OS reclaims the native memory when the process dies. Kept as a public API for
     * explicit teardown in tests/embedding, but do NOT wire it into the ViewModel.
     */
    fun close() {
        Log.d("KZKT/YOLO", "Closing...")
        try {
            ortSession?.close()
        } catch (e: Exception) {
            KLog.w("KZKT/YOLO", "Session close failed: ${e.message}")
        }
        ortEnv?.close()
    }
}
