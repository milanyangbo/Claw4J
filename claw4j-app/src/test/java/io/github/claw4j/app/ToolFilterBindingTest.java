package io.github.claw4j.app;

import io.github.claw4j.capability.mcp.McpServerProperties;
import io.github.claw4j.domain.principal.ToolFilter;
import io.github.claw4j.example.sales.SalesProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code tool-filter} 写进 yml 之后真的绑得上，不写时也不是 null（票 07）。
 *
 * <p>这两条都在验同一件容易漏的事：{@code ToolFilter} 是 record，外面那层用
 * {@code @DefaultValue} 声明"可以没有"。绑不上的话，缺失的那个字段会是 null，
 * 而调用点一条 {@code filter.matches(...)} 就会在启动之后第一轮请求里 NPE——
 * 那时看到的是"模型这一轮崩了"，离 yml 少一行隔着一整条链路。
 *
 * <p>用 Binder 直接绑，不起容器：这两个类都是配置对象，验的是"这段属性变成一个对象"，
 * 与"装配出多少个 bean"是两件事。容器那一侧由各自的装配用例覆盖。
 */
class ToolFilterBindingTest {

    @Test
    @DisplayName("MCP server：写了 tool-filter 就绑成那两个清单，没写的 server 是不筛")
    void mcpServerBindsItsToolFilter() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("claw4j.capability.mcp.servers.demo.command", "python3");
        properties.put("claw4j.capability.mcp.servers.demo.tool-filter.include[0]", "runtimeSnapshot");
        properties.put("claw4j.capability.mcp.servers.demo.tool-filter.include[1]", "cron*");
        properties.put("claw4j.capability.mcp.servers.demo.tool-filter.exclude[0]", "simulateSlowCall");
        properties.put("claw4j.capability.mcp.servers.plain.command", "python3");

        McpServerProperties bound = bind("claw4j.capability.mcp", McpServerProperties.class, properties);

        McpServerProperties.Server demo = bound.getServers().get("demo");
        assertThat(demo.toolFilter().include()).containsExactly("runtimeSnapshot", "cron*");
        assertThat(demo.toolFilter().exclude()).containsExactly("simulateSlowCall");
        assertThat(demo.toolFilter().matches("cronNext")).isTrue();
        assertThat(demo.toolFilter().matches("simulateSlowCall")).isFalse();

        assertThat(bound.getServers().get("plain").toolFilter())
                .as("没写这段的 server 拿到的是空过滤，不是 null")
                .isEqualTo(ToolFilter.none());
    }

    @Test
    @DisplayName("业务示例：只写 exclude 时其余全留")
    void salesBindsItsToolFilter() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("claw4j.example.sales.tool-filter.exclude[0]", "querySales");

        SalesProperties sales = bind("claw4j.example.sales", SalesProperties.class, properties);

        assertThat(sales.toolFilter().matches("querySales")).isFalse();
    }

    private static <T> T bind(String prefix, Class<T> type, Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind(prefix, Bindable.of(type))
                .orElseThrow(() -> new AssertionError("绑不上：" + prefix));
    }
}
