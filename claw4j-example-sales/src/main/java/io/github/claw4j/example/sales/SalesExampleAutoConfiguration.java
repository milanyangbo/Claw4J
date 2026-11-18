package io.github.claw4j.example.sales;

import io.github.claw4j.domain.principal.ToolFilter;
import io.github.claw4j.domain.principal.ToolTier;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Arrays;
import java.util.List;

/**
 * 示例插件的装配。这个类就是第三方工具插件长什么样，可以整份照抄。
 *
 * <p>它做三件事，与框架之间只通过两个契约打交道：把 {@link SalesTools} 暴露成
 * {@code ToolCallbackProvider}，Agent 侧只认这个类型，不认识 {@code querySales}；登记工具属于哪个
 * 能力档，清单组装处只认 {@link ToolTierRegistry}（P09）。两者都不让位，容器里可以有来自好几个
 * 来源的 provider 与画像表，收集方按列表收。
 *
 * <p>它曾经还登记"这个工具会碰哪些表、需要什么动作"，那张表随权限登记表在
 * ADR-0030 里整块下线：这个插件现在只回答"我提供哪个工具、它属于哪一档"，业务数据范围是
 * 第二步的事。
 *
 * <p>不在 {@code claw4j-capability} 里：它是一份业务示例，不是框架能力。框架模块里放业务的结果
 * 是装了框架就等于装了业务表，而这个类证明的是相反的路径：一个只依赖 {@code claw4j-core} 与
 * Spring AI 的 jar，加一行依赖就能接进来，拿掉它框架照常启动。
 *
 * <p>关掉它用 {@code claw4j.example.sales.enabled=false}。示例插件必须给得出怎么关掉它的答案，
 * 否则它就不是插件、是内置功能。
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration")
@ConditionalOnProperty(prefix = "claw4j.example.sales", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SalesProperties.class)
public class SalesExampleAutoConfiguration {

    /** 工具名。权限表与档位表都按它登记，写两份字面量迟早会漂。 */
    private static final String TOOL_NAME = "querySales";

    /**
     * 真实的数据源实现：只读事务加行上限。没有 {@code JdbcClient} 就不注册，让没配数据库表现为
     * 没有工具可调，而不是启动时炸在一个没人看得懂的地方。
     *
     * <p>{@code afterName} 是必须的：自动配置按类名排序，{@code io.github.claw4j.*} 排在
     * {@code org.springframework.*} 前面，于是本类的条件会在 Boot 自己的 {@code JdbcClient} bean
     * 注册之前求值，{@code @ConditionalOnBean(JdbcClient.class)} 永远为假，真实执行器永远不注册，
     * 工具静默消失，排查时看到的是模型没有可用工具。
     *
     * <p>{@code @ConditionalOnMissingBean} 是给测试留的位置：Agent 侧的循环测试要塞一个替身进来，
     * 那些测试就不需要 Docker。
     *
     * @param jdbc 容器里的 JDBC 客户端
     * @param transactionManager 事务管理器，只读标记挂在它开的事务上
     * @return 只读执行器
     */
    @Bean
    @ConditionalOnMissingBean(ReadOnlySqlExecutor.class)
    @ConditionalOnBean(JdbcClient.class)
    public ReadOnlySqlExecutor salesReadOnlySqlExecutor(JdbcClient jdbc,
                                                        PlatformTransactionManager transactionManager) {
        return new JdbcReadOnlySqlExecutor(jdbc, transactionManager);
    }

    /**
     * 工具来源：一个 provider 就是这一层有哪些工具。
     *
     * <p>不写 {@code @ConditionalOnMissingBean(ToolCallbackProvider.class)}：那条条件会在容器里
     * 已有 provider 时让位，于是 MCP、别的插件一旦先注册，{@code querySales} 就静默消失，
     * 模型手上没这个工具，于是它开始编答案。容器里本来就该有多个来源，让位等于随机少一种。
     *
     * <p>条件改成本层能不能成立，即有没有可用的执行器，多个来源各交各的。
     *
     * <p>{@code tool-filter} 排掉它时（票 07），这里交出一个空清单：过滤发生在"工具变成工具"
     * 之前，所以它不进权限与画像登记，{@code allow} 也加不回来。
     *
     * @param executor 本层用的只读执行器
     * @param properties 插件配置，取 {@code tool-filter}
     * @return 只含存活工具的来源
     */
    @Bean
    @ConditionalOnBean(ReadOnlySqlExecutor.class)
    public ToolCallbackProvider salesToolCallbackProvider(ReadOnlySqlExecutor executor,
                                                          SalesProperties properties) {
        ToolCallbackProvider all = MethodToolCallbackProvider.builder()
                .toolObjects(new SalesTools(executor))
                .build();
        if (properties.toolFilter().filtersNothing()) {
            // 不筛时原样交出去：包一层 lambda 会让"这条路径与没加过滤之前完全一样"多一个需要证明的点。
            return all;
        }
        return () -> Arrays.stream(all.getToolCallbacks())
                .filter(callback -> properties.toolFilter().matches(callback.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    /**
     * 画像：{@code {CODING, MESSAGING}}，并按<b>外部工具</b>登记（ADR-0030 裁决一）。
     *
     * <p>业务工具与 OpenClaw 的 {@code bundle-mcp} 同等待遇：{@code coding} 与 {@code messaging}
     * 都含它、{@code minimal} 不含。查数既不是"碰机器"也不是"自主行动"，所以它不该被关在
     * coding 里；但它也不是地基，所以 minimal 看不见它。
     *
     * <p>登记成 {@code external} 而不是 {@code sameFor}：{@code group:plugins} 要靠它把业务工具一次点名，
     * 否则 {@code deny: [group:plugins]} 关不掉业务工具，而"装了模块就有、要关得逐个写名字"
     * 正是这套机制要避免的事。
     *
     * <p>这一段也是给第三方模块作者的示范：新增工具必须同时登记权限与画像。少了画像不是默认
     * 最宽，是启动失败并点名这个工具，因为工具出现在哪个画像应当是一个被人做过的决定。
     *
     * @param properties 插件配置，取 {@code tool-filter}
     * @return 把 {@code querySales} 登记成外部工具的画像表；工具被排掉时是空表
     */
    @Bean
    @ConditionalOnBean(ReadOnlySqlExecutor.class)
    public ToolTierRegistry salesToolTierRegistry(SalesProperties properties) {
        return ToolTierRegistry.external(exposed(properties) ? List.of(TOOL_NAME) : List.of(),
                ToolTier.CODING, ToolTier.MESSAGING);
    }

    /**
     * 这个工具在不在。
     *
     * <p>三处（工具来源、权限、画像）都问它，而不是各写一遍 {@code toolFilter.matches(...)}：
     * 三处的判据必须相同，否则会出现"清单里有它但权限表没有"这种最难查的组合。
     */
    private static boolean exposed(SalesProperties properties) {
        return properties.toolFilter().matches(TOOL_NAME);
    }
}
