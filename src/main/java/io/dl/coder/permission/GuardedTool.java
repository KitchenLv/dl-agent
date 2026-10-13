package io.dl.coder.permission;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

/**
 * 给 harness 内建工具补上内容级规则匹配。
 *
 * <p>
 * 框架注解式工具（execute / read_file 等）未覆写 {@code ToolBase#matchRule}，默认实现只认
 * {@code null} 规则内容——即 {@code PermissionRule.ruleContent} 非空时永不命中， 内容级
 * DENY/ASK 规则（如 "git push"、"*.env*"）形同虚设。本类在注册后的 Toolkit 里把目标工具包一层：
 * 执行与元信息全部委托原工具，仅新增两块语义：
 *
 * <ul>
 * <li>{@link #matchRule}：ruleContent 视为正则（大小写不敏感），在调用参数文本（命令 / 路径）中查找；
 * 非法正则回退为字面量匹配
 * <li>{@link #generateSuggestions}："始终允许"的建议规则仍为内容级——命令工具按程序名集合放宽
 * （答一次 "a"，同程序组合的命令免问），文件工具精确到本次路径文本
 * </ul>
 *
 * <p>
 * 规则优先级仍由框架 PermissionEngine 决定（DENY &gt; ASK &gt; 工具自检 &gt; ALLOW &gt; 模式默认）， 因此内容级
 * ALLOW 建议不会越过 DENY/ASK 规则放行危险调用。
 */
public final class GuardedTool extends ToolBase {

    private final ToolBase delegate;
    private final Function<Map<String, Object>, String> contentExtractor;
    private final boolean commandStyle;

    /**
     * @param delegate 被包装的原工具（保留同名注册，调用时整体委托）
     * @param contentExtractor 从调用入参提取待匹配文本（execute 取 command，文件工具取 path）
     * @param commandStyle "a" 建议粒度：true=命令工具，按程序名集合放宽；false=精确到本次文本
     */
    public GuardedTool(ToolBase delegate, Function<Map<String, Object>, String> contentExtractor,
            boolean commandStyle) {
        super(delegate.getName(), delegate.getDescription(), delegate.getParameters(),
                delegate.isReadOnly(), delegate.isConcurrencySafe(), false, null, false, false);
        this.delegate = delegate;
        this.contentExtractor = contentExtractor;
        this.commandStyle = commandStyle;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return delegate.callAsync(param);
    }

    @Override
    public Boolean getStrict() {
        return delegate.getStrict();
    }

    /** ruleContent 按正则（大小写不敏感）在参数文本中查找；空内容 = 工具级规则，匹配一切。 */
    @Override
    public boolean matchRule(String ruleContent, Map<String, Object> toolInput) {
        if (ruleContent == null || ruleContent.isBlank()) {
            return true;
        }
        String value = contentExtractor.apply(toolInput == null ? Map.of() : toolInput);
        if (value == null || value.isEmpty()) {
            return false;
        }
        return compile(ruleContent).matcher(value).find();
    }

    /** "始终允许"建议：命令工具按程序名集合，文件工具精确到本次路径文本。 */
    @Override
    public List<PermissionRule> generateSuggestions(Map<String, Object> toolInput) {
        String value = contentExtractor.apply(toolInput == null ? Map.of() : toolInput);
        if (value == null || value.isEmpty()) {
            return List.of(
                    new PermissionRule(getName(), null, PermissionBehavior.ALLOW, "suggested"));
        }
        String content = commandStyle ? programScopeRule(value) : value;
        return List.of(new PermissionRule(getName(), content, PermissionBehavior.ALLOW, "suggested"));
    }

    /**
     * 把命令文本收敛为"程序名集合"规则：按 {@code &&} / {@code ||} / {@code |} 切段取每段首个
     * token，生成要求所有段头都在集合内的全串正则。同程序组合的后续命令免问；换程序或追加
     * 未批准程序（如 {@code && git push}）不命中，回落默认 ASK。
     */
    private static String programScopeRule(String command) {
        List<String> heads = new ArrayList<>();
        for (String segment : command.split("&&|\\|\\||\\|")) {
            String head = headToken(segment);
            if (!head.isEmpty()) {
                heads.add(Pattern.quote(head));
            }
        }
        if (heads.isEmpty()) {
            return command; // 切不出程序名，退回整条命令原文精确匹配
        }
        // "?…?" 容忍 "带空格路径" 的引号形式；\b 放在闭引号前保证词边界成立
        return "^(?:\\s*\"?(?:" + String.join("|", heads) + ")\\b\"?[^\\n&|]*"
                + "(?:\\s*(?:&&|\\|\\||\\|)\\s*)?)*$";
    }

    /** 段首程序名（兼容 "带空格路径" 的引号形式）；空段返回空串。 */
    private static String headToken(String segment) {
        String s = segment.strip();
        if (s.isEmpty()) {
            return "";
        }
        if (s.startsWith("\"")) {
            int close = s.indexOf('"', 1);
            return close > 1 ? s.substring(1, close) : s.substring(1);
        }
        return s.split("\\s+", 2)[0];
    }

    /** 非法正则（命令文本常含括号等元字符）回退为字面量。 */
    private static Pattern compile(String content) {
        try {
            return Pattern.compile(content, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            return Pattern.compile(Pattern.quote(content), Pattern.CASE_INSENSITIVE);
        }
    }
}
