# 主动服务的闸门：为什么"触发即服务"，以及改了什么

日期：2026-09-20。范围：手机端在线服务 runtime（CVTT 前端 → 模型 → 投递/回执）。
证据：`dist/OPSTATE_Streaming_Harness_Code_Evidence_20260920.zip`（解包在 `.local/harness_evidence_20260920/`）。

## 1. 现象

在线服务会话里，只要 CVTT 前端触发一帧，模型几乎必然给出一张卡；把「强制服务」关掉也不会变，
而且同一件事会被反复投递。用户原话：「只要触发，就一定的会服务，不管有没有开强制服务，也不管有没有新增价值」。

## 2. 根因（三条，彼此独立，都能单独造成这个现象）

### 2.1 回执不携带"说了什么"

`ActionReceipt` / `WireReceipt` 里没有任何卡面文本。流式路径每包写入的
`intentFingerprint` 是常量 `"packet-stream"`，`objectHandle` 是常量 `"stream"`
（`StudyRuntimeEngine.recordPackets`，`InferenceContract.buildPacketRequest`）。
模型能看到的每一条回执内容都一样，只有一个 id、一个时间戳和它当时引用的证据 id。

结果是**模型无法判断"这句话我是不是已经说过了"**。而回执存在的全部意义就是这个判断：
资源包的冻结合同写明重复抑制是 `MODEL_VISIBLE_RECEIPTS_ONLY`，harness 不做语义去重，
由模型看着回执决定。回执里没有内容，这条路就是断的。

### 2.2 确定性去重在流式路径上不可达

`ReceiptPolicy.shouldSuppress` 的匹配键是 `(episodeHandle, intentFingerprint)`。
流式路径给每个包发一个**新的** episodeHandle：`"stream-${packet.packetId}"`。
所以包 1 写下的回执永远匹配不上包 2 的 episode，`SUPPRESS_DUPLICATE` 在
服务会话里是死代码——只有同一包的重复投递才可能命中，而那已经被 requestId 去重挡住了。

### 2.3 提示词把 NO_SERVICE 的条件写成了不可能事件

`prompt.md` 的标准分支（非强制）里写着：

> Choose NO_SERVICE only when a receipt already covers the same assistance, or when there is no visible basis for anything at all.

第二条在实拍里几乎不成立，第一条由 2.1 + 2.2 保证不成立。
于是标准提示词与 `prompt_force.md` 的实际行为一致——**这就是"开不开强制服务都一样"**。

### 2.4 放大项：检索是无条件的整本倾倒

`historyRetrievalSnapshot` 把「最近 1 小时上下文（上限 128 条）+ 词面命中 8 条 +
操作员导入的整本记录（`pinnedIds` 全量注入）+ 4 张历史图」塞进每一次请求。
导入的合并记录有 200+ 条情境行，且**全部**进入 `historyContextIds` 被钉住。
于是每一次调用都带着整本记录，"总有一点可说的"恒为真，连 2.3 的第二条兜底也失效。

## 3. 资源包 HR 的对照

冻结集 `prompts_frozen_sol2k`（`prompt_hash = da12206f…`），方法 `history_retrieval`。

| | HR 的做法 | 手机端改前 |
|---|---|---|
| 先决结构 | STEP 先形成 candidate（`candidate_kind` / `why_now` / `missing_facts`），SERVICE 要求 `candidate_kind != null` 且 `missing_facts == []` | 没有中间层，直接出卡 |
| 证据门槛 | 程序强制：≥2 条**非相机** STATE 记录（回执/记忆引用会展开回原始记录），不足记 `INSUFFICIENT_DISTINCT_STATE_RECORDS` 并抑制 | 无 |
| 重复 | 回执带正文（`message`）与 `completion_status`，**由模型判断**；harness 明确不做语义去重 | 回执无正文，且确定性去重不可达 |
| 检索 | prefetch 是**有界选择**（top-4/top-8 + 3072 ref token），只取 PUBLIC_RECORD，排除本包记录；**取不满不补**，空就是空 | 无条件整本 + 词面 8 条 |
| 提示词 | 高打断门槛：确立的目标/承诺/未决项 + 有界贡献；venue hours、可用性、邻近、想象意图都不算 | TESTING BIAS，倾向 SERVICE |

两次整周 HR 运行（同 425 包、同冻结提示词、同实现哈希）：

| run | SERVICE | 交付 | 率 |
|---|---|---|---|
| qwen3.8-flash | 4 | 4 | 0.94% |
| gpt-5.6-sol | 26 + 21 次 RETRIEVE 中 3 次转 SERVICE | 27（2 次被证据门槛抑制） | 6.35% |

