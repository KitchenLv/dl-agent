package io.dl.coder.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 工作区首启模板（P2-2）：补齐缺失文件、绝不覆盖已有内容。密闭。 */
class WorkspaceInitializerTest {

    @TempDir
    Path workspace;

    @Test
    void 空工作区补齐三个模板() throws Exception {
        List<String> created = WorkspaceInitializer.initialize(workspace);
        assertEquals(3, created.size());
        for (String relative : created) {
            Path file = workspace.resolve(relative);
            assertTrue(Files.isRegularFile(file), relative + " 应存在");
            assertTrue(Files.size(file) > 0, relative + " 内容非空");
        }
        // 子代理模板带 frontmatter，技能模板在 skills/ 下
        String reviewer = Files.readString(workspace.resolve("subagents/reviewer.md"));
        assertTrue(reviewer.startsWith("---"));
        assertTrue(Files.exists(workspace.resolve("skills/commit-convention/SKILL.md")));
    }

    @Test
    void 已存在的不覆盖_幂等() throws Exception {
        WorkspaceInitializer.initialize(workspace);
        Path agents = workspace.resolve("AGENTS.md");
        Files.writeString(agents, "# 用户自己的规约，不许动");
        List<String> second = WorkspaceInitializer.initialize(workspace);
        assertFalse(second.contains("AGENTS.md"));
        assertEquals("# 用户自己的规约，不许动", Files.readString(agents).strip());
    }
}
