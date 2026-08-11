# AGENTS.md — Instructions for AI Coding Agents

> **Read this before modifying or building anything in this repo.** These are the
> conventions the project owner expects every AI agent to follow. Human-facing
> build docs live in [`BUILD_RELEASE.md`](BUILD_RELEASE.md) and the README.

---

## 1. Project at a glance

KZKT is a native Android (Kotlin + Jetpack Compose, Material 3) manga-translation
app. It imports single images, whole folders, ZIP/CBZ/EPUB archives and PDFs,
detects speech bubbles with a 3-stage YOLO ONNX cascade (optional Smart Image
Upscaler + ML Kit OCR), translates via an LLM provider, and writes translated
images back. It also ships an in-app reader (page / webtoon modes, bubble
touch-up editor), an instant PDF reader for results, a glossary, and a built-in
update checker.

- **Package:** `com.kzkt.app` · **minSdk 26** · **targetSdk 36** · **compileSdk 37**
- **Kotlin 2.x** with the built-in Compose compiler plugin (AGP 9.x style build)
- **OpenCV** is a local Gradle module (`opencv/`, native `.so` in `opencv/native/libs`)
- **YOLO model asset:** `app/src/main/assets/models/kzkt.dat` — REQUIRED at runtime,
  never delete it
- **Distribution:** GitHub Releases (per-ABI APKs via CI). There is **no Play Store**.

---

## 2. Non-negotiable rules

1. **Never commit `keystore.properties` or any `.jks`/keystore file.** They are in
   `.gitignore`. If you need a keystore, copy `keystore.properties.example` and fill
   in the developer's own values.
2. **Never run `git push` / `git commit` / destructive commands** unless the user
   explicitly asks. When asked to commit, use a clear conventional message
   (`feat:`/`fix:`/`refactor:`/`ci:`/`docs:`) and push to `main`.
3. **`versionCode` must always increase** — never build a release with a lower
   version than what's installed (Android rejects the update). Prefer the auto bump
   (below) over hand-editing `app/build.gradle.kts`.
4. **Don't "fix" R8/proguard** by disabling minification. `isMinifyEnabled = true`
   is intentional. Known R8 pitfalls (WorkManager `WorkDatabase_Impl`
   NoSuchMethodException, `InvalidForegroundServiceTypeException`) are already
   handled in the build files — don't regress them.
5. **Don't delete assets/models** (`kzkt.dat`), fonts, or `opencv/` binaries.
6. **Keep `app/proguard-rules.pro` intact** — it covers ONNX Runtime, OpenCV,
   OkHttp, Gson, Room/WorkManager, ML Kit, and app model classes.
7. **Follow existing code conventions** — look at neighboring files before writing.
   Compose snapshot state (`mutableStateOf`) must only be written on the Main
   thread; use the `post {}` pattern from `MainViewModel` for background writes.
8. **WorkManager input data is limited to 10 KB.** Never pass a big file list via
   `workDataOf`; write it to a JSON file in `cacheDir` and pass only the file path
   (see `TranslationWorker.startTranslation`).
9. **Release descriptions ALWAYS come from `CHANGELOG.md` — never from git commit
   history.** The CI workflow (`kzkt-auto-release.yml`) extracts the section
   matching the version (`## [v1.30.0]`) as the release body;
   `generate_release_notes` is
   intentionally off. Keep the changelog as the single source of truth for what
   users see on the release page.
10. **Before every release, add a `CHANGELOG.md` section first.** Without
    `## [vX.Y.Z] - YYYY-MM-DD` the release body has no notes (the CI prints a
    warning and ships only the APK list). Write the section, commit + push it,
    THEN trigger the KZKT Auto Release workflow — never release without changelog.

---

## 3. Build commands (what the CI runs)

Everything below runs on Linux with `./gradlew` (Windows: `gradlew.bat`).

### 3.1 Quick validation (fast, before any big build)

```bash
./gradlew compileDebugKotlin          # compile check only
./gradlew testDebugUnitTest           # unit tests
```

> Run BOTH after any Kotlin change. Report BUILD SUCCESSFUL/FAILED to the user.

