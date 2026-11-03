# Communication contracts

The apps use two independent channels:

- [HTTP API](http.md): Android to LAN Agent over pinned HTTPS on TCP 54431. UDP 45876 is used only for discovery.
- [Named pipe](pipe.md): Credential Provider to LAN Agent on the same Windows machine.

These files describe the current wire format. They do not generate code or change runtime behavior.
