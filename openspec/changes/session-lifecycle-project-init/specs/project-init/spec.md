# project-init 规约

## ADDED Requirements

### Requirement: /init 命令生成项目 AGENTS.md
`/init` MUST 作为固定中文提示词经现有 chat 管线发给 agent,由 agent 分析当前项目根并生成
项目根目录的 `AGENTS.md`,内容涵盖构建/测试命令、架构概览、代码约定。文件写入 MUST 走
既有工具权限确认(ASK→HITL)闭环。

#### Scenario: 首次初始化
- **WHEN** 项目根不存在 `AGENTS.md`,用户执行 `/init`
- **THEN** agent 调研项目后通过 write_file 生成 `AGENTS.md`,写入前经过用户确认

#### Scenario: /init 开局即物化会话
- **WHEN** 空会话状态下执行 `/init`
- **THEN** 该会话按 session-lifecycle 规约物化注册,初始化消息成为首轮对话

### Requirement: 已有 AGENTS.md 时合并更新
项目根已存在 `AGENTS.md` 时,`/init` MUST 指示 agent 先读取既有内容并合并更新,而非
覆盖或推倒重写;既有用户内容 MUST NOT 丢失。

#### Scenario: 增量更新
- **WHEN** 项目根已有用户维护的 `AGENTS.md`,用户执行 `/init`
- **THEN** agent 先读旧文件,在保留既有内容的基础上补充/更新,产出合并后的文件(仍经用户确认)

### Requirement: 项目 AGENTS.md 每轮注入系统提示词
系统 MUST 通过自定义 middleware 在每轮 reasoning 时读取项目根 `AGENTS.md`:存在则将其
内容以独立小节追加进系统提示词,不存在则提示词保持不变。注入 MUST 不依赖 agent 重建
(文件变更后下一轮即生效),且 MUST 同时对子代理生效。

#### Scenario: 文件存在时注入
- **WHEN** 项目根存在 `AGENTS.md` 且用户发送消息
- **THEN** 本轮系统提示词包含该文件内容(以项目规约小节形式),无需重启或 /cd

#### Scenario: 文件不存在时无副作用
- **WHEN** 项目根不存在 `AGENTS.md`
- **THEN** 系统提示词与未启用该 middleware 时一致

#### Scenario: /cd 后跟随新项目根
- **WHEN** 用户 `/cd` 切换项目根并触发 agent 重建
- **THEN** 注入来源切换为新项目根的 `AGENTS.md`

### Requirement: 项目技能约定目录 .dl-coder/skills
agent 装配 MUST 将项目级技能目录显式注册为 `<项目根>/.dl-coder/skills/`(经
projectGlobalSkillsDir),同时 MUST 禁用经文件系统 overlay 对裸 `<项目根>/skills/` 的
技能发现,防止项目业务目录被误读为 agent 技能。该约定目录 MUST NOT 被自动创建,仅在
已存在时生效。

#### Scenario: 项目技能被发现
- **WHEN** `<项目根>/.dl-coder/skills/<技能名>/SKILL.md` 存在
- **THEN** 该技能对模型可见、可加载

#### Scenario: 裸 skills 目录不被误吞
- **WHEN** 项目根存在与 agent 无关的业务目录 `<项目根>/skills/`(其中含 SKILL.md)
- **THEN** 该目录下的文件不被发现为 agent 技能

#### Scenario: 个人技能不受影响
- **WHEN** 禁用 overlay 技能源后启动 REPL
- **THEN** workspace `~/.dl-coder/workspace/skills/` 下的个人技能(如 commit-convention)仍被列出

### Requirement: /skills 双来源列表
`/skills` 命令 MUST 同时列出 workspace 个人技能与项目 `.dl-coder/skills/` 项目技能,并
标注各自来源;两处皆空时给出空提示。

#### Scenario: 双来源展示
- **WHEN** workspace 与项目 dotdir 各有技能,用户执行 `/skills`
- **THEN** 输出分来源列出全部技能名

#### Scenario: 项目无技能目录
- **WHEN** 项目根不存在 `.dl-coder/skills/`
- **THEN** `/skills` 仅列出个人技能,不报错
