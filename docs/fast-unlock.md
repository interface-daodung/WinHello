# Fast unlock design review

The original `fast_unlock_plan.md` cannot use the existing `PhoneSigningKey` to approve without a fingerprint. That key has `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`, so Android requires a biometric prompt for every signature. Fast unlock therefore has a separate Android Keystore signing key. Activating that key requires one biometric signature from the existing paired key, a one-use QR ticket, TOTP, and the pinned TLS connection.

The PC is the authority for expiry and revocation. The Android status is a short-lived display cache and cannot authorize an approval by itself. `/v1/demo/approve` selects the fast key only when the PC record is active, paired to the same phone, and unexpired. `FAST_OFF` deletes the record and any fast-approved pending request. Unpairing also deletes the PC record. The phone has no off control.

`isDeviceSecure()` only proves that a screen lock is configured. It does not prove that the phone is unlocked, and it cannot be attested to the PC by this protocol. The app checks `isDeviceLocked()` and the fast key uses `setUnlockedDeviceRequired(true)`. A compromised phone or app can still use the key while the device is unlocked. This is the security cost of skipping per-approval biometric authentication. Android 12–14 can reject an unlocked-device-required key after some biometric unlocks; the app falls back to the normal biometric flow when fast signing fails.

`winhello regi` installs WinHello and displays an enrollment QR. Phone pairing is a later step in the Android app; the fast ticket is available only after that step completes. The admin-only named pipe requires an elevated CLI for `winhello fast` commands because it also carries Windows credentials for LogonUI.
