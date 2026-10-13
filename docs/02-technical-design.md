# Coding Agent Demo 技术设计文档

| 项目 | 内容 |
|---|---|
| 产品代号 | `dl-coder` |
| 文档版本 | v1.0 |
| 日期 | 2026-09-29 |
| 技术底座 | AgentScope-Java 2.x（`agentscope-harness` ≥ 2.0.3） |
| 上游文档 | [01-product-requirements.md](01-product-requirements.md) |
| 参照实现 | AgentScope 官方示例 `agentscope-codingagent`（仅借鉴其 Agent 装配与工具实现，分布式派发部分不采用） |

---

## 1. 设计目标与原则

对应 PRD 的 M1~M5 里程碑，技术设计遵循四条原则（均来自官方博客提炼的行业共识）：

1. **能力叠加，而非重写循环**——不自己实现 ReAct 循环，全部建立在 `HarnessAgent` 之上，每个里程碑基本只改 Builder 配置和外围代码；
2. **先划边界，再放权**——端侧信任模型对标 Claude Code：Agent 工作在独立工作区与目标项目目录内，越界访问与高危操作靠**确定性权限规则**拦截（ASK 确认/只读模式），不依赖沙箱；单机 Docker 沙箱仅为可选增强；
3. **agentic 与 deterministic 分离**——模型决定"做什么"，关键保障（上下文压缩、大结果卸载、预算、确认门）用确定性代码（middleware/hook）保证，不靠 prompt 祈祷；
4. **频繁变化的东西放文件，不放代码**——人格（AGENTS.md）、技能（skills/）、子代理（subagents/）、记忆（MEMORY.md）都是工作区里的 Markdown，可版本化、可独立更新。

## 2. 总体架构

### 2.1 架构分层

```
┌─────────────────────────────────────────────────────────┐
│ 交互层  CodingChatCli（REPL / 流式渲染 / 斜杠命令）        │
│         dl-coder 自研，M1 起                          │
├─────────────────────────────────────────────────────────┤
│ Agent 层  HarnessAgent（io.agentscope.harness）          │
│  ├ WorkspaceContextMiddleware  AGENTS.md/MEMORY.md 注入  │
│  ├ CompactionMiddleware        对话摘要压缩       (M2)   │
│  ├ ToolResultEvictionMiddleware 大工具结果卸载    (M2)   │
│  ├ PlanModeMiddleware          计划模式白名单     (M3)   │
│  ├ SubagentsMiddleware         子代理编排         (M3)   │
│  ├ DynamicSkillMiddleware      技能按需加载       (M3)   │
│  ├ MemoryFlushMiddleware       会话结束抽事实     (M2)   │
│  └ 权限系统（ASK/DENY/CONFIRM） 高危操作确认      (M4)   │
├─────────────────────────────────────────────────────────┤
│ 核心层  ReActAgent / Toolkit / AgentState（core 模块）    │
├─────────────────────────────────────────────────────────┤
│ 工具层  read_file · write_file · edit_file · grep_files  │
│         glob_files · list_files · execute                │
│         memory_* · session_* · plan_* · todo_write       │
│         agent_spawn/agent_send · load_skill · 自定义工具 │
│   （全部经 AbstractFilesystem 接口路由，见 §2.2）         │
├─────────────────────────────────────────────────────────┤
│ 文件系统层（可插拔，Agent 代码零改动）                     │
│  默认: LocalFilesystemSpec（本机 + sh -c）                │
│  可选: DockerFilesystemSpec（单机容器，一行切换）          │
├─────────────────────────────────────────────────────────┤
│ 模型层  ModelRegistry 字符串解析                          │
│  dashscope:qwen-plus（默认）│ openai: │ anthropic: │ …   │
├─────────────────────────────────────────────────────────┤
│ 状态层  AgentStateStore（本地 JSON 文件，端侧即用）        │
│         按 (userId, sessionId) 寻址，自动落盘/恢复        │
└─────────────────────────────────────────────────────────┘
```

### 2.2 关键机制：文件系统抽象是整个设计的支点

所有文件工具（`read_file` 等 6 个）和 `execute` 都经 `AbstractFilesystem` 接口路由。切换 `LocalFilesystemSpec` → `DockerFilesystemSpec` 是**一行配置**，Agent 人格、工具集、对话代码全部不变——这是端侧在“本机直跑 ↔ 单机容器隔离”之间切换的技术前提（本 demo 不涉及 Remote/K8s 等分布式后端）。

