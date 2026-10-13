# 提案:会话操作三指令(/thinking、/clear、/rewind)

## Why

当前 dl-agent 缺少三个高频会话控制能力:模型 thinking 强度只能改配置文件重启生效;
想清空上下文重开话题只能放弃整个会话(丢失会话身份/权限/任务状态)或手工新建;
对话走偏后没有任何回退手段,只能忍受污染的上下文或重建会话。对标 Claude Code 的
`/model` thinking 切换、`/clear`、`/rewind` 三件套,补齐会话生命周期中的日常操作闭环。

## What Changes

- 新增 `/thinking <级别>` 指令:`off|low|medium|high` 四档,运行时经 middleware 覆盖
  模型调用参数立即生效;级别**持久化**写入 `config.json`(重启保留),新会话默认读取
  配置值。按 provider 映射:anthropic → `thinkingBudget`,openai → `reasoningEffort`,
  ollama → 忽略(输出提示不支持)。
- 新增 `/clear` 指令:经 `HarnessAgent.clearContext(ctx)` 清除模型可见对话上下文与压缩
  摘要,**保留**会话身份、权限配置、工具状态、任务、计划模式状态;执行前 **y/n 确认**。
- 新增 `/rewind` 指令:回退对话消息到指定历史轮次。dl-agent 自管 checkpoint——每个
  用户轮次完成后将 `AgentState` 快照(JSON)存 `~/.dl-coder/checkpoints/<sessionId>/`,
  每会话保留约 20 份(超限裁剪最旧)。恢复仅回退**对话消息**(截断 context + 清压缩
  摘要),不动文件改动、任务、计划模式状态。无参交互式列出可选轮次(n 取消),
  `/rewind <n>` 直接回退 n 个用户轮次,执行前 y/n 确认。
- 无 param 细则见 design.md;三条指令均注册进 `/help` 自动生成清单(现有机制)。

## Capabilities

### New Capabilities

- `thinking-config`:`/thinking` 指令、级别→provider 参数映射、middleware 运行时覆盖、
  级别持久化与启动加载。
- `session-clear`:`/clear` 指令、清除范围(消息+摘要,保留身份/权限/工具/任务/计划)、
  确认交互、in-flight 保护。
- `session-rewind`:checkpoint 打点与裁剪、`/rewind` 交互式列表与按数回退、恢复范围
  (仅对话消息)、确认交互、会话切换/清除时的 checkpoint 一致性。

### Modified Capabilities

(无——openspec/specs/ 目前为空,三个能力全部新建;与进行中 change
`session-lifecycle-project-init` 的 `session-lifecycle` 无 spec 级重叠,实现层面
共享 CoderCli/SessionManager 代码,任务顺序上待其收尾后实施。)

## Impact

- `config/ModelConfig`:新增可选 `thinkingLevel` 字段(持久化载体,经 AppConfig 序列化)。
- `config/AppConfig`:thinking 级别读写与默认值回退(缺省 off/不注入)。
- 新文件 `middleware/ThinkingMiddleware`(onModelCall 覆盖 GenerateOptions;内部
  AtomicReference 供 REPL 运行时切换)。
- `cli/CoderCli`:三条斜杠命令注册与交互(确认、列表)、轮次完成后的 checkpoint 打点、
  rewind 恢复调用(getAgentState → contextMutable 截断 → setSummary(null) →
  saveAgentState)。
- 新文件 `cli/CheckpointManager`(快照序列化、按会话存取、20 份裁剪)。
- 框架依赖 agentscope-harness 2.0.4-SNAPSHOT 不改,仅使用公开 API(clearContext、
  getAgentState/saveAgentState、GenerateOptions.mergeOptions、MiddlewareBase.onModelCall)。
- 测试:级别映射、middleware 覆盖逻辑、checkpoint 序列化/裁剪、rewind 截断计算;
  全部 hermetic(`mvn test` 为验收线)。
