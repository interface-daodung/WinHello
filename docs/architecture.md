# Architecture

```text
Android app (Java)
  | UDP discovery 45876; HTTPS 54431 with certificate pinning
  v
LAN Agent (C# Windows Service, LocalSystem)
  | local named pipe: STATUS, START, CREDENTIAL
  v
Credential Provider (C++ DLL inside LogonUI)
  | serialized Windows credential
  v
Winlogon / LSA
```

## Ownership

- `apps/android/` owns QR enrollment, local TOTP storage, biometric-backed signing and the widget.
- `apps/lan-agent/` owns pairing records, challenge state, TOTP and signature verification, HTTPS, UDP discovery, and the encrypted Windows credential file.
- `apps/credential-provider/` owns the Windows sign-in tile, its interaction with the Agent pipe, and credential serialization for Winlogon.
- `contracts/` records the messages crossing application boundaries. Changes to these messages need coordinated updates to both sides.

Each app keeps its native build system: Gradle, `dotnet`, or CMake. Root PowerShell scripts provide one place to run those builds and manage the Windows installation. No shared runtime code is required between Java, C#, and C++.

The installed service and DLL live in `C:\Program Files\WinHelloLanDemo`. Enrollment data lives in `C:\ProgramData\WinHelloLanDemo`. Neither location moves when the source tree changes.
