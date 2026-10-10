package io.dl.coder.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.dl.coder.exceptions.ConfigException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 服务器配置，作为 {@code ~/.dl-coder/config.json} 中与 {@code model} 同级的
 * {@code mcp} 段（单一配置文件，不单独拆文件）：
 *
 * <pre>
 * {
 *   "provider": "anthropic",
 *   "model": { "name": "claude-sonnet-5", "apiKey": "sk-..." },
 *   "mcp": {
 *     "mcpServers": {
 *       "github": {
 *         "transport": "http",
 *         "url": "https://api.example.com/mcp/",
 *         "headers": { "Authorization": "Bearer ${GITHUB_MCP_TOKEN}" }
 *       },
 *       "filesystem": {
 *         "transport": "stdio",
 *         "command": "npx",
 *         "args": ["-y", "@modelcontextprotocol/server-filesystem", "."],
 *         "enableTools": ["read_file", "write_file"]
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>server 条目采用标准 MCP 客户端配置形状，字段直接映射框架的
 * {@link McpServerConfig}；{@code transport} 为判别字段（stdio / sse / http）。
 *
 * <p>所有字符串值支持 {@code ${ENV_VAR}} 环境变量展开（url / headers / env / command /
 * args），便于 API Key 不落盘；引用未定义环境变量时启动即报错。
 *
 * <p>经 {@link #toToolsConfig()} 交给 {@code HarnessAgent.builder().toolsConfig(...)}，
 * 由框架统一完成连接与工具注册（工具命名 {@code mcp__{server}__{tool}}）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpConfig {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** server 名 → 连接配置（保持声明顺序） */
    private final Map<String, McpServerConfig> mcpServers;

    /** Jackson 反序列化入口 */
    @SuppressWarnings("unused")
    private McpConfig() {
        this.mcpServers = new LinkedHashMap<>();
    }

    public Map<String, McpServerConfig> getMcpServers() {
        return mcpServers;
    }

    // -----------------------------------------------------------------
    //  解析（AppConfig 反序列化时，把 mcp 段的原始 JSON 树转成 server 表）
    // -----------------------------------------------------------------

    /** 从 {@code mcp} 段的 JSON 树解析并校验；段缺失或为 null 时返回 null（可选项）。 */
    public static McpConfig parse(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new ConfigException("配置项 mcp 必须是对象");
        }
        JsonNode servers = node.get("mcpServers");
        if (servers == null || servers.isNull()) {
            return new McpConfig();
        }
        if (!servers.isObject()) {
            throw new ConfigException("配置项 mcp.mcpServers 必须是对象");
        }
        McpConfig config = new McpConfig();
        servers.fields().forEachRemaining(entry -> {
            String name = entry.getKey();
            McpServerConfig server;
            try {
                server = MAPPER.treeToValue(entry.getValue(), McpServerConfig.class);
            } catch (IOException e) {
                throw new ConfigException("MCP 服务器配置不合法: " + name, e);
            }
            config.mcpServers.put(name, validateAndExpand(name, server));
        });
        return config;
    }

    private static McpServerConfig validateAndExpand(String name, McpServerConfig server) {
        String transport = server.getTransport();
        if (transport == null || transport.isBlank()) {
            throw new ConfigException("MCP 服务器 [" + name + "] 缺少 transport 字段");
        }
        switch (transport) {
            case "stdio" -> {
                if (server.getCommand() == null || server.getCommand().isBlank()) {
                    throw new ConfigException(
                            "MCP 服务器 [" + name + "] transport=stdio 需要 command 字段");
                }
            }
            case "sse", "http" -> {
                if (server.getUrl() == null || server.getUrl().isBlank()) {
                    throw new ConfigException("MCP 服务器 [" + name + "] transport="
                            + transport + " 需要 url 字段");
                }
            }
            default -> throw new ConfigException("MCP 服务器 [" + name + "] transport 不支持: "
                    + transport + "（可选 stdio / sse / http）");
        }
        try {
            if (server.getCommand() != null) {
                server.setCommand(windowsCommandFix(expand(server.getCommand())));
            }
            server.setArgs(expandList(server.getArgs()));
            server.setEnv(expandMap(server.getEnv()));
            if (server.getUrl() != null) {
                server.setUrl(expand(server.getUrl()));
            }
            server.setHeaders(expandMap(server.getHeaders()));
            server.setQueryParams(expandMap(server.getQueryParams()));
        } catch (EnvVarMissingException e) {
            throw new ConfigException(
                    "MCP 服务器 [" + name + "] " + e.getMessage());
        }
        return server;
    }

    /**
     * Windows 下 stdio 进程由 ProcessBuilder 直接拉起，不做 PATHEXT 解析—— 写 "npx" 找不到
     * npx.cmd，server 注册静默失败（框架只打日志不阻断启动）。这里给无扩展名的命令 补 ".cmd"（存在则用，
     * 否则保持原样）。
     */
    private static String windowsCommandFix(String command) {
        if (!IS_WINDOWS || command == null || command.isBlank()) {
            return command;
        }
        Path path = Path.of(command);
        if (path.getNameCount() != 1 || command.lastIndexOf('.') >= 0) {
            // 绝对/相对路径或已带扩展名：不动
            return command;
        }
        String cmd = command + ".cmd";
        return resolvesOnPath(cmd) ? cmd : command;
    }

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    /** 在 PATH 上按 PATHEXT 语义找可执行文件（cmd 也覆盖 PATHEXT.COM 的场景）。 */
    private static boolean resolvesOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                if (Files.isRegularFile(Path.of(dir, executable))) {
                    return true;
                }
            } catch (InvalidPathException ignored) {
                // PATH 里的非法条目跳过
            }
        }
        return false;
    }

    // -----------------------------------------------------------------
    //  ${ENV_VAR} 展开
    // -----------------------------------------------------------------

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private static String expand(String value) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        Matcher m = ENV_VAR.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = System.getenv(m.group(1));
            if (val == null || val.isBlank()) {
                throw new EnvVarMissingException("引用了未定义的环境变量 ${" + m.group(1) + "}");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static List<String> expandList(List<String> list) {
        if (list == null) {
            return null;
        }
        List<String> out = new ArrayList<>(list.size());
        for (String s : list) {
            out.add(expand(s));
        }
        return out;
    }

    private static Map<String, String> expandMap(Map<String, String> map) {
        if (map == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, expand(v)));
        return out;
    }

    private static final class EnvVarMissingException extends RuntimeException {
        EnvVarMissingException(String message) {
            super(message);
        }
    }

    // -----------------------------------------------------------------
    //  转换与查询
    // -----------------------------------------------------------------

    /** 转为框架 ToolsConfig；未配置 server 时返回 null（保留 workspace tools.json 的默认加载路径）。 */
    public ToolsConfig toToolsConfig() {
        if (mcpServers.isEmpty()) {
            return null;
        }
        ToolsConfig config = new ToolsConfig();
        config.setMcpServers(mcpServers);
        return config;
    }

    public boolean isEmpty() {
        return mcpServers.isEmpty();
    }

    /** 摘要：每行一个 server（名称 + transport），用于 /mcp 命令与启动日志。 */
    public String summary() {
        if (mcpServers.isEmpty()) {
            return "（未配置 MCP 服务器，可在 ~/.dl-coder/config.json 的 mcp.mcpServers 中添加）";
        }
        StringBuilder sb = new StringBuilder();
        new TreeSet<>(mcpServers.keySet())
                .forEach(k -> sb.append("- ").append(k).append(" (")
                        .append(mcpServers.get(k).getTransport()).append(")\n"));
        return sb.toString().stripTrailing();
    }
}
