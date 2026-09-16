# V5 历史实现与验证跟踪

> 此页为按时间累计的历史记录，表内早期“待完成”不代表当前实现。当前状态以 [V5_ACCEPTANCE_STATUS.md](V5_ACCEPTANCE_STATUS.md)、[MIGRATION_5080.md](MIGRATION_5080.md) 和根目录 STATUS 为准。2026-09-04 用户取消长测，转为整理与迁移；已筛选证据在 `reports/evidence/2026-09-04/`。

目标依据：根目录 `OPSTATE_Rokid_Full_Development_Spec_V5.md`（2026-09-03）。本表跟踪完整目标；组件测试通过不会替代端到端验收。此前 `VALIDATION.md` 只记录基础修复阶段。

**最新增量：历史续接与可展示性。** 已接入开始页的 R6 历史播放/暂停/拖动、载入后单独开始现场服务、情境脚本进度、自动/手动拍照、四类合成事件表单、眼镜演示与安静视图。运行中跳转使用独立片段，旧历史/回执不回填。操作与验证边界见 [LIVE_HISTORY_WORKFLOW.md](LIVE_HISTORY_WORKFLOW.md)。

- 手机 8 项测试与 JVM 21 项通过；1852 条 R6 历史续接、手工 Calendar 进入模型输入、倒退隔离及完整导出已验证（此项使用测试传输/模型）。新短文本 Schema 经真实 Qwen 调用验证。
- 真实自动采样短测：两次基线请求间隔 60,216 ms；一次手动请求；含质量重试共 6 张照片，实际通道包含 CXR_BLUETOOTH 和 WIFI_P2P。均为黑帧，未用于模型评估，详情 `automatic-capture-result.json`。
- 新 UI 的真实设备+模型联合测试已确认启动提示实际绘制、表单输入、相机、供应商响应；首轮长回复被正确拒绝，补齐约束后再次运行被黑帧质量门槛阻止。待明亮实景复验，不能宣布该联合验收通过。

**最新用户调整优先于下列历史实现记录**：当前使用手机模型 API Key 直连，暂停自建服务器连接部署。5090 的 VLN 项目只准读取、查找和下载，禁止修改，相关源码已经复制。用户随后提供官方 Client Secret / `.lc`，现有 CXR 连接已通过鉴权及研究握手；原生 RFCOMM 仍是已核对的参考方案，未切换为实际运行实现。

| 阶段 | 完成要求与证据 | 当前状态 |
|---|---|---|
| 0 硬件与 SDK | 设备/固件/SDK 哈希矩阵，官方 SDK 真实连接与权限验证 | 官方授权已导入，CXR-M 实际鉴权、CXR-S 通信与研究握手通过；仍需长期硬件 gate |
| 1 协议与传输 | Bluetooth 控制、P2P 图像、分片降级、epoch/idempotency、心跳重连；30 分钟记录与 50 次传图 | 实际 Bluetooth/P2P 短测已通过；手机接管已授权 SDK 的 P2P 协商并使用 groupOwnerIntent=0，30 分钟/50 次 gate 待完成 |
| 2 宿主与会话 | 两个可启动 APK、前台服务、单会话/单捕获/单推理、暂停/重置/停止/恢复 | 两个宿主 APK 已安装启动；眼镜 actor/前台服务已实现，手机业务 actor 与前台服务已接入，待完整状态回归与 SDK 闭环验证 |
| 3 感知与 Observation | Camera2 真拍、方向与质量、最多一次重试、60 秒基线、事件采样、IMU 摘要；一小时采样记录 | 固件硬件 JPEG 路径超时已定位；改用真实 Camera2 YUV 帧编码后，720×1280 正向 JPEG 真机测试通过；完整无线采样链尚未通过 |
| 4 场景与 Anchor | 公共场景/虚拟时钟、严格因果历史、candidate/material revision、hard gates、cooldown、negative preservation | 已接入现场 actor；episode 90 秒、routine 10 分钟、材料变化与服务后审计、负例覆盖已补齐并通过核心回归，长期现场验证待完成 |
| 5 Frozen Harness | v1 health/contract、冻结字段、原始结果落盘、超时/非法/evidence 校验、幂等；20 次请求语义对照 | V2 mock 266 锚点与 Android/Python 20 请求哈希对照已通过；新增真实冻结服务，Qwen 手机调用与 GPT Responses 服务器调用各 1 个合成请求通过。真实场景连续 20 次及 Track A 语义对齐仍待完成 |
| 6 显示与 Receipt | Context/Service 卡片、true silence、两帧 ACK、TTL、epoch/材料版本校验、重复抑制/重新服务；20 轮真实序列 | 首个真实无线 SERVICE 闭环产生 1 条两帧可见回执，NO_SERVICE 闭环零回执；20 轮序列和像素级沉默对照仍待完成 |
| 7 存储/导出/控制台 | Room+DAO、私有图片、append-only audit、完整 JSONL/hash 导出、删除、各控制台页面与故障恢复 | Room v2/索引、分批完整导出、会话/试次/图像/原文/删除/SAF 导出已实现；新增进程中断收尾，完整现场状态恢复仍待完善 |
| 8 集成实验 | 自建 mock 场景，四条 V5 闭环、故障注入、性能分位数、实际物理链路证据 | 待实现与部署后实验 |
| 9 无电脑运行与冻结 | HTTPS endpoint、拔电脑运行记录、APK/模型/prompt/parser/preprocess/scenario/policy hashes | 待实现；不可用 ADB relay 结果替代 |
| 10 Pilot 与研究项 | 4–6 位参与者 pilot、阈值校准、问卷与分析方案、P2 可选输入/VAD 验证 | 工程完成后准备并按实际参与者条件执行，不以 mock 冒充真实 pilot |

