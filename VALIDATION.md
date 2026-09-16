# 基础可运行性验证记录（V5 完整业务开发前）

本页保留前一阶段结果。当前正在按 V5 补齐完整业务，新增代码与真机实验以
[当前验收](docs/implementation/V5_ACCEPTANCE_STATUS.md) 和 `reports/evidence/2026-09-04/` 为准；本页的通过结果不代表 V5 已完成。

验证日期：2026-09-03。本轮保留核心算法，只修复构建、接口和运行异常；Android 源码按原包名迁入标准目录。

| 验证项 | 结果 |
|---|---|
| Kotlin 核心回归 | 11 / 11 通过，核心源码未修改 |
| Kotlin 两段模拟流程 | 通过，覆盖服务、静默、重复回执抑制和材料变更 |
| Python 回归 | 11 / 11 通过 |
| JSON / Schema / 导出轨迹 / Python 语法检查 | 通过 |
| 手机与眼镜 Android 库 | debug / release AAR 均构建成功 |
| 手机 HTTP 与协议单元测试 | 3 个用例在 debug / release 均通过 |
| Android lint | 0 错误；保留 7 条可升级依赖的提示，未为消除提示而更换基线版本 |
| 手机真机测试 | BVL-AN00，Android 15 / API 35：2 / 2 通过 |
| 眼镜真机测试 | RG-glasses，Android 12 / API 32：2 / 2 通过 |

## 真机验证内容

手机运行的是当前库代码：实际生成/解码 JPEG，验证质量判定和重复帧 novelty；通过 ADB reverse 调用本地 mock，验证 multipart 上传、SERVICE、幂等重试和 NO_SERVICE 响应。

眼镜运行实际 `AndroidImuSampler`，验证有效 IMU 窗口、时间范围与有限数值；通过 Android CameraManager 检查相机接口可用。没有进行拍照或录音测试。

真机脚本使用显式序列号，只安装独立 instrumentation 测试 APK；完成后清理本轮新装测试包和新增端口映射。

## 复现

```bash
make setup
make check-all
```

启动 `make serve` 后，在另一个终端执行：

```bash
make device-test PHONE_SERIAL=ANYXVB4906000933 GLASSES_SERIAL=1901092551802804
```

环境和当前命令详见 [开发说明](docs/DEVELOPMENT.md)。早期重复构建日志已在迁移清理时移除；保留记录如下：

- [原始交付静态报告](archive/original-v4/STATIC_VALIDATION.json)
- [当前迁移验证](reports/migration-2026-09-04/result.json)
- [当前业务验收](docs/implementation/V5_ACCEPTANCE_STATUS.md)

## 当时的验证边界（已被后续实现推进）

本阶段结束时的产物是两个可编译、可在真机测试的 Android 库，不是完整业务应用。Rokid 蓝牙/P2P 传输、真实相机抓拍、两帧显示 ACK、完整会话持久化以及真实模型端点仍需宿主应用与实际 SDK 适配，尚未完成端到端验证。麦克风路径本轮修复了权限撤回和录音设备异常处理，但没有实际开启录音。

原始 ZIP、BUNDLE_MANIFEST.json、SHA256SUMS.txt 与早期静态报告保留为历史记录，不代表当前源码状态。

原始 V4 报告和清单已在 2026-09-04 整理至 `archive/original-v4/`，当前迁移验证见 `reports/migration-2026-09-04/`。
