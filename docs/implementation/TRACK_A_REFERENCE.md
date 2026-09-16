# Track A 参考与后续对齐边界

用户指定：先完成当前工程部署与真机验收，再开始 Track A 实验对标。当前只读检查参考代码和公开配置，未修改服务器、运行付费推理或启动 Track A 实验。

- 连接入口：本机 `~/Scripts/5080_sjtu.sh`，内容为 `ssh caizhuoqiang@10.180.165.151`；已验证可用。
- 仓库：`/home/caizhuoqiang/Code/pro_simualtor/opstate-history-harness`。
- 读取时上级 Git HEAD：`84fae4a4c72636dd39863200a0c87b9259b62f92`。再次核对发现 Track A 所在目录整体未被上级仓库跟踪；此 HEAD 不能代表 Track A 源码快照，应以逐文件哈希冻结。
- 密钥文件：`/home/caizhuoqiang/Code/pro_simualtor/.env`。目前只核对环境变量名；不在本仓库、手机或日志中复制密钥值。模型供应商凭证与手机访问 Harness 的令牌是两类凭证。

## 已核对的模型配置

| 配置 | wire API | 模型 | 固定参数 | 凭证变量名 |
|---|---|---|---|---|
| `configs/track_a_gpt_sol_high.yaml` | `responses` | `gpt-5.6-sol` | temperature 0、top_p 1、max_output_tokens 4096、seed null、structured_output true、reasoning_effort high | `SHAI_API_OPENAI_KEY` |
| `configs/track_a_qwen3_7_flash.yaml` | `chat_completions`（默认） | `qwen3.7-flash-2026-07-15` | temperature 0、top_p 1、max_output_tokens 512、seed 20260824、structured_output true、enable_thinking false | `DASHSCOPE_API_KEY` |

上述是用户实验仓库的实际配置，不是对模型可用性或供应商能力的独立认证。两个配置分别使用第三方 Responses 端点与 DashScope 兼容端点；后续按服务器配置读取，不把供应商密钥填入手机的 Harness 访问令牌栏。

`ModelConfig` 校验：Responses 使用 `reasoning_effort`，不允许 `enable_thinking`；Chat Completions 使用 `enable_thinking`，不允许 `reasoning_effort`。版本、weights_hash、模型参数、wire_api 等进入 Track A 缓存契约，不可只更改模型名复用缓存。

实际请求构造另有重要差异：`src/opstate_history/model.py` 的 Responses 分支只发送 `max_output_tokens` 和 `reasoning.effort`，不发送 YAML 中的 temperature / top_p / seed。手机模型配置卡应展示实际生效参数；本仓库新 `rokid-gpt-sol.json` 因此只声明发送的参数。Chat Completions 分支会发送 temperature、top_p、max_tokens，以及非空 seed / enable_thinking。

随后已独立部署 Rokid V5 工程模型服务，用生成的棋盘格与合成历史分别检查 Qwen 和 GPT 的接口，未运行 Track A 数据或对标实验。新服务使用独立的 V2 prompt / parser / 输入构造，不能称为 Track A 等价。见 `FROZEN_MODEL_SERVICE.md`。

## 输入结构与当前回放的差异

- `track_a_schema.py` 的快照包含 candidate card、candidate scope、candidate thread、recent ambient stream、action receipt ledger、current image、visible evidence IDs 及 snapshot SHA。
- 当前渲染默认 `INTENT_REMOVED_WITH_SCOPE`：移除 `candidate_intent`，必须提供冻结的、具体且中性的 candidate_scope。旧的通用 query scope 被显式拒绝。
- `track_a_context.py` 按因果可见流选择，默认 UTF-8 预算 32,768 字节，优先相关回执、最近任务事件、任务起始事件、环境事件、中间历史及其他回执。不是简单把全部历史拼接后截断。
- `track_a_render.py` 将候选卡、三组历史、当前图像和输出契约渲染为固定 system/user prompt，分别记录 input/base/recent/history/response-schema 哈希。
- `track_a_runner.py` 分开计数传输重试和格式修复，缓存复用先核对冻结契约；格式修复会追加明确的格式指令。
- 本仓库当前 `CONTRACT_REPLAY` 是完整因果公共历史的 V2 HTTP 协议回放，与 Track A 的预算、分组、prompt 和输出契约尚不等价。266 个 mock 通过不等于 Track A 对齐通过。

## 对齐阶段验收顺序

1. 冻结参考代码、模型 YAML、prompt、输入/输出 schema 与候选卡视图版本。
2. 在服务端实现并验证与 Track A 相同的快照选择、渲染、图像引用与哈希；Gold/私有调度状态仍只留在评测侧。
3. 让手机配置卡选择对应冻结服务/模型，读取实际参数快照；把实际生效配置写入每个会话的审计记录。
4. 逐锚点比较选入证据、时间截断、candidate scope、输入哈希和输出解析，再开始真实模型实验。
5. 分开报告协议正确性、模型评估结果和实际眼镜显示/回执结果。

参考文件 SHA-256：

```text
8d77b7a49a658db72587c3a527996f35b1d07a674187cffb021ed78c2e3606c1  configs/track_a_gpt_sol_high.yaml
8b767b82fa075b83a86e635361b218935e39899e47cddbeadb7a04628e96e56e  configs/track_a_qwen3_7_flash.yaml
bd4dee0f901ff3660cff3bec8b0d8eb29a313b90afc2d0470dc37b764ecec08f  src/opstate_history/track_a_context.py
47939d9c417e86768cdc651d46d05999606065dc3a6226652c3f8497ce1e4099  src/opstate_history/track_a_render.py
131ec1c65247c92a2d70db7fa46fc3cfd4d1432b08e4a293ccbe2978f71e60d7  src/opstate_history/track_a_schema.py
e9411d417854deb257f77a34f95a3daf50eb0d4bf477df24cf1d43e08b51db09  src/opstate_history/track_a_runner.py
```
