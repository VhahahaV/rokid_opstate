# OPSTATE 待办清单

记录 2026-09-18 之后仍未完成、需要开发或上机验证的内容。

**这个项目的最终目的是展示**：基于采集的数据跑在线服务模式，捕捉"主动服务"发生的时刻，并把**当时的画面 + 卡片**一起截下来，供后续挑选效果好的案例。下面的优先级按这个目标排。

证据纪律（沿用既有约定）：一次真实运行只是冒烟结果，不是 p95 gate；组件级 fixture 证据不能替代真实链路验收；不要把不同版本、不同进程的指标拼接成一个成功率。

---

## 已完成并上机验证（2026-09-18）

### 眼镜端显示：绿底

根因是**用 View 树搭页面**会让整帧背景变成 `(16,16,16)`——在这块单波长面板上就是铺满全屏的绿底。换成单个 `Canvas` 绘制的 `HudView` 后回到 96–98% 纯黑。另外窗口写 `screenBrightness` 会造成同一类蒙版，已彻底不写。见 `74a23a6`..`050394d`。

### 主动服务真的能送达了（原来一条都送不到）

第一次真实在线服务会话什么都投不出来。手机显示"尚未收到启动提示的显示确认"，之后每张卡片都被拒绝。三个独立缺陷叠在一起，全部修掉（`a195fd1`）：

1. **面板从未点亮**。`presentPage()` 依赖 Activity 的 `turnScreenOn`，但面板睡着时这个页面已经是 resumed 状态，start 被当作 `onNewIntent`，`turnScreenOn` 不触发。改为 `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`，实测 `Asleep → Awake`。
2. **页面永远无法就绪**。`displayVisible` 被"页面已经画了内容"门控，而活跃会话无卡片时故意渲染空黑页——于是页面无法接受那张会赋予它内容的卡片，同时服务又扣着卡片等页面就绪。死锁。现在它是字面意思：页面在、有焦点、可以接卡片。
3. **被扣住的卡片在到达时就花掉了幂等键**。卡片判定跑在 `gate.admit` 之后，于是卡片到达时被 admit 一次，flush 时再 admit 变成 `DUPLICATE` 被丢掉——**恰恰是为了让佩戴者看到才扣下的那张卡片，成了唯一画不出来的那张**。现在先判定、真正处理时才 admit 一次。

### 真实端到端闭环（第一次）

真拍摄 → 观察包 → **真实模型** → SERVICE 决策 → 眼镜卡片 → 两帧可见 ACK → 回执。实测：`观察 2 / 观察包 2 / 真实回执 2`，`最近投递结果 已实际显示`，`眼镜已显示启动提示`。

### 演示帧按需渲染 + 索引

"当时的画面 + 卡片"现在能生成了（`38460cc`）。**按需渲染而非运行时合成**：零件本来就都在 journal 里（模型看到的那张 JPEG、决策的 title/body/mode、证明它真的可见过的回执），所以样式可以改、可以重渲、跑会话时零成本。只取 `delivery == DISPLAY_VISIBLE` 的服务——被抑制、过期、没被确认的都不是"有人看到过的时刻"。

合成遵循 HUD 自己的规则：单一发光色 + alpha、`SCREEN` 加亮混合（黑底不加光，也就不会在实景上糊一个黑方块）、保持黑位的 1.35× 增益。HUD 放在画面中段（波导覆盖的视野比相机窄）。

入口：记录页 →「生成演示帧」，输出 `Android/data/ai.opstate.rokid.phone/files/demo/<sessionId>/`（每帧一张 JPEG + 一份带 mode/时延/时间的 `index.jsonl`）。

实测输出：教室场景 `教室环境观察`、桌面场景 `桌面物品整理`（"桌面上有笔记本电脑、钥匙和扩展坞，建议将钥匙归位至包内或固定位置，避免滑落。"），模型时延 2.4–4.0 s。

### 手动触发＝可控的演示杠杆（已验证）

在线服务模式下点「拍照观察一次」→ 一次观察 → 一个包 → 一次模型调用 → 一张真实卡片 → 一条回执。**一次点击＝一次真实主动服务**，可复现、可重来。摆好场景再点，比调阈值靠谱。

---

## P1 — 让"挑片子"变成日常工作流

### 4. 候选帧的挑选与导出

现在只能生成、拉出来看。还缺：

- **手机上的挑选界面**：列出候选帧（图 + 模式 + 时延 + 时间），能标记、能只导出选中的那几个。
- **保存到相册**：目前写在 `Android/data/...` 下，手机相册看不到。改成 MediaStore（`Pictures/OPSTATE`）就能直接在手机上翻看。索引仍留在 app 私有目录。
- **批量与跨会话**：现在一次只处理一条会话。挑片子通常想跨会话看。

### 5. 可读性

亮背景下文字会被冲淡（`SCREEN` 混合在接近纯白的区域无能为力——真实波导也是这个问题）。当前用 1.35× 增益缓解。若要宣传片级效果，可考虑给 HUD 加一圈极淡的暗描边，或优先挑中低亮度场景。

---

## P1 — 触发策略（当前在线链路没有策略）

### 6. 门控算法两端都没上线

`AnchorScheduler` / `AnchorPolicy` / `QuietGate` / `ScenarioRuntime` / `TriggerIndex` **在生产代码里没有任何调用者**，只出现在 `code/core/tests/CoreTests.kt` 和 `core/simulation/`。在线链路是 packet 驱动的，结果是：

- 没有语音门、没有运动门、没有最小评估冷却、没有分数阈值。
- `StudyRuntimeEngine.kt:721` 传入的 `lastEvaluationElapsedMs` **从未被赋值**，所以即使接上，评估冷却也不会生效。

