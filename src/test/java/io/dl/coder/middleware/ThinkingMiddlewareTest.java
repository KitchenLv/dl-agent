package io.dl.coder.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.dl.coder.config.ModelProvider;
import io.dl.coder.config.ThinkingLevel;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * thinking 级别到厂商生成参数的映射。密闭：不发起网络请求，next 只捕获透传的 ModelCallInput，
 * 模型实际收到的是捕获项里的 options。
 */
class ThinkingMiddlewareTest {

    private final AtomicReference<ThinkingLevel> level = new AtomicReference<>(ThinkingLevel.OFF);

    /** 以指定级别执行一次 onModelCall，返回传给 next 的 input。 */
    private ModelCallInput call(ModelProvider provider, ThinkingLevel current,
            GenerateOptions inputOptions) {
        level.set(current);
        AtomicReference<ModelCallInput> captured = new AtomicReference<>();
        ModelCallInput input = new ModelCallInput(List.of(), List.of(), inputOptions, null);
        new ThinkingMiddleware(provider, level)
                .onModelCall(null, null, input, next -> {
                    captured.set(next);
                    return Flux.empty();
                })
                .blockLast();
        return captured.get();
    }

    @Test
    void anthropic三档映射为思考预算() {
        GenerateOptions empty = GenerateOptions.builder().build();
        assertEquals(1024, call(ModelProvider.ANTHROPIC, ThinkingLevel.LOW, empty)
                .options().getThinkingBudget());
        assertEquals(8192, call(ModelProvider.ANTHROPIC, ThinkingLevel.MEDIUM, empty)
                .options().getThinkingBudget());
        assertEquals(16384, call(ModelProvider.ANTHROPIC, ThinkingLevel.HIGH, empty)
                .options().getThinkingBudget());
    }

    @Test
    void openai与dashscope映射为reasoningEffort() {
        assertEquals("low", call(ModelProvider.OPENAI, ThinkingLevel.LOW,
                GenerateOptions.builder().build()).options().getReasoningEffort());
        assertEquals("medium", call(ModelProvider.DASHSCOPE, ThinkingLevel.MEDIUM,
                GenerateOptions.builder().build()).options().getReasoningEffort());
    }

    @Test
    void off透传不注入() {
        GenerateOptions input = GenerateOptions.builder().thinkingBudget(1).build();
        ModelCallInput passed = call(ModelProvider.ANTHROPIC, ThinkingLevel.OFF, input);
        assertSame(input, passed.options());
        assertEquals(1, passed.options().getThinkingBudget());
    }

    @Test
    void ollama不支持注入透传() {
        GenerateOptions input = GenerateOptions.builder().build();
        ModelCallInput passed = call(ModelProvider.OLLAMA, ThinkingLevel.HIGH, input);
        assertSame(input, passed.options());
        assertNull(passed.options().getThinkingBudget());
        assertNull(passed.options().getReasoningEffort());
        assertFalse(new ThinkingMiddleware(ModelProvider.OLLAMA, level).isSupported());
        assertTrue(new ThinkingMiddleware(ModelProvider.ANTHROPIC, level).isSupported());
    }

    @Test
    void 级别参数覆盖请求已有值() {
        GenerateOptions input = GenerateOptions.builder().thinkingBudget(1).build();
        assertEquals(8192, call(ModelProvider.ANTHROPIC, ThinkingLevel.MEDIUM, input)
                .options().getThinkingBudget());
    }
}