### 3.2 Debug APK (for testing)

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-<abi>-debug.apk` (with per-ABI splits, the
files are named per ABI, e.g. `app-arm64-v8a-debug.apk` / `app-universal-debug.apk`).

> CI passes `-PciDebug=true`, which suffixes the applicationId with `.debug` and the
> versionName with `-debug` (see the debug buildType in `app/build.gradle.kts`).
> The resulting APK (`com.kzkt.app.debug`) installs ALONGSIDE the release app — a
> debug-signed APK can no longer clash with the release-signed one. Local debug
> builds without the flag keep the plain `com.kzkt.app` id.

### 3.3 Release APK — all ABIs (what CI distributes)

```bash
./gradlew assembleRelease
```

Produces 5 APKs in `app/build/outputs/apk/release/`:
`app-arm64-v8a-`, `app-armeabi-v7a-`, `app-x86-`, `app-x86_64-`, `app-universal-`.

Release builds are **R8-minified, resource-shrunk, and auto-signed** with the
keystore from `keystore.properties` (or the local debug keystore as fallback).

### 3.4 Release APK — single ABI (fast, targeted)

```bash
./gradlew assembleRelease -PabiFilter=arm64-v8a
```

Only builds that one ABI (half the time, half the size). Use `arm64-v8a` for
modern phones (e.g. POCO F5), `armeabi-v7a` for old ones.

### 3.5 Auto version bump (PREFERRED — don't hand-edit versionCode)

The CI lets you bump the version from the command line without touching
`app/build.gradle.kts`:

```bash
./gradlew assembleRelease -PversionName=1.25.2
```

- `versionName=1.25.2` → `versionCode` derived automatically (`1.25.2` →
  `12502000`, always higher than previous releases).
- Optional: `-PversionCode=12345` to override manually.
- No flags → defaults `versionName=1.25.1.22`, `versionCode=1250122`.

**Always prefer `-PversionName` for a new release** so the APK is installable as an
update over the previous version.

### 3.6 Verify a built APK

```bash
BT=$(ls -d $HOME/Android/Sdk/build-tools/* | tail -1)

# Version:
$BT/aapt dump badging app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  | grep -oE "versionCode='[0-9]+' versionName='[^']+'"

# Signature (expect: Signer #1 certificate DN: CN=..., v1/v2/v3 = true):
$BT/apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk
$BT/apksigner verify --verbose app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

---

## 4. Sending the APK to the phone (KDE Connect)

This project's workflow often ends by sharing the built APK to the developer's
phone via KDE Connect:

```bash
# Find the device ID (once):
kdeconnect-cli -a

# Share an APK:
kdeconnect-cli -d <DEVICE_ID> --share /absolute/path/to/app.apk
```

- **Pick the right variant:** for a POCO F5 (arm64-v8a) prefer the arm64-v8a APK
  over the universal one (half the size).
- **Signature caveat:** a debug-signed APK cannot be installed over a
  release-signed one (and vice versa) without uninstalling first. Warn the user if
  the installed build type differs.

---

## 5. Docker build (no local Android SDK needed)

```bash
# Build the image (once):
docker build -t kzkt-builder .

# Debug:
docker run --rm -v kzkt-gradle:/root/.gradle \
    kzkt-builder ./gradlew assembleDebug

# Release (falls back to local debug keystore via $HOME/.android mount):
docker run --rm -v kzkt-gradle:/root/.gradle \
    -v "$HOME/.android:/root/.android" \
    kzkt-builder ./gradlew assembleRelease

# Extract:
docker cp <CID>:/app/app/build/outputs/apk/release/app-arm64-v8a-release.apk .
```

---

## 6. GitHub Actions (what's already wired)

- **`.github/workflows/kzkt-trigger-debug.yml`** — **KZKT Trigger Debug** (CI on
  every push / PR to `main`): builds via the Dockerfile, runs unit tests, uploads
  the per-ABI debug APK artifacts. It passes `-PciDebug=true`, so the debug APK
  has applicationId `com.kzkt.app.debug` and installs alongside the release app
  instead of requiring an uninstall.
- **`.github/workflows/kzkt-auto-release.yml`** — **KZKT Auto Release** (manual
  dispatch): multi-job matrix (prepare → build 5 ABI variants in parallel → create
  GitHub Release + Telegram notification). Version comes from the workflow input
  or the git tag.
  Uses `-PabiFilter` per job and `-PversionName` from the input. The release body
  is **extracted from `CHANGELOG.md`** (the `## [vX.Y.Z]` section for the version
  being released) — commit history is NOT used (`generate_release_notes` is off).

If you add release/CI features, keep this structure: `prepare → build (matrix) →
release`, and always pass `-PabiFilter` + `-PversionName` to Gradle.

### 6.1 Cutting a release (ALWAYS in this order)

1. **Add a section to `CHANGELOG.md`**: `## [v1.30.0] - YYYY-MM-DD` with
   `### Added` / `### Changed` / `### Fixed` bullets. This exact text becomes the
   GitHub release description.
2. **Commit + push the changelog** to `main` — the release job reads the file
   from the checked-out repo, so it must already be on the branch.
3. **Trigger the release**:
   ```bash
   gh workflow run kzkt-auto-release.yml -f version=1.30.0
   ```
   or via the GitHub UI: **Actions → KZKT Auto Release → Run workflow → version**.
4. **Verify after it finishes**: the release body starts with the changelog
   section (not commit history) and all 5 APKs + `sha256sums.txt` are attached.

---

## 7. Gotchas / known pitfalls

| Area | Pitfall | What to do |
|---|---|---|
| **R8/minify** | Disabling it "to fix a crash" is a regression | Keep it on; fix the underlying code or proguard rules |
| **WorkManager** | Input data > 10 KB → `IllegalStateException: Data cannot occupy more than 10240 bytes` | Pass a cache file path, not the data (see `TranslationWorker`) |
| **FGS type** | Targeting SDK 35+/36 without `foregroundServiceType` → `InvalidForegroundServiceTypeException` | Keep the `SystemForegroundService` + `tools:node="merge"` block in the manifest |
| **Keystore** | Committing `.jks`/`keystore.properties` leaks secrets | Never stage them; they're gitignored |
| **Version code** | Lower/equal `versionCode` → install fails as "older version" | Always use `-PversionName` bump (auto-derived code) |
| **OpenCV** | `System.loadLibrary("opencv_java4")` must run before `OpenCVLoader.initLocal()` | See `KzktApplication.onCreate` — don't reorder |
| **YOLO asset** | `kzkt.dat` missing → model fails to load at runtime | It's committed in `app/src/main/assets/models/`; never delete |
| **AGP `abiFilters` vs `splits`** | Setting both with different ABIs fails the build | `-PabiFilter` makes `splits` control the ABI and skips `ndk.abiFilters` (already handled in `app/build.gradle.kts`) |
| **Release notes** | Release body has no notes / only the APK list → the `## [vX.Y.Z]` section is missing from `CHANGELOG.md` | Always add the changelog section and push it BEFORE triggering Auto Release (rules 9–10) |

---

## 8. Before finishing a task

1. Compile + unit tests pass (`compileDebugKotlin` + `testDebugUnitTest`).
2. If the user asked for an APK: build the **right variant** (release + matching
   ABI), verify version & signature, and share it via KDE Connect.
3. If asked to commit: conventional message, only relevant files, push to `main`.
4. Summarize concisely: what changed, verification results, and how the user can
   test it.

---

## 9. Communication style (owner preferences)

The owner keeps communication clean and minimal. Follow these rules in every
reply and in every artifact you produce:

1. **No emojis. Ever.** Not in chat replies, instructions, commit messages,
   GitHub release descriptions, Telegram notifications, or documentation
   (`CHANGELOG.md`, READMEs, `AGENTS.md`, code comments).
2. **Be concise and structured.** Use short paragraphs, headers, tables, and code
   blocks where they add clarity — no filler, no celebration, no bullet spam.
3. **Report concrete results**: `BUILD SUCCESSFUL`/`FAILED`, version codes, file
   paths, and actual command outputs — never vague summaries.
4. **Mirror the user's language** — reply in the language they write in
   (Indonesian or English).
