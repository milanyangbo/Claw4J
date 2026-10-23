package io.github.claw4j.tools;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.tools.sales.JdbcReadOnlySqlExecutor;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

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
 *
 * <p>{@code afterName} 是必须的：自动配置按类名排序，{@code io.github.claw4j.*}
 * 排在 {@code org.springframework.*} 前面，于是本类的条件会在 Boot 自己的
 * {@code JdbcClient} bean 注册<b>之前</b>求值——{@code @ConditionalOnBean(JdbcClient.class)}
 * 永远为假，真实执行器永远不注册，工具静默消失。这种失效不报错、只是没有工具，
 * 排查时看到的是"模型没有可用工具"，离真正的原因隔了十万八千里。
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration")
public class ToolsAutoConfiguration {

    /**
     * 真实的数据源实现：只读事务 + 行上限。没有 {@code JdbcClient} 就不注册，
     * 让"没配数据库"这件事表现为没有工具可调，而不是启动时炸在一个没人看得懂的地方。
     *
     * <p>条件挂在 {@code @ConditionalOnMissingBean} 上是给测试留的位置：
     * Agent 侧的循环测试（自纠错、调用上限）要塞一个替身进来，
     * 于是那些测试不需要 Docker，而这个替身一出现，真实实现自动让位。
     */
    @Bean
    @ConditionalOnMissingBean(ReadOnlySqlExecutor.class)
    @ConditionalOnBean(JdbcClient.class)
    public ReadOnlySqlExecutor salesReadOnlySqlExecutor(JdbcClient jdbc,
                                                        PlatformTransactionManager transactionManager) {
        return new JdbcReadOnlySqlExecutor(jdbc, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(ToolCallbackProvider.class)
    public ToolCallbackProvider salesToolCallbackProvider(ReadOnlySqlExecutor executor) {
        return MethodToolCallbackProvider.builder().toolObjects(new SalesTools(executor)).build();
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
     *
     * <p><b>表清单跟着 SQL 走</b>：05 号票之后这个工具接的是模型生成的 SQL，
     * 它可能 join 到四张表里的任意一张。所以登记的是全部四张，
     * 而不是只写"主要那张"——少登记一张的后果是鉴权放行了本该拒绝的调用，
     * 而这里承担的失败方向必须是拒绝。
     */
    @Bean
    @ConditionalOnMissingBean(ToolPermissionRegistry.class)
    public ToolPermissionRegistry salesToolPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(
                "querySales", ToolPermission.read(
                        "sales_order", "order_item", "customer", "region")));
    }
}
