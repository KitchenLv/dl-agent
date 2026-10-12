# AGENTS.md — DL Coder 项目规约

本文件供编码代理在本仓库工作时遵循。规则基于项目现状核实，与代码不一致时以代码为准并更新本文件。

## 项目是什么

DL Coder：跑在开发者本机的命令行 Coding Agent，基于 **AgentScope-Java 2.x**（`io.agentscope:agentscope-harness`）。对标 Claude Code 的端侧形态：REPL、文件/命令工具、会话持久化、计划模式、权限系统、MCP 工具接入。纯单机，无分布式组件。Java 26 + Maven。

代码注释、Javadoc、系统提示词、UI 文案一律用**中文**，新代码保持该约定。

## 构建与测试命令

```bash
mvn compile                              # 编译
mvn test                                 # 全部测试（43 个，密闭无网络，约 5 秒）
mvn test -Dtest=SessionManagerTest       # 单个测试类
mvn test -Dtest=AppConfigTest#方法名     # 单个测试方法
mvn exec:java                            # 启动 REPL（mainClass 已在 pom 配置）
```

- 运行前配置：复制 `config.example.json` 为 `~/.dl-coder/config.json` 并填 apiKey；模型支持 Anthropic / OpenAI / ollama（本地零成本）。
- 测试是密闭的：不需要配置文件、不联网，可直接跑。
- `AgentTest` 带 `@Disabled` 跳过（1 skipped 属正常）。

## 架构概览

入口是 `io.dl.coder.cli.CoderCli`（REPL 主循环）。注意：`docs/README.md` 里写的 `CoderApplication` 已过时，实际不存在。

```
io.dl.coder
├── cli/         CoderCli（REPL 主循环、斜杠命令分发）、ConsoleRenderer（终端渲染）、
│                SessionManager（会话名注册表）、SlashCommand（斜杠命令定义）
├── agent/       CoderAgentFactory（装配 HarnessAgent：Toolkit、状态存储、模型）
├── middleware/  ProjectContextMiddleware（每轮现读项目根 AGENTS.md 注入系统提示词）
├── prompt/      SystemPrompt（中文系统提示词分节组装）
├── permission/  权限系统（PermissionMode、PermissionRule，敏感操作 y/n/a 人工确认）
├── config/      AppConfig、McpConfig、ModelProvider（配置与模型选择）
└── workspace/   WorkspaceInitializer（初始化 ~/.dl-coder 工作区）
```

核心数据流：

1. 用户输入进入 `CoderCli` REPL；`/` 开头走 `SlashCommand`（本地处理，不进模型）。
2. 其余交给 HarnessAgent reasoning；`ProjectContextMiddleware` 每轮把项目根 AGENTS.md 追加进系统提示词。
3. 模型发起工具调用（读/写/执行命令等），经 permission 层检查，敏感操作弹出 y/n/a 确认。
4. 流式输出经 `ConsoleRenderer` 渲染到终端。
5. 会话内容由框架 AgentStateStore 按 (userId, sessionId) 自动持久化到 `~/.dl-coder`；`SessionManager` 只维护会话名清单（`sessions.json`），不存对话数据。

工作区内嵌模板（首次启动初始化用）在 `src/main/resources/workspace-templates/`，含 workspace 级 AGENTS.md、技能与子代理的 Markdown 模板——人格、技能、子代理都是 Markdown 文件。

设计文档在 `docs/`（PRD、技术设计、完善计划）。

## 代码约定

- **中文优先**：注释、Javadoc、系统提示词、UI 文案全用中文。
- **改动最小聚焦**：只改与任务直接相关的代码，不顺手重构、不修无关 bug（发现就汇报）。
- **验证闭环**：每次改动后运行与改动直接相关的编译/测试；命令报错就是真的错了，检查不过不声称完成。
- **快照与回退**：大改前 `git add -A && git commit -m "checkpoint"` 打快照；改坏就 `git checkout --` 回退换思路，不留 `.bak` 文件。
- **危险操作先问**：`git push`、删文件、改写历史前先向用户确认。
- **提交信息**：conventional commits 风格（`feat:` / `fix:` 等），描述用中文。
- **汇报格式**：聚焦"改了什么、验证结果、遗留事项"，输出用 `###` 小标题。
