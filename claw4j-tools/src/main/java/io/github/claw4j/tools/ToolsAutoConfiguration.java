package io.github.claw4j.tools;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.tools.sales.JdbcReadOnlySqlExecutor;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
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
 * <p><b>每种来源各出一个 provider，谁都不让位</b>（M2-01 修掉的坑）：
 * 条件早先是"整个 provider 缺失才注册"，于是第二个来源进来时第一个会静默消失。
 * 现在的条件是"有没有可用的执行器"，加来源只是再加一个 bean，不碰这里。
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

    /**
     * 工具来源：一个 provider 就是"这一层有哪些工具"。
     *
     * <p><b>这里曾经写着 {@code @ConditionalOnMissingBean(ToolCallbackProvider.class)}，
     * 那条条件是错的</b>：它的语义是"容器里已经有 provider 了就让位"，
     * 于是第二个工具来源（M2 的 MCP 与 Script Package）一旦先注册，
     * {@code querySales} 就静默消失——不报错、不告警，只是模型手上没这个工具，
     * 于是它开始编答案。M2 要在同一个容器里放三种来源，让位等于随机少一种。
     *
     * <p>条件改成"有没有可用的执行器"：没有数据源就注册一个空来源，
     * 表现是"没有工具可调"而不是启动失败——这与上面那条
     * {@code @ConditionalOnBean(JdbcClient.class)} 是同一个取向。
     *
     * <p>多个来源并存时，谁的工具都会被收走（收集发生在 Agent 侧装配），
     * 所以这里只管把自己这一层交出去，不猜容器里还有谁。
     */
    @Bean
    public ToolCallbackProvider salesToolCallbackProvider(ObjectProvider<ReadOnlySqlExecutor> executors) {
        ReadOnlySqlExecutor executor = executors.getIfAvailable();
        return executor == null
                ? () -> new ToolCallback[0]
                : MethodToolCallbackProvider.builder().toolObjects(new SalesTools(executor)).build();
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
