package io.dl.coder.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * checkpoint 存储边界（/rewind 数据层）：往返一致、序号递增、上限裁剪、会话隔离、
 * 损坏处理与回退截断计算。全部密闭：临时目录 + 直接构造 AgentState。
 */
class CheckpointManagerTest {

    @TempDir
    Path root;

    private CheckpointManager mgr;

    @BeforeEach
    void setUp() {
        mgr = new CheckpointManager(root);
    }

    /** 构造带若干用户消息的状态。 */
    private static AgentState state(String... messages) {
        var builder = AgentState.builder().sessionId("s").userId("u");
        for (String m : messages) {
            builder.addMessage(new UserMessage(m));
        }
        return builder.build();
    }

    @Test
    void 保存读取往返_内容一致() throws IOException {
        AgentState original = state("第一问", "第二问");
        int seq = mgr.save("sess", original.toJson(), "第一问");
        assertEquals(1, seq);

        List<CheckpointManager.CheckpointInfo> infos = mgr.list("sess");
        assertEquals(1, infos.size());
        assertEquals(1, infos.get(0).seq());
        assertEquals("第一问", infos.get(0).userMessage());

        AgentState loaded = mgr.load("sess", seq);
        assertEquals(2, loaded.getContext().size());
        assertEquals("第二问", loaded.getContext().get(1).getTextContent());
    }

    @Test
    void 序号在会话内递增() throws IOException {
        assertEquals(1, mgr.save("s", state("a").toJson(), "a"));
        assertEquals(2, mgr.save("s", state("b").toJson(), "b"));
        assertEquals("b", mgr.load("s", 2).getContext().get(0).getTextContent());
    }

    @Test
    void 超出上限删最旧() throws IOException {
        int total = CheckpointManager.MAX_CHECKPOINTS + 2;
        for (int i = 1; i <= total; i++) {
            mgr.save("s", state("m" + i).toJson(), "m" + i);
        }
        List<CheckpointManager.CheckpointInfo> infos = mgr.list("s");
        assertEquals(CheckpointManager.MAX_CHECKPOINTS, infos.size());
        assertEquals(3, infos.get(0).seq());
        assertEquals(total, infos.get(infos.size() - 1).seq());
        assertThrows(IOException.class, () -> mgr.load("s", 1));
        assertEquals("m" + total, mgr.load("s", total).getContext().get(0).getTextContent());
    }

    @Test
    void 会话间目录隔离() throws IOException {
        mgr.save("a", state("甲").toJson(), "甲");
        mgr.save("b", state("乙").toJson(), "乙");
        assertEquals(1, mgr.list("a").size());
        assertEquals(1, mgr.list("b").size());
        assertEquals("甲", mgr.load("a", 1).getContext().get(0).getTextContent());
        assertEquals("乙", mgr.load("b", 1).getContext().get(0).getTextContent());
    }

    @Test
    void 损坏缺失与格式不兼容均报错_列表跳过损坏() throws IOException {
        mgr.save("s", state("x").toJson(), "x");
        Path file = root.resolve("s").resolve("1.json");

        Files.writeString(file, "不是JSON");
        assertTrue(assertThrows(IOException.class, () -> mgr.load("s", 1)).getMessage()
                .contains("损坏"));
        // 解析失败的文件在列表展示时跳过
        assertTrue(mgr.list("s").isEmpty());

        // 合法 JSON 但缺少状态数据：格式不兼容
        Files.writeString(file, "{\"seq\":1}");
        assertTrue(assertThrows(IOException.class, () -> mgr.load("s", 1)).getMessage()
                .contains("格式不兼容"));

        assertTrue(assertThrows(IOException.class, () -> mgr.load("s", 99)).getMessage()
                .contains("不存在"));
    }

    @Test
    void 回退目标解析_合法映射与边界报错() {
        List<CheckpointManager.CheckpointInfo> cps = List.of(
                new CheckpointManager.CheckpointInfo(1, 0, "a"),
                new CheckpointManager.CheckpointInfo(2, 0, "b"),
                new CheckpointManager.CheckpointInfo(3, 0, "c"));
        assertEquals(3, CheckpointManager.resolveRewindTarget(cps, 1));
        assertEquals(2, CheckpointManager.resolveRewindTarget(cps, 2));
        assertEquals(1, CheckpointManager.resolveRewindTarget(cps, 3));
        assertThrows(IllegalArgumentException.class,
                () -> CheckpointManager.resolveRewindTarget(cps, 0));
        assertThrows(IllegalArgumentException.class,
                () -> CheckpointManager.resolveRewindTarget(cps, -1));
        assertThrows(IllegalArgumentException.class,
                () -> CheckpointManager.resolveRewindTarget(cps, 4));
        assertThrows(IllegalArgumentException.class,
                () -> CheckpointManager.resolveRewindTarget(List.of(), 1));
    }

    @Test
    void 回退截断_保留前缀并清空摘要() {
        AgentState snapshot = state("m1", "m2");
        AgentState current = state("m1", "m2", "m3", "m4", "m5", "m6");
        current.setSummary("压缩摘要");

        int kept = CheckpointManager.applyRewindTruncation(current, snapshot);

        assertEquals(2, kept);
        assertEquals(2, current.getContext().size());
        assertEquals("m2", current.getContext().get(1).getTextContent());
        String summary = current.getSummary();
        assertTrue(summary == null || summary.isBlank(), "回退后应清空压缩摘要");
    }

    @Test
    void 回退截断_快照更长时不扩充() {
        AgentState snapshot = state("a", "b", "c", "d");
        AgentState current = state("a", "b");
        assertEquals(2, CheckpointManager.applyRewindTruncation(current, snapshot));
        assertEquals(2, current.getContext().size());
    }
}
