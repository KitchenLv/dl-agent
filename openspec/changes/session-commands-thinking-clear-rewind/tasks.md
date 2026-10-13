# 任务:会话操作三指令(/thinking、/clear、/rewind)

> 前置:与进行中 change `session-lifecycle-project-init` 在 CoderCli 上有实现层交叉,
> 实施前确认其剩余任务(4.2 冒烟)状态,必要时先收尾再动工。

## 1. thinking 级别基础

- [ ] 1.1 新增 `ThinkingLevel` 枚举(off/low/medium/high,大小写容错解析,含非法值报错)
- [ ] 1.2 `config/ModelConfig` 增加可选 `thinkingLevel` 字段;AppConfig 读写兼容(缺省/非法回退 off)
- [ ] 1.3 新建 `middleware/ThinkingMiddleware`(onModelCall + GenerateOptions.mergeOptions 覆盖;AtomicReference 持有当前级别;anthropic→thinkingBudget、openai→reasoningEffort、ollama→不注入)
- [ ] 1.4 单测:级别解析、provider 映射(anthropic/openai/ollama 三 case)、merge 覆盖语义、off 不注入

## 2. /thinking 命令与持久化

- [ ] 2.1 CoderCli 注册 `/thinking`:无参显示当前级别与可选值;带参切换(即时生效 + 写回 config.json);非法值报错
- [ ] 2.2 CoderAgentFactory 从配置读取初始级别装配 middleware
- [ ] 2.3 单测:切换写回配置、启动加载初始级别、ollama 提示文案

## 3. checkpoint 机制

- [ ] 3.1 新建 `cli/CheckpointManager`:快照序列化(JSON/UTF-8)写 `~/.dl-coder/checkpoints/<sessionId>/<seq>.json`、序号递增、读取、20 份上限裁剪
- [ ] 3.2 CoderCli 在每个用户轮次完成后打点(失败仅警告不中断);未对话不打点
- [ ] 3.3 单测:序列化往返、序号递增、裁剪最旧、会话目录隔离、损坏快照读取报错

## 4. /rewind 命令

- [ ] 4.1 CoderCli 注册 `/rewind`:无参列出 checkpoint 清单(序号/消息前缀/时间)+ 选择 + n 取消;`/rewind <n>` 直退;两者均 y/n 确认
- [ ] 4.2 回退执行:getAgentState → contextMutable 截断至快照消息数 → setSummary(null) → saveAgentState;快照不兼容时报错状态不变;成功后回显位置并提示"文件与任务不受影响"
- [ ] 4.3 单测:截断计算、n 非法/超范围报错、回退后上下文不含近 n 轮消息、摘要被清

## 5. /clear 命令

- [ ] 5.1 CoderCli 注册 `/clear`:确认语(清除/保留/in-flight 说明)+ y/n;确认后调 agent.clearContext(ctx);空会话输出"没有可清除的上下文"
- [ ] 5.2 单测:确认/取消分支、空会话提示、清除范围断言(身份/权限/任务状态不变)

## 6. 收尾验证

- [ ] 6.1 `/help` 清单确认包含三条新命令;文档(README/docs)同步补命令说明
- [ ] 6.2 `mvn compile` + 全量 `mvn test` 全绿(hermetic);REPL 手工冒烟:thinking 切换重启保留、clear 后续聊无历史、rewind 两轮后上下文正确
