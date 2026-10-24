package io.github.claw4j.tools.generator;

import io.github.claw4j.domain.principal.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import io.github.claw4j.domain.principal.ToolPermissionRegistry;
import io.github.claw4j.tools.script.ScriptPackage;
import io.github.claw4j.tools.script.ScriptPackageCatalog;
import io.github.claw4j.tools.script.ScriptPackageRegistry;
import io.github.claw4j.tools.script.ScriptPackageToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生成器这一层的装配。
 *
 * <p>钉的是三件事，每件都有各自的失效姿势：
 * <ul>
 *   <li><b>配了才启用</b>：默认不该有这一层。它是"模型能产出新的可执行能力"的入口，
 *       存在与否应该是一个决定。
 *   <li><b>没有脚本层就没有生成器</b>：人审之后的注册目标是脚本层的清单，
 *       没有它，"人签了字"会变成一个没有任何效果、却看起来成功的动作。
 *   <li><b>两个来源都在清单里</b>：{@code propose_script} 进来时，
 *       脚本层那些包不能被挤掉（01 号票那条断言的形状）。
 * </ul>
 */
class GeneratorWiringTest {

    @TempDir
    Path parent;

    private final ApplicationContextRunner scriptsOnly = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    io.github.claw4j.tools.script.ScriptPackageAutoConfiguration.class,
                    GeneratorAutoConfiguration.class));

    @Test
    @DisplayName("配了暂存区就有生成器，清单里同时有脚本包与 propose_script")
    void 两个来源都在清单里() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        context(packages).run(context -> {
            assertThat(context).hasSingleBean(SkillGenerator.class);
            assertThat(context).hasSingleBean(ScriptPackageRegistry.class);
            assertThat(toolNames(context)).contains("echo", SkillGeneratorToolCallback.TOOL_NAME);
        });
    }

    @Test
    @DisplayName("没配暂存区就没有这一层")
    void 没配暂存区就没有生成器() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        scriptsOnly
                .withPropertyValues("claw4j.scripts.directory=" + packages)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SkillGenerator.class);
                    assertThat(toolNames(context)).containsExactly("echo");
                });
    }

    /**
     * 没有脚本层就没有生成器：注册目标是脚本层的清单，没有它签字就会变成一个
     * 没有效果、却看起来成功的动作——那比"这一层不存在"更难发现。
     */
    @Test
    @DisplayName("没有脚本层就没有生成器")
    void 没有脚本层就没有生成器() {
        scriptsOnly
                .withPropertyValues("claw4j.generator.staging-directory=" + parent.resolve("staging"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SkillGenerator.class);
                });
    }

    /** 权限登记表：动作是 draft_script，表一张都没有。 */
    @Test
    @DisplayName("生成器这一层的权限登记形状")
    void 权限登记的形状() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        context(packages).run(context -> {
            List<ToolPermissionRegistry> registries = context.getBeansOfType(ToolPermissionRegistry.class)
                    .values().stream().toList();
            boolean declared = registries.stream().anyMatch(registry ->
                    registry.permissionFor(SkillGeneratorToolCallback.TOOL_NAME)
                            .map(Principal.of("u", "admin", Set.of("*"), Set.of("*"))::canUse)
                            .orElse(false));

            assertThat(declared).as("draft_script 动作必须被显式登记，否则这个工具一律按未登记被拒").isTrue();
        });
    }

    /**
     * 人审之后进清单：这条走的是真实装配，清单从 {@code ScriptPackageToolProvider} 现取。
     *
     * <p>未人审时清单里没有它——这两条合起来就是票面上"未人审的脚本不在工具清单里"。
     */
    @Test
    @DisplayName("人审通过之后新包出现在工具清单里")
    void 人审之后进清单() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        context(packages).run(context -> {
            SkillGenerator generator = context.getBean(SkillGenerator.class);
            ToolCallback propose = Arrays.stream(toolCallbacks(context))
                    .filter(callback -> SkillGeneratorToolCallback.TOOL_NAME
                            .equals(callback.getToolDefinition().name()))
                    .findFirst().orElseThrow();
            String script = "#!/bin/sh\necho generated\n";

            propose.call(input("generated", "一个生成出来的脚本包", script),
                    new ToolContext(Map.of(ContextKeys.PRINCIPAL, admin())));

            assertThat(toolNames(context)).doesNotContain("generated");

            try {
                new ReviewGate().approve(generator.reviewDirectory(), "generated", "ethan",
                        Instant.now(), script);
            }
            catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            assertThat(generator.publishApproved()).contains("generated");
            assertThat(toolNames(context)).contains("generated", "echo");
        });
    }

    /**
     * 已注册的包被真的执行时也要留一条痕。
     *
     * <p>这条走的是"脚本层 → 执行观察者容器 → 生成器审计"这条跨层的接线：
     * 它最容易断在装配顺序上（脚本层先装配，那会儿还没有审计）。
     */
    @Test
    @DisplayName("已注册的包被执行时审计里多一条 EXECUTED")
    void 执行也留痕() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        context(packages).run(context -> {
            ToolCallback echo = Arrays.stream(toolCallbacks(context))
                    .filter(callback -> "echo".equals(callback.getToolDefinition().name()))
                    .findFirst().orElseThrow();

            echo.call("{}", new ToolContext(Map.of(ContextKeys.PRINCIPAL, admin())));

            GenerationAudit audit = context.getBean(GenerationAudit.class);
            assertThat(audit.replay("echo"))
                    .extracting(entry -> entry.stage())
                    .contains(GenerationAudit.Stage.EXECUTED);
            assertThat(audit.replay("echo"))
                    .anySatisfy(entry -> assertThat(entry.principal()).isEqualTo("ethan"));
        });
    }

    /** 没有身份的提交被拒：生成脚本会落盘并在沙箱里跑一遍，那是有副作用的事。 */
    @Test
    @DisplayName("没有携带身份的提交被拒绝")
    void 没有身份的提交被拒() throws IOException {
        Path packages = packagesDirectory("echo", "#!/bin/sh\necho hi\n");

        context(packages).run(context -> {
            ToolCallback propose = Arrays.stream(toolCallbacks(context))
                    .filter(callback -> SkillGeneratorToolCallback.TOOL_NAME
                            .equals(callback.getToolDefinition().name()))
                    .findFirst().orElseThrow();

            String answer = propose.call(input("anon", "没身份的提交", "#!/bin/sh\necho hi\n"), null);

            assertThat(answer).contains("没有携带身份");
            assertThat(context.getBean(ScriptPackageRegistry.class).contains("anon")).isFalse();
        });
    }

    // ------------------------------------------------------------------ 辅助

    private ApplicationContextRunner context(Path packages) {
        return scriptsOnly
                .withPropertyValues("claw4j.scripts.directory=" + packages,
                        "claw4j.generator.staging-directory=" + parent.resolve("staging"),
                        "claw4j.generator.review-directory=" + parent.resolve("reviews"),
                        "claw4j.generator.audit-file=" + parent.resolve("audit.tsv"));
    }

    private Path packagesDirectory(String name, String script) throws IOException {
        Path root = Files.createDirectories(parent.resolve("packages").resolve(name));
        Path file = root.resolve(ScriptPackage.DEFAULT_ENTRYPOINT);
        Files.writeString(file, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        Files.writeString(root.resolve(ScriptPackageCatalog.MANIFEST), "description=一个回声脚本\n");
        return parent.resolve("packages");
    }

    private static String input(String name, String description, String script) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + description + "\",\"script\":"
                + quoteJson(script) + "}";
    }

    private static String quoteJson(String script) {
        return "\"" + script.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static Principal admin() {
        return Principal.of("ethan", "admin", Set.of(Principal.WILDCARD), Set.of(Principal.WILDCARD));
    }

    private static List<String> toolNames(org.springframework.context.ApplicationContext context) {
        return context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    private static ToolCallback[] toolCallbacks(org.springframework.context.ApplicationContext context) {
        return context.getBeansOfType(ToolCallbackProvider.class).values().stream()
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
    }
}
