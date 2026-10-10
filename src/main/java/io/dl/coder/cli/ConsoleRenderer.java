package io.dl.coder.cli;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.model.ChatUsage;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件流 → 终端渲染。
 *
 * <p>消费 {@code streamEvents} 的事件：文本增量直接打印（流式），
 * 工具调用显示为暗色单行（开始 → 名称，结束 → 状态 + 参数摘要），并统计本轮用量（含 token）。
 */
public class ConsoleRenderer {

    /** ANSI 颜色（Windows Terminal / VSCode 终端可用；不支持时显示转义符，可 setColored(false) 关闭） */
    private static final String DIM = "\u001b[2m";
    private static final String YELLOW = "\u001b[33m";
    private static final String RESET = "\u001b[0m";

    private final PrintStream out;
    private boolean colored = true;

    /** 本轮统计（每轮 reset，累计到 total） */
    private long turnModelCalls;
    private long turnToolCalls;
    private long turnChars;
    private long turnInputTokens;
    private long turnOutputTokens;
    /** 进程生命周期累计 */
    private final AtomicLong totalModelCalls = new AtomicLong();
    private final AtomicLong totalToolCalls = new AtomicLong();
    private long totalChars;
    private long totalInputTokens;
    private long totalOutputTokens;

    /** 本轮是否有文本输出（决定结束时要不要补换行） */
    private boolean printedTextThisTurn;

    /** 缓存每个工具调用的结果文本（toolCallId → 累计 delta），ERROR 时展示原因 */
    private final Map<String, StringBuilder> toolResultTexts = new HashMap<>();

    /** 缓存每个工具调用的入参 JSON 增量（toolCallId → 累计 delta），结束时展示摘要 */
    private final Map<String, StringBuilder> toolCallInputs = new HashMap<>();

    public ConsoleRenderer(PrintStream out) {
        this.out = out;
    }

    public void setColored(boolean colored) {
        this.colored = colored;
    }

    /** 新一轮开始：清零本轮统计。 */
    public void beginTurn() {
        turnModelCalls = 0;
        turnToolCalls = 0;
        turnChars = 0;
        turnInputTokens = 0;
        turnOutputTokens = 0;
        printedTextThisTurn = false;
        toolCallInputs.clear();
    }

