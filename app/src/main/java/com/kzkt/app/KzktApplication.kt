package com.kzkt.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class KzktApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Load native library explicitly (prior to initLocal)
        try {
            System.loadLibrary("opencv_java4")
            Log.d("KZKT", "Native opencv_java4 loaded via System.loadLibrary")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("KZKT", "CRITICAL: System.loadLibrary opencv_java4 failed: ${e.message}", e)
        }

        // 2. Initialize OpenCV JNI bindings (registers Mat.n_Mat(), etc.)
        if (OpenCVLoader.initLocal()) {
            Log.d("KZKT", "OpenCV JNI initialized via initLocal()")
        } else {
            Log.e("KZKT", "OpenCV initLocal() returned false")
        }

        // 3. One-time migration: encrypt legacy plaintext API keys (idempotent).
        appScope.launch {
            try {
                com.kzkt.app.data
                    .SettingsRepository(this@KzktApplication)
                    .migrateLegacyApiKeys()
            } catch (e: Exception) {
                Log.e("KZKT", "API key migration failed: ${e.message}")
            }
        }
    }

    companion object {
        lateinit var instance: KzktApplication
            private set

        @Volatile
        var yolo: com.kzkt.app.core.YoloOnnx? = null

        @Volatile
        var textRenderer: com.kzkt.app.core.TextRenderer? = null
    }
}
