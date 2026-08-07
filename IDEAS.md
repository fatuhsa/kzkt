# 💡 Roadmap Ide Fitur KZKT

> Daftar ide pengembangan aplikasi penerjemah manga **KZKT**.
> Berdasarkan audit menyeluruh codebase (Agustus 2026) — setiap ide menyertakan
> referensi kode yang terlibat, estimasi effort, dan nilai dampak.

## Cara Membaca

| Simbol | Arti |
| :--- | :--- |
| 🟢 | Effort kecil — modal kode sudah ada |
| 🟡 | Effort menengah — butuh desain UI/flow tambahan |
| 🔴 | Ambisius — butuh perencanaan & riset |
| ✅ / ⬜ | Status implementasi (centang saat selesai) |

Skala nilai: **1** (rendah) – **5** (tinggi).

---

## 🟢 Tier 1 — Quick Wins

### 1. Invalidasi cache saat ganti provider/model ✅ *(nilai 5, effort kecil)*
- **Status:** Sudah terimplementasi di kode (key cache = `hash_crop + bahasa + provider + model`).
- **Masalah:** `TranslationCacheRepository` meng-key cache hanya dari `hash_crop + bahasa` (`getTranslation`/`saveTranslation`). Ganti provider atau model → hasil terjemahan lama masih dipakai, sehingga output "basi".
- **Solusi:** tambahkan `provider + model` ke key cache. Opsional: tombol "Clear cache" sudah ada di Settings.
- **File:** `app/src/main/java/com/kzkt/app/data/TranslationCacheRepository.kt`, `TranslationPipeline.kt`.

### 2. Undo/Redo di touch-up editor ✅ *(nilai 5, effort kecil)*
- **Status:** Sudah terimplementasi (stack history + tombol Undo/Redo di `InteractiveEditorDialog`).
- **Masalah:** setiap edit (teks/posisi/ukuran) langsung diterapkan tanpa bisa dibatalkan.
- **Solusi:** simpan snapshot `bubbles` (SnapshotStateMap) tiap perubahan; tombol Undo/Redo di `InteractiveEditorDialog`.
- **File:** `app/src/main/java/com/kzkt/app/ui/component/InteractiveEditorDialog.kt`.

### 3. Find & Replace / batch styling di editor ✅ *(nilai 4, effort kecil)*
- **Status:** Dialog "Batch Edit" di editor: Find & Replace semua bubble + apply bold/italic/align/size ke semua bubble.
- Terapkan bold/italic/alignment/ukuran ke banyak bubble sekaligus, dan ganti teks massal (mis. perbaiki nama karakter yang salah konsisten).
- Data style per bubble sudah ada di `BubbleMeta`.

### 4. Retry otomatis antar-model (failover dalam provider) ✅ *(nilai 4, effort kecil)*
- **Status:** `createFallbackProviders` kini mencoba model alternatif dari `PRESET_MODELS` provider yang sama sebelum lompat ke provider lain.
- Kalau model utama gagal di `RateLimiter.executeWithRetry`, coba model cadangan pada provider yang sama sebelum lompat ke provider lain (fallback chain sudah ada di `TranslationPipeline`).

### 5. Export CBZ mempertahankan struktur folder ✅ *(nilai 3, effort kecil)*
- **Status:** `createCbz` kini memakai path relatif dari direktori induk bersama (`commonParentDir`).
- **Bug kecil dari audit:** `ArchiveExtractor.createCbz` menulis entry flat (`file.name` saja) padahal ekstraksi mempertahankan path asli. Gunakan path relatif agar nama tidak tabrakan.
- **File:** `app/src/main/java/com/kzkt/app/util/ArchiveExtractor.kt`.

### 6. Dukungan ACTION_SEND_MULTIPLE ✅ *(nilai 3, effort kecil)*
- **Status:** Intent-filter `SEND_MULTIPLE` + baca `ClipData`/`EXTRA_STREAM` di `MainActivity`.
- Manifest hanya mendeklarasikan `ACTION_SEND` (1 gambar). Tambah `SEND_MULTIPLE` + baca `ClipData` agar bisa share banyak gambar sekaligus dari galeri.
- **File:** `AndroidManifest.xml`, `MainActivity.kt`.

