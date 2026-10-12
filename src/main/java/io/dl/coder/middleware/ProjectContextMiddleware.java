package io.dl.coder.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

/**
 * 项目规约注入：每轮 reasoning 现读项目根的 AGENTS.md 并追加到系统提示词。
 *
 * <p>
 * 框架的 WorkspaceContextMiddleware 读 AGENTS.md 走 overlay 文件系统（上层 workspace 优先），
 * 首启模板必然存在的 workspace AGENTS.md 会遮蔽项目根的同名文件；本中间件走独立的
 * onSystemPrompt 通路直接读本地磁盘，绕开该限制。文件变更下一轮即生效（每轮现读，无缓存）；
 * 中间件随 builder 拷贝给子代理，子代理同样获得项目规约。
 */
public class ProjectContextMiddleware implements MiddlewareBase {

    private static final String AGENTS_MD = "AGENTS.md";

    private final Path projectDir;

    public ProjectContextMiddleware(Path projectDir) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        String content = readAgentsMd();
        if (content.isBlank()) {
            return Mono.just(currentPrompt);
        }
        return Mono.just(currentPrompt + "\n\n---\n\n### 项目规约（AGENTS.md）\n\n"
                + content.strip() + "\n\n（以上为项目根 AGENTS.md 全文，其规则覆盖默认行为）");
    }

    /** 读取项目根 AGENTS.md；不存在或读取失败返回空串（注入通路静默降级）。 */
    private String readAgentsMd() {
        Path file = projectDir.resolve(AGENTS_MD);
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 读不到视同没有：不让 IO 问题打断对话
            return "";
        }
    }
}
