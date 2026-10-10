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

#### P1-1 斜杠命令补全：`/model` `/cd` `/memory` `/skills` ✅（2026-10-08 完成）
- **实现**：命令分发抽成 `SlashCommand` 接口 + `CoderCli` 内注册表（`/help` 自动生成）；新增四命令——`/model [name]` 热切换（改内存配置 + 重建 agent，不落盘）、`/cd [path]` 切项目根（校验目录存在 + 重建，execute 的 cwd 与文件边界跟随）、`/memory` 打印 workspace MEMORY.md 尾部 30 行、`/skills` 列出 workspace skills/ 技能目录。`CoderAgentFactory` 增加显式传 config/projectDir 的重载供重建复用；会话状态在 stateStore 按 (userId, sessionId) 恢复，重建不丢上下文。
- **验收**：实测 `/model glm-5.3-flash` 切换后对话正常、`/cd` 切 docs 后 execute 边界跟随、重建后对话正常（"只回复两个字：收到"→ 正常回复）。

#### P1-2 `/stats` 增加 token 用量 ✅（2026-10-08 完成，机制就绪）
- **实现**：`ConsoleRenderer` 消费 `MODEL_CALL_END.getUsage()`（`ChatUsage`），本轮（endTurn 暗色行 `↑in ↓out tokens`）与进程累计（`/stats`）双口径，字符数保留。
- **已知边界**：当前 anthropic 兼容网关（bigmodel）流式响应不回传 usage，实测显示 `↑0 ↓0`；机制正确，数值取决于网关是否返回。

#### P1-3 MCP 端到端实测 ✅（2026-10-08 完成，含两个修复）
- **实测**：`@modelcontextprotocol/server-filesystem`（stdio）接入成功，`list_directory {"path":...} → SUCCESS` 返回真实目录内容。
- **修复 ①**：Windows 下 stdio 的 `command: npx` 拉不起进程（ProcessBuilder 不解析 `.cmd`），且框架注册失败只打 SLF4J 日志（本项目无 provider → 静默失败）——`McpConfig` 对无扩展名的裸命令名在 PATH 上找 `xxx.cmd`，找到则补全。
- **修复 ②**：`/exit` 后 JVM 挂住（MCP stdio 读线程非守护线程，2.0.2 的 `close()` 不终止）——`main` 末尾 `System.exit(0)`。
- **与文档的偏差**：工具注册名是**原名**（如 `list_directory`），不是技术设计说的 `mcp__{server}__{tool}` 前缀；且与内建工具同名时直接覆盖注册表（`read_file` 等会被 server 版本替换）。
- **遗留观察**：模型偶尔会"以为"自己没有 MCP 工具（实测第一轮自述无 fs 工具，强制提示后调用成功）——提示词/工具描述层面的问题，不阻断使用。

#### P1-4 模型厂商扩展：ollama / dashscope ✅（2026-10-08 完成）
- **实现**：`ModelProvider` 增加 `OLLAMA`（默认 `http://localhost:11434/v1`，key 可缺省填占位符）、`DASHSCOPE`（默认 compatible-mode 端点）；两者走 `OpenAIChatModel`，无需新依赖。`ModelProviderTest` 3 例覆盖。
- **验收**：解析与端点单测通过；真实 ollama/dashscope 服务对话未实测（本机无 ollama 运行时）。

#### P1-5 渲染与中断体验 ⚠️ 部分完成（2026-10-08）
- **已完成**：`TOOL_CALL_START` 显示工具名；`TOOL_RESULT_END` 行附带入参 JSON 摘要（截断 100 字符，缓冲自 `TOOL_CALL_DELTA`）。
- **未完成**：文件编辑类工具的 diff 摘要；Ctrl+C/Esc 中断当前轮（阻塞式 `BufferedReader` + Windows 控制台信号处理改造量大，且需取消 Reactor 订阅并处理会话半完成状态，建议单独立项）。

### P2 — 工程质量与可选增强

#### P2-1 测试补齐 ✅（2026-10-10 完成，并抓到两个真 bug）
- **实现**：`mvn test` **全密闭**（无需配置与网络），36 用例全过：新增 `McpConfigTest`（8：结构校验/`${ENV}` 展开/Windows 补 cmd）、`SessionManagerTest`（3）、`WorkspaceInitializerTest`（2）、`AgentStatePersistenceTest`（1：假模型 + 新实例共享 stateStore，验证同会话两轮历史恢复）；重写 `AppConfigTest`（8：校验边界 + 示例文件）；`AgentTest` 修正笔误（原 `toolkit.registerTool(toolkit)`）+ 补断言 + 改为 `DL_LIVE_TEST=true` 才跑的 opt-in live 测试。
- **抓到的 bug ①**：`SessionManager` 的 DTO 字段 private 无 getter，Jackson 默认不序列化——`sessions.json` 恒写空对象，跨进程会话清单从未持久化。已修（字段改 public）。
- **抓到的 bug ②**：pom 钉 `jackson-databind:2.17.2` 与 harness 传递的 `jackson-dataformat-yaml:2.21.1` 冲突，子代理 frontmatter 解析直接 `NoSuchMethodError`（此前表现为静默挂死）。已对齐 2.21.1。
- **未覆盖**：`SlashCommand` 分发（命令是绑定 CoderCli 实例的 lambda，抽接口需重构，违背最小改动原则）；Plan Mode 拒绝路径（需模拟工具调用流，留待 P1-5 中断机制一起做）。

