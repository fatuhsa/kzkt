# Changelog

All notable changes to KZKT are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v1.25.1.22] - 2026-08-07

### Added

- **Batch Edit in Touch-up Editor**: New 🎨 button opens a Batch Edit dialog — Find & Replace text across every bubble, and apply Bold / Italic / Alignment / Font Size to all bubbles at once.
- **In-Provider Model Failover**: New `ProviderFactory` builds the fallback chain — if the primary model fails or is rate-limited, alternate models of the *same* provider are tried first, then the other configured providers.
- **CBZ Export Preserves Folder Structure**: `createCbz` now writes entries relative to the deepest common parent directory, keeping chapter/folder layout intact inside the archive.
- **Share Multiple Images**: `ACTION_SEND_MULTIPLE` intent-filter + `ClipData` fallback in `MainActivity`, so the app accepts multi-image shares from galleries/file managers.
- **"Test API Key & Connection" Button**: Settings health-check that sends one tiny request through the selected provider and reports latency/error inline — validates what you are *typing*, not the debounced saved value.
- **Pick Folder Input**: SAF tree picker that recursively imports every image inside a folder (sorted), opening whole manga chapters in one tap.
- **Themed App Icon**: Adaptive icon + `anydpi-v33` monochrome layer (Material You themed icon on Android 13+).
- **Translate Sound Effects (SFX) Mode**: Settings toggle that instructs the LLM to translate onomatopoeia (ドドド → "DOKO DOKO") instead of skipping them — applied to both vision-LLM and Local OCR paths.
- **Full Backup & Restore**: New `BackupManager` exports settings, glossary, history and the translation cache into one JSON file, shareable via KDE Connect / cloud; restore overwrites everything with a confirmation dialog.
- **Auto-Detect Local OCR Script**: Removed the Latin/Japanese script selector — a single bundled ML Kit model (`gocrjapanese_and_latin`) now reads **both** Japanese and Latin text automatically.

### Changed

- **Release Signing Setup**: `app/build.gradle.kts` reads a git-ignored `keystore.properties` for a per-developer custom keystore, falling back to the local debug keystore when absent. `assembleRelease` now outputs a directly-signed `app-release.apk`.
- **Build Guide**: New `BUILD_RELEASE.md` (English) covering debug builds, release builds with custom keystores, signature verification and troubleshooting; `keystore.properties.example` added.
- **Repository Hygiene**: `.gitignore` rewritten (build outputs, keystores, IDE and local files); removed ~104 MB of unneeded tracked files (OpenCV test binaries, OpenCV javadoc, one-shot icon script, Python venv ignored).

### Fixed

- **Release crash — R8 + WorkManager**: `NoSuchMethodException: WorkDatabase_Impl.<init>` on every launch (v1.25.1.15). Fixed with Room `-keep <init>()` rules.
- **Release crash — Android 16 FGS**: `InvalidForegroundServiceTypeException: Starting FGS with type none` when pressing Translate (v1.25.1.17–18). Root cause was WorkManager 2.10.0 forwarding a `type=0` from the 2-arg `ForegroundInfo` — fixed with an explicit `FOREGROUND_SERVICE_TYPE_DATA_SYNC` in `ForegroundInfo` plus the manifest declaration.
- **Release crash — ML Kit two-client NPE**: `NullPointerException` inside `com.google.mlkit.vision.text.internal` (de-obfuscated via R8 mapping) when a second recognizer type was created. Now only ONE ML Kit client (Japanese + Latin model) is ever created, plus ML Kit keep rules in ProGuard.
- **History date-range filter did nothing**: The "Custom Date" button set state but never rendered a picker — a `DateRangePicker` dialog now opens (wrapped in a `Dialog`+`Card`, since `DateRangePickerDialog` no longer exists in material3 1.5.0-alpha25).
- **Filename collisions in `copyUriToCache`**: Two sources with the same display name (e.g. `001.jpg` from different folders during folder import / multi-share) silently overwrote each other — now deduplicated with numeric suffixes (`001_1.jpg`, …).
- **Translation memory ignored PDF/batch runs**: `processImageBatch` never checked nor saved the local cache — identical bubbles across pages (and PDFs) are now served free from the cache, mirroring the single-image path.
- **Custom provider had no failover**: `createFallbackProviders` now includes the Custom provider (when a base URL is configured).
- **History could point at deleted files**: when MediaStore does not expose a real `_data` path, the output is parked in app-external storage instead of returning the cache path that is deleted right after.
- **OCR all-bubbles-empty confusion**: ML Kit errors are now surfaced in the log (`ML Kit error: …`) instead of a misleading "(No text recognized)".

