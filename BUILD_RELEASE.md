# Building KZKT APKs (Debug & Signed Release)

This guide explains how to build KZKT — from a quick **debug** APK for testing to a
minified, **signed release** APK for distribution — with either your **own keystore**
or the local debug-keystore fallback.

---

## Prerequisites

- JDK 17+
- Android SDK (the project uses `compileSdk = 37`, `targetSdk = 36`), or simply open
  the project in Android Studio and let it set everything up.
- First build downloads Gradle + dependencies (internet required once).

> On Linux, the Gradle wrapper is `./gradlew` (Windows: `gradlew.bat`).

---

## 1. Debug build (fast, for testing)

Produces an installable APK with the `debug` build type — no minification, signed
automatically with the debug keystore:

```bash
./gradlew assembleDebug
```

Output:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device/emulator:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> The debug APK shares the debug-keystore signature with the release fallback
> below, so you can install one over the other without losing app data.

---

## 2. Release build — quick (debug-keystore fallback)

With no configuration at all, `assembleRelease` signs the APK with the local debug
keystore (`~/.android/debug.keystore`):

```bash
./gradlew assembleRelease
```

Output (already signed, R8-minified + resource-shrunk):

```
app/build/outputs/apk/release/app-release.apk
```

> **Debug-signing must NOT be published** to the Play Store. Use it only for
> personal installation / testing across devices.

---

## 3. Release build — your own keystore (required for publishing)

### 3.1 Generate a keystore (once)

```bash
keytool -genkeypair -v \
  -keystore kzkt-release.jks \
  -alias kzkt \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass YOUR_PASSWORD \
  -keypass YOUR_PASSWORD
```

Fill in your name/organization when prompted. **Keep the `.jks` file and passwords in
a safe place** — if they are lost, you can never update an already-published APK
(the Play Store rejects APKs with a different signature).

### 3.2 Create `keystore.properties`

```bash
cp keystore.properties.example keystore.properties
# edit keystore.properties — set storeFile, storePassword, keyAlias, keyPassword
```

Example:

```properties
storeFile=/home/username/kzkt-release.jks
storePassword=your-store-password
keyAlias=kzkt
keyPassword=your-key-password
```

> `keystore.properties` is **never committed** (it is in `.gitignore`), so every
> developer / CI machine can use their own keystore without conflicts.

### 3.3 Build

```bash
./gradlew assembleRelease
```

### 3.4 Verify the signature

```bash
# Replace <BT> with your build-tools version, e.g. /home/username/Android/Sdk/build-tools/36.0.0
<BT>/apksigner verify --verbose \
  app/build/outputs/apk/release/app-release.apk
```

Expected output:

```
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
```

---

## 4. Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` reinstalls/updates without wiping app data, as long as the signature matches.

---

## 5. Build with Docker (no local Android SDK required)

The self-contained `Dockerfile` (JDK 17 + Android SDK) builds both variants inside
a container — nothing but Docker is needed on the machine. First build downloads
the image layers; later builds reuse them. The `kzkt-gradle` volume keeps the
Gradle dependency cache across runs.

```bash
# 1) Build the image (once, ~5–10 min first time)
docker build -t kzkt-builder .

# 2) Build debug + release and extract the APKs
CID=$(docker run -d -v kzkt-gradle:/root/.gradle \
      -v "$HOME/.android:/root/.android" \
      kzkt-builder ./gradlew assembleDebug assembleRelease)
docker wait "$CID"
docker cp "$CID:/app/app/build/outputs/apk/debug/app-debug.apk" .
docker cp "$CID:/app/app/build/outputs/apk/release/app-release.apk" .
docker rm "$CID"
```

- The `-v "$HOME/.android:/root/.android"` mount lets the release build fall back to
  your local **debug keystore** (quick installs). For Play Store publishing, instead
  mount your keystore read-only:
  `-v "$PWD/keystore.properties:/app/keystore.properties:ro" -v "$PWD/kzkt-release.jks:/app/kzkt-release.jks:ro"`.
- Skip the Gradle-cache volume if you want a completely clean build every time
  (slower: dependencies are re-downloaded).

> Verified: `assembleDebug` + `assembleRelease` both succeed in the container
> (v2-signed release APK; YOLO model + OpenCV `.so` embedded correctly).

---

## Notes

| Topic | Details |
|---|---|
| **R8 + shrink** | Enabled on release (`isMinifyEnabled`, `isShrinkResources`) — much smaller APK. |
| **Proguard** | Rules already cover ONNX Runtime, OpenCV, OkHttp, Gson, Room/WorkManager, ML Kit, and app model classes. |
| **Versioning** | Bump `versionCode`/`versionName` in `app/build.gradle.kts` for every release. |
| **Keystore** | NEVER commit `.jks` / `keystore.properties`. Back them up offline. |
| **CI** | `.github/workflows/kzkt-trigger-debug.yml` builds via the same `Dockerfile`, runs unit tests, and uploads the debug APK as a workflow artifact. |
| **Model assets** | `app/src/main/assets/models/kzkt.dat` (YOLO) is required at runtime and must stay in the repo. |

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `./gradlew assembleRelease` fails during R8 | Keep `app/proguard-rules.pro` intact. Known R8 crashes (`WorkDatabase_Impl` NoSuchMethodException, `InvalidForegroundServiceTypeException`) are already fixed in this project. |
| `apksigner` not found | Use the build-tools path you actually have: `ls ~/Android/Sdk/build-tools/`. |
| `storeFile` not found | Make sure the path in `keystore.properties` is absolute and the file exists. |
| `com.android.dx` / dex errors | Clean and rebuild: `./gradlew clean assembleRelease`. |
