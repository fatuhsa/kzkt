# Changelog

All notable changes to KZKT are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v1.25.1.13] - 2026-08-05

### Added

- **Full KZKT Rebrand**: Complete application rebranding to **KZKT** (`com.kzkt.app`), redesigned README layout, and aligned UI theme across all screens.
- **KZKT App Launcher Icon Redesign**: Updated app launcher icon across all screen densities (`mdpi` to `xxxhdpi`) featuring stylized handwritten ink-brush `kzkt` lettermark on a clean white background.
- **Consolidated Provider Configuration Card**: Dedicated per-provider card that isolates API Key, Base URL, Model Selection, and Model Detection for the selected provider.
- **Per-Provider Base URL Customization**: Individual Base URL settings for Gemini, OpenAI, OpenRouter, Zen, OpenCode Go, and Custom providers with a one-click reset button to default official endpoints.
- **Dynamic API Model Detection for All Providers**: Integrated "Detect Models from API" across all supported providers to dynamically query `/v1/models` and populate available models without fixed hardcoded lists.
- **In-App Manga & PDF Reader**: Fullscreen reader with `HorizontalPager`, pinch-to-zoom, pan, *Original vs. Translated* toggle switch, and live text touch-up editor.
- **Interactive Touch-up Editor**: Live text editing dialog directly accessible from the main translation card and History screen.
- **Verbose Developer Logs Toggle**: Settings switch to toggle between clean progress logging and detailed telemetry mode for technical debugging.
- **"Copy All Logs" Button**: Dedicated copy button on the `LogCard` header to instantly copy full execution logs to the clipboard.
- **Custom API Timeout Slider**: User-adjustable API timeout slider added to Settings.
- **On-Device Local OCR (Google ML Kit)**: Integrated local OCR (Japanese & Latin) to pre-extract text before sending to non-vision LLM endpoints.
- **Reasoning LLMs Support (DeepSeek-R1)**: Automatic stripping of `<think>...</think>` tags and extended 90s read timeout for reasoning models.
- **Expanded Multi-Provider Support**: Standalone text translation API support for `ZenProvider`, `OpenCodeGoProvider`, and `OpenRouterProvider`.
- **Android CI Workflow**: Added GitHub Actions CI configuration (`JDK 17`, `assembleDebug`).

### Changed

- **Optimized Default Settings**: Updated application default configuration to 30 bubbles/request (`maxBubblesPerRequest = 30`), 2.0s minimum delay (`minRequestDelay = 2.0s`), and 30s request timeout (`customTimeoutSec = 30s`).
- **Clean Settings UI Layout**: Removed redundant stacked API Key lists and duplicate Base URL sections for a streamlined, clutter-free configuration screen.

- **Background Translation Service**: Runs the translation pipeline inside a Foreground Service with dynamic status bar progress notifications and automatic retry timers.
- **PDF Memory Optimization (6-Page Grouping)**: PDF page extraction and processing grouped into 6-page chunks with eager bitmap recycling to cap peak RAM consumption.
- **HTTP Connection Pooling & Fast Request Delay**: Enabled persistent HTTP connection pooling and reduced inter-request delay to 0.1s for ultra-fast text translation.
- **Scoped Storage & MediaStore**: Direct output routing to the public `/Download/KZKT/` folder via MediaStore API for Android 10+ compatibility.
- **Git LFS Model Tracking**: Configured Git LFS to track the encrypted YOLO ONNX model file (`kzkt.dat`).

### Fixed & Performance

- **PDF Fault Tolerance**: PDF processing continues through subsequent pages even if individual batches or pages encounter network/API errors.
- **Local OCR Early Abort**: Automatically aborts remaining local OCR batches early if 2 consecutive batches fail across all configured providers.
- **Custom Provider Endpoint Fixes**: Eliminated duplicate `/v1/v1` path bug and aligned CustomProvider payload structure with standard OpenAI Chat API specs.
- **Unblocked Reader Gestures**: Replaced `pointerInput` with `combinedClickable` in the image viewer to allow unblocked `HorizontalPager` swipes.
- **120 FPS Scrolling Optimization**: Hoisted History state into `StateFlow` and cached `SimpleDateFormat` instances to eliminate scroll frame drops.
- **Tolerant Streaming JSON Parser**: Fallback `JsonReader` streaming parser to handle duplicate keys in LLM JSON responses without failing the batch.
- **Background YOLO Initialization**: Offloaded ONNX model decryption and loading to `Dispatchers.IO` to keep the UI main thread responsive at startup.
- **MediaStore Output Fixes**: Resolved `IllegalArgumentException` when saving standalone image outputs on Android 10+.

## [v1.0.0] - 2026-07-xx

### Added

- **In-App Fullscreen Image Viewer** dengan gestur pinch-to-zoom & pan (`a6e06d5`).
- **Quick Action buttons** untuk buka hasil terjemahan langsung di System Gallery atau share via sosial media (`a6e06d5`).
- **Official CYPY app launcher icon** di semua kepadatan layar Android (mdpi hingga xxxhdpi) (`bb4d524`).

### Fixed

- **Translation cancellation delay**: menekan Cancel sekarang menghentikan coroutine dan request jaringan instan — tidak lagi nunggu batch selesai (`8470ccb`).
- **Custom LLM remote endpoint compatibility**: memaksa `stream: false` dan menambah parsing JSON dinamis untuk Ollama, LM Studio, vLLM, serta tunnel Cloudflare/Ngrok (`d11b068`).
- **PhotoPicker synthetic path crash pada Android 13+**: routing file output langsung ke publik `/Download/CYPY/` (`51f330c`).
- **OpenCV JNI `JNIEnv` library loading issue pada release build**: menggunakan uncompressed legacy packaging (`51f330c`).
- **JSON parsing errors pada response LLM non-standar**: pakai lenient Gson parsing (`b275190` — perbaikan lebih lanjut di [Unreleased] di atas).
- **Rilis native Mat/ONNX resources**, 3-stage YOLO cascade, bubble-sized overlay (`7dea054`).
- **PDF input/output** via built-in `PdfRenderer`/`PdfDocument`, shared render path (`9911db9`).
- **Tema warna** diganti dari ungu ke Light Blue (Sky Blue) modern (`4c8a657`).

---

*Catatan: Riwayat penuh lihat `git log --oneline`.*