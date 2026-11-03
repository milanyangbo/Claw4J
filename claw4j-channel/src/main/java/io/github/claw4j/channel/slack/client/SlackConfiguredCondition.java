package io.github.claw4j.channel.slack.client;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * "两样都齐了才装配"这个条件。
 *
 * <p>与另外三个通道同一个理由：{@code @ConditionalOnProperty} 表达不了"这个属性不是空串"，
 * 而 {@code signing-secret: ${SLACK_SIGNING_SECRET:}} 在没配环境变量时恰好是"存在且为空"——
 * 条件会成立，然后拿空密钥去验签，把"没配"报成"签名全错"。
 *
 * <p>这两样是按"缺了会怎样"挑的：缺 {@code signing-secret} 所有回调被拒（连配置地址那一步的
 * 握手都过不去），缺 {@code bot-token} 收得到提问但发不出答案。少了任何一样，
 * 这个通道都不该装配起来占着一个端点。
 */
class SlackConfiguredCondition implements Condition {

    static final String SIGNING_SECRET = "claw4j.channel.slack.signing-secret";

    static final String BOT_TOKEN = "claw4j.channel.slack.bot-token";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return present(context, SIGNING_SECRET) && present(context, BOT_TOKEN);
    }

    private static boolean present(ConditionContext context, String key) {
        String value = context.getEnvironment().getProperty(key);
        return value != null && !value.isBlank();
    }
}
