# Acceptance tests

## Gate 0 — privacy and source migration

- Sanitizer reports no unreviewed secret or unsafe archive path.
- Final clean project contains no original private product name, endpoint, key,
  signing material, unrelated media or build output.
- A private provenance manifest maps every retained file to its reviewed action.

## Gate 1 — device connection

- 30 minutes connected without unrecoverable failure.
- Disconnect/reconnect produces a new ready handshake for the current epoch.
- Commands from an old epoch are discarded.
- Duplicate idempotency keys do not repeat a side effect.

## Gate 2 — image path

- 50 sequential capture round trips.
- At least 95% succeed without manual restart.
- Image hash, size and orientation match at both ends.
- P2P loss either falls back cleanly or logs a failure; no old image substitution.
- One invalid image is retried at most once.

## Gate 3 — cadence and sensors

- Baseline capture median interval is close to 60 s while the session is active.
- Material change can request an earlier capture after minimum cooldown.
- Raw IMU is not persisted.
- When VAD is enabled, raw PCM is not persisted or transmitted.
- Motion-to-stable transition emits the expected summary on recorded traces.

## Gate 4 — automatic anchors

- No candidate episode means no anchor.
- Speech, recent interaction, display-busy and inference-in-flight block anchors.
- Stable material-change and transition-settled examples emit anchors.
- Periodic coverage emits controlled hard-negative checkpoints.
- Completed/waiting/already-served candidates are not silently removed in
  `BENCHMARK_ALIGNED` mode.
- Target-model request contains no scheduler diagnostics or Gold.

## Gate 5 — causal inference

- `available_at` future event is absent from the request.
- A late caption does not alter an earlier request.
- `NO_SERVICE` with HOW is rejected.
- Evidence reference not present in causal request is rejected.
- Prompt, preprocessing, parser and model hashes are recorded.
- Model timeout is a technical failure and does not trigger another model.

## Gate 6 — service and receipt

- NO_SERVICE changes nothing on the glasses display.
- SERVICE is acknowledged only after two rendered frames.
- No visible ACK means no receipt.
- Same episode/intent/material revision is suppressed after a visible receipt.
- Higher material revision can be served again.
- Expired decision is never shown.

## Gate 7 — end-to-end sequence

Run 20 consecutive repetitions:

```text
material update
→ early checkpoint and silence
→ action-boundary checkpoint and SERVICE
→ visible ACK and receipt
→ duplicate checkpoint and suppression
→ material revision increase
→ new SERVICE
```

No manual answer injection is permitted.

## Gate 8 — export

Export contains:

```text
session_manifest.json
observations.jsonl
sensor_windows.jsonl
context_events.jsonl
episodes.jsonl
anchors.jsonl
inference_requests.jsonl
decisions.jsonl
deliveries.jsonl
receipts.jsonl
user_feedback.jsonl
audit.jsonl
images/
SHA256SUMS.txt
```

`tools/validate_runtime_trace.py` passes and all manifest hashes match.
