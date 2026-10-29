# WinHello

Monorepo for phone-approved Windows sign-in. The Android app discovers and pairs with a PC, then approves a challenge with TOTP and a biometric-protected signing key. The LAN Agent stores pairing and serves the Credential Provider. After approval, the Credential Provider submits the stored Windows password to Winlogon.

## Layout

| Path | Runtime | Responsibility |
| --- | --- | --- |
| `apps/android/` | Android / Java / Gradle | QR enrollment, discovery, approval, widget |
| `apps/lan-agent/` | Windows Service / .NET | HTTPS API, TOTP and signature checks, named pipe |
| `apps/credential-provider/` | LogonUI / C++ / CMake | Sign-in tile and Windows credential serialization |
| `cli/` | Windows CMD / PowerShell | `winhello` commands for registration and removal |
| `assets/` | PNG / ICO | Shared WinHello logo |
| `contracts/` | Documentation | HTTP and named-pipe messages shared across apps |
| `docs/` | Documentation | Architecture and design notes |
| `scripts/` | PowerShell | Build, installation, update and removal |
| `cmake/` | CMake | LLVM-MinGW toolchain configuration |

## Build from the repository root

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-lan-agent.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-credential-provider.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-android.ps1
```

The Android APK is at `apps/android/app/build/outputs/apk/debug/app-debug.apk`. See [architecture](docs/architecture.md) and [INSTALL.md](INSTALL.md) for the system flow and installation commands. The live Windows installation in `C:\Program Files\WinHelloLanDemo` is separate from this source tree; moving source files does not change it.

Build the distributable Windows TUI with `scripts/build-windows.ps1`. The package is written to `dist/winhello-windows/`. Run `scripts/install-cli.ps1` once to put its `winhello` command on the user PATH, then open a new CMD window and run `winhello h`.

For GitHub Release files, run `scripts/build-release.ps1` and use the artifacts in `publish/`. See [release instructions](docs/release.md).

This is a prototype. Keep a working Windows password sign-in method available. Phone approval gates a stored Windows password; TOTP alone is not a Windows authentication package.
