package io.github.claw4j.capability.builtin.agenttools;

import io.github.claw4j.capability.LaunchDirectoryEnvironmentPostProcessor;
import io.github.claw4j.domain.principal.ToolTierRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认工具这一层：默认导出哪几个、开关生效在哪、导出与登记对不对得上、边界拦不拦得住。
 *
 * <p>这里全部用 {@code ApplicationContextRunner} 起一个只有这一个自动配置的上下文，不起整机：
 * 要判的是"这一层导出了什么"，整机那一层另有 {@code ApplicationAssemblyTest} 与真实例验。
 */
@DisplayName("默认工具：Read / Grep / Glob / TodoWrite，写与搜索按配置来")
class AgentToolsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentToolsAutoConfiguration.class));

    @Test
    @DisplayName("默认只有读与搜：Write / Edit 不在清单里，也不在授权表里")
    void readOnlyToolsAreOnByDefault() {
        runner.run(context -> {
            assertThat(names(context))
                    .containsExactlyInAnyOrder("Read", "Grep", "Glob", "TodoWrite")
                    .doesNotContain("Write", "Edit", "WebSearch");

        });
    }

    @Test
    @DisplayName("开了 write-enabled 才有 Write / Edit，而且 Read 不会因此出现两次")
    void writeToolsAppearOnlyWhenEnabled() {
        runner.withPropertyValues("claw4j.tools.files.write-enabled=true").run(context -> {
            List<String> exported = names(context);

            assertThat(exported)
                    .contains("Write", "Edit")
                    .as("同一个 FileSystemTools 实例被两个 provider 导出，只读那支必须筛掉写的那两个")
                    .containsOnlyOnce("Read");

            assertThat(exported).doesNotHaveDuplicates();
        });
    }

    @Test
    @DisplayName("导出的每个工具都查得到档位与权限：漏登记的表现是启动失败，不是静默放行")
    void everyExportedToolIsRegistered() {
        runner.withPropertyValues("claw4j.tools.files.write-enabled=true").run(context -> {
            ToolTierRegistry tiers = context.getBean(ToolTierRegistry.class);

            for (String name : names(context)) {
                assertThat(tiers.profilesFor(name))
                        .as("%s 没登记画像：清单组装会在它身上直接失败", name)
                        .isPresent();
            }
        });
    }

    @Test
    @DisplayName("目录外的路径被拒，答案是错误文案而不是抛异常")
    void pathOutsideTheDirectoryIsRefused(@TempDir Path tempDir) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "不该被读到");

        runner.withPropertyValues("claw4j.tools.files.directory=" + root).run(context -> {
            String answer = context.getBean(FileSystemTools.class).read(outside.toString(), null, null);

            assertThat(answer)
                    .as("拒的方式必须是一句话：抛异常会把这个工具变成 500")
                    .startsWith("Error: Access denied");
        });
    }

    @Test
    @DisplayName("相对路径按 files.directory 解析；`..` 跳出去照样被拒")
    void relativePathsResolveAgainstTheConfiguredDirectory(@TempDir Path tempDir) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs/note.md"), "# 标题");
        Files.writeString(tempDir.resolve("outside.md"), "# 外面");

        runner.withPropertyValues("claw4j.tools.files.directory=" + root).run(context -> {
            GlobTool glob = context.getBean(GlobTool.class);

            assertThat(glob.glob("**/*.md", "docs"))
                    .as("相对路径该落在配置的目录里，而不是 JVM 的工作目录")
                    .contains("note.md");

            assertThat(glob.glob("*.md", "../"))
                    .as("`..` 是跳出边界的第一种写法")
                    .startsWith("Error: Access denied");
        });
    }

    @Test
    @DisplayName("没 key 就不装 WebSearch：不做一个调了才失败的工具")
    void webSearchNeedsAKey() {
        runner.run(context -> assertThat(names(context)).doesNotContain("WebSearch"));

        runner.withPropertyValues("claw4j.tools.web.search-api-key=probe-token").run(context -> {
            assertThat(names(context)).contains("WebSearch");
            assertThat(context.getBean(ToolTierRegistry.class).profilesFor("WebSearch"))
                    .as("装了就必须登记画像，否则清单组装会在它身上直接失败")
                    .isPresent();
        });
    }

    @Test
    @DisplayName("启动目录被记成 claw4j.launch-directory：`.` 在 Servlet 应用里会被 Tomcat 改成 docbase")
    void launchDirectoryIsRecordedAsAProperty() {
        StandardEnvironment environment = new StandardEnvironment();

        new LaunchDirectoryEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(LaunchDirectoryEnvironmentPostProcessor.PROPERTY))
                .as("两处默认目录（exec.workspace / files.directory）都靠这一条，缺了它们就落到 Tomcat 的临时目录")
                .isEqualTo(System.getProperty("user.dir"));
    }

    /** 容器里所有 provider 导出的工具名，按导出顺序。 */
    private static List<String> names(AssertableApplicationContext context) {        return context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }
}
