# 技术设计:会话生命周期 + 项目初始化

## Context

- 现状:`CoderCli` 启动即 `currentSession = "default"` 并注册,框架按 `(userId="dev", sessionId)`
  从 stateStore 恢复对话——每次启动自动续聊同一个 default 桶。
- 框架(agentscope-harness 2.0.2,不可改)关键事实:
  - `WorkspaceContextMiddleware.readAgentsMd` 经 overlay 文件系统读相对路径 `AGENTS.md`,
    **上层 workspace 优先,遮蔽项目根的同名文件**(框架测试 ProjectAwareOverlayTest 明确断言)。
  - `ReActAgent.seedSystemMsg` 每轮 reasoning 都会调用 middleware 链的
    `onSystemPrompt(agent, ctx, prompt) → Mono<String>`(ReActAgent.java:789);
    `HarnessAgent.Builder.middleware()` 公开(HarnessAgent.java:1613),且 builder 会把
    middleware 拷贝给子代理(HarnessAgentBuilderSupport.java:423)。
  - 技能发现层(低→高):L1 `projectGlobalSkillsDir(Path)`(宿主自选目录)、L2 用户仓库、
    L3 workspace `skills/`(java.nio 直扫)、L4 `WorkspaceSkillRepository(filesystem, "skills")`
    ——L4 走 overlay glob,**两层合并**,因此裸 `<项目根>/skills/` 也会被发现;
    `disableDefaultWorkspaceSkills()` 只关 L4,L3 保留。
  - stateStore 文件名安全模式 `^[a-zA-Z0-9_\-.]+$`,不匹配的 sessionId 会被 Base64 编码成
    不可读目录名。

## Goals / Non-Goals

**Goals:**
- 启动即空会话,首条消息物化随机命名会话;未聊天退出零残留。
- `/init` 由 agent 分析项目生成/合并 `AGENTS.md`,复用 chat 管线与 HITL。
- 项目 `AGENTS.md` 每轮注入;项目技能走 `.dl-coder/skills/` 显式约定。
- `mvn test` 全绿(36 个 hermetic 测试)为验收线。

**Non-Goals:**
- 不改 agentscope-harness 框架(不做 L4 glob 目录可配、readAgentsMd 合并两层等)。
- 不做会话自动摘要命名(随机名固定,不做按首条消息生成标题)。
- 不做 `/resume` 交互式选择器(`/session switch` 已够用)。
- 不迁移/清理老 "default" 注册条目与其历史状态文件。
- 不支持项目级 subagents / knowledge 目录(workspace 机制照旧)。

## Decisions

### D1:eager sessionId + lazy 注册(而非"首条消息才生成会话")

启动即生成随机名作为 currentSession 并用于一切 `RuntimeContext`,但**不**写入 sessions.json;
首条用户消息(含 `/init` 罐头消息)时才 `register` 落盘并切换提示符显示。
- 备选:首条消息才物化 sessionId——被否:`/plan on`、`/perm dont-ask` 等会话级命令在
  物化前会悬空,或产生"权限模式设置丢失"的隐性 bug。
- 提示符:未物化时显示 `[新会话] you>`,物化后 `[brave-otter-42] you>`。

### D2:随机名 = 形容词-名词-两位数(英文)

内置约 30×30 词表 + `SecureRandom` 两位数(≈9 万组合),`SessionManager.randomName()`
生成时对 `sessions.names()` 查重,重名即重摇。
- 英文的理由:stateStore 文件名安全模式直接命中(中文名会 Base64 成乱码目录);
  Windows 控制台 GBK 代码页下无编码风险。
- 语法 `<adjective>-<noun>-<nn>`,全小写,连字符分隔。

### D3:/init = 罐头中文提示词走 chat()

`/init` 注册为普通 slash command,实现为 `chat(<固定提示词>)`:指示 agent 分析项目根
(构建/测试命令、架构概览、代码约定)并生成 `AGENTS.md`;**若已存在则先读旧内容合并更新,
不推倒重写**。写文件经 `write_file` 走既有 ASK→HITL 确认闭环。
- 备选:CLI 静态模板复制(WorkspaceInitializer 式)——被否:无项目分析能力,与
  Claude Code 模式不符。
