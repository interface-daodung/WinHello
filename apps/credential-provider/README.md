# Windows Credential Provider

Native C++/COM DLL loaded by LogonUI. It shows a phone sign-in option, requests approval through the LAN Agent pipe, and submits the stored Windows password to Winlogon after approval.

Build from the repository root with `scripts/build-credential-provider.ps1`. See [root installation instructions](../../INSTALL.md) and [pipe contract](../../contracts/pipe.md).
