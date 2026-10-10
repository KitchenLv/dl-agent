package io.dl.coder.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.dl.coder.exceptions.ConfigException;
import org.junit.jupiter.api.Test;

/** P1-4 验收：厂商枚举扩展与默认兼容端点。 */
class ModelProviderTest {

    @Test
    void 解析四个厂商_大小写不敏感() {
        assertEquals(ModelProvider.ANTHROPIC, ModelProvider.parse("anthropic"));
        assertEquals(ModelProvider.OPENAI, ModelProvider.parse("OpenAI"));
        assertEquals(ModelProvider.OLLAMA, ModelProvider.parse("ollama"));
        assertEquals(ModelProvider.DASHSCOPE, ModelProvider.parse(" DashScope "));
    }

    @Test
    void 非法厂商报错() {
        assertThrows(ConfigException.class, () -> ModelProvider.parse("claude"));
        assertThrows(ConfigException.class, () -> ModelProvider.parse(""));
        assertThrows(ConfigException.class, () -> ModelProvider.parse(null));
    }

    @Test
    void 兼容端点与key约定() {
        assertEquals("http://localhost:11434/v1", ModelProvider.OLLAMA.defaultCompatibleBaseUrl());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1",
                ModelProvider.DASHSCOPE.defaultCompatibleBaseUrl());
        assertNull(ModelProvider.ANTHROPIC.defaultCompatibleBaseUrl());
        assertTrue(ModelProvider.OLLAMA.apiKeyOptional());
        assertEquals("DASHSCOPE_API_KEY", ModelProvider.DASHSCOPE.apiKeyEnvVar());
    }
}
