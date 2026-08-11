# Changelog

All notable changes to KZKT are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v1.35.0] - 2026-08-11

### Added

- **Retry failed pages from History**: failed translations are now recorded in the History tab with a "Failed" badge and a Retry button. Tapping it re-runs only that source file, and a successful retry replaces the failed entry instead of duplicating it. (The same cleanup applies to retried PDFs.)
- **Retry failed pages from the Translate tab**: after a batch finishes, a "Retry Failed (n)" button appears when any file failed and re-enqueues only the failed pages, leaving the successful ones untouched.
- **Per-file batch status**: every selected file in the Translate tab now shows a live status icon (processing / done / failed) as the batch runs.
- **Reading-position bookmark**: the reader remembers the last-read page per book (grouped chapter/folder). Reopening a book from History or from a batch result resumes where you left off instead of always starting at page 1.
- **Rendered text settings**: choose the translated bubble text color (Auto / White / Black) and a global font scale slider (80%–150%) in Settings — applied to all new translations.
- **JPEG output quality setting**: a new slider in Advanced settings (70–100) controls the compression quality of saved .jpg/.jpeg translations, trading file size against image quality.
- **Multi-select in History with ZIP/PDF export**: the History tab gains a Select mode. Pick several entries, then export them as a single ZIP or PDF (images only, ordered by page name) or delete them in bulk with one Undo action.
- **Immersive fullscreen in the reader**: a new fullscreen toggle hides the system status/navigation bars for distraction-free reading (swipe from an edge to bring them back).

### Changed

- **Real pinch-to-zoom in the reader**: pinch gestures now zoom in/out from any level, not just double-tap — the previous gesture handler only enabled zoom after a double-tap had already zoomed in.
- **More responsive panning while zoomed**: single-finger drags move the page 1.8x faster than the finger, and the pan is clamped so the page can never be pushed fully off-screen.
- **History export saves straight to Downloads/KZKT**: exporting selected pages as ZIP or PDF copies the file into the public Downloads/KZKT folder and shows a confirmation toast — no share sheet anymore (ZIP/CBZ files now get the correct `application/zip` MIME type in MediaStore).
- **Better selection-mode icons**: the History action bar now shows a proper folder-zip icon for ZIP export and an outline delete icon, matching the PDF/file icons used elsewhere.
- Failed History entries now keep their source input path so they can always be retried later.

## [v1.30.4] - 2026-08-11

### Changed

- **4x faster image saving**: translated `.jpg`/`.jpeg` pages are now encoded as JPEG at quality 92 instead of lossless PNG, cutting encode time from ~1.4 s to ~0.33 s on upscaled 2x pages and shrinking output files about 4x (16.6 MB -> 4.3 MB) — measured with a dedicated encode benchmark. `.png`/`.webp` and other extensions keep lossless PNG so the filename extension and gallery MIME type always match.
- **Lower peak memory in batch translation**: the batch pipeline no longer makes a redundant full-resolution copy of every page before rendering — pages are rendered directly from the loaded bitmap, freeing roughly 23-93 MB per page (up to ~280 MB across the 3 concurrent pages) while a batch runs.
- **Local OCR batches are ~50% larger**: the on-device OCR chunk size is raised from 6 to 12 bubbles per LLM request, halving the number of text-translation API calls for OCR mode (the request pacing from the rate limiter is unchanged).
- **No more dead 500 ms wait between OCR batches**: the extra fixed delay after every OCR batch was removed; the rate limiter already enforces the minimum request interval.

### Fixed

- **Unscaled crop bitmaps are now recycled**: the single-image translation path freed the scaled crop copy but let the original-size crop become garbage — it now recycles it immediately, matching the batch path and keeping the memory spike flat on bubble-heavy pages.
- **Potential OpenCV Mat double-release**: when outside-bubble masking is disabled, the same Mat could be released twice (corrupting its reference count). A guard now releases the mask Mat only when it is a separate object.

## [v1.30.3] - 2026-08-11

### Added

- **Instant in-app PDF reader**: translated PDFs now open in a new lazy reader that renders only the pages on screen via Android's built-in `PdfRenderer` — no more waiting for the whole document to be rasterized to disk first. Includes pinch-to-zoom (1x–4x), webtoon mode, a page counter, and sharing the PDF. Wired into both the History tab and the Translate result preview.
- **Save feedback in the touch-up editor**: the Save button now shows a "Saving…" spinner and blocks dismissal while edits are being written, then confirms with a toast — and reports failure honestly instead of silently closing.
- **Persistent theme**: dark mode, pure black, and the accent color are now saved to settings and restored on the next launch (previously they reset at every app start).

