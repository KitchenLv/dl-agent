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
import io.dl.coder.workspace.WorkspaceInitializer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 交互式 REPL 入口。
 *
 * <p>
 * 会话隔离：每个会话对应一个 sessionId，经 {@link RuntimeContext} 传给 {@code streamEvents}；框架按 (userId, sessionId)
 * 自动持久化并恢复各自的对话状态， 同会话串行、不同会话互不串扰。切换会话只是换一个 sessionId，无需重建 agent。
 *
 * <p>
 * 斜杠命令经注册表分发（{@link #register}）；{@code /model}、{@code /cd} 会重建 agent 实例—— 会话状态存在
 * stateStore 里按 (userId, sessionId) 恢复，重建不丢上下文。
 *
 * <p>
 * 用法：mvn exec:java -Dexec.mainClass=io.dl.coder.cli.CoderCli
 */
public class CoderCli implements AutoCloseable {

    private static final String USER_ID = "dev";
    /** 单轮对话内人工确认的最大轮数，防确认-拒绝-再确认死循环。 */
    private static final int MAX_CONFIRM_ROUNDS = 20;
    /** /memory 展示 MEMORY.md 的尾部行数。 */
    private static final int MEMORY_TAIL_LINES = 30;
    private static final String BANNER = """
            ╭──────────────────────────────╮
            │  DL Coder  ·  AgentScope CLI │
            ╰──────────────────────────────╯""";

    private final PrintStream out;
    private final BufferedReader in;
    private final ConsoleRenderer renderer;
    private final SessionManager sessions;
    private final McpConfig mcp;
    private final AppConfig config;
    private final Path workspace;
    /** 命令注册表（保持注册顺序，/help 按此生成） */
    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();
    private final Map<String, String> helps = new LinkedHashMap<>();

    private String currentSession = SessionManager.DEFAULT_SESSION;
    /** 项目根：execute 的 cwd、代码落点；/cd 可变，变更后重建 agent。 */
    private Path projectDir;
    /** 可变：/model、/cd 重建。会话状态在 stateStore，不随实例销毁。 */
    private HarnessAgent agent;

    public CoderCli(HarnessAgent agent, McpConfig mcp, AppConfig config, Path workspace,
            Path projectDir) {
        this.agent = agent;
        this.mcp = mcp;
        this.config = config;
        this.workspace = workspace;
        this.projectDir = projectDir;
        // 编码跟随控制台（JDK 18+ 的 System.out.charset() 在控制台上返回其代码页，
        // 如 Windows GBK 936）。不要强制 UTF-8：控制台仍是 GBK 时中文会乱码。
        // 想要完整 UTF-8 体验，可先执行 chcp 65001 再启动。
        this.out = System.out;
        this.in = new BufferedReader(
                new InputStreamReader(System.in, System.out.charset()));
        this.renderer = new ConsoleRenderer(this.out);
        this.sessions = SessionManager.createDefault();
        this.sessions.register(currentSession);
        registerCommands();
    }

    public static void main(String[] args) throws IOException {
        Path workspace = Path.of(System.getProperty("user.home"), ".dl-coder", "workspace");
        // 框架不会替我们创建工作区目录；缺目录时文件工具会 "Path does not exist"
        Files.createDirectories(workspace);
        // 首启补齐 AGENTS.md / 技能 / 子代理模板（已存在的不覆盖）
        List<String> created = WorkspaceInitializer.initialize(workspace);
        if (!created.isEmpty()) {
            System.out.println("首次启动，已初始化工作区模板: " + String.join(", ", created));
        }
        AppConfig config = AppConfig.load();
        Path projectDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        HarnessAgent agent = CoderAgentFactory.createAgent(workspace, new Toolkit());
        try (CoderCli cli = new CoderCli(agent, config.getMcp(), config, workspace, projectDir)) {
            cli.run();
        }
        // MCP stdio 子进程的读线程与 Reactor 调度器是非守护线程，2.0.2 的 close() 不终止它们，
        // 正常返回后 JVM 仍挂着——CLI 场景直接强制退出
        System.exit(0);
    }

    @Override
    public void close() {
        // agent 可能已被 /model、/cd 换成新实例，关闭当前那个
        if (agent != null) {
            agent.close();
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
                    if (!dispatch(input)) {
                        out.println("再见。");
                        return;
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
    // 命令注册与分发
    // -----------------------------------------------------------------

    private void register(String name, String help, SlashCommand command) {
        commands.put(name, command);
        helps.put(name, help);
    }

    private void registerCommands() {
        register("/exit", "/exit                       退出（等同 /quit）", args -> false);
        register("/quit", "/quit                       退出", args -> false);
        register("/help", "/help                       查看可用命令", args -> {
            printHelp();
            return true;
        });
        register("/session", "/session [list|new|switch] 会话管理", this::session);
        register("/plan", "/plan on|off|status         计划模式（只读调研 → 人确认 → 执行）", this::plan);
        register("/perm", "/perm [mode]                权限模式（default|explore|accept-edits|dont-ask）",
                this::perm);
        register("/model", "/model [name]               查看/热切换模型（重建 agent，下一轮生效）", this::model);
        register("/cd", "/cd [path]                  查看/切换项目根（重建 agent，execute 的 cwd 跟随）",
                this::cd);
        register("/memory", "/memory                      查看 MEMORY.md 尾部", this::memory);
        register("/skills", "/skills                      列出工作区技能", this::skills);
        register("/mcp", "/mcp                        查看 MCP 服务器", args -> {
            out.println(mcp == null || mcp.isEmpty() ? "未配置 MCP 服务器（config.json 的 mcp 段）"
                    : "MCP 服务器:\n" + mcp.summary());
            return true;
        });
        register("/stats", "/stats                      本进程用量统计（含 token）",
                args -> {
                    out.println(renderer.stats());
                    return true;
                });
    }

    /** @return false 表示退出 REPL */
    private boolean dispatch(String input) {
        String[] parts = input.split("\\s+");
        SlashCommand command = commands.get(parts[0].toLowerCase());
        if (command == null) {
            out.println("未知命令 " + parts[0] + "，/help 查看可用命令");
            return true;
        }
        return command.execute(parts);
    }

    private void printHelp() {
        out.println("命令:");
        helps.values().forEach(out::println);
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
    // 各命令实现
    // -----------------------------------------------------------------

    private boolean session(String[] parts) {
        RuntimeContext ctx = currentContext();
        if (parts.length == 1) {
            out.println("当前会话: " + currentSession + "（plan mode: "
                    + (agent.isPlanModeActive(ctx) ? "开" : "关") + "）");
            return true;
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
        return true;
    }

    private boolean plan(String[] parts) {
        RuntimeContext ctx = currentContext();
        if (parts.length < 2 || parts[1].equals("status")) {
            out.println("计划模式: " + (agent.isPlanModeActive(ctx) ? "开" : "关"));
            return true;
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
        return true;
    }

    private boolean perm(String[] parts) {
        if (parts.length < 2) {
            out.println("当前权限模式: " + agent.getPermissionMode(USER_ID, currentSession));
            out.println("可选: default | explore | accept-edits | dont-ask");
            return true;
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
            return true;
        }
        agent.setPermissionMode(currentContext(), mode);
        out.println("权限模式已切换: " + parts[1]);
        return true;
    }

    /** /model：无参显示当前模型；带参热切换（改内存配置 + 重建 agent，不落盘）。 */
    private boolean model(String[] parts) {
        if (parts.length < 2) {
            out.println("当前模型: " + config.getProviderEnum().toString().toLowerCase() + "/"
                    + config.getModel().getName());
            out.println("切换: /model <模型名>（如 /model glm-5.3-flash；仅本轮进程生效，不写回 config.json）");
            return true;
        }
        String name = parts[1];
        config.getModel().setName(name);
        rebuildAgent();
        out.println("已切换模型 → " + config.getProviderEnum().toString().toLowerCase() + "/" + name
                + "（下一轮生效，会话上下文保留）");
        return true;
    }

    /** /cd：无参显示项目根；带参切换（重建 agent，execute 的 cwd 跟随）。 */
    private boolean cd(String[] parts) {
        if (parts.length < 2) {
            out.println("当前项目根: " + projectDir);
            return true;
        }
        Path target = Path.of(parts[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            out.println("目录不存在: " + target);
            return true;
        }
        projectDir = target;
        rebuildAgent();
        out.println("项目根已切换 → " + projectDir + "（execute 的 cwd 与文件边界跟随，会话上下文保留）");
        return true;
    }

    /** /memory：打印工作区 MEMORY.md 的尾部（长期记忆的最近条目）。 */
    private boolean memory(String[] parts) {
        Path file = workspace.resolve("MEMORY.md");
        if (!Files.isRegularFile(file)) {
            out.println("尚无 MEMORY.md（工作区: " + workspace + "）。agent 通过 memory_save 工具写入后这里可查看。");
            return true;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            int from = Math.max(0, lines.size() - MEMORY_TAIL_LINES);
            out.println("── MEMORY.md 尾部 " + (lines.size() - from) + "/" + lines.size() + " 行 ──");
            for (int i = from; i < lines.size(); i++) {
                out.println(lines.get(i));
            }
        } catch (IOException e) {
            out.println("读取失败: " + e.getMessage());
        }
        return true;
    }

    /** /skills：列出工作区 skills/ 下的技能目录。 */
    private boolean skills(String[] parts) {
        Path dir = workspace.resolve("skills");
        if (!Files.isDirectory(dir)) {
            out.println("尚无技能目录（" + dir + "）。放入 skills/<名字>/SKILL.md 即可被 agent 发现。");
            return true;
        }
        try (Stream<Path> entries = Files.list(dir)) {
            List<String> names = entries.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
            if (names.isEmpty()) {
                out.println("skills/ 为空。放入 skills/<名字>/SKILL.md 即可被 agent 发现。");
            } else {
                out.println("技能: " + String.join(", ", names));
            }
        } catch (IOException e) {
            out.println("读取失败: " + e.getMessage());
        }
        return true;
    }

    // -----------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------

    /** 按当前配置与项目根重建 agent（/model、/cd 用）。旧实例关闭，会话状态在 stateStore 不受影响。 */
    private void rebuildAgent() {
        HarnessAgent old = this.agent;
        this.agent = CoderAgentFactory.createAgent(workspace, new Toolkit(), config, projectDir);
        if (old != null) {
            old.close();
        }
    }

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
        out.println("工作区: ~/.dl-coder/workspace   项目根: " + projectDir + "   输入 /help 查看命令");
        if (mcp != null && !mcp.isEmpty()) {
            out.println("MCP:\n" + mcp.summary());
        }
    }
}
