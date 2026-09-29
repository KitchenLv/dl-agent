package io.dl.coder.cli;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.dl.coder.agent.CoderAgentFactory;
import io.dl.coder.config.AppConfig;
import io.dl.coder.config.McpConfig;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 交互式 REPL 入口。
 *
 * <p>
 * 会话隔离：每个会话对应一个 sessionId，经 {@link RuntimeContext} 传给 {@code streamEvents}；框架按 (userId, sessionId)
 * 自动持久化并恢复各自的对话状态， 同会话串行、不同会话互不串扰。切换会话只是换一个 sessionId，无需重建 agent。
 *
 * <p>
 * 用法：mvn exec:java -Dexec.mainClass=io.dl.coder.cli.CoderCli
 */
public class CoderCli {

    private static final String USER_ID = "dev";
    /** 单轮对话内人工确认的最大轮数，防确认-拒绝-再确认死循环。 */
    private static final int MAX_CONFIRM_ROUNDS = 20;
    private static final String BANNER = """
            ╭──────────────────────────────╮
            │  DL Coder  ·  AgentScope CLI │
            ╰──────────────────────────────╯""";

    private final HarnessAgent agent;
    private final ConsoleRenderer renderer;
    private final SessionManager sessions;
    private final McpConfig mcp;
    private final PrintStream out;
    private final BufferedReader in;

    private String currentSession = SessionManager.DEFAULT_SESSION;

    public CoderCli(HarnessAgent agent, McpConfig mcp) {
        this.agent = agent;
        this.mcp = mcp;
        // 编码跟随控制台（JDK 18+ 的 System.out.charset() 在控制台上返回其代码页，
        // 如 Windows GBK 936）。不要强制 UTF-8：控制台仍是 GBK 时中文会乱码。
        // 想要完整 UTF-8 体验，可先执行 chcp 65001 再启动。
        this.out = System.out;
        this.in = new BufferedReader(
                new InputStreamReader(System.in, System.out.charset()));
        this.renderer = new ConsoleRenderer(this.out);
        this.sessions = SessionManager.createDefault();
        this.sessions.register(currentSession);
    }

    public static void main(String[] args) throws IOException {
        Path workspace = Path.of(System.getProperty("user.home"), ".dl-coder", "workspace");
        // 框架不会替我们创建工作区目录；缺目录时文件工具会 "Path does not exist"
        java.nio.file.Files.createDirectories(workspace);
        AppConfig config = AppConfig.load();
        HarnessAgent agent = CoderAgentFactory.createAgent(workspace, new Toolkit());
        try (agent) {
            new CoderCli(agent, config.getMcp()).run();
        }
    }

    public void run() {
        printBanner();
        while (true) {
            out.print("[" + currentSession + "] you> ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                break;
            }
            if (line == null) { // Ctrl+D / 流结束
                break;
            }
            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            try {
                if (input.startsWith("/")) {
                    if (!handleCommand(input)) {
                        return; // /exit
                    }
                } else {
                    chat(input);
                }
            } catch (Exception e) {
                out.println("出错: " + e.getMessage());
            }
        }
        out.println("再见。");
    }

    // -----------------------------------------------------------------
    // 对话
    // -----------------------------------------------------------------

    private void chat(String input) {
        RuntimeContext ctx = currentContext();
        renderer.beginTurn();
        try {
            Msg msg = new UserMessage(input);
            int rounds = 0;
            while (true) {
                // ASK 决策时事件流在 RequireUserConfirmEvent 后结束，恢复方式是
                // 把 List<ConfirmResult> 放进下一条消息的 metadata 再发一轮
                AtomicReference<RequireUserConfirmEvent> pending = new AtomicReference<>();
                agent.streamEvents(msg, ctx).doOnNext(event -> {
                    renderer.onEvent(event);
                    if (event instanceof RequireUserConfirmEvent confirmEvent) {
                        pending.set(confirmEvent);
                    }
                }).blockLast();
                if (pending.get() == null) {
                    return;
                }
                if (++rounds > MAX_CONFIRM_ROUNDS) {
                    out.println("确认轮数过多，本轮中止（会话状态保留，可继续对话）");
                    return;
                }
                msg = resumeWithConfirmResults(pending.get());
            }
        } finally {
            renderer.endTurn();
        }
    }

    // -----------------------------------------------------------------
    // HITL 人工确认（P0-1）
    // -----------------------------------------------------------------

