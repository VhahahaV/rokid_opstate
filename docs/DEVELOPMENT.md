# 开发与运行

在本地仓库开发，通过本地 ADB 连接手机和眼镜。纯手机业务使用 API Key 直连供应商，不需要启动 Python 服务。

## 环境

- JDK 17–23；`tools/gradle.sh` 会选用本机可用版本。
- Gradle Wrapper 8.11.1、Kotlin 2.1.21、AGP 8.7.2。
- Android SDK Platform 35、Build Tools 34.0.0、platform-tools。
- Python 3.9+；依赖来自 `requirements.txt`。
- Rokid `client-m:1.2.2` 与 `cxr-service-bridge:1.0` 由 Gradle 获取并缓存。

新的 Linux x86_64 工作站执行 `bash tools/setup_linux.sh`。脚本在用户目录安装环境，验证 JDK/SDK 下载校验和，通过 sdkmanager 接受 SDK 许可，创建机器本地 `.local/environment.sh` 和 `local.properties`。重复执行可复用已安装工具。依赖下载时间不属于测试时长。

macOS（arm64/x86_64）执行 `bash tools/setup_macos.sh`。它先复用机器上已有的 JDK 17–23（Homebrew openjdk 或 `/usr/libexec/java_home`）与 Android SDK（Platform 35、Build Tools 34.0.0），都找不到时才下载 Temurin 21 和官方命令行工具；因此已配置的机器重复执行不产生下载。两个脚本写入相同变量名和同名机器本地文件，所以 `make quick-check`、`make apks`、`make device-apks` 等命令在两端一致，仓库内没有平台分支。

本机首次配置后执行 `make apks` 和 `make quick-check`。

`tools/gradle.sh` 自动读取该机器的 `.local/environment.sh`；没有此文件时按 `OPSTATE_JAVA_HOME`、`JAVA_HOME` 和常见 JDK 路径选择 Java。macOS 上它会优先选中 Homebrew 的 `openjdk`。Android SDK 路径由机器本地 `local.properties` 指定，两端写法相同。

## 命令与产物

| 命令 | 范围 |
|---|---|
| `make apks` | 两个可安装 debug APK |
| `make quick-check` | Python、core、protocol、phone JVM 短回归 |
| `make android` | 两个平台 AAR 与手机 JVM 测试 |
| `make test` | 静态 Schema/轨迹校验及 host 测试 |
| `make demo` | 两段 Kotlin 模拟流程 |
| `make serve` | 可选 HTTP mock，监听 127.0.0.1:8765 |
| `make check-all` | 完整构建与 lint；本次迁移不重复执行 |

APK 输出为 `code/phone-app/build/outputs/apk/debug/phone-app-debug.apk` 与 `code/glasses-app/build/outputs/apk/debug/glasses-app-debug.apk`。R6 公共历史已经包含在 debug assets 内；release 默认不打包研究测试数据。

历史设备已测 APK 保留在 `dist/device-tested-2026-09-04/`。本机通过 `OPSTATE_DEBUG_KEYSTORE` 复用现有调试签名，以便 `adb install -r` 覆盖安装并保留设备上的会话与加密配置。签名文件放在源码之外；不要卸载应用后重装来绕过签名问题。

## 数据与可选 mock

原始 R6 ZIP 保持在仓库根目录。若需重新生成公共输入：

```bash
.venv/bin/python tools/import_validation_bundle.py january_v10_online_validation_r6_data_bundle_20260903.zip --app-assets
```

该命令校验原始清单并只向 app 投影公共字段；Gold、作者元数据和 teacher receipts 不进入 app。中间解包目录 `.local/datasets/` 可再生，不需要迁移。

Python 服务仅用于兼容契约和 mock 实验。`make serve` 启动后可访问 `http://127.0.0.1:8765/health`；用 Ctrl-C 停止。真实手机模型配置见 [DIRECT_MODEL_API.md](implementation/DIRECT_MODEL_API.md)。

## 真机检查

USB 设备连接本机后，用 `adb devices -l` 核对序列号，再分别执行：

```bash
adb -s PHONE_SERIAL install -r code/phone-app/build/outputs/apk/debug/phone-app-debug.apk
adb -s GLASSES_SERIAL install -r code/glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

库 instrumentation 使用 `ai.opstate.rokid.phone.librarytest` / `ai.opstate.rokid.glasses.librarytest`，与正式研究宿主分开。`tools/device_smoke.py` 按显式序列号安装测试包。宿主硬件测试和 `tools/device_checks/` 中的可选脚本需要单独选择；默认短回归不会运行 30 分钟测试或产生模型用量。

本地设备的 OPSTATE 专用模式可用 `tools/configure_phone_opstate_exclusive.sh apply PHONE_SERIAL` 与 `tools/configure_glasses_opstate_mode.sh apply GLASSES_SERIAL` 配置；两脚本都支持 `restore`。眼镜脚本保留 Rokid 系统桌面，以免冷启动时 CXR 握手失败。连接身份更新用 `make cxr-repair PHONE_SERIAL=… GLASSES_SERIAL=…`。原因与验证边界见 [蓝牙稳定性与设备配置](implementation/BLUETOOTH_STABILITY.md)。

## 文件边界

模块导航见 [CODE_MAP.md](CODE_MAP.md)。`.local/`、`.venv/`、`build/`、`.gradle/`、`reports/latest/` 都属于可再生或机器私有目录。`reports/evidence/` 是选定证据；`archive/original-v4/` 是历史交付。原始哈希不代表当前代码。

当前源码清单：

```bash
.venv/bin/python tools/generate_manifest.py . --output reports/latest/source-manifest.json
```

源码清单排除环境、凭证、构建产物和历史归档。迁移另核对包含原始输入、证据、APK 的完整交付清单。
