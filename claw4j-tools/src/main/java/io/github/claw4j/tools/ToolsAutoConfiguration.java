package io.github.claw4j.tools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 把工具暴露成 {@code ToolCallbackProvider} bean，而不是让调用方去 new 工具类。
 *
 * <p>为什么走这一层：Agent 侧装配只需要"容器里有哪些工具回调"，
 * 不需要知道工具类的名字。于是加工具是加一个 bean，改 Agent 装配的代码动不到，
 * 测试里想换掉全部工具也只是注册一个自己的 provider（{@code @ConditionalOnMissingBean} 让位）。
 *
 * <p>注册为自动配置，是为了让任何模块引入依赖即可生效，不必要求调用方把
 * {@code io.github.claw4j.tools} 加进组件扫描路径。
 */
@AutoConfiguration
public class ToolsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ToolCallbackProvider.class)
    public ToolCallbackProvider salesToolCallbackProvider() {
        return MethodToolCallbackProvider.builder().toolObjects(new SalesTools()).build();
    }
}