## 范围与实现原则

- 保留已有纯 Kotlin 领域模型；将协议、SDK 适配、应用宿主与研究运行时分开。
- 只选择与实际设备匹配的一套 Rokid SDK。调试替身不能作为该 SDK 真实连接通过的证据。
- mock 只替换模型决策/合成历史，不替换真实相机、无线传图、眼镜显示或 ACK。
- 运行日志不得把技术失败记成 NO_SERVICE；NO_SERVICE 不向眼镜发出 UI、音频或震动动作。
- V5 示例与原 wire schema 有差异：在修改契约前明确版本与兼容策略，冻结后按实际 schema 比对。
- 所有试验保留原始日志、导出与哈希；失败项继续留在清单中。

## 本轮数据与界面要求

- 输入包：`january_v10_online_validation_r6_data_bundle_20260903.zip`，SHA-256 `fc76d7c37d295d1a04f8d3306e178bc9033b6fe9584701aefd4f2945674b5745`。外层 277 / 内层 276 校验和全部通过。
- 266 个锚点、3,921 条观察、253 张当前帧图片已提取公共投影；Gold / authoring / teacher receipts 不进入应用公共数据。数据仅打入 debug APK。
- 严格 V2 HTTP mock 回放：266 / 266，通过；SERVICE 78，NO_SERVICE 188。长历史超过默认 1 MiB 的问题已修复为受限 8 MiB 文本 part，未截断历史。原始失败记录保留。
- 这不是模型准确率、完整无线链路或现场相机回放通过的证据。
- 真机证据：`reports/latest/v5-yuv-camera-test.txt`、`v5-glasses-live-capture.json`、`v5-phone-native-library-test.txt`、`v5-glasses-hardware-test.txt`（含原始 JPEG 失败及已通过的显示/桥接子测试）。
- 新手机界面截图：`reports/latest/v5-phone-overview.png`；实际会话状态页继续随运行时完善。
- 用户后来已提供自己的 SDK 凭证，当前已加密导入并通过实际鉴权；早期等待凭证的记录仅属历史状态。没有使用其他人的凭证或将 ADB 通道当作无线链路。

## 当前继续开发入口

