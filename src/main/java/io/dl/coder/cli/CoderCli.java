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
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.dl.coder.agent.CoderAgentFactory;
import io.dl.coder.config.AppConfig;
import io.dl.coder.config.McpConfig;
import io.dl.coder.config.ModelProvider;
import io.dl.coder.config.ThinkingLevel;
import io.dl.coder.exceptions.ConfigException;
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
 * 斜杠命令经注册表分发（{@link #register}）；{@code /model}、{@code /cd} 会重建 agent 实例—— 会话状态存在 stateStore 里按
 * (userId, sessionId) 恢复，重建不丢上下文。
 *
 * <p>
 * 用法：mvn exec:java -Dexec.mainClass=io.dl.coder.cli.CoderCli
 */
public class CoderCli implements AutoCloseable {

    private static final String USER_ID = "dl";
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
    /** 会话 checkpoint 存储（/rewind 用），每轮对话结束后自动打点 */
    private final CheckpointManager checkpoints = new CheckpointManager();
    /** thinking 级别共享引用：与 agent 内的 ThinkingMiddleware 同一实例，/thinking 切换即时生效 */
    private final AtomicReference<ThinkingLevel> thinkingLevel;
    /** 命令注册表（保持注册顺序，/help 按此生成） */
    private final Map<String, SlashCommand> commands = new LinkedHashMap<>();
    private final Map<String, String> helps = new LinkedHashMap<>();

    /** 未物化会话的提示符占位（启动即生成随机 sessionId，首条消息才注册落盘）。 */
    private static final String FRESH_SESSION_LABEL = "新会话";

    private String currentSession;
    /** 项目根：execute 的 cwd、代码落点；/cd 可变，变更后重建 agent。 */
    private Path projectDir;
    /** 可变：/model、/cd 重建。会话状态在 stateStore，不随实例销毁。 */
    private HarnessAgent agent;

    public CoderCli(HarnessAgent agent, McpConfig mcp, AppConfig config, Path workspace,
            Path projectDir, AtomicReference<ThinkingLevel> thinkingLevel) {
        this.agent = agent;
        this.mcp = mcp;
        this.config = config;
        this.workspace = workspace;
        this.projectDir = projectDir;
        this.thinkingLevel = thinkingLevel;
        // 编码跟随控制台（JDK 18+ 的 System.out.charset() 在控制台上返回其代码页，
        // 如 Windows GBK 936）。不要强制 UTF-8：控制台仍是 GBK 时中文会乱码。
        // 想要完整 UTF-8 体验，可先执行 chcp 65001 再启动。
        this.out = System.out;
        this.in = new BufferedReader(new InputStreamReader(System.in, System.out.charset()));
        this.renderer = new ConsoleRenderer(this.out);
        this.sessions = SessionManager.createDefault();
        // eager id + lazy 注册：启动即生成随机 sessionId 供 RuntimeContext（/plan、/perm 等
        // 会话级命令在未物化时也能正常作用），但不写注册表——首条用户消息才物化，未聊天
        // 退出零残留
        this.currentSession = sessions.randomName();
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
        // 传入已加载的 config 与 projectDir：两参重载内部会再 load 一次，那份实例与
        // CLI 持有的 config 不是同一对象，两者会静默失同步
        // thinking 级别共享引用：CLI（/thinking）与 agent（ThinkingMiddleware）经它同步
        AtomicReference<ThinkingLevel> thinkingLevel =
                new AtomicReference<>(config.getModel().getThinkingLevelOrOff());
        HarnessAgent agent = CoderAgentFactory.createAgent(workspace, new Toolkit(), config,
                projectDir, thinkingLevel);
        try (CoderCli cli = new CoderCli(agent, config.getMcp(), config, workspace, projectDir,
                thinkingLevel)) {
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

    /** 当前提示符显示名：未物化显示占位，物化后为会话名。 */
    private String sessionLabel() {
        return sessions.exists(currentSession) ? currentSession : FRESH_SESSION_LABEL;
    }

    public void run() {
        printBanner();
        while (true) {
            out.print("[" + sessionLabel() + "] you> ");
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
        register("/session", "/session [list|new|switch] 会话管理（new 可不带名字=随机名）", this::session);
        register("/init", "/init                        初始化项目 AGENTS.md（agent 分析项目生成）", this::init);
        register("/plan", "/plan on|off|status         计划模式（只读调研 → 人确认 → 执行）", this::plan);
        register("/perm", "/perm [mode]                权限模式（default|explore|accept-edits|dont-ask|bypass）",
                this::perm);
        register("/model", "/model [name]               查看/热切换模型（重建 agent，下一轮生效）", this::model);
        register("/cd", "/cd [path]                  查看/切换项目根（重建 agent，execute 的 cwd 跟随）",
                this::cd);
        register("/memory", "/memory                      查看 MEMORY.md 尾部", this::memory);
        register("/skills", "/skills                      列出技能（个人 workspace + 项目 .dl-coder/skills）",
                this::skills);
        register("/mcp", "/mcp                        查看 MCP 服务器", args -> {
            out.println(mcp == null || mcp.isEmpty() ? "未配置 MCP 服务器（config.json 的 mcp 段）"
                    : "MCP 服务器:\n" + mcp.summary());
            return true;
        });
        register("/stats", "/stats                      本进程用量统计（含 token）", args -> {
            out.println(renderer.stats());
            return true;
        });
        register("/clear", "/clear                      清空当前会话对话上下文（需确认，/rewind 可回退）",
                this::clear);
        register("/rewind", "/rewind [n]                回退 n 轮对话（无参列出 checkpoint 交互选择）",
                this::rewind);
        register("/thinking",
                "/thinking [off|low|medium|high] 查看/切换 thinking 深度（写入 config.json）",
                this::thinking);
    }

    /** @return false 表示退出 REPL */
    private boolean dispatch(String input) {
        String[] parts = input.split("\\s+");
        SlashCommand command = commands.get(parts[0].toLowerCase());
        if (command == null) {
            // 未知命令兜底为技能名：/<skill-name> [参数] 显式执行技能（注册命令优先，不冲突）
            String skillName = parts[0].substring(1);
            Path skillFile = resolveSkillFile(skillName);
            if (skillFile != null) {
                return executeSkill(skillName, input.substring(parts[0].length()).strip(),
                        skillFile);
            }
            out.println("未知命令 " + parts[0] + "，/help 查看可用命令");
            return true;
        }
        return command.execute(parts);
    }

    private void printHelp() {
        out.println("命令:");
        helps.values().forEach(out::println);
        out.println("技能: /<技能名> [参数]        直接执行技能（/skills 查看可用技能）");
    }

    // -----------------------------------------------------------------
    // 对话
    // -----------------------------------------------------------------

    private void chat(String input) {
        // 首条用户消息物化会话：此前 sessionId 已存在但未注册（eager id + lazy 注册）
        if (!sessions.exists(currentSession)) {
            sessions.register(currentSession);
            out.println("已创建会话: " + currentSession);
        }
        if (agent == null) {
            // rebuildAgent() 先关旧再建新，重建失败时 agent 为 null（失败原因已由 run() 打印）
            out.println("agent 当前不可用（上次重建失败），请用 /model <模型名> 或 /cd <路径> 重试");
            return;
        }
        RuntimeContext ctx = currentContext();
        // 轮次开始前快照 agent 状态：本轮结束后落盘，/rewind 即可回退到本轮之前的状态。
        // 快照失败只警告不阻断对话（spec：打点失败仅提示）
        String snapshotJson = null;
        try {
            snapshotJson = agent.getDelegate().getAgentState(USER_ID, currentSession).toJson();
        } catch (RuntimeException e) {
            out.println("（checkpoint 打点失败，本轮结束后无法 /rewind: " + e.getMessage() + "）");
        }
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
            // 控制权回到 REPL 即视为本轮完成（含确认中断与异常路径）：落盘轮前快照
            if (snapshotJson != null) {
                try {
                    checkpoints.save(currentSession, snapshotJson, input);
                } catch (IOException e) {
                    out.println("（checkpoint 落盘失败: " + e.getMessage() + "）");
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // HITL 人工确认（P0-1）
    // -----------------------------------------------------------------

    /** 渲染待确认调用，收集 y/n/a/all 决策，组装携带 ConfirmResult 的恢复消息。 */
    private UserMessage resumeWithConfirmResults(RequireUserConfirmEvent event) {
        List<ConfirmResult> results = new ArrayList<>();
        List<PermissionRule> acceptedRules = new ArrayList<>();
        List<ToolUseBlock> calls = event.getToolCalls();
        boolean allowRest = false;
        for (int i = 0; i < calls.size(); i++) {
            ToolUseBlock call = calls.get(i);
            out.println("  ⏸ [" + (i + 1) + "/" + calls.size() + "] " + call.getName() + " "
                    + summarize(call.getInput()));
            String answer = allowRest ? "y" : askConfirm();
            if ("all".equals(answer)) {
                allowRest = true;
                answer = "y";
                out.println("    → 本轮剩余调用全部允许（仅本次，不落规则）");
            }
            switch (answer) {
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
     * 把 "a" 接受的规则写回会话的权限上下文。框架（2.0.2）每次调用都从 AgentState 重建 PermissionEngine，ConfirmResult
     * 里的规则只进当轮引擎实例；不写回 state 的话 下一轮同样的调用会再次询问。
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
        current.getAllowRules()
                .forEach((tool, rules) -> rules.forEach(r -> b.addAllowRule(tool, r)));
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

    /** 提示 y/n/a/all；回车与 Ctrl+D 视为拒绝。 */
    private String askConfirm() {
        out.print("    允许? [y=允许 n=拒绝 a=始终允许 all=剩余全允许]（回车=n）: ");
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
            case "all" -> "all";
            default -> "n"; // 无法识别的输入按拒绝处理，宁可保守
        };
    }

    /**
     * "始终允许"的规则：优先取工具自己的建议（GuardedTool：命令工具按程序名集合、文件工具精确到
     * 本次路径，避免一条 "a" 放行整个工具），查不到工具时退化为工具级放行。
     */
    private List<PermissionRule> alwaysAllowRules(ToolUseBlock call) {
        AgentTool tool = agent.getToolkit().getTool(call.getName());
        Map<String, Object> input = call.getInput() == null ? Map.of() : call.getInput();
        if (tool instanceof ToolBase base) {
            return base.generateSuggestions(input);
        }
        return List.of(
                new PermissionRule(call.getName(), null, PermissionBehavior.ALLOW, "suggested"));
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
            boolean started = sessions.exists(currentSession);
            out.println(
                    "当前会话: " + (started ? currentSession : "（未开始，首条消息后将创建 " + currentSession + "）")
                            + "（plan mode: " + (agent.isPlanModeActive(ctx) ? "开" : "关") + "）");
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
                // 无参 = 随机名；带参 = 指定名。均为显式创建，立即注册
                String name =
                        parts.length >= 3 && !parts[2].isBlank() ? parts[2] : sessions.randomName();
                sessions.register(name);
                currentSession = name;
                out.println("已新建并切换到会话: " + currentSession);
            }
            case "switch" -> {
                requireName(parts, "/session switch <名字>");
                sessions.register(parts[2]); // 切到不存在的名字 = 新建（状态为空，从头开始）
                currentSession = parts[2];
                out.println("已切换到会话: " + currentSession);
            }
            default -> out.println("用法: /session [list|new [名字]|switch <名字>]");
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
            out.println("可选: default | explore | accept-edits | dont-ask | bypass");
            return true;
        }
        PermissionMode mode = switch (parts[1]) {
            case "default" -> PermissionMode.DEFAULT;
            case "explore" -> PermissionMode.EXPLORE;
            case "accept-edits" -> PermissionMode.ACCEPT_EDITS;
            case "dont-ask" -> PermissionMode.DONT_ASK;
            case "bypass" -> PermissionMode.BYPASS;
            default -> null;
        };
        if (mode == null) {
            out.println("未知模式: " + parts[1] + "（可选 default | explore | accept-edits | dont-ask | bypass）");
            return true;
        }
        agent.setPermissionMode(currentContext(), mode);
        out.println("权限模式已切换: " + parts[1]);
        if (mode == PermissionMode.BYPASS) {
            out.println("（bypass 仅把默认档改为放行；no-secrets DENY 与危险命令 ASK 规则仍然生效）");
        }
        return true;
    }

    /** /init 的罐头提示词：走 chat 管线由 agent 分析项目并生成/合并 AGENTS.md。 */
    private static final String INIT_PROMPT = """
            请分析当前项目并生成项目根目录的 AGENTS.md（若已存在，先读旧内容，在其基础上
            合并更新，不要推倒重写、不要丢失既有内容）。要求：

            1. 先调研：读构建文件（pom.xml/package.json 等）、目录结构、入口类，必要时检索代码
            2. 产出中文的 AGENTS.md，包含：
               - 构建与测试命令（常用目标、单测运行方式）
               - 架构概览（模块划分、关键入口、核心数据流）
               - 代码约定（语言/风格/注释/提交规范等项目中实际在用的）
            3. 只写事实：命令必须真实可跑、结构必须与代码一致，不确定的宁可不写
            4. 写入项目根的 AGENTS.md（相对路径 AGENTS.md）

            完成后简要汇报生成了哪些内容。""";

    /** /init：初始化项目规约（AGENTS.md），参考 Claude Code 的 /init。 */
    private boolean init(String[] parts) {
        out.println("正在初始化项目规约（AGENTS.md）…");
        chat(INIT_PROMPT);
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
        String previous = config.getModel().getName();
        config.getModel().setName(name);
        try {
            rebuildAgent();
        } catch (Exception e) {
            // 重建失败回滚显示名，避免 /model 显示一个从未生效的模型
            config.getModel().setName(previous);
            throw e;
        }
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
        // 相对路径相对当前项目根解析（resolve 遇绝对参数会直接返回该参数）；
        // 不能 toAbsolutePath() 裸转——它锚定 JVM 进程 CWD（从不变化），连续 /cd 会锚错基准
        Path target = projectDir.resolve(parts[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(target)) {
            out.println("目录不存在: " + target);
            return true;
        }
        Path previous = projectDir;
        projectDir = target;
        try {
            rebuildAgent();
        } catch (Exception e) {
            // 重建失败回滚项目根，与 /model 的回滚保持一致
            projectDir = previous;
            throw e;
        }
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

    /** /skills：双来源列出技能——workspace 个人技能 + 项目 .dl-coder/skills 项目技能。 */
    private boolean skills(String[] parts) {
        List<String> personal = skillNames(workspace.resolve("skills"));
        List<String> project = skillNames(projectDir.resolve(".dl-coder").resolve("skills"));
        if (personal.isEmpty() && project.isEmpty()) {
            out.println("暂无技能。个人技能放 ~/.dl-coder/workspace/skills/<名字>/SKILL.md，"
                    + "项目技能放 <项目根>/.dl-coder/skills/<名字>/SKILL.md。");
            return true;
        }
        personal.forEach(name -> out.println("  (个人) " + name));
        project.forEach(name -> out.println("  (项目) " + name));
        out.println("提示: 输入 /<技能名> [参数] 可直接执行技能");
        return true;
    }

    /** 列出一个技能根目录下的技能子目录名（目录不存在/读取失败返回空列表）。 */
    private static List<String> skillNames(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 按名字解析技能的 SKILL.md：项目 .dl-coder/skills 优先于个人 workspace/skills， 找不到时忽略大小写再扫一遍。未命中返回
     * null。
     */
    private Path resolveSkillFile(String name) {
        for (Path root : List.of(projectDir.resolve(".dl-coder").resolve("skills"),
                workspace.resolve("skills"))) {
            Path exact = root.resolve(name).resolve("SKILL.md");
            if (Files.isRegularFile(exact)) {
                return exact;
            }
            Path loose = skillFileIgnoreCase(root, name);
            if (loose != null) {
                return loose;
            }
        }
        return null;
    }

    /** 在技能根目录下忽略大小写匹配技能子目录，返回其 SKILL.md（无匹配返回 null）。 */
    private static Path skillFileIgnoreCase(Path root, String name) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                    .map(p -> p.resolve("SKILL.md")).filter(Files::isRegularFile).findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * /&lt;skill-name&gt;：显式执行技能——把 SKILL.md 内容注入 prompt 走普通对话管线， 模型按技能说明自行使用工具完成任务。
     */
    private boolean executeSkill(String name, String args, Path skillFile) {
        String content;
        try {
            content = Files.readString(skillFile);
        } catch (IOException e) {
            out.println("读取技能失败: " + e.getMessage());
            return true;
        }
        out.println("执行技能: " + name + (args.isEmpty() ? "" : "（参数: " + args + "）"));
        String prompt = "用户通过 /" + name + " 显式调用技能，请严格按下方技能说明执行"
                + (args.isEmpty() ? "。" : "，用户参数：" + args)
                + "\n\n<skill name=\"" + name + "\">\n" + content + "\n</skill>";
        chat(prompt);
        return true;
    }

    // -----------------------------------------------------------------
    // 会话管理指令（P1-3 /clear、/rewind、/thinking）
    // -----------------------------------------------------------------

    /** /clear：确认后清空当前会话对话上下文（框架内部完成持久化）。 */
    private boolean clear(String[] args) {
        if (args.length > 1) {
            out.println("/clear 不接受参数（需要回退请用 /rewind）");
            return true;
        }
        if (agent == null || !sessions.exists(currentSession)) {
            out.println("当前会话还没有对话内容，无需清除。");
            return true;
        }
        AgentState state = agent.getDelegate().getAgentState(USER_ID, currentSession);
        String summary = state.getSummary();
        boolean empty = state.getContext().isEmpty()
                && (summary == null || summary.isBlank());
        if (empty) {
            out.println("当前会话还没有对话内容，无需清除。");
            return true;
        }
        out.println("将清空会话 " + currentSession + " 的对话上下文：");
        out.println("  - 清除: " + state.getContext().size() + " 条消息"
                + ((summary == null || summary.isBlank()) ? "" : " 与压缩摘要"));
        out.println("  - 保留: checkpoint 历史（/rewind 可找回）与文件改动、任务/计划状态");
        if (!confirm("确认清空? (y/N): ")) {
            out.println("已取消，未做任何改动。");
            return true;
        }
        agent.clearContext(currentContext());
        out.println("已清空当前会话上下文。");
        return true;
    }

    /** /rewind [n]：对话回退 n 轮（无参交互选择 checkpoint）；只动对话消息，不回退文件改动。 */
    private boolean rewind(String[] args) {
        if (agent == null || !sessions.exists(currentSession)) {
            out.println("当前会话还没有对话历史，无法回退。");
            return true;
        }
        List<CheckpointManager.CheckpointInfo> all = checkpoints.list(currentSession);
        if (all.isEmpty()) {
            out.println("该会话还没有 checkpoint（每轮对话结束后自动打点），无法回退。");
            return true;
        }
        int seq;
        if (args.length > 1) {
            int rounds;
            try {
                rounds = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                out.println("回退轮数必须是整数，收到: " + args[1]);
                return true;
            }
            try {
                seq = CheckpointManager.resolveRewindTarget(all, rounds);
            } catch (IllegalArgumentException e) {
                out.println(e.getMessage());
                return true;
            }
        } else {
            seq = pickCheckpoint(all);
            if (seq < 0) {
                out.println("已取消。");
                return true;
            }
        }
        CheckpointManager.CheckpointInfo target = all.stream()
                .filter(c -> c.seq() == seq).findFirst().orElseThrow();
        AgentState snapshot;
        try {
            snapshot = checkpoints.load(currentSession, seq);
        } catch (IOException e) {
            out.println("读取 checkpoint #" + seq + " 失败: " + e.getMessage());
            return true;
        }
        AgentState live = agent.getDelegate().getAgentState(USER_ID, currentSession);
        out.println("将回退到 checkpoint #" + seq + "（" + target.time() + " 之前，该轮输入: "
                + target.messagePrefix() + "）");
        out.println("  - 上下文消息: " + live.getContext().size() + " 条 → "
                + snapshot.getContext().size() + " 条（文件改动不回退）");
        if (!confirm("确认回退? (y/N): ")) {
            out.println("已取消，未做任何改动。");
            return true;
        }
        int kept = CheckpointManager.applyRewindTruncation(live, snapshot);
        agent.getDelegate().saveAgentState(USER_ID, currentSession);
        out.println("已回退，保留 " + kept + " 条消息。");
        return true;
    }

    /** /thinking [off|low|medium|high]：查看或切换 thinking 深度，立即生效并写入 config.json。 */
    private boolean thinking(String[] args) {
        ThinkingLevel current = thinkingLevel.get();
        if (args.length <= 1) {
            out.println("当前 thinking 级别: " + current.lowerName());
            if (config.getProviderEnum() == ModelProvider.OLLAMA) {
                out.println("（ollama 本地模型不消费 thinking 参数，级别仅保存不生效）");
            }
            out.println("用法: /thinking <off|low|medium|high>");
            return true;
        }
        ThinkingLevel target;
        try {
            target = ThinkingLevel.parse(args[1]);
        } catch (IllegalArgumentException e) {
            out.println(e.getMessage());
            return true;
        }
        thinkingLevel.set(target);
        config.getModel().setThinkingLevel(target.lowerName());
        try {
            config.save();
            out.println("thinking 级别已切换为 " + target.lowerName() + "，已写入 config.json。");
        } catch (ConfigException e) {
            out.println("thinking 级别已切换为 " + target.lowerName()
                    + "，但写入 config.json 失败: " + e.getMessage());
        }
        return true;
    }

    /** 交互式列出 checkpoint 供选择（最新在前），返回所选序号；取消返回 -1。 */
    private int pickCheckpoint(List<CheckpointManager.CheckpointInfo> all) {
        out.println("可回退到的状态（1 = 最近一轮之前）：");
        for (int i = 0; i < all.size(); i++) {
            CheckpointManager.CheckpointInfo c = all.get(all.size() - 1 - i);
            out.println("  " + (i + 1) + ". 回退 " + (i + 1) + " 轮 → " + c.time()
                    + "「" + c.messagePrefix() + "」");
        }
        out.print("选择（1-" + all.size() + "，回车取消）: ");
        out.flush();
        String line = readLineOrNull();
        if (line == null || line.isBlank()) {
            return -1;
        }
        try {
            int idx = Integer.parseInt(line.strip());
            if (idx < 1 || idx > all.size()) {
                out.println("超出范围: " + idx);
                return -1;
            }
            return all.get(all.size() - idx).seq();
        } catch (NumberFormatException e) {
            out.println("不是有效数字: " + line.strip());
            return -1;
        }
    }

    /** y/n 确认：y/yes 执行；回车、Ctrl+D 或其他输入一律取消。 */
    private boolean confirm(String prompt) {
        out.print(prompt);
        out.flush();
        String line = readLineOrNull();
        return line != null
                && (line.strip().equalsIgnoreCase("y") || line.strip().equalsIgnoreCase("yes"));
    }

    /** 读一行输入，Ctrl+D / IO 错误返回 null（视作取消）。 */
    private String readLineOrNull() {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    // -----------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------

    /**
     * 按当前配置与项目根重建 agent（/model、/cd 用）。 先关旧再建新——先建后关会让两个 agent 短暂同时 持有同一 (userId, sessionId)
     * 的状态文件锁、并重复拉起 MCP stdio 进程（锁被占用会让新回合挂起）。 会话状态在 stateStore，不受实例销毁影响；重建失败时 agent 为
     * null，调用方负责回滚自己的状态。
     */
    private void rebuildAgent() {
        if (agent != null) {
            agent.close();
            agent = null;
        }
        agent = CoderAgentFactory.createAgent(workspace, new Toolkit(), config, projectDir,
                thinkingLevel);
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
