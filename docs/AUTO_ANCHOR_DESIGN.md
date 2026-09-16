# Automatic service-anchor design

## 1. The central correction

A service anchor is not a moment at which the system has already decided to
serve. It is a moment at which the system asks the OPSTATE decision model:

```text
Given the current image, causal history, candidate episode and receipts,
should the assistant intervene now?
```

Therefore the online system has three separate decisions:

```text
Observation sampler: should a new image be captured?
Anchor scheduler: should this observation become an evaluation checkpoint?
Target model: SERVICE or NO_SERVICE, then HOW if SERVICE?
```

Conflating these stages would make every stable moment look like a positive
service opportunity and would destroy alignment with the benchmark.

## 2. Why one-minute capture is not enough

One image per minute gives predictable coverage and makes the real-device stream
comparable to minute-level lifelog data. It does not reliably capture short
boundaries such as:

- closing a laptop and standing up;
- entering a checkout or decision surface;
- arriving at a door after a short movement;
- receiving a material digital update;
- returning to a task after an interruption.

The final capture planner therefore combines:

```text
60 s baseline capture
+ material-change capture
+ transition-settled capture
+ deadline/routine-window capture
```

A minimum 15 s capture interval prevents bursts. The 60 s schedule is based on
`elapsedRealtime`, not wall clock, so clock changes do not duplicate or skip
captures.

## 3. Candidate episodes come first

The MVP does not solve open-world intent discovery. Candidate episodes are
materialized from controlled synthetic events and scenario definitions.

Example:

```json
{
  "object_handle": "obj_19a1",
  "episode_handle": "ep_8f0c",
  "intent_fingerprint": "intent_2c91",
  "service_mode": "PREPARATION",
  "scheduler_eligible": true,
  "material_revision": 1,
  "scheduler_trigger_keys": [
    "surface:door",
    "transition:work_to_departure"
  ]
}
```

The handles sent to the model stay opaque. Trigger keys, scheduler score,
operator criteria and private lifecycle labels stay in scheduler-private storage.

This matches the benchmark assumption that an upstream mechanism already
provides a candidate object/episode at an evaluation anchor.

## 4. Two-stage scheduler

### Stage A: hard eligibility gates

An accepted image can become an anchor only when all required gates pass:

```text
at least one scheduler-eligible candidate episode exists
image is readable and not severely blurred or exposed
Rokid connection is ready
no inference for the same session is in flight
no context/service card is currently covering the display
minimum evaluation interval has elapsed
no active voice session or high speech ratio
no very recent touch/key interaction
pose is stable, or a permitted transition-settled micro-window is detected
```

A lack of speech is a blocker release, not a positive reason to serve.
Likewise, IMU stability only indicates a low-interruption sampling window; it
contains no service semantics by itself.

### Stage B: checkpoint score

The initial pilot score is:

```text
0.30  active candidate episode
0.25  material context change pending
0.20  movement followed by a stable micro-window
0.15  candidate deadline within 30 minutes
0.10  active routine window
0.10  sufficient visual novelty
0.05  periodic coverage with no stronger event
0.10  planned post-service audit checkpoint
```

Emit when the bounded score is at least `0.45`, with at most three candidate
episodes per accepted image. These values are calibration seeds. They must be
frozen only after pilot traces on the actual glasses.

## 5. Initial sensor calibration

Suggested starting values:

| Signal | Initial threshold | Window |
|---|---:|---:|
| IMU sampling | 25–50 Hz | continuous during session |
| stable angular RMS | below 2.5 degrees/s | 3–4 s |
| stable linear-acceleration RMS | below 0.18 m/s² | 3–4 s |
| moving angular RMS | above 12 degrees/s | 1–2 s |
| moving linear-acceleration RMS | above 0.60 m/s² | 1–2 s |
| stable ratio | at least 0.75 | 3–4 s |
| transition lookback | movement in previous 20 s | then stable |
| VAD speech ratio | at most 0.35 | 2.5 s |
| no recent interaction | at least 2.5 s | touch/key/voice |

These thresholds must be measured from the actual head-mounted sensor axes.
They are not treated as universal physiological constants.

Prefer `TYPE_ROTATION_VECTOR` plus `TYPE_LINEAR_ACCELERATION`. If the device does not expose a reliable rotation vector, use gyroscope angular velocity directly; freeze the chosen sensor path in the device manifest and calibration report.

## 6. Do not require full-body stillness

Useful anchors can occur while the user is walking. A strict “linear acceleration
must be near zero” rule would miss preparation and transition opportunities.
The recommended policy distinguishes:

```text
stationary stable window
    low angular and low linear motion

walking micro-stable head window
    low angular motion for a short period,
    linear motion may remain moderate

high-motion window
    capture can be deferred unless max staleness requires an observation;
    target-model evaluation remains blocked
```

Implement the stationary path first. Add walking micro-windows only after real
trace calibration and image-quality inspection.

## 7. Trigger families and benchmark coverage

| Trigger family | Online meaning | Benchmark role |
|---|---|---|
| `MATERIAL_CHANGE` | a new message/calendar/task update becomes available | new or reactivated service |
| `TRANSITION_SETTLED` | movement ends at a potential action boundary | why-now timing |
| `DEADLINE_APPROACH` | a candidate enters a preparation window | preparation opportunity |
| `ROUTINE_WINDOW` | a recurring context window is active | routine/personalization |
| `VISUAL_NOVELTY` | current scene materially differs from previous observation | scene transition |
| `PERIODIC_COVERAGE` | no stronger trigger but stream coverage is needed | hard negatives and missed-event audit |
| `POST_SERVICE_AUDIT` | checkpoint after a visible service | already-served negative |

The scheduler must deliberately produce negative checkpoints. In
`BENCHMARK_ALIGNED` mode it must not remove a candidate merely because an internal
state says completed, waiting or already served. Those are target-model and
receipt-control cases central to OPSTATE.

## 8. Suggested automatic sequence for one candidate

```text
T0: synthetic event appears
    → mark material change
    → capture after 3 s when quiet/stable
    → early checkpoint can produce NO_SERVICE_BAD_TIMING

T1: user moves, then stabilizes near an action boundary
    → event-driven capture
    → transition-settled checkpoint
    → possible SERVICE

T2: service becomes visibly rendered
    → write receipt
    → schedule a post-service audit checkpoint after cooldown
    → expected behavior is often NO_SERVICE_ALREADY_SERVED

T3: new material revision arrives
    → capture and checkpoint again
    → receipt no longer blocks the changed state
```

## 9. Optional semantic trigger keys

Image-derived trigger keys can improve candidate routing without deciding the
label:

```text
surface:door
surface:laptop_closed
surface:checkout
place:office
transition:work_to_departure
```

They may be produced by a lightweight observation endpoint only when a stored
image is novel or a candidate episode requests a surface check. A late caption
gets its real response time as `available_at`; it cannot modify an earlier
anchor.

The target-model request must not include:

```text
scheduler score
trigger family
expected decision
operator criteria
Gold
private lifecycle
```

## 10. Scheduler evaluation

Report scheduler behavior separately from decision quality:

```text
candidate checkpoint rate per hour
positive-opportunity coverage under human review
negative checkpoint rate
duplicate checkpoint rate
capture-to-anchor latency
anchors blocked by motion, speech, display or cooldown
energy and transfer overhead
```

The main user study should use one frozen, condition-blind scheduler for all
model conditions.