## [v1.25.1.14] - 2026-08-07

### Added

- **Custom Font Importer**: Integrated a custom font selection dialog supporting `.ttf` and `.otf` font imports with persistent state across translations.
- **EPUB Support**: Added support for selecting `.epub` comic/manga archives alongside CBZ and ZIP files directly from the UI.
- **CBZ Filename Retention**: The Manga Reader CBZ exporter now perfectly retains the original directory structures and CJK filenames instead of flattening into sequential numbers.
- **Webtoon Reader Mode**: Added vertical scrolling mode toggle (`LazyColumn`) in `MangaReaderDialog` for seamless Webtoon and Manhwa reading.
- **Smart Image Upscaler**: OpenCV Bicubic interpolation + Unsharp Masking enhancement filter to double resolution and sharpen low-res scan text before OCR/LLM detection.
- **Provider Cache Invalidation**: Automatic translation cache invalidation when switching LLM models or providers to prevent stale translations.
- **Undo/Redo in Editor**: Added state history stack to the Interactive Touch-up Editor, allowing users to Undo and Redo bubble edits seamlessly.
- **History Filters**: Added Language and Date Range filters to the History Screen for easier translation management.
- **Side-by-side View**: Introduced a toggleable split-screen mode in MangaReaderDialog to compare the original comic and the translated version side-by-side.

### Changed

- **Background Translation Resilience**: Migrated translation service from ForegroundService to WorkManager (`TranslationWorker`) to persist translation jobs and prevent OS from killing them prematurely.
- **Redesigned KZKT Icon**: Cleaned up the app icon to feature a large 'K' on the left and stacked 'z' and 't' on the right, maintaining a minimalistic white-on-transparent design.
- **Refined Reset Settings**: Relocated the "Reset Advanced Settings" action into the Advanced card with a proper confirmation dialog, explicitly excluding sensitive API credentials.
- **Long Filename UI**: Implemented text truncation (ellipsis) for selected filenames in the MainScreen to prevent UI push-down on lengthy manga titles.

### Fixed

- **Android Archive Extraction Bug**: Swapped `ZipInputStream` for `ZipFile` to bypass a core Android extraction bug affecting Data Descriptors, permanently fixing 0-byte extracted images.
- **CJK Filename Destruction**: Removed aggressive regex sanitization that was corrupting Japanese, Chinese, and Korean filenames during extraction and causing page overwrites.

## [v1.25.1.13] - 2026-08-01 → 2026-08-06

### 🎨 Major UI Overhaul (2026-08-02)

Directly following the documentation commit that outlined the app's key features — **PDF support, 3-stage YOLO cascade, and zero memory leaks** (`b201476`) — the whole interface was rebuilt around modern Material 3 (`e15eea9`, +2556/−671 lines across 20 files):

- **Modern Material 3 theme**: Material You seed-color theming (MaterialKolor) + M3 typography (`Theme.kt`/`Type.kt`), replacing the old custom theme.
- **Bottom navigation with History tab**: `MainScreen` rebuilt as a 3-tab bottom nav — **Translate / History / Settings** — using `navigation-compose`.
- **New History screen**: `HistoryRepository` (DataStore-backed JSON) + `HistoryScreen` with search, provider filter, and tap-to-preview bottom sheet.
- **Rebuilt component library**: `BottomSheet`, `Material3SettingsGroup`, `EmptyPlaceholder`, `Menu`, `IconButton`, `ChipsRow` — reused across all screens.
- **Settings redesigned**: `Material3SettingsGroup` cards with expandable sections and accent-color presets.
- **Fixed translation lag**: all snapshot-state writes marshaled to the main thread, non-animated `scrollToItem`, `derivedStateOf` scoping, keyed list items — eliminating jank while a translation runs in the background.
- **All UI strings translated to English**.

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
- **Smart OCR Typo Correction**: Prompt instructs the LLM to auto-correct OCR noise/typos using context before translating Local OCR text.
- **Pencil Edit on Every Reader Page**: Touch-up editing enabled on all in-app reader pages (Main preview + History), not just fresh results.
- **Android CI Workflow**: Added GitHub Actions CI configuration (`JDK 17`, `assembleDebug`).
- **Git LFS**: YOLO ONNX model (`kzkt.dat`) tracked via Git LFS with README prerequisites.

