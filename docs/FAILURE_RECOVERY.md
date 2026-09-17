# Failure and recovery

## 1. Session epochs

Every device command includes `session_epoch`. After reset or reconnection, the
glasses discard commands from older epochs. This prevents a delayed service from
a prior participant or scenario appearing in the current session.

## 2. Idempotency

Every command and inference request has a stable idempotency key. Duplicate
capture or service commands return the previous result rather than repeating the
side effect.

## 3. Expiry

```text
capture command: expires after approximately 5 s
model decision: expires approximately 20 s after production/capture policy
service display command: carries the same or stricter expiry
```

A late service is logged as `STALE_DECISION` and never displayed.

## 4. Connection loss

```text
Bluetooth loss
→ pause automatic anchors
→ retain material-change pending flag
→ reconnect until the transport is disabled, with capped backoff
→ require DEVICE_READY for current epoch
→ resume with one fresh capture
```

Reconnection is deliberately unbounded. Walking out of range and back is an
ordinary event that may take minutes, so a fixed retry window leaves the link dead
for good — see section 8.

Do not display queued cards immediately after reconnect.

## 5. P2P loss

Fallback options:

```text
small image through Bluetooth chunking
or
log transfer failure and retry one fresh capture
```

Do not reuse an old image while claiming it is current.

## 6. Inference failure

Formal mode:

```text
no provider/model fallback
no hand-authored emergency answer
log technical failure
clear pending state
continue observation sampling
```

## 7. Visible ACK failure

A service command without a visible ACK is `DISPLAY_FAILED`. No receipt is
written. A later checkpoint remains eligible because the user was never actually
served.

## 8. CXR socket identity

The CXR data channel is RFCOMM, opened with `createRfcommSocketToServiceRecord(uuid)`.
The UUID is issued by the glasses during `initBluetooth` (`onConnectionInfo`) and the
app stores it alongside the socket MAC address.

```text
stored identity present  → connect directly, no wearer action
stored identity missing  → initBluetooth, which needs the glasses in its
                           scannable state (BLE service 00009100-…), reachable
                           only by folding the temples and triple-clicking the
                           function key
```

### Rule: never discard a stored identity on a failed attempt

A socket failure is far more often transient (out of range) than a rotated UUID.
Discarding the identity converts a recoverable drop into a permanent failure that
only the physical pairing gesture can clear.

`CxrPhoneLink.connect` therefore keeps the saved identity and merely alternates
between the saved fast path and a fresh `initBluetooth` on successive attempts, so
a momentary drop recovers on its own while a genuinely rotated UUID is still
re-learned. `rememberIdentity` clears the alternation once a fresh identity is saved.

### 2026-09-17 incident

Walking out of Bluetooth range produced `CXR_SOCKET_CONNECT_FAILED`; the catch block
then deleted `socket_uuid`/`socket_address` and the link could never be restored.
Restarting the app, toggling Bluetooth on either device, restarting the glasses
process and rebooting the glasses all failed, because the phone no longer held any
identity to connect with. Fixed by the rule above.

### Recovering the socket UUID

The glasses' CXR service loads its connection info at startup and logs it:

```bash
# Path comes from /system/etc/cxr-service.json → client_connection_info_file.
adb -s <glasses> shell cat /system/etc/cxr-service.json   # cxr.connection.data
adb -s <glasses> reboot
adb -s <glasses> logcat -d | grep CXRConnectionInfo
# cxr-service: load CXRConnectionInfo: <uuid>, <base64 mac>
```

Write the UUID back into the phone's stored identity (the app is debuggable) instead
of performing the pairing gesture:

```bash
adb shell am force-stop ai.opstate.rokid.phone
# <map> with selected_address/selected_name/socket_uuid/socket_address
adb push prefs.xml /data/local/tmp/prefs.xml
adb shell run-as ai.opstate.rokid.phone cp /data/local/tmp/prefs.xml \
    /data/data/ai.opstate.rokid.phone/shared_prefs/cxr-connection.xml
```

The UUID rotates across pairings — two values observed three weeks apart differed —
so always read the current value rather than reusing an old one. Treat the value as a
credential: it is half of what the RFCOMM connect needs, and it must not be committed.