    /** 渲染待确认调用，收集 y/n/a 决策，组装携带 ConfirmResult 的恢复消息。 */
    private UserMessage resumeWithConfirmResults(RequireUserConfirmEvent event) {
        List<ConfirmResult> results = new ArrayList<>();
        List<PermissionRule> acceptedRules = new ArrayList<>();
        List<ToolUseBlock> calls = event.getToolCalls();
        for (int i = 0; i < calls.size(); i++) {
            ToolUseBlock call = calls.get(i);
            out.println("  ⏸ [" + (i + 1) + "/" + calls.size() + "] " + call.getName() + " "
                    + summarize(call.getInput()));
            switch (askConfirm()) {
                case "y" -> {
                    results.add(new ConfirmResult(true, call));
                    out.println("    → 本次允许");
                }
                case "a" -> {
                    List<PermissionRule> rules = alwaysAllowRules(call);
                    results.add(new ConfirmResult(true, call, rules));
                    acceptedRules.addAll(rules);
                    out.println("    → 已允许，同类调用（" + call.getName() + "）今后不再询问");
                }
                default -> {
                    // 2.0.2 的 ConfirmResult 无拒绝原因参数，模型会看到默认 "Permission denied
                    // by user" 的工具结果
                    results.add(new ConfirmResult(false, call));
                    out.println("    → 已拒绝");
                }
            }
        }
        persistAllowRules(acceptedRules);
        Map<String, Object> meta = new HashMap<>();
        meta.put(Msg.METADATA_CONFIRM_RESULTS, results);
        return UserMessage.builder().textContent("confirmed").metadata(meta).build();
    }

    /**
     * 把 "a" 接受的规则写回会话的权限上下文。框架（2.0.2）每次调用都从 AgentState 重建
     * PermissionEngine，ConfirmResult 里的规则只进当轮引擎实例；不写回 state 的话 下一轮同样的调用会再次询问。
     */
    private void persistAllowRules(List<PermissionRule> newRules) {
        if (newRules.isEmpty()) {
            return;
        }
        ReActAgent delegate = agent.getDelegate();
        PermissionContextState current =
                delegate.getAgentState(USER_ID, currentSession).getPermissionContext();
        PermissionContextState.Builder b = PermissionContextState.builder().mode(current.getMode());
        current.getWorkingDirectories().forEach(b::addWorkingDirectory);
        current.getDenyRules().forEach((tool, rules) -> rules.forEach(r -> b.addDenyRule(tool, r)));
        current.getAskRules().forEach((tool, rules) -> rules.forEach(r -> b.addAskRule(tool, r)));
        current.getAllowRules().forEach((tool, rules) -> rules.forEach(r -> b.addAllowRule(tool, r)));
        for (PermissionRule rule : newRules) {
            boolean exists = current.getAllowRules().getOrDefault(rule.toolName(), List.of())
                    .stream().anyMatch(r -> sameContent(r, rule));
            if (!exists) {
                b.addAllowRule(rule.toolName(), rule);
            }
        }
        delegate.replacePermissionContext(USER_ID, currentSession, b.build());
    }

    private static boolean sameContent(PermissionRule a, PermissionRule b) {
        return a.toolName().equals(b.toolName())
                && Objects.equals(a.ruleContent(), b.ruleContent());
    }

    /** 提示 y/n/a；回车与 Ctrl+D 视为拒绝。 */
    private String askConfirm() {
        out.print("    允许? [y=允许 n=拒绝 a=始终允许]（回车=n）: ");
        out.flush();
        String line;
        try {
            line = in.readLine();
        } catch (IOException e) {
            line = null;
        }
        if (line == null) {
            return "n";
        }
        return switch (line.strip().toLowerCase()) {
            case "", "n", "no" -> "n";
            case "y", "yes" -> "y";
            case "a", "always" -> "a";
            default -> "n"; // 无法识别的输入按拒绝处理，宁可保守
        };
    }

    /**
     * "始终允许"的规则：优先取工具自己的建议（GuardedTool 会精确到本次命令/路径文本， 避免一条 "a"
     * 放行整个工具），查不到工具时退化为工具级放行。
     */
    private List<PermissionRule> alwaysAllowRules(ToolUseBlock call) {
        AgentTool tool = agent.getToolkit().getTool(call.getName());
        Map<String, Object> input = call.getInput() == null ? Map.of() : call.getInput();
        if (tool instanceof ToolBase base) {
            return base.generateSuggestions(input);
        }
        return List.of(new PermissionRule(call.getName(), null, PermissionBehavior.ALLOW,
                "suggested"));
    }

    /** 调用参数的单行摘要（截断）。 */
    private static String summarize(Map<String, Object> input) {
        String text = input == null ? "{}" : input.toString();
        return text.length() > 160 ? text.substring(0, 160) + "…" : text;
    }

