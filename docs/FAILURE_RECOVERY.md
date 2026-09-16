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
→ attempt bounded reconnect
→ require DEVICE_READY for current epoch
→ resume with one fresh capture
```

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
