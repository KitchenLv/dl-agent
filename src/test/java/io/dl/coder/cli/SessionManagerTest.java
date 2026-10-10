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
}
