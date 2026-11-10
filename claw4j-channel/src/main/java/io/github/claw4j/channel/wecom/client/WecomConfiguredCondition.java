package io.github.claw4j.channel.wecom.client;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 五样配置都齐了才装配的条件。
 *
 * <p>与 {@code FeishuConfiguredCondition} 同一个理由：{@code @ConditionalOnProperty}
 * 表达不了「不是空串」，而 {@code corp-id: ${WECOM_CORP_ID:}} 在没配环境变量时恰好是
 * 「存在且为空」，条件成立后拿空凭据去换 token，把「没配」报成「凭据错」。
 *
 * <p>{@code agent-id} 多一道数字判断：它绑不上时 Spring 直接把 0 填进来，而 0 是个
 * 「看着有值」的缺省。不拦的话错误会延后到第一次发消息，变成一句看不懂的 {@code 40056}（应用不存在）。
 */
class WecomConfiguredCondition implements Condition {

    static final String CORP_ID = "claw4j.channel.wecom.corp-id";

    static final String CORP_SECRET = "claw4j.channel.wecom.corp-secret";

    static final String AGENT_ID = "claw4j.channel.wecom.agent-id";

    static final String TOKEN = "claw4j.channel.wecom.token";

    static final String ENCODING_AES_KEY = "claw4j.channel.wecom.encoding-aes-key";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return present(context, CORP_ID) && present(context, CORP_SECRET)
                && positiveInteger(context, AGENT_ID)
                && present(context, TOKEN) && present(context, ENCODING_AES_KEY);
    }

    private static boolean present(ConditionContext context, String key) {
        String value = context.getEnvironment().getProperty(key);
        return value != null && !value.isBlank();
    }

    private static boolean positiveInteger(ConditionContext context, String key) {
        String value = context.getEnvironment().getProperty(key);
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(value.strip()) > 0;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }
}