- `/init` 是首条消息时自动物化会话(与 D1 咬合,和 Claude Code 一致)。

### D4:项目 AGENTS.md 注入 = 自定义 ProjectContextMiddleware(onSystemPrompt)

新增 `middleware/ProjectContextMiddleware implements MiddlewareBase`,覆写
`onSystemPrompt`:每轮现读 `projectDir/AGENTS.md`(UTF-8),存在则以
`### 项目规约(AGENTS.md)` 节追加到系统提示词尾部;不存在原样返回。
`CoderAgentFactory` 装配 `.middleware(new ProjectContextMiddleware(projectDir))`,
`/cd` 重建 agent 时自然重绑新项目根。
- 备选 A:拼进 `SystemPrompt.build()`——被否:sysPrompt 在 build 时固化,/init 生成后
  需 rebuild 才生效,手改文件永久 stale。
- 备选 B:改框架 readAgentsMd 合并两层——被否:违反不改框架约束。
- 该通路与框架 workspace AGENTS.md 注入互不干扰,天然规避遮蔽限制;middleware 随
  builder 拷贝给子代理,reviewer 等也获得项目规约。
- SystemPrompt.java:57 现有"项目根若有 AGENTS.md 先读再动手"的指引保留不动(注入后
  该指引依然成立,双保险)。

### D5:项目技能 = `.dl-coder/skills/` 显式约定,关闭裸目录发现

`CoderAgentFactory` 增加:
`.projectGlobalSkillsDir(projectDir.resolve(".dl-coder/skills"))` +
`.disableDefaultWorkspaceSkills()`。
- 对齐 Claude Code 的 `.claude/skills` 惯例:跨工具标准的 AGENTS.md 放项目根,
  工具私有的东西进 dotdir。
- 关闭 L4 的理由:裸 `<项目根>/skills/` 会被 overlay 合并发现——项目自身业务 skills
  目录(游戏/教育类项目常见)可能被误吞为 agent 技能;L3(java.nio 扫 workspace/skills)
  继续承担个人技能,能力等价(FileSystemSkillRepository:mtime 缓存、资源懒加载)。
- 目录惰性注册:L1 在 build 时 `Files.isDirectory` 检查,目录不存在不注册、**不自动创建**
  (避免给所有项目塞 .dl-coder/ 目录);中途创建后 `/cd .` 或重启生效。

### D6:/skills 双来源列表

CLI 侧 java.nio 同时扫 `workspace/skills` 与 `projectDir/.dl-coder/skills`,输出标注
`(个人)`/`(项目)`,修复现在模型可见、人不可见的项目技能盲点。

## Risks / Trade-offs

- [L4 关闭后 workspace 技能是否仍列出] → 理论确定(L3 独立仓库),实现后冒烟:启动即见
  commit-convention 技能。
- [overlay glob 合并项目技能的行为在框架测试中无直接覆盖] → 本设计的 L4 本就要关闭,
  项目技能改走 L1 显式目录,不依赖该未测行为。
- [技能同名优先级与 Claude Code 相反:L1 最低,workspace 个人压过项目] → 接受的妥协;
  builder 旋钮无法把项目技能排到 L3 之上,同名冲突罕见;如未来需要,走 `skillRepositories()`
  自排顺序(记录于此,不实施)。
- [老用户 "default" 会话不再自动续聊] → 有意的行为变更(proposal 已标 BREAKING);
  registry 条目保留,`/session switch default` 可回。
- [随机名可读性] → 纯装饰用途,`/session new <名字>` 仍可命名;不做重命名命令。
- [ProjectContextMiddleware 每轮读盘] → 本地小文件读,开销可忽略;不做 mtime 缓存。

## Migration Plan

无需数据迁移:sessions.json 旧条目(含 default)原样保留;stateStore 老状态文件不动。
回滚 = revert 代码(随机名会话的状态文件会成为孤儿,registry 残留条目无害)。

## Open Questions

(无——探索阶段已全部拍板:英文随机名、合并更新、middleware 注入、不管老 default。)
