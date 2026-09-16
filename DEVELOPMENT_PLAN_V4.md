# OPSTATE Rokid 手机中心式自动 Anchor 真机部署开发计划 V4

## 文档状态

```text
架构：已收束
自动 Anchor 核心：已实现为可执行、可测试的纯 Kotlin 参考代码
Android/Rokid 集成：已提供代码覆盖层，等待实际私有仓库和设备接入
RealTopia 私有仓库审计：当前阻断，上传文件未挂载到可读取运行时
正式真机验证：尚未执行
```

本计划的目标不是继续开发一个通用 AI 眼镜助手，而是构建一个与
OPSTATE benchmark 严格对齐的、可在真实场景中运行的研究系统：眼镜持续形成
稀疏第一人称观察，手机维护因果上下文和候选 episode，自动选择值得执行
WHEN/HOW 评估的 checkpoint，远程冻结模型做真实决策，最终 SERVICE 在眼镜中
显示，NO_SERVICE 则保持真正安静。

---

# 1. 先说明本次私有仓库处理边界

用户提供了 `RealTopia-main.zip`，并明确要求不得泄漏其中核心代码。当前对话中
虽然存在该上传记录，但文件字节没有挂载到本次可执行环境，上传文件索引也无法
检索该 ZIP。因此，本次不能诚实地声称已经逐文件阅读、删除和重构 RealTopia。

本 V4 包采取两项措施：

1. **不复制、不引用、不复述任何 RealTopia 私有代码；**
2. 提供一套默认 dry-run 的私有仓库清洗工具、迁移策略和全新的 clean-room
   OPSTATE 核心代码。

当 ZIP 能够被实际读取后，第一步必须是安全 inventory，而不是直接解压后运行或
大规模修改。最终迁移仍应在新的干净仓库中完成，避免把无关产品依赖、密钥、
私有接口和历史包袱带入研究原型。

---

# 2. 最终系统定位

## 2.1 设备拓扑

```text
开发阶段

Computer
├── Android Studio / Gradle
├── ADB / Logcat / Database Inspector
├── 本地 Mock Harness
└── 测试和导出检查
        │ USB 或无线 ADB
        ▼
Android Phone
        │ Bluetooth 控制
        │ Wi-Fi P2P 图片
        ▼
Rokid Glasses

正式实验

Rokid Glasses
├── IMU
├── 可选 VAD-only
├── 第一人称拍照
├── Context Card
├── Service Card
└── Visible ACK
        │
        │ Bluetooth 控制 + P2P 图片
        ▼
Android Phone
├── StudyRuntimeService
├── 合成历史与虚拟时间
├── CausalContextStore
├── CandidateEpisodeRegistry
├── ObservationSampler
├── AutomaticAnchorScheduler
├── FrozenHarnessClient
├── Delivery / Receipt Policy
├── Room + 私有图片目录
└── Operator UI / Export
        │ HTTPS
        ▼
Remote Frozen OPSTATE Harness / VLM
```

电脑只用于开发、调试和可选本地 mock。正式 user study 可以拔掉电脑。

## 2.2 研究声明边界

该系统验证：

```text
OPSTATE 任务能否在真实智能眼镜交互中被实例化
历史感知是否改善服务时机与服务内容
NO_SERVICE 是否能表现为低打扰的真实安静
Receipt 是否能抑制重复服务
Material change 是否能重新激活服务
系统延迟、连接和显示是否可接受
```

该系统不声称：

```text
已经解决开放世界意图发现
已经完成全天候成熟 Memory 产品
已经证明长期真实采用率
自动 Anchor scheduler 本身等同于 Gold
真机结果替代系统性的 benchmark 主实验
```

---

# 3. 最重要的概念拆分

```text
一分钟自动拍照
    = Observation sampling

自动判断 service anchor
    = 上游 scheduler 选择一个 Evaluation Checkpoint

SERVICE / NO_SERVICE
    = 目标模型的 WHEN 决策

服务内容
    = 只有 SERVICE 后才生成的 HOW
```

不能把“IMU 稳定”直接解释成“应该服务”。IMU 稳定只说明此刻更适合拍照或
评估，不能提供任务语义。没有交流输入也只是一项低打扰 gate，并不是服务理由。

正确的在线链路是：

