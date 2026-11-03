package io.github.claw4j.example.sales;

import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

/**
 * 示例插件的装配。**这个类就是"第三方工具插件长什么样"的答案**，可以整份照抄。
 *
 * <p>它做四件事，与框架之间只通过三个契约打交道：
 * <ol>
 *   <li>把 {@link SalesTools} 暴露成一个 {@code ToolCallbackProvider} —— Agent 侧只认这个类型，
 *       不认识 {@code querySales}；</li>
 *   <li>登记这些工具会碰到哪些表 —— Agent 侧的鉴权 Advisor 只认 {@link ToolPermissionRegistry}；</li>
 *   <li>登记这些工具属于哪个能力档 —— 清单组装处只认 {@link ToolTierRegistry}（P09）；</li>
 *   <li>四者都<b>不让位</b>：容器里可以有来自好几个来源的 provider 与两张表，收集方按列表收。</li>
 * </ol>
 *
 * <p>为什么这个类不在 {@code claw4j-capability} 里：它是一份业务示例，不是框架能力。
 * 框架模块里放业务的结果是"装了框架就等于装了业务表"，而这个类证明的是相反的路径——
 * 一个只依赖 {@code claw4j-core} 与 Spring AI 的 jar，加一行依赖就能接进来，
 * 拿掉它框架照常启动（{@code claw4j-app} 里那条依赖就是唯一的接线点）。
 *
 * <p><b>关掉它</b>：{@code claw4j.example.sales.enabled=false}。示例插件必须给得出
 * "怎么关掉我"的答案，否则它就不是插件、是内置功能。
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration")
@ConditionalOnProperty(prefix = "claw4j.example.sales", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SalesExampleAutoConfiguration {

    /** 工具名。权限表与档位表都按它登记，写两份字面量迟早会漂。 */
    private static final String TOOL_NAME = "querySales";

    /**
     * 真实的数据源实现：只读事务 + 行上限。没有 {@code JdbcClient} 就不注册，
     * 让"没配数据库"表现为没有工具可调，而不是启动时炸在一个没人看得懂的地方。
     *
     * <p>{@code afterName} 是必须的：自动配置按类名排序，{@code io.github.claw4j.*}
     * 排在 {@code org.springframework.*} 前面，于是本类的条件会在 Boot 自己的
     * {@code JdbcClient} bean 注册<b>之前</b>求值——{@code @ConditionalOnBean(JdbcClient.class)}
     * 永远为假，真实执行器永远不注册，工具静默消失。这种失效不报错、只是没有工具，
     * 排查时看到的是"模型没有可用工具"，离真正的原因隔了十万八千里。
     *
     * <p>条件挂在 {@code @ConditionalOnMissingBean} 上是给测试留的位置：
     * Agent 侧的循环测试要塞一个替身进来，于是那些测试不需要 Docker，而这个替身一出现，
     * 真实实现自动让位。
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
     * <p><b>不要写 {@code @ConditionalOnMissingBean(ToolCallbackProvider.class)}</b>。
     * 那条条件的语义是"容器里已经有 provider 了就让位"，于是第二个工具来源
     * （MCP、脚本包、别的插件）一旦先注册，{@code querySales} 就静默消失——
     * 不报错、不告警，只是模型手上没这个工具，于是它开始编答案。
     * 容器里本来就该有多个来源，让位等于随机少一种。
     *
     * <p>条件改成本层能不能成立（有没有可用的执行器），多个来源各交各的。
     */
    @Bean
    @ConditionalOnBean(ReadOnlySqlExecutor.class)
    public ToolCallbackProvider salesToolCallbackProvider(ReadOnlySqlExecutor executor) {
        return MethodToolCallbackProvider.builder().toolObjects(new SalesTools(executor)).build();
    }

    /**
     * 这些工具各自需要什么权限。与工具放在同一个模块里是刻意的：
     * 只有写工具的人知道它要去碰哪张表（ADR-0007）。
     *
     * <p>登记的是工具<b>会碰到什么</b>（结构事实），不是"谁能用它"（运营口径）。
     * 后者在角色的授权表里，改那个不用发版。
     *
     * <p><b>表清单跟着 SQL 走</b>：这个工具接的是模型生成的 SQL，可能 join 到四张表里的任意一张。
     * 所以登记全部四张，而不是只写"主要那张"——少登记一张的后果是鉴权放行了本该拒绝的调用，
     * 而这里承担的失败方向必须是拒绝。
     *
     * <p>同样<b>不要</b>写 {@code @ConditionalOnMissingBean(ToolPermissionRegistry.class)}：
     * 两个来源互相让位的结果是只有一张表被注册，让出去那一方的工具从此一律按"未登记权限"被拒。
     */
    @Bean
    @ConditionalOnBean(ReadOnlySqlExecutor.class)
    public ToolPermissionRegistry salesToolPermissionRegistry() {
        return ToolPermissionRegistry.of(Map.of(
                TOOL_NAME, ToolPermission.read(
                        "sales_order", "order_item", "customer", "region")));
    }

    /**
     * 档位：{@link ToolTier#MESSAGING}。
     *
     * <p><b>"查表"必须留在 {@code messaging} 档里</b>——这不是随手定的，ADR-0020 的原话是
     * 「把工具策略切到 messaging 档，run_script 与 draft_script 不在工具清单里，
     * 读消息与查表都不受影响」。把业务只读工具推到 {@code coding} 或 {@code full}，
     * 就等于让"只做消息与问答"的部署连数据都查不了，而它恰恰是最常见的落地形态。
     *
     * <p><b>这一段是给第三方插件作者的示范</b>：新增工具必须同时登记权限与档位。
     * 少了档位不是"默认最宽"，是**启动失败并点名这个工具**——那是有意的，
     * 因为"它出现在哪个档"应当是一个被人做过的决定。
     */
    @Bean
    @ConditionalOnBean(ReadOnlySqlExecutor.class)
    public ToolTierRegistry salesToolTierRegistry() {
        return ToolTierRegistry.sameFor(List.of(TOOL_NAME), ToolTier.MESSAGING);
    }
}
