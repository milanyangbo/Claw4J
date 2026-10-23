package io.github.claw4j.tools;

import io.github.claw4j.tools.sales.QueryResult;
import io.github.claw4j.tools.sales.ReadOnlySqlExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具来源的注册条件：**加第二个来源时，第一个不许消失。**
 *
 * <p>为什么值得单独立一条测试：这个缺陷的姿势是"工具静默消失"——不报错、不告警，
 * 只是模型手上没有那个工具，于是它开始编答案。M2 要在容器里放进三种来源
 * （Java {@code @Tool} / MCP / Script Package），任何一种把另一种挤掉，
 * 表现都是"模型变笨了"，而没有任何一行日志指向原因。
 *
 * <p>盯的是 {@code ToolsAutoConfiguration} 上那条
 * {@code @ConditionalOnMissingBean(ToolCallbackProvider.class)}：
 * 它的语义是"已经有 provider 了就让位"，于是**第二个来源一旦先注册，第一个就没了**。
 */
class ToolsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolsAutoConfiguration.class))
            .withBean(ReadOnlySqlExecutor.class, () -> sql -> QueryResult.empty());

    @Test
    @DisplayName("第二个工具来源进来时 querySales 仍在容器里")
    void 第二个来源不会挤掉第一个() {
        contextRunner
                .withBean("probeProvider", ToolCallbackProvider.class,
                        () -> MethodToolCallbackProvider.builder().toolObjects(new ProbeTools()).build())
                .run(context -> assertThat(toolNames(context)).contains("querySales", "probe"));
    }

    @Test
    @DisplayName("没有数据源时是没有工具，而不是启动失败")
    void 没有数据源时给一个空来源() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolsAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(toolNames(context)).isEmpty();
                });
    }

    private static List<String> toolNames(ApplicationContext context) {
        return context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    /** 第二个来源的最小替身：它存在的唯一意义是让"两个 provider 同时在容器里"这件事发生。 */
    static final class ProbeTools {

        @Tool(description = "第二个来源的探针工具")
        String probe() {
            return "ok";
        }
    }
}