```text
低成本连续信号
    ↓
是否值得形成新 Observation？
    ↓
当前 Observation 是否适合作为 candidate-conditioned checkpoint？
    ↓
目标模型读取当前图像、因果历史、candidate 与 receipts
    ↓
SERVICE / NO_SERVICE
```

---

# 4. 为什么仍采用手机中心式双端架构

Rokid 眼镜负责最靠近用户的感知和呈现，手机负责系统状态、网络、模型请求和
实验控制。这样能够复用 Rokid 双端 SDK 的连接、消息、文件和媒体能力，同时避免
把复杂逻辑塞进资源更有限、调试更困难的眼镜端。

工程上不应把所有数据都强行放进 Bluetooth：

| 通道 | 内容 |
|---|---|
| Bluetooth | 扫描、连接、session、sensor summary、capture command、文本卡片、ACK、错误 |
| Wi-Fi P2P | JPEG、较大的调试文件 |
| Bluetooth SPP fallback | 第一版图片分片、P2P 失败后的受控降级 |
| HTTPS | 手机访问冻结 OPSTATE Harness |

第一版可以先用 Bluetooth 分片图片跑通闭环，但 paper-ready 版本应优先 P2P
传图，Bluetooth 保留 fallback。

---

# 5. 新仓库结构

不要直接在 RealTopia 原仓库上“删到能用”。建立新的目标仓库：

```text
opstate-rokid-study/
├── opstate-core/
│   ├── sensor/
│   ├── scheduler/
│   ├── context/
│   ├── episode/
│   ├── inference/
│   ├── receipt/
│   └── runtime/
├── phone-app/
│   ├── transport/
│   ├── runtime/
│   ├── scenario/
│   ├── data/
│   ├── inference/
│   ├── delivery/
│   ├── export/
│   └── ui/
├── glasses-app/
│   ├── transport/
│   ├── sensor/
│   ├── capture/
│   ├── display/
│   └── runtime/
├── schemas/
├── scenarios/
├── scripts/
└── docs/
```

`opstate-core` 必须尽量保持纯 Kotlin，不依赖 Android。这样自动 Anchor、因果
过滤、Receipt 和状态机都能在电脑上执行单元测试和 trace replay。

---

# 6. RealTopia 私有仓库保留、重写和删除策略

## 6.1 优先保留

```text
Rokid SDK 初始化与设备发现
Bluetooth 连接、心跳、重连
P2P 协商与图片传输
消息 envelope、ID、ACK 与错误处理
相机打开、曝光预热、JPEG、方向处理
眼镜端 IMU 访问
前台服务与生命周期
眼镜显示、按键、触摸和 clear
构建配置、ProGuard、必要权限
连接与传输测试
```

## 6.2 保留但重写

```text
产品级大 Service
    → 拆成 StudyRuntimeService 与小型 controller

产品特定协议
    → 重命名为 rokid-study-v4 envelope

产品 Camera Manager
    → WarmCameraSession 接口

产品 Message Router
    → RokidTransportPort + typed event

产品 UI
    → ContextCardRenderer / ServiceCardRenderer

产品数据库
    → OPSTATE 的 session/observation/anchor/decision/receipt 表
```

## 6.3 直接删除

```text
与 OPSTATE 无关的业务页面和领域逻辑
账户、登录、支付、社交、推荐、游戏化
地图、完整导航、路线规划
通用聊天、模型 provider 大全
STT/TTS/实时语音聊天
无关的云同步、analytics、telemetry、广告
大型演示媒体、音频、视频、模型权重
生成的 build、APK、AAB、缓存
签名文件、证书、local.properties、.env
私有域名、token、测试账号
无关的 CI 发布流程
```

## 6.4 安全迁移流程

```text
原 ZIP 只读保存并记录 SHA-256
    ↓
工具 dry-run inventory
    ↓
人工复核 KEEP / REWRITE / DROP / QUARANTINE
    ↓
复制到新 clean-room 仓库
    ↓
包名、类名和配置重命名
    ↓
逐能力测试
    ↓
扫描原产品名、endpoint、secret、资产残留
    ↓
冻结 provenance manifest
```

V4 中的 `tools/sanitize_private_repo.py` 默认不复制文件、不打印源代码、不显示
secret 值，只输出路径、哈希、动作和命中的 secret 规则名。