- 最近构建/主机回归：`reports/latest/v5-milestone-build.txt`（两个 APK、core、protocol、phone 单测成功），`reports/latest/v5-python-tests.txt`（16 个 Python 用例通过）。
- 现场运行入口：`StudyRuntimeEngine.kt`（phone library）+ `StudyRuntimeService.kt`（前台服务），由 `PhoneApplication.studyRuntime` 提供。首版 live actor 已编译，尚未通过完整状态/设备闭环。
- 新 UI：`code/phone-app/.../ui/OperatorScreen.kt`，总览/会话/数据/设置已实现；已接入 live 会话启动、暂停/继续/结束，但等待 SDK 真实连接后验证。
- 仍需完善：完整现场状态恢复、业务 actor 故障覆盖、长期运行以及远程 HTTPS 无电脑测试。眼镜 epoch 持久化、定时覆盖和每 episode cooldown 已完成代码与对应回归。
- 需要检查的具体边界：SESSION_START 失败清理；模型结果期间 material revision 变化；断线/重连 epoch；延迟/丢失可见 ACK；重复 IMAGE_ACK 与分片 ACK；pause 后世界时间继续且迟到事件不得回填。
- 本地严格数据集 mock 服务由本轮启动，PID 89683、端口 8765（继续工作前必须重新确认仍运行）。环境模式为 dataset，不适用于 live meeting 场景；live 场景需独立重启为 heuristic/scripted 模式，不能静默换模型。
- Mac 浏览器检查曾因锁屏不可用；未登录/修改开发者账号。手机已解锁，新 UI 已实际截图。

## 手机回放与控制台增量（2026-09-03）

- 最新手机运行结果：`reports/latest/phone-dataset-replay.json`；完整 266 个锚点，SERVICE 78 / NO_SERVICE 188 / 技术异常 0。`v5-phone-dataset-instrumentation.txt` 两项真机测试通过，126.434 秒，含 20 个 Python/Android 请求哈希对照。
- `v5-phone-fixture-comparison.json` 在主机评测侧逐锚点对照所有 266 个 mock 预期，全部相符；这些标签未回灌手机输入。
- 手机生成的 ZIP 为 `reports/latest/phone-dataset-replay.zip`，47,031,138 字节；解包 337 MiB。`v5-phone-export-validation.json` 完整校验通过：266 请求、266 原始响应、266 决策、5,747 条审计记录、0 条显示回执。
- 4,413 条导出 observations 包含锚点可见的公共历史、每个锚点的公开 query 与独立图像附件记录；不是 4,413 张现场照片。数据集末尾晚于最后锚点的观察不进入模型请求。
- 新增手机前台回放、进度/失败计数、可停止操作；现场与回放入口互斥。数据页显示真实目录统计，当前图像来源清楚标注。
- Compose 增加会话总览、服务/沉默/待查筛选、图像与证据详情、原始请求/响应预览、SAF 导出、删除确认。各标签及详情页面分别保留滚动位置。
- 修复停止会话丢失 frozen contract、导出与 manifest schema 不一致、一次加载所有请求的内存风险。导出固定审计序号快照，按 16 行分批验证与写入，含每文件字节数/哈希及 SHA256SUMS。
- Room v2 增加 anchorId / assetId 索引并迁移旧记录，避免打开早期试次时全文扫描累计请求。原 canonical JSON 和链哈希不变。
- 进程重启记录 SESSION_INTERRUPTED 并结束旧会话；保留冻结信息，不自动重新请求/展示/提交回执。`v5-phone-recovery-test.txt` 真机测试已通过。完整 live 会话重建和继续仍待实现。
- 主机 21 个 Python 测试通过（含文件篡改、重新计算文件哈希后篡改审计负载、截断审计及未列文件探针）。校验器使用本地 schema registry，避免解析相对引用时访问网络。
- 操作与复现实验入口：`docs/implementation/PHONE_REPLAY.md`。本次不宣称 Bluetooth/P2P、无电脑运行或真实服务回执闭环通过。

## 模型配置与 Track A 最新要求

