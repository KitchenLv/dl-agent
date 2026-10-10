package io.dl.coder.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 真实模型联调（P2-1 修正：原 toolsAgentTest 的 {@code toolkit.registerTool(toolkit)} 属笔误且无断言）。
 *
 * <p>
 * 默认跳过（保持 {@code mvn test} 密闭）；设置 {@code DL_LIVE_TEST=true} 并配好
 * {@code ~/.dl-coder/config.json} 后运行： {@code DL_LIVE_TEST=true mvn test -Dtest=AgentTest}
 */
@EnabledIfEnvironmentVariable(named = "DL_LIVE_TEST", matches = "true")
class AgentTest {

    @Test
    void 真实模型能对话() {
        var agent = CoderAgentFactory.createAgent(
                Path.of(System.getProperty("user.home"), ".dl-coder", "workspace"), new Toolkit());
        try (agent) {
            var msg = agent.call("只回复两个字：收到");
            String text = msg != null ? msg.block().getTextContent() : null;
            assertNotNull(text, "应返回非空消息");
            assertFalse(text.isBlank(), "回复不应为空: " + text);
        }
    }
}
