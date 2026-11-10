package io.github.claw4j.app;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 把插件版本契约挂在启动的最前面（P16）。
 *
 * <p>整个自动配置只有 {@code static} 那个 {@code @Bean} 是有意义的东西。非静态不会让闸失效，
 * 容器会为了拿到它先实例化配置类，再照常把它当 BFPP 调用；代价是配置类被提前实例化，
 * 它将来若长出依赖，那些依赖不经过 BeanPostProcessor，而这没有任何运行时症状。
 * 所以用 {@code static} 把它按住，测试里用反射钉住。
 *
 * <p>放在 app 而不是 capability：这道闸属于启动期装配，app 的职责是启动、配置与容器编排
 * （README 的模块表）。判断逻辑在 {@code claw4j-core} 的 {@code PluginContract} 里，纯计算、
 * 零框架；这里只负责读资源、扫 classpath、让启动停下来。代价是另有自己一套 Spring 启动的
 * 用法人不会自动获得它，要自己调一次判断。
 *
 * <p>没有开关：能被人关掉的闸，出问题时第一件事就是被关掉。
 */
@AutoConfiguration
public class PluginContractAutoConfiguration {

    @Bean
    static BeanFactoryPostProcessor pluginContractGate() {
        return new PluginContractGate();
    }
}