### Changed

- **Smart Image Upscaler no longer distorts pages**: PDF page rasterization now caps resolution with one uniform scale factor so the page aspect ratio is always preserved. Previously the 2048px cap was applied to width and height independently, which stretched or squished large pages — most visible when the upscaler doubled translated PDF pages.
- **Saved edits show up immediately**: the reader reloads the edited page from disk right after saving, instead of showing the stale cached image until the page was swiped away and back.
- **Touch-up editing for auto-split (landscape) pages**: bubble metadata from every split part is merged into the recombined page, so the editor works on wide images too. Intermediate bitmaps are now recycled (small memory leak fixed).
- **Local OCR falls back to the vision LLM**: when ML Kit recognizes no text in a chunk, that chunk is sent to the image-capable provider chain instead of being dropped — a page no longer fails outright just because OCR found nothing.

### Fixed

- **Reader crash on app exit during a background translation**: the ViewModel no longer recycles bitmaps the background worker may still be using — the retry cache is now owned and cleared by the worker itself.
- **Webtoon mode stuck (unable to scroll)**: tapping the toolbar no longer hijacks the drag gesture, and each page reserves its aspect-ratio space while decoding so the list scrolls smoothly.
- **History reader showing a single page**: sibling pages with pure-numbered names (`001`, `002`, …) or ` (1)` name collisions are now grouped into one reader session instead of opening one isolated page.
- **Glossary UI jank and lost terms**: glossary file I/O moved off the main thread and mutations are serialized, so rapid add/remove can no longer drop or overwrite existing terms.

## [v1.30.2] - 2026-08-09

### Added

- **Foreground-service download**: the update download now runs in a dedicated foreground service (`dataSync` type, same pattern as the translation worker), so it survives app backgrounding / swipe-from-recents and no longer aborts mid-way. Progress keeps streaming to the notification shade and the dialog reflects the live state when the app is reopened.
- **Speed + ETA in the UI**: the update dialog and notification now show the transferred amount, download speed, and estimated time remaining (e.g. `12.3 / 110 MB · 0.3 MB/s · ~5 min`), reported every 1% or every 500 ms — so slow downloads never look frozen.

### Changed

- **Resumable downloads (HTTP Range)**: partial files are stored under a stable name (`kzkt-update-<version>.apk`) and resumed from the last byte via the `Range` header (server replies `206 Partial Content`). If a transfer is interrupted, the next attempt continues where it left off instead of restarting from zero.
- **Separate download timeout**: the download client now uses a 120-second read timeout instead of the 15-second API timeout, so short network stalls no longer kill the transfer.
- **Automatic retry**: interrupted downloads retry up to 3 times with escalating backoff (2 s / 4 s), each attempt resuming from the partial file. User cancellations are respected and never retried.

## [v1.30.1] - 2026-08-09

### Fixed

- **Startup update check no longer pops up a dialog**: the launch-time auto-check now runs fully in the background and only shows the update dialog when a newer version is actually available (previously a "Checking for updates…" dialog appeared at every app launch). Manual checks from Settings keep their spinner + feedback.
- **Stuck "Checking for updates…" dialog**: when the background check found nothing new (or failed), its state was never reset and the spinner dialog stayed on screen indefinitely — it now clears silently. A dedicated concurrency guard also prevents overlapping auto/manual checks.

## [v1.30.0] - 2026-08-08

### Added

- **Update download notification**: the in-app self-update now mirrors its download progress (percent + progress bar) in the notification shade via a dedicated low-importance channel, so progress stays visible even if the app is backgrounded. No-ops cleanly when notification permission is off.

### Changed

- **Release descriptions now come from `CHANGELOG.md`**: the CI release workflow extracts this version's section from this file as the release body (instead of auto-generating notes from commit history), so every release page reads exactly like the changelog.

### Fixed

- **White update dialog**: the update popup ignored the app theme (rendered with the default light scheme) because it was composed outside `KzktTheme` — it now follows dark/light mode and dynamic Material You colors.
- **Raw markdown in release notes**: the update dialog now renders release-note markdown properly (headers, bullet lists, bold, inline code, links) via a lightweight in-app renderer instead of showing literal `##` text.

## [v1.25.1] - 2026-08-08

### Added

