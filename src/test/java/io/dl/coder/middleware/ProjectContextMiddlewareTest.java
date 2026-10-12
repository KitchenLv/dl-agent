package io.dl.coder.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 项目 AGENTS.md 注入：存在追加、不存在原样返回、内容完整拼接。密闭，不依赖模型。 */
class ProjectContextMiddlewareTest {

    @TempDir
    Path tempDir;

    @Test
    void 文件不存在时提示词原样返回() {
        ProjectContextMiddleware mw = new ProjectContextMiddleware(tempDir);
        String result = mw.onSystemPrompt(null, null, "base prompt").block();
        assertEquals("base prompt", result);
    }

    @Test
    void 文件存在时以项目规约节追加() throws Exception {
        Files.writeString(tempDir.resolve("AGENTS.md"), "- 只用中文注释\n- mvn test 验证");
        ProjectContextMiddleware mw = new ProjectContextMiddleware(tempDir);
        String result = mw.onSystemPrompt(null, null, "base prompt").block();
        assertTrue(result.startsWith("base prompt"), "原有提示词必须保留在前");
        assertTrue(result.contains("### 项目规约（AGENTS.md）"), "必须有独立小节标题");
        assertTrue(result.contains("- 只用中文注释") && result.contains("mvn test 验证"),
                "文件内容必须完整注入");
    }

    @Test
    void 文件变更后下一次调用即生效() throws Exception {
        Path file = tempDir.resolve("AGENTS.md");
        Files.writeString(file, "v1");
        ProjectContextMiddleware mw = new ProjectContextMiddleware(tempDir);
        String first = mw.onSystemPrompt(null, null, "p").block();
        Files.writeString(file, "v2 完全不同的内容");
        String second = mw.onSystemPrompt(null, null, "p").block();
        assertTrue(first.contains("v1") && second.contains("v2 完全不同的内容"),
                "每轮现读，无缓存");
    }
}