本机模式实际产出 **OverlayFilesystem**：

```
读：先 workspace 层 → 未命中读 project 层（copy-on-write 提升到 workspace）
写：projectWritable(true) 时按路径路由——
    工作区元数据（MEMORY.md/memory//skills//subagents//plans/）→ workspace
    其余（src/**、pom.xml 等代码文件）→ project 目录
shell：cwd = project 根
```

## 3. 技术栈与依赖

| 项 | 选择 | 说明 |
|---|---|---|
| JDK | 17+ | 框架硬性要求 |
| 构建 | Maven 3.9+ | 与官方示例一致 |
| 核心依赖 | `io.agentscope:agentscope-harness:2.0.3` | 自动传递 `agentscope-core` |
| 模型扩展 | `io.agentscope:agentscope-extensions-model-dashscope:2.0.3` | 切厂商时替换对应 `extensions-model-*` |
| 默认模型 | `dashscope:qwen-plus` | 字符串形式由 `ModelRegistry` 解析，自动读 `DASHSCOPE_API_KEY` |
| 可选扩展 | `agentscope-extensions-model-ollama`（本地零成本联调，任意里程碑可换） | **无任何分布式依赖**——全程单机 |

```xml
<dependencies>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-harness</artifactId>
        <version>2.0.3</version>
    </dependency>
    <dependency>
        <groupId>io.agentscope</groupId>
        <artifactId>agentscope-extensions-model-dashscope</artifactId>
        <version>2.0.3</version>
    </dependency>
</dependencies>
```

Gradle 等价：`implementation "io.agentscope:agentscope-harness:2.0.3"`。模型扩展可替换为 `extensions-model-{openai,openai-official,gemini,anthropic,ollama}`（字符串 id 经 `ModelRegistry` SPI 解析并自动读对应 `*_API_KEY`；`ollama:` 读 `OLLAMA_BASE_URL`，适合零成本本地联调）。

## 4. 工程结构

单 Maven 模块起步（demo 阶段不拆多模块），包结构对齐官方 codingagent 示例的职责划分：

```
dl-coder/
├── pom.xml
├── docs/                          # 本文档所在
└── src/main/java/io/dl/coder/
    ├── CoderApplication.java      # main：装配 + 启动 REPL
    ├── agent/
    │   └── CoderAgentFactory.java # 唯一的 HarnessAgent 装配点（§6.1）
    ├── cli/
    │   ├── CodingChatCli.java     # REPL 主循环、流式渲染
    │   ├── SlashCommand.java      # /exit /plan /memory /skills /session /clear /rewind /thinking …
    │   └── ConsoleRenderer.java   # TextBlockDelta / ToolCall 事件 → 终端 UI
    ├── config/
    │   └── AppConfig.java         # 环境变量 → 配置对象（API key、沙箱开关…）
    ├── tools/
    │   └── WebFetchTool.java      # M1 自定义工具示例（§5.4）
    ├── permission/
    │   └── PermissionPolicy.java  # M4 ASK/DENY 规则装配（§5.10）
    └── workspace/                 # 初始化模板（首次启动复制进工作区）
        ├── AGENTS.md
        ├── skills/commit-convention/SKILL.md
        └── subagents/reviewer.md
```

运行期数据目录（与代码分离）：

```
~/.agentscope/dl-coder/workspace/     # 工作区（AGENTS.md、MEMORY.md、skills/…）
~/.agentscope/state/dl-coder/         # AgentState（按 userId/sessionId）
```

## 5. 核心设计

### 5.1 Agent 装配（M1 完整形态）

`CoderAgentFactory` 是全项目**唯一**触碰 `HarnessAgent.builder()` 的地方，里程碑演进 = 修改这一个方法：

