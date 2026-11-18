package io.github.claw4j.example.sales;

import io.github.claw4j.domain.principal.ToolFilter;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务示例上的存在性过滤（票 07）：{@code claw4j.example.sales.tool-filter} 排掉 {@code querySales}
 * 之后，它在三处一起消失——工具清单、权限登记、画像登记。
 *
 * <p>这三处必须一起少：只少清单会出现"权限表里还留着它"，只少权限会出现"清单里有它但一调就被拒"。
 * 两种现象都不指向配置，而配置只有三行。
 *
 * <p>不连库：执行器是个一调就炸的替身。这条用例验的是"有没有把它交出去"，不是 SQL 对不对
 * （那是 {@link SalesQueryTest} 的事，要真 PostgreSQL）。
 */
class SalesToolFilterTest {

    private static final ReadOnlySqlExecutor NO_DATABASE = sql -> {
        throw new IllegalStateException("这条用例不该真的执行 SQL：" + sql);
    };

    @Test
    @DisplayName("不配过滤：清单里就是 querySales，画像也登记着它")
    void noFilterKeepsTheBusinessTool() {
        SalesExampleAutoConfiguration configuration = new SalesExampleAutoConfiguration();
        SalesProperties properties = new SalesProperties(ToolFilter.none());

        ToolCallbackProvider provider = configuration.salesToolCallbackProvider(NO_DATABASE, properties);
        ToolTierRegistry tiers = configuration.salesToolTierRegistry(properties);

        assertThat(provider.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("querySales");
        assertThat(tiers.profilesFor("querySales")).isPresent();
    }

    @Test
    @DisplayName("exclude 掉 querySales：清单空，画像也不登记它")
    void excludedBusinessToolIsAbsent() {
        SalesExampleAutoConfiguration configuration = new SalesExampleAutoConfiguration();
        SalesProperties properties = new SalesProperties(new ToolFilter(List.of(), List.of("querySales")));

        ToolCallbackProvider provider = configuration.salesToolCallbackProvider(NO_DATABASE, properties);
        ToolTierRegistry tiers = configuration.salesToolTierRegistry(properties);

        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertThat(callbacks).isEmpty();
        assertThat(tiers.profilesFor("querySales"))
                .as("画像层答不出来才是对的：一个不存在的工具不该有档位")
                .isEmpty();
        assertThat(tiers.externalToolNames()).isEmpty();
    }
}
