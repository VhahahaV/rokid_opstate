# CXR SDK 接入基线（V5）

选择 CXR-M / CXR-S，同一进程不引入 Security SDK。当前 API 签名同时对照官方文档与 Maven AAR 的 public class API；SDK 文档示例部分签名较旧，以锁定的二进制编译结果为准。

| 端侧 | Maven 坐标 | 本轮下载 SHA-256 |
|---|---|---|
| 手机 | `com.rokid.cxr:client-m:1.2.2` | `c75c5d3fde2641d1dc21bbc59d41c08c5f9652278ddb290f7089d44708ab7690` |
| 眼镜 | `com.rokid.cxr:cxr-service-bridge:1.0` | `fbf6da50ac99542f9474487a193c17612ead399cfebae172bed7df1d254a0384` |

官方仓库：[Rokid Maven](https://maven.rokid.com/repository/maven-public/)。minSdk 为 28；现场手机 API 35、眼镜 API 32 满足 API 基线。

## 真实连接前置条件

- 用户自己的 Client Secret 与绑定这副眼镜的 SN `.lc` 授权文件。应用使用 Android Keystore 加密保存，不进入 Git、日志或模型请求。
- 首次 BLE 扫描使用官方 00009100 服务 UUID；通过官方 initBluetooth 获取 socket UUID/MAC，再调用带 SN 授权的 connectBluetooth。
- P2P 由同一 CXR-M 实例在蓝牙已连接后协商。当前文档对应的兼容 API 在 1.2.2 标为 deprecated，但仍为公开接口；正式冻结前以真实连接试验判断版本兼容性。
- 用户随后已提供 Client Secret / SN `.lc`，现已通过手机 Keystore 加密导入。OPSTATE 的 CXR-M 实际鉴权与研究握手通过，证据为 `v5-sdk-authorized-result.json`；不能把一次连接通过扩展为长期稳定 gate 通过。
- 当前六参数 connectBluetooth 使用 Client Secret 与 `.lc`，不需要将用户同时提供的 Client ID / Accesskey 填入模型配置。模型 API Key 与设备授权分别保存。
- P2P 图像服务使用每会话证书和指纹固定的 HTTPS；Android 眼镜本地 TLS 测试已通过，真实 SDK 协商后的 P2P 传图仍待单独验证。不能以 TLS 回环测试替代 Wi-Fi P2P gate。

首轮真实设备闭环已经通过 CXR 蓝牙降级通道完成 JPEG 回传，并获得真实两帧可见 ACK 与 1 条 Receipt。眼镜 App 必须处于“启动研究模式”且显示就绪；仅 SDK 连接成功不等于研究握手通过。P2P 仍需单独验收。

## 官方 API 来源

- [mobile-import](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/bc6df927163347008fd7b78812cf2082)
- [mobile-quickstart](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/c3315f385e2a42948cb9c5a81d2c33c7)
- [mobile-connect](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/02f35584888b47948193aa369d6748c2)
- [mobile-custom](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/f0f3a70c56ef4f65b69baf71663ca1b7)
- [mobile-files](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/7dc3301a5b2a440f98c07b0cbf2ec3fc)
- [bridge-import](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/12e8f5f375e54ae8aa499472cf084755)
- [bridge-state](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/b83004f000184a6e80bcfad324605aa5)
- [bridge-data](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/aa3a3736489c4b28b42f4b0e13123372)
- [bridge-receive](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/087795f9923b4f219584c587a7b5d86a)
- [bridge-send](https://ar-independent-manager.rokid.com/out/document/selectWebDocument/2745b4ceff3645de94339961bff07622)
