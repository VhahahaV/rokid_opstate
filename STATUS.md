# 当前状态 · 2026-09-16

**5080 是当前开发主机；手机相册、眼镜磁盘缓存与本地 trigger 版本已安装，已核对两台设备同时拔线 54.21 秒期间持续拍摄和无线同步；尚未达到双机同时离线 60 秒的验收时长。** 仓库：`/home/caizhuoqiang/Code/rokid_opstate`。默认由手机 API Key 直连模型；mock 只用于测试。

| 范围 | 状态 |
|---|---|
| 源码与数据 | 六个 Gradle 模块、测试、原始 R6 ZIP、公共 replay 数据、图标、文档和选定证据已迁入 |
| 依赖 | 独立 JDK 21、Linux Android SDK 35/Build Tools 34、Gradle/Kotlin/Rokid 缓存、Python 3.12 venv 已配置 |
| Linux 短回归 | `make quick-check` 通过：Python 33、核心 23、协议 13、手机 JVM 29 |
| Android 构建 | 手机、眼镜 APK 及 instrumentation APK 已成功构建 |
| 5080 真机短回归 | BVL-AN00 手机 20 项、RG-glasses 眼镜 2 项通过；采集→本机历史→服务的新增设备用例通过 |
| 签名与凭证 | 现有调试签名保留在源码外，两个宿主 APK 签名一致；Rokid 凭证私有迁移，文件权限 600 |
| 清理 | 清除约 1.5 GB 缓存/重复数据库/解包与导出产物；原始输入和有效证据保留 |
| 现场业务 | 官方 SDK 握手、真实 Bluetooth/P2P 拍照、眼镜重启恢复、20 轮显示与沉默组件序列已有真机证据；5 秒间隔连续采集 62 秒得到 13/13 张有效原图，60 秒累计定时漂移 105 ms |
| 手机操作 | 首页先可选导入本机照片/合成数据或 R6 公共历史，再选采集/在线服务；导入只读且不启动会话、trigger 或模型。采集页展示照片与待同步数量，记录页按日期显示相册，大图可翻页并查看时间、尺寸、大小、定位和设备信息 |
| 眼镜采集 | 默认每 1 分钟拍照，选项为 5 秒、30 秒、1 分钟、2 分钟；照片先原子落盘，手机设置每批 1–500 张（初始默认 60）。批量达到阈值、手动同步或结束时传输；手机原图与日志持久化后发送 `IMAGE_COMMITTED`，眼镜才删除对应原图。断连继续采集，进程重启保留缓存，下一次采集恢复同步；重启进程后需恢复会话，不能宣称无人值守自动重启采集 |
| 在线服务 | CVTT v1（DINOv3 + 3 个 GRU ONNX 头 + 迟滞，**无画质门**：模糊/过暗的帧照样判定，画质指标只用于采集模式的「需重拍」）**跑在手机上**：在线服务每拍一张立刻上传，手机对每一帧跑一次前端判断，命中才建包调用模型，其余帧照样保存、计数、上屏。眼镜只负责拍摄、缓冲与上传（服务模式不攒批）。设置项为拍照频率与「自动前端触发」（无上传频率、无批量），开始前和会话中都可调；每张图在记录与相册里带判定标记（触发 / 未触发 / 无判定）。眼镜端有可用的状态页：采集开在页上，服务默认静默、单击触控板即显示（拍照频率、已拍、待同步、前端开关）。|
| 本轮验证 | 手机组件 4 项、眼镜组件 4 项（含 100 张磁盘队列及重建/错误 ACK）、手机历史与相册 UI 2 项、眼镜 trigger 实机链路 1 项通过；真实采集分批同步和 GPS 元数据已观察到。100 张验证使用生成的组件图像，不是 100 张真实拍摄长测 |
| 后台运行 | 已修正当前眼镜的后台 AppOps 限制，增加应用电池优化白名单和两端 CPU 唤醒锁。双屏关闭 69 秒时手机落盘 15 张（USB 连接）。主机日志确认 16:06:52–16:07:46 双机 USB 同时断开 54.21 秒，期间拍摄 12 张、无线落盘 12 张，附近最大拍摄间隔 5.133 秒；整次 482 张原图哈希通过，结束后眼镜缓存为空。双机离线满 60 秒和长期运行仍未验证 |
| 当前真机链路 | CXR 应用通道、研究握手、Camera2 实拍、手机落盘、本机历史导入和真实直连模型已联合通过；本次设备在进入应用配对状态后需要重启一次眼镜端 OPSTATE 进程以刷新 CXR 订阅 |
| 后续研究 | 明亮实景完整联合评估、长期无电脑运行、Track A 和 pilot 未执行，留给后续任务 |

