<div align="center">
  <img src="docs/assets/logo.png" width="100" alt="KZKT Logo" />
  <h1>KZKT</h1>

  <p>
    <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge&logo=open-source-initiative&logoColor=white" alt="License: MIT"></a>
    <a href="https://kotlinlang.org/"><img src="https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin"></a>
    <a href="https://developer.android.com"><img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white" alt="Android SDK"></a>
    <a href="https://onnxruntime.ai/"><img src="https://img.shields.io/badge/ONNX_Runtime-v1.29-0078D4.svg?style=for-the-badge&logo=onnx&logoColor=white" alt="ONNX Runtime"></a>
    <a href="https://opencv.org/"><img src="https://img.shields.io/badge/OpenCV-v4.10.0-5C3EE8.svg?style=for-the-badge&logo=opencv&logoColor=white" alt="OpenCV"></a>
  </p>

  <p>
    <a href="README.md"><img src="https://img.shields.io/badge/EN-6e7681.svg?style=for-the-badge" alt="English"></a>
    <a href="README.id.md"><img src="https://img.shields.io/badge/ID-0078D4.svg?style=for-the-badge" alt="Bahasa Indonesia"></a>
  </p>
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

- **Banyak format input** — gambar, satu folder penuh, share beberapa gambar sekaligus, arsip (ZIP / CBZ / EPUB), sampai file PDF — plus **PDF masuk, PDF terjemahan keluar**.
- **14 bahasa target** — Inggris, Indonesia, Jepang, Korea, Mandarin, Spanyol, Prancis, Jerman, dan lainnya.
- **Bebas pilih provider LLM** — Google Gemini, OpenAI, OpenRouter, Zen, OpenCode Go, atau endpoint OpenAI-compatible mana pun (Ollama, LM Studio, LocalAI, vLLM), dengan fallback otomatis antargambar.
- **Pipeline AI on-device** — kaskade YOLO 3 tahap (ONNX Runtime) mendeteksi balon kata, OCR opsional via ML Kit (Jepang + Latin) biar model non-vision tetap jalan, plus **Smart Image Upscaler** yang melipatgandakan resolusi halaman biar hasilnya makin tajam.
- **Reader bawaan** — lihat hasil per halaman atau mode **webtoon** yang bisa discroll, pinch-to-zoom sampai 4x, dan **editor pensil** buat mengubah teks balon langsung di aplikasi.
- **Pembaca PDF instan** — PDF terjemahan langsung kebuka, dirender per halaman tanpa nunggu seluruh dokumen diproses.
- **Glosarium & memory terjemahan** — istilah kustommu tersimpan, dan kalimat yang sama tidak diterjemahkan ulang.
- **Terjemahan latar belakang & auto-update** — proses lanjut jalan walau aplikasi ditutup (hasil ke `/Download/KZKT/`), dan aplikasi otomatis cek rilis baru saat dibuka, lengkap dengan unduhan yang bisa dilanjutkan.

---

## Tech Stack

| Bagian | Teknologi |
| :--- | :--- |
| Bahasa & Inti | Kotlin 2.4, Java 17, AGP 9.3 |
| UI | Jetpack Compose (BOM 2026.01.01), Material 3 1.5.0-alpha25, MaterialKolor (tema Material You dinamis), Navigation Compose 2.9.8, Coil 2.7 |
| Konkurensi & State | Coroutines 1.9, Flow, ViewModel, DataStore Preferences 1.1.2 |
| Machine Learning | ONNX Runtime 1.29 (YOLO), ML Kit Text Recognition 16.0.1 (Latin + Jepang) |
| Computer Vision | OpenCV 4.10.0 Android SDK (`libopencv_java4.so` C++ JNI) |
| Networking & JSON | OkHttp 4.12, Gson 2.11 |
| Persistensi & Background | DataStore Preferences, WorkManager 2.11, MediaStore |
| API Target | `minSdk = 26` (Android 8.0), `compileSdk = 37`, `targetSdk = 36` |

---

## Alur Pipeline

