package io.dl.coder.config;

import io.dl.coder.exceptions.ConfigException;

/**
 * 支持的模型厂商。
 */
public enum ModelProvider {
    ANTHROPIC,
    OPENAI;

    /**
     * 解析配置文件中的厂商名，大小写不敏感；无法识别时抛出异常。
     */
    public static ModelProvider parse(String value) {
        if (value == null || value.isBlank()) {
            throw new ConfigException("配置项 provider 不能为空，可选值: anthropic / openai");
        }
        try {
            return ModelProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConfigException("不支持的模型厂商: " + value + "，可选值: anthropic / openai");
        }
    }

    /** 该厂商默认的 API Key 环境变量名。 */
    public String apiKeyEnvVar() {
        return name() + "_API_KEY";
    }
}
