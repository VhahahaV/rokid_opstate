# Android and Rokid runtime plan

> 历史设计文档。当前已经实现并部署 CXR 官方连接、Camera2、宿主和直连模型；实际模块见 [代码导航](CODE_MAP.md)，验收见 [当前状态](implementation/V5_ACCEPTANCE_STATUS.md)。

## 1. Foreground runtime

A one-minute research sampler cannot rely on periodic WorkManager. Run the phone
study loop inside a foreground service started while the operator Activity is
visible.

Phone foreground-service responsibilities:

```text
maintain Rokid connection
run monotonic capture ticker
maintain scenario and causal state
call remote harness
persist logs and pending uploads
```

The camera is physically on the glasses, so the glasses helper owns its camera
permission and camera foreground-service type. Optional VAD similarly belongs on
the glasses and requires microphone permission and explicit participant consent.

## 2. Service startup

```text
operator opens phone app
→ permissions are checked
→ phone connects to glasses
→ operator taps Start Session
→ foreground service starts
→ glasses helper enters study mode
```

Do not depend on boot-time background startup for camera/microphone access.

## 3. Rokid SDK abstraction

Freeze one SDK family after testing actual hardware:

```text
SecuritySdkTransport
or
LegacyCxrTransport
```

Both implement `RokidTransportPort`. A release build enables one implementation
through a Gradle flavor or dependency-injection binding. Never initialize both
SDK stacks concurrently.

## 4. Bluetooth and P2P

Bluetooth carries small, frequent messages:

```text
session epoch
sensor summaries
capture command
context/service text
ACKs and errors
```

P2P carries JPEG data. The image transfer must include:

```text
capture ID
byte length
SHA-256
width/height
rotation
timestamp
channel used
```

Bluetooth chunking remains a tested fallback for the first MVP.

## 5. Sensor placement

Use the glasses IMU rather than the phone IMU because the scheduler needs head
motion and current-view stability. Aggregate on glasses, then transmit a summary
every second. This avoids streaming 50 Hz raw sensor data over Bluetooth.

## 6. Optional VAD

The included reference gate is energy based and deliberately limited. It emits:

```text
speech_ratio
mean_dbfs
window times
```

It discards PCM immediately. It does not identify speakers, transcribe speech or
store audio. Start with VAD disabled; enable only after permission, consent and
pilot calibration are complete.

## 7. ADB development

The computer connects to the phone:

```bash
adb devices
adb reverse tcp:8765 tcp:8765
./gradlew :phone-app:installDebug
adb logcat -s OPSTATE:R RokidTransport:R StudyRuntime:R '*:S'
```

The phone debug build can call a mock server at `127.0.0.1:8765` through
`adb reverse`. The formal build points to the frozen remote endpoint and runs
without a connected computer.
