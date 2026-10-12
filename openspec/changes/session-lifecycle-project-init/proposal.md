# 提案:会话生命周期重构 + 项目初始化(参考 Claude Code 模式)

## Why

当前每次启动都落入固定的 "default" 会话并自动续聊上次历史:多话题工作全挤在一个桶里、
sessions.json 里永远只有一条记录、对话隔离名存实亡。同时项目侧缺少 Claude Code 的两个核心
机制——`/init` 生成项目规约、项目级 AGENTS.md/技能每轮生效。本提案对齐 Claude Code 的
"每次启动即新会话、显式恢复" 与 "项目规则/技能按项目生效" 模式。

## What Changes

- **去掉 default 会话**:启动进入空会话(提示符显示 `[新会话]`),首条用户消息时才物化
  会话并用随机名(形容词-名词-数字,如 `brave-otter-42`)注册;未聊天直接退出零残留。
- **BREAKING(行为)**:启动不再自动续聊上一次的对话。老会话(含历史上的 "default")
  仍在注册表里,通过 `/session switch` 显式切回。
- `/session new` 支持不传名字:无参 = 随机名。
- `/plan`、`/perm` 等会话级命令在会话物化前即可正常使用(sessionId 启动即生成,仅注册延后)。
- 新增 `/init` 命令:发送固定中文提示词走现有 chat 管线,由 agent 分析项目并生成/合并更新
  项目根 `AGENTS.md`(构建命令、架构、约定);写文件照常过 HITL 权限确认。
- 项目根 `AGENTS.md` 每轮注入系统提示词(新增自定义 middleware,规避框架 workspace
  AGENTS.md 遮蔽项目 AGENTS.md 的限制)。
- 项目级技能约定目录 `<项目根>/.dl-coder/skills/`(对齐 Claude Code 的 `.claude/skills`),
  同时关闭对裸 `<项目根>/skills/` 的自动发现,避免项目自身业务 skills 目录被误读为 agent 技能。
- `/skills` 命令改为双来源列表(workspace 个人技能 + 项目技能),标注来源。

## Capabilities

### New Capabilities

- `session-lifecycle`:空会话启动、随机命名、首条消息物化注册、`/session new` 无参随机名、
  显式切回老会话。
- `project-init`:`/init` 生成与合并更新 AGENTS.md、项目 AGENTS.md 每轮注入、项目技能
  dotdir 约定与发现边界、`/skills` 双来源展示。

### Modified Capabilities

(无——openspec/specs/ 目前为空,全部为新建)

## Impact

- `cli/CoderCli`:会话初始化与物化时机、`run()` 提示符、`session()`/`skills()` 命令、
  新增 `/init` 注册与罐头提示词。
- `cli/SessionManager`:移除 `DEFAULT_SESSION`、新增随机名生成(词表 + 查重)。
- `agent/CoderAgentFactory`:注册 `ProjectContextMiddleware`、`projectGlobalSkillsDir`、
  `disableDefaultWorkspaceSkills`。
- 新文件 `middleware/ProjectContextMiddleware`(onSystemPrompt 每轮注入项目 AGENTS.md)。
- 测试:随机名生成器、middleware 纯函数拼装、`/skills` 双来源;现有 36 个 hermetic 测试
  保持全绿(`mvn test` 为验收线)。
- 框架依赖 agentscope-harness 2.0.2 不改;其源码(D:\KitchenLv\agentscope-java-main)仅作参考。
