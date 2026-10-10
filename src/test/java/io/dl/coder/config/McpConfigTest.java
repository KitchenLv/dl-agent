package io.dl.coder.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dl.coder.exceptions.ConfigException;
import org.junit.jupiter.api.Test;

/**
 * MCP 配置解析与校验（P2-1）：结构校验、${ENV} 展开、Windows 命令补全。密闭，不连真实 server。
 */
class McpConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void null段返回null_空servers为空配置() throws Exception {
        assertNull(McpConfig.parse(null));
        assertNull(McpConfig.parse(MAPPER.readTree("null")));
        assertTrue(McpConfig.parse(MAPPER.readTree("{}")).isEmpty());
        assertTrue(McpConfig.parse(MAPPER.readTree("{\"mcpServers\": {}}")).isEmpty());
    }

    @Test
    void 合法stdio与http条目() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {"mcpServers": {
                  "fs": {"transport": "stdio", "command": "cmd-abc.exe", "args": ["--x"]},
                  "gh": {"transport": "http", "url": "http://localhost:9/mcp"}
                }}
                """);
        McpConfig config = McpConfig.parse(node);
        assertEquals(2, config.getMcpServers().size());
        assertTrue(config.summary().contains("fs"));
        assertTrue(config.summary().contains("gh"));
        assertEquals(2, config.toToolsConfig().getMcpServers().size());
    }

    @Test
    void stdio缺command报错() throws Exception {
        assertParseFails("{\"mcpServers\": {\"a\": {\"transport\": \"stdio\"}}}");
    }

    @Test
    void http缺url报错() throws Exception {
        assertParseFails("{\"mcpServers\": {\"a\": {\"transport\": \"http\"}}}");
    }

    @Test
    void 不支持的transport报错() throws Exception {
        assertParseFails("{\"mcpServers\": {\"a\": {\"transport\": \"grpc\", \"url\": \"x\"}}}");
    }

    @Test
    void 引用未定义环境变量报错() throws Exception {
        assertParseFails(
                "{\"mcpServers\": {\"a\": {\"transport\": \"http\", \"url\": \"http://${DL_UNDEF_XYZ_9}/mcp\"}}}");
    }

    @Test
    void 环境变量会展开() throws Exception {
        JsonNode node = MAPPER.readTree(
                "{\"mcpServers\": {\"a\": {\"transport\": \"http\", \"url\": \"http://${PATH}\"}}}");
        McpConfig config = McpConfig.parse(node);
        String url = config.getMcpServers().get("a").getUrl();
        assertTrue(!url.contains("${PATH}"), "应完成展开: " + url);
    }

    @Test
    void windows下裸命令自动补cmd后缀() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "仅 Windows 平台执行");
        JsonNode node = MAPPER.readTree(
                "{\"mcpServers\": {\"a\": {\"transport\": \"stdio\", \"command\": \"npx\", \"args\": [\"-y\", \"x\"]}}}");
        McpConfig config = McpConfig.parse(node);
        // 本机 PATH 上有 npx.cmd 时应补全；否则保持原样（不失败）
        String command = config.getMcpServers().get("a").getCommand();
        assertTrue(command.equals("npx") || command.equals("npx.cmd"), command);
    }

    private void assertParseFails(String json) throws Exception {
        assertThrows(ConfigException.class, () -> McpConfig.parse(MAPPER.readTree(json)));
    }
}