---

# 7. 一分钟自动拍摄的真实实现

## 7.1 为什么不能用 WorkManager

一分钟是研究运行时的精确采样周期，应由正在运行的 foreground service 和
monotonic ticker 管理。周期性 WorkManager 的最小周期远大于一分钟，而且执行
时间受系统调度和省电影响，不适合该任务。

## 7.2 Capture Planner

手机每秒执行一次轻量 tick：

```text
now_elapsed = SystemClock.elapsedRealtime()

if now_elapsed - last_capture >= 60 s:
    reason += PERIODIC_BASELINE

if material_change_pending and change_age >= 3 s:
    reason += MATERIAL_CHANGE

if recent_motion and current_motion_window_is_stable:
    reason += TRANSITION_SETTLED

if candidate_deadline_near:
    reason += DEADLINE_WINDOW

if routine_window_active:
    reason += ROUTINE_WINDOW

if reason not empty and capture_cooldown >= 15 s:
    send CAPTURE_REQUEST
```

Baseline interval初始为 60 秒，max staleness 为 90 秒，最小 capture interval 为
15 秒。这意味着：正常时每分钟一张；数字事件或动作边界出现时可更早补拍；系统
不会在短时间内拍很多张。

## 7.3 相机生命周期

短时 user study 推荐保留温热 Camera session：

```text
打开低分辨率 preview
等待约 1.2 秒让 AE/AWB/anti-banding 稳定
复用 session 执行 one-shot JPEG capture
```

第一版图片建议：

```text
1280 × 720
JPEG quality 75–80
附带 capture_id、时间、尺寸、rotation、SHA-256
```

如果温度和耗电过高，再切换为 capture 前预热、capture 后关闭的低功耗模式。

## 7.4 图片质量与重试

手机本地确定性检查：

```text
平均亮度
边缘能量模糊代理
dHash 视觉新颖度
尺寸与方向
SHA-256
```

坏图最多重拍一次。不能用上一张旧图替代当前图，也不能在日志中把旧图标记成
当前 observation。

---

# 8. IMU、交流状态与低打扰窗口

## 8.1 IMU 在眼镜端采集

使用眼镜 IMU，而不是手机 IMU。目标是估计当前头部视角是否稳定、是否刚经历
转换。原始 25–50 Hz samples 只保留在眼镜内存的 4 秒滚动窗口，每秒发送一次
summary：

```text
angular velocity RMS / P95
linear acceleration RMS / P95
stable ratio
is_stable
is_moving
window timestamps
sample count
```

## 8.2 初始阈值

```text
stable angular RMS < 2.5 deg/s
stable linear acceleration RMS < 0.18 m/s²
stable ratio >= 0.75
moving angular RMS > 12 deg/s
or moving linear acceleration RMS > 0.60 m/s²
transition settled = 20 s 内出现过 motion，随后 3–4 s stable
```

这些是 pilot 起点，不是最终参数。必须采集实际设备 trace，检查传感器轴、噪声、
佩戴松动和走路头动后重新冻结。

## 8.3 不能只看稳定

以下情况都可能需要服务：

```text
用户站定看门牌
用户走到出口后的短暂停顿
用户在货架前转头后停留
用户走路中头部短暂稳定
```

第一版先只支持 stationary stable 和 transition-settled。后续可以增加
walking micro-stable：角速度低，但允许一定线性加速度。不能把整段步行全部判为
“不可能是 anchor”。

## 8.4 交流输入

“没有明显交流输入”应实现为 blocker release：

```text
没有正在进行的 voice session
最近 2.5 s 没有 touch/key 操作
没有 Context/Service card 正在占屏
可选 VAD speech ratio <= 0.35
```

可选 VAD 只在眼镜本地计算 energy-based speech ratio，PCM 当帧清零并丢弃，
不做 ASR、不区分说话人、不上传音频。MVP 默认关闭 VAD，待权限、伦理和阈值
pilot 完成后再启用。

---

# 9. Automatic Anchor Scheduler 的完整设计

## 9.1 Candidate Episode Registry

在线系统首先要知道“现在有哪些 candidate episode 值得被检查”。MVP 不从图像
开放世界生成所有意图，而从合成数字事件和 scenario materialize：

