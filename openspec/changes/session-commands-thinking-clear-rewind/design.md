# 设计:会话操作三指令(/thinking、/clear、/rewind)

## Context

三条指令全部挂在 `CoderCli` REPL 的斜杠命令层,共用 `HarnessAgent`(AgentScope-Java
2.0.4-SNAPSHOT)的公开 API。技术探索阶段已在框架源码(`D:\KitchenLv\agentscope-java-main`)
逐点验证:

- **thinking**:框架无"事后改系统提示词级别"的入口,但 `MiddlewareBase.onModelCall`
  可拦截模型调用并用 `GenerateOptions.mergeOptions(覆盖, input.options())` 覆盖参数
  (merge 语义:primary 非空字段胜出)。Hook 系统已废弃,不走。
- **clear**:`HarnessAgent.clearContext(ctx)` 只清模型可见对话消息与压缩摘要,保留
  会话身份、权限、工具、任务、计划状态,立即持久化,空会话 no-op;不可在 in-flight
  调用中调用(REPL 斜杠命令天然串行,满足)。
- **rewind**:模型可见上下文的唯一真身是 stateStore 单一键 `agent_state` 内 `AgentState`
  的 context 消息列表;agent 把状态常驻堆内(CAS 写回),**不可绕过 agent 直接改存储**。
  框架无历史版本 API(versioning 仅 CAS 乐观锁),`TranscriptStore` 只追加不可变,故
  rewind 需 dl-agent 自建 checkpoint。官方支持 get → mutate → save 管理模式
  (`getAgentState` Javadoc 明示;`contextMutable()` 返回可变 List)。

用户已拍板的 5 项决策:thinking 级别持久化到 config.json;rewind 只回退对话消息;
/clear 需要 y/n 确认;checkpoint 每会话保留 20 份;三条指令合为一个 change。

## Goals / Non-Goals

**Goals:**

- `/thinking` 四档(off/low/medium/high),运行时立即生效,重启后保留。
- `/clear` 一键清上下文重开话题,会话身份与状态全保留。
- `/rewind` 回退对话到指定轮次,checkpoint 自动打点、自动裁剪。
- 三条指令全部 hermetic 可测,不触碰网络。

**Non-Goals:**

- 不做文件系统回滚(v1 只回退对话消息;文件改动留在原地,`/rewind` 输出中提示)。
- 不做跨会话 checkpoint(每会话独立目录,互不可见)。
- 不改框架依赖版本,不碰废弃的 hook 系统。
- 不做 rewind 的"预览 diff"(列出目标轮次摘要即可)。

## Decisions

### D1. /thinking:middleware 覆盖 + AtomicReference 运行时切换 + 配置持久化

- 新建 `middleware/ThinkingMiddleware extends MiddlewareBase`,仅覆写
  `onModelCall`:`GenerateOptions merged = GenerateOptions.mergeOptions(
  build(currentLevel), input.options())`,再 `new ModelCallInput(...)` 重建后放行。
  `build()` 按 provider 映射:anthropic → `thinkingBudget`(off=不注入,low/medium/
  high ≈ 1024/8192/16384);openai → `reasoningEffort`(off 不注入,low/medium/high
  同名直传);ollama → 不注入任何字段(输出提示仅本地不支持,不报错)。
- 级别存 `AtomicReference<ThinkingLevel>` 于 middleware 实例内,REPL `/thinking x`
  直接 `set`,下一次模型调用即生效——不重建 agent、不改框架。
- 持久化:`config/ModelConfig` 增加可选字段 `thinkingLevel`(枚举字符串,缺省无该键
  等同 off);`/thinking x` 切换时同步写回 `config.json`(经现有 AppConfig 保存路径);
  启动时 `CoderAgentFactory` 从配置读取并作为 middleware 初始值。**备选否决**:只存
  内存(重启丢失,违背用户决策 1)、写系统提示词(框架流式管线无稳定注入点)。

### D2. /clear:直接调 HarnessAgent.clearContext + y/n 确认

- `CoderCli` 捕获 `/clear` → 渲染确认语(列出将清除:对话消息与压缩摘要;将保留:
  会话名/身份、权限配置、工具状态、任务与计划模式;提示 in-flight 调用不受影响也不
  被取消)→ y/a 放行、n 取消。
