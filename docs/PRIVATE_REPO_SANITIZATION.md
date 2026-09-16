# Private repository sanitization and migration

## 1. Privacy rule

The source project is private. No report, chat response or public artifact should
contain copied source fragments, secret values, private endpoints, proprietary
assets, participant data or model credentials.

This V4 kit contains no code from the inaccessible private archive.

## 2. Required migration procedure

### Step 1: immutable staging

```text
copy archive to local secure workspace
record archive SHA-256
extract into read-only staging
reject path traversal and oversized members
never run repository scripts before inspection
```

### Step 2: dry-run inventory

Generate only:

```text
relative path
file type
byte size
SHA-256
classification
secret-rule name, never matched value
```

Do not print source contents.

### Step 3: classify every component

| Class | Action |
|---|---|
| `KEEP` | reusable build/SDK/transport/camera/sensor/display file |
| `KEEP_REWRITE` | useful core with private package/product names removed |
| `REIMPLEMENT` | behavior is useful but code is tightly coupled or license/provenance is unclear |
| `DROP` | unrelated product feature, private asset, analytics, account, model zoo or build output |
| `QUARANTINE` | credential, key, certificate, personal data or suspicious binary |

### Step 4: clean-room target

Copy only allowlisted components into a new repository. Do not delete in place and
continue using the old project. A fresh target gives a clear dependency and
provenance boundary.

### Step 5: rename and decouple

Recommended target names:

```text
private product package → ai.opstate.rokid
product-specific service → StudyRuntimeService
navigation/task-specific transport → RokidTransportPort
product camera manager → WarmCameraSession
product UI → ContextCardRenderer / ServiceCardRenderer
```

Rename only after tests capture the original behavior.

### Step 6: final leakage scan

Block release when any of the following remain:

```text
original package or product name
private hostnames and API endpoints
access tokens, secrets or signing material
participant identifiers
large private media/model assets
analytics/telemetry SDKs
unrelated domain prompts
compiled APK/AAB/build output
```

## 3. Likely keep set

The real source audit should prioritize:

```text
Rokid SDK initialization and connection adapter
Bluetooth control messages
P2P connection and image transfer
camera capture and orientation handling
IMU access and timestamps
foreground-service lifecycle
card rendering and device input
visible-display acknowledgement
permissions, ProGuard and Gradle SDK configuration
reconnection and checksum tests
```

## 4. Likely drop set

```text
unrelated navigation/game/social/product logic
login/account/profile/payment
analytics, telemetry and crash upload
provider/model catalog not used by OPSTATE
general chatbot, STT, TTS and live conversation
map/navigation path planning
large media assets, demos and screenshots
model weights, cached datasets and generated files
release signing keys, certificates and local properties
CI release publishing unrelated to the study
```

## 5. Tool usage

Dry run:

```bash
python tools/sanitize_private_repo.py \
  RealTopia-main.zip \
  --policy configs/private_repo_policy.example.json \
  --output private_sanitized_staging \
  --report reports/private_sanitization_plan.json
```

Apply after human review:

```bash
python tools/sanitize_private_repo.py \
  RealTopia-main.zip \
  --policy configs/private_repo_policy.reviewed.json \
  --output private_sanitized_staging \
  --report reports/private_sanitization_applied.json \
  --apply
```

The tool never prints code or secret values. Its default is dry-run.
