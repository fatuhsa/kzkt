# Changelog

Semua perubahan signifikan pada CyKt dicatat di file ini.

Format mengikuti [Keep a Changelog](https://keepachangelog.com/id-ID/1.1.0/), versi mengikuti [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v1.25.1.13] - 2026-08-03

### Added

- **In-App Manga & PDF Reader**: fullscreen `HorizontalPager` with pinch-to-zoom, Original vs Translated toggle, and live text touch-up editing.
- Reader integrated into the History tab and the PDF result card.
- Interactive Touch-up Editor connected to the MainScreen edit action.
- v2.0 feature set: custom fonts, OpenCV inpainting, translation cache memory, multi-language expansion, auto-fallback multi-provider chain.
- Android CI workflow (JDK 17, `assembleDebug`).

### Changed

- History entries hoisted into a `StateFlow` (parsed once, replayed in memory); tweak sliders extracted to top-level composables — zero recomposition lag when switching tabs.
- Cached date formatter and settings lists for 120 FPS zero-framedrop scrolling.
- PDF extraction optimized to JPEG 90% at 2048px; mosaic payloads compressed to JPEG 85%; max bubbles raised to 35; rate-limiter delay cut to 0.5 s.
- Settings converted to `LazyColumn` for smooth 60-120 FPS scroll.
- Parallel inpainting pipeline; pre-allocated direct `FloatBuffer` in YOLO.
- Output files routed to public `Download/KZKT` via MediaStore with fallback (fixes `EACCES` crash on Android 10+).
- Full cypy-to-KZKT rebrand (`com.kzkt.app`); README rewritten, original cypy credited.

### Fixed

- Pager swipe unblocked: `pointerInput` tap detector replaced with `combinedClickable`; BottomSheet gesture trap removed.
- History swipe gathers every image entry for horizontal navigation.
- Swipe gesture disabled while zoomed; zoom reset on page switch.
- Missing inpainting loop in `processImageBatch`; broader text-stroke thresholding.
- Old cache files auto-cleaned before PDF extraction.
- OpenCV access synchronized across coroutines; 350 ms debounce on text fields.
- No live DataStore writes per keystroke; settings state reads scoped with `derivedStateOf`.
- Eliminated card `animateContentSize` lag; fixed PDF progress bar 100% math; fixed PDF page memory leaks.
- Tap coordinate letterbox calculation, OpenCV `submat` bounds clamping, and bitmap recycling safety hardened.

## [Unreleased]

### Added

- **Pengaturan collapsible**: halaman Settings dirombak menjadi accordion Material — setiap grup (Provider, Target Language, API Keys, Model, Tweak Parameters, SFX Filter Mode) bisa di-hide/show dengan menekan header. Provider dan Target Language terbuka secara default karena paling sering dipakai.
- **Komponen `SettingsSection`** baru: Card Material dengan header clickable, ikon chevron naik/turun, dan animasi buka-tutup. State ekspansi disimpan via `rememberSaveable` sehingga bertahan saat rotasi layar.

### Changed

- **Model & Custom URL digabung**: bagian `Model` sekarang berisi pengaturan base URL custom beserta tombol "Detect Models from API" di dalamnya, tidak lagi terpisah di tengah layar.
- **Toggler API Keys diseragamkan**: tombol `Show/Hide API Keys` yang lama diganti accordion yang konsisten dengan section lain.

### Fixed (terbaru, commit `b275190`)

- **JSON parsing toleran duplicate key**: LLM kadang mengembalikan JSON dengan key duplikat (mis. `"5_1": "..."` muncul 2×). Parser sebelumnya crash total, sekarang pakai fallback `JsonReader` streaming yang *skipValue()* duplikat — batch translasi selamat, halaman tengah tidak kehilangan terjemahan.
- **YOLO init dipindahkan ke background**: inisialisasi model ONNX + dekripsi `eyecypy.dat` kini jalan di `Dispatchers.IO` via `viewModelScope.launch` — main thread tidak diblokir saat startup.

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