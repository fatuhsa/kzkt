# KZKT

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge&logo=open-source-initiative&logoColor=white)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android SDK](https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![ONNX Runtime](https://img.shields.io/badge/ONNX_Runtime-v1.21-0078D4.svg?style=for-the-badge&logo=onnx&logoColor=white)](https://onnxruntime.ai/)
[![OpenCV](https://img.shields.io/badge/OpenCV-v4.10.0-5C3EE8.svg?style=for-the-badge&logo=opencv&logoColor=white)](https://opencv.org/)

<p align="center"><a href="README.md">English</a> · <b>Bahasa Indonesia</b></p>

KZKT adalah aplikasi Android native untuk terjemahan manga dan komik secara otomatis. Aplikasi ini mendeteksi balon kata (speech bubble) di halaman dengan AI on-device, mengirim teksnya ke vision LLM pilihan Anda, lalu merender teks terjemahan kembali ke halaman — semuanya lokal, tanpa perlu akses root.

---

## Keunggulan

- **Layanan terjemahan latar belakang** - menjalankan pipeline terjemahan di dalam Foreground Service dengan notifikasi progres dinamis di status bar, sehingga tugas tetap berjalan meski aplikasi ditutup.
- **Cache halaman untuk retry cepat** - menyimpan koordinat YOLO dan bitmap crop balon kata setelah deteksi awal, sehingga pekerjaan yang terhenti/dibatalkan bisa dilanjutkan seketika tanpa harus deteksi ulang dari nol.
- **Deteksi balon kata on-device** - YOLO via Microsoft ONNX Runtime Android, dengan kaskade 3 tahap untuk deteksi balon kata, SFX, dan kotak yang saling tumpang tindih secara akurat.
- **Vision LLM multi-provider** - Google Gemini, OpenAI, OpenRouter, Zen, OpenCode Go, plus endpoint kustom/lokal apa pun (Ollama, LM Studio, LocalAI, vLLM) dengan deteksi model otomatis.
- **Mosaic batching hemat biaya** - balon kata dipotong dan dikemas menjadi mozaik RTL vertikal untuk memangkas permintaan API hingga 80%.
- **PDF masuk, PDF keluar** - merender halaman PDF menjadi gambar dan menyusun ulang halaman hasil terjemahan kembali ke PDF hanya dengan `PdfRenderer` dan `PdfDocument` bawaan Android.
- **Parsing JSON tangguh** - toleran terhadap key duplikat dan keluaran LLM yang tidak rapi, sehingga satu respons buruk tidak pernah membatalkan satu batch.
- **Rendering teks adaptif** - masking balon otomatis dengan bentuk bulat/oval, auto-scaling font, dan word wrapping via `Canvas` dan `StaticLayout`.
- **Kompatibilitas scoped storage** - menulis bitmap sementara dan hasil ke cache privat aplikasi dulu sebelum dipublikasikan via MediaStore, mencegah error izin EACCES di Android 10+.

---

## Teknologi

| Lapisan | Teknologi |
| :--- | :--- |
| Bahasa & Inti | Kotlin, Java 17 |
| UI | Jetpack Compose, Material 3, Navigation Compose, Coil |
| Konkurensi & State | Coroutines, Flow, ViewModel, DataStore Preferences |
| Machine Learning | ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android:1.21.0`) |
| Computer Vision | OpenCV 4.10.0 Android SDK (`libopencv_java4.so` C++ JNI) |
| Networking & JSON | OkHttp 4.12, Gson (mode lenient) |
| API Target | `minSdk = 26` (Android 8.0), `targetSdk = 36` (Android 15+) |

---

## Alur Pipeline

```text
[ Gambar input / halaman manga ]
           |
           v
[ 1. Deteksi balon kata YOLO ONNX ] --> kotak pembatas
           |
           v
[ 2. Filter OpenCV & smart crop ] --> buang SFX, gabungkan yang tumpang tindih
           |
           v
[ 3. Pembuat mozaik ] --> kemas crop ke mozaik RTL vertikal + label ID merah
           |
           v
[ 4. Provider vision LLM ] --> Gemini / OpenAI / OpenRouter / lokal kustom
           |
           v
[ 5. Renderer teks & masking ] --> mask dalam balon + teks terbungkus auto-scale
           |
           v
[ Output di /Download/KZKT/ ] --> langsung terlihat di galeri
```

---

## Struktur Proyek

```text
├── app/
│   └── src/main/
│       ├── java/com/kzkt/app/
│       │   ├── core/           # pipeline, engine YOLO ONNX, OpenCV, renderer teks
│       │   ├── core/providers/ # provider Gemini, OpenAI, OpenRouter, kustom
│       │   ├── data/           # pengaturan & riwayat (persistensi DataStore)
│       │   ├── ui/             # layar Compose (Terjemah, Riwayat, Pengaturan)
│       │   ├── ui/component/   # komponen Material 3 yang dapat dipakai ulang
│       │   └── util/           # helper
│       ├── assets/             # model YOLO terenkripsi (kzkt.dat) & font
│       └── AndroidManifest.xml
├── opencv/                     # modul SDK OpenCV 4.x Android (JNI native)
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## Build APK

> File model YOLO (`kzkt.dat`, ±100 MB) di-commit langsung di repositori ini,
> jadi proses clone membutuhkan waktu lebih lama karena ukuran file-nya besar.

1. Clone repositori:
   ```bash
   git clone https://github.com/kouzen-neo/kzkt.git
   cd kzkt
   ```

2. Buka di Android Studio, biarkan Gradle sync, lalu build:
   ```bash
   ./gradlew assembleDebug
   ```

3. Install APK:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

---

## Lisensi

Proyek ini dilisensikan di bawah [Lisensi MIT](LICENSE).

---

## Ucapan Terima Kasih Open Source

KZKT dapat terwujud berkat karya banyak proyek dan komunitas open source:

- CYPY oleh [indravoyager](https://github.com/indravoyager) sebagai framework dasar.
- OpenCV, ONNX Runtime, dan Google ML Kit untuk visi dan OCR on-device.
- Jetpack Compose dan Material 3 untuk toolkit UI.
- Tulis ulang Android native dan pengembangan oleh kouzen-neo.
- Penerjemah, penguji beta, dan kontributor yang mendukung proyek ini.
