package io.github.claw4j.app;

import io.github.claw4j.tools.sales.JdbcReadOnlySqlExecutor;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;

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
 */
@SpringBootTest
class ToolsWiringTest {

    @Autowired
    private ReadOnlySqlExecutor executor;

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void 真实执行器而不是替身被注册() {
        assertThat(executor).isInstanceOf(JdbcReadOnlySqlExecutor.class);
    }

    @Test
    void querySales在工具清单里() {
        assertThat(toolCallbackProvider.getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .contains("querySales");
    }

    /** 工具描述里必须带着 schema：模型不知道表名列名就只能在错误里瞎猜。 */
    @Test
    void 工具描述带着固定schema() {
        ToolCallback querySales = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .filter(callback -> "querySales".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        assertThat(querySales.getToolDefinition().description())
                .contains("business.sales_order")
                .contains("east")
                .contains("CANCELLED");
    }
}