- 确认后调 `agent.clearContext(ctx)`(框架立即持久化,REPL 无需额外保存)。
  空会话执行时输出"没有可清除的上下文",不算错误。**备选否决**:手工截断 context
  (重复造轮子,且丢失框架的摘要清理与持久化语义)。

### D3. /rewind:自管 checkpoint + 仅回退消息

- **打点**:每个用户轮次的 reasoning 结束后(即本轮工具/回复完成、控制权回到 REPL),
  `CoderCli` 调 `CheckpointManager.save(agent.getAgentState(ctx))`,序列化 JSON 写
  `~/.dl-coder/checkpoints/<sessionId>/<seq>.json`,`seq` 单调递增(目录内最大序号+1)。
  快照语义 = **本轮开始前**的状态:因此打点时机是"用户消息入队前"留存当前上下文,
  回退到第 n 份 = 回到该轮用户消息发出之前。每会话保留 20 份,超出删最旧序号。
- **恢复**:`/rewind` 无参 → 列出 checkpoint 清单(序号、对应用户消息前缀、时间),
  n 取消;`/rewind <n>` → y/n 确认后:
  1. `AgentState snap = CheckpointManager.load(...)` 反序列化;
  2. `AgentState cur = agent.getAgentState(ctx)`;
  3. `cur.contextMutable()` 截断到 `snap.getContext().size()` 条(简单 size 截断,
     避免逐条内容比对;消息前缀一致性校验仅在可比较时提示警告,不阻断);
  4. `cur.setSummary(null)`;
  5. `agent.saveAgentState(ctx)` 写回(走 CAS,不绕过 agent)。
  不重建 agent、不动 permissionContext、不动任务/计划状态。
- **一致性维护**:会话删除(`/session rm`,若既有行为包含)或 `/clear` 成功后,清理
  该会话 checkpoint 目录或打新基线;`/clear` 后旧 checkpoint 仍可回退到"clear 之前"
  是可接受行为(消息前缀校验会提示),v1 不深究。**备选否决**:用框架 TranscriptStore
  (append-only 不可变,无法承载"恢复")、直接改 stateStore 文件(会被 agent 堆内
  副本在下次 CAS 写回时覆盖,产生竞态)。

### D4. ThinkingLevel 枚举与归一

新增 `model/ThinkingLevel`(或置于 config 包):`OFF, LOW, MEDIUM, HIGH`;解析容错
大小写;`/thinking` 无参显示当前级别与可选值;非法值报错并列出可选值。

## Risks / Trade-offs

- [middleware 覆盖与用户显式 options 冲突] → dl-agent 现无其他 options 来源,merge
  primary 胜出语义即"thinking 覆盖一切";文档明示。
- [checkpoint JSON 与框架 AgentState 结构耦合] → 快照只用于本版本框架内回退,框架
  升级如改结构,旧 checkpoint 作废(加载失败时报"无法恢复,快照不兼容",不崩溃)。
- [size 截断误伤(上下文中间发生压缩/摘要替换)] → 恢复前比对快照/当前消息数并提示
  差异;v1 接受轻微不精确,文件类副作用本就不回滚。
- [checkpoint 磁盘占用] → 20 份/会话 + 会话删除时清理;AgentState 快照通常远小于
  完整对话历史的双份。
- [/rewind 在 in-flight 时执行] → REPL 斜杠命令天然串行,与 /clear 同一约束,无需
  额外锁。
- [Windows 路径/编码] → checkpoint 目录经 `~` 展开,JSON 统一 UTF-8,沿用现有
  SessionManager 的处理方式。

## Migration Plan

纯新增,无数据迁移。`config.json` 兼容:旧配置无 `thinkingLevel` 键 = off,不破坏。
回滚 = 删除三条命令注册与两个新类,middleware 未注册时管线行为与现状完全一致。

## Open Questions

- checkpoint 打点是否需要覆盖"工具调用中途"(v1:不需要,只在轮次边界打点)。
- `/clear` 后 checkpoint 目录是清空还是保留(v1:保留,靠前缀校验提示)。
