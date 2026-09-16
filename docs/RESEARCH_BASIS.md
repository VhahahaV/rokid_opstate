# Engineering research basis

The design was cross-checked against the following primary sources:

- Android WorkManager `PeriodicWorkRequest` API: periodic work is inexact and its
  minimum interval is not suitable for one-minute capture.
- Android foreground-service documentation: camera and microphone service types
  and while-in-use permissions must be declared and started from an allowed
  visible flow.
- Android Sensor Framework and `SensorEvent`: rotation vector and linear
  acceleration provide the orientation/motion inputs used by the summary layer.
- Rokid Glass3 SDK overview and troubleshooting: phone/glasses use a dual-end
  architecture; Bluetooth is appropriate for discovery/control and P2P for
  images/large payloads.
- The project's OPSTATE benchmark protocol: model-visible observations and
  receipts are separated from Gold/authoring state; anchors are
  candidate-conditioned and receipts follow evaluation.

All numeric motion, image and latency thresholds in this kit are engineering
starting points and require pilot calibration on the actual hardware.
