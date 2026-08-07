# ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# OpenCV
-keep class org.opencv.** { *; }
-keepclasseswithmembernames class org.opencv.** {
    native <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# ── ML Kit OCR ──────────────────────────────────────────────────
# ML Kit creates its text-recognition clients/components reflectively and keeps
# internal state in com.google.mlkit.vision.text.internal.*. R8 full-mode renaming
# those internals can produce NPEs at runtime ("Attempt to read from field ... on a
# null object reference" inside TextRecognition.getClient). Keep the whole SDK and
# the generated bundled-model classes so client creation stays intact.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_bundled_common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text_common.**

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }

# Keep our model classes
-keep class com.kzkt.app.core.** { *; }

# HistoryEntry is serialized/deserialized with Gson by reflection (HistoryRepository).
# Without this rule R8 renames its fields, which would corrupt saved history JSON
# across builds (and empty the list on launch after an update).
-keep class com.kzkt.app.data.HistoryEntry { *; }

# ── WorkManager + Room ─────────────────────────────────────────────
# WorkManager initializes a Room database (WorkDatabase_Impl) reflectively at app
# startup via androidx.startup. R8 full-mode strips the generated no-arg
# constructor because it is never called directly → NoSuchMethodException
# ("androidx.work.impl.WorkDatabase_Impl.<init> []") on every launch.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.** { *; }
-dontwarn androidx.work.**