9 月 16 日交互补验：眼镜收起时释放常亮、窗口亮度置零，系统实测 `Display OFF`；手机后台 149.25 秒，其中双屏关闭 64.71 秒期间新增保存 14 张真实照片，两端前台服务仍运行。注入返回键后眼镜回到 Rokid 桌面，随后继续保存 4 张照片；实体触控条双击尚未重测。测试使用 5 秒/张、每 2 张同步，USB 连接，已结束并清空待同步缓存。默认选项仍为 1 分钟，保留用户已保存的同步批量 2 张（新安装默认 60 张）。证据：[后台与熄屏实测](reports/latest/background_display_real_device.json)、[显示与返回组件回归](reports/latest/glasses_display_navigation_test.txt)。

相机占用优化已部署：采集间隔 5 秒/30 秒复用预览，1 分钟/2 分钟逐张释放相机；空闲、暂停、结束也释放。首次取图等待实际 AE/AWB 稳定，替代固定延迟。当前眼镜三轮冷启动实拍为 1.271–1.342 秒，三张均通过现有画质检查；修改前立即取首帧的三张均未通过。1 分钟实测两次连接/关闭相机并同步 2 张，5 秒实测复用相机、暂停释放、最终保存 5 张；两个会话均已结束且缓存为空。短回归、APK 构建和新增真机用例通过。此证据不测量整机电量或续航增益；阈值未做功耗盈亏标定。证据：[启动前](reports/latest/camera_startup_before.json)、[启动后](reports/latest/camera_startup_after.json)、[实拍占用与释放](reports/latest/camera_duty_cycle_real_device.json)、[设备用例](reports/latest/camera_power_device_tests.txt)。

[迁移交接与运行命令](docs/implementation/MIGRATION_5080.md) · [代码导航](docs/CODE_MAP.md) · [批量采集及熄屏证据](reports/latest/buffered_capture_real_device.json) · [眼镜 trigger 真机证据](reports/latest/edge_trigger_real_device.json) · [历史/相册 UI 验证](reports/latest/history_gallery_ui_device_tests.txt) · [业务证据边界](docs/implementation/V5_ACCEPTANCE_STATUS.md)

9 月 19 日在线服务的拍摄与上传对齐采集模式：**前端算法从眼镜端搬到手机端**：在线服务每拍一张立刻上传到手机，手机对每一帧跑一次 CVTT 判断，命中才调用模型；眼镜只负责拍摄、缓冲与上传，服务模式不设批量（采集模式仍按「每批同步张数」攒批）。眼镜 APK 因此不再包含 111 MB 的 ONNX 图。手机端把「实时观察与情境」与「拍摄与上传」拆成两块（修掉了原先挤成一列竖排的按钮），每张缩略图右上角标出前端判定（触发 / 未触发 / 无判定），大图详情里给出判定、前端版本与触发分数；服务模式唯一的设置项是拍照频率与「自动前端触发」。眼镜端补上了服务会话的前端：过去只在采集模式下发状态页，服务会话 `demoStatus` 为空、`render` 无分支可走，单击触控板只是在两个空页面之间切换；现在两次模式一致地由眼镜自己的状态出页（拍照频率、已拍、待同步、前端开关），采集开在状态页上、服务默认静默、单击即显示。实测触发率：合并采集记录 791 帧命中 24 次（3.03%/帧），22:56 会话 400 帧命中 13 次（3.25%/帧）；按标定 30 秒帧距折算 3.6–3.9 次/小时，与数据集实验的约 6 次/小时同量级。真机验证：`ServiceCaptureParityDeviceTest`（未触发帧保存且不评估、触发帧建包评估、批量下发到眼镜）、`ServiceCapturePanelUiTest`（在线服务面板显示三个计数与「每 N 张自动上传」）、`GlassesHardwareTest.aServiceSessionShowsItsCaptureStateWhenTapped` 与 `aLiveServiceSessionPublishesItsCaptureStateToThePage`（真机会话：静默→单击出页→再点收起）、`AutomaticCaptureDeviceTest#serviceSessionUploadsItsCapturesInBatchesOfTheConfiguredCount`（真机 5 秒×2 张批量上传，证据 `reports/latest/`）。本轮未测真实模型评估路径（真机验证用的是本地脚本模型）与长时间续航。

