<div align="center">
  <img src="docs/assets/logo.png" width="100" alt="KZKT Logo" />
  <h1>KZKT</h1>

  <p>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge&logo=open-source-initiative&logoColor=white" alt="License: MIT"></a>
    <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin"></a>
    <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white" alt="Android SDK"></a>
    <a href="https://onnxruntime.ai/"><img src="https://img.shields.io/badge/ONNX_Runtime-v1.21-0078D4.svg?style=for-the-badge&logo=onnx&logoColor=white" alt="ONNX Runtime"></a>
    <a href="https://opencv.org/"><img src="https://img.shields.io/badge/OpenCV-v4.10.0-5C3EE8.svg?style=for-the-badge&logo=opencv&logoColor=white" alt="OpenCV"></a>
  </p>

  <p><a href="README.md">English</a> · <b>Bahasa Indonesia</b></p>
</div>

KZKT adalah aplikasi Android untuk menerjemahkan manga dan komik secara otomatis. Balon kata di tiap halaman dideteksi pakai AI langsung di perangkat (on-device), teksnya dikirim ke LLM pilihanmu, lalu hasil terjemahannya ditulis kembali ke halaman — semua dikerjakan lokal, tanpa butuh akses root.

<div align="center">
  <table>
    <tr>
      <td align="center" width="33%"><b>Layar Utama / Terjemah</b></td>
      <td align="center" width="33%"><b>Layar Riwayat / History</b></td>
      <td align="center" width="33%"><b>Layar Pengaturan / Settings</b></td>
    </tr>
    <tr>
      <td align="center" width="33%"><img src="docs/screenshots/translate.png" width="100%" alt="Layar Terjemah"></td>
      <td align="center" width="33%"><img src="docs/screenshots/history.png" width="100%" alt="Layar Riwayat"></td>
      <td align="center" width="33%"><img src="docs/screenshots/settings.png" width="100%" alt="Layar Pengaturan"></td>
    </tr>
  </table>
</div>

---

## Keunggulan

- **Banyak format yang didukung** — bisa terima gambar, satu folder penuh, share beberapa gambar sekaligus, arsip (ZIP / CBZ / EPUB), sampai file PDF.
- **Terjemahan ke 14 bahasa** — tinggal pilih bahasa targetnya: Inggris, Indonesia, Jepang, Korea, Mandarin, Spanyol, Prancis, Jerman, dan masih banyak lagi.
- **Bebas pilih provider LLM** — Google Gemini, OpenAI, OpenRouter, Zen, OpenCode Go, atau endpoint lokal apa pun yang kompatibel OpenAI (Ollama, LM Studio, LocalAI, vLLM).
- **Deteksi balon kata on-device** — YOLO (ONNX Runtime) mendeteksi balon kata langsung di perangkat, plus OCR opsional via ML Kit untuk model non-vision.
- **PDF masuk, PDF keluar** — halaman PDF dirender, diterjemahkan, lalu disusun kembali jadi PDF.
- **Terjemahan tetap jalan di latar belakang** — meski aplikasi ditutup prosesnya lanjut terus, hasilnya disimpan ke `/Download/KZKT/`.

---

## Tech Stack

| Bagian | Teknologi |
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
[ Gambar / halaman manga ]
           |
           v
[ 1. Deteksi balon kata YOLO ONNX ] --> kotak pembatas
           |
           v
[ 2. Filter OpenCV & crop otomatis ] --> buang SFX, gabungkan yang bertumpuk
           |
           v
[ 3. Susun mozaik ] --> crop dikemas jadi mozaik RTL vertikal + label ID merah
           |
           v
[ 4. Provider vision LLM ] --> Gemini / OpenAI / OpenRouter / lokal kustom
           |
           v
[ 5. Render teks & masking ] --> mask di dalam balon + teks menyesuaikan ukuran
           |
           v
