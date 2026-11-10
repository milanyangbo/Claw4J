package io.github.claw4j.agent.observability;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 「配了端点才造 provider」这个条件。
 *
 * <p>不用 {@code @ConditionalOnProperty(value = "claw4j.observability.endpoint")}：那个注解只判断
 * 属性存在且不是 false，而 {@code endpoint: ${OTLP_ENDPOINT:}} 在没配环境变量时恰好是「存在且为空」
 * ——条件成立，然后 exporter 去连一个默认入口，于是持续刷连接被拒的日志，而真正缺的那一项没人说。
 * 同一个坑在通道那几个条件类上已经踩过（见 {@code SlackConfiguredCondition}）。
 *
 * <p>端点为空时不造 provider 是刻意的：一个没有导出去处的 provider 只会在内存里攒 span 然后按队列
 * 上限丢，它带来的唯一确定性行为是占内存。
 */
class OtlpConfiguredCondition implements Condition {

    static final String ENDPOINT = "claw4j.observability.endpoint";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty(ENDPOINT);
        return value != null && !value.isBlank();
    }
}