```text
Calendar event
Message update
Task state
Document state
Routine profile
Prior receipt
    ↓
CandidateEpisodeRegistry
```

每个 entry 包含：

```text
opaque object_handle
opaque episode_handle
intent_fingerprint
service_mode
scheduler_eligible
material_revision
deadline
routine_window
scheduler-only trigger_keys
```

只有 handles、模型允许读取的 observations 和 receipts 进入目标模型。Trigger
keys 与 lifecycle authoring 不进入模型。

## 9.2 硬 Gate

一张已接收图片要变成 Evaluation Anchor，必须满足：

```text
存在 candidate episode
设备连接 ready
图片有效
当前没有 inference in flight
display 不忙
evaluation cooldown 已过
没有 active speech/voice session
最近没有 touch/key
当前有稳定 pose 或允许的 transition-settled micro-window
```

## 9.3 评分

Pilot 初始值：

```text
0.30 candidate episode present
0.25 material change pending
0.20 transition settled
0.15 deadline approach
0.10 routine window
0.10 visual novelty
0.05 periodic coverage
0.10 post-service audit
```

阈值 `0.45`。同一 observation 最多发出三个 episode-specific anchors。

## 9.4 Trigger Family

```text
PERIODIC_COVERAGE
MATERIAL_CHANGE
TRANSITION_SETTLED
DEADLINE_APPROACH
STABLE_DWELL
ROUTINE_WINDOW
VISUAL_NOVELTY
POST_SERVICE_AUDIT
MANUAL_DEBUG
```

这些值写入 scheduler-private audit，不能作为 target model feature。

## 9.5 为什么必须保留 Negative Anchor

真实部署如果 scheduler 只在“非常可能需要服务”时触发，模型几乎只看到正例，
就无法验证 OPSTATE 最重要的低打扰能力。因此 BENCHMARK_ALIGNED 模式必须主动
产生：

```text
事件刚到但时机过早
等待外部条件
episode 已完成
刚服务过，没有 material change
有相关历史但当前没有增量价值
稳定场景中的周期 coverage
```

scheduler 不能因为自己知道 completed/receipt 就提前全部删掉这些 case。目标模型
和 delivery/receipt control 必须接受这些 checkpoint 的考验。

## 9.6 场景连续序列

```text
A. 新消息到达，用户仍在专注工作
   MATERIAL_CHANGE + stable
   → anchor
   → 目标模型可选择 NO_SERVICE_BAD_TIMING

B. 用户收起电脑，移动后在出口附近稳定
   TRANSITION_SETTLED + deadline
   → anchor
   → 目标模型可选择 SERVICE

C. 服务真实可见并写入 receipt，稍后再形成 checkpoint
   POST_SERVICE_AUDIT
   → anchor
   → 目标模型或 control 应保持安静

D. 新消息让 material_revision 从 1 变为 2
   MATERIAL_CHANGE
   → anchor
   → 允许再次 SERVICE
```

---

# 10. 是否需要一个额外模型判断 Anchor

## 10.1 第一版：不需要大模型判断 Anchor

第一版使用确定性 scheduler，优点是：

```text
可解释
可复现
不把第二个模型的偏差混入 WHEN 评测
可以明确审计每个 blocker 和 trigger
开发和延迟更可控
```

## 10.2 可选轻量 Observation Model

当 candidate episode 需要识别当前 surface 时，可以对高 novelty 图片调用轻量
`/v1/observe`：

```text
短 caption
place/surface tags
transition tags
quality confidence
```

它只用于 candidate routing，不输出 SERVICE/NO_SERVICE。响应的真实返回时间是
`available_at`，不能回写到过去。

## 10.3 后续开放世界扩展

论文之外可以研究：

```text
visual event detector
learned checkpoint policy
uncertainty-triggered sensing
service opportunity discovery
```

但这些需要独立 scheduler benchmark，不能直接混入当前 user study 后声称效果都
来自 OPSTATE decision model。

---

# 11. 模型调用与因果数据组织

## 11.1 `/v1/evaluate`

手机发送 multipart：

```text
request: request.json
image: current.jpg
```

request 包含：

```text
protocol_version
request_id
anchor_id
anchor_time
current_asset_id
current_image_sha256
opaque candidate object/episode
causal observations
committed receipts
prompt/preprocess hash
```

