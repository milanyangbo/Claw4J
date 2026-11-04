package io.github.claw4j.app;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 把插件版本契约挂在启动的最前面（P16）。
 *
 * <p>整个自动配置只有一行有意义的东西：{@code static} 那个 {@code @Bean}。
 * <b>非静态会让它失效</b>——BFPP 必须能在配置类被实例化之前拿到，非静态的 {@code @Bean}
 * 要求先建配置类 bean，Spring 会打一行警告然后按普通 bean 处理，于是校验被推到单例实例化阶段，
 * 也就回到了"插件的 bean 可能先炸"那个位置上。这条不会报错，只会让闸慢慢变成装饰。
 *
 * <p><b>为什么在 app 而不是 capability</b>：这道闸是"启动期装配"的一部分，而 app 的职责原文
 * 就是"启动、配置与容器编排"（README 的模块表）。判断逻辑本身在 {@code claw4j-core} 的
 * {@code PluginContract} 里，纯计算、零框架；这里只负责读资源、扫 classpath、让启动停下来。
 * 代价如实写：自己另起一套 Spring 启动的用法人不会自动获得它，要自己调一次判断。
 *
 * <p>没有开关：一个能被人关掉的闸，出问题时第一件事就是被关掉，而不是被修。
 */
@AutoConfiguration
public class PluginContractAutoConfiguration {

    @Bean
    static BeanFactoryPostProcessor pluginContractGate() {
        return new PluginContractGate();
    }
}
