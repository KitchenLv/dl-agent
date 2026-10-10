# DL Coder（dl-agent）

跑在开发者本机的命令行 Coding Agent——基于 [AgentScope-Java 2.x](https://github.com/agentscope-ai/agentscope-java)（`agentscope-harness`），对标 Claude Code 的端侧形态：REPL、文件/命令工具、会话持久化、计划模式、权限系统（y/n/a 人工确认）、MCP 工具接入。纯单机，无分布式组件。

## 快速开始

```bash
# 1. 配置模型（~/.dl-coder/config.json，参考 config.example.json；ollama 可零成本本地跑）
cp config.example.json ~/.dl-coder/config.json   # 填入你的 apiKey

# 2. 启动 REPL
mvn exec:java

# 3. 首启会自动初始化工作区模板（AGENTS.md / 技能 / reviewer 子代理）
You> 给 GuardedTool 补几个边界测试
You> 用 agent_spawn 委派 reviewer 审查这次改动      # 子代理演示
```

常用命令:`/help` `/session` `/plan` `/perm` `/model` `/cd` `/memory` `/skills` `/stats` `/mcp`。

构建与测试:

```bash
mvn compile                 # 编译
mvn test                    # 全部测试（密闭，无需配置与网络）
DL_LIVE_TEST=true mvn test -Dtest=AgentTest   # 真实模型联调（需配置）
```

## 配置

`~/.dl-coder/config.json`(可用环境变量 `DL_CODER_CONFIG` 指到别处):

| 厂商 | provider | 说明 |
|---|---|---|
| Anthropic | `anthropic` | `baseUrl` 可指向兼容网关 |
| OpenAI | `openai` | 同上 |
| Ollama | `ollama` | 默认 `http://localhost:11434/v1`,apiKey 可不填 |
| 通义 | `dashscope` | 默认 compatible-mode 端点 |

`mcp.mcpServers` 段可选,支持 stdio/sse/http 三种 transport,字符串值支持 `${ENV_VAR}` 展开(避免密钥落盘)。

## Windows 已知限制

本项目在 Windows 上开发,已知问题与绕行方式:

| 问题 | 现象 | 绕行 |
|---|---|---|
| **跨盘符绝对路径** | 工作区在 `C:`、项目在 `D:` 时,`list_files`/`glob_files` 等传另一盘的绝对路径报 `'other' has different root`(上游 `Path.relativize` 限制) | 传相对路径,或用 `execute` 跑 `ls`/`rg` |
| **控制台中文乱码** | REPL 编码跟随控制台代码页,默认 GBK 936 | 启动前 `chcp 65001`;或在 Windows Terminal / Git Bash 里跑 |
| **MCP stdio 命令** | `command: "npx"` 这类裸命令 `ProcessBuilder` 不解析 `.cmd` | 本项目已自动补 `.cmd` 后缀;自定义命令建议写全 `npx.cmd` 或绝对路径 |

推荐用 **Git Bash + Windows Terminal**,`execute` 走的是 `cmd`(`cd /d x && cmd` 形式拼装),复杂命令注意引号差异。

## 文档

- [产品需求](docs/01-product-requirements.md) · [技术设计](docs/02-technical-design.md) · [完善计划/进度台账](docs/03-improvement-plan.md)