- **Self-Update via GitHub Releases**: `UpdateManager` checks the public `releases/latest` endpoint on app launch (toggle in Settings, ON by default) and via a "Check for Updates" button. It picks the APK matching the device ABI (arm64-v8a → armeabi-v7a → x86_64 → x86, universal fallback), downloads it with live progress, and opens the system installer through FileProvider (`REQUEST_INSTALL_PACKAGES`).
- **Auto Version Bump**: CI release workflow now takes a version input (e.g. `1.25.2`) and passes `-PversionName`/`-PversionCode` to Gradle — `versionCode` is derived automatically from the name (`1.25.2` → `12502000`), so releases no longer require editing `build.gradle.kts`.
- **Per-ABI Release APKs (ala Komikku)**: `assembleRelease` now splits output into one APK per ABI (arm64-v8a, armeabi-v7a, x86, x86_64) plus a universal APK; `-PabiFilter=<abi>` builds just one variant.
- **Multi-Job CI Matrix**: `.github/workflows/kzkt.yml` restructures the release pipeline into `prepare → build (5 parallel ABI jobs) → create release` — with the arrow flowchart on the Actions page and a Telegram notification containing clickable links.
- **AGENTS.md**: AI-agent instruction file so external AI tools follow the same build/verify/sign/send workflow as this project's maintainers.

### Changed

- **CI artifact naming**: debug APK artifact path fixed after per-ABI splits (`app-arm64-v8a-debug.apk` / `app-universal-debug.apk`).
- **GitHub Release flow**: releases are published immediately (no draft), with APK assets + `sha256sums.txt`.

### Fixed

- **WorkManager 10 KB limit crash**: importing a folder with many images threw `IllegalStateException: Data cannot occupy more than 10240 bytes when serialized` because the whole file list was passed as WorkManager input data. The list is now written to a JSON file in `cacheDir` and only its path is passed; the worker reads + deletes it, with a size-safe fallback and a stale-file sweep for orphans.
- **Telegram notification newlines**: `\n` was sent as literal text — now built with `printf` so links render on their own clickable lines.

## [v1.25.1.22] - 2026-08-07

### Added

- **Batch Edit in Touch-up Editor**: New palette-icon button opens a Batch Edit dialog — Find & Replace text across every bubble, and apply Bold / Italic / Alignment / Font Size to all bubbles at once.
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
- **Repository History Rewrite**: dropped the pre-Android Python desktop era (105 commits) so the repository starts at the native Kotlin/Compose Android port (2026-07-30) — a solo Android repo. Desktop-era tags were removed, `v1.1-beta` was re-pointed, and the commit hashes cited in this changelog were updated to their rewritten equivalents. The original full history is preserved in a local backup bundle.

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

### Major UI Overhaul (2026-08-02)

Directly following the documentation commit that outlined the app's key features — **PDF support, 3-stage YOLO cascade, and zero memory leaks** (`4d13002`) — the whole interface was rebuilt around modern Material 3 (`1ba321f`, +2556/−671 lines across 20 files):

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

- **In-App Fullscreen Image Viewer** with pinch-to-zoom & pan gestures (`96dd00c`).
- **Quick Action buttons** to open translated results in the System Gallery or share via social media (`96dd00c`).
- **Official app launcher icon** at every Android screen density (mdpi to xxxhdpi) (`1f39e00`).

### Fixed

- **Translation cancellation delay**: pressing Cancel now stops the coroutine and network request instantly — no longer waits for the batch to finish (`37df21b`).
- **Custom LLM remote endpoint compatibility**: forced `stream: false` and added dynamic JSON parsing for Ollama, LM Studio, vLLM, and Cloudflare/Ngrok tunnels (`343619a`).
- **PhotoPicker synthetic-path crash on Android 13+**: output files routed directly to public `/Download/` (`5f5fb24`).
- **OpenCV JNI `JNIEnv` library loading on release builds**: uses uncompressed legacy packaging (`5f5fb24`).
- **JSON parsing errors on non-standard LLM responses**: lenient Gson parsing (`b35de92`).
- **Released native Mat/ONNX resources**, 3-stage YOLO cascade, bubble-sized overlay (`d3be474`).
- **PDF input/output** via built-in `PdfRenderer`/`PdfDocument`, shared render path (`4d3ff98`).
- **Theme color** changed from purple to a modern Light Blue (Sky Blue) (`ad8dae0`).

---

*Full history: see `git log --oneline`.*
