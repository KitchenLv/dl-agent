package io.dl.coder.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * /clear 的核心行为：clearContext 后下一次模型调用不再携带历史消息，且会话可继续对话。
 * 确认/取消分支属于 REPL 交互（CoderCli），由冒烟覆盖；此处断言清除范围。
 * 不依赖真实模型与网络。
 */
class ClearContextTest {

    @TempDir
    Path workspace;

    @Test
    void 清空后新调用不携带历史消息() throws Exception {
        EchoModel model = new EchoModel();
        RuntimeContext ctx = RuntimeContext.builder().userId("tester").sessionId("clear-check")
                .build();

        try (HarnessAgent agent = agent(model)) {
            agent.streamEvents(new UserMessage("第一轮的问题"), ctx).blockLast();
            agent.streamEvents(new UserMessage("第二轮的问题"), ctx).blockLast();
            assertTrue(textOf(model.calls.get(1)).contains("第一轮的问题"),
                    "清空前第二次调用应携带第一轮历史");

            agent.clearContext(ctx);

            agent.streamEvents(new UserMessage("第三轮的问题"), ctx).blockLast();
        }

        String lastCall = textOf(model.calls.get(model.calls.size() - 1));
        assertFalse(lastCall.contains("第一轮的问题"), "清空后不应再携带第一轮消息");
        assertFalse(lastCall.contains("第二轮的问题"), "清空后不应再携带第二轮消息");
        assertTrue(lastCall.contains("第三轮的问题"), "清空后应能继续对话");
    }

    private HarnessAgent agent(Model model) {
        return HarnessAgent.builder()
                .model(model)
                .name("clear-test-agent")
                .agentId("clear-test-agent")
                .sysPrompt("test")
                .stateStore(new JsonFileAgentStateStore())
                .workspace(workspace)
                .maxIters(3)
                .build();
    }

    /** 拼接一次模型调用收到的全部消息文本。 */
    private static String textOf(List<Msg> messages) {
        StringBuilder sb = new StringBuilder();
        messages.forEach(m -> sb.append(m.getTextContent()).append('\n'));
        return sb.toString();
    }

    /** 假模型：记录每次收到的消息，固定回复。 */
    private static final class EchoModel implements Model {

        final List<List<Msg>> calls = new CopyOnWriteArrayList<>();

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(new ArrayList<>(messages));
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("回复").build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "echo-model";
        }
    }
}
