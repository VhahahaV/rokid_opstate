# Observation and capture pipeline

## 1. Runtime timeline

```text
Glasses IMU at approximately 50 Hz
    ↓ aggregate every 1 s
Phone receives motion/quiet summary
    ↓
Phone capture planner checks 60 s baseline and event triggers
    ↓ CAPTURE_REQUEST over Bluetooth
Glasses camera captures one JPEG
    ↓ P2P preferred, Bluetooth chunks as fallback
Phone verifies hash, orientation and quality
    ↓
Observation is appended with occurred_at and available_at
    ↓
Anchor scheduler may emit zero or more candidate checkpoints
```

## 2. Camera session modes

### Study-performance mode

Open a low-resolution repeating preview at session start, allow about 1.2 seconds
for exposure/white balance/anti-banding to settle, and reuse the initialized
session for one-shot JPEG capture. This reduces capture latency during a short,
controlled study.

### Low-power mode

Open the camera shortly before a planned baseline capture and close it after the
image. This saves power but increases latency and image variation.

Use study-performance mode for the controlled experiment, then measure thermal
and battery behavior before considering longer field sessions.

## 3. Image format

Initial configuration:

```text
1280 × 720 JPEG
quality 75–80
corrected rotation metadata
SHA-256 over exact transmitted bytes
optional 320 × 180 local thumbnail
```

The phone stores the JPEG in app-private files and stores only metadata/path/hash
in Room.

## 4. Quality gate

The included local reference analyzer checks:

```text
mean luminance
edge-energy blur proxy
64-bit dHash novelty
resolution and rotation
SHA-256
```

If the image is invalid:

```text
retry once after a short delay
otherwise log IMAGE_REJECTED
never call the target model with a silently substituted old image
```

Thresholds must be calibrated on actual Rokid captures.

## 5. Observation captioning

Captioning every minute is optional and should not block the stream.

Recommended policy:

```text
store every accepted baseline image
caption only when:
    visual novelty is high, or
    a candidate episode requires a surface trigger, or
    the image is selected as an anchor
```

The controlled study can rely mainly on synthetic message/calendar/task history
and send the current image directly to the target VLM. This avoids creating an
unnecessary second memory system.

## 6. Backpressure

Only one capture and one target-model inference should be active per session.
When image upload or inference is slow:

```text
new periodic tick is coalesced
material-change request remains pending
expired capture command is discarded by glasses
expired model decision is never displayed
```

Do not queue many stale minute captures.
