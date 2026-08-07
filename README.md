# KZKT

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge&logo=open-source-initiative&logoColor=white)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android SDK](https://img.shields.io/badge/Android-API%2026%2B-3DDC84.svg?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![ONNX Runtime](https://img.shields.io/badge/ONNX_Runtime-v1.21-0078D4.svg?style=for-the-badge&logo=onnx&logoColor=white)](https://onnxruntime.ai/)
[![OpenCV](https://img.shields.io/badge/OpenCV-v4.10.0-5C3EE8.svg?style=for-the-badge&logo=opencv&logoColor=white)](https://opencv.org/)

<p align="center"><b>English</b> · <a href="README.id.md">Bahasa Indonesia</a></p>

KZKT is a native Android application for automatic manga and comic translation. It detects speech bubbles on-page with on-device AI, sends the text to the vision LLM of your choice, and renders the translated text back into the page — all locally, with no root access required.

<div align="center">
  <table>
    <tr>
      <td align="center"><b>Translate Screen</b></td>
      <td align="center"><b>History Screen</b></td>
      <td align="center"><b>Settings Screen</b></td>
    </tr>
    <tr>
      <td><img src="docs/screenshots/translate.png" width="100%" alt="Translate Screen"></td>
      <td><img src="docs/screenshots/history.png" width="100%" alt="History Screen"></td>
      <td><img src="docs/screenshots/settings.png" width="100%" alt="Settings Screen"></td>
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