### 7. Provider health check ("Test API Key") ✅ *(nilai 4, effort kecil)*
- **Status:** Tombol "Test API Key & Connection" di Settings — 1 request kecil + hasil inline.
- Tombol yang mengirim 1 request kecil (model list / ping) untuk memvalidasi API key + model sebelum batch besar.
- **File:** `MainViewModel.fetchModelsForProvider` (pola sudah ada).

### 8. Filter history: bahasa + rentang tanggal ✅ *(nilai 3, effort kecil)*
- **Status:** Sudah terimplementasi (filter chip bahasa + date range picker di `HistoryScreen`).
- Search & filter provider sudah ada di `HistoryScreen`; tambahkan filter bahasa dan rentang tanggal.

### 9. Input dari folder (SAF tree picker) ✅ *(nilai 4, effort kecil)*
- **Status:** Tombol "Pick Folder" di Translate — `OpenDocumentTree` + traversal rekursif `DocumentFile`, gambar di-copy ke cache.
- Pilih satu folder berisi puluhan gambar sekaligus (`OpenDocumentTree`), rekursif ambil gambar — lebih cepat daripada multi-select satu-satu.

### 10. Themed icon Android 13+ (monochrome) ✅ *(nilai 2, effort kecil)*
- **Status:** Adaptive icon `mipmap-anydpi-v26` + layer `monochrome` di `mipmap-anydpi-v33`.
- Tambahkan layer `monochrome` pada launcher icon agar tampil rapi di Pixel/launcher Material You.

---

## 🟡 Tier 2 — High Value

### 11. Mode terjemahan SFX ✅ *(nilai 5, effort menengah)*
- **Status:** Toggle "Translate Sound Effects (SFX)" — prompt menginstruksikan LLM menerjemahkan onomatope alih-alih `SKIP`.
- Sekarang SFX di-*skip* (LLM diinstruksikan membalas `SKIP`). Tambah toggle **"Translate SFX"** agar efek suara (ドドド, バキ, dsb.) ikut diterjemahkan dengan gaya bold/large.
- **File:** `Constants.buildPrompt`, `TranslationPipeline.renderTranslations`, `TextRenderer`.

### 12. Multi-bahasa per run ⬜ *(nilai 5, effort menengah)*
- Minta LLM sekali dengan mosaic yang sama, lalu render ke N bahasa sekaligus. Biaya API nyaris tidak bertambah karena batching mosaic sudah efisien (hingga 30 bubble/request).
- **File:** `TranslationPipeline` (LLM phase), output path per bahasa di `MosaicBuilder.makeOutputPath`.

### 13. Pre-translation preview ⬜ *(nilai 4, effort menengah)*
- Tampilkan hasil JSON LLM (bubble → teks) **sebelum** render final; user bisa koreksi, lalu render. Mencegah 100 halaman terbuang karena prompt/glossary salah.

### 14. Backup & restore (settings + glossary + cache + history) ✅ *(nilai 4, effort menengah)*
- **Status:** `BackupManager` — export/import 1 file JSON (settings + glossary + history + translation cache) via menu Data di Settings.
- Export semua data lokal ke 1 file (JSON/zip) yang bisa dibagikan ke perangkat lain.
- **File:** `SettingsRepository`, `GlossaryRepository`, `TranslationCacheRepository`, `HistoryRepository` (semua sudah JSON-friendly).

### 15. Re-translate dari History (bahasa lain) ⬜ *(nilai 4, effort menengah)*
- Dari entri history, pilih bahasa target lain → proses ulang dari file asli. Metadata original sudah tersimpan di `EditMetadataRepository`.

### 16. Status per-file di queue ⬜ *(nilai 4, effort menengah)*
- Progress sekarang agregat (done/total + log). Tambahkan status per file: pending / processing / done / failed dengan ikon di UI.
- **File:** `TranslationService`, `MainViewModel`, `MainScreen`.

