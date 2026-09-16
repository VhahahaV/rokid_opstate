# Private repository migration matrix template

Complete this table after the archive can be read. Keep it in the private project,
not in a public paper artifact.

| Source path/hash | Capability | Dependencies | Sensitive content | Action | Target path | Required test | Reviewer |
|---|---|---|---|---|---|---|---|
| pending | Rokid connection | pending | pending | KEEP_REWRITE | `phone/transport/...` | 30 min reconnect | pending |
| pending | P2P image transfer | pending | pending | KEEP_REWRITE | `phone/transport/...` | 50 image hashes | pending |
| pending | glasses camera | pending | pending | KEEP_REWRITE | `glasses/capture/...` | orientation/quality | pending |
| pending | IMU | pending | pending | KEEP_REWRITE | `glasses/sensor/...` | trace replay | pending |
| pending | display | pending | pending | REIMPLEMENT | `glasses/display/...` | visible ACK | pending |

## Decision rules

```text
KEEP
    already minimal, clearly licensed/owned, maps directly to target interface

KEEP_REWRITE
    useful implementation but contains private package/product coupling

REIMPLEMENT
    behavior is useful but source coupling, provenance or complexity is unsafe

DROP
    no direct OPSTATE runtime responsibility

QUARANTINE
    secret, personal data, key, certificate or suspicious binary
```
