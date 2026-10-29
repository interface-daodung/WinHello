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

`CREDENTIAL` consumes the approved request. Its response contains a plaintext Windows password and must remain local to the trusted sign-in path. The current wire format uses `|` separators; changing it requires coordinated changes to both apps.
