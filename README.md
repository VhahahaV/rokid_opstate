# OPSTATE Rokid 主动服务研究应用

手机控制台负责历史回放、现场采样、模型 API Key 直连和审计；Rokid 眼镜负责拍照、传感器摘要与可见服务卡片。现有实现使用官方 CXR SDK 鉴权、Bluetooth 控制和 Wi-Fi P2P 图像传输。

在本地仓库开发，并通过本地 ADB 安装到手机与眼镜。应用业务由手机 API Key 直连模型。

```bash
cd /path/to/rokid_opstate
make apks          # 构建手机和眼镜 APK
make quick-check   # 短回归；不启动真机、长测或真实模型请求
```

| 入口 | 内容 |
|---|---|
| [使用流程](docs/implementation/LIVE_HISTORY_WORKFLOW.md) | 拖动历史 → 载入 → 开始现场 → 每分钟/手动拍照 → 填写合成事件 |
| [模型配置](docs/implementation/DIRECT_MODEL_API.md) | API Key、模型、端点及冻结参数 |
| [开发说明](docs/DEVELOPMENT.md) | 构建、依赖、产物、可选 mock |
| [代码导航](docs/CODE_MAP.md) | 模块职责与主要实现 |
| [当前状态](STATUS.md) | 已验证能力与后续研究范围 |
| [蓝牙稳定性](docs/implementation/BLUETOOTH_STABILITY.md) | 本地断链修复、连接身份恢复和设备专用模式 |
| [验收证据](docs/implementation/V5_ACCEPTANCE_STATUS.md) | 真机、mock、真实模型证据分界 |

`code/phone-app` 和 `code/glasses-app` 是可安装宿主；`code/android-overlay` 是平台集成库；`code/core` 和 `code/protocol` 是共享逻辑与通信协议。保持现有包名和依赖方向。

用户提供的 `icon.png`、R6 原始 ZIP 和 APK 内的公共 replay 数据均保留。原始 V4 交付归档在 `archive/original-v4/`；已筛选的真机证据在 `reports/evidence/2026-09-04/`；新报告写入 `reports/latest/`。数据中的 Gold/authoring 字段不进入模型公共输入。

本次按用户要求取消长测。现有短测覆盖官方连接、P2P/蓝牙拍照、眼镜重启恢复、显示回执及模型调用；长期脱离电脑运行、明亮实景完整联合评估和 Track A/pilot 尚未完成，不作为迁移的前置条件。