421 条 SILENT **全部**是 `candidate_kind = null`：沉默的结构含义是"没有形成候选"，不是"想了一想决定不说"。
qwen 的 4 张卡里有 3 张是**跨记录拼接**，另一半正是靠 prefetch 送进来的。

## 4. retrieve 概念：值得搬的是"选择语义"，不是那一轮往返

资源包里的 RETRIEVE 是模型发起的一轮显式检索：`route="RETRIEVE"`，一条 ≤160 字符的查询，
`service=null`，系统最多检索一次，然后把结果和原快照交给只能回答 SILENT/SERVICE 的 FINAL。
FTS5 trigram + BM25 排序，只排除 FINAL 已保留原文的记录，**无匹配就返回空结果，不补齐名额**。

几条值得单独记住的规则：

- 检索是**内部调查**，不是打断；证据门槛不适用于"去找"这个动作（"Do not require the delivery evidence minimum before searching"）。
- **检索假设不是事实**。FINAL 拿到的是 candidate + query + 结果，它的作用是定位问题，不是当证据用。
- 检索结果为空**不代表**那条记录不存在（"Do not mistake an empty search for proof"）。
- 检索成功而最终沉默是正常结局；不要为了给检索一个交代而编一张卡。
- 实测上它很少被用到，且用完仍多半沉默：gpt-5.6-sol 21 次 RETRIEVE 里 18 次 FINAL 仍 SILENT。

**结论：真正决定"触发≠服务"的不是那一轮往返，而是三条结构性规则**——
回答前必须先有候选、卡片必须站在非相机记录上、以及工作集是**有界且允许为空**的选择。
这三条在手机端都可以不引入第二轮调用就落地。往返本身留作后续（见 §7）。

## 5. 手机端改了什么

映射到 §3 三条规则 + 去重可判定性：

1. **回执带卡面**（对应 HR 的 `message`）。`ActionReceipt` / `ModelActionReceipt` / `WireReceipt` 增加
   `title` / `body`；`ReceiptPolicy.finalizeDelivery` 从 decision 填入；`RECEIPT_COMMITTED` 日志同步记录。
   模型现在读得到自己之前说过什么。
2. **确定性重复抑制真的会触发**。新增 `ContributionKey`（去掉大小写、空白、标点后的卡面摘要）与
   `ReceiptPolicy.shouldSuppressContribution`。只对**逐字相同**的卡生效——语义层面的"换个说法还是同一件事"
   仍然交给模型，因为那正是只有模型能判断的部分。
3. **程序强制的记录门槛**（对应 HR 的 2 条非相机 STATE 记录）。`DeliveryPolicy.Grounding`：
   卡片必须引用 ≥2 条非 IMAGE 观测，回执引用会展开回它当时引用的记录。
   **仅当请求里确实带了足够的非相机证据时才启用**——没有记录可站的会话（纯实拍）不该因此被整体噤声。
   未达标记 `UNGROUNDED`，卡不显示但决定照常入账。
4. **工作集变成有界、按相关性排序的选择**（对应 HR 的 prefetch 语义）。
   `historyRetrievalDetailed` 返回工作集 + `RetrievalTrace`：
   - 查询词只从**本包带来的文本**里取（没有新文本时退到"当前会话在做什么"），并剔除在记录中过半数出现的通用词——
     这些词匹配一切，等于没有信息。
   - pinned 记录**不再全量注入**，改为相关性优先、最近优先，上限 12 条。
   - 取不满不补：没有匹配就是空集，空集是有效答案。
   - 每个包的 `HISTORY_RETRIEVAL` 日志现在记录查询词与各层条数，所以"服务在一无所有上"可以回读。
5. **提示词换成真正的判定规则**。`prompt.md` 去掉 TESTING BIAS，改为 HR 的打断门槛（确立且未决的具体事项 +
   有界贡献），并写明记录门槛会被程序执行、回执带正文、时间条件缺失就不要说话。
   `prompt_force.md` 保持演示语义，并显式声明该模式的程序闸门是关的。
6. **"这一刻"必须是理由，不能是记录**。真机实测（§8）暴露出只加前面几条还不够：会话里**只要挂着一条记录**，
   模型就会为每一帧复述那条记录（"周四组会改至B302…另：差旅报销单需在月底前提交"），
   帧本身与卡片毫无关系——8/8 全服务。补上了 HR 冻结提示词里那条被现行 `prompts/` 删掉的时机规则：
   > For a non-emergency SERVICE, require a newly visible state or non-reused frame that shows the user is
   > action-ready in the relevant execution context … Digital evidence arriving by itself is not an
   > action-ready moment. The only exception is a real recorded deadline or time boundary whose useful
   > intervention value would be lost by waiting for such a frame.

   写进 `prompt.md` 后同一组帧变成 1/8。要点是：**未打勾是记录的默认状态，不是新信息**；
   复述记录（引用得再多）不算贡献；记录里的截止时间只有在"再等就没有价值"时才算时机。
