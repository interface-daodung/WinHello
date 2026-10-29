# WinHello Credential Provider demo

This native C++/COM DLL exposes a tile in LogonUI only while the LAN Agent named pipe reports ready. It can request a phone approval and display the result. It submits no Windows credential and cannot unlock an account.

Build with VS Code + LLVM-MinGW UCRT + CMake/Ninja:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-credential-provider.ps1
```

Use [INSTALL.md](../../INSTALL.md) for the unified guarded install, enrollment, reset, and complete uninstall. Only register in a disposable VM and retain a working recovery sign-in method.
