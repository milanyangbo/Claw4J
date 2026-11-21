package io.github.claw4j.secret;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 真值从部署环境来：问一个"属性名 → 值"的函数，查不到就是没有（P08）。
 *
 * <p>为什么参数是 {@link Function} 而不是 Spring 的 {@code Environment}：本模块不许出现 Spring
 * （enforcer 与 ArchUnit 两处守着，见 {@code claw4j-core/pom.xml}），而"取值"这件事只需要一个
 * 查表动作。调用方把 {@code environment::getProperty} 递进来即可，一行，不用为此再复制一份实现。
 *
 * <p>为什么读 {@code Environment} 而不是 {@code System.getenv}：本仓库的 {@code .env} 是被
 * {@code spring.config.import} 当属性文件读进来的，它不进操作系统环境。直接读 {@code System.getenv}
 * 会让"用 .env 本机跑"这条路径静默失效——配置看着对，启动报"没有真值"。而 {@code Environment}
 * 同时包含系统环境变量与导入的属性，一处看全两处。
 *
 * <p>它不是 bean：容器里没有 {@code SecretResolver} 可注入，就没有"某段业务代码顺手注入一下、
 * 在任意时刻取真值"这种写法。要真值只有一条路——拿一个 {@code SecretValue} 走到它的出口去 render。
 *
 * <p>不缓存：每次问都回源头。凭据轮换时不必重启进程，代价只是一次属性查找。
 */
public final class PropertySecretResolver implements SecretResolver {

    private final Function<String, String> properties;

    private PropertySecretResolver(Function<String, String> properties) {
        this.properties = properties;
    }

    /**
     * 用给定的属性查询建解析器。
     *
     * @param properties 属性名 → 值，查不到返回 {@code null}；通常是 {@code environment::getProperty}
     * @return 读它的解析器
     * @throws NullPointerException 没有属性源
     */
    public static PropertySecretResolver of(Function<String, String> properties) {
        return new PropertySecretResolver(Objects.requireNonNull(properties,
                "SecretResolver 需要一个属性源：没有任何属性源就取不到真值"));
    }

    /**
     * 按名字查真值。
     *
     * @param name 句柄名，转换成环境变量写法后查属性
     * @return 去掉首尾空白的真值；没有或为空白时为空
     */
    @Override
    public Optional<String> lookup(String name) {
        String value = properties.apply(SecretResolver.environmentName(name));
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
    }
}
