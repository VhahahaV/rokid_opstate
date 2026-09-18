# OPSTATE 待办清单

记录 2026-09-18 这一轮之后仍未完成、需要开发或上机验证的内容。按"先能送达、再谈策略"的顺序排列。

证据纪律（沿用既有约定）：一次真实运行只是冒烟结果，不是 p95 gate；组件级 fixture 证据不能替代真实链路验收；不要把不同版本、不同进程的指标拼接成一个成功率。

---

## P0 — 先让"服务真的送达并被确认"

### 1. 启动提示卡片从未被确认显示

**现象**：真实会话启动后手机一直显示 `尚未收到启动提示的显示确认，请检查眼镜画面`，眼镜面板 `Asleep`，卡片超时作废。

**为什么以前没发现**：显示相关的测试都是把卡片直接塞进 UI 状态（`GlassesHardwareTest`、`DisplaySequenceDeviceTest`），从没走过"手机发启动卡 → 眼镜唤醒面板 → 两帧 ACK → `SESSION_STATUS_VISIBLE_ACK`"这条真实路径。

**已知约束**：
- 手机只在 `event.statusId == "start-${session.id}"` 时认这条 ACK（`StudyRuntimeEngine.kt:515`），该 id 来自眼镜 `GlassesStudyService.card()` 里的 `event_id`，不能改。
- 眼镜侧接受卡片要求 `graph.displayVisible.value` 为真；面板只是睡着时走 `heldCard` + `presentPage()`（`GlassesStudyService.kt:227-232`）。
- ACK 需要连续两次绘制且 `power.isInteractive`（`VisibleDrawAcknowledger.kt:19`）。
- 面板点亮依赖 `FLAG_KEEP_SCREEN_ON`；该 flag 在这台设备上会让 WindowManager 持有 `SCREEN_BRIGHT_WAKE_LOCK`——已实测有效（见 `050394d`）。

**待查**：卡片到达的那一刻面板处于什么状态；`presentPage()` 的 `startActivity` 在 Activity 已 resume（`launchMode=singleTop`）时是否还能触发 `turnScreenOn`；`wakeForCard()` 是否真的执行到。

**验收**：真实会话启动后手机显示 `眼镜已显示启动提示`，且眼镜侧能看到卡片；导出里能找到对应的 `SESSION_STATUS_VISIBLE_ACK`。

### 2. 真实链路还没产出过一次 SERVICE

**现状**：只跑到过 `NO_SERVICE`。真实拍摄 + 真实模型 + 真实投递策略已经串起来一次，但投递与回执这一段仍未在真实链路上走通。

**待办**：
- 让眼镜看到"值得服务"的内容（屏幕上的消息、日程、明确的状态变化），而不是静止桌面。
- 拿到一次真实 SERVICE，确认卡片在眼镜上可见、两帧 ACK 回来、`RECEIPT_COMMITTED` 落库。
- 量并记录：provider latency、`decision_to_visible_ms`、`packet_to_visible_ms`、`capture_round_trip_ms`、`command_ack_rtt_ms`。
- 导出并跑 `tools/validate_runtime_trace.py` + `tools/report_runtime_metrics.py`（`packets` 流的校验缺口已在 `b3e9af4` 修掉，否则这一步必然失败）。

**注意**：`tools/report_runtime_metrics.py` 的百分位 gate 需要 ≥20 样本，单次运行只会报 `INSUFFICIENT_SAMPLES`，如实记录即可，不要凑数。

---

## P1 — 触发策略（当前在线链路没有策略）

### 3. 门控算法两端都没上线

`AnchorScheduler` / `AnchorPolicy` / `QuietGate` / `ScenarioRuntime` / `TriggerIndex` **在生产代码里没有任何调用者**，只出现在 `code/core/tests/CoreTests.kt` 和 `core/simulation/`。在线链路是 packet 驱动的（`StreamingPacketizer` → `InferenceContract.buildPacketRequest`），结果是：

