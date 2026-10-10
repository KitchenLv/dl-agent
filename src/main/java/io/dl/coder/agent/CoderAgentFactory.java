package io.dl.coder.agent;

import java.nio.file.Path;
import java.nio.file.Paths;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.dl.coder.config.AppConfig;
import io.dl.coder.config.McpConfig;
import io.dl.coder.config.ModelProvider;
import io.dl.coder.permission.PermissionPolicy;
import io.dl.coder.prompt.SystemPrompt;

public class CoderAgentFactory {

    public static HarnessAgent createAgent(Path workspace, Toolkit toolkit) {
        return createAgent(workspace, toolkit, AppConfig.load(),
                Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize());
    }

    /**
     * 供 /model、/cd 等需要重建 agent 的命令复用：显式传入配置与项目根， 其余装配与默认路径一致。
     * 会话状态按 (userId, sessionId) 存在 stateStore 里，重建后同一会话继续可用。
     */
    public static HarnessAgent createAgent(Path workspace, Toolkit toolkit, AppConfig config,
            Path projectDir) {
        McpConfig mcp = config.getMcp();
        var builder = HarnessAgent.builder().model(buildModel(config)).name("coder-agent")
                .sysPrompt(SystemPrompt.build(projectDir))
                .filesystem(new LocalFilesystemSpec()
                        // project = 目标项目根：作为 overlay 只读下层（读 src 等）+ execute 的 cwd；
                        // projectWritable 让代码文件直接写进项目目录（工作区元数据仍写 workspace）。
                        // 不配的话 ROOTED 策略会把项目绝对路径判为越界 → list_files 等报 ERROR。
                        .project(projectDir)
                        .projectWritable(true)
                        .isolationScope(IsolationScope.SESSION))
                .agentId("coder-agent")
                .compaction(CompactionConfig.builder().triggerMessages(40).keepMessages(15)
                        .flushBeforeCompact(true).build())
                .toolResultEviction(ToolResultEvictionConfig.defaults()).toolkit(toolkit)
                .maxIters(50)
                .permissionContext(PermissionPolicy.buildPermissionContext(projectDir))
                .stateStore(new JsonFileAgentStateStore()).workspace(workspace);
        // 配置了 MCP server 时显式传入；为空则不传，保留 workspace tools.json 的默认加载路径
        if (mcp != null && !mcp.isEmpty()) {
            builder.toolsConfig(mcp.toToolsConfig());
        }
        HarnessAgent agent = builder.build();
        // 内建工具在 build 时才注册进 agent 的 toolkit 副本，之后包上内容级规则匹配
        // （rm -rf/git push → ASK，.env/.ssh → DENY 等，见 PermissionPolicy）
        PermissionPolicy.harden(agent.getToolkit());
        return agent;
    }

    private static Model buildModel(AppConfig config) {
        ModelProvider provider = config.getProviderEnum();
        var model = config.getModel();
        switch (provider) {
            case ANTHROPIC: {
                var builder = AnthropicChatModel.builder().apiKey(model.getApiKey())
                        .modelName(model.getName());
                if (model.getBaseUrl() != null) {
                    builder.baseUrl(model.getBaseUrl());
                }
                return builder.build();
            }
            case OPENAI, OLLAMA, DASHSCOPE: {
                // ollama / dashscope 走 OpenAI 兼容端点；baseUrl 未配置时用厂商默认
                var builder = OpenAIChatModel.builder().apiKey(model.getApiKey())
                        .modelName(model.getName());
                String baseUrl =
                        model.getBaseUrl() != null ? model.getBaseUrl() : provider.defaultCompatibleBaseUrl();
                if (baseUrl != null) {
                    builder.baseUrl(baseUrl);
                }
                return builder.build();
            }
            default:
                throw new IllegalStateException("不支持的模型厂商: " + provider);
        }
    }

}
