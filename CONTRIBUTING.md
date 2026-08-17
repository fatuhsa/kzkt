# Contributing to KZKT

Thanks for wanting to contribute. This document describes how this project actually
works day-to-day: branching, experimenting, quality gates, changelog discipline, and
releasing. Read it before opening a PR or touching the build.

> Human-facing build docs live in [`BUILD_RELEASE.md`](BUILD_RELEASE.md); this file
> covers the contribution *process*. The project owner's non-negotiables are listed in
> [`AGENTS.md`](AGENTS.md) — they apply to human contributors too.

---

## Development flow

KZKT is a solo project with a simple, linear history. The flow is:

```text
feature branch (dev-kz-*) → commit per step → verify → merge to main → release
```

### 1. Branching

- `main` is the stable branch. Never commit directly to it.
- Work happens on a feature branch named `dev-kz-*` (e.g. `dev-kz-tlfree`,
  `dev-kz-debug`). Pick a short name describing the experiment or feature.
- For experiments that might fail: **commit a clean baseline first**, then commit
  every meaningful step on top. That way a failed experiment can be reverted to the
  baseline commit without losing unrelated work (`git revert` or `git reset --hard`
  to the baseline hash).

### 2. Commit early, commit often

Small, self-contained commits are preferred over one giant commit. Each commit should
compile on its own when feasible. When a change is a multi-step feature, keep the
intermediate commits on the feature branch and only squash/clean up at merge time.

### 3. Verify before merging

Every Kotlin change must pass, in order:

```bash
./gradlew compileDebugKotlin      # compile check only
./gradlew testDebugUnitTest       # unit tests
./gradlew ktlint                  # lint (baseline-aware, see below)
```

Report BUILD SUCCESSFUL/FAILED — do not merge a failing build.

### 4. Merge to main

When the feature is stable and the branch is pushed and reviewed (or self-reviewed):

```bash
git checkout main
git merge dev-kz-<feature>
git push origin main
```

The project keeps `main` and any active `dev-kz-*` branches in sync; after a merge,
push all branches that should carry the change.

---

## Code style & quality gates

- **Follow existing conventions** — look at neighboring files before writing. Compose
  snapshot state (`mutableStateOf`) must only be written on the Main thread; use the
  `post {}` pattern from `MainViewModel` for background writes.
- **ktlint with a baseline** — the repo uses `config/ktlint-baseline.xml` to tolerate
  pre-existing debt, but **new code must be 0 violations**. If you need to regenerate
  the baseline (`rm -f config/ktlint-baseline.xml && ./gradlew ktlint`), verify that
  none of *your* new lines got covered by it. Do not "fix" lint by hiding new code in
  the baseline.
- **Tests** — add unit tests for new pure logic (e.g. parsing, sorting, key
  normalization). Look at `app/src/test/java/com/kzkt/app/` for the style.
- **Don't break the build** — R8/minification is intentional on release; never disable
  it to "fix" a crash. Keep `app/proguard-rules.pro` intact. Never delete the YOLO
  asset (`app/src/main/assets/models/kzkt.dat`) or `opencv/` binaries.
- **WorkManager input data is limited to 10 KB** — never pass a big file list via
  `workDataOf`; write it to a JSON file in `cacheDir` and pass only the file path.

---

## Changelog discipline

`CHANGELOG.md` is the single source of truth for what users see on release pages —
release descriptions are extracted from it, never from git history.

- Add user-facing changes under `## [Unreleased]` with `### Added` / `### Changed` /
  `### Fixed` bullets, using the existing bold-lead style
  (e.g. `- **Feature Name**: description of what it does and why it matters.`).
- Technical refactors that don't change user-visible behavior can skip the changelog.
- **Before every release**, move the Unreleased entries into a dated section
  (`## [vX.Y.Z] - YYYY-MM-DD`) and commit + push it **before** triggering the release
  workflow — never release without a changelog section.

---

## Building

### Debug APK

```bash
./gradlew assembleDebug                              # all ABIs + universal
./gradlew assembleDebug -PabiFilter=arm64-v8a        # just your phone's ABI (fast)
./gradlew assembleDebug -PabiFilter=arm64-v8a -PciDebug=true   # com.kzkt.app.debug, installs alongside release
```

### Release APK

```bash
./gradlew assembleRelease                            # 5 APKs (4 ABIs + universal)
./gradlew assembleRelease -PabiFilter=arm64-v8a      # single ABI, half the time/size
```

Version: **always prefer the auto bump** — never hand-edit `versionCode`:

