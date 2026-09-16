# OPSTATE Rokid 手机中心式真实场景部署：完整开发文档

**版本**：V5  
**日期**：2026-09-03  
**定位**：面向 OPSTATE Benchmark 在线/真机验证与 User Study 的研究原型。  
**现场硬件**：Android 手机 1 台 + Rokid 智能眼镜 1 副。电脑仅用于 Android Studio/Gradle/ADB 开发调试；正式实验可拔掉。推理由远程 Frozen OPSTATE Harness 完成。

## 1. 项目目标与边界

系统要把离线 OPSTATE 任务映射到真实场景：眼镜提供实时第一人称图像，手机维护 synthetic/真实历史与 candidate episode，自动选择 Evaluation Anchor，远程模型执行 WHEN/HOW，最后把 SERVICE 显示到眼镜；NO_SERVICE 必须表现为真正沉默。

核心等价关系：

```text
Offline Benchmark:
current image + candidate episode + causal history + receipts -> WHEN/HOW

Online Deployment:
live Rokid image + candidate episode + causal history + receipts -> WHEN/HOW
```

不开发通用聊天助手、复杂长期 Memory Writer、向量数据库、实时视频流、开放世界服务发现、多模型 Provider 管理或成熟 consumer app。

## 2. 总体架构

```text
Computer (development only)
└─ Android Studio / Gradle / ADB / Logcat / Local Mock Harness

Android Phone (runtime hub)
├─ StudyRuntimeService
├─ Scenario Engine
├─ Causal Context Store
├─ Candidate Episode Registry
├─ Observation Scheduler
├─ Sensor / Interaction State
├─ Auto Anchor Scheduler
├─ Frozen Harness Client
├─ Delivery Policy
├─ Receipt Manager
├─ Room / Files / Audit Log
└─ Operator Console / Export
        │
        ├─ Bluetooth: control, status, cards, ACK
        └─ Wi-Fi P2P preferred: JPEG; Bluetooth chunking fallback
        ▼
Rokid Glasses
├─ CameraController
├─ SensorSummaryService
├─ CommandReceiver
├─ ImageTransfer
├─ ContextCardRenderer
├─ ServiceCardRenderer
└─ VisibleAckEmitter

Android Phone ──HTTPS multipart──> Frozen OPSTATE Harness
```

设计原则：复杂逻辑集中在手机；眼镜保持薄客户端；模型端保持冻结；Observation、Anchor、Service 三层严格解耦。

## 3. 推荐代码结构

```text
opstate-rokid-study/
├── common/
│   ├── protocol/
│   ├── model/
│   ├── schema/
│   └── util/
├── phone-app/
│   ├── runtime/
│   ├── rokid/
│   ├── scenario/
│   ├── context/
│   ├── observation/
│   ├── anchor/
│   ├── inference/
│   ├── delivery/
│   ├── receipt/
│   ├── data/
│   ├── logging/
│   └── ui/
├── glasses-app/
│   ├── runtime/
│   ├── transport/
│   ├── capture/
│   ├── sensor/
│   ├── display/
│   └── ui/
├── mock-harness/
├── scenarios/
├── schemas/
├── scripts/
└── docs/
```

## 4. 手机端功能

### 4.1 StudyRuntimeService

Foreground Service，负责整个 session 生命周期：连接眼镜、加载 scenario、启动定时采样、接收事件、生成 anchor、调用模型、投递服务、提交 receipt、写日志、异常恢复。

```kotlin
enum class RuntimeState {
    IDLE, CONNECTING, READY, RUNNING,
    CAPTURING, EVALUATING, DELIVERING,
    PAUSED, ERROR
}
```

约束：单 active session、单 capture in flight、单主 inference/anchor；session reset 清理 transient state。

### 4.2 RokidTransport

统一封装 SDK，避免业务层绑定具体 Rokid API。

```kotlin
interface RokidTransport {
    val state: StateFlow<TransportState>
    val events: Flow<TransportEvent>
    suspend fun scan(): List<RokidDevice>
    suspend fun connect(device: RokidDevice)
    suspend fun disconnect()
    suspend fun sendCommand(command: StudyCommand): DeliveryToken
    suspend fun requestCapture(request: CaptureRequest)
    suspend fun clearDisplay()
}
```

