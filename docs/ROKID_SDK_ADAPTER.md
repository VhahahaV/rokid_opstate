# Rokid SDK adapter plan

> 历史设计文档。当前已经实现并部署 CXR 官方连接、Camera2、宿主和直连模型；实际模块见 [代码导航](CODE_MAP.md)，验收见 [当前状态](implementation/V5_ACCEPTANCE_STATUS.md)。

## 1. Freeze one transport family

The target project exposes one `RokidTransportPort` to the study runtime. The
implementation is selected only after the actual glasses and firmware are tested.

```text
SecuritySdkTransport
    current enterprise/work-system stack

LegacyCxrTransport
    legacy CXR-M/CXR-S or compatible private project stack
```

Do not merge both callback systems into `StudyRuntimeService`.

## 2. Adapter responsibilities

```text
scan and connect
disconnect and bounded reconnect
current connection state
control message send/receive
P2P negotiation and image receive
Bluetooth image fallback
session epoch enforcement
idempotency
hash and capture-ID correlation
SDK error normalization
```

## 3. Common error codes

Map SDK-specific values into stable study codes:

```text
DEVICE_NOT_FOUND
PAIRING_FAILED
BLUETOOTH_DISCONNECTED
P2P_NEGOTIATION_FAILED
P2P_TRANSFER_FAILED
CONTROL_SEND_FAILED
CAPTURE_TIMEOUT
CAPTURE_FAILED
HASH_MISMATCH
STALE_EPOCH
DUPLICATE_COMMAND
DEVICE_NOT_READY
```

Persist both normalized code and redacted SDK-native code for debugging.

## 4. Private-source migration

When the private project becomes readable, create one table per relevant file:

| Source capability | Target interface | Action | Tests required |
|---|---|---|---|
| connection manager | `RokidTransportPort` | port/rewrite | connect, reconnect, epoch |
| message serializer | `DeviceEnvelope` | replace or adapt | round trip, duplicate |
| P2P receiver | image channel | port | 50 transfers, hash |
| camera manager | `WarmCameraSession` | port/rewrite | orientation, exposure, retry |
| sensor manager | `AndroidImuSampler` | port/rewrite | timestamp, rate, axes |
| display page | `StudyRenderer` | rewrite | visible ACK, clear |

Never preserve a source file merely because it compiles. It must map to an
explicit OPSTATE runtime responsibility.

## 5. Recommended integration order

```text
connection only
→ small text round trip
→ clear command
→ one camera capture
→ image transfer and hash
→ sensor summary
→ Context Card
→ Service Card and visible ACK
→ automatic runtime
```
