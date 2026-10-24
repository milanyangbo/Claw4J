package io.github.claw4j.app;

import io.github.claw4j.tools.sales.JdbcReadOnlySqlExecutor;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具的自动配置真的把真实执行器挂上了链。
 *
 * <p>为什么值得单独一条测试：装配失败在这里的姿势是<b>工具静默消失</b>——
 * 不报错、不告警，只是模型手上没有任何工具可用，于是它开始编答案。
 * 这种缺陷在聊天界面上完全看不出来，只有"这个数怎么是编的"这种反馈才会暴露。
 *
 * <p>具体盯的是 {@code ToolsAutoConfiguration} 上那条 {@code afterName}：
 * 自动配置按类名排序，{@code io.github.claw4j.*} 排在 {@code org.springframework.*} 前面，
 * 少了它，{@code @ConditionalOnBean(JdbcClient.class)} 会在 Boot 注册 JdbcClient
 * <b>之前</b>求值，于是真实执行器永远不注册。去掉那条属性，这两条断言立刻红。
 *
 * <p>工具来源按<b>列表</b>注入而不是注入"那一个"：M2 之后容器里可以有多个来源
 * （Java {@code @Tool} / MCP / Script Package），只按类型注入单个 bean 会在
 * 第二个来源出现的那天直接装配失败，而报的是"找到了 2 个"——
 * 离"哪个多了出来"隔了一层。列表 + 下面那条"只有一个来源"的断言，
 * 报出来的是"多出来的是 mcpToolCallbacks"，一眼能看出是框架那份没关。
 */
@SpringBootTest
class ToolsWiringTest {

    @Autowired
    private ReadOnlySqlExecutor executor;

    @Autowired
    private List<ToolCallbackProvider> toolCallbackProviders;

    @Test
    void 真实执行器而不是替身被注册() {
        assertThat(executor).isInstanceOf(JdbcReadOnlySqlExecutor.class);
    }

    @Test
    void querySales在工具清单里() {
        assertThat(callbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("querySales");
    }

    /** 工具描述里必须带着 schema：模型不知道表名列名就只能在错误里瞎猜。 */
    @Test
    void 工具描述带着固定schema() {
        ToolCallback querySales = Arrays.stream(callbacks())
                .filter(callback -> "querySales".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        assertThat(querySales.getToolDefinition().description())
                .contains("business.sales_order")
                .contains("east")
                .contains("CANCELLED");
    }

    /**
     * 框架那份 MCP 工具来源（{@code mcpToolCallbacks}）是关掉的，容器里只有自己这一份。
     *
     * <p>为什么要有一条断言盯着它：{@code spring.ai.mcp.client.enabled=false} 写在
     * {@code application.yml} 里，而"配置文件里少一行"是最容易在合并里丢掉的那种改动——
     * 丢了之后的表现是某些按类型注入的地方装配失败，看起来跟 MCP 毫无关系。
     */
    @Test
    void 框架那份MCP工具来源是关掉的() {
        assertThat(toolCallbackProviders)
                .as("容器里只应有 claw4j 自己那一份工具来源；框架那份 mcpToolCallbacks 由 "
                        + "spring.ai.mcp.client.enabled=false 关掉（见 application.yml）")
                .hasSize(1);
    }

    private ToolCallback[] callbacks() {
        return toolCallbackProviders.stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
    }
}
