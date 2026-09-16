# 可选的真机验证工具

这些脚本不会由 `make quick-check` 执行。日常接手开发不需要重跑长测。

- `watch_display_pixels.py --phone SERIAL --glasses SERIAL`：配合 Android `DisplaySequenceDeviceTest` 的 20 轮真实沉默像素检查。需要本机 ADB 和本目录的 Pillow 依赖，在另一终端先启动此脚本，再启动对应 instrumentation。
- `run_provider_ui_sequence.py --phone SERIAL`：在手机 HISTORY_READY 状态下，点击 UI 的 API 检查按钮 20 次；使用手机内已保存的 Key，产生真实模型用量。脚本坐标针对 BVL-AN00 竖屏，不是通用 UI 自动化框架。

均支持 `--adb /path/to/adb` 与 `--output DIRECTORY`。设备需要连接执行脚本的电脑；5080 未配置跨公网 ADB。长测源代码 `DeviceSoakTest` 保留为今后可选验证，本次迁移明确取消执行。
