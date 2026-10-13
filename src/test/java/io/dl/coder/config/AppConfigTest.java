package io.dl.coder.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dl.coder.exceptions.ConfigException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 配置加载与校验边界（P2-1）。全部密闭：临时文件 + 仓库内示例文件，不读用户目录、不依赖环境变量。
 */
class AppConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void 配置文件不存在报错() {
        Path missing = tempDir.resolve("no-such-config.json");
        ConfigException e = assertThrows(ConfigException.class, () -> AppConfig.load(missing));
        assertTrue(e.getMessage().contains("配置文件不存在"));
    }

    @Test
    void 缺少model段报错() throws Exception {
        Path file = write("{\"provider\": \"anthropic\"}");
        ConfigException e = assertThrows(ConfigException.class, () -> AppConfig.load(file));
        assertTrue(e.getMessage().contains("不完整"));
    }

    @Test
    void model名为空报错() throws Exception {
        Path file = write("{\"provider\": \"anthropic\", \"model\": {\"name\": \" \", \"apiKey\": \"sk-x\"}}");
        ConfigException e = assertThrows(ConfigException.class, () -> AppConfig.load(file));
        assertTrue(e.getMessage().contains("model.name"));
    }

    @Test
    void 非法厂商报错() throws Exception {
        Path file = write("{\"provider\": \"claude\", \"model\": {\"name\": \"m\", \"apiKey\": \"sk-x\"}}");
        assertThrows(ConfigException.class, () -> AppConfig.load(file));
    }

    @Test
    void mcp段非对象报错() throws Exception {
        Path file = write("{\"provider\": \"anthropic\", \"model\": {\"name\": \"m\", \"apiKey\": \"sk-x\"},"
                + " \"mcp\": \"oops\"}");
        // mcp 段按需惰性解析（getMcp 时才转 McpConfig），校验在那时触发
        AppConfig config = AppConfig.load(file);
        assertThrows(ConfigException.class, () -> config.getMcp());
    }

    @Test
    void 仓库示例配置可解析() {
        // surefire 工作目录 = 项目根；示例文件是占位符 key，校验应通过
        AppConfig config = AppConfig.load(Path.of("config.example.json"));
        assertEquals(ModelProvider.ANTHROPIC, config.getProviderEnum());
        assertEquals("claude-sonnet-5", config.getModel().getName());
    }

    @Test
    void mcp段解析与摘要() throws Exception {
        Path file = write("""
                {"provider": "anthropic", "model": {"name": "m", "apiKey": "sk-x"},
                 "mcp": {"mcpServers": {"fs": {"transport": "http", "url": "http://localhost/mcp"}}}}
                """);
        AppConfig config = AppConfig.load(file);
        assertNotNull(config.getMcp());
        assertTrue(config.getMcp().summary().contains("fs"));
        assertNotNull(config.getMcp().toToolsConfig());
    }

    @Test
    void 无mcp段时getMcp为空() throws Exception {
        Path file = write("{\"provider\": \"anthropic\", \"model\": {\"name\": \"m\", \"apiKey\": \"sk-x\"}}");
        AppConfig config = AppConfig.load(file);
        assertEquals(null, config.getMcp());
    }

    @Test
    void thinkingLevel读写往返() throws Exception {
        Path file = write("{\"provider\": \"anthropic\", \"model\": {\"name\": \"m\", \"apiKey\": \"sk-x\","
                + " \"thinkingLevel\": \"high\"}}");
        AppConfig config = AppConfig.load(file);
        assertEquals(ThinkingLevel.HIGH, config.getModel().getThinkingLevelOrOff());

        // /thinking 切换的写回路径：setThinkingLevel + save，重新加载后生效
        config.getModel().setThinkingLevel(ThinkingLevel.LOW.lowerName());
        config.save();

        AppConfig reloaded = AppConfig.load(file);
        assertEquals(ThinkingLevel.LOW, reloaded.getModel().getThinkingLevelOrOff());
        assertNull(reloaded.getMcp(), "save 写回不应引入多余的 mcp 段");
    }

    private Path write(String json) throws Exception {
        Path file = tempDir.resolve("config.json");
        Files.writeString(file, json);
        return file;
    }
}