最终只启用一个实现：`SecuritySdkTransport` 或 `LegacyCxrTransport`。禁止正式运行时同时初始化两套 SDK。

### 4.3 Scenario Engine

管理实验场景、虚拟时间、synthetic events、reset protocol。Public scenario 只能包含参与者/运行时可见信息，不能包含 Gold、expected decision、private lifecycle、why-now、negative type 等 authoring 信息。

### 4.4 Causal Context Store

只做因果历史，不做复杂 memory。事件可包括：OBSERVATION、MESSAGE、CALENDAR、TASK_STATE、DOCUMENT_STATE、WEARABLE、ACTION_RECEIPT。

每条事件至少包含：`event_id, event_type, available_at, episode_handle, object_handle, payload, material_revision`。

模型历史严格：

```python
visible_events = [e for e in all_events if e.available_at <= anchor_time]
```

任何迟到 caption、标签或 synthetic event 都以真实 `available_at` 为准，不能回填改变过去 anchor。

### 4.5 Candidate Episode Registry

Auto Anchor 不从零做开放世界意图发现。Candidate 来源于 Scenario、synthetic event、真实日历/消息、routine registry 等上游模块。

模型只看到 opaque `episode_handle/object_handle`；trigger keys、scheduler score、private semantics 仅供 scheduler 使用。

### 4.6 Observation Scheduler

基线：每 60 秒请求眼镜拍 1 张图。推荐初始参数：

```text
baseline_interval = 60s
max_staleness = 90s
min_capture_interval = 15s
material_change_delay = 3s
transition_lookback = 20s
image = 1280x720 JPEG quality 80
camera_warmup = ~1.2s
bad_image_retry = 1
```

除周期采样外允许 event-driven capture：material change、motion→stable、deadline window、post-service audit、manual debug。所有触发进入统一 Capture Debouncer，禁止并发拍摄。

### 4.7 Image Quality Gate

JPEG decode、尺寸、平均亮度、过暗/过曝比例、blur score、SHA-256。状态：VALID / TOO_DARK / TOO_BRIGHT / BLURRY / CORRUPTED。最多自动重拍一次。

### 4.8 Sensor State Store

眼镜端原始 IMU 只在短 rolling buffer 中存在，手机只保存摘要：angular RMS、linear acceleration RMS、stable ratio、motion state、transition flag。

Pilot seed：稳定窗口可从 `angular RMS < 2.5 deg/s`、`linear accel RMS < 0.18 m/s²`、`stable_ratio >= 0.75` 起步；最终必须真机 pilot 校准，不能直接作为论文固定常数。

### 4.9 Interaction State Store

保存最近手机触控、眼镜按键、Context/Service Card 显示、recording state；可选 VAD-only speech ratio。默认不持续录音、不做 ASR、不保存 PCM。

### 4.10 Auto Anchor Scheduler

输入：latest observation、active candidates、visible history、IMU summary、interaction state、receipt state、time。输出 0..N Evaluation Anchors。

Hard Gate：candidate 存在、image valid、transport healthy、无 inference/capture 冲突、无 service card 正在显示、cooldown 满足、低打扰窗口满足。

初始评分：

```text
active candidate base       +0.30
material change             +0.25
motion -> stable            +0.20
deadline approaching        +0.15
routine window              +0.10
visual novelty              +0.10
periodic coverage           +0.05
post-service audit          +0.10
```

建议 `emit_threshold = 0.45`。同一 current image 最多评估 3 个 candidate，但每个 candidate 必须独立构造模型请求，不能把多个 episode 混成一个 WHEN。

关键原则：IMU 稳定只说明“现在相对适合评估”，绝不能直接等价为“现在应该服务”。

### 4.11 Anchor Cooldown

初始建议：same episode 90s；post-service audit 20–45s；material change 可绕过普通 cooldown；routine 至少 10min。正式参数经 pilot 冻结。

### 4.12 Inference Request Builder / Harness Client

