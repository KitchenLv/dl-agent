package io.dl.coder.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.GenerateOptions;
import io.dl.coder.config.ModelProvider;
import io.dl.coder.config.ThinkingLevel;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * thinking 级别注入：拦截模型调用，把当前级别映射为厂商生成参数并覆盖默认值。
 *
 * <p>级别经共享的 {@link AtomicReference} 持有，/thinking 切换后下一次模型调用即生效，
 * /model、/cd 重建 agent 时传入同一引用，级别不丢。映射规则：anthropic → thinkingBudget
 * （1024/8192/16384），openai/dashscope → reasoningEffort（同名直传），ollama → 不注入。
 */
public class ThinkingMiddleware implements MiddlewareBase {

    /** anthropic thinkingBudget 三档（最大思考 tokens）。 */
    private static final int BUDGET_LOW = 1024;
    private static final int BUDGET_MEDIUM = 8192;
    private static final int BUDGET_HIGH = 16384;

    private final ModelProvider provider;
    private final AtomicReference<ThinkingLevel> level;

    public ThinkingMiddleware(ModelProvider provider, AtomicReference<ThinkingLevel> level) {
        this.provider = provider;
        this.level = level;
    }

    /** 当前 provider 是否支持注入 thinking 参数（ollama 本地推理不支持）。 */
    public boolean isSupported() {
        return provider != ModelProvider.OLLAMA;
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx, ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        GenerateOptions override = optionsFor(level.get());
        if (override == null) {
            return next.apply(input);
        }
        GenerateOptions merged = GenerateOptions.mergeOptions(override, input.options());
        return next.apply(new ModelCallInput(input.messages(), input.tools(), merged, input.model()));
    }

    /** 级别 → 当前厂商的生成参数；off 或不支持注入时返回 null（透传原 options）。 */
    private GenerateOptions optionsFor(ThinkingLevel current) {
        if (current == null || current == ThinkingLevel.OFF || !isSupported()) {
            return null;
        }
        var builder = GenerateOptions.builder();
        switch (provider) {
            case ANTHROPIC -> builder.thinkingBudget(switch (current) {
                case LOW -> BUDGET_LOW;
                case MEDIUM -> BUDGET_MEDIUM;
                case HIGH -> BUDGET_HIGH;
                case OFF -> null; // 方法开头已排除，穷举分支仅为编译通过
            });
            case OPENAI, DASHSCOPE -> builder.reasoningEffort(current.lowerName());
            case OLLAMA -> {
                return null;
            }
        }
        return builder.build();
    }
}
