# Release artifacts

From the repository root on a Windows build machine with LLVM-MinGW, .NET 10 SDK, Android SDK/Gradle, and **Inno Setup 7 x64** installed:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1
```

The script reads `VERSION`, verifies the Android `versionName`, and writes these artifacts under `publish/`:

- `WinHello-verX.Y.Z-windows-x64-setup.exe`: Inno Setup 7 installer for the prebuilt TUI package. It installs the `winhello` command on the machine PATH. Run `winhello regi` after installation to register the Windows sign-in service and scan the TOTP QR.
- `WinHello-verX.Y.Z-android-debug.apk`: installable debug-signed Android build for testing.
- `WinHello-verX.Y.Z-SHA256SUMS.txt`: SHA-256 hashes for the two binaries.

`publish/` is ignored by Git. Upload the files as GitHub Release assets rather than committing binaries to the source tree. The Android artifact is debug-signed; a production Android release requires a separate signing keystore and release configuration. The Windows installer is not code-signed, so Windows may show an unverified publisher prompt.
