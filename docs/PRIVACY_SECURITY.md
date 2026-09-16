# Privacy and security

## 1. Data minimization

```text
raw IMU: memory-only rolling window
raw audio: never stored
VAD: aggregate ratio only
images: app-private storage
model requests: participant alias, not identity
exports: explicit researcher action
```

## 2. Bystanders

The study protocol should avoid capturing uninvolved people where possible.
Before any external sharing, manually review and redact faces, screens, badges,
addresses and other identifying content.

## 3. Network

```text
HTTPS only in formal mode
bearer token stored using Android Keystore-backed storage
no token in logs or exported JSON
certificate and endpoint frozen before study
request IDs used for idempotency
```

## 4. Private source

Private repository files, package names, endpoints, assets and credentials are
not part of the paper artifact unless the owner explicitly approves them. The
sanitization report records hashes and actions, not code contents.

## 5. Participant control

The phone UI needs:

```text
Pause sensing
Emergency clear
Stop session
Delete current session
Export selected session
```

Stopping a session must close camera/VAD, clear glasses cards, cancel pending
inference and prevent late responses from being displayed.
