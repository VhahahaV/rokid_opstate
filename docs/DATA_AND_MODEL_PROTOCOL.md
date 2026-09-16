# Data and model protocol

## 1. Causal event semantics

Every event has two times:

```text
occurred_at: when the event happened in the world
available_at: when the runtime/model could actually access it
```

Model visibility at anchor time `t` is based only on:

```text
available_at <= t
```

A caption that returns after the anchor is not retroactively visible.

## 2. Model request

The phone sends a multipart request:

```text
request.json
current.jpg
```

The JSON contains:

```text
protocol version
request/anchor identifiers
anchor time
current asset ID and image hash
opaque object/episode handles
causal observations
committed action receipts
prompt and preprocessing hashes
```

It excludes scheduler-private and evaluator-private state.

## 3. History organization

For a short controlled deployment, use a bounded causal view:

```text
recent live observations
scenario-provided synthetic events
candidate-related observations
committed receipts
```

Do not implement an extra vector database unless a later field study genuinely
requires retrieval. Long-term history can be pre-authored and loaded into the
phone with realistic `occurred_at` and `available_at` timestamps.

## 4. Response contract

`NO_SERVICE` must contain no HOW fields.

```json
{
  "request_id": "req-...",
  "decision_id": "decision-...",
  "decision": "NO_SERVICE",
  "evidence_observation_ids": ["obs-..."],
  "produced_at_epoch_ms": 0,
  "expires_at_epoch_ms": 0
}
```

`SERVICE` requires mode, title and body.

```json
{
  "request_id": "req-...",
  "decision_id": "decision-...",
  "decision": "SERVICE",
  "service_mode": "PREPARATION",
  "title": "Head to B302",
  "body": "The meeting moved to B302; leaving now gives enough walking time.",
  "evidence_observation_ids": ["obs-message-change"],
  "produced_at_epoch_ms": 0,
  "expires_at_epoch_ms": 0
}
```

## 5. Validation order

```text
HTTP and JSON schema
→ request ID correlation
→ evidence IDs are causal and visible
→ SERVICE/HOW structural rules
→ text-length rules
→ decision expiry
→ raw response immutable log
→ receipt suppression
→ transport delivery
→ visible ACK
→ receipt commit
```

## 6. No silent fallback

Formal mode freezes:

```text
model ID
endpoint
prompt hash
image preprocessing hash
parser hash
timeouts
```

A timeout is a technical failure. The app must not silently switch model or
provider because such a fallback changes the experimental condition.

## 7. Optional observation endpoint

`POST /v1/observe` may return:

```text
short caption
scene tags
trigger keys
quality confidence
```

It is upstream observational processing, not the WHEN/HOW decision. Its model,
prompt and latency must still be logged and frozen if used in the study.
