package io.github.claw4j.agent.model;

import io.github.claw4j.capability.secret.SpringSecretResolver;
import io.github.claw4j.secret.MissingSecretException;
import io.github.claw4j.secret.SecretResolver;
import io.github.claw4j.secret.SecretValue;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;

/**
 * 决定"这一家供应商要不要装配真实 ChatModel"：句柄能换出真值就装，一家都换不出就让启动失败。
 *
 * <p>为什么需要自定义 Condition 而不是 {@code @ConditionalOnProperty}：
 * 后者只能判断"属性等于某个值"，判断不了"这个句柄在部署环境里有没有真值"。
 * 写 {@code @ConditionalOnProperty(name = "claw4j.model.deepseek.api-key")} 的话，
 * 空字符串也算属性存在、写死的句柄在"只配了别家"的部署里也算存在，条件照样通过，
 * 于是带着空 key 去构造模型——正是要避免的情况。
 *
 * <p>每家供应商各有一个子类（{@link DeepSeekModelCondition}、{@link QwenModelCondition}），
 * 判定逻辑写在这一份里。
 *
 * <p><b>没有 mock 兜底（ADR-0011）</b>。mock 兜底的代价是"本地跑着 mock 却以为在跑真模型"——
 * 假模型既不会真的失败，也不会真的需要工具，于是只在真链路上才出现的缺陷一直验不出来
 * （跨厂商降级丢工具、模型内部重试把降级拖成分钟级，都属于这一类）。
 * 取向是**没 key 就起不来**，与"主模型那家没配 key 时启动失败"是同一个原则。
 *
 * <p>判定表：
 * <pre>
 *   本家句柄有真值             → 装这一家
 *   本家取不到、别家也取不到    → 装（装配方法随即抛异常让启动失败，fail fast 并说清去哪配）
 *   本家取不到、别家取得到      → 这一家不装，让别家上
 * </pre>
 *
 * <p>最后一行要看"所有家"而不只是"本家"：只配了 Qwen 的真值时，
 * DeepSeek 这一侧不该跳出来说"我没有"所以启动失败——有别的家用就够。
 * 反过来说，一家都没有时**至少有一个子类会匹配**，
 * 于是异常一定会抛出来，而不是静默装出零个模型。
 */
public abstract class RealChatModelCondition extends SpringBootCondition {

    /** 所有供应商的 key 属性。判断"一个 key 都没有"时要看全，不能只看本家。 */
    public static final List<String> API_KEY_PROPERTIES = List.of(
            "claw4j.model.deepseek.api-key",
            "claw4j.model.qwen.api-key");

    /** 本家的 key 属性。 */
    protected abstract String apiKeyProperty();

    /** 本家的名字，只用于日志。 */
    protected abstract String label();

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment env = context.getEnvironment();
        SecretResolver secrets = SpringSecretResolver.of(env);

        if (resolvable(env, apiKeyProperty(), secrets)) {
            return ConditionOutcome.match(label() + " 的 " + apiKeyProperty() + " 有真值，装配真实模型");
        }
        if (!API_KEY_PROPERTIES.stream().anyMatch(property -> resolvable(env, property, secrets))) {
            // 一家都没有：交给装配方法抛异常，让启动失败并说清去哪配。
            // 这里必须 match 而不是 noMatch——两个子类都 noMatch 的话容器里一个 ChatModel 都没有，
            // 失败会推迟到"某个业务 bean 找不到 ChatModel"，报错离真正的原因很远。
            return ConditionOutcome.match("没有任何供应商的句柄取得到真值，交由装配方法 fail fast");
        }
        return ConditionOutcome.noMatch(label() + " 的 " + apiKeyProperty() + " 取不到真值，这一家不装");
    }

    /**
     * 这一家的句柄能不能换出真值（P08）。
     *
     * <p>判"能不能换出"而不是"字符串非空"：yaml 里写死的句柄在"只配了另一家"的部署里也是非空的，
     * 按非空判断会把这一家也装起来，然后在装配方法里失败——那就把"只配了千问"变成了"起不来"。
     * 这里只问在不在，不保留值：真值仍然每次出口现取。
     */
    private static boolean resolvable(Environment env, String property, SecretResolver secrets) {
        // 形状非法（比如把 key 明文写进配置）在 parse 里直接抛，启动失败并点名配置项：
        // 那种错误不该表现为"这一家静默不装"。
        SecretValue value = SecretValue.parse(env.getProperty(property, ""), property, false);
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