[ Hasil di /Download/KZKT/ ] --> langsung muncul di galeri
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
│       │   ├── ui/component/   # komponen Material 3 yang bisa dipakai ulang
│       │   └── util/           # helper
│       ├── assets/             # model YOLO terenkripsi (kzkt.dat) & font
│       └── AndroidManifest.xml
├── opencv/                     # modul SDK OpenCV 4.x Android (JNI native)
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile                  # image build Android lengkap (JDK 17 + SDK)
└── README.md
```

---

## Kebutuhan Sebelum Build

- **JDK 17** — misalnya [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17).
- **Android SDK** — `compileSdk = 37`, `minSdk = 26`, `targetSdk = 36` (otomatis terpasang lewat SDK Manager di Android Studio).
- **Gradle 9.6.1** — tidak perlu install manual, wrapper `gradlew` yang mengunduhnya.
- **Android Studio** (versi stabil terbaru) — disarankan untuk sync Gradle, edit kode, dan emulator.

---

## Cara Build APK

> File model YOLO (`kzkt.dat`, ±100 MB) disimpan langsung di repositori ini,
> jadi proses clone agak lebih lama karena filenya besar.

1. Clone repositorinya:
   ```bash
   git clone https://github.com/kouzen-neo/kzkt.git
   cd kzkt
   ```

2. Buka di Android Studio, tunggu Gradle sync selesai, lalu build:
   ```bash
   ./gradlew assembleDebug
   ```

3. Install APK-nya:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Build dengan Docker

Reponya sudah dibekali `Dockerfile` lengkap (JDK 17 + Android SDK), jadi kamu bisa build tanpa perlu install Android Studio:

```bash
# 1. Bangun image builder-nya (cukup sekali)
docker build -t kzkt-builder .

# 2. APK debug (cache Gradle disimpan lewat named volume supaya awet)
docker run --rm -v kzkt-gradle:/root/.gradle \
  kzkt-builder ./gradlew assembleDebug

# 3. APK release — otomatis pakai debug keystore lokalmu
#    (mount ~/.android biar keystore-nya kebaca di dalam container)
docker run --rm -v kzkt-gradle:/root/.gradle \
  -v "$HOME/.android:/root/.android" \
  kzkt-builder ./gradlew assembleRelease

# 4. Salin APK keluar dari container
CID=$(docker run -d -v kzkt-gradle:/root/.gradle \
  -v "$HOME/.android:/root/.android" \
  kzkt-builder ./gradlew assembleRelease)
docker wait "$CID"
docker cp "$CID:/app/app/build/outputs/apk/release/app-release.apk" .
docker rm "$CID"
```

Kalau mau publish, mount keystore punyamu sendiri (read-only):

```bash
docker run --rm -v kzkt-gradle:/root/.gradle \
  -v "$PWD/keystore.properties:/app/keystore.properties:ro" \
  -v "$PWD/release.keystore:/app/release.keystore:ro" \
  kzkt-builder ./gradlew assembleRelease
```

### Integrasi Berkelanjutan (GitHub Actions)

Setiap push / pull request ke `main` otomatis di-build oleh [GitHub Actions](.github/workflows/android.yml) pakai **Dockerfile yang sama**: unit test + `assembleDebug` dijalankan di dalam container, lalu APK debug-nya diunggah sebagai **artifact** yang bisa diunduh (`kzkt-app-debug`) di halaman run. Dependensi Gradle di-cache antar-run, jadi build berikutnya jauh lebih cepat.

---

## Ucapan Terima Kasih Open Source

KZKT bisa terwujud berkat karya banyak proyek dan komunitas open source:

- CYPY oleh [indravoyager](https://github.com/indravoyager) sebagai framework dasar.
- OpenCV, ONNX Runtime, dan Google ML Kit untuk visi dan OCR on-device.
- Jetpack Compose dan Material 3 untuk toolkit UI.
- Tulis ulang Android native dan pengembangan oleh kouzen-neo.
- Penerjemah, penguji beta, dan kontributor yang mendukung proyek ini.

---

## Lisensi

Proyek ini dilisensikan di bawah [Lisensi MIT](LICENSE).