### 17. Deteksi warna bubble nyata ⬜ *(nilai 4, effort menengah)*
- `ImageProcessor.detectBubbleBackgroundColor` hanya mengembalikan hitam/putih. Deteksi warna asli (kuning, biru tua, dsb.) → masking dan kontras teks jauh lebih akurat.

### 18. Side-by-side Original vs Translated ⬜ *(nilai 3, effort menengah)*
- Toggle sudah ada di reader; tambah mode belah-dua (split view) untuk cek hasil cepat.

### 19. Posisi baca tersimpan per buku ⬜ *(nilai 3, effort menengah)*
- Reader mengingat halaman terakhir per buku (key dari `bookGroupKey` yang sudah ada di `HistoryScreen`).

### 20. Edit prompt custom (advanced) ⬜ *(nilai 3, effort menengah)*
- Izinkan pengguna lanjutan mengedit template prompt (sekarang hardcoded di `Constants.buildPrompt`), mis. untuk genre tertentu.

---

## 🔴 Tier 3 — Ambisius

### 21. Terjemahan offline penuh (ONNX NLLB) ⬜ *(nilai 5, effort besar)*
- Integrasi model NLLB-200 distilled via ONNX Runtime — infrastruktur ONNX sudah terbukti berjalan di device (YOLO). Kualitas lebih rendah dari LLM, tapi 100% gratis & privat. Cocok sebagai mode kedua.

### 22. Job persist lintas restart (WorkManager) ⬜ *(nilai 4, effort besar)*
- Foreground Service saat ini menyimpan state in-memory (`TranslationProgressTracker`); WorkManager membuat job bertahan walau app di-kill sistem, dengan progress tersimpan di disk.

### 23. Watch folder auto-translate ⬜ *(nilai 4, effort besar)*
- Pilih folder, file baru otomatis diterjemahkan (FileObserver + service yang sudah berjalan di background).

### 24. Text-to-speech per bubble ⬜ *(nilai 3, effort menengah)*
- Tap bubble → baca terjemahan dengan suara (aksesibilitas + belajar bahasa).

### 25. Estimasi biaya & statistik ⬜ *(nilai 3, effort menengah)*
- Track ukuran mosaic × resolusi × jumlah request per provider; tampilkan perkiraan token/biaya di History.

### 26. Tata letak teks mengikuti bentuk bubble ⬜ *(nilai 4, effort besar)*
- Mask saat ini rounded-rect; dukung bentuk awan/oval/ekor agar teks mengikuti kontur bubble.

### 27. Room untuk History ⬜ *(nilai 4, effort besar)*
- Ganti JSON-in-DataStore dengan Room agar ribuan entri tetap cepat dicari & diurutkan.

### 28. Dukungan format tambahan ⬜ *(nilai 3, effort menengah)*
- `.avif`, `.gif` (frame pertama), `.rar` (lib eksternal), atau baca dari URL/cloud.

### 29. Auto-deteksi bahasa sumber ⬜ *(nilai 4, effort menengah)*
- Deteksi bahasa asli (1 request LLM kecil atau heuristik) sebelum translate agar prompt lebih akurat.

---

## 🗺️ Roadmap Rekomendasi

Prioritas yang disarankan untuk 3 rilis ke depan (dampak tinggi, risiko rendah):

| Urutan | Fitur | Alasan |
| :--- | :--- | :--- |
| 1 | **Invalidasi cache per provider/model** | Perbaikan kualitas hasil — output tidak konsisten lebih merusak daripada lambat |
| 2 | **Undo/Redo + batch style editor** | Editor adalah fitur unggulan; UX-nya langsung terasa |
| 3 | **Multi-bahasa per run** | Fitur *wow* yang memanfaatkan batching mosaic yang sudah efisien |

> Setiap item di atas siap dikerjakan — cukup bilang nomornya, atau pilih kombinasi.
