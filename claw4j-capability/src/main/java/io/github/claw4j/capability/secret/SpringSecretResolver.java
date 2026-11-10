package io.github.claw4j.capability.secret;

import io.github.claw4j.secret.SecretResolver;
import org.springframework.core.env.Environment;

import java.util.Optional;

/**
 * 真值从部署环境来：{@code Environment} 里查，查不到就是没有（P08）。
 *
 * <p>读 Spring 的 {@code Environment} 而不是 {@code System.getenv}：本仓库的 {@code .env} 是被
 * {@code spring.config.import} 当属性文件读进来的，它不进操作系统环境。直接读 {@code System.getenv}
 * 会让"用 .env 本机跑"这条路径静默失效——配置看着对，启动报"没有真值"。而 {@code Environment}
 * 同时包含系统环境变量与导入的属性，一处看全两处。
 *
 * <p>放在 capability 模块，是因为 agent（模型 key）与 capability（MCP）两边都要用，而
 * {@code claw4j-core} 不许出现 Spring（那个模块的 enforcer 会当场拦下），agent 又依赖本模块。
 * 这是模块方向决定的落点，不是"它属于能力层"。
 *
 * <p>它不是 bean：容器里没有 {@code SecretResolver} 可注入，就没有"某段业务代码顺手注入一下、
 * 在任意时刻取真值"这种写法。要真值只有一条路——拿一个 {@code SecretValue} 走到它的出口去 render。
 *
 * <p>不缓存：每次问都回源头。凭据轮换时不必重启进程，代价只是一次属性查找。
 */
public final class SpringSecretResolver implements SecretResolver {

    private final Environment environment;

    private SpringSecretResolver(Environment environment) {
        this.environment = environment;
    }

    /**
     * 用给定的属性源建解析器。
     *
     * @param environment Spring 的属性源，系统环境变量与导入的属性都在里面
     * @return 读它的解析器
     * @throws IllegalArgumentException 没有属性源
     */
    public static SpringSecretResolver of(Environment environment) {
        if (environment == null) {
            throw new IllegalArgumentException("SecretResolver 需要一个 Environment：没有任何属性源就取不到真值");
        }
        return new SpringSecretResolver(environment);
    }

    /**
     * 按名字查真值。
     *
     * @param name 句柄名，转换成环境变量写法后查 {@code Environment}
     * @return 去掉首尾空白的真值；没有或为空白时为空
     */
    @Override
    public Optional<String> lookup(String name) {
        String value = environment.getProperty(SecretResolver.environmentName(name));
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }
}
