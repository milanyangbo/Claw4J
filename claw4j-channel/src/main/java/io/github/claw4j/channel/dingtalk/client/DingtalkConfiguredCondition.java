package io.github.claw4j.channel.dingtalk.client;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 三样配置都非空时才装配的条件。
 *
 * <p>{@code @ConditionalOnProperty} 表达不了"这个属性不是空串"，而
 * {@code client-id: ${DINGTALK_CLIENT_ID:}} 在没配环境变量时恰好是"存在且为空"——
 * 条件会成立，然后拿空凭据去换 token，把"没配"报成"凭据错"。
 *
 * <p>这三样按"缺了会怎样"挑：缺 {@code client-id} / {@code client-secret} 换不到 token、
 * 也验不了签；缺 {@code robot-code} 消息发不出去。少了任何一样，这个通道要么收不到、
 * 要么发不出，都不该装配起来占着一个端点。
 */
class DingtalkConfiguredCondition implements Condition {

    static final String CLIENT_ID = "claw4j.channel.dingtalk.client-id";

    static final String CLIENT_SECRET = "claw4j.channel.dingtalk.client-secret";

    static final String ROBOT_CODE = "claw4j.channel.dingtalk.robot-code";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return present(context, CLIENT_ID) && present(context, CLIENT_SECRET)
                && present(context, ROBOT_CODE);
    }

    private static boolean present(ConditionContext context, String key) {
        String value = context.getEnvironment().getProperty(key);
        return value != null && !value.isBlank();
    }
}
