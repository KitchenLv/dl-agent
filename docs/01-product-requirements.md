# Coding Agent Demo 产品需求文档（PRD）

| 项目 | 内容 |
|---|---|
| 产品代号 | `dl-coder`（可改） |
| 文档版本 | v1.0 |
| 日期 | 2026-09-29 |
| 技术底座 | AgentScope-Java 2.x（`agentscope-harness` 2.0.1+） |
| 文档读者 | 产品/研发/学习者本人 |

---

## 1. 背景与动机

Coding Agent 已从"个人提效工具"（Claude Code、Cursor）演进到"组织级研发体系"（Stripe Minions、Ramp Inspect、Coinbase Cloudbot、LangChain Open SWE）。AgentScope-Java 2.x 的 Harness 层正是为这一形态设计的：它把工作区、长期记忆、会话持久化、子 Agent、沙箱、Plan Mode 等工程能力打包进一个 `HarnessAgent` Builder。

本项目要做的，是**基于 AgentScope-Java 2.x 构建一个功能比较完整的 Coding Agent Demo**，达到三个目的：

1. **跑通**：验证 AgentScope Harness 在真实 coding 场景下的能力边界；
2. **学习**：以可运行的代码理解"上下文工程、工具精选、确定性保障"这些生产级 Coding Agent 的核心模式；
3. **展示**：形成一个可以对外演示、可以持续打磨的个人端侧工具。

**产品决策**：本 Demo 为**纯端侧（本地单机）形态**，对标 Claude Code 的信任模型——工作区边界 + 权限确认 + 本地状态，**不包含任何分布式组件**（多副本、Redis/OSS、webhook/IM 通道均不做）。Coding Agent 的主战场是开发者本机；组织级服务化是另一个产品，不在本项目范围。

### 1.1 为什么值得做

- 行业趋同已经发生：per-session 隔离沙箱、确定性 thread 路由、middleware 拦截链、repo 级指令文件（AGENTS.md）、draft PR 输出契约——这些架构决策被 Stripe/Ramp/Coinbase/Anthropic/LangChain 独立验证过。
- AgentScope-Java 官方已有 `agentscope-codingagent` 示例（组织级、GitHub webhook 形态），但**缺少一个从零起步、面向个人开发者的端侧完整实现**——本 Demo 填补这个空档：只借鉴官方示例的 Agent 装配与工具实现，不采用其分布式派发部分。

## 2. 产品定位

### 2.1 一句话定位

> 一个跑在开发者本机的、具备完整工程化能力（工作区/记忆/计划/子代理/权限）的命令行 Coding Agent——对标 Claude Code 的端侧形态，纯单机。

### 2.2 是什么 / 不是什么

| ✅ 是 | ❌ 不是 |
|---|---|
| 交互式 CLI/REPL 形态的 AI 结对编程助手 | IDE 插件（不做编辑器内嵌） |
| 在**独立工作区**里读写文件、执行命令、跑构建和测试的自治 Agent | 直接暴露宿主机任意目录的裸 shell 工具 |
| 分阶段可演示的教学/展示项目 | 商业化产品 |
| 可选沙箱增强：本机直跑 ↔ 单机 Docker 容器（一行切换） | 多副本服务 / SaaS（明确不做） |

### 2.3 参照系

| 参照物 | 借鉴点 |
|---|---|
| Claude Code | 交互形态（REPL、流式输出、斜杠命令）、Plan Mode、CLAUDE.md → AGENTS.md |
| LangChain Open SWE | "先隔离再放权"哲学、~15 个精选工具、agentic/deterministic 分离 |
| AgentScope 官方 `agentscope-codingagent` 示例 | Agent 装配参数与自定义工具实现范例（其分布式派发不在本 demo 范围） |
| GitHub Copilot Coding Agent | 改动完成后用项目现有 CI/测试做验证，人类确认后才合入 |

## 3. 目标用户与使用场景

### 3.1 目标用户

- **主用户（P0）**：开发者本人——在终端里让 Agent 帮忙写代码、改 bug、跑测试、做重构。
- 纯端侧单用户工具，不设团队/多用户场景。

### 3.2 核心用户故事

| # | 用户故事 | 优先级 |
|---|---|---|
| US1 | 作为开发者，我在 REPL 里输入自然语言需求，Agent 读取/创建/编辑工作区中的文件并告诉我它做了什么 | P0 |
| US2 | 作为开发者，我让 Agent 执行 shell 命令（构建、测试、git），并实时看到命令输出 | P0 |
| US3 | 作为开发者，我重启程序后接着上次的会话继续聊，Agent 还记得之前的上下文和改动 | P0 |
| US4 | 作为开发者，面对大任务，我要求 Agent 先给出计划、我确认后再动手（Plan Mode） | P1 |
| US5 | 作为开发者，我把重复性工作（如"代码审查""写单测"）交给专职子 Agent 处理 | P1 |
| US6 | 作为开发者，Agent 用得越久越懂我——长期记忆沉淀了我的偏好和项目规约 | P1 |
| US7 | 作为开发者，Agent 的命令执行被隔离在 Docker 沙箱里，跑坏了也不伤我的机器 | P1 |
| US8 | 作为开发者，我用斜杠命令查看/管理会话、计划、记忆和技能 | P1 |

