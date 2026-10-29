# WinHello LAN Agent

.NET 10 ASP.NET Core host with Windows Service integration. It listens on HTTPS TCP 54431, responds to one UDP discovery ping on 45876, and exposes a named pipe for the Credential Provider. Pairing and approvals require an RFC 6238 TOTP and a signature from the enrolled Android Keystore key. The setup secret and certificate pin are delivered by a temporary QR during install.

Use the unified install and uninstall flows in [INSTALL.md](../../INSTALL.md). Installation registers the logon tile only after phone enrollment succeeds. The service records phone approval but does not authenticate a Windows account.

For development, provide a TLS PFX with `WINHELLO_TLS_PFX` and optionally `WINHELLO_TLS_PASSWORD`; the agent writes a development QR under ProgramData. Build with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-lan-agent.ps1
```
