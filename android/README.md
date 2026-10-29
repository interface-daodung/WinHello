# Android LAN demo

Java app using Android's built-in `AppWidgetProvider` and `RemoteViews` APIs plus ZXing for QR scanning. Tapping its home-screen widget opens the app and starts one UDP broadcast discovery scan. During setup it scans a QR containing the PC ID, TOTP secret, and TLS certificate pin; it encrypts the secret at rest with Android Keystore and confirms enrollment using a current RFC 6238 code. For each waiting request, a strong biometric unlocks a per-PC Android Keystore signing key; the app sends both the TOTP code and signed challenge. It never transmits a fingerprint or Windows password.

The project targets API 36 and uses Android Gradle Plugin 8.13.2 with Java 17. ZXing Android Embedded is the only added Android dependency. This workspace has JDK 17, Gradle 8.13, and Android SDK Platform 36/build tools installed. SDK licenses have been accepted.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-android.ps1
```

The checked-in script uses the local SDK and Gradle under `tools/` plus the installed Temurin JDK 17. The resulting debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`. VS Code also exposes this as the **Build Android APK** task.

On Android versions/devices where a strong fingerprint is unavailable, the system may offer another supported strong biometric or reject approval. BLE is deferred; no Bluetooth permission is declared. TOTP plus biometric-backed approval remains demo-only and does not unlock Windows.
