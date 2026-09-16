# 共用 Android 运行时回归

这里的测试与合成场景同时由 `phone-app` 和 `opstate-phone` 的 androidTest 编译。

常规 actor、Room、历史隔离和 shadow 回归可运行在独立的 `ai.opstate.rokid.phone.librarytest` 包中，不启动研究宿主，不读取宿主凭证，也不控制真实眼镜。需要验证实际宿主/SDK/相机的测试仍位于 `phone-app/src/androidTest`，按各自显式参数运行。

库测试与宿主测试使用不同 application ID，避免相互覆盖测试 APK。公共 R6 数据来源于原 `phone-app/src/debug/assets`，不复制或导入 Gold。
