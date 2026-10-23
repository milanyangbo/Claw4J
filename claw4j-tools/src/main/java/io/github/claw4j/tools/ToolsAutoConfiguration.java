package io.github.claw4j.tools;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * 把工具暴露成 {@code ToolCallbackProvider} bean，而不是让调用方去 new 工具类。
 *
 * <p>为什么走这一层：Agent 侧装配只需要"容器里有哪些工具回调"，
 * 不需要知道工具类的名字，改 Agent 装配的代码动不到。
 *
 * <p><b>当前只有一个 provider，条件是按"整个 provider 缺失"来判的</b>：
 * 再加第二个工具类时，要么把它们塞进同一个 provider（改这里一行），
 * 要么把条件改成按 {@code ToolCallback} 收集——否则第二个 provider bean 会
 * 因为"已有 provider"而让位，工具静默消失。这个坑留给加工具那天，不要等到那天才发现。
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

    /**
     * 这些工具各自需要什么权限。与工具放在同一个模块里，是刻意的：
     * 只有写工具的人知道它要去碰哪张表（ADR-0007）。
     *
     * <p>Agent 侧的鉴权 Advisor 只认 {@link ToolPermissionRegistry} 这个接口，
     * 它不认识 {@code querySales}。于是"新增一个工具"这件事只有一处要改，
     * 而漏改的后果是拒绝而不是放行——工具没登记权限，鉴权一律按没权限处理。
     *
     * <p>这里登记的是工具**会碰到什么**（结构事实），不是"谁能用它"（运营口径）。
     * 后者在角色的授权表里，改那个不用发版。
     */
    @Bean
    @ConditionalOnMissingBean(ToolPermissionRegistry.class)
    public ToolPermissionRegistry salesToolPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(
                "querySales", ToolPermission.read("sales_order")));
    }
}