9 月 19 日采集历史补充：合成情境先按连续故事组织并经记录页追加（原始 144 条 → 214 条），随后按「载体自己产生的东西」重写：消息有发送者、邮件有发件人·主题、日程是时间与地点、备忘只写本人的一句提醒；删去 14 条写成服务说明或态势分析的条目，C-311 那条线从 9 次复述压到 4 条并按 15 分钟以上间隔铺开。旧台账的 68 条事实由记录页同一个「导入」入口就地撤回（`CONTEXT_RETRACTED` 墓碑，不改写任何已有行），新台账 41 条随后写入同一条记录：情境行 214 → 253，记录页与历史读取都按（发生时间, 文本）去重，显示 **791 张可用图片、57 条情境**，同一条事实只画一次，重复执行不产生新行。完整审计链校验通过；C-311 的服务事实改由日程（18:00–22:00 · 学生中心三楼）和同学的消息承载，签到截止仍未知。本轮没有执行真实模型或眼镜 SERVICE 回执测试。详见 [故事数据与因果边界](docs/implementation/SYNTHETIC_STORY_HISTORY.md)及[验证摘要](reports/latest/story_history_validation.json)。

9 月 19 日 CVTT 前端接入：`dist/cvtt_frontend_v1.zip` 的 ONNX 编码器、三个 GRU 头、冻结契约与 DINOv3 许可已进入眼镜 APK；手机和眼镜均以保留的调试签名覆盖安装。真实 RG 眼镜的 18 帧 ONNX 夹具短测通过（25.283 秒），逐帧分数容差 0.005、触发判定完全匹配。`make quick-check`、两端 APK 与眼镜 instrumentation APK 均通过。本轮不把 18 帧夹具当作真实在线服务或长期效果证据。详见 [CVTT 接入与运行设置](docs/implementation/CVTT_FRONTEND.md)。

9 月 20 日主动服务闸门：在线服务此前「只要触发就一定会服务」，与「强制服务」开关无关。三条独立根因：回执不携带卡面正文（`intentFingerprint` 是常量），模型无法判断"是否说过"；流式路径每包发新 `episodeHandle`，`ReceiptPolicy.shouldSuppress` 永远匹配不上，确定性去重在服务会话里是死代码；`prompt.md` 的 NO_SERVICE 条件被写成不可能事件，标准提示词与强制提示词行为一致。放大项是检索无条件把导入的整本记录（200+ 条）全量钉进每次请求，"总有一点可说的"恒为真。对照资源包冻结 HR 配置（`prompts_frozen_sol2k`）：STEP 必须先形成 candidate、投递前程序强制 ≥2 条非相机记录、回执带正文且由模型判断重复、prefetch 是有界选择且取不满不补。本轮采纳后三条，未引入 RETRIEVE 往返。改动：回执增加 `title`/`body`；新增 `ContributionKey` 与 `shouldSuppressContribution`（只对逐字相同的卡生效）；`DeliveryPolicy.Grounding` 记录门槛（仅在请求带了足够非相机证据时启用，未达标记 `UNGROUNDED`）；`historyRetrievalDetailed` 把查询词限制在本包文本并剔除通用词、pinned 记录改为相关性排序上限 12 条、空集不补齐，日志记录查询词与各层条数；`prompt.md` 换回真实判定规则，`prompt_force.md` 明确其程序闸门关闭；`DeliveryPolicy.Mode` 分离演示与生产。`make quick-check` 通过（Python 38、核心 27 含新增 3 项）。**真机已回归：**新增 `ServiceDecisionGateDeviceTest` 用一个每次都答 SERVICE 的注入模型在手机上验证了四条——只引用相机的卡记 `UNGROUNDED` 且不发显示命令、引用两条记录后正常投递且回执带卡面、显示空闲后逐字重复同一张卡记 `SUPPRESSED_BY_RECEIPT`、打开强制服务后同一张无依据的卡又被投递；`ServiceCaptureParityDeviceTest`、`RuntimeLifecycleDeviceTest`、`CollectionModeDeviceTest`、`PhotoDeletionDeviceTest` 共 19 项通过；`DisplaySequenceDeviceTest` 配像素监视器在真机上 20 轮通过（沉默期间眼镜面板 0 像素变化）。**真实模型实测（手机上的 qwen3.8-max，真实 API）：**`RealModelServiceRateDeviceTest` 用同一组 8 张真实拍摄帧跑三个臂，变量只有会话里挂什么记录——挂一条与画面无关的记录时，只加前面的改动仍是 8/8 全服务（模型为每帧复述记录）；补上 HR 冻结提示词里被现行 `prompts/` 删掉的时机规则（"Digital evidence arriving by itself is not an action-ready moment"，例外是再等就失去价值的到期时间）后降到 1/16；无记录 0/13；有具体未决项的记录 15/15（两次重复、48 次真实调用合计）。另发现手机到 dashscope 有约 8% 的调用死在 IPv6 连接超时上（传输层，与闸门无关，本次未改）。证据 `reports/latest/service_rate_probe.json`。**未做整周率标定。** 详见[主动服务闸门](docs/implementation/PROACTIVE_SERVICE_GATE.md)。