```java
public static HarnessAgent create(AppConfig cfg) {
    return HarnessAgent.builder()
        .name("dl-coder")
        // 人格与规约放 AGENTS.md，这里只留一句兜底
        .sysPrompt("你是一个务实的编程助手，用中文交流。")
        // 字符串模型 → ModelRegistry 解析；切厂商只改配置
        .model(cfg.modelId())                       // 默认 "dashscope:qwen-plus"
        .maxIters(50)                               // ReAct 迭代上限；默认 10 对 coding 任务偏小
                                                   // （官方 codingagent 亦取 50）
        // ---- 工作区与文件系统（M1：本机模式）----
        .workspace(cfg.workspacePath())             // ~/.agentscope/dl-coder/workspace
        .filesystem(new LocalFilesystemSpec()
            .project(cfg.projectDir())              // 目标项目根 = shell cwd + overlay 下层
            .projectWritable(true)                  // 代码文件直接写项目目录（关键）
            .inheritEnv(true)                       // 继承宿主环境变量（mvn/git 可用）
            .executeTimeoutSeconds(300)             // 构建/测试命令较长
            .maxOutputBytes(100_000))
        // ---- 上下文管理（M2：默认已开启，这里收紧阈值并显式声明意图）----
        .compaction(CompactionConfig.builder()
            .triggerMessages(30)                    // 默认 50 条触发；demo 收紧到 30
            .keepMessages(10)                       // 默认 20；保留尾部 10 条原文
            .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                .maxArgLength(2000).build())        // write_file 大入参截断
            .build())
        .toolResultEviction(ToolResultEvictionConfig.defaults()) // 默认 80K 字符落盘留摘要
        // ---- 编排（M3）----
        .enablePlanMode()                           // 先想清楚再动手
        .planFileDirectory("plans")
        // .subagent(...)                           // 声明式子代理走 subagents/*.md，免注册
        // ---- 记忆微调（M2）----
        .memory(MemoryConfig.builder()
            .flushTrigger(FlushTrigger.throttled(Duration.ofMinutes(10))) // 省 token
            .build())
        .build();
}
```

要点：
- 源码主干上 compaction 与 toolResultEviction **默认开启**（[HarnessAgent.java:1240-1242](../../agentscope-java-main/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java)：默认 `CompactionConfig.builder().build()` 即 50 条触发 / 20 条保留，eviction 80K 字符触发；早期文档表述为"默认关"）。demo 中仍显式配置，一是收紧阈值，二是把意图写进代码；
- 溢出兜底（`recoverFromOverflow`）在 compaction 开启后自动附带：真撞 `context_length_exceeded` 时紧急压缩后重试，无需配置；
- 压缩不会触碰 Plan 状态、todo 清单、后台子任务、权限上下文——可放心开启。

### 5.2 会话与状态

```java
RuntimeContext ctx = RuntimeContext.builder()
    .userId(cfg.userName())       // 默认 "dev"
    .sessionId(currentSessionId)  // REPL 维护；/session new 切换
    .build();
agent.call(new UserMessage(input), ctx).block();
```

- 同 `(userId, sessionId)` 自动加载/落盘 `AgentState`，跨进程重启续聊（US3）；
- 同 session 请求自动串行化，不同 session 完全并行；
- `AgentState` 存工作区**之外**的 `~/.agentscope/state/`（状态是恢复工作区的前提，不能耦合）；
- 工作区内另有永不压缩的原始日志 `agents/<agentId>/sessions/<sessionId>.log.jsonl`，供 `session_search` 工具检索。

### 5.3 CLI 交互层

用 `streamEvents()` 替代 `call()` 拿实时事件流（Web/TUI 通用）：

```java
agent.streamEvents(new UserMessage(input), ctx)
    .doOnNext(ev -> {
        switch (ev.getType()) {
            case TEXT_BLOCK_DELTA -> renderer.appendText(((TextBlockDeltaEvent) ev).getDelta());
            case TOOL_CALL_START  -> renderer.toolStart(((ToolCallStartEvent) ev).getToolCallName());
            case TOOL_CALL_END    -> renderer.toolEnd(/* 结果状态 */);
            default -> { }
        }
    })
    .blockLast();
```

斜杠命令在 REPL 层拦截（不进模型）：

| 命令 | 实现 |
|---|---|
| `/exit` | 退出循环 |
| `/session new [id]\|list\|switch <id>` | REPL 维护 sessionId；启动进入空会话（随机名 eager id、不落盘），首条消息物化注册；`new` 无参 = 随机名（形容词-名词-两位数） |
| `/plan` | `agent.enterPlanMode(ctx)` / `agent.exitPlanMode(ctx)`（程序化入口，不触发 HITL） |
| `/plan status` | `agent.isPlanModeActive(ctx)` + 读 `plans/PLAN.md` |
| `/memory` | 打印 `MEMORY.md` 尾部 N 行 |
| `/init` | 固定提示词走 chat 管线，agent 分析项目生成/合并项目根 `AGENTS.md`（写入过 HITL 确认） |
| `/skills` | 双来源列出技能：workspace 个人 `skills/` + 项目 `.dl-coder/skills/`（标注来源） |
| `/model <id>` | 改 `AppConfig.modelId` 并重建 agent 实例 |
| `/cd <path>` | 改 `projectDir` 并重建 agent 实例 |
| `/perm <mode>` | 切换 `PermissionMode`：`default` / `explore`（只读）/ `accept-edits`（M4，`agent.setPermissionMode(ctx, mode)`） |