7. **强制服务与生产闸门分离**。`DeliveryPolicy.Mode`：`FORCED_DEMO` 跳过记录门槛与去重（演示要出卡），
   但保留全部正确性检查（过期、会话、证据可见性、屏幕占用）。

## 6. 校准旋钮

服务率现在由这几个量决定，都是具名参数或单点常量：

| 旋钮 | 位置 | 默认 |
|---|---|---|
| 记录门槛条数 | `DeliveryPolicy.groundingFor(minimum=)` | 2 |
| 工作集 pinned 上限 | `CausalContextStore.historyRetrievalDetailed(pinnedLimit=)` | 12 |
| 词面检索上限 | 同上 `retrievalLimit` | 8 |
| 近期上下文上限 / 窗口 | 同上 `recentLimit` / `recentWindowMs` | 32 / 1 小时 |
| 通用词阈值 | `distinctiveTerms`（超过 1/4 记录的词不计；记录不足 8 条时不做此过滤） | 1/4 |
| 提示词门槛 | `prompt.md` | HR 规则，偏"有话就说" |

资源包对服务率**没有**任何数值目标（`capability_report.json` 与门禁都只查完整性），
所以手机端的率由实验标定，不照抄。要更高：放宽门槛条数与提示词；要更低：反之。
强制服务开关不受这些影响。

## 7. 没有做的部分

- **模型发起的 RETRIEVE 往返**（STEP 出 `route="RETRIEVE"` + `query` → FINAL 定夺）。改动面为：
  出力 schema 增加第三态与 `query`、`DirectModelClient` 的两阶段解析、引擎状态机多一轮、
  请求增加 `retrieval_context`、两份提示词。收益是让模型在"有怀疑但缺一条对应记录"时能去看，
  从而把一部分沉默转成有依据的服务；代价是约 5% 的包多一次调用与 2–5 秒延迟
  （gpt-5.6-sol 实测 21/425 触发 RETRIEVE）。**建议作为下一步单独提交**，
  因为它是协议变更，需要真机回归，且不改变本文件已经落地的三条结构规则。
- HR 的 `candidate` 中间结构与 `missing_facts` 字段。手机端用证据门槛 + 提示词达到同等效果，
  少一次协议变更；若将来要度量"候选形成率"，再补这个字段。
- `memory_view` 的 INDEX/RELEVANT 双模式。HR 里 memory 恒为空，该机制属于 incremental_memory 臂；
  手机端把对应思想用在检索上（有新闻本用相关性、无新文本退到最近）。

## 8. 验证

宿主机：

- `./tools/gradle.sh -PwithAndroid=true :opstate-core:coreTest`：27 项，含新增 3 项
  （相机独证被挡 / 回执带卡面且同卡不重发 / 空帧只带一个有界选择）。
- `make quick-check`：Python 38、核心 27、协议与手机 JVM 全通过。
- `make demo`：`DUPLICATE→SUPPRESSED_BY_RECEIPT`、`MATERIAL_CHANGE→DISPLAY_VISIBLE` 序列未变。
- 两端 APK、两端 instrumentation APK 构建通过。

真机（手机 `ANYXVB4906000933` / 眼镜 `1901092551802804`，`-r` 覆盖安装，未卸载）：

- **新增 `ServiceDecisionGateDeviceTest`**（`code/testing/runtime/…`）通过。它把真实引擎跑在手机上，
  注入一个**每次都答 SERVICE** 的模型，四步断言：只引用相机的卡记 `UNGROUNDED` 且不发显示命令 →
  引用两条记录后投递且回执带上 `title`/`body` → 显示空闲后逐字重复同一张卡记 `SUPPRESSED_BY_RECEIPT`
  且只发过一次卡 → 打开强制服务后同一张无依据的卡又被投递。**这就是"触发≠服务"的端到端证据。**
- `ServiceCaptureParityDeviceTest`(3)、`RuntimeLifecycleDeviceTest`(11)、`CollectionModeDeviceTest`(3)、
  `PhotoDeletionDeviceTest`(2) 全通过。
- `DisplaySequenceDeviceTest` 加上外部像素监视器（`tools/device_checks/watch_display_pixels.py`）在真机上通过：
  20 轮沉默/服务/重复/修订跑完真实 CXR 链路与真实显示，真实两帧可见 ACK，20 轮沉默期间眼镜面板
  `changed_pixels` 全为 0，`result.json` 记 `PASS`。该用例共用的本地模型（`RealHardwareLoopDeviceTest.LocalModel`）
  原先对每次调用都返回同一段文案，与"重复即抑制"不能共存（它会让"修订后应重新服务"这一步被当成重复）；
  已改为把 candidate 与 revision 写进卡面，与真实模型的行为一致。
