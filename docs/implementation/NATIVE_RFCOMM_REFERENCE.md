# 原生 RFCOMM 参考代码核对

用户指定服务器 `~/Scripts/5090_syj.sh`：`ssh -p 59925 sunyujie@202.120.38.209`。本轮只执行查找、读取、哈希及 scp 下载，没有安装工具、运行项目或修改服务器文件。

当前业务已使用用户授权的官方 CXR SDK；下文原生 RFCOMM 为源码参考，不是当前实现。5080 副本位于 `~/.local/share/opstate-dev/reference/5090-syj/`。

## 本地副本

- `.local/reference/5090-syj/indoor-vln-app-real-deployment`：51 个文件，604,930 字节。
- `.local/reference/5090-syj/indoor-vln-app-demo`：56 个文件，628,880 字节。
- 合计 107 个文件、1,233,810 字节，包括共用协议、手机与眼镜 Java 源码、协议测试、Manifest、Gradle 配置和 README；没有下载 SDK、Gradle 缓存、模型资源或实验数据。
- `SOURCE_MANIFEST.json` 与 `reports/latest/v5-vln-reference-copy.json` 保存路径、大小和 SHA-256。全部与远端源码比对一致。一次 scp 中断后已补齐并完成校验。

## 已确认的控制链路

1. 系统先完成蓝牙配对。
2. 手机 `BluetoothServer` 使用 `listenUsingInsecureRfcommWithServiceRecord` 监听自定义 UUID。非空的已选眼镜 MAC 会用于过滤连接。
3. 眼镜 `BluetoothClient` 枚举 `getBondedDevices()`，优先历史手机，再调用 `createInsecureRfcommSocketToServiceRecord(...).connect()`。
4. `HELLO / HELLO_ACK` 和 `PING / PONG` 通过自定义协议交换。

这些蓝牙类不依赖 CXR 的 `connectBluetooth`，不从 OPSTATE 的 Client Secret / SN `.lc` 获取控制通道授权。这是自家 App 之间的 Android 蓝牙通信，不是调用受鉴权的 SDK 能力。

`VlnProtocol` 使用大端帧：magic、version、type、flags、requestId、payloadLength、payload；`readFully` 处理流分包，长度有上限。其 UUID 是 `766c6e31-726f-6b69-642d-64656d6f0001`。OPSTATE 后续应使用自身 UUID 与版本，避免连接到 VLN 应用。

参考代码的 insecure RFCOMM、已配对列表和 MAC 过滤分别解决兼容性、设备发现和选定设备约束；不能把 HELLO 当作独立的密码学身份认证。OPSTATE 应明确限制已配对且已选定的设备，并核对实际连接安全属性。

## P2P 仍有另一套依赖

- 两份项目的手机依赖均包含 `com.rokid.cxr:client-m:1.2.2`；眼镜依赖 `cxr-service-bridge:1.0`。
- `RokidPhotoTransport` 使用旧 `CxrController.connectBluetooth(context, socketUuid, glassAddress, "GlassNav")`，再进行 P2P 初始化。不能据原生控制通道断言整个项目都不调用 CXR SDK。
- real-deployment 的照片通道仍含固定 CXR socket UUID；当前 OPSTATE 眼镜已升级固件，不能直接沿用该值。
- demo 的 `SafeWifiDirectController` 使用原生 `WifiP2pManager` 接管手机协商，按 generation 丢弃旧回调，并限制重试。源码注释说明其目的之一是避免 vendor WifiController 在 deinit 后的延迟回调崩溃。
- `PhotoSocketServer` 提供持久 TCP JPEG 通道，对阻塞写入设定超时，并在关闭失效 socket 后更换发送执行器。
- `CXRServiceBridge` 在眼镜内对接系统服务，不是上述自定义 RFCOMM 通道的鉴权器。

## 当时提出的接入方向（已被后续官方授权路径替代）

用户已将当前模型连接方向改为手机直接使用 API Key，暂停自建服务器连接部署。手机应使用加密的模型 Key、固定参数与本地请求构造，继续保留原始响应和 Receipt 规则。

蓝牙控制优先接入自家手机服务端与眼镜客户端。保留 OPSTATE 的 StudyWire、session/epoch、幂等、因果历史、图像哈希、两帧 DISPLAY_VISIBLE_ACK 和 true silence；VLN 的导航消息、状态提示或 GUIDANCE_PRESENTED 不能替代这些语义。

先验证原生 RFCOMM 的实际控制与图片降级闭环，再单独核对 P2P 在当前固件上的路径。此页是源码证据与实现约束，不是 OPSTATE 已完成原生蓝牙连接的证明。