- 没有语音门、没有运动门、没有最小评估冷却、没有分数阈值。
- `StudyRuntimeEngine.kt:721` 传入的 `lastEvaluationElapsedMs` **从未被赋值**（`:105` 声明，无写入），所以即使接上，`AnchorScheduler` 的评估冷却也不会生效。

**实测后果**：对着静止桌面跑一分钟，只产生 1 个观察包。包需要视觉新奇度 ≥ 0.44 且受 180 秒冷却限制（`StreamingPacketizer.kt:25-31`），静止场景等于静音；反之在活动场景下又可能过于频繁。

**待办**：决定是否把门控接进在线链路（含补上 `lastEvaluation` 的写入），并冻结参数。**这是行为变更，需要单独评审**。

---

## P1 — 现场恢复（进程被杀后继续原会话）

**现状**：手机进程死亡（崩溃 / 被系统杀 / 覆盖安装）后，启动时会**结束**会话并写终止记录。`RUNTIME_RECOVERY.md` 明说："结果不证明跨进程继续原现场会话"。用户现场实验一跑很久，被杀一次就得重来；30 分钟 soak 就是被系统管家 force-stop 掉、直接记成失败的。

**已经查清的关键事实**（避免重复踩坑）：

- **状态只在 `journal` 表里**（canonical JSON）。`observation` / `context_event` / `episode` / `action_receipt` / `decision` / `delivery` 这些 Room 表是**死表——没有任何代码往里写**，读它们是错的。
- `active: Session?` 是单个内存对象（约 30 个字段），checkpoint 只在换阶段时写，**没有周期性落盘**。
- `zeroElapsed`（`SystemClock.elapsedRealtime()` 基准）**没有持久化**，重启后无法把 elapsed 系状态放回时间轴。
- `advanceEpoch` 只在 `endedAtEpochMs == null` 时允许，而现在的恢复正是把 epoch 花在"关闭"上。
- **`EpisodeRegistry` / `ReceiptPolicy` 没有重建入口**，需要先给 core 加 API，不是写个 reader 就够。
- 眼镜侧：`SESSION_START` 携带更高的 epoch 会被接受（`StudyProtocol.kt:66-75`），并触发 `resetTransient()` 清掉卡片/ACK/触发器，同时重发 `SESSION_READY`。所以"同会话 + 更高 epoch"这条路是通的。
- 眼镜的 `PhotoBuffer` 是文件支撑的，进程死亡不影响；但 SERVICE 模式下 `eligiblePhotos()` 按当前 `session_id` 过滤，旧会话的照片会滞留不同步。

**设计方向**（未实施）：恢复时挂起而非关闭 → 从 journal 行重建 `CausalContextStore` / `EpisodeRegistry` / `ReceiptPolicy` / `SessionSetup` → 丢弃队列与在途工作并如实写入终止记录 → 推进 epoch、重发 `SESSION_START` → 记录一条恢复审计事件，让导出能看出这道接缝。

**必须一起改的测试**（现在断言的是旧契约"关闭"）：`ProcessRecoveryDeviceTest`（verify 阶段断言 `endedAtEpochMs != null` 与状态 `STOPPED`）、`JournalLifecycleDeviceTest.interruptedProcessIsClosedOnceWithoutInventingReceipts`。验收要改成：同一 session id、epoch 更高、历史与回执完整、**零重复回执**、在途工作恰好取消一次。之后再做真机 force-stop 验证。

**UI**：`SessionReviewScreen` 现在把中断会话显示为"进程中断 · 已收尾"，恢复后这个标签不再准确，需要真实状态。

---

## P2 — 在线链路的已知脆弱点

按影响排序，均来自本轮代码走查，未实测：

