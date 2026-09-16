# Architecture V4

## 1. Research boundary

This system is a controlled real-device instantiation of the OPSTATE task. It is
not a general-purpose smart-glasses assistant.

```text
Benchmark or scenario layer
    provides causal observations, candidate episodes and timestamps

Upstream scheduler
    selects evaluation checkpoints

Target model
    decides WHEN and, only after SERVICE, generates HOW

Delivery control
    validates evidence, expiry and receipts

Rokid glasses
    capture the live current scene and render the final service
```

## 2. Components

### Phone

The phone is the system owner. It maintains one authoritative state machine and
persists every transition before or immediately after external side effects.

```text
StudyRuntimeService
├── RokidTransportPort
├── ScenarioRuntime
├── CausalContextStore
├── EpisodeRegistry
├── ObservationPipeline
├── AnchorScheduler
├── FrozenHarnessClient
├── EvidenceValidator
├── DeliveryController
├── ReceiptPolicy
└── StudyRepository / ExportManager
```

### Glasses

The glasses app must remain thin:

```text
StudyGlassesController
├── AndroidImuSampler
├── optional VadOnlyGate
├── WarmCameraSession
├── ImageUploader
├── ContextCardRenderer
├── ServiceCardRenderer
└── VisibleAckController
```

### Remote harness

The remote harness is the only component allowed to execute the frozen target
model. The phone must not silently replace it with a local or alternate model.

## 3. Channels

| Channel | Payload | Requirement |
|---|---|---|
| Bluetooth | discovery, session control, sensor summaries, capture command, cards, ACKs | always available after pairing |
| Wi-Fi P2P | JPEG images and larger files | preferred paper-ready media path |
| Bluetooth image fallback | chunked JPEG with checksum and retransmission | MVP and emergency fallback |
| HTTPS | phone to frozen harness | authenticated and version-frozen |

## 4. Ownership invariants

1. The phone owns session and trial state.
2. The glasses never write receipts.
3. A model response is logged before delivery policy changes it.
4. A receipt is committed only after a two-frame visible ACK.
5. `available_at` controls causal visibility.
6. Scheduler diagnostics are private and never sent to the target model.
7. The scheduler is condition-blind in the main user study.
8. Gold and authoring-private fields never enter the runtime model request.

## 5. Runtime modes

### `BENCHMARK_ALIGNED`

Used for the main controlled deployment. It over-generates checkpoints and
preserves completed, waiting, already-served and bad-timing cases for the target
model to judge.

### `DEPLOYMENT_OPTIMIZED`

A later field-demo mode. It may use stronger pre-model suppression and service
budgets, but its results cannot be mixed with the benchmark-aligned condition
without separate scheduler evaluation.

### `MANUAL_DEBUG`

Allows a developer to request capture or anchor creation. Every manual action is
logged and excluded from automatic-scheduler metrics.
