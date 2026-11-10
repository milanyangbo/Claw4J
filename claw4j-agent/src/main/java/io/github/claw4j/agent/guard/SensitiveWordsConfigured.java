package io.github.claw4j.agent.guard;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 词表非空才装 {@link org.springframework.ai.chat.client.advisor.SafeGuardAdvisor}。
 *
 * <p>用条件而不是「总是装、空表就等于不拦」：空词表时框架的 {@code containsSensitiveWord}
 * 恒为 false，行为上确实等价。但链上会多一个永远不生效的 Advisor，看容器里的 Advisor 清单时
 * 它和生效中的长得一模一样。这类「配置看着已经生效」的假象在 {@code cleanupInterval} 与总开关
 * 上都出现过。装不出来，就是最明确的没生效。
 *
 * <p>判定只读一次：这里与 {@link GuardProperties} 都不缓存变量，判断依据就是绑定后的那份
 * record（空表已归一成空列表），不存在「配置层说开、装配层说关」的第二处判断。
 */
public class SensitiveWordsConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("claw4j.guard", Bindable.of(GuardProperties.class))
                .map(properties -> !properties.sensitiveWords().isEmpty())
                .orElse(false);
    }
}