明确排除：

```text
scheduler score
trigger family
Gold
expected decision
operator criteria
private lifecycle
authoring support
future event
```

## 11.2 History

受控部署不需要完整向量 Memory。手机维护：

```text
预先加载的长期 synthetic observations
本次 session 中出现的 context events
被接受的 live image observations
可选 caption/tag observations
ACTION_RECEIPT
```

在 anchor 时按 `available_at <= anchor_time` 切因果快照，再按 candidate episode
关联和全局 receipt 规则构造 bounded history。

## 11.3 返回

```text
NO_SERVICE:
    不带 mode/title/body

SERVICE:
    service_mode
    title <= 40 chars
    body <= 140 chars
    evidence observation IDs
```

服务卡还要进一步压缩到眼镜可读的中文 35–55 字左右。

## 11.4 Timeout 与 stale

工程目标：

```text
connect timeout 5 s
write timeout 10 s
read timeout 12 s
call timeout 15 s
decision TTL 20 s
```

这些是初始预算。任何超过 TTL 的 SERVICE 都记录 `STALE_DECISION`，不在眼镜
突然弹出。正式模式不允许模型 fallback。

---

# 12. Raw Decision、Delivery 与 Receipt

必须分三层落盘：

```text
RAW MODEL DECISION
    ↓ schema/evidence/expiry validation
FINAL DELIVERY DECISION
    ↓ Bluetooth send / glasses render
VISIBLE OUTCOME
```

可能出现：

```text
raw SERVICE
→ invalid evidence
→ final no display

raw SERVICE
→ duplicate receipt
→ final suppressed

raw SERVICE
→ command sent but display failed
→ no receipt

raw NO_SERVICE
→ true silence
```

## 12.1 Visible ACK

眼镜 Compose 把 Service Card 加入 UI 后，等待至少两个 rendered frames，再发：

```text
DISPLAY_VISIBLE_ACK
decision_id
visible_at
render_frame_count >= 2
```

Transport ACK 不能替代 Visible ACK。

## 12.2 Receipt 条件

只有以下全部成立才写 receipt：

```text
raw decision = SERVICE
response valid and causal
not expired
not suppressed
service card visible ACK received
same session epoch
```

Receipt scope：

```text
episode_handle
object_handle
intent_fingerprint
service_mode
served_material_revision
visible_at
evidence IDs
```

同 episode、intent、mode 且 material revision 未变时抑制。Revision 增加或 episode
reopen 后允许再次服务。

---

# 13. 手机本地数据

## 13.1 Room 只存 metadata

```text
study_session
context_event
observation
sensor_window
episode
anchor_candidate
inference_request
decision
delivery
action_receipt
user_feedback
audit_event
```

JPEG 存 app-private files：

```text
files/sessions/{session_id}/images/{capture_id}.jpg
```

不要把大量图片作为 Room BLOB。

## 13.2 Export

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

Manifest 冻结：

```text
phone/glasses APK version and SHA
runtime config hash
scenario hash
model ID
prompt hash
preprocess hash
parser hash
session start/end
```

---

# 14. 手机 Operator UI

## 14.1 Connection

```text
Rokid device
Bluetooth state
P2P state
battery/thermal
phone network
remote harness health
```

## 14.2 Session

```text
participant alias
scenario
condition A/B
session epoch
start/pause/stop/delete/export
```

## 14.3 Live Monitor

```text
last sensor summary
last capture and reason
last image thumbnail
active candidate episodes
anchor blockers
anchor count
inference latency
raw decision
final delivery
receipt count
```

正式 blind study 中，Operator 不显示 expected Gold，也不把条件命名为 History/
Scene-only。

## 14.4 Emergency

始终有：

```text
Pause sensing
Clear glasses
Abort inference
Stop session
Delete session
```

---

# 15. 眼镜 UI

眼镜不是聊天窗口。界面只需要：

```text
极小连接状态
Context Card
Service Card
可选简短 hint
```

NO_SERVICE 不显示“无需服务”，不转圈，不发声，不改变当前页面。

Service Card：

```text
助手建议 · PREPARATION

现在前往 B302

会议地点已变更，
此时出发更稳妥。
```

Context Card 与 Service Card 使用不同但克制的视觉层级，避免把世界事件误认为
Agent 建议。

