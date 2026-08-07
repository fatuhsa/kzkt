# 📦 Panduan Build APK Release Signed

Panduan ini menjelaskan cara menghasilkan APK release (R8 minify + resource shrinking)
yang **sudah ter-sign** — baik dengan keystore custom Anda sendiri maupun fallback
debug keystore untuk pengujian cepat.

---

## Cara 1 — Build cepat (fallback debug keystore)

Tanpa konfigurasi apa pun, `assembleRelease` otomatis menandatangani dengan
debug keystore lokal (`~/.android/debug.keystore`):

```bash
./gradlew assembleRelease
```

Hasil:

```
app/build/outputs/apk/release/app-release.apk   ← sudah signed (v1+v2+v3)
```

> ⚠️ APK debug-signing **tidak boleh** dipublikasikan ke Play Store.
> Gunakan hanya untuk instalasi pribadi / pengujian antar perangkat.

---

## Cara 2 — Keystore custom (wajib untuk publikasi)

### 2.1 Buat keystore Anda sendiri (sekali saja)

```bash
keytool -genkeypair -v \
  -keystore kzkt-release.jks \
  -alias kzkt \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass PASSWORD_ANDA \
  -keypass PASSWORD_ANDA
```

Isi data organisasi/pribadi saat diminta. **Simpan file `.jks` dan password di
tempat aman** — jika hilang, Anda tidak akan pernah bisa update APK yang sudah
terbit (Play Store menolak APK dengan tanda tangan berbeda).

### 2.2 Buat `keystore.properties`

Salin template lalu isi sesuai keystore Anda:

```bash
cp keystore.properties.example keystore.properties
# edit keystore.properties — isi storeFile, storePassword, keyAlias, keyPassword
```

Contoh isi:

```properties
storeFile=/home/username/kzkt-release.jks
storePassword=password-keystore-rahasia
keyAlias=kzkt
keyPassword=password-kunci-rahasia
```

> `keystore.properties` **tidak pernah di-commit** (sudah ada di `.gitignore`),
> sehingga setiap developer/CI bisa punya keystore sendiri tanpa konflik.

### 2.3 Build

```bash
./gradlew assembleRelease
```

### 2.4 Verifikasi tanda tangan

```bash
# Ganti <BT> dengan versi build-tools Anda, mis. /home/username/Android/Sdk/build-tools/36.0.0
/home/username/Android/Sdk/build-tools/36.0.0/apksigner verify --verbose \
  app/build/outputs/apk/release/app-release.apk
```

Output yang benar:

```
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Number of signers: 1
```

---

## Install ke HP

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` = reinstall (update) tanpa kehilangan data, asalkan tanda tangan sama.

---

## Catatan penting

| Hal | Keterangan |
|---|---|
| **R8 + shrink** | Aktif di build release (`isMinifyEnabled`, `isShrinkResources`) — APK jauh lebih kecil. |
| **Proguard** | Rules sudah disiapkan untuk ONNX Runtime, OpenCV, OkHttp, Gson, Room/WorkManager, ML Kit, dan model app. |
| **Versi** | Naikkan `versionCode`/`versionName` di `app/build.gradle.kts` setiap rilis. |
| **Keystore** | JANGAN commit `.jks` / `keystore.properties`. Simpan offline + backup. |
| **CI** | Workflow GitHub Actions (`ci.yml`) sudah menjalankan unit test + build debug; tambahkan upload-artifact jika ingin publikasi dari CI. |

---

## Troubleshooting

| Gejala | Solusi |
|---|---|
| `./gradlew assembleRelease` gagal di R8 | Pastikan `proguard-rules.pro` utuh; error `NoSuchMethodException`/`InvalidForegroundServiceTypeException` sudah di-fix di versi ini. |
| `apksigner` tidak ditemukan | Pakai path build-tools yang terpasang: `ls ~/Android/Sdk/build-tools/`. |
| `storeFile` tidak ketemu | Pastikan path di `keystore.properties` absolut dan file ada. |
| Crash `WorkDatabase_Impl` / `SystemForegroundService` | Sudah diperbaiki via proguard keep-rules + manifest `foregroundServiceType=dataSync`. |
