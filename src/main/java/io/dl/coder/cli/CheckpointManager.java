package io.dl.coder.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.state.AgentState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 会话 checkpoint 存储：~/.dl-coder/checkpoints/&lt;sessionId&gt;/&lt;seq&gt;.json。
 *
 * <p>每个快照记录"某轮用户消息入队前"的完整 AgentState（JSON 字符串），供 /rewind 回退。
 * 序号在会话内单调递增（目录内最大序号+1），每会话只保留最近 {@link #MAX_CHECKPOINTS} 份。
 * 只负责文件的读写与裁剪；状态恢复必须由 CoderCli 经 agent API 完成，不得绕过 agent 直接改存储。
 */
public class CheckpointManager {

    /** 每会话保留的 checkpoint 数量上限，超出删最旧。 */
    public static final int MAX_CHECKPOINTS = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Pattern SEQ_FILE = Pattern.compile("(\\d+)\\.json");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final Path root;

    public CheckpointManager() {
        this(Path.of(System.getProperty("user.home"), ".dl-coder", "checkpoints"));
    }

    /** 指定根目录（测试用）。 */
    public CheckpointManager(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** checkpoint 元信息（/rewind 列表展示用）。 */
    public record CheckpointInfo(int seq, long timestamp, String userMessage) {

        /** 时间列（本地时区，分钟精度）。 */
        public String time() {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp),
                    ZoneId.systemDefault()).format(TIME_FORMAT);
        }

        /** 消息前缀：压成单行并截断到 40 字符。 */
        public String messagePrefix() {
            String oneLine = (userMessage == null ? "" : userMessage).replaceAll("\\s+", " ").strip();
            return oneLine.length() > 40 ? oneLine.substring(0, 40) + "…" : oneLine;
        }
    }

    /**
     * 落盘一份快照：序号取目录内最大序号+1，写入后超出上限删最旧。
     *
     * @return 分配的序号
     */
    public int save(String sessionId, String stateJson, String userMessage) throws IOException {
        Path dir = dirOf(sessionId);
        Files.createDirectories(dir);
        int seq = seqs(dir).stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        var node = MAPPER.createObjectNode();
        node.put("seq", seq);
        node.put("timestamp", System.currentTimeMillis());
        node.put("userMessage", userMessage == null ? "" : userMessage);
        node.put("state", stateJson == null ? "" : stateJson);
        MAPPER.writeValue(dir.resolve(seq + ".json").toFile(), node);
        trim(dir);
        return seq;
    }

    /** 读取指定序号的快照；文件缺失/损坏/格式不兼容均抛 IOException（消息可直接展示）。 */
    public AgentState load(String sessionId, int seq) throws IOException {
        Path file = dirOf(sessionId).resolve(seq + ".json");
        if (!Files.isRegularFile(file)) {
            throw new IOException("checkpoint #" + seq + " 不存在");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(file.toFile());
        } catch (IOException e) {
            throw new IOException("checkpoint #" + seq + " 已损坏: " + e.getMessage(), e);
        }
        JsonNode state = node.get("state");
        if (state == null || !state.isTextual() || state.asText().isBlank()) {
            throw new IOException("checkpoint #" + seq + " 格式不兼容（缺少状态数据）");
        }
        try {
            return AgentState.fromJsonString(state.asText());
        } catch (RuntimeException e) {
            throw new IOException("checkpoint #" + seq + " 无法恢复（框架版本不兼容）: " + e.getMessage(), e);
        }
    }

    /** 列出会话的全部 checkpoint（按序号升序）；目录不存在返回空表，损坏文件跳过。 */
    public List<CheckpointInfo> list(String sessionId) {
        Path dir = dirOf(sessionId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<CheckpointInfo> result = new ArrayList<>();
        for (int seq : seqs(dir)) {
            try {
                JsonNode node = MAPPER.readTree(dir.resolve(seq + ".json").toFile());
                result.add(new CheckpointInfo(seq, node.path("timestamp").asLong(0),
                        node.path("userMessage").asText("")));
            } catch (IOException e) {
                // 列表展示忽略损坏文件；真正回退时 load 会再报错
            }
        }
        result.sort(Comparator.comparingInt(CheckpointInfo::seq));
        return result;
    }

    /** /rewind &lt;n&gt; 的目标快照：回退 n 轮 = 回到倒数第 n+1 份快照；n 非正或超范围报错。 */
    public static int resolveRewindTarget(List<CheckpointInfo> checkpoints, int rewindRounds) {
        if (rewindRounds <= 0) {
            throw new IllegalArgumentException("回退轮数必须是正整数，当前: " + rewindRounds);
        }
        if (rewindRounds > checkpoints.size()) {
            throw new IllegalArgumentException("回退轮数超出范围: " + rewindRounds
                    + "，该会话只有 " + checkpoints.size() + " 份 checkpoint（可用范围 1-"
                    + checkpoints.size() + "）");
        }
        return checkpoints.get(checkpoints.size() - rewindRounds).seq();
    }

    /**
     * 把 current 的对话上下文截断到 snapshot 的消息数并清空压缩摘要（快照即"目标轮之前"的状态）。
     * 只做内存修改，持久化由调用方经 agent.saveAgentState 完成。
     *
     * @return 截断后保留的消息数
     */
    public static int applyRewindTruncation(AgentState current, AgentState snapshot) {
        int targetSize = snapshot.getContext().size();
        var live = current.contextMutable();
        if (live.size() > targetSize) {
            live.subList(targetSize, live.size()).clear();
        }
        current.setSummary(null);
        return live.size();
    }

    /** 超出上限删最旧（按序号从小到大删，保证留下最近 N 份）。 */
    private void trim(Path dir) throws IOException {
        List<Integer> seqs = seqs(dir);
        for (int i = 0; i < seqs.size() - MAX_CHECKPOINTS; i++) {
            Files.deleteIfExists(dir.resolve(seqs.get(i) + ".json"));
        }
    }

    /** 目录内全部快照序号（升序）；目录不存在/不可读返回空表。 */
    private static List<Integer> seqs(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            entries.map(p -> p.getFileName().toString()).map(SEQ_FILE::matcher)
                    .filter(Matcher::matches).forEach(m -> result.add(Integer.parseInt(m.group(1))));
        } catch (IOException e) {
            return List.of();
        }
        result.sort(Integer::compareTo);
        return result;
    }

    private Path dirOf(String sessionId) {
        return root.resolve(sessionId);
    }
}