- 用户提供 5080 服务器上的 Track A/Track B 实验仓库和凭证位置，要求先完成已有部署任务，再对标 Track A。已只读核对代码/配置并记录于 `TRACK_A_REFERENCE.md`；尚未运行 Track A 实验。
- 手机设置页新增模型配置卡：保存服务地址、预期模型 ID 和加密 Harness 令牌，读取实际冻结契约。现场/回放准备均核对绑定模型；运行期间不可修改配置。供应商 API Key 仍由服务器管理。
- 模型绑定错误的 HTTP 合约测试通过，详情索引与 Room 迁移真机测试通过；原完整回放审计链仍有效，首/末试次加载分别为 23 / 96 ms（`v5-phone-trial-index.json`）。
- 已新增 Qwen / GPT 两个服务器冻结 profile，配置卡可读取实际发送的参数与快照哈希；参数由服务器配置文件冻结，手机不直接改写供应商参数。Track A 快照选择、预算与输入语义对齐仍留在后续阶段。

- 真机从数据页按钮启动的 20 锚点快速回放，在按 Home 后仍由 dataSync 前台服务执行，最终 20/20、SERVICE 7 / NO_SERVICE 13 / 异常 0；完成后服务退出（`v5-phone-replay-foreground-service.txt`、`v5-phone-replay-service-finished.txt`、`v5-phone-replay-ui-result.png`）。
- 手机完整 266 个 HTTP 请求延迟：median 182.5 ms，P95 428 ms，max 664 ms（不包含无线传图或显示时间）。

- 原生 Android 文件保存路径已实际操作并验证：手机 Downloads 中保存 `opstate-replay-30543072-f541-4831-8f41-6ca49969dd8a.zip`（4,108,070 字节），主机重新读取并完整校验通过（`v5-phone-saf-validation.json`：20 请求 / 20 响应 / 20 决策 / 4,025 审计记录）。
- 新版 UI 实机截图：`v5-phone-model-config.png`、`v5-phone-session-review.png`、`v5-phone-trial-list.png`、`v5-phone-replay-ui-result.png`。手机当前保留完整回放、后台快速回放及中断收尾记录；未删除用户提供的数据包。

- 后续以 Compose 语义定位复验发现详情导航确有子组合保留旧画面的问题，已通过统一导航状态及按试次保存页面状态修复。真实数据集的列表 → 图像 → 请求原文 → 返回原列表位置已通过自动化真机验证，截图为 `v5-phone-trial-detail-verified.png`。

## 历史诊断：官方 App 与 OPSTATE 都无法建立应用连接

- 用户明确确认两个应用都失败。眼镜 CXRService（系统包，版本属性 1.135）进程存在且未禁用，系统 Bluetooth ON、音频 profile 有连接；9100 GATT 服务仍注册。
- 官方 App 日志实际报 `SOCKET_CONNECT_FAILED / java.io.IOException: Connect refused`，每约 30 秒用同一个旧 socket UUID 重连。
- 新增只读真机发现诊断（`RokidDiscoveryDeviceTest`）：系统已配对，SDP 请求完成，眼镜公布的自定义 UUID 与官方 App 缓存的 UUID 不同；8 秒内没有看到目标 BLE 配对广播，扫描无错误。证据见 `v5-rokid-discovery.json` 与 `v5-bluetooth-diagnosis.json`。
- 此前检索检查了佩戴传感器调试标志，但没有将其认定为本次连接根因，也没有修改这些标志或其他系统配置。未恢复出厂、未清除官方 App 数据、未重写其私有凭证。
- OPSTATE 已修复可观测性和重连路径：扫描列表合并已配对 Rokid 设备并区分是否正在广播，连接页分开显示系统配对/SDK 授权/SDK 数据通道/研究握手，缺授权错误持续显示；socket 连接失败后仅移除自身缓存 socket UUID/MAC，下次经官方 BLE 初始化重新获取。已编译部署。
- 当时通过正常 UI 进入官方 App 搜索引导页（`v5-official-pairing-guide.png`）。随后用户已完成官方应用配对，不能继续把这条历史诊断记为当前官方连接状态。
- OPSTATE 自己的 Client Secret/SN .lc 仍缺失，是官方连接修复后另一个独立 SDK 授权条件。Track A 仍按用户顺序延后。

## 官方连接恢复后的开发与验证（2026-09-03）

