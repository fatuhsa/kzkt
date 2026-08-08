import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ── Release signing ──────────────────────────────────────────────
// Mendukung keystore custom per-developer via file keystore.properties
// (TIDAK di-commit — sudah di .gitignore). Contoh: lihat keystore.properties.example
// dan panduan lengkap di BUILD_RELEASE.md.
// Jika keystore.properties tidak ada, fallback ke debug keystore lokal
// (~/.android/debug.keystore) sehingga `./gradlew assembleRelease` tetap
// menghasilkan APK release yang bisa diinstall.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

// ── Per-ABI APK (ala Komikku) ────────────────────────────────────
// Build menghasilkan 1 APK per chip + 1 universal. CI matrix dapat
// membatasi build ke 1 ABI dengan -PabiFilter=arm64-v8a.
// Tanpa flag = semua ABI + universal (perilaku default seperti sebelumnya).
val abiFilter = (project.findProperty("abiFilter") as String?)?.takeIf {
    it.isNotBlank() && it != "all"
}

android {
    namespace = "com.kzkt.app"
    compileSdk = 37

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            } else {
                val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")
                if (debugKeystore.exists()) {
                    storeFile = debugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    defaultConfig {
        applicationId = "com.kzkt.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1250122
        versionName = "1.25.1.22"

        ndk {
            // AGP melarang ndk.abiFilters di-set bersamaan dengan splits.abi.
            // -PabiFilter=arm64-v8a → splits yang mengontrol (1 ABI saja).
            // tanpa flag → set abiFilters semua ABI (perilaku default).
            if (abiFilter == null) {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            if (abiFilter != null) {
                include(abiFilter)
                isUniversalApk = false
            } else {
                include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
                isUniversalApk = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // APK release langsung ter-sign saat build (tanpa langkah manual).
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AGP 9.0 built-in Kotlin
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("../opencv/native/libs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    // Compose BOM (androidx) — material3 overridden to the same 1.5.0-alpha25 Metrolist uses
    val composeBom = platform("androidx.compose:compose-bom:2026.01.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3:1.5.0-alpha25")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Theme: MaterialKolor (seed-color dynamic theming, same as Metrolist)
    implementation("com.materialkolor:material-kolor:5.0.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Activity + Navigation
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // OpenCV Android SDK (local module)
    implementation(project(":opencv"))

    // ONNX Runtime (includes NNAPI support)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.0")

    // HTTP + JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore (settings)
    implementation("androidx.datastore:datastore-preferences:1.1.2")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Coil (image loading)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Google ML Kit Text Recognition (Latin + Japanese)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0")
}
