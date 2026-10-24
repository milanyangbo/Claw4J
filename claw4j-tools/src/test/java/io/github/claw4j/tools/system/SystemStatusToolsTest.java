package io.github.claw4j.tools.system;

import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermission;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行时状态这一层。
 *
 * <p>读数是注入进来的固定值：**断言钉的是"工具怎么把读数交给模型"，不是"这台机器
 * 此刻的堆是多少"**。后者会在装了不同内存配置的机器上红，而红的原因与代码对错无关。
 */
class SystemStatusToolsTest {

    private static final ContainerLimits NO_LIMITS = ContainerLimits.of(path -> Optional.empty());

    private static final ContainerLimits IN_CONTAINER = ContainerLimits.of(path ->
            Optional.ofNullable(Map.of(
                    ContainerLimits.V2_MEMORY_MAX, "536870912",
                    ContainerLimits.V2_CPU_MAX, "200000 100000").get(path)));

    @Test
    @DisplayName("读数原样进文本：堆、处理器、线程、运行时长都在")
    void 读数都在文本里() {
        String text = new SystemStatusTools(() -> new SystemStatus(
                64L * 1024 * 1024, 512L * 1024 * 1024, 8, 42, 91_000L, NO_LIMITS)).systemStatus();

        assertThat(text)
                .contains("64 MB")
                .contains("512 MB")
                .contains("8 核")
                .contains("42")
                .contains("91 秒");
    }

    @Test
    @DisplayName("不在容器里时如实写未设限，而不是编一个限额")
    void 读不到限额时如实说未设限() {
        String text = new SystemStatusTools(() -> new SystemStatus(
                64L * 1024 * 1024, 512L * 1024 * 1024, 8, 42, 91_000L, NO_LIMITS)).systemStatus();

        assertThat(text).contains("容器限额：不在容器里，或容器未设限");
        assertThat(text).doesNotContain("-1");
    }

    /** 这一条是防"把读不到的 -1 当数字格式化出去"：那样模型会看到一个负的限额。 */
    @Test
    @DisplayName("容器限额读得到时按读到的值说")
    void 容器限额读得到时如实说() {
        String text = new SystemStatusTools(() -> new SystemStatus(
                1, 2, 1, 1, 0L, IN_CONTAINER)).systemStatus();

        assertThat(text).contains("512 MB").contains("2.00 核");
    }

    @Test
    @DisplayName("采样一次拿到的是当下的数：处理器数至少 1、运行时长不为负")
    void 真实采样不是空值() {
        SystemStatus status = SystemStatus.sample();

        assertThat(status.processors()).isGreaterThanOrEqualTo(1);
        assertThat(status.threads()).isGreaterThanOrEqualTo(1);
        assertThat(status.uptimeMillis()).isGreaterThanOrEqualTo(0L);
        assertThat(status.heapMaxBytes()).isGreaterThan(0L);
        assertThat(status.containerLimits()).isNotNull();
    }

    @Test
    @DisplayName("装配：工具在清单里，且登记了 read_system")
    void 装配出工具与权限() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SystemStatusAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(toolNames(context)).contains("systemStatus");

                    ToolPermissionRegistry registry = context.getBean(ToolPermissionRegistry.class);
                    assertThat(registry.permissionFor("systemStatus").orElseThrow())
                            .isEqualTo(ToolPermission.of(SystemStatusAutoConfiguration.ACTION));
                });
    }

    /**
     * 权限的边界：动作是 {@code read_system} 且不带表。
     *
     * <p>不带表的含义是"不受表级约束"（JVM 指标不是业务数据），
     * 但动作那一条仍然要过——这条断言盯的就是"不带表"不能被理解成"不看动作"。
     */
    @Test
    @DisplayName("没有 read_system 动作的身份看不到运行时状态")
    void 不给这个动作就看不了() {
        ToolPermission permission = ToolPermission.of(SystemStatusAutoConfiguration.ACTION);

        assertThat(permission.grantedTo(Principal.of("u", "guest", Set.of("region"), Set.of("read"))))
                .as("guest 能读 region，但运行时状态不是业务数据，不给它这个动作")
                .isFalse();
        assertThat(permission.grantedTo(Principal.of("u", "read-only", Set.of("*"),
                Set.of("read", "read_system")))).isTrue();
    }

    private static List<String> toolNames(ApplicationContext context) {
        return context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }
}
