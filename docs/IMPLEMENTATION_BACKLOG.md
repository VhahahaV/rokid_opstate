# Implementation backlog

## Phase 0 — private audit and hardware freeze

- [ ] Re-upload or mount the private repository archive.
- [ ] Run sanitizer in dry-run mode.
- [ ] Freeze phone model/Android version.
- [ ] Freeze Rokid model, enterprise/consumer stack and firmware.
- [ ] Choose exactly one SDK family.
- [ ] Build and run the vendor/private connection demo.
- [ ] Record camera orientation and available sensors.

Exit: one documented SDK/hardware matrix and reviewed migration plan.

## Phase 1 — clean project and transport

- [ ] Create clean `core`, `phone-app`, `glasses-app` modules.
- [ ] Port only reviewed transport/camera/sensor files.
- [ ] Rename private packages and remove unrelated dependencies.
- [ ] Implement session epoch and idempotency.
- [ ] Bluetooth control loop and reconnect.
- [ ] P2P image transfer; Bluetooth fallback.

Exit: 30-minute connection run and 50 successful image round trips.

## Phase 2 — observation runtime

- [ ] Phone foreground service.
- [ ] Glasses camera/microphone/connected-device service as needed.
- [ ] IMU aggregation at 25–50 Hz.
- [ ] Optional VAD feature flag.
- [ ] Monotonic one-minute capture ticker.
- [ ] Event-driven capture requests.
- [ ] Image quality, orientation and hash verification.

Exit: one-hour trace with no duplicated capture IDs and at least 95% valid transfers.

## Phase 3 — automatic anchor scheduler

- [ ] Scenario-backed candidate episode registry.
- [ ] Material-revision events.
- [ ] Transition-settled detector.
- [ ] Quiet/display/inference blockers.
- [ ] Periodic and post-service audit checkpoints.
- [ ] Scheduler-private logs.
- [ ] Guarantee private diagnostics are absent from model request.

Exit: deterministic replay tests and pilot review of emitted checkpoints.

## Phase 4 — frozen model path

- [ ] Multipart `/v1/evaluate` client.
- [ ] Prompt/preprocess/parser hash freeze.
- [ ] Causal history builder.
- [ ] Structured response validation.
- [ ] Raw decision log before delivery control.
- [ ] No-fallback failure path.

Exit: 20 offline anchors produce byte-equivalent request semantics through runner and phone adapter.

## Phase 5 — delivery and receipt

- [ ] Service card UI.
- [ ] True silence for NO_SERVICE.
- [ ] Two-frame visible ACK.
- [ ] Receipt commit after visible ACK only.
- [ ] Same-revision suppression.
- [ ] Material-change reactivation.
- [ ] Decision expiry and emergency clear.

Exit: scripted early/service/duplicate/reactivation sequence passes 20 consecutive times.

## Phase 6 — data/export and operator UI

- [ ] Room entities/DAOs and app-private image paths.
- [ ] Append-only audit chain.
- [ ] Session setup and scenario console.
- [ ] live connection/capture/inference status.
- [ ] pause/stop/delete/export.
- [ ] JSONL export plus manifest hashes.

Exit: exported session passes `validate_runtime_trace.py`.

## Phase 7 — pilot and freeze

- [ ] 4–6 pilot participants.
- [ ] Calibrate motion, image and VAD thresholds.
- [ ] Freeze runtime configuration.
- [ ] Freeze APK hashes and server container digest.
- [ ] Counterbalance study condition/order.
- [ ] Record technical exclusion rules before formal collection.