### 5.4 自定义工具

框架内置 6 文件工具 + `execute` 已覆盖大部分场景，demo 只补一个 **WebFetchTool（M1）**（抓取 URL → Markdown 摘要，供"查文档"场景），守住"工具精选"原则（总数 ≤15）。高危确认不自研工具，直接用框架权限系统（§5.10）。

**注解式定义**（`io.agentscope.core.tool.Tool` / `ToolParam`），`toolkit.registerTool(obj)` 反射注册：

```java
public class WebFetchTool {
    @Tool(name = "web_fetch",
          description = "抓取 URL 并返回正文摘要（HTML 转 Markdown，截断到 8K 字符）",
          readOnly = true, concurrencySafe = true)
    public String fetch(
            @ToolParam(name = "url", description = "要抓取的 http(s) 地址")
            String url) {
        // HttpClient 抓取 + 简单正文抽取；完整实现参考官方示例 FetchUrlTool
        return HttpGrabber.grab(url, 8_000);
    }
}

// 装配：
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new WebFetchTool());
// HarnessAgent.builder()... .toolkit(toolkit)
```

补充说明：
- `@Tool` 属性：`readOnly`（只读标记，Plan Mode 下可用）、`dangerousFiles`/`dangerousDirectories`（声明危险路径，自动触发 ASK）、`converter`（自定义返回值转换）；
- 无 `@ToolParam` 的参数由框架注入：`ToolEmitter` / `AgentState` / `RuntimeContext`，或经 `RuntimeContext.builder().put(X.class, x)` 注册的业务 POJO；
- 复杂场景用编程式 `ToolBase`（实现 `Mono<ToolResultBlock> callAsync(ToolCallParam)`，返回 `ToolResultBlock.builder().id(...).name(...).output(...).state(ToolResultState.SUCCESS).build()`），并可覆写 `checkPermissions(...)` 参与权限决策；
- MCP 工具经 `McpClientBuilder.create("name").stdioTransport(...)/streamableHttpTransport(...)` + `toolkit.registerMcpClient(mcp)` 接入，命名 `mcp__{server}__{tool}`（M5 可选）；
- 工具内部如需读写文件，必须走 `agent.getWorkspaceManager()`（按当前文件系统模式正确路由），不要直接用 `java.nio.Files`——沙箱/远端模式下会写错地方。

### 5.5 Plan Mode（M3）

框架流转（无需自研状态机）：

```
模型自主调 plan_enter
  → 只读阶段：仅白名单工具可用
    （plan_enter/plan_write/plan_exit/todo_write/agent_spawn/agent_send/
     agent_list/task_output/task_list + 只读工具）
  → plan_write(content) 写 plans/PLAN.md
  → plan_exit 触发 HITL 确认（复用权限系统 ASK，终端 y/n）
  → ConfirmResult(true) 后全部工具解禁，进入执行
```

工程注意：
- Plan 状态随 `AgentState` 持久化，重启后仍在 plan 阶段；
- 判断结果勿只看 `isPlanModeActive()`——四种终态：未进入 / 已退出 / 待批准（有 PLAN.md）/ 只说不做（无 PLAN.md），后两种同 session 发消息可继续推进；
- 执行阶段用 `todo_write` 把 PLAN.md 拆成 5~8 条结构化任务（恰好一个 in_progress），两者独立但配套；
- `allowShellInPlanMode()` 保持默认 false（不放开 plan 阶段 shell）。

### 5.6 子代理（M3）

**声明式优先**（文件即配置，正文 = 子代理系统提示）：

```markdown
<!-- workspace/subagents/reviewer.md -->
---
description: 代码审查专家，审查改动并输出结构化 findings  # 必填：主 agent 委派决策依据
tools: [read_file, grep_files, glob_files, execute]        # 工具白名单
workspace.mode: isolated
model: dashscope:qwen-plus
steps: 8
---
你是资深代码审查员。审查范围…输出格式：问题/位置/严重度/建议…
```