#### P2-2 workspace 首启模板初始化 ✅（2026-10-10 完成）
- **实现**：`workspace/WorkspaceInitializer` + classpath 模板（AGENTS.md 工作区规约 / skills/commit-convention / subagents/reviewer），`CoderCli.main` 首启补齐，**已存在的一律不覆盖**。实测：真实工作区首启打印初始化清单，`/skills` 列出技能。
- **注意**：classpath 资源路径必须带前导 `/`（相对路径会解析到本类包下），测试抓出。

#### P2-3 子代理落地 ⚠️ 机制验证通过，结果回传不稳定（2026-10-10）
- **已验证**：`subagents/reviewer.md` 模板被框架发现；`agent_spawn`（y 确认后）成功拉起 reviewer 独立 ReAct 循环（真实 read/grep/execute 审查动作）；`agent_send` 双向通信可发起（再次确认）。
- **已知问题**：① 子代理默认迭代上限偏小，reviewer 深挖框架源码时超迭代提前结束、首轮无最终报告；② `agent_send` 回复为空、`session_history`/`session_list` 查不到子代理会话——产出取不回来（主 agent 最终 fallback 自己审查）。根因疑在 2.0.2 上游（异步任务通道/会话登记），后续可从 `wait_async_results`/`task_list` 异步模式入手排查。
- **顺手修复**：jackson 版本冲突（见 P2-1 bug ②）——不修则 spawn 在解析 frontmatter 时即崩。

#### P2-4 Docker 沙箱可选切换 ⬜ 未做（可选项）
- 本机无 Docker 环境，按计划标记为可选，未动。

#### P2-5 安全整改：config.json 中的真实 API Key ✅（2026-10-10 完成）
- **现状**：项目根 `config.json`（含真实 key）已删除；git 历史从未包含它（`.gitignore` 第 28 行已排除，仓库只有 `config.example.json` 占位符）。
- **遗留（用户操作）**：该 key 曾明文出现在仓库目录与 `~/.dl-coder/config.json`，**建议到 bigmodel 控制台作废重建**。

#### P2-6 Windows 兼容标注 ✅（2026-10-10 完成）
- **实现**：README 重写——快速开始、配置表、**Windows 已知限制**（跨盘符 `'other' has different root` 及绕行、GBK 控制台与 `chcp 65001`、MCP stdio 的 `.cmd` 说明）、Git Bash + Windows Terminal 建议。

---

## 3. 实施批次

| 批次 | 内容 | 预估 | 出口标准 |
|---|---|---|---|
| **第一批（安全闭环）** | ✅ P0-3 maxIters → ✅ P0-2 权限规则 → ✅ P0-1 确认流 → ✅ P0-4 web_fetch 核查（2026-09-29，出口标准全过）；⬜ P2-5 安全整改 + git 基线仍待办 | ~2.5 天 | `git push`/`rm -rf` 弹 y/n 且 `a` 可持久放行；读 `.env` 被拒；50 轮迭代不截断 |
| **第二批（M5 收尾）** | ✅ P1-1 命令补全 → ✅ P1-2 token 统计（网关不回传 usage 时显示 0）→ ✅ P1-3 MCP 实测（含 Windows npx.cmd 与 /exit 挂住两个修复）→ ✅ P1-4 ollama/dashscope（2026-10-08）；⚠️ P1-5 渲染/中断部分完成（工具名+参数摘要已做，diff 摘要与 Ctrl+C 中断未做，建议单独立项） | ~2.5 天 | MCP 真实调用成功、`/model` 即时生效、`/stats` 出 token 数（依赖网关回传） |
| **第三批（质量与增强）** | ✅ P2-1 测试（36 例密闭，抓到 SessionManager 序列化与 jackson 冲突两个真 bug）→ ✅ P2-2 模板 → ⚠️ P2-3 子代理（spawn/独立循环/send 验证通过，结果回传不稳定，遗留上游排查）→ ✅ P2-5 安全整改 → ✅ P2-6 Windows 标注（2026-10-10）；⬜ P2-4 沙箱（可选，本机无 Docker）；⬜ P1-5 剩余（diff 摘要与 Ctrl+C 中断，建议单独立项） | ~3~4 天 | 关键路径有测试护栏；子代理演示可跑 |

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
