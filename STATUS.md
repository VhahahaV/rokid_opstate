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
| 在线服务 | 眼镜复用 pHash/直方图与 `StreamingPacketizer`，仅在视觉、合成情境、周期 trigger 或手动触发时上传图片；手机接小型状态/控制消息，维护历史并直连模型。真实硬件短测得到 VISUAL、DIGITAL 两次上传，手机拍照请求为 0；此测试模型为本机模拟，不是真实模型效果证据 |
| 本轮验证 | 手机组件 4 项、眼镜组件 4 项（含 100 张磁盘队列及重建/错误 ACK）、手机历史与相册 UI 2 项、眼镜 trigger 实机链路 1 项通过；真实采集分批同步和 GPS 元数据已观察到。100 张验证使用生成的组件图像，不是 100 张真实拍摄长测 |
| 后台运行 | 已修正当前眼镜的后台 AppOps 限制，增加应用电池优化白名单和两端 CPU 唤醒锁。双屏关闭 69 秒时手机落盘 15 张（USB 连接）。主机日志确认 16:06:52–16:07:46 双机 USB 同时断开 54.21 秒，期间拍摄 12 张、无线落盘 12 张，附近最大拍摄间隔 5.133 秒；整次 482 张原图哈希通过，结束后眼镜缓存为空。双机离线满 60 秒和长期运行仍未验证 |
| 当前真机链路 | CXR 应用通道、研究握手、Camera2 实拍、手机落盘、本机历史导入和真实直连模型已联合通过；本次设备在进入应用配对状态后需要重启一次眼镜端 OPSTATE 进程以刷新 CXR 订阅 |
| 后续研究 | 明亮实景完整联合评估、长期无电脑运行、Track A 和 pilot 未执行，留给后续任务 |

9 月 16 日交互补验：眼镜收起时释放常亮、窗口亮度置零，系统实测 `Display OFF`；手机后台 149.25 秒，其中双屏关闭 64.71 秒期间新增保存 14 张真实照片，两端前台服务仍运行。注入返回键后眼镜回到 Rokid 桌面，随后继续保存 4 张照片；实体触控条双击尚未重测。测试使用 5 秒/张、每 2 张同步，USB 连接，已结束并清空待同步缓存。默认选项仍为 1 分钟，保留用户已保存的同步批量 2 张（新安装默认 60 张）。证据：[后台与熄屏实测](reports/latest/background_display_real_device.json)、[显示与返回组件回归](reports/latest/glasses_display_navigation_test.txt)。

相机占用优化已部署：采集间隔 5 秒/30 秒复用预览，1 分钟/2 分钟逐张释放相机；空闲、暂停、结束也释放。首次取图等待实际 AE/AWB 稳定，替代固定延迟。当前眼镜三轮冷启动实拍为 1.271–1.342 秒，三张均通过现有画质检查；修改前立即取首帧的三张均未通过。1 分钟实测两次连接/关闭相机并同步 2 张，5 秒实测复用相机、暂停释放、最终保存 5 张；两个会话均已结束且缓存为空。短回归、APK 构建和新增真机用例通过。此证据不测量整机电量或续航增益；阈值未做功耗盈亏标定。证据：[启动前](reports/latest/camera_startup_before.json)、[启动后](reports/latest/camera_startup_after.json)、[实拍占用与释放](reports/latest/camera_duty_cycle_real_device.json)、[设备用例](reports/latest/camera_power_device_tests.txt)。

[迁移交接与运行命令](docs/implementation/MIGRATION_5080.md) · [代码导航](docs/CODE_MAP.md) · [批量采集及熄屏证据](reports/latest/buffered_capture_real_device.json) · [眼镜 trigger 真机证据](reports/latest/edge_trigger_real_device.json) · [历史/相册 UI 验证](reports/latest/history_gallery_ui_device_tests.txt) · [业务证据边界](docs/implementation/V5_ACCEPTANCE_STATUS.md)
