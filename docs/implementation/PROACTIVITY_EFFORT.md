# 在线服务主动性 effort（三档）

2026-09-24 更新。三档 UI 与此前测试的历史见 [三档短验收](../../reports/latest/proactivity-effort/summary.md)。该历史报告中的程序 fallback 属于旧 APK，现行手机 APK 已移除；当前真机证据见 [模型生成必定档验收](../../reports/latest/model_generated_guaranteed_20260924.md)。

在线服务「当前会话」卡片的拖动条从左到右为 **克制服务、倾向服务、必定服务**。它是会话级设置，变更后保存在手机设置中，下一次会话沿用；采集模式不显示。自动 CVTT 命中的观察包、数字事件包、手动拍照、选择已存照片和选择情境，全部从同一个会话状态读取档位。「手动触发」的三种来源直接提交模型判断，跳过自动 CVTT；是否真正服务仍由模型和当前 effort 决定。手动面板不再提供「强制服务」按钮。

三档控制只在手机原生 HR 客户端就绪时可拖动；旧远端 Harness 兼容会话会显示不支持说明，避免表面变档而实际沿用旧模型。档位控制的是**进入模型评估后的服务判定**，CVTT 的视觉触发阈值和拍照频率仍由原设置管理。

| 档位 | 决策路径 | 投递语义 |
|---|---|---|
| 克制服务（默认） | 已验证的手机 HR STEP→可选检索→FINAL 与当前严格提示词 | 保留时机、视觉关系、重复检查 |
| 倾向服务 | 同一 HR 合同，带独立身份的提示词强调已记录目标下的轻量、有限帮助 | 保留事实、证据、重复和时机约束；模型仍可沉默 |
| 必定服务 | STEP 优先由模型直接写卡；沉默或原卡被正确性检查拦下时，以同一因果快照再请求一次模型生成，校验后才投递；无程序模板卡 | 跳过仅为控制稀疏度的时机和同文重复限制；仍保留新证据引用、过期、会话身份、显示占用、真实两帧 ACK 与内部证据编号/日期矛盾检查 |

「必定服务」要求由**模型**给出有当前证据的内容。若第一次沉默，第二次 `GUARANTEED_SERVICE` 阶段必须返回 `SERVICE`；生成内容还要通过证据、事实矛盾、内部编号和视觉关系检查。模型再次沉默、技术失败、证据不足、过期或显示不可用时，宁可没有卡，也不展示程序模板。第二次模型生成在审计中记 `HR_GUARANTEED_MODEL_SERVICE`，与首轮直接 SERVICE 区分；原始提供商响应照常保存。该档位可能多一次模型调用、增加时延，不能承诺每次真实上屏。

换档写 `PROACTIVITY_EFFORT_CHANGED`，记录前后档位、提示词哈希及重排数；正在调用旧提示词的请求取消。尚未过期的排队包保留其因果快照，但更换请求 ID 和新档位合同后再评估；过期包终止并说明原因。已实际显示的卡继续依真实 ACK/退场生命周期运行。每次模型请求的冻结合同中包含档位及提示词身份，导出时不依赖 UI 文字猜档位。

短验证：`make quick-check`、`make apks` 已通过；HR 单元用例检查同一自动/手动输入在克制、倾向、必定档下的结果及安全观察卡引用；HTTP 模拟用例检查档位提示词确实发给模型，三档提示词身份不同。手机上的 2 项 Compose UI、3 项 effort 组件、同图三档真模型短回放均通过；真实眼镜注入模型两帧 ACK 另行通过。仍未测量长期服务频率或独立 Gold 质量。历史 [24 帧 HR 回放](../../reports/latest/hr_mobile_migration_progress_20260923.md)只代表改动前的克制档，不能替代三档对照。

设备空闲后短验收顺序：先检查手机 `runtime_checkpoint` 没有 `RUNNING/EVALUATING/DELIVERING`，用仓库保留的调试签名 `adb install -r` 覆盖手机 APK 与 instrumentation APK；随后分别运行 `ProactivityEffortControlUiTest`（滑动条）、`ProactivityEffortDeviceTest`（手动/自动/换档重排）。真实眼镜 ACK 用 `HrRealGlassesAckDeviceTest` 的显式 `-e hr_live_ack true`，它用注入模型，只验证真实显示链路。真实模型小对照用 `HrExistingExperimentDeviceTest` 的 `-e hr_existing_experiment true -e hr_arm hr -e hr_clips 1 -e hr_clip_from 1 -e hr_effort RESTRAINED|INCLINED|GUARANTEED`；每档跑同一两帧片段，逐档拉取 `hr_existing_experiment_result.json`，不把它们写成独立机会率。需要同时确认每次请求的 `proactivity_effort`、模型 SERVICE、程序 fallback、实际可投递数和错误数；中途技术失败单列。

可复现入口是 `tools/run_proactivity_short_checks.sh`。它在安装前只读检查手机数据库中的活动 checkpoint，发现 `EVALUATING` 等状态会拒绝运行（已验证该保护退出码 2，且没有安装）；空闲时执行 `PHONE_SERIAL=ANYXVB4906000933 tools/run_proactivity_short_checks.sh`，本轮完整通过，输出分别保存在 `reports/latest/proactivity-effort/`。设置 `RUN_REAL_GLASSES=1` 才额外执行注入模型的真实眼镜 ACK 短测，设置 `RUN_LIVE_MODEL_GLASSES=1` 才额外执行一次真实模型＋眼镜必定档短测。脚本不清除现有用户记录；真实模型三个档位每档只用同一两帧、单次输出覆盖前先拉取独立文件。旧的眼镜采集计数 UI 回归在 CXR 断开或计数等待中失败，未通过；三档所需的完整当前会话卡片 UI 已由不依赖蓝牙的测试覆盖。