## 4. 功能需求

功能按四个模块组织，优先级 P0（M1 必须）→ P1（完整形态）→ P2（演进方向）。

### 4.1 交互层（CLI/REPL）

| ID | 功能 | 优先级 | 说明 |
|---|---|---|---|
| I-1 | 交互式 REPL | P0 | `You>` 提示符、多行输入、退出命令 |
| I-2 | 流式渲染 | P0 | 文本增量输出 + 工具调用实时展示（调用名、参数摘要、结果状态） |
| I-3 | 斜杠命令 | P1 | `/exit` `/plan` `/memory` `/skills` `/subagents` `/session` `/init` `/perm` `/model` `/cd` `/mcp` `/stats` `/clear` `/rewind` `/thinking` |
| I-4 | 会话管理 | P1 | 新建/切换/列出会话；`sessionId` 显式传入 |
| I-5 | 工具执行确认 | P1 | 高危操作（如删除、`git push`）执行前请求人工确认 |

### 4.2 工具层（能力集）

遵循"**工具精选比工具数量重要**"原则，控制在 ~15 个以内。

| ID | 功能 | 优先级 | 说明 |
|---|---|---|---|
| T-1 | 文件读取 | P0 | 读文件（分页、行号） |
| T-2 | 文件写入/编辑 | P0 | 写新文件 + 精确字符串替换编辑 |
| T-3 | 文件检索 | P0 | glob 模式匹配 + 正则 grep 内容搜索 |
| T-4 | 目录列表 | P0 | 列目录树 |
| T-5 | Shell 执行 | P0 | 在工作区内执行命令，带回显与超时 |
| T-6 | Web 获取/搜索 | P1 | 抓取文档页、搜索资料 |
| T-7 | 记忆工具 | P1 | 长期记忆读写（由 Harness 内建） |
| T-8 | 子代理派发 | P1 | `agent_spawn` 委派子任务（由 Harness 内建） |
| T-9 | 本地 git 辅助 | P2 | 生成 commit message、分支管理（经 `execute` 跑 git 命令实现，无需独立工具） |

### 4.3 记忆与上下文管理

| ID | 功能 | 优先级 | 说明 |
|---|---|---|---|
| M-1 | 会话持久化 | P0 | 同 `(userId, sessionId)` 自动恢复对话状态，跨进程重启 |
| M-2 | 对话压缩 | P1 | 消息超阈值自动摘要压缩，防止长会话上下文爆炸 |
| M-3 | 大结果卸载 | P1 | 超长工具输出（大 diff、长日志）写入工作区文件，上下文只留摘要 + 路径 |
| M-4 | 长期记忆 | P1 | 每日事实 → `memory/YYYY-MM-DD.md` → 周期合并 `MEMORY.md` → 自动注入 system prompt |
| M-5 | AGENTS.md 人格规约 | P1 | repo 级指令文件驱动 Agent 行为，可版本化 |

### 4.4 编排与安全

| ID | 功能 | 优先级 | 说明 |
|---|---|---|---|
| S-1 | 独立工作区 | P0 | Agent 只在 `~/.agentscope/<app>/workspace/` 内读写，与用户项目隔离 |
| S-2 | Plan Mode | P1 | 大改动先只读调研 → 产出计划 → 人工确认 → 进入执行；退出计划需确认 |
| S-3 | 子 Agent | P1 | markdown 声明式定义（职责+工具集），主 Agent 按需委派 |
| S-4 | Skill 机制 | P1 | `skills/` 目录存放团队 SOP（提交规范、测试规范），按需加载 |
| S-5 | 沙箱执行（可选增强） | P2 | 一行配置切换单机 `DockerFilesystemSpec`，读写/执行进容器；端侧默认信任模型靠权限系统，沙箱非必须 |
| S-6 | 调用预算 | P2 | 单会话模型调用上限（`maxIters` 之外的第二道闸），防失控循环烧自己的钱 |
| S-7 | 用量统计 | P2 | 会话级 token / 调用数统计，`/stats` 查看 |

## 5. 非功能需求

| 维度 | 要求 |
|---|---|
| 运行环境 | JDK 17+；Windows/macOS/Linux；Docker（仅可选沙箱增强需要） |
| 模型 | 默认 DashScope `qwen-plus`；可切 OpenAI/Anthropic/Gemini/本地 Ollama，仅改一行配置 |
| 启动成本 | 一条命令 + 一个 API Key 即可跑通 P0 功能；无需 Docker/webhook |
| 响应体验 | 首 token 流式可见；工具调用过程实时可见 |
| 安全底线 | 默认不触碰工作区之外的路径；高危操作一律弹确认（ASK），危险路径内置拦截 |
| 成本可控 | 压缩+卸载默认开启；`maxIters` + 会话调用预算兜底 |
| 代码质量 | 模块边界清晰，每个演进 Stage 可独立演示 |

