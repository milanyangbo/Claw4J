package io.github.claw4j.agent.model;

import io.github.claw4j.capability.secret.SpringSecretResolver;
import io.github.claw4j.secret.MissingSecretException;
import io.github.claw4j.secret.SecretResolver;
import io.github.claw4j.secret.SecretValue;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

/**
 * 决定这一家供应商要不要装配真实 ChatModel：句柄能换出真值就装，一家都换不出就让启动失败。
 *
 * <p>需要自定义 Condition 而不是 {@code @ConditionalOnProperty}：后者只能判断"属性等于某个值"，
 * 判断不了"这个句柄在部署环境里有没有真值"。用 {@code @ConditionalOnProperty(name = ...)} 的话，
 * 空字符串也算属性存在、写死的句柄在"只配了别家"的部署里也算存在，条件照样通过，
 * 于是带着空 key 去构造模型。
 *
 * <p>供应商现在是一张表（{@code claw4j.model.providers}），判定要按 <b>name</b> 找自己那一条，
 * 不能按 {@code claw4j.model.<名字>.api-key} 这种把名字写进路径的方式读属性——那样加一家就要改
 * 一次条件。这里改成把表绑一遍（{@link Binder}），再按名字挑。
 *
 * <p>每家供应商各有一个子类（{@link DeepSeekModelCondition}、{@link QwenModelCondition}），
 * 判定逻辑只写在这一份里。
 *
 * <p>没有 mock 兜底（ADR-0011）：假模型既不会真的失败，也不会真的需要工具，只在真链路上才出现的
 * 缺陷一直验不出来（跨厂商降级丢工具、模型内部重试把降级拖成分钟级，都属于这一类）。
 *
 * <p>判定表：
 * <pre>
 *   本家句柄有真值             → 装这一家
 *   本家取不到、别家也取不到    → 装（装配方法随即抛异常让启动失败，说清去哪配）
 *   本家取不到、别家取得到      → 这一家不装，让别家上
 * </pre>
 *
 * <p>最后一行要看所有家而不只是本家：只配了 Qwen 的真值时，DeepSeek 这一侧不该跳出来说
 * "我没有"所以启动失败。反过来说，一家都没有时至少有一个子类会匹配，异常一定会抛出来，
 * 而不是静默装出零个模型。
 */
public abstract class RealChatModelCondition extends SpringBootCondition {

    /** 供应商表在配置里的位置。条件与装配读的是同一份。 */
    public static final String PROVIDERS_PROPERTY = "claw4j.model.providers";

    /** 本家的名字，判定时按它在表里找自己那一条。 */
    protected abstract String providerName();

    /** 本家的名字，只用于日志。 */
    protected abstract String label();

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        SecretResolver secrets = SpringSecretResolver.of(env);
        List<ModelProperties.Provider> providers = bindProviders(env);

        boolean mine = providers.stream()
                .filter(provider -> providerName().equals(provider.name()))
                .findFirst()
                .map(provider -> resolvable(provider.apiKey(), secrets))
                .orElse(false);
        if (mine) {
            return ConditionOutcome.match(label() + " 的句柄有真值，装配真实模型");
        }
        if (providers.stream().noneMatch(provider -> resolvable(provider.apiKey(), secrets))) {
            // 一家都没有：交给装配方法抛异常，让启动失败并说清去哪配。
            // 这里必须 match 而不是 noMatch——两个子类都 noMatch 的话容器里一个 ChatModel 都没有，
            // 失败会推迟到"某个业务 bean 找不到 ChatModel"，报错离真正的原因很远。
            return ConditionOutcome.match("没有任何供应商的句柄取得到真值，交由装配方法 fail fast");
        }
        return ConditionOutcome.noMatch(label() + " 的句柄取不到真值，这一家不装");
    }

    /**
     * 把供应商表绑出来。自己绑而不是注入 {@code ModelProperties}：条件比 bean 早，
     * 那会儿容器里还没有它。
     *
     * @param env 容器的 Environment
     * @return 表里配的那几家；没配时是空表
     */
    static List<ModelProperties.Provider> bindProviders(Environment env) {
        return Binder.get(env)
                .bind(PROVIDERS_PROPERTY, Bindable.listOf(ModelProperties.Provider.class))
                .orElse(List.of());
    }

    /**
     * 这一家的句柄能不能换出真值（P08）。
     *
     * <p>判"能不能换出"而不是"字符串非空"：yaml 里写死的句柄在"只配了另一家"的部署里也是非空的，
     * 按非空判断会把这一家也装起来，然后在装配方法里失败——"只配了千问"就变成了"起不来"。
     * 这里只问在不在，不保留值：真值仍然每次出口现取。
     *
     * @param value   这一家的凭据句柄
     * @param secrets 密钥解析器
     * @return 能换出真值为 true
     */
    private static boolean resolvable(SecretValue value, SecretResolver secrets) {
        // 形状非法（比如把 key 明文写进配置）在 parse 里直接抛，启动失败并点名配置项：
        // 那种错误不该表现为"这一家静默不装"。
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