1. **`serviceVisible` 没有超时**：`StudyRuntimeEngine.kt:570` 置位，只在收到眼镜 DISMISS（`:583`）或取消（`:918`）时清除，而 `kickModel` 阻塞在它上面。一次丢失的 `DISPLAY_DISMISSED` 会让链路**永久停摆**，没有看门狗。
2. **同修订抑制在 packet 路径上实际失效**：`:951` 每个包新建一个合成 episode（`stream-${packetId}`），而 `ReceiptPolicy.shouldSuppress` 以 `episodeHandle` 为键，永远匹配不上。`DisplaySequenceDeviceTest` 用的是稳定 episode，覆盖不到这个缺口。
3. **回执在每次请求里无上限增长**：`:962` 传 `allReceipts()` 全量，观测有 128+8+4 的上限而回执没有，直到 2 MiB 上限（`DirectModelClient.kt:86`）直接抛错。长会话必炸。
4. **模型请求去重缓存永远命中不了**：缓存以 `requestId` 为键（`DirectModelClient.kt:75`），而 `:962` 每个包都生成新 UUID。重复提交会重新打供应商并重新落原文。
5. **采集与模型严格互斥**：`timeoutMs=15000` 意味着模型慢时**最多 15 秒完全不拍照**，之后还有 `decisionTtlMs=20000` 等 ACK。没有时延预算。
6. **队列无上限且持有 JPEG**：`:102` `ArrayDeque<PendingModel>` 不设上限，每项含当前帧 + 最多 4 张历史图；FIFO 队头阻塞，且不会丢弃快照已过期的包。
7. **actor 内阻塞等待**：`resume()`（`:761`）里的 `transport.refreshPeer()` 最长 25 秒，期间单 actor 不处理任何 tick/图像/ACK。
8. **每次请求重新解析网络**：`InternetRouting.client()` 在每次 `exchange` 里重跑网络枚举并新建 OkHttp client。
9. **`EMERGENCY_CLEAR` 从未被生产代码调用**（只有测试 fixture），显示清理完全依赖眼镜本地 TTL 和显式 `CLEAR_SERVICE_CARD`。
10. **显示命令无重试、无看门狗**：`transport.command(...)` 一发即忘，唯一兜底是 `tick()` 里的过期检查。
11. `CausalContextStore.historyRetrievalSnapshot` 对每个 term 重复 `lowercase()`（最多 32 次/事件），且在 actor 线程上同步跑。

---

## P2 — 环境与数据

1. **P2P 未确认就绪**：图像实际走 CXR 蓝牙降级通道（单张往返实测约 3.4 秒）。P2P 失败被当作非致命吞掉，所以"能跑"但很慢。
2. **内置 R6 历史在任何可构建包里都没有**：`assets/datasets/january-v10-r6/` 被 gitignore。历史只能来自一次 COLLECTION 采集或 `tools/import_validation_bundle.py` 导入。
3. **图像质量阈值未校准**：`min_luma 18 / max_luma 238 / min_edge_energy 25`，作用在 **32×24 缩略图**上（`LocalImageQualityAnalyzer.kt:26`）。本轮实测 8 张真实采集：亮度 119–137、清晰度 33–45，正常光线下通过；但历史上有过"均为黑帧"导致零请求的记录，阈值仍应在 pilot 里校准。
4. **历史正文日期不自动改写**：导入的 R6 文本保留旧日期对照当前时钟，属于实验设计问题。

---

## P3 — 研究验证（未开始）

- 4–6 人真实 pilot、`docs/study/PILOT_PROTOCOL.md` 的可用性评价。
- 30 分钟 soak（上次在约 201 秒被系统管家 force-stop，**未通过**，且不能与新进程拼接）。
- 50 次传图完成率、1 小时采样、两 profile 的真实条件对比、正式版本冻结、Track A 对照。
- 拔掉电脑的纯无线运行（目前开发验证仍依赖 ADB）。

---

## 本轮已完成（供对照）

- 眼镜端显示：绿底问题已解决。根因是**用 View 树搭页面**会让整帧背景变成 `(16,16,16)`，换成单个 `Canvas` 绘制的 `HudView` 后回到 96–98% 纯黑；另外窗口写 `screenBrightness` 也会造成同样的蒙版。见 `74a23a6`..`050394d`。
- 导出校验器补上 `packets` 流，否则第一次真实在线服务会话的导出必然校验失败；样本导出同步修正。见 `b3e9af4`。
- 手机历史记录页把操作模块提到顶部。见 `33b82f5`。