**实测后果**：自动路径需要视觉新奇度 ≥ 0.44 且受 180 秒冷却限制，对着静止场景基本静音；手动触发则完全绕过它。演示场景下"手动为主 + 自动兜底"已经够用，但若要自动捕捉，这是唯一的路。**这是行为变更，需要单独评审。**

---

## P1 — 现场恢复（进程被杀后继续原会话）

**现状**：手机进程死亡后，启动时会**结束**会话并写终止记录（本轮实测：覆盖安装 APK 后那条 20:38 的会话就变成了"进程中断 · 已收尾"）。用户现场实验一跑很久，被杀一次就得重来；30 分钟 soak 就是被系统管家 force-stop 掉、直接记成失败的。

**已经查清的关键事实**（避免重复踩坑）：

- **状态只在 `journal` 表里**（canonical JSON）。`observation` / `context_event` / `episode` / `action_receipt` / `decision` / `delivery` 这些 Room 表是**死表——没有任何代码往里写**，读它们是错的。
- `active: Session?` 是单个内存对象（约 30 个字段），checkpoint 只在换阶段时写，**没有周期性落盘**。
- `zeroElapsed`（`SystemClock.elapsedRealtime()` 基准）**没有持久化**，重启后无法把 elapsed 系状态放回时间轴。
- `advanceEpoch` 只在 `endedAtEpochMs == null` 时允许，而现在的恢复正是把 epoch 花在"关闭"上。
- **`EpisodeRegistry` / `ReceiptPolicy` 没有重建入口**，需要先给 core 加 API，不是写个 reader 就够。
- 眼镜侧：`SESSION_START` 携带更高的 epoch 会被接受（`StudyProtocol.kt:66-75`），并触发 `resetTransient()` 清掉卡片/ACK/触发器，同时重发 `SESSION_READY`。所以"同会话 + 更高 epoch"这条路是通的。

**设计方向**（未实施）：恢复时挂起而非关闭 → 从 journal 行重建 `CausalContextStore` / `EpisodeRegistry` / `ReceiptPolicy` / `SessionSetup` → 丢弃队列与在途工作并如实写入终止记录 → 推进 epoch、重发 `SESSION_START` → 记录一条恢复审计事件，让导出能看出这道接缝。

**必须一起改的测试**（现在断言的是旧契约"关闭"）：`ProcessRecoveryDeviceTest`（verify 阶段断言 `endedAtEpochMs != null` 与状态 `STOPPED`）、`JournalLifecycleDeviceTest.interruptedProcessIsClosedOnceWithoutInventingReceipts`。验收要改成：同一 session id、epoch 更高、历史与回执完整、**零重复回执**、在途工作恰好取消一次。

---

## P2 — 在线链路的已知脆弱点

按影响排序，均来自代码走查，未实测：

1. **`serviceVisible` 没有超时**：`StudyRuntimeEngine.kt:570` 置位，只在收到眼镜 DISMISS 或取消时清除，而 `kickModel` 阻塞在它上面。一次丢失的 `DISPLAY_DISMISSED` 会让链路**永久停摆**，没有看门狗。
2. **同修订抑制在 packet 路径上实际失效**：`:951` 每个包新建一个合成 episode，而 `ReceiptPolicy.shouldSuppress` 以 `episodeHandle` 为键，永远匹配不上。`DisplaySequenceDeviceTest` 用的是稳定 episode，覆盖不到。
3. **回执在每次请求里无上限增长**：`:962` 传 `allReceipts()` 全量，观测有上限而回执没有，直到 2 MiB 上限直接抛错。长会话必炸。
4. **模型请求去重缓存永远命中不了**：缓存以 `requestId` 为键，而 `:962` 每个包都生成新 UUID。
5. **采集与模型严格互斥**：`timeoutMs=15000` 意味着模型慢时最多 15 秒完全不拍照。
6. **队列无上限且持有 JPEG**：`:102` `ArrayDeque<PendingModel>` 不设上限，FIFO 队头阻塞。
7. **actor 内阻塞等待**：`resume()` 里的 `transport.refreshPeer()` 最长 25 秒，期间不处理任何事件。
8. **每次请求重新解析网络**并新建 OkHttp client。
9. **`EMERGENCY_CLEAR` 从未被生产代码调用**。
10. **显示命令无重试、无看门狗**。

---

## P2 — 环境与数据

1. **P2P 未确认就绪**：图像实际走 CXR 蓝牙降级通道（单张往返约 3.4 秒）。
2. **内置 R6 历史在任何可构建包里都没有**（`assets/datasets/...` 被 gitignore）。历史只能来自一次 COLLECTION 采集或 `tools/import_validation_bundle.py` 导入。
3. **图像质量阈值未校准**：`min_luma 18 / max_luma 238 / min_edge_energy 25`，作用在 **32×24 缩略图**上。本轮实测 8 张真实采集全部通过（亮度 119–137、清晰度 33–45），但历史上出现过"均为黑帧"导致零请求。
4. **历史正文日期不自动改写**。

---

## P3 — 研究验证（未开始）

- 4–6 人真实 pilot、`docs/study/PILOT_PROTOCOL.md` 的可用性评价。
- 30 分钟 soak（上次在约 201 秒被系统管家 force-stop，**未通过**，且不能与新进程拼接）。
- 50 次传图完成率、1 小时采样、两 profile 的真实条件对比、正式版本冻结、Track A 对照。
- 拔掉电脑的纯无线运行（目前开发验证仍依赖 ADB）。