- 用户确认官方 App 已能配对；最新设备固件 `1.25.012-20260901-150201`、CXRService `1.146`，双设备时间已对齐，见 `v5-connection-restored-state.json`。OPSTATE 的 CXR-M 鉴权仍等待用户自己的授权文件，不能用官方配对成功替代。
- 传输层等待连续三次相机/显示就绪，断线取消旧握手与图像工作，重试同一 SESSION_START 时保留幂等键，重复末片只补 ACK。眼镜命令回复缓存、epoch 落盘、BT 分片重传均有回归测试。
- 手机拒绝私网明文 HTTP 的配置保持不变；P2P 图像改为每会话 HTTPS，并通过蓝牙发布的证书指纹固定对端证书。没有 trust-all 或禁用主机名校验。眼镜 Android TLS 回环测试通过（`v5-glasses-tls-probe.json`），这仅证明设备上的 TLS 实现可用，不代表 Wi-Fi P2P 无线通道通过。
- 新固件下真实相机、两次绘制 ACK、CXR-S 本地桥接订阅共三项通过（`v5-glasses-current-firmware-hardware.txt`）；新照片 720×1280、rotation 0、101,379 字节，哈希与时间在 `v5-current-camera-probe.json`。
- 暂停后恢复会持久化并推进 epoch，保留历史/冻结契约；Receipt 先在 Room 事务落盘，再更新内存抑制状态。进程中断仍采用安全收尾，完整现场会话重建尚未完成。
- 手机详情图像采用自适应解码与完整宽度展示；UI 导航和两项 Journal 生命周期测试通过（`v5-phone-final-ui-lifecycle.txt`）。最新 Python 校验器 21 项通过（`v5-current-python-tests.txt`）。

## 用户提供的应用图标

- 原图 `icon.png` 以原字节复制到手机资源 `drawable-nodpi/opstate_brand.png`，两者 SHA-256 均为 `9d315af44d7ea06272bd048dde2eb2393bfb41211249ea939682e350dae710c0`。
- 已接入 Android 自适应/传统桌面图标、Android 12+ 启动画面图标及应用首页标识。仅通过资源布局适配系统遮罩，未重绘原图。
- 已安装到 BVL_AN00；系统应用信息页和应用首页真实截图分别为 `v5-phone-system-icon.png`、`v5-phone-icon-overview.png`。原有详情导航真机测试通过（`v5-phone-icon-ui-test.txt`，1 项，6.069 秒）。构建包中的所有密度均引用新的自适应图标。

## 中断操作与迟到回执回归

- 传图计时按 CaptureCommand 的实际截止时间执行；超时、相机时钟异常、眼镜拒绝捕获都会关联 capture_id 返回失败。已结束的捕获拒绝迟到图像/重复错误，避免通用错误留下长期“捕获中”状态。
- 眼镜错误回复保留 capture_id / decision_id 并参与幂等重放；显示窗口未就绪时明确拒绝显示请求。手机将显示拒绝写成 DISPLAY_FAILED。
- 暂停/停止先取消并等待模型写入任务结束，再记录 CAPTURE_CANCELLED / INFERENCE_CANCELLED / ANCHOR_CANCELLED 及待显示操作的终止原因。重复暂停不重复记录，恢复继续推进 epoch。
- 材料版本变化、可见 ACK 超时、未 ACK 前关闭卡片均记录唯一终止状态，并更新界面阶段；失效服务卡只清除服务显示。迟到 ACK 不再生成回执。
- `v5-runtime-terminal-build.txt`：两个 APK 与手机 AndroidTest 构建成功。传输层 6 项及 HTTP 客户端 4 项 JVM 回归通过。
- `v5-runtime-terminal-device.txt`：手机运行时 3 项集成测试通过（7.003 秒），覆盖捕获暂停/停止/恢复、6 种待显示终止原因、HTTP 取消与 NO_SERVICE 无显示命令。使用真实 Android actor、HTTP 客户端、解析器和隔离 Room；传输、图像、IMU、ACK 明确使用测试 fixture，不能作为物理相机/无线/显示验收证据。fixture 仅位于 AndroidTest APK，测试后清理隔离数据库和图片。
- 新 APK 已分别安装到手机和眼镜；完整现场恢复、真实无线回执闭环及长期 gate 仍保持未完成。

