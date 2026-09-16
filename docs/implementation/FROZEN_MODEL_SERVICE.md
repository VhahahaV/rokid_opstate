# 冻结模型服务

`code/server-contract/frozen_harness.py` 提供真实模型调用；原 `mock_harness.py` 与 266 锚点数据回放保持独立。手机现场业务已通过同一个 `RemoteHarnessClient` 读取冻结契约并发送 V2 请求。

## 配置与边界

每个服务进程只加载一份不可变配置。`configs/models/rokid-qwen.json` 使用 Chat Completions；`rokid-gpt-sol.json` 使用 Responses。参数仅声明实际发送的值，变化会改变模型配置与 preprocess 哈希。请求中的旧哈希会收到 409，不会在同一冻结条件下静默切换模型。

模型供应商 Key 只从服务器环境或用户指定 `.env` 读取。手机使用另行生成的 Harness 访问令牌；二者与 Rokid Client Secret / SN `.lc` 无关。访问令牌文件默认权限 0600，不写入请求、模型 prompt 或审计日志。

接口：`GET /v1/health`、带鉴权的 `GET /v1/contract` 和 `POST /v1/evaluate`。后者接受 `request` 文本 part 与 `image` JPEG part，校验 schema、图像哈希、当前图像 observation、严格因果时间、唯一证据 ID 和已提交的两帧可见 ACK。

模型只生成 decision / service / evidence_refs；服务校验之后添加 request_id / decision_id / TTL。NO_SERVICE 必须具有 null service。格式错误、非法证据、不完整输出、拒答、网络超时及供应商错误均返回技术失败，不转换为 NO_SERVICE。输入超预算明确拒绝，不截断历史。

每个供应商响应在解析前写入 SQLite 审计，包括状态、原始内容、哈希、用量、耗时和重试序号。若响应意外包含 Key，会标注脱敏并分别记录原始与已存内容哈希。请求结果持久化；同一 request_id 与相同输入复用结果，不同输入/配置返回冲突。服务重启时，未完成请求标为 HARNESS_RESTARTED，避免自动重新执行可能已经计费的请求。一个数据库只允许一个进程/worker。

请求结构依据 [OpenAI 图片输入文档](https://developers.openai.com/api/docs/guides/images-vision)和[结构化输出文档](https://developers.openai.com/api/docs/guides/structured-outputs)，同时核对了用户服务器上的实际 Track A 请求构造。第三方接口的兼容性通过下述工程请求单独验证。

## 启动

在项目根目录、Python 3.9+ 虚拟环境中安装依赖：

```sh
python -m pip install -r code/server-contract/requirements.txt
python tools/serve_frozen_harness.py \
  --config configs/models/rokid-qwen.json \
  --model-env-file /home/caizhuoqiang/Code/pro_simualtor/.env \
  --state-dir .local/frozen-harness/qwen --port 8767
```

命令只打印令牌文件路径。默认监听 127.0.0.1；非回环监听必须提供 `--tls-cert` 和 `--tls-key`。进程使用独立数据库与日志，不修改原实验仓库或 `.env`。

当前 5080 部署目录为 `/home/caizhuoqiang/Code/rokid_opstate_deploy`，使用独立 `.venv`；Qwen 监听回环 8767，GPT 监听回环 8768，状态与服务日志位于 `state/qwen` 和 `state/gpt-sol`。源文件与本机 SHA-256 对照通过，见 `reports/latest/v5-frozen-provider-validation.json`。跨 Mac/Linux 同步应使用 `rsync --exclude='__pycache__' --exclude='._*'`，避免将 AppleDouble 元数据当成 schema。

目前手机工程验证路径为 ADB reverse → Mac SSH forwarding → 5080 模型服务。它证明远程模型 HTTP 调用可用，尚不满足脱离电脑的 HTTPS 运行要求；后者仍需要完成手机可达的部署入口与现场无线验收。

## 已完成的验证

| 项目 | 证据与范围 |
|---|---|
| Python 回归 | `v5-frozen-all-python-tests.txt`：27 项通过，包含 mock 兼容、因果/冻结检查、非法输出、超时、原始输出顺序、重试、持久化幂等和服务重启处理 |
| Qwen 真调用 | `v5-frozen-model-phone-device.txt`：实际手机客户端读取契约、生成棋盘格、调用远程模型、解析 V2，再重复同一请求；1 项通过。供应商实际执行 1 次，562 输入 / 37 输出 token，服务记录总耗时约 735 ms |
| GPT 真调用 | `v5-frozen-model-gpt-result.json`：服务器端通过 Responses 配置调用同类合成输入并重复请求；供应商实际执行 1 次，806 输入 / 32 输出 token，总耗时约 4,033 ms。此项不是手机端 GPT 测试 |
| 配置卡 | `v5-frozen-model-ui-device.txt`、`v5-frozen-model-config-ui.png`：实际鉴权读取生效模型与参数；测试后恢复原服务地址、模型绑定和令牌 |

原始手机请求、生成图像和原始响应分别保存在 `v5-frozen-model-phone-request.json`、`v5-frozen-model-fixture.jpg`、`v5-frozen-model-phone-raw.json`。两次单请求验证都返回 NO_SERVICE，且重复请求返回相同 decision_id。它们不构成模型效果比较、延迟分位数、Track A 对标或物理无线闭环证据。

当前 V5 工程 prompt 使用完整因果公共历史，不等价于 Track A 的预算选择、candidate scope 和分组渲染。所有新契约保留 `not_for_paper: true`；按用户要求，在已有部署与设备任务完成后再进行正式 Track A 对齐。