```text
[ Gambar / halaman manga ]
           |
           v
[ 0. Smart upscaler ] --> peningkatan resolusi 2x (opsional)
           |
           v
[ 1. Kaskade YOLO (3 tahap) ] --> kotak balon kata (on-device, ONNX)
           |
           v
[ 2. Filter OpenCV & crop ] --> buang SFX, gabungkan yang bertumpuk, mask area luar balon
           |
           v
[ 3. Susun mozaik ] --> crop dikemas jadi mozaik RTL vertikal + label ID merah
           |
           v
[ 4. Vision LLM ] --> Gemini / OpenAI / OpenRouter / lokal
           |            (atau OCR ML Kit + LLM teks kalau OCR aktif)
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
│       │   ├── core/           # pipeline terjemahan, engine YOLO ONNX, OpenCV, OCR, updater
│       │   ├── core/providers/ # provider Gemini, OpenAI, OpenRouter, Zen, OpenCode Go, kustom
│       │   ├── data/           # pengaturan, riwayat, glosarium & cache (persistensi)
│       │   ├── ui/             # layar Compose (Terjemah, Riwayat, Pengaturan)
│       │   ├── ui/component/   # komponen Material 3 yang bisa dipakai ulang
│       │   └── util/           # helper
│       ├── assets/             # model YOLO terenkripsi (kzkt.dat) & font
│       └── AndroidManifest.xml
├── opencv/                     # modul SDK OpenCV 4.x Android (JNI native)
├── .github/workflows/          # KZKT Trigger Debug + KZKT Auto Release (APK per-ABI)
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile                  # image build Android lengkap (JDK 17 + SDK)
├── BUILD_RELEASE.md            # panduan keystore custom + publish rilis signed
├── CHANGELOG.md
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
   # APK debug — satu per ABI + satu universal
   ./gradlew assembleDebug
   #   → app/build/outputs/apk/debug/app-<abi>-debug.apk (+ app-universal-debug.apk)

   # Lebih cepat: cukup ABI HP kamu (arm64-v8a untuk mayoritas HP modern)
   ./gradlew assembleDebug -PabiFilter=arm64-v8a
   #   → app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
   ```

3. Build **release signed** (sudah di-minify R8):
   ```bash
   ./gradlew assembleRelease -PabiFilter=arm64-v8a
   #   → app/build/outputs/apk/release/app-arm64-v8a-release.apk
   ```
   Release langsung ter-sign otomatis: kalau ada file `keystore.properties` itu yang dipakai,
   kalau tidak, fallback ke debug keystore lokal. Panduan lengkap bikin dan publish pakai
   keystore sendiri ada di [BUILD_RELEASE.md](BUILD_RELEASE.md) (template-nya:
   `keystore.properties.example`).

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
  kzkt-builder ./gradlew assembleRelease -PabiFilter=arm64-v8a

# 4. Salin APK keluar dari container
CID=$(docker run -d -v kzkt-gradle:/root/.gradle \
  -v "$HOME/.android:/root/.android" \
  kzkt-builder ./gradlew assembleRelease -PabiFilter=arm64-v8a)
docker wait "$CID"
docker cp "$CID:/app/app/build/outputs/apk/release/app-arm64-v8a-release.apk" .
docker rm "$CID"
```

Kalau mau publish, mount keystore punyamu sendiri (read-only):

```bash
docker run --rm -v kzkt-gradle:/root/.gradle \
  -v "$PWD/keystore.properties:/app/keystore.properties:ro" \
  -v "$PWD/release.keystore:/app/release.keystore:ro" \
  kzkt-builder ./gradlew assembleRelease -PabiFilter=arm64-v8a
```

### Integrasi Berkelanjutan (GitHub Actions)

Ada dua workflow di `.github/workflows/`:

- **KZKT Trigger Debug** (`kzkt-trigger-debug.yml`) — setiap push / pull request ke `main`,
  unit test dan `assembleDebug` dijalankan di image Docker yang sama, lalu APK debug-nya
  diunggah sebagai **artifact** yang bisa diunduh (`kzkt-app-debug`). APK debug ini pakai
  applicationId sendiri (`com.kzkt.app.debug`) plus label dan warna ikon launcher berbeda
  (`KZKT Debug`), jadi bisa diinstall berdampingan dengan release dan gampang dibedain.
- **KZKT Auto Release** (`kzkt-auto-release.yml`) — build **APK release signed** untuk empat ABI
  (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) plus satu universal secara paralel, lalu bikin
  GitHub Release bernama `KZKT vX.Y.Z` berisi semua APK + file `sha256sums.txt`, dan kirim
  notifikasi ke Telegram. Deskripsi release diambil dari section yang cocok di `CHANGELOG.md`.
  Caranya trigger: push tag `v*`, atau jalankan manual dari tab Actions dengan isi input versi
  (mis. `1.30.4`). Keystore penandatangan di-restore dari repository secrets
  (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) — lihat
  [BUILD_RELEASE.md](BUILD_RELEASE.md).

---

## Ucapan Terima Kasih Open Source

KZKT bisa terwujud berkat karya banyak proyek dan komunitas open source:

- CYPY oleh [indravoyager](https://github.com/indravoyager/cypy) sebagai framework dasar.
- Penerjemah, penguji beta, dan kontributor yang mendukung proyek ini.

---

## Lisensi

Proyek ini dilisensikan di bawah [Lisensi MIT](LICENSE).