## 重连换代与进程恢复补强

- 断线时手机 actor 立即暂停、取消在途工作并记录 SESSION_LINK_INTERRUPTED。底层连接恢复后，先以新 epoch 完成 SESSION_START，再继续原会话；会话开始时间、历史和冻结契约保留。用户在断线期间主动暂停会取消自动恢复意图。
- 传输层在断线后禁止发送捕获/图像请求及显示命令，直到新连接完成会话绑定；旧 epoch 和通过 sendControl 进入的旧显示命令同样被拒绝。`v5-reconnect-epoch-build.txt`：7 项传输测试、4 项 HTTP 测试通过；`v5-reconnect-epoch-device.txt`：5 项手机 actor 测试通过。
- 新建现场或回放会话时，SESSION_START、SESSION_CONFIGURATION 与初始 checkpoint 在同一个 Room 事务写入，缩小启动过程中的不完整状态。恢复改为扫描所有未结束会话，能覆盖旧版本留下的“有会话、无 checkpoint”窗口。
- 进程恢复按已验证的审计链收集未结束操作，补写捕获/anchor/推理取消、尚未决定投递或仍等待可见 ACK 的终止记录，再结束旧会话；不重放请求、显示或回执。实现拆分为 `StudyJournal` 的事务恢复与 `InterruptedOperations` 的流式状态归纳。
- `v5-recovery-lifecycle-device.txt`：5 项 actor + 4 项 Journal 真机测试通过，7.557 秒。进程内断线恢复继续原会话；跨进程仍采用保留数据并结束旧会话的恢复策略，完整 live 状态重建未实现。
- `v5-process-recovery-prepare.txt` / `v5-process-recovery-relaunch.txt` / `v5-process-recovery-verify.txt`：在真实手机创建测试中断窗口，实际 force-stop OPSTATE 并重新启动后验收通过。`v5-process-recovery-result.json` 记录不同进程 PID、取消捕获 1 次、新增回执 0 条、冻结配置保留、已有会话审计链不变。测试会话已清理，原实验数据保留。
- 新手机 APK 已安装并启动。复现操作见 `RUNTIME_RECOVERY.md`；以上回归不计入 Bluetooth/P2P 物理链路 gate。

## 真实模型服务部署与手机配置卡

- 当前旧服务器代码仅实现 mock，旧提示词仍对应 V1；新增独立 `frozen_harness.py`、`model_provider.py`、`harness_store.py` 与 V2 模型提示词，保留原 mock 契约和回放。每个进程只运行一个冻结模型配置，不构建多 provider 管理平台。
- 真实服务支持用户实验中使用的 Chat Completions / Responses 结构，严格检查冻结字段、因果证据、服务输出、图像哈希与输入预算；先保存供应商原始响应再解析，错误保持技术失败。SQLite 保存请求结果和用量，重复请求复用原结果，服务重启不自动重新执行未完成模型请求。
- 服务已部署到 5080 的独立目录 `/home/caizhuoqiang/Code/rokid_opstate_deploy`，使用独立虚拟环境，从原 `.env` 读取所选模型 Key，不改动原实验仓库或复制供应商 Key 到手机。
- `v5-frozen-all-python-tests.txt`：27 项通过。`v5-frozen-model-phone-device.txt`：Qwen 原生手机 HTTP 客户端检查通过；`v5-frozen-model-gpt-result.json`：GPT Responses 服务端检查通过。输入均为生成的棋盘格与合成历史，未使用 Track A 数据或进行效果对标。
- `v5-frozen-provider-validation.json`：部署源码 SHA 与本地相同。Qwen 供应商执行 1 次，562 输入 / 37 输出 token，总耗时 734.561 ms；GPT 执行 1 次，806 输入 / 32 输出 token，总耗时 4,033.44 ms。两者返回 NO_SERVICE，重复请求复用原 decision_id；不据此计算 P95 或模型效果。
- 手机配置卡现显示实际模型 profile、wire API、发送参数及配置哈希；真实鉴权读取和显示已通过 `v5-frozen-model-ui-device.txt`，截图 `v5-frozen-model-config-ui.png`。测试结束后恢复了原服务地址、模型绑定与令牌。
- 当前手机连 5080 仍使用 ADB reverse + SSH 转发，仅是开发验证通道；正式 HTTPS 入口、拔电脑运行及真实无线闭环尚未通过。部署与复现说明见 `FROZEN_MODEL_SERVICE.md`。

