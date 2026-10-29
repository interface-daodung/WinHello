# LAN Agent

.NET Windows Service exposing HTTPS to Android and a local named pipe to the Credential Provider. Pairing and approval verify TOTP and the phone's signature. After approval, the local pipe can return the stored Windows credential to LogonUI. Pipe access is limited to LocalSystem and Administrators.

Build from the repository root with `scripts/build-lan-agent.ps1`. See [root installation instructions](../../INSTALL.md) and [protocol contracts](../../contracts/README.md).
