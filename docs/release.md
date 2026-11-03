# Release artifacts

From the repository root on a Windows build machine with LLVM-MinGW, .NET 10 SDK, Android SDK/Gradle, and **Inno Setup 7 x64** installed:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1
```

The script reads `VERSION`, verifies the Android `versionName`, and writes these artifacts under `publish/`:

- `WinHello-verX.Y.Z-windows-x64-setup.exe`: Inno Setup 7 installer for the prebuilt TUI package. It installs the `winhello` command on the machine PATH. Run `winhello regi` after installation to register the Windows sign-in service and scan the TOTP QR.
- `WinHello-verX.Y.Z-android.apk`: Android release APK signed with the configured release keystore.
- `WinHello-verX.Y.Z-SHA256SUMS.txt`: SHA-256 hashes for the two binaries.
- `latest.json`: latest version, APK URL/file and SHA-256, and Windows installer filename and SHA-256.

`publish/` is ignored by Git. Upload the files as GitHub Release assets rather than committing binaries to the source tree. Keep the Android release keystore configured in `.env` and reuse it for every release. The Windows installer is not code-signed, so Windows may show an unverified publisher prompt.

## Update server

Use `start-server.bat` on the publishing PC. It creates a temporary Quick Tunnel, registers the current hostname at the stable Worker, and serves files from `publish/`. Build Android with the default Worker URL from `config/update-server.json`; do not build with the temporary Quick Tunnel hostname.

## Update security notes

The public endpoint uses HTTPS and the updater checks the APK SHA-256 before passing it to Android. The hash currently comes from the same `latest.json` as the APK, so it detects accidental corruption but is not an independent signature: an attacker who can replace both files (for example, through a compromised origin/account or a device with a malicious trusted CA) could replace the hash too. Android's package installer also checks that an update is signed by the same app signing key as the installed app. Release APKs use the configured Android release keystore. Keep that signing key protected and consistent or Android will reject upgrades. A signed update manifest with an embedded verification key would further protect against a compromised Worker. Keep Cloudflare account access protected with MFA and do not treat a Quick Tunnel URL as private; anyone with it can download the published installers.

## Applying updates

Build both signed Android and Windows release artifacts with `scripts\build-release.ps1`, then start `start-server.bat` to publish the new `publish\latest.json` and binaries. On Windows, run `winhello update`; it checks the Windows installer hash, requests Administrator approval, and updates the CLI plus an installed LAN Agent/Credential Provider while preserving pairing data. On Android, open WinHello; it checks the manifest periodically, asks before downloading, verifies the APK hash, then opens Android's installer for the final confirmation.

Android release builds require the existing release keystore settings in the repo `.env` file. Keep that same keystore for every update so Android can install over the existing app without uninstalling it. If an installed copy was signed with a different key (for example, a debug build), Android will reject the update; uninstalling that copy removes its local pairing data. `versionCode` is derived from `VERSION`, so increase the version before each release.
# Cloudflare Worker and Quick Tunnel

The Worker at `https://api-183.interface-daodung.workers.dev` is the stable public URL. The repo contains the deployed proxy code in `worker/src/index.js`. The original deployed Worker answered `Hello World!`, so there was no proxy implementation to retrieve; it has now been replaced with the repo version. A KV namespace is bound in `worker/wrangler.jsonc` to hold the active Quick Tunnel hostname.

## One-time Worker setup

1. Install Node.js and cloudflared, then from the repo run `npx wrangler@latest login`.
2. A KV namespace was created for this Worker and its ID is stored in `worker/wrangler.jsonc`.
3. Deploy from the repo: `npx wrangler@latest deploy --config worker/wrangler.jsonc`.
4. Set the Worker control secret: `npx wrangler@latest secret put CONTROL_SECRET --config worker/wrangler.jsonc`. Use a long random value and retain it; the first `start-server.bat` run asks for that same value and stores it encrypted for the current Windows user.

If the authenticated account does not own the `interface-daodung` workers.dev subdomain, Cloudflare will deploy to another URL. Update `config/update-server.json` and Android `WINHELLO_UPDATE_BASE_URL` accordingly.

After deployment, run `start-server.bat`. It serves `publish/` locally, starts a Quick Tunnel, and registers that random origin at the Worker using the secret. Keep its window open. Closing it asks the Worker to clear the origin; abrupt PC/network failure is detected through `/__status` and returns 503 for downloads. On each restart, cloudflared creates a new random origin and the script registers it automatically. Check status with `winhello server`; `winhello app` and `winhello update` use the stable Worker URL.

The Worker only accepts Quick Tunnel HTTPS origins, only proxies GET/HEAD download requests, and never exposes the stored origin through public status. Protect `CONTROL_SECRET`; anyone who obtains it can redirect downloads. HTTPS protects transport against ordinary on-path interception, while a compromised Cloudflare account/Worker or publisher can still serve malicious files. The APK SHA-256 is delivered by the same Worker, so it detects accidental corruption but does not authenticate the publisher against a fully compromised Worker. Android also checks package signing continuity during installation.