主 agent 自动获得 `agent_spawn` / `agent_send` / `agent_list` / `task_output` 等工具：
- 同步委派：`timeout_seconds > 0`（默认 30s，上限 600s），超时自动转后台任务；
- 后台委派：`timeout_seconds = 0`，立即返回 `task_id`，**完成后框架自动把结果注入下一轮推理**（`<system-reminder>` 通道，无需轮询）；
- 同一轮多个同步 spawn 自动并行 fan-out/fan-in；
- 递归保护（子不可再 spawn，上限 3 层）、父 DENY 权限自动继承、Plan Mode 下子代理自动只读。

### 5.7 Skill（M3）

`workspace/skills/<name>/SKILL.md` 免注册即生效；YAML frontmatter 的 `description` 决定 agent 是否按需加载，正文为指令，路径一律相对。示例：

```
skills/commit-convention/
├── SKILL.md          # name + description + 提交规范指令
└── references/
    └── examples.md   # 好坏示例（按需加载，不占常驻 prompt）
```

运行机制：每轮推理重新合成 `<available_skills>` 清单进 system prompt；agent 需要时 `load_skill_through_path(skillId, "SKILL.md")` 拉详情——**常驻成本只有一行 description**。demo 不启用技能市场/自学习闭环（`GitSkillRepository` 等留作演进）。

### 5.8 长期记忆（M2）

双层结构全自动：会话结束/压缩前抽取事实 → `memory/YYYY-MM-DD.md`（只追加）→ 后台节流合并（默认间隔 30min）→ `MEMORY.md`（受 `maxContextTokens=8000` 预算约束注入）。模型自动获得 `memory_search` / `memory_get` / `memory_save` 工具。demo 微调一处：`flushTrigger` 改 THROTTLED(10min)，默认的每轮 flush 对 coding 场景偏费 token。

### 5.9 安全设计

| 层 | 默认形态（本机 + 权限，即 Claude Code 信任模型） | 可选增强（单机 Docker） |
|---|---|---|
| 文件边界 | `LocalFilesystemSpec` ROOTED 模式，写路由限 workspace+project | 容器内 `/workspace`，宿主零暴露 |
| 命令边界 | `execute` 仅在 project cwd 下执行，超时 300s，输出上限 100K | 同左，但在容器内 |
| 人工确认 | 权限系统 ASK 规则 + 内置危险路径清单（M4 起启用，见 §5.10） | 同左 |
| 隔离粒度 | 进程级（单用户端侧足够） | `IsolationScope.SESSION`：每会话一容器 |
| 状态边界 | AgentState 独立本地目录，与工作区解耦 | 同左（保持本地 JSON） |

沙箱切换（可选增强，仅改 factory 一处）：

```java
.filesystem(new DockerFilesystemSpec()
    .image("agentscope/coding-sandbox:latest")   // 或 ubuntu:24.04
    .isolationScope(IsolationScope.SESSION))
// 端侧单机：stateStore 保持默认本地 JSON 即可（框架仅打 warn 提示，可忽略）；
// 只有未来服务化多副本才必须换分布式存储（RemoteFilesystemSpec + 本地 store 会直接抛 IllegalStateException）
```

镜像硬约束：POSIX `sh`、`sed/grep/find`、GNU `stat -c`、`tar`、`base64`、`python3`（`edit_file` 依赖）、可写 `/workspace`。`ubuntu:24.04`/`debian` 满足，**alpine/distroless 不满足**。

### 5.10 权限与人工确认（M4）

不自研确认工具，直接用框架三态权限系统（ALLOW / ASK / DENY）。决策优先级：显式规则 Rules → 全局模式 Mode → 工具内置检查（`checkPermissions` + 危险路径清单，即使 BYPASS 也生效）。

**规则配置**（装配时传入，随 `AgentState` 持久化）：

```java
PermissionContextState permCtx = PermissionContextState.builder()
    .mode(PermissionMode.DEFAULT)
    // 高危 shell 模式弹确认（ruleContent 匹配命令内容）
    .addAskRule("dangerous-shell",
        new PermissionRule("dangerous-shell", "rm -rf|git push|mvn deploy", PermissionBehavior.ASK, "app"))
    // 绝不触碰敏感文件
    .addDenyRule("no-secrets",
        new PermissionRule("no-secrets", ".env|.ssh/|credentials", PermissionBehavior.DENY, "app"))
    .addWorkingDirectory(projectDir, new AdditionalWorkingDirectory(projectDir, "app"))
    .build();
// HarnessAgent.builder()... .permissionContext(permCtx)
```

