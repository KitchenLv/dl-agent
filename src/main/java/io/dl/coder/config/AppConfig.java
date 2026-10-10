package io.dl.coder.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dl.coder.exceptions.ConfigException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 应用配置，从用户目录下的 ~/.dl-coder/config.json 读取。
 * <p>
 * 可通过环境变量 DL_CODER_CONFIG 指定其他配置文件路径。
 * 
 * <pre>
 * {
 *   "provider": "anthropic",
 *   "model": {
 *     "name": "claude-sonnet-5",
 *     "apiKey": "sk-...",
 *     "baseUrl": "https://api.anthropic.com"
 *   },
 *   "mcp": {
 *     "mcpServers": {
 *       "github": {
 *         "transport": "http",
 *         "url": "https://api.example.com/mcp/",
 *         "headers": { "Authorization": "Bearer ${GITHUB_MCP_TOKEN}" }
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>{@code mcp} 为可选段，结构见 {@link McpConfig}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {

    /** 配置文件默认所在目录名 */
    public static final String CONFIG_DIR = ".dl-coder";
    /** 配置文件名 */
    public static final String CONFIG_FILE = "config.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelProvider provider;
    private ModelConfig model;
    /** mcp 段以原始 JSON 保存，按需解析为 McpConfig（见 {@link #getMcp()}） */
    private JsonNode mcp;

    private transient McpConfig parsedMcp;

    /**
     * 从用户目录加载配置；文件不存在时抛出 ConfigException 并提示创建。
     */
    public static AppConfig load() {
        return load(defaultConfigPath());
    }

    /**
     * 从指定路径加载配置。
     */
    public static AppConfig load(Path path) {
        if (!Files.exists(path)) {
            throw new ConfigException("配置文件不存在: " + path
                    + "，请按以下示例创建: {\"provider\": \"anthropic\", \"model\": {\"name\": \"claude-sonnet-5\", \"apiKey\": \"sk-...\"}}");
        }
        AppConfig config;
        try {
            config = MAPPER.readValue(Files.readString(path), AppConfig.class);
        } catch (IOException e) {
            throw new ConfigException("解析配置文件失败: " + path, e);
        }
        config.validateAndFill(path);
        return config;
    }

    /** 默认配置文件路径 ~/.dl-coder/config.json，可用 DL_CODER_CONFIG 覆盖。 */
    public static Path defaultConfigPath() {
        String override = System.getenv("DL_CODER_CONFIG");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        return Path.of(System.getProperty("user.home"), CONFIG_DIR, CONFIG_FILE);
    }

    private void validateAndFill(Path path) {
        if (provider == null || model == null) {
            throw new ConfigException("配置不完整（provider / model 均为必填）: " + path);
        }
        if (model.getName() == null || model.getName().isBlank()) {
            throw new ConfigException("配置项 model.name 不能为空: " + path);
        }
        // apiKey 未配置时回退到环境变量，如 ANTHROPIC_API_KEY / OPENAI_API_KEY
        if (model.getApiKey() == null || model.getApiKey().isBlank()) {
            model.setApiKey(System.getenv(provider.apiKeyEnvVar()));
        }
        if (model.getApiKey() == null || model.getApiKey().isBlank()) {
            if (provider.apiKeyOptional()) {
                // ollama 等本地服务不校验 key，OpenAI 客户端又要求非空，填占位符
                model.setApiKey("ollama");
            } else {
                throw new ConfigException(
                        "缺少 API Key: 请在配置文件中设置 model.apiKey，" + "或设置环境变量 " + provider.apiKeyEnvVar());
            }
        }
    }

    public ModelProvider getProviderEnum() {
        return provider;
    }

    public ModelConfig getModel() {
        return model;
    }

    /** 解析后的 MCP 配置；未配置 mcp 段时返回 null（可选项）。 */
    public McpConfig getMcp() {
        if (parsedMcp == null) {
            parsedMcp = McpConfig.parse(mcp);
        }
        return parsedMcp;
    }

    // Jackson 反序列化使用 String -> ModelProvider

    public String getProvider() {
        return provider == null ? null : provider.name().toLowerCase();
    }

    public void setProvider(String provider) {
        this.provider = ModelProvider.parse(provider);
    }

    public void setModel(ModelConfig model) {
        this.model = model;
    }

    public void setMcp(JsonNode mcp) {
        this.mcp = mcp;
    }
}
