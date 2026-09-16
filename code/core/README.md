# OPSTATE phone-centered core

This directory is a dependency-free Kotlin reference implementation of the parts
that must remain deterministic and testable outside Android:

- IMU window feature extraction;
- one-minute plus event-driven capture planning;
- benchmark-aligned anchor checkpoint scheduling;
- causal `available_at` history filtering;
- opaque candidate-episode registry;
- frozen inference request/response validation;
- visible-ACK-gated receipt policy;
- single-owner runtime state machine.

Run:

```bash
./run_tests.sh
```

Requires JDK 17–23 (17 or 21 recommended). The root Gradle wrapper downloads the
fixed Kotlin compiler version; no global `kotlinc` installation is needed. The
script works with macOS's default Bash and can be called from any directory.
Use `make demo` from the repository root to run just the two simulations.

The Android overlay adapts platform sensors, Rokid transport, Camera2 and Room
into these domain types. Scheduler diagnostics are intentionally absent from the
model request classes.
