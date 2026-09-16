# 手机端 R6 数据回放

手机应用现在支持：数据校验 → 20 个均匀采样锚点或完整 266 个锚点 → V2 HTTP 请求 → 原始响应落盘 → 协议校验 → 会话/试次详情 → ZIP 导出。它使用同一套 HTTP 客户端、响应解析器和 Room 日志，不产生眼镜显示回执。

## 准备

在仓库根目录运行：

```sh
.venv/bin/python tools/import_validation_bundle.py january_v10_online_validation_r6_data_bundle_20260903.zip --app-assets
./tools/gradle.sh -PwithAndroid=true :phone-app:assembleDebug :phone-app:assembleDebugAndroidTest
```

公共投影只进入 debug assets；标签和教师回执留在本机评测侧。不要把原始压缩包整体放进 APK。

在单独终端启动数据集 mock（若 8765 已有相同服务，无须重复启动）：

```sh
OPSTATE_MOCK_MODE=dataset OPSTATE_STRICT_CONTRACT=1 \
OPSTATE_MOCK_DATASET=.local/datasets/fc76d7c37d29/source \
.venv/bin/python -m uvicorn mock_harness:app --app-dir code/server-contract --host 127.0.0.1 --port 8765
```

开发机 HTTP 通道：

```sh
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 install -r code/phone-app/build/outputs/apk/debug/phone-app-debug.apk
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 reverse tcp:8765 tcp:8765
```

这条 ADB reverse 只用于手机访问电脑上的 mock，不是眼镜 Bluetooth/P2P 验收，也不满足脱离电脑运行要求。

## 手机操作

1. 设置页的“模型配置”保存 `http://127.0.0.1:8765`，可绑定 `mock-january-v10-r6`，访问令牌按服务鉴权需要填写。点击“读取并校验”核对实际契约。
2. 数据页选择“快速验证”或“完整回放”。回放通过前台服务运行，可停止；异常单独计数。
3. 会话页打开记录，用“全部 / 服务 / 沉默 / 待查”筛选试次。
4. 试次页可看当前 JPEG、决策与证据引用，以及最近四条观察和请求/响应原文预览。原文预览最多 16,000 字符；ZIP 保留完整请求。
5. 会话停止后“导出完整记录”，通过 Android 文件选择器保存 ZIP。可删除已停止会话；会删除私有图像和导出缓存。

进程被终止或升级 APK 后，启动时把未结束记录标为中断并收尾，保留已有数据，不自动重放请求或提交显示回执。目前这是安全中断恢复，不是继续同一个现场会话。

## 自动验证

```sh
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 install -r code/phone-app/build/outputs/apk/androidTest/debug/phone-app-debug-androidTest.apk
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 shell am instrument -w -r \
  -e class ai.opstate.rokid.phone.DatasetReplayDeviceTest \
  ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
```

先校验 Android 与 Python 的 20 个冻结请求哈希，再完整回放。测试产物位于应用私有 `files/validation/phone-dataset-replay.zip` 和同名 JSON。保留会话供界面查看；测试不会写虚假的显示回执。

在上述回放后可运行索引/迁移与中断恢复检查：

```sh
.local/android-sdk/platform-tools/adb -s ANYXVB4906000933 shell am instrument -w -r \
  -e class ai.opstate.rokid.phone.JournalReviewDeviceTest,ai.opstate.rokid.phone.JournalLifecycleDeviceTest \
  ai.opstate.rokid.phone.test/androidx.test.runner.AndroidJUnitRunner
```

主机校验导出：

```sh
.venv/bin/python tools/validate_runtime_trace.py reports/latest/phone-dataset-export
.venv/bin/python -m unittest discover -s tests -p 'test_*.py' -v
```

完整导出校验离线加载仓库 schema，检查 manifest 文件清单、SHA256SUMS、审计链与各 JSONL 对应关系、冻结配置、因果观察/回执、图像哈希、原始响应与解析结果。PASS 仅针对这些可复核条件，不代表模型准确率或真实无线显示成功。

Room v2 给锚点与图像引用增加索引。v1 数据库升级会填充索引，保留原始 canonical JSON 和审计链；导出按 16 条记录分批读取，避免把所有累计请求放进内存。
