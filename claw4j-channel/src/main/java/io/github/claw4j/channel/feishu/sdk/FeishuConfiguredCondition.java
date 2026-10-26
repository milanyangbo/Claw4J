package io.github.claw4j.channel.feishu.sdk;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * "凭据齐了才装配"这个条件。
 *
 * <p>为什么不用 {@code @ConditionalOnProperty}：它能表达"这个属性存在/等于某值"，
 * 表达不了"这个属性不是空串"，而 {@code app-id: ${FEISHU_APP_ID:}} 在没配环境变量时
 * 恰好就是"存在且为空"——条件会成立，然后拿空凭据去连，把"没配"报成"连不上"。
 *
 * <p>为什么不用 {@code @ConditionalOnExpression}：那要写 SpEL 里的字符串比较，
 * 引号与占位符嵌套，读的人得先在心里求值一遍才敢改。
 */
class FeishuConfiguredCondition implements Condition {

    static final String APP_ID = "claw4j.channel.feishu.app-id";

    static final String APP_SECRET = "claw4j.channel.feishu.app-secret";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return configured(context, APP_ID) && configured(context, APP_SECRET);
    }

    private static boolean configured(ConditionContext context, String key) {
        String value = context.getEnvironment().getProperty(key);
        return value != null && !value.isBlank();
    }
}
