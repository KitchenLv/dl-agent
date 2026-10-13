# thinking-config 规约

## ADDED Requirements

### Requirement: /thinking 指令查看与设置
REPL MUST 提供 `/thinking` 斜杠命令:无参显示当前 thinking 级别与可选值
(`off|low|medium|high`);带合法级别时切换并回显新级别;带非法值时 MUST 报错并列出
可选值,不改变当前级别。级别解析 MUST 容忍大小写。

#### Scenario: 查看当前级别
- **WHEN** 用户执行 `/thinking`
- **THEN** 输出当前级别(如 `medium`)与可选值列表,不改变任何状态

#### Scenario: 设置合法级别
- **WHEN** 用户执行 `/thinking high`
- **THEN** 当前级别切换为 high 并回显,从下一次模型调用起生效

#### Scenario: 非法级别
- **WHEN** 用户执行 `/thinking max`
- **THEN** 输出错误提示与可选值 `off|low|medium|high`,当前级别保持不变

### Requirement: thinking 级别运行时立即生效
级别切换 MUST 经 middleware 在下一次模型调用时覆盖生成参数(不重建 agent、不重启);
`/thinking` 切换与进行中的模型调用互不干扰(进行中的调用按旧级别完成)。

#### Scenario: 切换后下轮生效
- **WHEN** 用户在会话中执行 `/thinking low` 后发送新消息
- **THEN** 该轮模型调用的生成参数按 low 档构造(middleware 覆盖)

#### Scenario: 空闲会话切换
- **WHEN** 全新空会话中用户执行 `/thinking medium` 后开始对话
- **THEN** 首轮模型调用即按 medium 档构造

### Requirement: 级别按 provider 映射
`off` 档 MUST NOT 注入任何 thinking 相关生成参数;`low|medium|high` 档按 provider
映射:anthropic → `thinkingBudget`(三档递增,low 至少 1024);openai →
`reasoningEffort`(同名直传);ollama → 不注入,`/thinking` 设置时 MUST 输出"当前
provider 不支持 thinking 配置"提示且不报错。

#### Scenario: anthropic 映射
- **WHEN** provider 为 anthropic 且级别为 low
- **THEN** 模型调用生成参数含 thinkingBudget ≈ 1024;级别为 off 时不含该参数

#### Scenario: openai 映射
- **WHEN** provider 为 openai 且级别为 high
- **THEN** 模型调用生成参数 reasoningEffort = high;off 时不含该参数

#### Scenario: ollama 忽略
- **WHEN** provider 为 ollama 且用户执行 `/thinking low`
- **THEN** 回显支持提示(不支持),模型调用生成参数不含 thinking 相关字段

### Requirement: 级别持久化到配置
`/thinking <级别>` 切换成功时 MUST 把级别写入用户 `config.json`(模型配置节);
启动装配 agent 时 MUST 从配置读取该值作为初始级别;配置缺省该键或值非法时 MUST 回退
为 `off` 且不注入任何参数。

#### Scenario: 切换后重启保留
- **WHEN** 用户执行 `/thinking medium` 后退出并重启 REPL
- **THEN** 当前级别为 medium,首轮对话模型调用按 medium 构造

#### Scenario: 旧配置兼容
- **WHEN** config.json 中无 thinkingLevel 键(旧版本配置)时启动
- **THEN** 级别为 off,启动与对话行为与旧版本一致
