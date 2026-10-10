package io.dl.coder.agent;

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
 * AgentState 持久化-恢复（P2-1）：同一 (userId, sessionId) 两轮对话，第二轮用**新 agent 实例**
 * （共享 stateStore）——只有状态真正落盘并恢复，假模型才能在第二轮看到第一轮的消息。
 * 不依赖真实模型与网络。
 */
class AgentStatePersistenceTest {

    @TempDir
    Path workspace;

    @Test
    void 同会话两轮_新实例能看到历史() throws Exception {
        EchoModel model1 = new EchoModel();
        EchoModel model2 = new EchoModel();
        RuntimeContext ctx = RuntimeContext.builder().userId("tester").sessionId("persist-check")
                .build();

        // 第一轮：实例 A
        try (HarnessAgent agentA = agent(model1)) {
            agentA.streamEvents(new UserMessage("第一轮的问题"), ctx).blockLast();
        }
        // 第二轮：新实例 B，同一 stateStore + workspace + 同一会话
        try (HarnessAgent agentB = agent(model2)) {
            agentB.streamEvents(new UserMessage("第二轮的问题"), ctx).blockLast();
        }

        List<List<Msg>> secondRound = model2.calls;
        assertTrue(secondRound.size() >= 1, "第二轮应至少发起一次模型调用");
        String history = stringify(secondRound);
        assertTrue(history.contains("第一轮的问题"), "新实例应从 stateStore 恢复第一轮用户消息");
        assertTrue(history.contains("第1轮回复"), "新实例应恢复第一轮的助手回复");
    }

    private HarnessAgent agent(Model model) {
        return HarnessAgent.builder()
                .model(model)
                .name("state-test-agent")
                .agentId("state-test-agent")
                .sysPrompt("test")
                .stateStore(new JsonFileAgentStateStore())
                .workspace(workspace)
                .maxIters(3)
                .build();
    }

    private static String stringify(List<List<Msg>> calls) {
        StringBuilder sb = new StringBuilder();
        calls.forEach(msgs -> msgs.forEach(m -> sb.append(m.getTextContent()).append('\n')));
        return sb.toString();
    }

    /** 假模型：记录每次收到的消息，固定回复"第N轮回复"。 */
    private static final class EchoModel implements Model {

        final List<List<Msg>> calls = new CopyOnWriteArrayList<>();
        private int round = 0;

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(new ArrayList<>(messages));
            String reply = "第" + (++round) + "轮回复";
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(reply).build()))
                    .finishReason("stop")
                    .build());
        }

        @Override
        public String getModelName() {
            return "echo-model";
        }
    }
}
