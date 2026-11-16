package io.github.claw4j.agent.guard;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.nio.file.Path;

/**
 * 词表文件里真有词，才装 {@link org.springframework.ai.chat.client.advisor.SafeGuardAdvisor}。
 *
 * <p>用条件而不是「总是装、空表就等于不拦」：空词表时框架的 {@code containsSensitiveWord}
 * 恒为 false，行为上确实等价。但链上会多一个永远不生效的 Advisor，看容器里的 Advisor 清单时
 * 它和生效中的长得一模一样。装不出来，就是最明确的没生效。
 *
 * <p>判定只读一次配置，但会把小文件读两遍（这里一次、建 bean 时一次）。两处走同一个
 * {@link SensitiveWords#load(Path)}，所以「文件不存在」与「文件为空」的结论不会一处说开、一处说关。
 *
 * <p>文件配了却读不到时，这里把 {@link SensitiveWords#load(Path)} 的异常直接抛出去，让启动失败。
 * 吞掉它换来的是一个消息直通模型、日志干净的部署——正是这次要消掉的假象。
 */
public class SensitiveWordsConfigured implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment())
                .bind("claw4j.guard", Bindable.of(GuardProperties.class))
                .map(properties -> properties.sensitiveWordsFile() != null
                        && !SensitiveWords.load(Path.of(properties.sensitiveWordsFile())).isEmpty())
                .orElse(false);
    }
}
