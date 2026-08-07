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

  <p><b>English</b> · <a href="README.id.md">Bahasa Indonesia</a></p>
</div>

KZKT is a native Android application for automatic manga and comic translation. It detects speech bubbles on-page with on-device AI, sends the text to the vision LLM of your choice, and renders the translated text back into the page — all locally, with no root access required.

<div align="center">
  <table>
    <tr>
      <td align="center" width="33%"><b>Translate Screen</b></td>
      <td align="center" width="33%"><b>History Screen</b></td>
      <td align="center" width="33%"><b>Settings Screen</b></td>
    </tr>
    <tr>
      <td align="center" width="33%"><img src="docs/screenshots/translate.png" width="100%" alt="Translate Screen"></td>
      <td align="center" width="33%"><img src="docs/screenshots/history.png" width="100%" alt="History Screen"></td>
      <td align="center" width="33%"><img src="docs/screenshots/settings.png" width="100%" alt="Settings Screen"></td>
    </tr>
  </table>
</div>

---

## Highlights

- **Wide input support** - single images, whole folders, multi-image share, archives (ZIP / CBZ / EPUB), and PDF files.
- **Translate to 14 languages** - English, Indonesian, Japanese, Korean, Mandarin, Spanish, French, German, and more.
- **Multi-provider LLM** - Google Gemini, OpenAI, OpenRouter, Zen, OpenCode Go, or any OpenAI-compatible local endpoint (Ollama, LM Studio, LocalAI, vLLM).
- **On-device detection** - YOLO (ONNX Runtime) finds speech bubbles locally, with optional ML Kit OCR for non-vision models.
- **PDF in, PDF out** - render PDF pages, translate, and reassemble the translated pages back into a PDF.
- **Background translation** - keeps translating even when the app is closed, then saves results to `/Download/KZKT/`.

---

## Tech Stack

| Layer | Technologies |
| :--- | :--- |
| Language & Core | Kotlin, Java 17 |
| UI | Jetpack Compose, Material 3, Navigation Compose, Coil |
| Concurrency & State | Coroutines, Flow, ViewModel, DataStore Preferences |
| Machine Learning | ONNX Runtime Android (`com.microsoft.onnxruntime:onnxruntime-android:1.21.0`) |
| Computer Vision | OpenCV 4.10.0 Android SDK (`libopencv_java4.so` C++ JNI) |
| Networking & JSON | OkHttp 4.12, Gson (lenient mode) |
| Target API | `minSdk = 26` (Android 8.0), `targetSdk = 36` (Android 15+) |

---

## Pipeline

```text
[ Input image / manga page ]
           |
           v
[ 1. YOLO ONNX bubble detection ] --> bounding boxes
           |
           v
[ 2. OpenCV filtering & smart crop ] --> remove SFX, merge overlaps
           |
           v
[ 3. Mosaic builder ] --> pack crops into vertical RTL mosaic + red ID labels
           |
           v
[ 4. Vision LLM provider ] --> Gemini / OpenAI / OpenRouter / custom local
           |
           v
[ 5. Text renderer & masking ] --> in-bubble mask + auto-scaled wrapped text
           |
           v
[ Output in /Download/KZKT/ ] --> visible in gallery instantly
```

---

## Project Structure

```text
├── app/
│   └── src/main/
│       ├── java/com/kzkt/app/
│       │   ├── core/           # pipeline, YOLO ONNX engine, OpenCV, text renderer
│       │   ├── core/providers/ # Gemini, OpenAI, OpenRouter, custom providers
│       │   ├── data/           # settings & history (DataStore persistence)
│       │   ├── ui/             # Compose screens (Translate, History, Settings)
│       │   ├── ui/component/   # reusable Material 3 components
│       │   └── util/           # helpers
│       ├── assets/             # encrypted YOLO model (kzkt.dat) & fonts
│       └── AndroidManifest.xml
├── opencv/                     # OpenCV 4.x Android SDK module (native JNI)
├── build.gradle.kts
├── settings.gradle.kts
├── Dockerfile                  # self-contained Android build image (JDK 17 + SDK)
└── README.md
```

---

## Requirements

- **JDK 17** — e.g. [Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17).
- **Android SDK** — `compileSdk = 37`, `minSdk = 26`, `targetSdk = 36` (installed automatically via Android Studio's SDK Manager).
- **Gradle 9.6.1** — no manual install needed; the `gradlew` wrapper downloads it for you.
- **Android Studio** (latest stable) — recommended for Gradle sync, editing, and emulators.

---

## Build the APK

> The YOLO model file (`kzkt.dat`, ~100 MB) is committed directly in this repository,
> so cloning takes a bit longer to download the large file.

1. Clone the repository:
   ```bash
   git clone https://github.com/kouzen-neo/kzkt.git
   cd kzkt
   ```

2. Open in Android Studio, let Gradle sync, then build:
   ```bash
   ./gradlew assembleDebug
   ```

3. Install the APK:
   ```text
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Build with Docker

The repo ships a self-contained `Dockerfile` (JDK 17 + Android SDK) so you can build without installing Android Studio:

```bash
# 1. Build the builder image (first time only)
docker build -t kzkt-builder .

# 2. Debug APK (Gradle cache persists via a named volume)
docker run --rm -v kzkt-gradle:/root/.gradle \
  kzkt-builder ./gradlew assembleDebug

# 3. Release APK — falls back to your local debug keystore
#    (mount ~/.android so the keystore is available in the container)
docker run --rm -v kzkt-gradle:/root/.gradle \
  -v "$HOME/.android:/root/.android" \
  kzkt-builder ./gradlew assembleRelease

# 4. Copy the APK out of the container
CID=$(docker run -d -v kzkt-gradle:/root/.gradle \
  -v "$HOME/.android:/root/.android" \
  kzkt-builder ./gradlew assembleRelease)
docker wait "$CID"
docker cp "$CID:/app/app/build/outputs/apk/release/app-release.apk" .
docker rm "$CID"
```

For publishing, mount your own keystore read-only:

```bash
docker run --rm -v kzkt-gradle:/root/.gradle \
  -v "$PWD/keystore.properties:/app/keystore.properties:ro" \
  -v "$PWD/release.keystore:/app/release.keystore:ro" \
  kzkt-builder ./gradlew assembleRelease
```

### Continuous Integration (GitHub Actions)

Every push / pull request to `main` is built automatically by [GitHub Actions](.github/workflows/android.yml) using the **same Dockerfile**: unit tests + `assembleDebug` run in a container, and the debug APK is uploaded as a downloadable **artifact** (`kzkt-app-debug`) on the run page. Gradle dependencies are cached between runs, so follow-up builds are much faster.

---

## Open-Source Acknowledgments

KZKT is made possible by the work of many open-source projects and communities:

- CYPY by [indravoyager](https://github.com/indravoyager) for the base framework.
- OpenCV, ONNX Runtime, and Google ML Kit for on-device vision and OCR.
- Jetpack Compose and Material 3 for the UI toolkit.
- Native Android rewrite and development by kouzen-neo.
- Translators, beta testers, and contributors who support the project.

---

## License

This project is licensed under the [MIT License](LICENSE).
