# Session export format

## 1. Directory

```text
session-{id}/
├── session_manifest.json
├── observations.jsonl
├── sensor_windows.jsonl
├── context_events.jsonl
├── episodes.jsonl
├── captures.jsonl
├── anchors.jsonl
├── inference_requests.jsonl
├── decisions.jsonl
├── deliveries.jsonl
├── receipts.jsonl
├── user_feedback.jsonl
├── audit.jsonl
├── images/
└── SHA256SUMS.txt
```

## 2. Timestamps

Persist both when relevant:

```text
epoch milliseconds for causal/research time
elapsedRealtime milliseconds for local intervals and latency
```

Never use wall-clock differences for one-minute cadence or timeout measurement.

## 3. Anchors

`anchors.jsonl` contains scheduler-private diagnostics for audit, but the exported
model request is saved separately so leakage can be checked mechanically.

```text
anchor_id
anchor_time
trigger observation
asset ID
opaque candidate handles
trigger families
scheduler score
blockers considered
policy/config hash
```

## 4. Decisions

Persist raw and parsed forms:

```text
raw_response_json
parsed label/HOW/evidence
validation errors
model ID and request ID
produced/expires timestamps
latency
```

## 5. Deliveries

Delivery status is not inferred from the model label. It records the actual path:

```text
NO_SERVICE_SILENCE
INVALID_EVIDENCE
STALE_DECISION
SUPPRESSED_BY_RECEIPT
TRANSPORT_FAILED
DISPLAY_FAILED
DISPLAY_VISIBLE
```

## 6. Privacy

Before sharing an export:

```text
replace participant ID with alias
remove bearer tokens and endpoints
review images for bystanders and screens
remove private scheduler authoring notes
retain hashes and version identifiers
```
