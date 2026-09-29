package io.dl.coder.config;

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
}
