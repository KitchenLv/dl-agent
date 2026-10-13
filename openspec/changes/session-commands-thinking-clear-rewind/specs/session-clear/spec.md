# session-clear 规约

## ADDED Requirements

### Requirement: /clear 指令确认后清除对话上下文
REPL MUST 提供 `/clear` 斜杠命令:执行前 MUST 展示确认提示(说明将清除对话消息与
压缩摘要、将保留会话身份与任务等状态),用户确认(y/yes)后才执行;取消(n/no)时
MUST NOT 产生任何副作用。实际清除 MUST 调用框架 `HarnessAgent.clearContext`,
即只清模型可见对话消息与压缩摘要,并立即持久化。

#### Scenario: 确认清除
- **WHEN** 有对话历史的会话中用户执行 `/clear` 并回答 y
- **THEN** 模型可见对话上下文与压缩摘要被清空且持久化,后续对话不携带此前历史

#### Scenario: 取消清除
- **WHEN** 用户执行 `/clear` 后回答 n
- **THEN** 对话上下文不变,可继续原对话

### Requirement: 清除范围边界
`/clear` MUST NOT 改变:会话名与身份、权限模式与已授权规则、已注册工具、任务状态、
计划模式状态。空会话(无任何对话消息)执行 `/clear` MUST 输出"没有可清除的上下文"
并保持无副作用,不算错误。

#### Scenario: 保留权限与计划状态
- **WHEN** 用户在 `/perm dont-ask` 与 `/plan on` 生效状态下执行 `/clear` 并确认
- **THEN** 清除后权限模式与计划模式仍保持生效

#### Scenario: 空会话执行
- **WHEN** 启动后未发送任何消息即执行 `/clear`
- **THEN** 输出无内容可清的提示,不报错、无任何状态变化

### Requirement: /clear 同步执行不中断后台任务
`/clear` 执行时框架后台工具任务与待办状态 MUST 保持原样(不取消、不回滚);
REPL 斜杠命令串行执行,`/clear` MUST NOT 在模型调用进行中被触发。

#### Scenario: 任务状态保留
- **WHEN** 存在进行中后台任务的会话执行 `/clear` 并确认
- **THEN** 对话上下文清空,后台任务继续运行,任务列表不丢失
