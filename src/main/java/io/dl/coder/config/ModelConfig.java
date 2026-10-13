package io.dl.coder.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 模型信息配置。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ModelConfig {

    /** 模型名，例如 claude-sonnet-5、gpt-4o */
    private String name;

    /** API Key；留空时回退读取厂商对应的环境变量（如 ANTHROPIC_API_KEY） */
    private String apiKey;

    /** 可选，自定义 API 地址（代理 / 中转场景） */
    private String baseUrl;

    /** 可选，thinking 深度级别（off/low/medium/high）；缺省或非法值按 off 处理 */
    private String thinkingLevel;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getThinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
    }

    /** 当前生效的 thinking 级别：配置缺省或非法值一律回退 OFF（派生值，不参与序列化）。 */
    @JsonIgnore
    public ThinkingLevel getThinkingLevelOrOff() {
        return ThinkingLevel.parseOrOff(thinkingLevel);
    }
}
