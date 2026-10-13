# session-rewind 规约

## ADDED Requirements

### Requirement: 轮次 checkpoint 自动打点
每个用户轮次完成(本轮模型回复结束、控制权回到 REPL)时,系统 MUST 自动把该轮开始
前的 `AgentState` 对话状态快照(JSON)写入 `~/.dl-coder/checkpoints/<sessionId>/`
目录,文件名序号单调递增。快照 MUST 通过 agent 的 get→mutate→save 通道使用,禁止
绕过 agent 直接改写会话存储。打点失败 MUST NOT 中断对话(输出警告后继续)。

#### Scenario: 轮次结束自动打点
- **WHEN** 用户发送一条消息并收到完整回复
- **THEN** 该会话 checkpoint 目录新增一个序号文件,内容为该轮开始前的对话状态

#### Scenario: 未对话不打点
- **WHEN** 启动后未发送任何消息
- **THEN** 不产生任何 checkpoint 文件

### Requirement: checkpoint 数量上限裁剪
每个会话的 checkpoint MUST 保留最近 20 份;新快照导致超限时 MUST 删除最旧序号的
文件。裁剪 MUST NOT 影响其他会话的目录。

#### Scenario: 超限裁剪
- **WHEN** 某会话已有 20 份 checkpoint 后又完成新一轮对话
- **THEN** 目录仍为 20 份,最旧的一份被删除

### Requirement: /rewind 交互式回退
REPL MUST 提供 `/rewind` 斜杠命令:无参时 MUST 列出该会话可用 checkpoint(序号、
对应用户消息前缀、时间)并支持选择,输入 n 取消;选定目标后 MUST 经 y/n 确认才执行
回退。回退 MUST 通过 agent API 完成:读取当前 `AgentState`,将 context 消息截断至
快照消息数,清空压缩摘要,然后保存写回;MUST NOT 重建 agent,且 MUST NOT 改动
权限上下文、任务、计划模式状态与任何文件系统副作用。

#### Scenario: 交互式选择回退
- **WHEN** 有 3 份 checkpoint 的会话执行 `/rewind`,选择第 2 份并确认 y
- **THEN** 模型可见对话回到第 2 份快照对应轮次之前的状态,压缩摘要被清空,
  会话身份/权限/任务/计划状态不变,后续对话从该点继续

#### Scenario: 取消回退
- **WHEN** 用户在 checkpoint 列表或 y/n 确认处输入 n
- **THEN** 对话状态不变,checkpoint 文件无增删

### Requirement: /rewind 按轮次数直退
`/rewind <n>` MUST 直接回退 n 个用户轮次(等价于回退到倒数第 n+1 个 checkpoint);
n 非法(非正整数或超出可用 checkpoint 数)时 MUST 报错并提示可用范围,不产生任何
状态变化。执行前同样 MUST y/n 确认。回退成功后 MUST 回显回到的轮次位置。

#### Scenario: 直接回退两轮
- **WHEN** 已完成 5 个用户轮次的会话执行 `/rewind 2` 并确认
- **THEN** 对话回到第 3 轮开始前,近两轮消息从模型上下文移除

#### Scenario: 超范围回退
- **WHEN** 仅有 3 份 checkpoint 的会话执行 `/rewind 5`
- **THEN** 报错提示可用范围为 1-3,状态不变

### Requirement: 回退范围仅对话消息
`/rewind` 只回退模型可见对话消息与压缩摘要;此前轮次产生的文件写入、命令执行效果、
后台任务 MUST 保持原样。回退执行时 MUST 在输出中明示"文件与任务不受影响"。
快照加载失败(如格式不兼容)MUST 报错并保持当前状态不变。

#### Scenario: 文件副作用不回滚
- **WHEN** 第 3 轮对话中 agent 修改了文件,随后 `/rewind` 回到第 2 轮
- **THEN** 模型上下文不含第 3 轮消息,但文件保持被修改后的状态,输出中有不回滚提示

#### Scenario: 快照不兼容
- **WHEN** checkpoint 文件因框架升级无法反序列化时执行回退
- **THEN** 报错提示快照不兼容,当前对话状态不变
