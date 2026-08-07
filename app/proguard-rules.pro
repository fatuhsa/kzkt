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

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }

# Keep our model classes
-keep class com.kzkt.app.core.** { *; }

# HistoryEntry is serialized/deserialized with Gson by reflection (HistoryRepository).
# Without this rule R8 renames its fields, which would corrupt saved history JSON
# across builds (and empty the list on launch after an update).
-keep class com.kzkt.app.data.HistoryEntry { *; }
