# Credential Provider to LAN Agent

Local named pipe: `\\.\pipe\WinHello.LanAgent`. Commands and responses are UTF-8 lines. The pipe ACL permits LocalSystem and Administrators. The service runs as LocalSystem; the Credential Provider is loaded by LogonUI.

| Command | Response | Meaning |
| --- | --- | --- |
| `STATUS` | `READY|<deviceId>|IDLE` | Agent available, no request |
| `STATUS` | `READY|<deviceId>|UNPAIRED` | No paired phone; hide WinHello sign-in option |
| `STATUS` | `READY|<deviceId>|PENDING|<requestId>` | Waiting for phone |
| `STATUS` | `READY|<deviceId>|APPROVED|<requestId>` | Phone approved |
| `START` | `PENDING|<requestId>` or `APPROVED|<requestId>` | Create or reuse current request |
| `START` | `ERROR|not_paired` | No phone is paired; do not create a request |
| `CREDENTIAL` | `CREDENTIAL|<domain>|<username>|<password>` or `ERROR|<reason>` | Return stored Windows credential after approval |
| `FAST_STATUS` | `ACTIVE|<days>|<expiresUtc>` or `INACTIVE` | Read PC-authoritative fast unlock state |
| `FAST_CREATE <days>` | `TICKET|<pcId>|<ticket>|<days>|<certPin>` or `ERROR|not_paired` | Create a 10-minute, one-use activation ticket for a paired PC; 1-30 days |
| `FAST_OFF` | `OK` or `ERROR|persistence_failed` | Revoke fast unlock and any pending fast approval |
| `PAIRING_STATUS` | `UNPAIRED` or `PAIRED|<phoneDeviceId>|<base64DeviceName>|<pairedAtUtc>` | Local administrator query used by `winhello pair` |
| `UNPAIR_LOCAL` | `OK` or `ERROR|not_paired_or_persist_failed` | Revoke this PC's paired phone, fast-unlock record and pending approvals; local administrator only |

`CREDENTIAL` consumes the approved request. Its response contains a plaintext Windows password and must remain local to the trusted sign-in path. The current wire format uses `|` separators; changing it requires coordinated changes to both apps.