请求包含：current image、anchor、candidate handles、causal observations、committed receipts、prompt/preprocess/parser hashes、model ID。HTTPS multipart：`request.json + current_image.jpg`。

支持 transport retry、timeout、schema validation、idempotency。模型 timeout/非法响应必须记 `TECHNICAL_FAILURE`，绝不能自动当作 NO_SERVICE。

### 4.13 Raw Decision Store

模型原始结果收到后立即不可变落盘，和最终 delivery 分开保存。

### 4.14 Delivery Policy

按顺序检查：schema、evidence、expiry、session epoch、duplicate receipt、material revision、当前是否已有 service card。最终状态：DELIVER / NO_SERVICE / SUPPRESS_DUPLICATE / SUPPRESS_STALE / SUPPRESS_INVALID_EVIDENCE / SUPPRESS_SESSION_CHANGED / TECHNICAL_FAILURE。

### 4.15 Receipt Manager

只有收到眼镜 `DISPLAY_VISIBLE_ACK` 才 commit Receipt。Receipt 至少含：receipt_id、anchor_id、episode_handle、intent_fingerprint、material_revision、delivered_at、service_mode。

同 episode + 同 intent + 同 material revision 已服务 → suppress；material revision 变化 → 允许再次服务。

### 4.16 Operator Console

页面：Connection、Session Setup、Scenario Control、Live Runtime、Trial Detail、Logs、Export、Settings。Live 页面展示连接状态、Scenario、虚拟时间、最新 Observation、active candidates、latest anchor、raw decision、final delivery、receipt count、latency。正式实验隐藏 Gold/expected decision/private notes。

### 4.17 Session Export

导出 `session_manifest.json`、context/observation/sensor/anchor/request/response/delivery/receipt/audit JSONL、images/ 和 `SHA256SUMS.txt`。

## 5. 眼镜端功能

### 5.1 GlassesRuntime

仅负责连接、相机、IMU、显示和 ACK。状态：DISCONNECTED / READY / CAPTURING / TRANSFERRING / DISPLAYING / ERROR。

### 5.2 CameraController

支持 remote capture、1280×720、JPEG quality 80、预热、orientation metadata、单 capture in flight。返回 capture_id、timestamp、width/height、orientation、sha256。

### 5.3 SensorSummaryService

优先 rotation vector + linear acceleration，gyroscope fallback。25–50Hz raw sampling；4 秒 rolling buffer；每秒向手机发送 summary。raw IMU 不长期落盘。

### 5.4 CommandReceiver

支持：SESSION_START、SESSION_RESET、CAPTURE_REQUEST、SHOW_CONTEXT_CARD、SHOW_SERVICE_CARD、CLEAR_DISPLAY、PING、EMERGENCY_CLEAR。

### 5.5 Context Card Renderer

表示世界/数字环境发生的事件，例如消息、日历、任务更新。必须与 Agent Service Card 在视觉和语义上明确区分。

### 5.6 Service Card Renderer

Service Card 表示 Agent 真正主动介入。建议 480×640 黑底高对比，标题 <=40 chars，body schema <=140 chars，中文实际最好 35–55 字。避免聊天气泡、长文本、多页阅读。

### 5.7 True Silence

NO_SERVICE 时眼镜不能显示“无需服务”、processing、toast、声音或震动，界面保持完全不变。

### 5.8 Visible ACK

收到 SERVICE_CARD 后：render queued → first frame → second frame → DISPLAY_VISIBLE_ACK。Receipt 必须等待此 ACK。

### 5.9 User Action

第一版只需回传 DISMISS 和 TIMEOUT；后续可加 ACKNOWLEDGE / OPEN_MORE。

## 6. 模型端功能

### 6.1 Frozen Harness API

```text
POST /v1/evaluate
GET  /v1/health
GET  /v1/contract
```

### 6.2 输入

anchor 包含 anchor_id/time、trigger observation、candidate object/episode handles；history 只含 causal observations 和 committed receipts；另带 frozen contract hashes。Current image 作为 multipart 文件上传。

### 6.3 任务

模型只做 WHEN：SERVICE / NO_SERVICE；只有 SERVICE 才生成 HOW：mode/title/body/evidence_refs。

