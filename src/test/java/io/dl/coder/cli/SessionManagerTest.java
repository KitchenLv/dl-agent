package io.dl.coder.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话注册表持久化（P2-1）：落盘-重载、幂等注册、损坏容忍。密闭。 */
class SessionManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void 注册后重建实例能读到() {
        Path file = tempDir.resolve("sessions.json");
        SessionManager first = new SessionManager(file);
        first.register("alpha");
        first.register("beta");

        SessionManager reloaded = new SessionManager(file);
        assertEquals(List.of("alpha", "beta"), reloaded.names());
        assertNotNull(reloaded.createdAt("alpha"));
        assertTrue(reloaded.exists("beta"));
    }

    @Test
    void 重复注册不产生重复条目() {
        SessionManager manager = new SessionManager(tempDir.resolve("sessions.json"));
        manager.register("alpha");
        manager.register("alpha");
        assertEquals(List.of("alpha"), manager.names());
    }

    @Test
    void 注册表损坏时容忍并重建() throws Exception {
        Path file = tempDir.resolve("sessions.json");
        Files.writeString(file, "{ 不是合法 json");
        SessionManager manager = new SessionManager(file);
        assertTrue(manager.names().isEmpty());
        // 损坏后仍可继续注册
        manager.register("gamma");
        assertEquals(List.of("gamma"), new SessionManager(file).names());
    }

    @Test
    void 随机名匹配形容词名词两位数格式() {
        SessionManager manager = new SessionManager(tempDir.resolve("sessions.json"));
        for (int i = 0; i < 100; i++) {
            assertTrue(manager.randomName().matches("^[a-z]+-[a-z]+-\\d{2}$"),
                    "随机名格式应为 形容词-名词-两位数");
        }
    }

    @Test
    void 随机名不与现有会话重名() {
        SessionManager manager = new SessionManager(tempDir.resolve("sessions.json"));
        String name = manager.randomName();
        manager.register(name);
        // 大量抽样确认查重生效：已注册名不会再被生成（词表空间 9 万+，撞名概率极低）
        for (int i = 0; i < 200; i++) {
            assertTrue(!name.equals(manager.randomName()), "随机名必须避开已注册条目");
        }
    }

    @Test
    void 随机名命中stateStore文件名安全字符集() {
        SessionManager manager = new SessionManager(tempDir.resolve("sessions.json"));
        // JsonFileAgentStateStore 的安全模式：不匹配的 sessionId 会被 Base64 编码成不可读目录名
        for (int i = 0; i < 100; i++) {
            assertTrue(manager.randomName().matches("^[a-zA-Z0-9_\\-.]+$"));
        }
    }
}
