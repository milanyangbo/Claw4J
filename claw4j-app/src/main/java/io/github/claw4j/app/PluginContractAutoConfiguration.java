package io.github.claw4j.app;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 把插件版本契约挂在启动的最前面（P16）。
 *
 * <p>整个自动配置只有一行有意义的东西：{@code static} 那个 {@code @Bean}。
 * <b>非静态不会让闸失效</b>——实测过：容器会为了拿到它先实例化这个配置类，然后照常把它
 * 当 BFPP 调用，闸一样跑。代价是配置类被提前实例化，于是它自己将来若长出依赖，那些依赖
 * 不经过 BeanPostProcessor。而这件事没有任何运行时症状，只在某天加了个 {@code @Autowired}
 * 之后才发作，所以用 {@code static} 把它按在原地，测试里用反射钉住。
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
