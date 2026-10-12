# session-lifecycle 规约

## ADDED Requirements

### Requirement: 启动进入空会话
REPL 启动后 MUST NOT 注册或恢复任何命名会话;当前提示符 MUST 显示 `新会话` 占位而非会话名。
内部 sessionId 在启动时即已生成(随机名),供 `/plan`、`/perm` 等会话级命令使用。

#### Scenario: 全新启动
- **WHEN** 用户启动 REPL 且不输入任何内容直接退出
- **THEN** sessions.json 中没有新增条目,磁盘无新注册会话

#### Scenario: 物化前使用会话级命令
- **WHEN** 用户在发送任何消息前执行 `/plan on` 或 `/perm dont-ask`
- **THEN** 命令正常生效,且生效状态保留到该会话物化后的首轮对话

### Requirement: 首条消息物化随机会话
用户发送首条对话消息(含 `/init` 注入的固定提示词)时,系统 MUST 用随机名注册会话并落盘
sessions.json,提示符切换为该随机名。后续消息沿用同一会话。

#### Scenario: 首条普通消息
- **WHEN** 空会话状态下用户输入普通文本消息
- **THEN** 会话以随机名注册(含创建时间),提示符从 `新会话` 变为该随机名,对话状态按该名持久化

#### Scenario: 以 /init 开局
- **WHEN** 空会话状态下用户执行 `/init`
- **THEN** 会话同样被物化注册,agent 随后处理初始化提示词

### Requirement: 随机名生成
随机名 MUST 形如 `<形容词>-<名词>-<两位数字>`(全小写英文、连字符分隔,如
`brave-otter-42`),MUST 匹配 `^[a-zA-Z0-9_\-.]+$`(stateStore 文件名安全模式),且
MUST NOT 与注册表中现有会话名重复(重复则重新生成)。

#### Scenario: 格式与唯一性
- **WHEN** 生成随机会话名
- **THEN** 名字匹配形容词-名词-数字格式,且与 sessions.json 已有条目不同名

### Requirement: /session new 支持无参调用
`/session new` 不带名字时 MUST 创建并切换到随机名会话(显式创建,立即注册);带名字时
行为不变(注册指定名并切换)。

#### Scenario: 无参创建
- **WHEN** 用户执行 `/session new`
- **THEN** 创建随机名会话、立即写入注册表并切换,输出提示含新会话名

#### Scenario: 带名创建(既有行为)
- **WHEN** 用户执行 `/session new my-feature`
- **THEN** 会话 `my-feature` 被注册并切换

### Requirement: 显式切回老会话
启动 MUST NOT 自动恢复历史会话(含历史上的 default 会话);老会话条目 MUST 保留在注册表
中,`/session switch <名字>` 仍可切换并恢复其对话状态。

#### Scenario: 不再自动续聊
- **WHEN** 用户上次以某会话退出后再次启动 REPL
- **THEN** 新启动处于空会话,不自动加载上次会话的对话历史

#### Scenario: 切回老会话
- **WHEN** 用户执行 `/session switch default`(该条目来自旧版本注册表)
- **THEN** 切换成功并恢复 default 会话的历史状态