- `HistoryContinuationDeviceTest` 无法运行：它要 `datasets/january-v10-r6/catalog.json`，而该数据集是
  gitignore 且已弃用。与被改动无关，失败发生在构造 dataset 时。

未做：整周服务率标定（单次会话的服务次数分布）。

### 8.2 真实模型上的服务率（`reports/latest/service_rate_probe.json`）

真机 + 真模型（手机上的 `qwen3.8-max`，走 `RealModelServiceRateDeviceTest`，`-e service_rate_probe true`）。
变量只有会话里挂什么记录，**同一组 8 张真实拍摄帧**在三个臂里各跑一遍，两个重复，共 48 次真实 API 调用：

| 臂 | 会话里的记录 | 加时机规则之前 | 加之后（两次合计） |
|---|---|---|---|
| `empty-record` | 一条与这些帧无关的记录（周会改地点 + 报销未提交） | **8/8 = 100%** | **1/16 = 6%** |
| `empty-bare` | 无记录 | 0/8 | 0/13 = 0% |
| `grounded` | 一条具体未决项 + 它到期的时间（15:30 出发 / 充电器与常用药未确认） | 8/8 | **15/15 = 100%** |

四件事值得记下来：

- **记录门槛拦不住复述。** `empty-record` 那 8 张卡全都合法地引用了两条记录，程序门槛照常通过——
  它要的是"站在记录上"，而这类卡恰恰最擅长站在记录上。卡面本身与画面无关，这才是问题，
  只有提示词能判断，所以修在提示词。
- **记录门槛确实做了它该做的。** 加时机规则前 8 张里有一张只引用了相机，被记 `UNGROUNDED` 拦下。
- **留了一条真的。** 第一次跑 `empty-record` 剩下的那 1 张，是模型在画面里认出了记录里写的 B302，
  属于"这一刻"与记录的联结而不是复述；第二次跑 0 张。6% 不是噪声，也不该压到 0。
  另一端 `grounded` 15/15，说明加严没有把该说的也一起压掉。
- **手机到 dashscope 有约 8% 的调用死在 IPv6 上**（48 次里 4 次）：
  `failed to connect to dashscope.aliyuncs.com/[IPv6] … after 5000ms`。
  `DirectModelClient` 用 `connectTimeout(5000)` 配 `retryOnConnectionFailure(false)`，而
  `InternetRouting` 的 `Dns` 按解析器顺序返回 A 与 AAAA 记录——手机的 IPv6 路由不通时，
  这次调用直接失败而不是退到 IPv4。**这是传输层问题，与闸门无关，本次没有改动传输行为。**
  候选修法是在该 `Dns.lookup` 里把 IPv4 排在前面（IPv6-only 网络下 A 查询为空，会自然退回 AAAA）。

这组数字是 8 帧的配对样本、两次重复，不是整周标定：它说明的是方向与量级，不是最终率。

### 8.1 顺带查出的两个既有问题（不是本次改动引入）

`AutomaticCaptureDeviceTest#glassesTriggerUploadsOnlyTriggeredFramesAndHandlesDigitalContext` 在真机上失败。
追下去有两条都与本次改动无关：

1. **眼镜不再因触发而立即拍照。** 未提交的 CVTT 迁移删掉了眼镜拍照条件里的
   `|| pendingTrigger.isNotEmpty()`（`GlassesStudyService`），并删除了 `EDGE_DIGITAL_EVENT` 分支。
   旧设计下"来了一条情境"会立刻产生一帧，该用例的 15 s / 25 s 预算是照着那个行为写的；
   现在眼镜只按 `intervalMs` 拍，而该用例用的是 `SessionSetup` 默认的 60 s，25 s 内等不到第二帧。
   已按同文件的批量用例做法显式设成 10 s，并补上同样的"等 app 自己握手完成再自行连接"的守卫
   （缺这一条会以 `BLUETOOTH_DISCONNECTED` 失败）。改完用例越过了超时。
2. **"这一帧为什么被评估"没有记在帧上。** 观测行里的 `trigger_types` 只写前端判定
   （`frontEndVerdict`），DIGITAL / MANUAL 只进入 `ObservationPacket`，不进入观测元数据。
   所以一条因情境而被评估的帧，在记录与相册里看起来是"未触发"。用例随后断言的
   `assertEquals(2, triggered.size)` 因此恒为 1。**这是未提交的 CVTT 迁移留下的语义缺口，
   属于产品决定（页面上的标记到底表示"前端判定"还是"为何评估"），未擅自改动。**
   触发集合在 `packets` 流的 `OBSERVATION_PACKET` 里是完整的。
