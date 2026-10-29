# Android to LAN Agent

Transport: HTTPS on TCP 54431. The Android app pins the certificate obtained from the enrollment QR. Discovery uses UDP 45876. JSON property names below follow the .NET endpoint records.

| Method | Path | Request | Successful response |
| --- | --- | --- | --- |
| GET | `/health` | None | Agent status, device ID, pairing and pending flags, `sessionState` (`locked`, `unlocked`, or `unknown`) for the physical console |
| POST | `/v1/pair` | `DeviceId`, `TotpCode`, `PublicKey` | `paired`, `deviceId` |
| POST | `/v1/unpair` | `DeviceId`, `TotpCode` | `unpaired` |
| GET | `/v1/demo/pending?deviceId=...` | Paired phone ID | `RequestId`, `DeviceId`, `Nonce`, `ExpiresAt` |
| POST | `/v1/demo/approve` | `DeviceId`, `RequestId`, `Signature`, `TotpCode` | `approved` |

The enrollment QR is a `winhello://enroll` URI with `pcid`, `secret`, `pin`, and `https` parameters. The `windowsAuthentication` response property still contains the legacy demo label; do not use that property as the sign-in result. The Windows result comes from LogonUI after Credential Provider serialization.
