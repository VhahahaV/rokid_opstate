# 5080 开发迁移交接

迁移日期：2026-09-04。目标来自用户指定 `~/Scripts/5080.sh`，使用 `ssh -p 59926 caizhuoqiang@202.120.38.209`。**仓库：`/home/caizhuoqiang/Code/rokid_opstate`**。目标初始不存在；本次没有覆盖其它实验工程，也没有修改 5090。

## 直接继续开发

```bash
ssh -p 59926 caizhuoqiang@202.120.38.209
cd /home/caizhuoqiang/Code/rokid_opstate
source .local/environment.sh
make apks
make quick-check
```

`make quick-check` 是本地/JVM 短回归，不连接 USB、不拍照、不调用付费模型。`make serve` 只在需要 mock 时手动使用；正常手机业务直接调用配置的模型 API。

## 已配置目录

| 路径 | 内容 |
|---|---|
| `/home/caizhuoqiang/Code/rokid_opstate` | 源码、Gradle Wrapper、脚本、文档、原始 R6 ZIP、公共 replay assets、图标、选定证据 |
| `~/.local/share/opstate-dev/jdk-21` | Temurin 21.0.12.1+1；项目独立 JDK |
| `~/.local/share/opstate-dev/android-sdk` | Linux SDK：Platform 35、Build Tools 34.0.0、command-line tools 19、platform-tools |
| `~/.local/share/opstate-dev/gradle` | Gradle 8.11.1 及 Kotlin/Android/Rokid 依赖缓存 |
| `仓库/.venv` | Linux Python 3.12 虚拟环境，按 requirements 安装 |
| `仓库/.local/environment.sh`、`local.properties` | 机器专用路径；不提交源码 |
| `~/.config/opstate-dev/debug.keystore` | 原开发设备使用的调试签名，权限 600 |
| `~/.config/opstate-dev/rokid/` | 用户提供的 Client Secret 与 `.lc`，目录 700、文件 600 |
| `~/.local/share/opstate-dev/reference/5090-syj/` | 指定 VLN 参考项目只读下载副本，附原 SHA 清单 |
| `仓库/dist/device-tested-2026-09-04/` | 当前真机已测 APK 与 SHA-256，保留作为迁移前基线 |

系统默认 Java 8、已有 Python 工程与旧 `rokid_opstate_deploy` 均未调整。没有部署后台模型服务、systemd 或公网 ADB。

手机里已有的模型 API Key/官方授权仍由 Android Keystore 加密管理。迁移调试签名用于覆盖升级；服务器上的 `.lc`/Secret 是后续设备重新配置的私有备份，不会自动写入源码或 APK。Android Keystore 密钥不可通过复制普通偏好文件迁出。

## 编译结果与手机安装

```text
code/phone-app/build/outputs/apk/debug/phone-app-debug.apk
code/glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

手机和眼镜的 USB 当前直接接在 5080。覆盖升级使用保留的调试签名：

```bash
source .local/environment.sh
adb -s ANYXVB4906000933 install -r code/phone-app/build/outputs/apk/debug/phone-app-debug.apk
adb -s 1901092551802804 install -r code/glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

不要清除应用数据，手机 API Key、配对与已有照片通过覆盖升级保留。

眼镜系统曾将 OPSTATE 的 `RUN_IN_BACKGROUND` 和 `RUN_ANY_IN_BACKGROUND` 设为 `ignore`，锁屏一分钟后停止前台拍照服务。当前设备已改为 `allow`，并加入 OPSTATE 专属的电池优化白名单。这是设备的一次性后台运行配置，不依赖主机常驻；更换眼镜或恢复出厂设置后需重新核对。CPU 唤醒锁不能替代系统的后台运行授权。

手机 OPSTATE 同样已加入电池优化白名单，并允许后台运行。两端采集均由前台服务持有，退出页面不结束采集；手机通知提供「结束并同步」。眼镜「USB 充电时保持唤醒」开发设置已关闭，当前系统无操作熄屏时间为 5 秒。页面收起时立即置零窗口亮度、释放常亮标志，实机已观察到 `Display State=OFF`；双击对应返回键退出页面，采集继续。系统强制停止应用、重启或厂商杀进程不属于普通返回桌面，尚无自动恢复会话保证。

采集默认间隔 1 分钟，可选 5 秒、30 秒、1 分钟、2 分钟。照片首先保存在眼镜私有磁盘，手机默认每 60 张同步，支持 1–500 张、手动同步和结束时同步余量。两端保留拍摄时间，手机相册显示原图尺寸、大小、传输及可获得的位置/电量信息。GPS 来源是手机接收时的近期定位（最多两分钟），批量同步时不能作为眼镜拍摄位置。导出 ZIP 含原图及 JSON 元数据；旧照片没有的元数据不会补造。

相机按间隔管理：5 秒/30 秒采集期间复用已打开的预览；1 分钟/2 分钟每张拍完关闭相机，下一拍重新打开并等待实际曝光/白平衡稳定；空闲、暂停和结束也释放相机。阈值是当前保守工程策略，尚非实测功耗的盈亏平衡点。启动耗时与整机耗电分别验证，不能将相机关闭时长直接换算成续航提升比例。

## 整理边界

保持已有 6 个 Gradle 模块和包名；共享 Android 回归在 `code/testing/runtime/`。新增可复用 Linux 环境安装、短回归/APK Make 入口、迁移过滤清单和可选真机验证脚本。修正旧文档中“尚无宿主/传输实现”的过时表述。

已删除约 1.5 GB 可再生产物：项目 Gradle/Kotlin/build 缓存、重复数据库与导出、数据解包副本、SDK 示例的生成目录。保留原始用户输入、研究代码、公共数据、代表性完整导出和像素证据。清理清单在 `reports/cleanup-2026-09-04.json`。

`archive/original-v4/` 保存原始 ZIP/清单/早期报告；原 SHA 不代表当前代码。`reports/evidence/2026-09-04/` 保存本次前已完成的选定证据及其 SHA。`reports/latest/` 是每台机器的新输出，不互相覆盖。

若要再次同步，过滤规则为 `configs/migration-rsync-excludes.txt`。不要把 Mac 的 `.venv`、Android 可执行文件和 `local.properties` 复制到 Linux；环境用 `tools/setup_linux.sh` 创建，依赖缓存可复用。后续以服务器为开发主副本，不再把旧 Mac 工作区盲目覆盖回服务器。

## 验证边界

迁移只做 Python/JVM 短回归、所有 Android 源码编译、APK 签名与文件完整性核对。长测已明确取消；最后记录为 755,246 ms / 28 张图像，不能当作 30 分钟通过。明亮实景的真实模型联合评估、长期脱离电脑、Track A 正式对照和参与者 pilot 留待后续单独开展。

已完成：Python 33 + 核心 21 + 协议 13 + 手机 JVM 26，共 93 项短回归；两个宿主及四个 instrumentation APK 构建成功，首次 Linux 全量编译 83 秒。没有执行 5080 真机测试。最终机器验证结果见 `reports/migration-2026-09-04/`；完整业务证据范围见 [V5_ACCEPTANCE_STATUS.md](V5_ACCEPTANCE_STATUS.md)。