**CLI 侧 HITL 流程**（流式模式）：工具触发 ASK 时事件流会收到 `RequireUserConfirmEvent`（`getToolCalls()` 拿到待确认调用）；REPL 渲染 y/n 提示，用户答复后把 `List<ConfirmResult>` 放进下一条 `UserMessage.metadata` 的 `Msg.METADATA_CONFIRM_RESULTS` 键再提交，agent 从断点恢复执行。用户选择"始终允许"的规则会被持久化，后续同类调用自动放行。

其他模式备选：`EXPLORE`（只读，等同 Plan Mode 外的临时锁）、`ACCEPT_EDITS`（工作目录内文件操作自动放行）、`DONT_ASK`（ASK 降级为 DENY，无人值守场景）。框架还内置危险路径清单（`ToolDangerousPathConstants`：`.bashrc`、`.ssh/*`、`.env`、`.git/` 等命中强制 ASK），自定义工具可用 `@Tool(dangerousDirectories = ...)` 追加。

## 6. 关键流程：一次用户输入的完整流转

```
用户输入 "给 UserService 加一个分页查询并补测试"
  │
  ▼ REPL（斜杠命令? → 本地处理，不进模型）
  ▼ streamEvents(msg, RuntimeContext)
  │
  ├─ HarnessAgent 加载 AgentState(userId, sessionId)          ← M1
  ├─ WorkspaceContextMiddleware 重拼 system prompt：
  │    AGENTS.md 全文 + MEMORY.md(≤8K) + skills 清单 + Workspace 段
  ├─ [若触发阈值] CompactionMiddleware 摘要压缩（压缩前 flush 事实+落盘原文） ← M2
  │
  ▼ ReAct 推理循环（core）
  │   ├─ 模型返回 tool_call: grep_files("UserService")
  │   │    → AbstractFilesystem 路由（本地 overlay / 沙箱容器）
  │   │    → [结果>80K] ToolResultEviction 落盘留摘要            ← M2
  │   ├─ 模型返回 tool_call: edit_file(...) / execute("mvn test")
  │   │    → [Plan Mode 中] 非白名单即拒，模型看到固定 DENY 文案   ← M3
  │   │    → [命中 ASK 规则] RequireUserConfirmEvent → 终端 y/n  ← M4
  │   └─ …循环直至模型产出最终回复
  │
  ├─ 会话结束：MemoryFlush 异步抽事实 → memory/日期.md           ← M2
  ├─ AgentState 自动落盘（含 plan 状态/todo/权限上下文）
  ▼
REPL 渲染 TextBlockDelta 流式输出 → 回到 You> 提示符
```

## 7. 配置设计

环境变量（全部有默认值，M1 只需 1 个必填）：

| 变量 | 必填 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | ✅ | 默认模型 key（切厂商改 `DL_MODEL_ID` + 对应 key） |
| `DL_MODEL_ID` | | 默认 `dashscope:qwen-plus` |
| `DL_PROJECT_DIR` | | 目标项目根，默认当前目录 |
| `DL_WORKSPACE` | | 默认 `~/.agentscope/dl-coder/workspace` |
| `DL_SANDBOX` | | `docker` 时启用 DockerFilesystemSpec（M4 可选增强） |
| `DL_USER` | | userId，默认 `dev` |

AGENTS.md（人格与规约的唯一载体，节选）：

```markdown
# Junzi Coder
你是务实、简洁的编程助手。
- 修改代码前先读相关文件；改动后主动运行相关测试验证
- 测试命令优先用 mvn -pl <module> test（根目录全量测试很慢）
- 提交信息遵循 skills/commit-convention 中的规范
- 大改动（>3 文件）先进 Plan Mode 出计划
```

## 8. 里程碑实现方案

