package io.dl.coder.permission;

import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 权限规则装配（P0-2）。
 *
 * <p>
 * 三层防线（优先级由框架 PermissionEngine 保证：DENY &gt; ASK &gt; 工具自检 &gt; ALLOW &gt; 模式默认）：
 *
 * <ul>
 * <li><b>no-secrets</b>（DENY）：{@code .env} / {@code .ssh/} / credentials / 私钥文件，读写一律拒绝
 * <li><b>dangerous-shell</b>（ASK）：{@code rm -r*} / {@code git push} / {@code mvn deploy} 等，
 * 走 HITL 人工确认（y/n/a）
 * <li><b>只读与工作区工具</b>（ALLOW）：read/grep/glob/list、web、memory、todo 免确认
 * </ul>
 *
 * 其余调用（write_file / edit_file / execute 等）在 DEFAULT 模式下落到 ASK 人工确认， 用户答复
 * "a" 后由 {@link GuardedTool#generateSuggestions} 生成内容级 ALLOW 规则，同类调用不再询问。
 *
 * <p>
 * 内容级规则依赖 {@link #harden} 先把 execute / 文件工具包成 {@link GuardedTool}（框架注解式工具
 * 缺 matchRule 实现，见其类注释）；工具不存在时（如未注册 MCP 工具）包装自动跳过。
 *
 * <p>
 * 已知边界：grep_files 的 deny 只能拦截显式指向敏感文件的 path 参数， 整目录 grep（path=.）可能带出
 * .env 内容片段——敏感文件本身建议放在项目目录之外。
 */
public final class PermissionPolicy {

    /** 高危 shell 模式（ASK）：递归/强制删除、推送远端、发布构件、硬重置。 */
    public static final String DANGEROUS_SHELL =
            "rm\\s+-\\S*r|rm\\s+-\\S*f|git\\s+push|git\\s+reset\\s+--hard|mvn\\s+deploy";

    /** 敏感文件路径（DENY）：环境变量、SSH 目录、凭证、私钥。 */
    public static final String NO_SECRETS =
            "\\.env(\\.[\\w-]+)?$|\\.ssh[/\\\\]|credential|id_rsa|id_ed25519|\\.pem$";

    /** 按 path 参数匹配规则的工具。 */
    private static final List<String> FILE_TOOLS =
            List.of("read_file", "write_file", "edit_file", "grep_files");

    /** 工具级放行（ruleContent=null）的只读 / 工作区工具。 */
    private static final List<String> SAFE_TOOLS = List.of("read_file", "grep_files", "glob_files",
            "list_files", "web_fetch", "web_search", "memory_search", "memory_get", "memory_save",
            "session_search", "todo_write");

    private PermissionPolicy() {}

    /**
     * 组装权限上下文：规则表 + 项目根作为附加工作目录。
     *
     * @param projectDir 目标项目根（execute 的 cwd、代码落点）
     */
    public static PermissionContextState buildPermissionContext(Path projectDir) {
        String root = projectDir.toAbsolutePath().normalize().toString();
        PermissionContextState.Builder b = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .addWorkingDirectory(root, new AdditionalWorkingDirectory(root, "app"));
        for (String tool : FILE_TOOLS) {
            b.addDenyRule(tool, new PermissionRule(tool, NO_SECRETS, PermissionBehavior.DENY, "app"));
        }
        b.addAskRule("execute",
                new PermissionRule("execute", DANGEROUS_SHELL, PermissionBehavior.ASK, "app"));
        for (String tool : SAFE_TOOLS) {
            b.addAllowRule(tool, new PermissionRule(tool, null, PermissionBehavior.ALLOW, "app"));
        }
        return b.build();
    }

    /**
     * 给 agent 已注册的内建工具包上内容级规则匹配。须在 {@code HarnessAgent.build()} 之后调用
     * （内建工具在 build 时才注册进 agent 自己的 toolkit 副本）。
     */
    public static void harden(Toolkit toolkit) {
        wrap(toolkit, "execute", in -> str(in.get("command")));
        for (String tool : FILE_TOOLS) {
            wrap(toolkit, tool, in -> str(in.get("path")));
        }
    }

    private static void wrap(Toolkit toolkit, String name,
            Function<Map<String, Object>, String> extractor) {
        AgentTool existing = toolkit.getTool(name);
        if (existing instanceof ToolBase tb && !(existing instanceof GuardedTool)) {
            toolkit.registerAgentTool(new GuardedTool(tb, extractor));
        }
    }

    private static String str(Object value) {
        return value instanceof String s ? s : null;
    }
}
 
