# 运行时中断与恢复验证

本页验证手机运行时的状态与数据一致性。SDK 测试端口不能作为真实蓝牙/P2P、眼镜拍照或可见回执的证据。

## 恢复行为

同一进程内断线：取消在途捕获、推理和待显示服务，记录终止原因；连接重新就绪后推进 epoch、完成新的 SESSION_START，再继续原会话。原会话时间、历史、冻结契约和已提交回执保留，旧 epoch 的服务不会重放。用户在断线期间选择暂停后，必须由用户继续会话。

进程终止或更新 APK：启动时验证未结束会话的审计链，补齐中断操作的终止记录，并结束旧会话。已保存的契约、图像和历史仍可查看、导出。不会自动重新发出旧请求、显示服务或提交回执；继续现场工作需要新建会话。

现场与数据回放的创建过程会在同一事务写入会话、冻结配置和初始 checkpoint。对旧版本已经留下的无 checkpoint 会话，恢复也能找到并收尾；若当时尚未写入契约，则不能补造契约来导出为完整实验。

## 常规回归

```sh
./tools/gradle.sh -PwithAndroid=true :opstate-phone:testDebugUnitTest :phone-app:assembleDebug :phone-app:assembleDebugAndroidTest
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 install -r code/phone-app/build/outputs/apk/debug/phone-app-debug.apk
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 install -r code/phone-app/build/outputs/apk/androidTest/debug/phone-app-debug-androidTest.apk
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 shell am instrument -w -r \
  -e class ai.opstate.rokid.phone.RuntimeLifecycleDeviceTest,ai.opstate.rokid.phone.JournalLifecycleDeviceTest \
  ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
```

运行时测试使用真实 Android actor、HTTP 客户端、解析器和隔离 Room；图像、IMU、传输及 ACK 明确由测试 fixture 提供。覆盖捕获暂停/停止/恢复、HTTP 取消、真实业务中的 NO_SERVICE 无显示命令，以及暂停、停止、显示拒绝、未 ACK 前关闭、材料变化、ACK 超时的终止记录。

## 实际进程重启验证

这项验证会停止 OPSTATE 应用，因此应在没有现场会话或回放运行时执行。它在应用数据库中添加一个带 TEST_ONLY 标记的临时会话，验收时检查原会话审计链不变，并删除临时会话。没有显式 `recovery_phase` 参数时该测试跳过，避免普通测试运行留下中断窗口。

先准备中断窗口，确认输出 `OK (1 test)`：

```sh
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 shell am instrument -w -r \
  -e class ai.opstate.rokid.phone.ProcessRecoveryDeviceTest -e recovery_phase prepare \
  ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
```

实际终止并重新启动应用，再验证：

```sh
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 shell am force-stop ai.opstate.rokid.phone
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 shell am start -W -n ai.opstate.rokid.phone/.MainActivity
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 shell am instrument -w -r \
  -e class ai.opstate.rokid.phone.ProcessRecoveryDeviceTest -e recovery_phase verify \
  ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 exec-out run-as ai.opstate.rokid.phone \
  cat files/validation/process-recovery-result.json
```

PASS 检查不同进程 PID、会话停止、恰好一次中断与捕获取消、零新增回执、冻结契约保留、原会话审计链不变。结果不证明跨进程继续原现场会话，也不证明真实无线重连。
