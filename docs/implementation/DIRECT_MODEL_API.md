# 手机直接使用模型 API Key

根据用户最新要求，现场模型调用由手机直接访问供应商 API，不需要自建模型服务器。旧 Harness 仅保留给历史回放工具和已有测试；5090 参考服务器继续严格只读。

## 使用

1. 手机打开“设置 → 模型配置”，选择 Qwen Flash 或 GPT Sol，也可以填写兼容接口的模型 ID。
2. 填写 API 地址。支持 Base URL 或完整 `/chat/completions`、`/responses` 地址。
3. 输入模型平台 API Key，点击“保存模型与 Key”。留空仅能保留同一 API 地址下已经保存的 Key；切换接口需要新 Key。
4. 点击“验证已保存的 API Key”。验证只发送生成的棋盘格与合成文字，会产生一次模型调用，不使用参与者图像或历史。
5. 在眼镜上打开 OPSTATE，应用会自动准备研究模式；手机连接后选择历史续接或情境脚本，见 [现场操作](LIVE_HISTORY_WORKFLOW.md)。

模型 API Key 与 Rokid 设备授权是两类凭证。Rokid Client Secret / `.lc` 位于设备授权卡，不应填入模型 Key 栏。

预设来自用户提供的实验配置：Qwen 使用 `qwen3.7-flash-2026-07-15` 和 Chat Completions；GPT 使用 `gpt-5.6-sol` 和 Responses。高级参数可编辑最大输出、temperature、top_p、seed、Thinking 或 reasoning_effort。Responses 仅发送该接口适用的参数，不把 Chat 参数附加过去。

## 实现

- `DirectModelClient` 与原 Harness 客户端共同实现 `InferenceClient`，现场 actor、调度、显示和 Receipt 逻辑保持共用。
- Key 使用 Android Keystore 加密，并绑定 API 基础地址；不会随配置快照、模型输入或会话导出保存。运行期间禁止更改配置。
- 手机冻结实际模型、API 地址、发送参数、prompt、schema、预处理及解析代码哈希。直连路径不从服务器获取契约。
- 供应商响应先落盘，再解析；通过校验后记录标准化 V2 响应。401、超时、拒答、格式错误、不完整输出或非法证据均为技术失败，不作为 NO_SERVICE。
- 当前客户端会复用同一会话内已完成的同一请求；不承诺跨进程的供应商 exactly-once。进程恢复会结束旧会话，不自动重放旧请求。
- 试次详情分别展示“标准化响应”和“模型 API 原文”。导出校验器会比较两者，防止标准化结果与供应商输出不一致。

接口格式对照了 [OpenAI 图片输入](https://developers.openai.com/api/docs/guides/images-vision)与[结构化输出](https://developers.openai.com/api/docs/guides/structured-outputs)文档，并沿用已核对的用户实验参数。该工程输入仍未完成 Track A 的 scope、预算和分组语义对齐，不作为正式对标结果。

眼镜输出限定中文短标题与正文，JSON Schema 同时声明 40/140 个 Unicode 字符上限，客户端继续验证实际输出。根据 [百炼结构化输出](https://docs.modelstudio.console.alibabacloud.com/zh/model-studio/qwen-structured-output)，当前 Qwen 3.7 系列支持 JSON Schema；实际手机调用已验证该配置可用。超长输出保留供应商原文并显示明确错误，不自行裁剪或伪造 NO_SERVICE。

## 当前验证

- JVM：7 项直连客户端回归通过，覆盖两类 API 格式、原始响应顺序、完成请求复用、因果/契约检查、错误与 Key 脱敏、取消及地址绑定。
- Python：29 项通过，包含实际生成图像直连导出的复验，以及重新计算文件和审计哈希后篡改标准化结果的检测。
- 手机：`v5-direct-api-runtime-device.txt` 已完成真实 Qwen API 调用，使用生成图像与测试传输，经过实际 actor、解析器、Room 和 ZIP 导出。`v5-direct-api-export-validation.json` 完整校验通过，1 请求、1 供应商原文、1 标准化响应、1 决策、0 回执；不把该测试的模拟传输当作真实无线证据。
- 配置卡：`v5-direct-api-ui-device.txt` 真机验证通过，9.359 秒，包含输入并保存测试 Key、确认输入被清空、恢复真实 Key、从界面执行真实模型验证；截图 `v5-direct-api-config-ui.png`。真实 Key 未填回明文输入框。
- 真实眼镜闭环另见 `v5-real-service-loop.json` 和 `v5-real-silence-loop.json`：这些使用真实相机、蓝牙、显示与 ACK，但模型决策由手机本地模拟。两类证据分别记录，不能混称为同一轮真实模型与真实设备的联合验收。
