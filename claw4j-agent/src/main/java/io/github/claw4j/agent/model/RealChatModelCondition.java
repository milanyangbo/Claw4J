package io.github.claw4j.agent.model;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 决定"要不要装配真实 ChatModel"：有 key 就装，没 key 就不装，让 mock 兜底。
 *
 * <p>为什么需要自定义 Condition 而不是 {@code @ConditionalOnProperty}：
 * 后者只能判断"属性等于某个值"，判断不了"这个字符串属性非空"。
 * 写 {@code @ConditionalOnProperty(name = "claw4j.model.deepseek.api-key")} 的话，
 * 空字符串也算属性存在，条件照样通过，于是带着空 key 去构造模型——正是要避免的情况。
 *
 * <p>三态逻辑：
 * <pre>
 *   mock=true             → 不装（强制 mock）
 *   mock=false            → 装；若此时没有 key，装配方法会抛异常让启动失败（fail fast）
 *   mock 未设置 + 有 key   → 装
 *   mock 未设置 + 无 key   → 不装（自动降级）
 * </pre>
 */
public class RealChatModelCondition extends SpringBootCondition {

    public static final String MOCK_PROPERTY = "claw4j.model.mock";
    public static final String API_KEY_PROPERTY = "claw4j.model.deepseek.api-key";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        String mock = env.getProperty(MOCK_PROPERTY, "").trim();
        String apiKey = env.getProperty(API_KEY_PROPERTY, "").trim();

        if ("true".equalsIgnoreCase(mock)) {
            return ConditionOutcome.noMatch(MOCK_PROPERTY + "=true，强制使用 mock 模型");
        }
        if (apiKey.isEmpty()) {
            if ("false".equalsIgnoreCase(mock)) {
                // 显式要求真实模型却没有 key：交给装配方法抛异常，让启动失败并说清原因
                return ConditionOutcome.match("要求真实模型，但未提供 " + API_KEY_PROPERTY);
            }
            return ConditionOutcome.noMatch(API_KEY_PROPERTY + " 为空，降级到 mock 模型");
        }
        return ConditionOutcome.match(API_KEY_PROPERTY + " 已提供，装配真实模型");
    }
}
