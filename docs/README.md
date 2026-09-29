# Junzi Coder —— 基于 AgentScope-Java 2.x 的 Coding Agent Demo

> 一个跑在开发者本机的、具备完整工程化能力（工作区/记忆/计划/子代理/权限）的命令行 Coding Agent——对标 Claude Code 的端侧形态，纯单机，不含任何分布式组件。

## 文档导读

| 文档 | 内容 | 读者 |
|---|---|---|
| [01-product-requirements.md](01-product-requirements.md) | **产品需求文档（PRD）**：定位、用户故事、功能需求（P0/P1/P2）、里程碑 M1~M5 与验收标准、风险 | 所有人，先读 |
| [02-technical-design.md](02-technical-design.md) | **技术设计文档**：架构分层、依赖与工程结构、Agent 装配、Plan Mode/子代理/技能/记忆设计、关键流程时序、里程碑实现方案 | 研发 |
| [03-improvement-plan.md](03-improvement-plan.md) | **完善计划**：现状盘点（M1~M5 对照）、差距清单（P0/P1/P2）、实施批次与风险 | 研发 |

## 一图看懂

```
你（终端 REPL）
 └─ HarnessAgent "dl-coder"
     ├─ 工作区 AGENTS.md/skills/subagents   ← 人格、技能、子代理都是 Markdown
     ├─ 工具 ~15 个：read/write/edit/grep/glob/ls + execute + …
     ├─ 上下文管理：压缩 + 大结果卸载        ← 长会话不爆 token
     ├─ Plan Mode：先出计划，人确认再动手
     └─ 文件系统可插拔：本机 ↔ 单机 Docker 容器（一行切换，可选）
```

## 路线图（每个里程碑可独立演示）

- **M1 能干活**：REPL + 工作区 + 文件/命令工具 + 会话持久化（1~2 天）
- **M2 记得住**：压缩 + 卸载 + 长期记忆 + AGENTS.md（1 天）
- **M3 会规划**：Plan Mode + 子代理 + 技能 + 斜杠命令（2 天）
- **M4 够安全**：权限系统（ASK 确认/只读模式）+ 可选 Docker 沙箱（1~2 天）
- **M5 会扩展**（选做）：MCP 工具接入 + `/model` 热切换 + 用量统计

## 快速开始（M1 目标形态）

```bash
export DASHSCOPE_API_KEY=sk-...
mvn exec:java -Dexec.mainClass=io.dl.coder.CoderApplication

You> write hello.txt with a haiku about Java
You> clone https://github.com/owner/repo into the workspace and summarize it
```

## 参考资源

- 本地框架源码：`D:\KitchenLv\agentscope-java-main`
- 官方博客：《Coding Agent 的下半场》（`docs/v2/zh/blogs/agentscope-v2-coding-agent.md`）
- 官方组织级示例：`agentscope-examples/agents/agentscope-codingagent`（仅借鉴其 Agent 装配与工具实现，其分布式派发不在本 demo 范围）
- 框架文档：`docs/v2/zh/docs/`（quickstart / harness/* / building-blocks/*）