---

# 16. Android 生命周期与权限

## 16.1 手机

手机的 `StudyRuntimeService` 从可见 Activity 显式启动，负责 connected-device 与
网络/日志运行。它不使用手机摄像头和麦克风。

## 16.2 眼镜

眼镜端负责 camera；启用 VAD 时还负责 microphone。必须声明相应 foreground
service type，并在用户可见流程中获得权限。不要依赖后台自启动来偷开 camera 或
microphone。

## 16.3 省电和可靠性

正式 session 中：

```text
保持 foreground notification
WakeLock 只覆盖必要的 capture/transfer 临界区
ticker 使用 elapsedRealtime
网络和图片 upload 有超时
每次 side effect 带 idempotency key
重连后要求 current epoch DEVICE_READY
```

---

# 17. ADB 开发流程

```bash
adb devices
./gradlew :phone-app:installDebug
adb shell am start -n ai.opstate.rokid.phone/.MainActivity
adb reverse tcp:8765 tcp:8765
```

电脑启动开发 mock：

```bash
cd code/server-contract
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn mock_harness:app --host 127.0.0.1 --port 8765
```

手机 debug endpoint：

```text
http://127.0.0.1:8765
```

调试重点：

```text
Rokid connection state
capture ID correlation
image hash/orientation
scheduler blockers and score
causal request snapshot
raw vs final decision
visible ACK
receipt commit
```

正式 APK 替换为远程 HTTPS endpoint，并拔掉电脑完成完整 dry run。

---

# 18. 代码实现状态

V4 已经提供可执行纯 Kotlin 代码：

```text
MotionFeatureExtractor
QuietGate
ObservationCadence
AnchorPolicy
AnchorScheduler
CausalContextStore
EpisodeRegistry
InferenceContract
ReceiptPolicy
StudyStateMachine
Simulation
```

测试覆盖：

```text
稳定/运动窗口
一分钟和 material-change capture
anchor 产生
speech/display blocker
future isolation
scheduler-private 数据不泄漏
NO_SERVICE 无 HOW
Visible ACK 前不写 receipt
material change reactivation
runtime state correlation
```

Android overlay 提供：

```text
StudyRuntimeService
ObservationPipeline
RokidTransportPort
RemoteHarnessClient
Wire DTO
Room entities
LocalImageQualityAnalyzer
AndroidImuSampler
VadOnlyGate
CaptureCoordinator
VisibleAckRenderer
StudyGlassesController
manifest snippets
```

这些 overlay 仍需根据实际私有仓库和 Rokid SDK API 接线，不能声称已经在真机上
编译通过。

---

# 19. 开发阶段和硬验收

## Phase 0：私有代码与设备冻结，1–3 天

```text
让 RealTopia ZIP 可读取
运行 dry-run sanitizer
确认 Rokid 型号/系统/固件
确认手机 Android 版本
确认 Security SDK 或 Legacy CXR，二选一
跑通原项目最小连接、显示、相机
记录可以迁移的真实文件
```

## Phase 1：Clean-room Transport，2–4 天

```text
新建三模块
迁移 SDK adapter
Bluetooth control
P2P image
epoch/idempotency
clear/reconnect
```

Gate：30 分钟稳定连接，50 次 capture round trip，成功率至少 95%。

## Phase 2：Observation Runtime，2–3 天

```text
foreground service
IMU summary
60 s monotonic capture
material/transition capture
quality gate
Room metadata + private image path
```

Gate：一小时 observation trace，无重复 capture ID、无 raw audio/IMU 落盘。

## Phase 3：Auto Anchor，2–4 天

```text
candidate registry
hard gates
score
post-service audit
scheduler-private logs
trace replay
```

Gate：目标模型请求中没有 scheduler diagnostics；人工 pilot 能看到 early/action/
duplicate/material-change 四种 checkpoint。

## Phase 4：Frozen Harness，2–3 天

```text
multipart client
causal snapshot
schema validator
raw log
no fallback
expiry
```

Gate：抽取至少 20 个冻结 benchmark anchor，离线 runner 和手机 adapter 在数据语义
上逐字段一致。

## Phase 5：Delivery/Receipt，1–2 天

Gate：Visible ACK 前零 receipt；同 revision suppression；revision 增加后 re-service。

