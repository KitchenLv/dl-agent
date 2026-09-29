# Coding Agent Demo 完善计划

| 项目 | 内容 |
|---|---|
| 产品代号 | `dl-coder` |
| 文档版本 | v1.0 |
| 日期 | 2026-09-29 |
| 上游文档 | [01-product-requirements.md](01-product-requirements.md) · [02-technical-design.md](02-technical-design.md) |
| 现状基线 | M1~M4 主体完成，M5 部分完成（对照 PRD 里程碑） |

---

## 1. 现状盘点

| 里程碑 | 状态 | 证据 / 缺口 |
|---|---|---|
| M1 能干活 | ✅ | REPL + 流式渲染 + Harness 内建工具 + 会话持久化（`JsonFileAgentStateStore`） |
| M2 记得住 | ✅ | 压缩 40 条触发/保留 15 条、工具结果卸载、工作区 AGENTS.md/MEMORY.md |
| M3 会规划 | ✅ | `/plan on\|off`、`/session new\|switch\|list`；**子代理未落地** |
| M4 够安全 | ⚠️ 半完成 | `/perm` 四档模式可用；**HITL 确认流未接、权限规则为空**（见 §2 P0） |
| M5 会扩展 | ⚠️ 部分 | `/mcp`、`/stats` 已有；缺 `/model` `/cd` `/memory` `/skills`、token 统计、MCP 实测 |

---

## 2. 差距清单

优先级：**P0** = 核心闭环缺口，不补则安全机制形同虚设；**P1** = M5 收尾与体验；**P2** = 工程质量与可选增强。

### P0 — 安全与核心闭环

#### P0-1 接通 HITL 人工确认流
- **现状**：`ConsoleRenderer` 收到 `REQUIRE_USER_CONFIRM` 只打印"当前 CLI 未接确认流，默认拒绝"。工具确认等于永远拒绝，M4 的 y/n 确认实际不存在。
- **方案**：按技术设计 §5.10 —— 事件流收到 `RequireUserConfirmEvent` 后暂停，终端渲染待确认的工具调用与参数，`y/n/a`（a = 始终允许）输入后把 `List<ConfirmResult>` 放入下一条 `UserMessage.metadata`（`METADATA_CONFIRM_RESULTS` 键）恢复执行；"始终允许"规则随 AgentState 持久化。
- **改动点**：`CoderCli`（确认交互 + metadata 透传）、`ConsoleRenderer`（确认 UI）。
- **验收**：配置 `git push` 类 ASK 规则后，触发时终端弹确认，`y` 放行 / `n` 拒绝且 agent 收到拒绝结果 / `a` 后同类调用不再询问。
- **预估**：1 天

#### P0-2 权限规则装配（PermissionPolicy）
- **现状**：`CoderAgentFactory` 仅 `PermissionContextState.builder().mode(PermissionMode.DEFAULT).build()`，无任何规则。
- **方案**：新建 `permission/PermissionPolicy`，按技术设计 §5.10 装配：`dangerous-shell`（`rm -rf|git push|mvn deploy` → ASK）、`no-secrets`（`.env|.ssh/|credentials` → DENY）、`AdditionalWorkingDirectory` 绑定项目根。
- **验收**：单元测试覆盖规则匹配（命中/不命中/优先级）；`rm -rf` 触发确认，读 `.env` 被拒。
- **预估**：0.5 天

#### P0-3 设置 `maxIters`
- **现状**：factory 未调 `.maxIters()`，框架默认 10，复杂 coding 任务会被提前截断。
- **方案**：`.maxIters(50)`（技术设计 §5.1），并渲染 `EXCEED_MAX_ITERS` 警告（已支持）。
- **预估**：10 分钟

#### P0-4 `web_fetch` 工具核查
- **现状**：`SystemPrompt` 告诉模型"用 `web_fetch` 抓网页"，但项目无 `tools/` 包、未注册自定义工具。若 Harness 未内建该工具，模型会调用不存在的工具。
- **方案**：先验证框架内建清单；无则按技术设计 §5.4 实现 `WebFetchTool`（注解式，8K 截断，`readOnly=true`）；有则删提示词条目。
- **验收**：让 agent "抓取某 URL 并总结"，实际调用成功。
- **预估**：0.5 天

### P1 — M5 收尾与交互

#### P1-1 斜杠命令补全：`/model` `/cd` `/memory` `/skills`
- **方案**：按技术设计 §5.3 —— `/model <id>` 热切换（重建 agent 实例）、`/cd <path>` 切项目根（重建 agent）、`/memory` 打印 MEMORY.md 尾部、`/skills` 列出 workspace `skills/`。命令分发抽成 `SlashCommand` 类（对齐技术设计 §4 结构）。
- **验收**：`/model` 切换后下一轮生效；`/cd` 后 `execute` 的 cwd 跟随变化。
- **预估**：1 天

#### P1-2 `/stats` 增加 token 用量
- **方案**：从模型事件流累计 input/output token（`MODEL_CALL_END` 或用量事件），区分本轮/累计，替换现在"字符数"口径。
- **预估**：0.5 天