## 用户调整后的准备工作

- 已停止服务器 HTTPS 路线：尚未启用的两个 systemd unit 已移除，手机中尚未部署的实验 CA 配置与对应测试已移除；没有改动手机系统证书或官方配对。此前 5080 模型服务保留为已有工程参考。
- 保留了一处通用网络修复：经默认网关访问内网主机时可以选用有效 Internet 网络，设置检查与现场请求使用一致的网络选择。该修复也适用于后续 API Key 直连。
- 5090 的两份指定项目已通过 scp 下载所需源码，107 个文件的 SHA-256 全部匹配。远端没有安装工具、执行构建或修改文件。清单见 `v5-vln-reference-copy.json`，分析见 `NATIVE_RFCOMM_REFERENCE.md`。
- 原生 RFCOMM 控制通道已得到源码确认；P2P 仍有旧 CxrController 调用和协商适配，需分开处理。后续不再把新版 CxrApi 的 Client Secret / `.lc` 条件直接套用于自家原生控制通道。

## API Key 直连与官方授权后的首轮真实闭环

- `DirectModelConfig` / `DirectModelClient` 已接入实际现场 actor；配置卡改为模型、API 地址、API Key 与高级参数。Key 按 API 基础地址绑定并加密保存，供应商原文与标准化结果分别留档，导出验证器核对映射一致性。使用方法见 `DIRECT_MODEL_API.md`。
- `v5-direct-api-build.txt` / `v5-direct-api-ui-build.txt`：手机构建通过；JVM 当前包含 7 项传输、7 项直连、4 项 Harness 测试，全部通过。`v5-direct-api-python-tests.txt`：29 项通过。
- 真实手机客户端直接调用 Qwen，通过 actor、解析器、Room、导出完整流程。该项使用生成图像与测试传输，`v5-direct-api-runtime-result.json` 明确标注；`v5-direct-api-export-validation.json` 通过。
- 用户提供的官方 Client Secret 与 80 字节 `.lc` 已加密导入手机，未写入源码或研究导出；Client ID / Accesskey 不属于当前六参数 connectBluetooth 调用所需实参。
- 首次鉴权后 SDK 已连接但研究握手未通过；重新接入 ADB 后查明眼镜 App 未运行且设备睡眠。唤醒并以“启动研究模式”启动眼镜 App 后，SDK 鉴权与 HELLO/时钟握手通过，相机和显示均 ready，见 `v5-sdk-authorized-result.json`。没有改动眼镜传感器标志或系统配对。
- `v5-real-service-loop-device.txt`：实际手机前台服务 → 眼镜 Camera2 → CXR 蓝牙 JPEG 回传 → 手机本地模拟 SERVICE → 眼镜两帧可见 ACK → Room Receipt，15.948 秒通过。1 张实际照片、1 条回执；拍照请求至图像到达 3,429 ms。`v5-real-service-loop-validation.json` 完整导出通过。
- `v5-real-silence-loop-device.txt`：相同真实设备路径上的 NO_SERVICE 模拟决策，15.231 秒通过，零显示回执；`v5-real-silence-loop-validation.json` 完整导出通过。尚未完成像素级前后图像对照。
- 两轮图片均走 CXR_BLUETOOTH 降级通道；P2P 未确认就绪。长期稳定、50 次传图、1 小时采样、20 轮服务/重复抑制/材料变化序列及 pilot 仍未完成。
- API Key 配置卡的实际输入、加密保存、清空输入与真实验证按钮已通过真机测试（`v5-direct-api-ui-device.txt`，9.359 秒）；界面截图为 `v5-direct-api-config-ui.png`。测试过程中用无效测试字符串验证保存功能，随后在 finally 中恢复了原真实 Key，再执行真实验证；未把真实 Key 显示在输入框中。