    /** 处理单个事件（在流回调中调用）。 */
    public void onEvent(AgentEvent event) {
        switch (event.getType()) {
            case MODEL_CALL_START -> turnModelCalls++;
            case MODEL_CALL_END -> {
                // 模型事件携带 ChatUsage（部分兼容网关不回传，缺失时保持 0）
                ChatUsage usage = ((ModelCallEndEvent) event).getUsage();
                if (usage != null) {
                    turnInputTokens += usage.getInputTokens();
                    turnOutputTokens += usage.getOutputTokens();
                }
            }
            case TEXT_BLOCK_DELTA -> {
                String delta = ((TextBlockDeltaEvent) event).getDelta();
                if (delta != null && !delta.isEmpty()) {
                    out.print(delta);
                    turnChars += delta.length();
                    printedTextThisTurn = true;
                }
            }
            case TOOL_CALL_START -> {
                newlineIfPrinting();
                out.println(color(DIM, "  ⚙ " + ((ToolCallStartEvent) event).getToolCallName() + " …"));
            }
            case TOOL_CALL_DELTA -> {
                // 收集入参增量，TOOL_RESULT_END 时随状态一起展示
                ToolCallDeltaEvent d = (ToolCallDeltaEvent) event;
                if (d.getDelta() != null && d.getToolCallId() != null) {
                    toolCallInputs.computeIfAbsent(d.getToolCallId(), k -> new StringBuilder())
                            .append(d.getDelta());
                }
            }
            case TOOL_RESULT_TEXT_DELTA -> {
                // 收集工具结果文本，失败时用于展示原因（如 "Path outside root directory"）
                ToolResultTextDeltaEvent d = (ToolResultTextDeltaEvent) event;
                if (d.getDelta() != null && d.getToolCallId() != null) {
                    toolResultTexts.computeIfAbsent(d.getToolCallId(), k -> new StringBuilder())
                            .append(d.getDelta());
                }
            }
            case TOOL_CALL_END -> {
                // TOOL_CALL_END 携带工具名，紧随其后 TOOL_RESULT_END 携带状态，这里合并展示
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent tr = (ToolResultEndEvent) event;
                turnToolCalls++;
                String state = tr.getState() == null ? "?" : tr.getState().toString();
                newlineIfPrinting();
                out.println(color(DIM,
                        "  ✔ " + safeName(tr) + argSummary(tr.getToolCallId()) + " → " + state));
                if ("ERROR".equals(state)) {
                    String text = toolResultTexts.containsKey(tr.getToolCallId())
                            ? toolResultTexts.get(tr.getToolCallId()).toString().strip()
                            : "";
                    if (!text.isEmpty()) {
                        // 展示错误首行（截断），完整错误模型自己会看到
                        String firstLine = text.split("\n", 2)[0];
                        if (firstLine.length() > 200) {
                            firstLine = firstLine.substring(0, 200) + "…";
                        }
                        out.println(color(YELLOW, "    ↳ " + firstLine));
                    }
                }
            }
            case EXCEED_MAX_ITERS -> {
                newlineIfPrinting();
                out.println(color(YELLOW, "  ⚠ 达到最大迭代次数，本轮提前结束"));
            }
            case REQUIRE_USER_CONFIRM -> {
                // 待确认调用与 y/n/a 交互由 CoderCli 在事件流结束后统一处理
                newlineIfPrinting();
                out.println(color(YELLOW, "  ⏸ 工具请求人工确认，等待用户决定…"));
            }
            case AGENT_END -> {
                if (printedTextThisTurn) {
                    out.println();
                }
            }
            default -> { }
        }
    }

    /** 本轮结束：累计总量并输出暗色用量行。 */
    public void endTurn() {
        totalModelCalls.addAndGet(turnModelCalls);
        totalToolCalls.addAndGet(turnToolCalls);
        totalChars += turnChars;
        totalInputTokens += turnInputTokens;
        totalOutputTokens += turnOutputTokens;
        if (turnModelCalls > 0 || turnToolCalls > 0) {
            out.println(color(DIM, "  (" + turnModelCalls + " 次模型调用 · " + turnToolCalls
                    + " 次工具调用 · ↑" + turnInputTokens + " ↓" + turnOutputTokens + " tokens)"));
        }
    }

    /** /stats 命令输出。 */
    public String stats() {
        return "进程累计: " + totalModelCalls.get() + " 次模型调用 · " + totalToolCalls.get()
                + " 次工具调用 · 输入 " + totalInputTokens + " / 输出 " + totalOutputTokens
                + " tokens · 输出 " + totalChars + " 字符";
    }

    /** 工具调用入参的单行摘要（截断），无入参或空对象时为空串。 */
    private String argSummary(String toolCallId) {
        if (toolCallId == null) {
            return "";
        }
        String text = toolCallInputs.containsKey(toolCallId)
                ? toolCallInputs.get(toolCallId).toString().strip()
                : "";
        if (text.isEmpty() || "{}".equals(text)) {
            return "";
        }
        return text.length() > 100 ? " " + text.substring(0, 100) + "…" : " " + text;
    }

    private void newlineIfPrinting() {
        if (printedTextThisTurn) {
            out.println();
            printedTextThisTurn = false;
        }
    }

    private String safeName(ToolResultEndEvent event) {
        try {
            return event.getToolCallName() != null ? event.getToolCallName() : "tool";
        } catch (Exception e) {
            return "tool";
        }
    }

    private String color(String ansi, String text) {
        return colored ? ansi + text + RESET : text;
    }
}
