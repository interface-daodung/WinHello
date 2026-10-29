# Android LAN demo

Java app using Android's built-in `AppWidgetProvider` and `RemoteViews` APIs plus ZXing for QR scanning. The widget's scan button performs one LAN discovery and pinned HTTPS check without opening the main app. When a paired PC has a pending request, the widget shows an approval button that launches a brief biometric confirmation screen. The widget does not poll while the app is closed. During setup the app scans a QR containing the PC ID, TOTP secret, and TLS certificate pin; it encrypts the secret at rest with Android Keystore and confirms enrollment using a current RFC 6238 code. For each waiting request, a strong biometric unlocks a per-PC Android Keystore signing key; the app sends both the TOTP code and signed challenge. It never transmits a fingerprint or Windows password.

The project compiles against API 36, targets API 35, and uses Android Gradle Plugin 8.13.2 with Java 17. ZXing Android Embedded is the only added Android dependency. This workspace has JDK 17, Gradle 8.13, and Android SDK Platform 36/build tools installed. SDK licenses have been accepted.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-android.ps1
```

The checked-in script uses the local SDK and Gradle under `tools/` plus the installed Temurin JDK 17. The resulting debug APK is `apps/android/app/build/outputs/apk/debug/app-debug.apk`. VS Code also exposes this as the **Build Android APK** task.

On Android versions/devices where a strong fingerprint is unavailable, the system may offer another supported strong biometric or reject approval. BLE is deferred; no Bluetooth permission is declared. The phone approval allows the Credential Provider to submit the stored Windows password; the phone itself does not send the password.

The home screen keeps enrolled PCs visible while offline and refreshes LAN presence every five seconds while the screen is active. The `+` button opens a portrait QR scanner with a compact orange frame. The info icon explains the controls. Lock state is read from the PC's pinned HTTPS `/health` endpoint; if the Windows console state cannot be queried, the app shows only that the PC is online.