```bash
./gradlew assembleRelease -PabiFilter=arm64-v8a -PversionName=1.30.0
# versionCode derived automatically (1.30.0 → 13000000); optional -PversionCode=12345 override
```

### Verify a built APK

```bash
BT=$(ls -d $HOME/Android/Sdk/build-tools/* | tail -1)

# Version:
$BT/aapt dump badging app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  | grep -oE "versionCode='[0-9]+' versionName='[^']+'"

# Signature (expect v1/v2/v3 = true):
$BT/apksigner verify --print-certs app/build/outputs/apk/release/app-arm64-v8a-release.apk
$BT/apksigner verify --verbose app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

### Build with Docker (no local Android SDK needed)

```bash
docker build -t kzkt-builder .                       # once

docker run --rm -v kzkt-gradle:/root/.gradle \
  kzkt-builder ./gradlew assembleDebug               # debug

docker run --rm -v kzkt-gradle:/root/.gradle \
  -v "$HOME/.android:/root/.android" \
  kzkt-builder ./gradlew assembleRelease              # release (debug-keystore fallback)
```

### Send the APK to a phone (KDE Connect)

```bash
kdeconnect-cli -a                                    # find the device ID (once)
kdeconnect-cli -d <DEVICE_ID> --share /abs/path/app.apk
```

- Pick the ABI matching the phone — arm64-v8a over universal (half the size).
- Signature caveat: a debug-signed APK cannot install over a release-signed one (and
  vice versa) without uninstalling first. Warn the user if the installed build type
  differs.

---

## Known pitfalls (from AGENTS.md)

| Area | Pitfall | What to do |
|---|---|---|
| **R8/minify** | Disabling it "to fix a crash" is a regression | Keep it on; fix the code or proguard rules |
| **WorkManager** | Input data > 10 KB → `IllegalStateException` | Pass a cache file path, not the data (see `TranslationWorker`) |
| **FGS type** | Targeting SDK 35+ without `foregroundServiceType` → `InvalidForegroundServiceTypeException` | Keep `SystemForegroundService` + `tools:node="merge"` in the manifest |
| **Keystore** | Committing `.jks`/`keystore.properties` leaks secrets | Never stage them; they're gitignored |
| **Version code** | Lower/equal `versionCode` → install fails as "older version" | Always use `-PversionName` (auto-derived code) |
| **OpenCV** | `System.loadLibrary("opencv_java4")` must run before `OpenCVLoader.initLocal()` | See `KzktApplication.onCreate` — don't reorder |
| **YOLO asset** | `kzkt.dat` missing → model fails at runtime | It's committed in `app/src/main/assets/models/`; never delete |
| **AGP abiFilters vs splits** | Setting both with different ABIs fails the build | `-PabiFilter` makes `splits` control the ABI and skips `ndk.abiFilters` (handled in `app/build.gradle.kts`) |
| **Release notes** | Release body has no notes → `## [vX.Y.Z]` section missing | Add the changelog section and push it BEFORE triggering Auto Release |

---

## Releasing (maintainer)

Releases are cut with the **KZKT Auto Release** workflow, never by hand:

1. Add the changelog section (`## [vX.Y.Z] - YYYY-MM-DD`) and **commit + push it first**
   — the release job reads the file from the checked-out repo.
2. Trigger the workflow:
   ```bash
   gh workflow run kzkt-auto-release.yml -f version=1.30.0
   ```
   or via the Actions tab: **KZKT Auto Release → Run workflow → version**.
3. Verify after it finishes: the release body starts with the changelog section and all
   5 APKs + `sha256sums.txt` are attached.

The workflow builds all four ABIs plus a universal APK in parallel (using
`-PabiFilter` and `-PversionName`), auto-bumps `versionCode` from the version name, and
signs with the keystore from repo secrets. Debug builds run on every push to `main`
via **KZKT Trigger Debug** — keep that workflow green; it is part of the gate.

---

## Checklist for a contribution

- [ ] Work is on a `dev-kz-*` branch, not `main`
- [ ] Baseline committed before starting an experiment
- [ ] `compileDebugKotlin` + `testDebugUnitTest` pass
- [ ] New code is ktlint-clean (not hidden in the baseline)
- [ ] User-facing changes documented under `## [Unreleased]` in `CHANGELOG.md`
- [ ] No keystore files / secrets staged (they are gitignored)
- [ ] Version-affecting changes use `-PversionName`, not hand-edited `versionCode`
- [ ] `kzkt.dat`, `opencv/`, `app/proguard-rules.pro`, and the debug resource source
      set are untouched
