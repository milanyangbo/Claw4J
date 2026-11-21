package io.github.claw4j.model.config;

import io.github.claw4j.secret.MissingSecretException;
import io.github.claw4j.secret.PropertySecretResolver;
import io.github.claw4j.secret.SecretResolver;
import io.github.claw4j.secret.SecretValue;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * 通义那一家在不在、用不用得上：向量模型与语义缓存都压在它身上。
 *
 * <p>为什么需要自定义 Condition：{@code @ConditionalOnProperty} 只能判"属性等于某个值"，
 * 判不了"这把钥匙在部署环境里有没有真值"。而"配了句柄、没给真值"是最常见的形态
 * （.env 没被 local profile 导入），按属性存在去判会带着空 key 去构造向量模型。
 *
 * <p>条件比 bean 早，所以这里自己把表绑一遍（{@link Binder}），不注入 {@code ModelProperties}。
 *
 * <p>与装配层同一个口径：一家写进表里就是要用它，缺钥匙且没写 {@code auth: none} 时
 * {@link ModelConfiguration} 会直接启动失败；这里只回答"这一家能不能用"。
 */
public class QwenModelCondition extends SpringBootCondition {

    /** 这一家在 {@code claw4j.model.providers} 里的 id。 */
    public static final String PROVIDER = "qwen";

    /** 供应商表在配置里的位置。条件与装配读的是同一份。 */
    public static final String PROVIDERS_PROPERTY = "claw4j.model.providers";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        SecretResolver secrets = PropertySecretResolver.of(env::getProperty);
        Map<String, ModelProperties.Provider> providers = bindProviders(env);

        ModelProperties.Provider qwen = providers.get(PROVIDER);
        if (qwen == null) {
            return ConditionOutcome.noMatch("没有配 " + PROVIDER + " 这一家，向量模型与语义缓存不装");
        }
        if (!qwen.requiresCredential()) {
            return ConditionOutcome.match(PROVIDER + " 声明了 auth: none，装配向量模型");
        }
        if (resolvable(qwen.apiKey(), secrets)) {
            return ConditionOutcome.match(PROVIDER + " 的句柄有真值，装配向量模型");
        }
        return ConditionOutcome.noMatch(PROVIDER + " 的句柄取不到真值，向量模型与语义缓存不装");
    }

    /**
     * 把供应商表绑出来。
     *
     * @param env 容器的 Environment
     * @return id → 一家；没配时是空表
     */
    static Map<String, ModelProperties.Provider> bindProviders(Environment env) {
        Map<String, ModelProperties.Provider> bound = Binder.get(env)
                .bind(PROVIDERS_PROPERTY, Bindable.mapOf(String.class, ModelProperties.Provider.class))
                .orElse(Map.of());
        // 条件阶段没走过 validateShape，id 得在这里认一次：报错信息与句柄路径都要靠它。
        bound.forEach((id, provider) -> {
            if (provider != null) {
                provider.attach(id);
            }
        });
        return bound;
    }

    /**
     * 这一家的句柄能不能换出真值（P08）。只问在不在，不保留值。
     *
     * @param value   凭据句柄
     * @param secrets 密钥解析器
     * @return 能换出真值为 true
     */
    private static boolean resolvable(SecretValue value, SecretResolver secrets) {
        if (value.isAbsent()) {
            return false;
        }
        try {
            value.requireResolvable(secrets);
            return true;
        }
        catch (MissingSecretException missing) {
            return false;
        }
    }
}
