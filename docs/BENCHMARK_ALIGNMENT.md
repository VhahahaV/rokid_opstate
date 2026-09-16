# Benchmark alignment

## 1. Preserved task

The real-device system preserves the OPSTATE decision interface:

```text
current egocentric image
+ causal model-visible history
+ opaque candidate object/episode
+ committed action receipts
→ SERVICE or NO_SERVICE
→ HOW only after SERVICE
```

## 2. Model-visible stream

Only model-visible observations and committed action receipts enter the request.
The following stay private:

```text
Gold
support-edge annotations
scheduler score and trigger family
operator criteria
authoring state
private lifecycle labels
expected answer
future events
```

## 3. Equal-time order

```text
OBSERVATION
→ EVALUATE
→ ACTION_RECEIPT
```

The receipt produced by a service at one anchor can affect only later anchors.

## 4. Automatic scheduler boundary

The offline benchmark assumes a provided evaluation anchor. The real system must
instantiate that upstream boundary, but it must not turn the scheduler into a
second answer model.

The scheduler is permitted to use:

```text
candidate availability
clock/deadline window
material event arrival
IMU stability and transition
interaction quietness
image quality and visual novelty
generic trigger-key routing
```

It is not permitted in the benchmark-aligned mode to use:

```text
Gold
expected SERVICE/NO_SERVICE
future task outcome
private authoring support
post-hoc human verdict
condition-specific thresholds
```

## 5. Negative cases must survive scheduling

OPSTATE contains important negative mechanisms such as bad timing, already
served, completed, waiting and no incremental value. The upstream scheduler must
still emit checkpoints around these cases. Otherwise the real-device system
would evaluate only obvious positives and no longer represent the benchmark.

## 6. Controlled synthetic history

Synthetic message/calendar/task events are acceptable because they instantiate
the same digital surfaces already represented in the benchmark. They must have:

```text
occurred_at
available_at
episode binding
material revision
participant-visible presentation
model-visible representation
```

Participant-visible exposure must be identical across model conditions. Only the
history passed to the target model changes.

## 7. Claims

The real-device study can support:

```text
feasibility of the task abstraction on wearable hardware
perceived usefulness/timeliness/interruption
SERVICE and true-silence behavior
receipt suppression and material-change reactivation
latency and system overhead
```

It must not be presented as:

```text
an all-day autonomous assistant
a fully evaluated open-world intent detector
sealed future benchmark generalization
proof of long-term field adoption
```
