# 项目开发约定

- 当前开发主机：5080，仓库 `/home/caizhuoqiang/Code/rokid_opstate`。先读 `STATUS.md`、`docs/implementation/MIGRATION_5080.md` 和 `docs/CODE_MAP.md`。
- 保持现有包名和模块边界，以可运行性与完整业务为主，避免无关大重构。
- 默认使用手机 API Key 直连。5080 是开发机，Python mock/旧冻结服务不是应用运行依赖。
- 用户已要求不跑耗时长的验证。日常使用 `make quick-check`、`make apks`；长测、真实模型批量调用和参与者实验按后续明确任务执行。
- 运行 `tools/gradle.sh` 会加载机器私有 `.local/environment.sh`。凭证、调试签名、SDK/JDK 不能提交源码；不要打印 Key。使用保留的调试签名覆盖升级设备应用。
- 5090（SSH 59925）的参考项目只准读/查找/下载，禁止修改。5080（SSH 59926）是已授权开发与部署目标。
- 保留历史记录的因果隔离、真实两帧 ACK 和 SERVICE 回执语义。组件 mock、真实设备、真实模型证据分别标注；取消的长测不能记为通过。
- 原始输入和历史验收在 `archive/`、根目录 R6 ZIP、`reports/evidence/`；生成报告写入 `reports/latest/`。不要把历史报告当作当前构建状态。
