package io.dl.coder.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * P0-2 验收：规则匹配（命中 / 不命中 / 优先级）与工具包装，全部离线（不依赖模型）。
 */
class PermissionPolicyTest {

    @Test
    void 危险命令命中Ask规则() {
        GuardedTool execute = guardedExecute();
        for (String command : new String[] {"rm -rf /tmp/x", "rm -r target", "git push origin main",
                "cd /d proj && mvn deploy", "git reset --hard HEAD~1"}) {
            assertTrue(execute.matchRule(PermissionPolicy.DANGEROUS_SHELL, Map.of("command", command)),
                    "应命中: " + command);
        }
    }

    @Test
    void 普通命令不命中Ask规则() {
        GuardedTool execute = guardedExecute();
        for (String command : new String[] {"mvn -q compile", "git status", "ls -la", "rm notes.md",
                "git log -S UserService"}) {
            assertFalse(execute.matchRule(PermissionPolicy.DANGEROUS_SHELL, Map.of("command", command)),
                    "不应命中: " + command);
        }
    }

    @Test
    void 敏感路径命中Deny规则() {
        GuardedTool readFile = guardedReadFile();
        for (String path : new String[] {".env", "config/.env", ".env.local", "~/.ssh/config",
                "credentials.json", "server/id_rsa", "cert.pem"}) {
            assertTrue(readFile.matchRule(PermissionPolicy.NO_SECRETS, Map.of("path", path)),
                    "应命中: " + path);
        }
    }

    @Test
    void 普通路径不命中Deny规则() {
        GuardedTool readFile = guardedReadFile();
        for (String path : new String[] {"src/Main.java", "docs/环境说明.md", "pom.xml",
                "src/main/java/EnvParser.java"}) {
            assertFalse(readFile.matchRule(PermissionPolicy.NO_SECRETS, Map.of("path", path)),
                    "不应命中: " + path);
        }
    }

    @Test
    void 优先级_Deny压过Allow_敏感文件读被拒() {
        PermissionEngine engine = engine();
        // read_file 同时挂了 ALLOW(null) 与 DENY(no-secrets)：.env 必须被拒，普通文件放行
        assertEquals(PermissionBehavior.DENY, decide(engine, "read_file", Map.of("path", "config/.env")));
        assertEquals(PermissionBehavior.ALLOW, decide(engine, "read_file", Map.of("path", "src/A.java")));
    }

    @Test
    void 优先级_Ask压过模式默认_高危命令仍需确认() {
        PermissionEngine engine = engine();
        // "git push" 命中 ASK；安全命令未命中任何规则 → DEFAULT 模式默认 ASK（走 HITL）
        assertEquals(PermissionBehavior.ASK, decide(engine, "execute", Map.of("command", "git push origin main")));
        assertEquals(PermissionBehavior.ASK, decide(engine, "execute", Map.of("command", "mvn -q test")));
    }

    @Test
    void 只读工具免确认() {
        PermissionEngine engine = engine();
        for (String tool : new String[] {"read_file", "grep_files", "glob_files", "list_files",
                "web_fetch", "todo_write"}) {
            assertEquals(PermissionBehavior.ALLOW, decide(engine, tool, Map.of("path", "src/A.java")),
                    tool + " 应放行");
        }
    }

    @Test
    void 始终允许建议精确到命令文本() {
        GuardedTool execute = guardedExecute();
        List<PermissionRule> rules = execute.generateSuggestions(Map.of("command", "mvn -q compile"));
        assertEquals(1, rules.size());
        assertEquals("execute", rules.get(0).toolName());
        assertEquals("mvn -q compile", rules.get(0).ruleContent());
        assertEquals(PermissionBehavior.ALLOW, rules.get(0).behavior());
        // 建议按内容级规则回匹配：同命令命中，不同命令不命中
        assertTrue(execute.matchRule(rules.get(0).ruleContent(), Map.of("command", "mvn -q compile")));
        assertFalse(execute.matchRule(rules.get(0).ruleContent(), Map.of("command", "rm -rf /")));
    }

    @Test
    void 建议规则不能越过Ask规则() {
        PermissionEngine engine = engine();
        // 模拟用户对 "mvn -q compile" 答了 a：内容级 ALLOW 规则入表
        engine.addRule(new PermissionRule("execute", "mvn -q compile", PermissionBehavior.ALLOW, "session"));
        // 同类命令免确认，含危险模式的命令仍被 ASK 拦下
        assertEquals(PermissionBehavior.ALLOW, decide(engine, "execute", Map.of("command", "mvn -q compile")));
        assertEquals(PermissionBehavior.ASK,
                decide(engine, "execute", Map.of("command", "mvn -q compile && git push")));
    }

    @Test
    void harden包装内建工具且保留元信息() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new FakeTool("execute", false, "command"));
        toolkit.registerAgentTool(new FakeTool("read_file", true, "path"));
        PermissionPolicy.harden(toolkit);
        assertInstanceOf(GuardedTool.class, toolkit.getTool("execute"));
        assertInstanceOf(GuardedTool.class, toolkit.getTool("read_file"));
        assertEquals("execute", toolkit.getTool("execute").getName());
        assertTrue(toolkit.getTool("read_file").isReadOnly());
        // 包装后内容级匹配可用
        assertTrue(((ToolBase) toolkit.getTool("execute")).matchRule(PermissionPolicy.DANGEROUS_SHELL,
                Map.of("command", "rm -rf /")));
        // 未注册的工具跳过，不抛异常
        PermissionPolicy.harden(toolkit); // 幂等：已是 GuardedTool 不再重复包
    }

    // -----------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------

    private static PermissionEngine engine() {
        PermissionContextState context =
                PermissionPolicy.buildPermissionContext(Path.of(".").toAbsolutePath().normalize());
        return new PermissionEngine(context);
    }

    private static PermissionBehavior decide(PermissionEngine engine, String tool,
            Map<String, Object> input) {
        // 引擎评估的是包装后的工具（matchRule 语义），与 agent 内真实路径一致
        ToolBase wrapped = "execute".equals(tool) ? guardedExecute() : guardedReadFile();
        if (!tool.equals(wrapped.getName())) {
            wrapped = new GuardedTool(new FakeTool(tool, true, "path"), in -> str(in.get("path")));
        }
        PermissionDecision decision = engine.checkPermission(wrapped, input).block();
        return decision == null ? null : decision.getBehavior();
    }

    private static GuardedTool guardedExecute() {
        return new GuardedTool(new FakeTool("execute", false, "command"), in -> str(in.get("command")));
    }

    private static GuardedTool guardedReadFile() {
        return new GuardedTool(new FakeTool("read_file", true, "path"), in -> str(in.get("path")));
    }

    private static String str(Object value) {
        return value instanceof String s ? s : null;
    }

    /** 最小 ToolBase 桩：只提供元信息，不可调用。 */
    private static final class FakeTool extends ToolBase {
        FakeTool(String name, boolean readOnly, String param) {
            super(name, "test tool", Map.of("type", "object", "properties", Map.of(param, Map.of())),
                    readOnly, true, false, null, false, false);
        }

        @Override
        public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
            return Mono.error(new UnsupportedOperationException("test stub"));
        }
    }
}
