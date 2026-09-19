# 代码导航

| 模块 | 职责 | 依赖 |
|---|---|---|
| `code/core` / `:opstate-core` | 因果历史、HR 在线 packet 前端、历史检索、采样、图像指标、投递/回执策略 | 纯 Kotlin |
| `code/protocol` / `:opstate-protocol` | StudyWire V5、时钟/epoch、请求幂等、图像传输/TLS/哈希 | Kotlin serialization、OkHttp |
| `code/android-overlay/phone` / `:opstate-phone` | SDK、现场 actor、模型、Room、数据投影 | core + protocol |
| `code/android-overlay/glasses` / `:opstate-glasses` | CXR bridge、Camera2、IMU、可见显示确认 | core + protocol |
| `code/phone-app` / `:phone-app` | Application/Activity、控制台 UI、配置、回放与复盘 | phone 集成库 |
| `code/glasses-app` / `:glasses-app` | Application/Activity、眼镜前台服务与 HUD | glasses 集成库 |
| `code/testing/runtime` | 两种测试宿主复用的状态机/历史/模型回归 | 仅测试 source set |
| `code/server-contract` | 可选 Python mock 和历史冻结服务兼容实现 | schemas；非手机业务前置 |

核心与协议始终注册；Android 模块通过 `-PwithAndroid=true` 注册。包名和既有目录保持稳定。

## 手机主要入口

- `PhoneApplication.kt`：组装 SDK、配置、私有凭证和运行时依赖。
- `runtime/StudyRuntimeEngine.kt`：单 actor 的采集/服务双模式会话。手机产品入口使用眼镜自主拍摄：采集不初始化模型或 packet 前端，服务只对眼镜触发的图片建立推理包；手机负责历史、模型、投递和回执。`StudyRuntimeService.kt` 管理前台服务、CPU 唤醒锁和带时间戳的手机定位。
- `transport/LegacyCxrTransport.kt`：真实研究握手、clock sync、图像回传；`rokid/CxrPhoneLink.kt` / `StudyWifiDirect.kt` 管理官方 SDK 鉴权、Bluetooth 与 P2P。
- `runtime/DirectModelClient.kt` / `DirectModelConfig.kt` / `ResponseParser.kt`：默认 `qwen3.8-max` API Key 直连、当前画面与最多 4 张历史画面、packet envelope、冻结参数、原文与标准化结果。`RemoteHarnessClient.kt` 保留兼容路径。
- `data/StudyJournal.kt` / `StudyDatabase.kt`：Room v2、私有文件、审计链、完整导出和中断收尾。`deletePhoto` 单张删除：链是 append-only 的，所以删除写成一条 `PHOTO_DELETED` 事件并随后删掉 JPEG，而不是删除任何 journal 行——`verify` 与 `export` 因此照常，`deletedPhotoObservations` 是各读取方要减掉的那一组。已投递主动服务正在读的那一帧不允许删除（它是演示帧的实景那一半）。
- `review/Showcase.kt`：展示的唯一入口——跨记录扫描「真的在眼镜上显示过」的主动服务（按 decision 走 `decisions` + `deliveries`，再经请求的 `current_asset_id` 找到当时那张图；**不解析 observations**），选择持久化在 SharedPreferences，组装由 `ShowcaseExporter.write`（每个时刻合成图/原图/卡片文字三件 + `index.jsonl`，写在应用私有目录）与 `publish`（图片进相册 `Pictures/OPSTATE/<组名>`，文字进 `Download/OPSTATE/<组名>`——相册目录只收图片——同组重导先清空再写）两步完成。渲染复用 `review/DemoFrame.kt` 的 `DemoFrameRenderer.render`。
- `replay/LocalCollectionHistory.kt`：枚举已完成记录，校验图片哈希并读取合成数据；`PublicReplayDataset.kt` / `HistoryContinuation.kt` 管理 R6 公共历史、前缀隔离与现场续接。历史加载本身只读，不创建会话或触发服务；单次模型视觉历史最多 4 张。
- `scenario/ScenarioPlayback.kt` / `PublicScenario.kt`：虚拟时间线、暂停/拖动和 authoring 字段隔离。
- `ui/SessionSetupSheet.kt`：先可选加载历史，再选择采集/在线服务；采集间隔和 1–500 张同步批量由手机设置。`ObservationControls.kt` / `PhotoGallery.kt`：实时照片、按日期相册、大图和元数据，照片详情里可直接删除单张；`SessionReviewScreen.kt`：采集相册、服务试次与导出；`ShowcasePanel.kt`：展示标签页；`RuntimeDiagnostics.kt`：次级运行详情。

## 眼镜主要入口

- 宿主 `MainActivity.kt`：页面明确显示 OPSTATE；单击收起/展开，收起时释放屏幕常亮并将窗口亮度置零，允许系统熄屏。返回键（侧边双击）退出页面，采集前台服务继续；配对列表内返回键先关闭列表。隐藏页面不能产生可见 ACK。
- 宿主 `GlassesStudyService.kt`：真实设备消息、状态/情境/服务卡、自主定时拍照、本地 trigger、磁盘队列同步和 ACK。未连接时，侧边单击进入手机搜索/配对列表；心跳核对时钟及活动会话。
- `capture/PhotoBuffer.kt`：JPEG 与元数据原子写盘；达到批量阈值、手动同步或结束采集时按序发送；仅 `IMAGE_COMMITTED`（手机日志和原图已保存）清理对应缓存。传输 ACK 不删除原图。断连继续采集；应用进程重启保留缓存，下一次采集恢复同步。
- `code/android-overlay/shared/.../vision/VisualSignatureExtractor.kt`：手机/眼镜共用的 Android 图像解码与 pHash/直方图计算，复用 core `StreamingPacketizer`。眼镜在线服务传输 VISUAL、DIGITAL、HEARTBEAT 或用户手动触发的照片；控制和状态消息继续传输。
- 集成库 `capture/AndroidCameraSession.kt`：Camera2 YUV → JPEG；新开相机等待 AE/AWB 稳定（最多 3 秒），随后获取新帧；`CaptureCoordinator.kt`：串行捕获。宿主在采集间隔达到 1 分钟时逐张关闭相机，5 秒/30 秒复用预览；空闲、暂停和结束时释放相机。在线服务不采用逐张关闭策略。
- `display/VisibleDrawAcknowledger.kt` / `VisibleAckRenderer.kt`：窗口/屏幕状态检查与两帧可见确认；启动状态 ACK 与 SERVICE Receipt 分离。
- `transport/CxrGlassesLink.kt`：眼镜系统 bridge；`sensor/AndroidImuSampler.kt`：摘要；VAD 是保留的可选实现，未启用。

## 交付边界

`schemas/device_message_v5.schema.json` 描述当前实际 wire；V4 schema 保留兼容。`tests/` 存放 Python 和跨语言契约样例。`tools/` 是开发与校验工具；`configs/` 是无密钥配置。具体部署位置、参考源码、签名与凭证边界见 [迁移文档](implementation/MIGRATION_5080.md)。
