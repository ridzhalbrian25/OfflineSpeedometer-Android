# Offline GPS Speedometer Android (Metric & Imperial)

Proyek Android Studio (Kotlin + Jetpack Compose) untuk aplikasi speedometer yang bekerja **100% secara offline** menggunakan sensor satelit GNSS/GPS perangkat (`LocationManager.GPS_PROVIDER`) tanpa memerlukan koneksi internet atau kuota data.

## 3 Cara Build / Memasang Aplikasi di Android

### Cara 1: Build APK Otomatis di Cloud (Tanpa Install Android Studio)
Proyek ini sudah dilengkapi workflow **GitHub Actions** (`.github/workflows/build-apk.yml`):
1. Buat repositori baru di GitHub dan unggah seluruh isi folder ini.
2. Buka tab **Actions** di repositori GitHub Anda -> jalankan workflow **Build Offline Speedometer Android APK**.
3. Setelah selesai (~2 menit), unduh file **`OfflineSpeedometer-debug-apk`** (`app-debug.apk`) pada bagian *Artifacts* dan pasang di ponsel Android Anda.

### Cara 2: Build APK di Android Studio / Command Line
1. Ekstrak `OfflineSpeedometer_Android.zip` lalu buka folder `OfflineSpeedometer` di **Android Studio**.
2. Pilih menu **Build -> Build Bundle(s) / APK(s) -> Build APK(s)**, atau jalankan `./build_apk.sh` (`gradle assembleDebug`).
3. File APK hasil kompilasi akan berada di `app/build/outputs/apk/debug/app-debug.apk`.

### Cara 3: Gunakan Langsung Aplikasi Web Interaktif (`speedometer_app.html`)
Buka file aplikasi web di Chrome Android lalu pilih **Tambahkan ke Layar Utama (Add to Home Screen)** untuk menggunakannya secara penuh dan offline melalui sensor GPS perangkat.
