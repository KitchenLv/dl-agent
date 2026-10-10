# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

DL Coder — a local, single-machine CLI coding agent demo built on **AgentScope-Java 2.x** (`io.agentscope:agentscope-harness`). Think "mini Claude Code in a terminal": REPL, file/command tools, session persistence, plan mode, permission modes, optional MCP tools. Java 26, Maven, no distributed components.

Code comments, Javadoc, system prompt, and UI strings are all in **Chinese** — keep that convention in new code.

## Commands

```bash
mvn compile                              # build
mvn test                                 # all tests
mvn test -Dtest=AppConfigTest            # single test class
mvn test -Dtest=AppConfigTest#pathLoad   # single test method
mvn exec:java                            # run the REPL (mainClass configured in pom)
```

- `mvn test` is fully hermetic (36 tests, no config, no network). `AgentTest` is the only live test — opt in with `DL_LIVE_TEST=true` plus a working `~/.dl-coder/config.json`.
- Jackson versions are pinned to **2.21.x** on purpose: the harness pulls `jackson-dataformat-yaml:2.21.1` transitively, and an older `jackson-core` (e.g. 2.17) breaks subagent frontmatter parsing with `NoSuchMethodError` — it manifests as a *silent hang* mid-turn.
- On Windows consoles the REPL follows the console code page (often GBK); run `chcp 65001` before starting for full UTF-8 output.
- Ignore `docs/README.md`'s `io.dl.coder.CoderApplication` reference — the actual entry point is `io.dl.coder.cli.CoderCli`.

## Runtime configuration

The agent reads `~/.dl-coder/config.json` at startup (override the path with env var `DL_CODER_CONFIG`):

```json
{
  "provider": "anthropic",
  "model": { "name": "...", "apiKey": "...", "baseUrl": "..." },
  "mcp": { "mcpServers": { "...": {} } }
}
```

- `provider`: `anthropic`, `openai`, `ollama` (default `http://localhost:11434/v1`, API key optional), or `dashscope` (default compatible-mode endpoint) — ollama/dashscope go through `OpenAIChatModel`; missing `model.apiKey` falls back to `<PROVIDER>_API_KEY`.
- `baseUrl` points the provider client at a compatible gateway.
- `mcp` is optional; all string values support `${ENV_VAR}` expansion (undefined vars fail fast at startup). On Windows, bare stdio commands like `npx` are auto-suffixed `.cmd` if resolvable on PATH (ProcessBuilder doesn't do PATHEXT resolution; without this the server fails to start *silently* — the framework only logs via SLF4J, and this app has no SLF4J provider). MCP tools register under their **plain names** (`list_directory`), not `mcp__{server}__{tool}`, and same-named tools overwrite the built-ins in the registry.
- The repo-root `config.example.json` is a placeholder sample consumed by `AppConfigTest`; the app itself never reads it (and real `config.json` is gitignored).

## Architecture

Flow: `CoderCli.main` → `CoderAgentFactory.createAgent` → REPL loop.

- **`cli/CoderCli`** — REPL entry. Plain input becomes a `UserMessage` streamed via `agent.streamEvents(...)` with a `RuntimeContext(userId, sessionId)`; slash commands dispatch through a `SlashCommand` registry (`/session`, `/plan`, `/perm`, `/model`, `/cd`, `/memory`, `/skills`, `/mcp`, `/stats`; `/help` is generated from it). `/model` and `/cd` rebuild the agent in place (`rebuildAgent()`) — session state lives in the state store keyed by (userId, sessionId), so it survives rebuilds; `main` ends with `System.exit(0)` because MCP stdio reader threads are non-daemon and 2.0.2's `close()` doesn't stop them. HITL confirmation loop: an ASK decision ends the event stream with a `RequireUserConfirmEvent`; the CLI renders the pending calls, collects `y/n/a`, and resumes by sending a new message carrying `List<ConfirmResult>` under `Msg.METADATA_CONFIRM_RESULTS`.
- **`agent/CoderAgentFactory`** — the single assembly point for `HarnessAgent`: model built by provider switch, `SystemPrompt`, `LocalFilesystemSpec` (project dir = read-overlay + writable target + execute cwd; workspace = `~/.dl-coder/workspace` holds memory/skills/session state), compaction + tool-result eviction (long-session context management), `JsonFileAgentStateStore`, and MCP `toolsConfig` when configured. Calls `PermissionPolicy.harden(agent.getToolkit())` after build.
- **`permission/`** — `PermissionPolicy` assembles the permission context (secrets → DENY, dangerous shell → ASK, read-only tools → ALLOW, project root as working directory); `GuardedTool` wraps harness built-ins to add `matchRule` (ruleContent regex vs command/path arg — framework annotation tools lack it, so content-level rules never match without the wrapper) and content-precise "always allow" suggestions. Note: the 2.0.2 framework rebuilds the `PermissionEngine` from AgentState on every call, so `CoderCli.persistAllowRules` writes "a" rules back via `replacePermissionContext`.
- **`cli/SessionManager`** — only a session *name registry* (`~/.dl-coder/sessions.json`; DTO fields must stay visible to Jackson — private fields silently serialize to `{}`, a bug the tests caught). Conversation state is persisted per `(userId, sessionId)` by the framework's state store; switching sessions is just changing the sessionId string — no agent rebuild. Same session is serialized across processes — an orphaned JVM holding the same session's lock makes new turns hang, so kill stale `mvn exec:java` processes before smoke-testing.
- **`workspace/`** — `WorkspaceInitializer` copies first-boot templates from classpath `workspace-templates/` (AGENTS.md workspace rules, `skills/commit-convention`, `subagents/reviewer.md`) into the workspace, never overwriting existing files. Subagent declarations live at `workspace/subagents/*.md` (YAML frontmatter: `description` required, optional `tools` allowlist; filename = agent id; spawn via `agent_spawn`/`agent_send`, each gated by HITL).
- **`cli/ConsoleRenderer`** — maps the `streamEvents` event stream to terminal output: streaming text deltas, dim one-line tool status (tool name at start, arg-JSON summary at result), per-turn and process-lifetime usage stats including tokens from `MODEL_CALL_END` (`/stats`). Note: compatible gateways may not return `ChatUsage` in streams — counts then stay 0.
- **`config/`** — `AppConfig` (load / validate / env-var fallback), `ModelProvider` enum, `McpConfig` (maps the JSON `mcp` section to the framework's `ToolsConfig`; tool naming becomes `mcp__{server}__{tool}`).
- **`prompt/SystemPrompt`** — sectioned Chinese system prompt. Workspace/project rules (AGENTS.md, MEMORY.md, skills) are injected each turn by the framework's WorkspaceContextMiddleware, so they are deliberately absent from this prompt.

Most agent capabilities (tools, plan mode, permission system, compaction, MCP wiring, state persistence) come from the **agentscope-harness** dependency, not this repo — when behavior surprises you, look there. Framework source for reference: `D:\KitchenLv\agentscope-java-main`. Design docs (PRD, technical design, improvement plan, in Chinese): `docs/`.
