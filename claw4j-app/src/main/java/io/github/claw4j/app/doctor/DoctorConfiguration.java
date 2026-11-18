package io.github.claw4j.app.doctor;

import org.springframework.context.annotation.Configuration;

/**
 * doctor 起上下文用的那个"空壳"。
 *
 * <p>{@link org.springframework.boot.builder.SpringApplicationBuilder} 必须有一个 source 才能跑，
 * 而这个 source 唯一的作用就是让 Boot 走完配置数据阶段（yml、profile、{@code spring.config.import}）。
 * **它刻意不带 {@code @EnableAutoConfiguration}**：带了就会拉数据源、拉 servlet 容器，doctor 也就
 * 跟着起不来了——而"配置有问题时也能跑"正是它存在的理由。
 *
 * <p>代价要认：它在 {@code io.github.claw4j.app} 下面，因此也会被主应用的组件扫描扫到。
 * 扫到也没关系——这个类里没有任何 {@code @Bean}，装配出来的只是一个空配置。
 */
@Configuration(proxyBeanMethods = false)
class DoctorConfiguration {
}