    // -----------------------------------------------------------------
    // 斜杠命令
    // -----------------------------------------------------------------

    /** @return false 表示退出 REPL */
    private boolean handleCommand(String input) {
        String[] parts = input.split("\\s+");
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "/exit", "/quit" -> {
                return false;
            }
            case "/help" -> printHelp();
            case "/session" -> session(parts);
            case "/plan" -> plan(parts);
            case "/perm" -> perm(parts);
            case "/mcp" -> {
                out.println(mcp == null || mcp.isEmpty() ? "未配置 MCP 服务器（config.json 的 mcp 段）"
                        : "MCP 服务器:\n" + mcp.summary());
            }
            case "/stats" -> out.println(renderer.stats());
            default -> out.println("未知命令 " + cmd + "，/help 查看可用命令");
        }
        return true;
    }

    private void printHelp() {
        out.println("""
                命令:
                  /session                    查看当前会话
                  /session list               列出全部会话
                  /session new <名字>          新建并切换
                  /session switch <名字>       切换（不存在则创建）
                  /plan on|off|status         计划模式（只读调研 → 人确认 → 执行）
                  /perm default|explore|accept-edits|dont-ask   权限模式
                  /mcp                        查看 MCP 服务器
                  /stats                      本进程用量统计
                  /exit                       退出""");
    }

    private void session(String[] parts) {
        RuntimeContext ctx = currentContext();
        if (parts.length == 1) {
            out.println("当前会话: " + currentSession + "（plan mode: "
                    + (agent.isPlanModeActive(ctx) ? "开" : "关") + "）");
            return;
        }
        switch (parts[1]) {
            case "list" -> {
                out.println("会话列表:");
                sessions.names().forEach(
                        name -> out.println("  " + (name.equals(currentSession) ? "* " : "  ")
                                + name + "  (" + sessions.createdAt(name) + ")"));
            }
            case "new" -> {
                requireName(parts, "/session new <名字>");
                sessions.register(parts[2]);
                currentSession = parts[2];
                out.println("已新建并切换到会话: " + currentSession);
            }
            case "switch" -> {
                requireName(parts, "/session switch <名字>");
                sessions.register(parts[2]); // 切到不存在的名字 = 新建（状态为空，从头开始）
                currentSession = parts[2];
                out.println("已切换到会话: " + currentSession);
            }
            default -> out.println("用法: /session [list|new <名字>|switch <名字>]");
        }
    }

    private void plan(String[] parts) {
        RuntimeContext ctx = currentContext();
        if (parts.length < 2 || parts[1].equals("status")) {
            out.println("计划模式: " + (agent.isPlanModeActive(ctx) ? "开" : "关"));
            return;
        }
        switch (parts[1]) {
            case "on" -> {
                agent.enterPlanMode(ctx);
                out.println("已进入计划模式：只读调研，产出计划经确认后才会动手改代码");
            }
            case "off" -> {
                agent.exitPlanMode(ctx);
                out.println("已退出计划模式（程序化退出不触发人工确认）");
            }
            default -> out.println("用法: /plan on|off|status");
        }
    }

    private void perm(String[] parts) {
        if (parts.length < 2) {
            out.println("当前权限模式: " + agent.getPermissionMode(USER_ID, currentSession));
            out.println("可选: default | explore | accept-edits | dont-ask");
            return;
        }
        PermissionMode mode = switch (parts[1]) {
            case "default" -> PermissionMode.DEFAULT;
            case "explore" -> PermissionMode.EXPLORE;
            case "accept-edits" -> PermissionMode.ACCEPT_EDITS;
            case "dont-ask" -> PermissionMode.DONT_ASK;
            default -> null;
        };
        if (mode == null) {
            out.println("未知模式: " + parts[1] + "（可选 default | explore | accept-edits | dont-ask）");
            return;
        }
        agent.setPermissionMode(currentContext(), mode);
        out.println("权限模式已切换: " + parts[1]);
    }

    // -----------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------

    private RuntimeContext currentContext() {
        return RuntimeContext.builder().userId(USER_ID).sessionId(currentSession).build();
    }

    private void requireName(String[] parts, String usage) {
        if (parts.length < 3 || parts[2].isBlank()) {
            throw new IllegalArgumentException("缺少会话名，用法: " + usage);
        }
    }

    private void printBanner() {
        out.println(BANNER);
        out.println("工作区: ~/.dl-coder/workspace   输入 /help 查看命令");
        if (mcp != null && !mcp.isEmpty()) {
            out.println("MCP:\n" + mcp.summary());
        }
    }
}