| 里程碑 | 代码变更 | 配置变更 | 演示脚本 |
|---|---|---|---|
| **M1 能干活** | `CoderAgentFactory`（§5.1 去掉 M2/M3 段）+ REPL + 渲染器 + WebFetchTool | 仅 `DASHSCOPE_API_KEY` | 写 haiku 文件 → clone 仓库总结 → 重启续聊 |
| **M2 记得住** | factory 加 `.compaction` + `.toolResultEviction` + `.memory(...)`；`/memory` 命令 | 无 | 30 轮长对话不爆上下文；偏好跨会话生效；改 AGENTS.md 即时生效 |
| **M3 会规划** | factory 加 `.enablePlanMode()`；铺 workspace 模板（AGENTS.md/skills/subagents）；`/plan` 命令 | 无 | "重构 X" → 只读调研 → PLAN.md → 确认 → 执行+todo；委派 reviewer 审查 |
| **M4 够安全** | `.permissionContext(PermissionPolicy.build())`；REPL 接 `RequireUserConfirmEvent` 确认流 + `/perm` 模式切换 | （可选）`DL_SANDBOX=docker` + 构建镜像 | `git push` 前弹 y/n；`/perm explore` 只读生效；（可选）沙箱内 `rm -rf` 宿主无恙 |
| **M5 会扩展**（选做） | `toolkit.registerMcpClient(McpClientBuilder.create(...).stdioTransport(...))` 接 MCP；`/model` 热切换重建 agent；`/stats` 从事件流累计 token 用量 | 无新增必填 | 真实 MCP 工具调用成功；`/model ollama:qwen3` 即时生效；`/stats` 出数 |

## 9. 测试与验收策略

- **单元**：AppConfig 解析、SlashCommand 分发、PermissionPolicy 规则匹配（纯 JUnit5，无模型依赖）；
- **集成（关键）**：用 `InMemoryStore`/本地模型（`ollama:qwen3`）跑 AgentState 持久化与恢复——同 sessionId 两轮 call 断言记忆；Plan Mode 白名单拒绝路径断言；
- **验收**：每个里程碑跑 PRD §6 的 DoD 演示脚本，人工核对产物（文件、PLAN.md、MEMORY.md、容器）；
- **沙箱镜像检查**：M4 前按 §5.9 硬约束清单核验镜像工具链。

## 10. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 长会话上下文爆炸 | M2 起压缩+卸载必开；溢出兜底自动附带 |
| Windows 本地 `sh -c` 差异 | M1 文档标注建议 Git Bash/WSL；M4 沙箱统一为 Linux 环境 |
| 模型不擅长工具调用导致循环质量差 | 选 qwen-plus 及以上；`/model` 支持运行时换模对照 |
| Docker 不可用 | `DL_SANDBOX` 开关回退 LocalFilesystemSpec |
| 失控循环烧 token | `maxIters(50)` + 工具执行超时兜底；M5 加会话调用预算 |
| 子代理后台任务丢失 | 任务存 `workspace/agents/<id>/tasks/*.json`，可 `task_list`/`task_output` 找回 |

## 11. 附录：AgentScope 能力速查（本 demo 用到的）

| 能力 | Builder 入口 | 本 demo 状态 |
|---|---|---|
| 工作区 | `.workspace(path)` | M1 开 |
| 状态持久化 | `.stateStore(...)`（默认本地 JSON） | M1 起默认即用，不换 |
| 对话压缩 | `.compaction(CompactionConfig)`（默认开：50 条触发/20 条保留） | M2 收紧阈值 |
| 大结果卸载 | `.toolResultEviction(ToolResultEvictionConfig)`（默认开：80K 字符） | M2 显式声明 |
| 权限规则 | `.permissionContext(PermissionContextState)` | M4 开 |
| 长期记忆 | `.memory(MemoryConfig)`（默认开） | M1 开，M2 调 throttle |
| 子代理 | `workspace/subagents/*.md` 或 `.subagent(...)` | M3 开 |
| Plan Mode | `.enablePlanMode()`（默认关） | M3 开 |
| 技能 | `workspace/skills/`（个人）+ `projectGlobalSkillsDir(<项目根>/.dl-coder/skills)`（项目，禁用 overlay 裸 `skills/` 发现） | M3 开 |
| 本机文件系统 | `.filesystem(new LocalFilesystemSpec()...)` | M1 开 |
| Docker 沙箱 | `.filesystem(new DockerFilesystemSpec()...)` | M4 可选切换 |
| 通道/网关 | `agent.channel(ChatUiChannel.create())` | 不用（纯 CLI 直连 `call`/`streamEvents`） |
| 分布式 | `.distributedStore(RedisDistributedStore.fromJedis(...))` | **明确不用**（本项目纯端侧） |