## 6. 里程碑与验收标准

> 每个里程碑都是一个**可独立演示**的版本，对应技术设计文档 §9 的具体配置/代码变更。

### M1 —— "能干活"（P0，预计 1~2 天）

**范围**：REPL + HarnessAgent + 本地工作区 + 文件工具 + shell 执行 + 会话持久化 + 流式渲染。

**验收（DoD）**：
1. `mvn exec:java` 一条命令进入 REPL，仅依赖 `DASHSCOPE_API_KEY` 一个环境变量；
2. 输入 "write hello.txt with a haiku about Java" → 工作区出现该文件；
3. 输入 "clone <repo> 并总结它的功能" → Agent 完成克隆、读取并输出中文总结；
4. 重启进程，同 sessionId 继续对话，Agent 记得上一轮内容；
5. 所有文件操作只发生在工作区目录内。

### M2 —— "记得住"（P1，预计 1 天）

**范围**：压缩 + 大结果卸载 + 长期记忆 + AGENTS.md。

**验收（DoD）**：
1. 连续 30+ 轮对话后上下文自动压缩，长会话不因 token 爆掉而失败；
2. 构造超长工具输出（如全量测试日志），验证上下文只剩摘要 + 文件路径提示；
3. 对话中告知 Agent 个人偏好（如"测试统一用 JUnit5"），多轮后关闭会话重开，偏好仍生效（MEMORY.md）；
4. 修改 AGENTS.md 中的人格描述，重启后行为随之改变。

### M3 —— "会规划"（P1，预计 2 天）

**范围**：Plan Mode + 子 Agent + Skill + 斜杠命令。

**验收（DoD）**：
1. 开启 Plan Mode 后 Agent 只做只读调研并输出结构化计划，经确认后才执行修改；
2. 声明一个 `reviewer` 子 Agent，主 Agent 能把"审查刚才的改动"委派给它并回收结论；
3. 定义一个 `commit-convention` Skill，Agent 提交时遵循其中规范；
4. 斜杠命令可用：`/plan` `/memory` `/skills` `/session`。

### M4 —— "够安全"（P1，预计 1~2 天）

**范围**：权限系统为核心（执行确认 + 只读模式 + 危险路径拦截，对标 Claude Code 的信任模型）；单机 Docker 沙箱为可选增强。

**验收（DoD）**：
1. 高危工具调用（`git push`、删除、命中内置危险路径清单如 `.ssh/`、`.env`）前出现 y/n 确认；
2. 选择"始终允许"的规则持久化，后续同类调用自动放行；
3. `/perm explore` 进入只读模式、`/perm accept-edits` 自动放行工作区内编辑，切换即时生效；
4. （可选）`DL_SANDBOX=docker` 一行切换单机容器隔离，沙箱内执行破坏性命令宿主机无任何变化。

### M5 —— "会扩展"（P2，选做，纯端侧打磨）

**范围**：MCP 工具接入（stdio / streamableHttp）、`/model` 运行时换模（含本地 Ollama）、会话用量统计、REPL 体验（多行输入、历史记录）。

**验收（DoD）**：
1. 接入一个真实 MCP server 并成功调用其工具；
2. `/model ollama:qwen3` 热切换生效，无需重启进程；
3. `/stats` 显示本会话 token 用量与工具调用统计。

## 7. 风险与开放问题

| 风险/问题 | 影响 | 对策 |
|---|---|---|
| 模型不支持/弱支持工具调用 | Agent 循环质量差 | 选 `qwen-plus` 及以上；FallbackModel 对限流做透明重试 |
| 长会话上下文爆炸 | 请求失败/成本飙升 | M2 起压缩+卸载默认开启（官方明确"这不是可选项"） |
| Windows 本地 shell 差异 | 命令行为不一致 | 命令统一走 `sh -c` 语义封装；M4 起沙箱统一环境 |
| Docker 不可用（公司内网） | 可选沙箱用不了 | 端侧默认形态就是"本机 + 权限确认"，沙箱纯增强，无碍主线 |
| 失控循环烧 token | 成本事故 | `maxIters(50)` 兜底 + M5 会话调用预算 |
| Agent 误删工作区文件 | 演示事故 | 高危操作确认机制（I-5）+ 工作区快照 |

## 8. 术语表

| 术语 | 含义 |
|---|---|
| HarnessAgent | AgentScope-Java 2.x 推荐入口，打包工作区/记忆/持久化/子 Agent/沙箱的 Agent Builder |
| Workspace | Agent 的内容根目录：AGENTS.md、MEMORY.md、skills/、subagents/、sessions/ |
| AgentState | 对话历史、压缩摘要、Plan 状态、权限规则等的统称，按 `(userId, sessionId)` 落盘 |
| Plan Mode | 先只读调研产出计划、人工确认后再执行的受控流程 |
| Compaction | 对话超阈值后自动摘要压缩的机制 |
| IsolationScope | 沙箱隔离粒度：SESSION（每会话一容器）/ USER（每用户一容器） |
| MCP | Model Context Protocol，端侧接入外部工具的标准协议（M5 可选） |
