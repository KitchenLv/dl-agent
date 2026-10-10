package io.dl.coder.config;

import io.dl.coder.exceptions.ConfigException;

/**
 * 支持的模型厂商。
 *
 * <p>
 * ollama / dashscope 走 OpenAI 兼容协议（见 {@code CoderAgentFactory.buildModel}）， 无需各自的官方扩展依赖。
 */
public enum ModelProvider {
    ANTHROPIC,
    OPENAI,
    OLLAMA,
    DASHSCOPE;

    /**
     * 解析配置文件中的厂商名，大小写不敏感；无法识别时抛出异常。
     */
    public static ModelProvider parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigException("配置项 provider 不能为空，可选值: anthropic / openai / ollama / dashscope");
        }
        try {
            return ModelProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConfigException("不支持的模型厂商: " + value + "，可选值: anthropic / openai / ollama / dashscope");
        }
    }

    /** 该厂商默认的 API Key 环境变量名。 */
    public String apiKeyEnvVar() {
        return name() + "_API_KEY";
    }

    /**
     * OpenAI 兼容端点；仅 ollama / dashscope 使用（返回 null 表示走官方 SDK 默认地址）。
     */
    public String defaultCompatibleBaseUrl() {
        return switch (this) {
            case OLLAMA -> "http://localhost:11434/v1";
            case DASHSCOPE -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            default -> null;
        };
    }

    /** 该厂商是否允许无 API Key（ollama 本地服务不校验，填占位符即可）。 */
    public boolean apiKeyOptional() {
        return this == OLLAMA;
    }
}