### 6.4 输出

SERVICE 示例：

```json
{
  "decision": "SERVICE",
  "confidence": 0.81,
  "service": {
    "mode": "PREPARATION",
    "title": "现在可以出发",
    "body": "组会地点已改到 B302，现在出发更稳妥。"
  },
  "evidence_refs": ["event_message_02", "obs_021"]
}
```

NO_SERVICE 时 `service` 必须为 null。

### 6.5 Evidence Validation Contract

模型只能引用 current image、anchor 前 causal-visible events、committed receipts。引用 future event → INVALID_EVIDENCE。

### 6.6 Server Logging

保存 request_id、anchor_id、model_id、prompt_hash、token usage、model latency、raw output、parser result。Gold 永不进入模型 prompt。

## 7. 端到端运行流程

```text
Start Session
→ Phone connects Rokid
→ Health-check Harness
→ Load Scenario
→ Start Observation Scheduler

Synthetic/real event arrives
→ Causal Store
→ Candidate Registry update
→ optional Context Card
→ material revision update

60s/event trigger
→ CAPTURE_REQUEST
→ Rokid JPEG
→ Phone quality gate
→ Observation persisted

Observation persisted
→ candidate lookup
→ IMU/interaction hard gate
→ anchor score/cooldown
→ Evaluation Anchor

Anchor
→ causal snapshot
→ multipart request
→ Frozen Harness
→ raw decision immutable save

Raw SERVICE
→ Delivery Policy
→ Service Card
→ DISPLAY_VISIBLE_ACK
→ Receipt commit

Raw NO_SERVICE
→ log only
→ true silence
```

## 8. Benchmark-Aligned Anchor 策略

正式论文建议保留两种 profile：

`BENCHMARK_ALIGNED`：过生成 checkpoint、不使用 Gold、不提前求解 lifecycle、保留 waiting/already-served/no-incremental-value/bad-timing 等 negative anchor，用于主要研究。

`DEPLOYMENT_OPTIMIZED`：更严格 cooldown、interaction gate 和 candidate pruning，用于续航或产品 demo。

User Study 主结果优先使用 BENCHMARK_ALIGNED。

## 9. 会议场景示例

13:40 synthetic message：14:00 组会从 A201 改到 B302。

Anchor A：用户仍在电脑前工作，candidate exists、IMU stable。Scheduler 可生成 anchor；模型 NO_SERVICE；眼镜保持沉默。

Anchor B：13:51 用户合电脑、拿包、起身，motion→stable + deadline approaching；模型 SERVICE；眼镜显示“现在可以出发…B302…”。Visible ACK 后写 Receipt。

Anchor C：13:52 用户进入走廊、没有新 material change；post-service audit 可产生 anchor。即便模型输出相似 SERVICE，Delivery Policy 也可 SUPPRESS_DUPLICATE。

Anchor D：13:54 新消息“会议提前到 13:55”，material revision 增加；允许重新服务。

## 10. 通信协议

统一 envelope：schema_version、message_id、session_id、session_epoch、sent_at、type、payload，并加入 idempotency_key。重连后旧 epoch 命令不得重新显示。

控制/状态走 Bluetooth；图片优先 Wi-Fi P2P，Bluetooth chunk transfer 作为 fallback。

## 11. Android 服务与本地存储

手机 `StudyRuntimeService` 使用 connected-device foreground service；眼镜 capture runtime 使用 camera foreground service；均由用户从可见 UI 显式启动。

Room 表建议：sessions、context_events、candidate_episodes、observations、sensor_windows、anchors、inference_requests、model_decisions、delivery_events、action_receipts、audit_events。JPEG 放 app-private files，不放 Room BLOB。

## 12. ADB 开发流程

```bash
adb devices
./gradlew :phone-app:installDebug
./gradlew :glasses-app:installDebug
adb reverse tcp:8765 tcp:8765
```

开发期手机可访问电脑 `http://127.0.0.1:8765` 的 Mock Harness；正式实验切换远程 HTTPS endpoint，并拔掉电脑做完整回归。

## 13. Mock Harness