#### P1-3 MCP 端到端实测
- **现状**：`McpConfig`（含 `${ENV}` 展开、stdio/sse/http 三种 transport）与 `toolsConfig` 注入已完成，但从未连过真实 server。
- **方案**：接一个真实 MCP server（如 filesystem/stdio 或 github/http），验证工具注册命名 `mcp__{server}__{tool}`、调用成功、失败时的报错可读性；问题记录回本计划。
- **预估**：0.5 天

#### P1-4 模型厂商扩展：ollama / dashscope
- **方案**：`ModelProvider` 增加 `OLLAMA`、`DASHSCOPE`；factory 的 `buildModel` 增加分支（ollama 走 OpenAI 兼容 baseUrl 或官方扩展）。ollama 本地零成本，对演示与测试价值最大。
- **验收**：`config.json` 切 `provider: ollama` 可正常对话。
- **预估**：0.5 天

#### P1-5 渲染与中断体验
- **方案**：`TOOL_CALL_START` 显示工具名与参数摘要（当前只打"调用工具…"）；文件编辑类工具展示 diff 摘要；支持 Ctrl+C/Esc 中断当前轮（取消订阅事件流，保留会话状态）。
- **预估**：1 天

### P2 — 工程质量与可选增强

#### P2-1 测试补齐
- **现状**：仅 `AgentTest`（调真实模型、无断言，且 `toolkit.registerTool(toolkit)` 属笔误）与 `AppConfigTest`。
- **方案**：按技术设计 §9 —— AppConfig/McpConfig 校验边界（已有部分）、`SlashCommand` 分发、`PermissionPolicy` 匹配、AgentState 持久化-恢复（同 sessionId 两轮）、Plan Mode 拒绝路径；模型依赖用 mock 或 ollama 本地模型；修正 `AgentTest`。
- **预估**：1~2 天

#### P2-2 workspace 首启模板初始化
- **方案**：首启检测工作区为空时，复制内置模板（AGENTS.md / skills/commit-convention / subagents/reviewer.md，对齐技术设计 §4 的 `workspace/` 包规划）。
- **预估**：0.5 天

#### P2-3 子代理落地
- **方案**：验证 Harness 的 `agent_spawn`/`agent_send` 声明式子代理（`workspace/subagents/*.md`）在本项目可用，补 reviewer 模板并演示"委派代码审查"。
- **预估**：0.5~1 天

#### P2-4 Docker 沙箱可选切换（可选）
- **方案**：`DL_SANDBOX=docker` 时 factory 切 `DockerFilesystemSpec`（技术设计 §5.9），镜像按硬约束清单核验。
- **预估**：1 天（视 Docker 环境）

#### P2-5 安全整改：config.json 中的真实 API Key
- **现状**：项目根 `config.json` 内含真实 API Key（该文件实际不被读取，`AppConfig` 读 `~/.dl-coder/config.json`）。项目**尚未初始化 git**，一旦入库极易泄露。
- **方案**：作废该 key；项目根示例文件改为占位符（`sk-xxx`）并改名 `config.example.json`；**建议先 `git init` 并提交一版干净基线**，`.gitignore` 排除 `config.json`。
- **预估**：10 分钟

#### P2-6 Windows 兼容标注
- **现状**：`list_files` 跨盘符报错（`'other' has different root`）。
- **方案**：排查是上游问题还是用法问题；短期在 README 标注 Windows 已知限制与 Git Bash 建议。
- **预估**：0.5 天

---

## 3. 实施批次

| 批次 | 内容 | 预估 | 出口标准 |
|---|---|---|---|
| **第一批（安全闭环）** | P2-5 安全整改 + git 基线 → P0-3 maxIters → P0-2 权限规则 → P0-1 确认流 → P0-4 web_fetch | ~2.5 天 | `git push`/`rm -rf` 弹 y/n 且 `a` 可持久放行；读 `.env` 被拒；50 轮迭代不截断 |
| **第二批（M5 收尾）** | P1-1 命令补全 → P1-2 token 统计 → P1-3 MCP 实测 → P1-4 ollama/dashscope | ~2.5 天 | PRD M5 验收脚本全过：MCP 真实调用成功、`/model` 即时生效、`/stats` 出 token 数 |
| **第三批（质量与增强）** | P2-1 测试 → P1-5 渲染/中断 → P2-2 模板 → P2-3 子代理 →（可选）P2-4 沙箱 | ~3~4 天 | 关键路径有测试护栏；子代理演示可跑 |

原则：
1. 每项独立可验收，做完即跑对应验证命令，不攒批；
2. 严格最小改动——本计划只补缺口，不重构现有代码；
3. 每批结束更新本表的完成状态，作为进度台账。

---

## 4. 风险

| 风险 | 缓解 |
|---|---|
| 框架 `RequireUserConfirmEvent`/`ConfirmResult` API 与设计文档有出入 | 动手前先读 `D:\KitchenLv\agentscope-java-main` 源码核对签名，不按文档盲写 |
| `/model`、`/cd` 重建 agent 导致会话状态错乱 | 重建时保持同一 `(userId, sessionId)`，切换后先做一轮恢复验证 |
| MCP server 连接失败拖慢启动 | 连接超时与失败降级：打警告继续启动，不让 MCP 挂掉整个 REPL |
| ollama 模型工具调用质量差影响联调判断 | 测试用例仅断言"调用成功"，不依赖回答质量 |
| 无 git 期间误改难回退 | 第一批最前置安全整改：`git init` + 干净基线提交 |
