# Android to LAN Agent

Transport: HTTPS on TCP 54431. The Android app pins the certificate obtained from the enrollment QR. Discovery uses UDP 45876. JSON property names below follow the .NET endpoint records.

| Method | Path | Request | Successful response |
| --- | --- | --- | --- |
| GET | `/health` | None | Agent status, device ID, pairing and pending flags, `sessionState` (`locked`, `unlocked`, or `unknown`) for the physical console; `fastUnlock.active` and `fastUnlock.expiresAtUtc` |
| POST | `/v1/pair` | `DeviceId`, `TotpCode`, `PublicKey` | `paired`, `deviceId` |
| POST | `/v1/unpair` | `DeviceId`, `TotpCode` | `unpaired` |
| GET | `/v1/demo/pending?deviceId=...` | Paired phone ID | `RequestId`, `DeviceId`, `Nonce`, `ExpiresAt` |
| POST | `/v1/demo/approve` | `DeviceId`, `RequestId`, `Signature`, `TotpCode` | `approved` |
| POST | `/v1/fast-unlock/activate` | `DeviceId`, `Ticket`, `Days`, `FastPublicKey`, `Signature`, `TotpCode` | `expiresAtUtc` |

The enrollment QR is a `winhello://enroll` URI with `pcid`, `secret`, `pin`, and `https` parameters. The `windowsAuthentication` response property still contains the legacy demo label; do not use that property as the sign-in result. The Windows result comes from LogonUI after Credential Provider serialization.

Fast activation uses `winhello://fast-unlock` with `pcid`, `ticket`, `days`, `pin`, and `name`. The activation signature is made with the existing biometric-gated pairing key over UTF-8 `FAST_ACTIVATE\n<pcId>\n<ticket>\n<days>\n<fastPublicKey>`. Fast approval sets `Fast: true` on `/v1/demo/approve` and signs the usual challenge with a separate Android Keystore key usable only while the phone is unlocked. The PC checks its own expiry and revocation state on every fast approval. Normal approval continues to use the biometric-gated key.
