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

#### P0-1 接通 HITL 人工确认流 ✅（2026-09-29 完成）
- **现状**：~~`ConsoleRenderer` 收到 `REQUIRE_USER_CONFIRM` 只打印"当前 CLI 未接确认流，默认拒绝"。~~ 已接通。
- **实现**：`CoderCli.chat` 内循环——事件流在 `RequireUserConfirmEvent` 后结束，渲染待确认调用，逐个收集 `y/n/a`，把 `List<ConfirmResult>` 放入下一条 `UserMessage.metadata`（`METADATA_CONFIRM_RESULTS` 键）恢复执行，直到本轮不再产生确认（上限 20 轮防死循环）。
- **与设计的偏差**：① 框架 2.0.2 每次调用都从 AgentState 重建 `PermissionEngine`，`ConfirmResult.rules` 只进当轮引擎——`CoderCli.persistAllowRules` 通过 `getDelegate().replacePermissionContext(...)` 把 "a" 规则读改写回会话 state，实现跨轮（含跨重启）持久化；② 2.0.2 的 `ConfirmResult` 无拒绝原因构造器，`n` 时模型看到默认 "Permission denied by user"。
- **验收**：已实测（glm-5.3-flash，脚本化 REPL）：`git push` 弹确认，`n` 拒绝且 agent 收到拒绝结果；`a` 后同会话再次 `git status` 免确认直接执行。

#### P0-2 权限规则装配（PermissionPolicy）✅（2026-09-29 完成）
- **实现**：新建 `permission/` 包：
  - `PermissionPolicy.buildPermissionContext(projectDir)`：no-secrets（`.env|.ssh/|credentials|id_rsa|*.pem` → DENY，作用于 read/write/edit/grep）、dangerous-shell（`rm -r*|rm -f*|git push|git reset --hard|mvn deploy` → ASK）、只读与工作区工具 → 工具级 ALLOW、项目根 `AdditionalWorkingDirectory`。
  - `GuardedTool`：包装 harness 内建工具，补上注解式工具缺失的 `matchRule`（ruleContent 按正则匹配 command/path 参数）与内容级 `generateSuggestions`（"a" 精确到本次命令文本，不放行整个工具）。
- **与设计的偏差**：技术设计 §5.10 的 `ruleContent` 直接写规则即可用的前提不成立——框架注解式工具未覆写 `matchRule`，非空 ruleContent 永不命中，故需 `GuardedTool` 包装（在 `HarnessAgent.build()` 后经 `agent.getToolkit()` 替换注册）。
- **验收**：`PermissionPolicyTest` 10 例全过（命中/不命中/DENY>ASK>ALLOW 优先级/建议规则精度/包装幂等）；实测 `read_file .env` 被规则直接 DENIED、`list_files`/`glob_files` 免确认。
- **已知边界**：grep_files 的 DENY 只拦显式指向敏感文件的 path，整目录 grep 可能带出片段。

#### P0-3 设置 `maxIters` ✅（2026-09-29 完成）
- **实现**：`.maxIters(50)`（原为 30，非框架默认 10）；`EXCEED_MAX_ITERS` 警告渲染已支持。

#### P0-4 `web_fetch` 工具核查 ✅（2026-09-29 完成，无需改码）
- **结论**：`web_fetch`/`web_search` 为 harness 内建（`WebTools`，`disableWebTools` 默认 false 即默认注册），`SystemPrompt` 提示词条目准确，无需实现自定义工具。
- **验收**：源码核验通过（`HarnessAgent` build 流程 2738-2746 行注册）；抓取真实 URL 的端到端演示留待日常使用验证。

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
| **第一批（安全闭环）** | ✅ P0-3 maxIters → ✅ P0-2 权限规则 → ✅ P0-1 确认流 → ✅ P0-4 web_fetch 核查（2026-09-29，出口标准全过）；⬜ P2-5 安全整改 + git 基线仍待办 | ~2.5 天 | `git push`/`rm -rf` 弹 y/n 且 `a` 可持久放行；读 `.env` 被拒；50 轮迭代不截断 |
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