支持 always_service、always_no_service、scripted、delay、malformed、timeout，专门测试 SERVICE display、true silence、timeout、invalid response、stale result、receipt、reconnect。

## 14. 日志与审计

关键事件 append-only：SESSION_START、CONTEXT_EVENT、CAPTURE_REQUEST、OBSERVATION_PERSISTED、ANCHOR_EMITTED、INFERENCE_SENT、RAW_DECISION、DELIVERY_DECISION、DISPLAY_VISIBLE、RECEIPT_COMMITTED、SESSION_STOP。建议 hash chain：`hash_n = SHA256(hash_(n-1) + canonical_event_n)`。

## 15. 性能与稳定性目标

初始目标：Bluetooth command P95 <300ms；capture→image ready P95 <2.5s；image transfer P95 <3s；model P95 <8s；decision→visible P95 <500ms；anchor→visible service median <8s、P95 <12s。超过 TTL 的决策必须丢弃。

正式实验前：30min 无不可恢复断线；50 次 capture 成功率 >=95%；20 次连续模型调用无 protocol error；20 次 SERVICE Visible ACK 正确；duplicate receipt suppression 正确；future isolation 100%。

## 16. 隐私

默认不保存连续音频、不保存 raw IMU，只保存 sensor summary；图片只在 app-private storage；session 可整包删除；导出需显式操作。若启用 VAD，只保存 speech_ratio，不做 ASR，不保存 PCM。

## 17. 开发阶段

Phase 0 Hardware Gate：型号/固件/Android/SDK/ADB/Bluetooth/P2P/Camera/Display。  
Phase 1 Transport：connect/heartbeat/command/image/ACK/reconnect。  
Phase 2 Runtime：Foreground Service/session/scenario/Room/log。  
Phase 3 Observation：1-min capture/quality gate/IMU/event capture。  
Phase 4 Auto Anchor：candidate/hard gate/score/cooldown/negative preservation。  
Phase 5 Model：frozen request/API/schema/evidence/raw decision。  
Phase 6 Delivery & Receipt：card/silence/visible ACK/receipt/material change。  
Phase 7 Study UI：operator console/scenario/export/recovery。  
Phase 8 Pilot：4–6 人。  
Phase 9 Freeze：APK hashes、server/model/prompt/parser/preprocess hashes、scenario hashes、anchor policy、questionnaire、analysis plan。

## 18. 功能优先级

P0：Phone↔Glasses transport、Camera、Observation Scheduler、Scenario/History、Candidate Registry、Auto Anchor、Remote WHEN/HOW、Service Card、True Silence、Visible ACK、Receipt、Logs、Export。

P1：P2P image transfer、IMU transition trigger、shadow inference、automatic retry、audit hash chain。

P2：VAD-only、visual novelty、真实 Calendar/Message integration、routine mining。

明确不做：通用聊天、全天候 ASR、复杂 memory writer、向量库、实时视频、开放世界服务发现、多 provider 平台。

## 19. 推荐编码顺序

第一条闭环：Phone → CAPTURE_REQUEST → Rokid → JPEG → Phone → Mock Harness → SERVICE → Rokid Card → DISPLAY_VISIBLE_ACK → Receipt。

第二条：NO_SERVICE → 完全不显示。

第三条：60s Observation → Candidate → Auto Anchor → Frozen Harness。

第四条：Receipt suppression → Material change → re-service。

这四条全部稳定后，再扩展正式场景和 User Study。

## 20. 完成定义

工程完成：手机独立运行、Rokid 独立连接、正式实验可拔电脑。  
Benchmark 对齐：相同 prompt/parser/schema/causal-history/receipt 语义。  
实验完成：真实 SERVICE、真实 silence、真实 receipt suppression、真实 material-change reactivation。  
可复现完成：完整 session export、hashes、logs、版本冻结。

> 最终系统定义：OPSTATE Rokid Study System 是一套以 Android 手机为研究运行中枢、以 Rokid 眼镜提供真实第一人称感知和主动服务呈现、以冻结远程 Harness 完成 WHEN/HOW，并通过自动 Observation、Candidate Registry、Auto Anchor、因果历史和 Receipt 闭环实现真实场景在线验证的可审计研究系统。
