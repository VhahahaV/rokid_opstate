# 蓝牙稳定性与设备配置 · 2026-09-23

当前开发和部署均在本地仓库及本地 ADB 完成。手机使用官方 CXR SDK 的蓝牙控制通道，眼镜通过系统 CXR 服务桥接。

## 已定位的断链来源

1. 手机原先每 3 秒发 PING，单次 5 秒收不到 PONG 就调用 `link.disconnect()`。传图时控制消息短暂延迟也会触发一次主动拆链。现改为连续 3 次心跳失败才重置连接；眼镜端失联宽限从 10 秒改为 25 秒，避免一次迟到就停止服务拍摄。
2. 眼镜在未连接时轻触页面原先会直接调用 `startBTPairing`。这台设备的 CXR 日志显示它会执行 `glassRemoveBond`、清除连接信息并创建新 RFCOMM UUID。手机继续使用旧 UUID，便会反复连接失败。现在首次轻触只展示已配对手机；真正重配需要在菜单中选择并再次确认。
3. 旧本地修复脚本只读取眼镜开机加载的 UUID。同一次开机如果发生重配，该值已经过期。脚本现在取最后一条加载或 RFCOMM 实际监听记录，只更新手机私有配置中的 `socket_uuid`，不清除业务数据。
4. 手机另一个 Rokid 伴侣 App 曾在 OPSTATE 用例期间尝试连接同一副眼镜的 CXR socket。它是潜在连接争用源，现暂时禁用。日志观察到并发尝试，但没有受控证据能把每一次历史断链都归因于它。

## 当前设备设置

- 手机保留 OPSTATE、暂时禁用 `com.rokid.sprite.aiapp`；恢复命令：`tools/configure_phone_opstate_exclusive.sh restore PHONE_SERIAL`。
- 眼镜除 OPSTATE 外的第三方应用已暂时禁用，名单保存在本机 `.local/`；恢复命令：`tools/configure_glasses_opstate_mode.sh restore GLASSES_SERIAL`。Android、Rokid 桌面和 CXR 等系统服务仍会运行。
- 眼镜 OPSTATE 已使用项目图标，Rokid 原桌面仍是系统 Home。将 OPSTATE 设成 Home 后，重启时 CXR 应用握手失败；恢复原桌面后能收到 PONG。[Rokid 官方 Glass3 企业版文档](https://x-docs.rokid.com/docs/faq/%E5%BA%94%E7%94%A8%E8%87%AA%E5%90%AF%E4%B8%8E%E6%8C%89%E9%94%AE%E6%8B%A6%E6%88%AA%E8%87%AA%E5%AE%9A%E4%B9%89.html)的 `persist.vendor.boot.pkg` 在这台固件上设置后重启，仍只进入原桌面；已清除该属性。周期性 Job 只能作为后台服务恢复补救，不能保证开机立即打开页面。

## 验证边界

- `make quick-check` 和 `make apks` 通过；两端 APK 用保留签名覆盖安装。
- 本地真机重新识别连接身份后，手机持续收到应用 PONG，界面显示眼镜已连接。
- `AutomaticCaptureDeviceTest#serviceSessionUploadsItsCapturesInBatchesOfTheConfiguredCount` 在真实手机与眼镜上通过，耗时约 20 秒；用真实相机和传图，模型为本地脚本模型。
- 此轮没有做长时间在线服务断链率测试，也没有真实模型批量调用。历史的 10 分钟熄屏无断链记录属于上一轮构建，不作为这次修改后的长测结论。

若眼镜再次进入重配模式后出现旧连接身份错误，先运行 `make cxr-repair PHONE_SERIAL=… GLASSES_SERIAL=…`；脚本会核对最新监听 UUID 并覆盖修复手机配置。若系统蓝牙配对本身丢失，再从设备配对界面重新配对。