## Phase 6：Operator、Export、Pilot，3–5 天

Gate：20 次完整连续脚本全部通过，export trace validator PASS。

总开发量约 12–20 个工作日，具体取决于私有仓库中 Rokid 连接和 P2P 代码的可复用
程度。

---

# 20. 自动 Anchor 的校准实验

正式 user study 前，先做 4–6 人 pilot，采集但不用于论文主结论的校准 trace：

```text
坐着工作
低头阅读
抬头环视
收起电脑
站起
走路
门口停留
与他人交谈
看手机
操作眼镜
```

人工标记：

```text
image readable
low-interruption window
semantic action boundary
candidate checkpoint useful
should not evaluate
```

优化目标不是让 scheduler 猜 SERVICE，而是：

```text
高覆盖真正 evaluation boundary
控制每小时 checkpoint 数量
尽量减少 motion/speech 中的打扰
保留足够 hard negatives
```

建议冻结并报告：

```text
anchor rate/hour
human-reviewed boundary recall
checkpoint precision
blocked reason distribution
capture-to-anchor latency
battery/thermal overhead
```

---

# 21. User Study 主设计

两个条件：

```text
Condition A
current image + candidate + causal history + receipts

Condition B
same current image + same candidate
without long history/receipts
```

同一参与者看到的消息、日历、任务、道具和动作脚本完全相同。自动 scheduler 在两
条件下使用同一配置，并且对条件名称 blind。

体验指标：

```text
usefulness
timeliness
context fit
interruption
actionability
correct silence
adoption intention
```

系统指标：

```text
anchor rate
SERVICE rate
unnecessary intervention
model latency
end-to-end latency
technical failures
visible delivery success
receipt suppression
material-change reactivation
```

真机 study 是交互可行性和体验验证，benchmark 仍然承担系统性的模型比较。

---

# 22. 风险控制

| 风险 | 控制 |
|---|---|
| 私有代码泄漏 | dry-run inventory、无内容报告、新仓库迁移、secret scan |
| SDK 代际混用 | Phase 0 二选一，release 仅绑定一个 transport |
| 一分钟任务被系统延迟 | foreground ticker + elapsedRealtime，不用周期 WorkManager |
| IMU 稳定成为错误 shortcut | 只作 gate，不进入 target model，不决定 label |
| 对话中误打扰 | touch/display/voice/VAD blocker |
| 只产生正 anchor | periodic、early、post-service、waiting/completed checkpoint |
| scheduler 泄漏 Gold | private schema 与 model request 物理分离 |
| 旧 session 服务延迟弹出 | session_epoch + expires_at |
| command 重复 | idempotency_key |
| P2P 断线 | BT fallback 或明确失败，不复用旧图 |
| 模型延迟 | TTL，stale 不显示 |
| service 未显示却写 receipt | 两帧 Visible ACK 后 commit |
| raw audio/privacy | 默认关闭 VAD；启用时只存 aggregate |
| 旁观者 | 场景控制、最小采集、外发前人工去标识 |

---

# 23. 立即执行顺序

```text
1. 重新提供可读取的 RealTopia ZIP，或把私有 GitHub 仓库接入当前 GitHub connector
2. 对 ZIP 只运行 dry-run sanitizer
3. 输出真实 KEEP / REWRITE / DROP matrix
4. 确认 Rokid 型号、固件和 SDK family
5. 先迁移 Bluetooth/P2P/camera/display 四条最小链路
6. 把 V4 pure Kotlin core 接入 phone-app
7. 跑通每分钟 capture，不接模型
8. 跑通 deterministic auto anchor，不接模型
9. 用 adb reverse 接 mock harness
10. 接 frozen harness
11. 完成 visible ACK / receipt
12. 跑 20 次会议连续场景
13. 再扩展到其他场景和正式 user study
```

在 RealTopia 源码可读取之前，任何具体到“删除了哪些文件、保留了哪些类”的声明都
是不可靠的。本 V4 已经把与私有代码无关、同时最难定义的自动感知、Anchor、因果
协议和 Receipt 核心先实现并测试完毕；下一阶段应把私有仓库中的设备能力按这些
稳定接口接入，而不是反过来让旧产品架构决定 OPSTATE 的研究协议。
