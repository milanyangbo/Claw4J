package io.github.claw4j.agent.model;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

/**
 * 决定"这一家供应商要不要装配真实 ChatModel"：有 key 就装，没 key 就不装，让 mock 兜底。
 *
 * <p>为什么需要自定义 Condition 而不是 {@code @ConditionalOnProperty}：
 * 后者只能判断"属性等于某个值"，判断不了"这个字符串属性非空"。
 * 写 {@code @ConditionalOnProperty(name = "claw4j.model.deepseek.api-key")} 的话，
 * 空字符串也算属性存在，条件照样通过，于是带着空 key 去构造模型——正是要避免的情况。
 *
 * <p>每家供应商各有一个子类（{@link DeepSeekModelCondition}、{@link QwenModelCondition}），
 * 判定逻辑写在这一份里。
 *
 * <p>三态逻辑：
 * <pre>
 *   mock=true                    → 谁都不装（强制 mock）
 *   本家有 key                   → 装这一家
 *   mock=false 且所有家都没 key   → 装（装配方法随即抛异常让启动失败，fail fast）
 *   其余                         → 这一家不装
 * </pre>
 *
 * <p>最后一行的判定要看"所有家"而不只是"本家"：只配了 Qwen 的 key 又显式要求真实模型时，
 * DeepSeek 这一侧不该跳出来说"我没 key 所以启动失败"——有别的家用就够。
 */
public abstract class RealChatModelCondition extends SpringBootCondition {

    public static final String MOCK_PROPERTY = "claw4j.model.mock";

    /** 所有供应商的 key 属性。判断"一个 key 都没有"时要看全，不能只看本家。 */
    public static final List<String> API_KEY_PROPERTIES = List.of(
            "claw4j.model.deepseek.api-key",
            "claw4j.model.qwen.api-key");

    /** 本家的 key 属性。 */
    protected abstract String apiKeyProperty();

    /** 本家的名字，只用于日志。 */
    protected abstract String label();

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String mock = env.getProperty(MOCK_PROPERTY, "").trim();
        String apiKey = env.getProperty(apiKeyProperty(), "").trim();

        if ("true".equalsIgnoreCase(mock)) {
            return ConditionOutcome.noMatch(MOCK_PROPERTY + "=true，强制使用 mock 模型");
        }
        if (!apiKey.isEmpty()) {
            return ConditionOutcome.match(label() + " 的 " + apiKeyProperty() + " 已提供，装配真实模型");
        }
        if ("false".equalsIgnoreCase(mock) && !anyApiKey(env)) {
            // 显式要求真实模型却一个 key 都没有：交给装配方法抛异常，让启动失败并说清去哪配
            return ConditionOutcome.match(MOCK_PROPERTY + "=false 要求真实模型，但没有任何供应商提供了 key");
        }
        return ConditionOutcome.noMatch(label() + " 的 " + apiKeyProperty() + " 为空，这一家不装");
    }

    private static boolean anyApiKey(Environment env) {
        return API_KEY_PROPERTIES.stream()
                .anyMatch(property -> !env.getProperty(property, "").trim().isEmpty());
    }
}