### Changed

- **Optimized Default Settings**: Updated application default configuration to 30 bubbles/request (`maxBubblesPerRequest = 30`), 2.0s minimum delay (`minRequestDelay = 2.0s`), and 30s request timeout (`customTimeoutSec = 30s`).
- **Clean Settings UI Layout**: Removed redundant stacked API Key lists and duplicate Base URL sections for a streamlined, clutter-free configuration screen.
- **Background Translation Service**: Runs the translation pipeline inside a Foreground Service with dynamic status bar progress notifications and automatic retry timers.
- **PDF Memory Optimization (6-Page Grouping)**: PDF page extraction and processing grouped into 6-page chunks with eager bitmap recycling to cap peak RAM consumption.
- **HTTP Connection Pooling & Fast Request Delay**: Enabled persistent HTTP connection pooling and reduced inter-request delay to 0.1s for ultra-fast text translation.
- **Scoped Storage & MediaStore**: Direct output routing to the public `/Download/KZKT/` folder via MediaStore API for Android 10+ compatibility.
- **Core Audit Cleanup**: Removed dead code, unified the OpenAI-compatible providers, fixed the retry cache, and corrected the Gemini base URL handling.
- **History/Settings Performance**: Eliminated recomposition storms and main-thread disk I/O that caused frame drops while scrolling.

### Fixed & Performance

- **PDF Fault Tolerance**: PDF processing continues through subsequent pages even if individual batches or pages encounter network/API errors.
- **Local OCR Early Abort**: Automatically aborts remaining local OCR batches early if 2 consecutive batches fail across all configured providers.
- **Local OCR Micro-Batching**: Text requests chunked to max 6 bubbles per batch to prevent LLM token timeouts in PDF mode.
- **Custom Provider Endpoint Fixes**: Eliminated duplicate `/v1/v1` path bug and aligned CustomProvider payload structure with standard OpenAI Chat API specs.
- **Unblocked Reader Gestures**: Replaced `pointerInput` with `combinedClickable` in the image viewer to allow unblocked `HorizontalPager` swipes.
- **120 FPS Scrolling Optimization**: Hoisted History state into `StateFlow` and cached `SimpleDateFormat` instances to eliminate scroll frame drops.
- **Tolerant Streaming JSON Parser**: Fallback `JsonReader` streaming parser to handle duplicate keys in LLM JSON responses without failing the batch.
- **Background YOLO Initialization**: Offloaded ONNX model decryption and loading to `Dispatchers.IO` to keep the UI main thread responsive at startup.
- **YOLO Header Validation**: Corrected ONNX header validation so the bundled model actually loads on release builds.
- **MediaStore Output Fixes**: Resolved `IllegalArgumentException` when saving standalone image outputs on Android 10+ (plus an ENOENT fallback resolution).

## [v1.0.0] - 2026-07-xx

### Added

- **In-App Fullscreen Image Viewer** with pinch-to-zoom & pan gestures (`a6e06d5`).
- **Quick Action buttons** to open translated results in the System Gallery or share via social media (`a6e06d5`).
- **Official app launcher icon** at every Android screen density (mdpi to xxxhdpi) (`bb4d524`).

### Fixed

- **Translation cancellation delay**: pressing Cancel now stops the coroutine and network request instantly — no longer waits for the batch to finish (`8470ccb`).
- **Custom LLM remote endpoint compatibility**: forced `stream: false` and added dynamic JSON parsing for Ollama, LM Studio, vLLM, and Cloudflare/Ngrok tunnels (`d11b068`).
- **PhotoPicker synthetic-path crash on Android 13+**: output files routed directly to public `/Download/` (`51f330c`).
- **OpenCV JNI `JNIEnv` library loading on release builds**: uses uncompressed legacy packaging (`51f330c`).
- **JSON parsing errors on non-standard LLM responses**: lenient Gson parsing (`b275190`).
- **Released native Mat/ONNX resources**, 3-stage YOLO cascade, bubble-sized overlay (`7dea054`).
- **PDF input/output** via built-in `PdfRenderer`/`PdfDocument`, shared render path (`9911db9`).
- **Theme color** changed from purple to a modern Light Blue (Sky Blue) (`4c8a657`).

---

*Full history: see `git log --oneline`.*
